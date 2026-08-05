package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：皮尔逊相关、按日期对齐、TopK 推荐、排除自身、关键词匹配、积累不足回落 */
class SectorLearnerTest {

    @Test
    fun 皮尔逊_完全正相关为1() {
        val a = (1..20).map { it.toDouble() }
        assertEquals(1.0, SectorLearner.pearson(a, a)!!, 0.0001)
    }

    @Test
    fun 皮尔逊_完全负相关为负1() {
        val a = (1..20).map { it.toDouble() }
        val b = a.reversed()
        assertEquals(-1.0, SectorLearner.pearson(a, b)!!, 0.0001)
    }

    @Test
    fun 皮尔逊_样本不足返回null() {
        assertNull(SectorLearner.pearson(listOf(1.0, 2.0), listOf(1.0, 2.0)))
    }

    @Test
    fun 皮尔逊_方差为零返回null() {
        val flat = List(12) { 1.0 }
        assertNull(SectorLearner.pearson(flat, flat))
    }

    /** 构造 N 天联动快照：目标股票与 candA 完全同向、与 candB 完全反向 */
    private fun snapshots(days: Int): List<UniverseDailySnapshot> {
        return (1..days).map { d ->
            val g = d * 0.5
            UniverseDailySnapshot(
                date = "d$d",
                gaps = listOf(
                    UniverseGap("sz159915", "创业板ETF华夏", "新能源", g),
                    UniverseGap("sz300750", "宁德时代", "新能源", g),
                    UniverseGap("sh600519", "贵州茅台", "白酒", -g)
                )
            )
        }
    }

    @Test
    fun 推荐_同向股票入选且排除自身() {
        val rec = SectorLearner.recommend(Stock("159915", "创业板ETF华夏", "sz"), snapshots(20))!!
        assertNotNull(rec)
        // 宁德时代同向 → 入选；自身被排除
        assertTrue(rec.stocks.any { it.code == "sz300750" })
        assertTrue(rec.stocks.none { it.code == "sz159915" })
        // 反向的茅台相关为 -1 < MIN_CORR → 不入选
        assertTrue(rec.stocks.none { it.code == "sh600519" })
        assertEquals(1.0, rec.avgCorr, 0.001)
        assertEquals(20, rec.days)
    }

    @Test
    fun 推荐_积累不足10天返回null() {
        assertNull(SectorLearner.recommend(Stock("159915", "创业板ETF华夏", "sz"), snapshots(5)))
    }

    @Test
    fun 推荐_全部反向时返回null() {
        // 只有反向标的 → 无 ≥MIN_CORR 推荐
        val snaps = (1..15).map { d ->
            UniverseDailySnapshot("d$d", listOf(UniverseGap("sz159915", "ETF", null, 1.0), UniverseGap("sh600519", "茅台", "白酒", -1.0)))
        }
        assertNull(SectorLearner.recommend(Stock("159915", "创业板ETF华夏", "sz"), snaps))
    }

    @Test
    fun 推荐_候选池去重_跨行业重复代码只算一次() {
        val snaps = (1..12).map { d ->
            UniverseDailySnapshot(
                "d$d",
                listOf(
                    UniverseGap("sz159915", "ETF", null, d * 0.5),
                    UniverseGap("sz300274", "阳光电源", "新能源", d * 0.5),
                    UniverseGap("sz300274", "阳光电源", "光伏", d * 0.5) // 同一代码两个行业标签
                )
            )
        }
        val rec = SectorLearner.recommend(Stock("159915", "ETF", "sz"), snaps)!!
        assertEquals(1, rec.stocks.size)
        assertEquals(1.0, rec.avgCorr, 0.001)
    }

    @Test
    fun 关键词_长词优先匹配() {
        assertEquals("券商", SectorLearner.nameKeywordMatch("招商证券"))
        assertEquals("银行", SectorLearner.nameKeywordMatch("招商银行"))
        assertEquals("白酒", SectorLearner.nameKeywordMatch("贵州茅台"))
        assertEquals("芯片", SectorLearner.nameKeywordMatch("中芯国际"))
        assertEquals("算力", SectorLearner.nameKeywordMatch("中兴通讯"))
        assertNull(SectorLearner.nameKeywordMatch("创业板ETF华夏"))
        assertNull(SectorLearner.nameKeywordMatch("星网锐捷"))
    }
}

/** 覆盖：决策链（手选 > 自动 > 关键词 > 内置 > 兜底） */
class SectorResolverTest {

    private fun storeWithUniverse(): FakeSnapshotStore {
        val store = FakeSnapshotStore()
        (1..12).forEach { d ->
            store.addUniverseSnapshot(UniverseDailySnapshot(
                "d$d",
                listOf(
                    UniverseGap("sz002396", "星网锐捷", "算力", d * 0.5),
                    UniverseGap("sz300308", "中际旭创", "算力", d * 0.5)
                )
            ))
        }
        return store
    }

    @Test
    fun 手选行业优先于一切() {
        val store = storeWithUniverse()
        store.setUserSector("sz002396", "白酒")
        val r = SectorResolver.resolve(Stock("002396", "星网锐捷", "sz"), store)
        assertEquals(SectorSource.USER, r.source)
        assertTrue(r.detail.contains("白酒"))
        assertEquals(5, r.config.sector.size) // 白酒名单 5 只
    }

    @Test
    fun 自动推荐_积累足够时优先于关键词和内置() {
        val store = storeWithUniverse()
        val r = SectorResolver.resolve(Stock("002396", "星网锐捷", "sz"), store)
        assertEquals(SectorSource.LEARNED, r.source)
        assertTrue(r.detail.contains("联动度"))
        assertTrue(r.config.sector.any { it.first == "sz300308" })
    }

    @Test
    fun 关键词_积累不足时即时默认() {
        val store = FakeSnapshotStore() // 无联动快照
        val r = SectorResolver.resolve(Stock("000063", "中兴通讯", "sz"), store)
        assertEquals(SectorSource.KEYWORD, r.source)
        assertTrue(r.config.sector.isNotEmpty())
    }

    @Test
    fun 内置名单_159915保持内置配置() {
        val r = SectorResolver.resolve(Stock("159915", "创业板ETF华夏", "sz"), FakeSnapshotStore())
        assertEquals(SectorSource.BUILTIN, r.source)
        assertEquals(10, r.config.sector.size)
    }

    @Test
    fun 兜底_无任何匹配() {
        // 无关键词、无内置配置的股票 → 大盘兜底
        val r = SectorResolver.resolve(Stock("603000", "某某集团", "sh"), FakeSnapshotStore())
        assertEquals(SectorSource.FALLBACK, r.source)
        assertEquals(3, r.config.sector.size) // 上证/深成/沪深300
    }
}
