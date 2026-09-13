#!/usr/bin/env python3
"""Deterministic extraction of the legacy April perception artifacts.

Source of truth: docs/AprilPerceptionDemo-standalone-2.1.4.html
(SHA-256 a7dd693856d4c686690f3907f37c732f4af4017c9d19aef9665f18cc3303bd9c,
47,693,438 bytes).

Outputs (UTF-8 raw script content, base64-decoded for the ONNX model) into
app/src/main/assets/perception/ and a manifest with SHA-256 / byte counts into
docs/audits/perception_artifacts_manifest.json.

Hashes target the ORIGINAL script content bytes (no trim, no re-serialization),
as registered in docs/Loyea_Companion_Modulator_Perception_Integration_Spec_v1.0.md §3.1.
"""
import base64
import hashlib
import json
import os
import re
import sys

HTML_PATH = os.path.join("docs", "AprilPerceptionDemo-standalone-2.1.4.html")
ASSET_DIR = os.path.join("app", "src", "main", "assets", "perception")
MANIFEST_PATH = os.path.join("docs", "audits", "perception_artifacts_manifest.json")

HTML_SHA256 = "a7dd693856d4c686690f3907f37c732f4af4017c9d19aef9665f18cc3303bd9c"
HTML_SIZE = 47693438

EXPECTED = {
    "model.onnx": (
        "embeddedOnnxModel",
        "base64-decode",
        24085508,
        "a3e763f4bad5e4e9dfd5d2e75e10407aa2e5a336f438c67113761ff0c84419cb",
    ),
    "tokenizer.json": (
        "embeddedTokenizer",
        "raw-utf8",
        439753,
        "79a7b606f3b655b127404169b356eb9d6c469ea679cd15d3d1b2154067ed4ac0",
    ),
    "metadata.json": (
        "embeddedMetadata",
        "raw-utf8",
        4219,
        "c85279af4f912cfb2af6772f89c0bd8659be954788314d4dfd5f1a429a6e2853",
    ),
    "calibration.json": (
        "embeddedCalibration",
        "raw-utf8",
        953,
        "e4c2829e9b8dcd91340a9d6b39535b254478f584178fe62dbe9ee905fb15eadd",
    ),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    with open(HTML_PATH, "rb") as f:
        html_bytes = f.read()
    html_hash = sha256(html_bytes)
    if html_hash != HTML_SHA256 or len(html_bytes) != HTML_SIZE:
        print("FATAL: source HTML hash/size mismatch: %s %d" % (html_hash, len(html_bytes)))
        return 2
    html = html_bytes.decode("utf-8")

    results = {}
    os.makedirs(ASSET_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(MANIFEST_PATH), exist_ok=True)
    for out_name, (script_id, mode, exp_size, exp_hash) in EXPECTED.items():
        tag_re = re.compile(
            r'<script[^>]*id="%s"[^>]*>' % re.escape(script_id), re.S
        )
        m = tag_re.search(html)
        if not m:
            print("FATAL: script id not found: %s" % script_id)
            return 2
        start = html.find(">", m.start()) + 1
        end = html.find("</script>", start)
        if end < 0:
            print("FATAL: unterminated script: %s" % script_id)
            return 2
        content = html[start:end]
        raw = content.encode("utf-8")
        if mode == "base64-decode":
            payload = base64.b64decode(raw)
        else:
            payload = raw
        got_hash = sha256(payload)
        ok = got_hash == exp_hash and len(payload) == exp_size
        out_path = os.path.join(ASSET_DIR, out_name)
        with open(out_path, "wb") as f:
            f.write(payload)
        results[out_name] = {
            "scriptId": script_id,
            "extraction": mode,
            "bytes": len(payload),
            "sha256": got_hash,
            "expectedSha256": exp_hash,
            "expectedBytes": exp_size,
            "verified": ok,
            "outputPath": out_path.replace("\\", "/"),
        }
        print("%-18s %10d bytes  %s  %s" % (out_name, len(payload), got_hash[:16] + "...", "OK" if ok else "MISMATCH"))

    manifest = {
        "sourceHtml": {
            "path": HTML_PATH.replace("\\", "/"),
            "sha256": html_hash,
            "bytes": len(html_bytes),
            "referenceVersion": "April Perception Demo 2.1.4",
        },
        "artifacts": results,
        "excluded": {
            "embeddedOrtWasm": "Web runtime wasm; Android ships the official onnxruntime-android native runtime instead.",
        },
        "modelInterface": {
            "inputs": {"input_ids": "int64[1,96]", "attention_mask": "int64[1,96]"},
            "output": "logits (12 heads, 82 logits total)",
            "maxTokens": 96,
            "metadataSchema": "2.1.0",
            "decodeSchema": "2.1.1",
        },
        "license": {
            "origin": "User-provided legacy April perception research artifact, delivered alongside the Spec.",
        },
    }
    with open(MANIFEST_PATH, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    all_ok = all(r["verified"] for r in results.values())
    print("manifest ->", MANIFEST_PATH)
    return 0 if all_ok else 3


if __name__ == "__main__":
    sys.exit(main())
