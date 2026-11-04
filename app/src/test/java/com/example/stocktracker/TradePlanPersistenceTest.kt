package com.example.stocktracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TradePlanPersistenceTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val plan = TradePlanConfig(threshold = 400.0, step = 5.0,
        referencePrice = 50.0, startAfterTradeId = 10)
    private val first = StockAccount(Stock("000001", "First", "sz"),
        holdings = listOf(BuyLot(1, 50.0, 500, 500)), tradePlan = plan)
    private val second = StockAccount(Stock("000002", "Second", "sz"),
        tradePlan = plan.copy(thresholdMode = PlanThresholdMode.COST, threshold = 20_000.0,
            stepMode = PlanStepMode.PERCENT, step = 10.0, referencePrice = 30.0))

    @Test
    fun jsonRoundTripPreservesIndependentStockPlansAndAccountingData() {
        val state = StockState(accounts = listOf(first, second), selectedIndex = 1)
        assertEquals(state, parseState(buildState(state)))
    }

    @Test
    fun oldStateWithoutPlanFieldsRestoresItsHoldings() {
        val state = StockState(accounts = listOf(first.copy(tradePlan = null)), selectedIndex = 0)
        val json = JSONObject(buildState(state))
        json.getJSONArray("accounts").getJSONObject(0).remove("tradePlan")
        assertEquals(state, parseState(json.toString()))
    }

    @Test
    fun invalidPlanDoesNotDiscardStockOrTradingData() {
        val json = JSONObject(buildState(StockState(accounts = listOf(first), selectedIndex = 0)))
        json.getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan").put("stepMode", "UNKNOWN")
        assertEquals(first.copy(tradePlan = null), parseState(json.toString()).selected)
    }

    @Test
    fun configuringOneStockPersistsWithoutChangingAnotherStock() {
        val storage = FakeStorage().apply {
            loaded = StockState(accounts = listOf(first.copy(tradePlan = null), second), selectedIndex = 0)
        }
        val vm = StockViewModel(storage, initialFeeConfig = FeeConfig(0.0, 0.0, 0.0, 0.0))
        assertTrue(vm.configureTradePlan(plan))
        assertEquals(plan, vm.state.value.accounts[0].tradePlan)
        assertEquals(second, vm.state.value.accounts[1])
        assertEquals(vm.state.value.accounts, storage.saved.last().accounts)
        assertTrue(vm.configureTradePlan(null))
        assertNull(vm.state.value.accounts[0].tradePlan)
        assertEquals(second, vm.state.value.accounts[1])
    }

    @Test
    fun invalidConfigurationCannotReplaceTheExistingPlan() {
        val storage = FakeStorage().apply { loaded = StockState(listOf(first), 0) }
        val vm = StockViewModel(storage)
        assertFalse(vm.configureTradePlan(plan.copy(unitQty = 0)))
        assertEquals(plan, vm.state.value.selected!!.tradePlan)
    }

    @Test
    fun planSpecificSellMatchDoesNotChangeGlobalPreferenceAndSupportsUndo() {
        val storage = FakeStorage().apply {
            loaded = StockState(listOf(first.copy(holdings = first.holdings + BuyLot(2, 40.0, 100, 100))), 0)
        }
        val vm = StockViewModel(storage, initialFeeConfig = FeeConfig(0.0, 0.0, 0.0, 0.0))
        val original = vm.state.value.selected!!
        vm.sellWithMatch(35.0, 100, SellMatch.HIGHEST)
        assertEquals(SellMatch.LOWEST, vm.state.value.sellMatch)
        assertEquals(400, vm.state.value.selected!!.holdings.first { it.id == 1L }.remainingQty)
        assertEquals(plan, vm.state.value.selected!!.tradePlan)
        vm.undoLastTrade()
        assertEquals(original, vm.state.value.selected)
    }
}
