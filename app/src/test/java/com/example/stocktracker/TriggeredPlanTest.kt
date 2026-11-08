package com.example.stocktracker

import org.junit.Assert.*
import org.junit.Test

class TriggeredPlanTest {
    private val now = 1_794_096_000_000L
    private val fees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val config = TradePlanConfig(lowerCost = 1000.0, upperCost = 10000.0,
        orderAmount = 1000.0, stepPercent = 10.0, referencePrice = 10.0,
        capital = 20000.0, initialCash = 10000.0)
    private fun account(price: Double = 10.0, code: String = "000001", today: Boolean = false): StockAccount {
        val a = StockAccount(Stock(code, "Example", "sz"),
            holdings = listOf(BuyLot(1, 10.0, 500, 500, if (today) now else 0L)), currentPrice = 10.0)
        return observeTradePlanQuote(startTradePlan(a, config, 100), price)
    }

    @Test fun equalityIncludesThePersistentUpwardPoint() {
        val entry = triggeredPlanEntries(listOf(account(11.0)), fees, now).single()
        assertEquals(PlanDirection.UP, entry.orders.single().direction)
        assertEquals(100, entry.orders.single().qty)
        assertEquals(SellMatch.LOWEST, entry.orders.single().match)
    }

    @Test fun downReachedPointProposesAWholeAmountBuy() {
        val order = triggeredPlanEntries(listOf(account(9.09)), fees, now).single().orders.single()
        assertEquals(TradeType.BUY, order.type)
        assertEquals(100, order.qty)
        assertEquals(-1, order.gridIndex)
    }

    @Test fun returningInsideTheBandRemovesEntryWithoutInventingAFill() {
        val a = account(11.0)
        val b = observeTradePlanQuote(a, 10.8)
        assertEquals(1, triggeredPlanEntries(listOf(a), fees, now).size)
        assertTrue(triggeredPlanEntries(listOf(b), fees, now).isEmpty())
        assertTrue(b.trades.isEmpty())
        assertEquals(a.tradePlanState!!.phase, b.tradePlanState!!.phase)
    }

    @Test fun invalidLegacyMissingRuntimeAndInvalidQuotesCannotTrigger() {
        val a = account(11.0)
        val invalid = listOf(a.copy(tradePlan = null), a.copy(tradePlanState = null),
            a.copy(tradePlan = a.tradePlan!!.copy(legacyJson = "{}")), a.copy(currentPrice = null),
            a.copy(currentPrice = Double.NaN), a.copy(currentPrice = 0.0), a.copy(currentPrice = Double.POSITIVE_INFINITY))
        assertTrue(triggeredPlanEntries(invalid, fees, now).isEmpty())
    }

    @Test fun filteringRetainsOriginalMarketIndexesAndDuplicateNames() {
        val entries = triggeredPlanEntries(listOf(account(), account(11.0), account(9.09, "000002")), fees, now)
        assertEquals(listOf(1, 2), entries.map { it.accountIndex })
        assertEquals(listOf("sz000001", "sz000002"), entries.map { it.stock.marketCode })
    }

    @Test fun aSingleObservedQuoteCannotTriggerBothDirections() {
        for (price in listOf(1.0, 9.09, 10.0, 11.0, 100.0)) {
            assertTrue(triggeredPlanEntries(listOf(account(price)), fees, now).all { it.orders.size == 1 })
        }
    }

    @Test fun waitingAndTPlusOneOrdersAreNotIncluded() {
        assertTrue(triggeredPlanEntries(listOf(account(11.0, today = true)), fees, now).isEmpty())
        val a = account(9.09)
        val cashless = a.copy(tradePlanState = a.tradePlanState!!.copy(cash = 1.0))
        assertTrue(triggeredPlanEntries(listOf(cashless), fees, now).isEmpty())
    }

    @Test fun currentPriceAndConfiguredFeesArePassedThrough() {
        val custom = FeeConfig(0.01, 0.0, 0.0, 0.0)
        val order = triggeredPlanEntries(listOf(account(11.2)), custom, now).single().orders.single()
        assertEquals(11.0, order.triggerPrice!!, 1e-9)
        assertEquals(11.2, order.price!!, 1e-9)
        assertEquals(11.2, order.estimatedFee!!, 1e-9)
    }
}
