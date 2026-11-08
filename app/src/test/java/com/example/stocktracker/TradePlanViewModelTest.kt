package com.example.stocktracker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TradePlanViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val now = 1_783_080_000_000L
    private val day = 86_400_000L
    private val fees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val stock = Stock("002396", "星网锐捷", "sz")
    private fun config() = TradePlanConfig(lowerCost = 10_000.0, upperCost = 30_000.0,
        orderAmount = 8_000.0, stepPercent = 8.0, referencePrice = 10.0,
        capital = 50_000.0, initialCash = 30_000.0)

    private fun vm(): Pair<StockViewModel, FakeStorage> {
        val storage = FakeStorage().apply {
            loaded = StockState(listOf(StockAccount(stock,
                holdings = listOf(BuyLot(1, 10.0, 2000, 2000, now - day)), currentPrice = 10.0)), 0)
        }
        val vm = StockViewModel(storage, initialFeeConfig = fees)
        assertTrue(vm.configureTradePlan(config()))
        return vm to storage
    }

    @Test fun eachStockKeepsItsOwnEditableParametersWithoutAutomaticRecommendation() {
        val (vm, _) = vm()
        vm.addStock(QuoteResult("000001", "Other", 12.0, "sz"))
        assertNull(vm.state.value.selected!!.tradePlan)
        val custom = config().copy(lowerCost = 3000.0, upperCost = 21000.0,
            orderAmount = 2500.0, stepPercent = 5.0, referencePrice = 12.0, initialCash = 18000.0)
        assertTrue(vm.configureTradePlan(custom))
        vm.selectStock(0)
        assertEquals(8.0, vm.state.value.selected!!.tradePlan!!.stepPercent, 0.0)
        vm.selectStock(1)
        assertEquals(5.0, vm.state.value.selected!!.tradePlan!!.stepPercent, 0.0)
        assertEquals(2500.0, vm.state.value.selected!!.tradePlan!!.orderAmount, 0.0)
    }

    @Test fun quoteCursorAndPendingSignalSurviveARealJsonRestart() {
        val (vm, storage) = vm()
        vm.setCurrentPrice(10.8)
        val before = vm.state.value.selected!!
        assertTrue(evaluateTradePlan(before, fees, now)!!.upward.reached)
        val loaded = parseState(buildState(storage.saved.last()))
        val restarted = StockViewModel(FakeStorage().apply { this.loaded = loaded }, initialFeeConfig = fees)
        assertEquals(before.tradePlanState, restarted.state.value.selected!!.tradePlanState)
        assertTrue(evaluateTradePlan(restarted.state.value.selected!!, fees, now)!!.upward.reached)
    }

    @Test fun planFillConsumesSignalAndUndoRestoresCashPhaseAndOccupiedGrids() {
        val (vm, _) = vm()
        vm.setCurrentPrice(10.8)
        val before = vm.state.value.selected!!
        val order = evaluateTradePlan(before, fees, now)!!.upward
        assertTrue(vm.recordPlanTrade(order, 10.8, order.qty, now))
        val after = vm.state.value.selected!!
        assertNotNull(after.trades.last().planBefore)
        assertEquals(37_560.0, after.tradePlanState!!.cash, 1e-7)
        assertFalse(evaluateTradePlan(after, fees, now)!!.upward.reached)
        vm.undoLastTrade()
        val restored = vm.state.value.selected!!
        assertEquals(before.holdings, restored.holdings)
        assertEquals(before.tradePlanState!!.cash, restored.tradePlanState!!.cash, 1e-7)
        assertEquals(before.tradePlanState.phase, restored.tradePlanState.phase)
        assertEquals(before.tradePlanState.lotGrids, restored.tradePlanState.lotGrids)
    }

    @Test fun staleStrategyOrderCannotCreateADuplicateFill() {
        val (vm, _) = vm()
        vm.setCurrentPrice(10.8)
        val order = evaluateTradePlan(vm.state.value.selected!!, fees, now)!!.upward
        assertTrue(vm.recordPlanTrade(order, 10.8, order.qty, now))
        val trades = vm.state.value.selected!!.trades
        assertFalse(vm.recordPlanTrade(order, 10.8, order.qty, now))
        assertEquals(trades, vm.state.value.selected!!.trades)
    }

    @Test fun actualPartialSaleKeepsTheOriginalGridOccupied() {
        val (vm, _) = vm()
        vm.setCurrentPrice(10.8)
        val order = evaluateTradePlan(vm.state.value.selected!!, fees, now)!!.upward
        assertTrue(vm.recordPlanTrade(order, 10.8, 100, now))
        val account = vm.state.value.selected!!
        assertEquals(1900, account.holdings.single().remainingQty)
        assertTrue(account.tradePlanState!!.lotGrids.containsKey(1L))
    }

    @Test fun manualActualFillUpdatesStrategyCashWithoutForbiddingAnOccupiedGrid() {
        val (vm, _) = vm()
        assertTrue(vm.buy(10.0, 100, now))
        val account = vm.state.value.selected!!
        assertEquals(2100, account.totalQty)
        assertEquals(29000.0, account.tradePlanState!!.cash, 1e-7)
        assertEquals(1, account.tradePlanState.lotGrids.values.toSet().size)
        vm.undoLastTrade()
        assertEquals(30000.0, vm.state.value.selected!!.tradePlanState!!.cash, 1e-7)
    }

    @Test fun reverseStagePersistsUntilTheMiddleInsteadOfNewlyFreedHeadroom() {
        val (vm, _) = vm()
        var at = now
        repeat(2) {
            val before = vm.state.value.selected!!
            val target = evaluateTradePlan(before, fees, at)!!.downward.triggerPrice!!
            vm.setCurrentPrice(target)
            val order = evaluateTradePlan(vm.state.value.selected!!, fees, at)!!.downward
            assertTrue(vm.recordPlanTrade(order, target, order.qty, at))
            at += day
        }
        assertEquals(PlanPhase.DOWN_REDUCTION, vm.state.value.selected!!.tradePlanState!!.phase)
        val target = evaluateTradePlan(vm.state.value.selected!!, fees, at)!!.downward.triggerPrice!!
        vm.setCurrentPrice(target)
        val order = evaluateTradePlan(vm.state.value.selected!!, fees, at)!!.downward
        assertEquals(TradeType.SELL, order.type)
        assertTrue(vm.recordPlanTrade(order, target, order.qty, at))
        val account = vm.state.value.selected!!
        assertTrue(account.totalCost < 30000.0 && account.totalCost > 20000.0)
        assertEquals(PlanPhase.DOWN_REDUCTION, account.tradePlanState!!.phase)
    }

    @Test fun corporateActionAndFollowingTradeUndoInCorrectChronologicalOrder() {
        val (vm, storage) = vm()
        val before = vm.state.value.selected!!
        assertTrue(vm.registerPlanAdjustment(PlanAdjustmentInput(250.0, 600, 0.25, 30.0, 7.5), now))
        val adjusted = vm.state.value.selected!!
        assertEquals(2600, adjusted.totalQty)
        assertEquals(before.totalCost, adjusted.totalCost, 1e-7)
        assertEquals(30250.0, adjusted.tradePlanState!!.cash, 1e-7)
        assertEquals(250.0, adjusted.realizedPnlFee, 1e-7)
        assertEquals(2600, tradePlanSellableQty(adjusted, now))
        assertEquals(adjusted, parseState(buildState(storage.saved.last())).selected)
        assertTrue(vm.buy(7.5, 100, now))
        vm.undoLastTrade()
        assertEquals(2600, vm.state.value.selected!!.totalQty)
        vm.undoLastTrade()
        assertEquals(before.holdings, vm.state.value.selected!!.holdings)
        assertEquals(0.0, vm.state.value.selected!!.realizedPnlFee, 1e-7)
        assertEquals(30000.0, vm.state.value.selected!!.tradePlanState!!.cash, 1e-7)
    }

    @Test fun actualTaxDeductionIsCashAndIncomeWithoutInventingASell() {
        val (vm, _) = vm()
        assertTrue(vm.registerPlanAdjustment(PlanAdjustmentInput(-50.0, 0, 0.0, 0.0, 10.0), now))
        val account = vm.state.value.selected!!
        assertTrue(account.trades.isEmpty())
        assertEquals(-50.0, account.realizedPnlFee, 1e-7)
        assertEquals(29950.0, account.tradePlanState!!.cash, 1e-7)
    }

    @Test fun reactivationDoesNotRestoreAnotherEpochDuringUndo() {
        val (vm, _) = vm()
        vm.buy(9.0, 100, now)
        val oldEpoch = vm.state.value.selected!!.tradePlan!!.activationId
        assertTrue(vm.configureTradePlan(config()))
        assertNotEquals(oldEpoch, vm.state.value.selected!!.tradePlan!!.activationId)
        vm.undoLastTrade()
        assertEquals(1, vm.state.value.selected!!.trades.size)
        assertTrue(vm.state.value.message!!.contains("关闭策略"))
        assertTrue(vm.configureTradePlan(null))
        vm.undoLastTrade()
        assertTrue(vm.state.value.selected!!.trades.isEmpty())
        assertNull(vm.state.value.selected!!.tradePlanState)
    }

    @Test fun invalidNumbersCannotEnterTheLedgerOrStrategyCash() {
        val (vm, _) = vm()
        val before = vm.state.value.selected!!
        assertFalse(vm.buy(Double.NaN, 100, now))
        assertFalse(vm.sellWithMatch(Double.POSITIVE_INFINITY, 100, SellMatch.LOWEST, now))
        vm.setCurrentPrice(Double.NaN)
        assertEquals(before, vm.state.value.selected)
    }

    @Test fun etfActualFeesUseTheSameExemptionsAsTheStrategyEstimate() {
        val vm = StockViewModel(initialFeeConfig = FeeConfig())
        vm.addStock(QuoteResult("159915", "ETF", 3.0, "sz"))
        vm.buy(3.0, 100, now - day)
        vm.sellWithMatch(3.1, 100, SellMatch.LOWEST, now)
        assertEquals(5.0, vm.state.value.selected!!.trades.first().fee, 0.0)
        assertEquals(5.0, vm.state.value.selected!!.trades.last().fee, 0.0)
    }

    @Test fun anInflightQuoteUpdatesItsOriginalStockAfterSelectionChanges() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = object : StockApi() {
            override suspend fun fetchQuote(stock: Stock): QuoteFields? {
                started.complete(Unit)
                release.await()
                return QuoteFields(stock.name, stock.code, 22.0, 21.0, 22.0, 0.0, 0.0, "20260615100000")
            }
        }
        val storage = FakeStorage().apply { loaded = StockState(listOf(
            StockAccount(stock, currentPrice = 10.0), StockAccount(Stock("000001", "Other", "sz"), currentPrice = 8.0)), 0) }
        val vm = StockViewModel(storage, api, initialFeeConfig = fees)
        vm.refreshPrice()
        started.await()
        vm.selectStock(1)
        release.complete(Unit)
        withTimeout(5000) { vm.state.first { it.accounts[0].currentPrice == 22.0 } }
        assertEquals(8.0, vm.state.value.accounts[1].currentPrice!!, 0.0)
        assertEquals(1, vm.state.value.selectedIndex)
    }

    @Test fun queuedSavesCannotOverwriteNewerAccountingWithAnOlderSnapshot() = runTest {
        val began = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<StockState>()
        val storage = object : Storage {
            override suspend fun load() = StockState(listOf(StockAccount(stock, currentPrice = 10.0)), 0)
            override suspend fun save(state: StockState) {
                if (writes.isEmpty()) { began.complete(Unit); release.await() }
                writes += state
            }
        }
        val vm = StockViewModel(storage, initialFeeConfig = fees)
        began.await()
        vm.setCurrentPrice(11.0)
        vm.buy(10.0, 100, now)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(buildState(vm.state.value), buildState(writes.last()))
    }
}
