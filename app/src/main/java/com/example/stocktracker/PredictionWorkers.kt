package com.example.stocktracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

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

    fun ensureScheduled(context: Context) {
        scheduleNext(context, TAG_EARLY)
        scheduleNext(context, TAG_BASE)
        scheduleNext(context, TAG_PREDICT)
        scheduleNext(context, TAG_OUT30)
        scheduleNext(context, TAG_OUTCLOSE)
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
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
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
