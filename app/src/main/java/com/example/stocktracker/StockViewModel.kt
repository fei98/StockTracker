package com.example.stocktracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StockViewModel(
    private val storage: Storage? = null,
    private val api: StockApi = StockApi()
) : ViewModel() {

    private val _state = MutableStateFlow(StockState())
    val state: StateFlow<StockState> = _state.asStateFlow()

    private var nextId = 1L
    private var searchSeq = 0

    init {
        storage?.let { restore(it) }
    }

    /** 启动时从持久化存储恢复数据 */
    private fun restore(storage: Storage) {
        viewModelScope.launch {
            val s = storage.load()
            _state.value = s
            nextId = maxOf(
                s.accounts.flatMap { it.holdings.map { h -> h.id } }.maxOrNull() ?: 0L,
                s.accounts.flatMap { it.trades.map { t -> t.id } }.maxOrNull() ?: 0L
            ) + 1
            _state.update { it.copy(message = if (s.accounts.isNotEmpty()) "已恢复上次保存的数据" else null) }
        }
    }

    /** 每次数据变更后异步保存到本地 */
    private fun persist() {
        val snapshot = _state.value
        storage?.let { st -> viewModelScope.launch { st.save(snapshot) } }
    }

    // ---------------- 股票管理 ----------------

    /** 输入代码时自动查询名称/现价（带过期保护：只接受最新一次查询的结果） */
    fun searchStock(input: String) {
        if (inferMarket(input.trim()) == null) {
            _state.update { it.copy(searchResult = null, isSearching = false, searchedCode = null, searchHint = null) }
            return
        }
        val token = ++searchSeq
        _state.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.searchStock(input.trim()) }
            if (token != searchSeq) return@launch
            _state.update { s ->
                when {
                    result == null -> s.copy(
                        isSearching = false, searchResult = null,
                        searchedCode = input.trim(), searchHint = "未找到该股票，请检查代码"
                    )
                    s.accounts.any { it.stock.code == result.code && it.stock.market == result.market }
                    -> s.copy(
                        isSearching = false, searchResult = null,
                        searchedCode = input.trim(), searchHint = "该股票已在列表中"
                    )
                    else -> s.copy(
                        isSearching = false, searchResult = result,
                        searchedCode = input.trim(), searchHint = null
                    )
                }
            }
        }
    }

    fun clearSearch() = _state.update {
        it.copy(searchResult = null, isSearching = false, searchedCode = null, searchHint = null)
    }

    /** 确认添加股票并自动选中（同时记录查询到的现价） */
    fun addStock(result: QuoteResult) {
        _state.update { s ->
            if (s.accounts.any { it.stock.code == result.code && it.stock.market == result.market }) {
                s.copy(searchResult = null, message = "该股票已在列表中")
            } else {
                val acc = StockAccount(
                    stock = Stock(result.code, result.name, result.market),
                    currentPrice = result.price
                )
                s.copy(
                    accounts = s.accounts + acc,
                    selectedIndex = s.accounts.size,
                    searchResult = null,
                    searchedCode = null,
                    searchHint = null,
                    message = "已添加：${result.name} (${result.market}${result.code})"
                )
            }
        }
        persist()
    }

    fun selectStock(index: Int) {
        _state.update { it.copy(selectedIndex = index) }
        persist()
    }

    /** 刷新选中股票的实时价格并作为现价 */
    fun refreshPrice() {
        val stock = _state.value.selected?.stock ?: return
        viewModelScope.launch {
            val price = withContext(Dispatchers.IO) { api.fetchPrice(stock) }
            _state.update { s ->
                if (price == null) s.copy(message = "行情刷新失败，请检查网络")
                else s.copy(
                    accounts = updateSelected(s) { it.copy(currentPrice = price) },
                    message = "已刷新现价：${formatPrice(price)} 元"
                )
            }
            persist()
        }
    }

    // ---------------- 买入/卖出（作用于当前选中股票） ----------------

    fun buy(price: Double, qty: Int, time: Long = System.currentTimeMillis()) {
        if (price <= 0 || qty <= 0) { notify("请输入正确的单价和数量"); return }
        val idx = _state.value.selectedIndex
        if (idx < 0) { notify("请先添加并选择股票"); return }
        val lot = BuyLot(id = nextId++, price = price, originalQty = qty, remainingQty = qty, time = time)
        _state.update { s ->
            s.copy(
                accounts = updateSelected(s) { a ->
                    a.copy(
                        holdings = a.holdings + lot,
                        trades = a.trades + TradeRecord(nextId++, TradeType.BUY, price, qty)
                    )
                },
                message = "买入成功：${formatPrice(price)} 元 × $qty 股"
            )
        }
        persist()
    }

    /**
     * 卖出：按“最低价优先抵扣”匹配可卖持仓（T+1：当日买入的 A 股批次不可卖）。
     * 例如持仓含 1元100、3元300、5元200，卖出300股 @4元：
     *   先抵扣1元100股（盈利300），再抵扣3元200股（盈利200），合计盈利500，3元剩100股。
     */
    fun sell(price: Double, qty: Int) {
        if (price <= 0 || qty <= 0) { notify("请输入正确的单价和数量"); return }
        val s = _state.value
        val acc = s.selected ?: run { notify("请先添加并选择股票"); return }
        if (acc.totalQty <= 0) { notify("当前无持仓，无法卖出"); return }
        if (acc.sellableQty <= 0) { notify("今日买入的股票需 T+1，次日才能卖出"); return }
        if (qty > acc.sellableQty) { notify("卖出数量超过持仓可卖数量（${acc.sellableQty} 股）"); return }

        val sellableIds = acc.sellableHoldings.map { it.id }.toSet()
        var toSell = qty
        var profit = 0.0
        val remaining = mutableListOf<BuyLot>()

        // 冻结批次（当日买入）原样保留，不参与卖出
        acc.holdings.filter { it.id !in sellableIds }.forEach { remaining.add(it) }
        // 可卖批次按单价从低到高抵扣
        for (lot in acc.sellableHoldings.sortedBy { it.price }) {
            if (toSell <= 0) {
                remaining.add(lot); continue
            }
            val take = minOf(lot.remainingQty, toSell)
            profit += (price - lot.price) * take
            toSell -= take
            val left = lot.remainingQty - take
            if (left > 0) remaining.add(lot.copy(remainingQty = left))
        }

        _state.update {
            it.copy(
                accounts = updateSelected(it) { a ->
                    a.copy(
                        holdings = remaining,
                        trades = a.trades + TradeRecord(nextId++, TradeType.SELL, price, qty, profit)
                    )
                },
                message = "卖出成功：${formatPrice(price)} 元 × $qty 股，本次盈利 ${formatMoney(profit)} 元"
            )
        }
        persist()
    }

    /** 手动设置选中股票的现价，用于计算各批次浮动盈亏 */
    fun setCurrentPrice(price: Double?) {
        if (price != null && price < 0) { notify("现价不能为负"); return }
        val idx = _state.value.selectedIndex
        if (idx < 0) { notify("请先添加并选择股票"); return }
        _state.update { s ->
            s.copy(
                accounts = updateSelected(s) { it.copy(currentPrice = price) },
                message = if (price != null) "已更新现价 ${formatPrice(price)} 元" else "已清除现价"
            )
        }
        persist()
    }

    /** 清空当前选中股票的数据 */
    fun clearSelected() {
        val name = _state.value.selected?.stock?.name ?: return
        _state.update { s ->
            s.copy(
                accounts = updateSelected(s) { StockAccount(it.stock) },
                message = "已清空「$name」的数据"
            )
        }
        persist()
    }

    /** 一键清空所有股票 */
    fun clearAll() {
        _state.update { StockState(message = "已清空所有股票数据") }
        persist()
    }

    /** 清除提示消息 */
    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun notify(msg: String) = _state.update { it.copy(message = msg) }

    /** 对当前选中的账户应用变换，返回新账户列表 */
    private fun updateSelected(s: StockState, transform: (StockAccount) -> StockAccount): List<StockAccount> =
        s.accounts.mapIndexed { i, a -> if (i == s.selectedIndex) transform(a) else a }
}
