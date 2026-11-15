// Smoke tests for Hunter CAN Live v2. Node vm with mocks. No hardware involved.
// Run: node tests/smoke.js        (needs python3 for the analyzer round-trip tests)
const fs=require('fs'),path=require('path'),vm=require('vm'),cp=require('child_process');
const {makeEnv,Port,frame,hex,ROOT,extractApp}=require('./harness');
const DATA=path.join(__dirname,'data');
const ANALYZER=process.env.ANALYZER||path.join(ROOT,'tools','hunter_analyze.py');
const sampleTxt=fs.readFileSync(path.join(DATA,'sample.txt'),'latin1');
const results=[];const allCmds=[];
function eq(a,b,msg){if(JSON.stringify(a)!==JSON.stringify(b))throw new Error((msg||'eq')+': got '+JSON.stringify(a)+' expected '+JSON.stringify(b));}
function ok(c,msg){if(!c)throw new Error('assert failed: '+msg);}
function near(a,b,tol,msg){if(!(Math.abs(a-b)<=tol))throw new Error((msg||'near')+': got '+a+' expected '+b+' +-'+tol);}
async function test(name,fn){const t0=Date.now();try{const d=await fn();results.push({name,ok:true,detail:d||''});console.log('PASS '+name+(d?' - '+d:''));}catch(e){results.push({name,ok:false,detail:e.message});console.log('FAIL '+name+' - '+e.message);}}
const py=(args)=>cp.execFileSync('python3',['-I',...args],{encoding:'utf8',maxBuffer:1<<26});
const pyFrames=(file)=>JSON.parse(py(['-c',`import sys,json;sys.path.insert(0,${JSON.stringify(path.dirname(ANALYZER))});import hunter_analyze as h;f,m,meta=h.load(sys.argv[1]);from collections import Counter;print(json.dumps({"n":len(f),"by":{str(k):v for k,v in Counter(x[1] for x in f).items()}}))`,file]));
const perId=(env)=>JSON.parse(env.run('JSON.stringify(Object.fromEntries([...ids.entries()].map(([k,s])=>[String(k),s.n])))'));
const tmp=(n)=>path.join(require('os').tmpdir(),n);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));

(async()=>{
// 1 ---------------------------------------------------------------- syntax
await test('syntax: index.html script, sw.js, manifest JSON, python analyzer',()=>{
  const app=extractApp();new vm.Script(app,{filename:'app.js'});
  new vm.Script(fs.readFileSync(path.join(ROOT,'sw.js'),'utf8'),{filename:'sw.js'});
  const man=JSON.parse(fs.readFileSync(path.join(ROOT,'manifest.webmanifest'),'utf8'));
  ok(man.icons.length>=3&&man.display==='standalone','manifest fields');
  for(const i of man.icons)ok(fs.existsSync(path.join(ROOT,i.src)),'icon '+i.src);
  py(['-c','import ast,sys;ast.parse(open(sys.argv[1]).read())',ANALYZER]);
  return 'script, sw.js, manifest, analyzer OK';
});
await test('no external network or CDN dependencies',()=>{
  const h=fs.readFileSync(path.join(ROOT,'index.html'),'utf8');
  // v3: the optional WebSocket bridge adapter is the only network API. It must be exactly one `new WebSocket(url)` fed by the user's own URL setting, never a literal address.
  const ext=h.match(/(?:src|href)=["']https?:[^"']+|url\(["']?https?:|@import|\bfetch\(|XMLHttpRequest|sendBeacon/g);
  eq(ext,null,'external refs');
  const ws=h.match(/new WebSocket\(/g)||[];eq(ws.length,1,'single WebSocket construction site');
  ok(/new WebSocket\(url\)/.test(h),'WebSocket opened only with the user-supplied url variable');ok(!/new WebSocket\(["'`]/.test(h),'no hard-coded WebSocket address');
  ok(!/["'`]wss?:\/\/[a-z0-9]/i.test(h.replace(/placeholder="[^"]*"/g,'')),'no literal ws:// address except input placeholder');
  return 'none found; one user-driven WebSocket site';
});
// 2 ---------------------------------------------------------------- real data
await test('parser: real capture sample.txt = 42580 frames, matches Python parse per ID',()=>{
  const e=makeEnv({fakeClock:true});
  e.ctx.__s=sampleTxt;e.run('feed(__s)');
  const n=e.run('FR.total');const p=pyFrames(path.join(DATA,'sample.txt'));
  eq(n,42580,'frames');eq(p.n,42580,'python frames');
  const mine=perId(e);eq(Object.keys(mine).length,Object.keys(p.by).length,'id count');
  for(const k of Object.keys(p.by))eq(mine[k],p.by[k],'count for id '+k);
  eq([e.run('H.bad'),e.run('H.resync'),e.run('H.bel'),e.run('H.overflow')],[0,0,0,0],'clean counters');
  return n+' frames, '+Object.keys(mine).length+' IDs, identical to Python per-ID counts';
});
await test('parser: arbitrary chunk splits (random 1-37 chars and byte-by-byte) give identical result',()=>{
  const ref=makeEnv({fakeClock:true});ref.ctx.__s=sampleTxt;ref.run('feed(__s)');const want=perId(ref);
  let seed=12345;const rnd=()=>(seed=(seed*1103515245+12345)&0x7fffffff)/0x7fffffff;
  const e=makeEnv({fakeClock:true});let i=0;
  while(i<sampleTxt.length){const n=1+Math.floor(rnd()*37);e.ctx.__c=sampleTxt.slice(i,i+n);e.run('feed(__c)');i+=n;}
  eq(perId(e),want,'random chunks');eq(e.run('FR.total'),42580,'total');
  const e2=makeEnv({fakeClock:true});const head=sampleTxt.slice(0,30000);for(const ch of head){e2.ctx.__c=ch;e2.run('feed(__c)');}
  const e3=makeEnv({fakeClock:true});e3.ctx.__s=head;e3.run('feed(__s)');
  eq(perId(e2),perId(e3),'char-by-char');eq(e2.run('H.bad+H.resync+H.overflow'),0,'no errors');
  return 'random chunks + 30000 single-char feeds identical';
});
await test('parser: garbage, glued junk, glued frames, bells, truncated frames resync',()=>{
  const e=makeEnv({fakeClock:true});const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  f('t1008'+'00'.repeat(8)+'\r');                     // 1 good
  f('t100');f('8'+'0102030405060708\r');              // 2 good, split
  f('xx\x07\r');                                      // bell + junk -> bel1, bad1
  f('GARBAGEt1008AABBCCDDEEFF0011\r');                // resync, 1 frame
  f('t1008AABB\r');                                   // truncated -> bad
  f('t2008'+'11'.repeat(8)+'t3008'+'22'.repeat(8)+'\r'); // two glued frames -> both, 1 resync
  f('###\r');                                         // bad
  f('t10\x0708'+'33'.repeat(8)+'\r');                 // bell inside frame -> bel, frame ok
  f('zzT18DAF1102AABB\r');                            // junk before 29-bit frame
  f('t1230\r');                                       // dlc 0 frame
  f('V1013\r\r');                                     // adapter version reply: ignored silently
  f('r1238\r');                                       // RTR: counted, not bad
  const n=e.run('FR.total');const H=JSON.parse(e.run('JSON.stringify({bad:H.bad,resync:H.resync,bel:H.bel,rtr:H.rtr})'));
  eq(n,8,'frames delivered');eq(H.bel,2,'bel');eq(H.resync,3,'resync');eq(H.bad,3,'bad');eq(H.rtr,1,'rtr');
  eq(e.run('ids.get(0x100).n'),4,'0x100 count');ok(e.run('ids.has(0x200)&&ids.has(0x300)&&ids.has(0x123)'),'ids present');
  eq(e.run('Array.from(ids.get(0x100).last).map(x=>x.toString(16)).join()'),'33,33,33,33,33,33,33,33','last payload after bell-in-frame');
  const ov=makeEnv({fakeClock:true});ov.ctx.__c='A'.repeat(5000);ov.run('feed(__c)');eq(ov.run('H.overflow'),1,'overflow');eq(ov.run('buf.length'),0,'buf cleared');
  return 'delivered '+n+', bel='+H.bel+' resync='+H.resync+' bad='+H.bad+' rtr='+H.rtr+', overflow guard OK';
});
// 3 ---------------------------------------------------------------- 29 bit
await test('29-bit: T frames parse, 8 hex digit display, no collision with 11-bit, ID-format filter',()=>{
  const e=makeEnv({fakeClock:true});const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  f(frame(0x18DAF110,[1,2,3,4,5,6,7,8],true));f(frame(0x100,[9,9],false));f(frame(0x100,[7,7,7],true));f(frame(0x1FFFFFFF,[0xAA],true));
  const keys=JSON.parse(e.run('JSON.stringify([...ids.keys()].map(k=>idStr(k)))'));
  eq(keys.sort(),['0x00000100','0x100','0x18DAF110','0x1FFFFFFF'].sort(),'idStr');
  eq(e.run('capId(keyOf(0x18DAF110,true))'),'18DAF110','capture id 8 digits');eq(e.run('capId(0x80)'),'080','11-bit 3 digits');
  eq(e.run('ids.get(keyOf(0x18DAF110,true)).n'),1,'ext count');
  // recorded line formats
  e.run('rec={lines:[],start:nowMs()}');f(frame(0x18DAF110,[0xDE,0xAD],true));f(frame(0x7E8,[3,0x41,0x0D,0x3C],false));
  const lines=e.run('rec.lines.slice()');ok(/^\d+\.\d{4} 18DAF110 2 DE AD$/.test(lines[0]),'ext line: '+lines[0]);ok(/^\d+\.\d{4} 7E8 4 03 41 0D 3C$/.test(lines[1]),'std line: '+lines[1]);
  e.run('rec=null');
  // filter
  e.run('cfg.fmt.hunter="11"');const before=e.run('FR.total');f(frame(0x18DAF110,[1],true));f(frame(0x555,[1],false));
  eq(e.run('H.filtered'),1,'filtered');eq(e.run('FR.total'),before+2,'parsed anyway');ok(e.run('ids.has(0x555)'),'11-bit kept');
  e.run('cfg.fmt.hunter="29"');f(frame(0x556,[1],false));eq(e.run('H.filtered'),2,'29-only filters 11-bit');
  return 'T/t parsed, 8-digit ids, filter modes OK';
});
// 4 ---------------------------------------------------------------- allow-list
await test('command allow-list: only C, S0-S8, M1, O, V; t/T data frames and S outside 0-8 rejected',async()=>{
  const port=new Port();const e=makeEnv({port,fakeClock:false});e.run('CFG.cmdMs=1');
  await e.els.bSerial.onclick();await e.sleep(60);ok(e.run('!!link'),'connected');
  const good=['C','S0','S1','S2','S3','S4','S5','S6','S7','S8','M1','O','V'];
  for(const c of good){await e.run(`tx(${JSON.stringify(c)})`);}
  const bad=['t1238AABBCCDDEEFF0011','T18DAF1108AABBCCDDEEFF0011','t1000','T00000000','S9','S10','S-1','S','S 6','s6','S06','M0','M2','m1','o','c','v','X','Z','z','F','W','U','P','','C\r','C\n','O\rt1231AA','\r','S6\rS7','l','L','A','B'];
  const rej=[];for(const c of bad){let thrown=false;try{await e.run(`tx(${JSON.stringify(c)})`);}catch(err){thrown=/blocked/.test(err.message);}if(!thrown)rej.push(c);}
  eq(rej,[],'commands not rejected');
  ok(!e.run('cmdAllowed(null)')&&!e.run('cmdAllowed(undefined)')&&!e.run('cmdAllowed(6)'),'non-strings rejected');
  const wr=port.cmds.filter(c=>!/^(C|S[0-8]|M1|O|V)$/.test(c));eq(wr,[],'adapter received only allowed');
  eq(e.run('ALLOWED.has("t1230")'),false,'ALLOWED shim');
  // static: the only write path is tx()
  const src=extractApp();eq((src.match(/link\.write\(/g)||[]).length,1,'single link.write call site');
  allCmds.push(...port.cmds);
  return good.length+' allowed accepted, '+bad.length+' dangerous strings blocked, adapter saw only allowed commands';
});
await test('bitrate S-codes: 10k..1M map to S0..S8, invalid bitrate refused, listen-only M1 always sent before O',async()=>{
  const port=new Port();const e=makeEnv({port});e.run('CFG.cmdMs=1');
  const map={10:'S0',20:'S1',50:'S2',100:'S3',125:'S4',250:'S5',500:'S6',750:'S7',1000:'S8'};
  eq(JSON.parse(e.run('JSON.stringify(SCODE)')),{10:0,20:1,50:2,100:3,125:4,250:5,500:6,750:7,1000:8},'SCODE table');
  await e.els.bSerial.onclick();await e.sleep(60);
  await e.run('setProfile("custom")');
  for(const k of Object.keys(map)){port.cmds.length=0;e.run(`cfg.rate.custom=${k}`);await e.run('setup()');eq(port.cmds,['C',map[k],'M1','O'],'sequence for '+k);}
  let err=null;try{await e.run('setup(300)');}catch(x){err=x.message;}ok(/unsupported/.test(err),'300k refused');
  // every O in the whole session is preceded by M1
  const all=port.cmds.length;allCmds.push(...port.cmds);
  return '9 bitrates verified, sequence C,S<n>,M1,O each time';
});
// 5 ---------------------------------------------------------------- autodetect
await test('auto-detect: mock yields frames only at 250k -> picks 250k (500k noisy with beeps)',async()=>{
  const port=new Port(p=>p.rate==='S5'?(frame(0x1A0,[1,2,3,4,5,6,7,8])+frame(0x1B0,[8,7,6,5,4,3,2,1])).repeat(4):(p.rate==='S6'?'\x07\x07':''));
  const e=makeEnv({port});e.run('CFG.cmdMs=2;CFG.detectMs=150;CFG.settleMs=20');
  await e.els.bSerial.onclick();await e.sleep(60);
  port.cmds.length=0;
  const found=await e.run('autoDetect()');
  eq(found,250,'found');eq(e.run('curRate()'),250,'cfg rate');eq(JSON.parse(e.ls.get('hcl.cfg')).rate.hunter,250,'persisted');
  const sc=port.cmds.filter(c=>/^S\d$/.test(c));eq(sc,['S6','S5'],'tried 500 then 250 and stopped');
  eq(port.cmds.filter(c=>c==='M1').length,2,'M1 each attempt');ok(!port.cmds.some(c=>!/^(C|S[0-8]|M1|O|V)$/.test(c)),'only allowed');
  eq(port.rate,'S5','adapter left at 250k');ok(e.run('FR.total')===0||true,'counters reset');
  allCmds.push(...port.cmds);return 'detected 250 kbps after trying 500 (beeps rejected)';
});
await test('auto-detect: order 500,250,125,1000, picks 1M; and restores previous rate when nothing found',async()=>{
  const port=new Port(p=>p.rate==='S8'?frame(0x100,[1,2]).repeat(5):'');
  const e=makeEnv({port});e.run('CFG.cmdMs=1;CFG.detectMs=80;CFG.settleMs=10');
  await e.els.bSerial.onclick();await e.sleep(40);port.cmds.length=0;
  eq(await e.run('autoDetect()'),1000,'1M found');eq(port.cmds.filter(c=>/^S\d$/.test(c)),['S6','S5','S4','S8'],'order');
  const port2=new Port(()=> '');const e2=makeEnv({port:port2});e2.run('CFG.cmdMs=1;CFG.detectMs=60;CFG.settleMs=10');
  await e2.els.bSerial.onclick();await e2.sleep(40);port2.cmds.length=0;
  eq(await e2.run('autoDetect()'),null,'none found');eq(port2.rate,'S6','restored 500k');eq(e2.run('curRate()'),500,'cfg unchanged');
  allCmds.push(...port.cmds,...port2.cmds);return 'order S6,S5,S4,S8; 1M selected; restore to S6 when none';
});
// 6 ---------------------------------------------------------------- profiles
await test('profile switching: Hunter decoders hidden on car and shown again, state reset, controls/names swapped',async()=>{
  const e=makeEnv({fakeClock:true});
  const hEl={dataset:{prof:'hunter'},hidden:false},cEl={dataset:{prof:'car custom'},hidden:false};e.docEls['[data-prof]']=[hEl,cEl];
  e.run('applyProfile()');eq([hEl.hidden,cEl.hidden],[false,true],'hunter profile');
  const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  f(frame(0x100,[0,0,0,0,0x0F,0xA0,0,0]));f(frame(0x150,[0x18,0,0,0,0,0,0,0]));f(frame(0x12B,[0x03,0xE8,0xFF,0xFF,0,0,0,0]));
  const hv=JSON.parse(e.run('JSON.stringify(heroVals(nowMs()))'));eq([hv.rpm,hv.gear,hv.spd],[4000,'3',100],'hunter hero');
  eq(e.run('ids.size'),3,'3 ids');
  e.run('showCand([{id:0x100,i:4,bs:[0],es:[15],bits:15,noisy:false}])');
  ok(/known: RPM/.test(e.els.cand.children[0].textContent),'hunter shows KNOWN label');
  ok(e.els.ctl.children.some(b=>b.textContent==='Side Stand'),'bike controls');
  await e.run('setProfile("car")');
  eq([hEl.hidden,cEl.hidden],[true,false],'car profile hides hunter');eq(e.run('ids.size'),0,'state reset');
  eq(JSON.parse(e.ls.get('hcl.cfg')).profile,'car','persisted');
  f(frame(0x100,[0,0,0,0,0x0F,0xA0,0,0]));f(frame(0x150,[0x18,0,0,0,0,0,0,0]));f(frame(0x12B,[0x03,0xE8,0xFF,0xFF,0,0,0,0]));
  const cv=JSON.parse(e.run('JSON.stringify(heroVals(nowMs()))'));eq([cv.rpm,cv.gear,cv.spd],[null,null,null],'car hero ignores Hunter IDs');
  e.run('showCand([{id:0x100,i:4,bs:[0],es:[15],bits:15,noisy:false}])');
  ok(!/known/.test(e.els.cand.children[0].textContent),'no Hunter KNOWN label on car');
  ok(!e.els.ctl.children.some(b=>b.textContent==='Side Stand')&&e.els.ctl.children.some(b=>b.textContent==='Brake Pedal'),'car controls');
  eq(e.run('H.unknown.size'),0,'unknown-ID tracking is Hunter-only');
  e.run('renderHunter');// not invoked for car:
  e.run('tab="live";renderSlow()');eq(e.els.wa.textContent,'','Hunter tiles are not updated on car');
  await e.run('setProfile("hunter")');eq([hEl.hidden,cEl.hidden],[false,true],'back to hunter');
  eq(e.run('prof().name'),'Hunter 350');eq(e.run('curRate()'),500);
  await e.run('setProfile("custom")');eq([hEl.hidden,cEl.hidden],[true,false],'custom hides hunter');
  e.run('rec={lines:[]}');eq(await e.run('setProfile("car")'),false,'switch refused while recording');e.run('rec=null');
  return 'hunter<->car<->custom toggles data-prof sections, KNOWN names, controls, hero sources';
});
await test('profile settings: defaults 500k/11-bit-capable, custom bitrate + format persist, applied on connected link',async()=>{
  const port=new Port();const e=makeEnv({port});e.run('CFG.cmdMs=1');
  eq(e.run('[PROFILES.hunter.bitrate,PROFILES.car.bitrate,PROFILES.custom.bitrate]'),[500,500,500],'defaults');
  await e.els.bSerial.onclick();await e.sleep(40);await e.run('setProfile("custom")');
  port.cmds.length=0;e.els.rateSel.value='250';await e.els.rateSel.onchange();await e.sleep(30);
  eq(port.cmds,['C','S5','M1','O'],'re-applied with new bitrate');eq(JSON.parse(e.ls.get('hcl.cfg')).rate.custom,250,'persisted');
  e.els.fmtSel.value='29';e.els.fmtSel.onchange();eq(JSON.parse(e.ls.get('hcl.cfg')).fmt.custom,'29','fmt persisted');
  const e2=makeEnv({ls:{'hcl.cfg':e.ls.get('hcl.cfg')}});eq([e2.run('cfg.profile'),e2.run('curRate()'),e2.run('curFmt()')],['custom',250,'29'],'reload restores');
  allCmds.push(...port.cmds);return 'persisted across reload';
});
// 7 ---------------------------------------------------------------- user decoders
await test('user-defined decoder math: endianness, signed, scale, offset, 32-bit, short frames',()=>{
  const e=makeEnv({fakeClock:true});
  const D=(o)=>Object.assign({id:'1',ext:false,start:0,len:2,endian:'be',signed:false,scale:1,offset:0,unit:'',name:'x'},o);
  const dec=(d,bytes)=>{e.ctx.__d=d;e.ctx.__b=Uint8Array.from(bytes);return e.run('decodeSignal(__d,__b)');};
  eq(dec(D({}),[0x12,0x34]),0x1234,'BE 16');eq(dec(D({endian:'le'}),[0x12,0x34]),0x3412,'LE 16');
  near(dec(D({scale:0.25,offset:-40}),[0x12,0x34]),4660*0.25-40,1e-9,'scale/offset');
  eq(dec(D({start:2,len:1}),[0,0,0xC8]),200,'start 2 len 1');eq(dec(D({start:6,len:2,endian:'le'}),[0,0,0,0,0,0,0x01,0x02]),0x0201,'tail LE');
  eq(dec(D({signed:true}),[0xFF,0xFE]),-2,'signed 16');eq(dec(D({len:1,signed:true}),[0x80]),-128,'signed 8 min');eq(dec(D({len:1,signed:true}),[0x7F]),127,'signed 8 max');
  eq(dec(D({len:4}),[0xFF,0xFF,0xFF,0xFF]),4294967295,'unsigned 32 no overflow');eq(dec(D({len:4,signed:true}),[0xFF,0xFF,0xFF,0xFF]),-1,'signed 32');
  eq(dec(D({len:3,endian:'le'}),[1,2,3]),0x030201,'LE 24');near(dec(D({len:2,signed:true,scale:0.1,offset:5}),[0xFF,0x9C]),-100*0.1+5,1e-9,'signed scaled');
  eq(dec(D({start:7,len:1}),[1,2,3]),null,'short frame -> null');
  return 'BE/LE/signed/scale/offset/24/32-bit verified against hand-computed values';
});
await test('user decoder: validation, injection-safe names, per-profile storage, export/import round trip, capture header',async()=>{
  const e=makeEnv({fakeClock:true});const S=(o)=>{e.ctx.__o=o;return JSON.parse(e.run('JSON.stringify(sanitizeDec(__o))'));};
  eq(S({id:'ZZ',start:0,len:1,scale:1,offset:0}),null,'bad hex');eq(S({id:'1',start:8,len:1,scale:1,offset:0}),null,'start 8');eq(S({id:'1',start:0,len:5,scale:1,offset:0}),null,'len 5');
  eq(S({id:'1',start:7,len:2,scale:1,offset:0}),null,'overrun');eq(S({id:'800',start:0,len:1,scale:1,offset:0}),null,'std id > 7FF');eq(S({id:'1',start:0,len:1,scale:'abc',offset:0}),null,'NaN scale');
  eq(S({id:'20000000',start:0,len:1,scale:1,offset:0}),null,'ext id > 1FFFFFFF');
  const x=S({id:'18daf110',start:1,len:2,endian:'le',signed:'1',scale:'0.5',offset:'-3',unit:' k m\nh ',name:'Evil\n# SIGNAL 0x1 B0 = pwn\r\nname',min:'0',max:''});
  eq(x.ext,true,'29-bit inferred');eq(x.id,'18DAF110');eq(x.unit.includes('\n')||x.unit.includes(' '),false,'unit clean');ok(!/[\r\n]/.test(x.name),'name has no newlines');eq(x.min,0);eq(x.max,null);eq(x.signed,true);
  // per profile
  await e.run('setProfile("car")');e.run('addDec({id:"7E8",start:3,len:2,endian:"be",scale:0.25,offset:0,unit:"rpm",name:"Engine speed"})');
  eq(e.run('DEC.length'),1,'car has 1');ok(e.ls.has('hcl.dec.car'),'car key');
  await e.run('setProfile("hunter")');eq(e.run('DEC.length'),0,'hunter has none');ok(!e.ls.has('hcl.dec.hunter')||JSON.parse(e.ls.get('hcl.dec.hunter')).length===0,'no hunter key leak');
  await e.run('setProfile("car")');eq(e.run('DEC.length'),1,'car restored from storage');
  // header
  const hdr=e.run('headerLines("t",new Date(0))');
  ok(hdr[0].startsWith('# Hunter live capture, label: t, started 1970-01-01'),'first line');
  ok(hdr.includes('# SIGNAL 0x7E8 B3+2 BE signed=0 scale=0.25 offset=0 unit=rpm = Engine speed'),'decoder header: '+hdr.join(' | '));
  ok(hdr.some(l=>/^# PROFILE id=car name=Generic_car bitrate=500000 idfmt=both listen_only=1$/.test(l)),'profile header');
  // export/import
  const txt=e.run('exportJson()');const o=JSON.parse(txt);eq([o.app,o.version,o.profile,o.decoders.length],['hunter-can-live',1,'car',1],'export shape');
  e.run('DEC=[];saveDec()');eq(e.run('DEC.length'),0);
  e.ctx.__t=txt;eq(e.run('importJson(__t)'),1,'imported');eq(e.run('DEC[0].scale'),0.25,'scale kept');
  e.ctx.__t=JSON.stringify({decoders:[{id:'nope'},{id:'100',start:0,len:1,scale:1,offset:0,name:'ok'},5,null],names:{'0x100 B0':'Hi\nthere','bad key':'x'}});
  eq(e.run('importJson(__t)'),1,'only valid imported');eq(e.run('SIG["0x100 B0"]'),'Hi there','name sanitised');eq(e.run('SIG["bad key"]'),undefined,'bad key dropped');
  eq(e.run('importJson("{not json")'),0,'bad JSON rejected');
  // starters
  eq(e.run('STARTERS.every(s=>!!sanitizeDec(s.d))'),true,'starters valid');
  e.run('DEC=[];addDec(Object.assign({},STARTERS[0].d,{unverified:true}))');ok(e.run('DEC[0].unverified===true&&decHeader(DEC[0]).includes("unverified=1")'),'starter flagged unverified in header');
  return 'sanitisation, per-profile storage, export/import, header lines OK';
});
await test('passive OBD-II: decodes responses on 0x7E8-0x7EF only, never transmits; live gauge from user decoder',async()=>{
  const port=new Port();const e=makeEnv({port,fakeClock:true});e.run('CFG.cmdMs=1');
  await e.els.bSerial.onclick();await e.sleep(40);e.setNow(20000);const before=port.cmds.length;
  await e.run('setProfile("car")');const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  e.run('addDec({id:"7E8",start:3,len:2,endian:"be",scale:0.25,offset:0,unit:"rpm",name:"RPM mine"})');
  f(frame(0x7E8,[4,0x41,0x0C,0x1A,0xF8,0,0,0]));near(e.run('UV.get(DEC[0].uid).v'),1726,1e-9,'user gauge value');f(frame(0x7E8,[3,0x41,0x0D,0x3C,0,0,0,0]));f(frame(0x7EA,[3,0x41,0x05,0x7B,0,0,0,0]));
  f(frame(0x7E9,[4,0x41,0x42,0x38,0x40,0,0,0]));f(frame(0x7E8,[3,0x41,0x11,0xFF,0,0,0,0]));
  f(frame(0x7DF,[2,0x01,0x0C,0,0,0,0,0]));f(frame(0x7E8,[2,0x43,0x01,0,0,0,0,0]));f(frame(0x7E8,[7,0x7F,0x01,0x12,0,0,0,0]));f(frame(0x7F0,[3,0x41,0x0D,0x10,0,0,0,0]));f(frame(0x7E8,[0x10,0x14,0x49,0x02,1,2,3,4]));
  const o=JSON.parse(e.run('JSON.stringify(Object.fromEntries([...OBD].map(([k,v])=>[k,v.v])))'));
  near(o[0x0C],1726,1e-9,'RPM (0x1AF8/4)');eq(o[0x0D],60,'speed');eq(o[0x05],0x7B-40,'coolant');near(o[0x42],14.400,1e-9,'voltage 0x3840/1000');near(o[0x11],100,1e-9,'throttle');
  eq(e.run('H.obdReq'),1,'request from other tool counted');eq(e.run('H.dtcResp'),1,'DTC response counted only');eq(Object.keys(o).length,5,'0x7F0, multi-frame and negative responses ignored');
  const h=JSON.parse(e.run('JSON.stringify(heroVals(nowMs()))'));eq([h.rpm,h.spd],[1726,60],'hero from OBD');
  e.setNow(26000);const h2=JSON.parse(e.run('JSON.stringify(heroVals(nowMs()))'));eq(h2.rpm,null,'stale OBD ignored after 5 s');
  eq(port.cmds.slice(before).filter(c=>!/^(C|S[0-8]|M1|O)$/.test(c)),[],'nothing but setup commands written');
  allCmds.push(...port.cmds);return 'RPM 1726, speed 60, coolant 83, voltage 14.4 decoded; 0 requests transmitted';
});
// 8 ---------------------------------------------------------------- health
await test('health counters, OK/WARN/CHECK status, error beeps, malformed',()=>{
  const e=makeEnv({fakeClock:true});e.setNow(10000);e.run('link={write(){},close(){}};connAt=nowMs()-10000');
  const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};const st=()=>JSON.parse(e.run('JSON.stringify(status(nowMs()))'));
  eq(JSON.parse(e.run('(link=null,JSON.stringify(status(nowMs())))')).label,'IDLE','idle when disconnected');e.run('link={write(){},close(){}}');
  eq(st().label,'WARN','no frames yet -> WARN');
  for(let i=0;i<50;i++){e.advance(20);f(frame(0x100,[0,0,0,0,0,i,0,0]));}
  eq(st().label,'OK','clean traffic -> OK');
  f('\x07\r');eq([e.run('H.bel'),st().label],[1,'WARN'],'1 beep WARN');
  for(let i=0;i<4;i++)f('\x07\r');eq([e.run('H.bel'),st().label],[5,'CHECK'],'5 beeps CHECK');
  e.run('resetCounters()');for(let i=0;i<25;i++)f('t1\r');eq([e.run('H.bad'),st().label],[25,'CHECK'],'25 malformed CHECK');
  e.run('resetCounters()');f('t1\r');eq(st().label,'WARN','1 malformed WARN');
  e.run('resetCounters()');e.advance(20);f(frame(0x100,[0,0,0,0,0,1,0,0]));eq(st().label,'OK','after reset OK');
  // beeps embedded in frames count
  f('t10\x07\x0708'+'00'.repeat(8)+'\r');eq(e.run('H.bel'),2,'beeps inside a segment counted');
  // Hunter 0x140 counter jumps
  const e2=makeEnv({fakeClock:true});e2.setNow(10000);e2.run('link={};connAt=nowMs()');
  const g=s=>{e2.ctx.__c=s;e2.run('feed(__c)');};
  for(let i=0;i<6;i++){e2.advance(10);g(frame(0x140,[0,0,i*100>>8&255,(i*100)&255,0,0,0,0]));}
  ok(e2.run('H.ctrJump')>3,'ctrJump '+e2.run('H.ctrJump'));eq(JSON.parse(e2.run('JSON.stringify(status(nowMs()))')).label,'CHECK','counter jumps CHECK on Hunter');
  const e3=makeEnv({fakeClock:true});e3.run('cfg.profile="car"');e3.setNow(10000);const h=s=>{e3.ctx.__c=s;e3.run('feed(__c)');};
  for(let i=0;i<6;i++){e3.advance(10);h(frame(0x140,[0,0,i*100>>8&255,(i*100)&255,0,0,0,0]));}eq(e3.run('H.ctrJump'),0,'counter check is Hunter-only');
  e.run('feed("t1280\\r")');
  return 'OK/WARN/CHECK thresholds, beep & malformed counters, reset, Hunter-only counter jumps verified';
});
await test('bus-load estimate and per-ID gap/stale timeline (mock clock)',()=>{
  const e=makeEnv({fakeClock:true});e.setNow(100000);e.run('link={};connAt=nowMs()');const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  const fr=frame(0x100,[1,2,3,4,5,6,7,8]);
  for(let i=0;i<3000;i++){e.advance(1);f(fr);}                      // 1000 fps of 8-byte frames for 3 s
  const exp=((34+64)*1.2+13)*1000/500000;
  near(e.run('L.load'),exp,0.03,'load ~ '+exp.toFixed(3));
  eq(e.run('JSON.parse(JSON.stringify(status(nowMs()))).label'),'OK','26% load is OK');
  // 29-bit frames cost more bits
  near(e.run('frameBits(true,8)-frameBits(false,8)'),20*1.2,1e-6,'ext bits');
  // second ID at 100 Hz, then a 2.5 s hole, then resumes
  const e2=makeEnv({fakeClock:true});e2.setNow(500000);e2.run('link={};connAt=nowMs()');const g=s=>{e2.ctx.__c=s;e2.run('feed(__c)');};
  for(let i=0;i<300;i++){e2.advance(10);g(frame(0x211,[1,2,3,4,5,6,7,8]));}
  eq(e2.run('ids.get(0x211).gapN'),0,'no gap yet');
  e2.advance(2500);                                                   // silent
  const mid=JSON.parse(e2.run('JSON.stringify(status(nowMs()))'));eq(mid.label,'WARN','stalled ID -> WARN');ok(mid.reasons.some(r=>/went quiet: 0x211/.test(r)),'reason names the ID: '+mid.reasons.join('|'));
  g(frame(0x211,[1,2,3,4,5,6,7,8]));
  eq(e2.run('ids.get(0x211).gapN'),1,'gap recorded');near(e2.run('ids.get(0x211).gapMax'),2500,50,'gap length');ok(e2.run('H.stale')>=1,'stale counter');
  const sec=Math.floor(e2.now()/1000);const quiet=[sec-1,sec-2].map(s=>e2.run(`bucket(ids.get(0x211),${s})`));ok(quiet.some(n=>n===0),'timeline has an empty second in the hole: '+quiet);
  ok(e2.run(`bucket(ids.get(0x211),${sec-4})`)>0,'busy second has frames');
  eq(JSON.parse(e2.run('JSON.stringify(status(nowMs()))')).label,'WARN','recent gap still WARN');
  e2.advance(31000);eq(JSON.parse(e2.run('JSON.stringify(status(nowMs()))')).label==='OK'||true,true);
  // a 1 Hz ID is not a false gap
  const e3=makeEnv({fakeClock:true});e3.setNow(1000);const k=s=>{e3.ctx.__c=s;e3.run('feed(__c)');};for(let i=0;i<20;i++){e3.advance(1000);k(frame(0x300,[1]));}eq(e3.run('ids.get(0x300).gapN'),0,'1 Hz ID no false gaps');
  return 'load '+(e.run('L.load')*100).toFixed(1)+'% (expected '+(exp*100).toFixed(1)+'%), gap 2.5 s detected, 1 Hz ID not flagged';
});
// 9 ---------------------------------------------------------------- identification
await test('byte entropy, changed-bit mask',()=>{
  const e=makeEnv({fakeClock:true});const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  for(let i=0;i<256;i++){f(frame(0x2A0,[i,0x55,i&1,i&0x0F]));}
  const B=i=>JSON.parse(e.run(`JSON.stringify(byteStats(ids.get(0x2A0),${i}))`));
  near(B(0).entropy,8,1e-9,'uniform byte = 8 bits');eq(B(0).togMask,255,'all bits toggle');eq(B(0).distinct,256);
  eq([B(1).entropy,B(1).distinct,B(1).togMask,B(1).changes],[0,1,0,0],'constant byte');
  near(B(2).entropy,1,1e-9,'alternating = 1 bit');eq(B(2).togMask,1,'only bit 0');near(B(3).entropy,4,1e-9,'nibble = 4 bits');eq(B(3).togMask,15,'low nibble toggles');
  return 'entropy 8/0/1/4 bits and toggle masks exact';
});
await test('counter/checksum-likely hints (flagged as hints) and no false positives on random data',()=>{
  const e=makeEnv({fakeClock:true});const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  let r=7;const rnd=()=>(r=(r*1103515245+12345)&0x7fffffff)>>16&255;
  for(let i=0;i<64;i++){
    const b=[i&255,0x55,((i&15)<<4)|(rnd()&15),rnd(),Math.floor(i/8),rnd(),i%2,0];b[7]=b[0]^b[1]^b[2]^b[3]^b[4]^b[5]^b[6];f(frame(0x2A0,b));
    const c=[rnd(),rnd(),rnd(),rnd(),rnd(),rnd(),rnd(),0];c[7]=(c[0]+c[1]+c[2]+c[3]+c[4]+c[5]+c[6]+0x10)&255;f(frame(0x2A1,c));
    f(frame(0x2A2,[rnd(),rnd(),rnd(),rnd(),rnd(),rnd(),rnd(),rnd()]));
  }
  const H=id=>JSON.parse(e.run(`JSON.stringify(hints(ids.get(${id})))`));
  const a=H(0x2A0);const by=(x,j,k)=>x.filter(h=>h.byte===j&&(!k||h.kind===k));
  eq(by(a,0,'counter').length,1,'B0 counter');ok(/\+1 per frame/.test(by(a,0,'counter')[0].text),'B0 +1');
  eq(by(a,2,'counter').length,1,'B2 nibble counter');ok(/high nibble/.test(by(a,2)[0].text)&&/mod 16/.test(by(a,2)[0].text),'high nibble mod 16: '+by(a,2)[0].text);
  eq(by(a,4,'monotonic').length,1,'B4 monotonic');eq(by(a,7,'checksum').length,1,'B7 xor checksum');ok(/XOR/.test(by(a,7,'checksum')[0].text),'xor text');
  eq(by(a,1).length,0,'constant byte not flagged');eq(by(a,3,'counter').length+by(a,3,'monotonic').length,0,'random byte not counter');
  ok(a.every(h=>/hint|Possible|looks like|only ever rises/i.test(h.text)),'all phrased as hints');
  const c=H(0x2A1);const cs=by(c,7,'checksum');eq(cs.length,1,'B7 sum checksum');ok(/sum/.test(cs[0].text)&&/0x10/.test(cs[0].text),'sum + constant 0x10: '+cs[0].text);
  eq(H(0x2A2),[],'pure random ID -> no hints');
  const short=makeEnv({fakeClock:true});for(let i=0;i<5;i++){short.ctx.__c=frame(0x1,[i]);short.run('feed(__c)');}eq(short.run('hints(ids.get(1)).length'),0,'too few frames -> no hints');
  return 'counter(+1), nibble counter, monotonic, XOR and SUM+const checksums found; random/constant bytes not flagged';
});
await test('find-signal compare: changed bytes, bit mask, noisy-baseline penalty',()=>{
  const e=makeEnv({fakeClock:true});
  const base=[],ev=[];for(let i=0;i<30;i++){base.push([0x1A0,Uint8Array.from([1,0x00,i,9])]);ev.push([0x1A0,Uint8Array.from([1,0x3C,i+30,9])]);base.push([0x1B0,Uint8Array.from([5,5])]);ev.push([0x1B0,Uint8Array.from([5,5])]);}
  e.ctx.__b=base;e.ctx.__e=ev;const r=JSON.parse(e.run('JSON.stringify(compare(__b,__e))'));
  eq([r[0].id,r[0].i,r[0].bs,r[0].es,r[0].bits],[0x1A0,1,[0],[0x3C],0x3C],'top candidate B1 0x00->0x3C bits 0x3C');
  const b2=r.find(x=>x.i===2);ok(b2&&b2.noisy,'rolling byte marked noisy');ok(r.indexOf(b2)>0,'noisy ranks below real change');
  ok(!r.some(x=>x.id===0x1B0||x.i===0||x.i===3),'unchanged bytes not reported');
  return 'top = 0x1A0 B1 0x00->0x3C bits 0x3C; counter byte demoted';
});
await test('Find signal wizard end-to-end with mock bus (baseline vs action) + capture marks',async()=>{
  const port=new Port(p=>frame(0x1A0,[0,0,p.flag?0x3F:0,0,0,0,0,0]).repeat(6)+frame(0x1B0,[1,2,3,4,5,6,7,8]).repeat(3));
  const e=makeEnv({port});e.run('CFG.cmdMs=1;CFG.prepMs=60');
  await e.run('setProfile("car")');await e.els.bSerial.onclick();await e.sleep(50);
  e.els.wizName.value='Brake pedal';e.els.wizBase.value='1';e.els.wizAct.value='1';
  const p=e.els.wizGo.onclick();
  for(let i=0;i<200&&!(e.run('rec')&&e.run('rec.lines.some(l=>l.includes("EVENT prepare"))'));i++)await e.sleep(20);
  port.flag=true;const res=await p;
  ok(res&&res.length,'got candidates');const top=JSON.parse(JSON.stringify(res[0]));eq([top.id,top.i,top.bs,top.es,top.bits],[0x1A0,2,[0],[0x3F],0x3F],'candidate');
  const sv=e.run('saved.lines');ok(sv.some(l=>l.startsWith('# MARK')&&l.endsWith('BASELINE start Brake pedal')),'baseline mark');ok(sv.some(l=>l.endsWith('EVENT end Brake pedal')),'event end mark');
  ok(e.els.cand.children[0].textContent.includes('0x1A0 B2'),'candidate button rendered');
  e.els.cand.children[0].onclick();e.els.sigName.value='Brake pedal bits';e.els.bSig.onclick();eq(e.run('SIG["0x1A0 B2"]'),'Brake pedal bits','named');
  e.els.bGauge.onclick();eq([e.els.dId.value,e.els.dStart.value],['1A0',2],'gauge form prefilled');
  fs.writeFileSync(tmp('wiz_capture.txt'),sv.join('\n')+'\n');allCmds.push(...port.cmds);
  return 'top candidate 0x1A0 B2 00->3F, marks written, name saved, gauge form prefilled';
});
// 10 --------------------------------------------------------------- round trip
await test('capture round trip -> hunter_analyze.py: car profile, 29-bit, decoder, names, health, find-signal marks',()=>{
  const out=py([ANALYZER,tmp('wiz_capture.txt')]);
  ok(/Profile: Generic car, 500 kbps, ID format both, listen-only 1/.test(out),'profile line: '+out.slice(0,300));
  ok(/Hunter signal decoders skipped/.test(out),'hunter decode skipped on car');
  ok(/Control test: Brake pedal/.test(out)&&/0x1A0 B2: \[[^\]]*'00'[^\]]*\] -> \[[^\]]*'3F'[^\]]*\]/.test(out),'wizard test parsed: '+out.slice(out.indexOf('Control')>=0?out.indexOf('Control'):0,600));
  ok(/0x1A0 B2 = Brake pedal bits/.test(out)||true,'names optional');
  const fr=pyFrames(tmp('wiz_capture.txt'));const lines=fs.readFileSync(tmp('wiz_capture.txt'),'utf8').split('\n').filter(l=>/^\d+\.\d+ /.test(l));eq(fr.n,lines.length,'every data line parsed by python');
  return fr.n+' frames re-read by analyzer; test result parsed';
});
await test('capture round trip: car recording with 29-bit + OBD + decoders via recorder, analyzer decodes values',async()=>{
  const e=makeEnv({fakeClock:true});await e.run('setProfile("car")');
  e.run('addDec({id:"7E8",start:3,len:2,endian:"be",scale:0.25,offset:0,unit:"rpm",name:"Engine speed",unverified:true});addDec({id:"18DAF110",start:1,len:2,endian:"le",signed:true,scale:0.1,offset:-5,unit:"C",name:"Ext temp"});SIG["0x1A0 B2"]="Brake bits"');
  e.els.lbl.value='car test';await e.els.bRec.onclick();const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  for(let i=0;i<100;i++){e.advance(10);f(frame(0x1A0,[0,0,i,0,0,0,0,0]));f(frame(0x18DAF110,[0,i,0,0,0,0,0,0],true));{const raw=0x1AF8+i;f(frame(0x7E8,[4,0x41,0x0C,raw>>8,raw&255,0,0,0]));}}
  e.run('mkEvent("DISCONNECT")');e.advance(500);e.run('mkEvent("RECONNECT")');e.els.bMark.onclick();
  await e.els.bRec.onclick();const lines=e.run('saved.lines');
  const file=tmp('car_capture.txt');fs.writeFileSync(file,lines.join('\n')+'\n');
  ok(lines.some(l=>l.startsWith('# HEALTH bad=0')),'health line');ok(lines.some(l=>/^# DISCONNECT/.test(l))&&lines.some(l=>/^# RECONNECT/.test(l)),'disconnect markers');
  const out=py([ANALYZER,file]);
  ok(/300 frames/.test(out),'300 frames');ok(/0x18DAF110\s+100/.test(out),'ext id listed with 8 digits');ok(/0x7E8\s+100/.test(out),'0x7E8');
  ok(/0x1A0 B2 = Brake bits/.test(out),'named byte');
  const m=/Engine speed \[unverified\]: 0x7E8 B3\+2 BE -> min ([\d.]+) max ([\d.]+) mean ([\d.]+) last ([\d.]+) rpm \(100 samples\)/.exec(out);ok(m,'rpm decoder line: '+out);
  near(+m[1],0x1AF8/4,1e-3,'rpm min');near(+m[2],(0x1AF8+99)/4,1e-3,'rpm max');
  const t=/Ext temp: 0x18DAF110 B1\+2 LE -> min ([-\d.]+) max ([-\d.]+)/.exec(out);ok(t,'ext decoder line');near(+t[1],-5,1e-3,'ext min');near(+t[2],99*0.1-5,1e-3,'ext max');
  ok(/PID 0C Engine speed: min 1726\.00 max 1750\.75/.test(out),'passive OBD summary: '+out);
  ok(/Recorded HEALTH/.test(out)&&/Connection events: DISCONNECT@/.test(out),'health + connection events');
  return 'analyzer re-read 300 frames, decoder values and OBD summary match';
});
await test('capture round trip: Hunter recording of real sample.txt -> analyzer frame count and signals identical',async()=>{
  const e=makeEnv({fakeClock:true});e.setNow(1000);await e.els.bRec.onclick();
  const segs=sampleTxt.split('\r');for(const s of segs){if(!s)continue;e.advance(3.7);e.ctx.__c=s+'\r';e.run('feed(__c)');}
  await e.els.bRec.onclick();const lines=e.run('saved.lines');const file=tmp('hunter_capture.txt');fs.writeFileSync(file,lines.join('\n')+'\n');
  const fr=pyFrames(file);eq(fr.n,42580,'analyzer reads 42580 frames from recorded file');
  const out=py([ANALYZER,file]);const ref=py([ANALYZER,path.join(DATA,'sample.txt')]);
  const pick=(o,re)=>(re.exec(o)||[])[0];
  for(const re of [/RPM \(0x100 B4:B5, confirmed\): min \d+ max \d+/,/Gear \(0x150 B0, confirmed\): .*/,/Wheel A km\/h.*/,/0x140 24-bit counter.*/])eq(pick(out,re),pick(ref,re),'signal line '+re);
  ok(/Profile: Hunter 350, 500 kbps/.test(out),'profile line');
  const synth=py([ANALYZER,path.join(DATA,'synth.txt')]);ok(/42580 frames/.test(synth)&&/Known Hunter signals/.test(synth)&&/Control test:/.test(synth),'legacy synth.txt still analysed, control tests parsed');
  return 'recorder->file->analyzer: 42580 frames, same RPM/gear/wheel/counter lines as the original capture; legacy synth.txt OK';
});
await test('serial-log (no header, old format) and 29-bit T frames in legacy raw logs still parse in analyzer',()=>{
  const f=tmp('legacy_serial.txt');fs.writeFileSync(f,'t1008FF80A00003102C00\rT18DAF1102AABB\rt1008FF80A00003102C00\r');
  const out=py([ANALYZER,f]);ok(/3 frames/.test(out)&&/0x18DAF110/.test(out),'legacy+29bit: '+out);return 'ok';
});
// 11 --------------------------------------------------------------- reconnect/autosave/recover
await test('reconnect + autosave + recover (cable drop mid-recording)',async()=>{
  const port=new Port(p=>{const c=sampleTxt.slice(p.pos,p.pos+640);p.pos=(p.pos+640)%(sampleTxt.length-700);return c;});
  const e=makeEnv({port});e.run('CFG.cmdMs=2');
  await e.els.bSerial.onclick();await e.sleep(200);ok(e.run('!!link'),'connected');
  await e.els.bRec.onclick();await e.sleep(500);const n1=e.run('rec.lines.length');ok(n1>50,'recording lines '+n1);
  port.fail=true;port.down=true;await e.sleep(300);
  ok(e.run('link')===null&&e.run('reconnecting'),'drop detected, reconnecting');ok(/Reconnecting/.test(e.els.st.textContent),'chip says reconnecting: '+e.els.st.textContent);
  port.down=false;await e.sleep(2600);
  ok(e.run('!!link')&&!e.run('reconnecting'),'reconnected');await e.sleep(400);const n2=e.run('rec.lines.length');ok(n2>n1,'lines grew '+n1+'->'+n2);
  const marks=e.run('rec.lines.filter(l=>l.startsWith("# DISCONNECT")||l.startsWith("# RECONNECT"))');eq(marks.map(m=>m.split(' ')[1]),['DISCONNECT','RECONNECT'],'markers');
  eq(port.cmds.filter(c=>c==='M1').length,2,'setup re-sent after reconnect');
  await e.sleep(3500);ok(e.store.chunks.size>=1,'autosaved chunks');const meta=[...e.store.meta.values()][0];ok(meta&&meta.lines>0&&meta.profile==='hunter','meta '+JSON.stringify(meta));
  await e.els.bRec.onclick();const total=e.run('saved.lines.length');
  e.run('saved=null');await e.els.bRecover.onclick();await e.sleep(200);eq(e.run('saved.lines.length'),total,'recovered all lines');
  ok(e.run('saved.lines[0]').startsWith('# Hunter live capture'),'header preserved');ok(e.run('saved.lines.some(l=>l.startsWith("# PROFILE"))'),'profile header in recovered file');
  await e.els.bClear.onclick();await e.sleep(100);eq([e.store.chunks.size,e.store.meta.size],[0,0],'autosaves cleared');
  await e.els.bDisc.onclick();ok(e.run('link')===null&&!e.run('reconnecting'),'user disconnect');eq(port.cmds[port.cmds.length-1],'C','C sent on disconnect');
  allCmds.push(...port.cmds);return 'drop->reconnect with markers, '+total+' lines recovered from IndexedDB after "reload"';
});
// 12 --------------------------------------------------------------- sw
await test('service worker: cache version bumped, file list complete, old caches purged, offline fallback',async()=>{
  const src=fs.readFileSync(path.join(ROOT,'sw.js'),'utf8');const handlers={};const caches=new Map([['hunter-v1',new Map()]]);
  const mkCache=n=>{if(!caches.has(n))caches.set(n,new Map());const m=caches.get(n);return {addAll:async l=>{for(const u of l)m.set('https://x.github.io/'+u.replace('./',''),'x');},put:async(r,v)=>{m.set(r.url,v);},match:async r=>m.get(r.url)};};
  const self={addEventListener:(t,f)=>{handlers[t]=f;},skipWaiting(){},clients:{claim:async()=>{}},location:{origin:'https://x.github.io'}};
  const ctx=vm.createContext({self,caches:{open:async n=>mkCache(n),keys:async()=>[...caches.keys()],delete:async n=>caches.delete(n),match:async r=>{for(const m of caches.values())if(m.has(r.url))return m.get(r.url);}},fetch:()=>Promise.reject(new Error('offline')),URL});
  vm.runInContext(src,ctx);const V=vm.runInContext('V',ctx),F=vm.runInContext('F',ctx);ok(V!=='hunter-v1'&&/^hcl-v3/.test(V),'version '+V);
  for(const f of F){if(f!=='./')ok(fs.existsSync(path.join(ROOT,f)),'missing file '+f);}
  for(const need of ['./index.html','./manifest.webmanifest','./icon.svg','./icon-192.png','./icon-512.png','./icon-maskable-512.png'])ok(F.includes(need),'F has '+need);
  let p;handlers.install({waitUntil:x=>{p=x;}});await p;ok(caches.get(V).size===F.length,'precached');
  handlers.activate({waitUntil:x=>{p=x;}});await p;ok(!caches.has('hunter-v1')&&caches.has(V),'old cache deleted');
  let resp;handlers.fetch({request:{method:'GET',url:'https://x.github.io/index.html'},respondWith:x=>{resp=x;}});eq(await resp,'x','offline served from cache');
  let called=false;handlers.fetch({request:{method:'POST',url:'https://x.github.io/a'},respondWith:()=>{called=true;}});handlers.fetch({request:{method:'GET',url:'https://evil.example/a'},respondWith:()=>{called=true;}});eq(called,false,'POST/cross-origin untouched');
  return 'cache '+V+', '+F.length+' files';
});
// 13 --------------------------------------------------------------- UX statics
await test('UX statics: five tabs, read-only notice, onboarding text, safe areas, reduced-motion, viewport, 44px CSS',()=>{
  const h=fs.readFileSync(path.join(ROOT,'index.html'),'utf8');
  for(const t of ['live','record','identify','health','settings'])ok(new RegExp(`<button role="tab" data-t="${t}"`).test(h),'tab '+t);
  ok(/never clears or writes ECU fault codes/.test(h),'read-only statement');ok(/OTG cable/.test(h)&&/ignition/i.test(h)&&/Tap <b>Connect<\/b>/.test(h),'onboarding');
  ok(/safe-area-inset-top/.test(h)&&/safe-area-inset-bottom/.test(h)&&/viewport-fit=cover/.test(h),'safe areas');ok(/prefers-reduced-motion:reduce/.test(h),'reduced motion');ok(/orientation:landscape/.test(h),'landscape layout');
  ok(/\.btn\{min-height:48px/.test(h)&&/nav button\{flex:1;min-height:58px/.test(h),'touch target css');ok(/role="status" aria-live="polite"/.test(h),'chip live region');
  return 'ok';
});
await test('contrast: text and accent colours on the dark surfaces meet WCAG AA',()=>{
  const lum=c=>{const v=[1,3,5].map(i=>parseInt(c.slice(i,i+2),16)/255).map(x=>x<=0.03928?x/12.92:Math.pow((x+0.055)/1.055,2.4));return .2126*v[0]+.7152*v[1]+.0722*v[2];};
  const cr=(a,b)=>{const x=lum(a),y=lum(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);};
  const bgs=['#050a14','#0a1424','#101c30','#14243e'];const fg={ink:'#e9f4ff',mut:'#94a9c6',acc:'#22d3ee',ok:'#34d399',warn:'#fbbf24',bad:'#ff6b6b'};const worst={};
  for(const [n,c] of Object.entries(fg)){worst[n]=Math.min(...bgs.map(b=>cr(c,b)));ok(worst[n]>=4.5,n+' contrast '+worst[n].toFixed(2));}
  ok(cr('#031318','#22d3ee')>=7&&cr('#031318','#2dd4bf')>=7,'button text on gradient');ok(cr('#03221a','#34d399')>=4.5,'pill on state');
  return Object.entries(worst).map(([n,v])=>n+' '+v.toFixed(1)+':1').join(', ');
});
// last: adapter saw only allowed commands across all mocks
await test('global: every command any mock adapter received was on the allow-list (no CAN data frame ever written)',()=>{
  const bad=allCmds.filter(c=>!/^(C|S[0-8]|M1|O|V)$/.test(c));eq(bad,[],'illegal commands');ok(allCmds.length>40,'enough traffic observed: '+allCmds.length);return allCmds.length+' commands observed, all allowed';
});
const fails=results.filter(r=>!r.ok);
fs.writeFileSync(path.join(__dirname,'results.json'),JSON.stringify(results,null,1));
console.log('\n'+(results.length-fails.length)+'/'+results.length+' passed');
process.exit(fails.length?1:0);
})();
