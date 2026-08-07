package com.example.stocktracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 竞价预测本地通知 */
object PredictionNotifier {

    const val CHANNEL_ID = "prediction"
    const val NOTIFICATION_ID = 2001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "竞价预测", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "每日 9:25 竞价预测结果" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun post(context: Context, results: List<PredictionResult>) {
        if (results.isEmpty()) return
        // v13：竞价预测通知同步落盘到应用内历史（右上角铃铛可回看）
        runCatching {
            val store = PrefsNotificationLogStore(context)
            val now = System.currentTimeMillis()
            results.forEach { r ->
                store.add(
                    AppNotification(
                        timeMs = now,
                        kind = NotificationKind.AUCTION,
                        stock = r.stockName,
                        title = "${r.stockName} ${r.conclusion.label}",
                        body = "评分 ${formatScore(r.score)}，置信 ${r.confidence} · ${r.suggestion}"
                    )
                )
            }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = context.applicationInfo.icon ?: android.R.drawable.ic_dialog_info
        val title = "竞价预测：" + results.joinToString(" · ") { "${it.stockName} ${it.conclusion.label}" }
        val text = results.joinToString("\n") {
            "${it.stockName}（${it.stockCode}）：${it.conclusion.label}（评分 ${formatScore(it.score)}，置信 ${it.confidence}）· ${it.suggestion}"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(results.first().let { "${it.stockName} ${it.conclusion.label}（评分 ${formatScore(it.score)}）" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun formatScore(v: Double): String = if (v >= 0) "+" + String.format(java.util.Locale.US, "%.1f", v)
    else String.format(java.util.Locale.US, "%.1f", v)
}
