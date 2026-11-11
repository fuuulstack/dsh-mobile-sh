// 判定差异方向：对每个差异文件，检查远端哈希是否等于该文件在本地 git 历史中任一版本的哈希。
// 命中 → 远端只是旧版本（可安全覆盖）；未命中 → 远端有本地 git 里从未出现过的内容（必须人工看）。
// 用法：node scripts/diff-direction.mjs <远端清单> <文件...>
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const remote = new Map();
for (const line of readFileSync(process.argv[2], 'utf8').split('\n')) {
  const m = /^([0-9a-f]{64})\s{2}(.+)$/.exec(line.trim());
  if (m) remote.set(m[2], m[1]);
}

const sha = (buf) => createHash('sha256').update(buf).digest('hex');
let unique = 0;
for (const file of process.argv.slice(3)) {
  const target = remote.get(file);
  if (!target) { console.log(`?? ${file} 不在远端清单`); continue; }
  let revs = [];
  try {
    revs = execFileSync('git', ['rev-list', 'HEAD', '--', file], { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
  } catch { }
  const seen = new Map();
  let hit = null;
  for (const rev of revs) {
    let blob;
    try { blob = execFileSync('git', ['show', `${rev}:${file}`], { maxBuffer: 64 * 1024 * 1024 }); } catch { continue; }
    const h = sha(blob);
    seen.set(h, rev.slice(0, 8));
    if (h === target) { hit = rev.slice(0, 8); break; }
  }
  if (hit) console.log(`旧版 ${file}  ← 远端内容 = 本地历史 ${hit}（可安全覆盖）`);
  else {
    unique++;
    console.log(`★ 独有 ${file}  远端哈希 ${target.slice(0, 12)} 不在本地 git 历史（${seen.size} 个版本）中`);
  }
}
console.log(`\n结论：${unique} 个文件含本地 git 里从未出现过的内容。`);
