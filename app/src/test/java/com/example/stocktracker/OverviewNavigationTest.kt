package com.example.stocktracker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OverviewNavigationTest {
    @get:Rule val main = MainDispatcherRule()
    private fun storage() = FakeStorage().apply {
        loaded = StockState(listOf(
            StockAccount(Stock("000001", "Example", "sz")),
            StockAccount(Stock("000002", "Example", "sz"))
        ), 0)
    }

    @Test fun changingOverviewTabsDoesNotPersistOrChangeAccountingState() {
        val storage = storage()
        val vm = StockViewModel(storage)
        val writes = storage.saved.size
        val accounting = buildState(vm.state.value)
        assertEquals(OverviewTab.FLOAT, vm.overviewTab.value)
        for (tab in OverviewTab.entries) vm.selectOverviewTab(tab)
        vm.selectOverviewTab(null)
        assertNull(vm.overviewTab.value)
        assertEquals(writes, storage.saved.size)
        assertEquals(accounting, buildState(vm.state.value))
    }

    @Test fun stockNavigationDoesNotResetTheRememberedOverviewTab() {
        val vm = StockViewModel(storage())
        vm.selectOverviewTab(OverviewTab.TRADES)
        vm.selectStock(1)
        assertEquals("sz000002", vm.state.value.selected!!.stock.marketCode)
        assertEquals(OverviewTab.TRADES, vm.overviewTab.value)
        vm.selectOverviewTab(null)
        vm.selectStock(0)
        assertNull(vm.overviewTab.value)
    }

    @Test fun aNewViewModelStartsAtFloatEvenAfterAccountStateWasSaved() {
        val store = storage()
        val vm = StockViewModel(store)
        vm.selectOverviewTab(OverviewTab.TRADES)
        vm.selectStock(1)
        val newStore = FakeStorage().apply { loaded = parseState(buildState(store.saved.last())) }
        val restarted = StockViewModel(newStore)
        assertEquals(1, restarted.state.value.selectedIndex)
        assertEquals(OverviewTab.FLOAT, restarted.overviewTab.value)
    }
}
