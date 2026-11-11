# syntax=docker/dockerfile:1

# 中继服务镜像。两阶段：build 里装全量依赖、打 bundle；运行阶段只留 relay 的
# dist（esbuild 已把 zod / ws / qrcode 全部内联，node_modules 不进镜像）。
#
# 起服务的姿势（只有一个必填项）：
#   docker run -d -p 8080:8080 -v relay-data:/data -e RELAY_PASSWORD='…' dsh-mobile-sh-relay
FROM node:24.12.0-bookworm-slim AS build
WORKDIR /app
# 先只拷 manifest 装依赖：源码改动不会让 npm ci 这层缓存失效。
COPY package.json package-lock.json ./
COPY packages/core/package.json packages/core/package.json
COPY packages/relay/package.json packages/relay/package.json
COPY packages/plugin/package.json packages/plugin/package.json
RUN npm ci
COPY packages packages
COPY scripts scripts
RUN npm run build

FROM node:24.12.0-bookworm-slim
LABEL org.opencontainers.image.title="dsh-mobile-sh relay" \
      org.opencontainers.image.description="Self-hosted relay for the DSH mobile remote control (phone <-> relay <-> desktop plugin)."

# 默认值只保留「容器里必须这样」的四项：监听 0.0.0.0（否则容器外连不上）、密钥与
# SQLite 落在数据卷 /data、端口 8080。**对外地址故意不设**——容器不知道自己叫什么，
# 名字要等请求进来才知道；钉死一个错误的地址会让配对二维码指到 localhost。
ENV NODE_ENV=production \
    PORT=8080 \
    RELAY_LISTEN_HOST=0.0.0.0 \
    RELAY_SECRET_KEY_FILE=/data/relay.key \
    DATABASE_URL=/data/relay.db

WORKDIR /app
COPY --from=build /app/packages/relay/dist /app/

# 密钥与 SQLite 都在 /data。这一步的 chown 不是可选的：Docker 给**新建的**命名卷
# 复制镜像里该目录的属主，uid 1000（node）写不进去就等于容器起不来（表现为
# `EACCES: /data/relay.key` 或 SQLite 的 `unable to open database file`）。
RUN mkdir -p /data && chown node:node /data
VOLUME ["/data"]

USER node
EXPOSE 8080
# 只探 loopback，Host 头按 RELAY_BASE_URL 生成：子域路由下 target() 靠 Host 认出
# 「账户面」，不带就是 404；路径路由不看 Host。地址没配就退到本次监听的地址。
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "const p=process.env.PORT||8080;const u=new URL(process.env.RELAY_BASE_URL||('http://127.0.0.1:'+p));require('node:http').get({host:'127.0.0.1',port:p,path:'/healthz',headers:{host:u.host}},r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"

# `exec` 形态直接用 node 起进程：SIGTERM 直达 cli.ts 的处理器（关库、放掉隧道），
# 不用 shell 包一层——`docker stop` 才不会变成 10 秒后 SIGKILL。
ENTRYPOINT ["node", "/app/cli.js"]
CMD ["serve"]
