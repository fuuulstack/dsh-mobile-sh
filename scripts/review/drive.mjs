// UI 品味评审驱动（mobilewright SDK，与 Mobile MCP 同引擎）。
//
// 用法：
//   node scripts/review/drive.mjs <step> [args...]
//
// step 一览：
//   launch                启动 App 并输出首页树
//   dump <name>           截图 + 可访问性树
//   tap <text|@label|#id> 按可访问性选择器点击
//   tapxy <x> <y>         坐标点击
//   longpress <x> <y>     长按
//   swipe <up|down|left|right>
//   back                  系统返回
//   shot <name>           仅截图
//
// 所有产物落在 artifacts/review/。
import { android, expect } from 'mobilewright';
import { mkdirSync, writeFileSync } from 'node:fs';

const OUT = 'artifacts/review';
mkdirSync(OUT, { recursive: true });

const BUNDLE = 'com.dsh.mobile.v2';
const [step, ...rest] = process.argv.slice(2);

/** 把 viewTree 拍平成「可读行」，便于评审时快速浏览。 */
function flatten(nodes, depth = 0, out = []) {
  for (const n of nodes) {
    const text = (n.text ?? '').trim();
    const label = (n.label ?? n.contentDescription ?? '').trim();
    const id = (n.identifier ?? '').trim();
    const cls = String(n.type ?? '').split('.').pop();
    const b = n.bounds ?? {};
    if (text || label || id) {
      out.push({
        depth,
        cls,
        text,
        label,
        id,
        visible: n.isVisible,
        clickable: n.isEnabled && (n.clickable ?? undefined),
        bounds: `${b.x},${b.y} ${b.width}x${b.height}`,
      });
    }
    if (n.children?.length) flatten(n.children, depth + 1, out);
  }
  return out;
}

const d = await android.launch({ deviceName: /qb-api35/ });
try {
  const screen = d.screen;

  if (step === 'launch') {
    await d.launchApp(BUNDLE, { autoAppLaunch: true });
    await new Promise((r) => setTimeout(r, 2500));
  }

  switch (step) {
    case 'launch':
    case 'dump': {
      const name = rest[0] ?? step;
      const tree = await screen.viewTree();
      writeFileSync(`${OUT}/${name}.tree.json`, JSON.stringify(tree, null, 2));
      writeFileSync(`${OUT}/${name}.lines.json`, JSON.stringify(flatten(tree), null, 2));
      writeFileSync(`${OUT}/${name}.png`, await screen.screenshot());
      console.log(`[${name}] nodes=${tree.length}`);
      for (const row of flatten(tree)) {
        if (row.visible) console.log(`${'  '.repeat(row.depth)}${row.cls} "${row.text || row.label}" ${row.id} @${row.bounds}`);
      }
      break;
    }
    case 'shot': {
      const name = rest[0] ?? 'shot';
      writeFileSync(`${OUT}/${name}.png`, await screen.screenshot());
      console.log(`[${name}] saved`);
      break;
    }
    case 'tap': {
      const sel = rest[0];
      const loc = sel.startsWith('@')
        ? screen.getByLabel(sel.slice(1))
        : sel.startsWith('#')
          ? screen.getByTestId(sel.slice(1))
          : screen.getByText(sel);
      await loc.tap();
      await new Promise((r) => setTimeout(r, 900));
      console.log(`[tap] ${sel}`);
      break;
    }
    case 'tapxy': {
      await screen.tap(Number(rest[0]), Number(rest[1]));
      await new Promise((r) => setTimeout(r, 900));
      console.log(`[tapxy] ${rest[0]},${rest[1]}`);
      break;
    }
    case 'longpress': {
      await screen.longPress(Number(rest[0]), Number(rest[1]), 700);
      await new Promise((r) => setTimeout(r, 900));
      console.log(`[longpress] ${rest[0]},${rest[1]}`);
      break;
    }
    case 'swipe': {
      await screen.swipe(rest[0] ?? 'up');
      await new Promise((r) => setTimeout(r, 900));
      console.log(`[swipe] ${rest[0] ?? 'up'}`);
      break;
    }
    case 'back': {
      await screen.goBack();
      await new Promise((r) => setTimeout(r, 900));
      console.log('[back]');
      break;
    }
    default:
      console.error(`未知步骤: ${step}`);
      process.exitCode = 2;
  }
} finally {
  await d.close();
}
