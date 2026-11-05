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

class StockViewModel(
    private val storage: Storage? = null,
    private val api: StockApi = StockApi(),
    private val feeStore: FeeConfigStore? = null,
    initialFeeConfig: FeeConfig? = null
) : ViewModel() {

    private val _state = MutableStateFlow(StockState())
    val state: StateFlow<StockState> = _state.asStateFlow()

    // Session-only navigation state; intentionally excluded from Storage and saved instance state.
    private val _overviewTab = MutableStateFlow<OverviewTab?>(OverviewTab.FLOAT)
    val overviewTab: StateFlow<OverviewTab?> = _overviewTab.asStateFlow()

    fun selectOverviewTab(tab: OverviewTab?) {
        _overviewTab.value = tab
    }

    /** 当前费率配置（内存态；feeStore 非空时持久化） */
    var feeConfig: FeeConfig = initialFeeConfig ?: feeStore?.load() ?: FeeConfig()
        private set

    /** 今日分时数据（按当前选中股票，弹窗内缓存） */
    private val _intraday = MutableStateFlow<List<MinutePoint>?>(null)
    val intraday: StateFlow<List<MinutePoint>?> = _intraday.asStateFlow()

    /** 分时加载中 */
    private val _intradayLoading = MutableStateFlow(false)
    val intradayLoading: StateFlow<Boolean> = _intradayLoading.asStateFlow()

    private var intradayStockKey: String? = null

    /** 加载当前选中股票的今日分时（切股后首次点分时重新拉取；force=true 强制刷新） */
    fun loadIntraday(force: Boolean = false) {
        val acc = _state.value.selected ?: return
        val stock = acc.stock
        if (_intradayLoading.value) return
        if (!force && intradayStockKey == stock.marketCode) return // 已缓存该股
        _intradayLoading.value = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { api.fetchIntraday(stock) }
            _intraday.value = data
            intradayStockKey = if (data != null) stock.marketCode else null
            _intradayLoading.value = false
        }
    }

    private var nextId = 1L
    private var searchSeq = 0

    /** 更新费率设置 */
    fun updateFeeConfig(c: FeeConfig) {
        feeConfig = c
        feeStore?.save(c)
        notify("已更新费率设置（历史记录费用不回算，仅影响之后的操作）")
    }

    init {
        storage?.let { restore(it) }
    }

    /** 启动时从持久化存储恢复数据；并做一次性历史手续费重算迁移 */
    private fun restore(storage: Storage) {
        viewModelScope.launch {
            var s = storage.load()
            // 一次性迁移：旧数据（费用功能上线前）手续费为 0，用当前费率重算补齐
            if (feeStore != null && feeStore.needsFeeMigration()) {
                s = recalcHistoricalFees(s, feeConfig)
                feeStore.markFeeMigrated()
            }
            _state.value = s
            nextId = maxOf(
                s.accounts.flatMap { it.holdings.map { h -> h.id } }.maxOrNull() ?: 0L,
                s.accounts.flatMap { it.trades.map { t -> t.id } }.maxOrNull() ?: 0L
            ) + 1
            _state.update { it.copy(message = if (s.accounts.isNotEmpty()) "已恢复上次保存的数据" else null) }
            if (s.accounts.isNotEmpty()) persist()
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
                    currentPrice = result.price,
                    prevClose = result.prevClose
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

    /** 删除某只股票（连同持仓/交易记录/行情），并修正选中索引 */
    fun removeStock(index: Int) {
        val s = _state.value
        val acc = s.accounts.getOrNull(index) ?: return
        val name = acc.stock.name
        val newAccounts = s.accounts.filterIndexed { i, _ -> i != index }
        val newIndex = when {
            newAccounts.isEmpty() -> -1
            s.selectedIndex == index -> minOf(index, newAccounts.size - 1) // 删选中 → 选后一只（没有则前一只）
            s.selectedIndex > index -> s.selectedIndex - 1                 // 删前面的 → 索引前移
            else -> s.selectedIndex
        }
        _state.update {
            it.copy(
                accounts = newAccounts,
                selectedIndex = newIndex,
                message = "已删除「$name」及其全部数据"
            )
        }
        persist()
    }

    /** 刷新选中股票的实时价格并作为现价 */
    fun refreshPrice() {
        val stock = _state.value.selected?.stock ?: return
        viewModelScope.launch {
            val quote = withContext(Dispatchers.IO) { api.fetchQuote(stock) }
            _state.update { s ->
                if (quote?.price == null) s.copy(message = "行情刷新失败，请检查网络")
                else s.copy(
                    accounts = updateSelected(s) { it.copy(currentPrice = quote.price, prevClose = quote.prevClose) },
                    message = "已刷新现价：${formatPrice(quote.price)} 元"
                )
            }
            persist()
        }
    }

    /** 一键刷新所有股票的现价（并行请求），用于计算准确的账户总盈亏 */
    fun refreshAll() {
        val stocks = _state.value.accounts.map { it.stock }
        if (stocks.isEmpty()) return
        viewModelScope.launch {
            val results = stocks.map { stock -> async { stock to api.fetchQuote(stock) } }.awaitAll()
            val fetched = results.filter { it.second?.price != null }
            _state.update { s ->
                if (fetched.isEmpty()) s.copy(message = "行情刷新失败，请检查网络")
                else {
                    val accounts = s.accounts.map { a ->
                        val quote = fetched.firstOrNull { it.first.code == a.stock.code && it.first.market == a.stock.market }?.second
                        if (quote?.price != null) a.copy(currentPrice = quote.price, prevClose = quote.prevClose) else a
                    }
                    s.copy(
                        accounts = accounts,
                        message = "已刷新全部行情（${fetched.size}/${stocks.size} 只）"
                    )
                }
            }
            persist()
        }
    }

    // ---------------- 买入/卖出（作用于当前选中股票） ----------------

    fun buy(price: Double, qty: Int, time: Long = System.currentTimeMillis()) {
        if (price <= 0 || qty <= 0) { notify("请输入正确的单价和数量"); return }
        val idx = _state.value.selectedIndex
        if (idx < 0) { notify("请先添加并选择股票"); return }
        val buyFee = feeConfig.buyFee(price * qty)
        val lot = BuyLot(id = nextId++, price = price, originalQty = qty, remainingQty = qty, time = time, fee = buyFee)
        _state.update { s ->
            s.copy(
                accounts = updateSelected(s) { a ->
                    a.copy(
                        holdings = a.holdings + lot,
                        trades = a.trades + TradeRecord(nextId++, TradeType.BUY, price, qty, time = time, fee = buyFee, lotId = lot.id),
                        totalBuyFee = a.totalBuyFee + buyFee
                    )
                },
                message = "买入成功：${formatPrice(price)} 元 × $qty 股（手续费 ${formatMoney(buyFee)} 元）"
            )
        }
        persist()
    }

    /**
     * 卖出：按当前匹配策略抵扣持仓（默认最低价优先，可选最高价优先），不区分买入日期，T+1 不限制记账。
     * 例如持仓含 1元100、3元300、5元200，卖出300股 @4元（最低价优先）：
     *   先抵扣1元100股（盈利300），再抵扣3元200股（盈利200），合计盈利500，3元剩100股。
     */
    fun sell(price: Double, qty: Int) {
        sellWithMatch(price, qty, _state.value.sellMatch)
    }

    /** A plan-specific match applies to this trade without changing the global preference. */
    fun sellWithMatch(price: Double, qty: Int, strategy: SellMatch) {
        if (price <= 0 || qty <= 0) { notify("请输入正确的单价和数量"); return }
        val s = _state.value
        val acc = s.selected ?: run { notify("请先添加并选择股票"); return }
        if (acc.totalQty <= 0) { notify("当前无持仓，无法卖出"); return }
        if (qty > acc.totalQty) { notify("卖出数量超过持仓数量（${acc.totalQty} 股）"); return }

        val m = matchSellLots(acc.holdings, qty, price, strategy)

        val sellFee = feeConfig.sellFee(price * qty)
        _state.update {
            it.copy(
                accounts = updateSelected(it) { a ->
                    a.copy(
                        holdings = m.remaining,
                        trades = a.trades + TradeRecord(
                            nextId++, TradeType.SELL, price, qty, m.profit,
                            fee = sellFee, costFee = m.costFee, soldLots = m.soldLots
                        )
                    )
                },
                message = "卖出成功：${formatPrice(price)} 元 × $qty 股，净盈亏 ${formatMoney(m.profit - sellFee - m.costFee)} 元（费用 ${formatMoney(sellFee + m.costFee)} 元）"
            )
        }
        persist()
    }

    fun configureTradePlan(config: TradePlanConfig?): Boolean {
        if (_state.value.selected == null || (config != null && !config.isValid)) return false
        _state.update { s -> s.copy(accounts = updateSelected(s) { it.copy(tradePlan = config) }) }
        persist()
        return true
    }

    /** 切换卖出匹配策略（全局偏好，持久化；默认最低价优先） */
    fun setSellMatch(strategy: SellMatch) {
        _state.update { it.copy(sellMatch = strategy) }
        notify("卖出匹配已切换为：${strategy.label}")
        persist()
    }

    /**
     * 撤销最后一笔交易（防止写错）：
     * - 撤销买入：仅当该批次仍完整在持仓（未被后续卖出消耗）时执行，移除批次并退回买入手续费。
     * - 撤销卖出：按卖出时记录的批次明细原样恢复持仓（旧数据无明细时拒绝）。
     */
    fun undoLastTrade() {
        val idx = _state.value.selectedIndex
        if (idx < 0) { notify("请先添加并选择股票"); return }
        val acc = _state.value.accounts.getOrNull(idx) ?: return
        val last = acc.trades.lastOrNull() ?: run { notify("没有可撤销的交易"); return }
        when (last.type) {
            TradeType.BUY -> {
                val lotId = last.lotId
                if (lotId == null) {
                    notify("该笔买入缺少批次信息（旧数据），无法撤销")
                    return
                }
                val lot = acc.holdings.firstOrNull { it.id == lotId }
                if (lot == null) { notify("该笔买入的批次已被卖出，无法撤销（请先撤销之后的卖出）"); return }
                if (lot.remainingQty != lot.originalQty) {
                    notify("该笔买入已被部分卖出，无法撤销（请先撤销之后的卖出）")
                    return
                }
                _state.update { s ->
                    s.copy(
                        accounts = updateSelected(s) { a ->
                            a.copy(
                                holdings = a.holdings.filterNot { it.id == lot.id },
                                trades = a.trades.dropLast(1),
                                totalBuyFee = a.totalBuyFee - last.fee
                            )
                        },
                        message = "已撤销买入：${formatPrice(last.price)} 元 × ${last.qty} 股（退回手续费 ${formatMoney(last.fee)} 元）"
                    )
                }
            }
            TradeType.SELL -> {
                if (last.soldLots.isEmpty()) {
                    notify("该笔卖出缺少批次明细（旧数据），无法撤销")
                    return
                }
                _state.update { s ->
                    s.copy(
                        accounts = updateSelected(s) { a ->
                            val holdings = a.holdings.toMutableList()
                            last.soldLots.forEach { sl ->
                                val existing = holdings.firstOrNull { it.id == sl.lotId }
                                if (existing != null) {
                                    val i = holdings.indexOf(existing)
                                    holdings[i] = existing.copy(remainingQty = existing.remainingQty + sl.qty)
                                } else {
                                    holdings += BuyLot(sl.lotId, sl.price, sl.originalQty, sl.qty, sl.time, sl.fee)
                                }
                            }
                            a.copy(holdings = holdings, trades = a.trades.dropLast(1))
                        },
                        message = "已撤销卖出：${formatPrice(last.price)} 元 × ${last.qty} 股，持仓已恢复"
                    )
                }
            }
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
