// Signal-map corrections from the user's real Hunter 350 captures (2026-11-15): decoders, labels, battery state, gap rule.
// Node vm with mocks, no hardware. Run: node tests/signals.js
const fs=require('fs'),path=require('path'),cp=require('child_process');
const {makeEnv,frame,ROOT}=require('./harness');
const DATA=path.join(__dirname,'data','real');
const ANALYZER=path.join(ROOT,'tools','hunter_analyze.py');
const results=[];
const eq=(a,b,m)=>{if(JSON.stringify(a)!==JSON.stringify(b))throw new Error((m||'eq')+': got '+JSON.stringify(a)+' expected '+JSON.stringify(b));};
const ok=(c,m)=>{if(!c)throw new Error('assert failed: '+m);};
const near=(a,b,t,m)=>{if(!(Math.abs(a-b)<=t))throw new Error((m||'near')+': got '+a+' expected '+b+' +-'+t);};
function test(name,fn){try{const d=fn();results.push({name,ok:true,detail:d||''});console.log('PASS '+name+(d?' - '+d:''));}catch(e){results.push({name,ok:false,detail:e.message});console.log('FAIL '+name+' - '+e.message);}}
const HORN='hunter_Horn_20261115T072421.txt',IGN='hunter_Ignition_on_20261115T073249.txt',OFF='hunter_EngineOff_capture_20261115T073230.txt',GEAR1='hunter_gear_1_20261115T073306.txt',
  KILL='hunter_Kill_Switch_20261115T072832.txt',FB='hunter_Front_Brake_20261115T072224.txt',PASS='hunter_Pass_Flash_20261115T072508.txt';
function cap(name){return fs.readFileSync(path.join(DATA,name),'latin1').split('\n').filter(l=>l&&l[0]!=='#').map(l=>{const p=l.trim().split(/\s+/);return {t:parseFloat(p[0]),id:parseInt(p[1],16),d:p.slice(3,3+parseInt(p[2])).map(x=>parseInt(x,16))};});}
function mk(){const e=makeEnv({fakeClock:true});e.setNow(100000);e.run('link={};connAt=nowMs()');e.base=100000;return e;}
function feed(e,frames,from,to){for(const f of frames){if(f.t<from||f.t>=to)continue;e.setNow(e.base+Math.round(f.t*1000));e.ctx.__c=frame(f.id,f.d);e.run('feed(__c)');}}
const all=1e9;
const bv=(e)=>JSON.parse(e.run('JSON.stringify(batView(nowMs()))'));
const txt=(e,id)=>e.els[id].textContent;

test('gap regression: real Horn capture (clean, app reported gaps=25) gives gaps = 0',()=>{
  const e=mk();feed(e,cap(HORN),0,all);
  eq([e.run('H.stale'),e.run('H.bad')],[0,0],'gaps/bad');
  for(const n of [IGN,OFF,GEAR1,KILL,FB,PASS]){const x=mk();feed(x,cap(n),0,all);eq(x.run('H.stale'),0,n);}
  return 'Horn + 6 more real captures: gaps = 0';
});
test('gap rule: 1 Hz ID with a 3 s pause is not a gap; 10 Hz ID silent for 1.2 s is',()=>{
  const e=makeEnv({fakeClock:true});e.setNow(1000);e.run('link={};connAt=nowMs()');const k=(id,d)=>{e.ctx.__c=frame(id,d);e.run('feed(__c)');};
  for(let i=0;i<20;i++){e.advance(1000);k(0x300,[1]);}e.advance(3000);k(0x300,[1]);eq(e.run('ids.get(0x300).gapN'),0,'1 Hz no gap');
  for(let i=0;i<40;i++){e.advance(100);k(0x211,[1,2,3]);}e.advance(1200);k(0x211,[1,2,3]);eq(e.run('ids.get(0x211).gapN'),1,'10 Hz gap');
  eq(e.run('stalledIds(nowMs()).length'),0,'nothing stalled');
});
test('gap rule: disconnect/reconnect and bitrate-detect silence are not counted as gaps',()=>{
  const e=makeEnv({fakeClock:true});e.setNow(1000);e.run('link={};connAt=nowMs()');const k=()=>{e.ctx.__c=frame(0x100,[0,0,0,0,4,0xB0,0,0]);e.run('feed(__c)');};
  for(let i=0;i<200;i++){e.advance(10);k();}
  e.run('setConn(false)');e.advance(5000);e.run('setConn(true)');
  for(let i=0;i<200;i++){e.advance(10);k();}
  eq([e.run('H.stale'),e.run('ids.get(0x100).gapN')],[0,0],'reconnect');
  e.run('markDiscontinuity()');e.advance(4000);for(let i=0;i<50;i++){e.advance(10);k();}eq(e.run('H.stale'),0,'detect trial');
});

test('real gear-1 test: neutral bit set in neutral, cleared in 1st; REAR wheel 5.0 while FRONT 0.0',()=>{
  const e=mk(),g=cap(GEAR1);
  feed(e,g,0,1.0);e.run('renderHunter()');
  eq([txt(e,'wa'),txt(e,'wb')],['0.0','0.0'],'wheels in neutral');ok(e.els.pNe.classList.contains('on'),'neutral pill on');ok(!e.els.pB0.classList.contains('on'),'B0 bit0 off (engine running)');
  const eng=+txt(e,'engT'),intk=+txt(e,'intT');ok(eng>=55&&eng<=58,'engine temp '+eng);ok(intk>=35&&intk<=38,'intake temp '+intk);ok(/^13\.[78]$/.test(txt(e,'batV')),'battery '+txt(e,'batV'));
  feed(e,g,1.0,3.1);e.run('renderHunter()');
  ok(!e.els.pNe.classList.contains('on'),'neutral pill cleared in 1st gear');eq([txt(e,'wa'),txt(e,'wb')],['0.0','5.0'],'front 0.0, rear 5.0');
  eq(e.run('heroVals(nowMs()).spdSrc'),'front wheel','hero speed source label');
});
test('real captures: B0 bit0 off in front-brake capture (engine running), on all the time with the engine off',()=>{
  const e=mk();feed(e,cap(FB),0,all);e.run('renderHunter()');ok(!e.els.pB0.classList.contains('on'),'front-brake capture');
  const o=mk();feed(o,cap(OFF),0,all);o.run('renderHunter()');ok(o.els.pB0.classList.contains('on'),'engine-off capture');
  near(+txt(o,'batV'),12.4,0.11,'engine-off battery V');
});
test('labels: tiles, pills, KNOWN, no stale front-brake / provisional text',()=>{
  const h=fs.readFileSync(path.join(ROOT,'index.html'),'utf8');
  for(const s of ['Front wheel km/h','Rear wheel km/h','Engine temp','Intake temp','Battery V <span class="tag t-ok">strong evidence','B0 bit0 (engine-off?)','Neutral (likely)'])ok(h.includes(s),'missing '+s);
  ok(!/provisional/.test(h)&&!/id="pFb"/.test(h)&&!/Wheel B km\/h/.test(h),'old labels gone');
  const e=makeEnv({fakeClock:true});const K=JSON.parse(e.run('JSON.stringify(KNOWN)'));
  eq(K['0x80 B0'],'unverified: on when engine off (was front brake)');ok(/^Rear wheel/.test(K['0x12B B2'])&&/^Front wheel/.test(K['0x12B B0']),'wheel labels');
  ok(/Battery/.test(K['0x211 B2'])&&/Engine temp/.test(K['0x211 B0'])&&/Intake/.test(K['0x211 B1'])&&/neutral/.test(K['0x80 B7']),'0x211/0x80 labels');
  ok(/not found on CAN|were not found on CAN/.test(h)&&/horn/i.test(h)&&/pass\/flash/i.test(h)&&/kill switch/i.test(h),'test-mode note kept');
});

test('battery thresholds: engine off / running / cranking zones and boundaries',()=>{
  const e=makeEnv({fakeClock:true}),r=c=>JSON.parse(e.run('JSON.stringify('+c+')'));
  eq([12.7,12.6,12.4,12.2,12.1,11.8,11.7].map(v=>r('batOff('+v+')').label),['Good','Good','Fair','Fair','Low','Low','Poor']);
  eq([13.8,13.5,14.8,13.2,15.0,12.9,15.3].map(v=>r('batRun('+v+')').label),['Charging OK','Charging OK','Charging OK','Check charging','Check charging','Low charge','Overcharge']);
  eq([11.2,9.6,9.3,9.0,8.9].map(v=>r('batCrank('+v+')').label),['Start: good','Start: good','Start: weak','Start: weak','Start: poor battery']);
  eq([r('batOff(12.7)').level,r('batOff(12.4)').level,r('batOff(11.0)').level,r('batRun(16)').level],['ok','warn','bad','bad']);
  ok(/unverified for this bike/.test(e.run('BAT_T.TAG')),'threshold block tagged');
});
test('battery state on the real ignition capture: running -> engine just stopped -> off -> cranking -> running',()=>{
  const e=mk(),g=cap(IGN);let v;
  feed(e,g,0,1.0);v=bv(e);eq([v.state,v.label],['RUNNING','Check charging'],'t=1 (13.4 V)');near(v.volts,13.4,0.11);
  feed(e,g,1.0,3.0);eq(bv(e).state,'SETTLING','RPM 0 under 2 s');
  feed(e,g,3.0,4.6);v=bv(e);eq([v.state,v.label,v.level],['OFF','Fair','warn'],'t=4.6 (12.5 V)');near(v.volts,12.5,0.11);
  feed(e,g,4.6,5.5);v=bv(e);eq(v.state,'CRANKING');ok(v.crankMin<11.6,'crank min so far '+v.crankMin);
  feed(e,g,5.5,all);v=bv(e);near(v.crankMin,11.2,0.11,'cranking min');eq([v.crankLabel,v.crankLevel],['Start: good','ok']);
  eq([v.state,v.label],['RUNNING','Low charge'],'end: 12.9 V while still recovering');near(v.vMin,11.2,0.05);near(v.vMax,13.6,0.05);
  e.run('renderHunter()');ok(/Cranking min: 11\.[12] V\s+Start: good/.test(txt(e,'batCrank')),txt(e,'batCrank'));ok(/Low charge/.test(txt(e,'batState')),'state text');
});
test('battery state on real running (~13.8 V), engine-off (~12.4 V) and pass/flash (load dip) captures',()=>{
  let e=mk();feed(e,cap(HORN),0,all);let v=bv(e);eq([v.state,v.label,v.level],['RUNNING','Charging OK','ok']);near(v.volts,13.8,0.11);
  e=mk();feed(e,cap(OFF),0,all);v=bv(e);eq([v.state,v.label],['OFF','Fair']);near(v.volts,12.4,0.11);
  e=mk();feed(e,cap(KILL),0,all);v=bv(e);eq(v.state,'OFF');near(v.volts,12.3,0.11);
  e=mk();feed(e,cap(PASS),0,all);v=bv(e);ok(v.vMin<=13.0&&v.vMax>=13.7,'pass/flash dip '+v.vMin+'..'+v.vMax);
});
test('battery: stale data, no RPM, stall blip, and non-Hunter profile',()=>{
  const e=makeEnv({fakeClock:true});e.setNow(10000);
  eq(bv(e).state,'NO_DATA');e.run('batVolts(12.4,10000)');eq(bv(e).state,'NO_RPM');
  e.run('batRpm(1000,10000);batVolts(13.9,10010);batRpm(0,10500);batRpm(900,10900)');eq(e.run('BAT.crankStart'),-1,'RPM back within 2 s is not a start');
  e.setNow(20000);eq(bv(e).state,'NO_DATA','stale voltage');
  const c=makeEnv({fakeClock:true});c.run('cfg.profile="car"');c.setNow(1000);c.ctx.__c=frame(0x211,[1,2,0x7F,0,0,0,0,0]);c.run('feed(__c)');ok(isNaN(c.run('BAT.v')),'car profile ignores 0x211');
});
test('HEALTH footer carries vmin/vmax/crankmin',()=>{
  const e=mk();e.run('batResetMinMax()');feed(e,cap(IGN),0,all);const h=e.run('healthLine()');
  ok(h.startsWith('# HEALTH bad=0')&&/ gaps=0 /.test(h)&&/ vmin=11\.2 vmax=13\.6 crankmin=11\.[12]$/.test(h),h);
  const n=makeEnv({fakeClock:true});ok(!/vmin/.test(n.run('healthLine()')),'no voltage -> no vmin');
  return h;
});
test('app version strings and service-worker cache bumped to 3.1',()=>{
  ok(/V="hcl-v3\.1"/.test(fs.readFileSync(path.join(ROOT,'sw.js'),'utf8')),'sw cache');
  ok(/APPV="3\.1"/.test(fs.readFileSync(path.join(ROOT,'index.html'),'utf8')),'APPV');
});
test('analyzer: new labels, battery/engine/intake ranges, real-gap count, on all new and old captures',()=>{
  const out=cp.execFileSync('python3',['-I',ANALYZER,...fs.readdirSync(DATA).map(f=>path.join(DATA,f))],{encoding:'utf8',maxBuffer:1<<26});
  for(const s of ['Front wheel km/h','Rear wheel','Engine temp (0x211 B0 raw-40, likely)','Intake temp (0x211 B1 raw-40, likely)','Battery V (0x211 B2 x0.1, strong evidence)','unverified: on when engine off','Real gaps'])ok(out.includes(s),'analyzer output lacks '+s);
  ok(/Battery V .*: 11\.2\.\.13\.6 V/.test(out),'ignition battery range');ok(!/provisional/.test(out)&&!/front brake on/.test(out),'old analyzer text gone');
  const data=path.join(__dirname,'data');cp.execFileSync('python3',['-I',ANALYZER,path.join(data,'sample.txt'),path.join(data,'synth.txt')],{encoding:'utf8',maxBuffer:1<<26});
  return 'ok';
});

const fails=results.filter(r=>!r.ok);console.log('\n'+(results.length-fails.length)+'/'+results.length+' passed');
fs.writeFileSync(path.join(__dirname,'results_signals.json'),JSON.stringify(results,null,1));process.exit(fails.length?1:0);
