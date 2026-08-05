package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 模拟行情接口（不联网） */
class FakeApi(
    private val result: QuoteResult? = null,
    private val price: Double? = null,
    private val prices: Map<String, Double> = emptyMap(),
    private val quotes: Map<String, QuoteFields> = emptyMap()
) : StockApi() {
    override suspend fun searchStock(input: String): QuoteResult? = result
    override suspend fun fetchQuote(stock: Stock): QuoteFields? {
        quotes[stock.marketCode]?.let { return it }
        val p = prices[stock.marketCode] ?: price ?: return null
        return QuoteFields(stock.name, stock.code, p, null, null, null, null, "")
    }
    override suspend fun fetchPrice(stock: Stock): Double? = fetchQuote(stock)?.price
}

/** 覆盖：搜索股票名称/现价、搜索失败提示、重复股票提示、清除搜索 */
class SearchTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 搜索走真实 IO 线程，轮询等待状态更新 */
    private fun await(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("等待状态更新超时")
            Thread.sleep(10)
        }
    }

    @Test
    fun 搜索成功_返回名称现价并记录已查代码() {
        val vm = StockViewModel(api = FakeApi(QuoteResult("000001", "平安银行", 10.5, "sz")))
        vm.searchStock("000001")
        await { vm.state.value.searchResult != null }
        val s = vm.state.value
        assertEquals("平安银行", s.searchResult!!.name)
        assertEquals(10.5, s.searchResult!!.price!!, 0.0001)
        assertEquals("000001", s.searchedCode)
        assertNull(s.searchHint)
        assertEquals(false, s.isSearching)
    }

    @Test
    fun 搜索失败_提示未找到但不弹全局消息() {
        val vm = StockViewModel(api = FakeApi(null))
        vm.searchStock("999999")
        await { !vm.state.value.isSearching }
        val s = vm.state.value
        assertNull(s.searchResult)
        assertEquals("999999", s.searchedCode)
        assertTrue(s.searchHint!!.contains("未找到"))
        assertNull(s.message) // 不弹全局 Snackbar
    }

    @Test
    fun 搜索已在列表中的股票_提示已在列表中() {
        val vm = StockViewModel(api = FakeApi(QuoteResult("000001", "平安银行", null, "sz")))
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.searchStock("000001")
        await { !vm.state.value.isSearching }
        val s = vm.state.value
        assertNull(s.searchResult)
        assertTrue(s.searchHint!!.contains("已在列表"))
    }

    @Test
    fun 搜索成功后清除搜索_状态复位() {
        val vm = StockViewModel(api = FakeApi(QuoteResult("600519", "贵州茅台", 1500.0, "sh")))
        vm.searchStock("600519")
        await { vm.state.value.searchResult != null }
        vm.clearSearch()
        val s = vm.state.value
        assertNull(s.searchResult)
        assertNull(s.searchedCode)
        assertNull(s.searchHint)
        assertEquals(false, s.isSearching)
    }

    @Test
    fun 非法输入_不触发查询() {
        val vm = StockViewModel(api = FakeApi(QuoteResult("000001", "平安银行", null, "sz")))
        vm.searchStock("12")
        val s = vm.state.value
        assertNull(s.searchResult)
        assertNull(s.searchedCode)
        assertNull(s.searchHint)
    }

    @Test
    fun 一键刷新全部_更新所有股票现价() {
        val api = FakeApi(prices = mapOf("sz000001" to 10.5, "sh600519" to 1500.0))
        val vm = StockViewModel(api = api)
        vm.addStock(QuoteResult("000001", "平安银行", 9.0, "sz"))
        vm.addStock(QuoteResult("600519", "贵州茅台", 1400.0, "sh"))
        vm.refreshAll()
        await { vm.state.value.message?.contains("已刷新全部") == true }
        val s = vm.state.value
        assertEquals(10.5, s.accounts[0].currentPrice!!, 0.0001)
        assertEquals(1500.0, s.accounts[1].currentPrice!!, 0.0001)
    }

    @Test
    fun 一键刷新全部_部分失败只更新成功的() {
        val api = FakeApi(prices = mapOf("sz000001" to 10.5))
        val vm = StockViewModel(api = api)
        vm.addStock(QuoteResult("000001", "平安银行", 9.0, "sz"))
        vm.addStock(QuoteResult("600519", "贵州茅台", 1400.0, "sh"))
        vm.refreshAll()
        await { vm.state.value.message?.contains("1/2") == true }
        val s = vm.state.value
        assertEquals(10.5, s.accounts[0].currentPrice!!, 0.0001)
        assertEquals(1400.0, s.accounts[1].currentPrice!!, 0.0001) // 刷新失败保留旧价
    }

    @Test
    fun 刷新现价_同时记录昨收用于涨跌幅() {
        val api = FakeApi(quotes = mapOf(
            "sz159915" to QuoteFields("创业板ETF华夏", "159915", 3.542, 3.514, null, 0.8, null, "")
        ))
        val vm = StockViewModel(api = api)
        vm.addStock(QuoteResult("159915", "创业板ETF华夏", 3.0, "sz"))
        vm.refreshPrice()
        await { vm.state.value.selected?.prevClose == 3.514 }
        val acc = vm.state.value.selected!!
        assertEquals(3.542, acc.currentPrice!!, 0.0001)
        assertEquals(3.514, acc.prevClose!!, 0.0001)
        // (3.542-3.514)/3.514 ≈ +0.80%
        assertEquals(0.797, acc.dayChangePct!!, 0.01)
    }
}
