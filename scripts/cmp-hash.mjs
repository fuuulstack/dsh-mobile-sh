// 比对两份「sha256  相对路径」清单，输出三类差异：仅本地 / 仅远端 / 共有但不同。
// 用法：node scripts/cmp-hash.mjs <本地清单> <远端清单>
import { readFileSync } from 'node:fs';

function load(file) {
  const map = new Map();
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const m = /^([0-9a-f]{64})\s{2}(.+)$/.exec(line.trim());
    if (m) map.set(m[2], m[1]);
  }
  return map;
}

const local = load(process.argv[2]);
const remote = load(process.argv[3]);
const onlyLocal = [], onlyRemote = [], differ = [];
for (const [p, h] of local) {
  if (!remote.has(p)) onlyLocal.push(p);
  else if (remote.get(p) !== h) differ.push(p);
}
for (const p of remote.keys()) if (!local.has(p)) onlyRemote.push(p);

console.log(`本地 ${local.size} 个文件 / 远端 ${remote.size} 个文件`);
console.log(`\n=== 仅本地（远端缺失，需同步过去）${onlyLocal.length} ===`);
for (const p of onlyLocal.sort()) console.log('  + ' + p);
console.log(`\n=== 仅远端（本地没有，需判定是否要保留）${onlyRemote.length} ===`);
for (const p of onlyRemote.sort()) console.log('  - ' + p);
console.log(`\n=== 共有但内容不同（需判定方向）${differ.length} ===`);
for (const p of differ.sort()) {
  console.log(`  ~ ${p}`);
  console.log(`      本地 ${local.get(p).slice(0, 12)}  远端 ${remote.get(p).slice(0, 12)}`);
}
