// 把「本机 DSH」注册成中继上的一台主机，并把身份写进 ~/.dsh/.credentials.yaml。
//
// 为什么需要它：插件的注册流程要一次性的 DSH_MOBILE_SH_ENROLL_INVITATION 环境变量，
// 而桌面版 DSH 的环境由 GUI 启动器决定、改不了。于是先在外部把主机注册完（拿到 hostId），
// 再把 identity 写进凭据文件；插件下次启动时读到 hostId 就直接连隧道，不再需要邀请。
//
// 用法：
//   RELAY_ORIGIN=https://relay.example.test node scripts/enroll-host.mjs <alias>
// 邀请来源：artifacts/relay-enrollment-invitation.txt（scripts/relay-admin.mjs invite 写入）。
// 只打印 hostId / hostPublicId / operationId，绝不打印 hostTunnelToken。

import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync, copyFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const origin = (process.env.RELAY_ORIGIN ?? 'https://relay.example.test').replace(/\/+$/, '');
const alias = process.argv[2] ?? process.env.COMPUTERNAME ?? 'DSH 主机';
const invitationPath = process.env.RELAY_INVITATION_FILE ?? join('artifacts', 'relay-enrollment-invitation.txt');

if (!existsSync(invitationPath)) {
  console.error(`失败：找不到邀请文件 ${invitationPath}（先跑 node scripts/relay-admin.mjs invite）`);
  process.exit(1);
}
const invitation = readFileSync(invitationPath, 'utf8').trim();
if (!/^[A-Za-z0-9_-]{43}$/.test(invitation)) {
  console.error(`失败：邀请格式不对（长度 ${invitation.length}）`);
  process.exit(1);
}

const identity = {
  hostPublicId: randomBytes(16).toString('hex'),
  hostTunnelToken: randomBytes(32).toString('base64url'),
  operationId: randomBytes(16).toString('hex'),
  relayOrigin: origin,
};

const response = await fetch(`${origin}/control/enroll`, {
  method: 'POST',
  redirect: 'error',
  signal: AbortSignal.timeout(20000),
  headers: { authorization: `Bearer ${invitation}`, 'content-type': 'application/json' },
  body: JSON.stringify({
    operationId: identity.operationId,
    hostPublicId: identity.hostPublicId,
    hostTunnelToken: identity.hostTunnelToken,
    alias,
  }),
});
const text = await response.text();
if (!response.ok) {
  console.error(`失败：注册被拒（HTTP ${response.status}）${text.slice(0, 200)}`);
  process.exit(1);
}
const { hostId } = JSON.parse(text);
if (!/^[a-f0-9]{32}$/.test(hostId ?? '')) {
  console.error(`失败：返回的 hostId 不合法：${text.slice(0, 200)}`);
  process.exit(1);
}

// 写入凭据文件：整块替换 mobile-sh/tunnel 记录，其余行原样保留。
const credentialsPath = join(homedir(), '.dsh', '.credentials.yaml');
const original = readFileSync(credentialsPath, 'utf8');
const eol = original.includes('\r\n') ? '\r\n' : '\n';
const lines = original.split(/\r?\n/);
const start = lines.findIndex((line) => /^\s{2}mobile-sh\/tunnel:\s*$/.test(line));
if (start < 0) {
  console.error('失败：凭据文件里没有 mobile-sh/tunnel 记录（拒绝对文件做未知结构改写）');
  process.exit(1);
}
let end = start + 1;
while (end < lines.length && !/^\s{0,2}\S/.test(lines[end])) end++;
const block = [
  '  mobile-sh/tunnel:',
  '    kind: grant',
  '    payload:',
  `      hostPublicId: ${identity.hostPublicId}`,
  `      hostTunnelToken: ${identity.hostTunnelToken}`,
  `      operationId: ${identity.operationId}`,
  `      relayOrigin: ${origin}`,
  `      hostId: ${hostId}`,
];
const backupPath = `${credentialsPath}.bak-before-mobile-relay`;
copyFileSync(credentialsPath, backupPath);
writeFileSync(credentialsPath, [...lines.slice(0, start), ...block, ...lines.slice(end)].join(eol));
console.log(`已注册主机：hostId=${hostId} alias=${alias} relayOrigin=${origin}`);
console.log(`  hostPublicId=${identity.hostPublicId}`);
console.log(`  operationId=${identity.operationId}`);
console.log(`凭据已写入 ${credentialsPath}（备份 ${backupPath}）`);
console.log('注意：修改 DSH 正在使用的凭据文件后必须重启 DSH 进程，插件才会加载新身份。');
