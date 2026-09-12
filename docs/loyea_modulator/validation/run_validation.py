"""Reproduce tests, bounded-load timing, golden fixtures and exact dynamics plot.

Core/tests need Python standard library. Plotting additionally uses matplotlib.
All observations are hand-authored synthetic decoder outputs, not model inference.
"""
from pathlib import Path
import csv
import hashlib
import io
import json
import math
import platform
import sys
import time
import unittest
from dataclasses import asdict

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "prototype"))
from modulator import *
from demo import sample_perception


def normal(value):
    if isinstance(value, dict):
        return {k: normal(v) for k, v in value.items()}
    if isinstance(value, (set, frozenset)):
        return sorted(normal(v) for v in value)
    if isinstance(value, (list, tuple)):
        return [normal(v) for v in value]
    return value


def write_json(relative, value):
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(normal(value), ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def configuration():
    write_json("config/defaults.json", config_dict())
    implemented = {rule.label for rule in RULES.values()}
    write_json("config/state_dictionary.json", {
        "representation_version": "0.2", "modulator_version": VERSION,
        "aspects": ASPECT_ZH, "intensity": LEVEL_ZH,
        "aspect_states": ASPECT_STATES,
        "feeling_candidates": [{"id": k, "zh": STATE_ZH[k],
            "status": "implemented" if k in implemented else "reserved",
            "routes": [key for key, rule in RULES.items() if rule.label == k]}
            for k in sorted(FEELING_IDS)],
        "note": "31词均保留定义；当前实现15词，旧感知适配覆盖7词，宿主事件补足8词。未接事件来源的扩展不会自动出现。"
    })
    base = "joy affection sadness anger fear disgust surprise neutral".split()
    factuality = "asserted negated hypothetical reported unclear".split()
    heads = {
        "basePresence": base, "baseStrength": base,
        "fineStrength": "grievance disappointment loneliness gratitude relief shame_guilt frustration anticipation".split(),
        "dialogueAct": "inform question answer request_command agree_ack disagree_reject apologize_repair thank_appreciate comfort_support complain_protest greet_close joke_irony other".split(),
        "stances": "affiliative playful coquettish hostile defensive vulnerable repairing".split(),
        "speakerAffectTarget": "self listener third_party event_object general none unclear".split(),
        "utteranceFactuality": factuality,
        "dimensions": "valence arousal dominance intensity ambiguity toxicity".split(),
        "referencePresence": ["present"], "referenceEmotion": base,
        "referenceHolder": "speaker listener third_party mixed none unclear".split(),
        "referenceFactuality": factuality,
    }
    write_json("config/legacy_schema.json", {
        "source": "AprilPerceptionDemo-standalone-2.1.4.html",
        "source_sha256": "a7dd693856d4c686690f3907f37c732f4af4017c9d19aef9665f18cc3303bd9c",
        "model_metadata_schema": "2.1.0", "decoder_schema": "2.1.1",
        "heads": heads, "head_count": len(heads), "logit_count": sum(map(len, heads.values())),
        "max_tokens": 96, "q8_bytes": 24085508,
        "input_format": "last 3 context entries: [unused1]{speaker}:{text}; current: [unused2]{speaker}:{text}; join with spaces",
        "not_included": "权重与原始HTML不重复打包；此文件是兼容性登记，不是模型实现。"
    })


def golden_cases():
    sequences = []
    mixed = [Observation(f"hostile-{n}", n, (n-1)*120,
              perception=sample_perception("anger", target="listener", hostile=.95)) for n in range(1, 40)]
    mixed.append(Observation("humor", 40, 4560, perception=sample_perception(act="joke_irony", playful=.95)))
    mixed.append(Observation("rested", 41, 4560+86400))
    cases = {
        "user_distress": [Observation("u1", 1, 0, perception=sample_perception("sadness", target="self"))],
        "third_party_anger": [Observation("u1", 1, 0, perception=sample_perception("anger", target="third_party", hostile=.95))],
        "repair_sequence": [
            Observation("u1", 1, 0, perception=sample_perception("anger", target="listener", hostile=.95)),
            Observation("u2", 2, 90, Context(visible_evidence=frozenset({"u1"})), sample_perception("anger", target="listener", hostile=.95)),
            Observation("u3", 3, 150, Context(visible_evidence=frozenset({"u1", "u2"})), sample_perception(target="listener", act="apologize_repair")),
        ],
        "explicit_relationship": [Observation("u1", 1, 0, Context(relations=RelationView("love", .8, "distrust", .6, "declared-relation")))],
        "guilt_with_evidence": [Observation("u1", 1, 0, facts=(Fact("own_harm_confirmed", .75, "own-error", verified=True),))],
        "threat_resolution": [
            Observation("u1", 1, 0, facts=(Fact("future_threat", .75, "threat", verified=True),)),
            Observation("u2", 2, 60, Context(visible_evidence=frozenset({"threat"})), facts=(Fact("threat_resolved", .75, "resolved", verified=True, resolves=("threat",)),))],
        "background_and_current": mixed,
    }
    for name, observations in cases.items():
        m = Modulator(); steps = []
        for o in observations:
            d = m.process(o)
            steps.append({"input": asdict(o), "expected": {
                "rows": d.wire(), "fast": m.state.fast[:], "mood": m.state.mood[:],
                "traces": [asdict(t) for t in m.state.traces], "audit": d.audit}})
        sequences.append({"name": name, "initial_personality": asdict(m.personality), "initial_at": 0, "steps": steps})
    write_json("integration/golden_cases.json", {"version": VERSION, "numeric_tolerance": 1e-10,
               "source": "hand-authored synthetic observations; expected values produced by reference implementation",
               "sequences": sequences})
    return sequences


def benchmark():
    p = sample_perception()
    p["emotions"] = {k: {"calibratedProbability": .02, "strength": .02} for k in
                     "joy affection sadness anger fear disgust surprise neutral".split()}
    p["emotions"]["neutral"] = {"calibratedProbability": .95, "strength": .95}
    p["stances"] = dict.fromkeys("affiliative playful coquettish hostile defensive vulnerable repairing".split(), .02)
    p["fineStates"] = dict.fromkeys("grievance disappointment loneliness gratitude relief shame_guilt frustration anticipation".split(), .02)
    kinds = [k for k, r in RULES.items() if r.source == "host" and k != "threat_resolved"]
    rules = tuple(LoreRule(f"lore-{n:03}", "这是一个用于测量筛选开销的可选条目。", n % 8, always=True, group=f"group-{n%16}") for n in range(128))
    m = Modulator(); core_times, full_times = [], []
    count, warmup = 6000, 300
    for n in range(1, count + warmup + 1):
        # Maximum admitted event count; all 8 traces remain active.
        facts = tuple(Fact(k, .55, f"e{n}-{j}", verified=True) for j, k in enumerate(kinds))
        o = Observation(f"u{n}", n, n*5, perception=p, facts=facts)
        start = time.perf_counter_ns()
        d = m.process(o)
        core_end = time.perf_counter_ns()
        select_lore(d, o.context, rules)
        end = time.perf_counter_ns()
        if n > warmup:
            core_times.append((core_end-start)/1e6)
            full_times.append((end-start)/1e6)
    def stats(values):
        values = sorted(values)
        return {f"p{q}_ms": values[math.ceil(q/100*len(values))-1] for q in (50, 95, 99)}
    return {"environment": {"python": platform.python_version(), "platform": platform.platform()},
        "samples": count, "warmup": warmup, "active_traces": len(m.state.traces),
        "events_per_turn": len(kinds), "lore_candidates": len(rules),
        "modulator": stats(core_times), "modulator_plus_lore": stats(full_times),
        "checkpoint_utf8_bytes": len(m.dumps().encode("utf-8")),
        "excluded": ["ONNX inference", "tokenization", "network", "LLM generation", "database I/O", "Android runtime", "thermal and battery tests"]}


def dynamics_plot():
    # Evaluate analytical state only; sampling points are observations of the same path.
    series = []
    m = Modulator(); seq = 0
    for at in range(0, 7201, 30):
        if at <= 2280 and at % 120 == 0:
            seq += 1
            m.process(Observation(f"u{seq}", seq, at, perception=sample_perception("anger", target="listener", hostile=.95)))
        elif at == 2400:
            seq += 1
            m.process(Observation("repair", seq, at, perception=sample_perception(target="listener", act="apologize_repair")))
        else:
            m.idle_to(at)
        series.append([at] + m.state.fast[:] + m.state.mood[:])
    with (ROOT / "validation/dynamics.csv").open("w", newline="") as f:
        writer = csv.writer(f); writer.writerow(["seconds"] + [f"fast_{x}" for x in AXES] + [f"mood_{x}" for x in AXES]); writer.writerows(series)
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return "omitted: matplotlib is optional and unavailable"
    fig, axes = plt.subplots(2, 2, figsize=(11, 6.6), sharex=True)
    for j, ax in enumerate(axes.flat):
        ax.plot([r[0]/60 for r in series], [r[j+1] for r in series], color="#26589c", lw=1.7, label="Immediate state")
        ax.plot([r[0]/60 for r in series], [r[j+5] for r in series], color="#d58249", lw=2, label="Background mood")
        ax.axvspan(0, 38, color="#e8e8e8", alpha=.55)
        ax.axvline(40, color="#418667", ls="--", lw=1)
        ax.set_title(AXES[j].capitalize(), loc="left", fontsize=12)
        ax.set_ylim(BOUNDS[j][0]-.03, BOUNDS[j][1]+.03)
        ax.grid(alpha=.18); ax.set_xlabel("Minutes"); ax.spines[["top", "right"]].set_visible(False)
    axes[0, 0].legend(loc="lower right", fontsize=9)
    fig.suptitle("Loyea modulator: exact dynamics under synthetic repeated events", fontsize=14)
    fig.text(.5, .025, "Grey: repeated hostility every 2 min. Dashed line: linked repair. Then no new input.\nEngineering simulation only; no perception model or LLM was run.", ha="center", fontsize=9, color="#555555")
    fig.tight_layout(rect=(0, .07, 1, .96)); fig.savefig(ROOT / "validation/dynamics.png", dpi=170); plt.close(fig)
    return "generated from validation/dynamics.csv"


def main():
    for name in ("config", "integration", "validation"):
        (ROOT / name).mkdir(exist_ok=True)
    suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"))
    log = io.StringIO(); started = time.perf_counter()
    result = unittest.TextTestRunner(stream=log, verbosity=2).run(suite)
    elapsed = time.perf_counter() - started
    (ROOT / "validation/test_log.txt").write_text(log.getvalue())
    if not result.wasSuccessful():
        print(log.getvalue()); raise SystemExit(1)
    configuration(); sequences = golden_cases(); timing = benchmark(); plot = dynamics_plot()
    summary = {"version": VERSION, "tests": result.testsRun, "test_seconds": elapsed,
               "passed": result.wasSuccessful(), "stress_steps": 16000,
               "golden_sequences": len(sequences), "golden_observations": sum(len(x["steps"]) for x in sequences),
               "benchmark": timing, "plot": plot}
    write_json("validation/results.json", summary)
    report = f"""# 验证记录\n\n版本：{VERSION}。所有输入均为人工构造的解码结果或宿主事件。\n\n- {result.testsRun} 项测试通过，耗时 {elapsed:.3f} 秒；完整日志见 `test_log.txt`。\n- 32 组 OCEAN 边界人格，各 500 步随机事件，共 16,000 步，检查数值范围和缓存上限。\n- 100 组随机初始状态验证时间分段一致；独立 RK4 积分验证闭式解。\n- {len(sequences)} 组跨语言回放序列，共 {sum(len(x['steps']) for x in sequences)} 次观测，保存输入、状态和期望输出。\n\n| 测量范围 | p50 | p95 | p99 |\n|---|---:|---:|---:|\n| 调制器，8事件／8活动痕迹 | {timing['modulator']['p50_ms']:.4f} ms | {timing['modulator']['p95_ms']:.4f} ms | {timing['modulator']['p99_ms']:.4f} ms |\n| 加128条Lore候选筛选 | {timing['modulator_plus_lore']['p50_ms']:.4f} ms | {timing['modulator_plus_lore']['p95_ms']:.4f} ms | {timing['modulator_plus_lore']['p99_ms']:.4f} ms |\n\n环境：Python {platform.python_version()}，{platform.platform()}；预热300次，测量6,000次。当前满缓存序列化检查点 {timing['checkpoint_utf8_bytes']} 字节，**不等于实际堆内存**。\n\n已验证：有限状态有界、闲置恢复、主客体区分规则、证据关联、重试、快照校验、离散强度滞回、输出词表和Lore预算。\n\n未验证：旧感知模型在真实中文输入上的准确率，角色自然度，Android真机耗时、发热和耗电，仓库中的持久化事务。计时不含分词、ONNX、数据库、网络和LLM。\n\n`dynamics.png` 展示重复敌意事件、一次关联修复、随后无输入的解析轨迹；它是工程仿真，不是用户对话实验。\n"""
    (ROOT / "validation/Validation_Report.md").write_text(report)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
