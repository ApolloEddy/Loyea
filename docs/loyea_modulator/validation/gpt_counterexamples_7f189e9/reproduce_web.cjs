// Runs the branch's actual inline JavaScript with a controlled transport and DOM.
// No Kotlin server is simulated or claimed to have been executed.
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const root = process.argv[2] || __dirname + '/source';
const html = fs.readFileSync(root + '/plugins/modulator/web/index.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const nodes = new Map();
function node() { return {style:{}, dataset:{}, children:[], appendChild(x){this.children.push(x)}, querySelector(){return node()}}; }
const pending = [];
const ctx = vm.createContext({console, document:{getElementById(id){if(!nodes.has(id))nodes.set(id,node()); return nodes.get(id)}, createElement:node, querySelectorAll(){return []}}, fetch:async(url, init)=>new Promise(resolve=>pending.push({body:JSON.parse(init.body),resolve}))});
vm.runInContext(script, ctx);
function reply(req, checkpoint, at=0, withDecision=false) {
  const r={ok:true,checkpoint,state:{at,fast:[0,.2,0,0],mood:[0,.2,0,0],traces:[]}};
  if(withDecision)r.decision={rows:[{aspectZh:'当前感受',stateZh:'生气',intensityZh:'较强'}],audit:[]};
  req.resolve({json:async()=>r});
}
const tick = () => new Promise(r=>setImmediate(r));
(async()=>{
  reply(pending.shift(),'base'); await tick();
  const fixtures = JSON.parse(vm.runInContext('JSON.stringify(PERCEPTION_EVENTS.map(([name,build])=>({name,perception:build()})))',ctx));
  const p1=vm.runInContext('submit("process",{observation:observation(sample())},"first")',ctx);
  const p2=vm.runInContext('submit("process",{observation:observation(sample())},"second")',ctx);
  const [r1,r2]=pending.splice(0);
  assert.equal(r1.body.checkpoint,'base'); assert.equal(r2.body.checkpoint,'base');
  reply(r1,'base+first',0,true); await p1;
  reply(r2,'base+second',0,true); await p2;
  const concurrent={sentCheckpoints:[r1.body.checkpoint,r2.body.checkpoint],finalCheckpoint:vm.runInContext('S.checkpoint',ctx),history:vm.runInContext('S.history.length',ctx)};
  assert.equal(concurrent.history,2); assert.equal(concurrent.finalCheckpoint,'base+second');
  const tableBefore=nodes.get('table').innerHTML;
  const idle=vm.runInContext('submit("idle",{at:3600})',ctx);
  reply(pending.shift(),'after-idle',3600); await idle;
  const stale={tableUnchanged:nodes.get('table').innerHTML===tableBefore,title:nodes.get('table-title').textContent};
  assert(stale.tableUnchanged); assert(stale.title.includes('1.0 小时'));
  const old=vm.runInContext('submit("process",{observation:observation(sample())},"old")',ctx), oldReq=pending.shift();
  const reset=vm.runInContext('reset()',ctx); reply(pending.shift(),'fresh-session'); await reset;
  reply(oldReq,'resurrected-old-session',0,true); await old;
  const resetRace={finalCheckpoint:vm.runInContext('S.checkpoint',ctx)};
  assert.equal(resetRace.finalCheckpoint,'resurrected-old-session');
  console.log(JSON.stringify({method:'actual inline JS; stub DOM; controlled fetch responses',fixtures,concurrent,stale,resetRace},null,2));
})();
