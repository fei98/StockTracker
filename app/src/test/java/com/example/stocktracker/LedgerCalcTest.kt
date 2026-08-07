package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：目标盈利反推期望现价、相对均价涨幅、交易记录清仓置灰判定 */
class LedgerCalcTest {

    // ---------------- 目标盈利反推期望现价 ----------------

    @Test
    fun 期望现价_含费成本加目标盈利除以数量() {
        // 含费成本 1020，量 300，目标盈利 80 → (1020+80)/300 = 3.666…
        assertEquals(1100.0 / 300.0, expectedPriceForProfit(1020.0, 300, 80.0), 0.0001)
    }

    @Test
    fun 期望现价_目标为负即目标亏损() {
        assertEquals((1000.0 - 200.0) / 100, expectedPriceForProfit(1000.0, 100, -200.0), 0.0001)
    }

    @Test
    fun 期望现价_无持仓返回0() {
        assertEquals(0.0, expectedPriceForProfit(1000.0, 0, 100.0), 0.0001)
    }

    @Test
    fun 涨幅_相对持仓均价() {
        // 均价 3.0 → 现价 3.6，涨幅 20%
        assertEquals(20.0, pctChangeFromAvgPrice(3.0, 3.6), 0.0001)
        assertEquals(-10.0, pctChangeFromAvgPrice(3.0, 2.7), 0.0001)
    }

    @Test
    fun 涨幅_均价非正返回0() {
        assertEquals(0.0, pctChangeFromAvgPrice(0.0, 3.0), 0.0001)
    }

    // ---------------- 清仓置灰判定 ----------------

    private fun buy(id: Long, p: Double, q: Int) = TradeRecord(id, TradeType.BUY, p, q)
    private fun sell(id: Long, p: Double, q: Int) = TradeRecord(id, TradeType.SELL, p, q, profit = 0.0)

    @Test
    fun 清仓_全清_三笔全灰() {
        // 买100+买200，卖300 → 前面批次全清
        val trades = listOf(buy(1, 3.0, 100), buy(2, 4.0, 200), sell(30, 5.0, 300))
        val cleared = clearedTradeIds(trades)
        assertTrue(cleared == setOf(1L, 2L, 30L))
    }

    @Test
    fun 清仓_部分卖出_全清批次灰未清批次不灰() {
        // 买100 + 买200，卖100 → 第一次全清置灰，第二次不动
        val trades = listOf(buy(1, 3.0, 100), buy(2, 4.0, 200), sell(11, 5.0, 100))
        val cleared = clearedTradeIds(trades)
        assertTrue(cleared == setOf(1L, 11L)) // 买1 清；买2 剩200 不灰
    }

    @Test
    fun 清仓_跨批次部分清_部分清不灰() {
        // 买100 + 买200，卖150（最低价优先）：买1 全清清，买2 剩150 不灰；卖部分清 → 不灰
        val trades = listOf(buy(1, 3.0, 100), buy(2, 4.0, 200), sell(11, 5.0, 150))
        val cleared = clearedTradeIds(trades)
        assertTrue(cleared == setOf(1L)) // 仅买1 灰；买2 与卖出 不灰
    }

    @Test
    fun 清仓_卖后重新买入_新买入不灰() {
        // 买100 + 买200 + 卖300（清），再买300 → 旧三笔灰，新买入正常
        val trades = listOf(buy(1, 3.0, 100), buy(2, 4.0, 200), sell(11, 5.0, 300), buy(3, 6.0, 300))
        val cleared = clearedTradeIds(trades)
        assertTrue(cleared == setOf(1L, 2L, 11L))
        assertTrue(3L !in cleared)
    }
}