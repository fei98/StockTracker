package com.example.stocktracker

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** 假行情接口（不联网，按股票返回） */
class FakeMarketApi(
    val snapshots: MutableMap<String, MarketSnapshot> = mutableMapOf()
) : MarketDataApi {
    var target: QuoteFields?
        get() = snapshots["__target"]?.target
        set(v) { snapshots["__target"] = snapshots["__target"]?.copy(target = v) ?: MarketSnapshot(v, null, emptyList(), emptyList()) }

    override suspend fun fetchSnapshot(stock: Stock): MarketSnapshot =
        snapshots[stock.marketCode] ?: MarketSnapshot(null, null, emptyList(), emptyList())

    override suspend fun fetchTargetQuote(stock: Stock): QuoteFields? =
        fetchSnapshot(stock).target
}

/** 假存储（内存实现，按股票隔离） */
class FakeSnapshotStore : SnapshotStore {
    private val snapshots = mutableMapOf<String, MutableList<DailySnapshot>>()
    private val stages = mutableMapOf<String, AuctionStageSnapshot>()
    private val records = mutableMapOf<String, MutableList<PredictionRecord>>()
    private val last = mutableMapOf<String, PredictionResult>()
    private val universe = mutableListOf<UniverseDailySnapshot>()
    private val userSector = mutableMapOf<String, String>()

    override fun loadSnapshots(stock: String) = snapshots[stock]?.toList().orEmpty()
    override fun addSnapshot(stock: String, s: DailySnapshot) {
        snapshots.getOrPut(stock) { mutableListOf() } += s
    }
    override fun updateClose(stock: String, date: String, close: Double, dayAmount: Double) {
        val list = snapshots[stock] ?: return
        val i = list.indexOfFirst { it.date == date }
        if (i >= 0) list[i] = list[i].copy(close = close, dayAmount = dayAmount)
    }
    override fun loadStage(stock: String, date: String, stage: String) = stages["$stock:$date:$stage"]
    override fun saveStage(stock: String, s: AuctionStageSnapshot) { stages["$stock:${s.date}:${s.stage}"] = s }
    override fun loadRecords(stock: String) = records[stock]?.toList().orEmpty()
    override fun addRecord(stock: String, r: PredictionRecord) {
        records.getOrPut(stock) { mutableListOf() } += r
    }
    override fun updateOutcomes(stock: String, date: String, o30: PredictionOutcome?, ocvo: PredictionOutcome?, odvp: PredictionOutcome?) {
        val list = records[stock] ?: return
        val i = list.indexOfFirst { it.date == date }
        if (i >= 0) list[i] = list[i].copy(
            outcome30m = o30 ?: list[i].outcome30m,
            outcomeCloseVsOpen = ocvo ?: list[i].outcomeCloseVsOpen,
            outcomeDayVsPrev = odvp ?: list[i].outcomeDayVsPrev
        )
    }
    override fun saveLastResult(stock: String, r: PredictionResult) { last[stock] = r }
    override fun loadLastResult(stock: String) = last[stock]
    override fun loadUniverseSnapshots() = universe.toList()
    override fun addUniverseSnapshot(s: UniverseDailySnapshot) { universe += s }
    override fun getUserSector(stock: String) = userSector[stock]
    override fun setUserSector(stock: String, industry: String?) {
        if (industry == null) userSector.remove(stock) else userSector[stock] = industry
    }
}

/** 覆盖：引擎编排（观察区拦截、因子/记录/快照落库、结果回填） */
class PredictionEngineTest {

    private val date = "2026-08-05"

    /** 本地时区的某时刻（测试用） */
    private fun timeAt(hour: Int, minute: Int): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val morning = timeAt(10, 0)      // 10:00（非观察区）
    private val etf = Stock("159915", "创业板ETF华夏", "sz")
    private val starNet = Stock("002396", "星网锐捷", "sz")

    private fun quote(price: Double = 3.542, pct: Double = 5.0, amount: Double = 30000.0) = QuoteFields(
        name = "创业板ETF华夏", code = "159915",
        price = price, prevClose = 3.514, open = price, pct = pct, amountWan = amount, time = "20260805092500"
    )

    /** 构造 ≥10 天低竞价量基线，让 volZ 显著为正 */
    private fun storeWithBaseline(store: FakeSnapshotStore, stock: String = etf.marketCode) {
        (1..10).forEach { i ->
            store.addSnapshot(stock, DailySnapshot("2026-07-$i", 3.0, 2.9, 1000.0, 0.0, 0.0))
        }
    }

    private fun snapOf(snap: MarketSnapshot, stock: Stock = etf) =
        FakeMarketApi(mutableMapOf(stock.marketCode to snap))

    @org.junit.Test
    fun 观察期_返回null() = runTest {
        val engine = PredictionEngine(snapOf(MarketSnapshot(quote(), null, emptyList(), emptyList())), FakeSnapshotStore())
        assertNull(engine.runPrediction(etf, false, date, timeAt(9, 0)))
    }

    @org.junit.Test
    fun 行情缺失_返回null() = runTest {
        val engine = PredictionEngine(snapOf(MarketSnapshot(null, null, emptyList(), emptyList())), FakeSnapshotStore())
        assertNull(engine.runPrediction(etf, false, date, morning))
    }

    @org.junit.Test
    fun 非A股_返回null() = runTest {
        val hk = Stock("00700", "腾讯控股", "hk")
        val engine = PredictionEngine(FakeMarketApi(), FakeSnapshotStore())
        assertNull(engine.runPrediction(hk, false, date, morning))
    }

    @org.junit.Test
    fun 正常预测_落库结果记录与快照() = runTest {
        val store = FakeSnapshotStore()
        storeWithBaseline(store)
        val api = snapOf(
            MarketSnapshot(
                target = quote(), indexPct = 1.0,
                sector = listOf(SectorStock("300750", "宁德时代", 2.0)),
                externalPct = listOf(1.0, 0.5)
            )
        )
        val engine = PredictionEngine(api, store)
        val result = engine.runPrediction(etf, true, date, morning)!!
        assertNotNull(result)
        assertEquals(PredictionOutcome.UP, result.conclusion)  // 高开+放量 → 看涨
        assertEquals("sz159915", result.stockCode)
        assertEquals(1, store.loadRecords(etf.marketCode).size)
        assertEquals(date, store.loadRecords(etf.marketCode)[0].date)
        assertEquals(date, store.loadSnapshots(etf.marketCode).last().date)
        assertEquals(3.542, store.loadSnapshots(etf.marketCode).last().open, 0.0001)
        assertTrue(result.suggestion.isNotEmpty())
    }

    @org.junit.Test
    fun 观察区快照_用于末端上移因子() = runTest {
        val store = FakeSnapshotStore()
        storeWithBaseline(store)
        store.saveStage(etf.marketCode, AuctionStageSnapshot(date, PredictionEngine.STAGE_BASE, 3.40, 8000.0))
        val api = snapOf(MarketSnapshot(quote(price = 3.542, amount = 30000.0), null, emptyList(), emptyList()))
        val engine = PredictionEngine(api, store)
        val result = engine.runPrediction(etf, false, date, morning)!!
        // 末端上移 (3.542-3.40)/3.40 = +4.18% → 有值
        assertEquals(4.176, result.factors.endMovePct!!, 0.01)
    }

    @org.junit.Test
    fun 结果回填_30分钟与收盘() = runTest {
        val store = FakeSnapshotStore()
        storeWithBaseline(store)
        val api = snapOf(MarketSnapshot(quote(), null, emptyList(), emptyList()))
        val engine = PredictionEngine(api, store)
        engine.runPrediction(etf, false, date, morning)

        api.snapshots[etf.marketCode] = MarketSnapshot(quote(price = 3.60), null, emptyList(), emptyList())
        engine.recordOutcome30m(etf, date)
        assertEquals(PredictionOutcome.UP, store.loadRecords(etf.marketCode)[0].outcome30m)

        api.snapshots[etf.marketCode] = MarketSnapshot(quote(price = 3.40), null, emptyList(), emptyList())
        engine.recordOutcomeClose(etf, date)
        val rec = store.loadRecords(etf.marketCode)[0]
        assertEquals(PredictionOutcome.DOWN, rec.outcomeCloseVsOpen)  // 3.40 vs open 3.542
        assertEquals(PredictionOutcome.DOWN, rec.outcomeDayVsPrev)    // 3.40 vs prevClose 3.514
        assertEquals(3.40, store.loadSnapshots(etf.marketCode).last().close, 0.0001)
    }

    @org.junit.Test
    fun 观察区信息_意图形态() = runTest {
        val store = FakeSnapshotStore()
        store.saveStage(etf.marketCode, AuctionStageSnapshot(date, PredictionEngine.STAGE_INTENT, 3.30, 5000.0))
        store.saveStage(etf.marketCode, AuctionStageSnapshot(date, PredictionEngine.STAGE_BASE, 3.40, 8000.0))
        val info = PredictionEngine(FakeMarketApi(), store).observationInfo(etf, date)
        assertEquals(3.030, info.intentMovePct!!, 0.01) // (3.40-3.30)/3.30
        assertEquals(0.6, info.intentVolSurge!!, 0.01)
        assertTrue(info.hasBase)
    }

    @org.junit.Test
    fun 前推回测统计_三目标分离() = runTest {
        val store = FakeSnapshotStore()
        storeWithBaseline(store)
        val engine = PredictionEngine(snapOf(MarketSnapshot(quote(), null, emptyList(), emptyList())), store)
        engine.runPrediction(etf, false, date, morning)
        val stats = engine.walkForwardStats(etf, date)
        assertEquals(3, stats.size) // 三个目标各自统计
        stats.forEach { (t, s) -> assertNotNull(s) }
    }

    @org.junit.Test
    fun 投票序列_缓存一致且记录变化后重算() = runTest {
        val store = FakeSnapshotStore()
        storeWithBaseline(store)
        val engine = PredictionEngine(snapOf(MarketSnapshot(quote(), null, emptyList(), emptyList())), store)
        // 积累 25 天记录并回填结果
        repeat(25) { d ->
            val day = "2026-06-${d + 1}"
            engine.runPrediction(etf, false, day, timeAt(10, 0))
            store.updateOutcomes(etf.marketCode, day, PredictionOutcome.UP, PredictionOutcome.UP, PredictionOutcome.UP)
        }
        val s1 = engine.votedSeries(etf, date)
        val s2 = engine.votedSeries(etf, date)   // 命中缓存，结果一致
        assertEquals(s1, s2)
        assertTrue(s1.isNotEmpty())
        // 记录变化 → 缓存失效重算（不抛错、规模一致）
        store.updateOutcomes(etf.marketCode, "2026-06-10", PredictionOutcome.DOWN, null, null)
        val s3 = engine.votedSeries(etf, date)
        assertEquals(s1.size, s3.size)
    }

    @org.junit.Test
    fun 动量因子_从收盘快照计算() = runTest {        val store = FakeSnapshotStore()
        storeWithBaseline(store)
        // 21 天收盘价：从 3.0 稳步上涨到 3.84（每天 +4%），最近 5 日从 3.2→3.84
        var price = 3.0
        for (d in 1..21) {
            store.updateClose(etf.marketCode, "close-d$d", 0.0, 0.0) // 无操作
            store.addSnapshot(etf.marketCode, DailySnapshot("2026-07-$d", price, price - 0.1, 1000.0, 5000.0, price))
            price *= 1.04
        }
        // 收盘价序列：close 为 3.0..3.84 递增 → 连涨 21 天
        val api = snapOf(MarketSnapshot(quote(), null, emptyList(), emptyList()))
        val engine = PredictionEngine(api, store)
        val result = engine.runPrediction(etf, false, date, morning)!!
        val f = result.factors
        assertNotNull(f.momentum5dPct)          // 5 日动量有值
        assertNotNull(f.upStreak)
        assertTrue(f.upStreak!! >= 20)           // 连涨
        assertNotNull(f.trendDevPct)             // 20 日线偏差有值
        assertTrue(f.trendDevPct!! > 0)          // 价格在 20 日线上方
        assertNotNull(f.prevDayPct)
    }

    @org.junit.Test
    fun 多股票_基线记录快照互相隔离() = runTest {
        val store = FakeSnapshotStore()
        storeWithBaseline(store, etf.marketCode)
        storeWithBaseline(store, starNet.marketCode)
        // 只给 ETF 加基准快照（002396 没有），002396 的记录不能被污染
        val api = FakeMarketApi(
            mutableMapOf(
                etf.marketCode to MarketSnapshot(quote(price = 3.542, amount = 30000.0), 1.0, listOf(SectorStock("300750", "宁德时代", 2.0)), listOf(1.0)),
                starNet.marketCode to MarketSnapshot(
                    QuoteFields("星网锐捷", "002396", 28.7, 28.2, 28.7, 5.0, 8000.0, "20260805092500"),
                    1.5, listOf(SectorStock("300308", "中际旭创", 3.0)), listOf(1.0)
                )
            )
        )
        val engine = PredictionEngine(api, store)
        val r1 = engine.runPrediction(etf, true, date, morning)!!
        val r2 = engine.runPrediction(starNet, false, date, morning)!!
        assertEquals("sz159915", r1.stockCode)
        assertEquals("sz002396", r2.stockCode)
        // 各自记录只有自己的
        assertEquals(1, store.loadRecords(etf.marketCode).size)
        assertEquals(1, store.loadRecords(starNet.marketCode).size)
        assertTrue(store.loadRecords(etf.marketCode).all { it.stockCode == "sz159915" })
        assertTrue(store.loadRecords(starNet.marketCode).all { it.stockCode == "sz002396" })
        // 002396 的放量基线独立：也够 10 天 → 结果不为 null
        assertNotNull(store.loadSnapshots(starNet.marketCode))
        assertEquals(11, store.loadSnapshots(starNet.marketCode).size) // 10 天基线 + 今日快照
    }
}
