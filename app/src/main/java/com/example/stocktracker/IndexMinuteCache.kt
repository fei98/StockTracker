package com.example.stocktracker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 指数分钟线共享缓存（v11，收口 v8 评审③"锁粒度 per-VM"）：
 * 60 秒 TTL + Mutex 双重校验，由 StockViewModel / PredictionViewModel 共用，
 * 同一指数代码在进程内只发一次真实请求。
 */
object IndexMinuteCache {
    private var cacheCode: String? = null
    private var cacheAt = 0L
    private var cachePoints: List<MinutePoint>? = null
    private val mutex = Mutex()

    suspend fun fetch(api: StockApi, stock: Stock): List<MinutePoint>? {
        val code = TencentMarketDataApi.indexCodeFor(stock) ?: return null
        val now = System.currentTimeMillis()
        if (cacheCode == code && now - cacheAt < 60_000) return cachePoints
        return mutex.withLock {
            val now2 = System.currentTimeMillis()
            if (cacheCode == code && now2 - cacheAt < 60_000) return@withLock cachePoints
            val indexStock = Stock(code = code.substring(2), name = "", market = code.substring(0, 2))
            val pts = api.fetchIntraday(indexStock)
            cacheCode = code
            cacheAt = now2
            cachePoints = pts
            pts
        }
    }
}
