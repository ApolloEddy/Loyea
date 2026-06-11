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
        if (useRealLocation) {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            if (!hasFine && !hasCoarse) {
                return "[Error: Location Permission Denied. User must grant GPS permission in Settings]"
            }
                
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
            val isNetworkEnabled = try { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
            
            if (!isGpsEnabled && !isNetworkEnabled) {
                return "[Error: GPS and Network positioning are both DISABLED on this device]"
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
                    return "[Error: No Last Known Location found. Try opening a Map app to get a fresh GPS fix first]"
                }
            } catch (e: SecurityException) {
                return "[Error: System Security Exception while accessing location]"
            }
        }
        
        // ONLY if useRealLocation is FALSE, we return mock
        val mock = prefs.getString("mock_location", "39.9042, 116.4074") ?: "39.9042, 116.4074"
        return "Mock (Simulation On): $mock"
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
