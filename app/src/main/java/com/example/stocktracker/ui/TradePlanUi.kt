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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stocktracker.FeeConfig
import com.example.stocktracker.PlanAdjustmentInput
import com.example.stocktracker.PlanBlock
import com.example.stocktracker.PlanDirection
import com.example.stocktracker.PlanPhase
import com.example.stocktracker.PlanReason
import com.example.stocktracker.R
import com.example.stocktracker.StockAccount
import com.example.stocktracker.TradePlanConfig
import com.example.stocktracker.TradePlanOrder
import com.example.stocktracker.TradePlanPresetStore
import com.example.stocktracker.TradePlanResult
import com.example.stocktracker.TradeType
import com.example.stocktracker.TriggeredPlanEntry
import com.example.stocktracker.evaluateTradePlan
import com.example.stocktracker.formatMoney
import com.example.stocktracker.formatPrice
import com.example.stocktracker.ui.theme.DownColor
import com.example.stocktracker.ui.theme.UpColor
import com.example.stocktracker.validateTradePlanAdjustment
import java.math.BigDecimal

@Composable
internal fun TradePlanCard(
    account: StockAccount,
    feeConfig: FeeConfig,
    onSettings: () -> Unit,
    onTrade: (TradePlanOrder) -> Unit,
    onAdjustment: (PlanAdjustmentInput) -> Boolean = { false }
) {
    val result = evaluateTradePlan(account, feeConfig)
    var showDetails by remember(account.stock.marketCode) { mutableStateOf(false) }
    var showAdjustment by remember(account.stock.marketCode) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("仓位跟随", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (result == null) onSettings() else showDetails = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (result == null) "设置策略" else "详情", fontSize = 12.sp)
                }
            }
            if (result == null) {
                PlanHint(if (account.tradePlan?.needsSetup == true)
                    "旧配置已保留，请确认新模型参数与可用现金后启用。"
                else "每只股票独立设置上下限、每笔金额、步长和可用现金。")
            } else {
                PlanHint("${phaseLabel(result.phase)} · 中线 ¥${money(result.middleCost)} · 现金 ¥${money(result.availableCash)}")
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
            onAdjustment = { showDetails = false; showAdjustment = true },
            onDismiss = { showDetails = false })
    }
    if (showAdjustment) TradePlanAdjustmentDialog(account,
        onSave = {
            val applied = onAdjustment(it)
            if (applied) showAdjustment = false
            applied
        },
        onDismiss = { showAdjustment = false })
}

@Composable
private fun CompactPlanOrderRow(order: TradePlanOrder, onTrade: (TradePlanOrder) -> Unit) {
    val direction = directionLabel(order)
    val side = sideLabel(order)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.width(48.dp)) {
            Text(direction, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(order.block?.let(::blockShortLabel) ?: if (order.reached) "已到价" else "未到价",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(order.triggerPrice?.let { "¥${formatPrice(it)}" } ?: "—", fontSize = 16.sp,
            fontWeight = FontWeight.Bold, color = if (order.direction == PlanDirection.UP) UpColor else DownColor,
            modifier = Modifier.weight(1f))
        Text(if (order.type == null) side else "$side${order.qty}股", fontSize = 12.sp)
        TextButton(onClick = { onTrade(order) }, enabled = canRecord(order),
            modifier = Modifier.semantics { contentDescription = "记录$direction$side" },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("记录", fontSize = 12.sp) }
    }
}

@Composable
private fun TradePlanDetailsDialog(
    account: StockAccount, result: TradePlanResult, onSettings: () -> Unit,
    onTrade: (TradePlanOrder) -> Unit, onAdjustment: () -> Unit, onDismiss: () -> Unit
) {
    val config = account.tradePlan ?: return
    AlertDialog(onDismissRequest = onDismiss, title = { Text("策略详情 · ${account.stock.name}") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phaseLabel(result.phase), fontWeight = FontWeight.Bold)
                PlanHint("成本下限 ¥${money(config.lowerCost)} · 中线 ¥${money(result.middleCost)} · 上限 ¥${money(config.upperCost)}")
                PlanHint("每笔目标 ¥${money(config.orderAmount)} · 百分比步长 ${formatPrice(config.stepPercent)}%")
                PlanHint("剩余原始成本 ¥${money(result.positionCost)} · 可用现金 ¥${money(result.availableCash)}")
                PlanHint("固定锚点 ¥${formatPrice(result.referencePrice)} · 当前占用 ${result.occupiedGridCount} 格")
                PlanHint("该股累计已实现净盈亏 ${if (account.realizedPnlFee >= 0) "+" else ""}${money(account.realizedPnlFee)} 元")
                PlanOrderRow(result.upward, onTrade)
                HorizontalDivider()
                PlanOrderRow(result.downward, onTrade)
                PlanHint("按显示现价 ${account.currentPrice?.let { "¥${formatPrice(it)}" } ?: "未设置"} 判断；刷新或手动设置后更新。")
                PlanHint("参考价固定，只有实际成交记账才改变阶段。正向到上下限反转，反向到中线恢复正向。")
                PlanHint("每格只要还有股票就不能再次建议买入，全部卖完才释放；已到价且条件满足时才能记录策略单。")
                PlanHint("按完整一份及最接近金额的100股整数倍估算，允许越过成本边界；现金或可卖数量不足时等待。")
                PlanHint("只提供提示与记账，不自动在券商成交。金额、可卖股数及实际费用请与券商核对。")
                TextButton(onClick = onAdjustment) { Text("登记分红／转增／扣税") }
            }
        },
        confirmButton = { TextButton(onClick = onSettings) { Text("调整设置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun PlanOrderRow(order: TradePlanOrder, onTrade: (TradePlanOrder) -> Unit) {
    val side = sideLabel(order)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${directionLabel(order)} 至 ${order.triggerPrice?.let { "¥${formatPrice(it)}" } ?: "—"} · ${if (order.type == null) side else "$side ${order.qty} 股"}",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onTrade(order) }, enabled = canRecord(order)) { Text("记录$side") }
        }
        Text(order.block?.let(::blockMessage) ?: if (order.reached) "已到价，请核对实际成交后记账" else "等待价格到达",
            fontSize = 12.sp, color = if (canRecord(order)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        PlanHint(when (order.reason) {
            PlanReason.UP_PROFIT -> "上涨卖出盈利批次，按最低买价优先抵扣。"
            PlanReason.UP_REPLENISH -> "上涨反向跟随：突破最高剩余买价且目标格为空时买入。"
            PlanReason.DOWN_BUY -> "正向下跌买入，触及成本上限后才转为反向减仓。"
            PlanReason.DOWN_REDUCE -> "下跌反向减仓：最高买价优先，减到中线才恢复正向。"
            PlanReason.PULLBACK_BUY -> "上涨跟随途中回落到空格，可按规则买入；占用格继续等待。"
            PlanReason.WAIT -> "本格暂不产生可执行的策略记账。"
        })
        if (order.type != null) {
            PlanHint("按${order.price?.let { "¥${formatPrice(it)}" } ?: "当前价格"}估算；操作后 ${order.resultingQty} 股")
            order.match?.let { PlanHint("记账匹配：${it.label}") }
        }
        order.resultingCost?.let { PlanHint("预计剩余原始成本 ¥${money(it)} · 本次预计费用 ¥${money(order.estimatedFee ?: 0.0)}") }
        order.estimatedNetProfit?.let { PlanHint("本次预计已实现净盈亏 ${if (it >= 0) "+" else ""}${money(it)} 元") }
    }
}

@Composable
private fun PlanHint(text: String) = Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun TriggeredPlansPanel(entries: List<TriggeredPlanEntry>, onOpenStock: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlanHint("按显示现价筛选已到价且可执行的策略；未配置、待迁移或现价未知的股票不参与。")
        if (entries.isEmpty()) {
            Text("暂无已触发的买卖提醒", modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(entries, key = { it.stock.marketCode }) { entry ->
                Card(Modifier.fillMaxWidth().clickable { onOpenStock(entry.accountIndex) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.stock.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                PlanHint(entry.stock.marketCode)
                            }
                            Text("现价 ¥${formatPrice(entry.currentPrice)}", fontSize = 12.sp)
                        }
                        entry.orders.forEach { order ->
                            Text("${directionLabel(order)} ${order.triggerPrice?.let { "¥${formatPrice(it)}" } ?: "—"} · ${sideLabel(order)} ${order.qty}股",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (order.direction == PlanDirection.UP) UpColor else DownColor)
                        }
                        PlanHint("点击查看该股票")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TradePlanSettingsDialog(account: StockAccount, feeConfig: FeeConfig, onSave: (TradePlanConfig?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preset = remember(context, account.stock.marketCode) {
        runCatching {
            val raw = context.resources.openRawResource(R.raw.trade_plan_presets).bufferedReader().use { it.readText() }
            TradePlanPresetStore.find(TradePlanPresetStore.parse(raw), account.stock.marketCode)
        }.getOrNull()
    }
    val existing = account.tradePlan
    val referenceSuggestion = existing?.referencePrice?.takeIf { it.isFinite() && it > 0 }
        ?: account.currentPrice?.takeIf { it.isFinite() && it > 0 }
        ?: account.trades.lastOrNull { it.price.isFinite() && it.price > 0 }?.price
        ?: account.holdings.filter { it.remainingQty > 0 && it.price.isFinite() && it.price > 0 }.minOfOrNull { it.price }
    var lowerText by remember(account.stock.marketCode) { mutableStateOf(existing?.lowerCost?.inputOrBlank(true) ?: "") }
    var upperText by remember(account.stock.marketCode) { mutableStateOf(existing?.upperCost?.inputOrBlank() ?: "") }
    var amountText by remember(account.stock.marketCode) { mutableStateOf(existing?.orderAmount?.inputOrBlank() ?: "") }
    var stepText by remember(account.stock.marketCode) { mutableStateOf(existing?.stepPercent?.inputOrBlank() ?: "") }
    var referenceText by remember(account.stock.marketCode) { mutableStateOf(referenceSuggestion?.let(::inputNumber) ?: "") }
    var capitalText by remember(account.stock.marketCode) { mutableStateOf(existing?.capital?.inputOrBlank() ?: "") }
    val cashSuggestion = account.tradePlanState?.cash?.takeIf { it.isFinite() }
        ?: existing?.capital?.takeIf { it.isFinite() && it > 0 }?.let { (it - account.totalCostWithFee).coerceAtLeast(0.0) }
    var cashText by remember(account.stock.marketCode) { mutableStateOf(cashSuggestion?.let(::inputNumber) ?: "") }
    var cashEdited by remember(account.stock.marketCode) { mutableStateOf(false) }
    var presetFilled by remember(account.stock.marketCode) { mutableStateOf(false) }
    val lower = lowerText.finiteDouble(); val upper = upperText.finiteDouble()
    val amount = amountText.finiteDouble(); val step = stepText.finiteDouble()
    val reference = referenceText.finiteDouble(); val capital = capitalText.finiteDouble(); val cash = cashText.finiteDouble()
    val config = if (lower != null && upper != null && amount != null && step != null && reference != null && capital != null && cash != null &&
        lower >= 0 && upper > lower && amount > 0 && step > 0 && step < 100 && reference > 0 && capital > 0 && cash >= 0)
        TradePlanConfig(lowerCost = lower, upperCost = upper, orderAmount = amount, stepPercent = step,
            referencePrice = reference, capital = capital, initialCash = cash,
            startAfterTradeId = account.trades.maxOfOrNull { it.id } ?: 0L).takeIf { it.isValid } else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("仓位跟随 · ${account.stock.name}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing?.needsSetup == true) PlanHint("旧配置已保留，请确认新模型参数与可用现金后启用。")
                PlanHint("所有参数只属于此股票；推荐方案不会自动覆盖已保存的设置。")
                preset?.let { suggestion ->
                    TextButton(onClick = {
                        lowerText = inputNumber(suggestion.lowerCost); upperText = inputNumber(suggestion.upperCost)
                        amountText = inputNumber(suggestion.orderAmount); stepText = inputNumber(suggestion.stepPercent)
                        capitalText = inputNumber(suggestion.capital)
                        cashText = inputNumber((suggestion.capital - account.totalCostWithFee).coerceAtLeast(0.0))
                        cashEdited = false; presetFilled = true
                    }) { Text("填入${suggestion.name}推荐参数") }
                }
                if (presetFilled) PlanHint("已填入推荐方案，仍可自由编辑；确认保存后才生效。")
                PlanNumberField(lowerText, { lowerText = it }, "持仓成本下限（元）")
                PlanNumberField(upperText, { upperText = it }, "持仓成本上限（元）")
                if (lower != null && upper != null && lower >= 0 && upper > lower) PlanHint("中线自动计算：¥${money((lower + upper) / 2)}")
                PlanHint("按剩余批次原始买入成本计算，不随市价变化，不含手续费；上下限是切换阈值。")
                PlanNumberField(amountText, { amountText = it }, "每笔目标金额（元）")
                PlanNumberField(stepText, { stepText = it }, "百分比步长（%）")
                PlanHint("整笔按最接近金额的100股整数倍估算，最低100股；允许越过成本边界，不在边界缩量。")
                PlanNumberField(referenceText, { referenceText = it }, "本轮固定参考价（元）")
                PlanHint("网格由此价格固定建立，成交偏差不移动锚点。上下行共用格子，下降使用相邻比例的倒数。")
                PlanNumberField(capitalText, {
                    capitalText = it
                    if (!cashEdited) cashText = it.finiteDouble()?.takeIf { v -> v > 0 }
                        ?.let { v -> inputNumber((v - account.totalCostWithFee).coerceAtLeast(0.0)) } ?: ""
                }, "此股票策略资金总额（元）")
                PlanNumberField(cashText, { cashText = it; cashEdited = true }, "本轮初始可用现金（元）")
                if (account.tradePlanState?.cash?.let { it.isFinite() && it < 0 } == true)
                    PlanHint("当前策略现金为负，已保留实际记账余额；请核对券商余额后校准，不能以负现金重新启用。")
                PlanHint("当前持仓含剩余买入费约 ¥${money(account.totalCostWithFee)}。现金建议按资金总额减去该值估算，请以券商实际余额核对。")
                if (capital != null && capital < account.totalCostWithFee) PlanHint("现有持仓已超过所填资金总额，现金估算为0，请核对预算及实际可用现金。")
                if (lower != null && upper != null && (account.totalCost < lower || account.totalCost >= upper))
                    PlanHint("重新起算从正向开始，现有仓位超出边界时请核对参数和现金。")
                PlanHint("保存会保留真实持仓和交易记录，重新建立格子、恢复正向阶段，并按所填现金开始新一轮。")
                if (config == null) PlanHint("请填写有限数值：0≤下限＜上限，目标金额＞0，0＜步长＜100，参考价＞0，资金总额＞0，现金≥0。")
                if (existing != null) TextButton(onClick = { onSave(null) }) { Text("关闭此股票的策略") }
            }
        },
        confirmButton = { TextButton(onClick = { config?.let(onSave) }, enabled = config != null) { Text("确认参数并重新起算") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun TradePlanAdjustmentDialog(account: StockAccount, onSave: (PlanAdjustmentInput) -> Boolean, onDismiss: () -> Unit) {
    var cashText by remember { mutableStateOf("0") }
    var sharesText by remember { mutableStateOf("0") }
    var cashReferenceText by remember { mutableStateOf("0") }
    var bonusReferenceText by remember { mutableStateOf("0") }
    var bonusReferenceEdited by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var priceText by remember { mutableStateOf(account.currentPrice?.takeIf { it.isFinite() && it > 0 }?.let(::inputNumber) ?: "") }
    val cash = cashText.finiteDouble(); val shares = sharesText.toIntOrNull()
    val cashReference = cashReferenceText.finiteDouble(); val bonusReference = bonusReferenceText.finiteDouble(); val price = priceText.finiteDouble()
    val input = if (cash != null && shares != null && cashReference != null && bonusReference != null && price != null)
        PlanAdjustmentInput(netCash = cash, addedShares = shares, cashReference = cashReference,
            bonusReferencePercent = bonusReference, newPrice = price) else null
    val error = input?.let { validateTradePlanAdjustment(account, it) }
        ?: if (input == null) "请完整填写有限数值及实际新增整数股数。" else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("登记分红／转增／扣税") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanHint("仅手动记录券商实际结果，不自动获取公告。请核对到账现金、新增股份及除权后现价。")
                PlanNumberField(cashText, { cashText = it }, "实际净现金变化（元，扣款填负数）", signed = true)
                PlanHint("到账填正数，实际红利税或其他扣款填负数；不提前扣尚未发生的潜在税。")
                PlanNumberField(sharesText, {
                    sharesText = it
                    if (!bonusReferenceEdited) bonusReferenceText = it.toIntOrNull()?.let { v ->
                        if (account.totalQty > 0) inputNumber(v.toDouble() / account.totalQty * 100) else "0"
                    } ?: ""
                }, "券商实际新增股数", decimal = false)
                PlanHint("当前 ${account.totalQty} 股；新增股份允许产生零股，原始总成本和未摊买入费保持不变。")
                PlanNumberField(cashReferenceText, { cashReferenceText = it }, "公告每股除息参考（元）")
                PlanNumberField(bonusReferenceText, { bonusReferenceText = it; bonusReferenceEdited = true }, "公告价格参考转增比例（%）")
                PlanHint("比例可按新增股数估算，但公告参考参数可能因回购专户等原因不同于实际到账比例，请核对修正。仅登记扣款时参考参数填0。")
                PlanNumberField(priceText, { priceText = it }, "确认实际除权后现价（元）")
                PlanHint("登记后保留阶段，按公告参考参数调整固定格子，清除旧到价提示，以此现价重新定位并等待下一格。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (!submitting && input != null && error == null) {
                submitting = true
                if (!onSave(input)) submitting = false
            }
        }, enabled = !submitting && input != null && error == null) { Text("核对后登记") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PlanNumberField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean = true, signed: Boolean = false) {
    OutlinedTextField(value, onValueChange = { text ->
        val normalized = text.trim().replace('−', '-')
        val sign = if (signed) "-?" else ""
        val expression = if (decimal) "$sign[0-9]*([.][0-9]*)?" else "$sign[0-9]*"
        if (normalized.matches(Regex(expression))) onChange(normalized)
    }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = when {
            signed -> KeyboardType.Text
            decimal -> KeyboardType.Decimal
            else -> KeyboardType.Number
        }))
}

private fun phaseLabel(phase: PlanPhase): String = when (phase) {
    PlanPhase.NORMAL -> "正向"
    PlanPhase.DOWN_REDUCTION -> "下跌反向减仓"
    PlanPhase.UP_FOLLOW -> "上涨反向跟随"
}
private fun directionLabel(order: TradePlanOrder): String = if (order.direction == PlanDirection.UP) "上涨" else "下跌"
private fun sideLabel(order: TradePlanOrder): String = when (order.type) { TradeType.BUY -> "买入"; TradeType.SELL -> "卖出"; null -> "等待" }
private fun canRecord(order: TradePlanOrder): Boolean = order.reached && order.block == null && order.type != null
private fun blockShortLabel(block: PlanBlock): String = when (block) {
    PlanBlock.WAIT -> "等待"
    PlanBlock.INVALID_PRICE, PlanBlock.INVALID_HOLDINGS, PlanBlock.INVALID_STATE -> "需校准"
    PlanBlock.OCCUPIED_GRID -> "已占格"
    PlanBlock.INSUFFICIENT_CASH -> "现金不足"
    PlanBlock.INSUFFICIENT_PROFITABLE_SHARES -> "盈利不足"
    PlanBlock.INSUFFICIENT_SETTLED_SHARES -> "可卖不足"
    PlanBlock.NO_BREAKOUT -> "待突破"
}
private fun blockMessage(block: PlanBlock): String = when (block) {
    PlanBlock.WAIT -> "当前没有完整一份可执行操作，继续等待"
    PlanBlock.INVALID_PRICE -> "当前价格无法形成有效网格，请核对参考价和步长"
    PlanBlock.INVALID_HOLDINGS -> "持仓数量或买入价格无效，请先核对记账数据"
    PlanBlock.INVALID_STATE -> "策略状态需要校准，请确认参数和可用现金后重新起算"
    PlanBlock.OCCUPIED_GRID -> "该格仍有股票，全部卖完前不重复买入"
    PlanBlock.INSUFFICIENT_CASH -> "可用现金不足支付完整一份及买入费用，等待而不借款"
    PlanBlock.INSUFFICIENT_PROFITABLE_SHARES -> "盈利批次数量不足完整一份，不混入亏损批次卖出"
    PlanBlock.INSUFFICIENT_SETTLED_SHARES -> "券商可卖数量不足完整一份，请核对T+1限制"
    PlanBlock.NO_BREAKOUT -> "尚未突破最高剩余买价，上涨跟随暂不买入"
}
private fun money(value: Double): String = if (value.isFinite()) formatMoney(value) else "—"
private fun inputNumber(value: Double): String = if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else ""
private fun Double.inputOrBlank(allowZero: Boolean = false): String = if (isFinite() && (this > 0 || allowZero && this == 0.0)) inputNumber(this) else ""
private fun String.finiteDouble(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() }
