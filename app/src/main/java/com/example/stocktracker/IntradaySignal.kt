package com.example.stocktracker

import java.util.Locale

/** 盘中择时信号等级 */
enum class IntradayAction(val label: String) {
    BUY("买入信号"), HOLD("持有"), SELL("卖出信号"), WATCH("观望"), NO_TRADE("不交易")
}

/** 盘中时段（按分钟列表最后一个点位置推断，不依赖时钟） */
enum class MarketPhase {
    EARLY,            // 开盘 30 分钟内（位置 < 40）
    MORNING,          // 9:30–11:30
    LUNCH,            // 11:30–13:00（位置 120，13:00 与 11:30 共享索引）
    AFTERNOON_START,  // 13:00–13:30（位置 121~150，mom30 跨午休跳变无意义）
    AFTERNOON,        // 13:30–15:00
    CLOSED            // 15:00 后（位置 ≥ 240）
}

/** 盘中择时信号结果 */
data class IntradaySignal(
    val action: IntradayAction,
    val score: Double,
    val reasons: List<String>,
    val degraded: Boolean = false
)

/** 特征快照（供单测断言与 v3 标定积累） */
data class IntradayFeatures(
    val phase: MarketPhase,
    val mom15: Double?,      // 最近15分钟涨跌幅 %
    val mom30: Double?,      // 最近30分钟涨跌幅 %
    val indexMom30: Double?, // 指数最近30分钟涨跌幅 %
    val rsIndex: Double,     // mom30 − indexMom30；指数缺失时为 0
    val dayGain: Double?,    // 现价 vs 昨收 %
    val volRatio: Double?,   // 最近5分钟均量 / 全天均量（按位置差分）
    val aboveAvg: Double,    // 现价 vs 该分钟均价 %（>0 站上均价）
    val fromHigh: Double,    // 现价 vs 日内最高 %（≤0）
    val hasIndexData: Boolean
)

/**
 * 盘中择时信号（INTRADAY_SIGNAL_PLAN.md v2）。
 * 纯函数、无 Android 依赖。所有"最近 N 分钟"特征按列表位置取点
 * （parseMinuteIndex 将 11:30 与 13:00 都映射为 120，按 minute 定位会错位）。
 */
object IntradaySignalEvaluator {

    const val MIN_POINTS = 40
    const val EARLY_POS = 40
    const val AFTERNOON_START_POS = 121
    const val AFTERNOON_STABLE_POS = 151
    const val LAST_POS = 240

    const val MOM15_WINDOW = 15
    const val MOM30_WINDOW = 30
    const val VOL_WINDOW = 5
    const val VOL_SURGE = 1.5

    const val BUY_SCORE = 2.5
    const val SELL_SCORE = -2.5
    const val CHASE_DAY_GAIN_PCT = 2.0
    const val CHASE_MOM30_PCT = 1.5
    const val CHASE_FROM_HIGH_PCT = -0.5

    const val W_MOM15 = 1.0
    const val W_MOM30 = 0.8
    const val W_RS = 1.5
    const val W_ABOVE = 2.0
    const val W_VOL = 0.8

    private fun noTrade(reason: String) = IntradaySignal(IntradayAction.NO_TRADE, 0.0, listOf(reason))

    private fun fmt(v: Double): String = String.format(Locale.US, "%+.1f%%", v)

    /** 时段推断：按最后一个点的列表位置（0 基） */
    fun phaseOf(points: List<MinutePoint>): MarketPhase {
        if (points.isEmpty()) return MarketPhase.EARLY
        val lastPos = points.size - 1
        return when {
            lastPos < EARLY_POS -> MarketPhase.EARLY
            lastPos < 120 -> MarketPhase.MORNING
            lastPos == 120 -> MarketPhase.LUNCH
            lastPos <= 150 -> MarketPhase.AFTERNOON_START
            lastPos < LAST_POS -> MarketPhase.AFTERNOON
            else -> MarketPhase.CLOSED
        }
    }

    private fun pctChange(a: Double, b: Double): Double? =
        if (b > 0) (a - b) / b * 100 else null

    /** 按位置取点：最近 window 分钟涨跌幅（不足 window 点返回 null） */
    fun momPct(points: List<MinutePoint>, window: Int): Double? {
        val lastPos = points.size - 1
        if (lastPos < window) return null
        return pctChange(points[lastPos].price, points[lastPos - window].price)
    }

    /** 按位置差分：最近 VOL_WINDOW 分钟均量 / 全天均量（排除含开盘集合竞价的首个差分） */
    fun volRatioOf(points: List<MinutePoint>): Double? {
        val lastPos = points.size - 1
        if (lastPos <= VOL_WINDOW) return null
        val recent = (lastPos - VOL_WINDOW + 1..lastPos)
            .map { points[it].cumVolume - points[it - 1].cumVolume }
        val day = (1..lastPos).map { points[it].cumVolume - points[it - 1].cumVolume }
        val dayAvg = day.average()
        if (dayAvg <= 0) return null
        return recent.average() / dayAvg
    }

    fun features(
        points: List<MinutePoint>,
        indexPoints: List<MinutePoint>?,
        prevClose: Double?
    ): IntradayFeatures {
        val lastPos = points.size - 1
        val last = points[lastPos]
        val mom15 = momPct(points, MOM15_WINDOW)
        val mom30 = momPct(points, MOM30_WINDOW)
        val indexMom30 = indexPoints?.let { momPct(it, MOM30_WINDOW) }
        val hasIndex = indexPoints != null
        val rs = if (mom30 != null && indexMom30 != null) mom30 - indexMom30 else 0.0
        val dayGain = prevClose?.let { pctChange(last.price, it) }
        val aboveAvg = if (last.avgPrice > 0) pctChange(last.price, last.avgPrice) ?: 0.0 else 0.0
        val dayHigh = points.maxOf { it.price }
        val fromHigh = if (dayHigh > 0) pctChange(last.price, dayHigh) ?: 0.0 else 0.0
        return IntradayFeatures(
            phase = phaseOf(points),
            mom15 = mom15,
            mom30 = mom30,
            indexMom30 = indexMom30,
            rsIndex = rs,
            dayGain = dayGain,
            volRatio = volRatioOf(points),
            aboveAvg = aboveAvg,
            fromHigh = fromHigh,
            hasIndexData = hasIndex
        )
    }

    fun evaluate(
        points: List<MinutePoint>,
        indexPoints: List<MinutePoint>?,
        prevClose: Double?,
        hasPosition: Boolean
    ): IntradaySignal {
        if (points.isEmpty()) return noTrade("数据积累中")
        val f = features(points, indexPoints, prevClose)
        when (f.phase) {
            MarketPhase.EARLY -> return noTrade("数据积累中")
            MarketPhase.LUNCH -> return noTrade("午间休市")
            MarketPhase.AFTERNOON_START -> return noTrade("数据积累中")
            MarketPhase.CLOSED -> return noTrade("已收盘")
            else -> {}
        }

        val volBoost = when {
            f.volRatio == null || f.volRatio <= VOL_SURGE -> 0.0
            (f.mom30 ?: 0.0) > 0 -> 1.0
            (f.mom30 ?: 0.0) < 0 -> -1.0
            else -> 0.0
        }
        val score = W_MOM15 * (f.mom15 ?: 0.0) +
            W_MOM30 * (f.mom30 ?: 0.0) +
            W_RS * f.rsIndex +
            W_ABOVE * f.aboveAvg +
            W_VOL * volBoost

        val reasons = mutableListOf<String>()
        f.mom15?.let { reasons += "15分钟动量${fmt(it)}" }
        f.mom30?.let { reasons += "30分钟动量${fmt(it)}" }
        when {
            !f.hasIndexData -> reasons += "指数数据缺失，信号降级"
            f.indexMom30 == null -> reasons += "指数数据不足，信号降级"
            else -> reasons += "相对指数${fmt(f.rsIndex)}"
        }
        reasons += if (f.aboveAvg >= 0) "站上均价" else "跌破均价"
        if (f.volRatio != null && f.volRatio >= VOL_SURGE) {
            reasons += if ((f.mom30 ?: 0.0) > 0) "放量上攻" else "放量下跌"
        }

        // 追高拦截：接近日内高点 + (当日累计涨幅大 或 30分钟涨速快) → 降级
        val chase = (f.dayGain != null && f.dayGain > CHASE_DAY_GAIN_PCT || (f.mom30 ?: 0.0) > CHASE_MOM30_PCT) &&
            f.fromHigh > CHASE_FROM_HIGH_PCT

        var action: IntradayAction
        if (hasPosition) {
            action = if (score <= SELL_SCORE) IntradayAction.SELL else IntradayAction.HOLD
        } else {
            action = when {
                score >= BUY_SCORE -> IntradayAction.BUY
                score <= -BUY_SCORE && (f.mom30 ?: 0.0) < 0 -> IntradayAction.NO_TRADE
                else -> IntradayAction.WATCH
            }
        }

        val degraded = !f.hasIndexData || f.indexMom30 == null
        if (action == IntradayAction.BUY && chase) {
            action = IntradayAction.WATCH
            reasons += "已接近日内高点（距最高${fmt(f.fromHigh)}），追高风险大"
        }

        return when (action) {
            IntradayAction.BUY -> IntradaySignal(action, score, listOf("动量与相对强度确认，可考虑买入") + reasons, degraded)
            IntradayAction.WATCH -> IntradaySignal(action, score, listOf("观望等待确认") + reasons, degraded)
            IntradayAction.HOLD -> IntradaySignal(action, score, listOf("持有观察") + reasons, degraded)
            IntradayAction.SELL -> IntradaySignal(action, score, listOf("动量转弱，考虑减仓/离场") + reasons, degraded)
            IntradayAction.NO_TRADE -> IntradaySignal(action, score, listOf("看跌，暂不建议买入") + reasons, degraded)
        }
    }
}
