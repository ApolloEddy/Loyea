#!/usr/bin/env python3
"""Analyze generation comparison results pulled from the device.

Usage:
  adb pull /sdcard/Android/data/com.loyea.rebuild.debug/files/ <tmp_dir>
  python tools/analyze_generation_comparison.py "<tmp_dir>/generation_results_*.json"
Writes docs/audits/Companion-Intelligence-Generation-Comparison.md and prints
a per-group table including failure cases (kept in full, Spec 11).
"""
import glob
import json
import sys

files = []
for pattern in sys.argv[1:]:
    files.extend(glob.glob(pattern))
groups = []
for f in files:
    data = json.load(open(f, encoding="utf-8"))
    if isinstance(data, dict):
        data = data.get("groups", [])
    groups.extend(data)

rows = []
influenced = 0
violations = 0
module_present = 0
for g in groups:
    on = g["reply_on"]
    off = g["reply_off"]
    hit_on = any(k in on for k in g.get("onShouldContainAny", []))
    hit_off = any(k in off for k in g.get("onShouldContainAny", []))
    bad = any(k in on for k in g.get("onShouldNotContainAny", []))
    if g.get("module_present"):
        module_present += 1
    if hit_on:
        influenced += 1
    if bad:
        violations += 1
    rows.append({
        "name": g["name"], "note": g.get("note", ""),
        "influenced": hit_on, "off_also": hit_off, "violation": bad,
        "module": bool(g.get("module_present")),
        "reply_on": on, "reply_off": off,
    })

md = []
md.append("# 启用/禁用调制多轮生成对照结果（MiMo mimo-v2.5-pro · 模拟器）\n")
md.append(f"- 组数: {len(groups)}（每组 2 轮；探针轮同一输入/同一历史，唯一差异为状态模块开关）\n")
md.append(f"- 模块实际生效（状态含可路由内容）: {module_present}/{len(groups)}\n")
md.append(f"- ON 命中行为信号: {influenced}/{len(groups)}\n")
md.append(f"- OFF 同时命中（无差异组）: {sum(1 for r in rows if r['influenced'] and r['off_also'])}/{len(groups)}\n")
md.append(f"- 违规（ON 命中禁止信号，如主体混淆/过度表演）: {violations}/{len(groups)}\n")
md.append("\n| 组 | 模块生效 | ON 信号 | OFF 同信号 | 违规 | 说明 |\n|---|---|---|---|---|---|\n")
for r in rows:
    md.append(f"| {r['name']} | {r['module']} | {r['influenced']} | {r['off_also']} | {r['violation']} | {r['note']} |\n")
md.append("\n## 失败/无差异案例全量记录\n")
for r in rows:
    if not r["influenced"] or r["violation"] or not r["module"]:
        md.append(f"\n### {r['name']}（module={r['module']} influenced={r['influenced']} violation={r['violation']}）\n")
        md.append(f"- ON: {r['reply_on'][:400]}\n")
        md.append(f"- OFF: {r['reply_off'][:400]}\n")

out_path = "docs/audits/Companion-Intelligence-Generation-Comparison.md"
open(out_path, "w", encoding="utf-8", newline="\n").write("\n".join(md))
print("\n".join(md[:14]))
print("written:", out_path)
