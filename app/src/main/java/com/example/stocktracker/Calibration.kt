package com.example.stocktracker

import kotlin.math.abs

/**
 * Walk-forward 标定：16 权重预设 + 坐标下降连续搜索、经验阈值曲线（60%/70%）、
 * 信心等级经验映射、极值分段 + 封顶、相邻折翻转护栏、时间衰减加权（近期样本权重高）。
 * 纪律：每个折只用当日之前的数据拟合，评分一律用记录当时的标准值（out-of-sample）。
 */
object Calibrator {

    // ---- 常量 ----
    const val MIN_CAL_SAMPLES = 20      // 经验曲线所需最少样本
    const val MIN_CONF_SAMPLES = 10     // 信心映射所需最少样本
    const val CONF_NEIGHBORHOOD = 1.0   // |score 差| ≤ 1 视为邻域
    const val CAP_TRIGGER_PP = 0.10     // 极值段命中率比中段低 ≥10pp 触发封顶
    const val EXT_MIN_SAMPLES = 15      // 外围桶条件概率最少样本
    const val REF_THRESHOLD = 2.0       // 权重评估用的固定参照阈值
    const val DECAY_HALF_LIFE = 30.0    // 时间衰减半衰期（样本数），近期样本权重高
    const val COARSE_STEP = 0.5         // 坐标下降粗搜步长
    const val COARSE_RANGE = 1.5        // 坐标下降粗搜范围 ±1.5（先粗后细，避免局部陷阱）
    const val REFINE_STEP = 0.1         // 坐标下降细搜步长
    const val REFINE_RANGE = 0.3        // 坐标下降细搜范围 ±0.3
    const val REFINE_PASSES = 2         // 细搜轮数
    const val MIN_SCALE = 0.4           // 权重下界（防除零/过小）
    val CAP_CANDIDATES = listOf(8.0, 6.0, 5.0)  // 自适应封顶候选（主项扩展后按劣化位置取）
    val THRESHOLD_CANDIDATES = (10..120 step 5).map { it / 10.0 }  // 1.0..6.0 步长 0.5
    val SEGMENT_BOUNDS = listOf(0.0, 2.0, 4.0, 6.0)

    /** 基础权重预设：P0 默认 / P1 末端侧重 / P2 广度外围侧重 / P3 非对称（跌权>涨权） */
    private val BASE_PRESETS = listOf(
        CalibratedWeights(1.0, 0.7, 1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.5, 0.25, REF_THRESHOLD),
        CalibratedWeights(0.8, 0.6, 1.0, 1.0, 1.2, 1.0, 1.0, 0.5, 0.5, 0.25, REF_THRESHOLD),
        CalibratedWeights(1.2, 0.9, 1.2, 1.2, 1.2, 1.0, 1.0, 0.5, 0.5, 0.25, REF_THRESHOLD),
        CalibratedWeights(1.0, 0.7, 1.0, 0.8, 1.4, 1.0, 1.0, 0.5, 0.5, 0.25, REF_THRESHOLD)
    )

    /** 16 预设 = 基础 4 × 动量/连阳 4 组合（交互/合成权重由坐标下降搜索） */
    val PRESETS: List<CalibratedWeights> = buildList {
        for ((ms, ks) in listOf(1.0 to 1.0, 0.8 to 1.0, 1.0 to 0.8, 0.7 to 0.7)) {
            for (b in BASE_PRESETS) add(b.copy(momentumScale = ms, streakScale = ks))
        }
    }

    /** 多目标投票权重（8.1）：收盘vs开盘为主，30分钟/全天为辅 */
    val VOTE_WEIGHTS: Map<TargetType, Double> = mapOf(
        TargetType.CLOSE_VS_OPEN to 0.5,
        TargetType.OPEN30M to 0.25,
        TargetType.DAY_VS_PREV to 0.25
    )

    /** 时间衰减权重：越新的样本权重越高 */
    fun decayWeight(indexFromNewest: Int, halfLife: Double = DECAY_HALF_LIFE): Double =
        Math.exp(-Math.log(2.0) * indexFromNewest / halfLife)

    fun outcomeOf(r: PredictionRecord, t: TargetType): PredictionOutcome? = when (t) {
        TargetType.OPEN30M -> r.outcome30m
        TargetType.CLOSE_VS_OPEN -> r.outcomeCloseVsOpen
        TargetType.DAY_VS_PREV -> r.outcomeDayVsPrev
    }

    // ---------------- 外围三桶条件概率（意见3定稿版，时间衰减加权） ----------------

    private fun bucketOf(ext: Double): PredictionOutcome? = when {
        ext >= AuctionPredictor.EXT_BUCKET_BOUND -> PredictionOutcome.UP
        ext <= -AuctionPredictor.EXT_BUCKET_BOUND -> PredictionOutcome.DOWN
        else -> null
    }

    /**
     * 涨/跌桶贡献：2·(P(涨|桶) − P(跌|桶))，范围 ±2，样本按时间衰减加权。
     * 桶内实际涨跌样本 <15 → 贡献 0（不硬猜方向）。中性桶恒 0。
     */
    fun externalContribs(records: List<PredictionRecord>, target: TargetType, externalAvg: Double?): Pair<Double, Double> {
        if (externalAvg == null) return 0.0 to 0.0
        val bucket = bucketOf(externalAvg) ?: return 0.0 to 0.0
        val samples = mutableListOf<Pair<Int, PredictionOutcome>>()
        records.forEachIndexed { i, r ->
            val o = outcomeOf(r, target) ?: return@forEachIndexed
            val b = r.factors.externalAvg?.let { bucketOf(it) } ?: return@forEachIndexed
            if (b == bucket && o != PredictionOutcome.FLAT) samples += i to o
        }
        if (samples.size < EXT_MIN_SAMPLES) return 0.0 to 0.0
        val w = { idx: Int -> decayWeight(records.size - 1 - idx) }
        val upW = samples.filter { it.second == PredictionOutcome.UP }.sumOf { w(it.first) }
        val downW = samples.filter { it.second == PredictionOutcome.DOWN }.sumOf { w(it.first) }
        if (upW + downW == 0.0) return 0.0 to 0.0
        val contrib = 2.0 * (upW - downW) / (upW + downW)
        return if (bucket == PredictionOutcome.UP) contrib to 0.0 else 0.0 to contrib
    }

    // ---------------- 权重搜索：16 预设网格 + 坐标下降连续搜索 ----------------

    /**
     * 候选权重下的单记录评分：强度部分按候选合成权重（8.3）重算，
     * 其余因子用记录当时的标准值（out-of-sample）；ext 可换目标桶贡献（投票用）。
     */
    fun scoreFor(r: PredictionRecord, w: CalibratedWeights, strengthStats: Pair<Double, Double>?, ext: Pair<Double, Double>? = null): Double {
        val sf = if (ext != null) r.sf.copy(extUpContrib = ext.first, extDownContrib = ext.second) else r.sf
        val combined = AuctionPredictor.combinedStrength(r.factors, w)
        val strengthC = AuctionPredictor.strengthC(combined, strengthStats?.first, strengthStats?.second, w)
        val base = AuctionPredictor.score(sf, w)
        val baseStrengthC = AuctionPredictor.strengthC(sf.strengthRaw, sf.strengthMean, sf.strengthStd, w)
        return base - baseStrengthC + strengthC
    }

    /** 候选合成权重下的强度滚动统计（O(n)，每轮评估算一次） */
    fun strengthStatsOf(records: List<PredictionRecord>, w: CalibratedWeights): Pair<Double, Double>? =
        AuctionPredictor.rollingStats(records.mapNotNull { AuctionPredictor.combinedStrength(it.factors, w) })

    /** 样本内方向命中率（时间衰减加权），参照阈值 2.0 */
    fun inSampleHit(records: List<PredictionRecord>, preset: CalibratedWeights, target: TargetType): Double {
        val strengthStats = strengthStatsOf(records, preset)
        var hitW = 0.0
        var totW = 0.0
        records.forEachIndexed { i, r ->
            val o = outcomeOf(r, target) ?: return@forEachIndexed
            if (o == PredictionOutcome.FLAT) return@forEachIndexed
            val w = decayWeight(records.size - 1 - i)
            totW += w
            if (AuctionPredictor.classify(scoreFor(r, preset, strengthStats), REF_THRESHOLD) == o) hitW += w
        }
        return if (totW == 0.0) 0.0 else hitW / totW
    }

    /** 网格选出的基础预设（护栏用同一性比较） */
    fun bestBasePreset(records: List<PredictionRecord>, target: TargetType): CalibratedWeights =
        PRESETS.maxByOrNull { inSampleHit(records, it, target) } ?: CalibratedWeights.DEFAULT

    /** 坐标下降连续搜索：先粗搜（±1.5 步长 0.5）再细搜（±0.3 步长 0.1）多轮迭代 */
    fun refine(records: List<PredictionRecord>, target: TargetType, start: CalibratedWeights): CalibratedWeights {
        var best = start
        var bestHit = inSampleHit(records, best, target)
        // 粗搜一轮：覆盖预设之外更远的潜在最优
        var improved = true
        while (improved) {
            improved = false
            for (field in SCALE_FIELDS) {
                val cur = valueOf(best, field)
                var bestVal = cur
                for (d in listOf(-COARSE_RANGE, -COARSE_STEP, COARSE_STEP, COARSE_RANGE)) {
                    val cand = withValue(best, field, (cur + d).coerceAtLeast(MIN_SCALE))
                    val h = inSampleHit(records, cand, target)
                    if (h > bestHit + 1e-9) {
                        bestHit = h
                        bestVal = (cur + d).coerceAtLeast(MIN_SCALE)
                        improved = true
                    }
                }
                best = withValue(best, field, bestVal)
            }
        }
        // 细搜多轮
        repeat(REFINE_PASSES) {
            var imp = false
            for (field in SCALE_FIELDS) {
                val cur = valueOf(best, field)
                var bestVal = cur
                for (d in listOf(-REFINE_RANGE, -REFINE_RANGE / 2, REFINE_RANGE / 2, REFINE_RANGE)) {
                    val cand = withValue(best, field, (cur + d).coerceAtLeast(MIN_SCALE))
                    val h = inSampleHit(records, cand, target)
                    if (h > bestHit + 1e-9) {
                        bestHit = h
                        bestVal = (cur + d).coerceAtLeast(MIN_SCALE)
                        imp = true
                    }
                }
                best = withValue(best, field, bestVal)
            }
            if (!imp) return best
        }
        return best
    }

    private val SCALE_FIELDS =
        listOf("strength", "end", "breadth", "extUp", "extDown", "momentum", "streak", "interaction", "targetW", "indexW")

    private fun valueOf(w: CalibratedWeights, field: String): Double = when (field) {
        "strength" -> w.strengthScale
        "end" -> w.endScale
        "breadth" -> w.breadthScale
        "extUp" -> w.extUpScale
        "extDown" -> w.extDownScale
        "momentum" -> w.momentumScale
        "streak" -> w.streakScale
        "interaction" -> w.interactionScale
        "targetW" -> w.targetW
        else -> w.indexW
    }

    /** 合成权重有界的坐标下降写入（sectorW = 1-两者 ≥ 0.1） */
    private fun withValue(w: CalibratedWeights, field: String, v: Double): CalibratedWeights = when (field) {
        "strength" -> w.copy(strengthScale = v.coerceAtLeast(MIN_SCALE))
        "end" -> w.copy(endScale = v.coerceAtLeast(MIN_SCALE))
        "breadth" -> w.copy(breadthScale = v.coerceAtLeast(MIN_SCALE))
        "extUp" -> w.copy(extUpScale = v.coerceAtLeast(MIN_SCALE))
        "extDown" -> w.copy(extDownScale = v.coerceAtLeast(MIN_SCALE))
        "momentum" -> w.copy(momentumScale = v.coerceAtLeast(MIN_SCALE))
        "streak" -> w.copy(streakScale = v.coerceAtLeast(MIN_SCALE))
        "interaction" -> w.copy(interactionScale = v.coerceIn(0.0, 1.5))
        "targetW" -> {
            val t = v.coerceIn(0.2, 0.8)
            w.copy(targetW = if (t + w.indexW > 0.9) 0.9 - w.indexW else t)
        }
        else -> {
            val i = v.coerceIn(0.05, 0.45)
            w.copy(indexW = if (i + w.targetW > 0.9) 0.9 - w.targetW else i)
        }
    }

    /** 最终权重：网格选基础预设 → 坐标下降细化 */
    fun bestPreset(records: List<PredictionRecord>, target: TargetType): CalibratedWeights =
        refine(records, target, bestBasePreset(records, target))

    /**
     * 前推序列：对每个折 i，用前缀 [0, i) 拟合权重（含翻转护栏），
     * 强度按候选权重重算（scoreFor），分类（参照阈值 2.0 标方向，曲线再修正）。
     */
    fun walkForwardSeries(records: List<PredictionRecord>, target: TargetType): List<WFPoint> {
        if (records.size < 2) return emptyList()
        val series = mutableListOf<WFPoint>()
        var prevBase: CalibratedWeights? = null
        for (i in 1 until records.size) {
            val prefix = records.subList(0, i)
            val base = bestBasePreset(prefix, target)
            val stable = prevBase == null || base == prevBase
            val w = if (stable) refine(prefix, target, base) else CalibratedWeights.DEFAULT
            prevBase = base
            val r = records[i]
            val strengthStats = strengthStatsOf(prefix, w)
            val score = scoreFor(r, w, strengthStats)
            val pred = AuctionPredictor.classify(score, REF_THRESHOLD)
            val actual = outcomeOf(r, target) ?: continue
            series += WFPoint(r.date, score, pred, actual)
        }
        return series
    }

    /**
     * 多目标投票前推序列（8.1）：每个折对三目标分别用各自前缀权重评分，
     * 按 VOTE_WEIGHTS 加权合成 score；实际结果用主目标（CLOSE_VS_OPEN）判定。
     * 用于实时预测的阈值/信心/封顶标定。
     */
    fun walkForwardVotedSeries(records: List<PredictionRecord>): List<WFPoint> {
        if (records.size < 2) return emptyList()
        val targets = VOTE_WEIGHTS.keys.toList()
        val series = mutableListOf<WFPoint>()
        val prevBase = mutableMapOf<TargetType, CalibratedWeights>()
        for (i in 1 until records.size) {
            val prefix = records.subList(0, i)
            var combined = 0.0
            targets.forEach { t ->
                val base = bestBasePreset(prefix, t)
                val stable = prevBase[t] == null || prevBase[t] == base
                prevBase[t] = base
                val w = if (stable) refine(prefix, t, base) else CalibratedWeights.DEFAULT
                val strengthStats = strengthStatsOf(prefix, w)
                val ext = externalContribs(prefix, t, records[i].factors.externalAvg)
                val scoreT = scoreFor(records[i], w, strengthStats, ext)
                combined += VOTE_WEIGHTS[t]!! * scoreT
            }
            val r = records[i]
            val pred = AuctionPredictor.classify(combined, REF_THRESHOLD)
            val actual = outcomeOf(r, TargetType.CLOSE_VS_OPEN) ?: continue
            series += WFPoint(r.date, combined, pred, actual)
        }
        return series
    }

    // ---------------- 阈值 = 经验曲线（F1 最大化，8.7） ----------------

    /**
     * F1 最大化阈值：对每个候选 t，|score| ≥ t 的方向预测，
     * precision = 命中/入选数，recall = 命中/全部方向样本数，F1 = 2PR/(P+R)；取 F1 最大者。
     * 比纯命中率对样本不均衡更稳健。
     */
    fun curveThreshold(series: List<WFPoint>): Double? {
        if (series.size < MIN_CAL_SAMPLES) return null
        val dirTotal = series.count { it.actual != PredictionOutcome.FLAT }
        if (dirTotal == 0) return null
        var best: Pair<Double, Double>? = null // (threshold, F1)
        for (t in THRESHOLD_CANDIDATES) {
            val idxs = series.indices.filter { series[it].actual != PredictionOutcome.FLAT && abs(series[it].score) >= t }
            if (idxs.isEmpty()) continue
            var hitW = 0.0
            var totW = 0.0
            idxs.forEach { i ->
                val w = decayWeight(series.size - 1 - i)
                totW += w
                if (series[i].predicted == series[i].actual) hitW += w
            }
            val precision = if (totW == 0.0) 0.0 else hitW / totW
            val recallW = series.indices.sumOf { i ->
                if (series[i].actual != PredictionOutcome.FLAT) decayWeight(series.size - 1 - i) else 0.0
            }
            val recall = if (recallW == 0.0) 0.0 else hitW / recallW
            if (precision + recall == 0.0) continue
            val f1 = 2.0 * precision * recall / (precision + recall)
            if (best == null || f1 > best.second) best = t to f1
        }
        return best?.first
    }

    // ---------------- 信心等级 = 邻域经验命中率（时间衰减加权） ----------------

    fun confidence(series: List<WFPoint>, score: Double): String {
        if (series.size < MIN_CONF_SAMPLES) return "数据积累中"
        val idxs = series.indices.filter { abs(series[it].score - score) <= CONF_NEIGHBORHOOD && series[it].actual != PredictionOutcome.FLAT }
        if (idxs.isEmpty()) return "数据积累中"
        var hitW = 0.0
        var totW = 0.0
        idxs.forEach { i ->
            val w = decayWeight(series.size - 1 - i)
            totW += w
            if (series[i].predicted == series[i].actual) hitW += w
        }
        val rate = if (totW == 0.0) 0.0 else hitW / totW
        return when {
            rate >= 0.70 -> "高"
            rate >= 0.60 -> "中"
            else -> "低"
        }
    }

    // ---------------- 极值分段 + 自适应封顶（时间衰减加权） ----------------

    /** 区间内方向样本的衰减加权命中统计 */
    private fun weightedRange(series: List<WFPoint>, lo: Double, hi: Double): Pair<Int, Pair<Double, Double>> {
        var hitW = 0.0
        var totW = 0.0
        var n = 0
        series.forEachIndexed { i, p ->
            if (p.actual != PredictionOutcome.FLAT && abs(p.score) >= lo && abs(p.score) < hi) {
                val w = decayWeight(series.size - 1 - i)
                n++
                totW += w
                if (p.predicted == p.actual) hitW += w
            }
        }
        return n to (hitW to totW)
    }

    fun segmentStats(series: List<WFPoint>): List<SegmentStat> =
        SEGMENT_BOUNDS.map { lo ->
            val hi = if (lo == SEGMENT_BOUNDS.last()) Double.MAX_VALUE else SEGMENT_BOUNDS[SEGMENT_BOUNDS.indexOf(lo) + 1]
            val (n, w) = weightedRange(series, lo, hi)
            SegmentStat(lo, n, w.first, w.second)
        }

    /**
     * 自适应封顶：主项已扩展（理论范围更大），封顶值按劣化位置取候选
     * （|score|≥c 的衰减加权命中率低于中段 2-6 达 10pp 时，封顶到 c）。
     * 若最高候选段仍可靠 → 不封顶（返回 null）。
     */
    fun capDecision(series: List<WFPoint>): Double? {
        val (midN, midW) = weightedRange(series, 2.0, 6.0)
        if (midN < 5) return null
        val midRate = if (midW.second == 0.0) 0.0 else midW.first / midW.second
        for (c in CAP_CANDIDATES) {
            val (tailN, tailW) = weightedRange(series, c, Double.MAX_VALUE)
            if (tailN < 5) continue
            val tailRate = if (tailW.second == 0.0) 0.0 else tailW.first / tailW.second
            if (tailRate < midRate - CAP_TRIGGER_PP) return c
        }
        return null
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
        val best = bestBasePreset(records, target)
        // 护栏②：与上一折基础预设不一致 → 回落默认
        val lastBest = bestBasePreset(records.subList(0, records.size - 1), target)
        return if (lastBest == best) refine(records, target, best) else CalibratedWeights.DEFAULT
    }
}
