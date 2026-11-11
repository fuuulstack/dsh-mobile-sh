/**
 * 列出某台 Host 的会话，并标出会被 `isInternalSession` 过滤掉的内部会话。
 *
 * <p>用途：在**真实数据**上验证插件端的内部会话过滤（`dsh-mnemon:delegated` 等），
 * 而不是只看单测的构造样例。这里直接打未经插件过滤的原始 `session/list` RPC，
 * 所以打印出来的是过滤前的全集，能与手机列表逐条对照。
 *
 * <p>token 取自 `artifacts/relay-client.json`（`node scripts/relay-client.mjs login` 写的本地状态）。
 *
 * <p>用法：
 * {@code node node_modules/tsx/dist/cli.mjs scripts/list-internal-sessions.ts <hostId> [relayOrigin]}
 */
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { isInternalSession } from '../packages/core/src/index.js';

/** `session/list` 条目里本脚本要看的字段。 */
interface SessionItem {
  title?: unknown;
  parentSessionId?: unknown;
  origin?: unknown;
  projections?: { title?: unknown; values?: { title?: unknown } };
}

const [hostId, originArg] = process.argv.slice(2);
if (!hostId) {
  console.error('用法：node node_modules/tsx/dist/cli.mjs scripts/list-internal-sessions.ts <hostId> [relayOrigin]');
  process.exit(2);
}

const origin = (originArg ?? process.env.RELAY_ORIGIN ?? 'https://relay.example.test').replace(/\/$/, '');
const { token } = JSON.parse(readFileSync('artifacts/relay-client.json', 'utf8')) as { token: string };

const response = await fetch(`${origin}/native/v2/hosts/${hostId}/rpc/list`, {
  method: 'POST',
  headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
  body: JSON.stringify({
    type: 'client-request',
    rpcId: randomUUID(),
    method: 'session/list',
    payload: { args: { _request: {} } },
  }),
});

const body = await response.json() as { result?: { value?: { items?: unknown[] } }; error?: unknown };
if (body.error != null) {
  console.error(`RPC 失败：${JSON.stringify(body.error)}`);
  process.exit(1);
}
if (!response.ok) {
  console.error(`HTTP ${response.status}：确认 token 未过期（重新 login）与 hostId 是否正确。`);
  process.exit(1);
}

const items = (body.result?.value?.items ?? []) as SessionItem[];
const titleOf = (item: SessionItem): string => {
  const title = item.projections?.values?.title ?? item.projections?.title ?? item.title;
  return typeof title === 'string' ? title : '';
};

const internal = items.filter((item) => isInternalSession(item));
const kept = items.filter((item) => !isInternalSession(item));

console.log(`会话总数 ${items.length}：保留 ${kept.length}，插件会过滤 ${internal.length}`);
console.log(`（origin=${origin} host=${hostId}）`);
for (const item of internal) {
  console.log(`  [滤] ${JSON.stringify(titleOf(item))} parent=${String(item.parentSessionId ?? '-')} origin=${String(item.origin ?? '-')}`);
}
for (const item of kept) console.log(`  [留] ${JSON.stringify(titleOf(item))}`);
