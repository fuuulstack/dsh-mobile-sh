import test from 'node:test';
import assert from 'node:assert/strict';
import { chunkItemBytes, chunkSnapshotText, filterItemBytes, filterItemText, filterRpcBody, parseOmit, parseSince } from '../packages/core/src/index.js';

const all = { reasoning: true, tool: true, context: true };
const item = (value: unknown) => JSON.stringify({ type: 'item', streamId: 'chat', value });

test('parse the phone preference header and ignore empty or unknown values', () => {
  assert.deepEqual(parseOmit('reasoning,tool'), { reasoning: true, tool: true, context: false });
  assert.deepEqual(parseOmit('CONTEXT'), { reasoning: false, tool: false, context: true });
  assert.equal(parseOmit(''), undefined);
  assert.equal(parseOmit(undefined), undefined);
  assert.equal(parseOmit('nonsense'), undefined);
});

test('reasoning chunks survive as frames with no text so revisions stay contiguous', () => {
  const frame = item({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'a:1', revision: 5, index: 2, time: 1, chunk: { type: 'reasoning-delta', index: 0, text: 'thinking hard about it' } } });
  const out = JSON.parse(filterItemText(frame, all));
  assert.equal(out.value.frame.revision, 5, 'revision must be preserved');
  assert.equal(out.value.frame.index, 2, 'chunk index must be preserved');
  assert.equal(out.value.frame.chunk.type, 'reasoning-delta');
  assert.equal(out.value.frame.chunk.text, '', 'only the payload is emptied');
});

test('file edits keep their tool call identity but lose the diff body', () => {
  const value = {
    type: 'event',
    event: {
      type: 'assistant/message', seq: 30,
      data: {
        message: { role: 'assistant', content: [
          { type: 'reasoning', text: 'x'.repeat(400) },
          { type: 'tool-call', id: 'call_1', name: 'edit', arguments: JSON.stringify({ path: '/a/b.ts', old: 'y'.repeat(900) }) },
          { type: 'text', text: 'done' },
        ] },
        stream: [{ type: 'reasoning-chunks', texts: ['z'.repeat(500)] }],
        usage: { inputTokens: 1 },
      },
    },
  };
  const out = JSON.parse(filterItemText(item(value), all));
  const content = out.value.event.data.message.content;
  assert.deepEqual(content.map((p: { type: string }) => p.type), ['tool-call', 'text'], 'reasoning removed');
  assert.equal(content[0].id, 'call_1');
  assert.equal(content[0].name, 'edit');
  assert.equal(typeof JSON.parse(content[0].arguments).omitted, 'number', 'arguments replaced by a size note');
  assert.equal(content[1].text, 'done', 'assistant text is untouched');
  assert.equal(out.value.event.data.stream, undefined, 'the replayed stream copy is dropped');
  assert.deepEqual(out.value.event.data.usage, { inputTokens: 1 }, 'unrelated fields are preserved');
});

test('harness plumbing and non-user sources are emptied, user text is kept', () => {
  const header = item({ type: 'event', event: { type: 'request/header', seq: 1, data: { header: { config: 'c'.repeat(1000) } } } });
  // v2: nothing is classified as "context noise" any more. An event type this file
  // has never heard of arrives with its payload intact — the phone's projector, not
  // the tunnel, decides what to render. (v1 blanked this to `{omitted:'request/header'}`.)
  assert.equal(JSON.parse(filterItemText(header, all)).value.event.data.header.config, 'c'.repeat(1000));
  const injected = item({ type: 'event', event: { type: 'user/message', seq: 2, data: { source: { kind: 'skill-catalog' }, content: [{ type: 'text', text: 'k'.repeat(500) }] } } });
  assert.deepEqual(JSON.parse(filterItemText(injected, all)).value.event.data.content, []);
  const typed = item({ type: 'event', event: { type: 'user/message', seq: 3, data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'hello' }] } } });
  assert.deepEqual(JSON.parse(filterItemText(typed, all)).value.event.data.content, [{ type: 'text', text: 'hello' }]);
  const step = item({ type: 'event', event: { type: 'step/start', seq: 4, time: 12, data: { turn: 1, step: 2, detail: 'x'.repeat(400) } } });
  assert.equal(JSON.parse(filterItemText(step, all)).value.event.data.detail, 'x'.repeat(400), 'process bookkeeping keeps its payload');
  assert.equal(filterItemText(step, { reasoning: true, tool: true, context: false }), step);
});

test('no event type is suppressed: the allow-list is gone and unknown types pass through', () => {
  // 真机踩过的坑（v1 的三种死法，全部源自同一个 allow-list）：tool/call、tool/result、
  // turn/* 曾被当成 context 噪声整块清空 → 手机端渲染出标题「工具调用」的空行；
  // todo/write 被抹成 stub → 任务胶囊永远空；agent/inbox/spliced 被抹 → 排队卡片从不出现。
  // 每次修复都是往名单里再加一个名字，而名单按构造不可能补全：宿主随时可能发来新事件类型，
  // 只有手机的投影层知道哪些有用。v2 删掉机制本身，于是下面每一条都必须原样抵达。
  const contextOnly = { reasoning: false, tool: false, context: true };
  const survives = (type: string, data: unknown, seq: number) => {
    const frame = item({ type: 'event', event: { type, seq, time: 1, data } });
    assert.deepEqual(JSON.parse(filterItemText(frame, contextOnly)).value.event.data, data, type + ' must survive');
  };
  survives('tool/call', { turn: 1, callId: 'call_abc', name: 'pwsh', arguments: '{"command":"whoami"}' }, 10);
  survives('tool/result', { turn: 1, message: { role: 'tool', source: { kind: 'tool', callId: 'call_abc' }, content: [{ type: 'text', text: 'hello-hi' }] } }, 11);
  survives('turn/start', { turn: 7 }, 12);
  survives('todo/write', { sessionId: 's1', todos: [{ content: '甲', status: 'completed' }, { content: '乙', status: 'pending' }] }, 15);
  survives('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [{ id: 'm1', role: 'user', content: [{ type: 'text', text: '继续' }] }] }, 16);
  // 以前「真正的噪声」这一档现在也不存在了：未知类型照样带着 payload 走。
  survives('job/registered', { label: 'x'.repeat(400) }, 13);
  survives('compaction/summary', { summary: 'y'.repeat(900) }, 14);
  survives('session/title', { title: 'Hello' }, 17);
  survives('brand/new/event-from-a-later-host', { anything: [1, 2, 3] }, 18);

  // tool 是另一个独立旋钮：关掉它只压工具载荷，name/callId 照样在，行不会变空。
  const call = item({ type: 'event', event: { type: 'tool/call', seq: 10, time: 1, data: { turn: 1, callId: 'call_abc', name: 'pwsh', arguments: '{"command":"whoami"}' } } });
  const slim = JSON.parse(filterItemText(call, { reasoning: false, tool: true, context: true })).value.event;
  assert.equal(slim.data.name, 'pwsh');
  assert.equal(slim.data.callId, 'call_abc');
  assert.equal(typeof JSON.parse(slim.data.arguments).omitted, 'number');
});

test('the since header only accepts a non-negative safe integer', () => {
  assert.equal(parseSince('42'), 42);
  assert.equal(parseSince(['7', '9']), 7);
  assert.equal(parseSince('0'), 0);
  assert.equal(parseSince(undefined), undefined);
  assert.equal(parseSince(''), undefined, 'empty string parses as 0 otherwise');
  assert.equal(parseSince('-1'), undefined);
  assert.equal(parseSince('1.5'), undefined);
  assert.equal(parseSince('abc'), undefined);
});

test('since trims the opening replay to the tail the phone does not have', () => {
  const value = {
    type: 'snapshot', hasMore: true,
    records: [3, 4, 5, 6].map(seq => ({ event: { type: 'assistant/message', seq, data: { text: 'm' + seq } } })),
    assistantStream: { revision: 1 },
  };
  const trimmed = JSON.parse(filterItemText(item(value), all, 4));
  assert.deepEqual(trimmed.value.records.map((r: any) => r.event.seq), [5, 6]);
  assert.equal(trimmed.value.hasMore, true, 'pagination still comes from the host page');
  // No header, or a header past the replay: nothing is dropped.
  assert.deepEqual(JSON.parse(filterItemText(item(value), all)).value.records.map((r: any) => r.event.seq), [3, 4, 5, 6]);
  assert.deepEqual(JSON.parse(filterItemText(item(value), all, 99)).value.records, []);
  // Records without a numeric seq are never used as the boundary.
  const nod = { type: 'snapshot', records: [{ event: { type: 'tool/call', data: { name: 'x' } } }] };
  assert.equal(JSON.parse(filterItemText(item(nod), all, 9)).value.records.length, 1);
});

test('snapshot records and the live attempt baseline are filtered the same way', () => {
  const value = {
    type: 'snapshot', hasMore: false,
    records: [{ event: { type: 'assistant/message', seq: 9, data: { message: { content: [{ type: 'reasoning', text: 'r'.repeat(200) }, { type: 'text', text: 'ok' }] } } } }],
    assistantStream: { revision: 3, activeAttempt: { attemptId: 'a:2', nextIndex: 2, stream: [{ time: 1, chunk: { type: 'reasoning-delta', index: 0, text: 'q'.repeat(300) } }, { time: 2, chunk: { type: 'text-delta', index: 1, text: 'hi' } }] } },
  };
  const out = JSON.parse(filterItemText(item(value), all));
  assert.deepEqual(out.value.records[0].event.data.message.content, [{ type: 'text', text: 'ok' }]);
  const stream = out.value.assistantStream.activeAttempt.stream;
  assert.equal(stream[0].chunk.text, '', 'baseline reasoning is emptied, not removed');
  assert.equal(stream[1].chunk.text, 'hi', 'baseline text survives');
  assert.equal(out.value.assistantStream.revision, 3);
});

test('the envelope keeps its exact key set and untouched frames are returned as-is', () => {
  // A frame with nothing to filter is handed back byte-identical, envelope included.
  const odd = { type: 'item', streamId: 'chat', value: { type: 'event', event: { type: 'session/title', seq: 4, data: { title: 'Hello' } } } };
  assert.equal(filterItemText(JSON.stringify(odd), all), JSON.stringify(odd));
  // A panel event whose payload has nothing matching the omit is left byte-identical.
  const plain = JSON.stringify({ type: 'item', streamId: 'chat', value: { type: 'event', event: { type: 'assistant/message', seq: 8, data: { message: { content: [{ type: 'text', text: 'ok' }] } } } } });
  assert.equal(filterItemText(plain, all), plain, 'a frame with nothing to drop is not re-serialized');
  const rewritten = JSON.parse(filterItemText(item({ type: 'event', event: { type: 'system/message', seq: 5, data: { message: { content: [{ type: 'text', text: 's'.repeat(100) }] } } } }), all));
  assert.deepEqual(Object.keys(rewritten).sort(), ['streamId', 'type', 'value']);
});

test('byte form leaves binary frames alone and shrinks the payload', () => {
  const frame = item({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'a:1', revision: 1, index: 0, chunk: { type: 'reasoning-delta', index: 0, text: 'x'.repeat(2000) } } });
  const binary = Buffer.from([1, 2, 3]);
  assert.equal(filterItemBytes(binary, true, all), binary);
  const shrunk = filterItemBytes(Buffer.from(frame), false, all);
  assert.ok(shrunk.length < Buffer.byteLength(frame) / 2, 'payload shrinks');
  assert.doesNotThrow(() => Buffer.from(shrunk).toString('utf8').normalize(), 'still valid utf8');
});

test('malformed frames pass through instead of breaking the tunnel', () => {
  for (const text of ['', 'not json', '{}', JSON.stringify({ type: 'end', streamId: 'chat' }), JSON.stringify({ type: 'item', streamId: 'chat' })])
    assert.equal(filterItemText(text, all), text);
});

test('a history page over plain http is filtered like a snapshot record', () => {
  const page = (records: unknown[]) => Buffer.from(JSON.stringify({
    type: 'server-response', rpcId: 'p1',
    result: { ok: true, value: { cursor: 40, hasMore: true, records } },
  }));
  const record = (seq: number, type: string, data: unknown) => ({ event: { type, seq, time: 1, data } });
  const body = page([
    record(1, 'request/header', { header: { config: 'c'.repeat(4000) } }),
    record(2, 'assistant/message', { message: { content: [{ type: 'reasoning', text: 'r'.repeat(2000) }, { type: 'text', text: 'kept' }] } }),
  ]);
  const out = JSON.parse(filterRpcBody(body, all, '/api/session/page').toString('utf8'));
  assert.equal(out.type, 'server-response', 'the rpc envelope is preserved');
  assert.equal(out.result.value.cursor, 40, 'the cursor survives so paging can continue');
  assert.equal(out.result.value.records[1].event.data.message.content[0].text, 'kept');
  // v2: only the content knobs shrink a page. The reasoning part is gone, the
  // `request/header` event is not — an unknown type keeps its payload (v1 blanked it
  // to `{omitted}` and the 2×-shrink assertion here encoded that behaviour).
  assert.deepEqual(out.result.value.records[1].event.data.message.content, [{ type: 'text', text: 'kept' }]);
  assert.equal(out.result.value.records[0].event.data.header.config.length, 4000, 'unknown events keep their payload');
  assert.ok(filterRpcBody(body, all, '/api/session/page').length < body.length - 1800, 'dropping the reasoning part still shrinks the page');
  // Anything that is not a successful records page is handed back untouched.
  const control = Buffer.from(JSON.stringify({ type: 'client-request', rpcId: 'p1', method: 'session/page' }));
  assert.equal(filterRpcBody(control, all, '/api/session/page'), control);
  const failed = Buffer.from(JSON.stringify({ type: 'server-response', rpcId: 'p1', result: { ok: false, error: { code: 'x' } } }));
  assert.equal(filterRpcBody(failed, all, '/api/session/page'), failed);
});

test('a session list keeps only the fields the phone renders', () => {
  const list = (items: unknown[]) => Buffer.from(JSON.stringify({
    type: 'server-response', rpcId: 'p2', result: { ok: true, value: { items } },
  }));
  const item = {
    sessionId: 'session-1', cwd: '/tmp/work', updatedAt: 12, running: true, agentAvailable: true,
    // 宿主这一行曾经被一路带到手机列表；2026-10-28 起首行由手机本地现算，不再随列表下发。
    snippet: 'the fix landed',
    projections: {
      kind: 'cached', asOfSeq: 7,
      values: {
        title: 'Ship it', goal: 'g'.repeat(3000), tokenUsage: { total: 12345 },
        contextBreakdown: [{ kind: 'tool', tokens: 999 }], inbox: { pending: [1, 2, 3] },
        subagentTiming: { s: 's'.repeat(2000) },
      },
    },
  };
  const body = list([item]);
  const out = JSON.parse(filterRpcBody(body, all, '/api/session/list').toString('utf8'));
  const got = out.result.value.items[0];
  assert.equal(out.result.value.items.length, 1, 'every session is still listed');
  assert.equal(got.sessionId, 'session-1');
  assert.equal(got.running, true, 'the running flag still drives the label');
  assert.equal(got.projections.values.title, 'Ship it', 'the title survives');
  assert.equal(got.projections.values.goal, undefined, 'projections the phone never reads are dropped');
  assert.equal(got.snippet, undefined, 'the host snippet is no longer forwarded');
  assert.ok(filterRpcBody(body, all, '/api/session/list').length < body.length / 3, 'the list shrinks hard');
  const empty = list([]);
  assert.equal(JSON.parse(filterRpcBody(empty, all, '/api/session/list').toString('utf8')).result.value.items.length, 0);
});

test('a session list keeps the two projections the composer renders', () => {
  const list = (items: unknown[]) => Buffer.from(JSON.stringify({
    type: 'server-response', rpcId: 'p4', result: { ok: true, value: { items } },
  }));
  const values = {
    title: 'Ship it',
    modelSelection: { next: { provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'high' } },
    // The Host projects the resolved preset under `currentValue`, not the raw knobs.
    permissions: { currentValue: 'workspace-write' },
    tokenUsage: { total: 12345 }, contextBreakdown: [{ kind: 'tool', tokens: 999 }],
    goal: 'g'.repeat(3000), inbox: { pending: [1, 2, 3] },
  };
  const out = JSON.parse(filterRpcBody(list([{ sessionId: 's', projections: { values } }]), all, '/api/session/list').toString('utf8'));
  const kept = out.result.value.items[0].projections.values;
  assert.deepEqual(kept.modelSelection, values.modelSelection, 'the model chip needs the selection');
  assert.deepEqual(kept.permissions, values.permissions, 'the permission chip needs the preset');
  assert.equal(kept.title, 'Ship it');
  assert.equal(kept.tokenUsage, undefined, 'bulky projections still go');
  assert.equal(kept.goal, undefined);
});

test('a session list filters out internal dsh-mnemon:delegated and subagent sessions', () => {
  const list = (items: unknown[]) => Buffer.from(JSON.stringify({
    type: 'server-response', rpcId: 'p3', result: { ok: true, value: { items } },
  }));
  const normal = {
    sessionId: 'session-normal',
    projections: { values: { title: '接入进度条并补充动画' } }
  };
  const delegated = {
    sessionId: 'session-delegated',
    projections: { values: { title: '[dsh-mnemon:delegated review] Bounded co' } }
  };
  const subagent = {
    sessionId: 'session-sub',
    projections: { values: { title: 'Review memory', subagent: { identity: { label: 'Mnemon idle checkpoint review' } } } }
  };
  const out = JSON.parse(filterRpcBody(list([normal, delegated, subagent]), all, '/api/session/list').toString('utf8'));
  assert.equal(out.result.value.items.length, 1);
  assert.equal(out.result.value.items[0].sessionId, 'session-normal');
});

test('a snapshot that fits in one message is not split', () => {
  const frame = item({ type: 'snapshot', records: [{ event: { seq: 1 } }, { event: { seq: 2 } }] });
  assert.equal(chunkSnapshotText(frame, all, undefined, 1 << 20), undefined, 'one message is cheaper than three');
});

test('a non-snapshot frame is never split', () => {
  const frame = item({ type: 'event', event: { seq: 3 } });
  assert.equal(chunkSnapshotText(frame, all), undefined);
  const stream = item({ type: 'assistant-stream', frame: { type: 'chunk' } });
  assert.equal(chunkSnapshotText(stream, all), undefined);
});

test('an oversized snapshot becomes begin / records… / end with running progress', () => {
  const records = Array.from({ length: 40 }, (_, i) => ({
    event: { type: 'user/message', seq: i + 1, data: { text: 'x'.repeat(200) } }
  }));
  const frame = item({
    type: 'snapshot',
    header: { id: 'session-1', title: 'Ship it' },
    cursor: 40,
    hasMore: true,
    assistantStream: { activeAttempt: { attemptId: 'a:1' } },
    records,
  });
  const parts = chunkSnapshotText(frame, all, undefined, 2000);
  assert.ok(parts && parts.length >= 3, 'needs at least begin + one batch + end');

  const frames = parts!.map(part => JSON.parse(part).value);
  assert.equal(frames[0].type, 'snapshot-begin');
  assert.equal(frames[0].total, 40);
  assert.equal(frames[0].header.title, 'Ship it', 'the title rides on begin, not every batch');
  assert.equal(frames[0].hasMore, true);
  assert.equal(frames[0].cursor, 40);
  assert.equal(frames[0].records, undefined, 'begin carries no records');

  const last = frames[frames.length - 1];
  assert.equal(last.type, 'snapshot-end');
  assert.deepEqual(last.assistantStream, { activeAttempt: { attemptId: 'a:1' } });

  const batches = frames.filter((f: any) => f.type === 'snapshot-records');
  assert.ok(batches.length >= 1);
  assert.equal(batches[batches.length - 1].done, 40, 'progress reaches the total');
  assert.equal(batches[0].done, batches[0].records.length);
  for (let i = 1; i < batches.length; i++) {
    assert.ok(batches[i].done > batches[i - 1].done, 'done only ever grows');
  }
  const kept = batches.flatMap((b: any) => b.records.map((r: any) => r.event.seq));
  assert.deepEqual(kept, records.map(r => r.event.seq), 'every record survives, in order');
});

test('splitting applies the since trim before it batches', () => {
  const records = Array.from({ length: 20 }, (_, i) => ({
    event: { type: 'user/message', seq: i + 1, data: { text: 'y'.repeat(200) } }
  }));
  const frame = item({ type: 'snapshot', records });
  const parts = chunkSnapshotText(frame, all, 15, 1000);
  const batches = parts!.map(part => JSON.parse(part).value)
    .filter((f: any) => f.type === 'snapshot-records');
  const seqs = batches.flatMap((b: any) => b.records.map((r: any) => r.event.seq));
  assert.deepEqual(seqs, [16, 17, 18, 19, 20], 'records at or below since are dropped before batching');
  assert.equal(batches[batches.length - 1].done, 5);
});

test('chunkItemBytes keeps binary frames whole and splits text snapshots', () => {
  const binary = Buffer.from([0, 1, 2, 3]);
  assert.deepEqual(chunkItemBytes(binary, true, all), [binary]);

  const records = Array.from({ length: 30 }, (_, i) => ({
    event: { type: 'user/message', seq: i + 1, data: { text: 'z'.repeat(900) } }
  }));
  const parts = chunkItemBytes(Buffer.from(item({ type: 'snapshot', records })), false, all);
  assert.ok(parts.length >= 3);
  assert.equal(JSON.parse(parts[0]!.toString('utf8')).value.type, 'snapshot-begin');
});

test('a search answer is not mistaken for a session list (items shape is not a contract)', () => {
  // Regression: the rewrite used to key on "value has an items array" and slimmed every
  // such answer as if it were session/list. session/search also answers with items of
  // {sessionId,title,snippet}, and slimSession strips everything but a small whitelist,
  // so the phone's search results came back as anonymous rows.
  const search = Buffer.from(JSON.stringify({
    type: 'server-response', rpcId: 's1',
    result: { ok: true, value: { items: [{ sessionId: 's', title: 'Ship it', snippet: 'the fix landed' }] } },
  }));
  assert.equal(filterRpcBody(search, all, '/api/session/search'), search, 'search answers pass through byte-for-byte');
  // The same shape on the real list endpoint is still slimmed — the gate is the path.
  const slimmed = JSON.parse(filterRpcBody(search, all, '/api/session/list').toString('utf8'));
  assert.equal(slimmed.result.value.items[0].sessionId, 's');
  assert.equal(slimmed.result.value.items[0].title, undefined);
});

test('an unknown endpoint is never rewritten, whatever its JSON shape', () => {
  const body = Buffer.from(JSON.stringify({
    type: 'server-response', rpcId: 's2', result: { ok: true, value: { items: [{ x: 1 }], records: [{ event: { type: 'assistant/message', seq: 1, data: { message: { content: [{ type: 'reasoning', text: 'r'.repeat(999) }] } } } }] } },
  }));
  assert.equal(filterRpcBody(body, all, '/api/session/prompt'), body);
});

test('a subagent catalog entry carries the running state of its own session', () => {
  // 目录条目本身没有状态（宿主只发 {id, createdAt, mode, label?}），手机只能拿它
  // 自己那条会话的 running 判断「还在不在跑」。而子代理会话**会被
  // isInternalSession 整条删掉**（parentSessionId 非空），所以活体表必须在过滤
  // 之前建 —— 用户 2026-11-10 报的「一堆已结束的子代理让胶囊亮着」就是这个顺序
  // 没做对时唯一的补救办法（目录里根本没有状态可读）。
  const body = Buffer.from(JSON.stringify({
    type: 'server-response', rpcId: 'l1',
    result: {
      ok: true,
      value: {
        items: [
          {
            sessionId: 'parent', title: 'lead', running: true,
            projections: {
              values: {
                title: 'lead',
                subagentCatalog: [
                  { id: 'child-live', createdAt: 1, mode: 'continuable', label: '审查投影器' },
                  { id: 'child-done', createdAt: 2, mode: 'one-shot' },
                  { id: 'child-ext', createdAt: 3, mode: 'external' },
                  { id: 'child-never-seen', createdAt: 4, mode: 'unknown' },
                ],
              },
            },
          },
          // 子代理各自是一条会话：这两行会被 isInternalSession 删掉，
          // 但它们自己的 running 正是目录条目要的活体信号。
          { sessionId: 'child-live', parentSessionId: 'parent', origin: 'subagent', running: true },
          { sessionId: 'child-done', parentSessionId: 'parent', origin: 'subagent', running: false },
        ],
      },
    },
  }));
  const slimmed = JSON.parse(filterRpcBody(body, all, '/api/session/list').toString('utf8'));
  const items = slimmed.result.value.items;
  assert.deepEqual(items.map((i: { sessionId: string }) => i.sessionId), ['parent'], '子代理会话自己照旧被删掉');
  const catalog = items[0].projections.values.subagentCatalog as Array<{ id: string; running: boolean }>;
  const runningOf = (id: string) => catalog.find(e => e.id === id)!.running;
  assert.equal(runningOf('child-live'), true, '它自己那条会话在跑');
  assert.equal(runningOf('child-done'), false, '它自己那条会话停了');
  assert.equal(runningOf('child-ext'), false, 'external 没有本机会话，不该为它亮着');
  assert.equal(runningOf('child-never-seen'), false, '没见过的 id 当没在跑，不能靠猜点亮');
});
