// vm harness: runs the app's inline script in a Node vm with DOM / IndexedDB / localStorage / serial mocks.
const vm=require('vm'),fs=require('fs'),path=require('path');
const ROOT=path.resolve(__dirname,'..');
function extractApp(){const h=fs.readFileSync(path.join(ROOT,'index.html'),'utf8');const m=/<script id="app">([\s\S]*?)<\/script>/.exec(h);if(!m)throw new Error('app script not found');return m[1];}
function mkEl(){
  const cls=new Set();
  const o={children:[],style:{},dataset:{},hidden:false,value:"",textContent:"",_h:"",disabled:false,open:false,attrs:{},
    classList:{add:c=>cls.add(c),remove:c=>cls.delete(c),contains:c=>cls.has(c),toggle(c,f){(f===undefined?!cls.has(c):f)?cls.add(c):cls.delete(c);}},
    appendChild(c){o.children.push(c);},addEventListener(){},click(){},remove(){},focus(){},scrollTo(){},
    setAttribute(k,v){o.attrs[k]=v;},getAttribute:k=>o.attrs[k],querySelector:()=>null,querySelectorAll:()=>[],closest:()=>null};
  Object.defineProperty(o,'innerHTML',{get:()=>o._h,set(v){o._h=v;o.children.length=0;}});
  return o;
}
function makeEnv(opts={}){
  const els={},docEls={};
  let T=10000,fake=!!opts.fakeClock;
  const perf={now:()=>fake?T:require('perf_hooks').performance.now()};
  const document={getElementById:id=>els[id]||(els[id]=mkEl()),createElement:()=>mkEl(),body:{appendChild(){}},
    querySelectorAll:sel=>docEls[sel]||[],addEventListener(){},visibilityState:'visible'};
  const store={chunks:new Map(),meta:new Map()};
  const indexedDB={open(){const r={};setTimeout(()=>{
    const db={createObjectStore(){},transaction(names){const tx={};
      tx.objectStore=n=>({put(v,k){store[n].set(k,JSON.parse(JSON.stringify(v)));setTimeout(()=>tx.oncomplete&&tx.oncomplete(),0);},
        clear(){store[n].clear();setTimeout(()=>tx.oncomplete&&tx.oncomplete(),0);},
        openCursor(){const q={};const it=[...store[n].entries()];let i=0;
          const step=()=>{setTimeout(()=>{q.result=i<it.length?{key:it[i][0],value:it[i][1],continue(){i++;step();}}:null;q.onsuccess&&q.onsuccess();},0);};step();return q;}});
      return tx;}};
    r.result=db;r.onupgradeneeded&&r.onupgradeneeded();r.onsuccess&&r.onsuccess();},0);return r;}};
  const ls=new Map(Object.entries(opts.ls||{}));
  const localStorage={getItem:k=>ls.has(k)?ls.get(k):null,setItem:(k,v)=>ls.set(k,String(v))};
  const wrote=[];const downloads=[];
  const navigator={wakeLock:{request:async()=>({})}};
  if(opts.port){const port=opts.port;port.wrote=wrote;navigator.serial={requestPort:async()=>port,getPorts:async()=>port.down?[]:[port],addEventListener(){}};}
  const ctx=vm.createContext({document,navigator,indexedDB,localStorage,console,performance:perf,TextDecoder,TextEncoder,setTimeout,setInterval,clearInterval,clearTimeout,
    URL:{createObjectURL:()=>"blob:x",revokeObjectURL(){}},Blob:class{constructor(p){this.p=p;}}});
  vm.runInContext(extractApp(),ctx,{filename:'index.html<script>'});
  const elsP=new Proxy(els,{get:(t,k)=>typeof k==='string'?(t[k]||(t[k]=mkEl())):t[k]});
  return {ctx,els:elsP,docEls,store,ls,wrote,run:c=>vm.runInContext(c,ctx),
    setNow:t=>{fake=true;T=t;},advance:d=>{T+=d;},now:()=>perf.now(),sleep:ms=>new Promise(r=>setTimeout(r,ms))};
}
// Mock CANable. gen(port) returns the text for one read. Parses commands written to it.
class Port{
  constructor(gen){this.gen=gen||(()=>"");this.rate=null;this.opened=false;this.silent=false;this.cmds=[];this.fail=false;this.down=false;this.pos=0;}
  getInfo(){return {usbVendorId:0x16D0,usbProductId:0x117E};}
  async open(){if(this.down)throw new Error('no device');this.fail=false;const self=this;
    this.readable={getReader(){return {async read(){await new Promise(r=>setTimeout(r,5));if(self.fail)throw new Error('device lost');
      return {value:new TextEncoder().encode(self.opened?self.gen(self):""),done:false};},releaseLock(){}};}};
    this.writable={getWriter(){return {write(b){const t=new TextDecoder().decode(b);self.wrote&&self.wrote.push(t);
      for(const c of t.split('\r').filter(Boolean)){self.cmds.push(c);if(c==='C')self.opened=false;else if(/^S[0-8]$/.test(c))self.rate=c;else if(c==='M1')self.silent=true;else if(c==='O')self.opened=true;}
      return Promise.resolve();},releaseLock(){}};}};}
  async close(){}
}
const hex=(n,w)=>n.toString(16).toUpperCase().padStart(w,'0');
const frame=(id,bytes,ext)=>(ext?'T'+hex(id,8):'t'+hex(id,3))+bytes.length+bytes.map(b=>hex(b,2)).join('')+'\r';
module.exports={makeEnv,Port,frame,hex,ROOT,extractApp};
