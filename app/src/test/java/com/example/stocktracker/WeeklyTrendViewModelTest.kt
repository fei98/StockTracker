package com.example.stocktracker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 覆盖：未来一周趋势 ViewModel（切换股票的竞态保护、加载期清空旧结果） */
class WeeklyTrendViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun bars(n: Int, step: Double = 0.0, start: Double = 10.0): List<DailyBar> {
        var p = start
        return (0 until n).map { i ->
            val b = DailyBar("2026-%02d-%02d".format(1 + i / 28, 1 + i % 28), p, p, p * 1.01, p * 0.99, 100L)
            p += step
            b
        }
    }

    /** 假 API：按 code 提供 K 线，可选 gate 控制某只股票挂起（模拟慢请求） */
    private class FakeApi(
        private val klines: Map<String, List<DailyBar>>,
        private val gates: Map<String, CompletableDeferred<Unit>>
    ) : StockApi() {
        override suspend fun fetchDailyKline(stock: Stock, count: Int): List<DailyBar>? {
            gates[stock.marketCode]?.await()
            return klines[stock.marketCode]
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun 刷新_先A后B最终显示B的结果() = runBlocking {
        val api = FakeApi(mapOf(
            "sz000001" to bars(160, step = 0.05),
            "sz000002" to bars(160, step = -0.05)
        ), emptyMap())
        val vm = WeeklyTrendViewModel(api)
        vm.refresh(Stock("000001", "A", "sz"))
        vm.refresh(Stock("000002", "B", "sz"))
        // 最终必须归属最新选中的 B，慢请求 A 不得覆盖
        withTimeout(5000) { while (vm.ui.value.result?.stockCode != "sz000002") delay(10) }
        assertEquals("sz000002", vm.ui.value.result!!.stockCode)
        assertEquals("sz000002", vm.ui.value.stock!!.marketCode)
        assertEquals(false, vm.ui.value.loading)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun 刷新_加载期间清空旧结果() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(mapOf("sz000001" to bars(160, step = 0.05)), mapOf("sz000001" to gate))
        val vm = WeeklyTrendViewModel(api)
        vm.refresh(Stock("000001", "A", "sz"))
        // 请求挂起中：loading=true、result=null（不得残留上一只股票的预测）
        assertTrue(vm.ui.value.loading)
        assertNull(vm.ui.value.result)
        gate.complete(Unit)
        withTimeout(5000) { while (vm.ui.value.loading) delay(10) }
        assertNotNull(vm.ui.value.result)
        assertEquals("sz000001", vm.ui.value.result!!.stockCode)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun 刷新_切到非A股后慢请求不得覆盖() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(mapOf("sz000001" to bars(160, step = 0.05)), mapOf("sz000001" to gate))
        val vm = WeeklyTrendViewModel(api)
        vm.refresh(Stock("000001", "A", "sz"))
        // A 股请求挂起中，loading=true
        assertTrue(vm.ui.value.loading)
        // 切到港股（非 A 股）→ 立即置空，且应作废在途 A 股请求
        vm.refresh(Stock("00700", "腾讯", "hk"))
        assertEquals("hk00700", vm.ui.value.stock!!.marketCode)
        assertNull(vm.ui.value.result)
        assertEquals(false, vm.ui.value.loading)
        // 放行 A 股慢请求 → 若未作废，会回写 A 股预测导致标题/内容错位
        gate.complete(Unit)
        delay(100)
        assertEquals("hk00700", vm.ui.value.stock!!.marketCode)
        assertNull(vm.ui.value.result)
    }
}
