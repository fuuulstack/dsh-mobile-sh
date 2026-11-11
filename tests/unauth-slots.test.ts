import test from 'node:test';
import assert from 'node:assert/strict';
import { UnauthSlots } from '../packages/relay/src/proxy.js';

/**
 * 认证前的每 IP 额度表**必须有界**。
 *
 * <p>这张表的键是公网 IP，来源无穷。早期实现只在建隧道时 `set`、从来不删，
 * 扫描器换个地址就来一条——768MB 的机器上跑几天就是几十万条常驻。
 */

test('an IP is forgotten as soon as its last unauthenticated tunnel closes', () => {
  const slots = new UnauthSlots(4);
  const a = slots.acquire('203.0.113.7');
  const b = slots.acquire('203.0.113.7');
  assert.equal(slots.size(), 1, '同一 IP 的多条连接共用一项');
  a();
  assert.equal(slots.size(), 1, '还有一条连着就不能忘');
  b();
  assert.equal(slots.size(), 0, '最后一条断开后必须把这一项删掉，否则表跟着公网地址数一起长');
});

test('release is idempotent and the per-IP limit still bites', () => {
  const slots = new UnauthSlots(2);
  const first = slots.acquire('198.51.100.9');
  const second = slots.acquire('198.51.100.9');
  assert.throws(() => slots.acquire('198.51.100.9'), /ip-unauth-capacity/, '第三条必须被拒');
  // 被拒的那次不能留下一条 open=0 的空壳：拒绝路径也是「会增长的路径」。
  assert.equal(slots.size(), 1);
  first();
  first(); // 幂等：重复释放不能把别人的额度还掉
  assert.equal(slots.size(), 1, '重复释放不能让还在用的那一条被删');
  second();
  assert.equal(slots.size(), 0);
});

test('a rejected acquisition never leaks an entry for a brand-new IP', () => {
  const slots = new UnauthSlots(1);
  slots.acquire('192.0.2.1');
  // 换一个全新的 IP 把它自己的额度用光：第二条被拒，表里不能留下它。
  const ok = slots.acquire('192.0.2.2');
  assert.throws(() => slots.acquire('192.0.2.2'), /ip-unauth-capacity/);
  assert.equal(slots.size(), 2, '被拒的 IP 与已占用的 IP 各一条，不多不少');
  ok();
  assert.equal(slots.size(), 1);
});
