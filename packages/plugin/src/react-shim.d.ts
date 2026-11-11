/**
 * 最小 `react` 类型声明（只覆盖 `browser.ts` 用到的面）。
 *
 * <p>为什么不在 package.json 里加 `react` / `@types/react`：客户端半是**宿主提供 React**
 * 的插件 bundle（`dsh.client` 的共享模块表里已有 React），本地装一份会让
 * `pnpm/npm` 把它当成真实依赖，构建守卫也会因此误判「内联了 React」。
 * 这里只声明类型，运行时由宿主的 `require('react')` 供给，esbuild 侧保持 external。
 *
 * <p>面窄是有意的：客户端半只做槽位注册与一个 iframe，用到的只有
 * `createElement` 与 `ReactNode`。真需要更多 API 时再往这里补。
 */
declare module 'react' {
  /** 渲染节点：这里只要求「能交给宿主渲染」，不做结构约束。 */
  type ReactNode = unknown;

  /** 元素工厂的最小签名（属性为任意对象）。 */
  function createElement(type: unknown, props?: Record<string, unknown> | null, ...children: unknown[]): ReactNode;

  export { createElement };
  export type { ReactNode };
}
