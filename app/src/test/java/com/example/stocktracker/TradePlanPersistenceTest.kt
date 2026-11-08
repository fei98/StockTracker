package com.example.stocktracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TradePlanPersistenceTest {
    @get:Rule val main = MainDispatcherRule()
    private val fees = FeeConfig(0.0, 0.0, 0.0, 0.0)
    private val config = TradePlanConfig(lowerCost = 10000.0, upperCost = 30000.0,
        orderAmount = 8000.0, stepPercent = 8.0, referencePrice = 20.0,
        capital = 50000.0, initialCash = 30000.0)

    private fun active(code: String = "002396", activation: Long = 11L): StockAccount = startTradePlan(
        StockAccount(Stock(code, "Example", "sz"),
            holdings = listOf(BuyLot(1, 20.0, 1000, 1000)), currentPrice = 20.0),
        config, activation)

    private fun objectOf(account: StockAccount): JSONObject =
        JSONObject(buildState(StockState(listOf(account), 0)))

    @Test fun independentConfiguredStocksAndRuntimeRoundTrip() {
        val first = active()
        val second = startTradePlan(StockAccount(Stock("000001", "Second", "sz")),
            config.copy(lowerCost = 2000.0, upperCost = 20000.0, orderAmount = 2000.0,
                stepPercent = 3.0, initialCash = 50000.0), 12L)
        val state = StockState(accounts = listOf(first, second), selectedIndex = 1)
        assertEquals(state, parseState(buildState(state)))
        assertEquals(3, objectOf(first).getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan").getInt("version"))
    }

    @Test fun stateWithoutSettingsStillRestoresHoldings() {
        val account = active().copy(tradePlan = null, tradePlanState = null)
        val root = objectOf(account)
        root.getJSONArray("accounts").getJSONObject(0).remove("tradePlan")
        root.getJSONArray("accounts").getJSONObject(0).remove("tradePlanState")
        assertEquals(account, parseState(root.toString()).selected)
    }

    @Test fun invalidNewSettingsArePreservedButCannotRun() {
        val original = active()
        val root = objectOf(original)
        val jsonPlan = root.getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan")
        jsonPlan.put("stepPercent", 0.0)
        val restored = parseState(root.toString()).selected!!
        assertEquals(original.holdings, restored.holdings)
        assertTrue(restored.tradePlan!!.needsSetup)
        assertNull(restored.tradePlanState)
        assertNull(evaluateTradePlan(restored, fees))
        val saved = objectOf(restored).getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan")
        assertTrue(jsonPlan.similar(saved))
    }

    @Test fun oldThresholdSettingsRemainIntactAndDisabled() {
        val old = JSONObject().put("thresholdMode", "QUANTITY").put("threshold", 400)
            .put("unitQty", 100).put("stepMode", "PRICE").put("step", 5.0)
            .put("referencePrice", 50.0).put("startAfterTradeId", 9)
        val root = objectOf(active())
        root.getJSONArray("accounts").getJSONObject(0).put("tradePlan", old)
        val restored = parseState(root.toString()).selected!!
        assertTrue(restored.tradePlan!!.needsSetup)
        assertNull(restored.tradePlanState)
        assertNull(evaluateTradePlan(restored, fees))
        assertTrue(old.similar(objectOf(restored).getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan")))
    }

    @Test fun versionTwoRetainsReadableParametersAndRawSettings() {
        val old = JSONObject().put("version", 2).put("lowerCost", 7000.0).put("upperCost", 32000.0)
            .put("orderAmount", 6000.0).put("stepPercent", 6.0).put("referencePrice", 18.0)
            .put("startAfterTradeId", 99)
        val root = objectOf(active())
        root.getJSONArray("accounts").getJSONObject(0).put("tradePlan", old)
        val restored = parseState(root.toString()).selected!!
        val plan = restored.tradePlan!!
        assertTrue(plan.needsSetup)
        assertEquals(7000.0, plan.lowerCost, 0.0)
        assertEquals(32000.0, plan.upperCost, 0.0)
        assertEquals(6000.0, plan.orderAmount, 0.0)
        assertEquals(6.0, plan.stepPercent, 0.0)
        assertNull(restored.tradePlanState)
        assertTrue(old.similar(objectOf(restored).getJSONArray("accounts").getJSONObject(0).getJSONObject("tradePlan")))
    }

    @Test fun unsupportedFutureSettingsRemainDisabledAndSurviveSaving() {
        val raw = "{\"version\":99,\"futureField\":123}"
        val account = active().copy(tradePlan = config.copy(legacyJson = raw))
        val restored = parseState(buildState(StockState(listOf(account), 0))).selected!!
        assertTrue(restored.tradePlan!!.needsSetup)
        assertNull(restored.tradePlanState)
        assertTrue(JSONObject(raw).similar(JSONObject(restored.tradePlan!!.legacyJson!!)))
    }

    @Test fun savingSettingsActivatesOnlySelectedStockAndDoesNotOverwriteOtherParameters() {
        val first = active()
        val second = active("000001", 12L)
        val storage = FakeStorage().apply { loaded = StockState(listOf(first, second), 0) }
        val vm = StockViewModel(storage, initialFeeConfig = fees)
        val changed = config.copy(lowerCost = 6000.0, upperCost = 24000.0, orderAmount = 3000.0, stepPercent = 5.0)
        assertTrue(vm.configureTradePlan(changed))
        assertEquals(6000.0, vm.state.value.selected!!.tradePlan!!.lowerCost, 0.0)
        assertEquals(3000.0, vm.state.value.selected!!.tradePlan!!.orderAmount, 0.0)
        assertNotNull(vm.state.value.selected!!.tradePlanState)
        assertEquals(second, vm.state.value.accounts[1])
        assertEquals(vm.state.value.accounts, storage.saved.last().accounts)
        assertTrue(vm.configureTradePlan(null))
        assertNull(vm.state.value.selected!!.tradePlan)
        assertNull(vm.state.value.selected!!.tradePlanState)
    }

    @Test fun invalidConfigurationDoesNotReplaceCurrentSettingsOrRuntime() {
        val original = active()
        val vm = StockViewModel(FakeStorage().apply { loaded = StockState(listOf(original), 0) })
        assertFalse(vm.configureTradePlan(config.copy(upperCost = config.lowerCost)))
        assertEquals(original, vm.state.value.selected)
    }

    @Test fun matchingAndUndoPreserveCurrentPlanAndGlobalPreference() {
        val account = active().copy(holdings = active().holdings + BuyLot(2, 18.0, 100, 100))
        val original = startTradePlan(account, config, 15L)
        val store = FakeStorage().apply { loaded = StockState(listOf(original), 0) }
        val vm = StockViewModel(store, initialFeeConfig = fees)
        vm.sellWithMatch(21.0, 100, SellMatch.HIGHEST)
        val after = vm.state.value.selected!!
        assertEquals(SellMatch.LOWEST, vm.state.value.sellMatch)
        assertNotNull(after.trades.last().planBefore)
        assertEquals(after, parseState(buildState(vm.state.value)).selected)
        vm.undoLastTrade()
        assertEquals(original, vm.state.value.selected)
    }
}
