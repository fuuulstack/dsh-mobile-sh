// 像素取色与两色对比度（UI 品味评审）。
//
// 用法：
//   node scripts/review/contrast.mjs <png> <x,y> [<x,y> ...]     取点色值
//   node scripts/review/contrast.mjs --ratio '#aabbcc' '#ddeeff'  两色 WCAG 比值
//   node scripts/review/contrast.mjs <png> --scan                 统计整幅图的用色
import { readFileSync } from 'node:fs';
import { PNG } from 'pngjs';

const args = process.argv.slice(2);

const lin = (c) => {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};
const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
const hex = (c) => '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('');
const parseHex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const ratio = (a, b) => {
  const [l1, l2] = [lum(a), lum(b)].sort((m, n) => n - m);
  return (l1 + 0.05) / (l2 + 0.05);
};

// 模式一：两色直接算比值（不读图片）
if (args[0] === '--ratio') {
  console.log(`ratio ${args[1]} vs ${args[2]} = ${ratio(parseHex(args[1]), parseHex(args[2])).toFixed(2)}`);
  process.exit(0);
}

const [file, ...rest] = args;
const png = PNG.sync.read(readFileSync(file));
const px = (x, y) => {
  const i = (png.width * y + x) << 2;
  return [png.data[i], png.data[i + 1], png.data[i + 2]];
};

if (rest[0] === '--scan') {
  const counts = new Map();
  for (let y = 0; y < png.height; y += 2) {
    for (let x = 0; x < png.width; x += 2) {
      const k = px(x, y).join(',');
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
  }
  for (const [k, n] of [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)) {
    console.log(`${k}\t${n}\t${hex(k.split(',').map(Number))}`);
  }
} else {
  for (const p of rest) {
    const [x, y] = p.split(',').map(Number);
    const c = px(x, y);
    console.log(`${p}\t${c.join(',')}\t${hex(c)}`);
  }
}
