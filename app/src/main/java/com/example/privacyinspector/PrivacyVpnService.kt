package com.example.privacyinspector

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class PrivacyVpnService : VpnService() {

    companion object {
        init {
            try { System.loadLibrary("native-lib") }
            catch (e: Throwable) { Log.e("VPN", "Native lib error", e) }
        }
    }

    private external fun nativeGetDomain(packet: ByteArray): String?

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var udpSocket: DatagramSocket? = null

    // Виртуальный DNS (на него телефон шлет запросы)
    private val VIRTUAL_DNS = "10.0.0.5"
    // Реальный DNS (куда мы пересылаем)
    private val UPSTREAM_DNS = "8.8.8.8"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopVpn() else Thread { startVpn() }.start()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            // 1. Сокет для интернета
            udpSocket = DatagramSocket()
            if (!protect(udpSocket)) {
                Log.e("VPN", "PROTECT FAILED")
                return
            }

            // 2. Настройка VPN
            val builder = Builder()
            builder.setSession("PrivacyInspector")
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute(VIRTUAL_DNS, 32) // Ловим только наш DNS
            builder.addDnsServer(VIRTUAL_DNS)
            builder.setMtu(1500)
            builder.setBlocking(true)

            vpnInterface = builder.establish() ?: return
            isRunning = true
            broadcastStatus(true)

            Log.d("VPN", "VPN Online. Listening to $VIRTUAL_DNS")

            // 3. Используем каналы (Channels) для стабильности
            val inChannel = FileInputStream(vpnInterface!!.fileDescriptor).channel
            val outChannel = FileOutputStream(vpnInterface!!.fileDescriptor).channel
            val buffer = ByteBuffer.allocate(4096)

            while (isRunning) {
                // Читаем пакет
                val readBytes = inChannel.read(buffer)
                if (readBytes > 0) {
                    buffer.limit(readBytes)
                    val packetData = ByteArray(readBytes)
                    buffer.flip()
                    buffer.get(packetData)
                    buffer.clear()

                    try {
                        // 1. Логируем (C++)
                        val domain = nativeGetDomain(packetData)
                        if (domain != null && domain.isNotEmpty()) {
                            sendLogToUI(domain, BlocklistManager.isTracker(domain))
                        }

                        // 2. Проверяем, что это DNS (UDP порт 53)
                        // Смещение 20 (байт начала UDP) + 2 (Destination Port)
                        // В Java байты знаковые, поэтому сложная проверка
                        // Но так как мы ловим только 10.0.0.5, считаем что это DNS

                        // 3. Обрабатываем
                        val response = processDns(packetData)
                        if (response != null) {
                            buffer.put(response)
                            buffer.flip()
                            outChannel.write(buffer)
                            buffer.clear()
                        }

                    } catch (e: Exception) {
                        // Log.e("VPN", "Error processing packet", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VPN", "Fatal", e)
        } finally {
            stopVpn()
        }
    }

    private fun processDns(packetData: ByteArray): ByteArray? {
        // Минимальный IP(20) + UDP(8)
        if (packetData.size < 28) return null

        // 1. Извлекаем DNS Payload (вопрос)
        val dnsPayload = packetData.copyOfRange(28, packetData.size)

        // 2. Отправляем в реальный интернет
        val outPacket = DatagramPacket(
            dnsPayload,
            dnsPayload.size,
            InetAddress.getByName(UPSTREAM_DNS),
            53
        )
        udpSocket?.send(outPacket)

        // 3. Ждем ответ
        val respBuf = ByteArray(4096)
        val inPacket = DatagramPacket(respBuf, respBuf.size)
        udpSocket?.soTimeout = 2000 // 2 сек макс

        try {
            udpSocket?.receive(inPacket)
        } catch (e: Exception) {
            return null // Тайм-аут
        }

        if (inPacket.length > 0) {
            val dnsResponse = inPacket.data.copyOf(inPacket.length)
            // 4. Формируем ответ, подменяя заголовки
            return updateHeaders(packetData, dnsResponse)
        }
        return null
    }

    private fun updateHeaders(originalRequest: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeadLen = 20
        val udpHeadLen = 8
        val totalLen = ipHeadLen + udpHeadLen + dnsResponse.size

        val response = ByteBuffer.allocate(totalLen)

        // --- IP HEADER ---
        response.put(originalRequest, 0, 20) // Копируем базу из запроса

        // Исправляем длину
        response.putShort(2, totalLen.toShort())
        // Убираем флаги фрагментации (на всякий случай)
        response.putShort(6, 0)
        // Обнуляем чексумму
        response.putShort(10, 0)

        // МЕНЯЕМ IP МЕСТАМИ
        // Src IP (был Phone, станет 10.0.0.5)
        // 10.0.0.5 = 0A 00 00 05
        response.put(12, 10.toByte())
        response.put(13, 0.toByte())
        response.put(14, 0.toByte())
        response.put(15, 5.toByte())

        // Dest IP (был 10.0.0.5, станет Phone IP из запроса байты 12-15)
        response.put(16, originalRequest[12])
        response.put(17, originalRequest[13])
        response.put(18, originalRequest[14])
        response.put(19, originalRequest[15])

        // СЧИТАЕМ IP CHECKSUM
        val ipChecksum = calculateChecksum(response.array(), 20)
        response.putShort(10, ipChecksum.toShort())

        // --- UDP HEADER ---
        response.position(20)
        // Src Port (53)
        response.putShort(53)
        // Dest Port (порт приложения из запроса, байты 20-21)
        response.put(originalRequest[20])
        response.put(originalRequest[21])

        // Length
        val udpLen = 8 + dnsResponse.size
        response.putShort(udpLen.toShort())
        // Checksum 0 (валидно для UDP IPv4)
        response.putShort(0)

        // --- PAYLOAD ---
        response.put(dnsResponse)

        return response.array()
    }

    // Проверенный алгоритм чексуммы
    private fun calculateChecksum(buf: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len - 1) {
            val b1 = buf[i].toInt() and 0xFF
            val b2 = buf[i + 1].toInt() and 0xFF
            sum += (b1 shl 8) + b2
            i += 2
        }
        if (i < len) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun stopVpn() {
        isRunning = false
        try { udpSocket?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        broadcastStatus(false)
        stopSelf()
    }

    private fun sendLogToUI(domain: String, isTracker: Boolean) {
        val intent = Intent("VPN_LOG_UPDATE")
        intent.putExtra("domain", domain)
        intent.putExtra("isTracker", isTracker)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastStatus(active: Boolean) {
        val intent = Intent("VPN_STATUS")
        intent.putExtra("running", active)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() { super.onDestroy(); stopVpn() }
}