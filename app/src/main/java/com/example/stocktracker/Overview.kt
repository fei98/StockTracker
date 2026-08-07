package com.example.stocktracker

import java.util.Calendar

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

/** 已实现盈亏的统计时间段 */
sealed interface RealizedRange {
    object ThisMonth : RealizedRange
    data class Month(val year: Int, val month: Int) : RealizedRange
    data class Day(val dateMillis: Long) : RealizedRange
    data class Year(val year: Int) : RealizedRange
}

/** 该时间段对应的 [开始毫秒, 结束毫秒)（与 isSameDay 同用默认时区，纯函数可单测） */
fun realizedRangeBounds(range: RealizedRange, now: Long = System.currentTimeMillis()): Pair<Long, Long> = when (range) {
    is RealizedRange.ThisMonth -> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        start to cal.timeInMillis
    }
    is RealizedRange.Month -> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, range.year)
        cal.set(Calendar.MONTH, range.month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        start to cal.timeInMillis
    }
    is RealizedRange.Day -> {
        val cal = Calendar.getInstance().apply { timeInMillis = range.dateMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        start to cal.timeInMillis
    }
    is RealizedRange.Year -> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, range.year)
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.YEAR, 1)
        start to cal.timeInMillis
    }
}

/** 时间段的中文标题（紧凑格式；纯函数，可单测） */
fun realizedRangeLabel(range: RealizedRange, now: Long = System.currentTimeMillis()): String = when (range) {
    is RealizedRange.ThisMonth -> "本月"
    is RealizedRange.Month -> "${range.year}年${range.month}月"
    is RealizedRange.Day -> {
        val cal = Calendar.getInstance().apply { timeInMillis = range.dateMillis }
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        if (sameDay) "今日"
        else "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }
    is RealizedRange.Year -> {
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        if (range.year == nowCal.get(Calendar.YEAR)) "今年" else "${range.year}年"
    }
}

/** 指定时间段内各股票的已实现盈亏（含费）：只统计区间内的卖出净盈亏（纯函数，可单测） */
fun realizedEntriesInRange(accounts: List<StockAccount>, range: RealizedRange, now: Long = System.currentTimeMillis()): List<OverviewEntry> {
    val (from, to) = realizedRangeBounds(range, now)
    return accounts.map { acc ->
        val realized = acc.trades
            .filter { it.type == TradeType.SELL && it.time in from until to }
            .sumOf { it.netProfit }
        OverviewEntry(acc.stock.name, realized)
    }
}

/** 某年某月的天数（纯函数，可单测；Calendar 已处理闰年） */
fun daysInMonth(year: Int, month: Int): Int {
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month - 1)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
}

/** 某年某月每天（1..月末）的已实现收益，无卖出的当天为 0（纯函数，可单测） */
fun realizedDayTotalsInMonth(accounts: List<StockAccount>, year: Int, month: Int): List<Double> {
    val n = daysInMonth(year, month)
    val totals = DoubleArray(n)
    accounts.forEach { acc ->
        acc.trades.filter { it.type == TradeType.SELL }.forEach { tr ->
            val cal = Calendar.getInstance().apply { timeInMillis = tr.time }
            if (cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) + 1 == month) {
                totals[cal.get(Calendar.DAY_OF_MONTH) - 1] += tr.netProfit
            }
        }
    }
    return totals.toList()
}

/**
 * 从数据最老月份到当前月的连续月份收益，倒序（最新在前），每项 (年, 月, 已实现总收益)。
 * 无交易数据时只含当前月；数据早于 2000 年时截到 2000 年（纯函数，可单测）。
 */
fun realizedMonthTotals(accounts: List<StockAccount>, now: Long = System.currentTimeMillis()): List<Triple<Int, Int, Double>> {
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val curY = nowCal.get(Calendar.YEAR)
    val curM = nowCal.get(Calendar.MONTH) + 1
    var oldestY = curY
    var oldestM = curM
    accounts.forEach { acc ->
        acc.trades.forEach { tr ->
            val cal = Calendar.getInstance().apply { timeInMillis = tr.time }
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            if (y < oldestY || (y == oldestY && m < oldestM)) {
                oldestY = y
                oldestM = m
            }
        }
    }
    if (oldestY < 2000) oldestY = 2000
    val result = mutableListOf<Triple<Int, Int, Double>>()
    var y = curY
    var m = curM
    while (y > oldestY || (y == oldestY && m >= oldestM)) {
        val total = accounts.sumOf { acc ->
            acc.trades
                .filter { it.type == TradeType.SELL && dayOf(it.time) == (y to m) }
                .sumOf { it.netProfit }
        }
        result.add(Triple(y, m, total))
        m--
        if (m == 0) {
            m = 12
            y--
        }
    }
    return result
}

/** 从第一笔交易年份到今年的逐年收益，倒序（今年在前），每项 (年, 已实现总收益)（纯函数，可单测） */
fun realizedYearTotals(accounts: List<StockAccount>, now: Long = System.currentTimeMillis()): List<Pair<Int, Double>> {
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val curY = nowCal.get(Calendar.YEAR)
    // 最早年份：数据里最老一笔交易年份；无数据则今年
    val oldestY = accounts.flatMap { it.trades.map { tr -> Calendar.getInstance().apply { timeInMillis = tr.time }.get(Calendar.YEAR) } }
        .minOrNull() ?: curY
    return (oldestY..curY).reversed().map { y ->
        val total = accounts.sumOf { acc ->
            acc.trades
                .filter { it.type == TradeType.SELL && Calendar.getInstance().apply { timeInMillis = it.time }.get(Calendar.YEAR) == y }
                .sumOf { it.netProfit }
        }
        y to total
    }
}

/** 时间戳 → (年, 月)（用于按月份聚合） */
private fun dayOf(time: Long): Pair<Int, Int> {
    val cal = Calendar.getInstance().apply { timeInMillis = time }
    return cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
}
