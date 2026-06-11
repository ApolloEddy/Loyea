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
        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        if (bluetoothAdapter == null) {
            return "Bluetooth Not Supported"
        }
        if (!bluetoothAdapter.isEnabled) {
            return "Disabled (蓝牙已关闭)"
        }

        val connectedDevices = mutableListOf<String>()

        if (hasConnectPermission) {
            try {
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

        return if (connectedDevices.isEmpty()) {
            "No devices connected"
        } else {
            "Connected: ${connectedDevices.joinToString(", ")}"
        }
    }
}
