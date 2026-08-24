package com.example.stocktracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 未来一周趋势预测状态（当前选中股票 + 全部持仓）。
 * 日K线进程级缓存（按天），预测为纯计算，按需刷新即可（多日维度，无需 30 秒自动刷新）。
 */
class WeeklyTrendViewModel(
    private val api: StockApi = StockApi()
) : ViewModel() {

    data class HoldingTrend(
        val stock: Stock,
        val result: WeeklyTrendResult?
    )

    data class UiState(
        val stock: Stock? = null,
        val result: WeeklyTrendResult? = null,
        val allTrends: List<HoldingTrend> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** K线缓存：marketCode → (缓存日期, bars)；指数前缀 "idx:"。跨 Default/IO 线程访问，需线程安全 */
    private val klineCache = ConcurrentHashMap<String, Pair<String, List<DailyBar>>>()

    /** 刷新序号令牌：新的刷新会作废旧的在途刷新，防止慢请求后到覆盖新结果 */
    private var refreshSeq = 0L

    private fun todayCN(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = WeeklyTrendEvaluator.CN_TZ }
        .format(Date())

    /** 刷新当前选中股票的周趋势 */
    fun refresh(stock: Stock, force: Boolean = false) {
        if (!isAStock(stock)) {
            // 非 A 股也要作废在途刷新：否则切到港股/美股后，在途 A 股慢请求完成时
            // seq 未变会照样写回，把标题/内容错位成 A 股预测
            refreshSeq++
            _ui.update { it.copy(stock = stock, result = null, loading = false, error = null) }
            return
        }
        val seq = ++refreshSeq
        viewModelScope.launch {
            // 加载期清空旧结果，避免上一只股票的预测错位展示
            _ui.update { it.copy(stock = stock, result = null, loading = true, error = null) }
            val bars = withContext(Dispatchers.IO) { fetchKline(stock, force) }
            val index = withContext(Dispatchers.IO) { fetchIndexKline(stock, force) }
            val result = bars?.let {
                withContext(Dispatchers.Default) {
                    WeeklyTrendEvaluator.forecast(it, index, stock.marketCode, stock.name)
                }
            }
            if (seq != refreshSeq) return@launch
            _ui.update {
                it.copy(
                    loading = false,
                    result = result,
                    error = if (bars == null) "K线获取失败，请检查网络" else null
                )
            }
        }
    }

    /** 刷新全部 A 股持仓的周趋势（并行拉取，预测计算在后台线程） */
    fun refreshAll(accounts: List<StockAccount>, force: Boolean = false) {
        val stocks = accounts.map { it.stock }.filter { isAStock(it) }
        if (stocks.isEmpty()) return
        val seq = ++refreshSeq
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val rows = stocks.map { stock ->
                async(Dispatchers.Default) {
                    val bars = fetchKline(stock, force)
                    val index = fetchIndexKline(stock, force)
                    val result = bars?.let {
                        WeeklyTrendEvaluator.forecast(it, index, stock.marketCode, stock.name)
                    }
                    HoldingTrend(stock, result)
                }
            }.awaitAll()
            if (seq != refreshSeq) return@launch
            _ui.update { it.copy(loading = false, allTrends = rows) }
        }
    }

    private suspend fun fetchKline(stock: Stock, force: Boolean): List<DailyBar>? {
        val key = stock.marketCode
        val today = todayCN()
        val cached = klineCache[key]
        if (!force && cached != null && cached.first == today) return cached.second
        val bars = api.fetchDailyKline(stock, WeeklyTrendEvaluator.MAX_BARS) ?: return cached?.second
        klineCache[key] = today to bars
        return bars
    }

    private suspend fun fetchIndexKline(stock: Stock, force: Boolean): List<DailyBar>? {
        val code = indexCodeFor(stock) ?: return null
        if (code.length < 3) return null
        val key = "idx:$code"
        val today = todayCN()
        val cached = klineCache[key]
        if (!force && cached != null && cached.first == today) return cached.second
        val indexStock = Stock(code.substring(2), "", code.substring(0, 2))
        val bars = api.fetchDailyKline(indexStock, WeeklyTrendEvaluator.MAX_BARS) ?: return cached?.second
        klineCache[key] = today to bars
        return bars
    }
}
