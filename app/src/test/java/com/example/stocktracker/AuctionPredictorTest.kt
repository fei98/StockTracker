package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：合成强度、板块广度、末端移动、滚动 z、标准化回退除数、评分、分类、封顶、建议、结果判定 */
class AuctionPredictorTest {

    private fun factors(
        target: Double? = 1.0, index: Double? = null, sectorAvg: Double? = null,
        breadth: Double? = null, external: Double? = null, endMove: Double? = null, volZ: Double? = null,
        baselineDays: Int = 0
    ) = PredictionFactors(target, index, sectorAvg, breadth, external, endMove, volZ, baselineDays)

    @Test
    fun 合成强度_权重正确() {
        val f = factors(target = 1.0, index = 3.0, sectorAvg = 2.0)
        val c = AuctionPredictor.combinedStrength(f)!!
        assertEquals(0.5 * 1.0 + 0.25 * 3.0 + 0.25 * 2.0, c, 0.0001) // 1.75
    }

    @Test
    fun 合成强度_字段缺失时权重归一() {
        val f = factors(target = 2.0, index = null, sectorAvg = null)
        assertEquals(2.0, AuctionPredictor.combinedStrength(f)!!, 0.0001)
        val f2 = factors(target = 2.0, index = 4.0, sectorAvg = null)
        assertEquals(2.0 * 0.5 / 0.75 + 4.0 * 0.25 / 0.75, AuctionPredictor.combinedStrength(f2)!!, 0.0001)
    }

    @Test
    fun 合成强度_全部缺失返回null() {
        assertNull(AuctionPredictor.combinedStrength(factors(target = null)))
    }

    @Test
    fun 板块广度_涨跌家数比() {
        assertEquals(1.0, AuctionPredictor.sectorBreadth(listOf(1.0, 0.5, 2.0))!!, 0.0001)
        assertEquals(-1.0, AuctionPredictor.sectorBreadth(listOf(-1.0, -0.5))!!, 0.0001)
        assertEquals(0.0, AuctionPredictor.sectorBreadth(listOf(1.0, -1.0))!!, 0.0001)
        assertNull(AuctionPredictor.sectorBreadth(listOf(null, null)))
    }

    @Test
    fun 末端移动与量能扩张() {
        assertEquals(5.0, AuctionPredictor.endMovePct(10.5, 10.0)!!, 0.0001)
        assertEquals(1.0, AuctionPredictor.endVolSurge(20000.0, 10000.0)!!, 0.0001)
        assertNull(AuctionPredictor.endMovePct(10.0, 0.0))
    }

    @Test
    fun 滚动统计_不足10条返回null_满10条有值() {
        assertNull(AuctionPredictor.rollingStats(listOf(1.0, 2.0)))
        val (mean, std) = AuctionPredictor.rollingStats(List(10) { 5.0 })!!
        assertEquals(5.0, mean, 0.0001)
        assertEquals(1.0, std, 0.0001) // 零方差回退为 1.0
        val (mean2, _) = AuctionPredictor.rollingStats((0 until 10).map { it.toDouble() })!!
        assertEquals(4.5, mean2, 0.0001)
    }

    @Test
    fun 标准化_不足10天用领域除数回退() {
        val sf = AuctionPredictor.standardize(
            factors(target = 3.0, endMove = 0.7, volZ = null, baselineDays = 3),
            strengthHistory = emptyList(), endMoveHistory = emptyList(),
            extUpContrib = 0.0, extDownContrib = 0.0
        )
        assertEquals(3.0 / 1.0, sf.strengthZ, 0.0001)         // 合成强度 ÷ 1.0
        assertEquals(0.7 / 0.7, sf.endMoveZ!!, 0.0001)        // 末端移动 ÷ 0.7
        assertEquals(0.0, sf.volAmp, 0.0001)                  // volZ null → 0
        assertTrue(sf.insufficient.isNotEmpty())
    }

    @Test
    fun 标准化_满10天用滚动z() {
        val hist = (1..10).map { it.toDouble() } // mean 5.5, std = sqrt(8.25) ≈ 2.87
        val sf = AuctionPredictor.standardize(
            factors(target = 5.5, endMove = 0.0, volZ = 0.0, baselineDays = 10),
            strengthHistory = hist, endMoveHistory = hist,
            extUpContrib = 0.0, extDownContrib = 0.0
        )
        assertEquals(0.0, sf.strengthZ, 0.001)   // 5.5 = mean → z=0
        assertEquals(0.0, sf.volAmp, 0.0001)     // volZ=0 → tanh(0)=0
    }

    @Test
    fun 放量放大器_乘法放大强度() {
        val f1 = factors(target = 3.0, volZ = 0.0, baselineDays = 15)
        val f2 = factors(target = 3.0, volZ = 5.0, baselineDays = 15)
        val sf1 = AuctionPredictor.standardize(f1, emptyList(), emptyList(), 0.0, 0.0)
        val sf2 = AuctionPredictor.standardize(f2, emptyList(), emptyList(), 0.0, 0.0)
        val s1 = AuctionPredictor.score(sf1, CalibratedWeights.DEFAULT)
        val s2 = AuctionPredictor.score(sf2, CalibratedWeights.DEFAULT)
        assertTrue(s2 > s1)                       // 放量放大看多
        // 主项为 0 时放大无效（方向不明+天量 = 中性）
        val f3 = factors(target = 0.0, volZ = 5.0, baselineDays = 15)
        val sf3 = AuctionPredictor.standardize(f3, emptyList(), emptyList(), 0.0, 0.0)
        assertEquals(0.0, AuctionPredictor.score(sf3, CalibratedWeights.DEFAULT), 0.0001)
    }

    @Test
    fun 末端因子_无基准快照时贡献为零() {
        val sf = AuctionPredictor.standardize(factors(target = 3.0, endMove = null), emptyList(), emptyList(), 0.0, 0.0)
        assertNull(sf.endMoveZ)
        val s = AuctionPredictor.score(sf, CalibratedWeights.DEFAULT)
        val sWithEnd = AuctionPredictor.score(
            AuctionPredictor.standardize(factors(target = 3.0, endMove = 1.0), emptyList(), emptyList(), 0.0, 0.0),
            CalibratedWeights.DEFAULT
        )
        assertTrue(sWithEnd > s)
    }

    @Test
    fun 分类_阈值对称分档() {
        assertEquals(PredictionOutcome.UP, AuctionPredictor.classify(2.0, 2.0))
        assertEquals(PredictionOutcome.DOWN, AuctionPredictor.classify(-2.0, 2.0))
        assertEquals(PredictionOutcome.FLAT, AuctionPredictor.classify(1.9, 2.0))
        assertEquals(PredictionOutcome.FLAT, AuctionPredictor.classify(-1.9, 2.0))
    }

    @Test
    fun 封顶_范围限制() {
        assertEquals(5.0, AuctionPredictor.applyCap(8.0, 5.0), 0.0001)
        assertEquals(-5.0, AuctionPredictor.applyCap(-8.0, 5.0), 0.0001)
        assertEquals(3.0, AuctionPredictor.applyCap(3.0, 5.0), 0.0001)
        assertEquals(8.0, AuctionPredictor.applyCap(8.0, null), 0.0001)
    }

    @Test
    fun 建议_持仓与结论四象限() {
        assertTrue(AuctionPredictor.suggest(PredictionOutcome.UP, true, 3.0).contains("持有"))
        assertTrue(AuctionPredictor.suggest(PredictionOutcome.UP, false, 3.0).contains("买入"))
        assertTrue(AuctionPredictor.suggest(PredictionOutcome.DOWN, true, -3.0).contains("减仓"))
        assertTrue(AuctionPredictor.suggest(PredictionOutcome.DOWN, false, -3.0).contains("观望"))
        assertTrue(AuctionPredictor.suggest(PredictionOutcome.FLAT, true, 0.0).contains("震荡"))
    }

    @Test
    fun 结果判定_平盘带() {
        assertEquals(PredictionOutcome.UP, outcomeOf(10.3, 10.0))
        assertEquals(PredictionOutcome.DOWN, outcomeOf(9.8, 10.0))
        assertEquals(PredictionOutcome.FLAT, outcomeOf(10.01, 10.0))
    }
}
