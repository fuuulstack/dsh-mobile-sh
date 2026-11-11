// 竖向/横向扫描一条线上的颜色分段，用于量卡片边界、圆角、间距。
// 用法：node scripts/review/scan.mjs <png> <v|x> <坐标>
import { readFileSync } from 'node:fs';
import { PNG } from 'pngjs';

const [file, axis, pos] = process.argv.slice(2);
const png = PNG.sync.read(readFileSync(file));
const P = Number(pos);
const at = axis === 'v'
  ? (t) => { const i = (png.width * t + P) << 2; return [png.data[i], png.data[i + 1], png.data[i + 2]]; }
  : (t) => { const i = (png.width * P + t) << 2; return [png.data[i], png.data[i + 1], png.data[i + 2]]; };
const limit = axis === 'v' ? png.height : png.width;

const hex = (c) => '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('');
const segs = [];
let cur = null;
for (let t = 0; t < limit; t++) {
  const c = at(t);
  if (cur === null) cur = { c, start: t };
  else if (c.some((v, i) => Math.abs(v - cur.c[i]) > 2)) {
    segs.push({ ...cur, end: t - 1 });
    cur = { c, start: t };
  }
}
segs.push({ ...cur, end: limit - 1 });
for (const s of segs) {
  if (s.end - s.start >= 2) console.log(`${s.start}-${s.end} (${s.end - s.start + 1}px) ${hex(s.c)}`);
}
