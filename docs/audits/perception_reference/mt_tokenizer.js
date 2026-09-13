// Reference extract of the minified perception pipeline from
// docs/AprilPerceptionDemo-standalone-2.1.4.html
// (SHA-256 a7dd693856d4c686690f3907f37c732f4af4017c9d19aef9665f18cc3303bd9c).
// Verbatim copy for porting review; offsets are char ranges in app inline script #6.
var mt=class{constructor(t,r=96){let n=t.normalizer??{};this.vocab=t.model.vocab,this.unknownId=this.vocab["[UNK]"],this.clsId=this.vocab["[CLS]"],this.sepId=this.vocab["[SEP]"],this.padId=this.vocab["[PAD]"],this.maxLength=r,this.options={lowercase:!!n.lowercase,stripAccents:n.strip_accents??!!n.lowercase}}tokenize(t){let r=[];for(let n of Qo(String(t??""),this.options)){if(this.vocab[n]!==void 0){r.push(n);continue}let a=[
