package com.example.stocktracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stocktracker.FeeConfig
import com.example.stocktracker.FeeConfigStore
import com.example.stocktracker.MinutePoint
import com.example.stocktracker.OverviewEntry
import com.example.stocktracker.OverviewTab
import com.example.stocktracker.PredictionEngine
import com.example.stocktracker.PredictionOutcome
import com.example.stocktracker.PredictionViewModel
import com.example.stocktracker.PrefsSnapshotStore
import com.example.stocktracker.QuoteResult
import com.example.stocktracker.PrefsStorage
import com.example.stocktracker.StockAccount
import com.example.stocktracker.StockApi
import com.example.stocktracker.StockState
import com.example.stocktracker.StockViewModel
import com.example.stocktracker.TargetType
import com.example.stocktracker.TencentMarketDataApi
import com.example.stocktracker.TradeType
import com.example.stocktracker.overviewEntries
import com.example.stocktracker.formatMoney
import com.example.stocktracker.formatPrice
import com.example.stocktracker.formatTime
import com.example.stocktracker.isSameDay
import com.example.stocktracker.isValidCodeInput
import com.example.stocktracker.minuteIndexOf
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
        StockViewModel(PrefsStorage(context), feeStore = FeeConfigStore(context))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val acc = state.selected
    val predStore = remember { PrefsSnapshotStore(context) }
    val predVm: PredictionViewModel = viewModel {
        PredictionViewModel(PredictionEngine(TencentMarketDataApi(StockApi()), predStore), predStore)
    }
    val predUi by predVm.ui.collectAsStateWithLifecycle()
    val predStock = acc?.stock

    // 切换选中股票时，预测卡刷新为对应股票的数据
    LaunchedEffect(predStock?.marketCode) {
        predStock?.let { predVm.refresh(it) }
    }
    val snackbar = remember { SnackbarHostState() }
    var showClearDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showTradeDialog by remember { mutableStateOf(false) }
    var showPriceDialog by remember { mutableStateOf(false) }
    var showPredDetail by remember { mutableStateOf(false) }
    var showSectorPicker by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var showOverview by remember { mutableStateOf(false) }
    var showFeeSettings by remember { mutableStateOf(false) }
    var showIntradayDialog by remember { mutableStateOf(false) }
    val intraday by vm.intraday.collectAsStateWithLifecycle()
    val intradayLoading by vm.intradayLoading.collectAsStateWithLifecycle()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                    ),
                    actions = {
                        IconButton(onClick = { showOverview = true }) {
                            Icon(
                                Icons.Default.PieChart,
                                contentDescription = "账户总览",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = { showFeeSettings = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "费率设置",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
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
                if (state.accounts.isEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("添加股票（输入代码自动查询名称）")
                        }
                    }
                } else {
                    item { AccountPnlBar(state, onRefreshAll = vm::refreshAll) }
                    item {
                        StockBar(
                            accounts = state.accounts,
                            selectedIndex = state.selectedIndex,
                            onSelect = vm::selectStock,
                            onAddClick = { showAddDialog = true },
                            onDelete = { i ->
                                val n = state.accounts.getOrNull(i)?.stock?.name ?: return@StockBar
                                deleteTarget = i to n
                            }
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
                            onRefresh = vm::refreshPrice,
                            onIntraday = {
                                vm.loadIntraday()
                                showIntradayDialog = true
                            }
                        )
                    }
                    item {
                        if (predStock != null) {
                            PredictionCard(
                                ui = predUi,
                                onPredict = {
                                    requestNotifPermissionIfNeeded()
                                    predVm.predict(predStock, hasPosition = acc.totalQty > 0)
                                },
                                onDetail = { showPredDetail = true }
                            )
                        }
                    }
                    item { HoldingsCard(acc) }
                    item { HistoryPreviewCard(acc.trades) }
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
    if (showPriceDialog) {
        PriceDialog(
            current = acc?.currentPrice,
            onSet = vm::setCurrentPrice,
            onRefresh = vm::refreshPrice,
            onDismiss = { showPriceDialog = false }
        )
    }

    if (showPredDetail) {
        PredictionDetailDialog(
            ui = predUi,
            onDismiss = { showPredDetail = false },
            onConfigSector = { showPredDetail = false; showSectorPicker = true }
        )
    }

    if (showSectorPicker && predStock != null) {
        SectorPickerDialog(
            currentIndustry = predUi.stock?.let { predStore.getUserSector(it.marketCode) },
            onSelect = { ind ->
                predVm.setSector(predStock, ind)
                showSectorPicker = false
            },
            onDismiss = { showSectorPicker = false }
        )
    }

    deleteTarget?.let { (idx, name) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除股票") },
            text = { Text("删除「$name」？其持仓、交易记录与预测数据将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeStock(idx)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    if (showOverview) {
        OverviewDialog(
            accounts = state.accounts,
            predUi = predUi,
            onPredictAll = {
                requestNotifPermissionIfNeeded()
                predVm.predictAll(state.accounts)
            },
            onDismiss = { showOverview = false }
        )
    }

    if (showFeeSettings) {
        FeeSettingsDialog(
            current = vm.feeConfig,
            onSave = { vm.updateFeeConfig(it); showFeeSettings = false },
            onDismiss = { showFeeSettings = false }
        )
    }

    if (showIntradayDialog && acc != null) {
        val now = System.currentTimeMillis()
        // 今日买卖点（用于分时图标记）
        val todayBuys = acc.trades
            .filter { it.type == TradeType.BUY && isSameDay(it.time, now) }
            .map { minuteIndexOf(it.time) to it.price }
        val todaySells = acc.trades
            .filter { it.type == TradeType.SELL && isSameDay(it.time, now) }
            .map { minuteIndexOf(it.time) to it.price }
        IntradayDialog(
            points = intraday,
            loading = intradayLoading,
            prevClose = acc.prevClose,
            stockName = acc.stock.name,
            buys = todayBuys,
            sells = todaySells,
            onRefresh = { vm.loadIntraday(force = true) },
            onDismiss = { showIntradayDialog = false }
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

// ---------------- 股票切换标签栏（chip 上带删除按钮） ----------------
@Composable
private fun StockBar(
    accounts: List<StockAccount>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAddClick: () -> Unit,
    onDelete: (Int) -> Unit
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
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(acc.stock.name)
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除 ${acc.stock.name}",
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onDelete(i) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

// ---------------- 竞价预测卡（紧凑：仅核心结论，详情进弹窗） ----------------
@Composable
private fun PredictionCard(
    ui: PredictionViewModel.UiState,
    onPredict: () -> Unit,
    onDetail: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val stock = ui.stock
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "竞价预测 · ${stock?.name ?: ""}",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (stock != null && !PredictionEngine.isPredictable(stock)) {
                    HintText("无集合竞价")
                } else if (ui.running) {
                    HintText("预测中…")
                } else {
                    Button(
                        onClick = onPredict,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
                    ) { Text("预测", fontSize = 12.sp) }
                }
            }
            val result = ui.result
            when {
                stock != null && !PredictionEngine.isPredictable(stock) -> {
                    HintText("港股/美股无 A 股式集合竞价，不支持竞价预测")
                }
                ui.inObservationPhase -> {
                    val obs = ui.observation
                    if (obs?.intentMovePct != null && obs.intentVolSurge != null) {
                        HintText("观察区：量价变化 ${signedPct(obs.intentMovePct)} · 量能×${String.format("%.1f", 1 + obs.intentVolSurge)}，9:20 后可预测")
                    } else {
                        HintText("观察区（9:15–9:20 可撤单），9:20 后给出预测")
                    }
                }
                ui.error != null -> HintText(ui.error)
                result != null -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${result.conclusion.label} · 评分 ${signedPct(result.score)} · 置信 ${result.confidence}",
                            color = when (result.conclusion) {
                                PredictionOutcome.UP -> UpColor
                                PredictionOutcome.DOWN -> DownColor
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDetail) { Text("详情", fontSize = 12.sp) }
                    }
                }
                else -> HintText("9:20 后点击预测")
            }
        }
    }
}

@Composable
private fun signedPct(v: Double): String =
    String.format(if (v >= 0) "+%.1f%%" else "%.1f%%", v)

// ---------------- 竞价预测详情弹窗 ----------------
@Composable
private fun PredictionDetailDialog(
    ui: PredictionViewModel.UiState,
    onDismiss: () -> Unit,
    onConfigSector: () -> Unit
) {
    val result = ui.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("竞价预测详情") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (result == null) {
                    item { EmptyText(ui.error ?: "暂无预测结果") }
                } else {
                    item { DetailRow("结论", "${result.conclusion.label}（评分 ${signedPct(result.score)}）") }
                    item { DetailRow("置信", result.confidence) }
                    item { DetailRow("分档阈值", String.format("%.1f", result.weights.threshold)) }
                    item { DetailRow("封顶", result.capApplied?.let { "已封顶 ±$it" } ?: "未触发") }
                    item { HorizontalDivider() }
                    item { DetailRow("159915 高开", result.factors.targetGapPct?.let { signedPct(it) } ?: "—") }
                    item { DetailRow("创业板指", result.factors.indexGapPct?.let { signedPct(it) } ?: "—") }
                    item { DetailRow("权重股均值", result.factors.sectorAvgGap?.let { signedPct(it) } ?: "—") }
                    item { DetailRow("板块广度", result.factors.sectorBreadth?.let { String.format("%.2f", it) } ?: "—") }
                    item { DetailRow("隔夜美股", result.factors.externalAvg?.let { signedPct(it) } ?: "—") }
                    item { DetailRow("竞价末端上移", result.factors.endMovePct?.let { signedPct(it) } ?: "无基准快照") }
                    item { DetailRow("放量 z-score", result.factors.volZ?.let { String.format("%.2f", it) } ?: "数据积累中") }
                    item { DetailRow("5日动量", result.factors.momentum5dPct?.let { signedPct(it) } ?: "数据积累中") }
                    item {
                        DetailRow(
                            "连阳/连阴",
                            result.factors.upStreak?.let { if (it > 0) "连涨 $it 天" else "连跌 ${-it} 天" } ?: "—"
                        )
                    }
                    item { DetailRow("昨收偏差(20日线)", result.factors.trendDevPct?.let { signedPct(it) } ?: "—") }
                    if (result.insufficient.isNotEmpty()) {
                        item { Text(result.insufficient.joinToString("；"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                    }
                    item { HorizontalDivider() }
                    item { DetailRow("板块联动", ui.sectorDetail) }
                    item {
                        TextButton(
                            onClick = onConfigSector,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("配置行业联动名单", fontSize = 12.sp) }
                    }
                    item { HorizontalDivider() }
                    item { DetailRow("建议", result.suggestion) }
                    item { Text(result.disclaimer, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) }
                    item { HorizontalDivider() }
                    item { Text("前推回测命中率（预测方向正确率，不含平盘）", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    TargetType.entries.forEach { t ->
                        val s = ui.stats[t]
                        if (s != null) {
                            item {
                                DetailRow(
                                    t.label,
                                    if (s.directionalTotal == 0) "数据积累中"
                                    else String.format("%.0f%%（%d/%d）", s.hitRate * 100, s.directionalHit, s.directionalTotal)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------- 行业联动配置弹窗 ----------------
@Composable
private fun SectorPickerDialog(
    currentIndustry: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val industries = TencentMarketDataApi.INDUSTRY_SECTOR_LISTS.keys.toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置行业联动名单") },
        text = {
            Column {
                HintText("自动推荐会在积累 ≥10 天行情后生效；选择行业可立即使用内置龙头名单：")
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(industries) { ind ->
                        TextButton(
                            onClick = { onSelect(ind) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                ind + if (ind == currentIndustry) "（当前）" else "",
                                fontSize = 14.sp,
                                color = if (ind == currentIndustry) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(null) }) { Text("恢复自动推荐") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------- 账户总盈亏条（点击一键刷新全部行情） ----------------
@Composable
private fun AccountPnlBar(s: StockState, onRefreshAll: () -> Unit) {
    val color = pnlColor(s.totalPnl)
    val floatingText = if (s.hasAnyPrice) signedMoney(s.floatingPnl) else "—"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onRefreshAll)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("账户总盈亏", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(signedMoney(s.totalPnl), color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "浮动 ${floatingText} · 已实现 ${signedMoney(s.realizedPnl)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Refresh,
                contentDescription = "一键刷新全部行情",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun signedMoney(v: Double): String = if (v >= 0) "+${formatMoney(v)}" else formatMoney(v)

// ---------------- 概览卡片 ----------------
@Composable
private fun SummaryCard(acc: StockAccount, onEditPrice: () -> Unit, onRefresh: () -> Unit, onIntraday: () -> Unit) {
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
                IconButton(onClick = onIntraday) {
                    Icon(
                        Icons.Default.ShowChart,
                        contentDescription = "今日分时",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
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
                            val chg = acc.dayChangePct
                            if (chg != null) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    (if (chg >= 0) "+" else "") + String.format("%.2f%%", chg),
                                    fontSize = 11.sp,
                                    color = if (chg > 0.0001) Color(0xFFFFCDD2)
                                    else if (chg < -0.0001) Color(0xFFC8E6C9)
                                    else Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                InfoCol("市值", if (acc.currentPrice != null) "¥${formatMoney(acc.marketValue)}" else "—")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("浮盈·含费", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    val pnlColorValue = if (acc.currentPrice != null) {
                        if (acc.totalPnl > 0.0001) Color(0xFFFFCDD2)
                        else if (acc.totalPnl < -0.0001) Color(0xFFC8E6C9)
                        else Color.White
                    } else Color.White
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (acc.currentPrice != null) "${if (acc.totalPnl >= 0) "+" else ""}${formatMoney(acc.totalPnl)}" else "—",
                            color = pnlColorValue, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        if (acc.currentPrice != null && acc.totalPnlPercent != 0.0) {
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "${if (acc.totalPnlPercent > 0) "+" else ""}${String.format("%.2f%%", acc.totalPnlPercent)}",
                                color = pnlColorValue, fontSize = 11.sp
                            )
                        }
                    }
                }
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

// ---------------- 交易记录卡片（默认 3 条，点击"查看全部"原地展开） ----------------
@Composable
private fun HistoryPreviewCard(trades: List<com.example.stocktracker.TradeRecord>) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "交易记录") {
        if (trades.isEmpty()) {
            EmptyText("暂无交易")
        } else {
            val shown = if (expanded) trades.reversed() else trades.reversed().take(3)
            shown.forEach { t ->
                TradeRow(t)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
            if (trades.size > 3) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (expanded) "收起" else "查看全部 ${trades.size} 条") }
            }
        }
    }
}

@Composable
private fun TradeRow(t: com.example.stocktracker.TradeRecord) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
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
            Text(
                "${formatTime(t.time)} · 成本 ¥${formatMoney(t.cost)}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (t.type == TradeType.SELL) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (t.netProfit >= 0) "+" else ""}${formatMoney(t.netProfit)}",
                    color = pnlColor(t.netProfit), fontWeight = FontWeight.Bold
                )
                if (t.fee > 0) {
                    Text("手续费 ¥${formatMoney(t.fee)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        } else if (t.fee > 0) {
            Text(
                "手续费 ¥${formatMoney(t.fee)}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
            Row {
                TextButton(onClick = onClearSelected) { Text("仅清空当前") }
                TextButton(onClick = onClearAll) { Text("清空全部股票") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ---------------- 今日分时图弹窗 ----------------
@Composable
private fun IntradayDialog(
    points: List<MinutePoint>?,
    loading: Boolean,
    prevClose: Double?,
    stockName: String,
    buys: List<Pair<Int, Double>>,
    sells: List<Pair<Int, Double>>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    // 弹窗打开期间每 10 秒自动刷新分时数据（关闭即取消）
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            onRefresh()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$stockName 分时") },
        text = {
            when {
                loading && points == null -> HintText("分时加载中…")
                points == null -> HintText("分时数据获取失败，请检查网络")
                else -> IntradayChart(points, prevClose, buys, sells)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 分时图：价格线 + 均价线 + 昨收虚线 + 今日买卖点标记 + 时间刻度（红涨绿跌） */
@Composable
private fun IntradayChart(points: List<MinutePoint>, prevClose: Double?, buys: List<Pair<Int, Double>>, sells: List<Pair<Int, Double>>) {
    val prices = points.flatMap { listOf(it.price, it.avgPrice) } + listOfNotNull(prevClose)
    val minP = prices.minOrNull() ?: 0.0
    val maxP = prices.maxOrNull() ?: 0.0
    val range = (maxP - minP).coerceAtLeast(0.0001)
    val priceLineColor = Color(0xFF8E24AA)   // 价格线：紫色（同花顺风格）
    val avgColor = Color(0xFFFFC107)         // 均价线：黄色（同花顺风格）
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val dashColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("昨收 ${prevClose?.let { formatPrice(it) } ?: "—"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text("最高 ${formatPrice(maxP)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text("最低 ${formatPrice(minP)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val padTop = 6.dp.toPx()
            val padBottom = 4.dp.toPx()
            val chartH = size.height - padTop - padBottom
            val w = size.width
            val n = 240 // 全天分钟数
            fun x(minute: Int): Float = minute.toFloat() / n * w
            fun y(price: Double): Float = padTop + ((maxP - price) / range * chartH).toFloat()

            // 竖向网格线（9:30 / 10:30 / 11:30 / 14:00 / 15:00，午休压缩）
            listOf(0, 60, 120, 180, 240).forEach { m ->
                drawLine(
                    color = gridColor,
                    start = Offset(x(m), padTop),
                    end = Offset(x(m), size.height - padBottom),
                    strokeWidth = 1.dp.toPx()
                )
            }
            // 昨收虚线
            if (prevClose != null) {
                drawLine(
                    color = dashColor,
                    start = Offset(0f, y(prevClose)),
                    end = Offset(w, y(prevClose)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            }
            // 均价线
            val avgPath = Path()
            points.forEachIndexed { i, p ->
                val px = x(p.minute)
                val py = y(p.avgPrice)
                if (i == 0) avgPath.moveTo(px, py) else avgPath.lineTo(px, py)
            }
            drawPath(avgPath, color = avgColor, style = Stroke(width = 1.5.dp.toPx()))
            // 价格线（紫色整条，同花顺风格）
            val singlePath = Path()
            points.forEachIndexed { i, p ->
                val px = x(p.minute)
                val py = y(p.price)
                if (i == 0) singlePath.moveTo(px, py) else singlePath.lineTo(px, py)
            }
            drawPath(singlePath, color = priceLineColor, style = Stroke(width = 2.dp.toPx()))
            // 今日买入点标记（白圈 + 红点）
            buys.forEach { (minute, price) ->
                val px = x(minute.coerceIn(0, 240))
                val py = y(price)
                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(px, py))
                drawCircle(color = UpColor, radius = 3.2.dp.toPx(), center = Offset(px, py))
            }
            // 今日卖出点标记（白圈 + 绿点，同花顺风格：红买绿卖）
            sells.forEach { (minute, price) ->
                val px = x(minute.coerceIn(0, 240))
                val py = y(price)
                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(px, py))
                drawCircle(color = DownColor, radius = 3.2.dp.toPx(), center = Offset(px, py))
            }
        }
        // 时间刻度
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("9:30", "10:30", "11:30", "14:00", "15:00").forEach { label ->
                Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("价格线", fontSize = 10.sp, color = priceLineColor)
            Text("均价线", fontSize = 10.sp, color = avgColor)
        }
    }
}

// ---------------- 账户总览（并列标签：浮盈/市值/已实现/预测） ----------------
@Composable
private fun OverviewDialog(
    accounts: List<StockAccount>,
    predUi: PredictionViewModel.UiState,
    onPredictAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val dataTabs = OverviewTab.entries.toList()
    val tabLabels = dataTabs.map { it.label } + "预测"
    var selected by remember { mutableStateOf(0) }
    val isPredict = selected >= dataTabs.size
    val dataTab = if (!isPredict) dataTabs[selected] else null
    // 按 |值| 从大到小排序展示（市值即按大小，浮盈/已实现按绝对值）
    val entries = remember(accounts, dataTab) {
        if (dataTab == null) emptyList()
        else overviewEntries(accounts, dataTab).sortedByDescending { kotlin.math.abs(it.value) }
    }
    val total = entries.sumOf { kotlin.math.abs(it.value) }
    // 进入"预测"标签自动触发一次预测
    LaunchedEffect(isPredict) {
        if (isPredict) onPredictAll()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("账户总览", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    tabLabels.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = selected == i,
                            onClick = { selected = i },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = tabLabels.size)
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (isPredict) {
                    PredictionPanel(predUi, onPredictAll)
                } else if (accounts.isEmpty()) {
                    EmptyText("暂无股票数据")
                } else {
                    DonutChart(entries, dataTab!!, total)
                    HorizontalDivider()
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(entries) { e ->
                            OverviewLegendRow(e, dataTab, total, entries.indexOf(e))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PredictionPanel(predUi: PredictionViewModel.UiState, onPredictAll: () -> Unit) {
    Column {
        Button(
            onClick = onPredictAll,
            enabled = !predUi.running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (predUi.running) "预测中…" else "一键预测全部 A 股", fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        if (predUi.allResults.isEmpty()) {
            HintText(predUi.error ?: "9:20 后点击获取全部持仓的竞价预测")
        } else {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(predUi.allResults) { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.stockName, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(
                            "${r.conclusion.label} ${signedPct(r.score)} · 置信${r.confidence}",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = when (r.conclusion) {
                                PredictionOutcome.UP -> UpColor
                                PredictionOutcome.DOWN -> DownColor
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}

@Composable
private fun DonutChart(entries: List<OverviewEntry>, tab: OverviewTab, total: Double) {
    // 中心合计 = 真实符号求和（赚亏相抵）；total 用于画弧/占比（绝对值）
    val signedTotal = entries.sumOf { it.value }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(170.dp)) {
            val stroke = 34.dp.toPx()
            var start = -90f
            val nonZero = entries.filter { it.value != 0.0 }
            if (total > 0 && nonZero.isNotEmpty()) {
                nonZero.forEachIndexed { i, e ->
                    val sweep = (kotlin.math.abs(e.value).toFloat() / total.toFloat()) * 360f
                    drawArc(
                        color = overviewColor(e, tab, i),
                        startAngle = start,
                        sweepAngle = (sweep - 1.5f).coerceAtLeast(0f),
                        useCenter = false,
                        style = Stroke(width = stroke)
                    )
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tab.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text("合计 ${signedMoney(signedTotal)}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

private val overviewPalette = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFF4511E),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFFBC02D), Color(0xFF3949AB),
    Color(0xFF6D4C41), Color(0xFF00ACC1)
)

private fun overviewColor(e: OverviewEntry, tab: OverviewTab, index: Int): Color = when (tab) {
    OverviewTab.VALUE -> overviewPalette[index % overviewPalette.size]
    else -> if (e.value >= 0) UpColor else DownColor
}

@Composable
private fun OverviewLegendRow(e: OverviewEntry, tab: OverviewTab, total: Double, index: Int) {
    val color = overviewColor(e, tab, index)
    val pct = if (total > 0) kotlin.math.abs(e.value) / total * 100 else 0.0
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(e.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
        val sign = if (e.value > 0 && tab != OverviewTab.VALUE) "+" else ""
        Text(
            sign + formatMoney(e.value),
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = if (tab == OverviewTab.VALUE) MaterialTheme.colorScheme.onSurface else color
        )
        Spacer(Modifier.width(8.dp))
        Text(String.format("%.1f%%", pct), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

// ---------------- 费率设置弹窗 ----------------
@Composable
private fun FeeSettingsDialog(current: FeeConfig, onSave: (FeeConfig) -> Unit, onDismiss: () -> Unit) {
    var commission by remember { mutableStateOf((current.commissionRate * 10000).toString()) } // 万分之
    var minCom by remember { mutableStateOf(current.minCommission.toString()) }
    var stamp by remember { mutableStateOf((current.stampTaxRate * 100).toString()) }          // %
    var transfer by remember { mutableStateOf((current.transferRate * 10000).toString()) }     // 万分之
    val numFilter = { t: String -> t.filter { c -> c.isDigit() || c == '.' } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("费率设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HintText("买入费=佣金+过户费；卖出费=佣金+印花税+过户费。修改仅影响之后的操作。")
                OutlinedTextField(
                    value = commission, onValueChange = { commission = numFilter(it) },
                    label = { Text("佣金（万分之，默认 2.5）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = minCom, onValueChange = { minCom = numFilter(it) },
                    label = { Text("最低佣金（元，默认 5）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = stamp, onValueChange = { stamp = numFilter(it) },
                    label = { Text("印花税 %（仅卖出，默认 0.05）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = transfer, onValueChange = { transfer = numFilter(it) },
                    label = { Text("过户费（万分之，默认 0.1）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val c = commission.toDoubleOrNull()?.div(10000)
                    val m = minCom.toDoubleOrNull()
                    val s = stamp.toDoubleOrNull()?.div(100)
                    val t = transfer.toDoubleOrNull()?.div(10000)
                    if (c != null && m != null && s != null && t != null && c >= 0 && m >= 0 && s >= 0 && t >= 0) {
                        onSave(FeeConfig(c, m, s, t))
                    }
                }) { Text("保存") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onSave(FeeConfig()); onDismiss() }) { Text("恢复默认") }
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
