package com.loyea.perception

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class HapticManager(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        val appContext = context.applicationContext
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (t: Throwable) {
            Log.w("HapticManager", "Failed to resolve VibratorManager class (normal on Android 11-), falling back to legacy service: ${t.message}")
            try {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 触发特定类型的震动反馈
     * @param type 震动类型：heartbeat (心跳), poke (轻戳), whisper (低语/呼吸), bump (碰拳/击掌)
     */
    fun triggerHaptic(type: String) {
        val activeVibrator = vibrator ?: return
        if (!activeVibrator.hasVibrator()) {
            return
        }

        try {
            Log.d("HapticManager", "Triggering haptic: $type")
            when (type.lowercase()) {
                "heartbeat" -> {
                    // 心跳：双击震动。每次心跳之间有短暂间隔
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 50, 100, 40)
                        val amplitudes = intArrayOf(0, 140, 0, 90)
                        activeVibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        activeVibrator.vibrate(longArrayOf(0, 50, 100, 40), -1)
                    }
                }
                "poke" -> {
                    // 轻戳：极其短暂的轻快高频震动 (15ms)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activeVibrator.vibrate(VibrationEffect.createOneShot(15, 200))
                    } else {
                        @Suppress("DEPRECATION")
                        activeVibrator.vibrate(15)
                    }
                }
                "whisper" -> {
                    // 低语：绵长且极轻的超低频振动
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 150)
                        val amplitudes = intArrayOf(0, 35)
                        activeVibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        activeVibrator.vibrate(120)
                    }
                }
                "bump" -> {
                    // 碰拳/击掌：沉稳而有弹性回馈的中震 (40ms -> 30ms)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 40, 30, 20)
                        val amplitudes = intArrayOf(0, 170, 0, 70)
                        activeVibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        activeVibrator.vibrate(longArrayOf(0, 40, 30, 20), -1)
                    }
                }
                else -> {
                    // 默认轻震
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activeVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        activeVibrator.vibrate(30)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("HapticManager", "Fatal error triggering vibration: ${t.message}", t)
        }
    }
}
