package com.loyea.perception

import android.content.Context
import kotlin.random.Random

class MockWatchProvider(private val context: Context) : WatchProvider {
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)

    override fun getHeartRateBpm(): Int {
        if (!isWatchConnected()) return 0
        val isMoving = prefs.getBoolean("sim_watch_moving", false)
        return if (isMoving) {
            Random.nextInt(100, 140)
        } else {
            Random.nextInt(60, 90)
        }
    }

    override fun getMovementState(): String {
        return if (prefs.getBoolean("sim_watch_moving", false)) "Moving" else "Resting"
    }

    override fun isWatchConnected(): Boolean {
        return prefs.getBoolean("sim_watch_connected", false)
    }

    override fun setSimulationState(isMoving: Boolean) {
        prefs.edit().putBoolean("sim_watch_moving", isMoving).apply()
    }

    override fun setWatchConnected(connected: Boolean) {
        prefs.edit().putBoolean("sim_watch_connected", connected).apply()
    }
}
