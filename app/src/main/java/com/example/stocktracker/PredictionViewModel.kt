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
        val canSell: Boolean = false,
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
                        HoldingSignal(acc.stock, signal, acc.prevClose, acc.totalQty > 0, acc.sellableQty > 0, stats)
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

/**
 * 全部持仓信号按"最需要操作优先"排序（纯函数，可单测）。
 * 1. 优先级：BUY/SELL 并列最前 > HOLD > WATCH > NO_TRADE > 收集/休市(pending) > 获取失败(null)；
 * 2. 有持仓但当日买入被 T+1 冻结、本要卖却降级为 HOLD 的，视为 SELL 同档（最前），便于用户优先看到；
 * 3. 同优先级内按分数降序（更强的信号排前面），null/pending 视为分数 0。
 */
fun sortHoldingSignals(rows: List<PredictionViewModel.HoldingSignal>): List<PredictionViewModel.HoldingSignal> =
    rows.sortedWith(compareBy<PredictionViewModel.HoldingSignal> { rankOf(it) }
        .thenByDescending { h -> h.signal?.takeIf { it.pending == null }?.score ?: 0.0 })

/** 是否"想卖但被 T+1 冻结无可卖股"（用户需优先看到但无法立即卖） */
fun isFreezeBarredSell(h: PredictionViewModel.HoldingSignal): Boolean {
    val s = h.signal ?: return false
    if (h.canSell || !h.hasPosition) return false
    // 已降级为 HOLD 且含 T+1 冻结原因（v9 文案）：说明原为 SELL 但因当日买入不可卖
    return s.action == IntradayAction.HOLD &&
        s.reasons.any { it.contains("T+1") || it.contains("冻结") }
}

private fun rankOf(h: PredictionViewModel.HoldingSignal): Int {
    val s = h.signal
    return when {
        s == null -> 5
        s.pending != null -> 4
        isFreezeBarredSell(h) -> 0
        else -> s.action.priority()
    }
}
