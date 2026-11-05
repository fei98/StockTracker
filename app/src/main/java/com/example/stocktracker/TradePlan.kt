package com.example.stocktracker

import java.math.BigDecimal
import java.math.RoundingMode

enum class PlanDirection { UP, DOWN }
enum class PlanBlock { WAIT, INVALID_PRICE, INVALID_HOLDINGS }
enum class PlanReason { UP_PROFIT, UP_REPLENISH, DOWN_BUY, DOWN_REDUCE, WAIT }

data class TradePlanConfig(
    val lowerCost: Double = 15_000.0,
    val upperCost: Double = 50_000.0,
    val orderAmount: Double = 4_000.0,
    val stepPercent: Double = 4.0,
    val referencePrice: Double,
    val startAfterTradeId: Long = 0L,
    val legacyJson: String? = null
) {
    val needsSetup: Boolean get() = legacyJson != null
    val isValid: Boolean get() = !needsSetup && lowerCost.isFinite() && lowerCost >= 0 &&
        upperCost.isFinite() && upperCost > lowerCost && orderAmount.isFinite() && orderAmount > 0 &&
        stepPercent.isFinite() && stepPercent > 0 && stepPercent < 100 &&
        referencePrice.isFinite() && referencePrice > 0 && startAfterTradeId >= 0
}

data class TradePlanOrder(
    val direction: PlanDirection,
    val type: TradeType?,
    val price: Double?,
    val triggerPrice: Double?,
    val qty: Int,
    val match: SellMatch?,
    val reached: Boolean,
    val block: PlanBlock?,
    val reason: PlanReason,
    val resultingQty: Int,
    val resultingCost: Double?,
    val estimatedFee: Double?,
    val estimatedNetProfit: Double?
)

data class TradePlanResult(
    val referencePrice: Double,
    val positionCost: Double,
    val upward: TradePlanOrder,
    val downward: TradePlanOrder
)

data class TriggeredPlanEntry(
    val accountIndex: Int,
    val stock: Stock,
    val currentPrice: Double,
    val orders: List<TradePlanOrder>
)

fun triggeredPlanEntries(accounts: List<StockAccount>, feeConfig: FeeConfig): List<TriggeredPlanEntry> =
    accounts.mapIndexedNotNull { index, account ->
        val price = account.currentPrice?.takeIf { it.isFinite() && it > 0 }
            ?: return@mapIndexedNotNull null
        val plan = evaluateTradePlan(account, feeConfig) ?: return@mapIndexedNotNull null
        val orders = listOf(plan.upward, plan.downward).filter { it.reached && it.block == null }
        if (orders.isEmpty()) null else TriggeredPlanEntry(index, account.stock, price, orders)
    }

private val roundLot = BigDecimal.valueOf(100)
private const val maxRoundQty = Int.MAX_VALUE / 100 * 100
private fun Double.decimal() = BigDecimal.valueOf(this)

/** Both directions use the last recorded fill; quotes and waiting never move the anchor. */
fun evaluateTradePlan(account: StockAccount, feeConfig: FeeConfig): TradePlanResult? {
    val config = account.tradePlan?.takeIf { it.isValid } ?: return null
    val reference = account.trades.lastOrNull {
        it.id > config.startAfterTradeId && it.price.isFinite() && it.price > 0
    }?.price ?: config.referencePrice
    val holdings = account.holdings.filter { it.remainingQty > 0 }
    val totalQty = holdings.sumOf { it.remainingQty.toLong() }
    val validLots = totalQty <= Int.MAX_VALUE && account.holdings.none { it.remainingQty < 0 } &&
        holdings.all { it.price.isFinite() && it.price > 0 }
    val cost = if (validLots) holdings.fold(BigDecimal.ZERO) { sum, lot ->
        sum + lot.price.decimal() * BigDecimal.valueOf(lot.remainingQty.toLong())
    } else BigDecimal.ZERO
    val validHoldings = validLots && cost.toDouble().isFinite()
    val lower = config.lowerCost.decimal()
    val upper = config.upperCost.decimal()
    val current = account.currentPrice?.takeIf { it.isFinite() && it > 0 }

    fun planOrder(direction: PlanDirection): TradePlanOrder {
        val upward = direction == PlanDirection.UP
        val factor = BigDecimal.ONE + config.stepPercent.decimal().movePointLeft(2) *
            BigDecimal.valueOf(if (upward) 1 else -1)
        val target = (reference.decimal() * factor)
            .setScale(2, if (upward) RoundingMode.CEILING else RoundingMode.FLOOR)
            .toDouble().takeIf { it.isFinite() && it > 0 && if (upward) it > reference else it < reference }
        val reached = target != null && current != null && if (upward) current >= target else current <= target
        // Crossed points use the quote to size the order and prefill the trade editor consistently.
        val execution = if (reached) current else target
        fun blocked(block: PlanBlock) = TradePlanOrder(direction, null, execution, target, 0, null,
            reached, block, PlanReason.WAIT, totalQty.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            if (validHoldings) cost.toDouble() else null, null, null)
        if (!validHoldings) return blocked(PlanBlock.INVALID_HOLDINGS)
        val price = execution?.decimal() ?: return blocked(PlanBlock.INVALID_PRICE)
        if (target == null) return blocked(PlanBlock.INVALID_PRICE)

        fun lotsToQty(lots: BigDecimal): Int = lots.max(BigDecimal.ZERO)
            .min(BigDecimal.valueOf((maxRoundQty / 100).toLong())).toInt() * 100
        val requested = lotsToQty(config.orderAmount.decimal().divide(price * roundLot, 0, RoundingMode.HALF_UP)
            .max(BigDecimal.ONE))
        val buyCapacity = if (cost < upper) lotsToQty((upper - cost)
            .divide(price * roundLot, 0, RoundingMode.FLOOR)) else 0
        val integerCapacity = ((Int.MAX_VALUE.toLong() - totalQty) / 100 * 100).toInt()

        fun sellQty(strategy: SellMatch, profitableOnly: Boolean): Int {
            if (cost <= lower) return 0
            val ordered = if (strategy == SellMatch.LOWEST) holdings.sortedBy { it.price }
                else holdings.sortedByDescending { it.price }
            var budget = cost - lower
            var allowed = 0
            for (lot in ordered) {
                if (profitableOnly && lot.price.decimal() >= price) break
                val affordable = budget.divide(lot.price.decimal(), 0, RoundingMode.FLOOR)
                    .min(BigDecimal.valueOf((requested - allowed).toLong())).toInt()
                val take = minOf(lot.remainingQty, affordable)
                allowed += take
                budget -= lot.price.decimal() * BigDecimal.valueOf(take.toLong())
                if (take < lot.remainingQty || allowed >= requested) break
            }
            return allowed / 100 * 100
        }

        val sellMatch = if (upward) SellMatch.LOWEST else SellMatch.HIGHEST
        val saleQty = if (upward || buyCapacity == 0) sellQty(sellMatch, upward) else 0
        val type = if (saleQty > 0) TradeType.SELL else TradeType.BUY
        val qty = if (type == TradeType.SELL) saleQty else minOf(requested, buyCapacity, integerCapacity)
        if (qty <= 0) return blocked(PlanBlock.WAIT)
        val match = sellMatch.takeIf { type == TradeType.SELL }
        if (!(price * BigDecimal.valueOf(qty.toLong())).toDouble().isFinite()) return blocked(PlanBlock.INVALID_PRICE)
        val sale = match?.let { matchSellLots(holdings, qty, execution!!, it) }
        val resultingCost = if (sale == null) cost + price * BigDecimal.valueOf(qty.toLong())
            else sale.remaining.fold(BigDecimal.ZERO) { sum, lot ->
                sum + lot.price.decimal() * BigDecimal.valueOf(lot.remainingQty.toLong())
            }
        if (!resultingCost.toDouble().isFinite()) return blocked(PlanBlock.INVALID_PRICE)
        val amount = execution!! * qty
        val fee = if (type == TradeType.BUY) feeConfig.buyFee(amount) else feeConfig.sellFee(amount)
        val reason = when {
            upward && type == TradeType.SELL -> PlanReason.UP_PROFIT
            upward -> PlanReason.UP_REPLENISH
            type == TradeType.BUY -> PlanReason.DOWN_BUY
            else -> PlanReason.DOWN_REDUCE
        }
        return TradePlanOrder(direction, type, execution, target, qty, match, reached, null, reason,
            totalQty.toInt() + if (type == TradeType.BUY) qty else -qty, resultingCost.toDouble(), fee,
            sale?.let { it.profit - it.costFee - fee })
    }
    return TradePlanResult(reference, cost.toDouble(), planOrder(PlanDirection.UP), planOrder(PlanDirection.DOWN))
}
