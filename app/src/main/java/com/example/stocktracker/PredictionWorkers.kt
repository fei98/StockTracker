package com.example.stocktracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

/**
 * 竞价预测后台任务（WorkManager，事件驱动不常驻），覆盖所有 A 股持仓（港股/美股跳过）。
 * 时间轴：9:18 意图快照 → 9:20 基准快照 → 9:25 预测+聚合通知 → 10:00 30分钟结果 → 15:05 收盘结果。
 * 每个任务完成后自动排次日；国产 ROM 省电策略可能延迟，属尽力而为（手动按钮可兜底）。
 */
class IntentStageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching {
            val api = TencentMarketDataApi(StockApi(), PrefsSnapshotStore(applicationContext))
            val store = PrefsSnapshotStore(applicationContext)
            val date = PredictionEngine.today()
            trackedStocks(applicationContext).forEach { s ->
                val quote = api.fetchTargetQuote(s)
                if (quote?.price != null && quote.amountWan != null) {
                    store.saveStage(s.marketCode, AuctionStageSnapshot(date, PredictionEngine.STAGE_INTENT, quote.price, quote.amountWan))
                }
            }
        }
        PredictionScheduler.scheduleNext(applicationContext, PredictionScheduler.TAG_EARLY)
        return Result.success()
    }
}

class BaseStageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching {
            val api = TencentMarketDataApi(StockApi(), PrefsSnapshotStore(applicationContext))
            val store = PrefsSnapshotStore(applicationContext)
            val date = PredictionEngine.today()
            trackedStocks(applicationContext).forEach { s ->
                val quote = api.fetchTargetQuote(s)
                if (quote?.price != null && quote.amountWan != null) {
                    store.saveStage(s.marketCode, AuctionStageSnapshot(date, PredictionEngine.STAGE_BASE, quote.price, quote.amountWan))
                }
            }
        }
        PredictionScheduler.scheduleNext(applicationContext, PredictionScheduler.TAG_BASE)
        return Result.success()
    }
}

class PredictWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        // v10：迟到（国产 ROM 延迟）到窗口外不执行——盘中实时价会被当竞价因子污染历史记录与联动基线
        if (!PredictionEngine.isPredictionWindow(System.currentTimeMillis())) {
            PredictionScheduler.scheduleNext(context, PredictionScheduler.TAG_PREDICT)
            return Result.success()
        }
        val store = PrefsSnapshotStore(context)
        val engine = PredictionEngine(TencentMarketDataApi(StockApi(), store), store)
        val date = PredictionEngine.today()
        val state = PrefsStorage(context).load()
        val stocks = trackedStocks(context)
        val results = stocks.mapNotNull { s ->
            engine.runPrediction(
                s,
                hasPosition = state.accounts.any { it.stock.marketCode == s.marketCode && it.totalQty > 0 }
            )
        }
        if (results.isNotEmpty()) PredictionNotifier.post(context, results)
        // 顺带批量抓取候选池（100 只 + 持仓 A 股，一条请求），联动学习基线
        runCatching {
            val gaps = TencentMarketDataApi(StockApi(), store).fetchUniverse(stocks.map { it.marketCode })
            if (gaps.isNotEmpty()) store.addUniverseSnapshot(UniverseDailySnapshot(date, gaps))
        }
        PredictionScheduler.scheduleNext(context, PredictionScheduler.TAG_PREDICT)
        return Result.success()
    }
}

class Outcome30mWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching {
            val store = PrefsSnapshotStore(applicationContext)
            val engine = PredictionEngine(TencentMarketDataApi(StockApi(), store), store)
            val date = PredictionEngine.today()
            trackedStocks(applicationContext).forEach { engine.recordOutcome30m(it, date) }
        }
        PredictionScheduler.scheduleNext(applicationContext, PredictionScheduler.TAG_OUT30)
        return Result.success()
    }
}

class OutcomeCloseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching {
            val store = PrefsSnapshotStore(applicationContext)
            val engine = PredictionEngine(TencentMarketDataApi(StockApi(), store), store)
            val date = PredictionEngine.today()
            trackedStocks(applicationContext).forEach { engine.recordOutcomeClose(it, date) }
        }
        PredictionScheduler.scheduleNext(applicationContext, PredictionScheduler.TAG_OUTCLOSE)
        return Result.success()
    }
}

/**
 * 盘中信号采集 + 结果回填（v9，验证积累）：
 * 交易时段（10:10–14:50）每 10 分钟对全部 A 股持仓做一次信号快照；
 * 每次运行先给已过期（≥30/60 分钟）的快照回填当前价作为结果，随后采集新快照。
 */
class IntradaySignalWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        var isTradingDay = false
        runCatching {
            val state = PrefsStorage(context).load()
            val api = StockApi()
            val store = PrefsIntradaySignalStore(context)
            val date = PredictionEngine.today()
            val nowMs = System.currentTimeMillis()

            // v12：交易日判定（调休周末支持）。以行情时间戳日期 == 今天（Asia/Shanghai）为准，
            // 替代 v11 的"是否采到方向信号"脆弱判据——WATCH/HOLD slot 或午休会导致链在调休周末中断。
            // 普通周末行情时间戳是上一交易日 → false → 排班顺延下周一；调休周末交易日 → true → 10 分钟链继续。
            val todayCN = SimpleDateFormat("yyyyMMdd", Locale.US)
                .apply { timeZone = IntradaySignalEvaluator.CN_TZ }
                .format(Date())
            for (acc in state.accounts) {
                if (!PredictionEngine.isPredictable(acc.stock)) continue
                val q = api.fetchQuote(acc.stock)
                if (q != null && quoteTimeIsOnDate(q.time, todayCN)) {
                    isTradingDay = true
                    break
                }
            }

            // 1) 回填先行：用当前价回填已到期快照的 30/60 分钟结果（同日近似，不跨日）
            state.accounts.forEach { acc ->
                if (!PredictionEngine.isPredictable(acc.stock)) return@forEach
                val snapshots = store.loadSnapshots(acc.stock.marketCode, date)
                if (snapshots.isEmpty()) return@forEach
                val price = api.fetchQuote(acc.stock)?.price ?: return@forEach
                snapshots.forEach { s ->
                    val o30 = if (s.outcome30mPct == null && nowMs - s.timeMs >= 30 * 60_000L) {
                        (price - s.price) / s.price * 100.0
                    } else null
                    val o60 = if (s.outcome60mPct == null && nowMs - s.timeMs >= 60 * 60_000L) {
                        (price - s.price) / s.price * 100.0
                    } else null
                    if (o30 != null || o60 != null) {
                        store.updateOutcomes(
                            acc.stock.marketCode, s.timeMs,
                            o30, if (o30 != null) nowMs else null,
                            o60, if (o60 != null) nowMs else null
                        )
                    }
                }
            }

            // 2) 新快照采集：仅交易时段内（墙钟非收盘/周末）
            if (IntradaySignalEvaluator.wallClockPhase(nowMs) == null) {
                state.accounts.forEach { acc ->
                    val stock = acc.stock
                    if (!PredictionEngine.isPredictable(stock)) return@forEach
                    val indexCode = TencentMarketDataApi.indexCodeFor(stock)
                    val index = indexCode?.let {
                        api.fetchIntraday(Stock(it.substring(2), "", it.substring(0, 2)))
                    }
                    val pts = api.fetchIntraday(stock) ?: return@forEach
                    if (pts.isEmpty()) return@forEach
                    val sig = IntradaySignalEvaluator.evaluate(
                        pts, index, acc.prevClose, acc.totalQty > 0, nowMs,
                        priceLimitPct = priceLimitPct(stock),
                        canSell = acc.sellableQty > 0
                    )
                    // 只保存有方向的信号（BUY/SELL/看跌 NO_TRADE），普通周末残留数据/状态信号不进入统计
                    val direction = directionOf(sig)
                    if (direction != null) {
                        store.addSnapshot(
                            IntradaySignalSnapshot(
                                stockCode = stock.marketCode,
                                date = date,
                                timeMs = nowMs,
                                lastPos = pts.size - 1,
                                price = pts.last().price,
                                action = sig.action,
                                score = sig.score,
                                direction = direction
                            )
                        )
                    }
                }
            }
        }
        PredictionScheduler.scheduleIntradayNext(context, continueOnWeekend = isTradingDay)
        return Result.success()
    }
}

/** 所有支持竞价预测（A 股）的持仓股票 */
private suspend fun trackedStocks(context: Context): List<Stock> =
    PrefsStorage(context).load().accounts
        .map { it.stock }
        .filter { PredictionEngine.isPredictable(it) }

/** 每日任务调度：启动时 ensureScheduled，每个任务完成后自动排次日 */
object PredictionScheduler {

    const val TAG_EARLY = "auction_early"       // 9:18
    const val TAG_BASE = "auction_base"         // 9:20
    const val TAG_PREDICT = "auction_predict"   // 9:25
    const val TAG_OUT30 = "auction_out30"       // 10:00
    const val TAG_OUTCLOSE = "auction_outclose" // 15:05
    const val TAG_INTRADAY = "intraday_signal"  // 盘中信号采集（10:10–14:50 每 10 分钟）

    const val CAPTURE_START_MIN = 10 * 60 + 10 // 10:10（开盘 40 分钟后，特征充足）
    const val CAPTURE_END_MIN = 14 * 60 + 50   // 14:50（给 60 分钟结果留出回填窗口）
    const val CAPTURE_STEP_MIN = 10

    fun ensureScheduled(context: Context) {
        scheduleNext(context, TAG_EARLY)
        scheduleNext(context, TAG_BASE)
        scheduleNext(context, TAG_PREDICT)
        scheduleNext(context, TAG_OUT30)
        scheduleNext(context, TAG_OUTCLOSE)
        // 首次调度允许落在周末（调休交易日自动采集）；无实时数据时 Worker 会跳到下周一
        scheduleIntradayNext(context, continueOnWeekend = true)
    }

    fun scheduleNext(context: Context, tag: String) {
        val (h, m, cls) = specOf(tag) ?: return
        val delay = delayToNext(h, m)
        val request = OneTimeWorkRequest.Builder(cls)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(tag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
    }

    private fun delayToNext(h: Int, m: Int): Long {
        // v11（v10 §4c）：调度统一 Asia/Shanghai，与窗口校验一致，避免设备时区错置导致 9:25 调度触发时窗口不通过
        val now = Calendar.getInstance(IntradaySignalEvaluator.CN_TZ)
        val target = Calendar.getInstance(IntradaySignalEvaluator.CN_TZ).apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }

    /**
     * 盘中信号采集链：交易时段 10:10–14:50 内每 10 分钟一次（Worker 自续链），
     * 其余时间排下一次 10:10。周末默认仍安排一次 10:10 采集（调休交易日可自动出信号并延续 10 分钟链），
     * Worker 确认无实时数据（普通周末）后经 continueOnWeekend=false 跳到下周一，普通周末只多一次空跑。
     */
    fun scheduleIntradayNext(context: Context, continueOnWeekend: Boolean = false) {
        val now = Calendar.getInstance(IntradaySignalEvaluator.CN_TZ)
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val (dayAdd, minute) = when {
            nowMin < CAPTURE_START_MIN -> 0 to CAPTURE_START_MIN
            nowMin >= CAPTURE_END_MIN -> 1 to CAPTURE_START_MIN
            else -> 0 to nowMin + CAPTURE_STEP_MIN
        }
        val target = Calendar.getInstance(IntradaySignalEvaluator.CN_TZ).apply {
            add(Calendar.DAY_OF_YEAR, dayAdd)
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 5)
            set(Calendar.MILLISECOND, 0)
        }
        // 无实时数据确认时，目标落在周末 → 顺延到下周一
        if (!continueOnWeekend) {
            while (target.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                target.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            ) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val delay = (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        val request = OneTimeWorkRequest.Builder(IntradaySignalWorker::class.java)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(TAG_INTRADAY)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TAG_INTRADAY, ExistingWorkPolicy.KEEP, request)
    }

    private fun specOf(tag: String): Triple<Int, Int, Class<out CoroutineWorker>>? = when (tag) {
        TAG_EARLY -> Triple(9, 18, IntentStageWorker::class.java)
        TAG_BASE -> Triple(9, 20, BaseStageWorker::class.java)
        TAG_PREDICT -> Triple(9, 25, PredictWorker::class.java)
        TAG_OUT30 -> Triple(10, 0, Outcome30mWorker::class.java)
        TAG_OUTCLOSE -> Triple(15, 5, OutcomeCloseWorker::class.java)
        else -> null
    }
}
