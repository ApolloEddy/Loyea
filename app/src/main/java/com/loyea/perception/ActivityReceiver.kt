package com.loyea.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent)
                val mostProbableActivity = result?.mostProbableActivity
                mostProbableActivity?.let {
                    val typeStr = when (it.type) {
                        DetectedActivity.IN_VEHICLE -> "In Vehicle (乘车)"
                        DetectedActivity.ON_BICYCLE -> "On Bicycle (骑行)"
                        DetectedActivity.ON_FOOT -> "On Foot (步行中)"
                        DetectedActivity.RUNNING -> "Running (跑步中)"
                        DetectedActivity.STILL -> "Still (静止)"
                        DetectedActivity.TILTING -> "Tilting (倾斜手机)"
                        DetectedActivity.WALKING -> "Walking (步行中)"
                        else -> "Unknown"
                    }
                    Log.d("ActivityReceiver", "Detected activity: $typeStr with confidence ${it.confidence}")
                    
                    val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("google_activity_state", typeStr)
                        .putLong("google_activity_time", System.currentTimeMillis())
                        .apply()
                }
            }
        } catch (e: Exception) {
            Log.e("ActivityReceiver", "Error receiving activity updates", e)
        }
    }
}
