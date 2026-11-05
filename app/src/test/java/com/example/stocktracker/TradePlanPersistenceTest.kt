package com.example.stocktracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TradePlanPersistenceTest {
    @get:Rule val main = MainDispatcherRule()
    private val plan = TradePlanConfig(referencePrice = 5.0, startAfterTradeId = 10)
    private val first = StockAccount(Stock("000001", "First", "sz"),
        holdings = listOf(BuyLot(1, 5.0, 5000, 5000)), tradePlan = plan)
    private val second = StockAccount(Stock("000002", "Second", "sz"),
        tradePlan = plan.copy(lowerCost = 2000.0, upperCost = 20000.0, orderAmount = 2000.0, stepPercent = 3.0))

    @Test fun independentStockSettingsAndAccountingDataRoundTrip() {
        val state = StockState(accounts = listOf(first, second), selectedIndex = 1)
        assertEquals(state, parseState(buildState(state)))
    }

    @Test fun stateWithoutSettingsStillRestoresHoldings() {
        val state = StockState(listOf(first.copy(tradePlan = null)), 0)
        val json = JSONObject(buildState(state))
        json.getJSONArray("accounts").getJSONObject(0).remove("tradePlan")
        assertEquals(state, parseState(json.toString()))
    }

    @Test fun invalidNewSettingsDoNotDiscardStocksOrTrades() {
        val json = JSONObject(buildState(StockState(listOf(first), 0)))
        json.getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan").put("stepPercent", 0.0)
        assertEquals(first.copy(tradePlan = null), parseState(json.toString()).selected)
    }

    @Test fun oldThresholdSettingsRemainIntactButCannotGenerateNewSignals() {
        val old = JSONObject().put("thresholdMode", "QUANTITY").put("threshold", 400)
            .put("unitQty", 100).put("stepMode", "PRICE").put("step", 5.0)
            .put("referencePrice", 50.0).put("startAfterTradeId", 9)
        val json = JSONObject(buildState(StockState(listOf(first), 0)))
        json.getJSONArray("accounts").getJSONObject(0).put("tradePlan", old)
        val restored = parseState(json.toString())
        assertTrue(restored.selected!!.tradePlan!!.needsSetup)
        assertNull(evaluateTradePlan(restored.selected!!, FeeConfig()))
        val saved = JSONObject(buildState(restored)).getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan")
        assertTrue(old.similar(saved))
        assertEquals(first.holdings, restored.selected!!.holdings)
    }

    @Test fun savingNewSettingsReplacesLegacySettingsOnlyForTheSelectedStock() {
        val old = plan.copy(legacyJson = "{\"threshold\":400,\"unitQty\":100}")
        val storage = FakeStorage().apply { loaded = StockState(listOf(first.copy(tradePlan = old), second), 0) }
        val vm = StockViewModel(storage)
        assertTrue(vm.configureTradePlan(plan))
        assertEquals(plan, vm.state.value.selected!!.tradePlan)
        assertEquals(second, vm.state.value.accounts[1])
        assertEquals(vm.state.value.accounts, storage.saved.last().accounts)
        assertTrue(vm.configureTradePlan(null))
        assertNull(vm.state.value.selected!!.tradePlan)
    }

    @Test fun invalidConfigurationDoesNotReplaceExistingSettings() {
        val storage = FakeStorage().apply { loaded = StockState(listOf(first), 0) }
        val vm = StockViewModel(storage)
        assertFalse(vm.configureTradePlan(plan.copy(upperCost = plan.lowerCost)))
        assertEquals(plan, vm.state.value.selected!!.tradePlan)
    }

    @Test fun unsupportedFutureSettingsRemainDisabledAndSurviveSaving() {
        val raw = "{\"version\":99,\"futureField\":123}"
        val state = StockState(listOf(first.copy(tradePlan = plan.copy(legacyJson = raw))), 0)
        val restored = parseState(buildState(state))
        assertTrue(restored.selected!!.tradePlan!!.needsSetup)
        assertTrue(JSONObject(raw).similar(JSONObject(restored.selected!!.tradePlan!!.legacyJson!!)))
    }

    @Test fun oneTradeMatchingAndUndoKeepTheGlobalPreferenceAndPlan() {
        val storage = FakeStorage().apply { loaded = StockState(listOf(first.copy(
            holdings = first.holdings + BuyLot(2, 4.0, 100, 100))), 0) }
        val vm = StockViewModel(storage, initialFeeConfig = FeeConfig(0.0, 0.0, 0.0, 0.0))
        val original = vm.state.value.selected!!
        vm.sellWithMatch(3.5, 100, SellMatch.HIGHEST)
        assertEquals(SellMatch.LOWEST, vm.state.value.sellMatch)
        assertEquals(4900, vm.state.value.selected!!.holdings.first { it.id == 1L }.remainingQty)
        assertEquals(plan, vm.state.value.selected!!.tradePlan)
        vm.undoLastTrade()
        assertEquals(original, vm.state.value.selected)
    }
}
