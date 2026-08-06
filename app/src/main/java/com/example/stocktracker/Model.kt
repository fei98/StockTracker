package com.example.stocktracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 单笔买入持仓（按买入批次记录，便于按价格高低抵扣）
 * @param time 买入时间戳；0 表示历史/未知时间（视为可卖）
 * @param fee 该批买入手续费（佣金+过户费，含最低佣金）
 */
data class BuyLot(
    val id: Long,
    val price: Double,        // 买入单价
    val originalQty: Int,     // 原始买入数量
    val remainingQty: Int,    // 剩余未卖出数量
    val time: Long = 0L,
    val fee: Double = 0.0
)

/** 判断两个时间戳是否同一天（用于 T+1 判断） */
fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/**
 * 交易记录（用于历史展示）
 * @param fee 本次手续费：买入 = 买入费；卖出 = 卖出费（佣金+印花税+过户费）
 * @param costFee 仅卖出有意义：被卖出批次分摊的买入手续费
 */
data class TradeRecord(
    val id: Long,
    val type: TradeType,
    val price: Double,
    val qty: Int,
    val profit: Double = 0.0, // 仅卖出时有意义：本次卖出实现盈亏（裸价，不含任何费用）
    val time: Long = System.currentTimeMillis(),
    val fee: Double = 0.0,
    val costFee: Double = 0.0
) {
    /** 本次交易成本：买入 = 成交金额；卖出 = 被卖出持仓对应的裸成本（成交金额 - 盈亏） */
    val cost: Double get() = if (type == TradeType.BUY) price * qty else price * qty - profit

    /** 卖出净盈亏（扣本笔卖出费 + 被卖批次分摊的买入费）；买入为 0 */
    val netProfit: Double get() = if (type == TradeType.SELL) profit - fee - costFee else 0.0
}

/**
 * 交易费率配置（A 股常见口径；买入/卖出统一适用）
 * 买入费 = max(金额×佣金率, 最低佣金) + 金额×过户费率
 * 卖出费 = max(金额×佣金率, 最低佣金) + 金额×印花税率 + 金额×过户费率
 */
data class FeeConfig(
    val commissionRate: Double = 0.00025,  // 佣金率（万2.5）
    val minCommission: Double = 5.0,       // 最低佣金（元）
    val stampTaxRate: Double = 0.0005,     // 印花税（卖出，0.05%）
    val transferRate: Double = 0.00001     // 过户费（万0.1，双边）
) {
    fun buyFee(amount: Double): Double =
        maxOf(amount * commissionRate, minCommission) + amount * transferRate

    fun sellFee(amount: Double): Double =
        maxOf(amount * commissionRate, minCommission) + amount * stampTaxRate + amount * transferRate
}

enum class TradeType { BUY, SELL }

/**
 * 单笔持仓的盈亏信息（输入现价后计算）
 */
data class LotPnl(
    val lot: BuyLot,
    val pnl: Double,          // 该批次浮动盈亏 = (现价 - 买入价) * 剩余数量
    val pnlPercent: Double    // 盈亏百分比
)

/**
 * 一只股票
 * @param market 市场前缀：sh 沪 / sz 深 / bj 北交所 / hk 港股 / us 美股
 */
data class Stock(
    val code: String,
    val name: String,
    val market: String
) {
    /** 接口用的完整代码，如 sz000001 */
    val marketCode: String get() = "$market$code"

    /** 展示用，如 平安银行 (sz000001) */
    val displayName: String get() = "$name ($marketCode)"
}

/** 单只股票的全部记账数据 */
data class StockAccount(
    val stock: Stock,
    val holdings: List<BuyLot> = emptyList(),
    val trades: List<TradeRecord> = emptyList(),
    val currentPrice: Double? = null,
    val prevClose: Double? = null,      // 昨收（刷新行情时记录，用于涨跌幅）
    val totalBuyFee: Double = 0.0       // 累计买入手续费（含已卖出批次，持久化）
) {
    /** 总持仓数量 */
    val totalQty: Int get() = holdings.sumOf { it.remainingQty }

    /** 今日涨跌幅%（(现价-昨收)/昨收），缺昨收返回 null */
    val dayChangePct: Double? get() {
        val cp = currentPrice ?: return null
        val pc = prevClose ?: return null
        return if (pc > 0) (cp - pc) / pc * 100 else null
    }

    /** T+1 可卖批次：A 股（sh/sz/bj）当日买入的冻结，港美股 T+0 当日即可卖 */
    val sellableHoldings: List<BuyLot>
        get() {
            val now = System.currentTimeMillis()
            return holdings.filter { lot ->
                stock.market == "hk" || stock.market == "us" || !isSameDay(lot.time, now)
            }
        }

    /** 可卖数量 */
    val sellableQty: Int get() = sellableHoldings.sumOf { it.remainingQty }

    /** 持仓总成本（裸价，不含手续费） */
    val totalCost: Double get() = holdings.sumOf { it.price * it.remainingQty }

    /** 持仓均价（裸价，不含手续费），无持仓返回 0 */
    val avgPrice: Double get() = if (totalQty == 0) 0.0 else totalCost / totalQty

    /** 当前持有批次分摊的买入手续费 */
    val heldBuyFee: Double
        get() = holdings.sumOf { lot -> if (lot.originalQty > 0) lot.fee * lot.remainingQty / lot.originalQty else 0.0 }

    /** 持仓成本（含持有批次分摊的买入手续费） */
    val totalCostWithFee: Double get() = totalCost + heldBuyFee

    /** 现价下的总市值 */
    val marketValue: Double get() = (currentPrice ?: 0.0) * totalQty

    /** 现价下的总浮动盈亏（含手续费） */
    val totalPnl: Double get() = marketValue - totalCostWithFee

    /** 现价相对含费成本的收益率（%），无现价或无成本时为 0 */
    val totalPnlPercent: Double
        get() = if (currentPrice != null && totalCostWithFee > 0) totalPnl / totalCostWithFee * 100 else 0.0

    /** 已实现盈亏（含费）：卖盈 − 卖出费 − 被卖批次分摊的买入费 */
    val realizedPnlFee: Double
        get() {
            val sells = trades.filter { it.type == TradeType.SELL }
            return sells.sumOf { it.profit } - sells.sumOf { it.fee } - sells.sumOf { it.costFee }
        }

    /** 浮动盈亏（含费） */
    val floatingPnlFee: Double get() = totalPnl

    /** 每批持仓的盈亏明细（含该批分摊的买入手续费） */
    val lotPnls: List<LotPnl>
        get() = holdings.map {
            val cp = currentPrice ?: 0.0
            val lotFee = if (it.originalQty > 0) it.fee * it.remainingQty / it.originalQty else 0.0
            val costWithFee = it.price * it.remainingQty + lotFee
            val pnl = (cp - it.price) * it.remainingQty - lotFee
            val pct = if (costWithFee > 0) pnl / costWithFee * 100 else 0.0
            LotPnl(it, pnl, pct)
        }
}

/** 行情查询结果（输入代码后从接口返回） */
data class QuoteResult(
    val code: String,
    val name: String,
    val price: Double?,
    val market: String,
    val prevClose: Double? = null
)

/**
 * 今日分时点（腾讯 minute/query）：累计量单位为"手"（1手=100股）。
 * @param minute 分钟索引（0 = 9:30，每分钟 +1；午休不连续）
 * @param price 该分钟价格
 * @param cumVolume 累计成交量（手）
 * @param cumAmount 累计成交额
 */
data class MinutePoint(
    val minute: Int,
    val price: Double,
    val cumVolume: Long,
    val cumAmount: Double
) {
    /** 到该分钟的均价 = 累计额 / 累计股数（累计量×100），累计量为 0 时退回现价 */
    val avgPrice: Double get() = if (cumVolume > 0) cumAmount / (cumVolume * 100.0) else price
}

/** 交易时间戳 → 分时分钟索引（0=9:30，午休跳过，越界收敛到 [0,239]） */
fun minuteIndexOf(time: Long): Int {
    val cal = Calendar.getInstance().apply { timeInMillis = time }
    val idx = (cal.get(Calendar.HOUR_OF_DAY) - 9) * 60 + cal.get(Calendar.MINUTE) - 30
    return idx.coerceIn(0, 239)
}

data class StockState(
    val accounts: List<StockAccount> = emptyList(),
    val selectedIndex: Int = -1,
    val searchResult: QuoteResult? = null,
    val isSearching: Boolean = false,
    /** 最近一次已完成查询的代码（用于判断"未找到"提示是否属于当前输入） */
    val searchedCode: String? = null,
    /** 查询结果提示：未找到 / 已在列表中（查询成功时为 null） */
    val searchHint: String? = null,
    val message: String? = null
) {
    /** 当前选中的股票账户 */
    val selected: StockAccount? get() = accounts.getOrNull(selectedIndex)

    /** 账户已实现盈亏（含费）：各股票已实现盈亏之和 */
    val realizedPnl: Double get() = accounts.sumOf { it.realizedPnlFee }

    /** 账户浮动盈亏（含费）：各股票现价浮动盈亏之和（未设现价的股票按 0 计） */
    val floatingPnl: Double get() = accounts.sumOf { it.floatingPnlFee }

    /** 账户总盈亏 = 浮动盈亏 + 已实现盈亏 */
    val totalPnl: Double get() = floatingPnl + realizedPnl

    /** 是否已为任意股票设置现价（用于浮动盈亏展示） */
    val hasAnyPrice: Boolean get() = accounts.any { it.currentPrice != null }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
fun formatTime(t: Long): String = timeFormat.format(Date(t))

/** 把金额格式化成带符号、两位小数的字符串 */
fun formatMoney(v: Double): String {
    val s = String.format(Locale.US, "%,.2f", kotlin.math.abs(v))
    return if (v < 0) "-$s" else s
}

/** 价格显示：最多保留 3 位小数，自动去掉末尾多余的 0（如 10.5、3.415、10） */
fun formatPrice(v: Double): String {
    val s = String.format(Locale.US, "%.3f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** 重放用的批次状态（可变剩余量） */
private class LotState(
    val id: Long,
    val price: Double,
    val originalQty: Int,
    var remainingQty: Int,
    val time: Long,
    val fee: Double
)

/**
 * 用当前费率重算全部历史手续费（一次性迁移，覆盖旧数据 fee=0）。
 * 按交易时间序重放：买入建批次（带费），卖出按最低价优先匹配（T+1 冻结批次不参与），
 * 计算每笔卖出的 costFee（被卖批次分摊的买入费）；最后用重放结果重建持仓与累计买入费。
 */
fun recalcHistoricalFees(s: StockState, feeConfig: FeeConfig): StockState {
    val newAccounts = s.accounts.map { acc ->
        val lots = mutableListOf<LotState>()
        val newTrades = acc.trades.map { t ->
            if (t.type == TradeType.BUY) {
                val fee = feeConfig.buyFee(t.price * t.qty)
                lots += LotState(t.id, t.price, t.qty, t.qty, t.time, fee)
                t.copy(fee = fee)
            } else {
                // 最低价优先匹配可卖批次（A 股当日买入冻结 T+1）
                val frozen = acc.stock.market != "hk" && acc.stock.market != "us"
                var toSell = t.qty
                var costFee = 0.0
                val sellable = lots.filter { !(frozen && isSameDay(it.time, t.time)) }.sortedBy { it.price }
                for (lot in sellable) {
                    if (toSell <= 0) break
                    val take = minOf(lot.remainingQty, toSell)
                    if (lot.originalQty > 0) costFee += lot.fee * take / lot.originalQty
                    lot.remainingQty -= take
                    toSell -= take
                }
                t.copy(fee = feeConfig.sellFee(t.price * t.qty), costFee = costFee)
            }
        }
        val holdings = lots.filter { it.remainingQty > 0 }
            .map { BuyLot(it.id, it.price, it.originalQty, it.remainingQty, it.time, it.fee) }
        val totalBuyFee = newTrades.filter { it.type == TradeType.BUY }.sumOf { it.fee }
        acc.copy(holdings = holdings, trades = newTrades, totalBuyFee = totalBuyFee)
    }
    return s.copy(accounts = newAccounts)
}
