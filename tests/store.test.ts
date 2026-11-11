import test from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../packages/relay/src/store.js';
import { secret,uuid } from '../packages/relay/src/crypto.js';
import { pageSession } from '../packages/relay/src/sync-v2.js';

test('account-bound enrollment, replay-safe handoff, revocation and native token separation',async t=>{
  const db=new Store(':memory:',Buffer.alloc(32,1));t.after(()=>db.close());
  await db.createAccount('a@example.test','very-long-password',false);await db.createAccount('b@example.test','other-long-password',false);
  const login=await db.login('a@example.test','very-long-password','',false),a=db.session(login.token,'account');
  const other=await db.login('b@example.test','other-long-password','',false),b=db.session(other.token,'account');
  const invite=db.invitation(a),input={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'desktop'};
  const h=db.enroll(invite,input);assert.deepEqual(db.enroll(invite,input),h);assert.throws(()=>db.enroll(invite,{...input,hostTunnelToken:secret()}));
  const p=db.createPair(a,h.hostId);assert.equal(db.pair(b,p.code),null);assert.equal(db.pair(a,p.code),h.hostId);assert.equal(db.pair(a,p.token),null);
  const request=db.startHandoff(h.hostId),ticket=db.issueTicket(a,request.requestId);
  assert.throws(()=>db.consumeTicket(h.hostId,ticket.ticket,'wrong-bind'));
  const cookie=db.consumeTicket(h.hostId,ticket.ticket,request.bind);assert.equal(db.session(cookie,'host',h.hostId).device_id,a.device_id);
  assert.throws(()=>db.consumeTicket(h.hostId,ticket.ticket,request.bind));assert.throws(()=>db.session(cookie,'host',uuid()));
  const native=await db.login('a@example.test','very-long-password','',false,'Android',true);assert.throws(()=>db.session(native.token,'account'));const n=db.session(native.token,'native');
  assert.throws(()=>db.grant(n,h.hostId));const q=db.createPair(a,h.hostId);assert.equal(db.pair(n,q.code),h.hostId);
  db.revoke(a,'device',a.device_id);assert.throws(()=>db.session(cookie,'host',h.hostId));assert.doesNotThrow(()=>db.session(native.token,'native',h.hostId));
  assert.equal(db.authenticate(h.hostId,input.hostTunnelToken).id,h.hostId);
});

test('host platform is normalized on enroll and backfilled from authenticate',async t=>{
  const db=new Store(':memory:',Buffer.alloc(32,2));t.after(()=>db.close());
  await db.createAccount('p@example.test','very-long-password',false);
  const login=await db.login('p@example.test','very-long-password','',false),a=db.session(login.token,'account');
  // 老插件不上报：列存在但为空，手机端退回通用图标。
  const legacy=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'old'});
  assert.equal(db.get('SELECT platform FROM hosts WHERE id=?',legacy.hostId)!.platform,'');
  // 未知取值一律丢成空串：这一列最终会变成客户端的 drawable，不能当自由文本存。
  const junk=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'junk',platform:'plan9'});
  assert.equal(db.get('SELECT platform FROM hosts WHERE id=?',junk.hostId)!.platform,'');
  // Node 的 process.platform 说 darwin，客户端只认 macos。
  const mac=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'mac',platform:'darwin'});
  assert.equal(db.get('SELECT platform FROM hosts WHERE id=?',mac.hostId)!.platform,'macos');
  const win=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'win',platform:'win32'});
  assert.equal(db.get('SELECT platform FROM hosts WHERE id=?',win.hostId)!.platform,'windows');
  // 升级后的老主机不会重新 enroll，只能靠 authenticate 补写；已有值不被覆盖。
  db.notePlatform(legacy.hostId,'linux');
  assert.equal(db.get('SELECT platform FROM hosts WHERE id=?',legacy.hostId)!.platform,'linux');
  db.notePlatform(legacy.hostId,'');
  db.notePlatform(legacy.hostId,'plan9');
  assert.equal(db.get('SELECT platform FROM hosts WHERE id=?',legacy.hostId)!.platform,'linux');
});

test('redeeming a second device code on the same phone keeps the hosts it already paired',async t=>{
  // 事故：手机先配好电脑 A，再扫码配电脑 B，A 在设备管理和主机下拉里就变成了「未配对」。
  // 根因是 consumeDeviceCode 每兑换一次就 INSERT 一条新的 devices 行，而 grants 全挂在
  // 旧行上——新行对每台主机都是 authorized=false。设备身份该由「是哪台手机」决定，
  // 与这次用哪个码登录无关（与 login() 同一条复用规则）。
  const db=new Store(':memory:',Buffer.alloc(32,7));t.after(()=>db.close());
  await db.createAccount('p@example.test','very-long-password',false);
  const web=db.session((await db.login('p@example.test','very-long-password','',false)).token,'account');
  const laptop=db.enroll(db.invitation(web),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'笔记本'});
  const desktop=db.enroll(db.invitation(web),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'台式机'});

  const first=db.consumeDeviceCode(db.createDeviceCodeForAccount(web.account_id,'手机'),'Pixel 9') as {token:string,deviceId:string};
  const native1=db.session(first.token,'native');
  assert.equal(db.pair(native1,db.createPair(web,laptop.hostId).code),laptop.hostId);

  // 第二次扫码（同一个 label → 同一台手机）。
  const second=db.consumeDeviceCode(db.createDeviceCodeForAccount(web.account_id,'手机'),'Pixel 9') as {token:string,deviceId:string};
  const native2=db.session(second.token,'native');
  assert.equal(second.deviceId,first.deviceId,'同一台手机换码登录必须复用设备行，否则旧授权全部作废');
  assert.equal(db.pair(native2,db.createPair(web,desktop.hostId).code),desktop.hostId);

  // 两台都在：这才是「配对不丢历史」。
  const hosts=db.all('SELECT id,alias FROM hosts WHERE account_id=? AND revoked=0',web.account_id);
  const authorized=hosts.filter(h=>db.get('SELECT host_id FROM grants WHERE host_id=? AND device_id=? AND revoked=0',h.id,second.deviceId));
  assert.deepEqual(authorized.map(h=>h.alias).sort(),['台式机','笔记本']);
  // 设备表里仍然只有一台手机（外加网页那条），不会每扫一次长一条。
  assert.equal(db.all('SELECT id FROM devices WHERE account_id=? AND label=? AND revoked=0',web.account_id,'Pixel 9').length,1);

  // 不同 label 必须还是两台设备：复用按 label 走，不能把别人的手机并进来。
  const other=db.consumeDeviceCode(db.createDeviceCodeForAccount(web.account_id,'手机'),'iPhone 15') as {deviceId:string};
  assert.notEqual(other.deviceId,first.deviceId);
});

test('a tunnel that stops answering is reported offline well before the socket closes',async t=>{
  // 「电脑关机了 App 还显示在线」：只看 WebSocket 是否 ready 不够——关机/合盖/拔网线时
  // TCP 半开，close 几十秒才来甚至永远不来。在线的判据因此改成「最近真的说过话」
  // （PEER_LIVENESS_MS = 45 s，心跳 30 s 一次），这里用注入的时钟把结论钉住。
  const db=new Store(':memory:',Buffer.alloc(32,9));t.after(()=>db.close());
  await db.createAccount('o@example.test','very-long-password',false);
  const web=db.session((await db.login('o@example.test','very-long-password','',false)).token,'account');
  const h=db.enroll(db.invitation(web),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'会关机的主机'});
  const { Proxy } = await import('../packages/relay/src/proxy.js');
  const proxy=new Proxy(db,'https://relay.example.test',false,undefined,true);
  // 没有隧道时永远是离线。
  assert.equal(proxy.online(h.hostId),false);
  const now=Date.now();
  let probed=0;
  (proxy as unknown as {peers:Map<string,unknown>}).peers.set(h.hostId,{
    tunnel:{lastSeen:now,draining:false,probe:()=>{probed+=1;}},accountId:web.account_id,epoch:'e',limits:{},admission:{},next:2,ready:true,
  });
  assert.equal(proxy.online(h.hostId,now),true,'刚说过话 → 在线');
  assert.equal(proxy.online(h.hostId,now+44_999),true,'45 s 以内仍是活着的（心跳 30 s 一次，不会误判）');
  assert.equal(proxy.online(h.hostId,now+45_000),false,'超过存活窗口 → 离线，不必等 socket 关闭');
  // 刚说过话的隧道不补探测帧（每次拉列表都白跑一帧没有意义）。
  proxy.probe(h.hostId,now+1_000);
  assert.equal(probed,0);
  // 久未说话的隧道补一帧 ping，让下一次查询反映真相。
  proxy.probe(h.hostId,now+20_000);
  assert.equal(probed,1);
  // draining 的隧道一律离线，与 lastSeen 无关。
  (proxy as unknown as {peers:Map<string,{tunnel:{draining:boolean}}>}).peers.get(h.hostId)!.tunnel.draining=true;
  assert.equal(proxy.online(h.hostId,now),false);
});

/** Drain the migration in chunks, as `Relay.startBackfill` does. */
function drain(db: Store, hostId?: string, limit?: number): number {
  let copied = 0;
  let after: { hostId: string; sessionId: string; seq: number } | undefined;
  for (let guard = 0; guard < 10_000; guard++) {
    const result = db.backfillTimeline({ after, ...(limit === undefined ? {} : { limit }), ...(hostId === undefined ? {} : { hostId }) });
    copied += result.copied;
    if (result.done || !result.cursor) return copied;
    after = result.cursor;
  }
  throw new Error('backfill did not converge');
}

test('legacy archive rows are carried into the v2 timeline with prev_seq and kind',async t=>{
  const db=new Store(':memory:',Buffer.alloc(32,21));t.after(()=>db.close());
  await db.createAccount('back@example.test','very-long-password',false);
  const login=await db.login('back@example.test','very-long-password','',false);
  const a=db.session(login.token,'account');
  const h=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'old'});

  // v1 的归档形状是 `{event:{type,seq,data}}`——插件的 `enqueue()` 存的就是它。
  const legacy=[
    {type:'turn/start',seq:1,data:{turn:1}},
    {type:'user/message',seq:2,data:{turn:1,content:[{type:'text',text:'跑一下测试'}]}},
    {type:'tool/call',seq:3,data:{turn:1,step:1,name:'bash',arguments:{command:'npm test'}}},
    {type:'assistant/message',seq:4,data:{turn:1,content:[{type:'text',text:'都过了'}]}},
    {type:'turn/end',seq:5,data:{turn:1}},
  ];
  db.archiveRecords(h.hostId,legacy.map(e=>({sessionId:'sess-old',seq:e.seq,record:{event:e}})));

  assert.equal(drain(db),5,'every legacy row must be carried over');
  const page=db.timelineSlice(h.hostId,'sess-old',0,10);
  assert.deepEqual(page.map(r=>r.seq),[1,2,3,4,5]);
  // prev_seq 是 SQL 的 LAG 算出来的，不是「上一次收到的」。
  assert.deepEqual(page.map(r=>r.prevSeq),[0,1,2,3,4]);
  // 分类让历史对话在手机上仍然渲染得出来（留空 kind 会全部退化成 META_PATCH）。
  assert.deepEqual(page.map(r=>r.kind),
    ['META_PATCH','USER_INPUT','STEP_TOOL','ASSISTANT_TEXT','TURN_END']);
  assert.equal(page[1]!.turnId,'sess-old:t1','同一轮共享 turnId');
  // 载荷必须重新包成 v2 信封：v1 的 `{event:{...}}` 原样下发会让手机的
  // `recordOf()` 取不到 seq/kind，整页记录被静默丢弃（对话看着是空的）。
  const wrapped=JSON.parse(page[3]!.json);
  assert.equal(wrapped.sessionId,'sess-old');
  assert.equal(wrapped.seq,4);
  assert.equal(wrapped.prevSeq,3);
  assert.equal(wrapped.kind,'ASSISTANT_TEXT');
  assert.equal(wrapped.turnId,'sess-old:t1');
  // 内层 event 逐字节原样：迁移不许改写正文。
  assert.deepEqual(wrapped.payload,legacy[3]);
  // 水位立刻可用，手机第一次 /v2/sync 就有东西可拿。
  assert.equal(db.archiveCovered(h.hostId,'sess-old'),5);

  // 幂等：再跑一次不复制、不改动已有行。
  assert.equal(drain(db),0,'a second pass must be a no-op');
  // 只补缺口：新来的老记录仍然能被带上，且 prev_seq 指向前一条真实记录
  // （CTE 里排序，anti-join 只剪插入——不然漏掉的那条会让 prev_seq 退回 0）。
  db.archiveRecords(h.hostId,[{sessionId:'sess-old',seq:6,record:{event:{type:'turn/start',seq:6,data:{turn:2}}}}]);
  assert.equal(drain(db),1);
  const tail=db.timelineSlice(h.hostId,'sess-old',5,10);
  assert.deepEqual(tail.map(r=>[r.seq,r.prevSeq]),[[6,5]]);
});

test('backfill can be scoped to one host and survives an undecryptable row',async t=>{
  const db=new Store(':memory:',Buffer.alloc(32,22));t.after(()=>db.close());
  await db.createAccount('scoped@example.test','very-long-password',false);
  const login=await db.login('scoped@example.test','very-long-password','',false);
  const a=db.session(login.token,'account');
  const one=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'one'});
  const two=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'two'});
  const row=(n:number)=>({sessionId:'s',seq:n,record:{event:{type:'user/message',seq:n,data:{turn:1}}}});
  db.archiveRecords(one.hostId,[row(1)]);db.archiveRecords(two.hostId,[row(1),row(2)]);

  assert.equal(drain(db,one.hostId),1,'scoped pass touches one host only');
  assert.equal(db.timelineSlice(two.hostId,'s',0,10).length,0);
  assert.equal(drain(db),2,'unscoped pass mops up the rest');
  // 解不开的行（密钥轮换遗留）被跳过而不是让整次迁移失败。
  db.run('INSERT INTO records(host_id,session_id,seq,record,updated_at) VALUES(?,?,?,?,?)',
    one.hostId,'s',9,'not-ciphertext',Date.now());
  assert.equal(drain(db,one.hostId),0);
  assert.equal(db.timelineSlice(one.hostId,'s',0,10).length,1);
});

test('backfill reads a bounded chunk per call and resumes where it stopped',async t=>{
  const db=new Store(':memory:',Buffer.alloc(32,23));t.after(()=>db.close());
  await db.createAccount('chunk@example.test','very-long-password',false);
  const login=await db.login('chunk@example.test','very-long-password','',false);
  const a=db.session(login.token,'account');
  const h=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'chunky'});

  // 25 rows across two sessions, so a chunk boundary can fall *inside* a session -
  // that is the case where a naive per-call `LAG` or a single carried turn variable
  // would produce a wrong `prev_seq` or leak one session's turn into the next.
  const rows=[];
  for(let n=1;n<=15;n++)rows.push({sessionId:'sa',seq:n,record:{event:{type:'assistant/message',seq:n,data:{turn:1,content:[{type:'text',text:`a${n}`}]}}}});
  for(let n=1;n<=10;n++)rows.push({sessionId:'sb',seq:n,record:{event:{type:'user/message',seq:n,data:{turn:1,content:[{type:'text',text:`b${n}`}]}}}});
  db.archiveRecords(h.hostId,rows);

  const first=db.backfillTimeline({limit:8});
  assert.equal(first.copied,8,'one call must not exceed its limit');
  assert.equal(first.done,false,'a full chunk means there is more behind it');
  assert.deepEqual(first.cursor,{hostId:h.hostId,sessionId:'sa',seq:8});

  const second=db.backfillTimeline({after:first.cursor,limit:8});
  // sa has 7 rows left (9..15), so the chunk continues into sb and the cursor lands
  // mid-way through the *next* session: exactly the boundary a per-call `LAG` window
  // or a per-call carried turn variable would get wrong.
  assert.equal(second.copied,8);
  assert.deepEqual(second.cursor,{hostId:h.hostId,sessionId:'sb',seq:1},'the cursor resumes across a session boundary');

  // Drain the rest and check the merged result is complete and correctly chained.
  drain(db);
  const sa=db.timelineSlice(h.hostId,'sa',0,100);
  const sb=db.timelineSlice(h.hostId,'sb',0,100);
  assert.equal(sa.length,15);
  assert.equal(sb.length,10);
  assert.deepEqual(sa.map(r=>r.prevSeq),[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14]);
  // `sb` starts its own chain at 0: a carried turn variable per *call* would have
  // made this look like a continuation of `sa`.
  assert.deepEqual(sb.map(r=>r.prevSeq),[0,1,2,3,4,5,6,7,8,9]);
  assert.equal(sa[0]!.turnId,'sa:t1');
  assert.equal(sb[0]!.turnId,'sb:t1');
});

test('backfill honours a byte budget, not just a row count',async t=>{
  const db=new Store(':memory:',Buffer.alloc(32,24));t.after(()=>db.close());
  await db.createAccount('bytes@example.test','very-long-password',false);
  const login=await db.login('bytes@example.test','very-long-password','',false);
  const a=db.session(login.token,'account');
  const h=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'fat'});

  // Ten ~200 KB rows: a row limit alone would happily pull 2 MB of ciphertext into
  // memory in one call, which is precisely what knocked the production host over.
  const big='x'.repeat(200*1024);
  db.archiveRecords(h.hostId,Array.from({length:10},(_,i)=>({
    sessionId:'fat',seq:i+1,
    record:{event:{type:'assistant/message',seq:i+1,data:{turn:1,content:[{type:'text',text:big}]}}},
  })));

  const first=db.backfillTimeline({limit:50,maxBytes:512*1024});
  assert.ok(first.copied<10,`a byte budget must stop before the row limit (copied ${first.copied})`);
  assert.equal(first.done,false);
  assert.ok(first.cursor,'a truncated-by-budget chunk still reports where to resume');

  // Resuming must reach the end and still chain every prev_seq correctly.
  drain(db);
  const page=db.timelineSlice(h.hostId,'fat',0,100);
  assert.equal(page.length,10);
  assert.deepEqual(page.map(r=>r.prevSeq),[0,1,2,3,4,5,6,7,8,9]);
});

test('a v1 archive push is mirrored into the v2 timeline as it arrives',async t=>{
  const db=new Store(':memory:',Buffer.alloc(32,25));t.after(()=>db.close());
  await db.createAccount('mirror@example.test','very-long-password',false);
  const login=await db.login('mirror@example.test','very-long-password','',false);
  const a=db.session(login.token,'account');
  const h=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'mixed'});

  // A host still running the v1 plugin: it only ever calls `archiveRecords`.
  const push=(n:number)=>db.archiveRecords(h.hostId,[{sessionId:'live',seq:n,record:{event:{type:'assistant/message',seq:n,data:{turn:1,content:[{type:'text',text:`m${n}`}]}}}}]);
  const mirror=(n:number)=>db.mirrorLegacy(h.hostId,[{sessionId:'live',seq:n,record:{event:{type:'assistant/message',seq:n,data:{turn:1,content:[{type:'text',text:`m${n}`}]}}}}]);

  // The migration ran once and finished; new pushes keep coming afterwards.
  push(1);mirror(1);
  assert.equal(drain(db),0,'nothing left behind the finished migration');

  // Without the mirror this row would stay invisible to a v2 phone until the next
  // hourly mop-up - the "my newest messages never show up" failure mode.
  push(2);
  assert.equal(db.timelineSlice(h.hostId,'live',0,10).length,1,'a v1 push alone does not reach the v2 table');
  assert.equal(mirror(2),1,'the mirror lands it immediately');
  const page=db.timelineSlice(h.hostId,'live',0,10);
  assert.deepEqual(page.map(r=>r.seq),[1,2]);
  assert.deepEqual(page.map(r=>r.prevSeq),[0,1],'the mirrored row is chained to its predecessor');

  // Replaying the same push (plugin retry after a timeout) must not duplicate.
  assert.equal(mirror(2),0,'a replay is a no-op');
  assert.equal(db.timelineSlice(h.hostId,'live',0,10).length,2);
  // And the payload is still the original event, byte-for-byte.
  const wrapped=JSON.parse(page[1]!.json);
  assert.equal(wrapped.kind,'ASSISTANT_TEXT');
  assert.equal(wrapped.payload.data.content[0].text,'m2');
});

test('an already-synced session reports TAIL_COMPLETE, never UNKNOWN_SESSION',async t=>{
  // 实测事故（2026-10-30 云端 + 模拟器）：29 个**已经追平**的会话在 diag 里被标成
  // UNKNOWN_SESSION，看起来像「中继上没有这些会话」，于是照着日志去查归档是不是丢了。
  // 判据写错了一行：`covered <= afterSeq` 把「追平」和「从没见过」混成了一件事。
  const db=new Store(':memory:',Buffer.alloc(32,26));t.after(()=>db.close());
  await db.createAccount('caught@example.test','very-long-password',false);
  const login=await db.login('caught@example.test','very-long-password','',false);
  const a=db.session(login.token,'account');
  const h=db.enroll(db.invitation(a),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'caught'});

  const push=(n:number)=>db.archiveRecords(h.hostId,[{sessionId:'caught',seq:n,record:{event:{type:'assistant/message',seq:n,data:{turn:1,content:[{type:'text',text:`m${n}`}]}}}}]);
  push(1);push(2);push(3);drain(db);

  const store=db as unknown as Parameters<typeof pageSession>[0];
  const at=(afterSeq:number)=>pageSession(store,h.hostId,{sessionId:'caught',afterSeq,priority:0},512*1024);

  // 从头拉：有内容，正常推进。
  const first=at(0);
  assert.deepEqual(first.records.map(r=>JSON.parse(r).seq),[1,2,3]);
  assert.equal(first.stopReason,'TAIL_COMPLETE');

  // 已经追平：空页，但**不是**未知会话——这正是手机 diag 里那些重复行的来源。
  const caughtUp=at(3);
  assert.deepEqual(caughtUp.records,[]);
  assert.equal(caughtUp.stopReason,'TAIL_COMPLETE','a caught-up session is not unknown');
  assert.equal(caughtUp.hasMore,false);
  assert.equal(caughtUp.nextCursorSeq,3,'the cursor stays put instead of being rewound');

  // 真的不存在的会话才叫 UNKNOWN_SESSION。
  const unknown=pageSession(store,h.hostId,{sessionId:'never-archived',afterSeq:0,priority:0},512*1024);
  assert.equal(unknown.stopReason,'UNKNOWN_SESSION');
  assert.deepEqual(unknown.records,[]);
});
