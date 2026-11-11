import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describeCapacity, capacityFromEnv } from './limits.js';
import { Relay, configFromEnv, loadKey, requireCredentials } from './server.js';
import { Store } from './store.js';

const [command]=process.argv.slice(2);

/**
 * 首次启动自动建账号。
 *
 * <p>容器化之后没有「先跑一遍 create-account 再启动」的第二条命令——一行 `docker run`
 * 就得能直接对外服务。所以 `serve` 在拿到 `RELAY_ADMIN_EMAIL` + `RELAY_ADMIN_PASSWORD`
 * 时，先看一眼这个邮箱是否已存在：不存在就建、存在就跳过。**幂等**是关键，容器每次
 * 重启都会走这段代码，不能因为账号已存在就启动失败。
 *
 * <p>TOTP secret 只在这里打印一次（stdout）。容器里 `docker logs` 就是唯一出口，
 * 所以提示语要写清楚「现在存下来，之后不再显示」。
 */
async function provision(config:ReturnType<typeof configFromEnv>){
  // 凭据来自 configFromEnv，不在这里再读一遍环境变量：单一密码模式下邮箱是**推导**出来的
  // （RELAY_ADMIN_EMAIL 可能压根没配），两处各读一半会让「密码模式建不了号」这种坑
  // 只在某一条路径上出现。
  const email=config.adminEmail,password=config.adminPassword;
  if(!email||!password)return;
  const store=new Store(config.database,config.key);
  try{
    const existing=store.accountIdForEnroll(email);
    if(existing){
      // 「先起服务、后来才决定开两步验证」：账号是密码模式建出来的，里面没有 secret，
      // 而此时 requireTotp 已经打开——不在这里补一把，登录就只剩 401 可选。
      const added=config.requireTotp?store.ensureTotp(existing):undefined;
      console.log(`Account ${email} already exists, skipped provisioning.`);
      if(added)process.stdout.write(`TOTP enabled for ${email}. Add this secret to your authenticator now (shown once):\n${added}\n`);
      delete process.env.RELAY_ADMIN_PASSWORD;return;
    }
    // 只有真的要求 TOTP 时才塞 secret：单密码模式下的账号是一把密码就能进的，
    // 悄悄埋一把休眠的 secret 只会让日志里多一行看不懂的东西。
    const account=await store.createAccount(email,password,config.requireTotp);
    delete process.env.RELAY_ADMIN_PASSWORD;
    // Explicit local provisioning output, never part of the server access/application logs.
    if(account.totp)process.stdout.write(`Account created. Add this TOTP secret to your authenticator now (shown once):\n${account.totp}\n`);
    else console.log(`Account ${email} created (password only). Set RELAY_REQUIRE_TOTP=true to add a second factor.`);
  }finally{store.close();}
}

if(command==='init-key'){
  const path=resolve(process.env.RELAY_SECRET_KEY_FILE??'data/relay.key');
  mkdirSync(dirname(path),{recursive:true});
  // 复用 loadKey：文件已存在时只读不写，避免把线上正在用的密钥覆盖掉。
  loadKey(path);
  console.log(`Deployment key ready at ${path}. Keep it with encrypted database backups.`);
}else if(command==='create-account'){
  const config=configFromEnv();mkdirSync(dirname(config.database),{recursive:true});
  const email=config.adminEmail,password=config.adminPassword;
  if(!email||!password)throw new Error('RELAY_ADMIN_EMAIL and RELAY_ADMIN_PASSWORD secret environment required');
  const store=new Store(config.database,config.key);
  try{const account=await store.createAccount(email,password,config.requireTotp);delete process.env.RELAY_ADMIN_PASSWORD;
    // Explicit local provisioning output, never part of the server access/application logs.
    if(account.totp)process.stdout.write(`Account created. Add this TOTP secret to your authenticator now (shown once):\n${account.totp}\n`);
    else console.log(`Account ${email} created (password only). Set RELAY_REQUIRE_TOTP=true to add a second factor.`);
  }finally{store.close();}
}else if(command==='serve'){
  const config=configFromEnv();
  requireCredentials(config);
  mkdirSync(dirname(config.database),{recursive:true});await provision(config);
  const relay=new Relay(config);
  const port=await relay.listen();
  const mode=`${relay.routing}${relay.insecure?' · 明文':''}`;
  console.log(`Relay listening on ${config.listenHost??'127.0.0.1'}:${port} (${config.baseUrl??'地址按请求推导'}, ${mode})`);
  console.log(`Capacity ${describeCapacity(capacityFromEnv())}`);
  // 这两件事必须在 `docker logs` 里一眼看到：一是流量闸门被调过，二是动态验证码关着。
  // 自托管的运维常常只看启动那几行，事后不会再去翻文档。
  if(config.passwordMode)console.log(`Password mode: admin=${config.adminEmail} TOTP=${config.requireTotp?'on':'OFF (set RELAY_REQUIRE_TOTP=true to enable)'} enroll=${config.enrollAccount}`);
  if(!config.baseUrl)console.warn('RELAY_BASE_URL unset: absolute links are derived from each request Host (put it behind a reverse proxy that sets X-Forwarded-Proto).');
  let closing=false;const stop=()=>{if(closing)return;closing=true;void relay.close().then(()=>process.exit(0));};process.on('SIGINT',stop);process.on('SIGTERM',stop);
}else {process.stderr.write('Usage: init-key | create-account | serve\n');process.exitCode=1;}
