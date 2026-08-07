package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 覆盖：交易记录今天/昨天标记 + 总览已实现的时间区间统计 */
class RealizedRangeTest {

    private fun nowMillis(): Long = System.currentTimeMillis()

    private fun millis(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 某日 00:00（区间边界用） */
    private fun midnight(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---------------- dayLabel：今天 / 昨天 ----------------

    @Test
    fun 今天_标记今天() {
        assertEquals("今天", dayLabel(nowMillis(), nowMillis()))
    }

    @Test
    fun 昨天_标记昨天() {
        val now = nowMillis()
        val yesterday = now - 24 * 60 * 60 * 1000L
        assertEquals("昨天", dayLabel(yesterday, now))
    }

    @Test
    fun 更早_不标记() {
        val now = nowMillis()
        val old = now - 3 * 24 * 60 * 60 * 1000L
        assertNull(dayLabel(old, now))
    }

    @Test
    fun 跨月昨天仍标记昨天() {
        val now = millis(2026, 3, 1)   // 3 月 1 日
        val yesterday = millis(2026, 2, 28) // 2 月 28 日
        assertEquals("昨天", dayLabel(yesterday, now))
    }

    // ---------------- 已实现区间统计 ----------------

    @Test
    fun 本月_只统计本月卖出() {
        val inMonth = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 50.0, time = millis(2026, 3, 15), fee = 2.0, costFee = 1.0)
        val lastMonth = TradeRecord(2, TradeType.SELL, 10.0, 100, profit = 100.0, time = millis(2026, 2, 15), fee = 2.0, costFee = 1.0)
        val buy = TradeRecord(3, TradeType.BUY, 5.0, 100, time = millis(2026, 3, 1))
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            trades = listOf(inMonth, lastMonth, buy)
        )
        // 本月固定 now = 2026-03-20
        val now = millis(2026, 3, 20)
        val entries = realizedEntriesInRange(listOf(acc), RealizedRange.ThisMonth, now)
        // 只统计本月卖出：50 - 2 - 1 = 47；上月卖出不计入
        assertEquals(47.0, entries[0].value, 0.0001)
    }

    @Test
    fun 某日_只统计当天卖出() {
        val day = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 30.0, time = millis(2026, 3, 15, ), fee = 2.0, costFee = 1.0)
        val otherDay = TradeRecord(2, TradeType.SELL, 10.0, 100, profit = 60.0, time = millis(2026, 3, 16), fee = 2.0, costFee = 1.0)
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            trades = listOf(day, otherDay)
        )
        val entries = realizedEntriesInRange(listOf(acc), RealizedRange.Day(millis(2026, 3, 15)))
        assertEquals(27.0, entries[0].value, 0.0001)
    }

    @Test
    fun 某年_只统计当年卖出() {
        val y2026 = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 20.0, time = millis(2026, 5, 1), fee = 2.0, costFee = 1.0)
        val y2025 = TradeRecord(2, TradeType.SELL, 10.0, 100, profit = 80.0, time = millis(2025, 12, 31), fee = 2.0, costFee = 1.0)
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            trades = listOf(y2026, y2025)
        )
        val entries = realizedEntriesInRange(listOf(acc), RealizedRange.Year(2026))
        assertEquals(17.0, entries[0].value, 0.0001)
    }

    @Test
    fun 具体月_只统计当月卖出() {
        val inFeb = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 50.0, time = millis(2026, 2, 10), fee = 2.0, costFee = 1.0)
        val inMar = TradeRecord(2, TradeType.SELL, 10.0, 100, profit = 100.0, time = millis(2026, 3, 10), fee = 2.0, costFee = 1.0)
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            trades = listOf(inFeb, inMar)
        )
        val entries = realizedEntriesInRange(listOf(acc), RealizedRange.Month(2026, 2))
        // 只统计 2026 年 2 月卖出：50 - 2 - 1 = 47
        assertEquals(47.0, entries[0].value, 0.0001)
    }

    @Test
    fun 选无数据月份_返回零() {
        val inMar = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 50.0, time = millis(2026, 3, 10), fee = 2.0, costFee = 1.0)
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            trades = listOf(inMar)
        )
        val entries = realizedEntriesInRange(listOf(acc), RealizedRange.Month(2025, 6))
        assertEquals(0.0, entries[0].value, 0.0001)
    }

    @Test
    fun 区间边界_包含起点不包含终点() {
        val atStart = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 10.0, time = millis(2026, 3, 1), fee = 1.0, costFee = 1.0)
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            trades = listOf(atStart)
        )
        // 本月 now = 2026-03-01 → 区间 [3-1 00:00, 4-1 00:00)，3-1 正午应计入
        val now = millis(2026, 3, 1)
        val entries = realizedEntriesInRange(listOf(acc), RealizedRange.ThisMonth, now)
        assertEquals(8.0, entries[0].value, 0.0001)
    }

    // ---------------- 区间边界正确性 ----------------

    @Test
    fun 本月区间_包含下月一号零点作为终点() {
        val now = millis(2026, 3, 20)
        val (from, to) = realizedRangeBounds(RealizedRange.ThisMonth, now)
        assertEquals(midnight(2026, 3, 1), from)
        assertEquals(midnight(2026, 4, 1), to)
    }

    @Test
    fun 某年区间_跨年正确() {
        val (from, to) = realizedRangeBounds(RealizedRange.Year(2025))
        assertEquals(midnight(2025, 1, 1), from)
        assertEquals(midnight(2026, 1, 1), to)
    }

    @Test
    fun 具体月区间_当月一号到次月一号() {
        val (from, to) = realizedRangeBounds(RealizedRange.Month(2026, 2))
        assertEquals(midnight(2026, 2, 1), from)
        assertEquals(midnight(2026, 3, 1), to)
    }

    @Test
    fun 标签_本月与某年() {
        assertEquals("本月", realizedRangeLabel(RealizedRange.ThisMonth))
        assertEquals("2025年", realizedRangeLabel(RealizedRange.Year(2025)))
        assertEquals("2026年2月", realizedRangeLabel(RealizedRange.Month(2026, 2)))
        assertEquals("2026-03-05", realizedRangeLabel(RealizedRange.Day(millis(2026, 3, 5))))
    }

    @Test
    fun 标签_今年与今日() {
        val now = millis(2026, 3, 5)
        assertEquals("今年", realizedRangeLabel(RealizedRange.Year(2026), now))
        assertEquals("2025年", realizedRangeLabel(RealizedRange.Year(2025), now))
        assertEquals("今日", realizedRangeLabel(RealizedRange.Day(millis(2026, 3, 5)), now))
        assertEquals("2026-03-04", realizedRangeLabel(RealizedRange.Day(millis(2026, 3, 4)), now))
    }

    // ---------------- 日收益 / 月收益 / 年收益聚合 ----------------

    @Test
    fun 日收益_某月每天聚合_无卖出当天为零() {
        val d5 = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 50.0, time = millis(2026, 3, 5), fee = 2.0, costFee = 1.0)
        val d20 = TradeRecord(2, TradeType.SELL, 10.0, 100, profit = 30.0, time = millis(2026, 3, 20), fee = 2.0, costFee = 1.0)
        val acc = StockAccount(stock = Stock("000001", "平安银行", "sz"), trades = listOf(d5, d20))
        val totals = realizedDayTotalsInMonth(listOf(acc), 2026, 3)
        assertEquals(31, totals.size)          // 3 月 31 天
        assertEquals(47.0, totals[4], 0.0001)  // 3/5：50-2-1
        assertEquals(27.0, totals[19], 0.0001) // 3/20：30-2-1
        assertEquals(0.0, totals[0], 0.0001)   // 3/1 无卖出
    }

    @Test
    fun 月收益_从最老月份到当前月连续_倒序() {
        val old = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 50.0, time = millis(2025, 11, 3), fee = 2.0, costFee = 1.0)
        val cur = TradeRecord(2, TradeType.SELL, 10.0, 100, profit = 100.0, time = millis(2026, 2, 10), fee = 2.0, costFee = 1.0)
        val acc = StockAccount(stock = Stock("000001", "平安银行", "sz"), trades = listOf(old, cur))
        val months = realizedMonthTotals(listOf(acc), now = millis(2026, 2, 15))
        // 最老 2025-11 → 当前 2026-02：共 4 个月
        assertEquals(4, months.size)
        assertEquals(Triple(2026, 2, 97.0), months[0].let { Triple(it.first, it.second, it.third) })
        assertEquals(Triple(2026, 1, 0.0), months[1].let { Triple(it.first, it.second, it.third) })
        assertEquals(Triple(2025, 12, 0.0), months[2].let { Triple(it.first, it.second, it.third) })
        assertEquals(Triple(2025, 11, 47.0), months[3].let { Triple(it.first, it.second, it.third) })
    }

    @Test
    fun 月收益_无交易数据_只含当前月() {
        val months = realizedMonthTotals(emptyList(), now = millis(2026, 2, 15))
        assertEquals(1, months.size)
        assertEquals(2026 to 2, months[0].first to months[0].second)
    }

    @Test
    fun 年收益_从第一笔交易年份到今年_倒序() {
        val y2025 = TradeRecord(1, TradeType.SELL, 10.0, 100, profit = 100.0, time = millis(2025, 6, 1), fee = 2.0, costFee = 1.0)
        val y2026 = TradeRecord(2, TradeType.SELL, 10.0, 100, profit = 200.0, time = millis(2026, 1, 5), fee = 2.0, costFee = 1.0)
        val acc = StockAccount(stock = Stock("000001", "平安银行", "sz"), trades = listOf(y2025, y2026))
        val years = realizedYearTotals(listOf(acc), now = millis(2026, 3, 1))
        // 最老一笔在 2025 → 2025..2026 共 2 年，倒序（2026 在前）
        assertEquals(2, years.size)
        assertEquals(2026 to 197.0, years[0])
        assertEquals(2025 to 97.0, years[1])
    }

    @Test
    fun 年收益_无交易数据_只含今年() {
        val years = realizedYearTotals(emptyList(), now = millis(2026, 3, 1))
        assertEquals(1, years.size)
        assertEquals(2026 to 0.0, years[0])
    }

    @Test
    fun 闰年_二月天数() {
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(28, daysInMonth(2026, 2))
    }
}
