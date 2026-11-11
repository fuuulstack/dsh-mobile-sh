// 生成「相对路径 + sha256」清单，用于跨平台源码比对。
// 用法：node scripts/hash-tree.mjs <根目录> <子路径...>
// 只输出文件，跳过 node_modules/.git/dist/build 等。
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(process.argv[2]);
const subs = process.argv.slice(3);
const SKIP = new Set(['node_modules', '.git', 'dist', 'build', '.gradle', 'artifacts']);

function walk(dir, out) {
  let entries;
  try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    if (SKIP.has(e.name)) continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { walk(full, out); continue; }
    if (!e.isFile()) continue;
    out.push(full);
  }
}

const files = [];
for (const sub of subs) walk(path.join(root, sub), files);
const lines = [];
for (const f of files.sort()) {
  const rel = path.relative(root, f).split(path.sep).join('/');
  const h = createHash('sha256').update(readFileSync(f)).digest('hex');
  lines.push(`${h}  ${rel}`);
}
process.stdout.write(lines.join('\n') + '\n');
