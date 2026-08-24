package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：总览浮盈正收益时的可卖提示（T+1 冻结角度） */
class OverviewNoteTest {

    // A 股：昨日买入可卖；今日买入 T+1 冻结不可卖
    private fun lotToday(id: Long, qty: Int) = BuyLot(id, 3.0, qty, qty, time = System.currentTimeMillis(), fee = 0.0)
    private fun lotOld(id: Long, qty: Int) = BuyLot(id, 3.0, qty, qty, time = 0L, fee = 0.0)

    @Test
    fun 全部可卖_不标记() {
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            holdings = listOf(lotOld(1, 100), lotOld(2, 200))
        )
        assertNull(sellableNote(acc))
    }

    @Test
    fun 全部不可卖_标记不可卖() {
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            holdings = listOf(lotToday(1, 100), lotToday(2, 200))
        )
        assertEquals("不可卖", sellableNote(acc))
    }

    @Test
    fun 部分可卖_标记部分可卖() {
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            holdings = listOf(lotOld(1, 100), lotToday(2, 200))
        )
        assertEquals("部分可卖", sellableNote(acc))
    }

    @Test
    fun 无持仓_不标记() {
        val acc = StockAccount(stock = Stock("000001", "平安银行", "sz"))
        assertNull(sellableNote(acc))
    }

    // ---------------- 交易汇总（allTradesSorted） ----------------

    @Test
    fun 交易汇总_合并全部股票并按时间倒序() {
        val accA = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            trades = listOf(
                TradeRecord(1, TradeType.BUY, 3.0, 100, time = 100L),
                TradeRecord(2, TradeType.SELL, 3.5, 50, profit = 25.0, time = 300L)
            )
        )
        val accB = StockAccount(
            stock = Stock("600519", "贵州茅台", "sh"),
            trades = listOf(
                TradeRecord(3, TradeType.BUY, 1500.0, 100, time = 200L),
                TradeRecord(4, TradeType.SELL, 1600.0, 50, profit = 5000.0, time = 400L)
            )
        )
        val all = allTradesSorted(listOf(accA, accB))
        assertEquals(4, all.size)
        // 最新在前：400 → 300 → 200 → 100
        assertEquals(listOf(400L, 300L, 200L, 100L), all.map { it.second.time })
        assertEquals("贵州茅台", all[0].first.name)
        assertEquals("平安银行", all[1].first.name)
        // 每项股票归属正确
        assertEquals("sz000001", all[3].first.marketCode)
        assertEquals(TradeType.SELL, all[0].second.type)
    }

    @Test
    fun 交易汇总_空账户返回空() {
        assertTrue(allTradesSorted(emptyList()).isEmpty())
    }
}
