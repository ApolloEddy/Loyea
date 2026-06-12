package com.loyea.perception

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

class WifiProvider(private val context: Context) {

    /**
     * 获取当前连接的网络场景描述（Wi-Fi SSID 或蜂窝网络）
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
                // 如果是 Wi-Fi，尝试获取具体的 SSID
                try {
                    val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val info = wifiManager?.connectionInfo
                    val rawSsid = info?.ssid
                    
                    if (rawSsid != null && rawSsid != "<unknown ssid>" && rawSsid.isNotBlank() && rawSsid != "0x") {
                        rawSsid.replace("\"", "") // 去除 SSID 包含的双引号
                    } else {
                        "Wi-Fi Network" // 权限不足或 GPS 关闭时的通用返回
                    }
                } catch (e: SecurityException) {
                    "Wi-Fi Network"
                } catch (t: Throwable) {
                    "Wi-Fi Network"
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
