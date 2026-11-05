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

internal fun buildState(s: StockState): String {
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
                    .put("fee", lot.fee)
            )
        }
        val trades = JSONArray()
        acc.trades.forEach { t ->
            val soldArr = JSONArray()
            t.soldLots.forEach { sl ->
                soldArr.put(
                    JSONObject()
                        .put("lotId", sl.lotId)
                        .put("qty", sl.qty)
                        .put("price", sl.price)
                        .put("originalQty", sl.originalQty)
                        .put("time", sl.time)
                        .put("fee", sl.fee)
                )
            }
            trades.put(
                JSONObject()
                    .put("id", t.id)
                    .put("type", t.type.name)
                    .put("price", t.price)
                    .put("qty", t.qty)
                    .put("profit", t.profit)
                    .put("time", t.time)
                    .put("fee", t.fee)
                    .put("costFee", t.costFee)
                    .put("lotId", t.lotId ?: JSONObject.NULL)
                    .put("soldLots", soldArr)
            )
        }
        accounts.put(
            JSONObject()
                .put("code", acc.stock.code)
                .put("name", acc.stock.name)
                .put("market", acc.stock.market)
                .put("currentPrice", acc.currentPrice ?: JSONObject.NULL)
                .put("prevClose", acc.prevClose ?: JSONObject.NULL)
                .put("totalBuyFee", acc.totalBuyFee)
                .put("holdings", lots)
                .put("trades", trades)
                .put("tradePlan", acc.tradePlan?.let { plan ->
                    plan.legacyJson?.let(::JSONObject) ?: JSONObject()
                        .put("version", 2)
                        .put("lowerCost", plan.lowerCost)
                        .put("upperCost", plan.upperCost)
                        .put("orderAmount", plan.orderAmount)
                        .put("stepPercent", plan.stepPercent)
                        .put("referencePrice", plan.referencePrice)
                        .put("startAfterTradeId", plan.startAfterTradeId)
                } ?: JSONObject.NULL)
        )
    }
    return JSONObject()
        .put("accounts", accounts)
        .put("selectedIndex", s.selectedIndex)
        .put("sellMatch", s.sellMatch.name)
        .toString()
}

internal fun parseState(raw: String): StockState {
    val root = JSONObject(raw)
    val accountsArr = root.getJSONArray("accounts")
    val accounts = mutableListOf<StockAccount>()
    for (i in 0 until accountsArr.length()) {
        val a = accountsArr.getJSONObject(i)
        val holdings = mutableListOf<BuyLot>()
        val holdingsArr = a.getJSONArray("holdings")
        for (j in 0 until holdingsArr.length()) {
            val l = holdingsArr.getJSONObject(j)
            holdings.add(BuyLot(l.getLong("id"), l.getDouble("price"), l.getInt("originalQty"), l.getInt("remainingQty"), l.optLong("time", 0L), l.optDouble("fee", 0.0)))
        }
        val trades = mutableListOf<TradeRecord>()
        val tradesArr = a.getJSONArray("trades")
        for (j in 0 until tradesArr.length()) {
            val t = tradesArr.getJSONObject(j)
            val soldArr = t.optJSONArray("soldLots") ?: JSONArray()
            val soldLots = mutableListOf<SoldLot>()
            for (k in 0 until soldArr.length()) {
                val sl = soldArr.getJSONObject(k)
                soldLots.add(SoldLot(sl.getLong("lotId"), sl.getInt("qty"), sl.getDouble("price"), sl.getInt("originalQty"), sl.optLong("time", 0L), sl.optDouble("fee", 0.0)))
            }
            trades.add(TradeRecord(
                t.getLong("id"), TradeType.valueOf(t.getString("type")), t.getDouble("price"), t.getInt("qty"),
                t.getDouble("profit"), t.getLong("time"), t.optDouble("fee", 0.0), t.optDouble("costFee", 0.0),
                lotId = if (t.isNull("lotId")) null else t.optLong("lotId", 0L).let { if (it == 0L) null else it },
                soldLots = soldLots
            ))
        }
        accounts.add(
            StockAccount(
                stock = Stock(a.getString("code"), a.getString("name"), a.getString("market")),
                holdings = holdings,
                trades = trades,
                currentPrice = if (a.isNull("currentPrice")) null else a.getDouble("currentPrice"),
                prevClose = if (a.isNull("prevClose")) null else a.getDouble("prevClose"),
                totalBuyFee = a.optDouble("totalBuyFee", 0.0),
                tradePlan = a.optJSONObject("tradePlan")?.let { plan ->
                    if (plan.optInt("version", 1) != 2) {
                        // Preserve unsupported settings until the user configures the new model.
                        TradePlanConfig(referencePrice = plan.optDouble("referencePrice", 0.0),
                            legacyJson = plan.toString())
                    } else runCatching {
                        TradePlanConfig(
                            lowerCost = plan.getDouble("lowerCost"), upperCost = plan.getDouble("upperCost"),
                            orderAmount = plan.getDouble("orderAmount"), stepPercent = plan.getDouble("stepPercent"),
                            referencePrice = plan.getDouble("referencePrice"), startAfterTradeId = plan.optLong("startAfterTradeId", 0L)
                        )
                    }.getOrNull()?.takeIf { it.isValid }
                }
            )
        )
    }
    val sel = if (accounts.isEmpty()) -1 else root.optInt("selectedIndex", 0)
    val sellMatch = runCatching { SellMatch.valueOf(root.optString("sellMatch", "LOWEST")) }
        .getOrDefault(SellMatch.LOWEST)
    return StockState(accounts = accounts, selectedIndex = sel, sellMatch = sellMatch)
}
