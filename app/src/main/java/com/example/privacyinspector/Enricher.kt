package com.example.privacyinspector

object Enricher {

    data class CompanyInfo(
        val name: String,
        val category: String,
        val iconRes: String // Тут можно было бы ставить иконки, пока заглушка
    )

    // Простая база знаний (в реальном приложении это качается с сервера)
    fun identifyCompany(domain: String): CompanyInfo {
        val d = domain.lowercase()

        return when {
            d.contains("google") || d.contains("gstatic") || d.contains("1e100") ->
                CompanyInfo("Google LLC", "Services & Search", "G")
            d.contains("facebook") || d.contains("fbcdn") || d.contains("instagram") ->
                CompanyInfo("Meta Platforms", "Social Network", "F")
            d.contains("vk.com") || d.contains("userapi") || d.contains("vkuser") ->
                CompanyInfo("VK Corp", "Social Network", "V")
            d.contains("yandex") || d.contains("ya.ru") ->
                CompanyInfo("Yandex", "Search & Services", "Y")
            d.contains("whatsapp") ->
                CompanyInfo("WhatsApp", "Messenger", "W")
            d.contains("telegram") || d.contains("t.me") ->
                CompanyInfo("Telegram", "Messenger", "T")
            d.contains("tiktok") || d.contains("bytedance") ->
                CompanyInfo("TikTok", "Social Video", "TT")
            d.contains("apple") || d.contains("icloud") ->
                CompanyInfo("Apple Inc.", "Cloud Services", "A")
            d.contains("microsoft") || d.contains("live.com") ->
                CompanyInfo("Microsoft", "OS & Services", "M")
            d.contains("amazon") || d.contains("aws") ->
                CompanyInfo("Amazon AWS", "Cloud Hosting", "AWS")
            BlocklistManager.isTracker(domain) ->
                CompanyInfo("Ad Network", "Tracker / Ads", "AD")
            else ->
                CompanyInfo("Unknown Host", "Web / Content", "?")
        }
    }

    // Эмуляция определения IP (так как мы пересылаем на 8.8.8.8, мы не видим реальный IP ответа в Java слое просто так)
    // Для красоты будем генерировать "похожий" IP или писать DNS
    fun fakeIpResolution(domain: String): String {
        return "DNS Lookup"
    }
}