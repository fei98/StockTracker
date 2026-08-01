package com.example.stocktracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stocktracker.StockViewModel
import com.example.stocktracker.TradeType
import com.example.stocktracker.formatMoney
import com.example.stocktracker.formatPrice
import com.example.stocktracker.formatTime
import com.example.stocktracker.ui.theme.DownColor
import com.example.stocktracker.ui.theme.StockTrackerTheme
import com.example.stocktracker.ui.theme.UpColor
import com.example.stocktracker.ui.theme.pnlColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockApp() {
    val vm: StockViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                item { SummaryCard(state.totalQty, state.avgPrice, state.totalCost, state.currentPrice, state.marketValue, state.totalPnl) }
                item { CurrentPriceCard(state.currentPrice, vm::setCurrentPrice) }
                item { TradeCard("买入", isBuy = true, vm::buy) }
                item { TradeCard("卖出", isBuy = false, vm::sell) }
                item { HoldingsCard(state.lotPnls, state.currentPrice) }
                item { HistoryCard(state.trades) }
                item { ClearButton(vm::clearAll) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ---------------- 概览卡片 ----------------
@Composable
private fun SummaryCard(
    totalQty: Int,
    avgPrice: Double,
    totalCost: Double,
    currentPrice: Double?,
    marketValue: Double,
    totalPnl: Double
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("持仓概览", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoCol("总持仓", "$totalQty 股")
                InfoCol("持仓均价", "¥${formatPrice(avgPrice)}")
                InfoCol("总成本", "¥${formatMoney(totalCost)}")
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoCol("现价", currentPrice?.let { "¥${formatPrice(it)}" } ?: "未设置")
                InfoCol("市值", if (currentPrice != null) "¥${formatMoney(marketValue)}" else "—")
                InfoCol(
                    "浮动盈亏",
                    if (currentPrice != null) "${if (totalPnl >= 0) "+" else ""}${formatMoney(totalPnl)}" else "—",
                    valueColor = if (currentPrice != null) {
                        if (totalPnl > 0.0001) Color(0xFFFFCDD2)
                        else if (totalPnl < -0.0001) Color(0xFFC8E6C9)
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

// ---------------- 现价卡片 ----------------
@Composable
private fun CurrentPriceCard(current: Double?, onSet: (Double?) -> Unit) {
    var text by remember { mutableStateOf("") }
    SectionCard(title = "现价（计算浮动盈亏）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { t -> text = t.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("现价") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { text.toDoubleOrNull()?.let { onSet(it) } }) { Text("更新") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { text = ""; onSet(null) }) { Text("清除") }
        }
        if (current != null) {
            Spacer(Modifier.height(4.dp))
            Text("当前现价：¥${formatPrice(current)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

// ---------------- 买入/卖出卡片 ----------------
@Composable
private fun TradeCard(title: String, isBuy: Boolean, onAction: (Double, Int) -> Unit) {
    var price by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    val btnColor = if (isBuy) UpColor else DownColor

    SectionCard(title = title) {
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
                if (p != null && q != null) { onAction(p, q); price = ""; qty = "" }
            },
            colors = ButtonDefaults.buttonColors(containerColor = btnColor),
            modifier = Modifier.fillMaxWidth()
        ) { Text(title, fontWeight = FontWeight.Bold) }
    }
}

// ---------------- 持仓明细卡片 ----------------
@Composable
private fun HoldingsCard(lots: List<com.example.stocktracker.LotPnl>, currentPrice: Double?) {
    SectionCard(title = "持仓明细") {
        if (lots.isEmpty()) {
            EmptyText("暂无持仓")
        } else {
            lots.sortedByDescending { it.lot.price }.forEach { lp ->
                val lot = lp.lot
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("¥${formatPrice(lot.price)} 买入", fontWeight = FontWeight.Medium)
                        Text("剩余 ${lot.remainingQty} 股", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    val pctText = if (currentPrice != null) String.format("%.2f%%", lp.pnlPercent) else "—"
                    Text(
                        if (currentPrice != null) "${if (lp.pnl >= 0) "+" else ""}${formatMoney(lp.pnl)} 元" else "—",
                        color = pnlColor(lp.pnl), fontWeight = FontWeight.Bold
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
        }
    }
}

// ---------------- 交易记录卡片 ----------------
@Composable
private fun HistoryCard(trades: List<com.example.stocktracker.TradeRecord>) {
    SectionCard(title = "交易记录") {
        if (trades.isEmpty()) {
            EmptyText("暂无交易")
        } else {
            trades.reversed().forEach { t ->
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
        }
    }
}

// ---------------- 一键清空 ----------------
@Composable
private fun ClearButton(onClear: () -> Unit) {
    Button(
        onClick = onClear,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.DeleteSweep, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("一键清空所有数据", color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

// ---------------- 通用组件 ----------------
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
private fun EmptyText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
}
