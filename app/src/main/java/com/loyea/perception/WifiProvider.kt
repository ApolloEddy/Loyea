package com.loyea.perception

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

class WifiProvider(private val context: Context) {

    /**
     * 获取当前连接的网络场景描述并返回丰富的连接参数
     */
    @SuppressLint("MissingPermission")
    fun getNetworkSsid(): String {
        val appContext = context.applicationContext
        val connManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "No Connection"

        val activeNetwork = connManager.activeNetwork ?: return "Disconnected"
        val capabilities = connManager.getNetworkCapabilities(activeNetwork) ?: return "Disconnected"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                try {
                    val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val info = wifiManager?.connectionInfo
                    if (info != null) {
                        val rawSsid = info.ssid
                        val ssid = if (rawSsid != null && rawSsid != "<unknown ssid>" && rawSsid.isNotBlank() && rawSsid != "0x") {
                            rawSsid.replace("\"", "")
                        } else {
                            null
                        }

                        val rssi = info.rssi
                        val signalLevel = WifiManager.calculateSignalLevel(rssi, 5) // 0-4级
                        val speed = info.linkSpeed
                        val freq = info.frequency

                        val sb = java.lang.StringBuilder()
                        sb.append("Wi-Fi Network")
                        if (ssid != null) {
                            sb.append(" (SSID: $ssid")
                        } else {
                            sb.append(" (SSID: Hidden/No Location Permission")
                        }
                        sb.append(", Signal: $rssi dBm [Level $signalLevel/4]")
                        if (speed > 0) {
                            sb.append(", Speed: ${speed}Mbps")
                        }
                        if (freq > 0) {
                            sb.append(", Freq: ${freq}MHz")
                        }
                        sb.append(")")
                        sb.toString()
                    } else {
                        "Wi-Fi Network (Connected)"
                    }
                } catch (e: SecurityException) {
                    "Wi-Fi Network (Connected, Access Restricted)"
                } catch (t: Throwable) {
                    "Wi-Fi Network (Connected)"
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                "Cellular Mobile Data"
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                "Ethernet Connection"
            }
            else -> {
                "Unknown Network"
            }
        }
    }
}
