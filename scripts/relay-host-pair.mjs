// 在生产中继上为一台主机生成「设备配对码」。
//
// 与网页 /pair/create 同算法：8 位数字、5 分钟有效、一次性；消费后给设备写 grant。
// 差别只是不走网页（网页要先登录账号）。
//
// 用法（在 relay-host 上）：
//   sudo bash -c 'set -a; . /etc/dsh-mobile-sh.env; set +a; node gen-host-pair.mjs SECOND-HOST'
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes, randomInt } from 'node:crypto';

const PAIR_TTL = 5 * 60 * 1000;
const wanted = process.argv[2];
if (!wanted) {
  console.error('用法：node gen-host-pair.mjs <主机别名或 id 前缀>');
  process.exit(2);
}
const dbPath = process.env.DATABASE_URL;
if (!dbPath) {
  console.error('DATABASE_URL 未设置');
  process.exit(2);
}

const db = new DatabaseSync(dbPath);
db.exec('PRAGMA busy_timeout=5000');
const hash = (v) => createHash('sha256').update(v).digest('hex');
const uuid = () => randomBytes(16).toString('hex');
const secret = (bytes = 32) => randomBytes(bytes).toString('base64url');

const hosts = db.prepare('SELECT id,alias,account_id FROM hosts WHERE revoked=0').all();
const hit = hosts.filter((h) => h.alias === wanted || h.id.startsWith(wanted));
if (hit.length !== 1) {
  console.error(hit.length === 0 ? '找不到这台主机：' + wanted : '别名不唯一，请用 id 前缀');
  console.error('现有主机：' + hosts.map((h) => `${h.alias}(${h.id.slice(0, 8)})`).join(', '));
  process.exit(1);
}
const host = hit[0];

const id = uuid();
const code = randomInt(0, 100000000).toString().padStart(8, '0');
const now = Date.now();
const expires = now + PAIR_TTL;

db.exec('BEGIN IMMEDIATE');
try {
  // token_hash 是网页二维码里那条一次性链接用的，这里用不到，但仍要写一个合法值。
  db.prepare('INSERT INTO pairs VALUES(?,?,?,?,?,0,?,0)')
    .run(id, host.account_id, host.id, hash(code), hash(secret(16)), expires);
  db.prepare('INSERT INTO audit(at,account_id,action,result,subject) VALUES(?,?,?,?,?)')
    .run(now, host.account_id, 'pair-created', 'ok', host.id);
  db.exec('COMMIT');
} catch (e) {
  db.exec('ROLLBACK');
  console.error('写入失败：' + e.message);
  process.exit(1);
}

console.log(JSON.stringify({
  host: host.alias,
  hostId: host.id,
  code,
  expiresAt: new Date(expires).toISOString(),
  ttlMinutes: PAIR_TTL / 60000,
}));
