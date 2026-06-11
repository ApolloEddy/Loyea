package com.loyea.perception

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WeatherProvider(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun getLiveWeather(locationString: String?): String = withContext(Dispatchers.IO) {
        val query = resolveQuery(locationString)
        fetchWeatherFromWttr(query)
    }

    suspend fun getWeatherForecast(locationString: String?): String = withContext(Dispatchers.IO) {
        val query = resolveQuery(locationString)
        val url = "https://wttr.in/$query?format=j1&lang=zh"
        val request = Request.Builder()
            .url(url)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Weather Forecast Service Temp Unavailable (HTTP ${response.code})"
                }
                val bodyStr = response.body?.string() ?: return@withContext "No Weather Data"
                val json = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                
                val nearestArea = json.getAsJsonArray("nearest_area")?.firstOrNull()?.asJsonObject
                val areaName = nearestArea?.getAsJsonArray("areaName")?.firstOrNull()?.asJsonObject?.get("value")?.asString ?: query
                val country = nearestArea?.getAsJsonArray("country")?.firstOrNull()?.asJsonObject?.get("value")?.asString ?: ""
                
                val weatherArray = json.getAsJsonArray("weather")
                if (weatherArray == null || weatherArray.size() == 0) {
                    return@withContext "No forecast data found for $areaName"
                }
                
                val sb = java.lang.StringBuilder()
                sb.append("城市/地区: $areaName ($country)\n")
                sb.append("未来 3 天天气预报:\n")
                
                for (i in 0 until minOf(weatherArray.size(), 3)) {
                    val day = weatherArray.get(i).asJsonObject
                    val date = day.get("date")?.asString ?: ""
                    val maxTemp = day.get("maxtempC")?.asString ?: ""
                    val minTemp = day.get("mintempC")?.asString ?: ""
                    
                    val hourly = day.getAsJsonArray("hourly")
                    val midHour = if (hourly != null && hourly.size() > 4) hourly.get(4).asJsonObject else hourly?.firstOrNull()?.asJsonObject
                    val desc = midHour?.getAsJsonArray("lang_zh")?.firstOrNull()?.asJsonObject?.get("value")?.asString
                        ?: midHour?.getAsJsonArray("weatherDesc")?.firstOrNull()?.asJsonObject?.get("value")?.asString
                        ?: "未知"
                        
                    sb.append("- 日期: $date, 天气情况: $desc, 温度区间: $minTemp°C ~ $maxTemp°C\n")
                }
                sb.toString().trim()
            }
        } catch (e: Exception) {
            Log.e("WeatherProvider", "Error fetching weather forecast", e)
            "Offline / Network Error: Unable to fetch forecast data"
        }
    }

    private fun resolveQuery(locationString: String?): String {
        if (locationString.isNullOrEmpty() || locationString.contains("Error")) {
            return "Beijing"
        }
        
        // 过滤掉 Mock 文本前缀
        val cleanLoc = if (locationString.startsWith("Mock (Simulation On): ")) {
            locationString.removePrefix("Mock (Simulation On): ")
        } else {
            locationString
        }

        val coords = cleanLoc.split(",")
        if (coords.size >= 2) {
            val lat = coords[0].trim()
            val lon = coords[1].trim()
            return "$lat,$lon"
        }
        return cleanLoc
    }

    private fun fetchWeatherFromWttr(query: String): String {
        // 使用 ?m&... 强制使用公制单位（摄氏度）
        val url = "https://wttr.in/$query?m&format=%c+%C+%t+%h&lang=zh"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "curl") 
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val result = response.body?.string()?.trim() ?: "No Weather Data"
                    if (result.contains("Unknown location") || result.contains("<html>") || result.length > 50) {
                        "Weather Service Temp Unavailable"
                    } else {
                        result
                    }
                } else {
                    "Weather Code ${response.code}"
                }
            }
        } catch (e: Exception) {
            Log.e("WeatherProvider", "Error fetching weather", e)
            "Offline / Network Error"
        }
    }
}
