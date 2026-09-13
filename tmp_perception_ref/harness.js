// Golden reference harness: runs the ORIGINAL perception pipeline JS from
// AprilPerceptionDemo-standalone-2.1.4.html (verbatim slice) under Node.
// Usage: node harness.js <command> <args...>
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const PIPELINE = fs.readFileSync(path.join(__dirname, 'pipeline.js'), 'utf8');
(0, eval)(PIPELINE); // defines qo..mt..xa..Fi..hn..ji as globals (non-strict indirect eval)

const tokenizer = new mt(
  JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/assets/perception/tokenizer.json'), 'utf8')),
  96,
);
const calibration = JSON.parse(
  fs.readFileSync(path.join(ROOT, 'app/src/main/assets/perception/calibration.json'), 'utf8'),
);

function encode(joined) {
  const enc = tokenizer.encode(joined, 96);
  return {
    inputIds: Array.from(enc.inputIds, Number),
    attentionMask: Array.from(enc.attentionMask, Number),
    tokens: enc.tokens,
  };
}

// deterministic PRNG for synthetic logits (mulberry32)
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function decodeWithGrammar(logits, text) {
  const decoded = Fi(logits, { calibration, tuning: { default: { temperature: 1, bias: 0, policyWeight: 1 }, labels: {} } });
  return hn(decoded, { text, speaker: 'speaker_0', context: [] });
}

const cmd = process.argv[2];
if (cmd === 'tokenizer-golden') {
  const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'tokenizer_cases.json'), 'utf8'));
  const out = [];
  for (const c of cases) {
    const joined = ji({ text: c.text, speaker: c.speaker || 'speaker_0', context: c.context || [] });
    const enc = encode(joined);
    out.push({ ...c, joined, inputIds: enc.inputIds, attentionMask: enc.attentionMask, tokens: enc.tokens });
  }
  fs.writeFileSync(path.join(__dirname, 'tokenizer_golden.json'), JSON.stringify({ version: '2.1.4-pipeline', cases: out }, null, 1));
  console.log('tokenizer_golden.json written:', out.length, 'cases');
} else if (cmd === 'decoder-golden') {
  const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'decoder_cases.json'), 'utf8'));
  const out = [];
  cases.forEach((c, i) => {
    const rng = mulberry32(c.seed ?? 1234 + i * 7919);
    const logits = c.logits ?? Array.from({ length: 82 }, () => rng() * 14 - 6);
    const decoded = decodeWithGrammar(logits, c.text);
    decoded.logits = logits;
    decoded.text = c.text;
    out.push(decoded);
  });
  fs.writeFileSync(path.join(__dirname, 'decoder_golden.json'), JSON.stringify({ version: '2.1.4-pipeline', cases: out }, null, 1));
  console.log('decoder_golden.json written:', out.length, 'cases');
} else if (cmd === 'realmodel-encode') {
  const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'realmodel_cases.json'), 'utf8'));
  const out = [];
  for (const c of cases) {
    const joined = ji({ text: c.text, speaker: c.speaker || 'speaker_0', context: c.context || [] });
    const enc = encode(joined);
    out.push({ ...c, joined, inputIds: enc.inputIds, attentionMask: enc.attentionMask, tokens: enc.tokens });
  }
  fs.writeFileSync(path.join(__dirname, 'realmodel_ids.json'), JSON.stringify(out));
  console.log('realmodel_ids.json written:', out.length);
} else if (cmd === 'realmodel-golden') {
  // logits captured from the real ONNX model (via Python onnxruntime)
  const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'realmodel_logits.json'), 'utf8'));
  const out = [];
  for (const c of cases) {
    const decoded = decodeWithGrammar(c.logits, c.text);
    out.push({ text: c.text, context: c.context || [], speaker: c.speaker || 'speaker_0', logits: c.logits, decoded });
  }
  fs.writeFileSync(path.join(__dirname, 'realmodel_golden.json'), JSON.stringify({ version: '2.1.4-pipeline', cases: out }, null, 1));
  console.log('realmodel_golden.json written:', out.length, 'cases');
} else {
  console.log('unknown command');
  process.exit(2);
}
