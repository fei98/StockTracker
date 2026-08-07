package com.example.stocktracker

/** 账户总览标签卡 */
enum class OverviewTab(val label: String) {
    FLOAT("浮盈"),
    VALUE("市值"),
    REALIZED("已实现"),
    SHARES("股数")
}

/** 总览单项（一只股票的某个口径） */
data class OverviewEntry(val name: String, val value: Double)

/** 按口径聚合各股票数据（纯函数，可单测） */
fun overviewEntries(accounts: List<StockAccount>, tab: OverviewTab): List<OverviewEntry> = when (tab) {
    OverviewTab.FLOAT -> accounts.map { OverviewEntry(it.stock.name, it.floatingPnlFee) }
    OverviewTab.VALUE -> accounts.map { OverviewEntry(it.stock.name, it.marketValue) }
    OverviewTab.REALIZED -> accounts.map { OverviewEntry(it.stock.name, it.realizedPnlFee) }
    OverviewTab.SHARES -> accounts.map { OverviewEntry(it.stock.name, it.totalQty.toDouble()) }
}

/**
 * 浮盈为正时是否可卖的提示（T+1 冻结角度）：
 * - 全部可卖 → null（不标记）
 * - 部分可卖 → "部分可卖"
 * - 都不可卖 → "不可卖"
 * - 无持仓 → null
 */
fun sellableNote(acc: StockAccount): String? {
    val total = acc.totalQty
    if (total <= 0) return null
    val sellable = acc.sellableQty
    return when {
        sellable >= total -> null
        sellable <= 0 -> "不可卖"
        else -> "部分可卖"
    }
}
