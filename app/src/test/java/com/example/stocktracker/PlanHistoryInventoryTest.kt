package com.example.stocktracker

import org.junit.Assert.*
import org.junit.Test

class PlanHistoryInventoryTest {
    private val stock = Stock("002396", "Example", "sz")
    private val buy = TradeRecord(2, TradeType.BUY, 10.0, 100, lotId = 1)

    @Test fun bonusRemainderKeepsTheOriginalBuyRowOpen() {
        val account = StockAccount(stock, holdings = listOf(BuyLot(1, 1000.0 / 130, 130, 30)),
            trades = listOf(buy, TradeRecord(3, TradeType.SELL, 8.0, 100)))
        assertFalse(2L in clearedBuyTradeIds(account))
    }

    @Test fun aFullySoldLotClosesOnlyItsOwnBuyRow() {
        val second = TradeRecord(4, TradeType.BUY, 9.0, 100, lotId = 5)
        val account = StockAccount(stock, holdings = listOf(BuyLot(5, 9.0, 100, 100)), trades = listOf(buy, second))
        assertEquals(setOf(2L), clearedBuyTradeIds(account))
    }

    @Test fun legacyBuyIdsRemainUsableWhenTheyMatchCurrentInventory() {
        val legacy = buy.copy(id = 1, lotId = null)
        assertTrue(clearedBuyTradeIds(StockAccount(stock, listOf(BuyLot(1, 10.0, 100, 1)), listOf(legacy))).isEmpty())
        assertEquals(setOf(1L), clearedBuyTradeIds(StockAccount(stock, trades = listOf(legacy))))
    }
}
