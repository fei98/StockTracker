package com.example.stocktracker

import java.math.BigDecimal
import java.math.RoundingMode

enum class PlanThresholdMode { QUANTITY, COST }
enum class PlanStepMode { PRICE, PERCENT }
enum class PlanDirection { UP, DOWN }
enum class PlanBlock { NO_HOLDINGS, INVALID_PRICE }

data class TradePlanConfig(
    val thresholdMode: PlanThresholdMode = PlanThresholdMode.QUANTITY,
    val threshold: Double,
    val unitQty: Int = 100,
    val stepMode: PlanStepMode = PlanStepMode.PRICE,
    val step: Double,
    val referencePrice: Double,
    val startAfterTradeId: Long = 0L
) {
    val isValid: Boolean get() = threshold.isFinite() && threshold >= 0 &&
        (thresholdMode != PlanThresholdMode.QUANTITY ||
            (threshold <= Int.MAX_VALUE && threshold % 1.0 == 0.0)) &&
        unitQty > 0 && step.isFinite() && step > 0 &&
        (stepMode != PlanStepMode.PERCENT || step < 100) &&
        referencePrice.isFinite() && referencePrice > 0 && startAfterTradeId >= 0
}

data class TradePlanOrder(
    val direction: PlanDirection,
    val type: TradeType,
    val price: Double?,
    val qty: Int,
    val match: SellMatch?,
    val reached: Boolean,
    val block: PlanBlock?,
    val resultingQty: Int,
    val resultingCost: Double?,
    val estimatedFee: Double?,
    val estimatedNetProfit: Double?
)

data class TradePlanResult(
    val referencePrice: Double,
    val lowestBuyPrice: Double?,
    val overThreshold: Boolean,
    val upward: TradePlanOrder,
    val downward: TradePlanOrder
)

data class TriggeredPlanEntry(
    val accountIndex: Int,
    val stock: Stock,
    val currentPrice: Double,
    val orders: List<TradePlanOrder>
)

/** Groups currently reached, valid orders by stock, retaining account order and navigation indexes. */
fun triggeredPlanEntries(accounts: List<StockAccount>, feeConfig: FeeConfig): List<TriggeredPlanEntry> =
    accounts.mapIndexedNotNull { index, account ->
        val currentPrice = account.currentPrice?.takeIf { it.isFinite() && it > 0 }
            ?: return@mapIndexedNotNull null
        val plan = evaluateTradePlan(account, feeConfig) ?: return@mapIndexedNotNull null
        val orders = listOf(plan.upward, plan.downward).filter { it.reached && it.block == null }
        if (orders.isEmpty()) null else TriggeredPlanEntry(index, account.stock, currentPrice, orders)
    }

/** Three-decimal prices match the precision supported by the transaction editor. */
private fun planPrice(base: Double, config: TradePlanConfig, up: Boolean): Double? {
    val baseValue = BigDecimal.valueOf(base)
    val step = BigDecimal.valueOf(config.step)
    val delta = if (config.stepMode == PlanStepMode.PRICE) step
        else baseValue.multiply(step).movePointLeft(2)
    val result = (if (up) baseValue.add(delta) else baseValue.subtract(delta))
        .setScale(3, RoundingMode.HALF_UP).toDouble()
    return result.takeIf { it.isFinite() && it > 0 && (if (up) it > base else it < base) }
}

/** Quotes never advance the anchor; only trades entered after configuration can do so. */
fun evaluateTradePlan(
    account: StockAccount,
    feeConfig: FeeConfig
): TradePlanResult? {
    val config = account.tradePlan?.takeIf { it.isValid } ?: return null
    val reference = account.trades.lastOrNull {
        it.id > config.startAfterTradeId && it.price.isFinite() && it.price > 0
    }?.price ?: config.referencePrice
    val lowest = account.holdings.filter { it.remainingQty > 0 && it.price.isFinite() && it.price > 0 }
        .minOfOrNull { it.price }
    val over = when (config.thresholdMode) {
        PlanThresholdMode.QUANTITY -> account.totalQty > config.threshold
        PlanThresholdMode.COST -> account.totalCost - config.threshold > 0.000001
    }
    fun order(direction: PlanDirection, type: TradeType, price: Double?, match: SellMatch?): TradePlanOrder {
        val qty = if (type == TradeType.BUY) config.unitQty else minOf(config.unitQty, account.totalQty)
        val block = when {
            type == TradeType.SELL && qty <= 0 -> PlanBlock.NO_HOLDINGS
            price == null -> PlanBlock.INVALID_PRICE
            type == TradeType.BUY && account.totalQty.toLong() + qty > Int.MAX_VALUE -> PlanBlock.INVALID_PRICE
            else -> null
        }
        val current = account.currentPrice?.takeIf { it.isFinite() && it > 0 }
        val reached = price != null && current != null &&
            (if (direction == PlanDirection.UP) current >= price else current <= price)
        val sell = if (type == TradeType.SELL && qty > 0 && price != null)
            matchSellLots(account.holdings, qty, price, match!!) else null
        val fee = price?.let {
            if (type == TradeType.BUY) feeConfig.buyFee(it * qty) else feeConfig.sellFee(it * qty)
        }
        val resultingQty = (account.totalQty.toLong() + if (type == TradeType.BUY) qty else -qty)
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val resultingCost = when {
            price == null -> null
            type == TradeType.BUY -> account.totalCost + price * qty
            else -> sell!!.remaining.sumOf { it.price * it.remainingQty }
        }
        return TradePlanOrder(direction, type, price, qty, match, reached, block, resultingQty,
            resultingCost, fee, sell?.let { it.profit - it.costFee - fee!! })
    }

    return TradePlanResult(reference, lowest, over,
        order(PlanDirection.UP, TradeType.SELL, lowest?.let { planPrice(it, config, true) }, SellMatch.LOWEST),
        order(PlanDirection.DOWN, if (over) TradeType.SELL else TradeType.BUY,
            planPrice(reference, config, false), if (over) SellMatch.HIGHEST else null))
}
