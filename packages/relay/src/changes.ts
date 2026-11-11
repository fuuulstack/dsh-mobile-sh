import type { IncomingMessage, ServerResponse } from 'node:http';

/**
 * 会话变更的写扩散总线（对齐 OpenIM 的 write diffusion sync）。
 *
 * <p>OpenIM 的做法是：服务端在数据写入的那一刻就记下「哪些用户有哪些会话变了」，
 * 客户端上线后直接消费这份变更清单，而不是「先收到一个『有变化』的信号，再自己去
 * 拉一遍」。后者的代价在我们这套链路上被放大成了 8～30 秒：信号先到，`/sync` 却
 * 常常在宿主落库之前发出，拉回 0 条，只能等下一轮轮询。
 *
 * <p>这里承担的是「服务端」那一半：插件把归档记录推上来时，顺带把每个会话的最新
 * 位置（latestSeq）与首行预览记进一条变更，直接推给所有在线手机。手机端因此不再
 * 需要额外一次往返——推送本身就是可渲染的数据。
 *
 * <p>刻意保持无状态：不持久化、不做重放。断线期间的变更由手机重连后走一次
 * {@code /sync} 按游标补齐（OpenIM 的 {@code sync_once()} 也是这个定位），
 * 推送只负责「在线时的实时性」。
 */
export interface Change {
  /** 会话 id。 */
  sessionId: string;
  /** 这条变更之后该会话的最新 seq；手机拿它和本地游标比，落后才需要补拉。 */
  latestSeq: number;
  // 这里曾经有一个 `preview`：插件从归档记录里算出一条「首行」，随变更推给手机。
  // 2026-10-28 下线——它和手机本地真正落盘的消息是两条独立水位，会出现「列表里
  // 已经看到最新一行、点进会话却没有这条回复」。首行现在由手机用本地消息现算。
  /** 会话标题；新会话第一次出现时手机靠它建行。 */
  title: string;
  /** 是否还在跑；列表上的运行点。 */
  running: boolean;
  /** 变更发生时刻（epoch ms），用于排序与日志。 */
  at: number;
  /**
   * 这次变更自带的记录正文（已按归档口径过滤过）。
   *
   * <p>为什么把正文塞进变更帧：原来 SSE 只推「你变了，最新到 seq=N」，手机收到
   * 再发一次 `/sync`。两个问题——①多一次公网往返（弱网下 200ms～2s 起步）；
   * ②那条 `/sync` 发出时差量已经落库了才成立，一旦插件还在推、归档事务没提交，
   * 手机就拉回 0 条，只能等下一轮信号。中继在 publish 之前已经 `archiveRecords`
   * 落库（server.ts `/control/archive`），所以此刻推的正文一定可读。
   *
   * <p>预算不足（单会话或整批超限）时截断并把 {@link truncated} 置真，手机按游标
   * 走一次 `/sync` 补完——退化路径和以前完全一样。
   */
  records?: unknown[];
  /** records 是否被字节预算截断（true 表示手机还得补一次 /sync）。 */
  truncated?: boolean;
}

type Subscriber = {
  hostId: string;
  deviceId: string;
  /** 返回 true 表示这条变更被收下了（背压时由 sink 自己丢弃并 `noteDropped()`）。 */
  write: (change: Change) => boolean;
  close: () => void;
};

/**
 * 重入深度上限。
 *
 * <p>这里**不是**「慢客户端待发队列」——`sink()` 是同步调用，`pending++/--` 在一帧之内
 * 就闭合，慢客户端一条都拦不住（真正能说明堵住的是 `streamChanges` 里那个
 * `res.write()` 返回 false + 此后没 drain，见 {@link streamChanges}）。留着它只为
 * 拦住 `sink` 内部同步回头再 `publish` 的递归扇出。
 */
const REENTRANCY_LIMIT = 256;

export class ChangeBus {
  private readonly subs = new Set<Subscriber>();
  /** 累计被慢消费者丢弃的变更条数。不重置：/metrics 拉的是计数器，差值才看速率。 */
  private droppedCount = 0;
  /** 累计「写出去但内核缓冲没吃完」的次数，即真实背压发生的次数。 */
  private drainCount = 0;

  /** 由 SSE 写路径回报一次真实背压（`res.write()` 返回 false）。 */
  noteDrain(): void { this.drainCount++; }

  /**
   * 由 SSE 写路径回报一次丢弃。
   *
   * <p>丢的永远是**变更通知**，归档记录一条不丢——手机重连后按归档游标
   * `/sync` 就能补齐。这条边界必须守住：丢通知只是延迟，丢记录就是丢消息。
   */
  noteDropped(): void { this.droppedCount++; }

  /**
   * 登记一条订阅。`hostId` 为空表示这个账号下的全部主机。
   *
   * @returns 解绑函数；调用方在连接关闭时必须调用，否则订阅集合会泄漏。
   */
  subscribe(hostId: string, deviceId: string, sink: (change: Change) => boolean | void): () => void {
    let closed = false;
    let pending = 0;
    const sub: Subscriber = {
      hostId,
      deviceId,
      write: (change) => {
        if (closed) return false;
        if (pending >= REENTRANCY_LIMIT) { this.droppedCount++; return false; }
        pending++;
        try { return sink(change) !== false; } finally { pending--; }
      },
      close: () => { closed = true; },
    };
    this.subs.add(sub);
    return () => { sub.close(); this.subs.delete(sub); };
  }

  /**
   * 把一批变更推给匹配的订阅者。
   *
   * @returns 真正被订阅者收下的条数；背压/已关闭的那些不计入。
   */
  publish(hostId: string, changes: Change[]): number {
    if (changes.length === 0 || this.subs.size === 0) return 0;
    let delivered = 0;
    for (const sub of this.subs) {
      if (sub.hostId && sub.hostId !== hostId) continue;
      for (const change of changes) { if (sub.write(change)) delivered++; }
    }
    return delivered;
  }

  /** 当前订阅数；运维指标与测试用。 */
  size(): number { return this.subs.size; }

  /** 运维快照：在线订阅数、累计丢弃条数、累计等待 drain 的次数。 */
  stats(): { online: number; dropped: number; drains: number } {
    return { online: this.subs.size, dropped: this.droppedCount, drains: this.drainCount };
  }
}

/**
 * 把一条订阅写成 SSE 响应。
 *
 * <p>用 SSE 而不是再开一条 WebSocket：这条流只下行、帧很小、天然断线重连
 * （浏览器与 okhttp 都认 `text/event-stream`），而且不必再和隧道抢 WS 预算。
 * `retry:` 让客户端在 3 秒后自动重连，和 OpenIM 的心跳重连节奏接近。
 */
export function streamChanges(
  req: IncomingMessage,
  res: ServerResponse,
  bus: ChangeBus,
  hostId: string,
  deviceId: string,
  onOpen?: () => void,
): void {
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-store, no-transform',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',
  });
  res.write('retry: 3000\n\n');
  onOpen?.();
  /**
   * 真实的「这条订阅堵了没有」。
   *
   * <p>`ChangeBus` 里那个同步 `pending` 计数器**不代表 socket 队列**：`sink()` 里
   * 调 `res.write()` 立刻返回，数据只进了 Node 的用户态/内核缓冲，`pending++/--`
   * 在一帧之内就闭合了，永远是 0。真正能说明堵住的只有两件事——`res.write()`
   * 返回 false（高水位已到），以及这期间一直没有 `drain`。
   */
  let backedUp = false;
  const unsubscribe = bus.subscribe(hostId, deviceId, (change) => {
    if (res.writableEnded) return false;
    // 上一帧还没 drain 完就别再往里塞：SSE 丢一帧不会丢数据（手机按归档游标
    // 补拉），但把内核缓冲灌爆会拖垮整个中继进程。
    if (backedUp) {
      bus.noteDropped();
      return false;
    }
    const ok = res.write(`event: change\ndata: ${JSON.stringify(change)}\n\n`);
    if (!ok) {
      backedUp = true;
      bus.noteDrain();
      res.once('drain', () => { backedUp = false; });
    }
    return true;
  });
  // 心跳：中间代理会在空闲约 60 秒时掐断长连接，15 秒一次注释帧最省事。
  const beat = setInterval(() => { if (!res.writableEnded && !backedUp) res.write(': ping\n\n'); }, 15000);
  beat.unref();
  const done = () => { clearInterval(beat); unsubscribe(); if (!res.writableEnded) res.end(); };
  req.once('close', done);
  res.once('close', done);
  res.once('error', done);
}
