// 本机冒烟用假 DSH 宿主：一个最小上游（HTTP session/list + WS remote.mux 快照）+
// 真插件（HostClient 隧道 + Collector/Hub/Outbox/Pusher 归档），让模拟器上的 App 能走完整链路：
// APK ↔ TLS 前置(8443) ↔ Relay(18080) ↔ 隧道 ↔ 插件 ↔ 假上游。
// 用法：npx tsx scripts/smoke-fake-host.ts   （需 local-e2e.ts 先在跑）
import http from 'node:http';
import https from 'node:https';
import { readFileSync, writeFileSync } from 'node:fs';
import { once } from 'node:events';
import { randomBytes } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { HostClient } from '../packages/plugin/src/client.js';
import { ArchiveOutbox } from '../packages/plugin/src/outbox.js';
import { SessionHub } from '../packages/plugin/src/hub.js';
import { ArchivePusher } from '../packages/plugin/src/pusher.js';
import { Collector } from '../packages/plugin/src/collector.js';
import { ControlSession } from '../packages/plugin/src/control.js';
import type { Channel } from '../packages/core/src/index.js';

const RELAY_PORT = Number(process.env.LOCAL_E2E_RELAY_PORT ?? 18080);
const CA = readFileSync('artifacts/fixture-cert.pem');
const SESSION_ID = 'smoke-session-1';
const TITLE = '冒烟会话';

/** 子智能体活体验证开关：`0` 表示子代理「全部已结束」，用来复现胶囊该消失的那一帧。 */
const SMOKE_SUBAGENT_LIVE = process.env.SMOKE_SUBAGENT_LIVE !== '0';

const log = (...v: unknown[]) => console.log(new Date().toISOString().slice(11, 19), ...v);
let updatedAt = Date.now();

/**
 * 假会话的事件序列：两条完整回合。
 *
 * 第二条在收到 prompt 之后才追加——只有这样才验得动「发送 → 流式增量 → turn/end
 * 熄灭停止键」这条链路；固定快照只能验历史渲染。
 */
let turn = 1;
/**
 * 每个会话自己的轮次表。
 *
 * 之前只有一个全局 turn，所有会话共用同一份 records()，于是点「新建」出来的空
 * 会话里立刻能看到别的会话的历史，看起来像 App 把消息串了。空态的会话就该是空的：
 * 没发过 prompt 的会话不生成任何记录。
 */
const turns = new Map<string, number>();
/**
 * 会话清单。
 *
 * 真宿主的 session/list 会把自己名下所有会话列出来，新建的也在里面。假宿主之前
 * 固定只回冒烟会话，于是「新建 → 发第一条消息」在手机上验不动：发完返回列表，
 * 那条会话因为 list 里没有而被整体覆盖掉，看着像消息发丢了。
 * key 是 sessionId，value 是标题（首条消息前 12 字）。
 */
const sessions = new Map<string, string>([[SESSION_ID, TITLE]]);
/**
 * 每个会话的任务清单（宿主 `todo_write` 的产物）。
 *
 * 真宿主会写两条通路：`session/projections` 的 `values.todos`，以及流里的
 * `todo/write` 事件（整表覆盖）。假宿主原来两条都没有，手机上那颗「任务: n/n」
 * 胶囊永远不出现——2026-10-28 加胶囊时连一眼都验不了，所以这里把两条都补上。
 */
const todos = new Map<string, Array<{ content: string; status: string }>>();
/** 每个会话的 inbox 队列（仅供 __smoke/queue 造数）。 */
const inbox = new Map<string, Array<{ id: string; content: Array<{ type: string; text: string }> }>>();
/**
 * seq 必须单调递增。
 *
 * 假宿主每重启一次，若 seq 从 1 重新数，旧归档里同 seq 的记录会被 dedupeTimeline
 * 当成「同一位置」顶掉——真机上表现为发完消息界面毫无变化（2026-10-27 复现）。
 * 真 DSH 的 seq 是会话级单调计数器，这里用时间戳打底对齐同样的语义。
 */
/**
 * 全部 seq 共用**同一个量级**：毫秒时间戳 × 1000。
 *
 * <p>**这里曾出过一次很难查的假故障（2026-11-01）**：回合用 `Date.now()/1000*4`
 * （约 7.2e9），而钩子（splice / todo）用 `Date.now()*1000`（约 1.8e15），两者
 * 差 25 万倍。只要 `__smoke/queue` 先造过一条，手机水位就被推到 1.8e15，此后
 * 所有回合的 seq 都落在水位**之下** —— 同步只问「水位之后有什么」，于是
 * prompt 明明到了宿主（日志 `prompt -> turn 1` 一清二楚），手机上一条新记录都
 * 长不出来。看起来像「删除功能把消息弄丢了」，实际是夹具的序号尺度不统一。
 */
const seqBase = Date.now() * 1000;
let extraSeq = 0;
/**
 * 冒烟钩子的序号必须**跨进程重启严格递增**。
 *
 * <p>手机侧与中继都按「从水位之后拉」的语义同步，序号一旦落到水位下面，那条事件
 * 就永远送不到（不是丢了，是没人会去要它）。所以与 [seqBase] 同量级、并额外
 * 加上一个进程内自增序号，避免同一毫秒内撞号。
 */
const nextExtraSeq = (): number => Date.now() * 1000 + ++extraSeq;
/** 每步一条像样的推理句（等价于真宿主的 durable reasoning）。 */
// 每步的思考要**足够长**：手机上折叠行只在运行中显示「思考：<尾部>」，
// 而这一段的额度是按可用宽度算的（ChatViewModel.inlineThinkingBudget）。
// 二十来字的样本根本撑不满一行，宽度算得对不对在本地看不出来
// （2026-11-01 用户反馈「思考内容显示得太少了」，用短样本永远复现不了）。
const STEP_REASONING = [
  '用户问的是这条链路通不通。先把关键节点按顺序列出来：手机发送、中继转发、插件 loopback、宿主执行、事件回传、归档落库，然后逐个确认每一段有没有真的通。',
  '分帧、归档、同步三处都可能断。从最近改动过的那一处开始看更省时间：如果只是渲染少了东西，那事件本身应该是好的，先查投影层再做别的。',
  '假宿主这边只要把事件按序推出去就够了，不需要模拟真实延迟。但顺序必须跟真宿主一致，否则手机上看到的拼接结果是错的，验证出来的结论也就没意义。',
  '先确认 prompt 有没有真的到上游，再看有没有回帧。这两件事分开查：前者看插件日志里的 cmd prompt 与 loopback 结果，后者看手机上有没有新的时间线记录落进来。',
  '如果工具调用也走通了，就说明多步回合的拼接是对的。接下来要验证的是折叠与分段的边界：一段正文之后应该开新的折叠栏，而不是把整轮压成一条。',
];
// 超一屏的长正文开关：手机端「跟随最新」的滚动语义只有在这种回复下才暴露问题
// ——`scrollToItem(index)` 是「把该项对齐到视口顶部」，最后一项短于一屏时看不出，
// 高过一屏就会把首行钉在屏幕上沿（2026-10-31 真机「顶端与正文第一行对齐」）。
// 本地复现：LONG_REPLY=1 npm run smoke:fake
//
// 定义在 `records` 外面：正文增量（`text-delta`）的推送在 prompt 处理函数里，
// 与这里生成 durable `assistant/message` 的地方是两处，文案必须同源，
// 否则整段正文到达时会与增量拼出来的那份对不上，看着像「重打了一遍」。
const LONG_REPLY = process.env.SMOKE_LONG_REPLY === '1';
const longReply = (t: number, prompt: string): string => {
  const lines: string[] = [];
  for (let i = 1; i <= 40; i++) {
    lines.push(`${i}. 第 ${t} 轮第 ${i} 行：这是用来撑满一屏以上的长正文，验证滚动是否真的落在底部而不是把第一行顶到屏幕上沿（${prompt}）。`);
  }
  // 必须是真正的换行符。写成 `'\\n'` 会拼进字面量「反斜杠 + n」，手机上看到的是
  // 「…上沿 \n34. 第 1 轮第 34 行…」这种一长串没有分段的正文（2026-11-01 模拟器发现）。
  return lines.join('\n');
};
/** 某一轮的助手正文：durable 事件与 `text-delta` 增量都用它，保证一字不差。 */
const answerText = (t: number, prompt: string): string =>
  LONG_REPLY ? longReply(t, prompt) : `第 ${t} 轮回复：手机发来的「${prompt}」已经走完 prompt → follow → 归档 → SSE 全链路。`;

const records = (sessionId: string = SESSION_ID) => {
  const list: Array<{ event: { type: string; seq: number; time: number; data: unknown } }> = [];
  // 冒烟会话自带一轮历史，方便冷启动就有东西可看；其余会话从空开始。
  //
  // 历史回合用 turn=0，真实回合从 turn=1 起：两者若同号，手机端按 turn 认回合
  // （processFor 复用同一块面板）会把它们并成一块，联调里看起来像「历史思考行被新回合顶掉」，
  // 而真 DSH 的 turn 是逐回合递增的，不会出现这种合并。
  if (sessionId === SESSION_ID) list.push(
    { event: { type: 'turn/start', seq: seqBase + 1, time: updatedAt - 4000, data: { turn: 0 } } },
    { event: { type: 'user/message', seq: seqBase + 2, time: updatedAt - 3000, data: { turn: 0, source: { kind: 'user' }, content: [{ type: 'text', text: '你好，这是冒烟测试' }] } } },
    { event: { type: 'assistant/message', seq: seqBase + 3, time: updatedAt - 2000, data: { turn: 0, message: { role: 'assistant', content: [{ type: 'reasoning', text: '想想该怎么回。' }, { type: 'text', text: '收到！这是来自假宿主的回复，分帧与同步链路如果都在，你能在手机上看到这句话。' }] } } } },
    { event: { type: 'turn/end', seq: seqBase + 4, time: updatedAt - 1000, data: { turn: 0 } } },
  );
  const turn = turns.get(sessionId) ?? 0;
  // 每轮按**真宿主的形态**发：一轮拆成 3 个 step（step/start 与 step/end 成对），
  // 每个 step 各有 reasoning + tool/call + tool/result，正文只挂在最后一个 step 上。
  //
  // 这里曾经是「一轮 6 条、只有 1 个工具」的简化形态，于是列表里永远只出现
  // 「已处理 1 个操作」，而真机上一轮会渲染成 7~9 行摘要——折叠与回合合并的
  // 真实压力从来没被这条链路覆盖过（2026-10-28 用户在真机上才发现）。
  const STEPS_PER_TURN = 3;
  // 每步发几个工具调用。默认 1（就是老形状）；调大是为了造出「一级抽屉内容
  // 超过一屏」的场景——折叠行会报出几十个操作，抽屉里才滚得动。验收边界滚动、
  // 「跳到底部」这类交互时用它，比如 SMOKE_TOOLS_PER_STEP=10。
  //
  // 注意必须落在**同一个 step 内**：每个 step 会单独成段（`sealSegment`），
  // 抽屉一次只显示一个面板的步骤，把 STEPS_PER_TURN 调大反而看不出长内容。
  const TOOLS_PER_STEP = Math.max(1, Number(process.env.SMOKE_TOOLS_PER_STEP ?? 1));
  // 一轮的 seq 数（turn/start + user/message + 每步 3 条固定 + 每工具 2 条 + turn/end），
  // 回合基数必须按它递进，否则相邻回合会撞号、被 dedupeTimeline 顶掉。
  const TURN_SPAN = 2 + STEPS_PER_TURN * (3 + TOOLS_PER_STEP * 2) + 1;
  for (let t = 1; t <= turn; t++) {
    const base = (sessionId === SESSION_ID ? seqBase + 4 : seqBase) + (t - 1) * TURN_SPAN;
    let cursor = base;
    list.push({ event: { type: 'turn/start', seq: cursor + 1, time: updatedAt, data: { turn: t } } });
    cursor += 1;
    list.push({
      event: {
        type: 'user/message', seq: cursor + 1, time: updatedAt,
        data: { turn: t, source: { kind: 'user' }, content: [{ type: 'text', text: lastPrompt }] },
      },
    });
    cursor += 1;
    for (let s = 1; s <= STEPS_PER_TURN; s++) {
      const callId = `call-${t}-${s}`;
      const last = s === STEPS_PER_TURN;
      list.push({ event: { type: 'step/start', seq: cursor + 1, time: updatedAt, data: { turn: t, step: s } } });
      // 每个 step 的第一条只有 reasoning（真宿主就是这样），正文那条挂在最后一个 step。
      list.push({
        event: {
          type: 'assistant/message', seq: cursor + 2, time: updatedAt,
          data: {
            turn: t, step: s,
            message: {
              role: 'assistant',
              content: [
                // 思考正文要像真推理，不能是「第 X 轮第 X 步」这种占位：
                // 手机上这段会显示在折叠行末尾，占位文案让人以为没在思考
                // （2026-10-28 用户反馈「我怎么只看到类似第X轮第X步」）。
                { type: 'reasoning', text: STEP_REASONING[(t + s) % STEP_REASONING.length] },
                ...(last
                  ? [{ type: 'text', text: answerText(t, lastPrompt) }]
                  : []),
              ],
            },
          },
        },
      });
      // 用独立游标 `c` 递进：直接把 `cursor` 往前推会和下面的 `step/end` 撞号
      // （两个 tool 挨着时，第二个 tool/call 的 seq 正好等于 step/end 的 seq），
      // 撞号的那条会被 dedupeTimeline 顶掉，表现为「少了一个操作」。
      let c = cursor + 2;
      for (let k = 1; k <= TOOLS_PER_STEP; k++) {
        const id = TOOLS_PER_STEP === 1 ? callId : `${callId}-tool-${k}`;
        const label = TOOLS_PER_STEP === 1 ? `round-${t}-step-${s}` : `round-${t}-step-${s}-tool-${k}`;
        list.push({
          event: {
            type: 'tool/call', seq: c + 1, time: updatedAt,
            data: { turn: t, step: s, callId: id, name: 'shell', arguments: JSON.stringify({ command: `echo ${label}` }) },
          },
        });
        list.push({
          event: {
            type: 'tool/result', seq: c + 2, time: updatedAt + 200 * s,
            data: {
              turn: t, step: s,
              message: { role: 'tool', source: { callId: id }, content: [{ type: 'text', text: label }] },
            },
          },
        });
        c += 2;
      }
      list.push({ event: { type: 'step/end', seq: c + 1, time: updatedAt + 200 * s, data: { turn: t, step: s } } });
      cursor = c + 1;
    }
    list.push({ event: { type: 'turn/end', seq: cursor + 1, time: updatedAt + 1000, data: { turn: t } } });
  }
  return list;
};
let lastPrompt = '';
/**
 * 每个会话的权限预设 / 模型。
 *
 * 真 DSH 的 session/projections 会回传 {permissions:{currentValue},model:{...}}，
 * 手机端的权限图标颜色与模型胶囊文案全靠它。假宿主原来只回 title，导致
 * 「选了完全权限但图标不变红」这类问题无法在本地复现——不是 App 的错，是这里缺数据。
 */
// 与 DSH 一致的思考等级集合；id 是协议值，name 是展示名。
const EFFORTS = [
  { id: 'off', name: 'Off', description: 'Use for simple tasks that do not need reasoning.' },
  { id: 'low', name: 'Low', description: 'Prefer for routine or latency-sensitive tasks.' },
  { id: 'high', name: 'High', description: 'The default balance for most tasks.' },
  { id: 'max', name: 'Max', description: 'Reserve for the hardest quality-first tasks.' },
];

const perms = new Map<string, string>();
// 真宿主的 modelSelection 投影是 {provider, model, reasoningEffort}，选模型/思考
// 等级都会改这一份；手机端读的是 projections.values.modelSelection.next。
const selections = new Map<string, { provider: string; model: string; reasoningEffort: string }>();
const selectionOf = (id: string) =>
  selections.get(id) ?? { provider: 'deepseek', model: 'deepseek-v3', reasoningEffort: 'high' };
const permOf = (id: string) => perms.get(id) ?? 'workspace-write';

// ---- 假 DSH 上游 ----
const upstream = http.createServer((req, res) => {
  const chunks: Buffer[] = [];
  req.on('data', c => chunks.push(c as Buffer));
  req.on('end', () => {
    // 手机端 RelayApi 校验响应必须回显请求的 rpcId，固定值会被判「DSH 拒绝了请求」。
    let rpcId = 'r';
    try { rpcId = (JSON.parse(Buffer.concat(chunks).toString('utf8')) as { rpcId?: string }).rpcId ?? 'r'; } catch { /* keep */ }
    if (req.url === '/api/session/list') {
      res.setHeader('content-type', 'application/json');
      const base = [...sessions].map(([sessionId, title]) => ({
        sessionId,
        updatedAt,
        running: false,
        cwd: '/tmp/smoke',
        agentAvailable: true,
        projections: {
          values: {
            title,
            permissions: { currentValue: permOf(sessionId) },
            modelSelection: { next: selectionOf(sessionId) },
            // 子智能体目录：条目本身**不带状态**（真宿主只发
            // `{id, createdAt, mode, label?}`），活体只能从「子代理自己那条会话」
            // 的 `running` 上拿。下面那两行 child-* 就是那些会话 —— 它们会被插件的
            // `isInternalSession` 整条删掉，所以活体表必须在过滤之前建
            // （`runningSessions()`，见 packages/core/src/filter.ts）。
            subagentCatalog: [
              { id: 'child-live', createdAt: updatedAt - 60_000, mode: 'continuable', label: '审查投影器' },
              { id: 'child-done', createdAt: updatedAt - 120_000, mode: 'one-shot', label: '写文档' },
            ],
          },
        },
      }));
      // 子代理会话自己：`parentSessionId` 非空 ⇒ 插件会把它从列表里删掉，但它
      // 身上的 `running` 正是目录条目要的活体信号。`SMOKE_SUBAGENT_LIVE=0` 用来
      // 复现「全部结束」的那一帧（胶囊该消失）。
      const children = [
        { sessionId: 'child-live', parentSessionId: SESSION_ID, origin: 'subagent', running: SMOKE_SUBAGENT_LIVE, updatedAt, cwd: '/tmp/smoke', agentAvailable: true, projections: { values: { title: '审查投影器' } } },
        { sessionId: 'child-done', parentSessionId: SESSION_ID, origin: 'subagent', running: false, updatedAt, cwd: '/tmp/smoke', agentAvailable: true, projections: { values: { title: '写文档' } } },
      ];
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: { items: [...base, ...children] } } }));
      return;
    }
    // session/projections：手机端打开会话、改权限、改模型后都会拉一次。
    if (req.url === '/api/session/projections') {
      let target = SESSION_ID;
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as {
          payload?: { args?: { request?: { sessionId?: string } } };
        };
        target = body.payload?.args?.request?.sessionId ?? SESSION_ID;
      } catch { /* keep */ }
      const values = {
        title: sessions.get(target) ?? '新对话',
        permissions: { currentValue: permOf(target) },
        modelSelection: { next: selectionOf(target) },
        // 没有写过的会话给 null（真宿主也是 null，不是空数组）。
        todos: todos.get(target) ?? null,
      };
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: { values } } }));
      return;
    }
    // session/modelCatalog：手机端打开模型胶囊时拉一次。真宿主返回按 provider 分组
    // 的目录（groups[].models[]），这里给两组几条，够验证搜索与选中。
    if (req.url === '/api/session/modelCatalog') {
      const catalog = {
        default: { provider: 'deepseek', model: 'deepseek-v3', reasoningEffort: 'high' },
        groups: [
          {
            id: 'deepseek',
            name: 'DeepSeek',
            models: [
              // efforts 是宿主声明「这个模型支持哪些思考等级」，真 DSH 就是这么下发的
              // （off/low/high/max）；手机端不自己造等级，只照着渲染。
              { id: 'deepseek-v3', name: 'DeepSeek V3', reasoning: { defaultEffort: 'high', efforts: EFFORTS } },
              { id: 'deepseek-r1', name: 'DeepSeek R1', reasoning: { defaultEffort: 'high', efforts: EFFORTS } },
              { id: 'deepseek-coder', name: 'DeepSeek Coder' },
            ],
          },
          {
            id: 'anthropic',
            name: 'Anthropic',
            models: [
              { id: 'claude-sonnet-4', name: 'Claude Sonnet 4' },
              { id: 'claude-haiku-3.5', name: 'Claude Haiku 3.5' },
            ],
          },
        ],
        failures: [],
      };
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: catalog } }));
      return;
    }
    // session/selectModel：手机端在模型抽屉里选模型/思考等级直接调这个 RPC。
    // 真宿主改的是「下一回合使用」的选择，这里也落进 selections，再由 projections 回读。
    if (req.url === '/api/session/selectModel') {
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as {
          payload?: { args?: { request?: { sessionId?: string; provider?: string; model?: string; reasoningEffort?: string } } };
        };
        const req2 = body.payload?.args?.request;
        const target = req2?.sessionId ?? SESSION_ID;
        const prev = selectionOf(target);
        selections.set(target, {
          provider: req2?.provider ?? prev.provider,
          model: req2?.model ?? prev.model,
          reasoningEffort: req2?.reasoningEffort ?? prev.reasoningEffort,
        });
        log(`selectModel -> ${target} ${req2?.model ?? ''} ${req2?.reasoningEffort ?? ''}`);
      } catch { /* keep */ }
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: {} } }));
      return;
    }
    // commands/execute：桌面端改权限/模型走斜杠命令，手机端照抄。这里真的改状态，
    // 否则「选了完全权限」下一次 projections 又被旧值盖回去。
    if (req.url === '/api/commands/execute') {
      // 手机端按 DSH 的 commands/execute 形状发：args = {agentId, line, submittedAttachments}。
      let text = '';
      let target = SESSION_ID;
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as {
          payload?: { args?: { agentId?: string; line?: string } };
        };
        const args = body.payload?.args;
        target = args?.agentId ?? SESSION_ID;
        text = (args?.line ?? '').trim();
      } catch { /* keep */ }
      // 命令串是「/permission danger-full-access」：斜杠和动词连在一起，
      // 不能用 [, verb, value] 解构（那样 verb 会拿到参数值）。
      const parts = text.split(/\s+/);
      const verb = (parts[0] ?? '').replace(/^\//, '');
      const value = parts[1] ?? '';
      if (verb === 'permission' && value) perms.set(target, value);
      log(`command -> ${text}`);
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: {} } }));
      return;
    }
    // $events/result：手机回答一条 waterfall。真宿主的规则是「回答者必须是投递过
    // 这条事件的 client」——不在 deliveries 里就静默丢弃，结算后再来一次也无效。
    // 假宿主照抄这套，否则「重开流再回答」这种 bug 在本地验不出来。
    if (req.url === '/api/$events/result') {
      let clientId = '';
      let eventId = '';
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as {
          payload?: { args?: { clientId?: string; eventId?: string } };
        };
        clientId = body.payload?.args?.clientId ?? '';
        eventId = body.payload?.args?.eventId ?? '';
      } catch { /* keep */ }
      const pending = pendingWaterfalls.get(eventId);
      if (!pending || !pending.deliveries.has(clientId)) {
        log(`events/result ignored event=${eventId} client=${clientId} (not delivered to this client)`);
      } else {
        pendingWaterfalls.delete(eventId);
        log(`events/result settled ${eventId} by ${clientId}`);
      }
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: {} } }));
      return;
    }
    // __smoke/approval：测试钩子，让手机上随时能出现一张待审批卡片。
    if (req.url?.startsWith('/__smoke/approval')) {
      const u = new URL(req.url, 'http://localhost');
      const target = u.searchParams.get('sessionId') ?? SESSION_ID;
      // durableOnly=1：只写 session log 里的 `approval/asked`，不投 waterfall。
      // 用来验「归档重建的卡」——宿主那边这条 waterfall 已经过期，手机只能展示，
      // 答不回去。真实场景：审批挂起期间手机重装/重启，$events 重投的是新连接
      // 建立之后的事，更早的那批不会再回来。
      const durableOnly = u.searchParams.get('durableOnly') === '1';
      const eventId = raiseApproval(
        target,
        'bash',
        durableOnly
          ? '这条只写了持久事件，没有 waterfall：手机上应当只读。'
          : 'Gradle 必须读写 ~/.gradle 下的发行包与依赖缓存，该目录在会话工作区之外。',
        durableOnly,
      );
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ ok: true, eventId, agentId: target }));
      return;
    }
    // session/create：真宿主返回新建会话的 sessionId，这里也照做，否则手机端
    // 「新建」永远拿不到标识——只能看到一条「创建会话未返回标识」的红字。
    // 测试钩子：给某会话写一份任务清单，并像真宿主一样推一条 todo/write。
    //   curl "http://127.0.0.1:<port>/__smoke/todos?sessionId=smoke-session-1&done=2&total=5"
    // done/total 只是造数；要更真实的话直接传 statuses=completed,completed,pending
    // __smoke/turnfail：造一个「供应商报错收场」的回合，验证手机端能把原文显示出来。
    //   curl "http://127.0.0.1:<port>/__smoke/turnfail?sessionId=smoke-session-1"
    //
    // 真宿主在 catch 分支写 `turn/end` 时带上 `reason:{kind:"error", error:{message, code}}`
    // （`dsh-agent-loop/lib/index.js` 的 turnEnds 赋值）。桌面端据此把 429 的响应体整段
    // 摊开给人看；手机端以前整段丢掉，用户只看到「回合莫名其妙结束了」。
    if (req.url?.startsWith('/__smoke/turnfail')) {
      const u = new URL(req.url, 'http://127.0.0.1');
      const target = u.searchParams.get('sessionId') ?? SESSION_ID;
      const code = u.searchParams.get('code') ?? 'RATE_LIMIT';
      const message = u.searchParams.get('message')
        ?? '{"type":"rate_limit_error","code":"bili_throttle_hold","message":"upstream rate-limited 4 consecutive request(s)","retryable":true}';
      for (const sink of followers.get(target) ?? []) {
        sink({
          type: 'turn/end', seq: nextExtraSeq(), time: Date.now(),
          data: { turn: 1, reason: { kind: 'error', error: { message, code } } },
        });
      }
      log(`turn fail written for ${target}: ${code}`);
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ ok: true, code }));
      return;
    }
    if (req.url?.startsWith('/__smoke/todos')) {
      const u = new URL(req.url, 'http://127.0.0.1');
      const target = u.searchParams.get('sessionId') ?? SESSION_ID;
      const done = Number(u.searchParams.get('done') ?? 1);
      const total = Number(u.searchParams.get('total') ?? 3);
      const list = Array.from({ length: total }, (_, i) => ({
        content: `任务项 ${i + 1}`,
        status: i < done ? 'completed' : i === done ? 'in_progress' : 'pending',
      }));
      todos.set(target, list);
      // 真宿主是「整表覆盖」：每次变更都把完整清单推一遍，手机上直接替换。
      // 走 follow 流（$events 是审批/提问专用，任务清单不走那边）。
      for (const sink of followers.get(target) ?? []) {
        // sink 自己会包成 {type:'event', event}，这里只给事件本体，
        // 再包一层的话宿主侧取到的 type 是 'event'，整条会被当成未知事件丢掉。
        sink({ type: 'todo/write', seq: nextExtraSeq(), time: Date.now(), data: { sessionId: target, todos: list } });
      }
      log(`todos written for ${target}: ${done}/${total}`);
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ ok: true, sessionId: target, todos: list }));
      return;
    }
    // 测试钩子：像真宿主一样向 inbox 插一条排队消息。
    //   curl "http://127.0.0.1:<port>/__smoke/queue?sessionId=smoke-session-1&text=排队这句"
    // 真宿主的 `agent/inbox/spliced` 是**增量**：带 target/start/removedCount/inserted。
    // 只有按这个形状发，手机侧的投影才能真正验证到。
    if (req.url?.startsWith('/__smoke/queue')) {
      const u = new URL(req.url, 'http://127.0.0.1');
      const target = u.searchParams.get('sessionId') ?? SESSION_ID;
      const text = u.searchParams.get('text') ?? '排队的这句话';
      const id = `q-${Date.now().toString(36)}`;
      const bucket = inbox.get(target) ?? [];
      bucket.push({ id, content: [{ type: 'text', text }] });
      inbox.set(target, bucket);
      const seq = nextExtraSeq();
      const frame = {
        type: 'agent/inbox/spliced',
        seq,
        time: Date.now(),
        data: { target: 'next-turn', start: bucket.length - 1, removedCount: 0, inserted: [{ id, content: [{ type: 'text', text }] }] },
      };
      for (const sink of followers.get(target) ?? []) sink(frame);
      log(`inbox spliced for ${target}: ${id}`);
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ ok: true, sessionId: target, id, seq }));
      return;
    }
    // 队列项的 edit/remove/steer。真宿主语义（dsh-api-session-controller 的
    // updateQueue）：edit 换正文、remove 撤下、steer 要求正在跑。
    // 假宿主必须实现它，否则插件那条「空闲时先 remove 再 prompt」的分流走不通，
    // 端到端验出来是「点了没反应」，而问题其实在夹具。
    if (req.url === '/api/session/updateQueue') {
      let target = SESSION_ID;
      let itemId = '';
      let kind = 'remove';
      let editText = '';
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as {
          payload?: { args?: { request?: {
            sessionId?: string; itemId?: string;
            action?: { kind?: string; text?: string };
          } } };
        };
        const r = body.payload?.args?.request;
        target = r?.sessionId ?? target;
        itemId = r?.itemId ?? '';
        kind = r?.action?.kind ?? kind;
        editText = r?.action?.text ?? '';
      } catch { /* 形状不对就当成一次无操作 */ }
      const bucket = inbox.get(target) ?? [];
      const at = bucket.findIndex((m) => m.id === itemId);
      if (at < 0) {
        res.writeHead(404, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: false, error: { code: 'session/queue-item-not-found', message: 'queued item is no longer pending' } } }));
        return;
      }
      if (kind === 'remove' || kind === 'steer') {
        bucket.splice(at, 1);
        inbox.set(target, bucket);
        // 真宿主每改一次 inbox 都发一条 splice；手机上那颗排队卡片靠它消失。
        for (const sink of followers.get(target) ?? []) {
          sink({ type: 'agent/inbox/spliced', seq: nextExtraSeq(), time: Date.now(), data: { target: 'next-turn', start: at, removedCount: 1, inserted: [] } });
        }
        log(`inbox ${kind} ${itemId} for ${target} (${bucket.length} left)`);
      } else if (kind === 'edit') {
        bucket[at] = { ...bucket[at]!, content: [{ type: 'text', text: editText }] };
        inbox.set(target, bucket);
        for (const sink of followers.get(target) ?? []) {
          sink({ type: 'agent/inbox/spliced', seq: nextExtraSeq(), time: Date.now(), data: { target: 'next-turn', start: at, removedCount: 1, inserted: [bucket[at]] } });
        }
        log(`inbox edit ${itemId} for ${target}`);
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: { accepted: true } } }));
      return;
    }
    if (req.url === '/api/session/create') {
      const sessionId = `smoke-${Date.now().toString(36)}`;
      // 建出来的会话要真的进清单，否则手机端发完第一条消息返回列表时，
      // list 里没有它 → 整个列表被覆盖 → 那一轮看着像发丢了。
      sessions.set(sessionId, '新对话');
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: { sessionId } } }));
      return;
    }
    // 收到 prompt 就追加一轮假回合，让 follow 快照真的变长（流式链路才有的验）。
    if (req.url === '/api/session/prompt') {
      let target = SESSION_ID;
      let promptMode = '(unknown)';
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as {
          payload?: { args?: { request?: {
            sessionId?: string;
            address?: { sessionId?: string };
            content?: Array<{ text?: string }>;
            /** queue = 排在当前轮之后；steer = 插进正在跑的这轮（长按发送按钮）。 */
            mode?: string;
          } } };
        };
        lastPrompt = body.payload?.args?.request?.content?.[0]?.text ?? '';
        // prompt 把会话放在 request.sessionId，而 follow 用 request.address.sessionId；
        // 两处都认，否则新建会话的回合会被推到冒烟会话的流里。
        const req = body.payload?.args?.request;
        target = req?.sessionId ?? req?.address?.sessionId ?? SESSION_ID;
        // 投递方式要打进日志：长按发送按钮走的是 mode:"steer"，光看「发出一轮」
        // 分不出是排队还是插话，验收时这条是唯一的证据。
        promptMode = req?.mode ?? '(none)';
      } catch { /* keep */ }
      // 真宿主跑一轮之前会先 `inbox.claim()`：`mutate("next-turn", 0, 1, [], false)`
      // —— 也就是发一条 removedCount=1、inserted 空的 splice 把队首删掉
      // （`dsh-agent-loop/lib/index.js:103-111`）。假宿主原来从不 claim，
      // 于是 `__smoke/queue` 造的消息在手机上一挂就是永久，验收时看起来像
      // 「队列残留、永不消费」。这里补上同样的语义。
      claimInbox(target);
      const turn = (turns.get(target) ?? 0) + 1;
      turns.set(target, turn);
      // 真 DSH 的会话标题由首条消息撑起来，这里粗略对齐：还没改过名的会话，
      // 用第一句 prompt 的前 12 字当标题。
      if ((sessions.get(target) ?? '新对话') === '新对话' && lastPrompt) {
        sessions.set(target, lastPrompt.slice(0, 12));
      }
      updatedAt = Date.now();
      log(`prompt -> turn ${turn} [mode=${promptMode}]: ${lastPrompt}`);
      const all = records(target);
      // 只推这一轮：一轮现在有 2 + 3×5 + 1 = 18 条（turn/start → user/message →
      // 3 个 step × 5 条 → turn/end）。原来写死 slice(-6)，一轮变长之后就只剩
      // 最后一个 step 的片段——user/message 与 turn/start 都推不到，手机上助手正文
      // 会落在用户气泡上方、面板只剩半截（2026-10-28 模拟器截图发现）。
      const added = all
        .filter(row => (row.event.data as { turn?: number } | undefined)?.turn === turn)
        .map(row => ({ ...row, target }));
      // 真 DSH 在回合进行中持续广播 api-session/status(id, true)，结束时发 false。
      // 手机的「停止键 / 正在分析请求」直接吃这条，缺了就一直转圈。
      notify('api-session/status', [target, true]);
      const push = (value: unknown) => {
        for (const sink of followers.get(target) ?? []) sink(value);
      };
      // 真宿主除了 durable 的 assistant/message，还会在 follow 流上推
      // {type:'assistant-stream'} 的 reasoning-delta 增量——手机上「折叠行后面那行
      // 流式思考」就是靠它长出来的。这里从来没发过，所以那条渲染路径在冒烟链路里
      // 一直是死的（2026-10-28 用户要求「持续发送思考内容」时才发现）。
      //
      // 一轮只开一个 attempt：真宿主的 attemptId 在一轮内不变，chunk.index 递增，
      // 手机端按 index 去重（index < nextChunk 的丢）。
      const attemptId = `att-${target}-${turn}`;
      push({ type: 'assistant-stream', frame: { type: 'start', attemptId } });
      let chunkIndex = 0;
      // 假思考正文：每片几十个字，按真实语速（每 200~400ms 一片）往外挤，
      // 光靠 SMOKE_PUMP_MS 一拍一片太慢，看不出「在长」。
      const REASONING = [
        '先看一下用户这句话想要什么。',
        '要判断这个请求涉及哪几个文件。',
        '先把无关的部分排除掉，省得上下文过长。',
        '再看现有的实现里有没有现成的工具可以复用。',
        '如果没有，就按最小改动补一条路径。',
        '补完之后要跑一遍单测确认没有回归。',
        '最后把结论捋成一句话回复给用户。',
      ];
      let reasoningPiece = 0;
      // `SMOKE_REASONING_TOTAL`：思考一共推多少片，默认一直推（-1）。
      // 验「抽屉贴底跟随」时需要思考在**抽屉已经打开之后**继续增长几十秒：
      // 光靠 7 句循环、每 250ms 一片，两秒就停了，等手指点开二级抽屉时它早不长了。
      const reasoningTotal = Number(process.env.SMOKE_REASONING_TOTAL ?? -1);
      const reasoningTimer = setInterval(() => {
        if (reasoningTotal >= 0 && reasoningPiece >= reasoningTotal) return;
        const text = REASONING[reasoningPiece++ % REASONING.length] ?? '';
        if (!text) return;
        push({
          type: 'assistant-stream',
          frame: { type: 'chunk', attemptId, index: chunkIndex++, chunk: { type: 'reasoning-delta', index: 0, text } },
        });
      }, Number(process.env.SMOKE_REASONING_MS ?? 260));
      // 正文增量（`text-delta`）：真宿主在同一个 attempt 里与 reasoning-delta 交替推，
      // 手机端的「逐字吐字」全靠这条。假宿主原来只推 reasoning-delta，于是
      // `collector.ts` 里 `state.text` 恒为空串 → `hub.ingestDelta()` 每次都撞到
      // `if (!text) return` 早退 → 正文只在 turn 结束时以 `assistant/message` 整段到位。
      // 结果就是打字机那条路径在本地冒烟里**从没被触发过**，看着像「效果没生效」
      // （2026-11-01 模拟器核对发现，不是实现 bug，是夹具缺一条路径）。
      //
      // 文案必须与下面 durable 的 `assistant/message` 一字不差，否则整段正文到达时
      // 会与增量拼出来的那份对不上，界面看着像「重打了一遍」。
      const ANSWER = answerText(turn, lastPrompt);
      let textPiece = 0;
      // 一片 3 个字符：按 60ms 一片算，一秒 50 字，比真实模型稍快一点，
      // 这样十来秒的录屏就能看到逐字长出来的全过程，又快到能看出「在长」。
      const textTimer = setInterval(() => {
        if (textPiece >= ANSWER.length) return;
        const slice = ANSWER.slice(textPiece, textPiece + 3);
        textPiece += 3;
        push({
          type: 'assistant-stream',
          frame: { type: 'chunk', attemptId, index: chunkIndex++, chunk: { type: 'text-delta', index: 0, text: slice } },
        });
      }, Number(process.env.SMOKE_TEXT_MS ?? 60));
      let i = 0;
      const pump = setInterval(() => {
        const row: { event: unknown } | undefined = added[i++];
        if (!row) {
          clearInterval(pump);
          clearInterval(reasoningTimer);
          clearInterval(textTimer);
          push({ type: 'assistant-stream', frame: { type: 'end', attemptId } });
          notify('api-session/status', [target, false]);
          notify('api-session/activity', [target, Date.now()]);
          return;
        }
        push(row.event);
      }, Number(process.env.SMOKE_PUMP_MS ?? 700));
    }
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ type: 'server-response', rpcId, result: { ok: true, value: {} } }));
  });
});
/** 打开的 follow 流，按 sessionId 分组：prompt 只该推给那个会话自己的流。 */
const followers = new Map<string, Set<(event: unknown) => void>>();
/** 打开的 $events 通知流（宿主 → 中继的 api-session/* 广播）。 */
const eventStreams = new Set<(value: unknown) => void>();
/**
 * 未结算的 waterfall（审批 / 提问）。
 *
 * 真宿主的 settle 语义是「绑定到投递过它的那个 client」：`startRemoteEvent` 末尾会
 * 遍历所有仍未结算的事件，向**每条新建立的 `$events` 连接**重投一遍；回答必须由
 * 投递过的那个 client 发回，否则静默丢弃。假宿主不模拟这段，「审批卡点完又冒出来」
 * 这条最常见的毛病在本地根本跑不出来（2026-10-28 排查「一直在弹审批」时发现）。
 */
interface PendingWaterfall {
  eventId: string;
  event: string;
  agentId: string;
  request: unknown;
  /** 已经投给过哪些 clientId（真宿主是 client.answers 的 deliveries）。 */
  deliveries: Set<string>;
}
const pendingWaterfalls = new Map<string, PendingWaterfall>();
/** $events 连接自增序号，用来造 clientId。 */
let eventsClientSeq = 0;
/** 当前活着的 $events 连接（真宿主的 answers client 表）。 */
const eventClients = new Set<{ id: string; sink: (value: unknown) => void }>();
/** 把一个未结算事件投给某条 $events 连接。 */
const deliverWaterfall = (pending: PendingWaterfall, clientId: string, sink: (value: unknown) => void) => {
  pending.deliveries.add(clientId);
  sink({
    type: 'waterfall',
    event: pending.event,
    eventId: pending.eventId,
    agentId: pending.agentId,
    request: pending.request,
  });
};
/** 挂起一条审批，立刻投给当前所有连接。 */
/**
 * 像真宿主一样领走队首：发 removedCount=1 的 splice。
 *
 * <p>真宿主的 `ReactLoopInbox.claim` 是 `mutate("next-turn", 0, 1, [], false)`，
 * 落在 session log 里就是一条 `agent/inbox/spliced`。手机侧靠它把排队卡片撤下来
 * —— 收不到就永远挂着（不是 UI bug，是没人告诉它这条已经跑了）。
 */
const claimInbox = (agentId: string) => {
  const bucket = inbox.get(agentId) ?? [];
  if (!bucket.length) return;
  const [claimed] = bucket.splice(0, 1);
  for (const sink of followers.get(agentId) ?? []) {
    sink({
      type: 'agent/inbox/spliced',
      seq: nextExtraSeq(),
      time: Date.now(),
      data: { target: 'next-turn', start: 0, removedCount: 1, inserted: [] },
    });
  }
  log(`inbox claimed ${claimed?.id ?? '?'} for ${agentId} (${bucket.length} left)`);
};

const raiseApproval = (agentId: string, toolName: string, reason: string, durableOnly = false) => {
  const eventId = `approval-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  const callId = `call-${eventId}`;
  const pending: PendingWaterfall = {
    eventId,
    event: 'approval/request',
    agentId,
    request: { toolName, callId, reason, displayReason: { en: reason, zh: reason } },
    deliveries: new Set<string>(),
  };
  if (!durableOnly) {
    pendingWaterfalls.set(eventId, pending);
    for (const client of eventClients) deliverWaterfall(pending, client.id, client.sink);
  }
  // 真宿主还会往 session log 里写两条持久事件（`dsh-user-approval/lib/index.js`
  // 的 request()：先 append `approval/asked`，decide 之后再 append
  // `approval/decided`）。少了这一步，v2 归档里永远没有审批记录，
  // 「手机重装/冷启动后仍能看到那张待答的卡」这条路就从来没被验过。
  //
  // 注意 id：宿主这两个 id 与 $events 的 eventId **毫无关系**（各自 randomUUID），
  // 两边唯一共有的键是 request 里的 callId —— 补假数据时也必须保持这个形状，
  // 否则测试会把「靠 id 对账」这个错误实现验成绿的。
  const durableId = `approval-req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  for (const sink of followers.get(agentId) ?? []) {
    sink({
      type: 'approval/asked',
      seq: nextExtraSeq(),
      time: Date.now(),
      data: { id: durableId, toolName, callId, reason },
    });
  }
  log(`approval raised ${eventId} for ${agentId} (${toolName}) durable=${durableId}`);
  return eventId;
};
/**
 * 按真 DSH 的形状广播一条 api-session/* 通知。
 *
 * 注意两层形状不同：宿主 → 插件的 mux 帧是 {type:'emit', emit:{type,args}}，
 * 而中继转给手机的 $events 流是 {type:'emit', event, args}。之前把 mux 形状
 * 直接塞进 $events，手机端 frame.str('event') 取到空串，日志里就是
 * 「emit  emit」——通知到了但事件名丢了。
 */
const notify = (event: string, args: unknown[]) => {
  for (const push of eventStreams) push({ type: 'emit', event, args });
};
const wss = new WebSocketServer({ noServer: true });
upstream.on('upgrade', (req, socket, head) => wss.handleUpgrade(req, socket, head, ws => {
  ws.on('message', raw => {
    let frame: { type?: string; streamId?: string; endpoint?: string; payload?: { args?: { request?: { address?: { sessionId?: string } } } } };
    try { frame = JSON.parse(raw.toString()); } catch { return; }
    if (frame.type !== 'open' || !frame.streamId) return;
    if (frame.endpoint === '$events') {
      // 真 DSH 通过 $events 推 api-session/* 通知（archiver 靠它触发防抖刷新）。
      // 之前这里直接 return，等于整条「宿主主动通知 → 列表刷新 → running 熄灭」
      // 链路从未被跑过：turn/end 到了但界面一直转圈就是这么暴露不出来的。
      const eventsId = frame.streamId;
      const sink = (value: unknown) => ws.send(JSON.stringify({ type: 'item', streamId: eventsId, value }));
      eventStreams.add(sink);
      // 真宿主开流第一帧一定是 ready，手机记下 clientId 才能回答 waterfall
      // （$events/result 的 clientId 必须是投递过这条事件的那个连接）。
      const clientId = `events-client-${++eventsClientSeq}`;
      eventClients.add({ id: clientId, sink });
      sink({ type: 'ready', clientId });
      // 关键：新连接建立时，把仍未结算的事件全部重投一遍——这就是「点完审批卡
      // 进一次会话又冒出来」的来源，App 侧必须靠本地账本去重才算真的修好。
      for (const pending of pendingWaterfalls.values()) deliverWaterfall(pending, clientId, sink);
      return;
    }
    if (frame.endpoint !== 'session/follow') return;
    const sessionId = frame.payload?.args?.request?.address?.sessionId ?? SESSION_ID;
    const streamId = frame.streamId;
    const send = (value: unknown) => ws.send(JSON.stringify({ type: 'item', streamId, value }));
    // 只有冒烟会话本身有那几条示例记录。新建的会话必须回空 follow——
    // 之前不论 sessionId 一律返回同一份 records，点「新建」进去会看到别的会话的
    // 历史，看起来像「新对话里塞满了旧消息」。
    const all = records(sessionId);
    const cursor = all[all.length - 1]?.event.seq ?? 0;
    const title = sessions.get(sessionId) ?? '新对话';
    send({ type: 'snapshot', header: { id: sessionId, title }, cursor, hasMore: false, records: all });
    // 真 DSH 会在同一条 follow 流上推两种帧：`{type:'assistant-stream', frame}`
    // 是流式增量（思考/正文边写边发），`{type:'event', event}` 是 durable 事件。
    // 这里的调用方有的传整帧（push 传 assistant-stream）、有的只传事件本体
    // （todo/write），所以按 type 分流，不能一律包一层 —— 一律包会把
    // assistant-stream 变成 `{type:'event', event:{type:'assistant-stream'}}`，
    // 插件侧 `value.type === 'assistant-stream'` 那条分支就永远走不到，
    // v2 的思考通道在冒烟链路里是死的（2026-10-30 实测：手机上 reasoning 帧 0 条）。
    const sink = (value: unknown) => {
      const typed = value as { type?: unknown } | null;
      if (typed && typed.type === 'assistant-stream') { send(value); return; }
      send({ type: 'event', event: value });
    };
    const bucket = followers.get(sessionId) ?? new Set<(event: unknown) => void>();
    bucket.add(sink);
    followers.set(sessionId, bucket);
  });
}));
upstream.listen(0, '127.0.0.1');
await once(upstream, 'listening');
const upstreamPort = (upstream.address() as { port: number }).port;
log(`fake upstream on 127.0.0.1:${upstreamPort}`);

// ---- 主机身份：首次注册，之后复用 ----
//
// 每次重启都重新 enroll 会拿到新的 hostId，手机上已配对的旧主机立刻变离线（503
// host-offline），得手工重配对才能继续点。冒烟脚本要能反复重启，所以把 hostId +
// tunnel token 落盘；已有身份就直接连隧道，省掉一次邀请码。
const identityPath = 'artifacts/smoke-host-identity.json';
type Identity = { hostId: string; hostTunnelToken: string };
const loadIdentity = (): Identity | null => {
  try {
    const v = JSON.parse(readFileSync(identityPath, 'utf8')) as Identity;
    return v.hostId && v.hostTunnelToken ? v : null;
  } catch { return null; }
};

let identity = loadIdentity();
if (identity) {
  log(`reusing host ${identity.hostId}`);
} else {
  const invitation = await new Promise<string>((resolve, reject) => {
    https.get({ host: 'localhost', port: 8443, path: '/__local/invitation', ca: CA }, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => { try { resolve(JSON.parse(data).invitation); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
  const hostPublicId = randomBytes(16).toString('hex');
  const hostTunnelToken = randomBytes(32).toString('base64url');
  const enrolled = await new Promise<{ hostId: string }>((resolve, reject) => {
    const body = JSON.stringify({ operationId: randomBytes(16).toString('hex'), hostPublicId, hostTunnelToken, alias: '冒烟假主机', platform: process.platform });
    const req = http.request({ host: '127.0.0.1', port: RELAY_PORT, path: '/control/enroll', method: 'POST', headers: { host: 'relay.local.test', authorization: `Bearer ${invitation}`, 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } }, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => { try { resolve(JSON.parse(data)); } catch (e) { reject(new Error(`enroll ${res.statusCode}: ${data}`)); } });
    });
    req.on('error', reject); req.end(body);
  });
  identity = { hostId: enrolled.hostId, hostTunnelToken };
  writeFileSync(identityPath, JSON.stringify(identity, null, 2));
  log(`enrolled host ${enrolled.hostId}`);
}
const hostTunnelToken = identity.hostTunnelToken;

const getCookie = async () => 'dsh-auth-fake=smoke';

// 归档推送（Hub → Outbox → Pusher）：默认直连 relay（无 TLS）。冒烟里直连会因 Host 路由被拒
// （中继按 Host 分流账户面），所以经 TLS 前置走、由前置改写 Host——
// 自签证书用 SMOKE_INSECURE_TLS=1 放宽（仅本机冒烟）。
const archiverRelayOrigin = process.env.SMOKE_RELAY_ORIGIN ?? `http://127.0.0.1:${RELAY_PORT}`;
if (process.env.SMOKE_INSECURE_TLS === '1') process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
/**
 * 与 worker.ts 同构的装配顺序：pusher 先建（hub 的 onRecord 才有落点），
 * onDrained 指回 hub.retryPending()，否则溢出后记录永远卡在内存里。
 *
 * 这一段必须排在 HostClient 之前 —— 手机的 v2 控制流要在 `control` 工厂里
 * 拿到同一个 hub，才能「焦点 → 立刻回放内存尾部」，而不是等下一次归档拉取。
 */
const outbox = new ArchiveOutbox({ path: null });
const pusher = new ArchivePusher({
  outbox, relayOrigin: archiverRelayOrigin, hostId: identity.hostId, hostTunnelToken,
  onLog: m => log(`pusher ${m}`),
  onDrained: () => { hub.retryPending(); },
});
pusher.start();
const hub = new SessionHub({
  outbox,
  onOverflow: () => log('outbox full: holding records in memory until it drains'),
  onRecord: () => pusher.kick(),
});

/**
 * 手机的 v2 控制流终结在插件这一侧（见 `core/src/mux.ts` 的 `CONTROL_PATH`）。
 *
 * 冒烟里没有 supervisor 盖章的 epoch，就给一个固定值；`HELLO_ACK` 会把它回给手机，
 * 便于在 diag 里确认「连的是哪一代 worker」。
 */
const SMOKE_WORKER_EPOCH = 1;

/**
 * 坑 54 复现开关：`SMOKE_STALL_ACCEPT=1` 让控制流的 `ws-accept` 永远不回。
 *
 * <p>生产现场（2026-10-30）就是这个状态：隧道上流控把握手通知挂住，于是
 * `accept()` 里排在 `await send(ws-accept)` 之后的脉搏定时器**从未被创建**，
 * 而 bridge 已经建好 —— focus 与 records 全部正常，只有脉搏一条都没有。
 * 手机端 12 秒判死重连，插件重放尾部窗口，再卡一次。
 *
 * <p>这个开关用来在模拟器上把那条链路跑出来：开它，App 应当每 12 秒重连一次、
 * 插件日志里一条 `pulse begin` 都没有；关它，两者都应当正常。
 */
const STALL_ACCEPT = process.env.SMOKE_STALL_ACCEPT === '1';

/**
 * 把一个 channel 的 `send` 包一层：`ws-accept` 照样真发出去，但返回的 Promise 永不 resolve。
 *
 * <p>复刻的是生产现场的形态——帧确实到了中继（`packages/relay/src/proxy.ts:186`
 * 收到 `ws-accept` 才 `handleUpgrade`），只是 `Channel.send()` 那条隧道往返
 * （`packages/core/src/transport.ts:128`）的回执迟迟不回来。若像早先那样把帧整个
 * 拦下，中继根本不会建 ws，手机也就连不上，`ControlSession` 收不到 `FOCUS_SESSION`，
 * 脉搏定时器压根不会启动——那就不是复现，是把链路掐断了。
 *
 * <p>用 Proxy 而不是继承 `ControlSession`：被测的正是父类 `accept()` 里
 * 「脉搏定时器建在 `await send(ws-accept)` 之后」这个顺序，整个覆盖就测不到了；
 * 而 `channel` 是私有字段，从外面改不了。包 channel 是唯一不碰被测代码的做法。
 */
function stallAccept(channel: Channel): Channel {
  return new Proxy(channel, {
    get(target, prop, receiver) {
      if (prop !== 'send') return Reflect.get(target, prop, receiver);
      return (frame: { type?: string }) => {
        const real = Reflect.get(target, prop, receiver) as (f: unknown) => Promise<void>;
        const sent = real.call(target, frame);
        if (frame?.type !== 'ws-accept') return sent;
        // 吞掉真实结果（含失败），只留一个永不 settle 的 Promise 给调用方 await。
        void sent.catch(() => {});
        return new Promise<void>(() => {});
      };
    },
  });
}

let client: HostClient | undefined;
client = new HostClient({
  relayUrl: `ws://127.0.0.1:${RELAY_PORT}/tunnel`, hostId: identity.hostId, hostTunnelToken,
  upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie,
  control: raw => {
    const channel = STALL_ACCEPT ? stallAccept(raw) : raw;
    return new ControlSession(channel, {
      hub,
      upstreamOrigin: `http://127.0.0.1:${upstreamPort}`,
      getCookie,
      workerEpoch: SMOKE_WORKER_EPOCH,
      buildHash: 'smoke',
      onLog: m => log(`control ${m}`),
      bufferedBytes: () => client?.tunnel?.queuedBytes() ?? 0,
    });
  },
  onState: s => log(`tunnel ${s}`),
});
// 插件公共配置不会带自定义头；测试通道里用它把 Host 改成中继期望的账户域。
(client.options as { headers?: Record<string, string> }).headers = { Host: 'relay.local.test' };
client.connect();

const collector = new Collector({
  hub, outbox,
  upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie, topK: 5,
  onLog: m => log(`collector ${m}`),
});
collector.start();

// 每 30 秒 bump updatedAt：让会话在列表里保持“最近”，archiver 的轮询有东西可归档。
setInterval(() => { updatedAt = Date.now(); }, 30_000);

const stop = async () => { log('stopping'); await collector.stop(); await pusher.stop(); outbox.close(); await client.stop(); await new Promise<void>(r => upstream.close(() => r())); process.exit(0); };
process.on('SIGINT', () => void stop());
process.on('SIGTERM', () => void stop());
log('fake host running (Ctrl-C to stop)');
