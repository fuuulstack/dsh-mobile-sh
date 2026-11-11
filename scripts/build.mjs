import { build } from 'esbuild';
import { readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

// 依赖清单一律按 Node 的解析结果去读，不拼 `node_modules/<name>`：
// git worktree 里通常没有自己的 node_modules，Node 会向上级目录解析到主工作区那份，
// 而字符串拼接会直接 ENOENT（本仓库在 `.dsh-worktrees/` 下跑构建时实测踩到）。
const require = createRequire(import.meta.url);
const packageJsonOf = (name) => {
  const entry = require.resolve(`${name}/package.json`, { paths: [process.cwd()] });
  return join(dirname(entry), 'package.json');
};

/**
 * Build identity, pinned into every bundle (v2 §F4).
 *
 * <p>Three components ship independently — relay image, plugin tarball, APK — and the
 * only way to answer "is the desktop running the build I just made" from a phone is a
 * string each side reports. `git rev-parse --short HEAD` plus the build hour separates
 * local rebuilds that keep the same version number, which is exactly the trap in
 * AGENTS.md §31: same tgz version, new contents, pnpm keeps the old directory.
 */
const buildHash=(()=>{
  let commit='nogit';
  try{commit=execFileSync('git',['rev-parse','--short','HEAD'],{encoding:'utf8'}).trim()||'nogit';}catch{}
  return `${commit}.${new Date().toISOString().slice(0,13).replace(/[-:T]/g,'')}`;
})();
const base={'process.env.WS_NO_BUFFER_UTIL':'"1"','process.env.WS_NO_UTF_8_VALIDATE':'"1"','process.env.DSH_MOBILE_SH_BUILD_HASH':JSON.stringify(buildHash)};

const common={bundle:true,platform:'node',target:'node24',format:'esm',packages:'bundle',logLevel:'info',banner:{js:"import { createRequire as __createRequire } from 'node:module'; const require = __createRequire(import.meta.url);"}};
await build({...common,entryPoints:['packages/plugin/src/index.ts'],outfile:'packages/plugin/dist/index.js',define:base});
/**
 * 转发子进程（中期-13）：`lifecycle.ts` 用 `fork()` 拉起它，路径写死成与 index.js 同目录的
 * `worker.js`，因此必须**独立打包成第二个入口**（bundle 进 index.js 是 fork 不起来的）。
 * 与 index.js 共用同一套 define / 平台设置，免得两条产物在 ws 的 utf-8 校验上分叉。
 */
await build({...common,entryPoints:['packages/plugin/src/worker.ts'],outfile:'packages/plugin/dist/worker.js',define:base});
await build({...common,entryPoints:['packages/relay/src/cli.ts'],outfile:'packages/relay/dist/cli.js',external:['bufferutil','utf-8-validate'],define:base});

/**
 * 客户端半（浏览器 bundle）。
 *
 * DSH 的 Web 端按 `dsh.client` 声明加载 `/plugins/<id>/client.js`，而那个文件必须是
 * lazy-CJS 形态：`window.__ModuleLoader__.load({id, factory})`，factory 收到 `require`
 * 并在**首次 import 时**才执行（见 @deepseek-ai/dsh-client-modules 的 Lazy-CJS 模型）。
 * esbuild 的 `format:'cjs'` 产物已经是我们需要的形状（`module.exports` + `require('react')`），
 * 因此这里只做一层外壳包装，不引第三方打包器。
 *
 * 关键约束：
 * ① `react` 必须是 external —— 宿主把 React 放在共享模块表里，重复安装会让 hooks 报错；
 * ② factory 体内不得有副作用（只注册、不执行），所有挂载都在 `apply` 里；
 * ③ id 必须等于包名，否则宿主的 require 解析找不到这个 bundle。
 */
async function buildClientHalf(){
  const result=await build({
    entryPoints:['packages/plugin/src/browser.ts'],
    outfile:'packages/plugin/dist/client.js',
    bundle:true,format:'cjs',platform:'browser',target:'es2022',
    external:['react','react-dom','react/jsx-runtime'],
    // 生产构建去掉 console 噪音；`console.error` 保留（注册失败要有痕迹）。
    drop:['debugger'],
    logLevel:'info',
  });
  if(result.errors.length)throw new Error('client bundle failed');
  const pkg=JSON.parse(await readFile('packages/plugin/package.json','utf8'));
  const body=await readFile('packages/plugin/dist/client.js','utf8');
  // 外壳：包名必须与 dsh.client 的包名一致；缩进让栈里的行列号还能读。
  //
  // `module` / `exports` 必须由 factory 自己声明——宿主只注入 `require`，
  // esbuild 的 CJS 产物却依赖这两个自由变量（否则 `module is not defined`，
  // 表现是面板静默不出现、控制台一行报错）。写法与 dsh-taskboard 的产物一致。
  const wrapped=`window.__ModuleLoader__.load({ id: ${JSON.stringify(pkg.name)}, factory: (require) => {\nvar module = { exports: {} }; var exports = module.exports;\n${body}\nreturn module.exports; } });\n`;
  await writeFile('packages/plugin/dist/client.js',wrapped);
  // 守卫：客户端半绝不能内联 React（会与宿主的模块表产生两份实例）。
  if(!/require\(["']react["']\)/.test(wrapped))throw new Error('client bundle must require react from the host table');
  if(/^\s*import\s/m.test(wrapped))throw new Error('client bundle must not contain ESM imports');
  if(!/^window\.__ModuleLoader__\.load\(\{ id:/.test(wrapped))throw new Error('client bundle must be wrapped in the lazy-CJS loader shell');
}

await buildClientHalf();

let notice='DSH Mobile SH bundled third-party notices\n';for(const name of ['ws','zod','qrcode','@deepseek-ai/schemastery']){
 const pkg=JSON.parse(await readFile(packageJsonOf(name),'utf8'));notice+=`\n${name}@${pkg.version}\n`;
 for(const filename of ['LICENSE','LICENSE.md'])try{notice+=await readFile(join(dirname(packageJsonOf(name)),filename),'utf8');break;}catch{}
}
for(const path of ['packages/plugin/dist','packages/relay/dist'])await writeFile(`${path}/NOTICE.txt`,notice);
const pkg=JSON.parse(await readFile('packages/plugin/package.json','utf8'));
if(pkg.dependencies||pkg.peerDependencies)throw new Error('Plugin runtime dependencies must be bundled or host supplied');
const workspacePkg=JSON.parse(await readFile('package.json','utf8'));
if(workspacePkg.license!=='MIT'||pkg.license!=='MIT')throw new Error('Workspace and plugin package licenses must both be MIT');
if(!pkg.files?.includes('LICENSE'))throw new Error('Plugin tarball must include LICENSE');
const rootLicense=await readFile('LICENSE','utf8');
const pluginLicense=await readFile('packages/plugin/LICENSE','utf8');
if(rootLicense!==pluginLicense)throw new Error('Plugin LICENSE must match the repository LICENSE');
const output=await readFile('packages/plugin/dist/index.js','utf8');if(/from ["'](?:ws|zod)["']/.test(output))throw new Error('Unbundled dependency');
if(/^\s*import[^;\n]*from\s*["']@deepseek-ai\//m.test(output))throw new Error('插件不得在 dist 中保留 @deepseek-ai/* 的运行时导入：宿主只解析第一方 bundle');
await writeFile('packages/plugin/dist/build-info.json',JSON.stringify({node:process.version,wire:3,build:buildHash,ws:JSON.parse(await readFile(packageJsonOf('ws'),'utf8')).version},null,2));
