import type { IncomingHttpHeaders } from 'node:http';
import type { HeaderPairs } from './protocol.js';

export class HttpError extends Error {constructor(readonly status:number,message:string){super(message);}}
export function safePath(raw:string):string {
  if(!raw.startsWith('/')||raw.startsWith('//')||raw.length>8192||/[\\\x00-\x20\x7f#]/.test(raw)||/%(?![a-f0-9]{2})/i.test(raw))throw new HttpError(400,'invalid-path');
  const [pathname,query='']=raw.split('?',2);
  for(const segment of pathname!.split('/')){
    let decoded:string;try{decoded=decodeURIComponent(segment);}catch{throw new HttpError(400,'invalid-path');}
    if(decoded==='.'||decoded==='..'||/[\\/\x00-\x1f\x7f]/.test(decoded)||/%(?:25)*(?:2e|2f|5c|00)/i.test(decoded))throw new HttpError(400,'invalid-path');
  }
  if(/^\/(?:__relay|mobile-sh)(?:\/|$)/.test(pathname!)||pathname==='/connect'||new URLSearchParams(query).has('token'))throw new HttpError(403,'reserved-path');
  // `$events` is the Gateway-internal stream endpoint answering forwarded waterfalls; only
  // the exact unary form is admitted, never a query or traversal variant.
  if(pathname!.startsWith('/api/$events')&&pathname!=='/api/$events/result')throw new HttpError(403,'reserved-path');
  return raw;
}
/**
 * 只取「主机名 + 非默认端口」，用来做**忽略 scheme** 的 Origin 比对。
 *
 * <p>为什么需要它：中继没钉对外地址时（一行命令的容器部署），请求经过 nginx 才到中继，
 * TLS 在中继之外终止。中继看到的永远是 `http:`，而浏览器 Origin 头写的是 `https:`——
 * 严格比 scheme 会把**所有**正常表单提交判成 403。此时唯一能可靠比对的是 host，
 * 而 host 正是 Origin 校验真正要防的东西（别的站点的页面来打你的接口）。
 * 缺省仍然是严格比对，只有明确知道自己是「未钉地址 + 在反代之后」的调用方才开 `sameHost`。
 */
function originHost(url:string):string{
  try{const u=new URL(url);const dflt=u.protocol==='https:'?'443':u.protocol==='http:'?'80':'';
    return (u.port&&u.port!==dflt?`${u.hostname}:${u.port}`:u.hostname).toLowerCase();}catch{return '';}
}
function sameOriginHost(got:string,want:string):boolean{const a=originHost(got);return a!==''&&a===originHost(want);}
export function checkOrigin(method:string,rawPath:string,h:IncomingHttpHeaders,origin:string,baseOrigin:string,ws=false,sameHost=false):void {
  const o=h.origin,site=h['sec-fetch-site'];
  const match=(v:string)=>v===origin||(sameHost&&sameOriginHost(v,origin));
  if(!ws&&method==='GET'&&rawPath==='/'&&h['sec-fetch-mode']==='navigate'&&h['sec-fetch-dest']==='document'){
    if(o&&!match(o)&&!(o===baseOrigin||(sameHost&&sameOriginHost(o,baseOrigin))))throw new HttpError(403,'origin');return;
  }
  if(o&&!match(o))throw new HttpError(403,'origin');
  if(site&&site!=='same-origin')throw new HttpError(403,'fetch-site');
  if((ws||!['GET','HEAD'].includes(method))&&!o)throw new HttpError(403,'origin-required');
  if(!o&&!site)throw new HttpError(403,'source-required');
}
export function bodyInfo(method:string,h:IncomingHttpHeaders):{bodyKind:'none'|'bytes';lengthMode:'exact'|'unknown';declaredLength?:number} {
  const cl=h['content-length'],te=h['transfer-encoding'];
  if(cl&&te)throw new HttpError(400,'ambiguous-length');
  if(cl!==undefined&&(!/^\d+$/.test(cl)||!Number.isSafeInteger(Number(cl))))throw new HttpError(400,'invalid-length');
  if(te&&te.toLowerCase()!=='chunked')throw new HttpError(400,'invalid-encoding');
  const n=cl===undefined?0:Number(cl);
  if(['GET','HEAD'].includes(method)&&(n>0||te))throw new HttpError(400,'body-not-allowed');
  if(te)return {bodyKind:'bytes',lengthMode:'unknown'};
  return {bodyKind:n?'bytes':'none',lengthMode:'exact',declaredLength:n};
}
const hop=['connection','keep-alive','proxy-authenticate','proxy-authorization','te','trailer','transfer-encoding','upgrade'];
export function sanitizeHeaders(pairs:HeaderPairs,direction:'request'|'response',upstream?:string):HeaderPairs {
  const removed=new Set(hop);
  for(const [n,v]of pairs)if(n.toLowerCase()==='connection')v.split(',').forEach(x=>removed.add(x.trim().toLowerCase()));
  if(direction==='request')for(const n of ['cookie','authorization','host','origin','sec-fetch-site','referer','forwarded','sec-websocket-key','sec-websocket-accept','sec-websocket-extensions','sec-websocket-version','sec-websocket-protocol'])removed.add(n);
  else for(const n of ['set-cookie','set-cookie2','sec-websocket-key','sec-websocket-accept','sec-websocket-extensions'])removed.add(n);
  return pairs.filter(([n,v])=>{
    if(!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(n)||/[\r\n\x00]/.test(v))throw new HttpError(400,'invalid-header');
    return !removed.has(n.toLowerCase())&&!n.toLowerCase().startsWith('x-forwarded-')&&!n.toLowerCase().startsWith('x-relay-');
  }).map(([n,v])=>{
    if(direction==='response'&&n.toLowerCase()==='location'&&upstream){try{const u=new URL(v);if(u.origin===upstream)return [n,u.pathname+u.search+u.hash];}catch{}}
    return [n,v];
  });
}
export function rawPairs(raw:string[]):HeaderPairs {const out:HeaderPairs=[];for(let i=0;i<raw.length;i+=2)out.push([raw[i]!,raw[i+1]!]);return out;}
export function headerObject(pairs:HeaderPairs):Record<string,string|string[]> {
  const out:Record<string,string|string[]>=Object.create(null);for(const [n,v]of pairs){const k=n.toLowerCase(),p=out[k];out[k]=p===undefined?v:Array.isArray(p)?[...p,v]:[p,v];}return out;
}
