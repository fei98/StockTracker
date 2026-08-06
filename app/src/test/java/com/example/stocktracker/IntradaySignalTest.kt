package com.example.stocktracker

import java.util.Calendar
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

    /** 13. AFTERNOON 分支（评审 §1.4⑪）：完整上午 121 点 + 午后 ≥31 点 → 正常出 BUY/HOLD */
    @Test
    fun 午后稳定时段正常出信号() {
        val n = 157 // lastPos = 156 → AFTERNOON
        val perMin = List(n - 5) { 100L } + List(5) { 300L }
        val pts = series(List(n) { 10.0 + it * 0.001 }, avgPct = -1.0, perMin = perMin)
        val f = IntradaySignalEvaluator.features(pts, flatIndex, 10.0)
        assertEquals(MarketPhase.AFTERNOON, f.phase)

        val s = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false)
        assertEquals(IntradayAction.BUY, s.action)
        assertTrue(s.score >= 2.5)

        val h = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, true)
        assertEquals(IntradayAction.HOLD, h.action)
    }

    /** 14. 顶部反转回归（评审 §1.2①/§1.4⑫）：冲高后回落，VWAP 仍读多头、score 仍 ≥2.5，不得出 BUY */
    @Test
    fun 冲高回落不得出买入() {
        // 回落分支：快速拉到 10.30 后跌回 10.14（距高点 −1.55%），mom15 仍为正 → 靠 fromHigh 拦截
        val pullback = List(45) {
            10.0 + when (it) {
                in 0..39 -> 0.0
                40 -> 0.25
                41 -> 0.30
                42 -> 0.24
                43 -> 0.20
                else -> 0.14
            }
        }
        val s1 = IntradaySignalEvaluator.evaluate(series(pullback), flatIndex, 10.0, false)
        assertTrue(s1.score >= 2.5) // 盲区复现：score 仍够 BUY 阈值
        assertEquals(IntradayAction.WATCH, s1.action)
        assertTrue(s1.reasons.any { it.contains("回落") })

        // 横盘分支：前 29 分钟 10.0，之后 16 分钟拉到 10.14 并横住（mom30=1.4% 不触发追高），mom15 = 0 → 靠短动量拦截
        val flat15 = List(45) { 10.0 + if (it >= 29) 0.14 else 0.0 }
        val s2 = IntradaySignalEvaluator.evaluate(series(flat15), flatIndex, 10.0, false)
        assertTrue(s2.score >= 2.5)
        assertEquals(IntradayAction.WATCH, s2.action)
        assertTrue(s2.reasons.any { it.contains("转弱") })
    }

    /** 15. 墙钟兜底（评审 §1.3⑤/§2.2b）：分钟接口少行时 15:00 后不发陈旧信号；周末不按数据判时段 */
    @Test
    fun 墙钟收盘与周末兜底() {
        // 156 点（缺一行，位置本应 AFTERNOON）+ 墙钟 15:30 → CLOSED
        val pts = series(List(157) { 10.0 + it * 0.001 })
        val friday1500 = atTime(2026, 7, 7, 15, 30) // 2026-08-07 周五 15:30
        assertEquals(MarketPhase.CLOSED, IntradaySignalEvaluator.wallClockPhase(friday1500))
        assertEquals(MarketPhase.AFTERNOON, IntradaySignalEvaluator.phaseOf(pts))
        val sWeek = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false, friday1500)
        assertEquals(IntradayAction.NO_TRADE, sWeek.action)
        assertTrue(sWeek.reasons.any { it.contains("已收盘") })

        // 周六上午：墙钟直接 CLOSED，数据判段被覆盖
        val saturday1100 = atTime(2026, 7, 8, 11, 0) // 2026-08-08 周六 11:00
        assertEquals(MarketPhase.CLOSED, IntradaySignalEvaluator.wallClockPhase(saturday1100))
        val sSat = IntradaySignalEvaluator.evaluate(pts, flatIndex, 10.0, false, saturday1100)
        assertEquals(IntradayAction.NO_TRADE, sSat.action)
        assertTrue(sSat.reasons.any { it.contains("已收盘") })

        // 交易时段内墙钟不干扰数据判段（周五 10:00 → 仍按位置 AFTERNOON）
        val friday1000 = atTime(2026, 7, 7, 10, 0)
        assertEquals(null, IntradaySignalEvaluator.wallClockPhase(friday1000))
        assertEquals(
            MarketPhase.AFTERNOON,
            IntradaySignalEvaluator.features(pts, flatIndex, 10.0, friday1000).phase
        )
    }

    /** 构造指定本地时刻的 epoch 毫秒（与 wallClockPhase 同用默认时区，保证往返一致） */
    private fun atTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}