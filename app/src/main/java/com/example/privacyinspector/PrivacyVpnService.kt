package com.example.privacyinspector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class PrivacyVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "PrivacyInspectorVPN"
        const val NOTIFICATION_ID = 1

        init {
            try { System.loadLibrary("native-lib") }
            catch (e: Throwable) { Log.e("VPN", "Native Lib Error", e) }
        }
    }

    private external fun nativeGetDomain(packet: ByteArray): String?

    private var vpnInterface: ParcelFileDescriptor? = null

    // Пул потоков для сети (чтобы не создавать тысячи, а использовать 50 рабочих)
    private val dispatcher = Executors.newFixedThreadPool(50).asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(dispatcher + SupervisorJob())
    private var job: Job? = null

    private val VIRTUAL_DNS = "10.0.0.5"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
        } else {
            // 1. Создаем канал уведомлений (нужен для Android 8+)
            createNotificationChannel()
            // 2. Создаем само уведомление
            val notification = createNotification()

            // 3. Запускаем Foreground (для Android 14+ тип specialUse)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (job?.isActive == true) return

        job = serviceScope.launch {
            try {
                val builder = Builder()
                builder.setSession("Privacy Inspector")
                builder.addAddress("10.0.0.2", 32)
                builder.addRoute(VIRTUAL_DNS, 32)
                builder.addDnsServer(VIRTUAL_DNS)
                builder.setMtu(1500)
                builder.setBlocking(true)

                vpnInterface = builder.establish() ?: return@launch

                // Сообщаем UI, что мы запустились
                broadcastStatus(true)
                Log.d("VPN", "VPN Started")

                val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
                val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
                val buffer = ByteArray(4096)

                while (isActive) {
                    val length = vpnInput.read(buffer)
                    if (length > 0) {
                        val packetData = buffer.copyOf(length)

                        // Асинхронно обрабатываем пакет
                        launch {
                            processOutgoingPacket(packetData, vpnOutput)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VPN", "Fatal Error", e)
            } finally {
                stopVpn()
            }
        }
    }

    // Тот самый метод обработки, который мы вызывали
    private fun processOutgoingPacket(packetData: ByteArray, vpnOutput: FileOutputStream) {
        if (packetData.size <= 28) return

        var socket: DatagramSocket? = null
        try {
            // Аналитика C++
            val domain = nativeGetDomain(packetData)
            if (domain != null && domain.isNotEmpty()) {
                sendLogToUI(domain, BlocklistManager.isTracker(domain))
            }

            // Получаем IP твоего сервера из настроек
            val prefs = getSharedPreferences("vpn_prefs", android.content.Context.MODE_PRIVATE)
            val serverIpStr = prefs.getString("dns_server", "1.1.1.1") ?: "1.1.1.1"
            val destIp = InetAddress.getByName(serverIpStr)

            // Создаем сокет
            socket = DatagramSocket()
            if (!protect(socket)) return

            // Отправляем
            val dnsPayload = packetData.copyOfRange(28, packetData.size)
            val outPacket = DatagramPacket(dnsPayload, dnsPayload.size, destIp, 53)
            socket.send(outPacket)

            // Ждем ответ (макс 2 сек)
            val respBuf = ByteArray(4096)
            val inPacket = DatagramPacket(respBuf, respBuf.size)
            socket.soTimeout = 2000
            socket.receive(inPacket)

            if (inPacket.length > 0) {
                val dnsResponse = inPacket.data.copyOf(inPacket.length)
                val fakePacket = createFakeDnsResponse(packetData, dnsResponse)

                // Синхронно пишем в файл
                synchronized(vpnOutput) {
                    vpnOutput.write(fakePacket)
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки сети
        } finally {
            socket?.close()
        }
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ (Которые у тебя горели красным) ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, PrivacyVpnService::class.java).setAction("STOP")
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Privacy Inspector")
            .setContentText("Защита активна. Трафик фильтруется.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "ОСТАНОВИТЬ", pendingStop)
            .setOngoing(true)
            .build()
    }

    private fun broadcastStatus(active: Boolean) {
        val intent = Intent("VPN_STATUS")
        intent.putExtra("running", active)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendLogToUI(domain: String, isTracker: Boolean) {
        val intent = Intent("VPN_LOG_UPDATE")
        intent.putExtra("domain", domain)
        intent.putExtra("isTracker", isTracker)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stopVpn() {
        job?.cancel()
        job = null
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        broadcastStatus(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- МАТЕМАТИКА ПАКЕТОВ ---

    private fun createFakeDnsResponse(request: ByteArray, responseData: ByteArray): ByteArray {
        val totalLen = 20 + 8 + responseData.size
        val buffer = ByteBuffer.allocate(totalLen)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(0x45.toByte())
        buffer.put(0x00.toByte())
        buffer.putShort(totalLen.toShort())
        buffer.putShort(0)
        buffer.putShort(0x4000.toShort())
        buffer.put(64.toByte())
        buffer.put(17.toByte())
        buffer.putShort(0)

        buffer.put(byteArrayOf(10, 0, 0, 5))
        buffer.put(request[12]); buffer.put(request[13]); buffer.put(request[14]); buffer.put(request[15])

        val ipChecksum = calculateChecksum(buffer.array(), 20)
        buffer.putShort(10, ipChecksum)

        buffer.putShort(53)
        val dstPort = ((request[20].toInt() and 0xFF) shl 8) or (request[21].toInt() and 0xFF)
        buffer.putShort(dstPort.toShort())

        val udpLen = 8 + responseData.size
        buffer.putShort(udpLen.toShort())
        buffer.putShort(0)

        buffer.put(responseData)
        return buffer.array()
    }

    private fun calculateChecksum(data: ByteArray, len: Int): Short {
        var sum = 0
        var i = 0
        while (i < len - 1) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = data[i + 1].toInt() and 0xFF
            sum += (b1 shl 8) + b2
            i += 2
        }
        if (i < len) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toShort()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }
}