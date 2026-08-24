package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：腾讯行情字段解析 / 时间戳日期判定 / 分时解析 / 指数基准映射 */
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

    @Test
    fun 行情时间戳_日期判定容忍分隔符() {
        assertTrue(quoteTimeIsOnDate("20260807102532", "20260807"))            // YYYYMMDDHHMMSS
        assertTrue(quoteTimeIsOnDate("2026-08-07 10:25:32", "20260807"))       // 带分隔符
        assertFalse(quoteTimeIsOnDate("20260806102532", "20260807"))           // 上一交易日
        assertFalse(quoteTimeIsOnDate("", "20260807"))                          // 空时间戳
        assertFalse(quoteTimeIsOnDate("102532", "20260807"))                    // 不足日期前缀
    }

    @Test
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
        assertEquals(120, pts[3].minute)        // 13:00 → 120（午休压缩，从 120 继续）
        assertEquals(3.497, pts[0].price, 0.0001)
        // 均价 = 累计额 /（累计量手 × 100）
        assertEquals(500.0 / (100 * 100.0), pts[0].avgPrice, 0.0001)
        assertEquals(1500.0 / (300 * 100.0), pts[1].avgPrice, 0.0001)
    }

    @Test
    fun 分时数据_异常返回null或空() {
        assertNull(parseMinuteData("not json"))
        assertNull(parseMinuteData("""{"code":1,"data":{}}"""))
        assertNull(parseMinuteData("""{"code":0,"data":{"sz159915":{"data":{"data":[]}}}}"""))
    }

    @Test
    fun 分钟索引_非法输入返回null() {
        assertNull(parseMinuteIndex(""))
        assertNull(parseMinuteIndex("093"))
        assertNull(parseMinuteIndex("0830"))
        assertNull(parseMinuteIndex("1600"))
        assertEquals(0, parseMinuteIndex("0930")!!)
    }

    @Test
    fun 分钟索引_时间戳转换() {
        fun at(h: Int, m: Int): Long = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        assertEquals(0, minuteIndexOf(at(9, 30)))
        assertEquals(30, minuteIndexOf(at(10, 0)))
        assertEquals(120, minuteIndexOf(at(13, 0)))     // 午休压缩
        assertEquals(180, minuteIndexOf(at(14, 0)))
        assertEquals(240, minuteIndexOf(at(15, 0)))     // 收盘收敛
        assertEquals(0, minuteIndexOf(at(8, 0)))         // 开盘前收敛
    }

    @Test
    fun 指数基准按市场板块映射() {
        assertEquals("sh000001", indexCodeFor(Stock("600519", "贵州茅台", "sh")))
        assertEquals("sh000688", indexCodeFor(Stock("688981", "中芯国际", "sh")))
        assertEquals("sh000300", indexCodeFor(Stock("510300", "沪深300ETF", "sh")))
        assertEquals("sz399001", indexCodeFor(Stock("000001", "平安银行", "sz")))
        assertEquals("sz399006", indexCodeFor(Stock("300750", "宁德时代", "sz")))
        assertEquals("sz399006", indexCodeFor(Stock("159915", "创业板ETF", "sz"))) // 内置配置优先
        assertNull(indexCodeFor(Stock("832566", "北交个股", "bj")))
        assertNull(indexCodeFor(Stock("00700", "腾讯控股", "hk")))
    }
}
