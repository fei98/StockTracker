package com.example.stocktracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 预测编排（按股票独立）：9:20 后（不可撤单窗口）抓行情 → 因子 → 标定权重/阈值 → 评分 →
 * 分类 → 建议 → 落库。每只股票自己的基线/记录/回测，互不污染。
 * 两个结果回填（10:00 开盘30分钟方向、15:05 收盘）写回该股历史记录。
 */
class PredictionEngine(
    private val market: MarketDataApi,
    private val store: SnapshotStore
) {

    companion object {
        const val STAGE_INTENT = "INTENT"   // 9:18（9:15–9:20 意图，不进公式）
        const val STAGE_BASE = "BASE"       // 9:20（不可撤单窗口基准）

        /** 实时预测用标定目标 */
        val LIVE_TARGET = TargetType.CLOSE_VS_OPEN

        /** A 股（含集合竞价的市场）才支持预测 */
        fun isPredictable(stock: Stock): Boolean = stock.market in setOf("sh", "sz", "bj")

        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        /** 9:20 前 = 观察区（可撤单、虚拟单污染，不出评分） */
        fun isObservationPhase(nowMillis: Long): Boolean {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            return h < 9 || (h == 9 && m < 20)
        }
    }

    /** 观察区信息：9:15–9:20 量价变化形态（庄家意图参考，不参与评分） */
    fun observationInfo(stock: Stock, date: String): ObservationInfo =
        AuctionPredictor.observationInfo(
            store.loadStage(stock.marketCode, date, STAGE_INTENT),
            store.loadStage(stock.marketCode, date, STAGE_BASE)
        )

    /** 执行一次预测；观察期、非 A 股或行情缺失返回 null */
    suspend fun runPrediction(
        stock: Stock,
        hasPosition: Boolean,
        date: String = today(),
        nowMillis: Long = System.currentTimeMillis()
    ): PredictionResult? {
        if (isObservationPhase(nowMillis)) return null
        if (!isPredictable(stock)) return null
        val key = stock.marketCode
        val snap = market.fetchSnapshot(stock)
        val target = snap.target ?: return null

        val base = store.loadStage(key, date, STAGE_BASE)
        val history = store.loadSnapshots(key)
        val records = store.loadRecords(key).filter { it.date != date }

        // 因子
        val gaps = snap.sector.map { it.gapPct }
        val sectorAvg = gaps.filterNotNull().takeIf { it.isNotEmpty() }?.average()
        val externalAvg = snap.externalPct.takeIf { it.isNotEmpty() }?.average()
        val endMove = if (base != null && target.price != null) AuctionPredictor.endMovePct(target.price, base.price) else null
        val volZ = if (history.size >= AuctionPredictor.MIN_STD_SAMPLES && target.amountWan != null) {
            val amounts = history.takeLast(AuctionPredictor.MAX_STD_SAMPLES).map { it.auctionAmount }
            val stats = AuctionPredictor.rollingStats(amounts)
            stats?.let { (target.amountWan - it.first) / it.second }
        } else null

        val factors = PredictionFactors(
            targetGapPct = target.pct,
            indexGapPct = snap.indexPct,
            sectorAvgGap = sectorAvg,
            sectorBreadth = AuctionPredictor.sectorBreadth(gaps),
            externalAvg = externalAvg,
            endMovePct = endMove,
            volZ = volZ,
            baselineDays = history.size
        )

        // 标定（只用该股当日之前的数据）
        val strengthHistory = records.mapNotNull { AuctionPredictor.combinedStrength(it.factors) }
        val endMoveHistory = records.mapNotNull { it.factors.endMovePct }
        val (extUp, extDown) = Calibrator.externalContribs(records, LIVE_TARGET, externalAvg)
        val sf = AuctionPredictor.standardize(factors, strengthHistory, endMoveHistory, extUp, extDown)
        val series = Calibrator.walkForwardSeries(records, LIVE_TARGET)
        val weights = Calibrator.liveWeights(records, LIVE_TARGET)
        val threshold = Calibrator.curveThreshold(series, 0.60) ?: CalibratedWeights.DEFAULT.threshold

        val scoreRaw = AuctionPredictor.score(sf, weights)
        val cap = Calibrator.capDecision(series)
        val score = AuctionPredictor.applyCap(scoreRaw, cap)
        val conclusion = AuctionPredictor.classify(score, threshold)
        val confidence = Calibrator.confidence(series, score)
        val suggestion = AuctionPredictor.suggest(conclusion, hasPosition, score)

        val result = PredictionResult(
            stockCode = key,
            stockName = stock.name,
            date = date,
            score = score,
            capApplied = cap,
            conclusion = conclusion,
            factors = factors,
            weights = weights.copy(threshold = threshold),
            suggestion = suggestion,
            confidence = confidence,
            insufficient = sf.insufficient,
            hasPosition = hasPosition
        )

        store.saveLastResult(key, result)
        store.addRecord(key, PredictionRecord(key, date, factors, sf, weights, conclusion, target.open, target.prevClose))
        if (target.open != null && target.prevClose != null && target.amountWan != null) {
            store.addSnapshot(key, DailySnapshot(date, target.open, target.prevClose, target.amountWan, 0.0, 0.0))
        }
        return result
    }

    /** 10:00 回填：开盘后 30 分钟方向 */
    suspend fun recordOutcome30m(stock: Stock, date: String) {
        val key = stock.marketCode
        val rec = store.loadRecords(key).find { it.date == date } ?: return
        val open = rec.open ?: return
        val price = market.fetchTargetQuote(stock)?.price ?: return
        store.updateOutcomes(key, date, outcomeOf(price, open), null, null)
    }

    /** 15:05 回填：收盘 vs 开盘、全天 vs 昨收，并回填当日快照的收盘数据 */
    suspend fun recordOutcomeClose(stock: Stock, date: String) {
        val key = stock.marketCode
        val rec = store.loadRecords(key).find { it.date == date } ?: return
        val quote = market.fetchTargetQuote(stock) ?: return
        val price = quote.price ?: return
        val oCloseVsOpen = rec.open?.let { outcomeOf(price, it) }
        val oDay = rec.prevClose?.let { outcomeOf(price, it) }
        store.updateOutcomes(key, date, null, oCloseVsOpen, oDay)
        store.updateClose(key, date, price, quote.amountWan ?: 0.0)
    }

    /** 该股各目标的前推回测统计（详情弹窗展示） */
    fun walkForwardStats(stock: Stock, date: String): Map<TargetType, WalkForwardStats> {
        val records = store.loadRecords(stock.marketCode).filter { it.date != date }
        return TargetType.entries.associateWith { t ->
            Calibrator.statsOf(Calibrator.walkForwardSeries(records, t))
        }
    }
}
