// Runs only in the isolated local Docker test volume. Its stdout is captured, not logged.
import { readFileSync,writeFileSync,existsSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { Store } from '../packages/relay/src/store.js';
const keyFile=process.env.RELAY_SECRET_KEY_FILE!;
if(!existsSync(keyFile))writeFileSync(keyFile,randomBytes(32),{mode:0o600});
const store=new Store(process.env.DATABASE_URL!,readFileSync(keyFile));
// 账号邮箱与中继的单密码模式保持一致（`PASSWORD_MODE_EMAIL`）：这样 `serve` 起来后
// 走 `provision()` 会打印 "already exists, skipped"，而不是在同一个卷里悄悄多建一个
// admin@relay.local —— 两条建号路径必须是同一个账号。
const EMAIL='admin@relay.local',PASSWORD='docker-local-test-password';
const action=process.argv[2];
try{
  if(action==='init'){
    // 不带 TOTP：镜像默认是单密码模式，测试夹具要按**用户真的会看到的**那套走。
    const account=await store.createAccount(EMAIL,PASSWORD,false);
    const invitation=store.invitation({account_id:account.id});process.stdout.write(JSON.stringify({accountId:account.id,totp:account.totp,invitation}));
  }else if(action==='pair'){
    const account=store.get('SELECT id FROM accounts WHERE email=?',EMAIL)!;
    process.stdout.write(JSON.stringify(store.createPair({account_id:account.id},process.argv[3]!)));
  }else if(action==='inspect'){
    process.stdout.write(JSON.stringify({hosts:store.get('SELECT count(*) n FROM hosts')!.n,accounts:store.get('SELECT count(*) n FROM accounts')!.n,audit:store.get('SELECT count(*) n FROM audit')!.n}));
  }else throw new Error('unknown fixture operation');
}finally{store.close();}
