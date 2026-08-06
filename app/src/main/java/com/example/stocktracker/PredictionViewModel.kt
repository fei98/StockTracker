package com.example.stocktracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 竞价预测 UI 状态（绑定当前选中的股票） */
class PredictionViewModel(
    private val engine: PredictionEngine,
    private val store: SnapshotStore
) : ViewModel() {

    data class UiState(
        val stock: Stock? = null,
        val running: Boolean = false,
        val result: PredictionResult? = null,
        val allResults: List<PredictionResult> = emptyList(),
        val observation: ObservationInfo? = null,
        val inObservationPhase: Boolean = false,
        val stats: Map<TargetType, WalkForwardStats> = emptyMap(),
        val sectorSource: SectorSource? = null,   // 板块联动来源
        val sectorDetail: String = "数据积累中",   // 联动名单说明
        val error: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    /** 切股时刷新该股数据 */
    fun refresh(stock: Stock? = null) {
        val s = stock ?: _ui.value.stock ?: return
        val date = PredictionEngine.today()
        val resolved = SectorResolver.resolve(s, store)
        _ui.update {
            it.copy(
                stock = s,
                result = store.loadLastResult(s.marketCode)?.takeIf { r -> r.date == date },
                observation = engine.observationInfo(s, date),
                inObservationPhase = PredictionEngine.isObservationPhase(System.currentTimeMillis()),
                stats = engine.walkForwardStats(s, date),
                sectorSource = resolved.source,
                sectorDetail = resolved.detail,
                error = null
            )
        }
    }

    /** 手动选行业（null = 恢复自动推荐/清除配置） */
    fun setSector(stock: Stock, industry: String?) {
        store.setUserSector(stock.marketCode, industry)
        refresh(stock)
    }

    /** 手动预测当前选中股票；9:20 前返回观察区提示 */
    fun predict(stock: Stock, hasPosition: Boolean) {
        if (PredictionEngine.isObservationPhase(System.currentTimeMillis())) {
            _ui.update { it.copy(error = "当前为竞价观察区（9:15–9:20），9:20 后可预测") }
            return
        }
        if (!PredictionEngine.isPredictable(stock)) {
            _ui.update { it.copy(error = "港股/美股无集合竞价，不支持预测") }
            return
        }
        if (_ui.value.running) return
        viewModelScope.launch {
            _ui.update { it.copy(running = true, error = null) }
            val result = engine.runPrediction(stock, hasPosition)
            _ui.update {
                it.copy(
                    running = false,
                    result = result,
                    error = if (result == null) "预测失败：行情获取异常，请检查网络后重试" else null
                )
            }
            refresh(stock)
        }
    }

    /** 一键预测全部 A 股持仓（结果展示在账户总览） */
    fun predictAll(accounts: List<StockAccount>) {
        if (PredictionEngine.isObservationPhase(System.currentTimeMillis())) {
            _ui.update { it.copy(error = "当前为竞价观察区（9:15–9:20），9:20 后可预测") }
            return
        }
        if (_ui.value.running) return
        val stocks = accounts.filter { PredictionEngine.isPredictable(it.stock) }
        if (stocks.isEmpty()) {
            _ui.update { it.copy(error = "没有可预测的 A 股持仓") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(running = true, error = null) }
            val results = stocks.mapNotNull { acc ->
                engine.runPrediction(acc.stock, hasPosition = acc.totalQty > 0)
            }
            _ui.update {
                it.copy(
                    running = false,
                    allResults = results,
                    error = if (results.isEmpty()) "预测失败：行情获取异常，请检查网络后重试" else null
                )
            }
        }
    }
}
