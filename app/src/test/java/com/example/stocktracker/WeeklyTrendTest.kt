package com.example.stocktracker

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：未来一周趋势预测（K线解析/特征/评分/分类/目标/walk-forward/信心/完整预测） */
class WeeklyTrendTest {

    /** 合成日K：date 唯一升序，量均匀，price = start + step*i */
    private fun bars(n: Int, start: Double = 10.0, step: Double = 0.0, vol: Long = 100L): List<DailyBar> {
        var p = start
        return (0 until n).map { i ->
            val b = DailyBar("2026-%02d-%02d".format(1 + i / 28, 1 + i % 28), p, p, p * 1.01, p * 0.99, vol)
            p += step
            b
        }
    }

    private fun flatIndex(n: Int) = bars(n, start = 10.0, step = 0.0)

    // ---------------- K线解析 ----------------

    @Test
    fun 解析日K_真实格式() {
        val raw = """{"code":0,"msg":"","data":{"sz000001":{"qfqday":[
            ["2026-08-17","11.200","11.100","11.220","11.070","929043.000"],
            ["2026-08-18","11.100","11.050","11.140","11.030","808930.000"]
        ]}}}"""
        val bars = parseDailyKline(raw)!!
        assertEquals(2, bars.size)
        assertEquals("2026-08-17", bars[0].date)
        assertEquals(11.200, bars[0].open, 1e-9)
        assertEquals(11.100, bars[0].close, 1e-9)
        assertEquals(11.220, bars[0].high, 1e-9)
        assertEquals(11.070, bars[0].low, 1e-9)
        assertEquals(929043L, bars[0].volume)
    }

    @Test
    fun 解析日K_多字段仍可解析() {
        // 腾讯接口变体：第 7 个字段（成交额）应被忽略，只取前 6 个
        val raw = """{"code":0,"msg":"","data":{"sz000001":{"qfqday":[
            ["2026-08-17","11.200","11.100","11.220","11.070","929043.000","123456.789"],
            ["2026-08-18","11.100","11.050","11.140","11.030","808930.000","654321.987"]
        ]}}}"""
        val bars = parseDailyKline(raw)!!
        assertEquals(2, bars.size)
        assertEquals("2026-08-17", bars[0].date)
        assertEquals(11.100, bars[0].close, 1e-9)
        assertEquals(929043L, bars[0].volume)
    }

    @Test
    fun 解析日K_异常输入返回null() {
        assertNull(parseDailyKline("not json"))
        assertNull(parseDailyKline("""{"code":0,"data":{"sz000001":{"qfqday":[]}}}"""))
    }

    // ---------------- 特征 ----------------

    @Test
    fun 特征_动量和均线() {
        val b = bars(200, start = 10.0, step = 0.01)
        val f = WeeklyTrendEvaluator.featuresAt(b, null, 199)
        val c = b[199].close
        assertEquals((c - b[194].close) / b[194].close * 100, f.ret5!!, 1e-9)
        assertEquals((c - b[179].close) / b[179].close * 100, f.ret20!!, 1e-9)
        assertEquals((c - b[139].close) / b[139].close * 100, f.ret60!!, 1e-9)
        assertTrue(f.ma20Dev!! > 0)     // 上涨 → 站上 20 日线
        assertTrue(f.ma5vsMa20!! > 0)   // 均线多头
        assertTrue(f.ma20Slope!! > 0)   // 20 日线向上
        assertEquals(100.0, f.rsi14!!, 1e-9)   // 单调上涨 → RSI 上限
        assertTrue(f.streak > 0)
        assertTrue(f.pctOffHigh20!! <= 0)      // 现价 ≤ 区间最高（合成数据 close=price < high）
        assertEquals(1.0, f.volRatio!!, 1e-9)     // 均匀量
    }

    @Test
    fun 特征_连涨连跌天数精确() {
        // 单个上涨日 → 1；两个连续上涨日 → 2
        assertEquals(1, WeeklyTrendEvaluator.featuresAt(bars(2, start = 10.0, step = 0.01), null, 1).streak)
        assertEquals(2, WeeklyTrendEvaluator.featuresAt(bars(3, start = 10.0, step = 0.01), null, 2).streak)
        // 单调上涨 50 根 → 49 个连续上涨日
        val longUp = bars(50, start = 10.0, step = 0.01)
        assertEquals(49, WeeklyTrendEvaluator.featuresAt(longUp, null, 49).streak)
        // 连跌
        assertEquals(-2, WeeklyTrendEvaluator.featuresAt(bars(3, start = 10.0, step = -0.01), null, 2).streak)
        // 涨后回调：最后一个下跌日单独成段
        val mixed = listOf(
            DailyBar("d1", 10.00, 10.00, 10.00, 10.00, 100L),
            DailyBar("d2", 10.01, 10.01, 10.01, 10.01, 100L),
            DailyBar("d3", 10.02, 10.02, 10.02, 10.02, 100L),
            DailyBar("d4", 10.01, 10.01, 10.01, 10.01, 100L)
        )
        assertEquals(-1, WeeklyTrendEvaluator.featuresAt(mixed, null, 3).streak)
    }

    @Test
    fun 特征_指数相对强弱_按日期对齐() {
        // 个股停牌一天（缺 01-10），指数完整 → 序列错位；相对强弱必须按日期取值
        val dates = (1..25)
        val index = dates.map { d ->
            val c = 10.0 + 0.01 * (d - 1)
            DailyBar("2026-01-%02d".format(d), c, c, c, c, 100L)
        }
        val stock = dates.filter { it != 10 }.map { d ->
            val c = 10.0 + 0.02 * (d - 1)
            DailyBar("2026-01-%02d".format(d), c, c, c, c, 100L)
        }
        val i = stock.size - 1 // 23，对应日期 01-25
        val f = WeeklyTrendEvaluator.featuresAt(stock, index, i)
        val ret20 = f.ret20!!
        val idxNow = 10.0 + 0.01 * 24 // 01-25
        val idxPrev = 10.0 + 0.01 * 3  // 01-04
        val idxRet = (idxNow - idxPrev) / idxPrev * 100
        assertEquals(ret20 - idxRet, f.indexRS20!!, 1e-9)
        assertTrue(f.hasIndexData)
        // 位置对齐（旧逻辑）会取 01-24 的收盘，应得到不同值
        val idxRetPos = (10.0 + 0.01 * 23 - idxPrev) / idxPrev * 100
        assertTrue(abs((ret20 - idxRetPos) - f.indexRS20!!) > 1e-6)
    }

    @Test
    fun 特征_指数相对强弱() {
        val b = bars(200, start = 10.0, step = 0.02)     // 个股上涨
        val idx = bars(200, start = 10.0, step = 0.0)    // 指数横盘
        val f = WeeklyTrendEvaluator.featuresAt(b, idx, 199)
        assertTrue(f.hasIndexData)
        assertTrue(f.indexRS20!! > 0)
        // 无指数 → hasIndexData=false，indexRS20=null
        val f2 = WeeklyTrendEvaluator.featuresAt(b, null, 199)
        assertTrue(!f2.hasIndexData)
        assertNull(f2.indexRS20)
    }

    // ---------------- 评分 ----------------

    @Test
    fun 评分_上涨为正下跌为负() {
        // 横盘后急拉：当前动量/均线显著高于滚动窗口 → 强正分
        val upBars = bars(170) + bars(50, start = 10.0, step = 0.08)
        val upFeats = (0 until upBars.size).map { WeeklyTrendEvaluator.featuresAt(upBars, null, it) }
        val upScore = WeeklyTrendEvaluator.scoreAt(upFeats, upBars.size - 1)
        assertTrue("expected positive score, got $upScore", upScore > WeeklyTrendEvaluator.DEFAULT_THRESHOLD)

        // 横盘后急跌：强负分
        val downBars = bars(170) + bars(50, start = 10.0, step = -0.08)
        val downFeats = (0 until downBars.size).map { WeeklyTrendEvaluator.featuresAt(downBars, null, it) }
        val downScore = WeeklyTrendEvaluator.scoreAt(downFeats, downBars.size - 1)
        assertTrue("expected negative score, got $downScore", downScore < -WeeklyTrendEvaluator.DEFAULT_THRESHOLD)
    }

    // ---------------- 目标与分类 ----------------

    @Test
    fun 目标_未来五日方向与震荡带() {
        // 6 根：i=0 未来 5 日 +10% → UP
        val up = bars(6, start = 10.0, step = 2.0)
        assertEquals(TrendOutcome.UP, WeeklyTrendEvaluator.outcome5d(up, 0))
        // 未来不足 → FLAT
        assertEquals(TrendOutcome.FLAT, WeeklyTrendEvaluator.outcome5d(up, 5))
        // +0.5% 在 ±1% 震荡带内 → FLAT
        val flat = bars(6, start = 10.0, step = 0.01)
        assertEquals(TrendOutcome.FLAT, WeeklyTrendEvaluator.outcome5d(flat, 0))
        // 下跌 → DOWN
        val down = bars(6, start = 10.0, step = -2.0)
        assertEquals(TrendOutcome.DOWN, WeeklyTrendEvaluator.outcome5d(down, 0))
    }

    @Test
    fun 分类_阈值边界() {
        assertEquals(TrendOutcome.UP, WeeklyTrendEvaluator.classify(1.5, 1.5))
        assertEquals(TrendOutcome.DOWN, WeeklyTrendEvaluator.classify(-1.5, 1.5))
        assertEquals(TrendOutcome.FLAT, WeeklyTrendEvaluator.classify(1.4, 1.5))
        assertEquals(TrendOutcome.FLAT, WeeklyTrendEvaluator.classify(-1.4, 1.5))
    }

    // ---------------- walk-forward 回测 ----------------

    @Test
    fun 回测_横盘后急拉命中高平均收益正() {
        // 170 根横盘 + 50 根急拉：前推样本均落在急拉区，实际方向 UP，评分强正 → 命中高
        val b = bars(170) + bars(50, start = 10.0, step = 0.08)
        val series = WeeklyTrendEvaluator.walkForward(b, null)
        assertTrue(series.isNotEmpty())
        val stats = WeeklyTrendEvaluator.statsOf(series)
        assertTrue(stats.samples > 0)
        assertTrue(stats.hitRate!! >= 0.8)
        assertTrue(stats.avgForwardReturn!! > 0)
        assertTrue(stats.bucketStats.isNotEmpty())
    }

    @Test
    fun 回测_样本不足返回空() {
        val b = bars(100, start = 10.0, step = 0.05) // < MIN_BARS
        assertTrue(WeeklyTrendEvaluator.walkForward(b, null).isEmpty())
    }

    @Test
    fun 阈值_样本不足返回null() {
        val few = (0 until 10).map {
            WeeklyWFPoint("d$it", 2.0, TrendOutcome.UP, TrendOutcome.UP, 1.0)
        }
        assertNull(WeeklyTrendEvaluator.curveThreshold(few))
    }

    @Test
    fun 信心_样本不足返回数据积累中() {
        assertEquals("数据积累中", WeeklyTrendEvaluator.confidence(emptyList(), 2.0))
        val few = (0 until 5).map { WeeklyWFPoint("d$it", 2.0, TrendOutcome.UP, TrendOutcome.UP, 1.0) }
        assertEquals("数据积累中", WeeklyTrendEvaluator.confidence(few, 2.0))
    }

    // ---------------- 完整预测 ----------------

    @Test
    fun 预测_K线不足返回数据积累中() {
        val r = WeeklyTrendEvaluator.forecast(bars(100, step = 0.05), null, "sz000001", "测试")
        assertEquals(TrendOutcome.FLAT, r.outcome)
        assertEquals("数据积累中", r.confidence)
        assertNull(r.backtest)
        assertTrue(r.reasons.any { it.contains("数据不足") })
    }

    @Test
    fun 预测_足够K线出方向与回测() {
        val r = WeeklyTrendEvaluator.forecast(bars(220, start = 10.0, step = 0.05), null, "sz000001", "测试")
        assertNotNull(r.backtest)
        assertTrue(r.reasons.isNotEmpty())
        assertTrue(r.confidence == "高" || r.confidence == "中" || r.confidence == "低")
        assertEquals("sz000001", r.stockCode)
    }

    @Test
    fun 预测_无指数时降级但仍出结果() {
        val r = WeeklyTrendEvaluator.forecast(bars(220, start = 10.0, step = 0.05), null, "sz000001", "测试")
        assertNotNull(r.backtest)
        assertEquals(0, r.reasons.count { it.contains("指数") && !it.contains("相对指数") })
    }

    // ---------------- 市场判定 ----------------

    @Test
    fun A股判定() {
        assertTrue(isAStock(Stock("600519", "贵州茅台", "sh")))
        assertTrue(isAStock(Stock("300750", "宁德时代", "sz")))
        assertTrue(isAStock(Stock("832566", "北交个股", "bj")))
        assertTrue(!isAStock(Stock("00700", "腾讯控股", "hk")))
        assertTrue(!isAStock(Stock("AAPL", "苹果", "us")))
    }
}
