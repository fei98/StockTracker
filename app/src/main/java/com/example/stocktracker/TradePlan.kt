package com.example.stocktracker

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p

const val TRADE_PLAN_MAX_GRID_INDEX = 10_000

enum class PlanDirection { UP, DOWN }
enum class PlanPhase(val label: String) {
    NORMAL("正向"), DOWN_REDUCTION("下跌反向减仓"), UP_FOLLOW("上涨反向跟随")
}
enum class PlanBlock {
    WAIT, INVALID_PRICE, INVALID_HOLDINGS, INVALID_STATE, OCCUPIED_GRID, INSUFFICIENT_CASH,
    INSUFFICIENT_PROFITABLE_SHARES, INSUFFICIENT_SETTLED_SHARES, NO_BREAKOUT
}
enum class PlanReason { UP_PROFIT, UP_REPLENISH, DOWN_BUY, DOWN_REDUCE, PULLBACK_BUY, WAIT }

data class TradePlanConfig(
    val lowerCost: Double = 0.0,
    val upperCost: Double = 0.0,
    val orderAmount: Double = 0.0,
    val stepPercent: Double = 0.0,
    val referencePrice: Double,
    val startAfterTradeId: Long = 0L,
    val legacyJson: String? = null,
    val capital: Double = 0.0,
    val initialCash: Double = 0.0,
    val activationId: Long = 0L
) {
    val needsSetup: Boolean get() = legacyJson != null
    val isValid: Boolean get() = !needsSetup && lowerCost.isFinite() && lowerCost >= 0 &&
        upperCost.isFinite() && upperCost > lowerCost && orderAmount.isFinite() && orderAmount > 0 &&
        stepPercent.isFinite() && stepPercent > 0 && stepPercent < 100 &&
        referencePrice.isFinite() && referencePrice > 0 && startAfterTradeId >= 0 &&
        capital.isFinite() && capital > 0 && initialCash.isFinite() && initialCash >= 0 && activationId >= 0
}

data class TradePlanRuntime(
    val activationId: Long = 0L,
    val cash: Double = 0.0,
    val phase: PlanPhase = PlanPhase.NORMAL,
    val cursorGrid: Int = 0,
    val pendingDirection: PlanDirection? = null,
    val pendingGrid: Int? = null,
    val lotGrids: Map<Long, Int> = emptyMap(),
    val gridScale: Double = 1.0,
    val gridOffset: Double = 0.0,
    val revision: Long = 0L
) {
    val isValid: Boolean get() = activationId > 0 && cash.isFinite() && revision >= 0 &&
        gridScale.isFinite() && gridScale > 0 && gridOffset.isFinite() && validIndex(cursorGrid) &&
        ((pendingDirection == null && pendingGrid == null) ||
            (pendingDirection != null && pendingGrid == cursorGrid && validIndex(pendingGrid))) &&
        lotGrids.all { (id, index) -> id > 0 && validIndex(index) }
}

data class PlanAdjustmentInput(
    val netCash: Double,
    val addedShares: Int,
    val cashReference: Double,
    val bonusReferencePercent: Double,
    val newPrice: Double
)

data class PlanAdjustmentRecord(
    val id: Long,
    val time: Long,
    val input: PlanAdjustmentInput,
    val beforeHoldings: List<BuyLot>,
    val beforePlanState: TradePlanRuntime?,
    val beforeCurrentPrice: Double?,
    val beforePrevClose: Double?,
    val afterTradeId: Long,
    val matureQtyAfter: Int,
    val frozenQtyAfter: Int
)

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
    val estimatedNetProfit: Double?,
    val gridIndex: Int? = null,
    val activationId: Long = 0L,
    val revision: Long = 0L
)

data class TradePlanResult(
    val referencePrice: Double,
    val positionCost: Double,
    val upward: TradePlanOrder,
    val downward: TradePlanOrder,
    val phase: PlanPhase = PlanPhase.NORMAL,
    val middleCost: Double = 0.0,
    val availableCash: Double = 0.0,
    val occupiedGridCount: Int = 0
)

data class TriggeredPlanEntry(
    val accountIndex: Int,
    val stock: Stock,
    val currentPrice: Double,
    val orders: List<TradePlanOrder>
)

private val gridMath = MathContext.DECIMAL128
private val hundred = BigDecimal.valueOf(100)
private const val maxRoundQty = Int.MAX_VALUE / 100 * 100
private fun Double.decimal() = BigDecimal.valueOf(this)
private fun validIndex(index: Int?) = index != null && index in -TRADE_PLAN_MAX_GRID_INDEX..TRADE_PLAN_MAX_GRID_INDEX
private fun closeEnough(a: Double, b: Double) = abs(a - b) <= 1e-8 * maxOf(1.0, abs(a), abs(b))
private fun normalizedCash(value: BigDecimal): Double = value.toDouble().let { if (abs(it) < 1e-8) 0.0 else it }

/** Instrument fee treatment is shared by previews and subsequent ledger records. */
fun effectiveTradeFees(stock: Stock, feeConfig: FeeConfig): FeeConfig =
    if (isPlanExchangeFund(stock)) feeConfig.copy(stampTaxRate = 0.0, transferRate = 0.0) else feeConfig

fun isPlanExchangeFund(stock: Stock): Boolean =
    (stock.market == "sh" && stock.code.startsWith("5")) ||
        (stock.market == "sz" && (stock.code.startsWith("15") || stock.code.startsWith("16")))

private fun priceScale(stock: Stock) = if (isPlanExchangeFund(stock)) 3 else 2
private fun active(account: StockAccount): Pair<TradePlanConfig, TradePlanRuntime>? {
    val config = account.tradePlan?.takeIf { it.isValid } ?: return null
    val state = account.tradePlanState?.takeIf { it.isValid && it.activationId == config.activationId } ?: return null
    if (account.holdings.any { it.remainingQty > 0 && it.id !in state.lotGrids }) return null
    return config to state
}

private fun validHoldings(account: StockAccount): Boolean {
    val lots = account.holdings.filter { it.remainingQty > 0 }
    return account.holdings.none { it.remainingQty < 0 } &&
        lots.sumOf { it.remainingQty.toLong() } <= Int.MAX_VALUE &&
        lots.map { it.id }.distinct().size == lots.size &&
        lots.all { it.id > 0 && it.originalQty > 0 && it.price.isFinite() && it.price > 0 && it.fee.isFinite() && it.fee >= 0 } &&
        lots.sumOf { it.price * it.remainingQty }.isFinite()
}

private fun gridPrice(config: TradePlanConfig, state: TradePlanRuntime, index: Int, stock: Stock): Double? {
    if (!validIndex(index)) return null
    return runCatching {
        val ratio = BigDecimal.ONE + config.stepPercent.decimal().movePointLeft(2)
        val power = if (index >= 0) ratio.pow(index, gridMath)
            else BigDecimal.ONE.divide(ratio.pow(-index, gridMath), gridMath)
        (config.referencePrice.decimal().multiply(power, gridMath)
            .multiply(state.gridScale.decimal(), gridMath) + state.gridOffset.decimal())
            .setScale(priceScale(stock), RoundingMode.HALF_UP).toDouble().takeIf { it.isFinite() && it > 0 }
    }.getOrNull()
}

fun tradePlanGridPrice(account: StockAccount, index: Int): Double? =
    active(account)?.let { (config, state) -> gridPrice(config, state, index, account.stock) }

private fun approximateIndex(config: TradePlanConfig, state: TradePlanRuntime, price: Double): Double? {
    if (!price.isFinite() || price <= 0) return null
    val normalized = (price - state.gridOffset) / state.gridScale / config.referencePrice
    return (ln(normalized) / ln1p(config.stepPercent / 100)).takeIf { normalized > 0 && it.isFinite() }
}

private fun nearestIndex(config: TradePlanConfig, state: TradePlanRuntime, price: Double, stock: Stock): Int? {
    val approximate = approximateIndex(config, state, price) ?: return null
    if (approximate < -TRADE_PLAN_MAX_GRID_INDEX - 1.0 || approximate > TRADE_PLAN_MAX_GRID_INDEX + 1.0) return null
    val lower = floor(approximate).toInt().coerceIn(-TRADE_PLAN_MAX_GRID_INDEX, TRADE_PLAN_MAX_GRID_INDEX)
    val upper = ceil(approximate).toInt().coerceIn(-TRADE_PLAN_MAX_GRID_INDEX, TRADE_PLAN_MAX_GRID_INDEX)
    val a = gridPrice(config, state, lower, stock)
    val b = gridPrice(config, state, upper, stock)
    return when {
        a == null -> upper.takeIf { b != null }
        b == null -> lower
        abs(price - a) <= abs(price - b) -> lower
        else -> upper
    }
}

/** Starting a new epoch never invents historical fills or restores a guessed old phase. */
fun startTradePlan(account: StockAccount, config: TradePlanConfig, activationId: Long): StockAccount {
    require(config.isValid && activationId > 0 && validHoldings(account)) { "Invalid trade-plan setup." }
    val configured = config.copy(activationId = activationId,
        startAfterTradeId = account.trades.maxOfOrNull { it.id } ?: 0L, legacyJson = null)
    val initial = TradePlanRuntime(activationId = activationId, cash = config.initialCash)
    val mappings = account.holdings.filter { it.remainingQty > 0 }.associate { lot ->
        lot.id to requireNotNull(nearestIndex(configured, initial, lot.price, account.stock)) { "Unrepresentable initial holding grid." }
    }
    val result = account.copy(tradePlan = configured, tradePlanState = initial.copy(lotGrids = mappings))
    return account.currentPrice?.takeIf { it.isFinite() && it > 0 }?.let { observeTradePlanQuote(result, it) } ?: result
}

/** Quotes visit fixed levels but neither move the anchor nor switch the trading phase. */
fun observeTradePlanQuote(account: StockAccount, price: Double): StockAccount {
    require(price.isFinite() && price > 0) { "Invalid quote." }
    val observed = account.copy(currentPrice = price)
    val (config, state) = active(account) ?: return observed
    val up = gridPrice(config, state, state.cursorGrid + 1, account.stock)
    val down = gridPrice(config, state, state.cursorGrid - 1, account.stock)
    val cursorPrice = gridPrice(config, state, state.cursorGrid, account.stock) ?: return observed
    if (up != null && up <= cursorPrice || down != null && down >= cursorPrice) return observed
    val direction = when {
        up != null && price >= up -> PlanDirection.UP
        down != null && price <= down -> PlanDirection.DOWN
        else -> null
    } ?: return observed
    val approximate = approximateIndex(config, state, price) ?: return observed
    var target = (if (direction == PlanDirection.UP) floor(approximate) else ceil(approximate)).toInt()
        .coerceIn(-TRADE_PLAN_MAX_GRID_INDEX, TRADE_PLAN_MAX_GRID_INDEX)
    target = if (direction == PlanDirection.UP) maxOf(target, state.cursorGrid + 1)
        else minOf(target, state.cursorGrid - 1)
    if (!validIndex(target)) return observed
    // Rounded tick boundaries can differ slightly from the logarithmic estimate.
    for (attempt in 0 until 32) {
        val level = gridPrice(config, state, target, account.stock) ?: break
        var corrected = target
        if (direction == PlanDirection.UP) {
            if (level > price) corrected--
            else if (gridPrice(config, state, target + 1, account.stock)?.let { price >= it } == true) corrected++
        } else {
            if (level < price) corrected++
            else if (gridPrice(config, state, target - 1, account.stock)?.let { price <= it } == true) corrected--
        }
        if (corrected == target) break
        target = corrected
    }
    if (!validIndex(target) || target == state.cursorGrid || gridPrice(config, state, target, account.stock) == null) return observed
    return observed.copy(tradePlanState = state.copy(cursorGrid = target, pendingDirection = direction,
        pendingGrid = target, revision = state.revision + 1))
}

private fun requestedQty(amount: Double, price: Double): Int? = runCatching {
    val shares = amount.decimal().divide(price.decimal() * hundred, 0, RoundingMode.HALF_UP).max(BigDecimal.ONE) * hundred
    if (shares > BigDecimal.valueOf(maxRoundQty.toLong())) null else shares.toInt()
}.getOrNull()

/** Adjustment snapshots keep broker maturity separate from accounting cost matching. */
fun tradePlanSellableQty(account: StockAccount, now: Long): Int {
    val total = account.holdings.sumOf { it.remainingQty.toLong().coerceAtLeast(0) }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (account.stock.market in setOf("hk", "us")) return total
    val adjustment = account.planAdjustments.maxByOrNull { it.id }
        ?: return brokerSellableQty(account.holdings, account.trades, now).coerceIn(0, total)
    class BrokerLot(val time: Long, var qty: Long)
    val lots = mutableListOf(BrokerLot(0L, adjustment.matureQtyAfter.toLong().coerceAtLeast(0)),
        BrokerLot(adjustment.time, adjustment.frozenQtyAfter.toLong().coerceAtLeast(0)))
    account.trades.filter { it.id > adjustment.afterTradeId }.sortedBy { it.time }.forEach { trade ->
        if (trade.type == TradeType.BUY) lots += BrokerLot(trade.time, trade.qty.toLong().coerceAtLeast(0))
        else {
            var remaining = trade.qty.toLong().coerceAtLeast(0)
            val ordered = lots.sortedBy { if (it.time == 0L || !isSameDay(it.time, trade.time)) 0 else 1 }
            for (lot in ordered) {
                val take = minOf(lot.qty, remaining)
                lot.qty -= take
                remaining -= take
                if (remaining == 0L) break
            }
        }
    }
    return lots.sumOf { if (it.time == 0L || (!isSameDay(it.time, now) && it.time <= now)) it.qty else 0L }
        .coerceIn(0L, total.toLong()).toInt()
}

fun evaluateTradePlan(account: StockAccount, feeConfig: FeeConfig, now: Long = System.currentTimeMillis()): TradePlanResult? {
    val (config, state) = active(account) ?: return null
    val lots = account.holdings.filter { it.remainingQty > 0 }
    val holdingsValid = validHoldings(account)
    val quantity = lots.sumOf { it.remainingQty.toLong() }.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val cost = if (holdingsValid) lots.fold(BigDecimal.ZERO) { sum, lot -> sum + lot.price.decimal() * lot.remainingQty.toBigDecimal() } else BigDecimal.ZERO
    val current = account.currentPrice?.takeIf { it.isFinite() && it > 0 }
    val fees = effectiveTradeFees(account.stock, feeConfig)
    fun order(direction: PlanDirection): TradePlanOrder {
        val index = if (state.pendingDirection == direction) state.pendingGrid!!
            else state.cursorGrid + if (direction == PlanDirection.UP) 1 else -1
        val target = gridPrice(config, state, index, account.stock)
        val neighbor = gridPrice(config, state, index + if (direction == PlanDirection.UP) -1 else 1, account.stock)
        val reached = state.pendingDirection == direction && current != null && target != null &&
            if (direction == PlanDirection.UP) current >= target else current <= target
        val execution = if (reached) current else target
        fun blocked(block: PlanBlock) = TradePlanOrder(direction, null, execution, target, 0, null, reached, block,
            PlanReason.WAIT, quantity, if (holdingsValid) cost.toDouble() else null, null, null,
            index, state.activationId, state.revision)
        if (!holdingsValid) return blocked(PlanBlock.INVALID_HOLDINGS)
        if (lots.any { it.id !in state.lotGrids }) return blocked(PlanBlock.INVALID_STATE)
        if (state.cash < 0) return blocked(PlanBlock.INSUFFICIENT_CASH)
        if (execution == null || target == null || neighbor == null ||
            (direction == PlanDirection.UP && target <= neighbor) ||
            (direction == PlanDirection.DOWN && target >= neighbor)) return blocked(PlanBlock.INVALID_PRICE)
        val qty = requestedQty(config.orderAmount, execution) ?: return blocked(PlanBlock.INVALID_PRICE)
        val upward = direction == PlanDirection.UP
        val type = when {
            upward && state.phase == PlanPhase.UP_FOLLOW -> TradeType.BUY
            upward -> TradeType.SELL
            state.phase == PlanPhase.DOWN_REDUCTION -> TradeType.SELL
            else -> TradeType.BUY
        }
        val reason = when {
            upward && type == TradeType.BUY -> PlanReason.UP_REPLENISH
            upward -> PlanReason.UP_PROFIT
            type == TradeType.SELL -> PlanReason.DOWN_REDUCE
            state.phase == PlanPhase.UP_FOLLOW -> PlanReason.PULLBACK_BUY
            else -> PlanReason.DOWN_BUY
        }
        if (type == TradeType.BUY) {
            if (lots.any { state.lotGrids[it.id] == index }) return blocked(PlanBlock.OCCUPIED_GRID)
            if (upward && lots.any { it.price >= execution }) return blocked(PlanBlock.NO_BREAKOUT)
            if (quantity.toLong() + qty > Int.MAX_VALUE) return blocked(PlanBlock.INVALID_PRICE)
        } else {
            if (quantity < qty) return blocked(PlanBlock.WAIT)
            if (upward && lots.filter { it.price < execution }.sumOf { it.remainingQty.toLong() } < qty)
                return blocked(PlanBlock.INSUFFICIENT_PROFITABLE_SHARES)
            if (tradePlanSellableQty(account, now) < qty) return blocked(PlanBlock.INSUFFICIENT_SETTLED_SHARES)
        }
        val amount = execution * qty
        if (!amount.isFinite()) return blocked(PlanBlock.INVALID_PRICE)
        val fee = if (type == TradeType.BUY) fees.buyFee(amount) else fees.sellFee(amount)
        if (!fee.isFinite() || fee < 0) return blocked(PlanBlock.INVALID_PRICE)
        if (type == TradeType.BUY && execution.decimal() * qty.toBigDecimal() + fee.decimal() > state.cash.decimal())
            return blocked(PlanBlock.INSUFFICIENT_CASH)
        val match = if (type == TradeType.SELL) if (upward) SellMatch.LOWEST else SellMatch.HIGHEST else null
        val sale = match?.let { matchSellLots(lots, qty, execution, it) }
        val resultCost = sale?.remaining?.sumOf { it.price * it.remainingQty } ?: (cost.toDouble() + amount)
        if (!resultCost.isFinite()) return blocked(PlanBlock.INVALID_PRICE)
        return TradePlanOrder(direction, type, execution, target, qty, match, reached, null, reason,
            quantity + if (type == TradeType.BUY) qty else -qty, resultCost, fee,
            sale?.let { it.profit - it.costFee - fee }, index, state.activationId, state.revision)
    }
    return TradePlanResult(config.referencePrice, cost.toDouble(), order(PlanDirection.UP), order(PlanDirection.DOWN),
        state.phase, (config.lowerCost / 2 + config.upperCost / 2), state.cash,
        lots.mapNotNull { state.lotGrids[it.id] }.distinct().size)
}

fun triggeredPlanEntries(accounts: List<StockAccount>, feeConfig: FeeConfig, now: Long = System.currentTimeMillis()): List<TriggeredPlanEntry> =
    accounts.mapIndexedNotNull { index, account ->
        val price = account.currentPrice?.takeIf { it.isFinite() && it > 0 } ?: return@mapIndexedNotNull null
        val result = evaluateTradePlan(account, feeConfig, now) ?: return@mapIndexedNotNull null
        val orders = listOf(result.upward, result.downward).filter { it.reached && it.block == null }
        if (orders.isEmpty()) null else TriggeredPlanEntry(index, account.stock, price, orders)
    }

/** Actual manual fills remain authoritative, even when they deviate from suggestions. */
fun afterTradePlanFill(before: StockAccount, after: StockAccount, trade: TradeRecord, gridIndex: Int? = null): StockAccount {
    val (config, state) = active(before) ?: return after
    val index = gridIndex?.takeIf { validIndex(it) }
        ?: nearestIndex(config, state, trade.price, before.stock) ?: state.cursorGrid
    val alive = after.holdings.filter { it.remainingQty > 0 }.map { it.id }.toSet()
    val mappings = state.lotGrids.filterKeys { it in alive }.toMutableMap()
    if (trade.type == TradeType.BUY) trade.lotId?.takeIf { it in alive }?.let { mappings[it] = index }
    after.holdings.filter { it.remainingQty > 0 && it.id !in mappings }.forEach { lot ->
        nearestIndex(config, state, lot.price, after.stock)?.let { mappings[lot.id] = it }
    }
    val amount = trade.price.decimal() * trade.qty.toBigDecimal()
    val cash = normalizedCash(state.cash.decimal() +
        (if (trade.type == TradeType.BUY) amount.negate() else amount) - trade.fee.decimal())
    val middle = config.lowerCost / 2 + config.upperCost / 2
    val cost = after.holdings.sumOf { it.price * it.remainingQty }
    val phase = when (state.phase) {
        PlanPhase.DOWN_REDUCTION -> if (cost <= middle || closeEnough(cost, middle)) PlanPhase.NORMAL else state.phase
        PlanPhase.UP_FOLLOW -> if (cost >= middle || closeEnough(cost, middle)) PlanPhase.NORMAL else state.phase
        PlanPhase.NORMAL -> when {
            trade.type == TradeType.BUY && (cost >= config.upperCost || closeEnough(cost, config.upperCost)) -> PlanPhase.DOWN_REDUCTION
            trade.type == TradeType.SELL && (cost <= config.lowerCost || closeEnough(cost, config.lowerCost)) -> PlanPhase.UP_FOLLOW
            else -> PlanPhase.NORMAL
        }
    }
    return after.copy(tradePlanState = state.copy(cash = cash, phase = phase, cursorGrid = index,
        pendingDirection = null, pendingGrid = null, lotGrids = mappings, revision = state.revision + 1),
        trades = after.trades.map { if (it.id == trade.id) it.copy(planBefore = state) else it })
}

fun validateTradePlanFill(account: StockAccount, order: TradePlanOrder, price: Double, qty: Int,
                          feeConfig: FeeConfig, now: Long = System.currentTimeMillis()): String? {
    if (!price.isFinite() || price <= 0 || qty <= 0) return "请输入正确的实际成交价和数量"
    val (_, state) = active(account) ?: return "请先启用新仓位跟随策略"
    if (order.activationId != state.activationId || order.revision != state.revision) return "提示已更新，请返回刷新后重新选择"
    val result = evaluateTradePlan(account, feeConfig, now) ?: return "策略状态无效，请重新起算"
    val current = if (order.direction == PlanDirection.UP) result.upward else result.downward
    if (!order.reached || !current.reached || current.block != null || current.type == null || order.type != current.type ||
        order.gridIndex != current.gridIndex || order.match != current.match) return "当前条件不再允许此操作，请返回重新核对"
    val fees = effectiveTradeFees(account.stock, feeConfig)
    val amount = price * qty
    if (!amount.isFinite()) return "成交金额超出有效范围"
    if (current.type == TradeType.BUY) {
        if (account.totalQty.toLong() + qty > Int.MAX_VALUE) return "成交后股数超出有效范围"
        if (account.holdings.any { it.remainingQty > 0 && state.lotGrids[it.id] == current.gridIndex }) return "该格仍有持仓，不能重复买入"
        if (state.phase == PlanPhase.UP_FOLLOW && order.direction == PlanDirection.UP &&
            account.holdings.any { it.remainingQty > 0 && it.price >= price }) return "实际买入价尚未突破最高持仓买价"
        val fee = fees.buyFee(amount)
        if (!fee.isFinite() || fee < 0 || amount.decimal() + fee.decimal() > state.cash.decimal()) return "策略可用现金不足"
    } else {
        if (qty > account.totalQty) return "卖出数量超过持仓"
        if (qty > tradePlanSellableQty(account, now)) return "T+1可卖数量不足，请核对券商"
        if (order.direction == PlanDirection.UP &&
            account.holdings.filter { it.remainingQty > 0 && it.price < price }.sumOf { it.remainingQty.toLong() } < qty)
            return "实际成交价下的盈利批次数量不足"
    }
    return null
}

fun validateTradePlanAdjustment(account: StockAccount, input: PlanAdjustmentInput): String? {
    val (config, state) = active(account) ?: return "请先启用新仓位跟随策略"
    if (!validHoldings(account)) return "持仓数据无效，请先核对"
    if (!input.netCash.isFinite() || !input.cashReference.isFinite() || input.cashReference < 0 ||
        !input.bonusReferencePercent.isFinite() || input.bonusReferencePercent < 0 ||
        !input.newPrice.isFinite() || input.newPrice <= 0 || input.addedShares < 0) return "请填写有效的到账数据和除权后现价"
    val total = account.holdings.sumOf { it.remainingQty.toLong() }
    if (total + input.addedShares > Int.MAX_VALUE || (input.addedShares > 0 && total <= 0)) return "新增股份与当前持仓不匹配"
    val adjustedCash = normalizedCash(state.cash.decimal() + input.netCash.decimal())
    if (!adjustedCash.isFinite() || adjustedCash < 0) return "现金变化后策略余额不足，请核对券商"
    if (input.netCash == 0.0 && input.addedShares == 0 && input.cashReference == 0.0 && input.bonusReferencePercent == 0.0)
        return "请填写现金、转增或除权调整"
    val divisor = 1 + input.bonusReferencePercent / 100
    val adjusted = state.copy(gridScale = state.gridScale / divisor,
        gridOffset = (state.gridOffset - input.cashReference) / divisor)
    if (!adjusted.isValid || nearestIndex(config, adjusted, input.newPrice, account.stock) == null) return "除权参数无法形成有效网格"
    return null
}

fun applyTradePlanAdjustment(account: StockAccount, input: PlanAdjustmentInput, now: Long, id: Long): StockAccount {
    require(validateTradePlanAdjustment(account, input) == null) { "Invalid plan adjustment." }
    val (config, state) = requireNotNull(active(account))
    require(id > maxOf(account.trades.maxOfOrNull { it.id } ?: 0L,
        account.planAdjustments.maxOfOrNull { it.id } ?: 0L, config.activationId)) { "Adjustment IDs must be monotonic." }
    val activeLots = account.holdings.filter { it.remainingQty > 0 }
    val total = activeLots.sumOf { it.remainingQty.toLong() }
    val allocations = activeLots.associate { lot ->
        lot.id to if (total > 0) (input.addedShares.toLong() * lot.remainingQty / total).toInt() else 0
    }.toMutableMap()
    var remaining = input.addedShares - allocations.values.sum()
    if (remaining > 0) {
        activeLots.sortedWith(compareByDescending<BuyLot> {
            input.addedShares.toLong() * it.remainingQty % total
        }.thenBy { it.id }).forEach { lot ->
            if (remaining > 0) { allocations[lot.id] = allocations.getValue(lot.id) + 1; remaining-- }
        }
    }
    val mature = tradePlanSellableQty(account, now)
    val holdings = account.holdings.map { lot ->
        val added = allocations[lot.id] ?: 0
        if (added == 0) lot else {
            val newQty = lot.remainingQty + added
            val heldFee = lot.fee * lot.remainingQty / lot.originalQty
            lot.copy(price = (lot.price.decimal() * lot.remainingQty.toBigDecimal())
                .divide(newQty.toBigDecimal(), gridMath).toDouble(), originalQty = newQty,
                remainingQty = newQty, fee = heldFee)
        }
    }
    val divisor = 1 + input.bonusReferencePercent / 100
    val transformed = state.copy(cash = normalizedCash(state.cash.decimal() + input.netCash.decimal()), gridScale = state.gridScale / divisor,
        gridOffset = (state.gridOffset - input.cashReference) / divisor,
        pendingDirection = null, pendingGrid = null, revision = state.revision + 1)
    val cursor = requireNotNull(nearestIndex(config, transformed, input.newPrice, account.stock))
    val record = PlanAdjustmentRecord(id, now, input, account.holdings, state, account.currentPrice, account.prevClose,
        account.trades.maxOfOrNull { it.id } ?: 0L, mature + input.addedShares, total.toInt() - mature)
    return account.copy(holdings = holdings, currentPrice = input.newPrice, prevClose = null,
        tradePlanState = transformed.copy(cursorGrid = cursor), planAdjustments = account.planAdjustments + record)
}
