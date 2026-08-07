package com.example.stocktracker

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

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
    val degraded: Boolean = false,
    val direction: PredictionOutcome? = null, // 显式方向（v11）：避免依赖"看跌"文案匹配判断语义（v10 §4e）
    val pending: String? = null // 数据收集/休市等"未就绪"原因（v13）：非真实交易判断，UI 显示为中性"收集中"，不叫"不交易"
)

/** 特征快照（供单测断言与 v3 标定积累） */
data class IntradayFeatures(
    val phase: MarketPhase,
    val mom15: Double?,      // 最近15分钟涨跌幅 %
    val acc15: Double?,      // 短时加速度 = mom15 − mom30（v9 去共线性，消除重叠计价）
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

    const val MIN_POINTS = 30
    const val EARLY_POS = 30
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

    // 顶部反转加固（评审 §1.2①）：BUY 需短动量向上；距日内高点回落 ≥0.5% 禁买
    // v11 由 −1.0 收口到 −0.5：消除 v7 评审遗留的 fromHigh ∈ (−1.0, −0.5] 残余中间带（可能出 BUY）
    const val BUY_MOM15_MIN = 0.0
    const val BUY_FROM_HIGH_MAX = -0.5

    // 墙钟辅助时段（评审 §1.3⑤ / §2.2b）：data 缺行时兜底判"已收盘"，避免陈旧信号
    const val CLOSED_WALL_MINUTES = 15 * 60 // ≥15:00 视为收盘

    // 数据新鲜度（v9）：盘中数据位置与墙钟错位超过该容忍度视为异常（滞后/非交易日残留）
    const val STALE_TOLERANCE = 10

    // 涨跌停护栏（v9）：距涨/跌停板幅度 ≤ 0.5% 视为接近涨/跌停
    const val LIMIT_NEAR_MARGIN = 0.5

    // 双边成本估算（佣金双边万2.5 + 印花税卖出0.05% + 过户费双边万0.1）≈ 0.12%
    const val ROUND_TRIP_COST_PCT = 0.12

    // v9 评分（去共线性）：30分钟动量为主趋势项 + 短时加速度 + 相对强弱 + 均价 + 放量
    const val W_MOM30 = 1.0
    const val W_ACC15 = 0.6
    const val W_RS = 1.5
    const val W_ABOVE = 2.0
    const val W_VOL = 0.8

    /** A 股交易时区：墙钟兜底与数据新鲜度校验统一用 Asia/Shanghai，避免设备时区错置 */
    val CN_TZ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun noTrade(reason: String) = IntradaySignal(
        IntradayAction.NO_TRADE, 0.0, listOf(reason),
        pending = reason // v13：标记为"未就绪"，UI 不再称"不交易"
    )

    fun fmt(v: Double): String = String.format(Locale.US, "%+.1f%%", v)

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

    /**
     * 墙钟辅助时段：交易时段内返回 null（以数据位置为准），仅当 ≥15:00（Asia/Shanghai）返回 CLOSED。
     * 周末不再无条件判 CLOSED（v8 评审②）：调休周末交易日由数据 + 新鲜度校验决定（数据优先），
     * 普通周末残留数据会被数据相位（已收盘）或新鲜度校验（数据异常）拦截。
     */
    fun wallClockPhase(nowMillis: Long): MarketPhase? {
        val cal = Calendar.getInstance(CN_TZ).apply { timeInMillis = nowMillis }
        val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (min >= CLOSED_WALL_MINUTES) return MarketPhase.CLOSED
        return null
    }

    /**
     * 墙钟（Asia/Shanghai）对应的分钟列表位置（与 parseMinuteIndex 同口径）。
     * 用于数据新鲜度校验：盘中数据位置与墙钟严重错位 → 视为异常数据，不出信号。
     */
    fun expectedMinuteIndex(nowMillis: Long): Int {
        val cal = Calendar.getInstance(CN_TZ).apply { timeInMillis = nowMillis }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val idx = if (h <= 11) (h - 9) * 60 + m - 30 else 120 + (h - 13) * 60 + m
        return idx.coerceIn(0, 240)
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
        prevClose: Double?,
        nowMillis: Long? = null
    ): IntradayFeatures {
        val lastPos = points.size - 1
        val last = points[lastPos]
        val mom15 = momPct(points, MOM15_WINDOW)
        val mom30 = momPct(points, MOM30_WINDOW)
        val acc15 = if (mom15 != null && mom30 != null) mom15 - mom30 else null
        val indexMom30 = indexPoints?.let { momPct(it, MOM30_WINDOW) }
        val hasIndex = indexPoints != null
        val rs = if (mom30 != null && indexMom30 != null) mom30 - indexMom30 else 0.0
        val dayGain = prevClose?.let { pctChange(last.price, it) }
        val aboveAvg = if (last.avgPrice > 0) pctChange(last.price, last.avgPrice) ?: 0.0 else 0.0
        val dayHigh = points.maxOf { it.price }
        val fromHigh = if (dayHigh > 0) pctChange(last.price, dayHigh) ?: 0.0 else 0.0
        return IntradayFeatures(
            // nowMillis 传 null（纯函数/测试）时按数据位置；实机传入墙钟兜底收盘/周末
            phase = if (nowMillis != null) (wallClockPhase(nowMillis) ?: phaseOf(points)) else phaseOf(points),
            mom15 = mom15,
            acc15 = acc15,
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
        hasPosition: Boolean,
        nowMillis: Long? = null,
        priceLimitPct: Double? = null,
        canSell: Boolean = true
    ): IntradaySignal {
        if (points.isEmpty()) return noTrade("数据积累中")
        val f = features(points, indexPoints, prevClose, nowMillis)
        when (f.phase) {
            MarketPhase.EARLY -> return noTrade("数据积累中（约10:00后出信号）")
            MarketPhase.LUNCH -> return noTrade("午间休市")
            MarketPhase.AFTERNOON_START -> return noTrade("数据积累中（约13:30后出信号）")
            MarketPhase.CLOSED -> return noTrade("已收盘")
            else -> {}
        }

        // 数据新鲜度（v9）：交易日盘中但分钟数据与墙钟明显错位（数据滞后/非交易日残留）→ 停用信号
        if (nowMillis != null) {
            val expected = expectedMinuteIndex(nowMillis)
            if (points.size - 1 - expected !in -STALE_TOLERANCE..STALE_TOLERANCE) {
                return noTrade("行情数据异常（可能非交易日或数据滞后），已停用信号")
            }
        }

        val volBoost = when {
            f.volRatio == null || f.volRatio <= VOL_SURGE -> 0.0
            (f.mom30 ?: 0.0) > 0 -> 1.0
            (f.mom30 ?: 0.0) < 0 -> -1.0
            else -> 0.0
        }
        // v9：mom30 为主趋势项，acc15 = mom15 − mom30 为短时加速度，消除 15/30 分钟重叠双重计价
        val score = W_MOM30 * (f.mom30 ?: 0.0) +
            W_ACC15 * (f.acc15 ?: 0.0) +
            W_RS * f.rsIndex +
            W_ABOVE * f.aboveAvg +
            W_VOL * volBoost

        val reasons = mutableListOf<String>()
        f.mom30?.let { reasons += "30分钟动量${fmt(it)}" }
        f.acc15?.let { reasons += "短时加速${fmt(it)}" }
        when {
            !f.hasIndexData -> reasons += "指数数据缺失，信号降级"
            f.indexMom30 == null -> reasons += "指数数据不足，信号降级"
            else -> reasons += "相对指数${fmt(f.rsIndex)}"
        }
        reasons += if (f.aboveAvg >= 0) "站上均价" else "跌破均价"
        if (f.volRatio != null && f.volRatio >= VOL_SURGE) {
            reasons += if ((f.mom30 ?: 0.0) > 0) "放量上攻" else "放量下跌"
        }

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
        // 涨跌停护栏（v9，A 股特有）：接近涨停不买（封板难买），接近跌停卖出信号降级。
        // 先于追高拦截：命中涨停线时用更明确的"封板难买"语义，而非泛化的"追高风险大"。
        if (priceLimitPct != null && f.dayGain != null) {
            val near = priceLimitPct - LIMIT_NEAR_MARGIN
            if (action == IntradayAction.BUY && f.dayGain >= near) {
                action = IntradayAction.WATCH
                reasons += "已接近涨停（${fmt(f.dayGain)}，涨停线 ${fmt(priceLimitPct)}），封板难买，追高风险大"
            } else if (action == IntradayAction.SELL && f.dayGain <= -near) {
                action = IntradayAction.HOLD
                reasons += "已接近跌停（${fmt(f.dayGain)}），卖出困难，注意次日风险"
            }
        }
        // 追高拦截：接近日内高点 + (当日累计涨幅大 或 30分钟涨速快) → 降级
        // 同期评审 §1.3⑩：边界改为 >=，防止恰为 2.0%/1.5% 时抖动漏拦
        val chase = (f.dayGain != null && f.dayGain >= CHASE_DAY_GAIN_PCT || (f.mom30 ?: 0.0) >= CHASE_MOM30_PCT) &&
            f.fromHigh > CHASE_FROM_HIGH_PCT
        if (action == IntradayAction.BUY && chase) {
            action = IntradayAction.WATCH
            reasons += "已接近日内高点（距最高${fmt(f.fromHigh)}），追高风险大"
        }
        // 顶部反转确认（评审 §1.2①）：VWAP 滞后，冲高回落后 aboveAvg 仍会读多头，需额外确认。
        // 置于追高拦截之后：先覆盖"贴近日内高点"的追高语义，再处理"已回落/短动量转弱"的残留盲区
        if (action == IntradayAction.BUY) {
            val mom15 = f.mom15 ?: 0.0
            if (mom15 <= BUY_MOM15_MIN) {
                action = IntradayAction.WATCH
                reasons += "15分钟动量${fmt(mom15)}转弱，存在顶部反转风险，暂缓买入"
            }
            // v11（v7 §3 轻微项）：双条件同时命中时两个原因都展示，不再 if/else if 只显示一个
            if (f.fromHigh <= BUY_FROM_HIGH_MAX) {
                action = IntradayAction.WATCH
                reasons += "已从日内高点回落${fmt(f.fromHigh)}，不追回落"
            }
        }
        // T+1 冻结（v9，A 股特有）：当日买入不可卖，SELL 降级为 HOLD 并说明原因
        if (hasPosition && action == IntradayAction.SELL && !canSell) {
            action = IntradayAction.HOLD
            reasons += "今日买入 T+1 冻结，当日无法卖出"
        }

        return when (action) {
            IntradayAction.BUY -> IntradaySignal(action, score, listOf("动量与相对强度确认，可考虑买入") + reasons, degraded, PredictionOutcome.UP)
            IntradayAction.WATCH -> IntradaySignal(action, score, listOf("观望等待确认") + reasons, degraded)
            IntradayAction.HOLD -> IntradaySignal(action, score, listOf("持有观察") + reasons, degraded)
            IntradayAction.SELL -> IntradaySignal(action, score, listOf("动量转弱，考虑减仓/离场") + reasons, degraded, PredictionOutcome.DOWN)
            IntradayAction.NO_TRADE -> IntradaySignal(action, score, listOf("看跌，暂不建议买入") + reasons, degraded, PredictionOutcome.DOWN)
        }
    }
}

/**
 * A 股涨跌停幅度（%）：主板 10%、ST 5%、创业板/科创板 20%、北交所 30%；港美股无涨跌停返回 null。
 * ST 判断用名称包含 "ST"（腾讯返回 "STxxx" / "*STxxx" 等）。
 */
fun priceLimitPct(stock: Stock): Double? {
    val code = stock.code
    return when {
        stock.market == "hk" || stock.market == "us" -> null
        stock.market == "bj" -> 30.0
        code.startsWith("30") || code.startsWith("68") -> 20.0 // 创业板/科创板（含 ST）
        stock.name.contains("ST") -> 5.0
        else -> 10.0
    }
}

/** 盘中信号快照（v3/v9 验证积累：信号时刻数据 → 30/60 分钟后结果回填） */
data class IntradaySignalSnapshot(
    val stockCode: String,
    val date: String,
    val timeMs: Long,
    val lastPos: Int,
    val price: Double,
    val action: IntradayAction,
    val score: Double,
    val direction: PredictionOutcome? = null, // 方向（仅 BUY/SELL/看跌 NO_TRADE 有值），采集时刻固化
    val outcome30mPct: Double? = null, // 信号后 30 分钟涨跌幅 %（null=未回填）
    val outcome30Ms: Long? = null,
    val outcome60mPct: Double? = null, // 信号后 60 分钟涨跌幅 %（null=未回填）
    val outcome60Ms: Long? = null
)

/** 信号方向（v11 显式化）：直接取信号字段，不再依赖"看跌"文案匹配（v10 §4e） */
fun directionOf(s: IntradaySignal): PredictionOutcome? = s.direction

/** 30 分钟结果统计（v9）：方向命中率 + 扣费后平均净变动，用于样本外验证 */
data class IntradaySignalStats(
    val total: Int,           // 已回填 30 分钟结果的快照数
    val directional: Int,     // 有方向预测的样本数
    val hits: Int,            // 方向命中数
    val hitRate: Double?,     // hits / directional
    val avgNetMovePct: Double? // 方向样本扣费后平均净变动（买=outcome−成本；卖=−outcome−成本）
) {
    val hitRatePct: String? get() = hitRate?.let { String.format(Locale.US, "%.0f%%", it * 100) }
    val avgNetMoveText: String? get() = avgNetMovePct?.let { IntradaySignalEvaluator.fmt(it) }
}

/**
 * 样本外统计（v9）：
 * - 命中：预测方向与实际 30 分钟方向一致（±0.15% 平盘带，复用 outcomeOf）
 * - 净变动：BUY 记 outcome − 双边成本；SELL/NO_TRADE 记 −outcome − 双边成本
 */
fun statsOf(snapshots: List<IntradaySignalSnapshot>, roundTripCostPct: Double = IntradaySignalEvaluator.ROUND_TRIP_COST_PCT): IntradaySignalStats {
    var total = 0
    var directional = 0
    var hits = 0
    var netN = 0
    var netSum = 0.0
    snapshots.forEach { s ->
        val out = s.outcome30mPct ?: return@forEach
        total++
        val pred = s.direction ?: return@forEach
        val outcome = outcomeOf(s.price * (1 + out / 100.0), s.price)
        directional++
        if (pred == outcome) hits++
        // 净变动只计可执行的 BUY/SELL（看跌 NO_TRADE 未交易，不扣成本、不计收益）
        if (s.action == IntradayAction.BUY || s.action == IntradayAction.SELL) {
            val net = when (pred) {
                PredictionOutcome.UP -> out - roundTripCostPct
                PredictionOutcome.DOWN -> -out - roundTripCostPct
                else -> return@forEach
            }
            netSum += net
            netN++
        }
    }
    return IntradaySignalStats(
        total = total,
        directional = directional,
        hits = hits,
        hitRate = if (directional == 0) null else hits.toDouble() / directional,
        avgNetMovePct = if (netN == 0) null else netSum / netN
    )
}
