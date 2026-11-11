// 区域取证：在给定矩形内统计出现最多的「文字色」与「底色」并算对比度。
// 用法：node scripts/review/region.mjs <png> <x0> <y0> <x1> <y1>
import { readFileSync } from 'node:fs';
import { PNG } from 'pngjs';

const [file, x0, y0, x1, y1] = process.argv.slice(2);
const png = PNG.sync.read(readFileSync(file));
const [X0, Y0, X1, Y1] = [x0, y0, x1, y1].map(Number);

const lin = (c) => {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};
const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
const hex = (c) => '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('');

const counts = new Map();
for (let y = Y0; y < Math.min(Y1, png.height); y++) {
  for (let x = X0; x < Math.min(X1, png.width); x++) {
    const i = (png.width * y + x) << 2;
    const k = `${png.data[i]},${png.data[i + 1]},${png.data[i + 2]}`;
    counts.set(k, (counts.get(k) ?? 0) + 1);
  }
}

const entries = [...counts.entries()].map(([k, n]) => ({ c: k.split(',').map(Number), n }));
entries.sort((a, b) => b.n - a.n);
const bg = entries[0];
// 文字色 = 与底色亮度差最大、且样本量不少于 15 的那个
const fg = entries
  .slice(1)
  .filter((e) => e.n >= 15)
  .sort((a, b) => Math.abs(lum(b.c) - lum(bg.c)) - Math.abs(lum(a.c) - lum(bg.c)))[0] ?? entries[1];

const [l1, l2] = [lum(fg.c), lum(bg.c)].sort((m, n) => n - m);
const ratio = (l1 + 0.05) / (l2 + 0.05);
console.log(`bg ${hex(bg.c)} (${bg.n}px)  fg ${hex(fg.c)} (${fg.n}px)  ratio ${ratio.toFixed(2)}`);
console.log(`top5: ${entries.slice(0, 5).map((e) => `${hex(e.c)}x${e.n}`).join('  ')}`);
