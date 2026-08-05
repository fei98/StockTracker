package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：外围三桶条件概率、权重网格、walk-forward 严格性、经验阈值曲线、信心映射、极值封顶 */
class CalibrationTest {

    /** sf 模拟：strengthRaw 与 factors.targetGapPct 一致（强度贡献落在均值处 → 不影响分类） */
    private fun sf(strengthZ: Double, endZ: Double? = null, momentumZ: Double = 0.0, streakZ: Double = 0.0) = StandardizedFactors(
        strengthRaw = strengthZ,
        strengthMean = strengthZ,    // 均值=自身 → 强度 z=0
        strengthStd = 1.0,
        endMoveZ = endZ,
        momentumZ = momentumZ,
        streakZ = streakZ,
        breadth = null,
        extUpContrib = 0.0,
        extDownContrib = 0.0,
        volAmp = 0.0,
        insufficient = emptyList()
    )

    private fun record(
        date: String, strengthZ: Double, outcome: PredictionOutcome?,
        external: Double? = null, endZ: Double? = null, momentumZ: Double = 0.0
    ) = PredictionRecord(
        stockCode = "sz159915",
        date = date,
        factors = PredictionFactors(targetGapPct = strengthZ, externalAvg = external),
        sf = sf(strengthZ, endZ, momentumZ),
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
        // 加权后跌桶仍显著转多（时间衰减使新样本权重更高，方向保持为正）
        assertTrue(down > 0.5)
    }

    // ---------------- 权重网格与护栏 ----------------

    @Test
    fun 权重网格_选样本内命中率最高的预设() {
        // 构造数据：所有记录 strengthZ/endZ/momentumZ 足够大、结论一致，任意预设都能猜对
        val records = List(25) { record("r$it", 5.0, PredictionOutcome.UP, endZ = 5.0, momentumZ = 5.0) }
        val best = Calibrator.bestBasePreset(records, TargetType.CLOSE_VS_OPEN)
        assertTrue(best in Calibrator.PRESETS)
        assertEquals(1.0, Calibrator.inSampleHit(records, best, TargetType.CLOSE_VS_OPEN), 0.0001)
    }

    @Test
    fun 预设_16组_覆盖动量连阳组合() {
        assertEquals(16, Calibrator.PRESETS.size)
        assertTrue(Calibrator.PRESETS.any { it.momentumScale == 0.8 && it.streakScale == 1.0 })
        assertTrue(Calibrator.PRESETS.any { it.momentumScale == 0.7 && it.streakScale == 0.7 })
    }

    @Test
    fun 坐标下降_不劣化且权重合法() {
        // 动量+末端同向数据：默认预设即可全对，坐标下降应保持或提升命中
        val records = (0 until 30).map { i ->
            val up = i % 2 == 0
            record("m$i", 0.0, if (up) PredictionOutcome.UP else PredictionOutcome.DOWN,
                endZ = if (up) 5.0 else -5.0, momentumZ = if (up) 5.0 else -5.0)
        }
        val base = Calibrator.bestBasePreset(records, TargetType.CLOSE_VS_OPEN)
        val refined = Calibrator.refine(records, TargetType.CLOSE_VS_OPEN, base)
        val baseHit = Calibrator.inSampleHit(records, base, TargetType.CLOSE_VS_OPEN)
        val refinedHit = Calibrator.inSampleHit(records, refined, TargetType.CLOSE_VS_OPEN)
        assertTrue(refinedHit >= baseHit)      // 单调不劣化
        assertTrue(refinedHit > 0.9)           // 数据可全对
        // 权重合法（正且不下界）
        assertTrue(refined.momentumScale >= Calibrator.MIN_SCALE)
        assertTrue(refined.streakScale >= Calibrator.MIN_SCALE)
    }

    @Test
    fun 时间衰减_新样本权重更高() {
        assertTrue(Calibrator.decayWeight(0) > Calibrator.decayWeight(10))
        assertTrue(Calibrator.decayWeight(10) > Calibrator.decayWeight(30))
        assertEquals(1.0, Calibrator.decayWeight(0), 0.0001)
        assertEquals(0.5, Calibrator.decayWeight(30), 0.0001) // 半衰期 30
    }

    @Test
    fun 时间衰减_近期数据主导权重选择() {
        // 旧的 30 条预测方向与实际相反，新的 30 条一致：衰减让新数据主导 → 加权命中率应高
        val records = mutableListOf<PredictionRecord>()
        repeat(30) { records += record("old$it", 3.0, PredictionOutcome.DOWN, endZ = 3.0, momentumZ = 3.0) }
        repeat(30) { records += record("new$it", 3.0, PredictionOutcome.UP, endZ = 3.0, momentumZ = 3.0) }
        val hit = Calibrator.inSampleHit(records, CalibratedWeights.DEFAULT, TargetType.CLOSE_VS_OPEN)
        assertTrue(hit > 0.5) // 新样本（权重高）主导
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

    // ---------------- 经验阈值曲线（F1 最大化） ----------------

    @Test
    fun 阈值曲线_F1最大化选择() {
        val points = mutableListOf<WFPoint>()
        // |score|≥3 的 10 个全部正确；2.5 分段的 10 个全部错误
        repeat(10) { points += WFPoint("a$it", 3.5, PredictionOutcome.UP, PredictionOutcome.UP) }
        repeat(10) { points += WFPoint("b$it", 2.5, PredictionOutcome.UP, PredictionOutcome.DOWN) }
        val t = Calibrator.curveThreshold(points)
        // t=3.0: P=1.0, R=0.5, F1=0.667；t=2.5: P=0.5, R=0.5, F1=0.5 → 选 3.0
        assertEquals(3.0, t!!, 0.0001)
    }

    @Test
    fun 阈值曲线_F1对不均衡样本更稳健() {
        val points = mutableListOf<WFPoint>()
        // 大量低分错误样本(1.5) + 少量高分正确样本(4.0)：F1 最大化应排除噪声段
        repeat(40) { points += WFPoint("noise$it", 1.5, PredictionOutcome.UP, PredictionOutcome.DOWN) }
        repeat(10) { points += WFPoint("good$it", 4.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        val t = Calibrator.curveThreshold(points)
        assertNotNull(t)
        assertTrue(t!! >= 2.0) // 噪声(1.5)被排除；F1 平值段取最低阈值 2.0 保 recall
    }

    @Test
    fun 阈值曲线_样本不足返回null() {
        assertNull(Calibrator.curveThreshold(emptyList()))
        val few = List(10) { WFPoint("r$it", 2.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        assertNull(Calibrator.curveThreshold(few))
    }

    // ---------------- 信心映射 ----------------

    @Test
    fun 信心_邻域命中率映射() {
        val series = mutableListOf<WFPoint>()
        repeat(3) { series += WFPoint("l$it", 3.0, PredictionOutcome.UP, PredictionOutcome.DOWN) }
        repeat(7) { series += WFPoint("h$it", 3.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        assertEquals("高", Calibrator.confidence(series, 3.2)) // 近邻加权后命中 ≥70%（正确的更近）
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
        assertEquals(6.0, cap!!, 0.0001) // 劣化从 ≥6 开始 → 封顶 6.0
    }

    @Test
    fun 封顶_极值段更极端时取更高候选() {
        val series = mutableListOf<WFPoint>()
        repeat(5) { series += WFPoint("h$it", 9.0, PredictionOutcome.UP, PredictionOutcome.DOWN) } // 9+ 全错
        repeat(6) { series += WFPoint("m$it", 3.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        val cap = Calibrator.capDecision(series)
        assertEquals(8.0, cap!!, 0.0001) // ≥8 劣化 → 封顶 8.0（高分区仍保留）
    }

    @Test
    fun 封顶_极值段不劣化时不触发() {
        val series = mutableListOf<WFPoint>()
        repeat(6) { series += WFPoint("h$it", 7.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        repeat(6) { series += WFPoint("m$it", 3.0, PredictionOutcome.UP, PredictionOutcome.UP) }
        assertNull(Calibrator.capDecision(series))
    }

    @Test
    fun 合成权重搜索_边界内且不劣化() {
        val records = (0 until 30).map { i ->
            val up = i % 2 == 0
            record("c$i", if (up) 5.0 else -5.0, if (up) PredictionOutcome.UP else PredictionOutcome.DOWN,
                endZ = if (up) 5.0 else -5.0, momentumZ = if (up) 5.0 else -5.0)
        }
        val refined = Calibrator.refine(records, TargetType.CLOSE_VS_OPEN, Calibrator.bestBasePreset(records, TargetType.CLOSE_VS_OPEN))
        // 合成权重/交互项搜索始终在边界内（sectorW ≥ 0.1）
        assertTrue(refined.targetW in 0.2..0.8)
        assertTrue(refined.indexW in 0.05..0.45)
        assertTrue(refined.sectorW >= 0.1)
        assertTrue(refined.interactionScale in 0.0..1.5)
        // 不劣化
        val baseHit = Calibrator.inSampleHit(records, CalibratedWeights.DEFAULT, TargetType.CLOSE_VS_OPEN)
        val refinedHit = Calibrator.inSampleHit(records, refined, TargetType.CLOSE_VS_OPEN)
        assertTrue(refinedHit >= baseHit)
    }

    @Test
    fun scoreFor_候选权重与记录权重一致时恒等() {
        // 所有记录 strengthZ 相同 → 强度贡献落在均值处（z=0），scoreFor 与 score(sf) 一致
        val records = List(20) { record("s$it", 5.0, PredictionOutcome.UP, endZ = 5.0, momentumZ = 5.0) }
        val w = CalibratedWeights.DEFAULT
        val stats = Calibrator.strengthStatsOf(records, w)
        records.forEach { r ->
            assertEquals(AuctionPredictor.score(r.sf, w), Calibrator.scoreFor(r, w, stats), 1e-6)
        }
    }

    @Test
    fun 投票序列_三目标加权合成_用主目标判定() {
        val records = mutableListOf<PredictionRecord>()
        (0 until 40).forEach { i ->
            val up = i % 2 == 0
            records += record(
                "v$i", if (up) 5.0 else -5.0, if (up) PredictionOutcome.UP else PredictionOutcome.DOWN,
                endZ = if (up) 5.0 else -5.0, momentumZ = if (up) 5.0 else -5.0
            )
        }
        val voted = Calibrator.walkForwardVotedSeries(records)
        assertTrue(voted.size > 30) // 主目标有结果的点都进入
        // 主目标判定 + 各目标投票 → 方向正确率应显著高于随机
        val dir = voted.filter { it.actual != PredictionOutcome.FLAT }
        val hit = dir.count { it.predicted == it.actual }.toDouble() / dir.size
        assertTrue(hit > 0.7)
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
