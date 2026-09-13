// Reference extract of the minified perception pipeline from
// docs/AprilPerceptionDemo-standalone-2.1.4.html
// (SHA-256 a7dd693856d4c686690f3907f37c732f4af4017c9d19aef9665f18cc3303bd9c).
// Verbatim copy for porting review; offsets are char ranges in app inline script #6.
function ji({text:e,speaker:t="speaker_0",context:r=[]}){let n=r.slice(-3).map(a=>`[unused1]${a.speaker}:${a.text}`);return n.push(`[unused2]${t}:${e}`),n.join("
