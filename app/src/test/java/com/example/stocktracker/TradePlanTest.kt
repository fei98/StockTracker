package com.example.stocktracker

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TradePlanTest {
    private val now = LocalDateTime.of(2026, 11, 4, 12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val yesterday = now - 86_400_000L
    private val zeroFees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val config = TradePlanConfig(threshold = 400.0, step = 5.0,
        referencePrice = 50.0, startAfterTradeId = 1)

    private fun account(qty: Int = 500, plan: TradePlanConfig = config) = StockAccount(
        Stock("002396", "Example", "sz"),
        holdings = listOf(BuyLot(1, 50.0, qty, qty, yesterday)),
        trades = listOf(TradeRecord(1, TradeType.BUY, 50.0, qty, time = yesterday, lotId = 1)),
        tradePlan = plan
    )

    private fun evaluate(account: StockAccount) = evaluateTradePlan(account, zeroFees)!!

    private fun record(account: StockAccount, order: TradePlanOrder): StockAccount {
        val id = (account.trades.maxOfOrNull { it.id } ?: 0L) + 1
        val price = order.price!!
        return if (order.type == TradeType.BUY) account.copy(
            holdings = account.holdings + BuyLot(id, price, order.qty, order.qty, now),
            trades = account.trades + TradeRecord(id, TradeType.BUY, price, order.qty, time = now, lotId = id)
        ) else {
            val matched = matchSellLots(account.holdings, order.qty, price, order.match!!)
            account.copy(holdings = matched.remaining,
                trades = account.trades + TradeRecord(id, TradeType.SELL, price, order.qty,
                    profit = matched.profit, time = now, soldLots = matched.soldLots))
        }
    }

    @Test
    fun userScenarioSellsAt55Or45ThenBuysAt40AndSellsAt45Or35() {
        var account = account()
        var result = evaluate(account)
        assertEquals(55.0, result.upward.price!!, 0.000001)
        assertEquals(45.0, result.downward.price!!, 0.000001)
        assertEquals(TradeType.SELL, result.downward.type)
        assertEquals(SellMatch.HIGHEST, result.downward.match)

        account = record(account, result.downward)
        assertEquals(400, account.totalQty)
        result = evaluate(account)
        assertFalse(result.overThreshold)
        assertEquals(55.0, result.upward.price!!, 0.000001)
        assertEquals(40.0, result.downward.price!!, 0.000001)
        assertEquals(TradeType.BUY, result.downward.type)

        account = record(account, result.downward)
        assertEquals(500, account.totalQty)
        result = evaluate(account)
        assertTrue(result.overThreshold)
        assertEquals(45.0, result.upward.price!!, 0.000001)
        assertEquals(SellMatch.LOWEST, result.upward.match)
        assertEquals(35.0, result.downward.price!!, 0.000001)
        assertEquals(TradeType.SELL, result.downward.type)
        assertEquals(SellMatch.HIGHEST, result.downward.match)
    }

    @Test
    fun belowThresholdBuysOneLotAtEveryStepIncludingEquality() {
        var account = account(qty = 100)
        for (price in listOf(45.0, 40.0, 35.0, 30.0)) {
            val order = evaluate(account).downward
            assertEquals(TradeType.BUY, order.type)
            assertEquals(100, order.qty)
            assertEquals(price, order.price!!, 0.000001)
            account = record(account, order)
        }
        assertEquals(500, account.totalQty)
        val order = evaluate(account).downward
        assertEquals(TradeType.SELL, order.type)
        assertEquals(25.0, order.price!!, 0.000001)
    }

    @Test
    fun reboundSaleRemovesTheLowestLotAndRecomputesBothPoints() {
        var account = account()
        account = record(account, evaluate(account).downward)
        account = record(account, evaluate(account).downward)
        account = record(account, evaluate(account).upward)

        val result = evaluate(account)
        assertEquals(400, account.totalQty)
        assertEquals(50.0, result.lowestBuyPrice!!, 0.000001)
        assertEquals(55.0, result.upward.price!!, 0.000001)
        assertEquals(40.0, result.downward.price!!, 0.000001)
    }

    @Test
    fun costThresholdUsesPurchaseCostAndStillBuysAtEquality() {
        var account = account(plan = config.copy(thresholdMode = PlanThresholdMode.COST, threshold = 20_000.0))
        assertTrue(evaluate(account).overThreshold)
        account = record(account, evaluate(account).downward)
        account = account.copy(currentPrice = 1.0)
        assertEquals(20_000.0, account.totalCost, 0.000001)
        assertEquals(TradeType.BUY, evaluate(account).downward.type)
        account = record(account, evaluate(account).downward)
        assertEquals(24_000.0, account.totalCost, 0.000001)
        assertEquals(TradeType.SELL, evaluate(account).downward.type)
    }

    @Test
    fun percentageStepsUseTheSeparateBuyLotAndTradeAnchors() {
        var account = account(plan = config.copy(stepMode = PlanStepMode.PERCENT, step = 10.0))
        assertEquals(55.0, evaluate(account).upward.price!!, 0.000001)
        account = record(account, evaluate(account).downward)
        assertEquals(55.0, evaluate(account).upward.price!!, 0.000001)
        assertEquals(40.5, evaluate(account).downward.price!!, 0.000001)
    }

    @Test
    fun quoteChangesAndGapsNeverAdvanceTheReferenceOrAssumeTrades() {
        val account = account()
        val changed = account.copy(currentPrice = 20.0)
        val result = evaluate(changed)
        assertEquals(50.0, result.referencePrice, 0.000001)
        assertEquals(45.0, result.downward.price!!, 0.000001)
        assertEquals(100, result.downward.qty)
        assertTrue(result.downward.reached)
        assertEquals(account.trades, changed.trades)
        assertEquals(account.holdings, changed.holdings)
    }

    @Test
    fun configuredReferenceOverridesOlderTradePrices() {
        val account = account().copy(tradePlan = config.copy(referencePrice = 42.0))
        assertEquals(37.0, evaluate(account).downward.price!!, 0.000001)
        assertEquals(55.0, evaluate(account).upward.price!!, 0.000001)
    }

    @Test
    fun undoingATradeReturnsToThePreviousReference() {
        val original = account()
        val sold = record(original, evaluate(original).downward)
        assertEquals(45.0, evaluate(sold).referencePrice, 0.000001)
        val undone = sold.copy(holdings = original.holdings, trades = sold.trades.dropLast(1))
        assertEquals(50.0, evaluate(undone).referencePrice, 0.000001)
    }

    @Test
    fun sameDayASharesCanBeRecordedWhileBrokerAvailabilityRemainsZero() {
        val original = account()
        val frozen = original.copy(holdings = original.holdings.map { it.copy(time = now) },
            trades = original.trades.map { it.copy(time = now) })
        assertEquals(0, brokerSellableQty(frozen.holdings, frozen.trades, now))
        assertNull(evaluate(frozen).upward.block)
        assertNull(evaluate(frozen).downward.block)
        assertNull(evaluate(frozen.copy(tradePlan = config.copy(threshold = 500.0))).downward.block)
    }

    @Test
    fun sameDayTestPositionCanRecordTheReachedPercentageSellPoint() {
        val prices = listOf(13.0, 12.0, 11.0)
        val account = StockAccount(
            Stock("601857", "Example", "sh"),
            holdings = prices.mapIndexed { index, price ->
                BuyLot(index + 1L, price, 100, 100, now)
            },
            trades = prices.mapIndexed { index, price ->
                TradeRecord(index + 1L, TradeType.BUY, price, 100, time = now, lotId = index + 1L)
            },
            currentPrice = 13.92,
            tradePlan = config.copy(thresholdMode = PlanThresholdMode.COST, threshold = 3600.0,
                stepMode = PlanStepMode.PERCENT, step = 2.0, referencePrice = 11.0, startAfterTradeId = 3L)
        )
        val result = evaluate(account)
        assertEquals(0, brokerSellableQty(account.holdings, account.trades, now))
        assertEquals(11.22, result.upward.price!!, 0.000001)
        assertEquals(100, result.upward.qty)
        assertTrue(result.upward.reached)
        assertNull(result.upward.block)
        assertEquals(TradeType.BUY, result.downward.type)
    }

    @Test
    fun t0MarketsDoNotFreezeSameDayHoldings() {
        val account = account().copy(stock = Stock("TEST", "Example", "us"),
            holdings = listOf(BuyLot(1, 50.0, 500, 500, now)), trades = emptyList())
        assertNull(evaluate(account).upward.block)
        assertNull(evaluate(account).downward.block)
    }

    @Test
    fun anEmptyPositionOnlyHasABuyPoint() {
        val empty = account().copy(holdings = emptyList(), trades = emptyList())
        val result = evaluate(empty)
        assertEquals(PlanBlock.NO_HOLDINGS, result.upward.block)
        assertEquals(TradeType.BUY, result.downward.type)
        assertEquals(100, result.downward.qty)
        assertNull(result.downward.block)
    }

    @Test
    fun lastPartialLotCanBeSoldWithoutCreatingANegativePosition() {
        val account = account(qty = 50, plan = config.copy(threshold = 0.0))
        val order = evaluate(account).downward
        assertEquals(50, order.qty)
        assertEquals(0, order.resultingQty)
        assertEquals(0.0, order.resultingCost!!, 0.000001)
    }

    @Test
    fun estimatesIncludeSellFeesAndAllocatedBuyFees() {
        val base = account()
        val account = base.copy(holdings = base.holdings.map { it.copy(fee = 10.0) })
        val fees = FeeConfig()
        val order = evaluateTradePlan(account, fees)!!.upward
        assertEquals(500.0 - 2.0 - fees.sellFee(5500.0), order.estimatedNetProfit!!, 0.000001)
        assertEquals(20_000.0, order.resultingCost!!, 0.000001)
    }

    @Test
    fun invalidOrMissingConfigurationsDisableOnlyThePlan() {
        assertNull(evaluateTradePlan(account().copy(tradePlan = null), zeroFees))
        for (invalid in listOf(config.copy(step = 0.0), config.copy(threshold = Double.NaN),
            config.copy(unitQty = 0), config.copy(stepMode = PlanStepMode.PERCENT, step = 100.0),
            config.copy(threshold = 1.5), config.copy(referencePrice = Double.POSITIVE_INFINITY))) {
            assertNull(evaluateTradePlan(account(plan = invalid), zeroFees))
        }
    }

    @Test
    fun nonPositiveOrSubTickPricesCannotTriggerOrders() {
        assertEquals(PlanBlock.INVALID_PRICE, evaluate(account(plan = config.copy(step = 60.0))).downward.block)
        val subTick = evaluate(account(plan = config.copy(step = 0.0001)))
        assertEquals(PlanBlock.INVALID_PRICE, subTick.upward.block)
        assertEquals(PlanBlock.INVALID_PRICE, subTick.downward.block)
    }

    @Test
    fun equalityTriggersBothDirectionsAndMissingQuotesTriggerNeither() {
        val base = account()
        assertFalse(evaluate(base).upward.reached)
        assertFalse(evaluate(base).downward.reached)
        assertTrue(evaluate(base.copy(currentPrice = 55.0)).upward.reached)
        assertTrue(evaluate(base.copy(currentPrice = 45.0)).downward.reached)
    }
}
