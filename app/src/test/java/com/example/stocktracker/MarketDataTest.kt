package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：腾讯行情字段解析（真实接口布局，已实测验证） */
class MarketDataTest {

    /** 实测格式：0..37 位关键字段 */
    private fun raw(price: String = "3.542", prevClose: String = "3.514", open: String = "3.400",
                    pct: String = "0.80", amount: String = "715294", time: String = "20260805120536"): String {
        val fields = MutableList(40) { "" }
        fields[1] = "创业板ETF华夏"; fields[2] = "159915"
        fields[3] = price; fields[4] = prevClose; fields[5] = open
        fields[30] = time
        fields[31] = "0.028"; fields[32] = pct
        fields[33] = "3.549"; fields[34] = "3.400"
        fields[36] = "20547186"; fields[37] = amount
        return "v_sz159915=\"" + fields.joinToString("~") + "\";"
    }

    @Test
    fun 解析_正常行情() {
        val qf = parseQuoteFields(raw())!!
        assertEquals("创业板ETF华夏", qf.name)
        assertEquals("159915", qf.code)
        assertEquals(3.542, qf.price!!, 0.0001)
        assertEquals(3.514, qf.prevClose!!, 0.0001)
        assertEquals(3.400, qf.open!!, 0.0001)
        assertEquals(0.80, qf.pct!!, 0.0001)
        assertEquals(715294.0, qf.amountWan!!, 0.0001)
        assertEquals("20260805120536", qf.time)
    }

    @Test
    fun 解析_字段缺失返回null() {
        assertNull(parseQuoteFields("v_sz159915="))
        assertNull(parseQuoteFields("v_sz159915=\"1~a~2~3\";")) // 不足 38 字段
    }

    @Test
    fun 解析_美股指数同样格式() {
        val fields = MutableList(40) { "" }
        fields[1] = "纳斯达克"; fields[2] = "IXIC"
        fields[3] = "26584.99"; fields[4] = "25914.35"
        fields[32] = "2.59"
        val qf = parseQuoteFields("v_usIXIC=\"" + fields.joinToString("~") + "\";")!!
        assertEquals(2.59, qf.pct!!, 0.0001)
        assertEquals("IXIC", qf.code)
    }
}

/** 假 StockApi：按代码返回预设行情文本 */
class FakeStockApi(private val responses: Map<String, String>) : StockApi() {
    override suspend fun fetchRaw(query: String): String? = responses[query]
}

/** 覆盖：板块联动名单按股票配置、未配置股票走兜底 */
class SectorMapTest {

    private fun rawOf(code: String, name: String, pct: String): String {
        val fields = MutableList(40) { "" }
        fields[1] = name; fields[2] = code
        fields[3] = "10.0"; fields[32] = pct
        return "v_$code=\"" + fields.joinToString("~") + "\";"
    }

    private fun apiWith(vararg entries: Pair<String, String>): TencentMarketDataApi {
        val map = entries.associate { it }
        // 补上外部与目标所需的最小集合
        val full = map.toMutableMap()
        full.putIfAbsent("usDJI", rawOf("DJI", "道琼斯", "0.5"))
        full.putIfAbsent("usIXIC", rawOf("IXIC", "纳斯达克", "0.8"))
        full.putIfAbsent("usINX", rawOf("INX", "标普500", "0.3"))
        return TencentMarketDataApi(FakeStockApi(full))
    }

    @org.junit.Test
    fun 板块联动_ETF用创业板权重股名单() = kotlinx.coroutines.test.runTest {
        val api = apiWith(
            "sz159915" to rawOf("159915", "创业板ETF华夏", "1.0"),
            "sz399006" to rawOf("399006", "创业板指", "1.2"),
            "sz300750" to rawOf("300750", "宁德时代", "2.0"),
            "sz300059" to rawOf("300059", "东方财富", "1.0")
        )
        val snap = api.fetchSnapshot(Stock("159915", "创业板ETF华夏", "sz"))
        assertEquals(1.0, snap.target!!.pct!!, 0.0001)
        assertEquals(1.2, snap.indexPct!!, 0.0001)
        // 只返回了名单里实际有数据的
        assertEquals(2, snap.sector.size)
        assertTrue(snap.sector.any { it.code == "300750" && it.name == "宁德时代" })
        assertEquals(3, snap.externalPct.size)
    }

    @org.junit.Test
    fun 板块联动_个股用通信算力链名单() = kotlinx.coroutines.test.runTest {
        val api = apiWith(
            "sz002396" to rawOf("002396", "星网锐捷", "3.0"),
            "sz399001" to rawOf("399001", "深证成指", "1.0"),
            "sz300308" to rawOf("300308", "中际旭创", "2.0"),
            "sz000063" to rawOf("000063", "中兴通讯", "0.5")
        )
        val snap = api.fetchSnapshot(Stock("002396", "星网锐捷", "sz"))
        assertEquals(3.0, snap.target!!.pct!!, 0.0001)
        assertEquals(1.0, snap.indexPct!!, 0.0001)  // 深证成指
        assertEquals(2, snap.sector.size)
        assertTrue(snap.sector.any { it.code == "300308" })
    }

    @org.junit.Test
    fun 板块联动_未配置股票走大盘兜底() = kotlinx.coroutines.test.runTest {
        val api = apiWith(
            "sh603000" to rawOf("603000", "某某集团", "1.0"),
            "sh000001" to rawOf("000001", "上证指数", "0.5")
        )
        val snap = api.fetchSnapshot(Stock("603000", "某某集团", "sh"))
        assertNull(snap.indexPct)                                    // 兜底指数 sz399001 无数据 → null
        assertTrue(snap.sector.any { it.code == "000001" && it.name == "上证指数" })  // 兜底名单生效
        assertEquals(0, snap.sector.count { it.code == "300750" })  // 不会用 ETF 的名单
    }

    @org.junit.Test
    fun 候选池_20行业_总条目100只_代码基本唯一() {
        assertEquals(20, TencentMarketDataApi.INDUSTRY_SECTOR_LISTS.size)
        val entries = TencentMarketDataApi.INDUSTRY_SECTOR_LISTS.values.flatten()
        assertEquals(100, entries.size)                          // 20 × 5
        assertTrue(entries.map { it.first }.distinct().size >= 98) // 个别双行业标签（如阳光电源）
        assertEquals(entries.map { it.first }.distinct().size, TencentMarketDataApi.UNIVERSE_CODES.size)
        assertEquals(TencentMarketDataApi.UNIVERSE_CODES.size, TencentMarketDataApi.UNIVERSE_INDUSTRY.size)
    }

    @org.junit.Test
    fun 候选池_批量抓取解析一条响应() = kotlinx.coroutines.test.runTest {
        val lines = buildString {
            append(rawOf("600519", "贵州茅台", "1.0", "sh600519"))
            append(rawOf("300750", "宁德时代", "2.0", "sz300750"))
        }
        // 对任意查询都返回同一多行响应
        val api = TencentMarketDataApi(object : StockApi() {
            override suspend fun fetchRaw(query: String): String? = lines
        })
        val gaps = api.fetchUniverse(listOf())
        assertTrue(gaps.any { it.code == "sh600519" && it.name == "贵州茅台" && it.industry == "白酒" })
        assertTrue(gaps.any { it.code == "sz300750" && it.name == "宁德时代" && it.industry == "新能源" })
        assertEquals(2, gaps.size)
    }

    @org.junit.Test
    fun 分时数据_解析与均价推导() {
        val json = """{"code":0,"msg":"","data":{"sz159915":{"data":{"data":[
            "0930 3.497 100 500.00",
            "0931 3.510 300 1500.00",
            "1130 3.520 400 2050.00",
            "1300 3.530 600 3150.00"
        ]}}}}"""
        val pts = parseMinuteData(json)!!
        assertEquals(4, pts.size)
        assertEquals(0, pts[0].minute)          // 9:30 → 0
        assertEquals(1, pts[1].minute)          // 9:31 → 1
        assertEquals(120, pts[2].minute)        // 11:30 → 120
        assertEquals(210, pts[3].minute)        // 13:00 → 210（跳过午休）
        assertEquals(3.497, pts[0].price, 0.0001)
        // 均价 = 累计额 /（累计量手 × 100）
        assertEquals(500.0 / (100 * 100.0), pts[0].avgPrice, 0.0001)
        assertEquals(1500.0 / (300 * 100.0), pts[1].avgPrice, 0.0001)
    }

    @org.junit.Test
    fun 分时数据_异常返回null或空() {
        assertNull(parseMinuteData("not json"))
        assertNull(parseMinuteData("""{"code":1,"data":{}}"""))
        assertNull(parseMinuteData("""{"code":0,"data":{"sz159915":{"data":{"data":[]}}}}"""))
    }

    @org.junit.Test
    fun 分钟索引_非法输入返回null() {
        assertNull(parseMinuteIndex(""))
        assertNull(parseMinuteIndex("093"))
        assertNull(parseMinuteIndex("0830"))
        assertNull(parseMinuteIndex("1600"))
        assertEquals(0, parseMinuteIndex("0930")!!)
    }

    @org.junit.Test
    fun 分钟索引_时间戳转换() {
        fun at(h: Int, m: Int): Long = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        assertEquals(0, minuteIndexOf(at(9, 30)))
        assertEquals(30, minuteIndexOf(at(10, 0)))
        assertEquals(210, minuteIndexOf(at(13, 0)))
        assertEquals(239, minuteIndexOf(at(15, 0)))      // 收盘收敛
        assertEquals(0, minuteIndexOf(at(8, 0)))         // 开盘前收敛
    }

    private fun rawOf(code: String, name: String, pct: String, key: String): String {
        val fields = MutableList(40) { "" }
        fields[1] = name; fields[2] = code
        fields[3] = "10.0"; fields[32] = pct
        return "v_$key=\"" + fields.joinToString("~") + "\";"
    }
}
