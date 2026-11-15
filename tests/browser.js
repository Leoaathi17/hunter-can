// Real-browser render + DOM smoke test (headless Chrome via playwright). Mock serial port, no hardware.
const path=require('path');
let pw;try{pw=require('playwright');}catch(e){pw=require('/opt/node22/lib/node_modules/playwright');}
const fs=require('fs');
const root=path.resolve(__dirname,'..');
const out=process.env.SHOTS||'/tmp/shots';fs.mkdirSync(out,{recursive:true});
const sample=fs.readFileSync(path.join(__dirname,'data','sample.txt'),'latin1');
let fails=0;const ok=(n,c,d)=>{console.log((c?'PASS':'FAIL')+' '+n+(d?' - '+d:''));if(!c)fails++;};
(async()=>{
  const browser=await pw.chromium.launch({executablePath:process.env.CHROME||'/usr/bin/google-chrome',args:['--no-sandbox']});
  const errs=[];
  async function open(w,h,opts={}){
    const ctx=await browser.newContext({viewport:{width:w,height:h},deviceScaleFactor:2,isMobile:true,hasTouch:true,reducedMotion:opts.rm?'reduce':'no-preference'});
    const page=await ctx.newPage();
    page.on('pageerror',e=>errs.push(String(e)));page.on('console',m=>{if(m.type()==='error')errs.push(m.text());});
    await page.addInitScript(({sample})=>{
      const written=[];window.__written=written;
      let pos=0;
      const port={getInfo:()=>({usbVendorId:0x16D0,usbProductId:0x117E}),async open(){},async close(){},
        readable:{getReader:()=>({async read(){await new Promise(r=>setTimeout(r,8));const c=sample.slice(pos,pos+400);pos=(pos+400)%(sample.length-400);return {value:new TextEncoder().encode(c),done:false};},releaseLock(){}})},
        writable:{getWriter:()=>({write(b){written.push(new TextDecoder().decode(b));return Promise.resolve();},releaseLock(){}})}};
      Object.defineProperty(navigator,'serial',{value:{requestPort:async()=>port,getPorts:async()=>[port],addEventListener(){}}});
      navigator.wakeLock={request:async()=>({})};
    },{sample});
    await page.goto('file://'+root+'/index.html');
    return {ctx,page};
  }
  // portrait phone
  let {ctx,page}=await open(390,844);
  await page.screenshot({path:out+'/01_live_onboarding.png'});
  ok('onboarding visible first run',await page.locator('#onb').isVisible());
  await page.click('#bOnbOk');
  ok('onboarding dismissed',!(await page.locator('#onb').isVisible()));
  await page.click('#bConn');await page.waitForTimeout(1800);
  ok('chip shows connected',/Hunter · 500k/.test(await page.textContent('#st')),await page.textContent('#st'));
  const w=await page.evaluate(()=>window.__written);ok('setup commands sent C,S6,M1,O',JSON.stringify(w)===JSON.stringify(['C\r','S6\r','M1\r','O\r']),JSON.stringify(w));
  await page.screenshot({path:out+'/02_live_hunter.png'});
  ok('hunter card visible on hunter',await page.locator('[data-prof="hunter"]').first().isVisible());
  ok('obd card hidden on hunter',!(await page.locator('[data-prof="car custom"]').isVisible()));
  // tab sizes: touch targets
  const sizes=await page.evaluate(()=>[...document.querySelectorAll('#tabs button,.btn,select,input')].filter(e=>e.offsetParent).map(e=>{const r=e.getBoundingClientRect();return [e.id||e.textContent.trim().slice(0,14),Math.round(r.width),Math.round(r.height)];}));
  const small=sizes.filter(s=>s[2]<44&&s[0]);ok('visible controls >=44px high (live tab)',small.length===0,JSON.stringify(small));
  for(const t of ['record','identify','health','settings']){await page.click(`#tabs button[data-t=${t}]`);await page.waitForTimeout(700);
    const sz=await page.evaluate(()=>[...document.querySelectorAll('[data-tab]:not([hidden]) .btn,[data-tab]:not([hidden]) select,[data-tab]:not([hidden]) input:not([type=file]),#tabs button')].filter(e=>e.offsetParent).map(e=>{const r=e.getBoundingClientRect();return [e.id||e.textContent.trim().slice(0,14),Math.round(r.height)];}));
    const sm=sz.filter(s=>s[1]<44);ok('touch targets >=44px on '+t,sm.length===0,JSON.stringify(sm));
    await page.screenshot({path:out+`/03_${t}.png`,fullPage:false});}
  // switch to car profile
  await page.click('#tabs button[data-t=settings]');
  await page.click('#profPick button[data-p=car]');
  await page.click('#tabs button[data-t=live]');await page.waitForTimeout(400);
  ok('car: hunter card hidden',!(await page.locator('[data-prof="hunter"]').isVisible()));
  ok('car: obd card visible',await page.locator('[data-prof="car custom"]').isVisible());
  const txt=await page.evaluate(()=>document.body.innerText);
  ok('car: no Hunter signal names in visible text',!/Hunter 350 signals|Wheel A|Side stand/.test(txt));
  await page.screenshot({path:out+'/04_live_car.png'});
  // decoder add + gauge
  await page.click('#tabs button[data-t=identify]');
  await page.evaluate(()=>{document.querySelectorAll('details.card').forEach(d=>d.open=true);});
  await page.fill('#dId','12B');await page.fill('#dStart','2');await page.fill('#dLen','2');await page.fill('#dScale','0.1');await page.fill('#dName','Probe');await page.fill('#dUnit','km/h');
  await page.click('#bDecAdd');
  await page.click('#tabs button[data-t=live]');await page.waitForTimeout(1200);
  ok('user gauge rendered',await page.locator('#ugCard').isVisible());
  await page.screenshot({path:out+'/05_live_car_gauge.png'});
  await page.click('#tabs button[data-t=health]');await page.waitForTimeout(700);
  await page.screenshot({path:out+'/06_health.png'});
  // landscape
  await page.setViewportSize({width:844,height:390});await page.click('#tabs button[data-t=live]');await page.waitForTimeout(500);
  await page.screenshot({path:out+'/07_landscape_live.png'});
  const nav=await page.evaluate(()=>{const r=document.getElementById('tabs').getBoundingClientRect();return [Math.round(r.width),Math.round(r.height)];});
  ok('landscape: nav becomes side rail',nav[0]<120&&nav[1]>200,JSON.stringify(nav));
  const noScrollX=await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1);ok('no horizontal page overflow (landscape)',noScrollX);
  await ctx.close();
  // narrow 360 and reduced motion
  ({ctx,page}=await open(360,740,{rm:true}));
  await page.click('#bOnbOk');
  const ovf=await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1);ok('no horizontal overflow at 360px',ovf);
  const anim=await page.evaluate(()=>{const d=document.querySelector('.chip .dot');return getComputedStyle(d).animationName;});ok('reduced motion disables animation',anim==='none',anim);
  await page.screenshot({path:out+'/08_360_reduced.png'});
  await ctx.close();
  // ---- v3: adapter picker, Diagnostics enable flow, mode banner (mock serial, no ECU) ----
  ({ctx,page}=await open(390,844));
  await page.click('#bOnbOk');
  await page.click('#bConn');await page.waitForTimeout(1500);
  ok('v3 monitor banner visible and read-only',/MONITOR/.test(await page.textContent('#modeTxt'))&&await page.locator('#modeBar').isVisible());
  ok('v3 Diag tab hidden by default',!(await page.locator('#tabs button[data-t=diag]').isVisible()));
  await page.click('#tabs button[data-t=settings]');await page.waitForTimeout(300);
  const opts=await page.evaluate(()=>[...document.querySelectorAll('#adSel option')].map(o=>o.value));
  ok('v3 adapter picker lists only supported adapters (Web Serial mocked; real Chrome offers USB/BLE too)',opts.includes('slcan-serial')&&opts.includes('elm-serial')&&opts.every(v=>['slcan-serial','slcan-usb','gsusb','elm-serial','elm-ble','ws','ble-slcan'].includes(v)),JSON.stringify(opts));
  await page.screenshot({path:out+'/09_settings_adapter.png'});
  const stS=await page.evaluate(()=>[...document.querySelectorAll('[data-tab=settings] .btn,[data-tab=settings] select,[data-tab=settings] input:not([type=file])')].filter(e=>e.offsetParent).map(e=>[e.id||e.textContent.trim().slice(0,14),Math.round(e.getBoundingClientRect().height)]).filter(x=>x[1]<44));
  ok('v3 settings touch targets >=44px',stS.length===0,JSON.stringify(stS));
  await page.click('#bDiagEnable');await page.waitForTimeout(200);
  ok('v3 warning screen shown',await page.locator('#diagWarn').isVisible());
  ok('v3 enable disabled until acknowledged',await page.locator('#bWarnOk').isDisabled());
  const stW=await page.evaluate(()=>[...document.querySelectorAll('#diagWarn .btn,#diagWarn input')].map(e=>[e.id,Math.round(e.getBoundingClientRect().height)]).filter(x=>x[1]<44));
  ok('v3 warning screen touch targets >=44px',stW.length===0,JSON.stringify(stW));
  await page.screenshot({path:out+'/10_diag_warning.png'});
  await page.click('#diagAck');ok('v3 enable button active after acknowledgement',!(await page.locator('#bWarnOk').isDisabled()));
  await page.click('#bWarnOk');await page.waitForTimeout(300);
  ok('v3 Diag tab visible after enabling',await page.locator('#tabs button[data-t=diag]').isVisible());
  ok('v3 diag panel shows standard-only and Hunter experimental text',/standard OBD-II only/.test(await page.textContent('[data-tab=diag]'))&&/experimental and unverified/.test(await page.textContent('[data-tab=diag]')));
  const before=await page.evaluate(()=>window.__written.slice());
  ok('v3 nothing but monitor setup written so far',before.join('')==='C\rS6\rM1\rO\r',JSON.stringify(before));
  await page.screenshot({path:out+'/11_diag_monitor.png'});
  await page.click('#bDiagStart');await page.waitForTimeout(900);
  ok('v3 banner switches to DIAGNOSTICS active TX',/DIAGNOSTICS/.test(await page.textContent('#modeTxt'))&&await page.locator('#bToMon').isVisible());
  ok('v3 M0 sent on entering diagnostics',(await page.evaluate(()=>window.__written)).includes('M0\r'));
  await page.click('#bDtc3');await page.waitForTimeout(2200);
  ok('v3 no-answer reported as NO DATA',/No ECU answered/.test(await page.textContent('#dgDtc3')),await page.textContent('#dgDtc3'));
  const wr=await page.evaluate(()=>window.__written);ok('v3 request frame transmitted only after M0',wr.indexOf('M0\r')>=0&&wr.findIndex(x=>/^t7DF8/.test(x))>wr.indexOf('M0\r'),JSON.stringify(wr.slice(-6)));
  await page.screenshot({path:out+'/12_diag_active.png'});
  const stD=await page.evaluate(()=>[...document.querySelectorAll('[data-tab=diag] .btn,[data-tab=diag] input,#bToMon,#tabs button')].filter(e=>e.offsetParent).map(e=>[e.id||e.textContent.trim().slice(0,14),Math.round(e.getBoundingClientRect().height)]).filter(x=>x[1]<44));
  ok('v3 diagnostics touch targets >=44px',stD.length===0,JSON.stringify(stD));
  ok('v3 no horizontal overflow on diag tab',await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));
  await page.click('#bToMon');await page.waitForTimeout(900);
  ok('v3 one tap returns to MONITOR',/MONITOR/.test(await page.textContent('#modeTxt')));
  const w2=await page.evaluate(()=>window.__written);ok('v3 M1 sent again on return',w2.lastIndexOf('M1\r')>w2.lastIndexOf('M0\r'));
  await page.setViewportSize({width:844,height:390});await page.waitForTimeout(300);await page.screenshot({path:out+'/13_diag_landscape.png'});
  ok('v3 landscape: no overflow with banner',await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));
  await page.setViewportSize({width:360,height:740});await page.waitForTimeout(200);
  ok('v3 360px: no overflow with 6 tabs',await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));
  await page.screenshot({path:out+'/14_360_diag.png'});
  await ctx.close();
  ok('no page/console errors',errs.length===0,errs.slice(0,3).join(' | '));
  await browser.close();
  console.log(fails?('FAILED '+fails):'ALL PASS');process.exit(fails?1:0);
})().catch(e=>{console.error(e);process.exit(2);});
