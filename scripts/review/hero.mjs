// 设置页 hero（已连接卡）读数：进设置、等流量累计、导出可读行与截图。
//
// 用法：
//   node scripts/review/hero.mjs [等待秒数]
//
// 产物：artifacts/review/hero-settings.{png,lines.json,tree.json}
import { android } from 'mobilewright';
import { mkdirSync, writeFileSync } from 'node:fs';

const OUT = 'artifacts/review';
mkdirSync(OUT, { recursive: true });

const BUNDLE = 'com.dsh.mobile.v2';
const waitMs = Number(process.argv[2] ?? 10) * 1000;

/** 把 viewTree 拍平成「可读行」，与 drive.mjs 同一口径。 */
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
  await d.launchApp(BUNDLE, { autoAppLaunch: true });
  await new Promise((r) => setTimeout(r, 2000));

  // 底栏「设置」：可点击节点是挂 contentDescription 的 View，用 label 定位。
  await screen.getByLabel('设置').tap();
  await new Promise((r) => setTimeout(r, 1200));

  // hero 里的延迟/流量是按秒刷新的，等一会儿让计数器累积出非零读数。
  await new Promise((r) => setTimeout(r, waitMs));

  const tree = await screen.viewTree();
  const rows = flatten(tree);
  writeFileSync(`${OUT}/hero-settings.tree.json`, JSON.stringify(tree, null, 2));
  writeFileSync(`${OUT}/hero-settings.lines.json`, JSON.stringify(rows, null, 2));
  writeFileSync(`${OUT}/hero-settings.png`, await screen.screenshot());

  const KEYS = ['已连接', '未连接', '上行', '下行', '延迟', '往返', '断开', '中继', '设置', '冒烟'];
  console.log('--- hero 相关行 ---');
  for (const row of rows) {
    const s = `${row.text}${row.label}`;
    if (KEYS.some((k) => s.includes(k))) {
      console.log(`${row.cls} "${row.text || row.label}" @${row.bounds}`);
    }
  }
} finally {
  await d.close();
}
