package com.example.stocktracker

/** 单只标的的完整行情字段（腾讯 qt.gtimg.cn 布局） */
data class QuoteFields(
    val name: String,
    val code: String,
    val price: Double?,       // [3] 现价（竞价期为匹配价）
    val prevClose: Double?,   // [4] 昨收
    val open: Double?,        // [5] 今开
    val pct: Double?,         // [32] 涨跌幅%
    val amountWan: Double?,   // [37] 成交额(万)
    val time: String          // [30] 时间
)

/** 单只股票的指数基准配置：相对强弱用的指数代码 */
data class StockPredConfig(
    val indexCode: String,
    val sector: List<Pair<String, String>>    // 保留字段（兼容历史配置），周趋势暂只用到 indexCode
)

/** 解析腾讯行情文本为字段对象（字段索引已实测验证） */
fun parseQuoteFields(raw: String): QuoteFields? {
    val start = raw.indexOf('"')
    val end = raw.lastIndexOf('"')
    if (start < 0 || end <= start) return null
    val parts = raw.substring(start + 1, end).split("~")
    if (parts.size < 38) return null
    return QuoteFields(
        name = parts.getOrNull(1)?.trim().orEmpty(),
        code = parts.getOrNull(2)?.trim().orEmpty(),
        price = parts.getOrNull(3)?.trim()?.toDoubleOrNull(),
        prevClose = parts.getOrNull(4)?.trim()?.toDoubleOrNull(),
        open = parts.getOrNull(5)?.trim()?.toDoubleOrNull(),
        pct = parts.getOrNull(32)?.trim()?.toDoubleOrNull(),
        amountWan = parts.getOrNull(37)?.trim()?.toDoubleOrNull(),
        time = parts.getOrNull(30)?.trim().orEmpty()
    )
}

/**
 * 判断行情时间戳是否属于指定日期（yyyyMMdd）。
 * 腾讯时间戳可能带分隔符（"20260807102532" / "2026-08-07 10:25:32"），
 * 统一取数字前缀比较，用于判定调休周末等"是否真实交易日"。
 */
fun quoteTimeIsOnDate(time: String, yyyyMMdd: String): Boolean =
    time.filter { it.isDigit() }.startsWith(yyyyMMdd)

/** 内置指数基准配置（相对强弱因子用） */
val SECTOR_MAP: Map<String, StockPredConfig> = mapOf(
    "sz159915" to StockPredConfig(
        indexCode = "sz399006",   // 创业板指
        sector = emptyList()
    ),
    "sz002396" to StockPredConfig(
        indexCode = "sz399001",   // 深证成指（主板深市个股）
        sector = emptyList()
    )
)

/** 未配置股票兜底：大盘指数 */
val FALLBACK_CONFIG = StockPredConfig(
    indexCode = "sz399001",
    sector = emptyList()
)

/**
 * 指数基准（周趋势相对强弱）：内置配置 > 按市场/板块映射；北交所/港美股无对应指数基准 → null（降级）。
 */
fun indexCodeFor(stock: Stock): String? {
    SECTOR_MAP[stock.marketCode]?.let { return it.indexCode }
    val code = stock.code
    return when {
        stock.market == "sh" && code.startsWith("68") -> "sh000688" // 科创板 → 科创50
        stock.market == "sh" && code.startsWith("5") -> "sh000300"  // 沪市 ETF → 沪深300
        stock.market == "sh" && code.startsWith("6") -> "sh000001"  // 沪主板 → 上证指数
        stock.market == "sz" && code.startsWith("30") -> "sz399006" // 创业板 → 创业板指
        stock.market == "sz" &&
            (code.startsWith("0") || code.startsWith("1") || code.startsWith("2")) -> "sz399001" // 深主板/深ETF → 深证成指
        else -> null
    }
}
