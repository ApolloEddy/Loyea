package com.loyea.perception

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

class NoiseProvider(private val context: Context) {

    companion object {
        private const val TAG = "NoiseProvider"
        private const val SAMPLE_RATE = 8000
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 瞬时测算环境噪音分贝值 (dB)
     * 每次调用仅短暂录音约 120ms 计算 RMS，读取完毕后彻底释放麦克风以确保功耗与隐私安全
     */
    @SuppressLint("MissingPermission")
    fun getAmbientNoiseDb(): Int {
        if (com.loyea.ui.chat.ChatViewModel.isRecordingActive) {
            Log.d(TAG, "getAmbientNoiseDb: Voice recording is active, skipping hardware MIC to avoid conflict.")
            return 35 // 方案1：返回预设底噪，规避麦克风抢占冲突
        }

        if (!hasAudioPermission()) {
            Log.w(TAG, "getAmbientNoiseDb: Missing RECORD_AUDIO permission")
            return -1 // 代表权限缺失
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord min buffer size error")
            return 0
        }

        // 我们只需读取 1024 个采样，大约 128 毫秒，足以计算一个准确的声压级分贝
        val readSize = 1024
        val audioBuffer = ShortArray(readSize)
        
        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize.coerceAtLeast(readSize * 2)
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return 0
            }

            audioRecord.startRecording()
            
            // 循环尝试读取，直到读满 1024 个采样
            var totalRead = 0
            while (totalRead < readSize) {
                val readResult = audioRecord.read(audioBuffer, totalRead, readSize - totalRead)
                if (readResult <= 0) {
                    break
                }
                totalRead += readResult
            }

            if (totalRead <= 0) {
                return 0
            }

            // 计算 Root Mean Square (RMS) 均方根值
            var sumOfSquares = 0.0
            for (i in 0 until totalRead) {
                val value = audioBuffer[i].toDouble()
                sumOfSquares += value * value
            }
            val rms = sqrt(sumOfSquares / totalRead)

            // 将 RMS 转换成分贝。由于 16bit PCM 最大振幅是 32767：
            // 我们引入合理的物理声学底噪补偿（正常静室至少 30dB SPL）：
            val calculatedDb = (20 * log10(rms.coerceAtLeast(1.0))).toInt()
            val db = if (calculatedDb < 30) {
                30 + (calculatedDb / 6) // 平滑拟合到 30-35 dB 的保底底噪
            } else {
                calculatedDb
            }

            Log.d(TAG, "Calculated ambient noise: $db dB (RMS: $rms)")
            return db

        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture audio noise amplitude: ${e.message}")
            return 0
        } finally {
            try {
                audioRecord?.stop()
            } catch (e: Exception) {}
            try {
                audioRecord?.release()
            } catch (e: Exception) {}
        }
    }
}
