/**
 * `/sync` 的服务端侧统计。
 *
 * <p>为什么需要它：手机侧只能看到「消息晚到」，看不到「到底发了多少轮、每轮多大、
 * 有没有在原地打转」。评审现场的浪费清单（同一批记录被反复下载、32 个会话里 13 个
 * 没有本地正文、常见 31KiB 记录撞 16KiB 预算）全部是**服务端早知道**的事实——只要
 * 有人数过。这里就是那个计数器。
 *
 * <p>放中继而不是插件侧：`/sync` 根本不经过插件（中继自己答，桌面离线也能用），
 * 所以只有中继看得见这些轮次。
 *
 * <p>不重置、只累计：`/metrics` 拉的是计数器，两次拉取的差值才是速率。重置过一次的
 * 计数器在长跑进程里会自相矛盾（同一个数既表示「今天」又表示「这一分钟」）。
 */
export class SyncStats {
  /** 累计处理的 `/sync` 请求数（一次请求 = 一次调用，不是一个会话）。 */
  requests = 0;
  /** 累计返回的会话差量个数：一个会话一页算一个。 */
  pages = 0;
  /** 累计返回的归档记录条数。 */
  records = 0;
  /** 累计返回的字节数（每条记录候选的 JSON 字节，含被过滤后的 event）。 */
  bytes = 0;
  /**
   * 累计「零进展」次数：服务端说 `hasMore`，但 `nextCursor` 与请求游标一样。
   *
   * <p>稳态下必须是 0。它不是正常的分页（正常分页游标一定前进），而是
   * 「预算/体积让这一轮什么也装不下」——客户端会因此原地打转，是最该报警的一种。
   */
  zeroProgress = 0;
  /**
   * 累计「同一个会话用同一个游标又问了一次」的次数。
   *
   * <p>这是**重复下载**最直接的温度计：正常情况下要过一次就往前走，同一个游标
   * 再来必然意味着上一轮的记录没有落盘、或者客户端没记住游标。评审现场同一批
   * 3/28/21 条记录被反复下载了十几分钟，在这里会是一条持续上升的曲线。
   */
  repeats = 0;

  /** 上一次**真的服务过**该会话时，客户端报上来的游标。 */
  private readonly lastAsked = new Map<string, number>();

  /** 开始一轮请求，返回本轮的行号；[end] 用它收尾。 */
  begin(): number {
    this.requests++;
    return this.requests;
  }

  /** 记一次会话差量。 */
  notePage(hostId: string, sessionId: string, askedCursor: number, nextCursor: number, recordCount: number, bytes: number, hasMore: boolean): void {
    this.pages++;
    this.records += recordCount;
    this.bytes += bytes;
    // 零进展：说了还有，却一条也没多给。游标没动就是没进展。
    if (hasMore && nextCursor <= askedCursor) this.zeroProgress++;
    // 同会话同游标又问一次 = 上一轮给了它的记录**没有落盘**（或没记住游标）。
    // 这里比对的是「客户端报上来的游标」而不是「服务端上次回到的位置」：前者
    // 才代表客户端的真实进度，重复的成因也全在客户端这一侧。
    // 游标 0 同样要算——评审现场 32 个会话里 13 个就是这么空转的。
    const key = `${hostId}/${sessionId}`;
    const previous = this.lastAsked.get(key);
    if (previous !== undefined && previous === askedCursor) this.repeats++;
    this.lastAsked.set(key, askedCursor);
  }

  /** 运维快照；字段名与 `/metrics` 保持一致。 */
  snapshot(): { requests: number; pages: number; records: number; bytes: number; zeroProgress: number; repeats: number } {
    return {
      requests: this.requests,
      pages: this.pages,
      records: this.records,
      bytes: this.bytes,
      zeroProgress: this.zeroProgress,
      repeats: this.repeats,
    };
  }
}
