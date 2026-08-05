package com.example.stocktracker

/** 预测目标（分开回测，不混在一起） */
enum class TargetType(val label: String) {
    OPEN30M("开盘后30分钟方向"),
    CLOSE_VS_OPEN("收盘 vs 开盘"),
    DAY_VS_PREV("全天 vs 昨收")
}

/** 预测/结果方向 */
enum class PredictionOutcome(val label: String) {
    UP("看涨"), FLAT("震荡"), DOWN("看跌")
}

/**
 * 连续原始因子值（不提前离散化，标准化在预测时按历史滚动统计完成）。
 * 单位：涨跌幅一律为百分点（如 +1.2 表示 +1.2%）。
 */
data class PredictionFactors(
    val targetGapPct: Double? = null,   // 159915 竞价涨幅%
    val indexGapPct: Double? = null,    // 指数竞价涨幅%
    val sectorAvgGap: Double? = null,   // 权重股平均竞价涨幅%（null=全部抓取失败）
    val sectorBreadth: Double? = null,  // 上涨占比方向 +1 全涨 -1 全跌（null=无有效标的）
    val externalAvg: Double? = null,    // 隔夜美股平均涨幅%
    val endMovePct: Double? = null,     // 竞价末端上移幅度%（9:20基准→9:25），null=无基准快照
    val volZ: Double? = null,           // 竞价成交额 z-score（仅历史≥10天时有效）
    val baselineDays: Int = 0,          // 已有每日快照天数
    val momentum5dPct: Double? = null,  // 5 日收盘动量%（前期趋势状态）
    val prevDayPct: Double? = null,     // 昨日涨跌幅%（收盘口径）
    val upStreak: Int? = null,          // 连涨天数（负数=连跌天数），截至昨日
    val trendDevPct: Double? = null     // 昨收相对 20 日均线偏差%（市场情境）
)

/**
 * 标准化后的因子（进评分公式之前的状态）。
 * 所有值无量纲；外围按三桶条件概率折算，权重不搜符号（护栏）。
 * strength 存原始合成值与滚动统计，评分时再按候选合成权重重算（8.3 可搜索）。
 */
data class StandardizedFactors(
    val strengthRaw: Double?,        // 合成强度原始值（百分点，用记录当时的合成权重计算）
    val strengthMean: Double?,       // 滚动均值（仅历史），null=不足
    val strengthStd: Double?,        // 滚动标准差（仅历史），null=不足 → 领域除数回退
    val endMoveZ: Double?,           // 末端移动 z，null=无基准快照 → endC=0
    val momentumZ: Double = 0.0,     // 5 日动量 z（或领域除数回退，含昨日调制）
    val streakZ: Double = 0.0,       // 连阳/连阴比例缩放
    val breadth: Double?,            // null=无板块数据 → breadthC=0
    val extUpContrib: Double,        // 涨桶贡献 2·(P涨−P跌)，样本不足或非涨桶 = 0
    val extDownContrib: Double,      // 跌桶贡献 2·(P涨−P跌)，样本不足或非跌桶 = 0
    val volAmp: Double,              // 放量放大器（已含趋势情境调整），volZ null → 0
    val insufficient: List<String>   // 数据积累中标注
)

/** 权重与阈值（walk-forward 网格 + 坐标下降连续搜索 + 经验曲线标定；DEFAULT 仅在历史不足时占位） */
data class CalibratedWeights(
    val strengthScale: Double,   // 强度 tanh 除数
    val endScale: Double,        // 末端 tanh 除数
    val breadthScale: Double,
    val extUpScale: Double,      // 涨桶权重（不搜符号，仅缩放）
    val extDownScale: Double,    // 跌桶权重（不搜符号，仅缩放）
    val momentumScale: Double,   // 5 日动量 tanh 除数
    val streakScale: Double,     // 连阳/连阴 tanh 除数
    val interactionScale: Double,// 强度×动量交互项强度（8.4，搜索）
    val targetW: Double,         // 合成强度权重：目标股（8.3，搜索；indexW 同理，sectorW=1-两者）
    val indexW: Double,
    val threshold: Double        // 看涨/看跌分档阈值（经验曲线产物，默认仅占位）
) {
    val sectorW: Double get() = (1.0 - targetW - indexW).coerceIn(0.0, 1.0)

    companion object {
        /** 默认权重（占位，历史≤20条时使用） */
        val DEFAULT = CalibratedWeights(1.0, 0.7, 1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.5, 0.25, 2.0)
    }
}

/** 一次预测的完整结果 */
data class PredictionResult(
    val stockCode: String,           // 如 sz159915
    val stockName: String,
    val date: String,
    val score: Double,               // 已应用 cap
    val capApplied: Double?,         // 触发的封顶值（null=未触发）
    val conclusion: PredictionOutcome,
    val factors: PredictionFactors,
    val weights: CalibratedWeights,
    val suggestion: String,
    val confidence: String,
    val insufficient: List<String> = emptyList(),
    val disclaimer: String = "仅为数据参考，不构成投资建议",
    val hasPosition: Boolean
)

/** 每日快照（放量基线 + 回测用，按股票独立） */
data class DailySnapshot(
    val date: String,          // yyyy-MM-dd
    val open: Double,          // 今开
    val prevClose: Double,
    val auctionAmount: Double, // 竞价成交额(万)，9:25 时刻抓取
    val dayAmount: Double,     // 全天成交额(万)，收盘后回填（0=未回填）
    val close: Double          // 收盘价（0=未回填）
)

/**
 * 竞价阶段快照（按股票独立）。
 * stage: INTENT = 9:18（9:15–9:20 意图观察，不进公式）；BASE = 9:20（不可撤单窗口基准）
 */
data class AuctionStageSnapshot(
    val date: String,
    val stage: String,   // INTENT / BASE
    val price: Double,
    val amount: Double   // 成交额(万)
)

/** 观察区信息（9:15–9:20 庄家意图参考，不参与评分） */
data class ObservationInfo(
    val intentMovePct: Double?,   // 9:18→9:20 价格变化%
    val intentVolSurge: Double?,  // 9:18→9:20 量能扩张
    val hasBase: Boolean
)

/** 历史预测记录（按股票独立，含原始/已标准化因子与三个目标的结果） */
data class PredictionRecord(
    val stockCode: String,
    val date: String,
    val factors: PredictionFactors,
    val sf: StandardizedFactors,   // 预测当刻的标准化值（当时只用历史统计，out-of-sample）
    val weights: CalibratedWeights,
    val conclusion: PredictionOutcome,
    val open: Double?,
    val prevClose: Double?,
    val outcome30m: PredictionOutcome? = null,
    val outcomeCloseVsOpen: PredictionOutcome? = null,
    val outcomeDayVsPrev: PredictionOutcome? = null
)

/** 前推回测单点（score, 预测方向, 实际方向） */
data class WFPoint(
    val date: String,
    val score: Double,
    val predicted: PredictionOutcome,
    val actual: PredictionOutcome
)

/** 前推回测统计（分目标、分段）。命中定义：预测涨/跌方向与实际一致；实际 FLAT 样本不计入命中率 */
data class WalkForwardStats(
    val total: Int,              // 总样本数
    val flatActual: Int,         // 实际 FLAT 样本数（不计入命中率）
    val directionalTotal: Int,   // 实际涨/跌样本数
    val directionalHit: Int,     // 预测方向与实际的涨/跌一致数
    val segments: List<SegmentStat>
) {
    val hitRate: Double get() = if (directionalTotal == 0) 0.0 else directionalHit.toDouble() / directionalTotal
}

data class SegmentStat(
    val minScore: Double,       // 分段下界（|score|）
    val n: Int,                 // 方向样本数
    val hitW: Double,           // 时间衰减加权命中
    val totW: Double            // 时间衰减加权总数
) {
    val rate: Double get() = if (totW == 0.0) 0.0 else hitW / totW
}

/** 结果判定：涨/跌/平（平盘带 ±0.15%） */
fun outcomeOf(now: Double, ref: Double): PredictionOutcome {
    if (ref <= 0) return PredictionOutcome.FLAT
    val chg = (now - ref) / ref
    return when {
        chg > 0.0015 -> PredictionOutcome.UP
        chg < -0.0015 -> PredictionOutcome.DOWN
        else -> PredictionOutcome.FLAT
    }
}
