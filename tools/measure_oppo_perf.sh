#!/usr/bin/env bash
# OPPO Find X6 / OPPO Pad 3 Pro 真机性能测量脚本（Spec §11 待测项配套）。
# 用法：
#   1. USB 连接目标设备并开启 USB 调试；确认 `adb devices` 出现设备序列号。
#   2. export MIMO_API_KEY=...（可选：生成对照部分）
#   3. ./tools/measure_oppo_perf.sh <device-serial>
# 产物输出到 ./docs/audits/perf_measurements_<serial>/（logcat 原始记录 + PERF 摘要）。
set -euo pipefail

SERIAL="${1:?usage: measure_oppo_perf.sh <device-serial>}"
OUT="docs/audits/perf_measurements_${SERIAL}"
mkdir -p "$OUT"

adb -s "$SERIAL" shell echo "connected: $(adb -s "$SERIAL" shell getprop ro.product.model)"

echo "== 1. 安装 debug APK =="
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk

echo "== 2. 真机感知/接纳/渲染性能（16 条真实样本 + 1200 次渲染）=="
# 设备级 PERF 标记：prepareMs / inference p50,p95 / admitMs / render p50,p95
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.loyea.plugin.companion.runtime.CompanionEndToEndDeviceTest
adb -s "$SERIAL" logcat -d > "$OUT/logcat_e2e.txt"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.loyea.plugin.companion.perception.LegacySensorOnDeviceTest
adb -s "$SERIAL" logcat -d > "$OUT/logcat_sensor.txt"
grep -hE "PERF " "$OUT/logcat_e2e.txt" "$OUT/logcat_sensor.txt" | tail -20 > "$OUT/perf_summary.txt" || true

echo "== 3. 持续使用 30 分钟温度/功耗对照（开/关文字感知各一轮，人工交互脚本）=="
# 说明：真机温度需在设备上手动保持相同亮度与负载。脚本负责周期采样：
#   采样点：battery temperature（毫米摄氏度）+ thermal 服务状态，每 5 分钟一次，共 7 次。
#   第一轮：陪伴设置开启文字感知，正常聊天 30 分钟（每 3 分钟发一条消息）。
#   第二轮：设置页关闭文字感知，重复同样的聊天负载。
echo "请在设备上开始第一轮（文字感知开）。按回车开始采样..."; read -r
for i in $(seq 1 7); do
  {
    echo "--- sample $i $(date +%T) ---"
    adb -s "$SERIAL" shell dumpsys battery | grep -E "temperature"
    adb -s "$SERIAL" shell dumpsys thermalservice 2>/dev/null | grep -E "mIsStatusOverride|ThermalStatus" | head -3
  } >> "$OUT/thermal_on.txt"
  sleep 300
done
echo "第一轮完成。请关闭文字感知并重复聊天负载，按回车开始采样..."; read -r
for i in $(seq 1 7); do
  {
    echo "--- sample $i $(date +%T) ---"
    adb -s "$SERIAL" shell dumpsys battery | grep -E "temperature"
    adb -s "$SERIAL" shell dumpsys thermalservice 2>/dev/null | grep -E "mIsStatusOverride|ThermalStatus" | head -3
  } >> "$OUT/thermal_off.txt"
  sleep 300
done

echo "== 4. 闲置 30 分钟推理次数检查 =="
adb -s "$SERIAL" logcat -c
sleep 1800
adb -s "$SERIAL" logcat -d | grep -c "LegacyEmotionSensor\|ORT" > "$OUT/idle_inference_count.txt" || true

echo "== 完成。摘要: $OUT/perf_summary.txt；温度对照: thermal_on/off.txt =="
