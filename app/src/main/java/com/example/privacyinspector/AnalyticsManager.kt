package com.example.privacyinspector

import com.example.privacyinspector.Enricher.CompanyInfo

// Модель данных пакета
data class TrafficLog(
    val timestamp: String,
    val domain: String,
    val ipAddress: String,
    val isTracker: Boolean,
    val company: CompanyInfo,
    val protocol: String = "UDP/DNS"
)

// Модель для статистики
data class ThreatStat(val domain: String, var count: Int = 0)

object AnalyticsManager {
    // Хранилище логов в памяти
    private val sessionLogs = mutableListOf<TrafficLog>()

    // Добавление нового лога
    fun addLog(log: TrafficLog) {
        sessionLogs.add(0, log)
        // Ограничиваем размер списка, чтобы не забить память телефона
        if (sessionLogs.size > 2000) {
            sessionLogs.removeAt(sessionLogs.lastIndex)
        }
    }

    // Получить ВСЕ логи
    fun getLogs(): List<TrafficLog> = sessionLogs

    // --- ВОТ ЭТОЙ ФУНКЦИИ НЕ ХВАТАЛО ---
    // Получить ТОЛЬКО заблокированные (для BlockedActivity)
    fun getBlockedLogs(): List<TrafficLog> {
        return sessionLogs.filter { it.isTracker }
    }
    // -----------------------------------

    // Топ-5 угроз для главного экрана
    fun getTopThreats(): List<ThreatStat> {
        return sessionLogs
            .filter { it.isTracker }
            .groupBy { it.domain }
            .map { entry -> ThreatStat(entry.key, entry.value.size) }
            .sortedByDescending { it.count }
            .take(5)
    }

    // Статистика
    fun getTotalRequests() = sessionLogs.size
    fun getBlockedCount() = sessionLogs.count { it.isTracker }

    // Расчет риска (процент плохих пакетов)
    fun getRiskPercentage(): Int {
        if (sessionLogs.isEmpty()) return 0
        return (getBlockedCount() * 100) / sessionLogs.size
    }
}