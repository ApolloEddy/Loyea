"""Run a short, synthetic decoded-input demonstration. No model download."""
from modulator import Context, Modulator, Observation


def sample_perception(label="neutral", strength=.75, target="event_object", act="inform", **stances):
    return {
        "schemaVersion": "2.1.1",
        "emotions": {label: {"calibratedProbability": .95, "strength": strength}},
        "fineStates": {}, "stances": stances,
        "dialogueAct": {"primary": act, "confidence": .95},
        "speakerAffectTarget": {"value": target, "confidence": .95},
        "utteranceFactuality": {"value": "asserted", "confidence": .95},
        "dimensions": {"toxicity": .80 if stances.get("hostile", 0) >= .7 else .02},
    }


if __name__ == "__main__":
    m = Modulator()
    turns = [
        ("分享难过", 0, sample_perception("sadness", target="self")),
        ("随后开玩笑", 60, sample_perception(act="joke_irony", playful=.95)),
        ("两小时后聊新话题", 7260, sample_perception()),
    ]
    for n, (title, at, p) in enumerate(turns, 1):
        d = m.process(Observation(f"u{n}", n, at, Context(topic_id="demo"), p))
        print(f"\n{title}（合成感知结果）\n{d.markdown()}")
