package com.example.stocktracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 通知类型 */
enum class NotificationKind(val label: String) {
    AUCTION("竞价预测"),
    INTRADAY("盘中信号")
}

/** 一条应用内通知历史（系统通知之外，落盘可回看） */
data class AppNotification(
    val timeMs: Long,
    val kind: NotificationKind,
    val stock: String,   // 股票名
    val title: String,
    val body: String
)

/** 通知历史持久化（v13）：竞价预测 + 盘中信号通知统一落盘，右上角铃铛可回看、可一键清空 */
interface NotificationLogStore {
    fun load(): List<AppNotification>
    fun add(n: AppNotification)
    fun clear()
}

/** 本地实现：SharedPreferences + JSON，最新在前，保留最近 MAX_ENTRIES 条 */
class PrefsNotificationLogStore(context: Context) : NotificationLogStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("notification_log_prefs", Context.MODE_PRIVATE)

    override fun load(): List<AppNotification> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return parse(raw).sortedByDescending { it.timeMs }
    }

    override fun add(n: AppNotification) {
        val list = (listOf(n) + load()).take(MAX_ENTRIES)
        prefs.edit().putString(KEY, build(list)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "notifications"
        const val MAX_ENTRIES = 300
    }
}

private fun build(list: List<AppNotification>): String =
    JSONArray().apply {
        list.forEach { n ->
            put(JSONObject()
                .put("timeMs", n.timeMs)
                .put("kind", n.kind.name)
                .put("stock", n.stock)
                .put("title", n.title)
                .put("body", n.body))
        }
    }.toString()

private fun parse(raw: String): List<AppNotification> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            AppNotification(
                timeMs = o.getLong("timeMs"),
                kind = NotificationKind.valueOf(o.getString("kind")),
                stock = o.getString("stock"),
                title = o.getString("title"),
                body = o.getString("body")
            )
        }.getOrNull()
    }
}