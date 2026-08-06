package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：盘中择时信号（INTRADAY_SIGNAL_PLAN.md v2 全部 12 例） */
class IntradaySignalTest {

    /** 构造分钟序列：均匀成交量，均价固定为 price×(1+avgPct/100)（avgPct=0 → 站均价相等） */
    private fun series(
        prices: List<Double>,
        avgPct: Double = 0.0,
        perMin: List<Long> = List(prices.size) { 100L }
    ): List<MinutePoint> {
        var cum = 0L
        return prices.mapIndexed { i, p ->
            cum += perMin[i]
            MinutePoint(i, p, cum, cum * 100.0 * (p * (1 + avgPct / 100.0)))
        }
    }

    /** 构造带显式 minute 的序列（模拟 11:30 与 13:00 都等于 120 的重复索引） */
    private fun seriesWithMinutes(minutes: List<Int>, prices: List<Double>): List<MinutePoint> {
        var cum = 0L
        return minutes.mapIndexed { i, m ->
            cum += 100
            MinutePoint(m, prices[i], cum, cum * 100.0 * prices[i])
        }
    }

    private val flatIndex: List<MinutePoint> = series(List(60) { 100.0 })

    /** 1. 分钟点 < 40 → NO_TRADE 数据积累中 */
    @Test
    fun 数据不足_不给信号() {
        val pts = series(List(30) { 10.0 + it * 0.1 }) // 巨涨也不给信号
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false)
        assertEquals(IntradayAction.NO_TRADE, s.action)
        assertTrue(s.reasons.any { it.contains("数据积累中") })
    }

    /** 2. 开盘 30 分钟内（位置 < 40）→ NO_TRADE 数据积累中 */
    @Test
    fun 开盘30分钟内不给信号() {
        val pts = series(List(39) { 10.0 + it * 0.05 })
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false)
        assertEquals(IntradayAction.NO_TRADE, s.action)
        assertTrue(s.reasons.any { it.contains("数据积累中") })
    }

    /** 3. 午后 13:00–13:30（位置 121~150，mom30 跨午休）→ NO_TRADE 数据积累中 */
    @Test
    fun 午后刚开始不给信号() {
        val minutes = (0..120).toList() + 120 + (121..147) // lastPos = 148
        val pts = seriesWithMinutes(minutes, List(minutes.size) { 10.0 + it * 0.01 })
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false)
        assertEquals(IntradayAction.NO_TRADE, s.action)
        assertTrue(s.reasons.any { it.contains("数据积累中") })
    }

    /** 4. 单调上涨 + 站上均价 + 相对指数强 + 放量 → BUY */
    @Test
    fun 上涨站上均价放量买入() {
        val perMin = List(40) { 100L } + List(5) { 300L }
        val pts = series(List(45) { 10.0 + it * 0.0025 }, avgPct = -0.4, perMin = perMin)
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false)
        assertEquals(IntradayAction.BUY, s.action)
        assertTrue(s.score >= 2.5)
        assertTrue(s.reasons.any { it.contains("站上均价") })
        assertTrue(s.reasons.any { it.contains("放量上攻") })
    }

    /** 5. 慢涨追高：当日累计涨幅 > 2% 且接近日内高点 → 降级 WATCH + 追高风险大 */
    @Test
    fun 慢涨接近高点追高拦截() {
        val prices = List(45) { 10.0 + minOf(it, 15) * 0.0167 } // 前15分钟涨2.5%，之后横盘
        val pts = series(prices, avgPct = -1.5)
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false)
        assertEquals(IntradayAction.WATCH, s.action)
        assertTrue(s.reasons.any { it.contains("追高风险大") })
    }

    /** 6. 30 分钟动量 > 1.5% 且接近日内高点 → 降级 WATCH + 追高风险大 */
    @Test
    fun 快速拉升接近高点追高警告() {
        val prices = MutableList(45) { 10.0 }
        for (i in 14..44) prices[i] = 10.0 + (i - 14) * 0.0067 // 最近30分钟 +2%
        val pts = series(prices)
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.05, false)
        assertEquals(IntradayAction.WATCH, s.action)
        assertTrue(s.reasons.any { it.contains("追高风险大") })
    }

    /** 7. 下跌 + 跌破均价 + 指数弱（无持仓）→ NO_TRADE 看跌 */
    @Test
    fun 下跌跌破均价不买() {
        val pts = series(List(45) { 10.3 - it * 0.0067 }, avgPct = 0.6)
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.3, false)
        assertEquals(IntradayAction.NO_TRADE, s.action)
        assertTrue(s.reasons.any { it.contains("看跌") })
        assertTrue(s.reasons.any { it.contains("跌破均价") })
    }

    /** 8. 有持仓：弱势 → SELL；强势 → HOLD */
    @Test
    fun 持仓卖出或持有() {
        val down = series(List(45) { 10.3 - it * 0.0067 }, avgPct = 0.6)
        val sell = IntradaySignalEvaluator.evaluate(down, flatIndex, 10.3, true)
        assertEquals(IntradayAction.SELL, sell.action)
        assertTrue(sell.reasons.any { it.contains("减仓") })

        val up = IntradaySignalEvaluator.evaluate(
            series(List(45) { 10.0 + it * 0.0025 }, avgPct = -0.4), flatIndex, 10.0, true
        )
        assertEquals(IntradayAction.HOLD, up.action)
    }

    /** 9. 指数数据缺失 → degraded=true，原因注明，仍给出信号 */
    @Test
    fun 指数缺失降级() {
        val perMin = List(40) { 100L } + List(5) { 300L }
        val pts = series(List(45) { 10.0 + it * 0.0025 }, avgPct = -0.4, perMin = perMin)
        val s = IntradaySignalEvaluator.evaluate(pts, null, 10.0, false)
        assertTrue(s.degraded)
        assertTrue(s.reasons.any { it.contains("指数数据缺失") })
        assertEquals(IntradayAction.BUY, s.action)
    }

    /** 10. 午休边界：分钟含两个 minute=120，按位置取点不崩溃且特征正确 */
    @Test
    fun 午休重复索引按位置取点() {
        // lastPos 148 → AFTERNOON_START 拦截（跨午休）
        val minutes = (0..120).toList() + 120 + (121..147)
        val pts = seriesWithMinutes(minutes, List(minutes.size) { 10.0 + it * 0.001 })
        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false)
        assertEquals(IntradayAction.NO_TRADE, s.action)

        // lastPos 151 → AFTERNOON，特征按位置计算
        val minutes2 = (0..120).toList() + 120 + (121..149)
        val pts2 = seriesWithMinutes(minutes2, List(minutes2.size) { 10.0 + it * 0.001 })
        val f = IntradaySignalEvaluator.features(pts2, null, 10.0)
        assertEquals(IntradayAction.NO_TRADE, IntradaySignalEvaluator.evaluate(pts2, null, 10.0, false).action)
        val expectedMom15 = IntradaySignalEvaluator.momPct(pts2, 15)!!
        assertTrue(expectedMom15 > 0)
        assertTrue(f.mom30 != null && f.mom30!! > f.mom15!!)
        // 均匀量 → 量比 1.0，差分不受重复索引影响
        val r = IntradaySignalEvaluator.volRatioOf(pts2)
        assertTrue(r != null && (r - 1.0) < 1e-9)
    }

    /** 11. 放量下跌 → 负 volBoost（比分母相同无放量的情况下评分更低） */
    @Test
    fun 放量下跌负向加重() {
        val base = series(List(45) { 10.3 - it * 0.0067 }, avgPct = 0.6)
        val surge = series(
            List(45) { 10.3 - it * 0.0067 }, avgPct = 0.6,
            perMin = List(40) { 100L } + List(5) { 300L }
        )
        val sBase = IntradaySignalEvaluator.evaluate(base, flatIndex, 10.3, false)
        val sSurge = IntradaySignalEvaluator.evaluate(surge, flatIndex, 10.3, false)
        assertTrue(sSurge.score < sBase.score)
    }

    /** 12. 非交易时段：15:00 后 → 已收盘；午休（位置 120）→ 午间休市 */
    @Test
    fun 非交易时段不出信号() {
        val closed = series(List(241) { 10.0 }, perMin = List(241) { 100L }) // lastPos = 240
        val s1 = IntradaySignalEvaluator.evaluate(closed, flatIndex, 10.0, false)
        assertEquals(IntradayAction.NO_TRADE, s1.action)
        assertTrue(s1.reasons.any { it.contains("已收盘") })

        val lunchMinutes = (0..120).toList() // lastPos = 120（11:30）
        val lunch = seriesWithMinutes(lunchMinutes, List(lunchMinutes.size) { 10.0 })
        val s2 = IntradaySignalEvaluator.evaluate(lunch, flatIndex, 10.0, false)
        assertEquals(IntradayAction.NO_TRADE, s2.action)
        assertTrue(s2.reasons.any { it.contains("午间休市") })
    }
}