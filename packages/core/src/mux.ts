/** v2 control stream — the one channel the phone keeps open for a session.
 *
 *  In v1 the phone connected to the host's `/api/remote.mux` and parsed DSH's own
 *  envelopes, so the phone had to know DSH's event vocabulary, the relay had to
 *  agree on which of those events were "worth forwarding" (`filter.ts`'s
 *  whitelist, and three separate bugs caused by getting that list wrong), and the
 *  live path produced a different shape from the archived path — two parsers that
 *  had to agree by hand.
 *
 *  v2 removes the phone's access to `/api/remote.mux` entirely. The phone opens a
 *  single websocket to {@link CONTROL_PATH}; the *plugin* terminates it, replays
 *  the tail of the session from its own buffer, then streams TimelineRecords.
 *  Live records and archived records are then byte-identical, which is what lets
 *  the phone have exactly one projector.
 *
 *  Why a separate path instead of frames on the mux stream: the plugin bridges the
 *  phone's mux websocket at the byte level to the host. Intercepting frames there
 *  would mean JSON-parsing the lowest-latency stream in the system to find frames
 *  that are not ours. A dedicated path costs one `ws-open` and zero interception.
 *
 *  Wire format is one JSON object per websocket text frame, tagged with
 *  {@link MUX_V2_TAG} so a stray frame from another component is rejected rather
 *  than misread.
 */

import { SYNC_V2, type RecordKind, type TimelineRecord } from './records.js';

export const MUX_V2_TAG = 'dsh-v2';
export const CONTROL_PATH = '/__dsh/v2/control';

/** Why a focused session stopped replaying. The phone decides whether to call
 *  `/v2/sync` from this, not from the presence/absence of frames. */
export const STOP_REASONS = [
  'BUDGET_EXHAUSTED',
  'TAIL_COMPLETE',
  'QUEUE_EMPTY',
  'NOT_FOCUSED',
  'UNKNOWN_SESSION',
  'SHUTDOWN',
] as const;
export type StopReason = (typeof STOP_REASONS)[number];

/** Host liveness as seen from the plugin — the only component that can tell a
 *  long silent reasoning step apart from a dead tunnel. */
export type HostState = 'THINKING_SILENT' | 'STREAMING' | 'IDLE';

export interface SessionPulse {
  type: 'SESSION_PULSE';
  sessionId: string;
  /** Newest seq the plugin has assigned for this session. */
  latestSeq: number;
  /** Newest seq the relay has acknowledged into the archive. */
  archiveCoveredSeq: number;
  hostState: HostState;
  /** How long the host has produced nothing for this session. */
  silentDurationMs: number;
  /** Plugin's own send backlog, so the phone can tell "slow host" from "slow link". */
  tunnelBufferBytes: number;
  workerEpoch: number;
}

export interface RecordsFrame {
  type: 'RECORDS';
  sessionId: string;
  records: TimelineRecord[];
  /** True when the plugin still holds more for this session in the tail window. */
  hasMore: boolean;
  stopReason?: StopReason;
}

export interface FocusCommand {
  type: 'FOCUS_SESSION';
  sessionId: string;
  /** Newest seq the phone has committed. The replay starts strictly after this. */
  sinceSeq: number;
  /** Phone asks for at most this many bytes in the immediate replay burst. */
  byteBudget?: number;
}

export interface UnfocusCommand {
  type: 'UNFOCUS_SESSION';
  sessionId: string;
}

/** A write the phone wants acknowledged. Carries the idempotency key so the
 *  HTTPS side-channel can retry the exact same operation (risk #3: a control
 *  frame lost in a dead socket must not lose the user's approval). */
export interface CommandFrame {
  type: 'CMD';
  cmdId: string;
  op: 'prompt' | 'interaction' | 'queue' | 'cancel';
  payload: unknown;
}

export interface CommandAck {
  type: 'CMD_ACK';
  cmdId: string;
  ok: boolean;
  error?: string;
  /**
   * 插件从解出这条 `CMD` 到宿主答复之间的毫秒数（T3→T4）。
   *
   * <p>只报**时长**不报时间戳：两端时钟没有可比性（手机与电脑可能差几十秒），
   * 相减会得到负数或者几分钟，比没有更糟。时长是同一台机器上量的，可以安全地
   * 从手机收到 ack 的那一刻往前推。
   *
   * <p>可选：老插件不报，手机侧就只记 T1 与 T4。
   */
  pluginMs?: number;
}

export interface HelloFrame {
  type: 'HELLO';
  /** Phone build hash, so a mismatch shows up in the settings page instead of in
   *  a mysterious rendering difference. */
  buildHash: string;
  capabilities: string[];
}

/**
 * 一条审批/提问瀑布。
 *
 * <p>它**不是会话记录**：没有 seq、不属于某个会话的编号序列，只在宿主「正在问」
 * 的那段时间里存在。所以它不折进 `RECORDS`，而是单独一帧——否则手机上要么给它
 * 编一个假 seq（会污染游标），要么被当成流式增量吞掉。
 *
 * <p>`payload` 原样透传宿主 `$events` 的值：手机的卡片刻画器本来就会解它（v1 时代
 * 走的就是这条数据），中继与插件都不该多一次解析。
 *
 * <p>为什么必须搬过来：v1 的 `$events` 逻辑流是手机自己开的一条 WS，中继随 v1 路由
 * 一起删掉了。审批是**唯一**不能在断线后靠「重放会话记录」补回来的东西——它不落盘。
 * 所以插件侧的尾窗（见 hub 的 `publishInteraction`）是它唯一的持久性来源。
 */
export type InteractionFrame = {
  type: 'INTERACTION';
  kind: 'waterfall' | 'cancel';
  payload: unknown;
};

export type PhoneToPlugin = FocusCommand | UnfocusCommand | CommandFrame | HelloFrame;
export type PluginToPhone =
  | RecordsFrame
  | SessionPulse
  | CommandAck
  | InteractionFrame
  | { type: 'HELLO_ACK'; buildHash: string; capabilities: string[]; workerEpoch: number }
  | { type: 'ERROR'; code: string; message: string };

/** Frames the plugin may inject into the phone's record stream. */
export type StreamingRecordKind = Extract<RecordKind, 'STREAM_DELTA'>;

export function encodeMuxFrame(frame: PluginToPhone | PhoneToPlugin): string {
  return JSON.stringify({ tag: MUX_V2_TAG, ...frame });
}

/** Returns `undefined` for anything not addressed to this protocol. A malformed
 *  frame is *not* an error: the mux stream carries foreign frames during a
 *  rolling upgrade and the receiver must ignore them, not tear down the socket. */
export function decodeMuxFrame(raw: string): (PluginToPhone | PhoneToPlugin) | undefined {
  if (!raw.startsWith('{')) return undefined;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (typeof value !== 'object' || value === null) return undefined;
  const dict = value as Record<string, unknown>;
  if (dict.tag !== MUX_V2_TAG) return undefined;
  if (typeof dict.type !== 'string') return undefined;
  const { tag: _tag, ...rest } = dict;
  return rest as unknown as PluginToPhone | PhoneToPlugin;
}

/** Builds a pulse. Exported so the plugin's emitter and the tests build them the
 *  same way; every field is required because a phone-side heuristic that reads a
 *  missing field is how "silently never pulses" ships. */
export function pulse(input: {
  sessionId: string;
  latestSeq: number;
  archiveCoveredSeq: number;
  hostState: HostState;
  silentDurationMs: number;
  tunnelBufferBytes: number;
  workerEpoch: number;
}): SessionPulse {
  return { type: 'SESSION_PULSE', ...input };
}

/** Records the plugin may legally put on the wire: streaming deltas are live-only
 *  and never archived, everything else is a settled fact. */
export function isLiveOnly(kind: RecordKind): boolean {
  return kind === 'STREAM_DELTA';
}
