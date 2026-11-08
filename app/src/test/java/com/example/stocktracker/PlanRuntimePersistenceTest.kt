package com.example.stocktracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanRuntimePersistenceTest {
    private val config = TradePlanConfig(lowerCost = 10000.0, upperCost = 30000.0,
        orderAmount = 8000.0, stepPercent = 8.0, referencePrice = 20.0,
        capital = 50000.0, initialCash = 30000.0)
    private val now = 1793750400000L

    private fun active() = startTradePlan(StockAccount(Stock("002396", "Example", "sz"),
        holdings = listOf(BuyLot(1L, 20.0, 1000, 1000)), currentPrice = 20.0), config, 11L)

    private fun json(account: StockAccount) = JSONObject(buildState(StockState(listOf(account), 0)))
    private fun accountObject(root: JSONObject) = root.getJSONArray("accounts").getJSONObject(0)

    @Test fun allRuntimeFieldsAndNegativeActualCashSurviveRestart() {
        val account = active()
        val runtime = account.tradePlanState!!.copy(cash = -50.0, phase = PlanPhase.DOWN_REDUCTION,
            cursorGrid = -2, pendingDirection = PlanDirection.DOWN, pendingGrid = -2,
            lotGrids = mapOf(1L to 3), gridScale = 0.75, gridOffset = -0.2, revision = 19L)
        val updated = account.copy(tradePlanState = runtime)
        assertEquals(updated, parseState(buildState(StockState(listOf(updated), 0))).selected)
    }

    @Test fun tradeUndoSnapshotsFromDifferentActivationsRemainSeparate() {
        val original = active()
        val prior = original.tradePlanState!!.copy(activationId = 4L, cash = 42000.0)
        val transaction = TradeRecord(25L, TradeType.SELL, 21.0, 100, profit = 100.0, time = now,
            soldLots = listOf(SoldLot(1L, 100, 20.0, 1000, 0L, 0.0)), planBefore = prior)
        val updated = original.copy(holdings = listOf(original.holdings[0].copy(remainingQty = 900)),
            trades = listOf(transaction), tradePlanState = original.tradePlanState!!.copy(cash = 32100.0))
        val restored = parseState(buildState(StockState(listOf(updated), 0))).selected!!
        assertEquals(updated, restored)
        assertEquals(4L, restored.trades.single().planBefore!!.activationId)
        assertEquals(11L, restored.tradePlanState!!.activationId)
    }

    @Test fun adjustmentZeroSharesAndBonusOddLotsRoundTripWithUndoSnapshot() {
        val initial = active()
        val bonus = applyTradePlanAdjustment(initial, PlanAdjustmentInput(250.0, 130, 0.25, 13.0, 18.0), now, 100L)
        assertEquals(1130, bonus.totalQty)
        val restored = parseState(buildState(StockState(listOf(bonus), 0))).selected!!
        assertEquals(bonus, restored)
        assertEquals(initial.holdings, restored.planAdjustments.single().beforeHoldings)
        assertEquals(initial.tradePlanState, restored.planAdjustments.single().beforePlanState)
        val tax = applyTradePlanAdjustment(restored, PlanAdjustmentInput(-25.0, 0, 0.0, 0.0, 18.0), now + 1, 101L)
        assertEquals(tax, parseState(buildState(StockState(listOf(tax), 0))).selected)
    }

    @Test fun missingOrMalformedRuntimeNeverDiscardsAccountingData() {
        val cases: List<(JSONObject) -> Unit> = listOf(
            { it.remove("cash") },
            { it.put("cash", "NaN") },
            { it.put("phase", "UNKNOWN") },
            { it.put("activationId", 11.5) },
            { it.put("activationId", 12) },
            { it.put("revision", -1) },
            { it.put("cursorGrid", 1.25) },
            { it.put("cursorGrid", 10001) },
            { it.put("gridScale", 0.0) },
            { it.put("gridOffset", "Infinity") },
            { it.put("pendingDirection", "UP") },
            { it.put("pendingDirection", "UP"); it.put("pendingGrid", 1) },
            { it.put("lotGrids", JSONObject().put("-1", 0)) },
            { it.put("lotGrids", JSONObject().put("01", 0)) },
            { it.put("lotGrids", JSONObject().put("1", 1.5)) },
            { it.put("lotGrids", JSONObject()) }
        )
        cases.forEach { mutate ->
            val original = active()
            val root = json(original)
            mutate(accountObject(root).getJSONObject("tradePlanState"))
            val restored = parseState(root.toString()).selected!!
            assertEquals(original.holdings, restored.holdings)
            assertEquals(original.trades, restored.trades)
            assertEquals(original.stock, restored.stock)
            assertTrue(restored.tradePlan!!.isValid)
            assertNull(restored.tradePlanState)
            assertTrue(restored.planRecoveryData.any { it.startsWith("tradePlanState:") })
        }
    }

    @Test fun invalidBeforeSnapshotIsArchivedWithoutLosingItsTrade() {
        val initial = active()
        val account = initial.copy(trades = listOf(TradeRecord(30L, TradeType.BUY, 20.0, 100,
            lotId = 2L, time = now, planBefore = initial.tradePlanState)))
        val root = json(account)
        accountObject(root).getJSONArray("trades").getJSONObject(0).getJSONObject("planBefore").put("phase", "BAD")
        val restored = parseState(root.toString()).selected!!
        assertEquals(30L, restored.trades.single().id)
        assertNull(restored.trades.single().planBefore)
        assertTrue(restored.planRecoveryData.any { it.startsWith("trade[30].planBefore:") && it.contains("BAD") })
        val restarted = parseState(buildState(StockState(listOf(restored), 0))).selected!!
        assertEquals(restored, restarted)
    }

    @Test fun malformedAdjustmentIsArchivedAndStopsRuntimeWithoutDroppingHoldings() {
        val adjusted = applyTradePlanAdjustment(active(), PlanAdjustmentInput(250.0, 100, 0.25, 10.0, 18.0), now, 100L)
        val root = json(adjusted)
        val raw = accountObject(root).getJSONArray("planAdjustments").getJSONObject(0)
        raw.getJSONObject("input").put("addedShares", 0.5)
        val restored = parseState(root.toString()).selected!!
        assertEquals(adjusted.holdings, restored.holdings)
        assertEquals(adjusted.trades, restored.trades)
        assertNull(restored.tradePlanState)
        assertTrue(restored.planAdjustments.isEmpty())
        assertTrue(restored.planRecoveryData.any { it.startsWith("planAdjustments[0]:") && it.contains("0.5") })
        assertEquals(restored, parseState(buildState(StockState(listOf(restored), 0))).selected)
    }

    @Test fun malformedAdjustmentContainerIsArchivedRatherThanIgnoredAsAnEmptyArray() {
        val root = json(active())
        accountObject(root).put("planAdjustments", "unreadable")
        val restored = parseState(root.toString()).selected!!
        assertEquals(active().holdings, restored.holdings)
        assertNull(restored.tradePlanState)
        assertTrue(restored.planRecoveryData.any { it == "planAdjustments:unreadable" })
    }

    @Test fun invalidSnapshotNumberCannotBeTruncatedIntoAValidUndo() {
        val adjusted = applyTradePlanAdjustment(active(), PlanAdjustmentInput(250.0, 100, 0.25, 10.0, 18.0), now, 100L)
        val root = json(adjusted)
        accountObject(root).getJSONArray("planAdjustments").getJSONObject(0)
            .getJSONArray("beforeHoldings").getJSONObject(0).put("remainingQty", 1000.25)
        val restored = parseState(root.toString()).selected!!
        assertTrue(restored.planAdjustments.isEmpty())
        assertNull(restored.tradePlanState)
        assertTrue(restored.planRecoveryData.isNotEmpty())
    }

    @Test fun missingRuntimeRequiresConfirmationInsteadOfInventingCashOrGrids() {
        val root = json(active())
        accountObject(root).remove("tradePlanState")
        val restored = parseState(root.toString()).selected!!
        assertEquals(active().tradePlan, restored.tradePlan)
        assertNull(restored.tradePlanState)
        assertNull(evaluateTradePlan(restored, FeeConfig()))
    }

    @Test fun fractionalSchemaVersionCannotBeTruncatedIntoAnActivePlan() {
        val root = json(active())
        val raw = accountObject(root).getJSONObject("tradePlan").put("version", 3.5)
        val restored = parseState(root.toString()).selected!!
        assertTrue(restored.tradePlan!!.needsSetup)
        assertNull(restored.tradePlanState)
        assertTrue(raw.similar(JSONObject(restored.tradePlan!!.legacyJson!!)))
    }

    @Test fun incompleteAdjustmentUndoMappingIsQuarantinedRatherThanRestored() {
        val adjusted = applyTradePlanAdjustment(active(), PlanAdjustmentInput(250.0, 100, 0.25, 10.0, 18.0), now, 100L)
        val root = json(adjusted)
        accountObject(root).getJSONArray("planAdjustments").getJSONObject(0)
            .getJSONObject("beforePlanState").put("lotGrids", JSONObject())
        val restored = parseState(root.toString()).selected!!
        assertEquals(adjusted.holdings, restored.holdings)
        assertTrue(restored.planAdjustments.isEmpty())
        assertNull(restored.tradePlanState)
        assertTrue(restored.planRecoveryData.any { it.startsWith("planAdjustments[0]:") })
    }
}
