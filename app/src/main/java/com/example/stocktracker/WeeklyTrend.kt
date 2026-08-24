package com.example.stocktracker

import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 未来一周趋势预测（纯逻辑、无 Android 依赖）。
 *
 * 目标：预测未来 5 个交易日的方向（UP / DOWN / FLAT），输入为日K线（qfq 前复权）。
 * 评分：各特征按滚动 z 标准化后用有界 tanh 映射贡献，加权求和。
 * 标定：walk-forward 前推（每折只用当日之前的数据拟合阈值），F1 最大化阈值、邻域命中率信心，
 *       方向命中率 / 平均前向收益统计，纪律同竞价模型（严格不偷看未来）。
 *
 * 主要文件：WEEKLY_TREND_PLAN.md / WEEKLY_TREND_REVIEW.md。
 */
object WeeklyTrendEvaluator {

    // ---- 目标与时段 ----
    const val HORIZON = 5               // 预测未来 5 个交易日
    const val FLAT_BAND_PCT = 1.0       // 5 日收益 ±1% 视为震荡（方向样本剔除）
    const val MIN_BARS = 120            // 至少 120 根K线才可评分（60日线 + 滚动统计就绪）
    const val MAX_BARS = 300            // 拉取 K 线数量（约 14 个月）

    // ---- 标定 ----
    const val ROLL_WINDOW = 120         // 滚动 z 标准化窗口
    const val MIN_CAL_SAMPLES = 30      // 经验阈值所需最少方向样本
    const val MIN_CONF_SAMPLES = 10     // 信心映射所需最少样本
    const val CONF_NEIGHBORHOOD = 1.0   // |score 差| ≤ 1 视为邻域
    const val DECAY_HALF_LIFE = 40.0    // 时间衰减半衰期（样本数）
    const val DEFAULT_THRESHOLD = 1.5   // 历史不足时的兜底阈值
    const val ROLL_STD_MIN = 0.05       // z 分母下限（百分点），防零方差
    const val STREAK_DIV = 6.0          // 连阳/连阴比例缩放
    const val RSI_SCALE = 15.0          // RSI 相对 50 的 tanh 除数
    const val MIN_Z_SAMPLES = 20        // z 标准化所需最少窗口样本

    val THRESHOLD_CANDIDATES: List<Double> = (5..30 step 2).map { it / 10.0 } // 0.5..3.0

    // ---- 权重（经验初始值，阈值由回测标定） ----
    const val W_RET20 = 1.0             // 20 日动量（中期趋势）
    const val W_RET60 = 0.8             // 60 日动量（长期趋势）
    const val W_MA20DEV = 0.8           // 价格 vs 20 日线
    const val W_MA5V20 = 0.6            // 均线多头/空头排列
    const val W_MA20SLOPE = 0.5         // 20 日线斜率
    const val W_VOL = 0.5               // 量能配合（5日/20日量比）
    const val W_RS = 0.6                // 相对指数 20 日强弱
    const val W_RSI = 0.3               // RSI 强度
    const val W_STREAK = 0.3            // 连阳/连阴
    const val W_OFFHIGH60 = 0.4         // 距 60 日高点（趋势延续）
    const val W_VOLATILITY = -0.2       // 高波动降权

    const val C_SCALE = 1.0             // 通用 tanh 除数
    const val RET60_SCALE = 1.2
    const val VOLA_SCALE = 1.5

    /** A 股交易时区（K 线日期/缓存按此口径） */
    val CN_TZ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    // ---------------- 特征 ----------------

    private fun pctChange(a: Double, b: Double): Double? = if (b > 0) (a - b) / b * 100 else null

    /** 最近 n 日收盘简单均线 */
    private fun sma(bars: List<DailyBar>, i: Int, n: Int): Double =
        (i - n + 1..i).map { bars[it].close }.average()

    /** RSI(n)，简单平均涨跌（Wilder 的简化版），均价涨跌为 0 时返回 100 */
    private fun rsi(bars: List<DailyBar>, i: Int, n: Int = 14): Double? {
        if (i < n) return null
        var gain = 0.0
        var loss = 0.0
        for (k in i - n + 1..i) {
            val d = bars[k].close - bars[k - 1].close
            if (d >= 0) gain += d else loss += -d
        }
        val avgGain = gain / n
        val avgLoss = loss / n
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /** 截至 i 的连续涨/跌天数（正=连涨，负=连跌） */
    private fun streakOf(bars: List<DailyBar>, i: Int): Int {
        if (i < 1) return 0
        val sign = if (bars[i].close >= bars[i - 1].close) 1 else -1
        var streak = 1
        for (k in i - 1 downTo 1) {
            val up = bars[k].close >= bars[k - 1].close
            if (up == (sign > 0)) streak++ else break
        }
        return streak * sign
    }

    /** 最近 20 日收益标准差（波动率，百分点） */
    private fun volatility20(bars: List<DailyBar>, i: Int): Double? {
        if (i < 20) return null
        val rets = (i - 19..i).map { pctChange(bars[it].close, bars[it - 1].close) ?: return null }
        val mean = rets.average()
        return sqrt(rets.sumOf { (it - mean) * (it - mean) } / rets.size)
    }

    /** 第 i 根K线的特征（只用 [0, i] 的数据，不偷看未来） */
    fun featuresAt(bars: List<DailyBar>, indexBars: List<DailyBar>?, i: Int): WeeklyTrendFeatures {
        val c = bars[i].close
        val ret5 = if (i >= 5) pctChange(c, bars[i - 5].close) else null
        val ret20 = if (i >= 20) pctChange(c, bars[i - 20].close) else null
        val ret60 = if (i >= 60) pctChange(c, bars[i - 60].close) else null
        val ma5 = if (i >= 4) sma(bars, i, 5) else null
        val ma20 = if (i >= 19) sma(bars, i, 20) else null
        val ma60 = if (i >= 59) sma(bars, i, 60) else null
        val ma20Dev = ma20?.let { pctChange(c, it) }
        val ma60Dev = ma60?.let { pctChange(c, it) }
        val ma20Prev = if (i >= 24) sma(bars, i - 5, 20) else null
        val ma20Slope = if (ma20 != null && ma20Prev != null) pctChange(ma20, ma20Prev) else null
        val ma5vsMa20 = if (ma5 != null && ma20 != null) pctChange(ma5, ma20) else null
        val high20 = (i - 19).coerceAtLeast(0).let { lo -> (lo..i).maxOf { bars[it].high } }
        val high60 = (i - 59).coerceAtLeast(0).let { lo -> (lo..i).maxOf { bars[it].high } }
        val pctOffHigh20 = pctChange(c, high20)
        val pctOffHigh60 = pctChange(c, high60)
        val vol5 = if (i >= 4) (i - 4..i).map { bars[it].volume.toDouble() }.average() else null
        val vol20 = if (i >= 19) (i - 19..i).map { bars[it].volume.toDouble() }.average() else null
        val volRatio = if (vol5 != null && vol20 != null && vol20 > 0) vol5 / vol20 else null
        // 指数相对强弱按日期对齐（个股停牌导致序列错位时避免比较不同日期）
        val idxClose: Map<String, Double>? = indexBars?.associate { it.date to it.close }
        val indexRS20 = if (i >= 20 && idxClose != null) {
            val cNow = idxClose[bars[i].date]
            val cPrev = idxClose[bars[i - 20].date]
            val ibr20 = if (cNow != null && cPrev != null && cPrev > 0) (cNow - cPrev) / cPrev * 100 else null
            if (ret20 != null && ibr20 != null) ret20 - ibr20 else null
        } else null
        return WeeklyTrendFeatures(
            ret5 = ret5,
            ret20 = ret20,
            ret60 = ret60,
            ma20Dev = ma20Dev,
            ma60Dev = ma60Dev,
            ma20Slope = ma20Slope,
            ma5vsMa20 = ma5vsMa20,
            rsi14 = rsi(bars, i, 14),
            pctOffHigh20 = pctOffHigh20,
            pctOffHigh60 = pctOffHigh60,
            volRatio = volRatio,
            volatility20 = volatility20(bars, i),
            streak = streakOf(bars, i),
            indexRS20 = indexRS20,
            hasIndexData = idxClose?.containsKey(bars[i].date) == true
        )
    }

    // ---------------- 评分 ----------------

    /** 有界贡献：2·tanh(z / scale)，范围 ±2 */
    private fun contrib(z: Double, scale: Double = C_SCALE): Double = 2.0 * tanh(z / scale)

    private fun zScore(value: Double?, window: List<WeeklyTrendFeatures>, sel: (WeeklyTrendFeatures) -> Double?): Double {
        if (value == null) return 0.0
        val vals = window.mapNotNull(sel)
        if (vals.size < MIN_Z_SAMPLES) return 0.0
        val mean = vals.average()
        val variance = vals.sumOf { (it - mean) * (it - mean) } / vals.size
        val std = sqrt(variance)
        val denom = if (std < ROLL_STD_MIN) ROLL_STD_MIN else std
        return (value - mean) / denom
    }

    /**
     * 第 i 根K线评分（只用 [0, i] 数据）。allFeats 为整段特征预计算，
     * 滚动窗口取 [i-ROLL_WINDOW+1, i] 的同一特征值做 z 标准化。
     */
    fun scoreAt(allFeats: List<WeeklyTrendFeatures>, i: Int): Double {
        val f = allFeats[i]
        val lo = (i - ROLL_WINDOW + 1).coerceAtLeast(0)
        val window = allFeats.subList(lo, i + 1)

        val zRet20 = zScore(f.ret20, window) { it.ret20 }
        val zRet60 = zScore(f.ret60, window) { it.ret60 }
        val zMa20 = zScore(f.ma20Dev, window) { it.ma20Dev }
        val zMa5v20 = zScore(f.ma5vsMa20, window) { it.ma5vsMa20 }
        val zSlope = zScore(f.ma20Slope, window) { it.ma20Slope }
        val zVol = zScore(f.volRatio, window) { it.volRatio }
        val zRS = zScore(f.indexRS20, window) { it.indexRS20 }
        val zOffHigh = zScore(f.pctOffHigh60, window) { it.pctOffHigh60 }
        val zVola = zScore(f.volatility20, window) { it.volatility20 }

        val cStreak = contrib(f.streak / STREAK_DIV)
        val cRsi = f.rsi14?.let { 2.0 * tanh((it - 50.0) / RSI_SCALE) } ?: 0.0

        return W_RET20 * contrib(zRet20) +
            W_RET60 * contrib(zRet60, RET60_SCALE) +
            W_MA20DEV * contrib(zMa20) +
            W_MA5V20 * contrib(zMa5v20) +
            W_MA20SLOPE * contrib(zSlope) +
            W_VOL * contrib(zVol) +
            W_RS * contrib(zRS) +
            W_RSI * cRsi +
            W_STREAK * cStreak +
            W_OFFHIGH60 * contrib(zOffHigh) +
            W_VOLATILITY * contrib(zVola, VOLA_SCALE)
    }

    // ---------------- 分类 / 目标 ----------------

    fun classify(score: Double, threshold: Double): TrendOutcome = when {
        score >= threshold -> TrendOutcome.UP
        score <= -threshold -> TrendOutcome.DOWN
        else -> TrendOutcome.FLAT
    }

    /** 第 i 根K线的 5 日前向收益方向（i+HORIZON 超出列表 → 无未来数据返回 FLAT） */
    fun outcome5d(bars: List<DailyBar>, i: Int): TrendOutcome {
        if (i + HORIZON >= bars.size) return TrendOutcome.FLAT
        val ref = bars[i].close
        if (ref <= 0) return TrendOutcome.FLAT
        val chg = (bars[i + HORIZON].close - ref) / ref * 100
        return when {
            chg >= FLAT_BAND_PCT -> TrendOutcome.UP
            chg <= -FLAT_BAND_PCT -> TrendOutcome.DOWN
            else -> TrendOutcome.FLAT
        }
    }

    // ---------------- walk-forward 回测与标定 ----------------

    /** 时间衰减权重：越新样本权重越高 */
    fun decayWeight(indexFromNewest: Int, halfLife: Double = DECAY_HALF_LIFE): Double =
        Math.exp(-Math.log(2.0) * indexFromNewest / halfLife)

    /**
     * 前推序列：对每个有完整特征且未来可判的 bar，用此前样本拟合 F1 阈值后分类（严格 out-of-sample）。
     * 只保留方向样本（实际涨/跌），平盘不计入命中率。
     */
    fun walkForward(bars: List<DailyBar>, indexBars: List<DailyBar>?): List<WeeklyWFPoint> {
        if (bars.size < MIN_BARS + HORIZON) return emptyList()
        val feats = (0 until bars.size).map { featuresAt(bars, indexBars, it) }
        val pts = mutableListOf<WeeklyWFPoint>()
        for (i in MIN_BARS until bars.size - HORIZON) {
            val actual = outcome5d(bars, i)
            if (actual == TrendOutcome.FLAT) continue
            val fwd = (bars[i + HORIZON].close - bars[i].close) / bars[i].close * 100
            val score = scoreAt(feats, i)
            val threshold = curveThreshold(pts) ?: DEFAULT_THRESHOLD
            val pred = classify(score, threshold)
            pts += WeeklyWFPoint(bars[i].date, score, pred, actual, fwd)
        }
        return pts
    }

    /** F1 最大化阈值：对每个候选 t，|score| ≥ t 的方向预测，precision/recall 用候选 t 现场重分类 */
    fun curveThreshold(series: List<WeeklyWFPoint>): Double? {
        if (series.size < MIN_CAL_SAMPLES) return null
        var best: Pair<Double, Double>? = null
        for (t in THRESHOLD_CANDIDATES) {
            val idxs = series.indices.filter { abs(series[it].score) >= t }
            if (idxs.isEmpty()) continue
            var hitW = 0.0
            var totW = 0.0
            idxs.forEach { i ->
                val w = decayWeight(series.size - 1 - i)
                totW += w
                if (classify(series[i].score, t) == series[i].actual) hitW += w
            }
            val precision = if (totW == 0.0) 0.0 else hitW / totW
            val recallW = series.indices.sumOf { decayWeight(series.size - 1 - it) }
            val recall = if (recallW == 0.0) 0.0 else hitW / recallW
            if (precision + recall == 0.0) continue
            val f1 = 2.0 * precision * recall / (precision + recall)
            if (best == null || f1 > best.second) best = t to f1
        }
        return best?.first
    }

    /** 信心等级：邻域衰减加权命中率 */
    fun confidence(series: List<WeeklyWFPoint>, score: Double): String {
        if (series.size < MIN_CONF_SAMPLES) return "数据积累中"
        val idxs = series.indices.filter { abs(series[it].score - score) <= CONF_NEIGHBORHOOD }
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

    /** 回测统计：方向命中率 + 平均前向收益（按预测符号对齐）+ 分段命中率 */
    fun statsOf(series: List<WeeklyWFPoint>): WeeklyBacktestStats {
        val samples = series.size
        val hits = series.count { it.predicted == it.actual }
        val hitRate = if (samples == 0) null else hits.toDouble() / samples
        var fwdSum = 0.0
        var fwdN = 0
        series.forEach { p ->
            if (p.predicted == TrendOutcome.UP) { fwdSum += p.forwardReturn; fwdN++ }
            else if (p.predicted == TrendOutcome.DOWN) { fwdSum += -p.forwardReturn; fwdN++ }
        }
        val avgFwd = if (fwdN == 0) null else fwdSum / fwdN
        val buckets = listOf(0.0, 1.0, 2.0).map { lo ->
            val hi = if (lo == 2.0) Double.MAX_VALUE else lo + 1.0
            val inB = series.filter { abs(it.score) >= lo && abs(it.score) < hi }
            val n = inB.size
            val h = inB.count { it.predicted == it.actual }
            WeeklyBucketStat(lo, n, if (n == 0) null else h.toDouble() / n)
        }
        return WeeklyBacktestStats(samples, hits, hitRate, avgFwd, DEFAULT_THRESHOLD, buckets)
    }

    // ---------------- 完整预测 ----------------

    /** 关键因子原因文案（取贡献最大的前几项） */
    fun reasonsOf(f: WeeklyTrendFeatures, i: Int): List<String> {
        val out = mutableListOf<String>()
        f.ret20?.let { if (abs(it) >= 0.5) out += "20日动量${fmt(it)}" }
        f.ma20Dev?.let { out += if (it >= 0) "站上20日线（+${String.format("%.1f%%", it)}）" else "跌破20日线（${String.format("%.1f%%", it)}）" }
        if (f.ma5vsMa20 != null && f.ma5vsMa20 > 0) out += "均线多头排列"
        f.volRatio?.let { if (it >= 1.2) out += "近5日放量（${String.format("%.1f倍", it)}）" }
        f.indexRS20?.let { if (abs(it) >= 0.5) out += "相对指数${fmt(it)}" }
        f.rsi14?.let { if (it >= 55) out += "RSI偏强（${it.toInt()}）" else if (it <= 45) out += "RSI偏弱（${it.toInt()}）" }
        f.pctOffHigh60?.let { if (it <= -10) out += "距60日高点回撤${fmt(it)}" }
        if (out.isEmpty()) out += "趋势信号中性"
        return out.take(6)
    }

    fun fmt(v: Double): String = String.format(java.util.Locale.US, "%+.1f%%", v)

    /**
     * 完整预测：当前（最后一根K线）评分 + 信心 + 回测统计。
     * K线不足 MIN_BARS 时返回 FLAT + "数据积累中"（无回测）。
     */
    fun forecast(bars: List<DailyBar>, indexBars: List<DailyBar>?, stockCode: String, stockName: String): WeeklyTrendResult {
        if (bars.size < MIN_BARS) {
            return WeeklyTrendResult(
                stockCode, stockName, bars.lastOrNull()?.date ?: "",
                TrendOutcome.FLAT, 0.0, "数据积累中",
                listOf("K线数据不足（需至少 $MIN_BARS 个交易日）"),
                null
            )
        }
        val i = bars.size - 1
        val feats = (0 until bars.size).map { featuresAt(bars, indexBars, it) }
        val series = walkForward(bars, indexBars)
        val score = scoreAt(feats, i)
        val threshold = curveThreshold(series) ?: DEFAULT_THRESHOLD
        val outcome = classify(score, threshold)
        val stats = statsOf(series).copy(threshold = threshold)
        return WeeklyTrendResult(
            stockCode = stockCode,
            stockName = stockName,
            date = bars[i].date,
            outcome = outcome,
            score = score,
            confidence = confidence(series, score),
            reasons = reasonsOf(feats[i], i),
            backtest = stats
        )
    }
}

/** 未来一周趋势预测方向 */
enum class TrendOutcome(val label: String) {
    UP("看涨"), DOWN("看跌"), FLAT("震荡")
}

/** 日K线（qfq 前复权），date 为 yyyy-MM-dd，列表按日期升序 */
data class DailyBar(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Long
)

/** 周趋势特征快照（供单测断言与评分） */
data class WeeklyTrendFeatures(
    val ret5: Double?,         // 5 日涨跌幅 %
    val ret20: Double?,        // 20 日涨跌幅 %
    val ret60: Double?,        // 60 日涨跌幅 %
    val ma20Dev: Double?,      // 现价 vs 20 日线 %
    val ma60Dev: Double?,      // 现价 vs 60 日线 %
    val ma20Slope: Double?,    // 20 日线 5 日变化 %
    val ma5vsMa20: Double?,    // MA5 vs MA20 %
    val rsi14: Double?,        // RSI(14)
    val pctOffHigh20: Double?, // 距 20 日高点 %
    val pctOffHigh60: Double?, // 距 60 日高点 %
    val volRatio: Double?,     // 近 5 日均量 / 近 20 日均量
    val volatility20: Double?, // 20 日收益标准差 %
    val streak: Int,           // 连涨（正）/连跌（负）
    val indexRS20: Double?,    // ret20 − 指数 ret20（%）
    val hasIndexData: Boolean
)

/** 周趋势预测结果 */
data class WeeklyTrendResult(
    val stockCode: String,
    val stockName: String,
    val date: String,          // 预测基准日（最后可用 K 线日期）
    val outcome: TrendOutcome,
    val score: Double,
    val confidence: String,    // 高 / 中 / 低 / 数据积累中
    val reasons: List<String>,
    val backtest: WeeklyBacktestStats?
)

/** 回测统计（walk-forward，out-of-sample） */
data class WeeklyBacktestStats(
    val samples: Int,          // 方向样本数
    val hits: Int,
    val hitRate: Double?,
    val avgForwardReturn: Double?,  // 按预测符号对齐的平均 5 日前向收益 %
    val threshold: Double,
    val bucketStats: List<WeeklyBucketStat>
)

/** |score| 分段命中率 */
data class WeeklyBucketStat(
    val minScore: Double,
    val n: Int,
    val rate: Double?
)

/** 前推回测单点 */
data class WeeklyWFPoint(
    val date: String,
    val score: Double,
    val predicted: TrendOutcome,
    val actual: TrendOutcome,
    val forwardReturn: Double   // 5 日前向收益 %
)

/** 是否 A 股（周趋势预测仅支持 A 股） */
fun isAStock(stock: Stock): Boolean = stock.market in setOf("sh", "sz", "bj")
