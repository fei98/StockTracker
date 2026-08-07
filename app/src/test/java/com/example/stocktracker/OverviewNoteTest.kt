package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
