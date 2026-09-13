// Reference extract of the minified perception pipeline from
// docs/AprilPerceptionDemo-standalone-2.1.4.html
// (SHA-256 a7dd693856d4c686690f3907f37c732f4af4017c9d19aef9665f18cc3303bd9c).
// Verbatim copy for porting review; offsets are char ranges in app inline script #6.
Zo=/[\p{P}]/u,Jo=/\p{M}/gu;function Ko(e){return e==="	"||e===`
`||e==="\r"?!1:/\p{Cc}|\p{Cf}/u.test(e)}function Yo(e){let t=e.codePointAt(0);return t>=13312&&t<=19903||t>=19968&&t<=40959||t>=131072&&t<=195103||t>=63744&&t<=64255}function Xo(e,{lowercase:t,stripAccents:r}){let n="";for(let a of e.normalize("NFC"))a==="\0"||a==="\uFFFD"||Ko(a)||(n+=qo.test(a)?" ":a);return t&&(n=n.toLocaleLowerCase("und")),r&&(n=n.normalize("NFD").replace(Jo,"")),n}function Qo(e,t){let r=e.split(/(\[unused[12]\])/gu),n=[];for(let a of r){if(!a)continue;if(a==="[unused1]"||a==="[unused2]"){n.push(a);continue}let i="";for(let o of Xo(a,t))i+=Yo(o)?` ${o} `:o;for(let o of i.trim().split(/\s+/u)){if(!o)continue;let s="";for(let l of o)Zo.test(l)?(s&&n.push(s),n.push(l),s=""):s+=l;s&&n.push(s)}}return n}var mt=class{constructor(t,r=96){let n=t.normalizer??{};this.vocab=t.model.vocab,this.unknownId=this.vocab["
