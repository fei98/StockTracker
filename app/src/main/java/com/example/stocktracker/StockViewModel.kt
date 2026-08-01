package com.example.stocktracker

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StockViewModel : ViewModel() {

    private val _state = MutableStateFlow(StockState())
    val state: StateFlow<StockState> = _state.asStateFlow()

    private var nextId = 1L

    /** 买入：新增一个持仓批次 */
    fun buy(price: Double, qty: Int) {
        if (price <= 0 || qty <= 0) {
            notify("请输入正确的单价和数量"); return
        }
        val lot = BuyLot(id = nextId++, price = price, originalQty = qty, remainingQty = qty)
        _state.update { s ->
            s.copy(
                holdings = s.holdings + lot,
                trades = s.trades + TradeRecord(nextId++, TradeType.BUY, price, qty),
                message = "买入成功：${formatPrice(price)} 元 × $qty 股"
            )
        }
    }

    /**
     * 卖出：按“最低价优先抵扣”匹配持仓。
     * 例如持仓含 1元100、3元300、5元200，卖出300股 @4元：
     *   先抵扣1元100股（盈利300），再抵扣3元200股（盈利200），合计盈利500，3元剩100股。
     */
    fun sell(price: Double, qty: Int) {
        if (price <= 0 || qty <= 0) { notify("请输入正确的单价和数量"); return }
        val s = _state.value
        if (s.totalQty <= 0) { notify("当前无持仓，无法卖出"); return }
        if (qty > s.totalQty) { notify("卖出数量超过持仓（${s.totalQty} 股）"); return }

        val sorted = s.holdings.sortedBy { it.price } // 单价从低到高抵扣
        var toSell = qty
        var profit = 0.0
        val remaining = mutableListOf<BuyLot>()

        for (lot in sorted) {
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
                holdings = remaining,
                trades = it.trades + TradeRecord(nextId++, TradeType.SELL, price, qty, profit),
                message = "卖出成功：${formatPrice(price)} 元 × $qty 股，本次盈利 ${formatMoney(profit)} 元"
            )
        }
    }

    /** 设置现价，用于计算各批次浮动盈亏 */
    fun setCurrentPrice(price: Double?) {
        if (price != null && price < 0) { notify("现价不能为负"); return }
        _state.update { it.copy(currentPrice = price, message = if (price != null) "已更新现价 ${formatPrice(price)} 元" else "已清除现价") }
    }

    /** 一键清空 */
    fun clearAll() {
        _state.update { StockState(message = "已清空所有数据") }
    }

    /** 清除提示消息 */
    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun notify(msg: String) = _state.update { it.copy(message = msg) }
}
