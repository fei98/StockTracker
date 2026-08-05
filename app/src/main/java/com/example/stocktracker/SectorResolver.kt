package com.example.stocktracker

/** 板块联动配置来源 */
enum class SectorSource(val label: String) {
    USER("手动选择"),
    LEARNED("自动推荐"),
    KEYWORD("名称匹配"),
    BUILTIN("内置配置"),
    FALLBACK("大盘兜底")
}

/** 解析结果：配置 + 来源 + 展示说明 */
data class ResolvedSector(
    val config: StockPredConfig,
    val source: SectorSource,
    val detail: String
)

/**
 * 板块联动决策链（用户零操作，自动给出推荐）：
 * 用户手选行业 > 自动学习推荐（≥10 天联动度） > 名称关键词 > 内置名单(159915/002396) > 大盘兜底
 */
object SectorResolver {

    /** 行业名单统一用的指数（合成强度因子） */
    const val INDUSTRY_INDEX = "sz399001"

    fun resolve(stock: Stock, store: SnapshotStore?): ResolvedSector {
        val key = stock.marketCode
        val lists = TencentMarketDataApi.INDUSTRY_SECTOR_LISTS

        // 1. 用户手选行业（最高优先）
        store?.getUserSector(key)?.let { ind ->
            lists[ind]?.let { list ->
                return ResolvedSector(StockPredConfig(INDUSTRY_INDEX, list), SectorSource.USER, "手动选择：$ind")
            }
        }

        // 2. 自动学习推荐（≥10 天联动快照）
        if (store != null) {
            val rec = SectorLearner.recommend(stock, store.loadUniverseSnapshots())
            if (rec != null) {
                val config = StockPredConfig(INDUSTRY_INDEX, rec.stocks.map { it.code to it.name })
                return ResolvedSector(config, SectorSource.LEARNED,
                    "自动推荐：联动度 ${String.format("%.2f", rec.avgCorr)}（基于过去 ${rec.days} 天竞价同向性）")
            }
        }

        // 3. 名称关键词（积累期即时默认）
        SectorLearner.nameKeywordMatch(stock.name)?.let { ind ->
            lists[ind]?.let { list ->
                return ResolvedSector(StockPredConfig(INDUSTRY_INDEX, list), SectorSource.KEYWORD, "名称匹配：$ind")
            }
        }

        // 4. 内置名单（159915/002396 已人工配置）
        TencentMarketDataApi.SECTOR_MAP[key]?.let { cfg ->
            return ResolvedSector(cfg, SectorSource.BUILTIN, "内置配置")
        }

        // 5. 大盘兜底
        return ResolvedSector(TencentMarketDataApi.FALLBACK_CONFIG, SectorSource.FALLBACK, "大盘兜底（数据积累后自动推荐）")
    }
}
