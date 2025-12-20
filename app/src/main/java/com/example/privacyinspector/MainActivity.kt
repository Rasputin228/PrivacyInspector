package com.example.privacyinspector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

class WrapContentLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try { super.onLayoutChildren(recycler, state) } catch (e: IndexOutOfBoundsException) {}
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logAdapter = LogAdapter(AnalyticsManager.getLogs())
    private var isVpnRunning = false

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "VPN_LOG_UPDATE") {
                val domain = intent.getStringExtra("domain") ?: return
                val isTracker = intent.getBooleanExtra("isTracker", false)

                val companyInfo = Enricher.identifyCompany(domain)
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                val logItem = TrafficLog(time, domain, "DNS", isTracker, companyInfo)
                AnalyticsManager.addLog(logItem)

                logAdapter.notifyItemInserted(0)
                if ((binding.rvLogs.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() == 0) {
                    binding.rvLogs.scrollToPosition(0)
                }
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

        // Показываем текущий DNS
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val currentDns = prefs.getString("dns_server", "1.1.1.1")
        binding.tvCurrentDns.text = "Server: $currentDns"

        CoroutineScope(Dispatchers.Main).launch {
            BlocklistManager.loadBlocklist()
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(vpnReceiver,
            IntentFilter().apply {
                addAction("VPN_LOG_UPDATE")
                addAction("VPN_STATUS")
            }
        )
    }

    private fun setupUI() {
        binding.rvLogs.layoutManager = WrapContentLinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter
        (binding.rvLogs.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false

        // КНОПКА ЗАПУСКА (Огромная, по центру)
        binding.btnPower.setOnClickListener {
            toggleVpn()
        }
        binding.cardBlocked.setOnClickListener {
            val intent = Intent(this, BlockedActivity::class.java)
            startActivity(intent)
        }

        // НАСТРОЙКИ (Свой DNS)
        binding.btnSettings.setOnClickListener {
            showDnsDialog()
        }
    }

    private fun showDnsDialog() {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val currentDns = prefs.getString("dns_server", "1.1.1.1")

        val input = EditText(this)
        input.setText(currentDns)
        input.setHint("e.g. 1.1.1.1 or 8.8.8.8")
        input.setPadding(50, 50, 50, 50)
        input.setTextColor(Color.BLACK)

        AlertDialog.Builder(this)
            .setTitle("Custom DNS Server")
            .setMessage("Введите IP адрес DNS сервера (например 94.140.14.14 для AdGuard)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newDns = input.text.toString().trim()
                if (newDns.isNotEmpty()) {
                    prefs.edit().putString("dns_server", newDns).apply()
                    binding.tvCurrentDns.text = "Server: $newDns"
                    Toast.makeText(this, "DNS сохранен. Перезапустите VPN.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleVpn() {
        if (isVpnRunning) {
            startService(Intent(this, PrivacyVpnService::class.java).setAction("STOP"))
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, 0)
            else onActivityResult(0, RESULT_OK, null)
        }
    }

    private fun updateStatsUI() {
        binding.tvTotalCount.text = AnalyticsManager.getTotalRequests().toString()
        binding.tvBlockedCount.text = AnalyticsManager.getBlockedCount().toString()
    }

    private fun updateButtonState() {
        if (isVpnRunning) {
            binding.tvStatus.text = "CONNECTED"
            binding.tvStatus.setTextColor(getColor(R.color.neon_green))
            binding.btnPower.setColorFilter(getColor(R.color.neon_green))
            binding.btnPower.setBackgroundResource(R.drawable.circle_bg) // Можно сделать светящийся фон
        } else {
            binding.tvStatus.text = "DISCONNECTED"
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
            binding.btnPower.setColorFilter(getColor(R.color.text_secondary))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) startService(Intent(this, PrivacyVpnService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver) } catch (e: Exception) {}
    }
}

// ... LogAdapter оставь тот же, только можно поменять цвета текста на светлые для темной темы ...
class LogAdapter(private val logs: List<TrafficLog>) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {
    class LogViewHolder(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LogViewHolder(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        if (position >= logs.size) return
        val log = logs[position]
        holder.binding.tvTime.text = log.timestamp
        holder.binding.tvDomain.text = log.domain
        holder.binding.tvCompany.text = log.company.name
        holder.binding.tvCategory.text = log.company.category

        // Для темной темы лучше сделать текст белым
        holder.binding.tvDomain.setTextColor(Color.WHITE)

        if (log.isTracker) {
            holder.binding.tvStatus.text = "BLOCKED"
            holder.binding.tvStatus.setTextColor(Color.parseColor("#FF1744"))
        } else {
            holder.binding.tvStatus.text = "OK"
            holder.binding.tvStatus.setTextColor(Color.parseColor("#00E676"))
        }
    }
    override fun getItemCount() = logs.size
}