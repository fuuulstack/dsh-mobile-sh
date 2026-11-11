/**
 * 槽位注册逻辑（不依赖 React，可在 Node 里单测）。
 *
 * <p>为什么把这层单独拆出来：`browser.ts` 必须 `import react`（宿主模块表提供），
 * 而 Node 侧（单测、SSR）没有这个包——把它和注册逻辑写在一起，测试就得为了验证
 * 「两个槽都发出 inject」而先造一个 React 假实现。拆开之后：注册逻辑纯对象操作，
 * 组件留在 `browser.ts`，两边各自可测。
 *
 * <p>**核心约束（2026-10-27 实测踩坑）**：不要用 `slots.spec(key)` 做前置判断。
 * `sidebar.panellist` 是 `@deepseek-ai/dsh-client-ui-sidebar` 在它自己的 apply 里
 * 才声明的子槽；`slots.inject(key, cb)` 的语义正是「等这个槽出现再回调」。早期版本
 * 在 apply 里先 spec() 再决定注不注册，一旦本插件被排到 ui-sidebar 之前加载
 * （`dsh.client.immediately: true` 就会），spec() 拿到 undefined → 永久 return →
 * 侧栏没有这一行，而控制台、宿主日志、graph 全都没有任何异常，极难定位。
 */

/** 侧栏行与主区共用的面板 id。 */
export const PANEL_ID = 'mobile-sh';

/** 面板行的排序位：插件（0）、任务看板（10）之后。 */
export const PANEL_ORDER = 20;

/** 本模块用到的最小 slots 面（与宿主 `ctx.slots` 的结构子集一致）。 */
export interface SlotsFace {
  inject(key: string, callback: () => unknown): () => void;
  register(options: Record<string, unknown>, component: (props: Record<string, unknown>) => unknown): () => void;
  /** 只用于诊断；注册流程**不得**依赖它的返回值。 */
  spec?(key: string): unknown;
}

/** 侧栏行的显示名（宿主用 `resolveSlotLabel` 解析这个回调）。 */
export const PANEL_LABEL = '手机访问';

/**
 * 把面板注册到两个槽位。
 *
 * @param slots 宿主槽位服务
 * @param components 两个槽位的组件（由 `browser.ts` 用 React 构造）
 * @returns disposer；注册过程中出错时先摘掉已完成的那一半再抛出
 */
export function registerPanel(
  slots: SlotsFace,
  components: {
    main: (props: Record<string, unknown>) => unknown;
    sidebar: (props: Record<string, unknown>) => unknown;
  },
): () => void {
  const disposers: Array<() => void> = [];
  try {
    // main 是 keyed 槽：`key` 必须与侧栏行的 id 一致，否则点行切不到这个面板。
    disposers.push(slots.inject('main', () => slots.register({ name: 'main', key: PANEL_ID }, components.main)));
    // sidebar.panellist 是 list 槽：id 即面板 id，order 决定排序位。
    disposers.push(slots.inject('sidebar.panellist', () => slots.register({
      name: 'sidebar.panellist',
      id: PANEL_ID,
      order: PANEL_ORDER,
      label: () => PANEL_LABEL,
    }, components.sidebar)));
  } catch (error) {
    // 注册失败必须把自己已注册的那一半摘掉，否则会出现「侧栏有行、主区空白」。
    for (const dispose of disposers.splice(0)) dispose();
    throw error;
  }
  return () => { for (const dispose of disposers.splice(0)) dispose(); };
}
