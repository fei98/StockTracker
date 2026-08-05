package com.example.stocktracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 数据持久化接口（单元测试可用假实现） */
interface Storage {
    suspend fun load(): StockState
    suspend fun save(state: StockState)
}

/**
 * 本地持久化：把整个状态序列化为 JSON 存到 SharedPreferences。
 * App 重启、重装都不会丢失（卸载才丢）。
 */
class PrefsStorage(context: Context) : Storage {

    private val prefs =
        context.applicationContext.getSharedPreferences("stock_tracker_prefs", Context.MODE_PRIVATE)

    override suspend fun load(): StockState = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_STATE, null) ?: return@withContext StockState()
        runCatching { parseState(raw) }.getOrDefault(StockState())
    }

    override suspend fun save(state: StockState) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_STATE, buildState(state)).apply()
    }

    private companion object {
        const val KEY_STATE = "state"
    }
}

// ---------------- JSON 序列化 ----------------

private fun buildState(s: StockState): String {
    val accounts = JSONArray()
    s.accounts.forEach { acc ->
        val lots = JSONArray()
        acc.holdings.forEach { lot ->
            lots.put(
                JSONObject()
                    .put("id", lot.id)
                    .put("price", lot.price)
                    .put("originalQty", lot.originalQty)
                    .put("remainingQty", lot.remainingQty)
                    .put("time", lot.time)
            )
        }
        val trades = JSONArray()
        acc.trades.forEach { t ->
            trades.put(
                JSONObject()
                    .put("id", t.id)
                    .put("type", t.type.name)
                    .put("price", t.price)
                    .put("qty", t.qty)
                    .put("profit", t.profit)
                    .put("time", t.time)
            )
        }
        accounts.put(
            JSONObject()
                .put("code", acc.stock.code)
                .put("name", acc.stock.name)
                .put("market", acc.stock.market)
                .put("currentPrice", acc.currentPrice ?: JSONObject.NULL)
                .put("prevClose", acc.prevClose ?: JSONObject.NULL)
                .put("holdings", lots)
                .put("trades", trades)
        )
    }
    return JSONObject().put("accounts", accounts).put("selectedIndex", s.selectedIndex).toString()
}

private fun parseState(raw: String): StockState {
    val root = JSONObject(raw)
    val accountsArr = root.getJSONArray("accounts")
    val accounts = mutableListOf<StockAccount>()
    for (i in 0 until accountsArr.length()) {
        val a = accountsArr.getJSONObject(i)
        val holdings = mutableListOf<BuyLot>()
        val holdingsArr = a.getJSONArray("holdings")
        for (j in 0 until holdingsArr.length()) {
            val l = holdingsArr.getJSONObject(j)
            holdings.add(BuyLot(l.getLong("id"), l.getDouble("price"), l.getInt("originalQty"), l.getInt("remainingQty"), l.optLong("time", 0L)))
        }
        val trades = mutableListOf<TradeRecord>()
        val tradesArr = a.getJSONArray("trades")
        for (j in 0 until tradesArr.length()) {
            val t = tradesArr.getJSONObject(j)
            trades.add(TradeRecord(t.getLong("id"), TradeType.valueOf(t.getString("type")), t.getDouble("price"), t.getInt("qty"), t.getDouble("profit"), t.getLong("time")))
        }
        accounts.add(
            StockAccount(
                stock = Stock(a.getString("code"), a.getString("name"), a.getString("market")),
                holdings = holdings,
                trades = trades,
                currentPrice = if (a.isNull("currentPrice")) null else a.getDouble("currentPrice"),
                prevClose = if (a.isNull("prevClose")) null else a.getDouble("prevClose")
            )
        )
    }
    val sel = if (accounts.isEmpty()) -1 else root.optInt("selectedIndex", 0)
    return StockState(accounts = accounts, selectedIndex = sel)
}
