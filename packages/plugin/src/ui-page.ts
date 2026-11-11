/**
 * 设置页的 HTML 渲染（极简风格）。
 *
 * <p>为什么是服务端渲染的独立页面（而不是 React 组件）：这个页面要填中继域名、
 * 中继鉴权密码、生成配对二维码，全部是「点一下、发一个请求、看一个结果」的表单，
 * 没有一条需要 DSH 的会话/工作区状态。做成独立页面有三个直接好处：
 * ① 密码与配对码只存在于宿主进程到浏览器这一段 HTML 里，不经过任何前端状态管理；
 * ② 客户端半只需要一个 iframe，不用把 React 组件塞进主 bundle、也不用同步宿主的
 * 主题 token（页内自带明暗两套）；
 * ③ 整页可以用严格 CSP（`default-src 'none'`），比在宿主 SPA 里插 DOM 安全。
 *
 * <p>页面里**回显**中继鉴权密码（2026-10-28 按用户要求改）：原本的理由是「它是一次性凭据，
 * 回显等于写进 DOM/截图/历史」，但实际用下来，注册成功后插件会把密码从设置文件里清掉，
 * 用户下次打开面板看到的永远是空输入框，每次都要重新找密码粘一遍。中继侧的鉴权密码
 * 是**长期凭据**（部署方在 `/etc/…env` 里配的那个），不是一次性邀请，所以这里改为：
 * ①设置文件保留密码，②页面把它回填进 `type=password` 输入框（仍是掩码显示），
 * ③保存成功后**不再清空**输入框。页面本身仍跑在 `default-src 'none'` + nonce 下、
 * 只经宿主 loopback 提供，且 `cache-control: no-store`，不落任何缓存。
 *
 * <p>**设计约束（2026-10-27 / 2026-10-28 按用户要求）**：
 * - 只让用户填**中继域名**，wss 地址由插件自动生成，页面上不出现 `/tunnel` 之类的协议细节；
 * - 版本号缩成标题旁的小胶囊；
 * - 去掉「上游 DSH」「升级通道探测」等与操作无关的诊断行，以及所有解释性段落；
 * - **整体版式居中**：内容列横向居中（不再贴着面板左侧、右侧留一大片空白），
 *   标题、状态、按钮、二维码同样居中；
 * - 保存后**必须给出连接结果**（轮询状态直到 ready / 失败），不能只停在「正在重连」；
 * - 保存成功后**保留密码**，不清空输入框（用户明确要求：不要每次登录都清空）。
 * - 「连接时阻止电脑休眠」开关（2026-10-29 用户要求）单独一段、单独端点：它是纯本机行为，
 *   改它不该顺带重连中继；不支持时画成不可用并说明原因，不能让它看着能点却没反应。
 */
import type { ResolvedSettings } from './settings.js';

/** 页面需要的数据，全部由调用方算好（本模块不读状态、不发请求）。 */
export interface PageState {
  readonly state: string;
  readonly hostId: string | null;
  readonly relayOrigin: string | null;
  /** 规范化后的中继地址（wss://…/tunnel）；页面只展示其中的域名部分。 */
  readonly relayUrl: string;
  readonly relaySource: 'file' | 'config' | 'none';
  readonly secretConfigured: boolean;
  /**
   * 已保存的中继鉴权密码（明文，用于回填输入框）。
   *
   * <p>只在**用户自己在设置页填过**时才有值；来自环境变量/patch 的邀请不回显（那是部署方
   * 注入的一次性值，页面没理由把它画出来）。空串表示没有可回填的值。
   */
  readonly enrollSecret: string;
  /**
   * 「手机连着时别让电脑睡着」的开关状态（Electron `powerSaveBlocker`，取不到时退系统命令）。
   *
   * <p>`supported=false` 表示宿主没提供这个能力（非 Electron 宿主），页面把开关画成
   * 不可用并说明原因——比让它看起来能点、点了没反应要好。
   */
  readonly powerSave: { readonly enabled: boolean; readonly active: boolean; readonly supported: boolean };
  readonly version: string;
  readonly embedded: boolean;
}

const esc = (value: unknown): string => String(value ?? '').replace(/[&<>"']/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!
));

/**
 * 从规范化地址里取出用户当初输入的「域名」部分。
 *
 * <p>用户只需要填域名，页面也只回显域名——把 `wss://host/tunnel` 整串塞回输入框
 * 会让下一次编辑变成「在协议串里改字」，很容易改坏。
 *
 * @param relayUrl 规范化后的地址（可能为空）
 * @returns 主机名（含端口），未配置时为空串
 */
export function relayHostOf(relayUrl: string): string {
  if (!relayUrl) return '';
  try {
    return new URL(relayUrl).host;
  } catch {
    return '';
  }
}

/** 状态 → 简短中文标签与色调（`ok` 绿 / `warn` 黄 / `bad` 红 / `idle` 灰）。 */
function stateLabel(state: string): { text: string; tone: string } {
  switch (state) {
    case 'ready': return { text: '已连接', tone: 'ok' };
    case 'connecting': return { text: '连接中…', tone: 'warn' };
    case 'unpaired': return { text: '未连接', tone: 'warn' };
    case 'degraded': return { text: '连接失败', tone: 'bad' };
    case 'revoked': return { text: '凭据已失效', tone: 'bad' };
    case 'disabled': return { text: '已停用', tone: 'idle' };
    case 'starting': return { text: '启动中…', tone: 'idle' };
    case 'stopping': return { text: '停止中…', tone: 'idle' };
    default: return { text: state, tone: 'idle' };
  }
}

const STYLE = `
:root{color-scheme:light dark;--bg:#fff;--fg:#18181b;--dim:#71717a;--line:#e4e4e7;--accent:#2563eb;--ok:#15803d;--warn:#a16207;--bad:#b91c1c}
@media (prefers-color-scheme:dark){:root{--bg:#18181b;--fg:#f4f4f5;--dim:#a1a1aa;--line:#3f3f46;--accent:#60a5fa;--ok:#4ade80;--warn:#fbbf24;--bad:#f87171}}
*{box-sizing:border-box}
/* 居中：面板打开时主区很宽（侧栏折叠后可达上千 px），原先 max-width 只限制上限、
   左对齐就让整块内容贴在左侧、右边一片空白。margin:auto 把内容列放到可视区中间，
   内部各段（标题/状态/表单/按钮/二维码）再统一居中，竖屏窄面板下也自然满宽。 */
html{height:100%}
body{margin:0 auto;padding:22px 20px;background:var(--bg);color:var(--fg);font:13.5px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;max-width:420px;min-height:100%;display:flex;flex-direction:column}
body[data-embedded=true]{padding:16px}
.wrap{margin:auto 0;width:100%}
.head{display:flex;align-items:center;justify-content:center;gap:8px;margin:0 0 16px}
.head h1{margin:0;font-size:15px;font-weight:600;letter-spacing:.01em}
.ver{font-size:10.5px;line-height:1;padding:3px 7px;border-radius:999px;border:1px solid var(--line);color:var(--dim);font-variant-numeric:tabular-nums}
.status{display:flex;align-items:center;justify-content:center;gap:7px;font-size:12.5px;color:var(--dim);margin:0 0 18px}
.dot{width:7px;height:7px;border-radius:50%;flex:0 0 auto;background:var(--dim)}
.dot[data-tone=ok]{background:var(--ok)}.dot[data-tone=warn]{background:var(--warn)}
.dot[data-tone=bad]{background:var(--bad)}.dot[data-tone=idle]{background:var(--dim)}
label{display:block;margin:0 0 5px;font-size:11.5px;color:var(--dim);text-align:center}
input{width:100%;padding:9px 11px;margin:0 0 14px;border:1px solid var(--line);border-radius:8px;background:transparent;color:var(--fg);font:inherit;text-align:center}
input:focus-visible{outline:2px solid var(--accent);outline-offset:1px;border-color:transparent}
button{padding:8px 14px;border:1px solid var(--line);border-radius:8px;background:transparent;color:var(--fg);font:inherit;font-size:12.5px;cursor:pointer}
button:hover:not(:disabled){border-color:var(--accent);color:var(--accent)}
button:disabled{opacity:.45;cursor:default}
button[data-primary]{background:var(--accent);border-color:var(--accent);color:#fff}
button[data-primary]:hover:not(:disabled){color:#fff;filter:brightness(1.08)}
.actions{display:flex;justify-content:center}
.msg{margin:0 0 14px;font-size:12px;display:none;overflow-wrap:anywhere;text-align:center}
.msg[data-show]{display:block}
.msg[data-tone=ok]{color:var(--ok)}.msg[data-tone=bad]{color:var(--bad)}.msg[data-tone=warn]{color:var(--warn)}
.pair{margin-top:24px;padding-top:20px;border-top:1px solid var(--line)}
/* 防休眠开关：复用 label 的排版，但要去掉输入框那条「整行下边距」的通配样式。 */
.power{display:flex;align-items:center;justify-content:center;gap:9px;margin-top:20px;padding-top:18px;border-top:1px solid var(--line)}
.power input[type=checkbox]{width:15px;height:15px;margin:0;padding:0;flex:0 0 auto;accent-color:var(--accent);cursor:pointer}
.power label{margin:0;font-size:12.5px;color:var(--fg);text-align:left;cursor:pointer}
.power input[type=checkbox]:disabled{cursor:default}
.power input[type=checkbox]:disabled+label{color:var(--dim);cursor:default}
.powerhint{margin:7px 0 0;font-size:11px;color:var(--dim);text-align:center;min-height:1.2em}
.pairbox{display:none;margin-top:16px;text-align:center}
.pairbox[data-show]{display:block}
.pairbox img{width:180px;height:180px;display:block;margin:0 auto 10px;background:#fff;border-radius:8px;padding:6px}
.code{font:600 19px/1.2 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.16em}
.ttl{margin-top:5px;font-size:11px;color:var(--dim)}
`;

const SCRIPT = `
(function(){
  var API='/dsh-mobile-sh/api/';
  var msg=document.getElementById('msg');
  var dot=document.getElementById('dot');
  var stext=document.getElementById('stext');
  function say(text,tone){msg.textContent=text;msg.setAttribute('data-tone',tone);msg.setAttribute('data-show','');}
  function clear(){msg.removeAttribute('data-show');}
  function paint(state,label,tone){dot.setAttribute('data-tone',tone);stext.textContent=label;}
  function post(path,payload){
    return fetch(API+path,{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify(payload)})
      .then(function(r){return r.json().catch(function(){return {};}).then(function(b){return {status:r.status,body:b};});});
  }
  function get(path){
    return fetch(API+path,{credentials:'same-origin'}).then(function(r){return r.json().catch(function(){return {};});});
  }
  function labels(state){
    if(state==='ready')return ['已连接','ok'];
    if(state==='connecting')return ['连接中…','warn'];
    if(state==='degraded')return ['连接失败','bad'];
    if(state==='revoked')return ['凭据已失效','bad'];
    if(state==='disabled')return ['已停用','idle'];
    return ['未连接','warn'];
  }
  // 保存后必须给出结果：轮询状态直到 ready 或超时，期间如实显示「连接中」。
  function watch(){
    var tries=0;
    var timer=setInterval(function(){
      tries++;
      get('status').then(function(s){
        if(!s||!s.state)return;
        var l=labels(s.state);paint(s.state,l[0],l[1]);
        if(s.powerSave)paintPower(s.powerSave);
        if(s.state==='ready'){
          clearInterval(timer);
          say('已连接中继','ok');
          var pb=document.getElementById('pair');if(pb)pb.disabled=false;
        } else if(s.state==='degraded'||s.state==='revoked'){
          clearInterval(timer);
          say(s.state==='revoked'?'中继拒绝了这个凭据':'连不上中继，请检查域名与密码','bad');
        } else if(tries>20){
          clearInterval(timer);
          say('连接超时，请检查中继域名与密码','bad');
        }
      }).catch(function(){ if(tries>20){clearInterval(timer);} });
    },1000);
  }
  function save(ev){
    ev.preventDefault();
    var host=document.getElementById('relay').value;
    var secret=document.getElementById('secret').value;
    var btn=document.getElementById('save');
    btn.disabled=true;clear();paint('connecting','连接中…','warn');
    post('settings',{relayUrl:host,enrollSecret:secret}).then(function(r){
      btn.disabled=false;
      if(r.status!==200||!r.body.ok){say((r.body&&r.body.error)||('保存失败（HTTP '+r.status+'）'),'bad');return;}
      // 保存成功后**不清空**密码框：这是用户的长期凭据，清掉意味着下次还得重新找一遍
      // （2026-10-28 用户明确要求）。后端也已改为把密码留在设置文件里。
      say('已保存，正在连接…','warn');
      watch();
    }).catch(function(e){btn.disabled=false;say('保存失败：'+e,'bad');});
  }
  var timer;
  function pair(ev){
    ev.preventDefault();
    var btn=document.getElementById('pair');
    var box=document.getElementById('pairbox');
    btn.disabled=true;clear();
    post('pair',{}).then(function(r){
      btn.disabled=false;
      if(r.status!==200||!r.body.ok){box.removeAttribute('data-show');say((r.body&&r.body.error)||('生成失败（HTTP '+r.status+'）'),'bad');return;}
      document.getElementById('qr').src=r.body.qr;
      document.getElementById('code').textContent=r.body.code;
      box.setAttribute('data-show','');
      var left=r.body.ttlSeconds;
      var ttl=document.getElementById('ttl');
      function tick(){ttl.textContent=left>0?(Math.floor(left/60)+':'+('0'+(left%60)).slice(-2)+' 后失效'):'已失效，请重新生成';if(left>0){left--;timer=setTimeout(tick,1000);}}
      clearTimeout(timer);tick();
    }).catch(function(e){btn.disabled=false;say('生成失败：'+e,'bad');});
  }
  // 防休眠开关：单独一个端点，改完不回重连（它是纯本机行为，与中继地址无关）。
  // 失败要把勾选状态**改回去**：看着勾上了、其实没开，比开关不可用更糟。
  var pbox=document.getElementById('power');
  var phint=document.getElementById('powerhint');
  function paintPower(p){
    if(!p)return;
    if(pbox){pbox.checked=!!p.enabled;pbox.disabled=!p.supported||p.busy===true;}
    if(!phint)return;
    if(!p.supported){phint.textContent='当前系统没有可用的防休眠手段，此开关不可用';return;}
    if(p.enabled&&p.active){phint.textContent='已生效：屏幕可以熄灭，系统不会休眠';return;}
    if(p.enabled){phint.textContent='已开启：中继连上后生效';return;}
    phint.textContent='关闭时不改变系统的休眠行为';
  }
  if(pbox){
    pbox.addEventListener('change',function(){
      var next=pbox.checked;
      pbox.disabled=true;
      post('power',{enabled:next}).then(function(r){
        pbox.disabled=false;
        if(r.status!==200||!r.body.ok){
          pbox.checked=!next;
          say((r.body&&r.body.error)||('切换失败（HTTP '+r.status+'）'),'bad');
          return;
        }
        paintPower(r.body.powerSave);
      }).catch(function(e){pbox.disabled=false;pbox.checked=!next;say('切换失败：'+e,'bad');});
    });
  }
  document.getElementById('form').addEventListener('submit',save);
  document.getElementById('pairform').addEventListener('submit',pair);
})();
`;

/**
 * 渲染整页 HTML。
 *
 * @param state 页面状态快照
 * @param nonce CSP nonce；由路由生成并同时写进响应头，页面里的 `<style>/<script>`
 *   必须与它一致，否则浏览器拒绝执行
 * @returns 完整 HTML 文本
 */
export function renderPage(state: PageState, nonce: string): string {
  const status = stateLabel(state.state);
  const title = state.embedded ? '手机访问' : '手机访问 · 设置';
  // 输入框只回显域名：用户当初填的就是域名，wss 地址是插件自己生成的。
  const host = relayHostOf(state.relayUrl);
  const canPair = state.hostId !== null;
  // 密码回填：用户自己在设置页填过的那份会随页面一起回来（2026-10-28 用户要求：
  // 不要每次登录都清空）。值来自设置文件，仍是 `type=password` 掩码显示。
  const secret = state.enrollSecret ?? '';
  const secretPlaceholder = state.secretConfigured && secret === ''
    ? '已由部署配置提供（不回显）'
    : '与中继部署时设置的一致';
  return `<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<style nonce="${nonce}">${STYLE}</style>
<body data-embedded="${state.embedded ? 'true' : 'false'}">
<div class="wrap">
<div class="head"><h1>手机访问</h1><span class="ver">v${esc(state.version)}</span></div>
<div class="status"><span class="dot" id="dot" data-tone="${status.tone}"></span><span id="stext">${esc(status.text)}</span></div>
<div class="msg" id="msg"></div>
<form id="form" autocomplete="off">
  <label for="relay">中继域名</label>
  <input id="relay" name="relayUrl" type="text" inputmode="url" spellcheck="false"
    placeholder="relay.example.com" value="${esc(host)}">
  <label for="secret">中继鉴权密码</label>
  <input id="secret" name="enrollSecret" type="password" spellcheck="false" autocomplete="off" value="${esc(secret)}"
    placeholder="${esc(secretPlaceholder)}">
  <div class="actions"><button id="save" type="submit" data-primary>保存</button></div>
</form>
<div class="power">
  <input id="power" type="checkbox"${state.powerSave.enabled ? ' checked' : ''}${state.powerSave.supported ? '' : ' disabled'}>
  <label for="power">连接时阻止电脑休眠</label>
</div>
<p class="powerhint" id="powerhint">${esc(state.powerSave.supported
  ? (state.powerSave.enabled ? (state.powerSave.active ? '已生效：屏幕可以熄灭，系统不会休眠' : '已开启：中继连上后生效') : '关闭时不改变系统的休眠行为')
  : '当前系统没有可用的防休眠手段，此开关不可用')}</p>
<form class="pair" id="pairform" autocomplete="off">
  <div class="actions"><button id="pair" type="submit"${canPair ? '' : ' disabled'}>生成二维码</button></div>
  <div class="pairbox" id="pairbox">
    <img id="qr" alt="配对二维码" src="">
    <div class="code" id="code"></div>
    <div class="ttl" id="ttl"></div>
  </div>
</form>
</div>
<script nonce="${nonce}">${SCRIPT}</script>
</body></html>`;
}
