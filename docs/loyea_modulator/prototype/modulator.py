"""Loyea deterministic state modulator reference, v1.0.0-prototype.

Python standard library only. Engineering defaults, not a psychological scale.
This is the executable reference contract for a pure Kotlin port; no LLM calls.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Any
import copy
import math
import json

VERSION = "1.0.0-prototype"
AXES = ("valence", "activation", "tension", "irritation")
BOUNDS = ((-1.0, 1.0), (0.0, 1.0), (0.0, 1.0), (0.0, 1.0))
FAST_HALFLIVES = (180.0, 90.0, 240.0, 300.0)
MOOD_HALFLIVES = (1800.0, 1200.0, 2400.0, 2700.0)
LEVELS = ("mild", "moderate", "strong", "very_strong")
LEVEL_ZH = {"mild": "轻微", "moderate": "中等", "strong": "强烈", "very_strong": "非常强烈", "none": "无", "—": "—"}
THRESHOLDS = (0.30, 0.55, 0.80)
ASPECT_ZH = {"relationship_affect": "关系感情", "relationship_trust": "关系信任", "mood": "背景心境", "feeling": "当前感受"}
STATE_ZH = dict(zip(
    "joy affection sadness anger fear disgust surprise grievance disappointment loneliness gratitude relief shame_guilt frustration anticipation amusement excitement interest curiosity confusion hope admiration pride compassion worry hurt guilt shame embarrassment boredom despair".split(),
    "快乐 喜爱 悲伤 愤怒 恐惧 厌恶 惊讶 委屈 失望 孤独 感激 释然 羞愧／内疚（未细分） 挫败 期待 被逗乐 兴奋 兴趣 好奇 困惑 希望 钦佩 自豪 心疼 担忧 情感受伤 内疚 羞愧 难为情 无聊 绝望".split()))
STATE_ZH.update({"neutral": "中性", "unspecified": "未确定", "aversion": "反感", "love": "爱", "trust": "信任", "distrust": "不信任", "calm": "平静", "cheerful": "愉快", "gloomy": "低落", "tense": "紧绷", "irritable": "烦躁"})
FEELING_IDS = frozenset(list(STATE_ZH)[:31])
ASPECT_STATES = {
    "relationship_affect": frozenset({"unspecified", "neutral", "affection", "aversion", "love"}),
    "relationship_trust": frozenset({"unspecified", "trust", "distrust"}),
    "mood": frozenset({"unspecified", "neutral", "calm", "cheerful", "gloomy", "tense", "irritable"}),
    "feeling": FEELING_IDS,
}
INTERPRETATION = "表格描述角色当前被显式指定的内部状态。请结合 Soul、当前情境、上下文、记忆、Lorebook 与本轮行为指令共同理解。各行可以同时成立，强度表示内在感受，不规定必须表现多少。未确定表示系统没有提供判断，未列出的状态不等于零。以本轮表格为当前显式状态，不自动沿用旧表中的遗漏条目。请自然回应，无须逐项点名或表演状态。"


def finite(x: Any, name: str = "number") -> float:
    if isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x):
        raise ValueError(f"{name}: finite numeric value required")
    return float(x)


def unit(x: Any, name: str = "score") -> float:
    x = finite(x, name)
    if not 0.0 <= x <= 1.0:
        raise ValueError(f"{name}: expected [0,1]")
    return x


def identifier(value: Any, name: str = "id") -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= 128:
        raise ValueError(f"{name}: nonempty string of at most 128 characters required")
    return value


def clip(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, x))


def gate(confidence: float) -> float:
    """Evidence gate, deliberately distinct from intensity and not a probability."""
    return clip((confidence - 0.55) / 0.45)


@dataclass(frozen=True)
class Personality:
    openness: float = 0.50
    conscientiousness: float = 0.50
    extraversion: float = 0.50
    agreeableness: float = 0.50
    neuroticism: float = 0.50
    plasticity: float = 0.50
    total_interactions: int = 0
    profile_version: str = "default-1"

    def __post_init__(self):
        for key in ("openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism", "plasticity"):
            unit(getattr(self, key), key)
        if type(self.total_interactions) is not int or self.total_interactions < 0:
            raise ValueError("total_interactions: nonnegative integer required")
        identifier(self.profile_version, "profile_version")

    def baseline(self) -> list[float]:
        return [0.0, 0.10 + 0.20 * self.extraversion, 0.0, 0.0]

    def half_lives(self) -> tuple[list[float], list[float]]:
        factor = (0.75 + 0.75 * self.neuroticism) * (1.10 - 0.20 * self.conscientiousness)
        return ([x * factor for x in FAST_HALFLIVES],
                [x * (0.90 + 0.20 * self.neuroticism) for x in MOOD_HALFLIVES])

    def gain(self, family: str) -> float:
        return {
            "positive": 0.75 + 0.50 * self.extraversion,
            "empathy": 0.70 + 0.60 * self.agreeableness,
            "hostility": (0.70 + 0.60 * self.neuroticism) * (1.15 - 0.30 * self.agreeableness),
            "negative": 0.60 + 0.80 * self.neuroticism,
            "novelty": 0.60 + 0.80 * self.openness,
            "repair": 0.80 + 0.40 * self.conscientiousness,
            "neutral": 1.0,
        }[family]


@dataclass(frozen=True)
class Rule:
    label: str
    family: str
    targets: dict[str, float]
    half_life: float
    source: str


# Sparse appraisal targets: an absent axis is NOT reset by the event.
RULES = {
    "shared_joy": Rule("joy", "positive", {"valence": .80, "activation": .55}, 180, "legacy"),
    "user_distress": Rule("compassion", "empathy", {"valence": -.25, "activation": .35, "tension": .25}, 300, "legacy"),
    "kindness": Rule("gratitude", "positive", {"valence": .60, "activation": .25}, 240, "legacy"),
    "affection": Rule("affection", "positive", {"valence": .65, "activation": .30}, 300, "legacy"),
    "hostility": Rule("anger", "hostility", {"valence": -.70, "activation": .65, "tension": .55, "irritation": .85}, 240, "legacy"),
    "repair": Rule("relief", "repair", {"valence": .30, "activation": .10, "tension": 0, "irritation": 0}, 150, "legacy"),
    "humor": Rule("amusement", "positive", {"valence": .65, "activation": .45}, 120, "legacy"),
    "task_blocked": Rule("frustration", "negative", {"valence": -.50, "activation": .35, "tension": .30}, 180, "host"),
    "unresolved_information": Rule("confusion", "neutral", {"activation": .30, "tension": .10}, 120, "host"),
    "novel_topic": Rule("curiosity", "novelty", {"valence": .25, "activation": .40}, 180, "host"),
    "future_threat": Rule("worry", "empathy", {"valence": -.35, "activation": .45, "tension": .60}, 360, "host"),
    "threat_resolved": Rule("relief", "repair", {"valence": .40, "activation": .10, "tension": 0}, 150, "host"),
    "expectation_unmet": Rule("disappointment", "negative", {"valence": -.60, "activation": .20}, 300, "host"),
    "own_harm_confirmed": Rule("guilt", "repair", {"valence": -.45, "activation": .30, "tension": .35}, 300, "host"),
    "self_achievement": Rule("pride", "positive", {"valence": .65, "activation": .50}, 240, "host"),
    "unexpected_event": Rule("surprise", "neutral", {"activation": .70}, 60, "host"),
}


@dataclass(frozen=True)
class Fact:
    kind: str
    strength: float
    evidence_id: str
    confidence: float = 1.0
    verified: bool = False
    source: str = "host"
    target: str = "event"
    resolves: tuple[str, ...] = ()


@dataclass(frozen=True)
class RelationView:
    """Read-only confirmed relationship data. Never inferred from sensor affect."""
    affect: str = "unspecified"
    affect_strength: float | None = None
    trust: str = "unspecified"
    trust_strength: float | None = None
    evidence_id: str | None = None
    scope: str = "general"

    def validate(self):
        if self.affect not in {"unspecified", "neutral", "affection", "aversion", "love"}:
            raise ValueError("invalid relationship affect")
        if self.trust not in {"unspecified", "trust", "distrust"}:
            raise ValueError("invalid relationship trust")
        for label, strength in ((self.affect, self.affect_strength), (self.trust, self.trust_strength)):
            if label not in {"unspecified", "neutral"}:
                if not self.evidence_id or strength is None:
                    raise ValueError("relationship claims require evidence and strength")
                unit(strength)
            elif strength is not None:
                raise ValueError("neutral/unspecified do not have intensity")
        if self.affect == "neutral" and not self.evidence_id:
            raise ValueError("neutral relationship requires a known baseline")
        identifier(self.scope, "relation scope")
        if self.evidence_id is not None:
            identifier(self.evidence_id, "relation evidence")


@dataclass(frozen=True)
class Context:
    topic_id: str = "general"
    scene: str = "real"
    legitimate_feedback: bool = False
    visible_evidence: frozenset[str] = frozenset()
    active_tags: frozenset[str] = frozenset()
    relations: RelationView = field(default_factory=RelationView)
    trust_scope: str = "general"


@dataclass(frozen=True)
class Observation:
    event_id: str
    seq: int
    at: float
    context: Context = field(default_factory=Context)
    perception: dict[str, Any] | None = None
    facts: tuple[Fact, ...] = ()
    model_metadata_version: str = "2.1.0"


@dataclass(frozen=True)
class Appraisal:
    kind: str
    strength: float
    confidence: float
    evidence_id: str
    target: str
    topic_id: str
    resolves: tuple[str, ...] = ()


@dataclass
class Trace:
    kind: str
    target: str
    topic_id: str
    strength: float
    half_life: float
    evidence_ids: list[str]
    last_event_at: float


@dataclass
class Snapshot:
    at: float
    profile_version: str
    fast: list[float]
    mood: list[float]
    traces: list[Trace] = field(default_factory=list)
    last_seq: int = -1
    last_event_id: str = ""
    label_levels: dict[str, int] = field(default_factory=dict)
    selected_feelings: list[str] = field(default_factory=list)
    mood_label: str = "neutral"


@dataclass(frozen=True)
class Row:
    aspect: str
    state: str
    intensity: str


@dataclass(frozen=True)
class Decision:
    event_id: str
    seq: int
    rows: tuple[Row, ...]
    audit: tuple[str, ...]

    def markdown(self) -> str:
        lines = ["| aspect | state | intensity |", "|---|---|---|"]
        lines += [f"| {ASPECT_ZH[r.aspect]} | {STATE_ZH[r.state]} | {LEVEL_ZH[r.intensity]} |" for r in self.rows]
        return "\n".join(lines)

    def wire(self) -> list[dict[str, str]]:
        return [asdict(r) for r in self.rows]


def check_scores(value: Any, path: str = "perception"):
    """Validate consumed scores, not e.g. negative logits or model latency."""
    if not isinstance(value, dict):
        raise ValueError(f"{path}: object required")
    for name in ("fineStates", "stances"):
        if not isinstance(value.get(name, {}), dict) or len(value.get(name, {})) > 8:
            raise ValueError(f"{name}: bounded object required")
        for key, x in value.get(name, {}).items():
            unit(x, f"{name}.{key}")
    if not isinstance(value.get("emotions", {}), dict) or len(value.get("emotions", {})) > 8:
        raise ValueError("emotions: bounded object required")
    for key, scores in value.get("emotions", {}).items():
        if not isinstance(scores, dict):
            raise ValueError("emotion score object required")
        for name in ("calibratedProbability", "strength"):
            if name in scores:
                unit(scores[name], f"emotions.{key}.{name}")
    for key in ("dialogueAct", "speakerAffectTarget", "utteranceFactuality"):
        if key in value:
            unit(value[key].get("confidence", 0), f"{key}.confidence")
    if "toxicity" in value.get("dimensions", {}):
        unit(value["dimensions"]["toxicity"], "dimensions.toxicity")


def appraise(obs: Observation, state: Snapshot) -> tuple[list[Appraisal], list[str]]:
    ctx = obs.context
    out: list[Appraisal] = []
    audit: list[str] = []
    for fact in obs.facts:
        if fact.kind not in RULES or RULES[fact.kind].source != "host":
            raise ValueError(f"unsupported host fact: {fact.kind}")
        unit(fact.strength); unit(fact.confidence)
        identifier(fact.evidence_id, "fact evidence")
        identifier(fact.target, "fact target")
        if type(fact.verified) is not bool or len(fact.resolves) > 32:
            raise ValueError("invalid fact verification or resolution count")
        for source_id in fact.resolves:
            identifier(source_id, "resolved evidence")
        if not fact.verified or fact.source not in {"host", "authored_scenario"}:
            audit.append(f"ignored_unverified:{fact.kind}")
            continue
        if fact.kind == "threat_resolved" and not fact.resolves:
            audit.append("ignored_resolution_without_link")
            continue
        out.append(Appraisal(fact.kind, fact.strength, fact.confidence,
                             fact.evidence_id, fact.target, ctx.topic_id, fact.resolves))
    p = obs.perception
    if p is None:
        return out, audit + ["sensor_missing:no_new_sensor_impulse"]
    if not isinstance(p, dict):
        raise ValueError("perception object required")
    if obs.model_metadata_version != "2.1.0" or p.get("schemaVersion") != "2.1.1":
        raise ValueError("unsupported legacy metadata/decoder version pair")
    check_scores(p)
    fact = p.get("utteranceFactuality", {})
    if fact.get("value") != "asserted" or fact.get("confidence", 0) < .65:
        return out, audit + ["sensor_factuality_blocked"]
    target = p.get("speakerAffectTarget", {})
    target_id, tc = target.get("value", "unclear"), target.get("confidence", 0)
    fc = fact["confidence"]
    direct = target_id == "listener" and tc >= .75 and ctx.scene == "real"
    stance = p.get("stances", {})
    act = p.get("dialogueAct", {})
    act_id, ac = act.get("primary", "other"), act.get("confidence", 0)
    toxicity = p.get("dimensions", {}).get("toxicity", 0)

    def base(key: str) -> tuple[float, float]:
        e = p.get("emotions", {}).get(key, {})
        return e.get("calibratedProbability", 0), e.get("strength", 0)

    def add(kind: str, magnitude: float, confidence: float, target: str):
        out.append(Appraisal(kind, magnitude, confidence, obs.event_id, target, ctx.topic_id))

    hostile, playful = stance.get("hostile", 0), stance.get("playful", 0)
    # Ambiguous banter never gets an automatic hostile reading.
    if direct and hostile >= .70 and toxicity >= .55 and playful < .55 and not ctx.legitimate_feedback:
        bp, bs = base("anger")
        add("hostility", max(bs if bp >= .65 else 0, toxicity), min(fc, tc, hostile), "user")
    elif direct and hostile >= .70 and playful >= .55:
        audit.append("ambiguous_hostility_playfulness:no_hostility")
    if direct and act_id in {"thank_appreciate", "comfort_support"} and ac >= .70:
        add("kindness", .60, min(fc, tc, ac), "user")
    ap, av = base("affection")
    if direct and ap >= .70 and stance.get("affiliative", 0) >= .60:
        add("affection", av, min(fc, tc, ap), "user")
    if direct and act_id == "apologize_repair" and ac >= .75:
        related = [t for t in state.traces if t.kind == "hostility" and t.topic_id == ctx.topic_id]
        if related:
            ids = tuple(x for t in related for x in t.evidence_ids)
            out.append(Appraisal("repair", .65, min(fc, tc, ac), obs.event_id, "user", ctx.topic_id, ids))
        else:
            audit.append("apology_without_related_tension:no_relief")
    if act_id == "joke_irony" and ac >= .65 and playful >= .70 and hostile < .55:
        add("humor", .65, min(fc, ac, playful), "event")
    experiential = act_id not in {"question", "request_command"} or stance.get("vulnerable", 0) >= .65
    if experiential and tc >= .65 and target_id in {"self", "event_object", "general"}:
        candidates = [(prob, strength) for prob, strength in (base("sadness"), base("fear")) if prob >= .70]
        if candidates:
            prob, strength = max(candidates, key=lambda x: x[1])
            add("user_distress", strength, min(fc, tc, prob), "user")
        jp, js = base("joy")
        if jp >= .70:
            add("shared_joy", js, min(fc, tc, jp), "event")
    # fineStates, dominance, effectiveScore, global confidence are deliberately
    # not treated as direct character feelings or as calibrated universal evidence.
    return out, audit


def evolve(state: Snapshot, at: float, p: Personality):
    """Exact solution of f'=-a(f-b), m'=c(f-m), independently per axis."""
    at = finite(at, "time")
    if at < state.at:
        raise ValueError("time moved backwards: normalize host clock or replay")
    dt = at - state.at
    if dt == 0:
        return
    fh, mh = p.half_lives()
    baseline = p.baseline()
    for i in range(4):
        a, c = math.log(2) / fh[i], math.log(2) / mh[i]
        ea, ec = math.exp(-a * dt), math.exp(-c * dt)
        coupling = c * dt * ec if abs(a - c) < 1e-12 else c * (ea - ec) / (c - a)
        old_f = state.fast[i] - baseline[i]
        state.mood[i] = baseline[i] + (state.mood[i] - baseline[i]) * ec + old_f * coupling
        state.fast[i] = baseline[i] + old_f * ea
        lo, hi = BOUNDS[i]
        # Floating round-off only; the exact dynamics already preserve bounds.
        state.fast[i] = clip(state.fast[i], lo, hi)
        state.mood[i] = clip(state.mood[i], lo, hi)
    for t in state.traces:
        t.strength *= math.exp(-math.log(2) * dt / t.half_life)
    state.traces = [t for t in state.traces if t.strength >= .015]
    state.at = at


def quantize(state: Snapshot, key: str, value: float) -> str:
    if value == 0:
        state.label_levels.pop(key, None)
        return "none"
    new = sum(value >= x for x in THRESHOLDS)
    old = state.label_levels.get(key)
    if old is not None:
        if new > old:
            new = old
            while new < 3 and value >= THRESHOLDS[new] + .03:
                new += 1
        elif new < old:
            new = old
            while new > 0 and value < THRESHOLDS[new - 1] - .03:
                new -= 1
    state.label_levels[key] = new
    return LEVELS[new]


def compile_rows(state: Snapshot, ctx: Context, visible: set[str]) -> tuple[Row, ...]:
    r = ctx.relations
    rows: list[Row] = []
    # Relationship values remain read-only. Never promote affection to love.
    for aspect, label, val in (("relationship_affect", r.affect, r.affect_strength),
                              ("relationship_trust", r.trust, r.trust_strength)):
        if aspect == "relationship_trust" and r.scope not in {"general", ctx.trust_scope}:
            label, val = "unspecified", None
        rows.append(Row(aspect, label, "—" if val is None else quantize(state, f"{aspect}:{label}", val)))
    v, a, t, i = state.mood
    # Derived label scores do not create extra dynamical state variables.
    scores = {
        "cheerful": max(0, v),
        "gloomy": max(0, -v) * (1 - .5 * a),
        "tense": t,
        "irritable": i,
        "calm": min(max(0, v), max(0, .35 - a), max(0, .20 - t)) * 1.5,
    }
    old = state.mood_label
    eligible = {k: val for k, val in scores.items() if val >= (.10 if k == old else .16)}
    if eligible:
        best = max(eligible, key=lambda k: (eligible[k] + (.04 if k == old else 0), k))
        state.mood_label = best
        rows.append(Row("mood", best, quantize(state, f"mood:{best}", scores[best])))
    else:
        state.mood_label = "neutral"
        rows.append(Row("mood", "neutral", "—"))
    # Labels retain causal evidence; do not decode guilt/anger from V/A alone.
    feelings: dict[str, float] = {}
    for tr in state.traces:
        if tr.topic_id != ctx.topic_id or not visible.intersection(tr.evidence_ids):
            continue
        label = RULES[tr.kind].label
        feelings[label] = max(feelings.get(label, 0), tr.strength)
    candidates = {k: val for k, val in feelings.items() if val >= (.12 if k in state.selected_feelings else .18)}
    chosen = sorted(candidates, key=lambda k: (-(candidates[k] + (.04 if k in state.selected_feelings else 0)), k))[:2]
    state.selected_feelings = chosen
    for label in chosen:
        rows.append(Row("feeling", label, quantize(state, f"feeling:{label}", feelings[label])))
    # Persistent quantizer bookkeeping is bounded by the finite vocabulary.
    return tuple(rows)


@dataclass(frozen=True)
class LoreRule:
    entry_id: str
    text: str
    priority: int = 0
    states_any: frozenset[str] = frozenset()
    context_all: frozenset[str] = frozenset()
    group: str = ""
    always: bool = False
    enabled: bool = True


def select_lore(decision: Decision, ctx: Context, rules: tuple[LoreRule, ...],
                max_entries: int = 6, char_budget: int = 1600) -> tuple[str, ...]:
    """Select semantic candidates from the already resolved SINGLE active book.

    This does not replace Loyea's book resolution, token counting, or ST engine.
    Hard Soul constraints belong outside this optional budget and cannot be dropped.
    """
    if len(rules) > 128:
        raise ValueError("semantic extension supports at most 128 rules per active book")
    if len({r.entry_id for r in rules}) != len(rules):
        raise ValueError("duplicate lore entry id")
    ids = {f"{r.aspect}/{r.state}" for r in decision.rows if r.intensity != "none"}
    candidates = [r for r in rules if r.enabled and (
        r.always or ((not r.states_any or bool(r.states_any & ids)) and r.context_all <= ctx.active_tags))]
    candidates.sort(key=lambda r: (-r.priority, r.entry_id))
    chosen, groups, size = [], set(), 0
    for rule in candidates:
        if rule.group and rule.group in groups:
            continue
        if len(chosen) >= max_entries or size + len(rule.text) > char_budget:
            continue
        chosen.append(rule.entry_id); size += len(rule.text)
        if rule.group:
            groups.add(rule.group)
    return tuple(chosen)


class Modulator:
    def __init__(self, personality: Personality | None = None, at: float = 0):
        self.personality = personality or Personality()
        at = finite(at, "initial time")
        b = self.personality.baseline()
        self.state = Snapshot(at, self.personality.profile_version, b[:], b[:])
        self.last_decision: Decision | None = None

    def process(self, obs: Observation) -> Decision:
        if type(obs.seq) is not int or obs.seq < 0 or not obs.event_id:
            raise ValueError("seq and event_id are required")
        finite(obs.at, "observation time")
        identifier(obs.event_id, "event_id")
        if obs.seq == self.state.last_seq and obs.event_id == self.state.last_event_id:
            if self.last_decision is None:
                raise ValueError("retry must use persisted turn decision")
            return self.last_decision
        if obs.seq <= self.state.last_seq:
            raise ValueError("stale or conflicting sequence: use historical snapshot/replay")
        if obs.event_id == self.state.last_event_id:
            raise ValueError("same event id with a new sequence")
        if obs.at < self.state.at:
            raise ValueError("out-of-order event: replay required")
        if self.state.profile_version != self.personality.profile_version:
            raise ValueError("personality version change requires explicit rebase")
        if len(obs.facts) > 8 or len(obs.context.visible_evidence) > 128 or len(obs.context.active_tags) > 64:
            raise ValueError("observation exceeds bounded input contract")
        identifier(obs.context.topic_id, "topic_id")
        identifier(obs.context.scene, "scene")
        identifier(obs.context.trust_scope, "trust_scope")
        if type(obs.context.legitimate_feedback) is not bool:
            raise ValueError("legitimate_feedback must be boolean")
        for item in obs.context.visible_evidence | obs.context.active_tags:
            identifier(item, "context item")
        obs.context.relations.validate()
        # Copy-on-write: malformed data cannot partially advance the live state.
        s = copy.deepcopy(self.state)
        evolve(s, obs.at, self.personality)
        events, audit = appraise(obs, s)
        if len(events) > 8:
            raise ValueError("at most eight semantic events per admitted observation")
        # Exact duplicate facts in a single observation are not extra evidence.
        seen = set()
        unique = []
        for e in events:
            key = (e.kind, e.evidence_id, e.target)
            if key not in seen:
                unique.append(e); seen.add(key)
        amplitudes: list[tuple[Appraisal, float]] = []
        for e in unique:
            rule = RULES[e.kind]
            q = e.strength * gate(e.confidence) * self.personality.gain(rule.family)
            if e.kind == "future_threat":
                q *= 1 + .15 * s.mood[2]
            if e.kind == "hostility":
                q *= 1 + .10 * s.mood[3]
            previous = next((x for x in s.traces if (x.kind, x.target, x.topic_id) == (e.kind, e.target, e.topic_id)), None)
            if previous:
                elapsed = max(0, obs.at - previous.last_event_at)
                q *= .25 + .75 * min(1, elapsed / 45.0)
            q = clip(q, 0, .90)
            if q > 0:
                amplitudes.append((e, q))
                audit.append(f"appraisal:{e.kind}:amplitude={q:.6f}")
        # Simultaneous sparse update: result is invariant to within-turn event order.
        before = s.fast[:]
        for j, axis in enumerate(AXES):
            applicable = [(RULES[e.kind].targets[axis], q) for e, q in amplitudes if axis in RULES[e.kind].targets]
            if applicable:
                weight = math.fsum(q for _, q in applicable)
                anchor = math.fsum(target * q for target, q in applicable) / weight
                alpha = min(.55, -math.expm1(-.75 * weight))
                s.fast[j] = (1 - alpha) * before[j] + alpha * anchor
        # Explicit resolution is linked to evidence and never globally wipes memory.
        for e, q in amplitudes:
            if e.resolves:
                for tr in s.traces:
                    if set(e.resolves).intersection(tr.evidence_ids):
                        tr.strength *= 1 - .55 * q
        for e, q in amplitudes:
            rule = RULES[e.kind]
            tr = next((x for x in s.traces if (x.kind, x.target, x.topic_id) == (e.kind, e.target, e.topic_id)), None)
            if tr:
                tr.strength = min(.95, 1 - (1 - tr.strength) * (1 - q))
                tr.evidence_ids = list(dict.fromkeys(tr.evidence_ids + [e.evidence_id]))[-4:]
                tr.last_event_at = obs.at
            else:
                tr = Trace(e.kind, e.target, e.topic_id, q, rule.half_life,
                           [e.evidence_id], obs.at)
                s.traces.append(tr)
        s.traces.sort(key=lambda t: (-t.strength, t.kind, t.target, t.topic_id))
        s.traces = s.traces[:8]
        visible = set(obs.context.visible_evidence) | {obs.event_id}
        visible.update(e.evidence_id for e, _ in amplitudes)
        rows = compile_rows(s, obs.context, visible)
        s.last_seq, s.last_event_id = obs.seq, obs.event_id
        decision = Decision(obs.event_id, obs.seq, rows, tuple(audit))
        self.state, self.last_decision = s, decision
        return decision

    def idle_to(self, at: float):
        s = copy.deepcopy(self.state)
        evolve(s, at, self.personality)
        self.state = s

    def dumps(self) -> str:
        return json.dumps({"version": VERSION, "personality": asdict(self.personality),
                           "state": asdict(self.state),
                           "last_decision": asdict(self.last_decision) if self.last_decision else None},
                          ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)

    @classmethod
    def loads(cls, raw: str) -> "Modulator":
        if len(raw) > 65536:
            raise ValueError("oversized checkpoint")
        obj = json.loads(raw)
        if obj.get("version") != VERSION:
            raise ValueError("unsupported checkpoint version")
        p = Personality(**obj["personality"])
        d = obj["state"]
        for name in ("fast", "mood"):
            if len(d[name]) != 4:
                raise ValueError("corrupt state dimension")
            for value, (lo, hi) in zip(d[name], BOUNDS):
                if not lo <= finite(value) <= hi:
                    raise ValueError("corrupt state bound")
        if len(d["traces"]) > 8 or len(d["label_levels"]) > 64:
            raise ValueError("corrupt bounded cache")
        allowed_keys = {f"{aspect}:{label}" for aspect, labels in ASPECT_STATES.items() for label in labels}
        if any(k not in allowed_keys or type(v) is not int or not 0 <= v <= 3 for k, v in d["label_levels"].items()):
            raise ValueError("corrupt quantizer state")
        if d["mood_label"] not in ASPECT_STATES["mood"] or len(d["selected_feelings"]) > 2 or not set(d["selected_feelings"]) <= FEELING_IDS:
            raise ValueError("corrupt selected labels")
        if type(d["last_seq"]) is not int or d["last_seq"] < -1:
            raise ValueError("corrupt last sequence")
        d["traces"] = [Trace(**x) for x in d["traces"]]
        for tr in d["traces"]:
            if tr.kind not in RULES or len(tr.evidence_ids) > 4 or not tr.evidence_ids:
                raise ValueError("corrupt trace")
            unit(tr.strength)
            identifier(tr.target); identifier(tr.topic_id)
            for source_id in tr.evidence_ids:
                identifier(source_id, "trace evidence")
            if finite(tr.half_life) <= 0 or finite(tr.last_event_at) > finite(d["at"]):
                raise ValueError("corrupt trace time")
            if tr.half_life != RULES[tr.kind].half_life:
                raise ValueError("checkpoint rule mismatch")
        engine = cls(p, finite(d["at"]))
        engine.state = Snapshot(**d)
        if engine.state.profile_version != p.profile_version:
            raise ValueError("checkpoint profile mismatch")
        dec = obj["last_decision"]
        if dec:
            if dec["seq"] != d["last_seq"] or dec["event_id"] != d["last_event_id"]:
                raise ValueError("checkpoint decision mismatch")
            identifier(dec["event_id"])
            if not 3 <= len(dec["rows"]) <= 5 or len(dec["audit"]) > 32:
                raise ValueError("corrupt decision size")
            if [r["aspect"] for r in dec["rows"][:3]] != ["relationship_affect", "relationship_trust", "mood"] or any(r["aspect"] != "feeling" for r in dec["rows"][3:]):
                raise ValueError("corrupt decision aspects")
            for row in dec["rows"]:
                if row["state"] not in ASPECT_STATES[row["aspect"]] or row["intensity"] not in LEVEL_ZH:
                    raise ValueError("corrupt decision vocabulary")
                if (row["state"] in {"neutral", "unspecified"}) != (row["intensity"] == "—"):
                    raise ValueError("corrupt placeholder semantics")
            engine.last_decision = Decision(dec["event_id"], dec["seq"], tuple(Row(**r) for r in dec["rows"]), tuple(dec["audit"]))
        elif d["last_seq"] != -1 or d["last_event_id"]:
            raise ValueError("missing committed decision")
        return engine


def config_dict() -> dict[str, Any]:
    return {"version": VERSION, "axes": AXES, "bounds": BOUNDS,
            "fast_half_lives_seconds": FAST_HALFLIVES,
            "mood_half_lives_seconds": MOOD_HALFLIVES,
            "intensity_boundaries": THRESHOLDS, "intensity_hysteresis": .03,
            "feeling_enter": .18, "feeling_exit": .12, "selection_bias": .04,
            "mood_enter": .16, "mood_exit": .10,
            "max_traces": 8, "max_source_ids_per_trace": 4,
            "trace_prune": .015, "trace_cap": .95, "impulse_cap": .90,
            "blend_cap": .55, "blend_rate": .75,
            "refractory_seconds": 45, "refractory_floor": .25,
            "rules": {key: asdict(rule) for key, rule in RULES.items()},
            "legacy_versions": {"model_metadata": "2.1.0", "decoder": "2.1.1"}}
