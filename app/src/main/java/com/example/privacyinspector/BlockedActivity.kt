package com.example.privacyinspector

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.privacyinspector.databinding.ActivityBlockedBinding

class BlockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Кнопка назад
        binding.btnBack.setOnClickListener { finish() }

        // Настройка списка
        binding.rvBlocked.layoutManager = LinearLayoutManager(this)

        // Берем ТОЛЬКО ЗАБЛОКИРОВАННЫЕ из нашего менеджера
        val blockedLogs = AnalyticsManager.getBlockedLogs()

        // Используем тот же адаптер, что и на главном экране
        val adapter = LogAdapter(blockedLogs)
        binding.rvBlocked.adapter = adapter
    }
}