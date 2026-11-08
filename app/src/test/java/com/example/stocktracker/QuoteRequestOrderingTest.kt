package com.example.stocktracker

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QuoteRequestOrderingTest {
    @get:Rule val main = MainDispatcherRule()

    private val firstStock = Stock("002396", "First", "sz")
    private val secondStock = Stock("000001", "Second", "sz")
    private val zeroFees = FeeConfig(0.0, 0.0, 0.0, 0.0)

    private data class Request(val stock: Stock, val result: CompletableDeferred<QuoteFields?> = CompletableDeferred()) {
        fun complete(price: Double) {
            result.complete(QuoteFields(stock.name, stock.code, price, price - 0.1, price, 0.0, 0.0, "20260615100000"))
        }
    }

    private class ControlledApi : StockApi() {
        val requests = Channel<Request>(Channel.UNLIMITED)

        override suspend fun fetchQuote(stock: Stock): QuoteFields? {
            val request = Request(stock)
            requests.send(request)
            return request.result.await()
        }

        suspend fun next(): Request = bounded { requests.receive() }
    }

    private data class Fixture(val vm: StockViewModel, val api: ControlledApi) {
        fun close() {
            vm.viewModelScope.cancel()
            api.requests.cancel()
        }
    }

    private fun fixture(twoStocks: Boolean = false): Fixture {
        val api = ControlledApi()
        val vm = StockViewModel(api = api, initialFeeConfig = zeroFees)
        vm.addStock(QuoteResult(firstStock.code, firstStock.name, 10.0, firstStock.market))
        if (twoStocks) vm.addStock(QuoteResult(secondStock.code, secondStock.name, 8.0, secondStock.market))
        vm.selectStock(0)
        assertTrue(vm.configureTradePlan(TradePlanConfig(lowerCost = 1000.0, upperCost = 30000.0,
            orderAmount = 2000.0, stepPercent = 8.0, referencePrice = 10.0, capital = 50000.0, initialCash = 50000.0)))
        return Fixture(vm, api)
    }

    private fun start(vm: StockViewModel, action: () -> Unit): Job {
        val prior = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        action()
        return vm.viewModelScope.coroutineContext[Job]!!.children.single { it !in prior }
    }

    private fun account(vm: StockViewModel, stock: Stock = firstStock): StockAccount =
        vm.state.value.accounts.single { it.stock.marketCode == stock.marketCode }

    @Test fun anOlderSingleQuoteCannotOverwriteNewerPriceOrInventADownwardGridCrossing() = runTest {
        val (vm, api) = fixture()
        try {
            val olderJob = start(vm, vm::refreshPrice)
            val older = api.next()
            val newerJob = start(vm, vm::refreshPrice)
            val newer = api.next()
            newer.complete(12.0)
            bounded { newerJob.join() }
            val stateAfterNewer = account(vm).tradePlanState
            assertEquals(PlanDirection.UP, stateAfterNewer!!.pendingDirection)
            older.complete(10.1)
            bounded { olderJob.join() }
            assertEquals(12.0, account(vm).currentPrice!!, 0.0)
            assertEquals(stateAfterNewer, account(vm).tradePlanState)
        } finally {
            vm.viewModelScope.cancel()
            api.requests.cancel()
        }
    }

    @Test fun aNewerRefreshAllSupersedesAnOlderSingleQuoteForTheSameStock() = runTest {
        val fixture = fixture(twoStocks = true)
        val (vm, api) = fixture
        try {
            val olderJob = start(vm, vm::refreshPrice)
            val older = api.next()
            val allJob = start(vm, vm::refreshAll)
            val all = listOf(api.next(), api.next()).associateBy { it.stock.marketCode }
            all.getValue(firstStock.marketCode).complete(12.0)
            all.getValue(secondStock.marketCode).complete(9.0)
            bounded { allJob.join() }
            val latestState = account(vm).tradePlanState
            older.complete(10.1)
            bounded { olderJob.join() }
            assertEquals(12.0, account(vm).currentPrice!!, 0.0)
            assertEquals(latestState, account(vm).tradePlanState)
            assertEquals(9.0, account(vm, secondStock).currentPrice!!, 0.0)
        } finally { fixture.close() }
    }

    @Test fun aNewerSingleQuoteSupersedesOnlyItsStockWithinAnOlderRefreshAll() = runTest {
        val fixture = fixture(twoStocks = true)
        val (vm, api) = fixture
        try {
            val allJob = start(vm, vm::refreshAll)
            val all = listOf(api.next(), api.next()).associateBy { it.stock.marketCode }
            val newerJob = start(vm, vm::refreshPrice)
            val newer = api.next()
            newer.complete(12.0)
            bounded { newerJob.join() }
            val latestState = account(vm).tradePlanState
            all.getValue(firstStock.marketCode).complete(10.1)
            all.getValue(secondStock.marketCode).complete(9.0)
            bounded { allJob.join() }
            assertEquals(12.0, account(vm).currentPrice!!, 0.0)
            assertEquals(latestState, account(vm).tradePlanState)
            assertEquals(9.0, account(vm, secondStock).currentPrice!!, 0.0)
        } finally { fixture.close() }
    }

    @Test fun settingAnActualPriceInvalidatesAnOlderNetworkQuote() = runTest {
        val fixture = fixture()
        val (vm, api) = fixture
        try {
            val job = start(vm, vm::refreshPrice)
            val request = api.next()
            vm.setCurrentPrice(10.4)
            val manualState = account(vm).tradePlanState
            request.complete(12.0)
            bounded { job.join() }
            assertEquals(10.4, account(vm).currentPrice!!, 0.0)
            assertEquals(manualState, account(vm).tradePlanState)
        } finally { fixture.close() }
    }

    @Test fun clearingTheActualPriceIsNotUndoneByAnOlderNetworkQuote() = runTest {
        val fixture = fixture()
        val (vm, api) = fixture
        try {
            val job = start(vm, vm::refreshPrice)
            val request = api.next()
            vm.setCurrentPrice(null)
            request.complete(12.0)
            bounded { job.join() }
            assertNull(account(vm).currentPrice)
        } finally { fixture.close() }
    }

    @Test fun deletingAndReaddingTheSameCodeDoesNotAcceptTheDeletedAccountsInflightQuote() = runTest {
        val fixture = fixture(twoStocks = true)
        val (vm, api) = fixture
        try {
            val job = start(vm, vm::refreshPrice)
            val request = api.next()
            vm.removeStock(0)
            vm.addStock(QuoteResult(firstStock.code, "Replacement", 7.0, firstStock.market))
            request.complete(12.0)
            bounded { job.join() }
            val replacement = account(vm)
            assertEquals("Replacement", replacement.stock.name)
            assertEquals(7.0, replacement.currentPrice!!, 0.0)
            assertNull(replacement.tradePlan)
            assertNull(replacement.tradePlanState)
            assertTrue(replacement.trades.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun invalidSingleQuotesDoNotCrashOrChangePriceAndRuntime() = runTest {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val fixture = fixture()
            val (vm, api) = fixture
            try {
                val before = account(vm)
                val job = start(vm, vm::refreshPrice)
                api.next().complete(invalid)
                bounded { job.join() }
                assertFalse("An invalid quote must be handled as data, not cancel the request job", job.isCancelled)
                assertEquals(before.currentPrice, account(vm).currentPrice)
                assertEquals(before.tradePlanState, account(vm).tradePlanState)
                assertTrue(vm.state.value.message.orEmpty().contains("失败"))
            } finally { fixture.close() }
        }
    }

    @Test fun invalidQuotesInRefreshAllDoNotPreventAnotherStocksValidUpdate() = runTest {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val fixture = fixture(twoStocks = true)
            val (vm, api) = fixture
            try {
                val before = account(vm)
                val job = start(vm, vm::refreshAll)
                val requests = listOf(api.next(), api.next()).associateBy { it.stock.marketCode }
                requests.getValue(firstStock.marketCode).complete(invalid)
                requests.getValue(secondStock.marketCode).complete(9.0)
                bounded { job.join() }
                assertFalse("One invalid quote must not cancel refresh-all", job.isCancelled)
                assertEquals(before.currentPrice, account(vm).currentPrice)
                assertEquals(before.tradePlanState, account(vm).tradePlanState)
                assertEquals(9.0, account(vm, secondStock).currentPrice!!, 0.0)
            } finally { fixture.close() }
        }
    }

    companion object {
        // Real IO dispatchers are used by the ViewModel; do not advance a virtual timeout before they can reply.
        private suspend fun <T> bounded(block: suspend () -> T): T = withContext(Dispatchers.Default) {
            withTimeout(5000) { block() }
        }
    }
}
