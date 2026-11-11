// 临时：在首页底部「输入配对码」抽屉里填入主机配对码并点「配对」（评审回归用，不入库）。
// 用法：node scripts/review/pair.mjs <8位配对码>
import { android } from 'mobilewright';

const code = process.argv[2];
if (!code) throw new Error('用法：node scripts/review/pair.mjs <8位配对码>');

const d = await android.launch({ deviceName: /qb-api35/ });
try {
  const screen = d.screen;
  await screen.getByText('输入配对码').tap();
  await new Promise((r) => setTimeout(r, 1200));
  await screen.getByType('android.widget.EditText').first().fill(code);
  console.log('[1] 已填配对码', code);
  await screen.getByText('配对').tap();
  console.log('[2] 已点配对');
  await new Promise((r) => setTimeout(r, 6000));
  const tree = JSON.stringify(await screen.viewTree());
  const hints = ['配对成功', '失败', '无效', '过期', '已使用', '请重试'].filter((k) => tree.includes(k));
  console.log('结果关键词：', hints.length ? hints.join(',') : '（无明显提示）');
  console.log('会话列表是否出现主机相关文本：', tree.includes('冒烟假主机') || tree.includes('冒烟会话'));
} finally {
  await d.close();
}
