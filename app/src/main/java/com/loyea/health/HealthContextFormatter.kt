package com.loyea.health

/**
 * 健康文本的**唯一拼装点**。PhysicalContextManager 与 PerceptionMcpServer.get_health_data 共用，
 * 保证两处输出同源同格式；null 字段不输出（天然过滤 Permission Denied / No Data 噪音）。
 */
object HealthContextFormatter {

    /** 多行格式（供物理上下文与 get_health_data 工具）。无数据且非蓝牙 → 空串，调用方决定是否跳过。 */
    fun formatSnapshot(s: HealthSnapshot): String {
        if (!s.hasData && s.sourceLabel != "Smartwatch Bluetooth") return ""
        val src = "[${s.sourceLabel}]"
        val sb = StringBuilder()

        if (s.heartRateBpm != null) {
            sb.append("Heart Rate: ${s.heartRateBpm} bpm $src\n")
        } else if (s.sourceLabel == "Smartwatch Bluetooth") {
            sb.append("Heart Rate: Waiting for sensor... [Smartwatch Bluetooth]\n")
        }

        if (s.hasData) {
            s.steps?.let { sb.append("Today's Steps: $it $src\n") }
            if (s.bpSystolic != null && s.bpDiastolic != null) {
                sb.append("Blood Pressure: ${s.bpSystolic}/${s.bpDiastolic} mmHg $src\n")
            }
            s.sleepMinutes?.let {
                val quality = s.sleepQuality?.let { q -> " ($q)" } ?: ""
                sb.append("Last Sleep: ${it / 60}h ${it % 60}m$quality $src\n")
            }
            if (s.exerciseMinutes != null || s.exerciseCalories != null) {
                sb.append("Today's Exercise: ${s.exerciseMinutes ?: 0} mins, ${s.exerciseCalories ?: 0} kcal (${s.exerciseType ?: "Unknown"}) $src\n")
            }
            s.restingHeartRateBpm?.let { sb.append("Resting Heart Rate: $it bpm $src\n") }
        }

        return sb.toString()
    }

    /** 单行紧凑摘要（配对状态面板预览用，不注入 prompt）。 */
    fun formatCompact(s: HealthSnapshot): String {
        if (!s.hasData) return ""
        val parts = mutableListOf<String>()
        s.heartRateBpm?.let { parts += "HR ${it}bpm" }
        s.restingHeartRateBpm?.let { parts += "restHR ${it}bpm" }
        s.steps?.let { parts += "${it} steps" }
        if (s.bpSystolic != null && s.bpDiastolic != null) parts += "BP ${s.bpSystolic}/${s.bpDiastolic}"
        s.sleepMinutes?.let { parts += "sleep ${it / 60}h${it % 60}m" }
        return parts.joinToString(", ")
    }
}
