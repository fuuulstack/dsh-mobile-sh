import test from 'node:test';
import assert from 'node:assert/strict';
import { Store, type Row } from '../packages/relay/src/store.js';

/**
 * 扫码登录、设备复用、归档清理这几条查询曾经全是全表扫。
 *
 * <p>为什么值得单独钉一个测试：这些表的行数**只增不减**（每台手机每次登录写一行
 * `device_codes`、每次扫码写一行 `pairs`，prune 一小时才跑一次），所以「小库时看不出
 * 代价」正好是最容易蒙混过去的形态——本地 `:memory:` 库永远只有几十行，谁都不会注意
 * 到少了索引；等到生产上跑了一年才发现扫码登录要 200ms，那时再改就带着线上验证的
 * 风险。这里用 `EXPLAIN QUERY PLAN` 把「不许全表扫」写死。
 *
 * <p>判据是 `SCAN <表名>` 这半句：`SEARCH ... USING INDEX ...` 是通过索引定位，
 * `SCAN` 才是一行行读。唯一允许例外的是 `devices` 的整体回收（它本来就是「找出所有
 * 该删的设备」，全扫是对的），见最后一条断言。
 */
const db = new Store(':memory:', Buffer.alloc(32, 9));
test.after(() => db.close());

function plan(sql: string, ...args: unknown[]): string {
  return (db.all(`EXPLAIN QUERY PLAN ${sql}`, ...(args as never[])) as Array<{ detail: string }>)
    .map(r => r.detail).join(' | ');
}

function assertIndexed(sql: string, ...args: unknown[]): void {
  const p = plan(sql, ...args);
  assert.doesNotMatch(p, /\bSCAN\b/, `这条查询仍在全表扫：${sql}\n${p}`);
  assert.match(p, /USING (COVERING )?INDEX|USING PRIMARY KEY/, `这条查询没有走上任何索引：${sql}\n${p}`);
}

test('登录与配对路径上的每条热点查询都走索引', () => {
  // 同一台手机重复登录必须复用同一条 devices 行（grants 挂在 device_id 上，
  // 每兑换一次就插一行会让这台手机之前配好的主机全部掉授权）。
  assertIndexed('SELECT id FROM devices WHERE account_id=? AND label=? AND revoked=0', 'a', 'l');
  // 设备数上限检查：每次注册主机、每次渲染主机列表。
  assertIndexed('SELECT count(*) n FROM hosts WHERE account_id=? AND revoked=0', 'a');
  // 吊销设备 → 连带吊销授权；prune 的相关子查询也走这一条。
  assertIndexed('UPDATE grants SET revoked=1 WHERE device_id=?', 'd');
  // 配对码是**猜解路径**：限流挡的是次数，扫描成本挡不了，所以必须走索引。
  // 两个列各一条索引 + SQLite 的 OR-optimization（复合索引的前缀列是 account_id，
  // OR 的另一支用不上它，所以不能合成一条）。
  const pairPlan = plan('SELECT * FROM pairs WHERE account_id=? AND (code_hash=? OR token_hash=?) AND expires>? AND used=0 AND attempts<5', 'a', 'x', 'y', 0);
  assert.match(pairPlan, /MULTI-INDEX OR/, pairPlan);
  assert.match(pairPlan, /pairs_code/, pairPlan);
  assert.match(pairPlan, /pairs_token/, pairPlan);
  // 手机登录唯一入口：只认 code_hash，不经过账号。
  assertIndexed('SELECT * FROM device_codes WHERE code_hash=? AND used=0 AND revoked=0 AND expires>?', 'h', 0);
  assertIndexed('SELECT * FROM device_codes WHERE account_id=? ORDER BY created_at DESC LIMIT 50', 'a');
});

test('归档清理按时间列走索引，不会每小时把库读一遍', () => {
  assertIndexed('DELETE FROM timeline_blobs WHERE created_at<?', 0);
  assertIndexed('DELETE FROM timeline_blobs WHERE host_id=? AND session_id=? AND seq<?', 'h', 's', 0);
  assertIndexed('DELETE FROM archive_batches WHERE created_at<?', 0);
  assertIndexed('DELETE FROM session_meta WHERE updated_at<?', 0);
  assertIndexed('DELETE FROM session_digest WHERE updated_at<?', 0);
  assertIndexed('DELETE FROM timeline_records WHERE updated_at<?', 0);
  // 过期行清理的循环（invitations/receipts/pairs/requests/tickets/rate_limits/device_codes）。
  for (const table of ['invitations', 'receipts', 'pairs', 'requests', 'tickets', 'rate_limits', 'device_codes']) {
    assertIndexed(`DELETE FROM ${table} WHERE expires<?`, 0);
  }
});

test('timeline 的两条主查询不会退化成全扫', () => {
  // 会话列表的摘要：生产 49 749 行时这条曾经 1082ms。
  const summary = plan('SELECT session_id,MAX(updated_at) updatedAt FROM timeline_records WHERE host_id=? GROUP BY session_id ORDER BY updatedAt DESC', 'h');
  assert.match(summary, /COVERING INDEX timeline_coverage/, summary);
  assert.doesNotMatch(summary, /SCAN/, summary);
  // 尾部seq：走主键前缀。
  assertIndexed('SELECT MAX(seq) m FROM timeline_records WHERE host_id=? AND session_id=?', 'h', 's');
});

test('设备整体回收是全表扫，但它的两个相关子查询都走索引', () => {
  const p = plan(`UPDATE devices SET revoked=1 WHERE revoked=0`
    + ` AND NOT EXISTS(SELECT 1 FROM sessions WHERE device_id=devices.id AND revoked=0 AND expires>?)`
    + ` AND NOT EXISTS(SELECT 1 FROM grants WHERE device_id=devices.id AND revoked=0)`, 0);
  // 外层扫 devices 是对的：它要找的正是「所有没有任何活会话也没有授权的设备」。
  assert.match(p, /SCAN devices/, p);
  // 真正会随行数爆炸的是这两个子查询——每个设备一行，扫一次全表就是 O(n²)。
  assert.match(p, /SEARCH sessions USING INDEX session_device/, p);
  assert.match(p, /SEARCH grants USING INDEX grants_device/, p);
});

test('索引都是幂等建表语句，重复打开库不会报错', () => {
  const again = new Store(':memory:', Buffer.alloc(32, 9));
  try {
    const names = (again.all("SELECT name FROM sqlite_master WHERE type='index'") as Row[]).map(r => r.name);
    for (const expected of ['devices_account_label', 'hosts_account', 'grants_device', 'pairs_code', 'pairs_token',
      'device_codes_code', 'device_codes_account', 'timeline_blobs_created', 'archive_batches_created']) {
      assert.ok(names.includes(expected), `缺少索引 ${expected}`);
    }
  } finally { again.close(); }
});
