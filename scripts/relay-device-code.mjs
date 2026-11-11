// 在生产中继上生成一个设备配对码。
//
// 为什么不用网页：网页 /device-codes 需要先登录（邮箱 + 密码 + TOTP），
// 而这次只是要一个码。这里直接按 store.ts 里 createDeviceCode 的同一套算法
// 落库：8 位数字、sha256 存哈希、15 分钟有效、一次性。
//
// 用法（在 relay-host 上）：
//   sudo bash -c 'set -a; . /etc/dsh-mobile-sh.env; set +a; node gen-device-code.mjs "设备名"'
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes, randomInt } from 'node:crypto';

const DEVICE_CODE_TTL = 15 * 60 * 1000;
const label = (process.argv[2] || '手机').slice(0, 80);
const dbPath = process.env.DATABASE_URL;
if (!dbPath) {
  console.error('DATABASE_URL 未设置');
  process.exit(2);
}

const db = new DatabaseSync(dbPath);
db.exec('PRAGMA busy_timeout=5000');
const hash = (v) => createHash('sha256').update(v).digest('hex');
const uuid = () => randomBytes(16).toString('hex');

const account = db.prepare('SELECT id,email FROM accounts WHERE disabled=0 ORDER BY rowid LIMIT 1').get();
if (!account) {
  console.error('没有可用账号');
  process.exit(2);
}

const id = uuid();
const code = randomInt(0, 100000000).toString().padStart(8, '0');
const now = Date.now();
const expires = now + DEVICE_CODE_TTL;

db.exec('BEGIN IMMEDIATE');
try {
  db.prepare('INSERT INTO device_codes VALUES(?,?,?,?,?,0,null,0,0,?)')
    .run(id, account.id, hash(code), label, expires, now);
  db.prepare('INSERT INTO audit(at,account_id,action,result,subject) VALUES(?,?,?,?,?)')
    .run(now, account.id, 'device-code-created', 'ok', id);
  db.exec('COMMIT');
} catch (e) {
  db.exec('ROLLBACK');
  console.error('写入失败：' + e.message);
  process.exit(1);
}

console.log(JSON.stringify({
  code,
  label,
  account: account.email,
  expiresAt: new Date(expires).toISOString(),
  ttlMinutes: DEVICE_CODE_TTL / 60000,
}));
