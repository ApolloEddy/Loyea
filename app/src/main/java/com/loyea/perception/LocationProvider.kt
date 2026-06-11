package com.loyea.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

class LocationProvider(private val context: Context) {
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)

    fun getCurrentLocation(): String {
        val useRealLocation = prefs.getBoolean("use_real_location", false)
        if (!useRealLocation) {
            return "[物理定位感知开关已关闭。用户目前在应用的 设置 -> 物理感知与外设集成 页面中关闭了“获取真实物理定位”开关。请以友好的话术告知用户，并引导其前往开启此开关以获取其所处位置。]"
        }
        
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        if (!hasFine && !hasCoarse) {
            return "[地理定位权限未授予。用户虽然开启了“获取真实物理定位”开关，但尚未在手机系统设置中授予 Loyea 应用定位权限。请引导用户前往系统设置授予定位权限。]"
        }
            
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        val isNetworkEnabled = try { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
        
        if (!isGpsEnabled && !isNetworkEnabled) {
            return "[GPS和网络定位服务均未开启。虽然定位权限已授予，但系统级的 GPS 开关未打开，请引导用户开启手机定位服务。]"
        }

        try {
            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null
            for (provider in providers) {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                        bestLocation = location
                    }
                }
            }
            if (bestLocation != null) {
                return "${bestLocation.latitude}, ${bestLocation.longitude}"
            } else {
                return "[定位获取超时，无法找到上一次的位置缓存。请引导用户尝试在系统自带地图中刷新定位后重试。]"
            }
        } catch (e: SecurityException) {
            return "[读取系统定位时发生安全异常，可能是系统拦截了权限。]"
        }
    }

    fun setMockLocation(location: String) {
        prefs.edit().putString("mock_location", location).apply()
    }
    
    fun setUseRealLocation(use: Boolean) {
        prefs.edit().putBoolean("use_real_location", use).apply()
    }
    
    fun isUsingRealLocation(): Boolean {
        return prefs.getBoolean("use_real_location", false)
    }
    
    fun getMockLocation(): String {
        return prefs.getString("mock_location", "39.9042, 116.4074") ?: "39.9042, 116.4074"
    }
}
