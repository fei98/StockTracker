package com.example.stocktracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 盘中实时信号 UI 状态（当前股票 + 全部持仓） */
class PredictionViewModel(
    private val api: StockApi = StockApi(),
    private val signalStore: IntradaySignalStore? = null
) : ViewModel() {

    data class HoldingSignal(
        val stock: Stock,
        val signal: IntradaySignal?,
        val prevClose: Double?,
        val hasPosition: Boolean,
        val stats: IntradaySignalStats? = null
    )

    data class UiState(
        val stock: Stock? = null,
        val signal: IntradaySignal? = null,
        val allSignals: List<HoldingSignal> = emptyList(),
        val running: Boolean = false,
        val error: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var currentJob: Job? = null
    private var currentSeq = 0
    private var allJob: Job? = null
    private var allSeq = 0

    /** 刷新当前选中股票的盘中信号（竞态：后发请求生效） */
    fun refresh(stock: Stock, prevClose: Double?, hasPosition: Boolean, canSell: Boolean = true) {
        val token = ++currentSeq
        currentJob?.cancel()
        _ui.update { it.copy(stock = stock) }
        currentJob = viewModelScope.launch {
            val (index, pts) = withContext(Dispatchers.IO) {
                val index = fetchIndexPoints(stock)
                val pts = api.fetchIntraday(stock)
                index to pts
            }
            if (token != currentSeq) return@launch
            _ui.update {
                it.copy(
                    running = false,
                    signal = pts?.let { p ->
                        IntradaySignalEvaluator.evaluate(
                            p, index, prevClose, hasPosition, System.currentTimeMillis(),
                            priceLimitPct = priceLimitPct(stock),
                            canSell = canSell
                        )
                    },
                    error = if (pts == null) "行情获取失败，请检查网络" else null
                )
            }
        }
    }

    /** 刷新全部持仓的盘中信号（并发拉取，指数 60 秒缓存复用） */
    fun refreshAll(accounts: List<StockAccount>) {
        val stocks = accounts.filter { PredictionEngine.isPredictable(it.stock) }
        if (stocks.isEmpty()) return
        val token = ++allSeq
        allJob?.cancel()
        _ui.update { it.copy(running = true) }
        allJob = viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                stocks.map { acc ->
                    async {
                        val index = fetchIndexPoints(acc.stock)
                        val pts = api.fetchIntraday(acc.stock)
                        val signal = pts?.let {
                            IntradaySignalEvaluator.evaluate(
                                it, index, acc.prevClose, acc.totalQty > 0, System.currentTimeMillis(),
                                priceLimitPct = priceLimitPct(acc.stock),
                                canSell = acc.sellableQty > 0
                            )
                        }
                        val stats = signalStore?.let {
                            statsOf(it.loadSnapshots(acc.stock.marketCode, PredictionEngine.today()))
                        }
                        HoldingSignal(acc.stock, signal, acc.prevClose, acc.totalQty > 0, stats)
                    }
                }.awaitAll()
            }
            if (token != allSeq) return@launch
            _ui.update { it.copy(running = false, allSignals = rows) }
        }
    }

    private suspend fun fetchIndexPoints(stock: Stock): List<MinutePoint>? =
        IndexMinuteCache.fetch(api, stock)
}
