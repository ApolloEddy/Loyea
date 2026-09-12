"""Counterexamples against the committed Python reference, not Kotlin execution.
Usage: python3 reproduce_theory.py /path/to/repo web_results.json
The assertions confirm defects are reproducible; they are not passing acceptance gates.
"""
import sys, json
from pathlib import Path
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).parent/'source'
sys.path.insert(0,str(root/'docs/loyea_modulator/prototype'))
from modulator import Modulator, Observation, Context, Fact

def fact(kind,eid,strength=.75,**kw):
    return Fact(kind,strength,eid,verified=True,**kw)
def feelings(d):
    return [(r.state,r.intensity) for r in d.rows if r.aspect=='feeling']
def threat(m):
    return next(t for t in m.state.traces if t.kind=='future_threat')

# A and B are independent threats, in the same conversation topic.
m=Modulator()
m.process(Observation('u1',1,0,facts=(fact('future_threat','A'),fact('future_threat','B'))))
before=threat(m).strength
ids=list(threat(m).evidence_ids)
m.process(Observation('u2',2,0,facts=(fact('threat_resolved','resolve-A',resolves=('A',)),)))
after=threat(m).strength
assert ids==['A','B'] and after<before

# Hidden strong A contaminates the output for visible but weak, independent B.
a=Modulator()
a.process(Observation('u1',1,0,facts=(fact('future_threat','A',.9),)))
d=a.process(Observation('u2',2,0,Context(visible_evidence=frozenset({'B'})),facts=(fact('future_threat','B',.01),)))
b=Modulator()
control=b.process(Observation('u2',2,0,Context(visible_evidence=frozenset({'B'})),facts=(fact('future_threat','B',.01),)))
assert any(x[0]=='worry' for x in feelings(d)) and not feelings(control)
out={'method':'executed committed Python reference; matching Kotlin branches inspected',
 'independent_causes':{'merged_ids':ids,'strength_before':before,'strength_after_resolving_A_only':after,'remaining_ids':list(threat(m).evidence_ids)},
 'visibility_leak':{'only_B_visible_with_hidden_A':feelings(d),'B_alone':feelings(control),'merged_strength':threat(a).strength}}
if len(sys.argv)>2:
    fixtures={f['name']:f['perception'] for f in json.loads(Path(sys.argv[2]).read_text())['fixtures']}
    ui={}
    for label in ('温柔致谢','道歉修复'):
        def run(p):
            mod=Modulator(); mod.process(Observation('u1',1,0,perception=fixtures['敌意指向角色']))
            return feelings(mod.process(Observation('u2',2,0,perception=p)))
        p=fixtures[label]
        original=run(p)
        fixed=json.loads(json.dumps(p)); fixed['speakerAffectTarget']['value']='listener'
        repaired=run(fixed)
        assert original==[] and repaired
        ui[label]={'original':original,'with_target_listener':repaired}
    out['button_fixtures']=ui
print(json.dumps(out,ensure_ascii=False,indent=2))
