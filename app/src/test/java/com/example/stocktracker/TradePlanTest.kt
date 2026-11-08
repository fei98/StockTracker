package com.example.stocktracker

import org.junit.Assert.*
import org.junit.Test

class TradePlanTest {
    private val now = 1_794_096_000_000L
    private val zeroFees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val config = TradePlanConfig(lowerCost = 10_000.0, upperCost = 30_000.0,
        orderAmount = 4_000.0, stepPercent = 10.0, referencePrice = 10.0,
        capital = 50_000.0, initialCash = 40_000.0)

    private fun account(lots: List<BuyLot> = listOf(BuyLot(1, 10.0, 2000, 2000)),
                        plan: TradePlanConfig = config, stock: Stock = Stock("000001", "Example", "sz")): StockAccount =
        startTradePlan(StockAccount(stock, holdings = lots, currentPrice = plan.referencePrice), plan, 10_000L)

    private fun result(a: StockAccount) = evaluateTradePlan(a, zeroFees, now)!!
    private fun at(a: StockAccount, price: Double) = observeTradePlanQuote(a, price)
    private fun nextId(a: StockAccount) = maxOf(a.tradePlan!!.activationId,
        a.trades.maxOfOrNull { it.id } ?: 0L, a.holdings.maxOfOrNull { it.id } ?: 0L,
        a.planAdjustments.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun fill(a: StockAccount, type: TradeType, price: Double, qty: Int,
                     match: SellMatch = SellMatch.LOWEST, grid: Int? = null): StockAccount {
        val id = nextId(a)
        val fee = if (type == TradeType.BUY) zeroFees.buyFee(price * qty) else zeroFees.sellFee(price * qty)
        val sale = if (type == TradeType.SELL) matchSellLots(a.holdings, qty, price, match) else null
        val trade = TradeRecord(if (type == TradeType.BUY) id + 1 else id, type, price, qty,
            profit = sale?.profit ?: 0.0, time = now, fee = fee,
            costFee = sale?.costFee ?: 0.0, lotId = id.takeIf { type == TradeType.BUY },
            soldLots = sale?.soldLots ?: emptyList())
        val holdings = sale?.remaining ?: (a.holdings + BuyLot(id, price, qty, qty, time = now))
        return afterTradePlanFill(a, a.copy(holdings = holdings, trades = a.trades + trade), trade, grid)
    }

    private fun fill(a: StockAccount, order: TradePlanOrder): StockAccount {
        assertNull(order.block)
        return fill(a, order.type!!, order.price!!, order.qty, order.match ?: SellMatch.LOWEST, order.gridIndex)
    }

    @Test fun parametersHaveNoImplicitEconomicDefaults() {
        val unset = TradePlanConfig(referencePrice = 10.0)
        assertFalse(unset.isValid)
        assertEquals(0.0, unset.orderAmount, 0.0)
        assertNull(evaluateTradePlan(StockAccount(Stock("002396", "Example", "sz"), tradePlan = unset), zeroFees, now))
    }

    @Test fun fixedGridUsesOneAnchorAndReciprocalDownwardSteps() {
        val a = account()
        assertEquals(11.0, tradePlanGridPrice(a, 1)!!, 1e-9)
        assertEquals(9.09, tradePlanGridPrice(a, -1)!!, 1e-9)
        assertEquals(12.1, tradePlanGridPrice(a, 2)!!, 1e-9)
        assertEquals(20_000.0, result(a).middleCost, 1e-9)
        assertEquals(1, result(a).occupiedGridCount)
    }

    @Test fun aReachedPointRemainsVisibleAfterTheCursorVisitsIt() {
        val a = at(account(), 11.0)
        val order = result(a).upward
        assertEquals(1, a.tradePlanState!!.cursorGrid)
        assertEquals(1, order.gridIndex)
        assertTrue(order.reached)
        assertFalse(result(a).downward.reached)
        assertEquals(TradeType.SELL, order.type)
        assertEquals(400, order.qty)
    }

    @Test fun repeatedQuotesDoNotMoveAnchorOrConsumeThePendingPoint() {
        val a = at(account(), 11.0)
        val repeated = at(a, 11.1)
        assertEquals(a.tradePlanState, repeated.tradePlanState)
        assertEquals(10.0, repeated.tradePlan!!.referencePrice, 0.0)
        assertTrue(result(repeated).upward.reached)
    }

    @Test fun aGapVisitsOnlyTheFarthestPointWithoutInventingFills() {
        val a = at(account(), 20.0)
        assertTrue(a.tradePlanState!!.cursorGrid > 1)
        assertEquals(a.tradePlanState!!.cursorGrid, result(a).upward.gridIndex)
        assertTrue(a.trades.isEmpty())
        assertTrue(result(a).upward.reached)
    }

    @Test fun tickCollapsedLevelsDoNotProduceSpuriousCrossings() {
        val a = account(plan = config.copy(stepPercent = 0.00000001))
        val quoted = at(a, 10.0)
        assertEquals(a.tradePlanState, quoted.tradePlanState)
        assertEquals(PlanBlock.INVALID_PRICE, result(quoted).upward.block)
    }

    @Test fun completedFillConsumesTheSignalAndKeepsTheOriginalAnchor() {
        val a = at(account(), 11.0)
        val b = fill(a, result(a).upward)
        assertNull(b.tradePlanState!!.pendingDirection)
        assertFalse(result(b).upward.reached)
        assertEquals(12.1, result(b).upward.triggerPrice!!, 1e-9)
        assertEquals(10.0, b.tradePlan!!.referencePrice, 0.0)
        assertEquals(a.tradePlanState, b.trades.last().planBefore)
        assertEquals(44_400.0, b.tradePlanState!!.cash, 1e-9)
    }

    @Test fun downwardBuysCrossUpperInFullThenReductionLastsUntilMiddle() {
        var a = account(listOf(BuyLot(1, 10.0, 2800, 2800)))
        a = at(a, 9.09)
        a = fill(a, result(a).downward)
        assertEquals(PlanPhase.DOWN_REDUCTION, a.tradePlanState!!.phase)
        assertTrue(a.totalCost > config.upperCost)
        a = at(a, 8.26)
        val reduction = result(a).downward
        assertEquals(TradeType.SELL, reduction.type)
        assertEquals(SellMatch.HIGHEST, reduction.match)
        a = fill(a, reduction)
        assertEquals(PlanPhase.DOWN_REDUCTION, a.tradePlanState!!.phase)
        a = at(a, 7.51)
        a = fill(a, result(a).downward)
        assertEquals(PlanPhase.DOWN_REDUCTION, a.tradePlanState!!.phase)
        a = at(a, 6.83)
        a = fill(a, result(a).downward)
        assertEquals(PlanPhase.NORMAL, a.tradePlanState!!.phase)
        assertTrue(a.totalCost <= result(a).middleCost)
        a = at(a, 6.21)
        assertEquals(TradeType.BUY, result(a).downward.type)
    }

    @Test fun upwardSalesCrossLowerInFullThenFollowingLastsUntilMiddle() {
        var a = at(account(listOf(BuyLot(1, 10.0, 1000, 1000))), 11.0)
        a = fill(a, result(a).upward)
        assertEquals(6000.0, a.totalCost, 1e-9)
        assertEquals(PlanPhase.UP_FOLLOW, a.tradePlanState!!.phase)
        for (price in listOf(12.1, 13.31, 14.64)) {
            a = at(a, price)
            assertEquals(TradeType.BUY, result(a).upward.type)
            a = fill(a, result(a).upward)
            assertEquals(PlanPhase.UP_FOLLOW, a.tradePlanState!!.phase)
        }
        a = at(a, 16.11)
        a = fill(a, result(a).upward)
        assertEquals(PlanPhase.NORMAL, a.tradePlanState!!.phase)
        a = at(a, 17.72)
        assertEquals(TradeType.SELL, result(a).upward.type)
    }

    @Test fun normalDirectionDoesNotSwitchAtMiddle() {
        val a = at(account(listOf(BuyLot(1, 10.0, 1800, 1800))), 11.0)
        val b = fill(a, result(a).upward)
        assertEquals(14_000.0, b.totalCost, 1e-9)
        assertEquals(PlanPhase.NORMAL, b.tradePlanState!!.phase)
    }

    @Test fun quoteReboundsDoNotClearTheReductionPhase() {
        var a = account(listOf(BuyLot(1, 20.0, 2000, 2000)))
        a = a.copy(tradePlanState = a.tradePlanState!!.copy(phase = PlanPhase.DOWN_REDUCTION))
        a = at(a, 11.0)
        assertEquals(PlanBlock.INSUFFICIENT_PROFITABLE_SHARES, result(a).upward.block)
        assertEquals(PlanPhase.DOWN_REDUCTION, a.tradePlanState!!.phase)
        a = at(a, 10.0)
        assertEquals(TradeType.SELL, result(a).downward.type)
        assertEquals(SellMatch.HIGHEST, result(a).downward.match)
    }

    @Test fun profitableReboundCanAlsoEndReductionAtMiddle() {
        var a = account(listOf(BuyLot(1, 6.0, 4000, 4000)))
        a = a.copy(tradePlanState = a.tradePlanState!!.copy(phase = PlanPhase.DOWN_REDUCTION))
        a = at(a, 11.0)
        a = fill(a, result(a).upward)
        assertEquals(PlanPhase.DOWN_REDUCTION, a.tradePlanState!!.phase)
        a = at(a, 12.1)
        a = fill(a, result(a).upward)
        assertEquals(PlanPhase.NORMAL, a.tradePlanState!!.phase)
    }

    @Test fun followingPullbackWaitsOnOccupiedGridAndCanBuyAnEmptyGrid() {
        var a = at(account(listOf(BuyLot(1, 10.0, 1000, 1000))), 11.0)
        a = fill(a, result(a).upward)
        a = at(a, 12.1)
        a = fill(a, result(a).upward)
        a = at(a, 10.0)
        assertEquals(PlanBlock.OCCUPIED_GRID, result(a).downward.block)
        assertEquals(PlanPhase.UP_FOLLOW, a.tradePlanState!!.phase)
        a = at(a, 9.09)
        assertEquals(TradeType.BUY, result(a).downward.type)
        assertEquals(PlanReason.PULLBACK_BUY, result(a).downward.reason)
    }

    @Test fun followingRequiresBreakoutOfAllRemainingBuyPrices() {
        var a = account(listOf(BuyLot(1, 20.0, 500, 500)))
        a = a.copy(tradePlanState = a.tradePlanState!!.copy(phase = PlanPhase.UP_FOLLOW))
        a = at(a, 11.0)
        assertEquals(PlanBlock.NO_BREAKOUT, result(a).upward.block)
    }

    @Test fun partialSaleKeepsGridOccupiedAndFinalSaleReleasesIt() {
        val a = account(listOf(BuyLot(1, 10.0, 1000, 1000)))
        val partial = fill(a, TradeType.SELL, 11.0, 100)
        assertEquals(0, partial.tradePlanState!!.lotGrids[1])
        val complete = fill(partial, TradeType.SELL, 11.0, 900)
        assertFalse(1L in complete.tradePlanState!!.lotGrids)
        assertEquals(0, result(complete).occupiedGridCount)
    }

    @Test fun actualManualBuysCanShareAnOccupiedGridWithoutLosingItsIdentity() {
        val a = fill(account(), TradeType.BUY, 10.0, 100)
        assertEquals(2, a.tradePlanState!!.lotGrids.size)
        assertEquals(setOf(0), a.tradePlanState!!.lotGrids.values.toSet())
        assertEquals(1, result(a).occupiedGridCount)
    }

    @Test fun wholeOrdersAreNotClippedToCostBoundaries() {
        val a = at(account(plan = config.copy(upperCost = 20_400.0)), 9.09)
        val order = result(a).downward
        assertEquals(400, order.qty)
        assertTrue(order.resultingCost!! > 20_400)
        val b = at(account(plan = config.copy(lowerCost = 19_000.0)), 11.0)
        assertEquals(400, result(b).upward.qty)
        assertTrue(result(b).upward.resultingCost!! < 19_000)
    }

    @Test fun cashShortfallDoesNotInventAnUpperBoundaryTransition() {
        val a = at(account(plan = config.copy(initialCash = 500.0)), 9.09)
        assertEquals(PlanBlock.INSUFFICIENT_CASH, result(a).downward.block)
        assertEquals(PlanPhase.NORMAL, a.tradePlanState!!.phase)
    }

    @Test fun negativeManualCashBlocksSuggestionsButPreservesActualLedgerFill() {
        val a = fill(account(plan = config.copy(initialCash = 1.0)), TradeType.BUY, 10.0, 100)
        assertTrue(a.tradePlanState!!.cash < 0)
        assertEquals(1, a.trades.size)
        assertEquals(PlanBlock.INSUFFICIENT_CASH, result(a).upward.block)
    }

    @Test fun decimalCashExactlySpentDoesNotBecomeANegativeRoundingResidue() {
        val a = fill(account(plan = config.copy(initialCash = 110.0)), TradeType.BUY, 1.1, 100)
        assertEquals(0.0, a.tradePlanState!!.cash, 0.0)
        val adjusted = applyTradePlanAdjustment(account(plan = config.copy(initialCash = 0.3)),
            PlanAdjustmentInput(-0.3, 0, 0.0, 0.0, 10.0), now, 20_000L)
        assertEquals(0.0, adjusted.tradePlanState!!.cash, 0.0)
    }

    @Test fun profitableQuantityMustCoverTheWholeSuggestedSale() {
        val a = at(account(listOf(BuyLot(1, 5.0, 100, 100), BuyLot(2, 20.0, 1000, 1000))), 11.0)
        assertEquals(PlanBlock.INSUFFICIENT_PROFITABLE_SHARES, result(a).upward.block)
        assertNull(result(a).upward.type)
    }

    @Test fun suggestionsRespectCalendarTPlusOne() {
        val lot = BuyLot(1, 10.0, 2000, 2000, time = now)
        val a = at(account(listOf(lot)), 11.0)
        assertEquals(PlanBlock.INSUFFICIENT_SETTLED_SHARES, result(a).upward.block)
        assertEquals(TradeType.SELL, evaluateTradePlan(a, zeroFees, now + 86_400_000L)!!.upward.type)
        assertEquals(TradeType.SELL, result(at(account(listOf(lot), stock = Stock("EX", "Example", "us")), 11.0)).upward.type)
    }

    @Test fun targetAmountStillRequiresOneHundredSharesAtMinimum() {
        val a = at(account(plan = config.copy(orderAmount = 1.0)), 9.09)
        assertEquals(100, result(a).downward.qty)
        assertTrue(result(a).downward.price!! * 100 > 1)
    }

    @Test fun settingsApplyGenericallyToEveryStock() {
        val plan = config.copy(lowerCost = 0.0, upperCost = 1000.0, orderAmount = 500.0,
            stepPercent = 3.0, referencePrice = 2.0, capital = 3000.0, initialCash = 2000.0)
        val a = at(account(listOf(BuyLot(1, 2.0, 100, 100)), plan), 1.94)
        assertEquals(300, result(a).downward.qty)
        assertEquals(1.94, result(a).downward.triggerPrice!!, 1e-9)
    }

    @Test fun marketTicksAndFundFeesAreInstrumentSpecific() {
        val plan = config.copy(stepPercent = 8.0, referencePrice = 2.03)
        val stock = account(listOf(BuyLot(1, 2.0, 5000, 5000)), plan)
        val fund = account(listOf(BuyLot(1, 2.0, 5000, 5000)), plan, Stock("159915", "Example", "sz"))
        assertEquals(2.19, tradePlanGridPrice(stock, 1)!!, 1e-9)
        assertEquals(2.192, tradePlanGridPrice(fund, 1)!!, 1e-9)
        assertEquals(FeeConfig().buyFee(4000.0), effectiveTradeFees(stock.stock, FeeConfig()).buyFee(4000.0), 1e-9)
        assertEquals(5.0, effectiveTradeFees(fund.stock, FeeConfig()).sellFee(4000.0), 1e-9)
    }

    @Test fun previewIncludesBothSellFeesAndHistoricalBuyFees() {
        val a = at(account(listOf(BuyLot(1, 10.0, 2000, 2000, fee = 20.0))), 11.0)
        val order = evaluateTradePlan(a, FeeConfig(), now)!!.upward
        val fee = FeeConfig().sellFee(4400.0)
        assertEquals(fee, order.estimatedFee!!, 1e-9)
        assertEquals(400.0 - 4.0 - fee, order.estimatedNetProfit!!, 1e-9)
    }

    @Test fun pendingQuoteReturnRemovesReachedStatusWithoutChangingPhase() {
        val a = at(at(account(), 11.0), 10.8)
        assertFalse(result(a).upward.reached)
        assertEquals(PlanPhase.NORMAL, a.tradePlanState!!.phase)
    }

    @Test fun strategyFillValidationRejectsStaleOrUnreachedOrders() {
        val a = at(account(), 11.0)
        val order = result(a).upward
        assertNull(validateTradePlanFill(a, order, 11.0, 100, zeroFees, now))
        assertNotNull(validateTradePlanFill(a, order.copy(reached = false), 11.0, 100, zeroFees, now))
        assertNotNull(validateTradePlanFill(at(a, 12.1), order, 11.0, 100, zeroFees, now))
        assertNotNull(validateTradePlanFill(at(a, 10.8), order, 11.0, 100, zeroFees, now))
        assertNotNull(validateTradePlanFill(a, order, 9.0, 100, zeroFees, now))
        assertNotNull(validateTradePlanFill(a, order.copy(type = TradeType.BUY), 11.0, 100, zeroFees, now))
    }

    @Test fun strategyValidationUsesActualPartialFillCashAndQuantity() {
        val a = at(account(plan = config.copy(initialCash = 4000.0)), 9.09)
        val order = result(a).downward
        assertNull(validateTradePlanFill(a, order, 9.1, 37, zeroFees, now))
        assertNotNull(validateTradePlanFill(a, order, 9.1, 1000, zeroFees, now))
        assertNotNull(validateTradePlanFill(a, order, Double.NaN, 100, zeroFees, now))
    }

    @Test fun invalidRuntimeAndMissingMappingsCannotGenerateOrders() {
        val a = account()
        assertNull(evaluateTradePlan(a.copy(tradePlanState = a.tradePlanState!!.copy(cash = Double.NaN)), zeroFees, now))
        assertNull(evaluateTradePlan(a.copy(tradePlanState = a.tradePlanState!!.copy(lotGrids = emptyMap())), zeroFees, now))
        assertNull(evaluateTradePlan(a.copy(tradePlan = a.tradePlan!!.copy(legacyJson = "{}")), zeroFees, now))
        assertNull(evaluateTradePlan(a.copy(tradePlanState = a.tradePlanState!!.copy(activationId = 3)), zeroFees, now))
    }

    @Test fun invalidHoldingsAndQuantityOverflowAreBlocked() {
        val a = account()
        val bad = a.copy(holdings = listOf(BuyLot(1, Double.NaN, 2000, 2000)))
        assertEquals(PlanBlock.INVALID_HOLDINGS, result(bad).upward.block)
        val huge = at(account(plan = config.copy(orderAmount = 1e308, initialCash = 1e308)), 9.09)
        assertEquals(PlanBlock.INVALID_PRICE, result(huge).downward.block)
    }

    @Test fun adjustmentPreservesCostAndUnallocatedBuyFeesWhileAddingActualCash() {
        var a = account(listOf(BuyLot(1, 10.0, 1000, 600, fee = 10.0), BuyLot(2, 20.0, 100, 100, fee = 3.0)))
        a = a.copy(tradePlanState = a.tradePlanState!!.copy(phase = PlanPhase.DOWN_REDUCTION))
        val input = PlanAdjustmentInput(175.0, 210, 0.25, 30.0, 7.5)
        val b = applyTradePlanAdjustment(a, input, now, 20_000L)
        assertEquals(910, b.totalQty)
        assertEquals(a.totalCost, b.totalCost, 1e-8)
        assertEquals(a.heldBuyFee, b.heldBuyFee, 1e-9)
        assertEquals(40_175.0, b.tradePlanState!!.cash, 1e-9)
        assertEquals(PlanPhase.DOWN_REDUCTION, b.tradePlanState!!.phase)
        assertEquals(a.tradePlanState!!.lotGrids, b.tradePlanState!!.lotGrids)
        assertEquals(8.27, tradePlanGridPrice(b, 1)!!, 1e-9)
        assertNull(b.tradePlanState!!.pendingDirection)
        assertEquals(a.holdings, b.planAdjustments.single().beforeHoldings)
        assertEquals(a.tradePlanState, b.planAdjustments.single().beforePlanState)
    }

    @Test fun bonusSharesAreSellableTodayButOriginalSameDayBuysStayFrozen() {
        val lots = listOf(BuyLot(1, 10.0, 100, 100, now - 86_400_000L), BuyLot(2, 10.0, 100, 100, now))
        val a = account(lots)
        val b = applyTradePlanAdjustment(a, PlanAdjustmentInput(50.0, 60, 0.25, 30.0, 7.5), now, 20_000L)
        assertEquals(160, tradePlanSellableQty(b, now))
        assertEquals(260, tradePlanSellableQty(b, now + 86_400_000L))
        val bought = fill(b, TradeType.BUY, 7.0, 100)
        assertEquals(160, tradePlanSellableQty(bought, now))
        val sold = fill(bought, TradeType.SELL, 8.0, 100)
        assertEquals(60, tradePlanSellableQty(sold, now))
        assertEquals(260, tradePlanSellableQty(sold, now + 86_400_000L))
    }

    @Test fun largestRemainderAllocatesActualIntegerBonusWithoutLosingShares() {
        val a = account(listOf(BuyLot(1, 10.0, 100, 100), BuyLot(2, 10.0, 200, 200)))
        val b = applyTradePlanAdjustment(a, PlanAdjustmentInput(0.0, 1, 0.0, 0.333, 10.0), now, 20_000L)
        assertEquals(301, b.totalQty)
        assertEquals(100, b.holdings.first { it.id == 1L }.remainingQty)
        assertEquals(201, b.holdings.first { it.id == 2L }.remainingQty)
        assertEquals(a.totalCost, b.totalCost, 1e-8)
    }

    @Test fun adjustmentValidationRejectsInvalidInputsAndCannotOverdrawCash() {
        val a = account()
        val okay = PlanAdjustmentInput(-10.0, 0, 0.0, 0.0, 10.0)
        assertNull(validateTradePlanAdjustment(a, okay))
        for (input in listOf(okay.copy(netCash = -50_000.0), okay.copy(addedShares = -1),
            okay.copy(cashReference = Double.NaN), okay.copy(newPrice = 0.0))) {
            assertNotNull(validateTradePlanAdjustment(a, input))
        }
        assertNotNull(validateTradePlanAdjustment(account(emptyList()), okay.copy(addedShares = 100)))
    }
}
