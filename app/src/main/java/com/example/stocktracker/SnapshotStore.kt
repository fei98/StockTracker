package com.example.stocktracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 预测数据持久化接口（按股票隔离，单元测试可用假实现） */
interface SnapshotStore {
    fun loadSnapshots(stock: String): List<DailySnapshot>
    fun addSnapshot(stock: String, s: DailySnapshot)
    fun updateClose(stock: String, date: String, close: Double, dayAmount: Double)

    fun loadStage(stock: String, date: String, stage: String): AuctionStageSnapshot?
    fun saveStage(stock: String, s: AuctionStageSnapshot)

    fun loadRecords(stock: String): List<PredictionRecord>
    fun addRecord(stock: String, r: PredictionRecord)
    fun updateOutcomes(stock: String, date: String, o30: PredictionOutcome?, ocvo: PredictionOutcome?, odvp: PredictionOutcome?)

    fun saveLastResult(stock: String, r: PredictionResult)
    fun loadLastResult(stock: String): PredictionResult?

    /** 候选池联动快照（按日） */
    fun loadUniverseSnapshots(): List<UniverseDailySnapshot>
    fun addUniverseSnapshot(s: UniverseDailySnapshot)

    /** 用户手选行业（股票 → 行业名；null=清除回自动） */
    fun getUserSector(stock: String): String?
    fun setUserSector(stock: String, industry: String?)
}

/** 本地实现：SharedPreferences + JSON（复用预测量小、低频读写的场景） */
class PrefsSnapshotStore(context: Context) : SnapshotStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("prediction_prefs", Context.MODE_PRIVATE)

    override fun loadSnapshots(stock: String): List<DailySnapshot> =
        prefs.getString(keyOf(KEY_SNAPSHOTS, stock), null)?.let(::parseSnapshots).orEmpty()

    override fun addSnapshot(stock: String, s: DailySnapshot) {
        val list = (loadSnapshots(stock) + s).takeLast(40)
        prefs.edit().putString(keyOf(KEY_SNAPSHOTS, stock), buildSnapshots(list)).apply()
    }

    override fun updateClose(stock: String, date: String, close: Double, dayAmount: Double) {
        val updated = loadSnapshots(stock).map { if (it.date == date) it.copy(close = close, dayAmount = dayAmount) else it }
        prefs.edit().putString(keyOf(KEY_SNAPSHOTS, stock), buildSnapshots(updated)).apply()
    }

    override fun loadStage(stock: String, date: String, stage: String): AuctionStageSnapshot? {
        val raw = prefs.getString("$KEY_STAGE:$stock:$date:$stage", null) ?: return null
        return runCatching { parseStage(raw) }.getOrNull()
    }

    override fun saveStage(stock: String, s: AuctionStageSnapshot) {
        prefs.edit().putString("$KEY_STAGE:$stock:${s.date}:${s.stage}", buildStage(s)).apply()
    }

    override fun loadRecords(stock: String): List<PredictionRecord> =
        prefs.getString(keyOf(KEY_RECORDS, stock), null)?.let(::parseRecords).orEmpty()

    override fun addRecord(stock: String, r: PredictionRecord) {
        val list = (loadRecords(stock) + r).takeLast(200)
        prefs.edit().putString(keyOf(KEY_RECORDS, stock), buildRecords(list)).apply()
    }

    override fun updateOutcomes(stock: String, date: String, o30: PredictionOutcome?, ocvo: PredictionOutcome?, odvp: PredictionOutcome?) {
        val updated = loadRecords(stock).map {
            if (it.date == date) it.copy(
                outcome30m = o30 ?: it.outcome30m,
                outcomeCloseVsOpen = ocvo ?: it.outcomeCloseVsOpen,
                outcomeDayVsPrev = odvp ?: it.outcomeDayVsPrev
            ) else it
        }
        prefs.edit().putString(keyOf(KEY_RECORDS, stock), buildRecords(updated)).apply()
    }

    override fun saveLastResult(stock: String, r: PredictionResult) {
        prefs.edit().putString(keyOf(KEY_LAST, stock), buildResult(r)).apply()
    }

    override fun loadLastResult(stock: String): PredictionResult? {
        val raw = prefs.getString(keyOf(KEY_LAST, stock), null) ?: return null
        return runCatching { parseResult(raw) }.getOrNull()
    }

    override fun loadUniverseSnapshots(): List<UniverseDailySnapshot> =
        prefs.getString(KEY_UNIVERSE, null)?.let(::parseUniverse).orEmpty()

    override fun addUniverseSnapshot(s: UniverseDailySnapshot) {
        val list = (loadUniverseSnapshots() + s).takeLast(40)
        prefs.edit().putString(KEY_UNIVERSE, buildUniverse(list)).apply()
    }

    override fun getUserSector(stock: String): String? =
        prefs.getString("$KEY_USER_SECTOR:$stock", null)

    override fun setUserSector(stock: String, industry: String?) {
        prefs.edit().putString("$KEY_USER_SECTOR:$stock", industry).apply()
    }

    private fun keyOf(base: String, stock: String) = "$base:$stock"

    private companion object {
        const val KEY_SNAPSHOTS = "snapshots"
        const val KEY_STAGE = "stage"
        const val KEY_RECORDS = "records"
        const val KEY_LAST = "lastResult"
        const val KEY_UNIVERSE = "universe"
        const val KEY_USER_SECTOR = "userSector"
    }
}

// ---------------- JSON 序列化 ----------------

private fun buildSnapshots(list: List<DailySnapshot>): String =
    JSONArray().apply {
        list.forEach { s ->
            put(JSONObject()
                .put("date", s.date).put("open", s.open).put("prevClose", s.prevClose)
                .put("auctionAmount", s.auctionAmount).put("dayAmount", s.dayAmount).put("close", s.close))
        }
    }.toString()

private fun parseSnapshots(raw: String): List<DailySnapshot> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        runCatching {
            DailySnapshot(o.getString("date"), o.getDouble("open"), o.getDouble("prevClose"),
                o.getDouble("auctionAmount"), o.getDouble("dayAmount"), o.getDouble("close"))
        }.getOrNull()
    }
}

private fun buildStage(s: AuctionStageSnapshot): String =
    JSONObject().put("date", s.date).put("stage", s.stage).put("price", s.price).put("amount", s.amount).toString()

private fun parseStage(raw: String): AuctionStageSnapshot {
    val o = JSONObject(raw)
    return AuctionStageSnapshot(o.getString("date"), o.getString("stage"), o.getDouble("price"), o.getDouble("amount"))
}

private fun buildFactors(f: PredictionFactors): JSONObject =
    JSONObject().put("targetGapPct", f.targetGapPct ?: JSONObject.NULL)
        .put("indexGapPct", f.indexGapPct ?: JSONObject.NULL)
        .put("sectorAvgGap", f.sectorAvgGap ?: JSONObject.NULL)
        .put("sectorBreadth", f.sectorBreadth ?: JSONObject.NULL)
        .put("externalAvg", f.externalAvg ?: JSONObject.NULL)
        .put("endMovePct", f.endMovePct ?: JSONObject.NULL)
        .put("volZ", f.volZ ?: JSONObject.NULL)
        .put("baselineDays", f.baselineDays)

private fun parseFactors(o: JSONObject): PredictionFactors = PredictionFactors(
    targetGapPct = if (o.isNull("targetGapPct")) null else o.getDouble("targetGapPct"),
    indexGapPct = if (o.isNull("indexGapPct")) null else o.getDouble("indexGapPct"),
    sectorAvgGap = if (o.isNull("sectorAvgGap")) null else o.getDouble("sectorAvgGap"),
    sectorBreadth = if (o.isNull("sectorBreadth")) null else o.getDouble("sectorBreadth"),
    externalAvg = if (o.isNull("externalAvg")) null else o.getDouble("externalAvg"),
    endMovePct = if (o.isNull("endMovePct")) null else o.getDouble("endMovePct"),
    volZ = if (o.isNull("volZ")) null else o.getDouble("volZ"),
    baselineDays = o.getInt("baselineDays")
)

private fun buildSf(sf: StandardizedFactors): JSONObject =
    JSONObject().put("strengthZ", sf.strengthZ)
        .put("endMoveZ", sf.endMoveZ ?: JSONObject.NULL)
        .put("breadth", sf.breadth ?: JSONObject.NULL)
        .put("extUpContrib", sf.extUpContrib)
        .put("extDownContrib", sf.extDownContrib)
        .put("volAmp", sf.volAmp)
        .put("insufficient", JSONArray().apply { sf.insufficient.forEach { put(it) } })

private fun parseSf(o: JSONObject): StandardizedFactors {
    val arr = o.optJSONArray("insufficient")
    val ins = (0 until (arr?.length() ?: 0)).map { arr.getString(it) }
    return StandardizedFactors(
        strengthZ = o.getDouble("strengthZ"),
        endMoveZ = if (o.isNull("endMoveZ")) null else o.getDouble("endMoveZ"),
        breadth = if (o.isNull("breadth")) null else o.getDouble("breadth"),
        extUpContrib = o.getDouble("extUpContrib"),
        extDownContrib = o.getDouble("extDownContrib"),
        volAmp = o.getDouble("volAmp"),
        insufficient = ins
    )
}

private fun buildWeights(w: CalibratedWeights): JSONObject =
    JSONObject().put("strengthScale", w.strengthScale).put("endScale", w.endScale)
        .put("breadthScale", w.breadthScale).put("extUpScale", w.extUpScale)
        .put("extDownScale", w.extDownScale).put("threshold", w.threshold)

private fun parseWeights(o: JSONObject): CalibratedWeights = CalibratedWeights(
    o.getDouble("strengthScale"), o.getDouble("endScale"), o.getDouble("breadthScale"),
    o.getDouble("extUpScale"), o.getDouble("extDownScale"), o.getDouble("threshold")
)

private fun buildOutcome(o: PredictionOutcome?): Any = o?.name ?: JSONObject.NULL
private fun parseOutcome(o: JSONObject, key: String): PredictionOutcome? =
    if (o.isNull(key)) null else PredictionOutcome.valueOf(o.getString(key))

private fun buildRecord(r: PredictionRecord): JSONObject =
    JSONObject().put("stockCode", r.stockCode)
        .put("date", r.date)
        .put("factors", buildFactors(r.factors))
        .put("sf", buildSf(r.sf))
        .put("weights", buildWeights(r.weights))
        .put("conclusion", r.conclusion.name)
        .put("open", r.open ?: JSONObject.NULL)
        .put("prevClose", r.prevClose ?: JSONObject.NULL)
        .put("outcome30m", buildOutcome(r.outcome30m))
        .put("outcomeCloseVsOpen", buildOutcome(r.outcomeCloseVsOpen))
        .put("outcomeDayVsPrev", buildOutcome(r.outcomeDayVsPrev))

private fun buildRecords(list: List<PredictionRecord>): String =
    JSONArray().apply { list.forEach { put(buildRecord(it)) } }.toString()

private fun parseRecords(raw: String): List<PredictionRecord> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            PredictionRecord(
                stockCode = o.getString("stockCode"),
                date = o.getString("date"),
                factors = parseFactors(o.getJSONObject("factors")),
                sf = parseSf(o.getJSONObject("sf")),
                weights = parseWeights(o.getJSONObject("weights")),
                conclusion = PredictionOutcome.valueOf(o.getString("conclusion")),
                open = if (o.isNull("open")) null else o.getDouble("open"),
                prevClose = if (o.isNull("prevClose")) null else o.getDouble("prevClose"),
                outcome30m = parseOutcome(o, "outcome30m"),
                outcomeCloseVsOpen = parseOutcome(o, "outcomeCloseVsOpen"),
                outcomeDayVsPrev = parseOutcome(o, "outcomeDayVsPrev")
            )
        }.getOrNull()
    }
}

private fun buildResult(r: PredictionResult): String =
    JSONObject().put("stockCode", r.stockCode).put("stockName", r.stockName)
        .put("date", r.date).put("score", r.score)
        .put("capApplied", r.capApplied ?: JSONObject.NULL)
        .put("conclusion", r.conclusion.name)
        .put("factors", buildFactors(r.factors))
        .put("weights", buildWeights(r.weights))
        .put("suggestion", r.suggestion)
        .put("confidence", r.confidence)
        .put("insufficient", JSONArray().apply { r.insufficient.forEach { put(it) } })
        .put("hasPosition", r.hasPosition).toString()

private fun parseResult(raw: String): PredictionResult {
    val o = JSONObject(raw)
    val arr = o.optJSONArray("insufficient")
    val ins = (0 until (arr?.length() ?: 0)).map { arr.getString(it) }
    return PredictionResult(
        stockCode = o.getString("stockCode"),
        stockName = o.getString("stockName"),
        date = o.getString("date"),
        score = o.getDouble("score"),
        capApplied = if (o.isNull("capApplied")) null else o.getDouble("capApplied"),
        conclusion = PredictionOutcome.valueOf(o.getString("conclusion")),
        factors = parseFactors(o.getJSONObject("factors")),
        weights = parseWeights(o.getJSONObject("weights")),
        suggestion = o.getString("suggestion"),
        confidence = o.getString("confidence"),
        insufficient = ins,
        hasPosition = o.getBoolean("hasPosition")
    )
}

// ---------------- 候选池联动快照序列化 ----------------

private fun buildUniverse(list: List<UniverseDailySnapshot>): String =
    JSONArray().apply {
        list.forEach { s ->
            val gaps = JSONArray()
            s.gaps.forEach { g ->
                gaps.put(JSONObject()
                    .put("code", g.code).put("name", g.name)
                    .put("industry", g.industry ?: JSONObject.NULL)
                    .put("gap", g.gapPct ?: JSONObject.NULL))
            }
            put(JSONObject().put("date", s.date).put("gaps", gaps))
        }
    }.toString()

private fun parseUniverse(raw: String): List<UniverseDailySnapshot> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            val gapsArr = o.getJSONArray("gaps")
            val gaps = (0 until gapsArr.length()).mapNotNull { j ->
                runCatching {
                    val g = gapsArr.getJSONObject(j)
                    UniverseGap(
                        code = g.getString("code"),
                        name = g.getString("name"),
                        industry = if (g.isNull("industry")) null else g.getString("industry"),
                        gapPct = if (g.isNull("gap")) null else g.getDouble("gap")
                    )
                }.getOrNull()
            }
            UniverseDailySnapshot(o.getString("date"), gaps)
        }.getOrNull()
    }
}
