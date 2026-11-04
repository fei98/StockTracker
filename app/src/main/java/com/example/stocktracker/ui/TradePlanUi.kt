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
import androidx.compose.material3.FilterChip
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
import com.example.stocktracker.PlanStepMode
import com.example.stocktracker.PlanThresholdMode
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
                Text("仓位提醒", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (result == null) onSettings() else showDetails = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (result == null) "设置提醒" else "详情", fontSize = 12.sp)
                }
            }
            if (result == null) {
                PlanHint("设置每份股数、仓位阈值和步长。")
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
    val side = if (order.type == TradeType.BUY) "买入" else "卖出"
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.width(40.dp)) {
            Text(direction, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = when {
                order.block == PlanBlock.NO_HOLDINGS -> "无持仓"
                order.block != null -> "需设置"
                order.reached -> "已到价"
                else -> null
            }
            status?.let { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary) }
        }
        Text(order.price?.let { "¥${formatPrice(it)}" } ?: "—", fontSize = 16.sp,
            fontWeight = FontWeight.Bold, color = if (order.direction == PlanDirection.UP) UpColor else DownColor,
            modifier = Modifier.weight(1f))
        Text("$side${order.qty}股", fontSize = 12.sp)
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
    val threshold = if (config.thresholdMode == PlanThresholdMode.QUANTITY)
        "${config.threshold.toInt()} 股" else "¥${formatMoney(config.threshold)}"
    val step = if (config.stepMode == PlanStepMode.PRICE)
        "¥${formatPrice(config.step)}" else "${formatPrice(config.step)}%"
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("提醒详情 · ${account.stock.name}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanHint("仓位阈值 $threshold · 每份 ${config.unitQty} 股 · 步长 $step")
                PlanHint("${if (result.overThreshold) "已超过阈值，下跌减仓" else "未超过阈值，下跌买入一份"} · 下行参考 ¥${formatPrice(result.referencePrice)}")
                PlanHint("该股累计已实现净盈亏 ${if (account.realizedPnlFee >= 0) "+" else ""}${formatMoney(account.realizedPnlFee)} 元")
                PlanOrderRow(result.upward, onTrade)
                HorizontalDivider()
                PlanOrderRow(result.downward, onTrade)
                val price = account.currentPrice?.let { "¥${formatPrice(it)}" } ?: "未设置"
                PlanHint("按显示现价 $price 判断；刷新行情或设置现价后更新。")
                PlanHint("仅提示并记录实际成交；成交记账后才更新下一格。持仓成本不含已实现亏损。")
            }
        },
        confirmButton = { TextButton(onClick = onSettings) { Text("调整设置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun PlanOrderRow(order: TradePlanOrder, onTrade: (TradePlanOrder) -> Unit) {
    val side = if (order.type == TradeType.BUY) "买入" else "卖出"
    val direction = if (order.direction == PlanDirection.UP) "上涨" else "下跌"
    val price = order.price?.let { "¥${formatPrice(it)}" } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$direction 至 $price · $side ${order.qty} 股", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onTrade(order) }, enabled = order.block == null) { Text("记录$side") }
        }
        val status = when (order.block) {
            PlanBlock.NO_HOLDINGS -> "当前无持仓可卖"
            PlanBlock.INVALID_PRICE -> "当前价格或数量无法形成有效的一格，请调整配置"
            null -> if (order.reached) "已到达触发价，请核对实际行情" else "等待价格到达"
        }
        Text(status, fontSize = 12.sp,
            color = if (order.reached && order.block == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
        order.match?.let { PlanHint("记账按${it.label}；操作后 ${order.resultingQty} 股") }
            ?: PlanHint("买入一份，操作后 ${order.resultingQty} 股")
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
                                Text("$direction ¥${formatPrice(order.price!!)} · $side ${order.qty}股",
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
    var thresholdMode by remember { mutableStateOf(existing?.thresholdMode ?: PlanThresholdMode.QUANTITY) }
    var thresholdText by remember { mutableStateOf(existing?.threshold?.let(::formatPrice) ?: account.totalQty.toString()) }
    var qtyText by remember { mutableStateOf((existing?.unitQty ?: 100).toString()) }
    var stepMode by remember { mutableStateOf(existing?.stepMode ?: PlanStepMode.PRICE) }
    var stepText by remember { mutableStateOf(existing?.step?.let(::formatPrice) ?: "") }
    val initialReference = evaluateTradePlan(account, feeConfig)?.referencePrice
        ?: account.holdings.filter { it.remainingQty > 0 }.minOfOrNull { it.price }
        ?: account.currentPrice ?: account.trades.lastOrNull()?.price
    var referenceText by remember { mutableStateOf(initialReference?.let(::formatPrice) ?: "") }
    val threshold = thresholdText.toDoubleOrNull()
    val qty = qtyText.toIntOrNull()
    val step = stepText.toDoubleOrNull()
    val reference = referenceText.toDoubleOrNull()
    val config = if (threshold != null && qty != null && step != null && reference != null)
        TradePlanConfig(thresholdMode, threshold, qty, stepMode, step, reference,
            account.trades.maxOfOrNull { it.id } ?: 0L).takeIf { it.isValid } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("买卖提醒 · ${account.stock.name}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仓位阈值", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(thresholdMode == PlanThresholdMode.QUANTITY, onClick = {
                        if (thresholdMode != PlanThresholdMode.QUANTITY) {
                            thresholdMode = PlanThresholdMode.QUANTITY
                            thresholdText = account.totalQty.toString()
                        }
                    }, label = { Text("股数") })
                    FilterChip(thresholdMode == PlanThresholdMode.COST, onClick = {
                        if (thresholdMode != PlanThresholdMode.COST) {
                            thresholdMode = PlanThresholdMode.COST
                            thresholdText = formatPrice(account.totalCost)
                        }
                    }, label = { Text("买入成本") })
                }
                PlanNumberField(thresholdText, { thresholdText = it },
                    if (thresholdMode == PlanThresholdMode.QUANTITY) "仓位阈值（股）" else "剩余持仓买入成本阈值（元）",
                    decimal = thresholdMode == PlanThresholdMode.COST)
                PlanHint("超过阈值，下跌一格卖一份；等于或低于阈值，下跌一格买一份，可连续买入。")
                PlanHint("阈值是买卖方向分界，允许暂时超过一份；买入成本不含手续费，不是现价市值。")
                PlanNumberField(qtyText, { qtyText = it }, "每份股数", decimal = false)
                PlanHint("请按该股票在券商允许的交易单位设置每份股数。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(stepMode == PlanStepMode.PRICE, { stepMode = PlanStepMode.PRICE }, label = { Text("固定价差") })
                    FilterChip(stepMode == PlanStepMode.PERCENT, { stepMode = PlanStepMode.PERCENT }, label = { Text("百分比") })
                }
                PlanNumberField(stepText, { stepText = it }, if (stepMode == PlanStepMode.PRICE) "步长（元）" else "步长（%，大于0且小于100）")
                PlanNumberField(referenceText, { referenceText = it }, "下行初始参考价（元）")
                PlanHint("初始默认最低持仓买价。保存后重新以此价起算；之后只随实际交易记账更新。")
                PlanHint("上涨卖点＝最低持仓买价＋一步，卖最低价批次；下跌减仓卖最高价批次。")
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
