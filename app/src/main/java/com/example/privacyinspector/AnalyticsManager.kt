package com.example.privacyinspector

data class ThreatStat(val domain: String, var count: Int = 0)
data class TrafficLog(val timestamp: String, val domain: String, val isTracker: Boolean)

object AnalyticsManager {
    private val sessionLogs = mutableListOf<TrafficLog>()

    fun addLog(log: TrafficLog) { sessionLogs.add(log) }

    fun getTopThreats(): List<ThreatStat> {
        return sessionLogs
            .filter { it.isTracker }
            .groupBy { it.domain }
            .map { entry -> ThreatStat(entry.key, entry.value.size) }
            .sortedByDescending { it.count }
            .take(5)
    }

    fun getTotalRequests() = sessionLogs.size
    fun getBlockedCount() = sessionLogs.count { it.isTracker }

    fun getRiskPercentage(): Int {
        if (sessionLogs.isEmpty()) return 0
        return (getBlockedCount() * 100) / sessionLogs.size
    }
}