package com.example.stocktracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stocktracker.QuoteResult
import com.example.stocktracker.PrefsStorage
import com.example.stocktracker.StockAccount
import com.example.stocktracker.StockViewModel
import com.example.stocktracker.TradeType
import com.example.stocktracker.formatMoney
import com.example.stocktracker.formatPrice
import com.example.stocktracker.formatTime
import com.example.stocktracker.isValidCodeInput
import com.example.stocktracker.ui.theme.DownColor
import com.example.stocktracker.ui.theme.StockTrackerTheme
import com.example.stocktracker.ui.theme.UpColor
import com.example.stocktracker.ui.theme.pnlColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockApp() {
    val context = LocalContext.current
    val vm: StockViewModel = viewModel {
        StockViewModel(PrefsStorage(context))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val acc = state.selected
    val snackbar = remember { SnackbarHostState() }
    var showClearDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showTradeDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showPriceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            val job = launch { snackbar.showSnackbar(msg, duration = SnackbarDuration.Short) }
            delay(2000) // 2 秒后自动关闭
            snackbar.currentSnackbarData?.dismiss()
            job.join()
            vm.clearMessage()
        }
    }

    StockTrackerTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("炒股记账本", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item {
                    if (state.accounts.isEmpty()) {
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("添加股票（输入代码自动查询名称）")
                        }
                    } else {
                        StockBar(
                            accounts = state.accounts,
                            selectedIndex = state.selectedIndex,
                            onSelect = vm::selectStock,
                            onAddClick = { showAddDialog = true }
                        )
                    }
                }
                if (acc == null) {
                    // 无选中股票时只有添加按钮，无需其他提示
                } else {
                    item {
                        SummaryCard(
                            acc,
                            onEditPrice = { showPriceDialog = true },
                            onRefresh = vm::refreshPrice
                        )
                    }
                    item { HoldingsCard(acc) }
                    item { HistoryPreviewCard(acc.trades) { showHistoryDialog = true } }
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showTradeDialog = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("交易", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { showClearDialog = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("清空") }
                        }
                    }
                }
            }
        }
    }

    if (showTradeDialog && acc != null) {
        TradeDialog(
            sellableQty = acc.sellableQty,
            onBuy = vm::buy,
            onSell = vm::sell,
            onDismiss = { showTradeDialog = false }
        )
    }
    if (showHistoryDialog && acc != null) {
        HistoryDialog(acc.trades) { showHistoryDialog = false }
    }
    if (showPriceDialog) {
        PriceDialog(
            current = acc?.currentPrice,
            onSet = vm::setCurrentPrice,
            onRefresh = vm::refreshPrice,
            onDismiss = { showPriceDialog = false }
        )
    }

    if (showClearDialog) {
        ClearDialog(
            onClearSelected = { vm.clearSelected(); showClearDialog = false },
            onClearAll = { vm.clearAll(); showClearDialog = false },
            onDismiss = { showClearDialog = false }
        )
    }

    if (showAddDialog) {
        AddStockDialog(
            searchResult = state.searchResult,
            isSearching = state.isSearching,
            searchedCode = state.searchedCode,
            searchHint = state.searchHint,
            onSearch = vm::searchStock,
            onClearSearch = vm::clearSearch,
            onAdd = { vm.addStock(it); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ---------------- 股票切换标签栏 ----------------
@Composable
private fun StockBar(
    accounts: List<StockAccount>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAddClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        accounts.forEachIndexed { i, acc ->
            FilterChip(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                label = { Text(acc.stock.name) }
            )
        }
        FilterChip(selected = false, onClick = onAddClick, label = { Text("＋ 添加") })
    }
}

// ---------------- 添加股票弹窗 ----------------
@Composable
private fun AddStockDialog(
    searchResult: QuoteResult?,
    isSearching: Boolean,
    searchedCode: String?,
    searchHint: String?,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onAdd: (QuoteResult) -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150) // 等弹窗动画完成再弹键盘
        focusRequester.requestFocus()
    }

    LaunchedEffect(code) {
        val c = code.trim()
        if (isValidCodeInput(c)) {
            delay(500) // 防抖，等用户输入完再查询
            onSearch(c)
        } else {
            onClearSearch()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加股票") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { t ->
                        code = t.filter { it.isLetterOrDigit() }.uppercase()
                    },
                    label = { Text("股票代码（如 000001 / hk00700 / usAAPL）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Spacer(Modifier.height(8.dp))
                when {
                    isSearching -> HintText("查询中…")
                    searchResult != null -> Column {
                        Text("${searchResult.name} (${searchResult.market}${searchResult.code})", fontWeight = FontWeight.Bold)
                        Text(
                            "现价 ${searchResult.price?.let { "¥${formatPrice(it)}" } ?: "未知"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    searchedCode == code.trim() && searchHint != null -> HintText(searchHint)
                    isValidCodeInput(code.trim()) -> HintText("输入完成，自动查询名称…")
                    else -> HintText("支持 A 股 6 位代码、港股 hk+代码、美股 us+代码，自动查询名称")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { searchResult?.let { onAdd(it); code = "" } },
                enabled = searchResult != null
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------- 概览卡片 ----------------
@Composable
private fun SummaryCard(acc: StockAccount, onEditPrice: () -> Unit, onRefresh: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "持仓概览 · ${acc.stock.displayName}",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新现价",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoCol("总持仓", "${acc.totalQty} 股")
                InfoCol("可卖", "${acc.sellableQty} 股")
                InfoCol("持仓均价", "¥${formatPrice(acc.avgPrice)}")
                InfoCol("总成本", "¥${formatMoney(acc.totalCost)}")
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.clickable(onClick = onEditPrice),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("现价", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                acc.currentPrice?.let { "¥${formatPrice(it)}" } ?: "未设置",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "设置现价",
                                modifier = Modifier.size(12.dp).padding(start = 2.dp),
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                InfoCol("市值", if (acc.currentPrice != null) "¥${formatMoney(acc.marketValue)}" else "—")
                InfoCol(
                    "浮动盈亏",
                    if (acc.currentPrice != null) "${if (acc.totalPnl >= 0) "+" else ""}${formatMoney(acc.totalPnl)}" else "—",
                    valueColor = if (acc.currentPrice != null) {
                        if (acc.totalPnl > 0.0001) Color(0xFFFFCDD2)
                        else if (acc.totalPnl < -0.0001) Color(0xFFC8E6C9)
                        else Color.White
                    } else Color.White
                )
            }
        }
    }
}

@Composable
private fun InfoCol(label: String, value: String, valueColor: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ---------------- 设置现价弹窗 ----------------
@Composable
private fun PriceDialog(
    current: Double?,
    onSet: (Double?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置现价") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("现价（计算浮动盈亏）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                HintText("当前现价：${current?.let { "¥${formatPrice(it)}" } ?: "未设置"}")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onRefresh(); onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新")
                    }
                    OutlinedButton(
                        onClick = { onSet(null); onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) { Text("清除") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                text.toDoubleOrNull()?.let { onSet(it) }
                onDismiss()
            }) { Text("更新") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------- 交易弹窗（买入/卖出） ----------------
@Composable
private fun TradeDialog(
    sellableQty: Int,
    onBuy: (Double, Int) -> Unit,
    onSell: (Double, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var isBuy by remember { mutableStateOf(true) }
    var price by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    val btnColor = if (isBuy) UpColor else DownColor

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("交易") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = isBuy,
                        onClick = { isBuy = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = if (isBuy) UpColor else MaterialTheme.colorScheme.secondaryContainer,
                            activeContentColor = if (isBuy) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) { Text("买入") }
                    SegmentedButton(
                        selected = !isBuy,
                        onClick = { isBuy = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = if (!isBuy) DownColor else MaterialTheme.colorScheme.secondaryContainer,
                            activeContentColor = if (!isBuy) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) { Text("卖出") }
                }
                if (!isBuy) {
                    Spacer(Modifier.height(4.dp))
                    HintText(
                        if (sellableQty > 0) "可卖 $sellableQty 股（当日买入的部分 T+1 冻结）"
                        else "今日买入的股票需 T+1，次日才能卖出"
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { t -> price = t.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("单价(元)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = qty,
                        onValueChange = { t -> qty = t.filter { c -> c.isDigit() } },
                        label = { Text("数量(股)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val p = price.toDoubleOrNull(); val q = qty.toIntOrNull()
                        if (p != null && q != null) {
                            if (isBuy) onBuy(p, q) else onSell(p, q)
                            price = ""; qty = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isBuy) "买入" else "卖出", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        confirmButton = {}
    )
}

// ---------------- 持仓明细卡片 ----------------
@Composable
private fun HoldingsCard(acc: StockAccount) {
    val lots = acc.lotPnls
    val sellableIds = acc.sellableHoldings.map { it.id }.toSet()
    SectionCard(title = "持仓明细") {
        if (lots.isEmpty()) {
            EmptyText("暂无持仓")
        } else {
            lots.sortedByDescending { it.lot.price }.forEach { lp ->
                LotRow(lp, acc.currentPrice, lp.lot.id !in sellableIds)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun LotRow(lp: com.example.stocktracker.LotPnl, currentPrice: Double?, frozen: Boolean) {
    val lot = lp.lot
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("¥${formatPrice(lot.price)} 买入", fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("剩余 ${lot.remainingQty} 股", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.width(6.dp))
                if (frozen) {
                    Box(
                        Modifier
                            .background(Color(0xFFF9A825).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "今日买入 · T+1冻结",
                            color = Color(0xFFF9A825),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text("可卖", fontSize = 11.sp, color = UpColor)
                }
            }
        }
        val pctText = if (currentPrice != null) String.format("%.2f%%", lp.pnlPercent) else "—"
        Text(
            if (currentPrice != null) "${if (lp.pnl >= 0) "+" else ""}${formatMoney(lp.pnl)} 元 ($pctText)" else "—",
            color = pnlColor(lp.pnl), fontWeight = FontWeight.Bold
        )
    }
}

// ---------------- 交易记录预览卡片（最多 3 条，更多进弹窗） ----------------
@Composable
private fun HistoryPreviewCard(trades: List<com.example.stocktracker.TradeRecord>, onShowAll: () -> Unit) {
    SectionCard(title = "交易记录") {
        if (trades.isEmpty()) {
            EmptyText("暂无交易")
        } else {
            trades.reversed().take(3).forEach { t ->
                TradeRow(t)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
            if (trades.size > 3) {
                TextButton(
                    onClick = onShowAll,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("查看全部 ${trades.size} 条") }
            }
        }
    }
}

// ---------------- 交易记录弹窗（全部） ----------------
@Composable
private fun HistoryDialog(trades: List<com.example.stocktracker.TradeRecord>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("交易记录") },
        text = {
            if (trades.isEmpty()) {
                EmptyText("暂无交易")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(trades.reversed()) { t ->
                        TradeRow(t)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun TradeRow(t: com.example.stocktracker.TradeRecord) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(
                            if (t.type == TradeType.BUY) UpColor.copy(alpha = 0.15f) else DownColor.copy(alpha = 0.15f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (t.type == TradeType.BUY) "买入" else "卖出",
                        color = if (t.type == TradeType.BUY) UpColor else DownColor,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("¥${formatPrice(t.price)} × ${t.qty}股", fontWeight = FontWeight.Medium)
            }
            Text(formatTime(t.time), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        if (t.type == TradeType.SELL) {
            Text(
                "${if (t.profit >= 0) "+" else ""}${formatMoney(t.profit)}",
                color = pnlColor(t.profit), fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---------------- 一键清空 ----------------
@Composable
private fun ClearDialog(onClearSelected: () -> Unit, onClearAll: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一键清空") },
        text = { Text("请选择清空范围：") },
        confirmButton = {
            TextButton(onClick = onClearAll) { Text("清空全部股票") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClearSelected) { Text("仅清空当前") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ---------------- 其他 ----------------
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}

@Composable
private fun EmptyText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
}
