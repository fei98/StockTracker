package com.example.stocktracker

import org.junit.Assert.*
import org.junit.Test

class TriggeredPlanTest {
    private val fees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val config = TradePlanConfig(threshold = 400.0, step = 5.0, referencePrice = 50.0)

    private fun account(price: Double? = 50.0, qty: Int = 500) = StockAccount(
        Stock("000001", "Example", "sz"),
        holdings = listOf(BuyLot(1, 50.0, qty, qty)),
        currentPrice = price, tradePlan = config
    )

    @Test
    fun anUpwardPointIsIncludedAtEqualityWithLowestPriceMatching() {
        val entry = triggeredPlanEntries(listOf(account(55.0)), fees).single()
        assertEquals(55.0, entry.currentPrice, 0.000001)
        val order = entry.orders.single()
        assertEquals(PlanDirection.UP, order.direction)
        assertEquals(TradeType.SELL, order.type)
        assertEquals(SellMatch.LOWEST, order.match)
        assertEquals(100, order.qty)
    }

    @Test
    fun aDownwardPointAboveTheThresholdUsesHighestPriceMatching() {
        val order = triggeredPlanEntries(listOf(account(45.0)), fees).single().orders.single()
        assertEquals(PlanDirection.DOWN, order.direction)
        assertEquals(TradeType.SELL, order.type)
        assertEquals(SellMatch.HIGHEST, order.match)
    }

    @Test
    fun aDownwardPointAtTheThresholdStillRecommendsOneBuyLot() {
        val order = triggeredPlanEntries(listOf(account(45.0, qty = 400)), fees).single().orders.single()
        assertEquals(TradeType.BUY, order.type)
        assertEquals(100, order.qty)
        assertEquals(500, order.resultingQty)
    }

    @Test
    fun movingBackInsideTheBandRemovesTheStockWithoutKeepingTriggerHistory() {
        val original = account(60.0)
        assertEquals(1, triggeredPlanEntries(listOf(original), fees).size)
        assertTrue(triggeredPlanEntries(listOf(original.copy(currentPrice = 50.0)), fees).isEmpty())
        assertEquals(500, original.totalQty)
        assertTrue(original.trades.isEmpty())
    }

    @Test
    fun unconfiguredStocksAndInvalidQuotesCannotAppear() {
        val accounts = listOf(account(60.0).copy(tradePlan = null), account(null), account(0.0),
            account(-1.0), account(Double.NaN), account(Double.POSITIVE_INFINITY),
            account(60.0).copy(tradePlan = config.copy(step = 0.0)))
        assertTrue(triggeredPlanEntries(accounts, fees).isEmpty())
    }

    @Test
    fun navigationIndexesAndDistinctMarketCodesSurviveFilteringAndDuplicateNames() {
        val first = account(60.0)
        val second = account(40.0).copy(stock = Stock("000002", "Example", "sz"))
        val entries = triggeredPlanEntries(listOf(account(50.0), first, second), fees)
        assertEquals(listOf(1, 2), entries.map { it.accountIndex })
        assertEquals(listOf("sz000001", "sz000002"), entries.map { it.stock.marketCode })
    }

    @Test
    fun simultaneousDirectionsAreGroupedIntoOneStockEntry() {
        val account = account(75.0).copy(tradePlan = config.copy(referencePrice = 100.0))
        val entry = triggeredPlanEntries(listOf(account), fees).single()
        assertEquals(listOf(PlanDirection.UP, PlanDirection.DOWN), entry.orders.map { it.direction })
    }

    @Test
    fun blockedSellPointsAreExcludedButAnEmptyConfiguredPositionCanTriggerABuy() {
        val empty = account(60.0, qty = 0).copy(holdings = emptyList())
        assertTrue(triggeredPlanEntries(listOf(empty), fees).isEmpty())
        val order = triggeredPlanEntries(listOf(empty.copy(currentPrice = 40.0)), fees)
            .single().orders.single()
        assertEquals(TradeType.BUY, order.type)
    }

    @Test
    fun sameDayFrozenSharesDoNotHideBookkeepingSignals() {
        val now = 1_793_750_400_000L
        val account = account(60.0).copy(
            holdings = listOf(BuyLot(1, 50.0, 500, 500, now)),
            trades = listOf(TradeRecord(1, TradeType.BUY, 50.0, 500, time = now, lotId = 1))
        )
        assertEquals(0, brokerSellableQty(account.holdings, account.trades, now))
        assertEquals(1, triggeredPlanEntries(listOf(account), fees).size)
    }

    @Test
    fun theConfiguredFeesAreUsedForTheReturnedOrderEstimates() {
        val fees = FeeConfig(commissionRate = 0.01, minCommission = 0.0,
            stampTaxRate = 0.0, transferRate = 0.0)
        val order = triggeredPlanEntries(listOf(account(55.0)), fees).single().orders.single()
        assertEquals(445.0, order.estimatedNetProfit!!, 0.000001)
    }
}
