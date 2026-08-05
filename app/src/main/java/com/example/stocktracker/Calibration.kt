package com.example.stocktracker

import kotlin.math.abs

/**
 * Walk-forward 标定：权重网格（4 预设，含非对称跌权重）、经验阈值曲线（60%/70%）、
 * 信心等级经验映射、极值分段 + 封顶、相邻折翻转护栏。
 * 纪律：每个折只用当日之前的数据拟合，评分一律用记录当时的标准值（out-of-sample）。
 */
object Calibrator {

    // ---- 常量 ----
    const val MIN_CAL_SAMPLES = 20      // 经验曲线所需最少样本
    const val MIN_CONF_SAMPLES = 10     // 信心映射所需最少样本
    const val CONF_NEIGHBORHOOD = 1.0   // |score 差| ≤ 1 视为邻域
    const val CAP_VALUE = 5.0           // 极值段劣化时激活的封顶值
    const val CAP_TRIGGER_PP = 0.10     // 极值段命中率比中段低 ≥10pp 触发封顶
    const val EXT_MIN_SAMPLES = 15      // 外围桶条件概率最少样本
    const val REF_THRESHOLD = 2.0       // 权重网格评估用的固定参照阈值
    val THRESHOLD_CANDIDATES = (10..120 step 5).map { it / 10.0 }  // 1.0..6.0 步长 0.5
    val SEGMENT_BOUNDS = listOf(0.0, 2.0, 4.0, 6.0)

    /** 权重预设：P0 默认 / P1 末端侧重 / P2 广度外围侧重 / P3 非对称（跌权>涨权） */
    val PRESETS = listOf(
        CalibratedWeights(1.0, 0.7, 1.0, 1.0, 1.0, REF_THRESHOLD),
        CalibratedWeights(0.8, 0.6, 1.0, 1.0, 1.2, REF_THRESHOLD),
        CalibratedWeights(1.2, 0.9, 1.2, 1.2, 1.2, REF_THRESHOLD),
        CalibratedWeights(1.0, 0.7, 1.0, 0.8, 1.4, REF_THRESHOLD)
    )

    fun outcomeOf(r: PredictionRecord, t: TargetType): PredictionOutcome? = when (t) {
        TargetType.OPEN30M -> r.outcome30m
        TargetType.CLOSE_VS_OPEN -> r.outcomeCloseVsOpen
        TargetType.DAY_VS_PREV -> r.outcomeDayVsPrev
    }

    // ---------------- 外围三桶条件概率（意见3定稿版） ----------------

    private fun bucketOf(ext: Double): PredictionOutcome? = when {
        ext >= AuctionPredictor.EXT_BUCKET_BOUND -> PredictionOutcome.UP
        ext <= -AuctionPredictor.EXT_BUCKET_BOUND -> PredictionOutcome.DOWN
        else -> null
    }

    /**
     * 涨/跌桶贡献：2·(P(涨|桶) − P(跌|桶))，范围 ±2。
     * 桶内实际涨跌样本 <15 → 贡献 0（不硬猜方向）。中性桶恒 0。
     */
    fun externalContribs(records: List<PredictionRecord>, target: TargetType, externalAvg: Double?): Pair<Double, Double> {
        if (externalAvg == null) return 0.0 to 0.0
        val bucket = bucketOf(externalAvg) ?: return 0.0 to 0.0
        val samples = records.mapNotNull { r ->
            val o = outcomeOf(r, target) ?: return@mapNotNull null
            val b = r.factors.externalAvg?.let { bucketOf(it) } ?: return@mapNotNull null
            if (b == bucket && o != PredictionOutcome.FLAT) o else null
        }
        if (samples.size < EXT_MIN_SAMPLES) return 0.0 to 0.0
        val up = samples.count { it == PredictionOutcome.UP }
        val down = samples.count { it == PredictionOutcome.DOWN }
        if (up + down == 0) return 0.0 to 0.0
        val contrib = 2.0 * (up - down).toDouble() / (up + down)
        return if (bucket == PredictionOutcome.UP) contrib to 0.0 else 0.0 to contrib
    }

    // ---------------- 权重网格（样本内选择 + 相邻折翻转护栏） ----------------

    /** 样本内方向命中率（实际涨跌样本中预测方向一致的占比），参照阈值 2.0 */
    fun inSampleHit(records: List<PredictionRecord>, preset: CalibratedWeights, target: TargetType): Double {
        val dir = records.mapNotNull { r ->
            val o = outcomeOf(r, target) ?: return@mapNotNull null
            if (o == PredictionOutcome.FLAT) null else o
        }
        if (dir.isEmpty()) return 0.0
        var hit = 0
        records.forEach { r ->
            val o = outcomeOf(r, target) ?: return@forEach
            if (o == PredictionOutcome.FLAT) return@forEach
            if (AuctionPredictor.classify(AuctionPredictor.score(r.sf, preset), REF_THRESHOLD) == o) hit++
        }
        return hit.toDouble() / dir.size
    }

    fun bestPreset(records: List<PredictionRecord>, target: TargetType): CalibratedWeights =
        PRESETS.maxByOrNull { inSampleHit(records, it, target) } ?: CalibratedWeights.DEFAULT

    /**
     * 前推序列：对每个折 i，用前缀 [0, i) 拟合权重（含翻转护栏：与上一折最佳预设不一致 → 回落默认），
     * 再用该记录当时的标准化值评分、分类（参照阈值 2.0 标方向，曲线再修正）。
     */
    fun walkForwardSeries(records: List<PredictionRecord>, target: TargetType): List<WFPoint> {
        if (records.size < 2) return emptyList()
        val series = mutableListOf<WFPoint>()
        var prevBest: CalibratedWeights? = null
        for (i in 1 until records.size) {
            val prefix = records.subList(0, i)
            val best = bestPreset(prefix, target)
            val stable = prevBest == null || best == prevBest
            val w = if (stable) best else CalibratedWeights.DEFAULT
            prevBest = best
            val r = records[i]
            val score = AuctionPredictor.score(r.sf, w)
            val pred = AuctionPredictor.classify(score, REF_THRESHOLD)
            val actual = outcomeOf(r, target) ?: continue
            series += WFPoint(r.date, score, pred, actual)
        }
        return series
    }

    // ---------------- 阈值 = 经验曲线（60%/70%） ----------------

    /** 累计曲线：P(方向正确 | |score| ≥ t)，只统计实际涨跌样本；返回满足 minHit 的最小阈值 */
    fun curveThreshold(series: List<WFPoint>, minHit: Double): Double? {
        if (series.size < MIN_CAL_SAMPLES) return null
        for (t in THRESHOLD_CANDIDATES) {
            val dir = series.filter { it.actual != PredictionOutcome.FLAT && abs(it.score) >= t }
            if (dir.isEmpty()) continue
            val hit = dir.count { it.predicted == it.actual }
            if (hit.toDouble() / dir.size >= minHit) return t
        }
        return null
    }

    // ---------------- 信心等级 = 邻域经验命中率 ----------------

    fun confidence(series: List<WFPoint>, score: Double): String {
        if (series.size < MIN_CONF_SAMPLES) return "数据积累中"
        val dir = series.filter { abs(it.score - score) <= CONF_NEIGHBORHOOD && it.actual != PredictionOutcome.FLAT }
        if (dir.isEmpty()) return "数据积累中"
        val rate = dir.count { it.predicted == it.actual }.toDouble() / dir.size
        return when {
            rate >= 0.70 -> "高"
            rate >= 0.60 -> "中"
            else -> "低"
        }
    }

    // ---------------- 极值分段 + 封顶 ----------------

    fun segmentStats(series: List<WFPoint>): List<SegmentStat> =
        SEGMENT_BOUNDS.map { lo ->
            val hi = if (lo == SEGMENT_BOUNDS.last()) Double.MAX_VALUE else SEGMENT_BOUNDS[SEGMENT_BOUNDS.indexOf(lo) + 1]
            val dir = series.filter { it.actual != PredictionOutcome.FLAT && abs(it.score) >= lo && abs(it.score) < hi }
            SegmentStat(lo, dir.size, dir.count { it.predicted == it.actual })
        }

    /** 极值段（|score|≥6）命中率比中段（2-4）低 ≥10pp 且样本足够 → 封顶 5.0 */
    fun capDecision(series: List<WFPoint>): Double? {
        val high = series.filter { abs(it.score) >= 6.0 && it.actual != PredictionOutcome.FLAT }
        val mid = series.filter { abs(it.score) >= 2.0 && abs(it.score) < 6.0 && it.actual != PredictionOutcome.FLAT }
        if (high.size < 5 || mid.size < 5) return null
        val rHigh = high.count { it.predicted == it.actual }.toDouble() / high.size
        val rMid = mid.count { it.predicted == it.actual }.toDouble() / mid.size
        return if (rHigh < rMid - CAP_TRIGGER_PP) CAP_VALUE else null
    }

    fun statsOf(series: List<WFPoint>): WalkForwardStats {
        val flat = series.count { it.actual == PredictionOutcome.FLAT }
        val dirTotal = series.size - flat
        val dirHit = series.count { it.actual != PredictionOutcome.FLAT && it.predicted == it.actual }
        return WalkForwardStats(series.size, flat, dirTotal, dirHit, segmentStats(series))
    }

    /** 实时预测用权重：前缀（当日之前）拟合；历史不足用默认 */
    fun liveWeights(records: List<PredictionRecord>, target: TargetType): CalibratedWeights {
        if (records.size < MIN_CAL_SAMPLES) return CalibratedWeights.DEFAULT
        val best = bestPreset(records, target)
        // 护栏②：与上一折不一致 → 回落默认
        val series = walkForwardSeries(records, target)
        if (series.size < 2) return best
        val lastBest = bestPreset(records.subList(0, records.size - 1), target)
        return if (lastBest == best) best else CalibratedWeights.DEFAULT
    }
}
