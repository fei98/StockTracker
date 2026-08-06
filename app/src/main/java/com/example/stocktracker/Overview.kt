package com.example.stocktracker

/** 账户总览标签卡 */
enum class OverviewTab(val label: String) {
    FLOAT("浮盈"),
    VALUE("市值"),
    REALIZED("已实现")
}

/** 总览单项（一只股票的某个口径） */
data class OverviewEntry(val name: String, val value: Double)

/** 按口径聚合各股票数据（纯函数，可单测） */
fun overviewEntries(accounts: List<StockAccount>, tab: OverviewTab): List<OverviewEntry> = when (tab) {
    OverviewTab.FLOAT -> accounts.map { OverviewEntry(it.stock.name, it.floatingPnlFee) }
    OverviewTab.VALUE -> accounts.map { OverviewEntry(it.stock.name, it.marketValue) }
    OverviewTab.REALIZED -> accounts.map { OverviewEntry(it.stock.name, it.realizedPnlFee) }
}
