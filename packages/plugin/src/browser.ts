/**
 * 浏览器侧（Client half）：在左侧栏挂一个「手机访问」面板，面板里内嵌宿主自己的设置页。
 *
 * <p>为什么用 iframe 而不是 React 组件：设置页要收中继鉴权密码、要画配对二维码，
 * 两者都由宿主进程渲染（见 `ui-routes.ts`）。iframe 让这段 UI 与主 SPA 的状态/样式
 * 完全隔离，客户端半因此只依赖 `slots` 一个服务，不需要宿主主题 token，
 * 也不会因为宿主升级改样式而崩。
 *
 * <p>槽位注册逻辑在 `panel.ts`（不依赖 React，可单测）；本文件只负责组件与入口。
 *
 * <p>打包形态：DSH 的客户端 bundle 是 lazy-CJS，由
 * `window.__ModuleLoader__.load({id, factory})` 注册，factory 收到 `require`。
 * `scripts/build.mjs` 把 esbuild 的 CJS 产物包进这个外壳（见 build.mjs 的
 * `buildClientHalf`），所以本文件是**普通 ES 模块**，不需要自己写 factory。
 */
import * as React from 'react';
import { PANEL_ID, registerPanel, type SlotsFace } from './panel.js';

export { PANEL_ID };

/** 客户端插件名（与宿主侧 `dsh.client` 声明的包名一致）。 */
export const name = 'dsh-mobile-sh/client';

/** 必需服务：只有槽位系统是硬依赖。 */
export const inject = ['slots'];

/** 设置页地址（同源，由宿主路由提供）。 */
const PAGE_URL = '/dsh-mobile-sh/embed';

/** 最小客户端上下文面。 */
interface ClientContext {
  get?(name: string): unknown;
  effect?(fn: () => unknown, label?: string): void;
}

/** 侧栏图标：手机轮廓 + 信号弧，与宿主其余行同为 16×16 描边风格。 */
function PanelIcon(): React.ReactNode {
  return React.createElement(
    'svg',
    {
      viewBox: '0 0 16 16',
      width: 16,
      height: 16,
      fill: 'none',
      stroke: 'currentColor',
      strokeWidth: 1.3,
      strokeLinecap: 'round',
      strokeLinejoin: 'round',
      'aria-hidden': true,
    },
    React.createElement('rect', { x: 4.5, y: 1.5, width: 7, height: 13, rx: 1.6 }),
    React.createElement('path', { d: 'M7 12.6h2' }),
    // 两道信号弧：表示「远程可达」，与图标语义（手机访问）对应。
    React.createElement('path', { d: 'M2.2 4.6a4 4 0 0 0 0 6.8' }),
    React.createElement('path', { d: 'M13.8 4.6a4 4 0 0 1 0 6.8' }),
  );
}

/**
 * 主区内容：一个铺满可用高度的 iframe。
 *
 * <p>高度用 `100%` 而不是视口计算：主区的可滚动容器由宿主决定，
 * 写死 `100vh` 会在有顶栏/底部输入区时溢出。
 */
function PanelPage(): React.ReactNode {
  return React.createElement('div', {
    style: { height: '100%', minHeight: 0, overflow: 'auto', WebkitOverflowScrolling: 'touch' },
  }, React.createElement('iframe', {
    src: PAGE_URL,
    title: 'DSH 手机远程访问设置',
    // allow-same-origin 保留同源 cookie（页面要靠它做会话准入）。
    sandbox: 'allow-same-origin allow-scripts allow-forms allow-popups',
    style: { display: 'block', width: '100%', height: '100%', minHeight: '640px', border: '0', background: 'transparent' },
  }));
}

/** 侧栏行：只放图标（宿主在行尾另画 label；折叠成 rail 时也只显示图标）。 */
function SidebarRow(): React.ReactNode {
  return React.createElement(
    'span',
    { style: { display: 'inline-flex', alignItems: 'center', gap: 8 } },
    React.createElement(PanelIcon, null),
  );
}

/**
 * 客户端入口。
 *
 * <p>只调 `slots.inject`，**不做 `spec()` 前置判断**——槽位可能由别的插件稍后声明，
 * 见 `panel.ts` 的模块注释（这是实测踩过的坑）。
 *
 * @param ctx 客户端 cordis 上下文
 */
export function apply(ctx: ClientContext): void {
  const slots = (ctx.get?.('slots') ?? (ctx as unknown as Record<string, unknown>).slots) as SlotsFace | undefined;
  if (slots === undefined || typeof slots.inject !== 'function' || typeof slots.register !== 'function') {
    // 没有槽位系统（老宿主 / headless 组合）：静默不挂载，页面本身仍可通过
    // /dsh-mobile-sh 直接访问。
    return;
  }
  let dispose: () => void;
  try {
    dispose = registerPanel(slots, { main: PanelPage, sidebar: SidebarRow });
  } catch (error) {
    console.error('[mobile-sh] panel registration failed:', error);
    return;
  }
  // cordis 语义：回调体立即执行，**返回值**才是卸载时的 disposer。
  ctx.effect?.(() => () => dispose(), 'mobile-sh: sidebar panel');
}
