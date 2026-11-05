package com.example.stocktracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stocktracker.FeeConfig
import com.example.stocktracker.PlanBlock
import com.example.stocktracker.PlanDirection
import com.example.stocktracker.PlanReason
import com.example.stocktracker.StockAccount
import com.example.stocktracker.TradePlanConfig
import com.example.stocktracker.TradePlanOrder
import com.example.stocktracker.TradePlanResult
import com.example.stocktracker.TriggeredPlanEntry
import com.example.stocktracker.TradeType
import com.example.stocktracker.evaluateTradePlan
import com.example.stocktracker.formatMoney
import com.example.stocktracker.formatPrice
import com.example.stocktracker.ui.theme.DownColor
import com.example.stocktracker.ui.theme.UpColor

@Composable
internal fun TradePlanCard(
    account: StockAccount,
    feeConfig: FeeConfig,
    onSettings: () -> Unit,
    onTrade: (TradePlanOrder) -> Unit
) {
    val result = evaluateTradePlan(account, feeConfig)
    var showDetails by remember(account.stock.marketCode) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("仓位跟随", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (result == null) onSettings() else showDetails = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (result == null) "设置提醒" else "详情", fontSize = 12.sp)
                }
            }
            if (result == null) {
                PlanHint(if (account.tradePlan?.needsSetup == true) "旧提醒参数已保留，请重新设置金额上下限。"
                    else "设置持仓成本上下限、每格金额和百分比步长。")
            } else {
                CompactPlanOrderRow(result.upward, onTrade)
                HorizontalDivider()
                CompactPlanOrderRow(result.downward, onTrade)
            }
        }
    }
    if (showDetails && result != null) {
        TradePlanDetailsDialog(account, result,
            onSettings = { showDetails = false; onSettings() },
            onTrade = { order -> showDetails = false; onTrade(order) },
            onDismiss = { showDetails = false })
    }
}

@Composable
private fun CompactPlanOrderRow(order: TradePlanOrder, onTrade: (TradePlanOrder) -> Unit) {
    val direction = if (order.direction == PlanDirection.UP) "上涨" else "下跌"
    val side = when (order.type) { TradeType.BUY -> "买入"; TradeType.SELL -> "卖出"; null -> "等待" }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.width(40.dp)) {
            Text(direction, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = when {
                order.block == PlanBlock.WAIT -> "等待"
                order.block != null -> "需设置"
                order.reached -> "已到价"
                else -> null
            }
            status?.let { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary) }
        }
        Text(order.triggerPrice?.let { "¥${formatPrice(it)}" } ?: "—", fontSize = 16.sp,
            fontWeight = FontWeight.Bold, color = if (order.direction == PlanDirection.UP) UpColor else DownColor,
            modifier = Modifier.weight(1f))
        Text(if (order.type == null) side else "$side${order.qty}股", fontSize = 12.sp)
        TextButton(onClick = { onTrade(order) }, enabled = order.block == null,
            modifier = Modifier.semantics { contentDescription = "记录$direction$side" },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("记录", fontSize = 12.sp)
        }
    }
}

@Composable
private fun TradePlanDetailsDialog(
    account: StockAccount,
    result: TradePlanResult,
    onSettings: () -> Unit,
    onTrade: (TradePlanOrder) -> Unit,
    onDismiss: () -> Unit
) {
    val config = account.tradePlan!!
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("提醒详情 · ${account.stock.name}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanHint("买入成本下限 ¥${formatMoney(config.lowerCost)} · 上限 ¥${formatMoney(config.upperCost)}")
                PlanHint("每格约 ¥${formatMoney(config.orderAmount)} · 步长 ${formatPrice(config.stepPercent)}%")
                PlanHint("当前剩余买入成本 ¥${formatMoney(result.positionCost)} · 成交参考 ¥${formatPrice(result.referencePrice)}")
                PlanHint("该股累计已实现净盈亏 ${if (account.realizedPnlFee >= 0) "+" else ""}${formatMoney(account.realizedPnlFee)} 元")
                PlanOrderRow(result.upward, onTrade)
                HorizontalDivider()
                PlanOrderRow(result.downward, onTrade)
                val price = account.currentPrice?.let { "¥${formatPrice(it)}" } ?: "未设置"
                PlanHint("按显示现价 $price 判断；刷新行情或设置现价后更新。")
                PlanHint("两侧均以最近记账成交价起算；行情刷新和等待不移动参考价。")
                PlanHint("触发价按0.01元向外取整；已越过触发位时，按显示现价重算数量和买卖方向。")
                PlanHint("股数按100股取整并受成本边界裁剪；上涨只卖买价低于操作价的批次，费用另扣。")
                PlanHint("仅提示并记录实际成交；请在券商核对可用资金。买入成本不含手续费和已实现亏损。")
            }
        },
        confirmButton = { TextButton(onClick = onSettings) { Text("调整设置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun PlanOrderRow(order: TradePlanOrder, onTrade: (TradePlanOrder) -> Unit) {
    val side = when (order.type) { TradeType.BUY -> "买入"; TradeType.SELL -> "卖出"; null -> "等待" }
    val direction = if (order.direction == PlanDirection.UP) "上涨" else "下跌"
    val price = order.triggerPrice?.let { "¥${formatPrice(it)}" } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$direction 至 $price · ${if (order.type == null) side else "$side ${order.qty} 股"}", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onTrade(order) }, enabled = order.block == null) { Text("记录$side") }
        }
        val status = when (order.block) {
            PlanBlock.WAIT -> "当前仓位边界或整手条件不允许操作，继续等待"
            PlanBlock.INVALID_PRICE -> "当前价格或数量无法形成有效的一格，请调整配置"
            PlanBlock.INVALID_HOLDINGS -> "Invalid holdings data. Check quantities and prices."
            null -> if (order.reached) "已到达触发价，请核对实际行情" else "等待价格到达"
        }
        Text(status, fontSize = 12.sp,
            color = if (order.reached && order.block == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
        val reason = when (order.reason) {
            PlanReason.UP_PROFIT -> "有盈利批次，卖出最低买价批次并保留成本底仓。"
            PlanReason.UP_REPLENISH -> "接近成本底仓或没有可卖盈利批次，改为买入。"
            PlanReason.DOWN_BUY -> "仍有成本额度，下跌买入。"
            PlanReason.DOWN_REDUCE -> "成本额度不足再买100股，下跌卖出最高买价批次。"
            PlanReason.WAIT -> "本格不新增记账操作。"
        }
        PlanHint(reason)
        if (order.type != null) {
            order.match?.let { PlanHint("记账按${it.label}；操作后 ${order.resultingQty} 股") }
                ?: PlanHint("操作后 ${order.resultingQty} 股")
            PlanHint("股数和费用按 ¥${formatPrice(order.price!!)} 估算；已到价时按显示现价预填，请核对实际成交。")
        }
        order.resultingCost?.let { PlanHint("预计剩余买入成本 ¥${formatMoney(it)} · 本次手续费 ¥${formatMoney(order.estimatedFee ?: 0.0)}") }
        order.estimatedNetProfit?.let {
            PlanHint("本次预计已实现净盈亏 ${if (it >= 0) "+" else ""}${formatMoney(it)} 元")
        }
    }
}

@Composable
private fun PlanHint(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun TriggeredPlansPanel(
    entries: List<TriggeredPlanEntry>,
    onOpenStock: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlanHint("按当前显示现价筛选；未设置提醒或现价未知的股票不参与。")
        if (entries.isEmpty()) {
            Text("暂无已触发的买卖提醒", modifier = Modifier.padding(vertical = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                items(entries, key = { it.stock.marketCode }) { entry ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenStock(entry.accountIndex) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.stock.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    PlanHint(entry.stock.marketCode)
                                }
                                Text("现价 ¥${formatPrice(entry.currentPrice)}", fontSize = 12.sp)
                            }
                            entry.orders.forEach { order ->
                                val direction = if (order.direction == PlanDirection.UP) "上涨" else "下跌"
                                val side = if (order.type == TradeType.BUY) "买入" else "卖出"
                                Text("$direction ¥${formatPrice(order.triggerPrice!!)} · $side ${order.qty}股",
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = if (order.direction == PlanDirection.UP) UpColor else DownColor)
                            }
                            PlanHint("点击查看该股票")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TradePlanSettingsDialog(
    account: StockAccount,
    feeConfig: FeeConfig,
    onSave: (TradePlanConfig?) -> Unit,
    onDismiss: () -> Unit
) {
    val existing = account.tradePlan
    val active = existing?.takeUnless { it.needsSetup }
    var lowerText by remember { mutableStateOf(formatPrice(active?.lowerCost ?: 15_000.0)) }
    var upperText by remember { mutableStateOf(formatPrice(active?.upperCost ?: 50_000.0)) }
    var amountText by remember { mutableStateOf(formatPrice(active?.orderAmount ?: 4_000.0)) }
    var stepText by remember { mutableStateOf(formatPrice(active?.stepPercent ?: 4.0)) }
    val initialReference = evaluateTradePlan(account, feeConfig)?.referencePrice
        ?: account.trades.lastOrNull { it.price.isFinite() && it.price > 0 }?.price
        ?: account.currentPrice?.takeIf { it.isFinite() && it > 0 }
        ?: account.holdings.filter { it.remainingQty > 0 && it.price.isFinite() && it.price > 0 }.minOfOrNull { it.price }
    var referenceText by remember { mutableStateOf(initialReference?.let(::formatPrice) ?: "") }
    val lower = lowerText.toDoubleOrNull()
    val upper = upperText.toDoubleOrNull()
    val amount = amountText.toDoubleOrNull()
    val step = stepText.toDoubleOrNull()
    val reference = referenceText.toDoubleOrNull()
    val config = if (lower != null && upper != null && amount != null && step != null && reference != null)
        TradePlanConfig(lowerCost = lower, upperCost = upper, orderAmount = amount,
            stepPercent = step, referencePrice = reference,
            startAfterTradeId = account.trades.maxOfOrNull { it.id } ?: 0L).takeIf { it.isValid } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("仓位跟随 · ${account.stock.name}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing?.needsSetup == true) PlanHint("旧提醒参数已保留；保存后启用新的金额跟随规则。")
                PlanNumberField(lowerText, { lowerText = it }, "持仓成本下限（元）")
                PlanNumberField(upperText, { upperText = it }, "持仓成本上限（元）")
                PlanHint("按剩余批次的原始买入成本计算，不随现价涨跌变化；不含手续费。")
                PlanNumberField(amountText, { amountText = it }, "每格交易金额（元）")
                PlanNumberField(stepText, { stepText = it }, "步长（%）")
                PlanHint("股数按最接近金额的100股整数倍计算，临近边界时缩量。")
                PlanNumberField(referenceText, { referenceText = it }, "初始成交参考价（元）")
                PlanHint("默认最近实际记账成交价；无记录时使用现价。保存后以此价重新起算，之后只随交易记账更新。")
                PlanHint("上涨优先卖盈利的最低买价批次，否则补仓；下跌有额度就买，额度不足则卖最高买价批次。")
                if (config == null) PlanHint("Enter finite values: 0 ≤ lower < upper, amount > 0, 0 < step < 100, reference > 0.")
                if (existing != null) {
                    TextButton(onClick = { onSave(null) }) { Text("关闭此股票的提醒") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { config?.let(onSave) }, enabled = config != null) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PlanNumberField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean = true) {
    OutlinedTextField(value, onValueChange = { text ->
        val filtered = text.filter { it in '0'..'9' || (decimal && it == '.') }
        if (filtered.count { it == '.' } <= 1) onChange(filtered)
    }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}
