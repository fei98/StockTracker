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

    /** walk-forward 结果缓存（按股票 + 记录内容签名），避免详情弹窗反复重算 O(n²) */
    private val wfCache = mutableMapOf<String, Pair<Int, Map<TargetType, WalkForwardStats>>>()

    /** 投票前推序列缓存（按股票 + 记录内容签名），避免每次预测重算 O(n³) */
    private val votedCache = mutableMapOf<String, Pair<Int, List<WFPoint>>>()

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

        /** 竞价预测执行窗口（Asia/Shanghai）：9:20–9:35。9:25 正常调度 + 延迟容差；窗口外不落记录 */
        fun isPredictionWindow(nowMillis: Long): Boolean {
            val cal = Calendar.getInstance(IntradaySignalEvaluator.CN_TZ).apply { timeInMillis = nowMillis }
            val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return min in PREDICTION_START_MIN until PREDICTION_END_MIN
        }

        /** 结果回填窗口：30 分钟方向取 10:00–10:15 区间价；收盘取 15:00–16:00 收盘价 */
        fun isOutcome30mWindow(nowMillis: Long): Boolean {
            val cal = Calendar.getInstance(IntradaySignalEvaluator.CN_TZ).apply { timeInMillis = nowMillis }
            val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return min in OUT30_START_MIN until OUT30_END_MIN
        }

        fun isOutcomeCloseWindow(nowMillis: Long): Boolean {
            val cal = Calendar.getInstance(IntradaySignalEvaluator.CN_TZ).apply { timeInMillis = nowMillis }
            val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return min in CLOSE_START_MIN until CLOSE_END_MIN
        }

        const val PREDICTION_START_MIN = 9 * 60 + 20
        const val PREDICTION_END_MIN = 9 * 60 + 35
        const val OUT30_START_MIN = 10 * 60
        const val OUT30_END_MIN = 10 * 60 + 15
        const val CLOSE_START_MIN = 15 * 60
        const val CLOSE_END_MIN = 16 * 60
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
        // v11（v10 §4b 纵深防御）：引擎层窗口守卫，防未来恢复手动入口时盘中价污染历史记录
        if (!isPredictionWindow(nowMillis)) return null
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

        // 前期趋势状态：从已回填收盘价的快照计算（当日快照尚未生成，天然只用历史）
        val closes = history.filter { it.close > 0 }
        val momentum5d = if (closes.size >= 6) {
            val base5 = closes[closes.size - 6].close
            if (base5 > 0) (closes.last().close - base5) / base5 * 100 else null
        } else null
        val prevDayPct = if (closes.size >= 2 && closes[closes.size - 2].close > 0) {
            (closes.last().close - closes[closes.size - 2].close) / closes[closes.size - 2].close * 100
        } else null
        var upStreak: Int? = null
        if (closes.size >= 2) {
            val last = closes.last().close
            val prev = closes[closes.size - 2].close
            val sign = if (last >= prev) 1 else -1
            var streak = 1
            for (k in closes.size - 2 downTo 1) {
                val up = closes[k].close >= closes[k - 1].close
                if (up == (sign > 0)) streak++ else break
            }
            upStreak = streak * sign
        }
        val trendDev = if (closes.size >= 20) {
            val ma20 = closes.takeLast(20).map { it.close }.average()
            if (ma20 > 0) (closes.last().close - ma20) / ma20 * 100 else null
        } else null

        val factors = PredictionFactors(
            targetGapPct = target.pct,
            indexGapPct = snap.indexPct,
            sectorAvgGap = sectorAvg,
            sectorBreadth = AuctionPredictor.sectorBreadth(gaps),
            externalAvg = externalAvg,
            endMovePct = endMove,
            volZ = volZ,
            baselineDays = history.size,
            momentum5dPct = momentum5d,
            prevDayPct = prevDayPct,
            upStreak = upStreak,
            trendDevPct = trendDev
        )

        // 标定（只用该股当日之前的数据）
        // 三目标各自标定权重（8.1 投票）
        val weightsByTarget = Calibrator.VOTE_WEIGHTS.keys.associateWith { t ->
            Calibrator.liveWeights(records, t)
        }
        // strengthHistory 按各目标权重计算（消除 DEFAULT 权重统计偏差，评审中优先级）
        val strengthHistoryByTarget = Calibrator.VOTE_WEIGHTS.keys.associateWith { t ->
            val w = weightsByTarget[t]!!
            records.mapNotNull { AuctionPredictor.combinedStrength(it.factors, w) }
        }
        val endMoveHistory = records.mapNotNull { it.factors.endMovePct }
        val momentumHistory = records.mapNotNull { it.factors.momentum5dPct }

        val sfByTarget = Calibrator.VOTE_WEIGHTS.keys.associateWith { t ->
            val (extUp, extDown) = Calibrator.externalContribs(records, t, externalAvg)
            AuctionPredictor.standardize(
                factors, strengthHistoryByTarget[t]!!, endMoveHistory, momentumHistory,
                extUp, extDown, weightsByTarget[t]!!
            )
        }
        val scoreByTarget = Calibrator.VOTE_WEIGHTS.keys.associateWith { t ->
            AuctionPredictor.score(sfByTarget[t]!!, weightsByTarget[t]!!)
        }
        // 加权投票分：主目标(收盘vs开盘)0.5 + 30分钟/全天各 0.25
        val scoreRaw = Calibrator.VOTE_WEIGHTS.entries.sumOf { (t, v) -> v * scoreByTarget[t]!! }

        // 阈值/信心/封顶用投票前推序列标定（实际结果以主目标判定，带缓存避免 O(n³) 重算）
        val series = votedSeries(stock, date)
        val threshold = Calibrator.curveThreshold(series) ?: CalibratedWeights.DEFAULT.threshold
        val cap = Calibrator.capDecision(series)
        val score = AuctionPredictor.applyCap(scoreRaw, cap)
        val conclusion = AuctionPredictor.classify(score, threshold)
        val confidence = Calibrator.confidence(series, score)
        val suggestion = AuctionPredictor.suggest(conclusion, hasPosition, score)

        val weights = weightsByTarget[LIVE_TARGET]!!
        val sf = sfByTarget[LIVE_TARGET]!!
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

    /** 10:00 回填：开盘后 30 分钟方向（仅 10:00–10:15 窗口执行，防迟到用盘中价误标） */
    suspend fun recordOutcome30m(stock: Stock, date: String, nowMillis: Long = System.currentTimeMillis()) {
        if (!isOutcome30mWindow(nowMillis)) return
        val key = stock.marketCode
        val rec = store.loadRecords(key).find { it.date == date } ?: return
        val open = rec.open ?: return
        val price = market.fetchTargetQuote(stock)?.price ?: return
        store.updateOutcomes(key, date, outcomeOf(price, open), null, null)
    }

    /** 15:05 回填：收盘 vs 开盘、全天 vs 昨收，并回填当日快照的收盘数据（仅 15:00–16:00 窗口执行） */
    suspend fun recordOutcomeClose(stock: Stock, date: String, nowMillis: Long = System.currentTimeMillis()) {
        if (!isOutcomeCloseWindow(nowMillis)) return
        val key = stock.marketCode
        val rec = store.loadRecords(key).find { it.date == date } ?: return
        val quote = market.fetchTargetQuote(stock) ?: return
        val price = quote.price ?: return
        val oCloseVsOpen = rec.open?.let { outcomeOf(price, it) }
        val oDay = rec.prevClose?.let { outcomeOf(price, it) }
        store.updateOutcomes(key, date, null, oCloseVsOpen, oDay)
        store.updateClose(key, date, price, quote.amountWan ?: 0.0)
    }

    /** 该股各目标的前推回测统计（详情弹窗展示，带缓存） */
    fun walkForwardStats(stock: Stock, date: String): Map<TargetType, WalkForwardStats> {
        val key = stock.marketCode
        val records = store.loadRecords(key).filter { it.date != date }
        val sig = records.hashCode() // 数据类内容签名，记录变化即失效
        val cached = wfCache[key]
        if (cached != null && cached.first == sig) return cached.second
        val stats = TargetType.entries.associateWith { t ->
            Calibrator.statsOf(Calibrator.walkForwardSeries(records, t))
        }
        wfCache[key] = sig to stats
        return stats
    }

    /** 投票前推序列（阈值/信心/封顶标定用，带缓存；记录变化即失效） */
    fun votedSeries(stock: Stock, date: String): List<WFPoint> {
        val key = stock.marketCode
        val records = store.loadRecords(key).filter { it.date != date }
        val sig = records.hashCode()
        val cached = votedCache[key]
        if (cached != null && cached.first == sig) return cached.second
        val series = Calibrator.walkForwardVotedSeries(records)
        votedCache[key] = sig to series
        return series
    }
}
