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

// ---------------- JSON serialization ----------------

private fun buildLot(lot: BuyLot): JSONObject = JSONObject()
    .put("id", lot.id).put("price", lot.price).put("originalQty", lot.originalQty)
    .put("remainingQty", lot.remainingQty).put("time", lot.time).put("fee", lot.fee)

private fun buildRuntime(state: TradePlanRuntime): JSONObject {
    val grids = JSONObject()
    state.lotGrids.forEach { (id, grid) -> grids.put(id.toString(), grid) }
    return JSONObject().put("activationId", state.activationId).put("cash", state.cash)
        .put("phase", state.phase.name).put("cursorGrid", state.cursorGrid)
        .put("pendingDirection", state.pendingDirection?.name ?: JSONObject.NULL)
        .put("pendingGrid", state.pendingGrid ?: JSONObject.NULL).put("lotGrids", grids)
        .put("gridScale", state.gridScale).put("gridOffset", state.gridOffset)
        .put("revision", state.revision)
}

private fun buildPlan(plan: TradePlanConfig): JSONObject =
    plan.legacyJson?.let(::JSONObject) ?: JSONObject().put("version", 3)
        .put("lowerCost", plan.lowerCost).put("upperCost", plan.upperCost)
        .put("orderAmount", plan.orderAmount).put("stepPercent", plan.stepPercent)
        .put("referencePrice", plan.referencePrice).put("startAfterTradeId", plan.startAfterTradeId)
        .put("capital", plan.capital).put("initialCash", plan.initialCash)
        .put("activationId", plan.activationId)

private fun buildAdjustment(record: PlanAdjustmentRecord): JSONObject {
    val input = record.input
    return JSONObject().put("id", record.id).put("time", record.time)
        .put("input", JSONObject().put("netCash", input.netCash).put("addedShares", input.addedShares)
            .put("cashReference", input.cashReference).put("bonusReferencePercent", input.bonusReferencePercent)
            .put("newPrice", input.newPrice))
        .put("beforeHoldings", JSONArray().also { arr -> record.beforeHoldings.forEach { arr.put(buildLot(it)) } })
        .put("beforePlanState", record.beforePlanState?.let(::buildRuntime) ?: JSONObject.NULL)
        .put("beforeCurrentPrice", record.beforeCurrentPrice ?: JSONObject.NULL)
        .put("beforePrevClose", record.beforePrevClose ?: JSONObject.NULL)
        .put("afterTradeId", record.afterTradeId).put("matureQtyAfter", record.matureQtyAfter)
        .put("frozenQtyAfter", record.frozenQtyAfter)
}

internal fun buildState(s: StockState): String {
    val accounts = JSONArray()
    s.accounts.forEach { acc ->
        val lots = JSONArray().also { arr -> acc.holdings.forEach { arr.put(buildLot(it)) } }
        val trades = JSONArray()
        acc.trades.forEach { t ->
            val soldArr = JSONArray()
            t.soldLots.forEach { sl ->
                soldArr.put(JSONObject().put("lotId", sl.lotId).put("qty", sl.qty).put("price", sl.price)
                    .put("originalQty", sl.originalQty).put("time", sl.time).put("fee", sl.fee))
            }
            trades.put(JSONObject().put("id", t.id).put("type", t.type.name).put("price", t.price)
                .put("qty", t.qty).put("profit", t.profit).put("time", t.time).put("fee", t.fee)
                .put("costFee", t.costFee).put("lotId", t.lotId ?: JSONObject.NULL).put("soldLots", soldArr)
                .put("planBefore", t.planBefore?.let(::buildRuntime) ?: JSONObject.NULL))
        }
        accounts.put(JSONObject().put("code", acc.stock.code).put("name", acc.stock.name).put("market", acc.stock.market)
            .put("currentPrice", acc.currentPrice ?: JSONObject.NULL).put("prevClose", acc.prevClose ?: JSONObject.NULL)
            .put("totalBuyFee", acc.totalBuyFee).put("holdings", lots).put("trades", trades)
            .put("tradePlan", acc.tradePlan?.let(::buildPlan) ?: JSONObject.NULL)
            .put("tradePlanState", acc.tradePlanState?.let(::buildRuntime) ?: JSONObject.NULL)
            .put("planAdjustments", JSONArray().also { arr -> acc.planAdjustments.forEach { arr.put(buildAdjustment(it)) } })
            .put("planRecoveryData", JSONArray().also { arr -> acc.planRecoveryData.forEach { arr.put(it) } }))
    }
    return JSONObject().put("accounts", accounts).put("selectedIndex", s.selectedIndex)
        .put("sellMatch", s.sellMatch.name).toString()
}

private fun requiredDouble(obj: JSONObject, key: String): Double {
    val value = obj.get(key)
    require(value is Number)
    return value.toDouble().also { require(it.isFinite()) }
}

private fun requiredLong(obj: JSONObject, key: String): Long {
    val value = obj.get(key)
    require(value is Number)
    return java.math.BigDecimal(value.toString()).longValueExact()
}

private fun requiredInt(obj: JSONObject, key: String): Int {
    val value = obj.get(key)
    require(value is Number)
    return java.math.BigDecimal(value.toString()).intValueExact()
}

private fun nullableDouble(obj: JSONObject, key: String): Double? {
    require(obj.has(key))
    return if (obj.isNull(key)) null else requiredDouble(obj, key)
}

private fun parseLot(obj: JSONObject): BuyLot = BuyLot(
    obj.getLong("id"), obj.getDouble("price"), obj.getInt("originalQty"), obj.getInt("remainingQty"),
    obj.optLong("time", 0L), obj.optDouble("fee", 0.0)
)

private fun parseRuntime(obj: JSONObject?): TradePlanRuntime? = obj?.let {
    runCatching {
        require(obj.has("pendingDirection") && obj.has("pendingGrid"))
        val mappings = obj.getJSONObject("lotGrids")
        val grids = mutableMapOf<Long, Int>()
        mappings.keys().forEach { key ->
            val id = key.toLong()
            require(id > 0 && key == id.toString())
            grids[id] = requiredInt(mappings, key)
        }
        TradePlanRuntime(
            activationId = requiredLong(obj, "activationId"), cash = requiredDouble(obj, "cash"),
            phase = PlanPhase.valueOf(obj.getString("phase")), cursorGrid = requiredInt(obj, "cursorGrid"),
            pendingDirection = if (obj.isNull("pendingDirection")) null else PlanDirection.valueOf(obj.getString("pendingDirection")),
            pendingGrid = if (obj.isNull("pendingGrid")) null else requiredInt(obj, "pendingGrid"),
            lotGrids = grids, gridScale = requiredDouble(obj, "gridScale"),
            gridOffset = requiredDouble(obj, "gridOffset"), revision = requiredLong(obj, "revision")
        ).also { require(it.isValid) }
    }.getOrNull()
}

private fun legacyPlan(obj: JSONObject): TradePlanConfig = TradePlanConfig(
    lowerCost = obj.optDouble("lowerCost", 0.0).takeIf { it.isFinite() } ?: 0.0,
    upperCost = obj.optDouble("upperCost", 0.0).takeIf { it.isFinite() } ?: 0.0,
    orderAmount = obj.optDouble("orderAmount", 0.0).takeIf { it.isFinite() } ?: 0.0,
    stepPercent = obj.optDouble("stepPercent", 0.0).takeIf { it.isFinite() } ?: 0.0,
    referencePrice = obj.optDouble("referencePrice", 0.0).takeIf { it.isFinite() } ?: 0.0,
    startAfterTradeId = obj.optLong("startAfterTradeId", 0L), legacyJson = obj.toString(),
    capital = obj.optDouble("capital", 0.0).takeIf { it.isFinite() } ?: 0.0,
    initialCash = obj.optDouble("initialCash", 0.0).takeIf { it.isFinite() } ?: 0.0,
    activationId = obj.optLong("activationId", 0L)
)

private fun parsePlan(obj: JSONObject?): TradePlanConfig? = obj?.let {
    val version = runCatching { requiredInt(obj, "version") }.getOrDefault(1)
    if (version != 3) legacyPlan(obj)
    else runCatching {
        TradePlanConfig(
            lowerCost = requiredDouble(obj, "lowerCost"), upperCost = requiredDouble(obj, "upperCost"),
            orderAmount = requiredDouble(obj, "orderAmount"), stepPercent = requiredDouble(obj, "stepPercent"),
            referencePrice = requiredDouble(obj, "referencePrice"), startAfterTradeId = requiredLong(obj, "startAfterTradeId"),
            capital = requiredDouble(obj, "capital"), initialCash = requiredDouble(obj, "initialCash"),
            activationId = requiredLong(obj, "activationId")
        ).also { require(it.isValid) }
    }.getOrElse { legacyPlan(obj) }
}

private fun parseAdjustment(obj: JSONObject): PlanAdjustmentRecord? = runCatching {
    val rawInput = obj.getJSONObject("input")
    val input = PlanAdjustmentInput(
        netCash = requiredDouble(rawInput, "netCash"), addedShares = requiredInt(rawInput, "addedShares"),
        cashReference = requiredDouble(rawInput, "cashReference"),
        bonusReferencePercent = requiredDouble(rawInput, "bonusReferencePercent"), newPrice = requiredDouble(rawInput, "newPrice")
    )
    require(input.addedShares >= 0 && input.cashReference >= 0 && input.bonusReferencePercent >= 0 && input.newPrice > 0)
    val before = obj.getJSONArray("beforeHoldings")
    val holdings = (0 until before.length()).map { index ->
        val raw = before.getJSONObject(index)
        BuyLot(requiredLong(raw, "id"), requiredDouble(raw, "price"), requiredInt(raw, "originalQty"),
            requiredInt(raw, "remainingQty"), requiredLong(raw, "time"), requiredDouble(raw, "fee")).also { lot ->
        require(lot.id > 0 && lot.price.isFinite() && lot.price > 0 && lot.originalQty > 0 &&
            lot.remainingQty >= 0 && lot.remainingQty <= lot.originalQty && lot.time >= 0 && lot.fee.isFinite() && lot.fee >= 0)
    } }
    require(holdings.map { it.id }.distinct().size == holdings.size)
    require(obj.has("beforePlanState"))
    val rawState = obj.optJSONObject("beforePlanState")
    val state = parseRuntime(rawState)
    require(obj.isNull("beforePlanState") || state != null)
    require(state == null || holdings.filter { it.remainingQty > 0 }.all { it.id in state.lotGrids })
    PlanAdjustmentRecord(
        id = requiredLong(obj, "id"), time = requiredLong(obj, "time"), input = input,
        beforeHoldings = holdings, beforePlanState = state,
        beforeCurrentPrice = nullableDouble(obj, "beforeCurrentPrice"), beforePrevClose = nullableDouble(obj, "beforePrevClose"),
        afterTradeId = requiredLong(obj, "afterTradeId"), matureQtyAfter = requiredInt(obj, "matureQtyAfter"),
        frozenQtyAfter = requiredInt(obj, "frozenQtyAfter")
    ).also { record ->
        require(record.id > 0 && record.time >= 0 && record.afterTradeId >= 0 && record.id > record.afterTradeId)
        require(record.matureQtyAfter >= 0 && record.frozenQtyAfter >= 0)
        val total = holdings.sumOf { it.remainingQty.toLong() } + input.addedShares
        require(total <= Int.MAX_VALUE && record.matureQtyAfter.toLong() + record.frozenQtyAfter == total)
    }
}.getOrNull()

internal fun parseState(raw: String): StockState {
    val root = JSONObject(raw)
    val accountsArr = root.getJSONArray("accounts")
    val accounts = mutableListOf<StockAccount>()
    for (i in 0 until accountsArr.length()) {
        val a = accountsArr.getJSONObject(i)
        val recovery = mutableListOf<String>()
        val rawRecovery = a.optJSONArray("planRecoveryData")
        if (rawRecovery != null) {
            for (j in 0 until rawRecovery.length()) {
                val entry = rawRecovery.get(j)
                recovery += if (entry is String) entry else "planRecoveryData.entry:$entry"
            }
        } else if (a.has("planRecoveryData") && !a.isNull("planRecoveryData")) {
            recovery += "planRecoveryData:${a.get("planRecoveryData")}"
        }
        val holdingsArr = a.getJSONArray("holdings")
        val holdings = (0 until holdingsArr.length()).map { parseLot(holdingsArr.getJSONObject(it)) }
        val tradesArr = a.getJSONArray("trades")
        val trades = (0 until tradesArr.length()).map { index ->
            val t = tradesArr.getJSONObject(index)
            val soldArr = t.optJSONArray("soldLots") ?: JSONArray()
            val soldLots = (0 until soldArr.length()).map { k ->
                val sl = soldArr.getJSONObject(k)
                SoldLot(sl.getLong("lotId"), sl.getInt("qty"), sl.getDouble("price"), sl.getInt("originalQty"), sl.optLong("time", 0L), sl.optDouble("fee", 0.0))
            }
            val beforePlan = parseRuntime(t.optJSONObject("planBefore"))
            if (t.has("planBefore") && !t.isNull("planBefore") && beforePlan == null) {
                recovery += "trade[${t.getLong("id")}].planBefore:${t.get("planBefore")}"
            }
            TradeRecord(t.getLong("id"), TradeType.valueOf(t.getString("type")), t.getDouble("price"), t.getInt("qty"),
                t.getDouble("profit"), t.getLong("time"), t.optDouble("fee", 0.0), t.optDouble("costFee", 0.0),
                lotId = if (t.isNull("lotId")) null else t.optLong("lotId", 0L).takeIf { it != 0L },
                soldLots = soldLots, planBefore = beforePlan)
        }
        val plan = parsePlan(a.optJSONObject("tradePlan"))
        if (a.has("tradePlan") && !a.isNull("tradePlan") && a.optJSONObject("tradePlan") == null) {
            recovery += "tradePlan:${a.get("tradePlan")}"
        }
        val rawAdjustments = a.optJSONArray("planAdjustments") ?: JSONArray()
        val adjustments = mutableListOf<PlanAdjustmentRecord>()
        var malformedAdjustment = a.has("planAdjustments") && !a.isNull("planAdjustments") &&
            a.optJSONArray("planAdjustments") == null
        if (malformedAdjustment) recovery += "planAdjustments:${a.get("planAdjustments")}"
        val ids = mutableSetOf<Long>()
        for (j in 0 until rawAdjustments.length()) {
            val record = rawAdjustments.optJSONObject(j)?.let(::parseAdjustment)
            if (record == null || !ids.add(record.id)) {
                malformedAdjustment = true
                recovery += "planAdjustments[$j]:${rawAdjustments.get(j)}"
            } else adjustments += record
        }
        val parsedRuntime = parseRuntime(a.optJSONObject("tradePlanState"))
        val runtime = parsedRuntime?.takeIf { state ->
            !malformedAdjustment && plan?.isValid == true && plan.activationId > 0 && state.activationId == plan.activationId &&
                holdings.filter { it.remainingQty > 0 }.all { it.id in state.lotGrids }
        }
        if (a.has("tradePlanState") && !a.isNull("tradePlanState") && runtime == null) {
            recovery += "tradePlanState:${a.get("tradePlanState")}"
        }
        accounts += StockAccount(
            stock = Stock(a.getString("code"), a.getString("name"), a.getString("market")), holdings = holdings, trades = trades,
            currentPrice = if (a.isNull("currentPrice")) null else a.getDouble("currentPrice"),
            prevClose = if (a.isNull("prevClose")) null else a.getDouble("prevClose"), totalBuyFee = a.optDouble("totalBuyFee", 0.0),
            tradePlan = plan, tradePlanState = runtime, planAdjustments = adjustments,
            planRecoveryData = recovery.distinct()
        )
    }
    val sel = if (accounts.isEmpty()) -1 else root.optInt("selectedIndex", 0)
    val sellMatch = runCatching { SellMatch.valueOf(root.optString("sellMatch", "LOWEST")) }.getOrDefault(SellMatch.LOWEST)
    return StockState(accounts = accounts, selectedIndex = sel, sellMatch = sellMatch)
}
