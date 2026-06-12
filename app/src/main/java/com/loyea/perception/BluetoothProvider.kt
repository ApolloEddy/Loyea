package com.loyea.perception

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothProvider(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * 获取蓝牙设备连接状态总结
     */
    fun getBluetoothStatus(): String {
        try {
            val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            }

            if (bluetoothAdapter == null) {
                return "Bluetooth Not Supported"
            }
            val isEnabled = try { bluetoothAdapter.isEnabled } catch (e: SecurityException) { false }
            if (!isEnabled) {
                return "Disabled (蓝牙已关闭)"
            }

            val connectedDevices = mutableListOf<String>()

            if (hasConnectPermission) {
                try {
                    // 1. 获取所有已配对设备并反射检测在线连接状态
                    val bonded = bluetoothAdapter.bondedDevices
                    if (bonded != null) {
                        for (device in bonded) {
                            val isConnected = try {
                                val method = device.javaClass.getMethod("isConnected")
                                method.invoke(device) as Boolean
                            } catch (e: Exception) {
                                false
                            }
                            if (isConnected) {
                                val deviceName = device.name ?: "Unknown Device"
                                val majorClass = device.bluetoothClass?.majorDeviceClass ?: 0
                                val typeStr = when (majorClass) {
                                    android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
                                    android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "Wearable"
                                    android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
                                    android.bluetooth.BluetoothClass.Device.Major.HEALTH -> "Health"
                                    android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "Computer"
                                    android.bluetooth.BluetoothClass.Device.Major.PHONE -> "Phone"
                                    else -> "Other"
                                }
                                val battery = getDeviceBattery(device)
                                val batterySuffix = if (battery in 0..100) ", Battery: $battery%" else ""
                                connectedDevices.add("$deviceName ($typeStr$batterySuffix)")
                            }
                        }
                    }

                    // 2. 如果没有检测到任何已连接设备，则使用传统的 Audio Profile 进行状态探测作为兜底
                    if (connectedDevices.isEmpty()) {
                        val a2dpState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP)
                        val headsetState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                        val isAudioConnected = a2dpState == BluetoothProfile.STATE_CONNECTED || headsetState == BluetoothProfile.STATE_CONNECTED
                        
                        if (isAudioConnected) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
                                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                                for (device in devices) {
                                    if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                        val name = device.productName?.toString() ?: "Unknown Bluetooth Audio Device"
                                        connectedDevices.add("$name (Audio)")
                                    }
                                }
                            }
                        }
                        if (connectedDevices.isEmpty() && isAudioConnected) {
                            connectedDevices.add("Bluetooth Audio Device (Connected)")
                        }
                    }
                } catch (e: SecurityException) {
                    // fallback to AudioManager
                }
            }

            if (connectedDevices.isEmpty() && audioManager != null) {
                val isA2dpOn = try { audioManager.isBluetoothA2dpOn } catch (e: Exception) { false }
                val isScoOn = try { audioManager.isBluetoothScoOn } catch (e: Exception) { false }
                if (isA2dpOn || isScoOn) {
                    connectedDevices.add("Bluetooth Audio Device (Active)")
                }
            }

            val isPlaying = audioManager?.isMusicActive == true
            val volumeStr = if (audioManager != null) {
                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val pct = if (maxVol > 0) (curVol * 100) / maxVol else 0
                "$pct% ($curVol/$maxVol)"
            } else {
                "Unknown"
            }

            return if (connectedDevices.isEmpty()) {
                "No devices connected"
            } else {
                val devList = connectedDevices.joinToString(", ")
                "Connected: $devList | Audio Active: ${if (isPlaying) "Yes" else "No"} | Volume: $volumeStr"
            }
        } catch (t: Throwable) {
            return "Bluetooth Error"
        }
    }

    /**
     * 获取特定蓝牙设备的电量百分比
     * 不支持或未连接时返回 -1
     */
    fun getDeviceBattery(device: android.bluetooth.BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            method.invoke(device) as Int
        } catch (e: Exception) {
            -1
        }
    }
}
