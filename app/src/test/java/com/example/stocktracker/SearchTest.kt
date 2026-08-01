package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 模拟行情接口（不联网） */
class FakeApi(private val result: QuoteResult? = null, private val price: Double? = null) : StockApi() {
    override suspend fun searchStock(input: String): QuoteResult? = result
    override suspend fun fetchPrice(stock: Stock): Double? = price
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
}
