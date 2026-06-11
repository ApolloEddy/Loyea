package com.loyea.perception

interface WatchProvider {
    fun getHeartRateBpm(): Int
    fun getMovementState(): String // "Resting" | "Moving"
    fun isWatchConnected(): Boolean
    fun setSimulationState(isMoving: Boolean)
    fun setWatchConnected(connected: Boolean)
}
