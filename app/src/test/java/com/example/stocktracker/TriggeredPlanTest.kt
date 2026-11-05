package com.example.stocktracker

import org.junit.Assert.*
import org.junit.Test

class TriggeredPlanTest {
    private val fees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val config = TradePlanConfig(referencePrice = 5.0)
    private fun account(price: Double? = 5.0) = StockAccount(Stock("000001", "Example", "sz"),
        holdings = listOf(BuyLot(1, 5.0, 5000, 5000)), currentPrice = price, tradePlan = config)

    @Test fun equalityIncludesTheUpwardPointWithLowestPriceMatching() {
        val order = triggeredPlanEntries(listOf(account(5.2)), fees).single().orders.single()
        assertEquals(PlanDirection.UP, order.direction)
        assertEquals(SellMatch.LOWEST, order.match)
        assertEquals(800, order.qty)
    }

    @Test fun aFullCostBudgetTriggersHighestPriceReductionOnADrop() {
        val a = account(4.8).copy(holdings = listOf(BuyLot(1, 5.0, 10000, 10000)))
        val order = triggeredPlanEntries(listOf(a), fees).single().orders.single()
        assertEquals(TradeType.SELL, order.type)
        assertEquals(SellMatch.HIGHEST, order.match)
    }

    @Test fun costHeadroomTriggersAnAmountSizedBuy() {
        val order = triggeredPlanEntries(listOf(account(4.8)), fees).single().orders.single()
        assertEquals(TradeType.BUY, order.type)
        assertEquals(800, order.qty)
    }

    @Test fun returningInsideTheBandRemovesTheEntryWithoutTrading() {
        val a = account(5.3)
        assertEquals(1, triggeredPlanEntries(listOf(a), fees).size)
        assertTrue(triggeredPlanEntries(listOf(a.copy(currentPrice = 5.0)), fees).isEmpty())
        assertTrue(a.trades.isEmpty())
        assertEquals(5000, a.totalQty)
    }

    @Test fun missingInvalidOrLegacySettingsAndQuotesCannotTrigger() {
        val invalid = listOf(account(6.0).copy(tradePlan = null), account(null), account(0.0),
            account(-1.0), account(Double.NaN), account(Double.POSITIVE_INFINITY),
            account(6.0).copy(tradePlan = config.copy(stepPercent = 0.0)),
            account(6.0).copy(tradePlan = config.copy(legacyJson = "{}")))
        assertTrue(triggeredPlanEntries(invalid, fees).isEmpty())
    }

    @Test fun indexesAndMarketCodesSurviveFilteringAndDuplicateNames() {
        val second = account(4.7).copy(stock = Stock("000002", "Example", "sz"))
        val entries = triggeredPlanEntries(listOf(account(), account(5.3), second), fees)
        assertEquals(listOf(1, 2), entries.map { it.accountIndex })
        assertEquals(listOf("sz000001", "sz000002"), entries.map { it.stock.marketCode })
    }

    @Test fun aSingleFillAnchorCannotTriggerBothDirectionsAtOnce() {
        for (price in listOf(1.0, 4.8, 5.0, 5.2, 100.0)) {
            assertTrue(triggeredPlanEntries(listOf(account(price)), fees).all { it.orders.size == 1 })
        }
    }

    @Test fun waitingOrdersAreExcludedWhileAnEmptyPositionCanBuy() {
        val waiting = account(6.0).copy(tradePlan = config.copy(lowerCost = 25000.0, upperCost = 25010.0))
        assertTrue(triggeredPlanEntries(listOf(waiting), fees).isEmpty())
        val empty = account(4.8).copy(holdings = emptyList())
        assertEquals(TradeType.BUY, triggeredPlanEntries(listOf(empty), fees).single().orders.single().type)
    }

    @Test fun configuredFeesAndCurrentExecutionPriceArePassedThrough() {
        val configured = FeeConfig(commissionRate = 0.01, minCommission = 0.0, stampTaxRate = 0.0, transferRate = 0.0)
        val order = triggeredPlanEntries(listOf(account(5.5)), configured).single().orders.single()
        assertEquals(5.2, order.triggerPrice!!, 1e-9)
        assertEquals(5.5, order.price!!, 1e-9)
        assertEquals(700, order.qty)
        assertEquals(38.5, order.estimatedFee!!, 1e-9)
        assertEquals(311.5, order.estimatedNetProfit!!, 1e-9)
    }
}
