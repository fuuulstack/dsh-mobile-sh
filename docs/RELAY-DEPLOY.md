# RELAY-DEPLOY：把中继部署起来（读者＝要动手的 AI Agent）

> 这份文档的读者是**执行部署的 Agent**，不是人。写法刻意是「命令 + 期望输出 + 失败分支」：
> 每一步都要拿到判据再往下走；判据不符就去对应小节，不要靠猜改配置。
>
> 深度背景：`docs/PRD.md`（设计与协议）、`HANDOFF.md`（当前状态）、`docs/pitfalls.md`（踩过的坑全表）。
> 本文只讲「怎么把它跑起来、怎么证明它真的跑起来了」。

---

## 0. 先决定你要做哪一种（按顺序回答三个问题）

| 问题 | 答案 | 走哪一节 |
|---|---|---|
| 目标机器上有 Docker 吗？ | 有 | §2（推荐，一行命令） |
| | 没有 | 先装 Docker；本仓不提供裸机 systemd 单元 |
| 有域名 + 能签 TLS 证书吗？ | 有 | §3.B（`RELAY_BASE_URL=https://…` + 反代） |
| | 没有，只有公网 IP | §3.C（裸 IP + 明文 http，App 只对 IP 字面量放行明文） |
| 云安全组放了哪些端口？ | 只放 80/443（很常见） | §3.C 的「借 80 端口」分支 |
| | 能放任意端口 | §3.A（容器直接映射端口，最省事） |

**术语**：中继（relay）＝本仓 `packages/relay`；主机（host）＝跑着 DSH 桌面端的那台电脑，由插件注册进来；设备（device）＝手机。

---

## 1. 事实卡（不要凭记忆猜这些值）

| 项 | 值 |
|---|---|
| 镜像名 | `dsh-mobile-sh-relay:<tag>`（`deploy/compose.yaml` 缺省 tag `0.3.0`；名字的事实源是 `packages/core/src/naming.ts` 的 `NAMING.dockerImage`） |
| 构建 | `docker build -f deploy/Dockerfile -t dsh-mobile-sh-relay:0.3.0 .`（两阶段，基础镜像 `node:24.12.0-bookworm-slim`） |
| 容器内路径 | 入口 `/app/cli.js`，数据卷 `/data`（`relay.key`、`relay.db`、`releases/`） |
| 进程 | `node /app/cli.js serve`，监听 `0.0.0.0:8080`（容器内） |
| 必填环境变量 | **只有 `RELAY_PASSWORD`**（≥12 字符，否则容器起不来：`Error: password-length`） |
| 唯一对外必需端点 | `/healthz`（探活）、`/tunnel`（插件隧道 WS）、`/native/v2/*`（手机）、`/control/*`（主机）、`/api/dsh-mobile-sh-apk/*`（更新分发，若用 §6） |
| 密钥 | 首次启动自动生成 `32` 字节到 `/data/relay.key`（`0600`）；**丢了就解码不了库里的密文**，备份卷等于备份它 |
| 数据库 | `/data/relay.db`（SQLite，WAL；索引与 PRAGMA 已在 `store.ts` 建好，不需要手工 `CREATE INDEX`） |
| 日志 | `docker logs <容器>`（stdout；启动会打 `Account … created/already exists`、`Relay listening on …`、`Capacity …`、`Password mode: …`） |
| `/metrics` 鉴权 | `Authorization: Bearer <RELAY_METRICS_TOKEN>`；token 规则 **≥16 字符、不含空白**（可以就是 `RELAY_PASSWORD`）。缺 token 时该端点 401 |

**身份模型一句话**：一个密码同时是「管理员密码」和「主机注册口令」；账号默认 `admin@relay.local`（可用 `RELAY_ADMIN_EMAIL` 改）。收窄成三组老变量（`RELAY_ADMIN_*` / `RELAY_ENROLL_*`）仍然支持，但**不要混着填半套**——只给密码不给邮箱会直接报 `RELAY_ADMIN_EMAIL is required`。

---

## 2. 最快路径（Docker，一行命令）

### 2.1 起容器

```bash
RELAY_PASSWORD='<至少12字符的强口令>' docker run -d --name relay \
  --restart unless-stopped --init \
  -p 8080:8080 \
  -v relay-data:/data \
  -e RELAY_PASSWORD='<同一个口令>' \
  -e RELAY_METRICS_TOKEN='<≥16字符、无空白>' \
  --read-only --tmpfs /tmp:rw,size=16m \
  --cap-drop=ALL --security-opt=no-new-privileges:true \
  --memory 768m --pids-limit 128 \
  dsh-mobile-sh-relay:0.3.0
```

或者用仓库里的 compose（**注意 §3.C：安全组只开 80/443 时不要用这一条**）：

```bash
RELAY_PASSWORD='<同一个口令>' docker compose -f deploy/compose.yaml up -d
# 老机器上是 docker-compose（v1 5.5.1）也能跑：docker-compose -f deploy/compose.yaml up -d
```

### 2.2 三条判据（全部要过）

```bash
docker ps --filter name=relay --format '{{.Status}}'          # → Up … (healthy)
docker logs relay 2>&1 | head -8                               # → 见下面四条
curl -sS --noproxy '*' -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/healthz   # → 200
```

期望日志（顺序可能略有不同，四类都要出现）：

```
Account admin@relay.local created (password only). Set RELAY_REQUIRE_TOTP=true to add a second factor.
Relay listening on 0.0.0.0:8080 (地址按请求推导, path)
Capacity default memory=768MiB hosts=32 streams=64 maxBody=300MiB reservation=384MiB ws=256MiB
Password mode: admin=admin@relay.local TOTP=OFF (set RELAY_REQUIRE_TOTP=true to enable) enroll=admin@relay.local
```

> `RELAY_BASE_URL unset: …` 这条是**正常**的：不钉地址时，二维码/深链里的地址按请求的 `Host` 现推。
> 重启后再看到 `Account admin@relay.local already exists, skipped provisioning.` 也是正常（幂等）。

### 2.3 常见失败分支

| 现象 | 判据 / 原因 | 动作 |
|---|---|---|
| 容器起来就退出 | `docker logs` 有 `RELAY_PASSWORD is required to bootstrap the relay` | 补 `-e RELAY_PASSWORD=…`（或 `RELAY_ADMIN_EMAIL`+`RELAY_ADMIN_PASSWORD`） |
| | `Error: password-length` | 口令 <12 字符，换长的（**不要去改源码放宽**，那是故意的） |
| 容器 `Up` 但 `/healthz` 回 **403 `untrusted-proxy`** | 在中继前面没有反代、却设了 `RELAY_TRUSTED_PROXY` | 直连部署**留空**（老版本镜像默认 `*`，会踩这个坑；先确认镜像里有 `trustedPeer`） |
| `/healthz` 回 **404 `not-found`** | 请求没带 `Host` 头，或把请求打到了别的站点 | `curl -H "Host: <你的域名或IP>" …` |
| 密码正确但 `/login` 回 **401 `login-failed`** | 该账号带 TOTP secret 而镜像在要求验证码 | 用新版镜像（`login()` 只在 `RELAY_REQUIRE_TOTP=true` 时看 TOTP）；老库可显式 `RELAY_REQUIRE_TOTP=false` |
| `/metrics` 回 **401 `metrics-token`** | token 含空白或 <16 字符；老镜像还只认 `[A-Za-z0-9_-]`（`@` 会被拒） | 换 token，或升级镜像（新正则 `\S{16,128}`） |
| `EACCES: /data/relay.key` / `unable to open database file` | 用了 **bind mount** 而不是命名卷，宿主目录属主不是 uid 1000 | `sudo chown -R 1000:1000 <宿主目录>`（命名卷没这个问题，镜像里已 `chown node:node /data`） |

---

## 3. 对外暴露：三种形态

> 先把「中继自己认为的地址」想清楚：**钉了 `RELAY_BASE_URL` 就用它**，没钉就按每次请求的 `Host` + `X-Forwarded-Proto` 现推。
> 二维码、深链、`/open` 跳转全部走这个值——**钉错比不钉更糟**（会印出一个手机连不上的地址）。

### 3.A 直接映射端口（最快，适合内网/测试）

```bash
docker run … -p 8080:8080 …            # 已含在 §2.1
```
判据：`curl http://<公网IP>:8080/healthz` → 200。
注意：`RELAY_BASE_URL` 留空即可；App 对**IP 字面量**允许明文 http（域名必须 https）。
风险：8080 直连公网没有任何缓冲层，只建议测试期这样跑。

### 3.B 域名 + TLS（公网正式形态，推荐）

1. 先签证书（任选其一：certbot / Cloudflare 源站证书 / 已有泛域名证书）。
2. nginx 片段（`deploy/nginx.conf.example` 就是这份，可直接抄）：

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }   # 放 http{} 里

server {
    listen 443 ssl;
    server_name relay.example.com;
    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    client_max_body_size 0;          # APK 上传不分块缓冲，见 §6
    proxy_buffering off;
    proxy_http_version 1.1;
    proxy_set_header Host $host;                 # 未钉地址时，二维码/深链里的地址就是它
    proxy_set_header X-Forwarded-Proto $scheme;  # 决定 cookie 是否带 Secure / __Host- 前缀
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_read_timeout 3600s;        # 隧道是长连接，别用 60s 默认
    proxy_send_timeout 3600s;

    location / { proxy_pass http://127.0.0.1:8080; }
}
```

3. 容器侧：`-e RELAY_BASE_URL=https://relay.example.com`（钉住，子域路由也能用）。
4. 如果 nginx 允许指定「只信任哪个来源」：`-e RELAY_TRUSTED_PROXY=127.0.0.1`。**不要填 `*`**，那等于承认 `X-Forwarded-For` 可伪造。
5. 判据：
   ```bash
   curl -sS -o /dev/null -w '%{http_code}\n' https://relay.example.com/healthz         # 200
   curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' https://relay.example.com/  # 303 → https://relay.example.com/login
   ```
   以及**隧道能不能升级**（这条最容易漏）：下面 §4 插件连上后 `/metrics` 里 `online:true` 才算通。

### 3.C 裸 IP + 已有 nginx：借 80 端口（云安全组只开 22/80/443 时）

典型现场：一台已经有站的云主机（`ybs-api`、`pp-paradise`、兜底的 `autosign` 之类），安全组只放 80/443。
此时**容器不要映射公网端口**，只绑 `127.0.0.1:8080`，由 nginx 借 80 转进去。

```bash
docker run -d --name relay --restart unless-stopped --init \
  -p 127.0.0.1:8080:8080 -v relay-data:/data \
  -e RELAY_PASSWORD='<口令>' -e RELAY_METRICS_TOKEN='<token>' \
  --read-only --tmpfs /tmp:rw,size=16m --cap-drop=ALL \
  --security-opt=no-new-privileges:true --memory 768m --pids-limit 128 \
  dsh-mobile-sh-relay:0.3.0
```

新增一个站点文件（`/etc/nginx/sites-available/relay` + 软链到 `sites-enabled/`）：

```nginx
server {
    listen 80;
    server_name 203.0.113.10;                 # 换成你的裸 IP

    # 如果这个 IP 上原来就有站点（例如 ybs-api），一定要把它原有的这些 location 复制过来，
    # 否则同名 server_name 被这条顶掉后，那批路径会一起失效。
    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }
    include /etc/nginx/snippets/autosign-ybs-routes.conf;   # 按现场实际改/删

    client_max_body_size 0;
    client_body_buffer_size 1m;
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;

    location ^~ /api/dsh-mobile-sh-apk/ { proxy_pass http://127.0.0.1:8080; }   # 见 §6
    location / { proxy_pass http://127.0.0.1:8080; }
}
```

再补一段 `map`（放 `conf.d/relay-upgrade.conf`，它在 `http{}` 里）：
```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }
```

判据与失败分支：

```bash
nginx -t && systemctl reload nginx
curl -sS -H 'Host: 203.0.113.10' -o /dev/null -w '%{http_code}\n' http://127.0.0.1/healthz   # 200
curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' http://203.0.113.10/                # 303 → http://203.0.113.10/login
curl -sS -H 'Host: nonsense.example' -o /dev/null -w '%{http_code}\n' http://127.0.0.1/       # 404（兜底站没被破坏）
```

- `nginx -t` 只给 **`[warn] conflicting server name "…" on 0.0.0.0:80, ignored`**：说明同名 `server_name` 已存在，**先加载的那个赢**（`sites-enabled/` 按文件名排序）。被顶掉那块里原有的 `location` 必须复制进新块，否则那些路径 404 —— 这正是上面那条 `include` 与 `acme-challenge` 存在的原因。
- 打不通时先分辨「端口没通」与「安全组没放通」：`nc -vz <ip> <port>` → `Connection refused`＝机器上没进程；`Operation timed out`＝包被丢（安全组）。

#### 3.C.1 这台机器**已经装了 nginx**（最常见，也是最好的情况）

不用再起第二个 nginx，也不需要停现有的站。但先摸清现场，别凭猜：

```bash
nginx -v
ls /etc/nginx/sites-enabled/ /etc/nginx/conf.d/            # 有哪些站
nginx -T 2>/dev/null | grep -nE 'server_name|listen .*(80|443)|default_server|connection_upgrade' | head -30
ss -tlnp | grep -E ':(80|443|8080)\b'                     # 端口被谁占着
```

然后按「能不能给中继一个**专属 Host**」三选一。**中继不能挂在子路径下**（它用绝对路径
`/open`、`/native/v2/*`、cookie 的 `Path=/`），所以「在现有站点里加 `location /relay/`」这条路不存在。

| 现场 | 做法 | 冲突风险 |
|---|---|---|
| 有域名（哪怕是个子域名 `relay.example.com`） | **首选**：新 server 块，`server_name relay.example.com`，80 跳 443 | 零冲突，不碰任何现有站 |
| 只有裸 IP，且 80 上已有别的 `server_name`（常见：`server_name _;` + `default_server`） | 新块写 `server_name <你的裸IP>;` —— **精确 Host 优先于 `default_server`** | 低；但若该 IP 已被某块声明过，见下 |
| 80/443 全被占、也不想动现有站 | 换端口：容器 `-p 127.0.0.1:8088:8080`，再开 `listen 8088` 或直接 `-p 8088:8080`，地址填 `http://<ip>:8088` | 零冲突（但安全组要放通该端口） |

三条硬约束（每条都真踩过）：

1. **`map $http_upgrade $connection_upgrade` 全 `http{}` 只能定义一次**。先 `grep -r connection_upgrade /etc/nginx/`：
   已有就**直接复用**，重复定义会让 `nginx -t` 报 `"map" directive is duplicate`。没有就新建 `/etc/nginx/conf.d/relay-upgrade.conf`。
   不想用 map 的等价写法（会牺牲非 WS 请求的 keep-alive）：`proxy_set_header Connection "upgrade";`。
2. **`server_name` 重名不会报错，只会 warn 并让先加载的赢**（`sites-enabled/` 按文件名排序）：
   `[warn] conflicting server name "x" on 0.0.0.0:80, ignored`。被顶掉那块里原有的 `location`
   （ACME、`/api/...`、静态目录…）会一起失效，必须复制进新块 —— 复制完**两条路径都要打一遍**才算验证过。
3. **用 `reload` 不用 `restart`**：`nginx -t && systemctl reload nginx`。`restart` 会掐掉现有站的长连接。

两个额外形态：

- **nginx 本身也是容器**：别抢宿主 80，把中继接进同一个 docker network，`proxy_pass http://relay:8080;`
  （容器名当主机名），中继容器就**不用** `-p`。此时 `RELAY_TRUSTED_PROXY` 可以不填（来源是内网地址）。
- **前面还有一层**（Cloudflare / 云负载均衡 / 另一台反代）：`RELAY_TRUSTED_PROXY` 填**最靠近中继的那一跳**；
  要保证 `X-Forwarded-Proto` 一路传下来，否则 cookie 的 `Secure`/`__Host-` 前缀判错，表现为「登录成功又弹回登录页」。

---

## 4. 让 DSH 插件连上来（主机侧）

1. 在 DSH 的插件设置页（侧栏「手机访问」→ 设置）填 **中继地址**：
   - https 域名 → `https://relay.example.com`
   - 裸 IP → `http://203.0.113.10`（**明文只对 IP 字面量放行**；给域名填 http 会被拒）
   填完保存；`enrollSecret` 填 `RELAY_PASSWORD`（或显式 `RELAY_ENROLL_PASSWORD` / 生成的一次性注册口令）。
2. 插件会自己 `POST /control/enroll` 换到主机令牌，并在 `/tunnel` 建长连（WS）。
3. 判据（三条，缺一不可）：

```bash
# ① 中继台账里有这台主机
docker exec relay node -e "const{DatabaseSync}=require('node:sqlite');const d=new DatabaseSync('/data/relay.db');console.log(d.prepare('select id,alias,platform,revoked from hosts').all())"
# ② 隧道 online（心跳 30s 一次，45s 内算在线）
curl -sS --noproxy '*' -H 'Authorization: Bearer <RELAY_METRICS_TOKEN>' http://127.0.0.1:8080/metrics | python3 -c "import json,sys;print(json.load(sys.stdin)['hosts'])"
# ③ 插件日志里没有 tunnel 报错；DSH 插件面板能看到「已连接 / 延迟」
```

失败分支：
- `hosts` 表空 + 插件报 403 → 注册口令不对（`enrollSecret` 与 `RELAY_PASSWORD` 不一致），或这台机器早前注册过、令牌在 `~/.dsh/.credentials.yaml` 里被别的 profile 抢先用了。
- `hosts` 有行但 `online:false` → `/tunnel` 没升起来：80% 是 nginx 少了 §3.B 那两行 `Upgrade`/`Connection`，或 `proxy_read_timeout` 太短。
- 插件面板填了域名却连不上、中继日志完全没有请求 → 中继在别的 `server_name` 后面（见 §3.C 的重名警告）。

---

## 5. 让手机配对（设备侧）

两条路，任选：

```bash
# A. 一码配齐（推荐）：用主机令牌签发，返回 {login, code}，各 8 位、一次性、TTL 5 分钟
curl -sS -X POST http://203.0.113.10/control/pair \
  -H "Authorization: Bearer <hostTunnelToken>" \
  -H 'Content-Type: application/json' \
  -d '{"hostId":"<32位hex主机id>"}'
# → {"code":"12345678","login":"87654321","expiresAt":…}

# 手机侧（adb 调试时可这样灌深链；& 必须整条包在双引号里）
adb shell "am start -a android.intent.action.VIEW -d 'dsh://pair?relay=http://203.0.113.10&login=87654321&code=12345678'"
```

```bash
# B. 先登录再配对：手机用 8 位设备码 / 邮箱+密码登录，再输配对码
curl -sS -X POST http://203.0.113.10/native/v2/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@relay.local","password":"<口令>","label":"curl 验证"}'
```

判据（配对成功的唯一硬证据是**库里多了一行 grants**）：

```bash
docker exec relay node -e "const{DatabaseSync}=require('node:sqlite');const d=new DatabaseSync('/data/relay.db');console.log('devices',d.prepare('select id,label,revoked from devices').all());console.log('grants',d.prepare('select host_id,device_id,revoked from grants').all())"
```

失败分支：
- `devices` 没新增行 ⇒ 登录请求根本没到中继：检查手机填的地址（`RelayApi.normalize` 只对 IP 字面量放行 http）、中继访问日志里有没有这条请求。
- `GET /native/v2/hosts` 返回 `authorized:false` ⇒ 登录成功但没配对，回到上面补一次配对码。
- 请求带 `Origin:` 头被 **403 `origin`**：`/native/v2/*` 是给 App 用的，**不要带浏览器 Origin 头**。

---

## 6. 发布 APK 到中继（内建更新分发）

> 构建仍在本机（服务器上通常没有 JDK/Android SDK）。中继只负责托管与分发。

```bash
# 1) 先抬版本号：android/app/build.gradle 的 versionCode / versionName
# 2) 本机构建（必须用生产 keystore，否则装不上线上包；见 AGENTS.md「签名」一节）
cd android && ./gradlew :app:assembleRelease && cd ..

# 3) 一条命令推上去（hostId/token 自动按 relayOrigin 从 ~/.dsh/.credentials.yaml 里挑）
scripts/release-android.sh --relay http://203.0.113.10 <版本号> "本次说明"
```

判据：脚本会回读清单并断言 `latest` 与 `sha256` 就是刚发的包，成功时打印
`清单    latest=… size=… 校验通过`。再手工验三条：

```bash
curl -sS --noproxy '*' http://<中继>/api/dsh-mobile-sh-apk/downloads/version.json     # 200，八字段
curl -sS --noproxy '*' -r 0-99 -D - -o /dev/null http://<中继>/api/…/latest.apk | grep -i content-range   # 206 bytes 0-99/<size>
curl -sS --noproxy '*' -H 'Authorization: Bearer <hostToken>' 'http://<中继>/control/release?hostId=<id>'  # 台账
```

**必做的 nginx 一步**：不少站点的 `/api/**` 有一条兜底 `return 404`，会把 `/api/dsh-mobile-sh-apk/**` 一起吃掉——
症状是**中继日志里连请求都没有**、手机看到一张 nginx 404 页。加一条更长前缀即可（§3.C 已包含）：

```nginx
location ^~ /api/dsh-mobile-sh-apk/ { proxy_pass http://127.0.0.1:8080; }
```

失败分支：
| 现象 | 原因 | 动作 |
|---|---|---|
| 脚本 403 `credential-rejected` | 从 `~/.dsh/.credentials.yaml` 挑错了那台中继的记录（多台中继时按 `relayOrigin` 匹配） | 显式给 `RELAY_HOST_ID` / `RELAY_HOST_TOKEN` |
| 400 `release-sha256-mismatch` | `X-Content-Sha256` 与包体不符（传输被截断/改过） | 重发；别绕过这个校验 |
| 400 `release-version-invalid` | 版本号不匹配 `^[0-9][0-9A-Za-z.+-]{0,31}$`（它同时是文件名） | 用 `0.2.53` 这种形态 |
| 上传 200 但清单没变 | 你打的不是发布端点，或命中缓存 | 清单有 `cache-control: max-age=300`；重试前先看 `ETag`/`Age` |
| `413` / 连接重置 | 包体超过 `RELAY_RELEASE_MAX_MB`（默认 64）或 nginx `client_max_body_size` | 调 `RELAY_RELEASE_MAX_MB` **且**把 nginx 的 `client_max_body_size 0;` 加上 |

App 侧行为：**先问当前中继**的 `…/api/dsh-mobile-sh-apk/downloads/version.json`，取不到才回落
`https://downloads.example.test/api/ybs/downloads/version.json`。中继会关机、会换地址、新部署还是空库，这些只该让「查不到更新」退化。

---

## 7. 升级、回滚、备份

```bash
# 升级：换镜像 → 重建容器（卷保留，账号/授权/发布历史都在）
docker build -f deploy/Dockerfile -t dsh-mobile-sh-relay:<新tag> .
docker rm -f relay && docker run …（与 §2.1 完全相同的参数，只换 tag）
```

- **先验镜像真的换了**（tag 可变、`shasum` 因 `BUILD_HASH` 天生不等）：
  `docker exec relay grep -c 'dsh-mobile-sh-apk' /app/cli.js`（若本轮改动涉及该功能，应 ≥1）。
- 回滚：`docker rm -f relay` 后用旧 tag 同样参数起；数据卷不动。
- 备份（含密钥，丢了等于丢数据库）：
  ```bash
  docker run --rm -v relay-data:/data -v "$PWD":/backup node:24-bookworm-slim \
    tar czf /backup/relay-data-$(date +%F).tgz -C /data .
  ```
- 容量调参：`RELAY_MEMORY_MB`（决定字节预算按比例缩放）、`RELAY_MAX_HOSTS`、`RELAY_MAX_STREAMS`、`RELAY_MAX_BODY_MB`。
  启动日志里的 `Capacity …` 就是生效值。**`RELAY_MAX_STREAMS` 上限是 64**（线格式常量），写更大直接报错，不要为了「更多连接」去改它。
- 名字老库迁移：v1 归档会在启动后由回填补成 v2 记录（分块、可断点续跑，`RELAY_BACKFILL=off` 可临时关掉）。手机上看到「历史不全」先等回填，并看 `/metrics` 的 `backfill` 段。

---

## 8. 排障索引（症状 → 先看哪里 → 动作）

| 症状 | 先看 | 动作 |
|---|---|---|
| 手机说「请求失败（502）」 | 中继日志有没有 `upstream-error`；插件日志 `loopback … failed: <code>` | 这是**插件→本机 DSH 宿主**的回路断了，不是中继；重启 DSH 桌面端 |
| 手机一直停在「正在分析」 | 插件日志有没有 `focus … replay=`；手机上有没有 `control pulse` | 隧道半死：看 `/metrics` 的 `hostMetrics[].tunnel`（`bufferedPeakBytes`/`stalls`） |
| 顶栏一直红字 404 | 中继 access log 里被请求的 hostId | 手机缓存着**上一台中继的主机 id**：让 App 重新登录/配对一次（新版会清） |
| 扫码后 App 连不上 | `devices` 表有没有新行 | 没行＝请求没到：地址/明文限制/安全组；有行＝`TokenVault` 或配对码过期 |
| 登录成功又弹回登录页 | 响应里的 `Set-Cookie` 带不带 `Secure` | 明文部署下带 `Secure` 会被浏览器丢弃；不钉 `RELAY_BASE_URL` 且 `X-Forwarded-Proto` 正确时不会出现 |
| `/metrics` 401 | `Authorization` 头格式 | `Bearer <token>`，token ≥16 字符且无空白 |
| 发布后 App 仍说「已是最新」 | 清单的 `latest` / `sha256` | 对比 `android/app/build.gradle` 的 `versionName`；同名版本覆盖发布也算「已是最新」 |

---

## 9. Agent 硬规矩（违反任何一条都别提交）

1. **不要把口令写进仓库里的文件**（`deploy/compose.yaml` 用 `${RELAY_PASSWORD:?…}` 占位，`.env` 必须 gitignore）。
2. 直连部署时 **`RELAY_TRUSTED_PROXY` 留空**；只有在前面确实有反代时才填，且不要填 `*`。
3. 不要放宽凭据类限流（`login` / `device-code` / `enroll` / `pair`）；它们防的是猜 8 位码。资源类（`sync`/`changes`/`archive`）已经放宽，别重复调。
4. 不要改协议常量（`MUX_V2_TAG`、`CONTROL_PATH`、`LIMITS.streams`、窗口大小）——改了对手端直接 4422 `unsupported-window`。
5. **改动 `packages/**` 之后必须** `npm run check`（typecheck + 命名门禁 + 测试 + 打包）全绿再部署；Node 需 **24.x**（v22 会报 `node:crypto` 没有 `argon2` 导出）。
6. 部署后判据是**特征串或接口行为**，不是 `shasum`（构建把 `BUILD_HASH` 注入，哈希天生不同）。
7. 每次踩到新坑，先写进 `docs/pitfalls.md`（`AGENTS.md` 只保留最近几条 + 指针），再提交。

---

## 10. 收尾自检清单（复制到你的执行记录里逐条打勾）

- [ ] `docker ps` → `Up (healthy)`；`/healthz` 200
- [ ] `docker logs` 四类行齐全（账号、listening、Capacity、Password mode）
- [ ] 中继对外地址正确：`/` → 303 到 `<对外地址>/login`；钉住地址时二维码里不是 `localhost`
- [ ] 反代形态：`Upgrade`/`Connection` 两行在、`proxy_read_timeout 3600s`、`buffering off`
- [ ] 目标机本来就有 nginx：`map` 只定义一次、`server_name` 重名时把被顶掉那块原有的 `location` 复制进了新块（两条路径各打一遍验证）、用 `reload` 而非 `restart`
- [ ] `/metrics` 里 `hosts[].online:true`（插件隧道通）
- [ ] `devices` + `grants` 有对应行（手机登录 + 配对成功）
- [ ] 手机能发一条消息并收到流式回复
- [ ] 若启用 APK 分发：清单 200、`latest.apk` Range 206、`/control/release` 台账有版本、nginx 有 `^~ /api/dsh-mobile-sh-apk/`
- [ ] 新坑已登记 `docs/pitfalls.md`，改动已 `npm run check` 通过
