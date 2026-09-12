// web 调试页自测：实际内联 JS + 桩 DOM + 受控 fetch（无真实服务端/Kotlin）。
// 对应 GPT 反例复现包 reproduce_web.cjs（针对 7f189e9）的三个缺陷，
// 修复后改写为正确性断言：
//   1. fixture 正确性：致谢/道歉按钮的感知目标必须指向 listener（路由要求直接指向角色）
//   2. 请求串行化：并发 submit 时第二个请求必须携带第一个响应的检查点（不再同基点并发丢更新）
//   3. 重置竞态：reset 后到期的旧请求响应必须被丢弃，不得复活旧会话
// 运行：node plugins/modulator/web/selftest.cjs
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

const nodes = new Map();
function node() {
  return {
    style: {}, dataset: {}, children: [],
    appendChild(x) { this.children.push(x); },
    querySelector() { return node(); },
  };
}
const pending = [];
const ctx = vm.createContext({
  console,
  document: {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    createElement: node,
    querySelectorAll() { return []; },
  },
  fetch: async (url, init) => new Promise(resolve => pending.push({ url, body: JSON.parse(init.body), resolve })),
});
vm.runInContext(script, ctx);

const tick = () => new Promise(r => setImmediate(r));
function reply(req, checkpoint, at = 0, withDecision = false) {
  const r = { ok: true, checkpoint, state: { at, fast: [0, .2, 0, 0], mood: [0, .2, 0, 0], traces: [] } };
  if (withDecision) r.decision = { rows: [{ aspectZh: '当前感受', stateZh: '测试', intensityZh: '中等' }], audit: [] };
  req.resolve({ json: async () => r });
}

(async () => {
  // 页面加载时的自动 reset（经队列入队，先排空微任务）
  await tick(); await tick();
  assert.equal(pending.length, 1, '页面加载应发出初始 reset 请求');
  reply(pending.shift(), 'base');
  await tick();
  assert.equal(vm.runInContext('S.checkpoint', ctx), 'base');

  // 1. fixture 正确性：直接指向角色的路由（kindness/repair）要求 target=listener
  const fixtures = JSON.parse(vm.runInContext(
    'JSON.stringify(PERCEPTION_EVENTS.map(([name, build]) => ({ name, perception: build() })))', ctx));
  const byName = Object.fromEntries(fixtures.map(f => [f.name, f.perception]));
  assert.equal(byName['温柔致谢'].speakerAffectTarget.value, 'listener', '温柔致谢必须指向 listener');
  assert.equal(byName['道歉修复'].speakerAffectTarget.value, 'listener', '道歉修复必须指向 listener');

  // 2. 并发 submit 串行化：第二个请求携带第一个响应的检查点
  const p1 = vm.runInContext('submit("process", { observation: observation(sample()) }, "first")', ctx);
  await tick(); await tick();
  const p2 = vm.runInContext('submit("process", { observation: observation(sample()) }, "second")', ctx);
  await tick(); await tick();
  assert.equal(pending.length, 1, '排队期间任一时刻只有一个在途请求');
  assert.equal(pending[0].body.checkpoint, 'base', '第一个请求携带当前检查点');
  reply(pending.shift(), 'base+first', 0, true);
  await p1;
  await tick(); await tick();
  assert.equal(vm.runInContext('S.checkpoint', ctx), 'base+first');
  assert.equal(pending.length, 1, '第一个完成后第二个才发出');
  assert.equal(pending[0].body.checkpoint, 'base+first', '第二个请求必须携带第一个响应的检查点');
  reply(pending.shift(), 'base+second', 0, true);
  await p2;
  assert.equal(vm.runInContext('S.checkpoint', ctx), 'base+second');
  assert.equal(vm.runInContext('S.history.length', ctx), 2, '两轮都进入历史');

  // 3. 重置竞态：reset 后到期的旧响应必须被丢弃
  const pOld = vm.runInContext('submit("process", { observation: observation(sample()) }, "old")', ctx);
  await tick(); await tick();
  const oldReq = pending.shift();
  const r = vm.runInContext('reset()', ctx);
  await tick(); await tick();
  reply(oldReq, 'resurrected-old-session', 0, true);
  await pOld; // 旧响应：epoch 已过期，必须被丢弃
  assert.equal(vm.runInContext('S.checkpoint', ctx), null, '过期响应不得复活旧检查点');
  assert.equal(vm.runInContext('S.history.length', ctx), 0, '过期响应不得写入历史');
  await tick(); await tick();
  assert.equal(pending.length, 1, 'reset 请求在队列中发出');
  reply(pending.shift(), 'fresh-session');
  await r;
  assert.equal(vm.runInContext('S.checkpoint', ctx), 'fresh-session', '重置后使用新会话检查点');

  console.log('selftest: 3/3 组正确性断言通过（fixture / 串行化 / 重置竞态）');
})().catch(e => { console.error('selftest FAILED:', e.message); process.exit(1); });
