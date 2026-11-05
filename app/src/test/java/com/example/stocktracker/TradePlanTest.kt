package com.example.stocktracker

import org.junit.Assert.*
import org.junit.Test

class TradePlanTest {
    private val fees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val config = TradePlanConfig(referencePrice = 5.0)
    private val lots = listOf(6.0, 5.75, 5.5, 5.25, 5.0).mapIndexed { i, p ->
        BuyLot(i + 1L, p, 1000, 1000)
    }
    private fun account(holdings: List<BuyLot> = lots, plan: TradePlanConfig = config,
                        current: Double? = null) = StockAccount(Stock("000001", "Example", "sz"),
        holdings = holdings, tradePlan = plan, currentPrice = current)
    private fun plan(a: StockAccount) = evaluateTradePlan(a, fees)!!
    private fun record(a: StockAccount, order: TradePlanOrder): StockAccount {
        assertNull(order.block)
        val id = maxOf(a.trades.maxOfOrNull { it.id } ?: 0L, a.holdings.maxOfOrNull { it.id } ?: 0L) + 1
        return if (order.type == TradeType.BUY) a.copy(
            holdings = a.holdings + BuyLot(id, order.price!!, order.qty, order.qty),
            trades = a.trades + TradeRecord(id + 1, TradeType.BUY, order.price, order.qty, lotId = id))
        else {
            val sale = matchSellLots(a.holdings, order.qty, order.price!!, order.match!!)
            a.copy(holdings = sale.remaining, trades = a.trades + TradeRecord(id, TradeType.SELL,
                order.price, order.qty, profit = sale.profit, soldLots = sale.soldLots))
        }
    }

    @Test fun bothDirectionsUseTheSameFillAnchorWithOutwardCentRounding() {
        val result = plan(account())
        assertEquals(5.20, result.upward.triggerPrice!!, 1e-9)
        assertEquals(4.80, result.downward.triggerPrice!!, 1e-9)
        assertEquals(27_500.0, result.positionCost, 1e-9)
        val rounded = plan(account(plan = config.copy(referencePrice = 2.03)))
        assertEquals(2.12, rounded.upward.triggerPrice!!, 1e-9)
        assertEquals(1.94, rounded.downward.triggerPrice!!, 1e-9)
    }

    @Test fun upwardSellsProfitableLowestLotsAndDownwardBuysWithinBudget() {
        val result = plan(account())
        assertEquals(TradeType.SELL, result.upward.type)
        assertEquals(SellMatch.LOWEST, result.upward.match)
        assertEquals(800, result.upward.qty)
        assertEquals(23_500.0, result.upward.resultingCost!!, 1e-9)
        assertEquals(TradeType.BUY, result.downward.type)
        assertEquals(800, result.downward.qty)
        assertEquals(31_340.0, result.downward.resultingCost!!, 1e-9)
    }

    @Test fun downwardAtTheUpperLimitSellsTheHighestBatchAtALoss() {
        val order = plan(account(listOf(BuyLot(1, 5.0, 10000, 10000)))).downward
        assertEquals(TradeType.SELL, order.type)
        assertEquals(SellMatch.HIGHEST, order.match)
        assertEquals(PlanReason.DOWN_REDUCE, order.reason)
        assertEquals(46_000.0, order.resultingCost!!, 1e-9)
        assertTrue(order.estimatedNetProfit!! < 0)
    }

    @Test fun upwardAtTheLowerLimitBuysInsteadOfSellingTheBase() {
        val order = plan(account(listOf(BuyLot(1, 5.0, 3000, 3000)))).upward
        assertEquals(TradeType.BUY, order.type)
        assertEquals(PlanReason.UP_REPLENISH, order.reason)
        assertEquals(19_160.0, order.resultingCost!!, 1e-9)
    }

    @Test fun upwardWithoutAProfitableBatchBuysWhenThereIsRoom() {
        val order = plan(account(listOf(BuyLot(1, 6.0, 5000, 5000)))).upward
        assertEquals(TradeType.BUY, order.type)
        assertEquals(800, order.qty)
        assertEquals(34_160.0, order.resultingCost!!, 1e-9)
    }

    @Test fun aFullPositionWithoutProfitWaitsUntilTheQuoteMakesLotsProfitable() {
        val a = account(listOf(BuyLot(1, 8.0, 5000, 5000)), config.copy(upperCost = 40000.0), 6.0)
        assertEquals(PlanBlock.WAIT, plan(a).upward.block)
        val order = plan(a.copy(currentPrice = 8.01)).upward
        assertNull(order.block)
        assertEquals(5.2, order.triggerPrice!!, 1e-9)
        assertEquals(8.01, order.price!!, 1e-9)
        assertEquals(TradeType.SELL, order.type)
        assertEquals(500, order.qty)
    }

    @Test fun narrowCostBoundsCanMakeBothDirectionsWaitWithoutInventingATrade() {
        val p = config.copy(lowerCost = 25000.0, upperCost = 25010.0)
        val result = plan(account(listOf(BuyLot(1, 5.0, 5000, 5000)), p))
        for (order in listOf(result.upward, result.downward)) {
            assertEquals(PlanBlock.WAIT, order.block)
            assertNull(order.type)
            assertEquals(0, order.qty)
        }
    }

    @Test fun amountSizingUsesTheCrossedQuoteAndRoundsToTheNearestHundred() {
        val order = plan(account(current = 2.0)).downward
        assertEquals(4.8, order.triggerPrice!!, 1e-9)
        assertEquals(2.0, order.price!!, 1e-9)
        assertEquals(2000, order.qty)
        assertTrue(order.reached)
    }

    @Test fun aSmallAmountStillRequestsOneRoundLotWhenTheCostBoundsPermitIt() {
        assertEquals(100, plan(account(plan = config.copy(orderAmount = 1.0))).downward.qty)
    }

    @Test fun aBuyIsClippedToTheCostCeilingAndTheNextDropReverses() {
        var a = account(plan = config.copy(upperCost = 28000.0))
        val first = plan(a).downward
        assertEquals(TradeType.BUY, first.type)
        assertEquals(100, first.qty)
        assertEquals(27_980.0, first.resultingCost!!, 1e-9)
        a = record(a, first)
        val next = plan(a).downward
        assertEquals(TradeType.SELL, next.type)
        assertEquals(SellMatch.HIGHEST, next.match)
    }

    @Test fun lessThanOneHundredSharesOfCostHeadroomCountsAsFull() {
        val order = plan(account(plan = config.copy(upperCost = 27900.0))).downward
        assertEquals(TradeType.SELL, order.type)
        assertEquals(800, order.qty)
        assertEquals(22_700.0, order.resultingCost!!, 1e-9)
    }

    @Test fun salesAreClippedUsingMatchedPurchaseCostRatherThanSaleProceeds() {
        val p = config.copy(lowerCost = 26000.0, upperCost = 27500.0)
        val result = plan(account(plan = p))
        assertEquals(300, result.upward.qty)
        assertEquals(26_000.0, result.upward.resultingCost!!, 1e-9)
        assertEquals(200, result.downward.qty)
        assertEquals(26_300.0, result.downward.resultingCost!!, 1e-9)
    }

    @Test fun anUpwardSaleNeverIncludesAnUnprofitableBatchToFillTheAmount() {
        val order = plan(account(listOf(BuyLot(1, 4.0, 100, 100), BuyLot(2, 6.0, 5000, 5000)))).upward
        assertEquals(TradeType.SELL, order.type)
        assertEquals(100, order.qty)
    }

    @Test fun oddLegacyLotsAreConsumedInActualMatchOrderBeforeRoundingTheTotal() {
        val holdings = listOf(BuyLot(1, 4.0, 50, 50), BuyLot(2, 5.0, 150, 150))
        val order = plan(account(holdings, config.copy(lowerCost = 0.0))).upward
        assertEquals(200, order.qty)
        assertEquals(0.0, order.resultingCost!!, 1e-9)
        val insufficientProfit = plan(account(listOf(BuyLot(1, 4.0, 50, 50), BuyLot(2, 6.0, 150, 150)),
            config.copy(lowerCost = 0.0))).upward
        assertEquals(TradeType.BUY, insufficientProfit.type)
    }

    @Test fun quotesDoNotMoveTheAnchorAndUndoRestoresThePriorFill() {
        val a = account(plan = config.copy(startAfterTradeId = 10)).copy(trades = listOf(
            TradeRecord(9, TradeType.BUY, 100.0, 100), TradeRecord(11, TradeType.SELL, 6.0, 100)))
        val changedQuote = a.copy(currentPrice = 9.0)
        assertEquals(6.0, plan(changedQuote).referencePrice, 1e-9)
        assertEquals(6.24, plan(changedQuote).upward.triggerPrice!!, 1e-9)
        assertEquals(5.0, plan(a.copy(trades = a.trades.dropLast(1))).referencePrice, 1e-9)
    }

    @Test fun actualRecordedPricesBecomeTheNextReference() {
        val a = account().copy(trades = listOf(TradeRecord(11, TradeType.BUY, 4.7, 800)))
        val result = plan(a)
        assertEquals(4.7, result.referencePrice, 1e-9)
        assertEquals(4.89, result.upward.triggerPrice!!, 1e-9)
        assertEquals(4.51, result.downward.triggerPrice!!, 1e-9)
    }

    @Test fun sameDayBrokerFreezesDoNotRestrictBookkeepingSuggestions() {
        val now = 1_793_750_400_000L
        val a = account(listOf(BuyLot(1, 5.0, 5000, 5000, now))).copy(
            trades = listOf(TradeRecord(1, TradeType.BUY, 5.0, 5000, time = now, lotId = 1)))
        assertEquals(0, brokerSellableQty(a.holdings, a.trades, now))
        assertEquals(TradeType.SELL, plan(a).upward.type)
        assertNull(plan(a).upward.block)
    }

    @Test fun estimatesIncludeBothTheSaleFeeAndAllocatedHistoricalBuyFee() {
        val a = account(listOf(BuyLot(1, 5.0, 5000, 5000, fee = 50.0)))
        val order = evaluateTradePlan(a, FeeConfig())!!.upward
        val sellFee = FeeConfig().sellFee(5.2 * 800)
        assertEquals(sellFee, order.estimatedFee!!, 1e-9)
        assertEquals(160.0 - 8.0 - sellFee, order.estimatedNetProfit!!, 1e-8)
    }

    @Test fun decimalCapacityDoesNotCrossTheCeilingBecauseOfFloatingPointNoise() {
        val a = account(listOf(BuyLot(1, 0.1, 10, 10)),
            config.copy(lowerCost = 0.0, upperCost = 21.0, orderAmount = 10.0, referencePrice = 0.21))
        val order = plan(a).downward
        assertEquals(100, order.qty)
        assertEquals(21.0, order.resultingCost!!, 1e-9)
    }

    @Test fun existingPositionsOutsideTheLimitsCanMoveTowardsTheBand() {
        val over = plan(account(listOf(BuyLot(1, 10.0, 10000, 10000)))).downward
        assertEquals(TradeType.SELL, over.type)
        assertEquals(92_000.0, over.resultingCost!!, 1e-9)
        val under = plan(account(listOf(BuyLot(1, 5.0, 200, 200)))).upward
        assertEquals(TradeType.BUY, under.type)
    }

    @Test fun quantityOverflowCannotProduceANegativeBuy() {
        val a = account(listOf(BuyLot(1, 1.0, Int.MAX_VALUE, Int.MAX_VALUE)),
            config.copy(lowerCost = 0.0, upperCost = 1e20, orderAmount = 1e20, referencePrice = 1.0))
        assertEquals(PlanBlock.WAIT, plan(a).downward.block)
    }

    @Test fun invalidHoldingsAndUnsupportedSettingsCannotGenerateOrders() {
        val invalid = account(listOf(BuyLot(1, Double.NaN, 100, 100)))
        assertEquals(PlanBlock.INVALID_HOLDINGS, plan(invalid).upward.block)
        assertNull(evaluateTradePlan(account(plan = config.copy(legacyJson = "{}")), fees))
        for (bad in listOf(config.copy(lowerCost = -1.0), config.copy(upperCost = 15000.0),
            config.copy(orderAmount = 0.0), config.copy(stepPercent = 100.0),
            config.copy(stepPercent = Double.NaN), config.copy(referencePrice = 0.0))) {
            assertNull(evaluateTradePlan(account(plan = bad), fees))
        }
    }

    @Test fun overflowingValuesAreBlockedAndClosedInvalidLotsDoNotPolluteEstimates() {
        val overflow = account(listOf(BuyLot(1, 1e308, 100, 100)))
        assertEquals(PlanBlock.INVALID_HOLDINGS, plan(overflow).upward.block)
        assertEquals(PlanBlock.INVALID_PRICE, plan(account(current = 1e308)).upward.block)
        val closed = account(lots + BuyLot(99, Double.NaN, 100, 0))
        val order = plan(closed).upward
        assertNull(order.block)
        assertTrue(order.estimatedNetProfit!!.isFinite())
    }

    @Test fun centQuoteReplayMatchesTheIndependentlyCheckedSimulation() {
        var a = account()
        var cash = 72_500.0
        val waypoints = listOf(500, 200, 300, 200, 300, 200)
        var downQty = 0
        var downCost = 0.0
        for (i in 1 until waypoints.size) {
            val from = waypoints[i - 1]
            val to = waypoints[i]
            val sign = if (to > from) 1 else -1
            var quote = from + sign
            while (if (sign > 0) quote <= to else quote >= to) {
                a = a.copy(currentPrice = quote / 100.0)
                val result = plan(a)
                val order = listOf(result.upward, result.downward).firstOrNull { it.reached && it.block == null }
                if (order != null) {
                    cash += order.price!! * order.qty * if (order.type == TradeType.BUY) -1 else 1
                    a = record(a, order)
                }
                if (i == 1 && quote == 200) { downQty = a.totalQty; downCost = a.totalCost }
                quote += sign
            }
        }
        assertEquals(15_200, downQty)
        assertEquals(45_220.0, downCost, 1e-6)
        assertEquals(18_500, a.totalQty)
        assertEquals(49_991.0, a.totalCost, 1e-6)
        assertEquals(30_811.0, cash, 1e-6)
        assertTrue(a.totalCost in 15000.0..50000.0)
        assertTrue(cash > 0)
    }
}
