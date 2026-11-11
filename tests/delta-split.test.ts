/**
 * 流式增量必须按 type 分成正文与思考两条通道。
 *
 * 2026-10-30 真机反馈：手机上「思考过程被当成正文输出在聊天页了，而没有进入折叠栏」，
 * 同时「折叠栏右侧的思考流式预览没有了」。两句话是同一个 bug 的两面——
 * `deltaText` 原先无条件先看 `chunk.text`，`reasoning-delta` 的思考于是进了正文缓冲，
 * 折叠行那条通道自然一直空着。
 *
 * 这两种 chunk **都带 `text` 字段**，区别只在 `type`，所以判据只能是 type。
 */
import assert from 'node:assert/strict';
import test from 'node:test';

import { deltaReasoning, deltaText } from '../packages/plugin/src/collector.js';

test('a reasoning chunk never leaks into the answer text', () => {
  const chunk = { type: 'reasoning-delta', index: 0, text: '先看看用户这句话想要什么。' };
  assert.equal(deltaText(chunk), '', '思考不能进正文缓冲');
  assert.equal(deltaReasoning(chunk), '先看看用户这句话想要什么。', '思考要走自己的通道');
});

test('a text chunk never leaks into the reasoning preview', () => {
  const chunk = { type: 'text-delta', index: 1, text: '答案是 4。' };
  assert.equal(deltaText(chunk), '答案是 4。', '正文要走正文通道');
  assert.equal(deltaReasoning(chunk), '', '正文不能进折叠行的思考预览');
});

test('a thinking-delta alias is treated as reasoning', () => {
  // 部分 provider 用 `thinking-delta` 命名同一件事；漏掉它等于又把思考倒进正文。
  const chunk = { type: 'thinking-delta', text: '嗯……' };
  assert.equal(deltaText(chunk), '');
  assert.equal(deltaReasoning(chunk), '嗯……');
});

test('tool deltas go to neither channel', () => {
  // 工具参数流进「思考」那行会在折叠行里闪原始 JSON。
  for (const type of ['tool-delta', 'tool-call-delta']) {
    const chunk = { type, text: '{"command":"npm test"}' };
    assert.equal(deltaText(chunk), '', `${type} 不该进正文`);
    assert.equal(deltaReasoning(chunk), '', `${type} 不该进思考`);
  }
});

test('a reasoning chunk that only carries delta is still read', () => {
  // 有的宿主把增量放在 `delta` 而不是 `text`。
  assert.equal(deltaReasoning({ type: 'reasoning-delta', delta: '接着想' }), '接着想');
});

test('an untyped reasoning part still reaches the reasoning channel', () => {
  // Anthropic 原生形态：没有 type，靠 content part 的 type 认。
  const chunk = { content: [{ type: 'reasoning', text: '原生思考' }] };
  assert.equal(deltaText(chunk), '', '不能因为没写 type 就当成正文');
  assert.equal(deltaReasoning(chunk), '原生思考');
});

test('a plain text chunk with no type stays text', () => {
  // 向后兼容：老宿主只发 `{text}`，那种形态确实是正文。
  assert.equal(deltaText({ text: '普通正文' }), '普通正文');
  assert.equal(deltaReasoning({ text: '普通正文' }), '');
});
