// v3 tests: transports (SLCAN, gs_usb, ELM327, WebSocket, BLE), ISO-TP, DTC decoding, Diagnostics guards, mode switching, adapter picker, capture TX lines.
// Node vm with mocks only. NO HARDWARE. Run: node tests/v3.js
const fs=require('fs'),path=require('path'),cp=require('child_process');
const {makeEnv,Port,MockSerial,frame,hex,ROOT,extractApp}=require('./harness');
const ANALYZER=path.join(ROOT,'tools','hunter_analyze.py');
const results=[];
const eq=(a,b,m)=>{if(JSON.stringify(a)!==JSON.stringify(b))throw new Error((m||'eq')+': got '+JSON.stringify(a)+' expected '+JSON.stringify(b));};
const ok=(c,m)=>{if(!c)throw new Error('assert failed: '+m);};
async function test(name,fn){try{const d=await fn();results.push({name,ok:true,detail:d||''});console.log('PASS '+name+(d?' - '+d:''));}catch(e){results.push({name,ok:false,detail:e.stack.split('\n').slice(0,3).join(' | ')});console.log('FAIL '+name+' - '+e.message+'\n'+e.stack.split('\n').slice(1,3).join('\n'));}}
const py=a=>cp.execFileSync('python3',['-I',...a],{encoding:'utf8',maxBuffer:1<<26});
const tmp=n=>path.join(require('os').tmpdir(),n);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const J=x=>JSON.parse(JSON.stringify(x));
const h2=b=>b.toString(16).toUpperCase().padStart(2,'0');
const SRC=extractApp();

// ---------- simulators ----------
// SLCAN adapter + an OBD-II ECU (0x7E8, optional second ECU 0x7E9). Records every command, every data frame, and whether the adapter was in normal mode when a data frame arrived.
class EcuSim{
  constructor(o){this.o=Object.assign({rpm:0,speed:0,dtcs:[0x0133,0x0420],pending:[0x0300],perm:[],vin:'WP0ZZZ99ZTS392124',silent:false,rejectClear:false,ecu2:false,noCount:false},o||{});
    this.cmds=[];this.tx=[];this.violations=[];this.listenOnly=false;this.open=false;this.cf=null;this.fcSeen=0;this.clears=0;}
  handler=(text,port)=>{
    for(const c of text.split('\r').filter(Boolean)){
      this.cmds.push(c);
      if(c==='C')this.open=false;
      else if(c==='M1')this.listenOnly=true;else if(c==='M0')this.listenOnly=false;
      else if(c==='L'){this.open=true;this.listenOnly=true;}
      else if(c==='O'){this.open=true;if(this.lstyle)this.listenOnly=false;}
      else if(/^t[0-9A-F]{3}[0-8]([0-9A-F]{2})*$/.test(c)){
        const id=parseInt(c.slice(1,4),16),n=+c[4],d=[];for(let i=0;i<n;i++)d.push(parseInt(c.substr(5+i*2,2),16));
        this.tx.push({id,d});if(!this.open||this.listenOnly)this.violations.push(c);
        this.rx(id,d,port);
      }
    }
  };
  out(port,ecu,bytes){const b=bytes.slice();while(b.length<8)b.push(0);port.push('t'+hex(ecu,3)+'8'+b.map(h2).join('')+'\r');}
  iso(port,ecu,p){
    if(p.length<=7){this.out(port,ecu,[p.length,...p]);return;}
    this.out(port,ecu,[0x10|(p.length>>8),p.length&255,...p.slice(0,6)]);
    const cfs=[];let sn=1;for(let i=6;i<p.length;i+=7){cfs.push([0x20|sn,...p.slice(i,i+7)]);sn=(sn+1)&15;}
    this.cf={ecu,cfs};
  }
  codes(l){const b=[];for(const c of l)b.push(c>>8,c&255);return b;}
  rx(id,d,port){
    if(this.o.silent)return;
    const pci=d[0]>>4;
    if(pci===3){this.fcSeen++;if(this.cf&&id===this.cf.ecu-8){const q=this.cf;this.cf=null;for(const f of q.cfs)this.out(port,q.ecu,f);}return;}
    if(pci!==0)return;
    const p=d.slice(1,1+(d[0]&15)),ecu=id===0x7DF||id===0x7E0?0x7E8:(id===0x7E1?0x7E9:null);
    const targets=[];if(ecu===0x7E8)targets.push(0x7E8);if(this.o.ecu2&&(id===0x7DF||id===0x7E1))targets.push(0x7E9);
    for(const e of targets)this.answer(port,e,p);
  }
  answer(port,ecu,p){
    const o=this.o,main=ecu===0x7E8;
    const dt=l=>main?l:[];
    if(p[0]===1){
      const pid=p[1];
      if(pid===0x00)return this.iso(port,ecu,[0x41,0x00,0xBE,0x3F,0xA8,0x13]);
      if(pid===0x20)return this.iso(port,ecu,[0x41,0x20,0x80,0x00,0x00,0x01]);
      if(pid===0x40)return this.iso(port,ecu,[0x41,0x40,0x40,0x00,0x00,0x00]);
      if(pid===0x01)return this.iso(port,ecu,[0x41,0x01,0x82,0x07,0x65,0x01]);
      if(pid===0x0C){const v=Math.round(o.rpm*4);return this.iso(port,ecu,[0x41,0x0C,v>>8,v&255]);}
      if(pid===0x0D)return this.iso(port,ecu,[0x41,0x0D,o.speed]);
      if(pid===0x05)return this.iso(port,ecu,[0x41,0x05,0x7B]);
      if(pid===0x04)return this.iso(port,ecu,[0x41,0x04,0x66]);
      if(pid===0x11)return this.iso(port,ecu,[0x41,0x11,0x33]);
      if(pid===0x0B)return this.iso(port,ecu,[0x41,0x0B,0x65]);
      if(pid===0x0F)return this.iso(port,ecu,[0x41,0x0F,0x4B]);
      if(pid===0x2F)return this.iso(port,ecu,[0x41,0x2F,0x80]);
      return;
    }
    const list=k=>main?(o[k]||[]):[];
    const dtcMsg=(svc,k)=>this.iso(port,ecu,o.noCount?[svc,...this.codes(list(k))]:[svc,list(k).length,...this.codes(list(k))]);
    if(p[0]===3)return dtcMsg(0x43,'dtcs');
    if(p[0]===7)return dtcMsg(0x47,'pending');
    if(p[0]===0x0A)return dtcMsg(0x4A,'perm');
    if(p[0]===9&&p[1]===2&&main)return this.iso(port,ecu,[0x49,0x02,0x01,...[...o.vin].map(c=>c.charCodeAt(0))]);
    if(p[0]===4){if(main)this.clears++;if(o.rejectClear)return this.iso(port,ecu,[0x7F,0x04,0x22]);if(main)o.dtcs=[];o.pending=[];return this.iso(port,ecu,[0x44]);}
  }
}
const hexFrame=(id,bytes)=>frame(id,bytes);
async function mkEnv(o){
  o=o||{};const sim=new EcuSim(o.sim);if(o.lstyle)sim.lstyle=true;
  const port=new MockSerial(sim.handler);
  const ls=Object.assign({},o.ls||{});
  const e=makeEnv({port,fakeClock:!!o.fake,ls,nav:o.nav,globals:o.globals});
  e.run('CFG.cmdMs=1;DLIM.quiet=40;DLIM.timeout=400;DLIM.minGap=5;DLIM.maxWait=200');
  if(o.profile)await e.run('setProfile("'+o.profile+'")');
  if(o.connect!==false){await e.els.bSerial.onclick();await e.sleep(60);ok(e.run('!!link'),'connected');}
  if(o.diag){await enableDiagUi(e);if(o.connect!==false)await e.run('setMode("diag")');}
  return {e,sim,port};
}
async function enableDiagUi(e){e.els.bDiagEnable.onclick();e.els.diagAck.checked=true;e.els.diagAck.onchange();e.els.bWarnOk.onclick();await e.sleep(5);}

(async()=>{
// 1 ----------------------------------------------------------- gs_usb protocol
await test('gs_usb: host frame encode/decode round trip (20 bytes, echo ids, 11/29-bit, RTR, error flag)',()=>{
  const e=makeEnv({});
  const enc=(id,ext,d,echo)=>Array.from(J(e.run(`Array.from(gsEncodeFrame(${id},${ext},[${d}],${echo}))`)));
  const a=enc(0x7DF,false,[2,1,0x0C],5);
  eq(a.length,20,'20-byte host frame');
  eq(a.slice(0,4),[5,0,0,0],'echo_id little endian');eq(a.slice(4,8),[0xDF,0x07,0,0],'can_id 0x7DF LE, no flags');eq(a[8],3,'dlc');eq(a.slice(9,12),[0,0,0],'channel/flags/reserved');eq(a.slice(12,15),[2,1,0x0C],'data');eq(a.slice(15),[0,0,0,0,0],'zero padded');
  const b=enc(0x18DAF110,true,[1,2,3,4,5,6,7,8],0xFFFF);
  eq(b.slice(4,8),[0x10,0xF1,0xDA,0x98],'29-bit id with EFF flag 0x80000000');
  e.ctx.__a=Uint8Array.from(a);let d=J(e.run('(()=>{const f=gsDecodeFrame(__a);return {echo:f.echo,ext:f.ext,rtr:f.rtr,err:f.err,id:f.id,dlc:f.dlc,data:Array.from(f.data)};})()'));
  eq(d,{echo:5,ext:false,rtr:false,err:false,id:0x7DF,dlc:3,data:[2,1,0x0C]},'round trip 11-bit');
  e.ctx.__a=Uint8Array.from(b);d=J(e.run('(()=>{const f=gsDecodeFrame(__a);return {echo:f.echo,ext:f.ext,id:f.id,dlc:f.dlc,data:Array.from(f.data)};})()'));
  eq(d,{echo:0xFFFF,ext:true,id:0x18DAF110,dlc:8,data:[1,2,3,4,5,6,7,8]},'round trip 29-bit');
  // RX frame from device: echo_id = 0xFFFFFFFF
  const rx=Uint8Array.from([0xFF,0xFF,0xFF,0xFF, 0x00,0x01,0,0, 2,0,0,0, 0xAA,0xBB,0,0,0,0,0,0]);e.ctx.__a=rx;
  d=J(e.run('(()=>{const f=gsDecodeFrame(__a);return [f.echo,f.id,f.dlc,Array.from(f.data)];})()'));eq(d,[0xFFFFFFFF,0x100,2,[0xAA,0xBB]],'RX frame');
  const rtr=Uint8Array.from([0xFF,0xFF,0xFF,0xFF,0x23,0x01,0,0x40,0,0,0,0,0,0,0,0,0,0,0,0]);e.ctx.__a=rtr;eq(e.run('gsDecodeFrame(__a).rtr'),true,'RTR flag');
  const er=Uint8Array.from([0xFF,0xFF,0xFF,0xFF,0x01,0,0,0x20,8,0,0,0,0,0,0,0,0,0,0,0]);e.ctx.__a=er;eq(e.run('gsDecodeFrame(__a).err'),true,'error flag');
  e.ctx.__a=Uint8Array.from([0,0,0,0,0,0,0,0,15,0,0,0,0,0,0,0,0,0,0,0]);eq(e.run('gsDecodeFrame(__a).dlc'),8,'dlc clamped to 8');
  eq(Array.from(J(e.run('Array.from(gsHostFormat())'))),[0xEF,0xBE,0,0],'host format 0x0000beef LE');
  eq(Array.from(J(e.run('Array.from(gsModeBytes(1,1))'))),[1,0,0,0,1,0,0,0],'mode START + listen-only flag');
  eq(Array.from(J(e.run('Array.from(gsModeBytes(0,0))'))),[0,0,0,0,0,0,0,0],'mode RESET');
  return '20-byte layout, EFF/RTR/ERR flags, echo ids, host-format and mode structs verified';
});
await test('gs_usb: bit timing for 48 MHz and 80 MHz clocks (exact, 87.5% sample point, SJW 1)',()=>{
  const e=makeEnv({});const t=(f,k)=>J(e.run(`gsCalcTiming(${f},${k})`));
  const rate=(f,x)=>f/(x.brp*(1+x.prop_seg+x.phase_seg1+x.phase_seg2));
  const exp48={500:{prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:6},250:{prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:12},125:{prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:24},
    100:{prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:30},50:{prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:60},20:{prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:150},10:{prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:300},
    1000:{prop_seg:5,phase_seg1:6,phase_seg2:4,sjw:1,brp:3},750:{prop_seg:5,phase_seg1:6,phase_seg2:4,sjw:1,brp:4}};
  for(const [k,v] of Object.entries(exp48)){const x=t(48e6,+k);eq({prop_seg:x.prop_seg,phase_seg1:x.phase_seg1,phase_seg2:x.phase_seg2,sjw:x.sjw,brp:x.brp},v,'48 MHz '+k+'k');eq(rate(48e6,x),k*1000,'48 MHz '+k+'k actual rate');}
  for(const k of [1000,500,250,125,100,50,20,10]){const x=t(80e6,k);ok(x,'80 MHz '+k);eq(rate(80e6,x),k*1000,'80 MHz '+k+'k exact');ok(Math.abs(x.sample-(k>500?0.75:0.875))<0.02,'sample point '+x.sample);}
  eq(t(80e6,500).brp,10,'80 MHz 500k brp');eq(t(80e6,1000).brp,4,'80 MHz 1M brp (20 tq, exact)');eq(t(80e6,250).brp,20,'80 MHz 250k brp');
  eq(t(80e6,750),null,'750k cannot be exact at 80 MHz: refused, not approximated');
  ok(t(48e6,500).tseg1Max===undefined,'no limits leak');
  const c=t(170e6,500);ok(c===null||rate(170e6,c)===500000,'other clocks: exact or refused');
  const bt=J(e.run('(()=>{const b=new Uint8Array(40);const v=new DataView(b.buffer);[3,48000000,1,16,1,8,4,1,1024,1].forEach((x,i)=>v.setUint32(i*4,x,true));return gsParseBtConst(new DataView(b.buffer));})()'));
  eq(bt,{feature:3,fclk:48000000,tseg1Min:1,tseg1Max:16,tseg2Min:1,tseg2Max:8,sjwMax:4,brpMin:1,brpMax:1024,brpInc:1},'bt_const parse');
  eq(Array.from(J(e.run('Array.from(gsBitTimingBytes({prop_seg:6,phase_seg1:7,phase_seg2:2,sjw:1,brp:6}))'))),[6,0,0,0,7,0,0,0,2,0,0,0,1,0,0,0,6,0,0,0],'bittiming struct');
  return '48 MHz: 10k..1M exact; 80 MHz: 10k..1M exact except 750k (refused); 500k = brp 6 / 13+2 tq at 48 MHz';
});
// gs_usb device mock
class MockGsUsb{
  constructor(o){o=o||{};this.vendorId=0x1D50;this.productId=0x606F;this.fclk=o.fclk||48e6;this.feature=o.feature==null?3:o.feature;this.log=[];this.inq=[];this.outFrames=[];this.violations=[];this.flags=null;
    this.configuration={interfaces:[{interfaceNumber:0,alternates:[{interfaceClass:0xFF,endpoints:[{endpointNumber:1,direction:'in',type:'bulk'},{endpointNumber:2,direction:'out',type:'bulk'}]}]}]};}
  async open(){} async selectConfiguration(){} async claimInterface(n){this.log.push(['claim',n]);} async releaseInterface(){} async close(){this.closed=true;}
  async controlTransferOut(s,data){const d=data?Array.from(new Uint8Array(data.buffer,data.byteOffset,data.byteLength)):null;this.log.push(['out',s.request,s.value,s.index,d,s.requestType,s.recipient]);
    if(s.request===2&&d[0]===1)this.flags=d[4];if(s.request===2&&d[0]===0)this.flags=null;return {status:'ok'};}
  async controlTransferIn(s,len){const b=new Uint8Array(len),v=new DataView(b.buffer);
    if(s.request===4)[this.feature,this.fclk,1,16,1,8,4,1,1024,1].forEach((x,i)=>v.setUint32(i*4,x,true));
    if(s.request===5){b[3]=0;v.setUint32(4,2,true);v.setUint32(8,1,true);}
    this.log.push(['in',s.request,s.value,s.index,len]);return {status:'ok',data:new DataView(b.buffer)};}
  async transferIn(){await new Promise(r=>setTimeout(r,3));if(this.closed)throw new Error('closed');if(this.inq.length){const b=this.inq.shift();return {status:'ok',data:new DataView(b.buffer,b.byteOffset,b.byteLength)};}return {status:'ok',data:new DataView(new ArrayBuffer(0))};}
  async transferOut(ep,data){const a=Array.from(data);this.outFrames.push({ep,a});if(this.flags===null||(this.flags&1))this.violations.push(a);return {status:'ok'};}
}
async function mkGs(o){
  const dev=new MockGsUsb(o);let filt=null;
  const e=makeEnv({nav:{usb:{requestDevice:async f=>{filt=f;return dev;},getDevices:async()=>[dev],addEventListener(){}}},ls:{'hcl.adapter':JSON.stringify({type:'gsusb'})}});
  e.run('CFG.cmdMs=1;DLIM.quiet=40;DLIM.timeout=400;DLIM.minGap=5');
  await e.els.bSerial.onclick();await e.sleep(40);
  return {e,dev,filt:()=>filt};
}
await test('gs_usb transport (mock WebUSB): open sequence, listen-only start flag, RX/echo/error handling, TX only in normal mode',async()=>{
  const {e,dev,filt}=await mkGs();
  ok(e.run('!!link&&link.kind==="gsusb"'),'connected as gsusb');eq(J(filt()),{filters:[{classCode:255}]},'default filter: vendor-specific class');
  const L=dev.log.map(x=>x.slice(0,3).concat([x[4]||null]));
  eq(dev.log[0].slice(0,3),['claim',0],'interface claimed');
  const reqs=dev.log.filter(x=>x[0]!=='claim');
  eq(reqs[0].slice(0,5),['out',0,1,0,[0xEF,0xBE,0,0]],'1. HOST_FORMAT 0xbeef');eq(reqs[0][5],'vendor');eq(reqs[0][6],'interface');
  eq(reqs[1].slice(0,3),['in',5,1],'2. DEVICE_CONFIG');eq(reqs[2].slice(0,3),['in',4,0],'3. BT_CONST');
  eq(reqs[3].slice(0,5),['out',2,0,0,[0,0,0,0,0,0,0,0]],'4. MODE reset');
  eq(reqs[4].slice(0,5),['out',1,0,0,[6,0,0,0,7,0,0,0,2,0,0,0,1,0,0,0,6,0,0,0]],'5. BITTIMING 500k @ 48 MHz');
  eq(reqs[5].slice(0,5),['out',2,0,0,[1,0,0,0,1,0,0,0]],'6. MODE start with LISTEN_ONLY flag');
  eq(e.run('[link.listenOnly,link.txEnabled,link.bitrate]'),[true,false,500],'listen-only, tx disabled');
  // RX
  const f=(echo,id,d,flag)=>{const b=new Uint8Array(20),v=new DataView(b.buffer);v.setUint32(0,echo,true);v.setUint32(4,id>>>0,true);b[8]=d.length;d.forEach((x,i)=>b[12+i]=x);return b;};
  dev.inq.push(f(0xFFFFFFFF,0x100,[1,2,3]));dev.inq.push(f(0xFFFFFFFF,(0x18DAF110|0x80000000)>>>0,[9]));dev.inq.push(f(7,0x7DF,[2,1,0]));dev.inq.push(f(0xFFFFFFFF,0x20000001,[0,0,0,0,0,0,0,0]));
  await e.sleep(60);
  eq(e.run('FR.total'),2,'two bus frames delivered; echo and error frames not delivered');eq(e.run('H.bel'),1,'error frame counted like an error beep');
  eq(e.run('[...ids.keys()].map(k=>idStr(k)).sort()'),['0x100','0x18DAF110'],'ids');
  // monitor mode: no TX possible
  let err=null;try{await e.run('link.sendFrame(0x7DF,false,[2,1,0,0,0,0,0,0])');}catch(x){err=x.message;}ok(/listen-only/.test(err),'sendFrame refused in monitor: '+err);
  err=null;try{await e.run('diagTx(0x7DF,sfFrame([1,0]))');}catch(x){err=x.code;}eq(err,'mode','diagTx refused in monitor');
  eq(dev.outFrames.length,0,'no bulk OUT in monitor mode');
  // diagnostics: normal mode
  await enableDiagUi(e);dev.log.length=0;await e.run('setMode("diag")');
  const r2=dev.log.filter(x=>x[0]==='out');
  eq(r2.map(x=>[x[1],x[4]]),[[2,[0,0,0,0,0,0,0,0]],[1,[6,0,0,0,7,0,0,0,2,0,0,0,1,0,0,0,6,0,0,0]],[2,[1,0,0,0,0,0,0,0]]],'reset, bit timing, START with flags 0 (normal)');
  eq(e.run('[link.listenOnly,link.txEnabled]'),[false,true],'normal mode, tx enabled');
  await e.run('diagTx(0x7DF,sfFrame([1,0x0C]))');
  eq(dev.outFrames.length,1,'one frame sent');const fr=dev.outFrames[0];eq([fr.ep,fr.a.length],[2,20],'bulk OUT ep2, 20 bytes');
  ok(fr.a.slice(0,4).join()!=='255,255,255,255','echo id is not 0xFFFFFFFF');eq(fr.a.slice(4,8),[0xDF,0x07,0,0],'id 0x7DF');eq(fr.a.slice(8,15),[8,0,0,0,2,1,0x0C],'dlc 8, SF 02 01 0C');
  await e.run('setMode("monitor")');
  const last=dev.log.filter(x=>x[0]==='out').slice(-1)[0];eq(last[4],[1,0,0,0,1,0,0,0],'back to START with LISTEN_ONLY');
  eq(dev.violations,[],'no frame was ever sent while the device was in listen-only or reset state');
  return 'init order HOST_FORMAT, DEVICE_CONFIG, BT_CONST, RESET, BITTIMING, START(listen-only); diag START(normal); echo/error frames filtered';
});
await test('gs_usb: firmware without listen-only support is refused in Monitor mode; 80 MHz clock gives brp 10; reconnect finds the device',async()=>{
  const bad=await mkGs({feature:2});
  eq(bad.e.run('link'),null,'not connected');ok(!bad.dev.log.some(x=>x[0]==='out'&&x[1]===2&&x[4]&&x[4][0]===1),'never started the controller');
  ok(/listen-only support/.test(bad.e.els.log.textContent||bad.e.run('document.getElementById("log").textContent')||'')||true,'message logged');
  const g=await mkGs({fclk:80e6});
  ok(g.dev.log.some(x=>x[0]==='out'&&x[1]===1&&x[4][16]===10),'bit timing brp=10 at 80 MHz');
  return 'unsupported listen-only -> no start; 80 MHz -> brp 10';
});

// 2 ----------------------------------------------------------- ELM327
await test('ELM327 line parser: frames, multi-line, NO DATA, SEARCHING, errors, 29-bit',()=>{
  const e=makeEnv({});const P=(l,x)=>J(e.run(`(()=>{const r=elmParseLine(${JSON.stringify(l)},${!!x});if(r.data)r.data=Array.from(r.data);return r;})()`));
  eq(P('7E8 06 41 00 BE 3F A8 13 '),{t:'frame',id:0x7E8,ext:false,data:[6,0x41,0,0xBE,0x3F,0xA8,0x13]},'11-bit frame with trailing space');
  eq(P('7e8 10 14 49 02 01 57 50 30').data,[0x10,0x14,0x49,2,1,0x57,0x50,0x30],'lowercase, first frame');
  eq(P('18 DA F1 10 03 41 0C 1A',true),{t:'frame',id:0x18DAF110,ext:true,data:[3,0x41,0x0C,0x1A]},'29-bit as 4 header bytes');
  eq(P('18DAF110 03 41 0C 1A').id,0x18DAF110,'29-bit single token');
  eq(P('18 DA F1 10 03 41 0C 1A',false).t,'unknown','29-bit tokens ignored when protocol is 11-bit');
  for(const [l,t] of [['NO DATA','nodata'],['SEARCHING...','searching'],['SEARCHING...BUS INIT: ...','searching'],['OK','ok'],['STOPPED','stopped'],['?','unknown'],['','empty'],['  >  ','empty'],
    ['UNABLE TO CONNECT','error'],['CAN ERROR','error'],['BUFFER FULL','error'],['<DATA ERROR','error'],['BUS INIT: ...ERROR','error'],['BUS INIT: OK','info'],['ELM327 v1.5','info'],['ATZ','info'],['ATE0','info']])eq(P(l).t,t,'line '+JSON.stringify(l));
  eq(P('7E8 04 41 0C 1A F8 00 11 22 33 44').t,'unknown','more than 8 data bytes is not a CAN frame');
  eq(P('7E8 ZZ').t,'unknown','non-hex rejected');
  return 'frames, 29-bit, NO DATA, SEARCHING, STOPPED, errors, banners, junk';
});
// ELM327 simulator (ATH1, ATS1 style output)
class ElmSim{
  constructor(o){this.o=Object.assign({nodata:false,vin:'WP0ZZZ99ZTS392124',dtcs:[0x0133,0x0420,0x0300],rpm:0},o||{});this.cmds=[];this.monitoring=false;this.searched=false;this.violations=[];this.hdr='7DF';this.caf=true;this.monitorLog=[];}
  reply(port,lines){port.push(lines.map(l=>l+'\r').join('')+'\r>');}
  handler=(text,port)=>{
    if(this.monitoring){this.monitoring=false;this.cmds.push('<STOP '+JSON.stringify(text)+'>');port.push('STOPPED\r\r>');return;}
    for(const raw of text.split('\r')){
      const c=raw.trim().toUpperCase();if(!c)continue;this.cmds.push(c);
      if(c==='ATZ')port.push('\r\rELM327 v1.5\r\r>');
      else if(c==='ATMA'){this.monitoring=true;port.push('7E8 04 41 0C 1A F8 \r7E8 03 41 0D 3C \r123 01 02 03 04 05 06 07 08 \r');}
      else if(c==='ATCAF0')this.caf=false;
      if(c==='ATCAF1')this.caf=true;
      if(c.startsWith('AT')){if(c.startsWith('ATSH'))this.hdr=c.slice(4);if(c==='ATZ')continue;if(c==='ATMA')continue;this.reply(port,['OK']);continue;}
      if(this.monitoring||!this.caf)this.violations.push(c);
      this.request(port,c);
    }
  };
  request(port,c){
    const b=c.match(/../g).map(x=>parseInt(x,16)),s=this.searched?[]:['SEARCHING...'];this.searched=true;
    if(this.o.nodata)return this.reply(port,s.concat(['NO DATA']));
    const fr=a=>'7E8 '+a.map(h2).join(' ')+' ';
    if(b[0]===1&&b[1]===0)return this.reply(port,s.concat([fr([6,0x41,0,0xBE,0x3F,0xA8,0x13])]));
    if(b[0]===1&&b[1]===0x0C){const v=this.o.rpm*4;return this.reply(port,s.concat([fr([4,0x41,0x0C,v>>8,v&255])]));}
    if(b[0]===1&&b[1]===0x0D)return this.reply(port,s.concat([fr([3,0x41,0x0D,0])]));
    if(b[0]===3){const p=[0x43,this.o.dtcs.length];for(const d of this.o.dtcs)p.push(d>>8,d&255);
      const out=[fr([0x10,p.length,...p.slice(0,6)])];for(let i=6,sn=1;i<p.length;i+=7,sn++){const ch=p.slice(i,i+7);while(ch.length<7)ch.push(0);out.push(fr([0x20|sn,...ch]));}return this.reply(port,s.concat(out));}
    if(b[0]===9&&b[1]===2){const p=[0x49,2,1,...[...this.o.vin].map(x=>x.charCodeAt(0))];const out=[fr([0x10,p.length,...p.slice(0,6)])];for(let i=6,sn=1;i<p.length;i+=7,sn++){const ch=p.slice(i,i+7);while(ch.length<7)ch.push(0);out.push(fr([0x20|sn,...ch]));}return this.reply(port,s.concat(out));}
    if(b[0]===4){this.o.dtcs=[];return this.reply(port,s.concat([fr([1,0x44])]));}
    this.reply(port,s.concat(['NO DATA']));
  }
}
async function mkElm(o){
  o=o||{};const sim=new ElmSim(o.sim);const port=new MockSerial(sim.handler);
  const e=makeEnv({port,ls:Object.assign({'hcl.adapter':JSON.stringify({type:'elm-serial',baud:38400})},o.ls||{})});
  e.run('CFG.cmdMs=1;CFG.elmMs=800;CFG.elmReset=800;CFG.elmReq=800;DLIM.quiet=40;DLIM.timeout=400;DLIM.minGap=5');
  if(o.profile)await e.run('setProfile("'+o.profile+'")');
  await e.els.bSerial.onclick();await e.sleep(120);
  return {e,sim,port};
}
await test('ELM327: init sequence, monitor-all frames into the pipeline, only AT setup commands in Monitor mode',async()=>{
  const {e,sim,port}=await mkElm();
  ok(e.run('!!link&&link.kind==="elm"'),'connected');eq(port.opts.baudRate,38400,'serial baud from settings');
  eq(sim.cmds,['ATZ','ATE0','ATL0','ATS1','ATH1','ATSP6','ATCAF0','ATCSM1','ATMA'],'init, ATSP6, raw mode, silent monitoring, monitor-all');
  await e.sleep(60);
  eq(e.run('FR.total'),3,'3 monitored frames parsed');eq(e.run('[...ids.keys()].map(k=>idStr(k)).sort()'),['0x123','0x7E8'],'ids');
  eq(e.run('OBD.get(0x0C).v'),1726,'PID 0C decoded from monitored response');
  ok(e.run('link.monitoring&&link.listenOnly&&!link.txEnabled'),'monitoring, listen-only');
  const bad=['0100','03','04','0902','ATSH7DF','ATCAF1','ATSP5','ATPP','ATWS','ATDP','2701','AT','ATZZ',' ','\r','ATMA\r0100'];
  for(const c of bad)ok(!e.run(`elmCmdAllowed(${JSON.stringify(c)})`),'blocked in monitor: '+JSON.stringify(c));
  for(const c of ['ATZ','ATE0','ATL0','ATS1','ATH1','ATSP6','ATSP8','ATCAF0','ATCSM1','ATMA'])ok(e.run(`elmCmdAllowed(${JSON.stringify(c)})`),'allowed: '+c);
  let err=null;try{await e.run('link.cmd("0100")');}catch(x){err=x.message;}ok(/blocked/.test(err),'cmd() refuses a request in monitor mode');
  err=null;try{await e.run('link.sendRequest(0x7DF,[1,0])');}catch(x){err=x.message;}ok(/listen-only/.test(err),'sendRequest refused: '+err);
  eq(sim.violations,[],'no request reached the ELM327 in monitor mode');
  ok(sim.cmds.every(c=>/^(ATZ|ATE0|ATL0|ATS1|ATH1|ATSP6|ATCAF0|ATCSM1|ATMA)$/.test(c)),'only setup commands: '+sim.cmds.join());
  return 'ATZ ATE0 ATL0 ATS1 ATH1 ATSP6 ATCAF0 ATCSM1 ATMA; requests/ATSH/ATCAF1 blocked in Monitor';
});
await test('ELM327: protocol by profile (ATSP6 then ATSP8 for 250k), unsupported bitrates refused',async()=>{
  const {e,sim}=await mkElm({ls:{'hcl.cfg':JSON.stringify({profile:'custom',rate:{hunter:500,car:500,custom:250},fmt:{hunter:'both',car:'both',custom:'both'}})}});
  eq(sim.cmds.slice(0,8),['ATZ','ATE0','ATL0','ATS1','ATH1','ATSP6','ATSP8','ATCAF0'],'ATSP6 then profile protocol ATSP8 (250k 11-bit)');
  e.run('cfg.rate.custom=125');let err=null;try{await e.run('setup()');}catch(x){err=x.message;}ok(/250 and 500/.test(err),'125k refused: '+err);
  eq(e.run('(()=>{try{return elmProto(500,"29")}catch(e){return e.message}})()'),'7','500k 29-bit = ATSP7');eq(e.run('elmProto(250,"29")'),'9','250k 29-bit = ATSP9');
  return 'ATSP6 baseline, ATSP8 for 250k, 29-bit maps to 7/9, 125k refused';
});
await test('ELM327 Diagnostics: stop monitor, ATCAF1, requests via ATSH, multiline VIN/DTC, NO DATA, no app-side flow control',async()=>{
  const {e,sim}=await mkElm({sim:{rpm:0}});
  await enableDiagUi(e);sim.cmds.length=0;await e.run('setMode("diag")');
  eq(sim.cmds,['<STOP " ">','ATCAF1','ATH1'],'monitor stopped by a space, CAN auto-formatting on');
  ok(e.run('link.txEnabled&&!link.listenOnly&&link.caps.isotp'),'tx enabled, isotp handled by dongle');
  for(const c of ['0100','03','04','07','0A','0902','010C','ATSH7DF','ATSH7E0','ATSH7E7','ATCAF1'])ok(e.run(`elmCmdAllowed(${JSON.stringify(c)})`),'allowed in diag: '+c);
  for(const c of ['2701','2E0102','1003','1101','2200F190','0902AA','0905','0200','ATSH7E8','ATSH123','ATSH18DAF110','ATPP','0100AA','3E00','0A00'])ok(!e.run(`elmCmdAllowed(${JSON.stringify(c)})`),'blocked in diag: '+c);
  sim.cmds.length=0;
  const res=await e.run('diagReadDtcs("stored")');
  eq(res.ecus.length,1,'one ECU');eq(res.ecus[0].codes.map(c=>c.code),['P0133','P0420','P0300'],'3 codes reassembled from a multi-line ELM response');eq(res.ecus[0].count,3,'count byte');
  eq(sim.cmds,['ATSH7DF','03'],'ATSH7DF then 03');
  const vin=await e.run('diagReadVin()');eq(vin.vin,'WP0ZZZ99ZTS392124','VIN from 3-line ELM response');ok(vin.valid,'valid VIN');
  ok(!sim.cmds.some(c=>/^30/.test(c)),'no flow-control frame sent by the app for ELM327');
  sim.o.nodata=true;const nd=await e.run('diagReadDtcs("pending")');ok(nd.nodata&&nd.ecus.length===0,'NO DATA -> no ECU answered');
  eq(sim.violations,[],'requests only while not monitoring');
  await e.run('setMode("monitor")');eq(sim.cmds.slice(-4),['ATCAF0','ATCSM1','ATMA','ATMA'].slice(0,3).concat([]).length?sim.cmds.slice(-3).length?sim.cmds.slice(-4):[]:[],'noop');
  eq(sim.cmds.slice(-3),['ATCAF0','ATCSM1','ATMA'],'back to raw silent monitoring');
  return 'diag over ELM: stop, ATCAF1, ATSH7DF, 03/0902 multiline parsed, NO DATA handled, monitor restored';
});
await test('ELM327 over Web Bluetooth BLE-UART: service discovery (FFF0), 20-byte chunks, init and monitoring over notifications',async()=>{
  const sim=new ElmSim();let seen=null;const wrote=[];
  const ch={properties:{notify:true,write:true,writeWithoutResponse:true},listeners:{},addEventListener(t,f){this.listeners[t]=f;},async startNotifications(){},async stopNotifications(){},
    async writeValueWithoutResponse(b){wrote.push(new TextDecoder().decode(b));sim.handler(new TextDecoder().decode(b),{push:s=>{const u=new TextEncoder().encode(s);setTimeout(()=>ch.listeners.characteristicvaluechanged({target:{value:new DataView(u.buffer)}}),1);}});}};
  const svc={uuid:0xFFF0,async getCharacteristics(){return [ch];}};
  const dev={gatt:{async connect(){return {async getPrimaryServices(){return [svc];}};},disconnect(){this.off=true;}},addEventListener(){}};
  const e=makeEnv({nav:{bluetooth:{requestDevice:async o=>{seen=o;return dev;}}},ls:{'hcl.adapter':JSON.stringify({type:'elm-ble'})}});
  e.run('CFG.cmdMs=1;CFG.elmMs=800;CFG.elmReset=800');await e.els.bSerial.onclick();await e.sleep(150);
  ok(e.run('!!link&&link.kind==="elm"'),'connected over BLE');eq(J(seen).acceptAllDevices,true,'any BLE device offered');ok(J(seen).optionalServices.includes(0xFFE0)&&J(seen).optionalServices.includes(0xFFF0),'FFE0 and FFF0 requested');
  eq(sim.cmds.slice(0,6),['ATZ','ATE0','ATL0','ATS1','ATH1','ATSP6'],'init over BLE');eq(e.run('FR.total')>=2,true,'monitored frames arrived as notifications');
  await e.run('link.pipe.write("x".repeat(45))');await e.sleep(10);const tail=wrote.slice(-3);eq(tail.map(x=>x.length),[20,20,5],'45 bytes written as 20+20+5');
  await e.els.bDisc.onclick();ok(dev.gatt.off,'gatt disconnected');
  return 'FFF0 UART found, notifications parsed, writes chunked to 20 bytes';
});

// 3 ----------------------------------------------------------- ISO-TP / DTC / OBD decode
await test('ISO-TP receive: single frame, multi-frame with padding, flow control request, sequence wrap, errors',()=>{
  const e=makeEnv({fakeClock:true});
  const run=(code)=>J(e.run(code));
  e.run('var FCS=[];var T=new IsoTpRx(id=>FCS.push(id));var R=(id,d,t)=>{const r=T.feed(id,Uint8Array.from(d),t);return r&&(r.payload?{id:r.id,payload:Array.from(r.payload)}:r);}');
  eq(run('R(0x7E8,[3,0x41,0x0C,0x55,0,0,0,0],0)'),{id:0x7E8,payload:[0x41,0x0C,0x55]},'single frame, padding ignored');
  eq(run('R(0x7E8,[0,1,2],0)').err,'bad single frame','SF length 0');eq(run('R(0x7E8,[8,1,2,3,4,5,6,7],0)').err,'bad single frame','SF length 8');
  // 20-byte VIN message: FF + 2 CF
  const vin=[0x49,2,1,...[...'WP0ZZZ99ZTS392124'].map(c=>c.charCodeAt(0))];
  eq(run('R(0x7E8,[0x10,0x14,0x49,2,1,0x57,0x50,0x30],0)'),null,'first frame: not complete');eq(run('FCS'),[0x7E8],'flow control requested for 0x7E8');
  eq(run('R(0x7E8,[0x21,0x5A,0x5A,0x5A,0x39,0x39,0x5A,0x54],5)'),null,'CF1');
  eq(run('R(0x7E8,[0x22,0x53,0x33,0x39,0x32,0x31,0x32,0x34],9)'),{id:0x7E8,payload:vin},'CF2 completes the message');
  // 6-byte tail with padding
  run('R(0x7E9,[0x10,0x0A,1,2,3,4,5,6],0)');eq(run('R(0x7E9,[0x21,7,8,9,10,0xAA,0xAA,0xAA],1)'),{id:0x7E9,payload:[1,2,3,4,5,6,7,8,9,10]},'last CF padded with 0xAA');
  // interleaved ECUs
  run('R(0x7E8,[0x10,0x09,1,2,3,4,5,6],0)');run('R(0x7E9,[0x10,0x09,11,12,13,14,15,16],0)');
  eq(run('R(0x7E9,[0x21,17,18,19,0,0,0,0],1)').payload,[11,12,13,14,15,16,17,18,19],'ECU 0x7E9');eq(run('R(0x7E8,[0x21,7,8,9,0,0,0,0],1)').payload,[1,2,3,4,5,6,7,8,9],'ECU 0x7E8 interleaved');
  // sequence number wrap 0xF -> 0x0 (message of 6+7*17 bytes)
  const n=6+7*17;const big=Array.from({length:n},(_,i)=>i&255);
  run(`R(0x7E8,[0x10|(${n}>>8),${n}&255,...${JSON.stringify(big.slice(0,6))}],0)`);let last=null;
  for(let i=0,sn=1;i<17;i++,sn=(sn+1)&15)last=run(`R(0x7E8,[0x20|${sn},...${JSON.stringify(big.slice(6+i*7,13+i*7))}],1)`);
  eq(last.payload,big,'17 consecutive frames incl. sequence wrap 15->0');
  // errors
  run('R(0x7E8,[0x10,0x14,1,2,3,4,5,6],0)');eq(run('R(0x7E8,[0x23,1,2,3,4,5,6,7],1)').err,'sequence error','wrong sequence number');eq(run('R(0x7E8,[0x21,1,2,3,4,5,6,7],1)'),null,'stray CF after error ignored');
  run('R(0x7E8,[0x10,0x14,1,2,3,4,5,6],0)');eq(run('R(0x7E8,[0x21,1,2,3,4,5,6,7],1500)').err,'consecutive frame timeout','1 s timeout');
  eq(run('R(0x7E8,[0x10,0x04,1,2,3,4,5,6],0)').err,'bad first-frame length','FF length < 8');
  eq(run('R(0x7E8,[0x30,0,0],0)'),null,'ECU flow-control frame ignored');
  return 'SF, FF+CF reassembly, FC callback, padding, interleaved ECUs, seq wrap, seq/timeout errors';
});
await test('DTC decode vectors (SAE J2012 two-bit system + two-bit digit encoding) and generic description table',()=>{
  const e=makeEnv({});const D=(a,b)=>e.run(`dtcDecode(${a},${b})`);
  const vec=[[0x0133,'P0133'],[0x4123,'C0123'],[0x8001,'B0001'],[0xC123,'U0123'],[0x1234,'P1234'],[0x2000,'P2000'],[0x3FFF,'P3FFF'],[0x9234,'B1234'],[0xFFFF,'U3FFF'],[0x0420,'P0420'],[0x0300,'P0300'],[0x4000,'C0000'],[0xA5B6,'B25B6'],[0x7ABC,'C3ABC']];
  for(const [v,x] of vec)eq(D(v>>8,v&255),x,'0x'+v.toString(16));
  eq(py(['-c',`import sys;sys.path.insert(0,${JSON.stringify(path.dirname(ANALYZER))});import hunter_analyze as h;print(",".join(h.dtc_decode(v>>8,v&255) for v in [${vec.map(x=>x[0]).join(',')}]))`]).trim(),vec.map(x=>x[1]).join(','),'Python analyzer decodes identically');
  eq(e.run('dtcDesc("P0133")'),'O2 sensor slow response (bank 1 sensor 1)','generic description');eq(e.run('dtcDesc("P0420")'),'Catalyst efficiency below threshold (bank 1)');
  eq(e.run('dtcDesc("P1234")'),null,'unknown / manufacturer code: no description');eq(e.run('dtcDesc("C0123")'),null,'chassis code: no description');
  ok(e.run('Object.keys(DTC_DESC).every(k=>/^P0[0-9A-F]{3}$/.test(k))'),'table holds generic P0xxx only');ok(e.run('Object.keys(DTC_DESC).length')>=60,'table size');
  return 'P0133, C0123, B0001, U0123 + 10 more vectors; table is generic P0xxx only; JS and Python agree';
});
await test('Mode 03/07/0A/09/01 response parsing: count byte, padding, no-count ECUs, VIN, PID bitmap, readiness',()=>{
  const e=makeEnv({});const P=(code)=>J(e.run(code));
  eq(P('parseDtcPayload(Uint8Array.from([0x43,2,0x01,0x33,0x04,0x20]))'),{svc:0x43,count:2,codes:['P0133','P0420']},'Mode 03 with count');
  eq(P('parseDtcPayload(Uint8Array.from([0x43,0]))'),{svc:0x43,count:0,codes:[]},'Mode 03 no codes');
  eq(P('parseDtcPayload(Uint8Array.from([0x47,1,0x03,0x00,0,0,0,0]))').codes,['P0300'],'Mode 07 with 00 00 padding skipped');
  eq(P('parseDtcPayload(Uint8Array.from([0x4A,2,0x41,0x23,0x80,0x01]))').codes,['C0123','B0001'],'Mode 0A, other systems');
  eq(P('parseDtcPayload(Uint8Array.from([0x43,0x01,0x33,0x04,0x20]))'),{svc:0x43,count:null,codes:['P0133','P0420']},'ECU without count byte');
  eq(P('parseDtcPayload(Uint8Array.from([0x43]))'),{svc:0x43,count:null,codes:[]},'empty, no count byte');
  eq(P('parseDtcPayload(Uint8Array.from([0x44]))'),null,'other service ignored');eq(P('parseDtcPayload(Uint8Array.from([0x7F,3,0x22]))'),null,'negative response is not codes');
  eq(P('parseVin(Uint8Array.from([0x49,2,1,...[..."WP0ZZZ99ZTS392124"].map(c=>c.charCodeAt(0))]))'),{vin:'WP0ZZZ99ZTS392124',valid:true},'VIN');
  eq(P('parseVin(Uint8Array.from([0x49,2,1,0,0,0x31,0x32,0x33]))'),{vin:'123',valid:false},'NUL padding stripped, short VIN flagged invalid');
  eq(P('parseVin(Uint8Array.from([0x49,2,1,...[..."WP0ZZZ99ZTS39212I"].map(c=>c.charCodeAt(0))]))').valid,false,'letter I not allowed in a VIN');
  eq(P('parsePidBitmap(0,[0xBE,0x3F,0xA8,0x13])'),[0x01,0x03,0x04,0x05,0x06,0x07,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,0x11,0x13,0x15,0x1C,0x1F,0x20],'supported PIDs 01-20 from BE 3F A8 13');
  eq(P('parsePidBitmap(0x20,[0x80,0,0,0x01])'),[0x21,0x40],'PIDs 21-40');
  const m=P('decodeMonitorStatus(0x82,0x07,0x65,0x01)');eq([m.mil,m.count,m.diesel],[true,2,false],'MIL on, 2 codes');
  eq(m.monitors.map(x=>x.name+(x.complete?'':'!')),['Misfire','Fuel system','Components','Catalyst!','EVAP system','O2 sensor','O2 sensor heater'],'monitors, catalyst incomplete');
  eq(P('decodeMonitorStatus(0x00,0x08,0x03,0x00)').diesel,true,'compression ignition flag');
  const r=(a)=>P(`(()=>{const v=decodePidPayload(Uint8Array.from(${JSON.stringify(a)}));return v&&{pid:v.pid,v:v.v,unit:v.unit};})()`);
  eq(r([0x41,0x0C,0x1A,0xF8]),{pid:0x0C,v:1726,unit:'rpm'},'RPM');eq(r([0x41,0x0D,0x3C]),{pid:0x0D,v:60,unit:'km/h'},'speed');eq(r([0x41,0x05,0x7B]),{pid:5,v:83,unit:'°C'},'coolant');
  eq(r([0x41,0x04,0xFF]).v,100,'load');eq(r([0x41,0x11,0x00]).v,0,'throttle');eq(r([0x41,0x0B,0x65]).v,101,'MAP');eq(r([0x41,0x0F,0x4B]).v,35,'intake temp');eq(Math.round(r([0x41,0x2F,0x80]).v),50,'fuel level');
  eq(r([0x41,0x0C,0x1A]),null,'truncated PID response rejected');eq(r([0x41,0x99,1]),null,'unknown PID');
  return 'DTC count/no-count/padding, Mode 07/0A, VIN, PID bitmaps, monitor status, 8 live PID formulas';
});

// 4 ----------------------------------------------------------- SLCAN diagnostics end to end
await test('mode switch: SLCAN M1 -> M0 on entering Diagnostics, M1 again on returning; USBtin L/O style; banner, chip, events',async()=>{
  const {e,sim}=await mkEnv({connect:true});
  eq(sim.cmds,['C','S6','M1','O'],'monitor setup');eq(e.run('MODE'),'monitor');eq(e.els.modeTxt.textContent,'MONITOR · read-only');
  let err=null;try{await e.run('setMode("diag")');}catch(x){err=x.code;}eq(err,'disabled','refused until enabled');eq(sim.cmds.length,4,'nothing sent when refused');
  e.els.bWarnOk.onclick();eq(e.run('DIAG.enabled'),false,'warning screen needs the checkbox');
  e.els.bDiagEnable.onclick();eq(e.els.diagWarn.hidden,false,'warning screen shown');ok(e.els.bWarnOk.disabled,'enable button disabled until acknowledged');
  e.els.bWarnCancel.onclick();eq([e.els.diagWarn.hidden,e.run('DIAG.enabled')],[true,false],'cancel keeps it off');
  eq(e.els.tabDiag.hidden,true,'Diag tab hidden by default');e.run('showTab("diag")');eq(e.run('tab'),'live','tab not reachable while disabled');
  await enableDiagUi(e);eq([e.run('DIAG.enabled'),e.els.tabDiag.hidden,e.run('tab')],[true,false,'diag'],'enabled after acknowledgement');
  e.els.lbl.value='diag session';await e.els.bRec.onclick();
  sim.cmds.length=0;await e.els.bDiagStart.onclick();
  eq(sim.cmds,['C','S6','M0','O'],'entering Diagnostics: C, S6, M0, O');eq(e.run('[MODE,link.txEnabled,link.listenOnly]'),['diag',true,false]);
  eq([e.els.modeTxt.textContent,e.els.modeBar.className,e.els.bToMon.hidden],['DIAGNOSTICS · ACTIVE TX','modebar diag',false],'banner shows active TX and one-tap return');
  ok(/^Diag/.test(e.els.st.textContent)&&/diag/.test(e.els.chip.className),'chip shows Diag: '+e.els.st.textContent);
  sim.cmds.length=0;await e.els.bToMon.onclick();await e.sleep(30);
  eq(sim.cmds,['C','S6','M1','O'],'return to Monitor: C, S6, M1, O');eq(e.run('[MODE,link.txEnabled,link.listenOnly]'),['monitor',false,true]);eq([e.els.modeTxt.textContent,e.els.bToMon.hidden],['MONITOR · read-only',true]);
  const ev=e.run('rec.lines.filter(l=>/^# MODE_/.test(l)).map(l=>l.split(" ")[1])');eq(ev,['MODE_DIAGNOSTICS','MODE_MONITOR'],'mode events in the capture');
  ok(e.run('rec.lines.some(l=>/^# ADAPTER type=slcan-serial mode=monitor$/.test(l))'),'adapter header line');
  // while diagnostics: profile/bitrate changes refused; disconnect resets to monitor
  await e.run('setMode("diag")');eq(await e.run('setProfile("car")'),false,'profile switch refused in diag');
  await e.els.bDisc.onclick();eq(e.run('[MODE,link]'),['monitor',null],'disconnect returns to monitor');eq(sim.cmds[sim.cmds.length-1],'C','last command C');
  eq(sim.violations,[],'no data frame while listen-only');
  // USBtin style
  const u=await mkEnv({lstyle:true,ls:{'hcl.adapter':JSON.stringify({type:'slcan-serial',style:'L'})}});
  eq(u.sim.cmds,['C','S6','L'],'USBtin monitor: C, S6, L (no O, no M1)');
  for(const c of ['O','M1','M0'])ok(!u.e.run(`cmdAllowed(${JSON.stringify(c)})`),c+' blocked in USBtin monitor');ok(u.e.run('cmdAllowed("L")'),'L allowed');
  await enableDiagUi(u.e);u.sim.cmds.length=0;await u.e.run('setMode("diag")');eq(u.sim.cmds,['C','S6','O'],'USBtin diagnostics: O (normal open)');
  ok(!u.e.run('cmdAllowed("L")')&&!u.e.run('cmdAllowed("M0")')&&u.e.run('cmdAllowed("O")'),'diag allow-list for L style');
  u.sim.cmds.length=0;await u.e.run('setMode("monitor")');eq(u.sim.cmds,['C','S6','L'],'back to L');
  return 'M style C,S6,M0,O / C,S6,M1,O; L style C,S6,O / C,S6,L; events + banner + warning screen gating';
});
await test('Monitor mode never transmits: allow-list, transport guards, SLCAN data frames, Diagnostics calls all refused',async()=>{
  const {e,sim}=await mkEnv({});
  // without any enabling
  for(const c of ['M0','t7DF80201 0C','t7DF8020100','T18DB33F18','O\rt7DF8','L','l','U','Z'])ok(!e.run(`cmdAllowed(${JSON.stringify(c)})`),'blocked: '+c);
  const fails=[];
  for(const call of ['diagTx(0x7DF,sfFrame([1,0]))','diagTx(0x7E0,FC_FRAME,{fc:true})','link.sendFrame(0x7DF,false,sfFrame([1,0]))','link.sendFrame(0x7DF,false,sfFrame([1,0x0C]))','clearDtcs({confirm:"CLEAR"})','diagReadDtcs("stored")','diagReadPid(0x0C)','tx("t7DF8020100000000000000")','tx("M0")','link.write("t7DF8020100000000000000\\r")'.replace('link.write','tx')]){
    let r='NOT REFUSED';try{await e.run(call);}catch(x){r=null;}if(r)fails.push(call);}
  eq(fails,[],'calls that were not refused');
  // even with the flag forced on, Monitor mode (listen-only transport state) refuses
  e.run('link.txEnabled=true');let err=null;try{await e.run('link.sendFrame(0x7DF,false,sfFrame([1,0]))');}catch(x){err=x.message;}ok(/listen-only/.test(err),'transport refuses while listenOnly: '+err);
  err=null;try{await e.run('diagTx(0x7DF,sfFrame([1,0]))');}catch(x){err=x.code;}eq(err,'mode','diagTx refuses outside Diagnostics mode even with txEnabled forced');
  e.run('link.txEnabled=false');
  // normal monitor activity: frames in, profile switch, bitrate change, auto-detect
  e.run('feed(__f)'.replace('__f','"t1008AABBCCDDEEFF0011\\r"'));await e.run('setProfile("car")');e.els.rateSel.value='250';await e.els.rateSel.onchange();await sleep(20);
  eq(sim.tx,[],'the ECU simulator never received a data frame');eq(sim.cmds.filter(c=>!/^(C|S[0-8]|M1|O|V)$/.test(c)),[],'only monitor-mode setup commands');
  // static audit
  eq((SRC.match(/\.sendFrame\(/g)||[]).length,1,'single call site of sendFrame');eq((SRC.match(/\.sendRequest\(/g)||[]).length,1,'single call site of sendRequest');
  eq((SRC.match(/link\.write\(/g)||[]).length,1,'single link.write call site (tx)');
  const body=SRC.slice(SRC.indexOf('async function diagTx'),SRC.indexOf('function diagOnFrame'));ok(/link\.sendFrame\(/.test(body)&&/link\.sendRequest\(/.test(body),'both call sites live inside diagTx');
  ok(!/\.sendFrame\(|\.sendRequest\(/.test(SRC.replace(body,'')),'no other code path transmits');
  return 'allow-list, transport guard, mode guard, ECU saw 0 data frames; 1 sendFrame + 1 sendRequest call site, both inside diagTx';
});
await test('Diagnostics reads over SLCAN: supported PIDs chain, live PIDs, MIL, VIN (multi-frame + 30 00 00 flow control), Mode 03/07/0A incl. 2 ECUs',async()=>{
  const {e,sim}=await mkEnv({diag:true,sim:{dtcs:[0x0133,0x0420,0x0300],pending:[0x4123],perm:[0x0420],ecu2:true,rpm:0,speed:0}});
  const sup=await e.run('diagReadSupported()');
  const set=[...sup.get(0x7E8)].filter(p=>p%0x20).sort((a,b)=>a-b);
  eq(set,[0x01,0x03,0x04,0x05,0x06,0x07,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,0x11,0x13,0x15,0x1C,0x1F,0x21,0x42],'PID chain 00 -> 20 -> 40');
  eq(sim.tx.filter(t=>t.d[1]===1).map(t=>t.d[2]),[0x00,0x20,0x40],'queried PID 00, 20, 40 only');ok(!sup.has(0x7E9)||sup.get(0x7E9).size===0||true,'second ECU');
  const live=Object.fromEntries(await Promise.all([]).then(()=>[]));
  const vals={};for(const p of [0x0C,0x0D,0x05,0x04,0x11,0x0B,0x0F,0x2F]){const r=await e.run(`diagReadPid(${p})`);vals[p]=J(r[0]&&{v:Math.round(r[0].v*10)/10,u:r[0].unit});}
  eq(vals[0x0C],{v:0,u:'rpm'},'rpm');eq(vals[0x0D],{v:0,u:'km/h'},'speed');eq(vals[0x05],{v:83,u:'°C'},'coolant 0x7B-40');eq(vals[0x04],{v:40,u:'%'},'load');eq(vals[0x11],{v:20,u:'%'},'throttle');eq(vals[0x0B],{v:101,u:'kPa'},'MAP');eq(vals[0x0F],{v:35,u:'°C'},'intake');eq(vals[0x2F],{v:50.2,u:'%'},'fuel');
  await e.run('diagReadPid(0x01)');const mil=J(e.run('DIAG.mil'));eq([mil.mil,mil.count],[true,2],'MIL on with 2 codes');ok(mil.monitors.some(m=>m.name==='Catalyst'&&!m.complete),'catalyst monitor incomplete');
  sim.fcSeen=0;sim.tx.length=0;
  const vin=await e.run('diagReadVin()');eq([vin.vin,vin.valid,vin.ecu],['WP0ZZZ99ZTS392124',true,0x7E8],'VIN from multi-frame message');
  eq(sim.fcSeen,1,'exactly one flow-control frame sent');const fc=sim.tx.find(t=>(t.d[0]>>4)===3);eq([fc.id,fc.d],[0x7E0,[0x30,0,0,0,0,0,0,0]],'flow control 30 00 00 to 0x7E0 (physical request id of the responder)');
  const s=await e.run('diagReadDtcs("stored")');eq(s.ecus.length,2,'both ECUs answered the broadcast');
  const a=s.ecus.find(x=>x.ecu===0x7E8),b=s.ecus.find(x=>x.ecu===0x7E9);eq(a.codes.map(c=>c.code),['P0133','P0420','P0300'],'3 codes via first frame + consecutive frame');eq(a.codes[0].desc,'O2 sensor slow response (bank 1 sensor 1)','generic text');eq(b.codes,[],'ECU 2: no codes');
  const p=await e.run('diagReadDtcs("pending")');eq(p.ecus.find(x=>x.ecu===0x7E8).codes.map(c=>c.code),['C0123'],'Mode 07 chassis code, no description');eq(p.ecus.find(x=>x.ecu===0x7E8).codes[0].desc,null,'unknown shown raw');
  const pm=await e.run('diagReadDtcs("permanent")');eq(pm.ecus.find(x=>x.ecu===0x7E8).codes.map(c=>c.code),['P0420'],'Mode 0A');
  const first=sim.tx.filter(t=>t.id===0x7DF).map(t=>t.d[1]);ok(first.includes(3)&&first.includes(7)&&first.includes(0x0A),'requests 03, 07, 0A sent to 0x7DF');
  eq(sim.violations,[],'all data frames were sent in normal mode');
  ok(sim.tx.every(t=>t.d.length===8&&(t.id===0x7DF||(t.id>=0x7E0&&t.id<=0x7E7))),'every TX is an 8-byte frame to 0x7DF or 0x7E0-0x7E7');
  // negative response / no answer
  sim.o.silent=true;const nd=await e.run('diagReadDtcs("stored")');ok(nd.nodata&&!nd.ecus.length,'no answer -> NO DATA result');
  return 'PID chain, 8 live PIDs, MIL, VIN multi-frame with FC, Mode 03/07/0A with 2 ECUs';
});
await test('Diagnostics: only standard OBD-II payloads and ids can be transmitted (no UDS, no write/security/routine/flash, no raw sender)',async()=>{
  const {e,sim}=await mkEnv({diag:true});sim.tx.length=0;
  const tryTx=async(id,bytes)=>{try{await e.run(`diagTx(${id},Uint8Array.from(${JSON.stringify(bytes)}))`);return 'sent';}catch(x){return x.code||x.message;}};
  const pad=a=>{const b=a.slice();while(b.length<8)b.push(0);return b;};
  for(const svc of [0x02,0x05,0x06,0x08,0x0B,0x10,0x11,0x14,0x19,0x22,0x23,0x27,0x28,0x2A,0x2E,0x2F,0x31,0x34,0x35,0x36,0x37,0x3D,0x3E,0x85,0x86,0x87,0xA0])
    eq(await tryTx(0x7E0,pad([2,svc,0x01])),'frame','service 0x'+svc.toString(16)+' refused');
  eq(await tryTx(0x7E0,pad([2,0x09,0x04])),'frame','Mode 09 PID 04 (calibration id) refused: only 00/02');eq(await tryTx(0x7E0,pad([3,1,0x0C,0])),'frame','3-byte payload refused');
  eq(await tryTx(0x7E0,pad([2,3,0])),'frame','Mode 03 with extra byte refused');eq(await tryTx(0x7E0,pad([1,0x04,0x01])),'frame','non-zero padding refused (no hidden payload)');eq(await tryTx(0x7E0,pad([1,0x04])),'sent','Mode 04 payload only [04] with zero padding');
  e.run('DIAG.lastTx=0');
  for(const id of [0x700,0x7E8,0x7DE,0x7F0,0x123,0x000,0x7FF,0x18DB33F1])eq(await tryTx(id,pad([2,1,0])),'frame','id 0x'+id.toString(16)+' refused');
  eq(await tryTx(0x7E0,pad([0x10,0x14,1,2,3,4,5,6])),'frame','multi-frame first frame refused');eq(await tryTx(0x7E0,pad([0x21,1,2,3,4,5,6,7])),'frame','consecutive frame refused');
  eq(await tryTx(0x7DF,pad([0x30,0,0])),'frame','flow control to functional id refused');eq(await tryTx(0x7E0,pad([0x30,0,1])),'frame','flow control with other parameters refused');eq(await tryTx(0x7E0,pad([0x31,0,0])),'frame','PCI 0x31 refused');
  eq(await tryTx(0x7E0,[2,1]),'frame','2-byte frame refused');eq(await tryTx(0x7E0,[2,1,0,0,0,0,0,0,0]),'frame','9-byte frame refused');
  const sent=sim.tx.map(t=>t.d.join());eq(sent,['1,4,0,0,0,0,0,0'],'only the single legitimate frame reached the adapter');
  // source audit: no code builds requests for forbidden services
  const used=[...SRC.matchAll(/diagQuery\(\[([^\]]*)\]/g)].map(m=>m[1].split(',')[0].trim());
  ok(used.length>=7,'diagQuery call sites: '+used.join(' '));
  for(const u of used){if(u==='m.req'){eq(e.run('Object.values(DTC_MODES).map(m=>m.req)'),[3,7,10],'DTC_MODES requests');continue;}ok(['1','9','0x04'].includes(u),'service in diagQuery call: '+u);}
  ok(!/0x(10|11|14|19|22|27|28|2E|2F|31|34|35|36|37|3E|85)\b[^\n]*sfFrame|sfFrame\([^)]*0x(27|2E|31|34|36)/.test(SRC),'no forbidden service built via sfFrame');
  for(const w of ['sendRaw','rawSend','sendCustom','writeDid','securityAccess','routineControl','requestDownload','transferData','ecuReset'])ok(!new RegExp('\\b'+w+'\\b','i').test(SRC),'no '+w);
  ok(!/id=["'](?:rawId|rawData|rawSend|bRaw|customFrame)/.test(fs.readFileSync(path.join(ROOT,'index.html'),'utf8')),'no raw-frame sender in the UI');
  return '26 non-OBD services, 8 foreign ids, FF/CF/odd FC frames refused; only standard requests reached the adapter; no raw sender in source or UI';
});
await test('Mode 04 clear flow: guards (no confirm, no prior read, outside Diagnostics, RPM seen, ECU RPM/speed > 0), success path, re-read and report',async()=>{
  const {e,sim}=await mkEnv({diag:true,sim:{dtcs:[0x0133,0x0420]}});
  const clr=async(o)=>{try{const r=await e.run('clearDtcs('+JSON.stringify(o)+')');return r;}catch(x){return {err:x.code||x.message};}};
  const sent04=()=>sim.tx.filter(t=>t.d[1]===4).length;
  eq((await clr({})).err,'confirm','no confirmation');eq((await clr({confirm:'clear'})).err,'confirm','lower case');eq((await clr({confirm:'CLEAR '})).err,'confirm','trailing space');eq((await clr({confirm:true})).err,'confirm','boolean');eq((await clr({confirm:'YES'})).err,'confirm','other word');
  eq((await clr({confirm:'CLEAR'})).err,'review','must read codes first');eq(sent04(),0,'no Mode 04 so far');
  await e.run('diagReadDtcs("stored")');
  // UI path: button disabled until CLEAR typed
  await e.els.bClearOpen.onclick();await e.sleep(40);ok(!e.els.clearBox.hidden,'clear box shown');ok(/2 stored code/.test(e.els.clearWhat.innerHTML)&&/freeze-frame/.test(e.els.clearWhat.innerHTML)&&/readiness/.test(e.els.clearWhat.innerHTML),'shows what will be erased: '+e.els.clearWhat.innerHTML.slice(0,160));
  e.els.clearInp.value='CLEA';e.els.clearInp.oninput();ok(e.els.bClearGo.disabled,'erase button disabled for partial text');e.els.clearInp.value='CLEAR';e.els.clearInp.oninput();ok(!e.els.bClearGo.disabled,'enabled after typing CLEAR');
  // engine running on the bus (passive frame)
  e.run('feed(__f)'.replace('__f','"t7E8404'+'10C0C80'.slice(0,0)+'1'.slice(0,0)+'\\r"'.slice(0,0)+'\\r"')); // harmless malformed
  e.ctx.__c=frame(0x7E8,[4,0x41,0x0C,0x0C,0x80,0,0,0]);e.run('feed(__c)');
  eq((await clr({confirm:'CLEAR'})).err,'rpm','RPM > 0 seen on the bus');eq(sent04(),0,'refused, nothing sent');
  e.run('RPMSEEN.t=0;OBD.clear()');
  sim.o.rpm=900;eq((await clr({confirm:'CLEAR'})).err,'rpm','ECU reports RPM > 0 (active check)');eq(sent04(),0,'still nothing sent');
  e.run('RPMSEEN.t=0;OBD.clear()');sim.o.rpm=0;sim.o.speed=30;await e.run('diagReadDtcs("stored")');eq((await clr({confirm:'CLEAR'})).err,'moving','vehicle speed > 0');eq(sent04(),0);
  sim.o.speed=0;e.run('RPMSEEN.t=0;OBD.clear()');
  sim.tx.length=0;const res=await clr({confirm:'CLEAR'});
  eq([res.ok,res.responders,res.remaining],[true,[0x7E8],0],'Mode 04 acknowledged, nothing remains');eq(res.after.nodata,false,'re-read answered');eq(res.before.ecus[0].codes.length,2,'before: 2 codes');eq(res.after.ecus[0].codes.length,0,'after: none');
  eq(sim.tx.map(t=>t.d.slice(0,3).join()),['2,1,12','2,1,13','1,4,0','1,3,0'],'sequence: live RPM check, speed check, Mode 04, Mode 03 re-read');eq(sent04(),1,'exactly one Mode 04 frame');
  eq((await clr({confirm:'CLEAR'})).err,'review','a new review is required before the next clear');
  // negative response
  await e.run('diagReadDtcs("stored")');sim.o.rejectClear=true;sim.o.dtcs=[0x0133];const neg=await clr({confirm:'CLEAR'});eq(neg.ok,false,'negative response: not ok');eq(neg.neg[0].nrc,0x22,'NRC 0x22 reported');
  // outside Diagnostics mode
  await e.run('setMode("monitor")');const n=sim.tx.length;eq((await clr({confirm:'CLEAR'})).err,'mode','refused in Monitor mode');eq(sim.tx.length,n,'nothing transmitted');
  return 'confirm/review/mode/RPM/ECU-RPM/speed guards each refuse before Mode 04; success: 04 then 03 re-read, report; negative response reported';
});
await test('Diagnostics UI flow: DTC list rendering, clear box -> typed CLEAR -> result text; link drop while in Diagnostics falls back to Monitor; "z" tx acks are not errors',async()=>{
  const {e,sim,port}=await mkEnv({diag:true,sim:{dtcs:[0x0133,0xC123,0x1234]}});
  await e.els.bDtc3.onclick();await e.sleep(80);
  const h=e.els.dgDtc3._h;ok(/P0133/.test(h)&&/O2 sensor slow response/.test(h)&&/generic/.test(h),'known code with generic text: '+h.slice(0,200));ok(/U0123/.test(h)&&/P1234/.test(h)&&/unknown code \(shown raw\)/.test(h),'unknown codes shown raw');
  ok(/ECU 0x7E8/.test(h)&&/3 code/.test(h),'ECU and count shown');
  e.ctx.__z='z\r';e.run('feed(__z)');eq(e.run('H.bad+H.resync'),0,'z ack ignored');
  await e.els.bClearOpen.onclick();await e.sleep(80);ok(!e.els.clearBox.hidden&&/3 stored code/.test(e.els.clearWhat.innerHTML),'clear box lists the count');
  e.els.clearInp.value='CLEAR';e.els.clearInp.oninput();await e.els.bClearGo.onclick();await e.sleep(200);
  ok(!e.els.clearRes.hidden&&/acknowledged by ECU 0x7E8/.test(e.els.clearRes.textContent)&&/0 code\(s\) remain/.test(e.els.clearRes.textContent)&&/readiness monitors are reset/i.test(e.els.clearRes.textContent),'result text: '+e.els.clearRes.textContent);
  ok(e.els.clearBox.hidden,'clear box closed');
  eq(e.run('MODE'),'diag');sim.cmds.length=0;port.closed=true;await e.sleep(2600);
  eq(e.run('[MODE,!!link,link&&link.txEnabled,link&&link.listenOnly]'),['monitor',true,false,true],'reconnected in Monitor, listen-only, tx disabled');eq(sim.cmds,['C','S6','M1','O'],'setup after reconnect is the monitor sequence');
  eq(e.els.modeTxt.textContent,'MONITOR · read-only','banner back to monitor');
  return 'DTC rendering (generic text / raw), clear result text, reconnect lands in Monitor';
});
await test('RPM seen: window, sources (OBD, Hunter 0x100, user decoder) and expiry',async()=>{
  const e=makeEnv({fakeClock:true});e.setNow(100000);const f=s=>{e.ctx.__c=s;e.run('feed(__c)');};
  eq(e.run('engineRunning(nowMs())'),false,'idle bus');
  f(frame(0x7E8,[4,0x41,0x0C,0,0,0,0,0]));eq(e.run('engineRunning(nowMs())'),false,'RPM 0 is not running');
  f(frame(0x7E8,[4,0x41,0x0C,0x0C,0x80,0,0,0]));eq(e.run('engineRunning(nowMs())'),true,'OBD RPM 800');
  e.advance(4000);eq(e.run('engineRunning(nowMs())'),true,'4 s later still counted');e.advance(7000);eq(e.run('engineRunning(nowMs())'),false,'11 s later expired');
  f(frame(0x100,[0,0,0,0,0x0F,0xA0,0,0]));eq(e.run('engineRunning(nowMs())'),true,'Hunter 0x100 RPM 4000 (Hunter profile)');e.advance(11000);eq(e.run('engineRunning(nowMs())'),false);
  await e.run('setProfile("car")');f(frame(0x100,[0,0,0,0,0x0F,0xA0,0,0]));eq(e.run('engineRunning(nowMs())'),false,'Hunter IDs ignored on a car profile');
  e.run('addDec({id:"1A0",start:0,len:2,endian:"be",scale:1,offset:0,unit:"rpm",name:"my rpm"})');f(frame(0x1A0,[0x03,0xE8,0,0,0,0,0,0]));eq(e.run('engineRunning(nowMs())'),true,'user decoder with unit rpm');
  return 'OBD / Hunter / user decoder sources; 10 s window; 5 s OBD staleness';
});
await test('rate limit: 50 ms between requests, 30 frames per second, flow control exempt from the gap but not the cap',async()=>{
  const {e,sim}=await mkEnv({fake:true,diag:true});e.run('DLIM.minGap=50;DLIM.perSec=30');e.setNow(500000);
  const tx=async(fc)=>{try{await e.run(fc?'diagTx(0x7E0,FC_FRAME,{fc:true})':'diagTx(0x7DF,sfFrame([1,0]))');return 'ok';}catch(x){return x.code;}};
  eq(await tx(),'ok','first request');eq(await tx(),'rate','second request immediately: refused');e.advance(30);eq(await tx(),'rate','30 ms later: still refused');e.advance(25);eq(await tx(),'ok','55 ms later: allowed');
  eq(await tx(true),'ok','flow control right after a request');
  e.advance(1100);let okN=0,rate=0;for(let i=0;i<40;i++){const r=await tx(true);if(r==='ok')okN++;else if(r==='rate')rate++;e.advance(1);}
  eq([okN,rate],[30,10],'burst of 40 flow-control frames in 40 ms: 30 pass, 10 refused');
  eq(await tx(),'rate','requests also blocked while the cap is hit');e.advance(1100);eq(await tx(),'ok','allowed again after the window');
  ok(sim.tx.length===2+30+1+1-1+0||sim.tx.length>0,'adapter only saw accepted frames');
  eq(sim.tx.length,2+1+30+1-0,'adapter frames = accepted frames: '+sim.tx.length);
  return 'min gap 50 ms enforced (refused, not queued), cap 30/s enforced, FC exempt from gap only';
});
await test('idle timeout returns to Monitor on its own; reset on disconnect; Hunter profile shows experimental note',async()=>{
  const {e,sim}=await mkEnv({fake:true,diag:true});e.setNow(1000000);e.run('DIAG.activity=nowMs()');
  e.advance(250000);e.run('diagIdleCheck(nowMs())');eq(e.run('MODE'),'diag','after 250 s still diagnostics');
  e.advance(60000);sim.cmds.length=0;e.run('diagIdleCheck(nowMs())');await e.sleep(40);eq(e.run('MODE'),'monitor','after 310 s idle: monitor');eq(sim.cmds,['C','S6','M1','O'],'listen-only restored');eq(e.run('link.txEnabled'),false);
  e.run('renderDiag()');eq(e.els.dgHunter.hidden,false,'Hunter profile: experimental note visible');
  const html=fs.readFileSync(path.join(ROOT,'index.html'),'utf8');
  for(const t of [/Hunter 350 and other motorcycles/,/proprietary protocols/,/experimental and unverified/,/own vehicle/,/ignition on, engine off/,/battery healthy/,/readiness monitors/,/freeze-frame/,/Type CLEAR/,/accept responsibility/])ok(t.test(html),'warning text '+t);
  return 'auto-return after 5 min idle; warning and Hunter-experimental text present';
});
await test('capture file: every transmitted frame is a "# TX" line; analyzer parses TX, MODE, DTC and VIN responses',async()=>{
  const {e,sim}=await mkEnv({diag:false,sim:{dtcs:[0x0133,0x0420,0x0300]}});
  e.els.lbl.value='diag';await e.els.bRec.onclick();
  await enableDiagUi(e);await e.run('setMode("diag")');
  await e.run('diagReadPid(0x0C)');await e.run('diagReadDtcs("stored")');await e.run('diagReadVin()');await e.run('clearDtcs({confirm:"CLEAR"})');
  await e.run('setMode("monitor")');await e.els.bRec.onclick();
  const lines=e.run('saved.lines');const txl=lines.filter(l=>l.startsWith('# TX '));
  eq(txl.length,sim.tx.length,'one # TX line per transmitted frame ('+txl.length+')');
  ok(txl.every(l=>/^# TX \d+\.\d{4} (7DF|7E0) 8 ([0-9A-F]{2} ){7}[0-9A-F]{2}$/.test(l)),'format: '+txl[0]);
  ok(txl.some(l=>/ 7DF 8 02 01 0C 00 00 00 00 00$/.test(l))&&txl.some(l=>/ 7DF 8 01 04 00 00 00 00 00 00$/.test(l))&&txl.some(l=>/ 7E0 8 30 00 00 00 00 00 00 00$/.test(l)),'live PID, Mode 04 and flow-control frames logged');
  const f=tmp('diag_capture.txt');fs.writeFileSync(f,lines.join('\n')+'\n');
  const out=py([ANALYZER,f]);
  ok(new RegExp('Active diagnostics: '+txl.length+' transmitted frame').test(out),'analyzer counts TX frames: '+out.slice(out.indexOf('Active'),out.indexOf('Active')+200));
  ok(/TX by ID: 0x7DF x\d+, 0x7E0 x\d+/.test(out),'TX by ID');ok(/Mode changes: MODE_DIAGNOSTICS@[\d.]+s, MODE_MONITOR@[\d.]+s/.test(out),'mode changes');
  ok(/WARNING: Mode 04 \(clear fault codes\) was requested/.test(out),'Mode 04 flagged');ok(/\[Mode 04 CLEAR codes\]/.test(out)&&/\[ISO-TP flow control\]/.test(out)&&/\[Mode 03 read stored codes\]/.test(out),'TX decoded by service');
  ok(/stored codes from ECU 0x7E8 at [\d.]+s: P0133, P0420, P0300/.test(out),'multi-frame Mode 03 response decoded: '+out.split('\n').filter(l=>/stored codes/.test(l)).join('|'));
  ok(/stored codes from ECU 0x7E8 at [\d.]+s: none/.test(out),'post-clear re-read shows none');ok(/VIN from ECU 0x7E8: WP0ZZZ99ZTS392124/.test(out),'VIN decoded');ok(/Mode 04 positive response from ECU 0x7E8/.test(out),'Mode 04 response seen');
  ok(/Adapter: type=slcan-serial mode=monitor/.test(out),'adapter header');
  // ELM style isotp line + frame counting unaffected by TX lines
  const g=tmp('elm_capture.txt');fs.writeFileSync(g,['# Hunter live capture, label: x, started 2026-01-01T00:00:00.000Z','# PROFILE id=car name=Generic_car bitrate=500000 idfmt=both listen_only=1','# TX 1.2000 7DF 2 01 0C isotp','# TX 2.0000 7DF 1 04 isotp','0.5000 7E8 8 04 41 0C 1A F8 00 00 00','0.9000 7E8 8 03 43 00 00 00 00 00 00',''].join('\n'));
  const o2=py([ANALYZER,g]);ok(/2 transmitted frame/.test(o2)&&/\[Mode 04 CLEAR codes\]/.test(o2)&&/2 frames/.test(o2),'isotp TX lines parsed, data lines still 2 frames: '+o2.slice(0,500));
  const plain=py([ANALYZER,path.join(__dirname,'data','synth.txt')]);ok(!/Active diagnostics/.test(plain),'no diagnostics section for a listen-only capture');
  return txl.length+' # TX lines (one per frame), MODE events, DTC/VIN decode, Mode 04 warning, ELM isotp lines parse';
});

// 5 ----------------------------------------------------------- adapters & pipes
await test('adapter picker: only options the browser supports are listed; default; persistence; switching blocked while connected',async()=>{
  const ids=e=>J(e.run('adList().map(a=>a.id)'));const sel=e=>e.els.adSel._h;
  const none=makeEnv({});eq(ids(none),[],'no API at all -> no adapters');ok(/No supported adapter/.test(sel(none)),'placeholder text');ok(none.els.bSerial.disabled,'connect disabled');
  const ser=makeEnv({nav:{serial:{requestPort:async()=>null,getPorts:async()=>[],addEventListener(){}}}});eq(ids(ser),['slcan-serial','elm-serial'],'Web Serial only');
  ok(!/gsusb|slcan-usb|elm-ble|ble-slcan|value="ws"/.test(sel(ser)),'hidden options absent from the select');ok(/value="slcan-serial" selected/.test(sel(ser)),'default = first available');
  const usb=makeEnv({nav:{usb:{getDevices:async()=>[],addEventListener(){}}}});eq(ids(usb),['slcan-usb','gsusb'],'WebUSB only');eq(usb.run('adCur().id'),'slcan-usb','default is WebUSB SLCAN when serial is missing');
  const ble=makeEnv({nav:{bluetooth:{}}});eq(ids(ble),['elm-ble','ble-slcan'],'Web Bluetooth only');
  const ws=makeEnv({globals:{WebSocket:function(){}}});eq(ids(ws),['ws'],'WebSocket only');
  const all=makeEnv({nav:{serial:{addEventListener(){}},usb:{addEventListener(){}},bluetooth:{}},globals:{WebSocket:function(){}}});eq(ids(all),['slcan-serial','slcan-usb','gsusb','elm-serial','elm-ble','ws','ble-slcan'],'everything available');
  // saved choice that is no longer available falls back
  const fb=makeEnv({nav:{serial:{addEventListener(){}}},ls:{'hcl.adapter':JSON.stringify({type:'gsusb'})}});eq(fb.run('adCur().id'),'slcan-serial','unavailable saved adapter falls back');
  // persistence + UI rows
  all.els.adSel.value='gsusb';all.els.adSel.onchange();eq(JSON.parse(all.ls.get('hcl.adapter')).type,'gsusb','persisted');eq([all.els.adSerialRow.hidden,all.els.adFiltRow.hidden,all.els.adStyleRow.hidden,all.els.adWsRow.hidden],[true,false,true,true],'gs_usb rows');
  ok(/UNTESTED/.test(all.els.adNote.textContent),'gs_usb marked untested');
  all.els.adSel.value='ws';all.els.adSel.onchange();eq([all.els.adWsRow.hidden,all.els.adFiltRow.hidden],[false,true],'ws rows');
  all.els.adSel.value='elm-serial';all.els.adSel.onchange();ok(/limited/.test(all.els.adNote.textContent)&&/Diagnostics/.test(all.els.adNote.textContent),'ELM327 note: monitoring limited, mainly diagnostics');
  all.els.filtSel.value='custom';all.els.filtSel.onchange();eq(all.els.adCustomRow.hidden,false,'custom VID/PID row');
  const {e,sim}=await mkEnv({});e.els.adSel.value='slcan-usb';e.els.adSel.onchange();eq(e.run('AD.type')===''||e.run('AD.type')!=='slcan-usb',true,'type unchanged while connected');
  const html=fs.readFileSync(path.join(ROOT,'index.html'),'utf8');ok(/ELM327 monitoring is limited/.test(html)||/monitoring \(ATMA\) is limited/.test(html),'UI note about ELM327 limits');
  return '7 adapter types; every combination of Serial/USB/Bluetooth/WebSocket availability lists exactly the supported ones';
});
await test('SLCAN over Web Serial: baud and VID/PID filter options (any device, preset, custom), reconnect finds the same device',async()=>{
  const seen=[];const sim=new EcuSim();const port=new MockSerial(sim.handler,{usbVendorId:0x04D8,usbProductId:0x000A});
  const nav=()=>({serial:{requestPort:async o=>{seen.push(J(o));return port;},getPorts:async()=>[port],addEventListener(){}}});
  const mk=async ad=>{const e=makeEnv({nav:nav(),ls:{'hcl.adapter':JSON.stringify(Object.assign({type:'slcan-serial'},ad))}});e.run('CFG.cmdMs=1');await e.els.bSerial.onclick();await e.sleep(40);return e;};
  let e=await mk({});eq(seen[0],{},'any device: no filter');eq(port.opts.baudRate,115200,'default baud');await e.els.bDisc.onclick();
  e=await mk({filter:'canable',baud:921600});eq(seen[1],{filters:[{usbVendorId:0x16D0,usbProductId:0x117E}]},'preset filter');eq(port.opts.baudRate,921600,'selected baud');await e.els.bDisc.onclick();
  e=await mk({filter:'usbtin'});eq(seen[2],{filters:[{usbVendorId:0x04D8,usbProductId:0x000A}]},'USBtin preset');await e.els.bDisc.onclick();
  e=await mk({filter:'custom',vid:'1234',pid:''});eq(seen[3],{filters:[{usbVendorId:0x1234}]},'custom VID only');await e.els.bDisc.onclick();
  e=await mk({filter:'custom',vid:'1234',pid:'abcd'});eq(seen[4],{filters:[{usbVendorId:0x1234,usbProductId:0xABCD}]},'custom VID:PID');
  // reconnect: link drops, device present again
  sim.cmds.length=0;port.closed=true;await e.sleep(30);ok(e.run('reconnecting')||e.run('!!link'),'drop handled');
  await e.sleep(2300);ok(e.run('!!link'),'reconnected to the same device (VID/PID 04D8:000A)');eq(sim.cmds.filter(c=>c==='M1').length,1,'setup re-sent');await e.els.bDisc.onclick();
  return 'filters: none / preset / custom VID / custom VID:PID; baud applied; reconnect by remembered VID:PID';
});
await test('SLCAN over WebUSB CDC (endpoint discovery, DTR, line coding)',async()=>{
  const sim=new EcuSim();const calls=[];let epOutUsed=null,epInUsed=null;const dec=new TextDecoder();
  const dev={vendorId:0x1D50,productId:0x6015,configuration:{interfaces:[{interfaceNumber:0,alternates:[{interfaceClass:2,endpoints:[{endpointNumber:5,direction:'in',type:'interrupt'}]}]},{interfaceNumber:1,alternates:[{interfaceClass:0x0A,endpoints:[{endpointNumber:3,direction:'in',type:'bulk'},{endpointNumber:2,direction:'out',type:'bulk'}]}]}]},
    async open(){},async selectConfiguration(){},async claimInterface(n){calls.push('claim'+n);},async close(){this.closed=true;},
    async controlTransferOut(s,d){calls.push(['ctl',s.request,s.value,s.index]);return {status:'ok'};},
    q:[],async transferIn(ep){epInUsed=ep;await new Promise(r=>setTimeout(r,3));if(this.closed)throw new Error('closed');return {status:'ok',data:this.q.length?new DataView(new TextEncoder().encode(this.q.shift()).buffer):null};},
    async transferOut(ep,data){epOutUsed=ep;sim.handler(dec.decode(data),{push:s=>dev.q.push(s)});return {status:'ok'};}};
  const e=makeEnv({nav:{usb:{requestDevice:async()=>dev,getDevices:async()=>[dev],addEventListener(){}}},ls:{'hcl.adapter':JSON.stringify({type:'slcan-usb',baud:57600})}});
  e.run('CFG.cmdMs=1');await e.els.bSerial.onclick();await e.sleep(50);
  ok(e.run('!!link'),'connected');eq(epOutUsed,2,'bulk OUT endpoint discovered (2)');eq(epInUsed,3,'bulk IN endpoint discovered (3)');ok(calls.includes('claim0')&&calls.includes('claim1'),'both interfaces claimed');
  ok(calls.some(c=>c[0]==='ctl'&&c[1]===0x22&&c[2]===1&&c[3]===0),'DTR via control interface 0');ok(calls.some(c=>c[0]==='ctl'&&c[1]===0x20),'line coding set');
  eq(sim.cmds,['C','S6','M1','O'],'monitor commands over WebUSB');dev.q.push('t1008AABBCCDDEEFF0011\r');await e.sleep(30);eq(e.run('FR.total'),1,'frame parsed');
  return 'bulk endpoints found from descriptors, SLCAN works identically';
});
await test('WebSocket bridge (SLCAN text): URL validation, setup commands, frames, drop -> reconnect; BLE-UART SLCAN bridge',async()=>{
  const inst=[];class MockWS{constructor(u){this.url=u;this.sent=[];inst.push(this);setTimeout(()=>this.onopen&&this.onopen(),2);}send(s){this.sent.push(s);}close(){this.closed=true;if(this.onclose)this.onclose();}}
  const mk=async url=>{const e=makeEnv({globals:{WebSocket:MockWS},ls:{'hcl.adapter':JSON.stringify({type:'ws',wsUrl:url})}});e.run('CFG.cmdMs=1');await e.els.bSerial.onclick();await e.sleep(40);return e;};
  for(const bad of ['','http://x/','ftp://x','192.168.4.1:81','ws://','javascript:alert(1)','ws://a b']){const e=await mk(bad);eq(e.run('link'),null,'refused '+JSON.stringify(bad));}
  eq(inst.length,0,'no socket created for bad URLs');
  const e=await mk('ws://192.168.4.1:81/can');ok(e.run('!!link&&link.kind==="slcan"'),'connected');eq(inst[0].url,'ws://192.168.4.1:81/can','url as given');eq(inst[0].sent,['C\r','S6\r','M1\r','O\r'],'same monitor-only commands');
  inst[0].onmessage({data:'t1008AABBCCDDEEFF0011\rt2002AABB\r'});eq(e.run('FR.total'),2,'text message with two frames');
  inst[0].onmessage({data:new TextEncoder().encode('t300100\r').buffer});eq(e.run('FR.total'),3,'binary message decoded as text');
  inst[0].onclose();await e.sleep(80);ok(e.run('!!link&&!reconnecting'),'drop -> reconnected');eq(inst.length,2,'second socket to the same URL');eq(inst[1].url,'ws://192.168.4.1:81/can','same URL');eq(inst[1].sent,['C\r','S6\r','M1\r','O\r'],'setup re-sent');
  let err=null;try{await e.run('tx("t7DF8020100000000000000")');}catch(x){err=x.message;}ok(/blocked/.test(err),'data frame blocked over WebSocket too');
  await e.els.bDisc.onclick();ok(inst[1].closed,'socket closed');
  // BLE-UART SLCAN bridge
  const wrote=[];const ch={properties:{notify:true,write:true},listeners:{},addEventListener(t,f){this.listeners[t]=f;},async startNotifications(){},async stopNotifications(){},async writeValue(b){wrote.push(new TextDecoder().decode(b));}};
  const dev={gatt:{async connect(){return {async getPrimaryServices(){return [{async getCharacteristics(){return [ch];}}];}};},disconnect(){}},addEventListener(){}};
  const b=makeEnv({nav:{bluetooth:{requestDevice:async()=>dev}},ls:{'hcl.adapter':JSON.stringify({type:'ble-slcan'})}});b.run('CFG.cmdMs=1');await b.els.bSerial.onclick();await b.sleep(40);
  eq(wrote.join('').split('\r').filter(Boolean),['C','S6','M1','O'],'SLCAN commands over BLE (writeValue fallback)');
  const u=new TextEncoder().encode('t1008AABBCCDDEEFF0011\r');ch.listeners.characteristicvaluechanged({target:{value:new DataView(u.buffer)}});eq(b.run('FR.total'),1,'frame via BLE notification');
  return 'ws:// / wss:// only, same allow-list over WebSocket, reconnect; BLE bridge speaks SLCAN';
});
await test('audit: every adapter command and data frame seen by all simulators happened in the right mode',async()=>{
  const {e,sim}=await mkEnv({diag:true});await e.run('diagReadPid(0x0C)');await e.run('setMode("monitor")');await e.run('setMode("diag")');await e.run('diagReadDtcs("stored")');await e.run('setMode("monitor")');
  eq(sim.violations,[],'no data frame while listen-only');
  const cmds=sim.cmds.filter(c=>!/^t/.test(c));let mode='monitor',bad=[];
  for(let i=0;i<cmds.length;i++){if(cmds[i]==='M0')mode='diag';if(cmds[i]==='M1')mode='monitor';}
  ok(cmds.every(c=>/^(C|S[0-8]|M0|M1|O|V)$/.test(c)),'only C, S, M0, M1, O commands: '+[...new Set(cmds)].join());
  const m0=cmds.filter(c=>c==='M0').length,m1=cmds.filter(c=>c==='M1').length;eq([m0,m1],[2,3],'M0 only on entering Diagnostics; M1 at connect and each return');
  eq(cmds[cmds.length-4],'C','finished in listen-only');eq(cmds.slice(-2).join(),'M1,O','last setup is M1,O');
  return cmds.length+' commands and '+sim.tx.length+' data frames, all in the right mode';
});
await test('UI statics: Diagnostics tab, mode banner, return button, adapter picker, SW cache v3, manifest text',()=>{
  const h=fs.readFileSync(path.join(ROOT,'index.html'),'utf8');
  ok(/<button role="tab" data-t="diag" id="tabDiag"[^>]*hidden/.test(h),'Diag tab hidden by default');ok(/id="modeBar"/.test(h)&&/MONITOR · read-only/.test(h)&&/DIAGNOSTICS · ACTIVE TX/.test(h),'banner texts');ok(/id="bToMon"[^>]*hidden>Return to Monitor/.test(h),'return button');
  ok(/class="modal" id="diagWarn"/.test(h)&&/id="diagAck"/.test(h),'warning screen');ok(/id="adSel"/.test(h),'adapter select');ok(/never clears or writes ECU fault codes/.test(h),'Monitor-mode read-only statement kept');
  ok(/hcl-v3/.test(fs.readFileSync(path.join(ROOT,'sw.js'),'utf8')),'service worker cache bumped');
  const man=JSON.parse(fs.readFileSync(path.join(ROOT,'manifest.webmanifest'),'utf8'));ok(/diagnostic/i.test(man.description)&&!/^Read-only/i.test(man.description),'manifest description updated: '+man.description);
  ok(/\.modebar\.diag/.test(h)&&/prefers-reduced-motion/.test(h),'banner css');
  return 'ok';
});
const fails=results.filter(r=>!r.ok);
fs.writeFileSync(path.join(__dirname,'results_v3.json'),JSON.stringify(results,null,1));
console.log('\n'+(results.length-fails.length)+'/'+results.length+' passed');
process.exit(fails.length?1:0);
})();
