import fs from 'node:fs';
import { NAMING } from '../packages/core/src/naming.ts';

// 命名一致性门禁：package.json 的 name 字段无法从 TS 导入，这里做单一事实源校验。
// 项目改名时只改 packages/core/src/naming.ts + android/gradle.properties，
// 本脚本保证所有静态清单同步跟上了。
const read = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const errors = [];
const expect = (label, actual, wanted) => {
  if (actual !== wanted) errors.push(`${label}: 期望 "${wanted}"，实际 "${actual}"`);
};

const root = read('package.json');
expect('package.json name', root.name, NAMING.workspace);
expect('packages/plugin name', read('packages/plugin/package.json').name, NAMING.pluginPackage);
expect('packages/relay name', read('packages/relay/package.json').name, `@${NAMING.pluginPackage}/relay`);
expect('packages/core name', read('packages/core/package.json').name, `@${NAMING.pluginPackage}/core`);

const compose = fs.readFileSync('deploy/compose.yaml', 'utf8');
if (!compose.includes(`${NAMING.dockerImage}:`)) {
  errors.push(`deploy/compose.yaml: 缺少镜像缺省值 ${NAMING.dockerImage}:<tag>`);
}
const patch = fs.readFileSync('packages/plugin/cordis.patch.yml', 'utf8');
if (!patch.includes(`name: ${NAMING.pluginPackage}`)) {
  errors.push(`packages/plugin/cordis.patch.yml: 插件注册名不是 ${NAMING.pluginPackage}`);
}
if (patch.includes('DSH_MOBILE_SH_') && !patch.includes(`${NAMING.envPrefix}_RELAY`)) {
  errors.push('packages/plugin/cordis.patch.yml: 环境变量未换到正式前缀');
}

if (errors.length) {
  console.error('check-naming 失败:\n  ' + errors.join('\n  '));
  process.exit(1);
}
console.log('check-naming: ok (plugin=%s relay-image=%s env=%s_*)',
  NAMING.pluginPackage, NAMING.dockerImage, NAMING.envPrefix);
