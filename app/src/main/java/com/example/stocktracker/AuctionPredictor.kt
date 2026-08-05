package com.example.stocktracker

import kotlin.math.tanh

/**
 * 竞价预测纯逻辑：因子计算 → 标准化 → 评分 → 分类 → 建议。
 * 纪律：只用当日之前的历史做滚动统计（严格不偷看未来）。
 */
object AuctionPredictor {

    // ---- 常量（定死，见方案评审） ----
    const val FALLBACK_STRENGTH_DIV = 1.0   // <10 天回退：合成强度(百分点) ÷ 1.0
    const val FALLBACK_END_DIV = 0.7        // <10 天回退：末端移动(百分点) ÷ 0.7
    const val MIN_STD_SAMPLES = 10          // 滚动 z 所需最少样本
    const val MAX_STD_SAMPLES = 40          // 滚动统计窗口上限
    const val EXT_BUCKET_BOUND = 0.5        // 外围三桶边界 ±0.5%（美股标的）
    const val EXT_MIN_SAMPLES = 15          // 外围桶条件概率所需最少样本
    const val VOL_Z_DIV = 1.5               // 放量放大器 tanh 除数
    const val VOL_AMP_MAX = 0.5             // 放量放大器上限
    const val BREADTH_RANGE = 2.0           // 广度贡献范围 ±2
    const val TARGET_W = 0.5                // 合成强度权重：159915
    const val INDEX_W = 0.25                // 合成强度权重：创业板指
    const val SECTOR_W = 0.25               // 合成强度权重：权重股均值

    // ---------------- 因子计算 ----------------

    /**
     * 合成强度（百分点，单单位）：
     * combined = Σ(wᵢ·xᵢ) / Σwᵢ（仅有效字段，权重自动归一）；全缺失返回 null
     */
    fun combinedStrength(f: PredictionFactors): Double? {
        var sum = 0.0
        var wSum = 0.0
        f.targetGapPct?.let { sum += TARGET_W * it; wSum += TARGET_W }
        f.indexGapPct?.let { sum += INDEX_W * it; wSum += INDEX_W }
        f.sectorAvgGap?.let { sum += SECTOR_W * it; wSum += SECTOR_W }
        return if (wSum == 0.0) null else sum / wSum
    }

    /** 板块广度：(涨家 − 跌家) / 有效家数，-1..1；无有效标的返回 null */
    fun sectorBreadth(gaps: List<Double?>): Double? {
        val valid = gaps.filterNotNull()
        if (valid.isEmpty()) return null
        return valid.count { it > 0.001 } * 1.0 / valid.size -
            valid.count { it < -0.001 } * 1.0 / valid.size
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
     * - strength：对合成强度做滚动 z；不足则领域除数回退（combined/1.0）
     * - endMove：滚动 z；不足则 ÷0.7；无基准快照 → null
     * - vol：滚动 z（不足 → null → 放大器 0）
     * - 外围：三桶条件概率由调用方（Calibrator）提供
     */
    fun standardize(
        f: PredictionFactors,
        strengthHistory: List<Double>,
        endMoveHistory: List<Double>,
        extUpContrib: Double,
        extDownContrib: Double
    ): StandardizedFactors {
        val insufficient = mutableListOf<String>()
        if (f.baselineDays < MIN_STD_SAMPLES) insufficient += "放量基线积累中(${f.baselineDays}/${MIN_STD_SAMPLES}天)"

        val combined = combinedStrength(f)
        val strengthZ: Double
        if (combined == null) {
            strengthZ = 0.0
            insufficient += "行情数据缺失"
        } else {
            val s = rollingStats(strengthHistory)
            strengthZ = if (s != null) (combined - s.first) / s.second
            else combined / FALLBACK_STRENGTH_DIV
        }

        val endMoveZ = f.endMovePct?.let { end ->
            val s = rollingStats(endMoveHistory)
            if (s != null) (end - s.first) / s.second else end / FALLBACK_END_DIV
        }

        val volAmp = if (f.volZ != null) VOL_AMP_MAX * tanh(f.volZ / VOL_Z_DIV) else 0.0
        if (f.volZ == null) insufficient += "放量因子缺失"

        return StandardizedFactors(
            strengthZ = strengthZ,
            endMoveZ = endMoveZ,
            breadth = f.sectorBreadth,
            extUpContrib = extUpContrib,
            extDownContrib = extDownContrib,
            volAmp = volAmp,
            insufficient = insufficient
        )
    }

    // ---------------- 评分 ----------------

    /** score = (强度C + 末端C)·(1+放量放大) + 广度C + 外围C，名义范围 ±10 */
    fun score(sf: StandardizedFactors, w: CalibratedWeights): Double {
        val strengthC = 2.0 * tanh(sf.strengthZ / w.strengthScale)
        val endC = sf.endMoveZ?.let { 2.0 * tanh(it / w.endScale) } ?: 0.0
        val breadthC = sf.breadth?.let { BREADTH_RANGE * it * w.breadthScale } ?: 0.0
        val extC = sf.extUpContrib * w.extUpScale + sf.extDownContrib * w.extDownScale
        return (strengthC + endC) * (1.0 + sf.volAmp) + breadthC + extC
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
