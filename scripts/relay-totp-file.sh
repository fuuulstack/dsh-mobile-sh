#!/bin/bash
# 在服务端用中继自己的密钥与时钟生成 6 位动态码，写入指定文件（600），不回显任何机密。
# 用途：手机端登录需要 TOTP 时，避免本机时钟/密钥抄写带来的偏差。
# 用法：bash relay-totp-file.sh [输出路径]
set -euo pipefail
out="${1:-/tmp/relay-totp.txt}"
set -a; . /etc/dsh-mobile-sh.env; set +a
umask 077
cat > /tmp/relay-totp-gen.cjs <<'EOF'
const { DatabaseSync } = require('node:sqlite');
const { readFileSync, writeFileSync } = require('node:fs');
const { createDecipheriv, createHmac } = require('node:crypto');

const out = process.argv[2];
const key = readFileSync(process.env.RELAY_SECRET_KEY_FILE);
const db = new DatabaseSync(process.env.DATABASE_URL, { readOnly: true });
const prefix = (process.env.RELAY_ACCOUNT_EMAIL ?? 'admin').slice(0, 4);
const row = db.prepare('SELECT totp FROM accounts WHERE substr(email,1,4)=?').get(prefix);
if (!row || !row.totp) { console.error('账号未配置 TOTP'); process.exit(2); }

// 与 packages/relay/src/crypto.ts 的 encrypt/decrypt 同格式：base64url(iv | tag | 密文)
const b = Buffer.from(row.totp, 'base64url');
const d = createDecipheriv('aes-256-gcm', key, b.subarray(0, 12));
d.setAuthTag(b.subarray(12, 28));
const secret = Buffer.concat([d.update(b.subarray(28)), d.final()]).toString('utf8');

const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
let bits = 0, v = 0; const bytes = [];
for (const c of secret.toUpperCase()) {
  const n = alphabet.indexOf(c);
  if (n < 0) continue;
  v = (v << 5) | n; bits += 5;
  if (bits >= 8) { bits -= 8; bytes.push((v >>> bits) & 0xff); }
}
const step = Math.floor(Date.now() / 30000);
const buf = Buffer.alloc(8); buf.writeBigUInt64BE(BigInt(step));
const h = createHmac('sha1', Buffer.from(bytes)).update(buf).digest();
const o = h[19] & 15;
const code = String((h.readUInt32BE(o) & 0x7fffffff) % 1000000).padStart(6, '0');
writeFileSync(out, code, { mode: 0o600 });
console.log('totp written to ' + out + ' step=' + step);
EOF
node /tmp/relay-totp-gen.cjs "$out"
rm -f /tmp/relay-totp-gen.cjs
