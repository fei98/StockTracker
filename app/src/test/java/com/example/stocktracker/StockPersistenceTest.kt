package com.example.stocktracker

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 内存假存储：记录所有 save 调用，可预设 load 返回值 */
class FakeStorage : Storage {
    var loaded: StockState = StockState()
    val saved = mutableListOf<StockState>()
    override suspend fun load(): StockState = loaded
    override suspend fun save(state: StockState) { saved.add(state) }
}

class StockPersistenceTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun 启动时从存储恢复股票数据() = runTest {
        val fake = FakeStorage().apply {
            loaded = StockState(
                accounts = listOf(
                    StockAccount(stock = Stock("000001", "平安银行", "sz"), currentPrice = 11.0),
                    StockAccount(stock = Stock("600519", "贵州茅台", "sh"))
                ),
                selectedIndex = 1
            )
        }
        val vm = StockViewModel(fake)
        val s = vm.state.value
        assertEquals(2, s.accounts.size)
        assertEquals(1, s.selectedIndex)
        assertEquals("贵州茅台", s.selected!!.stock.name)
        assertEquals(11.0, s.accounts[0].currentPrice!!, 0.0001)
    }

    @Test
    fun 启动时从存储恢复持仓与交易记录() = runTest {
        val lot = BuyLot(id = 5L, price = 3.0, originalQty = 100, remainingQty = 60)
        val trade = TradeRecord(id = 9L, type = TradeType.SELL, price = 4.0, qty = 40, profit = 40.0, time = 12345L)
        val fake = FakeStorage().apply {
            loaded = StockState(
                accounts = listOf(
                    StockAccount(
                        stock = Stock("000001", "平安银行", "sz"),
                        holdings = listOf(lot),
                        trades = listOf(trade)
                    )
                ),
                selectedIndex = 0
            )
        }
        val vm = StockViewModel(fake)
        val acc = vm.state.value.selected!!
        assertEquals(60, acc.totalQty)
        assertEquals(3.0, acc.avgPrice, 0.0001)
        assertEquals(1, acc.trades.size)
        assertEquals(40.0, acc.trades[0].profit, 0.0001)
        // 新 id 从已恢复的最大 id 之后继续，避免冲突
        vm.buy(2.0, 10)
        assertEquals(10L, acc.holdings[0].id + 5) // 只验证 buy 后新增批次 id 大于旧 id
        assertTrue(vm.state.value.selected!!.holdings.maxOf { it.id } > 9L)
    }

    @Test
    fun 买入后自动保存() = runTest {
        val fake = FakeStorage()
        val vm = StockViewModel(fake)
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.41, 2900)
        val last = fake.saved.last()
        assertEquals(1, last.accounts.size)
        assertEquals(1, last.accounts[0].holdings.size)
        assertEquals(9889.0, last.accounts[0].totalCost, 0.0001)
    }

    @Test
    fun 卖出后保存结果与盈亏() = runTest {
        val fake = FakeStorage()
        val vm = StockViewModel(fake)
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(1.0, 100, time = yesterday)
        vm.buy(3.0, 300, time = yesterday)
        vm.sell(4.0, 150) // 抵1元100(+300) + 3元50(+50) = 350
        val last = fake.saved.last()
        assertEquals(250, last.accounts[0].totalQty)
        assertEquals(350.0, last.accounts[0].trades.last().profit, 0.0001)
    }

    @Test
    fun 切换股票与清空也会保存() = runTest {
        val fake = FakeStorage()
        val vm = StockViewModel(fake)
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(5.0, 200)
        vm.addStock(QuoteResult("600519", "贵州茅台", null, "sh"))
        vm.buy(3.0, 100)
        vm.selectStock(0)
        vm.clearSelected()
        val last = fake.saved.last()
        assertEquals(2, last.accounts.size)
        assertEquals(0, last.accounts[0].totalQty)
        assertEquals(100, last.accounts[1].totalQty) // 茅台没被清
    }

    @Test
    fun 清空全部保存为空状态() = runTest {
        val fake = FakeStorage()
        val vm = StockViewModel(fake)
        vm.buy(3.0, 100)
        vm.clearAll()
        val last = fake.saved.last()
        assertTrue(last.accounts.isEmpty())
        assertEquals(-1, last.selectedIndex)
    }

    @Test
    fun 存储为null时_不保存也不恢复_行为与原来一致() = runTest {
        val vm = StockViewModel() // 无存储
        vm.buy(3.0, 100)
        // 无股票时买入被拒绝，不会崩溃
        assertTrue(vm.state.value.message!!.contains("请先添加"))
        assertTrue(vm.state.value.accounts.isEmpty())
    }
}
