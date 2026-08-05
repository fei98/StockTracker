package com.example.stocktracker

import kotlin.math.tanh

/**
 * 竞价预测纯逻辑：因子计算 → 标准化 → 评分 → 分类 → 建议。
 * 纪律：只用当日之前的历史做滚动统计（严格不偷看未来）。
 */
object AuctionPredictor {

    // ---- 常量（定死，见方案评审；新因子回退除数纳入文档） ----
    const val FALLBACK_STRENGTH_DIV = 1.0   // <10 天回退：合成强度(百分点) ÷ 1.0
    const val FALLBACK_END_DIV = 0.7        // <10 天回退：末端移动(百分点) ÷ 0.7
    const val FALLBACK_MOMENTUM_DIV = 3.0   // <10 天回退：5 日动量(百分点) ÷ 3.0
    const val STREAK_DIV = 5.0              // 连阳/连阴比例缩放：streak ÷ 5.0（有界整数不做 z-score）
    const val MIN_STD_SAMPLES = 10          // 滚动 z 所需最少样本
    const val MAX_STD_SAMPLES = 40          // 滚动统计窗口上限
    const val EXT_BUCKET_BOUND = 0.5        // 外围三桶边界 ±0.5%（美股标的）
    const val EXT_MIN_SAMPLES = 15          // 外围桶条件概率所需最少样本
    const val VOL_Z_DIV = 1.5               // 放量放大器 tanh 除数
    const val VOL_AMP_MAX = 0.5             // 放量放大器上限
    const val TREND_CTX_SCALE = 3.0         // 趋势情境调整：20日线偏差% 的 tanh 除数
    const val TREND_CTX_MAX = 0.5           // 趋势情境调整上限（放大器乘数 ±50%）
    const val PREVDAY_CTX_SCALE = 1.0       // 昨日涨跌幅% 的 tanh 除数（动量延续置信度）
    const val PREVDAY_CTX_MAX = 0.5         // 昨日涨跌幅对动量的调制上限 ±50%
    const val BREADTH_RANGE = 2.0           // 广度贡献范围 ±2
    const val BREADTH_GAP_CAP = 3.0         // 加权广度：单股涨幅幅度封顶（百分点，8.2）
    const val TARGET_W = 0.5                // 合成强度默认权重：目标股（可搜索，见 CalibratedWeights）
    const val INDEX_W = 0.25                // 合成强度默认权重：指数
    const val SECTOR_W = 0.25               // 合成强度默认权重：板块均值

    // ---------------- 因子计算 ----------------

    /**
     * 合成强度（百分点，单单位）：
     * combined = Σ(wᵢ·xᵢ) / Σwᵢ（仅有效字段，权重自动归一）；全缺失返回 null。
     * 权重来自 CalibratedWeights.targetW/indexW（sectorW = 1-两者），可被 walk-forward 搜索（8.3）。
     */
    fun combinedStrength(f: PredictionFactors, w: CalibratedWeights = CalibratedWeights.DEFAULT): Double? {
        var sum = 0.0
        var wSum = 0.0
        f.targetGapPct?.let { sum += w.targetW * it; wSum += w.targetW }
        f.indexGapPct?.let { sum += w.indexW * it; wSum += w.indexW }
        f.sectorAvgGap?.let { sum += w.sectorW * it; wSum += w.sectorW }
        return if (wSum == 0.0) null else sum / wSum
    }

    /**
     * 板块广度（加权化，8.2）：计方向并按幅度加权，龙头涨 5% 与涨 0.1% 不再等同。
     * weightedBreadth = Σ(sign(g) × min(|g|, cap)) / Σ max(|g|, 1)，范围约 -1..1
     */
    fun sectorBreadth(gaps: List<Double?>): Double? {
        val valid = gaps.filterNotNull()
        if (valid.isEmpty()) return null
        var num = 0.0
        var den = 0.0
        valid.forEach { g ->
            val capped = minOf(kotlin.math.abs(g), BREADTH_GAP_CAP)
            num += if (g > 0) capped else -capped
            den += maxOf(kotlin.math.abs(g), 1.0)
        }
        return if (den == 0.0) null else num / den
    }

    /** 末端移动（百分点）：(9:25 − 基准) / 基准 × 100 */
    fun endMovePct(now: Double, base: Double): Double? =
        if (base <= 0) null else (now - base) / base * 100.0

    /** 末端量能扩张：(现额/基准额 − 1) */
    fun endVolSurge(nowAmount: Double, baseAmount: Double): Double? =
        if (baseAmount <= 0) null else nowAmount / baseAmount - 1.0

    /** 滚动均值/标准差（只用给定样本），样本不足返回 null */
    fun rollingStats(samples: List<Double>): Pair<Double, Double>? {
        if (samples.size < MIN_STD_SAMPLES) return null
        val mean = samples.sum() / samples.size
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        val std = kotlin.math.sqrt(variance)
        return mean to if (std == 0.0) 1.0 else std
    }

    /**
     * 标准化（进公式前）：
     * - strength：存原始合成值与滚动均值/标准差（不足则 null → 评分时领域除数回退）；
     *   合成权重取自 w（8.3 可搜索）
     * - endMove：滚动 z；不足则 ÷0.7；无基准快照 → null
     * - momentum：滚动 z；不足则领域除数回退；再用昨日涨跌幅做动量延续调制
     * - streak：有界整数，比例缩放 streak ÷ 5.0
     * - vol：滚动 z（不足 → null → 放大器 0）；放大器再乘趋势情境调整
     * - 外围：三桶条件概率由调用方（Calibrator）提供
     */
    fun standardize(
        f: PredictionFactors,
        strengthHistory: List<Double>,
        endMoveHistory: List<Double>,
        momentumHistory: List<Double>,
        extUpContrib: Double,
        extDownContrib: Double,
        w: CalibratedWeights = CalibratedWeights.DEFAULT
    ): StandardizedFactors {
        val insufficient = mutableListOf<String>()
        if (f.baselineDays < MIN_STD_SAMPLES) insufficient += "放量基线积累中(${f.baselineDays}/${MIN_STD_SAMPLES}天)"

        val combined = combinedStrength(f, w)
        if (combined == null) insufficient += "行情数据缺失"
        val stats = strengthHistory.takeIf { combined != null }?.let { rollingStats(it) }

        val endMoveZ = f.endMovePct?.let { end ->
            val s = rollingStats(endMoveHistory)
            if (s != null) (end - s.first) / s.second else end / FALLBACK_END_DIV
        }

        val momentumRaw = f.momentum5dPct?.let { m ->
            val s = rollingStats(momentumHistory)
            if (s != null) (m - s.first) / s.second else m / FALLBACK_MOMENTUM_DIV
        } ?: 0.0
        if (f.momentum5dPct == null) insufficient += "动量因子缺失(需6个收盘日)"
        // 动量延续调制：昨日涨跌幅同向放大动量，反向削弱
        val prevCtx = f.prevDayPct?.let { 1.0 + PREVDAY_CTX_MAX * tanh(it / PREVDAY_CTX_SCALE) } ?: 1.0
        val momentumZ = momentumRaw * prevCtx

        // 连阳/连阴：有界整数，比例缩放而非 z-score（避免小标准差导致 z 膨胀）
        val streakZ = f.upStreak?.let { it.toDouble() / STREAK_DIV } ?: 0.0

        val baseAmp = if (f.volZ != null) VOL_AMP_MAX * tanh(f.volZ / VOL_Z_DIV) else 0.0
        if (f.volZ == null) insufficient += "放量因子缺失"
        // 趋势情境调整：趋势向上放大放量信号，向下削弱
        val trendCtx = f.trendDevPct?.let { 1.0 + TREND_CTX_MAX * tanh(it / TREND_CTX_SCALE) } ?: 1.0

        return StandardizedFactors(
            strengthRaw = combined,
            strengthMean = stats?.first,
            strengthStd = stats?.second,
            endMoveZ = endMoveZ,
            momentumZ = momentumZ,
            streakZ = streakZ,
            breadth = f.sectorBreadth,
            extUpContrib = extUpContrib,
            extDownContrib = extDownContrib,
            volAmp = baseAmp * trendCtx,
            insufficient = insufficient
        )
    }

    /** 强度贡献：按候选权重重算合成值与标准化（8.3 搜索时用；stats 可空=领域除数回退） */
    fun strengthC(raw: Double?, mean: Double?, std: Double?, w: CalibratedWeights): Double {
        if (raw == null) return 0.0
        val z = if (std != null && std > 0 && mean != null) (raw - mean) / std
        else raw / FALLBACK_STRENGTH_DIV
        return 2.0 * tanh(z / w.strengthScale)
    }

    // ---------------- 评分 ----------------

    /** score = (强度 + 末端 + 动量 + 连阳)C·(1+放量放大) + 交互项C + 广度C + 外围C，名义范围约 ±13 */
    fun score(sf: StandardizedFactors, w: CalibratedWeights): Double {
        val strengthC = strengthC(sf.strengthRaw, sf.strengthMean, sf.strengthStd, w)
        val endC = sf.endMoveZ?.let { 2.0 * tanh(it / w.endScale) } ?: 0.0
        val momentumC = 2.0 * tanh(sf.momentumZ / w.momentumScale)
        val streakC = 2.0 * tanh(sf.streakZ / w.streakScale)
        // 交互项（8.4）：竞价强度 × 动量同向共振加分，反向扣分
        val interC = w.interactionScale *
            tanh(strengthC / 2.0) * tanh(momentumC / 2.0) * 2.0
        val breadthC = sf.breadth?.let { BREADTH_RANGE * it * w.breadthScale } ?: 0.0
        val extC = sf.extUpContrib * w.extUpScale + sf.extDownContrib * w.extDownScale
        return (strengthC + endC + momentumC + streakC) * (1.0 + sf.volAmp) + interC + breadthC + extC
    }

    // ---------------- 分类与建议 ----------------

    fun classify(score: Double, threshold: Double): PredictionOutcome = when {
        score >= threshold -> PredictionOutcome.UP
        score <= -threshold -> PredictionOutcome.DOWN
        else -> PredictionOutcome.FLAT
    }

    /** 封顶：仅封价格类主项（strength+end 主贡献），保留辅助项；范围 ±CAP */
    fun applyCap(score: Double, cap: Double?): Double = cap?.let { score.coerceIn(-it, it) } ?: score

    fun suggest(conclusion: PredictionOutcome, hasPosition: Boolean, score: Double): String = when (conclusion) {
        PredictionOutcome.UP -> if (hasPosition) {
            "预测看涨：持仓可继续持有；浮盈可观可考虑逢高分批止盈"
        } else {
            "预测看涨：可关注买入机会，注意控制仓位"
        }
        PredictionOutcome.DOWN -> if (hasPosition) {
            "预测看跌：建议考虑减仓或设好止损参考位"
        } else {
            "预测看跌：观望为主，暂不建议买入"
        }
        PredictionOutcome.FLAT -> "预测震荡：观望为主，等待方向明确"
    }

    // ---------------- 观察区（9:15–9:20 意图，不进公式） ----------------

    fun observationInfo(intent: AuctionStageSnapshot?, base: AuctionStageSnapshot?): ObservationInfo {
        if (intent == null || base == null) return ObservationInfo(null, null, base != null)
        return ObservationInfo(
            intentMovePct = endMovePct(base.price, intent.price),
            intentVolSurge = endVolSurge(base.amount, intent.amount),
            hasBase = true
        )
    }
}
