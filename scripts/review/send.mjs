// 临时：在聊天页发送一条消息并等待回合结束（评审回归用，不入库）。
// 用法：node scripts/review/send.mjs "文本"
import { android } from 'mobilewright';
import { mkdirSync, writeFileSync } from 'node:fs';

const text = process.argv[2] ?? '回归验证：请回复一句';
const OUT = 'artifacts/review';
mkdirSync(OUT, { recursive: true });

const d = await android.launch({ deviceName: /qb-api35/ });
try {
  const screen = d.screen;
  const fields = screen.getByType('android.widget.EditText');
  console.log('EditText 数量 =', await fields.count());
  await fields.last().fill(text);
  console.log('[1] 已输入：' + text);
  await screen.getByLabel('发送').tap();
  console.log('[2] 已点发送，等待回合结束…');
  await new Promise((r) => setTimeout(r, 12000));
  writeFileSync(`${OUT}/17-after-send.png`, await screen.screenshot());
  const flat = [];
  (function walk(nodes, out) {
    for (const n of nodes) {
      const t = (n.text ?? '').trim();
      if (t) out.push(t);
      if (n.children?.length) walk(n.children, out);
    }
    return out;
  })(await screen.viewTree(), flat);
  console.log('界面文本：', flat.join(' | '));
} finally {
  await d.close();
}
