package com.example.stocktracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.stocktracker.ui.StockApp

class MainActivity : ComponentActivity() {

    /** 系统通知点按带来的消息详情（由 Intent extras 携带，onNewIntent 也会更新） */
    private var pendingDetail by mutableStateOf<NotificationDetail?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PredictionNotifier.ensureChannel(this)
        PredictionScheduler.ensureScheduled(this)
        setContent {
            StockApp(
                pendingDetail = pendingDetail,
                onConsumeDetail = { pendingDetail = null }
            )
        }
        pendingDetail = detailFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDetail = detailFromIntent(intent)
    }

    private fun detailFromIntent(intent: Intent?): NotificationDetail? {
        if (intent == null) return null
        val title = intent.getStringExtra(PredictionNotifier.EXTRA_TITLE) ?: return null
        val body = intent.getStringExtra(PredictionNotifier.EXTRA_BODY) ?: return null
        val kind = intent.getStringExtra(PredictionNotifier.EXTRA_KIND) ?: ""
        return NotificationDetail(kindLabel = kind, title = title, body = body)
    }
}
