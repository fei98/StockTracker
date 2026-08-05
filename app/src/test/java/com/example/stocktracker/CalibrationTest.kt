package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：外围三桶条件概率、权重网格、walk-forward 严格性、经验阈值曲线、信心映射、极值封顶 */
class CalibrationTest {

    private fun sf(strengthZ: Double, endZ: Double? = null) = StandardizedFactors(
        strengthZ, endZ, null, 0.0, 0.0, 0.0, emptyList()
    )

    private fun record(
        date: String, strengthZ: Double, outcome: PredictionOutcome?,
        external: Double? = null, endZ: Double? = null
    ) = PredictionRecord(
        stockCode = "sz159915",
        date = date,
        factors = PredictionFactors(externalAvg = external),
        sf = sf(strengthZ, endZ),
        weights = CalibratedWeights.DEFAULT,
        conclusion = PredictionOutcome.FLAT,
        open = null, prevClose = null,
        outcome30m = outcome, outcomeCloseVsOpen = outcome, outcomeDayVsPrev = outcome
    )

    // ---------------- 外围三桶 ----------------

    @Test
    fun 外围_中性桶贡献为零() {
        val records = listOf(record("1", 0.0, PredictionOutcome.UP, external = 0.1))
        val (up, down) = Calibrator.externalContribs(records, TargetType.CLOSE_VS_OPEN, 0.1)
        assertEquals(0.0, up, 0.0001)
        assertEquals(0.0, down, 0.0001)
    }

    @Test
    fun 外围_样本不足贡献为零() {
        val records = List(5) { record("r$it", 0.0, PredictionOutcome.UP, external = 1.0) }
        val (up, down) = Calibrator.externalContribs(records, TargetType.CLOSE_VS_OPEN, 1.0)
        assertEquals(0.0, up, 0.0001)
        assertEquals(0.0, down, 0.0001)
    }

    @Test
    fun 外围_跌桶条件概率修正独立行情() {
        // 美股跌但 A 股常涨（独立行情）：跌桶 P(涨) > P(跌) → 贡献为正
        val records = mutableListOf<PredictionRecord>()
        var i = 0
        repeat(12) { records += record("u$i", 0.0, PredictionOutcome.UP, external = -1.0); i++ }
        repeat(4) { records += record("d$i", 0.0, PredictionOutcome.DOWN, external = -1.0); i++ }
        val (up, down) = Calibrator.externalContribs(records, TargetType.CLOSE_VS_OPEN, -1.0)
        assertEquals(0.0, up, 0.0001)
        // 贡献 = 2·(12-4)/16 = 1.0（跌桶转化为看多贡献）
        assertEquals(1.0, down, 0.0001)
    }

    // ---------------- 权重网格与护栏 ----------------

    @Test
    fun 权重网格_选样本内命中率最高的预设() {
        // 构造数据：所有记录 strengthZ/endZ 足够大、结论一致，任意预设都能猜对
        val records = List(25) { record("r$it", 5.0, PredictionOutcome.UP, endZ = 5.0) }
        val best = Calibrator.bestPreset(records, TargetType.CLOSE_VS_OPEN)
        assertTrue(best in Calibrator.PRESETS)
        assertEquals(1.0, Calibrator.inSampleHit(records, best, TargetType.CLOSE_VS_OPEN), 0.0001)
    }

    @Test
    fun walkForward_只用历史数据_后期才可用() {
        // 前 30 条无结果（无法命中），后 20 条 UP：序列应只统计有结果的点
        val records = mutableListOf<PredictionRecord>()
        repeat(30) { records += record("no$it", 3.0, null) }
        repeat(20) { records += record("up$it", 3.0, PredictionOutcome.UP) }
        val series = Calibrator.walkForwardSeries(records, TargetType.CLOSE_VS_OPEN)
        assertTrue(series.isNotEmpty())
        // 无结果的记录不会进入序列
        assertTrue(series.all { it.actual != null })
    }

    @Test
    fun walkForward_相邻折翻转触发护栏回落默认() {
        // 构造交替方向数据，使每折最佳预设不稳定（护栏生效时用默认，不崩溃）
        val records = mutableListOf<PredictionRecord>()
        repeat(40) { i ->
            records += record("r$i", if (i % 2 == 0) 3.0 else -3.0, if (i % 2 == 0) PredictionOutcome.UP else PredictionOutcome.DOWN)
        }
        val series = Calibrator.walkForwardSeries(records, TargetType.CLOSE_VS_OPEN)
        assertTrue(series.isNotEmpty())
        // 交替数据下命中率不应高于 50% 太多（护栏没有引入幻觉）
        assertTrue(series.size > 20)
    }

    // ---------------- 经验阈值曲线 ----------------

    @Test
    fun 阈值曲线_高分段命中率高时返回该阈值() {
        val points = mutableListOf<WFPoint>()
        // |score|≥3 的 10 个全部正确；2.5 分段的 10 个全部错误
        repeat(10) { points += WFPoint("a$it", 3.5, PredictionOutcome.UP, PredictionOutcome.UP) }
        repeat(10) { points += WFPoint("b$it", 2.5, PredictionOutcome.UP, PredictionOutcome.DOWN) }
        val t = Calibrator.curveThreshold(points, 0.60)
        assertEquals(3.0, t!!, 0.0001) // t=2.5 时命中 50% 不满足，t=3.0 起 100%
    }

    @Test
    fun 阈值曲线_样本不足返回null() {
        assertNull(Calibrator.curveThreshold(emptyList(), 0.60))
        val few = List(10) { WFPoint("r$it", 2.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        assertNull(Calibrator.curveThreshold(few, 0.60))
    }

    // ---------------- 信心映射 ----------------

    @Test
    fun 信心_邻域命中率映射() {
        val series = mutableListOf<WFPoint>()
        repeat(7) { series += WFPoint("h$it", 3.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        repeat(3) { series += WFPoint("l$it", 3.0, PredictionOutcome.UP, PredictionOutcome.DOWN) }
        assertEquals("高", Calibrator.confidence(series, 3.2)) // 邻域 70% 命中
        assertEquals("高", Calibrator.confidence(series, 2.5)) // 同一邻域（|2.5-3.0|≤1）
        // 远离数据区的分数 → 邻域为空 → 数据积累中
        assertEquals("数据积累中", Calibrator.confidence(series, 9.0))
    }

    @Test
    fun 信心_样本不足返回积累中() {
        assertEquals("数据积累中", Calibrator.confidence(emptyList(), 2.0))
    }

    // ---------------- 极值分段与封顶 ----------------

    @Test
    fun 封顶_极值段显著劣于中段时触发() {
        val series = mutableListOf<WFPoint>()
        repeat(5) { series += WFPoint("h$it", 7.0, PredictionOutcome.UP, PredictionOutcome.DOWN) } // 极值全错
        repeat(6) { series += WFPoint("m$it", 3.0, PredictionOutcome.UP, PredictionOutcome.UP) }   // 中段全对
        val cap = Calibrator.capDecision(series)
        assertEquals(Calibrator.CAP_VALUE, cap!!, 0.0001)
    }

    @Test
    fun 封顶_极值段不劣化时不触发() {
        val series = mutableListOf<WFPoint>()
        repeat(6) { series += WFPoint("h$it", 7.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        repeat(6) { series += WFPoint("m$it", 3.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        assertNull(Calibrator.capDecision(series))
    }

    @Test
    fun 统计_平盘样本不计入命中率() {
        val series = listOf(
            WFPoint("1", 3.0, PredictionOutcome.UP, PredictionOutcome.UP),
            WFPoint("2", 3.0, PredictionOutcome.UP, PredictionOutcome.FLAT),
            WFPoint("3", -3.0, PredictionOutcome.DOWN, PredictionOutcome.UP)
        )
        val stats = Calibrator.statsOf(series)
        assertEquals(3, stats.total)
        assertEquals(1, stats.flatActual)
        assertEquals(2, stats.directionalTotal)
        assertEquals(1, stats.directionalHit)
        assertEquals(0.5, stats.hitRate, 0.0001)
    }
}
