package com.example.stocktracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 盘中信号快照持久化（v9 验证积累：快照 → 结果回填 → 统计） */
interface IntradaySignalStore {
    fun loadSnapshots(stockCode: String, date: String? = null): List<IntradaySignalSnapshot>
    fun addSnapshot(s: IntradaySignalSnapshot)
    fun updateOutcomes(
        stockCode: String,
        timeMs: Long,
        outcome30mPct: Double?,
        outcome30Ms: Long?,
        outcome60mPct: Double?,
        outcome60Ms: Long?
    )
}

/** 本地实现：SharedPreferences + JSON（按股票隔离，每股票保留最近 500 条） */
class PrefsIntradaySignalStore(context: Context) : IntradaySignalStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("intraday_signal_prefs", Context.MODE_PRIVATE)

    override fun loadSnapshots(stockCode: String, date: String?): List<IntradaySignalSnapshot> {
        val list = prefs.getString("signals:$stockCode", null)?.let(::parseSnapshots).orEmpty()
        return if (date == null) list else list.filter { it.date == date }
    }

    override fun addSnapshot(s: IntradaySignalSnapshot) {
        // 10 分钟时间桶去重：同一采集周期内只保留一条，避免重复采集污染统计
        val bucket = s.timeMs / SNAPSHOT_BUCKET_MS
        val list = loadSnapshots(s.stockCode)
            .filterNot { it.date == s.date && it.timeMs / SNAPSHOT_BUCKET_MS == bucket } + s
        prefs.edit().putString("signals:${s.stockCode}", buildSnapshots(list.takeLast(MAX_SNAPSHOTS))).apply()
    }

    override fun updateOutcomes(
        stockCode: String,
        timeMs: Long,
        outcome30mPct: Double?,
        outcome30Ms: Long?,
        outcome60mPct: Double?,
        outcome60Ms: Long?
    ) {
        val updated = loadSnapshots(stockCode).map {
            if (it.timeMs == timeMs) it.copy(
                outcome30mPct = outcome30mPct ?: it.outcome30mPct,
                outcome30Ms = outcome30Ms ?: it.outcome30Ms,
                outcome60mPct = outcome60mPct ?: it.outcome60mPct,
                outcome60Ms = outcome60Ms ?: it.outcome60Ms
            ) else it
        }
        prefs.edit().putString("signals:$stockCode", buildSnapshots(updated)).apply()
    }

    private companion object {
        const val SNAPSHOT_BUCKET_MS = 10 * 60 * 1000L // 10 分钟
        const val MAX_SNAPSHOTS = 500
    }
}

// ---------------- JSON 序列化 ----------------

private fun buildSnapshots(list: List<IntradaySignalSnapshot>): String =
    JSONArray().apply {
        list.forEach { s ->
            put(JSONObject()
                .put("stockCode", s.stockCode)
                .put("date", s.date)
                .put("timeMs", s.timeMs)
                .put("lastPos", s.lastPos)
                .put("price", s.price)
                .put("action", s.action.name)
                .put("score", s.score)
                .put("direction", s.direction?.name ?: JSONObject.NULL)
                .put("outcome30mPct", s.outcome30mPct ?: JSONObject.NULL)
                .put("outcome30Ms", s.outcome30Ms ?: JSONObject.NULL)
                .put("outcome60mPct", s.outcome60mPct ?: JSONObject.NULL)
                .put("outcome60Ms", s.outcome60Ms ?: JSONObject.NULL))
        }
    }.toString()

private fun parseSnapshots(raw: String): List<IntradaySignalSnapshot> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            IntradaySignalSnapshot(
                stockCode = o.getString("stockCode"),
                date = o.getString("date"),
                timeMs = o.getLong("timeMs"),
                lastPos = o.getInt("lastPos"),
                price = o.getDouble("price"),
                action = IntradayAction.valueOf(o.getString("action")),
                score = o.getDouble("score"),
                direction = if (o.isNull("direction")) null else PredictionOutcome.valueOf(o.getString("direction")),
                outcome30mPct = if (o.isNull("outcome30mPct")) null else o.getDouble("outcome30mPct"),
                outcome30Ms = if (o.isNull("outcome30Ms")) null else o.getLong("outcome30Ms"),
                outcome60mPct = if (o.isNull("outcome60mPct")) null else o.getDouble("outcome60mPct"),
                outcome60Ms = if (o.isNull("outcome60Ms")) null else o.getLong("outcome60Ms")
            )
        }.getOrNull()
    }
}
