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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 盘中实时信号 UI 状态（当前股票 + 全部持仓） */
class PredictionViewModel(
    private val api: StockApi = StockApi()
) : ViewModel() {

    data class HoldingSignal(
        val stock: Stock,
        val signal: IntradaySignal?,
        val prevClose: Double?,
        val hasPosition: Boolean
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

    // 指数分钟线缓存（60 秒 TTL），个股分钟线按需刷新
    private var indexCacheCode: String? = null
    private var indexCacheAt = 0L
    private var indexCachePoints: List<MinutePoint>? = null

    // 指数拉取互斥锁（评审 §1.3⑦）：refreshAll 多股共享同指数代码时，并发只发起一次真实请求
    private val indexMutex = Mutex()

    private var currentJob: Job? = null
    private var currentSeq = 0
    private var allJob: Job? = null
    private var allSeq = 0

    /** 刷新当前选中股票的盘中信号（竞态：后发请求生效） */
    fun refresh(stock: Stock, prevClose: Double?, hasPosition: Boolean) {
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
                        IntradaySignalEvaluator.evaluate(p, index, prevClose, hasPosition, System.currentTimeMillis())
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
                                it, index, acc.prevClose, acc.totalQty > 0, System.currentTimeMillis()
                            )
                        }
                        HoldingSignal(acc.stock, signal, acc.prevClose, acc.totalQty > 0)
                    }
                }.awaitAll()
            }
            if (token != allSeq) return@launch
            _ui.update { it.copy(running = false, allSignals = rows) }
        }
    }

    private suspend fun fetchIndexPoints(stock: Stock): List<MinutePoint>? {
        val code = TencentMarketDataApi.SECTOR_MAP[stock.marketCode]?.indexCode
            ?: TencentMarketDataApi.FALLBACK_CONFIG.indexCode
        val now = System.currentTimeMillis()
        if (indexCacheCode == code && now - indexCacheAt < 60_000) return indexCachePoints
        return indexMutex.withLock {
            val now2 = System.currentTimeMillis()
            if (indexCacheCode == code && now2 - indexCacheAt < 60_000) return@withLock indexCachePoints
            val indexStock = Stock(code = code.substring(2), name = "", market = code.substring(0, 2))
            val pts = api.fetchIntraday(indexStock)
            indexCacheCode = code
            indexCacheAt = now2
            indexCachePoints = pts
            pts
        }
    }
}
