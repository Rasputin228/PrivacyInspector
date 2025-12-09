package com.example.privacyinspector

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object BlocklistManager {
    private val blockedDomains = HashSet<String>()
    private const val SOURCE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    suspend fun loadBlocklist() {
        withContext(Dispatchers.IO) {
            try {
                val text = URL(SOURCE_URL).readText()
                text.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("\\s+".toRegex())
                        if (parts.size >= 2) blockedDomains.add(parts[1])
                    }
                }
            } catch (e: Exception) {
                // Фолбэк
                blockedDomains.addAll(listOf("google-analytics.com", "doubleclick.net", "facebook.com"))
            }
        }
    }
    fun isTracker(domain: String) = blockedDomains.contains(domain)
    fun getSize() = blockedDomains.size
}