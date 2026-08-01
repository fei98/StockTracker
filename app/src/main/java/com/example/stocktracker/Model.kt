package com.example.stocktracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单笔买入持仓（按买入批次记录，便于按价格高低抵扣）
 */
data class BuyLot(
    val id: Long,
    val price: Double,        // 买入单价
    val originalQty: Int,     // 原始买入数量
    val remainingQty: Int     // 剩余未卖出数量
)

/**
 * 交易记录（用于历史展示）
 */
data class TradeRecord(
    val id: Long,
    val type: TradeType,
    val price: Double,
    val qty: Int,
    val profit: Double = 0.0, // 仅卖出时有意义：本次卖出实现盈亏
    val time: Long = System.currentTimeMillis()
)

enum class TradeType { BUY, SELL }

/**
 * 单笔持仓的盈亏信息（输入现价后计算）
 */
data class LotPnl(
    val lot: BuyLot,
    val pnl: Double,          // 该批次浮动盈亏 = (现价 - 买入价) * 剩余数量
    val pnlPercent: Double    // 盈亏百分比
)

data class StockState(
    val holdings: List<BuyLot> = emptyList(),
    val trades: List<TradeRecord> = emptyList(),
    val currentPrice: Double? = null,
    val message: String? = null
) {
    /** 总持仓数量 */
    val totalQty: Int get() = holdings.sumOf { it.remainingQty }

    /** 持仓总成本 */
    val totalCost: Double get() = holdings.sumOf { it.price * it.remainingQty }

    /** 持仓均价（总成本 / 总数量），无持仓返回 0 */
    val avgPrice: Double get() = if (totalQty == 0) 0.0 else totalCost / totalQty

    /** 现价下的总市值 */
    val marketValue: Double get() = (currentPrice ?: 0.0) * totalQty

    /** 现价下的总浮动盈亏 */
    val totalPnl: Double get() = marketValue - totalCost

    /** 每批持仓的盈亏明细 */
    val lotPnls: List<LotPnl>
        get() = holdings.map {
            val cp = currentPrice ?: 0.0
            val pnl = (cp - it.price) * it.remainingQty
            val pct = if (it.price > 0) (cp - it.price) / it.price * 100 else 0.0
            LotPnl(it, pnl, pct)
        }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
fun formatTime(t: Long): String = timeFormat.format(Date(t))

/** 把金额格式化成带符号、两位小数的字符串 */
fun formatMoney(v: Double): String {
    val s = String.format(Locale.US, "%,.2f", kotlin.math.abs(v))
    return if (v < 0) "-$s" else s
}

fun formatPrice(v: Double): String = String.format(Locale.US, "%.2f", v)
