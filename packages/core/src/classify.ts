/** Host event → TimelineRecord classification.
 *
 *  Lives in `core` rather than in the plugin because three places must agree on
 *  it: the plugin (which stamps records), the relay's tests (which assert nothing
 *  is filtered), and the phone's projector tests (which build fixtures). When
 *  each side had its own guess about "what event is this", they drifted.
 *
 *  The rule is deliberately dumb: `kind` is a coarse bucket for indexing and
 *  ordering, and the raw event travels untouched in `payload`. Anything the
 *  classifier has never seen becomes `META_PATCH` and is still forwarded and
 *  archived — that is the difference between this and the whitelist it replaces,
 *  where an unknown type had its payload erased.
 */

import type { RecordKind } from './records.js';

const isDict = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

/** Human-visible text of a message content array. */
export function contentText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  let out = '';
  for (const part of content) {
    if (typeof part === 'string') { out += part; continue; }
    if (!isDict(part)) continue;
    if (part.type === 'text' && typeof part.text === 'string') out += part.text;
  }
  return out;
}

/** Reasoning text inside a message content array. */
export function contentReasoning(content: unknown): string {
  if (!Array.isArray(content)) return '';
  let out = '';
  for (const part of content) {
    if (isDict(part) && part.type === 'reasoning' && typeof part.text === 'string') out += part.text;
  }
  return out;
}

export function hasToolCall(content: unknown): boolean {
  if (!Array.isArray(content)) return false;
  return content.some(part => isDict(part) && part.type === 'tool-call');
}

const num = (v: unknown): number | undefined =>
  typeof v === 'number' && Number.isFinite(v) ? v : undefined;

export interface Classification {
  kind: RecordKind;
  /** Stable identity for the turn this event belongs to. */
  turnId: string;
  stepId?: string;
}

const APPROVAL_TYPES = new Set([
  'approval/request',
  'approval/asked',
  'approval/needed',
]);
const APPROVAL_DONE = new Set([
  'approval/decided',
  'approval/resolved',
  'approval/answered',
]);
const QUESTION_TYPES = new Set(['question/request', 'question/asked']);
const QUESTION_DONE = new Set(['question/answered', 'question/decided', 'question/resolved']);

/** Classifies one host event.
 *
 *  `turnId` is derived from the host's own turn counter, which is 0-based and
 *  restarts per session — so it is namespaced with the session id. Events that
 *  carry no turn (task list writes, inbox splices, title changes) inherit the
 *  caller's "current turn", because regrouping them under a synthetic turn of
 *  their own would put the task pill in a timeline row that never existed.
 */
export function classify(event: unknown, sessionId: string, currentTurnId: string): Classification {
  const e = isDict(event) ? event : {};
  const type = typeof e.type === 'string' ? e.type : '';
  const data = isDict(e.data) ? e.data : {};
  const turn = num(data.turn);
  const step = num(data.step);
  const turnId = turn === undefined ? currentTurnId : `${sessionId}:t${turn}`;

  let kind: RecordKind;
  if (type === 'user/message') {
    const source = isDict(data.source) ? data.source : undefined;
    const sourceKind = source && typeof source.kind === 'string' ? source.kind : 'user';
    // Runtime context, skill catalogs and memory snapshots are written back as user
    // messages. They are not the user's words: rendering them as bubbles is the bug
    // that made a fresh session open with a wall of injected text.
    kind = sourceKind === 'user' ? 'USER_INPUT' : 'META_PATCH';
  } else if (type === 'assistant/message') {
    kind = 'ASSISTANT_TEXT';
  } else if (type === 'assistant/attempt') {
    kind = hasToolCall(data.content) ? 'STEP_TOOL' : 'STEP_THINKING';
  } else if (type === 'tool/call' || type === 'tool/result') {
    kind = 'STEP_TOOL';
  } else if (type === 'turn/end') {
    kind = 'TURN_END';
  } else if (type === 'agent/inbox/spliced') {
    // A real session-log event with a `seq`, unlike the approval waterfall: the
    // host applies this same splice to its own inbox, so the phone can replay it
    // and hold the queue without asking `/rpc/projections` (which used to fail
    // with ECONNRESET and leave the cards frozen).
    kind = 'INBOX_SPLICE';
  } else if (type === 'todo/write') {
    // Whole-list replacement, and it carries a `seq` — so the task pill survives
    // a cold start instead of waiting for a projections round trip.
    kind = 'TASK_LIST';
  } else if (APPROVAL_TYPES.has(type)) {
    kind = 'APPROVAL_REQ';
  } else if (APPROVAL_DONE.has(type)) {
    kind = 'APPROVAL_RES';
  } else if (QUESTION_TYPES.has(type)) {
    kind = 'QUESTION_REQ';
  } else if (QUESTION_DONE.has(type)) {
    kind = 'QUESTION_RES';
  } else {
    // step/start, step/end, turn/start, session/title, job/*, and anything that
    // did not exist when this file was written. All of it is carried verbatim;
    // none of it is dropped.
    kind = 'META_PATCH';
  }
  const stepId = step === undefined ? undefined : `${turnId}:s${step}`;
  return stepId === undefined ? { kind, turnId } : { kind, turnId, stepId };
}

/** True for events that only describe structure, not content — used by the
 *  plugin to decide what belongs in the live tail window. */
export function isStructural(kind: RecordKind): boolean {
  return kind === 'META_PATCH';
}
