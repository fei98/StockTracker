package com.example.stocktracker

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BrokerSellableQtyTest {
    private fun time(day: Int, hour: Int = 10): Long =
        LocalDateTime.of(2026, 11, day, hour, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun buy(id: Long, price: Double, qty: Int, time: Long) =
        TradeRecord(id, TradeType.BUY, price, qty, time = time, lotId = id)

    @Test
    fun historicalIntradayMatchingDoesNotLoseMatureShares() {
        val holdings = listOf(
            BuyLot(1, 30.0, 100, 100, time(2)),
            BuyLot(4, 19.0, 500, 500, time(3, 12))
        )
        val trades = listOf(
            buy(1, 30.0, 100, time(2)),
            buy(2, 20.0, 100, time(3)),
            TradeRecord(3, TradeType.SELL, 21.0, 100, time = time(3, 11)),
            buy(4, 19.0, 500, time(3, 12))
        )

        assertEquals(600, holdings.sumOf { it.remainingQty })
        assertEquals(600, brokerSellableQty(holdings, trades, time(4)))
    }

    @Test
    fun intradayReplacementRemainsFrozenUntilTheNextDay() {
        val holdings = listOf(BuyLot(1, 3.0, 300, 300, time(2)))
        val trades = listOf(
            buy(1, 3.0, 300, time(2)),
            buy(2, 2.0, 300, time(3)),
            TradeRecord(3, TradeType.SELL, 3.5, 300, time = time(3, 11))
        )

        assertEquals(0, brokerSellableQty(holdings, trades, time(3, 12)))
        assertEquals(300, brokerSellableQty(holdings, trades, time(4)))
    }

    @Test
    fun partialIntradaySaleKeepsTheUnsoldMatureBalanceAvailable() {
        val holdings = listOf(
            BuyLot(1, 3.0, 300, 300, time(2)),
            BuyLot(2, 2.0, 300, 150, time(3))
        )
        val trades = listOf(
            buy(1, 3.0, 300, time(2)),
            buy(2, 2.0, 300, time(3)),
            TradeRecord(3, TradeType.SELL, 3.5, 150, time = time(3, 11))
        )

        assertEquals(150, brokerSellableQty(holdings, trades, time(3, 12)))
        assertEquals(450, brokerSellableQty(holdings, trades, time(4)))
    }

    @Test
    fun highestPriceAccountingDoesNotDiscardAnotherBrokerLot() {
        val holdings = listOf(BuyLot(1, 2.0, 100, 100, time(2)))
        val trades = listOf(
            buy(1, 2.0, 100, time(2)),
            buy(2, 3.0, 200, time(2, 11)),
            TradeRecord(3, TradeType.SELL, 4.0, 200, time = time(3))
        )

        assertEquals(100, brokerSellableQty(holdings, trades, time(3, 12)))
    }

    @Test
    fun missingBuyHistoryFallsBackToHoldingsWithoutDoubleCountingKnownLots() {
        val holdings = listOf(
            BuyLot(1, 3.0, 100, 100, time = 0L),
            BuyLot(2, 2.0, 200, 200, time(2)),
            BuyLot(3, 1.0, 100, 100, time(3))
        )
        val trades = listOf(buy(2, 2.0, 200, time(2)))

        assertEquals(300, brokerSellableQty(holdings, trades, time(3, 12)))
    }

    @Test
    fun legacyBuyWithoutLotIdStillMatchesItsTradeId() {
        val holdings = listOf(
            BuyLot(1, 3.0, 100, 50, time(2)),
            BuyLot(3, 2.0, 100, 100, time(3))
        )
        val trades = listOf(
            TradeRecord(1, TradeType.BUY, 3.0, 100, time = time(2)),
            TradeRecord(2, TradeType.SELL, 4.0, 50, time = time(3)),
            buy(3, 2.0, 100, time(3, 11))
        )

        assertEquals(50, brokerSellableQty(holdings, trades, time(3, 12)))
    }

    @Test
    fun incompleteHistoryCannotMakeSellableExceedCurrentHoldings() {
        val holdings = listOf(BuyLot(1, 3.0, 100, 50, time(2)))
        val trades = listOf(buy(1, 3.0, 100, time(2)))

        assertEquals(50, brokerSellableQty(holdings, trades, time(3)))
    }

    @Test
    fun emptyHoldingsCannotHaveASellableBalance() {
        val trades = listOf(buy(1, 3.0, 100, time(2)))

        assertEquals(0, brokerSellableQty(emptyList(), trades, time(3)))
    }
}
