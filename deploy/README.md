# 中继服务部署（Docker）

中继是 DSH 手机遥控器的云端一跳：手机 App ↔ **中继** ↔ 插件（跑在电脑的 DSH 进程里）。
它不存业务状态，只做鉴权 + 转发 + 14 天会话归档。

镜像里只有一个文件 `/app/cli.js`（esbuild 已把 zod / ws / qrcode 全部内联，没有
`node_modules`），运行时以 `node` 用户、只读根文件系统、无 capability 启动；
密钥与 SQLite 都在 `/data` 数据卷里。

---

## 一、一行启动

**只需要一个密码**：

```bash
RELAY_PASSWORD='换成一个足够长的密码' docker compose -f deploy/compose.yaml up -d
```

等价的手写 `docker run`：

```bash
docker run -d --name relay --restart unless-stopped \
  -p 8080:8080 -v relay-data:/data \
  -e RELAY_PASSWORD='换成一个足够长的密码' \
  dsh-mobile-sh-relay:0.3.0
```

这一个密码同时是**网页登录密码**和**主机注册密码**，默认账号 `admin@relay.local`。
起来之后：

1. 浏览器打开 `http://<这台机器>:8080/`，用 `admin@relay.local` + 那个密码登录；
2. 首页「手机登录配对码」→ 让手机 App 扫码（或把手输的 8 位码填进 App）；
3. 插件设置页填「中继地址」（`http://<这台机器>:8080`）+ 同一个密码注册这台电脑。

> 首次启动会自动建号；之后每次重启打印 `Account … already exists, skipped provisioning.`
> 并跳过——幂等，不会失败。

**内网明文够用，公网请走 https**（见第二节）：`http://` 下 bearer、配对码、聊天正文
全在链路上裸奔。中继只在两种情况下允许明文：地址是 loopback，或你显式写了
`RELAY_ALLOW_INSECURE=true`。

---

## 二、域名 + TLS（公网推荐）

`deploy/nginx.conf.example` 是可直接套用的反代示例（含 HSTS、SSE/WS 不缓冲、
`X-Forwarded-For` 覆盖而非追加）。要点只有三条：`Host` 原样透传、
`X-Forwarded-Proto https`、`proxy_buffering off`。

```bash
RELAY_PASSWORD='换成一个足够长的密码' \
RELAY_BASE_URL=https://relay.example.com \
RELAY_TRUSTED_PROXY=127.0.0.1 \
RELAY_PORT=127.0.0.1:8088 \
docker compose -f deploy/compose.yaml up -d
```

此时明文端口只绑在 127.0.0.1 上（`RELAY_PORT` 支持 `地址:端口` 前缀），外面只看得见
nginx。`RELAY_TRUSTED_PROXY=127.0.0.1` 让除反代以外的来源一律 403——没设也一样能用，
这道闸门只在「你声明了反代」时才收窄。

**`RELAY_BASE_URL` 可以不配。** 不配时，绝对链接（配对二维码、手机深链）按每次请求的
`Host` 与 `X-Forwarded-Proto` 现推，Cookie 的 `Secure` / `__Host-` 前缀也跟着请求走。
配它的好处是 Origin 校验可以严格相等、子域路由需要它；**配错比不配更糟**（二维码会
指到错地址），拿不准就留空。

> 子域路由（`<hostId>.relay.example.com`）必须钉住 `RELAY_BASE_URL`：没配地址时中继
> 连自己叫什么都不知道，只能走路径路由 `/h/<hostId>/…`（不需要通配证书）。

### 裸 IP + 已有 nginx：借 80 端口（云安全组只开 80/443 时）

很多云主机只放通 22/80/443，而 80 上已经跑着别的站。这时**不要**给中继开 8080
（安全组不开，外面根本连不上），做法是：容器只绑 loopback，再用一个
`server_name` 精确等于该 IP 的 server 块把裸 IP 流量转给它。

```bash
# 1) 容器只听 127.0.0.1，外面永远直连不到 8080
docker run -d --name relay --restart unless-stopped --init \
  -p 127.0.0.1:8080:8080 -v relay-data:/data \
  -e RELAY_PASSWORD='换成一个足够长的密码' \
  --read-only --cap-drop=ALL --security-opt=no-new-privileges:true \
  --tmpfs /tmp:rw,size=16m dsh-mobile-sh-relay:0.3.0
```

```nginx
# 2) /etc/nginx/sites-available/relay
server {
    listen 80;
    server_name 10.0.0.12;          # 只认「Host 就是这台机器 IP」的请求

    # ⚠️ 如果同一个 IP 已经被别的站点写过 server_name，nginx 只认先加载的那个
    #    （按 sites-enabled 文件名排序），后写的会被 [warn] ignored。那你必须把
    #    那个站点在这台 IP 上的 location 原样 include 过来，否则它的 /api/** 会被
    #    中继接走。本例里那两行就是被顶掉的站点内容。
    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }
    include /etc/nginx/snippets/其它站点在这台 IP 上的路由.conf;

    client_max_body_size 0;          # 体积由中继按 RELAY_MAX_BODY_MB 判
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_http_version 1.1;
    proxy_set_header Host $host;      # 未钉地址时二维码/深链就按它生成
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;   # 见 conf.d/relay-upgrade.conf
    proxy_read_timeout 3600s;         # 隧道与 SSE 都是长连接，默认 60s 会掐断
    proxy_send_timeout 3600s;

    location / { proxy_pass http://127.0.0.1:8080; }
}
```

`Connection $connection_upgrade` 需要在 http 上下文里有个 map（Ubuntu 的
`/etc/nginx/conf.d/*.conf` 就在 http 上下文）：

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }
```

然后 `ln -s /etc/nginx/sites-available/relay /etc/nginx/sites-enabled/`、
`nginx -t && systemctl reload nginx`，用 `curl -H 'Host: 10.0.0.12' http://127.0.0.1/healthz`
先在本机验，再从外面 `curl http://10.0.0.12/healthz`。

手机与插件填的地址就是 `http://10.0.0.12`（**IP 字面量才允许明文**，
`RELAY_ALLOW_INSECURE` 也不用设：没配 `RELAY_BASE_URL` 时中继按请求判协议）。
域名流量不受影响，仍由原来那些 `server_name` 的站点接管；只有「Host = 这个 IP」
的请求落到中继。

---

## 三、环境变量

### 必填

| 变量 | 说明 |
| --- | --- |
| `RELAY_PASSWORD` | 唯一必填项。同时是网页登录密码与主机注册密码；默认账号 `admin@relay.local`。 |

### 常用可选

| 变量 | 缺省 | 说明 |
| --- | --- | --- |
| `RELAY_PORT` | `8080` | 对外端口（compose 变量）。容器内恒为 8080。 |
| `RELAY_BASE_URL` | 自动 | 钉住对外地址：`https://relay.example.com` 或 `http://203.0.113.7:8080`（不带路径）。见第二节。 |
| `RELAY_ALLOW_INSECURE` | `false` | 允许 `http://` 明文对外。**必须显式写 `true`**，中继不会悄悄降级。 |
| `RELAY_HOST_ROUTING` | `auto` | `auto`/`subdomain`/`path`。`auto` 按地址主机名判断（IP → path，域名 → subdomain）。没配地址时恒为 `path`。 |
| `RELAY_TRUSTED_PROXY` | 未设 | 只信任哪个来源地址。**直连部署留空**：不声明反代时中继按真实 socket 地址算限流键，也不采信可伪造的 `X-Forwarded-For`。挂了 nginx 就写它的地址（如 `127.0.0.1`），其余来源一律 `403 · untrusted-proxy`。写 `*` = 完全采信 XFF，只在反代地址无法固定时用。 |
| `RELAY_REQUIRE_TOTP` | 跟随 | 配了 `RELAY_PASSWORD` 时**默认关闭**；写 `true` 打开（首次建号时 TOTP secret 只打一次到 `docker logs`）。没配密码时恒为开。 |
| `RELAY_METRICS_TOKEN` | 无 | `/metrics` 的 bearer：**≥16 字符、不含空白**（可以就是那个中继密码）。留空则 `/metrics` 一律 401，其余功能不受影响。 |

### 容量（按机器规格调）

| 变量 | 缺省 | 说明 |
| --- | --- | --- |
| `RELAY_MEMORY_MB` | `768` | 同时决定容器 `mem_limit` 与中继自己的字节预算（预留 / 连接缓冲 / 单请求体）。128–8192。 |
| `RELAY_MAX_HOSTS` | `32` | 最多几台电脑同时挂在这个中继上。**条数不随内存自动缩**——4GB 的机器也不会想挂 150 台。 |
| `RELAY_MAX_STREAMS` | `64` | 同时进行的隧道流上限。线格式只认 64，写更大不会更宽（会直接报错而不是悄悄钳位）。 |
| `RELAY_MAX_BODY_MB` | `300` | 单请求体上限（MiB）。附件上传走这里，太小会让发图失败。 |

启动日志会打印一行 `Capacity …`，明确告诉你是默认值还是被调过：

```
Capacity custom memory=768MiB hosts=8 streams=64 maxBody=300MiB reservation=384MiB ws=256MiB
```

### 高级（一般不改）

| 变量 | 缺省 | 说明 |
| --- | --- | --- |
| `RELAY_ADMIN_EMAIL` | `admin@relay.local` | 想用真实邮箱登录时换它。 |
| `RELAY_ADMIN_PASSWORD` | = `RELAY_PASSWORD` | 把登录密码与注册密码拆开时用。 |
| `RELAY_ENROLL_PASSWORD` | = `RELAY_PASSWORD` | 主机注册密码。 |
| `RELAY_ENROLL_ACCOUNT` | = 管理员邮箱 | 上面那个密码归属的账号。与前一项**必须成对**，只给一个会拒绝启动。 |
| `RELAY_SECRET_KEY_FILE` | `/data/relay.key` | 字段加密密钥。不存在会**自动生成**（0600），所以一行命令不用先跑 `init-key`。**丢了它，库里的加密字段全部解不开**——和数据库一起备份。 |
| `DATABASE_URL` | `/data/relay.db` | SQLite 路径。 |
| `RELAY_RELEASE_DIR` | `<DATABASE_URL 所在目录>/releases` | 发布的 APK 落盘目录。用命名卷时不必设，跟着 `/data` 走。 |
| `RELAY_RELEASE_KEEP` | `5` | 保留最近几个版本，多了连台账带文件一起裁掉。1–50。 |
| `RELAY_RELEASE_MAX_MB` | `64` | 单次发布的包体上限（MiB）。1–512；微信/国内市场之外的分发没有 64MB 限制，按包大小调。 |
| `RELAY_BACKFILL` | 开 | 写 `off` 跳过 v1→v2 归档回填（迁移把机器压死时的紧急开关）。 |
| `TZ` | `UTC` | 只影响账户页上的时间显示与日志。 |

### 来源信任：`RELAY_TRUSTED_PROXY` 到底管什么

它只有两个作用，都不影响「服务连不连得上」：

1. **收窄来源**：写了它就等于声明「我的流量只会从这一个地址进来」，其余来源一律
   `403 · untrusted-proxy`。容器直连时 `remoteAddress` 是 Docker NAT 后的客户端地址，
   不可能是某个固定反代，所以直连部署要**留空**——留空 = 不设这道闸门。
2. **决定是否采信 `X-Forwarded-For`**：只有来源被信任时才拿 XFF 最后一段当客户端
   地址（限流键）。没声明反代就用真实 socket 地址，伪造 XFF 也改不了限流桶。

所以 `*` 不是「直连必须写的开关」，而是「我承认 XFF 可被伪造」的声明：只在反代地址
无法固定时用它（例如某些云 NAT），此时按 IP 限流退化成兜底，真正的防护是密码
（和打开时的 TOTP）。

### 密钥与备份

`/data` 卷里两样东西缺一不可：`relay.key`（字段加密）与 `relay.db`（SQLite）。
`relay.key` 丢了，库里的 TOTP secret、主机令牌、归档正文全部解不开。备份就备份整个卷：

```bash
docker run --rm -v relay-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/relay-data-$(date +%F).tgz -C /data .
```

---

## 四、镜像里已经处理好的细节

这些是「一行命令」之所以能成立的隐含条件，升级镜像时别把它们弄丢：

- **以 `node`（uid 1000）运行**，`read_only` 根文件系统 + `cap_drop: ALL` +
  `no-new-privileges`。唯一的可写面是 `/data` 卷与 16 MiB 的 `/tmp` tmpfs。
- **`/data` 的属主在镜像里就 chown 好了**。Docker 给新建命名卷复制镜像目录的属主；
  少了这一步，非 root 的 node 写不进去，表现为 `EACCES: /data/relay.key`。
- **密钥自动生成**：`/data/relay.key` 不存在就 `randomBytes(32)` 写入（`wx` + 0600）。
  容器重建复用同一卷 → 同一把钥匙。
- **SQLite 建表与索引**：`journal_mode=WAL`、`synchronous=NORMAL`、`temp_store=MEMORY`、
  `cache_size=-16000`、`busy_timeout=5000`；登录/配对/设备回收/归档清理/timeline 摘要
  这些**随行数变慢**的查询每一条都有索引（`tests/relay-indexes.test.ts` 用
  `EXPLAIN QUERY PLAN` 钉住「不许全表扫」）。退出时跑一次 `PRAGMA optimize`。
- **`HEALTHCHECK` 探 loopback `/healthz`**，`start-period=10s`；容器状态 `healthy`
  才算起来了。Host 头按 `RELAY_BASE_URL` 生成，没配就用监听地址。
- **PID 1 = tini（compose 的 `init: true`）**，`SIGTERM` 直达 `cli.ts` 的处理器
  （关库、放掉隧道），`docker stop` 不会拖到超时被 SIGKILL。

---

## 五、构建与验证

```bash
# 只建镜像
docker build -f deploy/Dockerfile -t dsh-mobile-sh-relay:0.3.0 .

# 容器端到端验收（起真容器、建号、注册主机、走隧道、重启保数据、HEALTHCHECK）
npx tsx scripts/docker-test.ts
```

`scripts/docker-test.ts` 会自己建一个专用卷与容器（名字写死，只删自己那份），
结果写 `artifacts/docker-test-result.json`。

```bash
docker inspect --format '{{.State.Health.Status}}' <container>
docker compose -f deploy/compose.yaml logs -f relay
```

---

## 六、发布 APK 到中继（内建更新分发）

中继自己托管安装包与版本清单，手机「检查更新」直接问当前中继。**构建仍在本机**
（服务器上没有 JDK / Android SDK，实测 self-hosted 上 `java`、`gradle` 都不存在），
推上去只是两个文件：`dsh-mobile-<版本>.apk` 与 `version.json`。

```bash
# 本机：构建 release 包（记得带生产签名四件套，见仓库 AGENTS.md）
cd android && ./gradlew :app:assembleRelease && cd ..

# 一条命令推到中继（hostId/凭据自动从 ~/.dsh/.credentials.yaml 按 relayOrigin 匹配）
scripts/release-android.sh --relay http://203.0.113.10 0.2.53 "本次说明"
```

- 鉴权走**主机凭据**（`Authorization: Bearer <hostTunnelToken>`，`?hostId=` 指路），
  也就是这台电脑的插件注册时拿到的那把；脚本按 `relayOrigin` 选记录，
  **多台中继时选错那条同样是 `403 credential-rejected`**（看起来像「这台不认这台机器」）。
- 想用环境变量：`RELAY_URL` / `RELAY_HOST_ID` / `RELAY_HOST_TOKEN`。
- 不带 `--relay` 时脚本退回历史行为（scp 到 `static-host` 静态目录）。
- 端点：`POST /control/release`（发布/覆盖同版本、`DELETE` 删版本、`GET` 看台账）、
  `GET /api/dsh-mobile-sh-apk/downloads/version.json`（公开，App 登录前就要读）、
  `GET …/dsh-mobile-<版本>.apk`、`GET …/latest.apk`（跟随最新）。下载支持
  `Range`（断点续传），`416` 表示「刚好取完」——手机侧的续传逻辑依赖这三态。
- **必须给这条前缀开 nginx**：不少站点的 `/api/**` 有一条兜底 `return 404`，
  它会把 `/api/dsh-mobile-sh-apk/**` 一起吃掉（中继日志里连请求都没有，看起来像
  「中继不提供更新」）。加一条更长的 location 即可：

```nginx
location ^~ /api/dsh-mobile-sh-apk/ {
    proxy_pass http://127.0.0.1:8080;
}
```

- 服务端自检三条：版本号必须匹配 `^[0-9][0-9A-Za-z.+-]{0,31}$`（它同时是文件名）、
  `X-Content-Sha256` 与自算摘要不符直接 400、先写 `.tmp` 再 `rename`（传一半断连
  不会留下一个能下载但装不上的包）。脚本上传成功后还会 GET 一次清单、断言
  `latest` 与 `sha256` 就是刚发的这个，防「200 了但清单没变」。
- App 侧：优先读**当前中继**的 `version.json`，取不到才回落旧地址
  `https://downloads.example.test/api/ybs/downloads/version.json`——中继会关机、会换地址、
  新部署还是空库，这些都不该让「检查更新」变成报错。

## 七、常见问题

**启动即退出，日志 `RELAY_PASSWORD is required`** → 没给密码。这是唯一必填项。

**启动即退出，日志 `untrusted-proxy`** → `RELAY_TRUSTED_PROXY` 没设成 `*`（直连部署）。

**启动即退出，日志 `https-domain-required`** → `RELAY_BASE_URL` 是 `http://` 但没给
`RELAY_ALLOW_INSECURE=true`。不想钉地址就把 `RELAY_BASE_URL` 留空。

**启动即退出，日志 `RELAY_ENROLL_ACCOUNT is required…`** → 拆开了配对密码与归属账号，
只给了一个；两项必须成对。

**登录成功却立刻弹回登录页** → Cookie 落不下。`__Host-` 前缀强制 `Secure`，浏览器在
http 下会整条丢弃。未钉地址时中继按 `X-Forwarded-Proto` 判断，所以反代必须送这个头；
钉住地址时以 `RELAY_BASE_URL` 的 scheme 为准——**以对外地址为准**。

**扫码后 App 连不上** → 二维码里的地址来自请求的 `Host`。反代改写了 `Host`（或用
`localhost` 反向代理），扫出来就是那个错地址。要么让反代 `proxy_set_header Host $host`，
要么干脆钉住 `RELAY_BASE_URL`。

**`/metrics` 401** → 没配 `RELAY_METRICS_TOKEN`（fail-closed）或 bearer 不对。
取的时候要带 `Host` 头（子域路由下 `/metrics` 只挂账户面）：

```bash
curl -H "Authorization: Bearer $RELAY_METRICS_TOKEN" http://127.0.0.1:8080/metrics
```

**手机 App 连不上 `http://<ip>`** → App 只对 IP 字面量放行明文（域名要求 https）。
域名部署请用 `https://`。
