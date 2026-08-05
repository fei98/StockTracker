package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：合成强度、板块广度、末端移动、滚动 z、标准化回退除数、评分、分类、封顶、建议、结果判定 */
class AuctionPredictorTest {

    private fun std(
        f: PredictionFactors,
        strengthHistory: List<Double> = emptyList(),
        endMoveHistory: List<Double> = emptyList(),
        momentumHistory: List<Double> = emptyList(),
        extUpContrib: Double = 0.0,
        extDownContrib: Double = 0.0
    ) = AuctionPredictor.standardize(f, strengthHistory, endMoveHistory, momentumHistory, extUpContrib, extDownContrib)

    private fun factors(
        target: Double? = 1.0, index: Double? = null, sectorAvg: Double? = null,
        breadth: Double? = null, external: Double? = null, endMove: Double? = null, volZ: Double? = null,
        baselineDays: Int = 0, momentum5d: Double? = null, upStreak: Int? = null, trendDevPct: Double? = null,
        prevDayPct: Double? = null
    ) = PredictionFactors(target, index, sectorAvg, breadth, external, endMove, volZ, baselineDays,
        momentum5d, prevDayPct, upStreak, trendDevPct)

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
    fun 板块广度_加权化_按幅度计方向() {
        // 加权广度 = Σ(sign·min(|g|,3)) / Σ max(|g|,1)
        assertEquals(0.875, AuctionPredictor.sectorBreadth(listOf(1.0, 0.5, 2.0))!!, 0.0001) // (1+0.5+2)/(1+1+2)
        assertEquals(-0.75, AuctionPredictor.sectorBreadth(listOf(-1.0, -0.5))!!, 0.0001)    // (-1-0.5)/(1+1)
        assertEquals(0.0, AuctionPredictor.sectorBreadth(listOf(1.0, -1.0))!!, 0.0001)
        // 幅度封顶：涨 5% 的贡献按 3 封顶，涨 3% 全量 → (3+3)/(5+3) = 0.75
        assertEquals(0.75, AuctionPredictor.sectorBreadth(listOf(5.0, 3.0))!!, 0.0001)
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
        val sf = std(
            factors(target = 3.0, endMove = 0.7, volZ = null, baselineDays = 3),
            strengthHistory = emptyList(), endMoveHistory = emptyList(),
            momentumHistory = emptyList(), extUpContrib = 0.0, extDownContrib = 0.0
        )
        assertEquals(3.0, sf.strengthRaw!!, 0.0001)    // 合成强度原始值（默认权重）
        assertEquals(null, sf.strengthMean)            // 无历史 → 均值 null（评分时领域除数回退）
        assertEquals(0.7 / 0.7, sf.endMoveZ!!, 0.0001) // 末端移动 ÷ 0.7
        assertEquals(0.0, sf.volAmp, 0.0001)           // volZ null → 0
        assertTrue(sf.insufficient.isNotEmpty())
    }

    @Test
    fun 标准化_满10天用滚动z() {
        val hist = (1..10).map { it.toDouble() } // mean 5.5, std = sqrt(8.25) ≈ 2.87
        val sf = std(
            factors(target = 5.5, endMove = 0.0, volZ = 0.0, baselineDays = 10),
            strengthHistory = hist, endMoveHistory = hist,
            momentumHistory = emptyList(), extUpContrib = 0.0, extDownContrib = 0.0
        )
        assertEquals(5.5, sf.strengthRaw!!, 0.001) // 合成强度原始值 = 5.5
        assertEquals(5.5, sf.strengthMean!!, 0.001) // 滚动均值 = 5.5
        assertEquals(0.0, sf.volAmp, 0.0001)       // volZ=0 → tanh(0)=0
    }

    @Test
    fun 放量放大器_乘法放大强度() {
        val f1 = factors(target = 3.0, volZ = 0.0, baselineDays = 15)
        val f2 = factors(target = 3.0, volZ = 5.0, baselineDays = 15)
        val sf1 = std(f1)
        val sf2 = std(f2)
        val s1 = AuctionPredictor.score(sf1, CalibratedWeights.DEFAULT)
        val s2 = AuctionPredictor.score(sf2, CalibratedWeights.DEFAULT)
        assertTrue(s2 > s1)                       // 放量放大看多
        // 主项为 0 时放大无效（方向不明+天量 = 中性）
        val f3 = factors(target = 0.0, volZ = 5.0, baselineDays = 15)
        val sf3 = std(f3)
        assertEquals(0.0, AuctionPredictor.score(sf3, CalibratedWeights.DEFAULT), 0.0001)
    }

    @Test
    fun 末端因子_无基准快照时贡献为零() {
        val sf = std(factors(target = 3.0, endMove = null))
        assertNull(sf.endMoveZ)
        val s = AuctionPredictor.score(sf, CalibratedWeights.DEFAULT)
        val sWithEnd = AuctionPredictor.score(
            std(factors(target = 3.0, endMove = 1.0)),
            CalibratedWeights.DEFAULT
        )
        assertTrue(sWithEnd > s)
    }

    @Test
    fun 动量因子_回退除数与评分影响() {
        // 无历史 → 领域除数回退：5日动量 ÷3.0
        val sf = std(factors(target = 1.0, momentum5d = 3.0, baselineDays = 3))
        assertEquals(1.0, sf.momentumZ, 0.0001)
        assertEquals(0.0, sf.streakZ, 0.0001) // 无连阳数据 → 0
        // 正动量推高评分
        val sfPos = std(factors(target = 1.0, momentum5d = 6.0, baselineDays = 3))
        val sfNeg = std(factors(target = 1.0, momentum5d = -6.0, baselineDays = 3))
        val sPos = AuctionPredictor.score(sfPos, CalibratedWeights.DEFAULT)
        val sNeg = AuctionPredictor.score(sfNeg, CalibratedWeights.DEFAULT)
        assertTrue(sPos > sNeg)
    }

    @Test
    fun 连阳因子_比例缩放非zscore() {
        // 有界整数：streak ÷ 5.0 直接映射（不做 z-score，避免小标准差膨胀）
        val sf5 = std(factors(target = 0.0, upStreak = 5, baselineDays = 3))
        assertEquals(1.0, sf5.streakZ, 0.0001)
        val sfUp = std(factors(target = 0.0, upStreak = 10, baselineDays = 3))
        val sfDown = std(factors(target = 0.0, upStreak = -10, baselineDays = 3))
        assertTrue(AuctionPredictor.score(sfUp, CalibratedWeights.DEFAULT) > 0)
        assertTrue(AuctionPredictor.score(sfDown, CalibratedWeights.DEFAULT) < 0)
        // 连阳 5 天 → tanh(1) ≈ 1.52，不应出现异常膨胀
        assertTrue(AuctionPredictor.score(sf5, CalibratedWeights.DEFAULT) < 1.7)
    }

    @Test
    fun 昨日涨跌幅_动量延续调制() {
        // 动量 +5，昨日同向 +3% → 动量放大；昨日反向 -3% → 动量削弱
        val sfSame = std(factors(target = 1.0, momentum5d = 5.0, prevDayPct = 3.0, baselineDays = 3))
        val sfNeutral = std(factors(target = 1.0, momentum5d = 5.0, prevDayPct = 0.0, baselineDays = 3))
        val sfOpp = std(factors(target = 1.0, momentum5d = 5.0, prevDayPct = -3.0, baselineDays = 3))
        assertTrue(sfSame.momentumZ > sfNeutral.momentumZ)
        assertTrue(sfOpp.momentumZ < sfNeutral.momentumZ)
        assertTrue(AuctionPredictor.score(sfSame, CalibratedWeights.DEFAULT) > AuctionPredictor.score(sfOpp, CalibratedWeights.DEFAULT))
    }

    @Test
    fun 趋势情境_放大或削弱放量放大器() {
        // 趋势向上（20日线上方）→ 放量放大器被放大
        val sfUp = std(factors(target = 3.0, volZ = 5.0, trendDevPct = 6.0, baselineDays = 15))
        val sfFlat = std(factors(target = 3.0, volZ = 5.0, trendDevPct = 0.0, baselineDays = 15))
        val sfDown = std(factors(target = 3.0, volZ = 5.0, trendDevPct = -6.0, baselineDays = 15))
        assertTrue(sfUp.volAmp > sfFlat.volAmp)
        assertTrue(sfDown.volAmp < sfFlat.volAmp)
        assertTrue(AuctionPredictor.score(sfUp, CalibratedWeights.DEFAULT) > AuctionPredictor.score(sfFlat, CalibratedWeights.DEFAULT))
    }

    @Test
    fun 动量缺失_标注数据积累中() {
        val sf = std(factors(target = 1.0, baselineDays = 15))
        assertTrue(sf.insufficient.any { it.contains("动量") })
    }

    @Test
    fun 交互项_强度与动量同向共振加分_反向扣分() {
        // 强度与动量同向（都正）→ 交互加分；反向 → 扣分
        val sfSame = std(factors(target = 3.0, momentum5d = 3.0, baselineDays = 15))
        val sfOpp = std(factors(target = 3.0, momentum5d = -3.0, baselineDays = 15))
        val sfNone = std(factors(target = 3.0, momentum5d = 0.0, baselineDays = 15))
        val sSame = AuctionPredictor.score(sfSame, CalibratedWeights.DEFAULT)
        val sOpp = AuctionPredictor.score(sfOpp, CalibratedWeights.DEFAULT)
        val sNone = AuctionPredictor.score(sfNone, CalibratedWeights.DEFAULT)
        assertTrue(sSame > sNone) // 同向共振加分
        assertTrue(sOpp < sNone)  // 反向扣分
        // 交互项放大同向/反向差距（无交互时差距仅来自动量本身）
        val w0 = CalibratedWeights.DEFAULT.copy(interactionScale = 0.0)
        val diffWithInter = sSame - sOpp
        val diffNoInter = AuctionPredictor.score(sfSame, w0) - AuctionPredictor.score(sfOpp, w0)
        assertTrue(diffWithInter > diffNoInter)
    }

    @Test
    fun 合成强度权重_可参数化() {
        // 自定义合成权重：目标权重拉高 → combined 向目标涨幅倾斜
        val f = factors(target = 2.0, index = 4.0, sectorAvg = 6.0)
        val wDefault = CalibratedWeights.DEFAULT
        val wTargetHeavy = wDefault.copy(targetW = 0.8, indexW = 0.1)
        // 默认: 0.5·2+0.25·4+0.25·6 = 3.5；目标重: 0.8·2+0.1·4+0.1·6 = 2.6
        assertEquals(3.5, AuctionPredictor.combinedStrength(f, wDefault)!!, 0.0001)
        assertEquals(2.6, AuctionPredictor.combinedStrength(f, wTargetHeavy)!!, 0.0001)
        // sectorW 自动推导
        assertEquals(0.1, wTargetHeavy.sectorW, 0.0001)
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
