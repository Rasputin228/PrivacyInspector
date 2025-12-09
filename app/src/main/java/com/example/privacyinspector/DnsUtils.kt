package com.example.privacyinspector

object DnsUtils {
    fun getDomainFromPacket(packet: ByteArray): String? {
        try {
            if (packet.isEmpty()) return null
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            if (packet[9].toInt() != 17) return null // UDP only

            val udpDestPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                    (packet[ipHeaderLen + 3].toInt() and 0xFF)

            // Если порт назначения не 53, то это ответ (Source 53), или вообще не DNS
            // Для упрощения парсим, если хоть один порт 53
            if (udpDestPort != 53) return null

            val dnsStart = ipHeaderLen + 8
            val questionStart = dnsStart + 12
            if (questionStart >= packet.size) return null

            return parseDnsName(packet, questionStart)
        } catch (e: Exception) { return null }
    }

    private fun parseDnsName(data: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        var pos = offset
        var jumps = 0
        while (pos < data.size && jumps < 100) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) break
            if (sb.isNotEmpty()) sb.append(".")
            for (i in 1..len) {
                if (pos + i < data.size) sb.append(data[pos + i].toChar())
            }
            pos += len + 1
            jumps++
        }
        return sb.toString()
    }
}