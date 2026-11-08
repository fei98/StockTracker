package com.example.stocktracker

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class PlanAdjustmentOverviewTest {
    private fun at(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, 12, 0, 0)
    }.timeInMillis

    private fun cash(id: Long, amount: Double, time: Long) = PlanAdjustmentRecord(
        id = id, time = time, input = PlanAdjustmentInput(amount, 0, 0.0, 0.0, 20.0),
        beforeHoldings = emptyList(), beforePlanState = null, beforeCurrentPrice = null,
        beforePrevClose = null, afterTradeId = 0L, matureQtyAfter = 0, frozenQtyAfter = 0)

    private val current = at(2026, 11, 8)
    private val sale = TradeRecord(10L, TradeType.SELL, 21.0, 100, profit = 100.0,
        time = at(2026, 11, 7), fee = 2.0, costFee = 1.0)
    private val account = StockAccount(Stock("002396", "Example", "sz"),
        trades = listOf(TradeRecord(1L, TradeType.BUY, 20.0, 100, time = at(2026, 11, 6)), sale),
        planAdjustments = listOf(cash(20, 250.0, at(2026, 11, 7)), cash(21, -25.0, at(2026, 11, 7)),
            cash(22, 40.0, at(2026, 10, 31)), cash(23, 30.0, at(2025, 5, 30))))

    @Test fun cashIncomeAndTaxAreAddedOnceToAccountAndOverallRealizedProfit() {
        assertEquals(392.0, account.realizedPnlFee, 0.0)
        assertEquals(392.0, overviewEntries(listOf(account), OverviewTab.REALIZED).single().value, 0.0)
        assertEquals(392.0, StockState(listOf(account), 0).realizedPnl, 0.0)
    }

    @Test fun dayAndMonthRangesIncludeActualCashOnItsRecordedDate() {
        assertEquals(322.0, realizedEntriesInRange(listOf(account), RealizedRange.Day(at(2026, 11, 7)), current).single().value, 0.0)
        assertEquals(322.0, realizedEntriesInRange(listOf(account), RealizedRange.ThisMonth, current).single().value, 0.0)
        assertEquals(40.0, realizedEntriesInRange(listOf(account), RealizedRange.Month(2026, 10), current).single().value, 0.0)
        assertEquals(0.0, realizedEntriesInRange(listOf(account), RealizedRange.Day(at(2026, 11, 6)), current).single().value, 0.0)
    }

    @Test fun dailyCalendarShowsNetCorporateCashRatherThanFakeSales() {
        val days = realizedDayTotalsInMonth(listOf(account), 2026, 11)
        assertEquals(30, days.size)
        assertEquals(322.0, days[6], 0.0)
        assertEquals(322.0, days.sum(), 0.0)
    }

    @Test fun monthHistoryIncludesOlderCorporateDateAndContinuousEmptyMonths() {
        val months = realizedMonthTotals(listOf(account), current)
        assertEquals(Triple(2026, 11, 322.0), months.first())
        assertEquals(Triple(2025, 5, 30.0), months.last())
        assertEquals(19, months.size)
        assertEquals(40.0, months.first { it.first == 2026 && it.second == 10 }.third, 0.0)
        assertEquals(0.0, months.first { it.first == 2025 && it.second == 6 }.third, 0.0)
    }

    @Test fun yearlyHistoryIncludesOlderCorporateDateAndCorrectNetAmounts() {
        assertEquals(listOf(2026 to 362.0, 2025 to 30.0), realizedYearTotals(listOf(account), current))
        assertEquals(362.0, realizedEntriesInRange(listOf(account), RealizedRange.Year(2026), current).single().value, 0.0)
    }

    @Test fun corporateFlowsNeverAppearAsZeroQuantityTransactions() {
        val transactions = allTradesSorted(listOf(account))
        assertEquals(2, transactions.size)
        assertTrue(transactions.all { it.second.qty == 100 })
        assertEquals(sale, transactions.first().second)
    }

    @Test fun corporateOnlyAccountStillHasDateHistoryAndTaxDeduction() {
        val only = StockAccount(Stock("000001", "Cash only", "sz"),
            planAdjustments = listOf(cash(1, 100.0, at(2024, 12, 31)), cash(2, -10.0, at(2026, 11, 7))))
        assertEquals(listOf(2026 to -10.0, 2025 to 0.0, 2024 to 100.0), realizedYearTotals(listOf(only), current))
        assertEquals(Triple(2024, 12, 100.0), realizedMonthTotals(listOf(only), current).last())
        assertTrue(allTradesSorted(listOf(only)).isEmpty())
    }

    @Test fun multipleAccountsKeepCorporateIncomeSeparate() {
        val other = StockAccount(Stock("000001", "Other", "sz"), planAdjustments = listOf(cash(50, 15.0, at(2026, 11, 7))))
        val result = realizedEntriesInRange(listOf(account, other), RealizedRange.ThisMonth, current)
        assertEquals(listOf(322.0, 15.0), result.map { it.value })
    }
}
