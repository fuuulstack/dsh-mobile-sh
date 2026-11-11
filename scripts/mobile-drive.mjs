#!/usr/bin/env node
// 用本机 mobile-mcp（@mobilenext/mobile-mcp 自带的 mobilecli）驱动安卓设备，
// 替代 instrumentation 直连 View 树的做法：UI 树的获取、弹窗、输入法都走
// 无障碍层级，因此 AlertDialog、系统键盘这些独立窗口同样可达。
//
// 用法：node scripts/mobile-drive.mjs <命令> [参数...]
//   dump [关键字]        列出元素（带中心坐标）；给了关键字就只列匹配项
//   all                  列出全部元素（不过滤包名）
//   tap <文本>           按文本点击（精确优先，其次包含）
//   tapdesc <描述>       按 content-desc 点击
//   filln <序号> <文本>  给第 N 个输入框填值（先清空）
//   clearn <序号>        清空第 N 个输入框
//   fields               列出输入框序号与坐标
//   tapxy <x>,<y>        按坐标点击
//   type <文本>          向当前焦点输入框输入文本
//   key <名称>           发送按键（HOME/BACK/ENTER/DEL...）
//   wait <文本> [毫秒]   等待文本出现，默认 15s
//   gone <文本> [毫秒]   等待文本消失
//   shot <路径>          截图
//   launch | stop | clear | fg
//   text                 打印当前屏所有可见文本（便于断言）
//
// 退出码：匹配失败/等待超时 = 1，便于脚本串起来用 && 判断。

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const HOME = process.env.USERPROFILE || process.env.HOME || '';
// 注意：同一个 node_modules 下有 mobilewright/dist/cli.js（输出表格）与
// mobilecli/index.js（输出 JSON）；驱动脚本要的是后者，别用 mobilewright 那个。
const CLI = resolve(
  HOME,
  '.agents/mcp/mobile-mcp/node_modules/mobilecli/index.js',
);
const DEVICE = process.env.MOBILE_DEVICE || 'qb-api35';
const PKG = process.env.MOBILE_PKG || 'com.dsh.mobile.v2';

if (!existsSync(CLI)) {
  console.error(`找不到 mobilecli 实体入口：${CLI}`);
  process.exit(2);
}

const env = { ...process.env, NO_PROXY: '127.0.0.1,localhost' };

/** 调用 mobilecli，返回解析后的 JSON（失败返回 null）。 */
function cli(args, { quiet = true } = {}) {
  try {
    const out = execFileSync(process.execPath, [CLI, ...args], {
      env,
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      stdio: ['ignore', 'pipe', quiet ? 'pipe' : 'inherit'],
    });
    const start = out.indexOf('{');
    if (start < 0) return null;
    return JSON.parse(out.slice(start));
  } catch (error) {
    if (!quiet) console.error(String(error.stderr || error.message));
    return null;
  }
}

function dumpTree(all = false) {
  const result = cli(['dump', 'ui', '--device', DEVICE]);
  const roots = result?.data?.elements ?? [];
  const flat = [];
  const walk = (node) => {
    flat.push(node);
    for (const child of node.children ?? []) walk(child);
  };
  for (const root of roots) walk(root);
  return flat.filter((node) => {
    const id = node.identifier ?? '';
    if (!all && id.startsWith('com.android.systemui')) return false;
    if (!all && id.startsWith('com.google.android')) return false;
    if (!all && id === 'android:id/navigationBarBackground') return false;
    return true;
  });
}

// mobilecli 的 JSON 里，"text" 是可见文本，"label" 是 content-desc；
// 两者都能用来定位元素，所以合并成一个可匹配值。
const label = (node) => String(node.text || node.label || '');

const center = (node) => {
  const r = node.rect ?? {};
  return {
    x: Math.round((r.x ?? 0) + (r.width ?? 0) / 2),
    y: Math.round((r.y ?? 0) + (r.height ?? 0) / 2),
  };
};

function findNode(nodes, needle) {
  const target = needle.trim();
  const candidates = nodes.filter((node) => label(node).length > 0);
  return (
    candidates.find((n) => label(n) === target) ??
    candidates.find((n) => label(n).includes(target)) ??
    null
  );
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForText(needle, timeoutMs = 15000, want = true) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hit = findNode(dumpTree(), needle);
    if (want ? hit : !hit) return hit;
    await sleep(400);
  }
  return want ? null : true;
}

function tap(x, y) {
  return cli(['io', 'tap', '--device', DEVICE, `${x},${y}`]);
}

/** 清空当前聚焦的输入框。
 *
 * 首选 ctrl+A 全选 + 退格：实测 ColorOS 的 OplusSecurityInputMethod 会把
 * `adb shell input keyevent 67` 这类裸按键丢掉（80 连发也清不掉），而走
 * mobilecli 的组合键能真正落到 EditText 上。失败再退回连按 DEL。 */
function clearFocusedField(times = 80) {
  if (cli(['io', 'keys', '--device', DEVICE, 'ctrl+a', 'backspace'])) return;
  const adb = process.env.ADB || 'adb';
  execFileSync(adb, ['shell', 'input', 'keyevent', '123'], { stdio: 'ignore' });
  execFileSync(adb, ['shell', 'input', 'keyevent', ...Array(times).fill('67')], { stdio: 'ignore' });
}

const commands = {
  dump(args) {
    const nodes = dumpTree();
    const key = args[0];
    const rows = key
      ? nodes.filter((n) => label(n).includes(key))
      : nodes.filter(
          (n) => label(n).length > 0 || n.identifier || /EditText/.test(n.type),
        );
    if (!rows.length) {
      console.log('（无匹配元素）');
      return 1;
    }
    for (const node of rows) {
      const { x, y } = center(node);
      const id = (node.identifier ?? '').replace(`${PKG}:id/`, '');
      console.log(
        `${String(node.ref).padEnd(8)} ${`(${x},${y})`.padEnd(12)} ${node.type.replace('android.widget.', '').padEnd(14)} ${id.padEnd(24)} ${JSON.stringify(label(node))}`,
      );
    }
    return 0;
  },

  all(args) {
    const rows = dumpTree(true).filter((n) => label(n).length > 0);
    for (const node of rows) {
      const { x, y } = center(node);
      console.log(`${`(${x},${y})`.padEnd(12)} ${JSON.stringify(label(node))}`);
    }
    return 0;
  },

  text() {
    for (const node of dumpTree()) {
      if (label(node).length > 0) console.log(label(node));
    }
    return 0;
  },

  tap(args) {
    const needle = args.join(' ');
    const node = findNode(dumpTree(), needle);
    if (!node) {
      console.error(`找不到元素：${needle}`);
      return 1;
    }
    const { x, y } = center(node);
    tap(x, y);
    console.log(`点击 ${JSON.stringify(label(node))} @ (${x},${y})`);
    return 0;
  },

  tapdesc(args) {
    const needle = args.join(' ');
    const node = findNode(dumpTree(), needle);
    if (!node) {
      console.error(`找不到元素：${needle}`);
      return 1;
    }
    const { x, y } = center(node);
    tap(x, y);
    console.log(`点击 ${JSON.stringify(label(node))} @ (${x},${y})`);
    return 0;
  },

  /** 点第一个 EditText 拿到焦点（弹窗里的输入框没有 id/text，只能按类型找）。 */
  tapedit() {
    const node = dumpTree().find((n) => /EditText/.test(n.type));
    if (!node) {
      console.error('当前界面没有 EditText');
      return 1;
    }
    const { x, y } = center(node);
    tap(x, y);
    console.log(`已聚焦输入框 @ (${x},${y})`);
    return 0;
  },

  /** 直接点坐标，跳过 dump（键盘弹起时用，避免重新 dump 期间坐标漂移）。 */
  tapxy(args) {
    const [x, y] = args.join(',').split(',').map(Number);
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      console.error('用法：tapxy <x>,<y>');
      return 1;
    }
    tap(x, y);
    console.log(`点击坐标 (${x},${y})`);
    return 0;
  },

  /** 直接给第一个 EditText 设值（走 io text，前提是已聚焦）。 */
  async fill(args) {
    const value = args.join(' ');
    const node = dumpTree().find((n) => /EditText/.test(n.type));
    if (!node) {
      console.error('当前界面没有 EditText');
      return 1;
    }
    const { x, y } = center(node);
    tap(x, y);
    await sleep(300);
    const result = cli(['io', 'text', '--device', DEVICE, value]);
    console.log(result?.data?.message ?? `已输入 ${JSON.stringify(value)}`);
    return 0;
  },

  /** 列出现有输入框（带序号），配合 filln 用。 */
  fields() {
    const nodes = dumpTree().filter((n) => /EditText/.test(n.type));
    nodes.forEach((node, index) => {
      const { x, y } = center(node);
      console.log(`${index}  (${x},${y})  ${JSON.stringify(label(node))}`);
    });
    return nodes.length ? 0 : 1;
  },

  /** 给第 N 个输入框填值：node scripts/mobile-drive.mjs filln 0 "值"。先清空再输入。 */
  async filln(args) {
    const index = Number(args[0]);
    const value = args.slice(1).join(' ');
    const nodes = dumpTree().filter((n) => /EditText/.test(n.type));
    const node = nodes[index];
    if (!node) {
      console.error(`没有第 ${index} 个输入框（共 ${nodes.length} 个）`);
      return 1;
    }
    const { x, y } = center(node);
    tap(x, y);
    await sleep(350);
    clearFocusedField();
    const result = cli(['io', 'text', '--device', DEVICE, value]);
    console.log(`[${index}] <- ${JSON.stringify(value)} ${result?.data?.message ?? ''}`);
    return 0;
  },

  /** 清空第 N 个输入框（不输入内容）：node scripts/mobile-drive.mjs clearn 0。 */
  async clearn(args) {
    const index = Number(args[0] ?? 0);
    const nodes = dumpTree().filter((n) => /EditText/.test(n.type));
    const node = nodes[index];
    if (!node) {
      console.error(`没有第 ${index} 个输入框（共 ${nodes.length} 个）`);
      return 1;
    }
    const { x, y } = center(node);
    tap(x, y);
    await sleep(350);
    clearFocusedField();
    console.log(`[${index}] 已清空`);
    return 0;
  },

  type(args) {
    const value = args.join(' ');
    const result = cli(['io', 'text', '--device', DEVICE, value]);
    console.log(result?.data?.message ?? `已输入 ${JSON.stringify(value)}`);
    return 0;
  },

  key(args) {
    const name = (args[0] ?? 'ENTER').toUpperCase();
    const result = cli(['io', 'button', '--device', DEVICE, name]);
    console.log(result?.data?.message ?? `已按键 ${name}`);
    return 0;
  },

  async wait(args) {
    const needle = args[0];
    const timeoutMs = Number(args[1] ?? 15000);
    const hit = await waitForText(needle, timeoutMs);
    if (!hit) {
      console.error(`等待超时：${needle}`);
      return 1;
    }
    console.log(`已出现：${needle}`);
    return 0;
  },

  async gone(args) {
    const needle = args[0];
    const timeoutMs = Number(args[1] ?? 15000);
    const ok = await waitForText(needle, timeoutMs, false);
    if (!ok) {
      console.error(`等待消失超时：${needle}`);
      return 1;
    }
    console.log(`已消失：${needle}`);
    return 0;
  },

  shot(args) {
    const path = resolve(args[0] ?? 'artifacts/shot.png');
    mkdirSync(dirname(path), { recursive: true });
    cli(['screenshot', '--device', DEVICE, '-o', path]);
    console.log(`已截图：${path}`);
    return 0;
  },

  launch() {
    cli(['apps', 'launch', '--device', DEVICE, PKG]);
    return 0;
  },

  stop() {
    cli(['apps', 'terminate', '--device', DEVICE, PKG]);
    return 0;
  },

  clear() {
    const adb = process.env.ADB || 'adb';
    try {
      const out = execFileSync(adb, ['shell', 'pm', 'clear', PKG], {
        encoding: 'utf8',
      });
      console.log(out.trim());
    } catch (error) {
      console.error(`清空数据失败：${error.message}`);
      return 1;
    }
    return 0;
  },

  fg() {
    const result = cli(['apps', 'foreground', '--device', DEVICE]);
    console.log(JSON.stringify(result?.data ?? result));
    return 0;
  },
};

const [command, ...args] = process.argv.slice(2);
if (!command || !commands[command]) {
  console.error('用法：node scripts/mobile-drive.mjs <dump|tap|type|key|wait|shot|text|...>');
  process.exit(2);
}
process.exit((await commands[command](args)) ?? 0);
