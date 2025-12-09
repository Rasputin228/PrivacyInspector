package com.example.privacyinspector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.privacyinspector.databinding.ActivityMainBinding
import com.example.privacyinspector.databinding.ItemLogBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// --- ЛЕКАРСТВО ОТ КРАШЕЙ ---
// Этот класс перехватывает ошибки при обновлении списка
class WrapContentLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e("RV", "Inconsistency detected! Ignored.")
        }
    }
}
// ---------------------------

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logList = mutableListOf<TrafficLog>()
    private val logAdapter = LogAdapter(logList)
    private var isVpnRunning = false

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "VPN_LOG_UPDATE") {
                val domain = intent.getStringExtra("domain") ?: return
                val isTracker = intent.getBooleanExtra("isTracker", false)

                // Добавляем в аналитику
                val logItem = TrafficLog("now", domain, isTracker)
                AnalyticsManager.addLog(logItem)

                // Обновляем UI безопасно
                updateLogUI(logItem)
                updateStatsUI()

            } else if (intent?.action == "VPN_STATUS") {
                isVpnRunning = intent.getBooleanExtra("running", false)
                updateButtonState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()

        CoroutineScope(Dispatchers.Main).launch {
            binding.tvDatabaseSize.text = "Загрузка базы..."
            BlocklistManager.loadBlocklist()
            binding.tvDatabaseSize.text = "База защиты: ${BlocklistManager.getSize()} доменов"
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(vpnReceiver,
            IntentFilter().apply {
                addAction("VPN_LOG_UPDATE")
                addAction("VPN_STATUS")
            }
        )
    }

    private fun setupUI() {
        // Используем наш безопасный менеджер
        binding.rvLogs.layoutManager = WrapContentLinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter

        binding.btnVpnToggle.setOnClickListener {
            if (isVpnRunning) {
                startService(Intent(this, PrivacyVpnService::class.java).setAction("STOP"))
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 0)
                } else {
                    onActivityResult(0, RESULT_OK, null)
                }
            }
        }
    }

    private fun updateLogUI(log: TrafficLog) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val displayLog = log.copy(timestamp = time)

        logList.add(0, displayLog)
        if (logList.size > 100) logList.removeAt(logList.lastIndex)

        // Используем notifyDataSetChanged() - это менее красиво, но на 100% стабильно
        logAdapter.notifyDataSetChanged()
    }

    private fun updateStatsUI() {
        val topThreats = AnalyticsManager.getTopThreats()
        val riskPercent = AnalyticsManager.getRiskPercentage()
        val total = AnalyticsManager.getTotalRequests()
        val blocked = AnalyticsManager.getBlockedCount()

        val sb = StringBuilder()
        sb.append("Запросов: $total | Трекеров: $blocked\n\n")

        if (topThreats.isEmpty()) {
            sb.append("Ожидание данных...")
        } else {
            sb.append("ТОП-5 УГРОЗ:\n")
            topThreats.forEachIndexed { index, stat ->
                sb.append("${index + 1}. ${stat.domain} (${stat.count})\n")
            }
        }

        binding.tvStats.text = sb.toString()
        binding.progressRisk.progress = riskPercent

        val color = if (riskPercent < 5) Color.GREEN else if (riskPercent < 20) Color.YELLOW else Color.RED
        binding.tvRiskPercent.setTextColor(color)
        binding.tvRiskPercent.text = "Риск: $riskPercent%"
        binding.progressRisk.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun updateButtonState() {
        if (isVpnRunning) {
            binding.btnVpnToggle.text = "STOP"
            binding.btnVpnToggle.background.setTint(Color.RED)
        } else {
            binding.btnVpnToggle.text = "START"
            binding.btnVpnToggle.background.setTint(Color.GREEN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            startService(Intent(this, PrivacyVpnService::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver)
        } catch (e: Exception) {}
    }
}

class LogAdapter(private val logs: List<TrafficLog>) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {
    class LogViewHolder(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        return LogViewHolder(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        // Добавляем защиту от выхода за границы
        if (position >= logs.size) return

        val log = logs[position]
        holder.binding.tvTime.text = log.timestamp
        holder.binding.tvDomain.text = log.domain

        if (log.isTracker) {
            holder.binding.tvStatus.text = "TRACKER"
            holder.binding.tvStatus.setTextColor(Color.RED)
        } else {
            holder.binding.tvStatus.text = "OK"
            holder.binding.tvStatus.setTextColor(Color.parseColor("#388E3C"))
        }
    }
    override fun getItemCount() = logs.size
}