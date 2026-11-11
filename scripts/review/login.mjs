// 临时：把登录表单填完并连接（评审回归用，不入库）。
// 用法：node scripts/review/login.mjs <relay地址> <8位配对码>
import { android } from 'mobilewright';

const endpoint = process.argv[2] ?? 'https://localhost:8443';
const code = process.argv[3];
if (!code) throw new Error('用法：node scripts/review/login.mjs <relay地址> <8位配对码>');

const d = await android.launch({ deviceName: /qb-api35/ });
try {
  const screen = d.screen;
  await d.launchApp('com.dsh.mobile.v2', { autoAppLaunch: true });
  await new Promise((r) => setTimeout(r, 3000));
  // 登录页三个输入框：0=中继地址、1=配对码、2=设备名称（树里的 EditText 顺序稳定）。
  const fields = screen.getByType('android.widget.EditText');
  await fields.nth(0).fill(endpoint);
  console.log('[1] 中继地址已填', endpoint);
  await fields.nth(1).fill(code);
  console.log('[2] 配对码已填');
  await screen.getByText('连接').tap();
  console.log('[3] 已点连接');
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 3000));
    const tree = await screen.viewTree();
    const flat = JSON.stringify(tree);
    if (flat.includes('更多') && !flat.includes('登录并连接')) {
      console.log('登录通过，已进入主界面');
      break;
    }
    const note = ['正在验证', '失败', '过期', '拒绝', '无法'].find((k) => flat.includes(k));
    if (note) {
      console.log('登录未通过的提示词：', note);
      break;
    }
  }
  const lines = (await screen.viewTree()).flatMap(function walk(n, out = []) {
    const t = (n.text ?? n.label ?? '').trim();
    if (t) out.push(t);
    for (const c of n.children ?? []) walk(c, out);
    return out;
  });
  console.log('当前界面文本：', lines.slice(0, 25).join(' | '));
} finally {
  await d.close();
}
