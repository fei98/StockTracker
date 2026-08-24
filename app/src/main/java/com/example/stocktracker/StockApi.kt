package com.example.stocktracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 判断输入的代码属于哪个市场，返回市场前缀；非法输入返回 null
 * 6 位 A 股代码规则：
 *   0 深主板 / 1 深基金(ETF、LOF) / 2 深B股 / 3 创业板 → sz
 *   5 沪基金(ETF) / 6 沪主板 / 9 沪B股 → sh
 *   4、8 开头 → 北交所 bj
 * hk 前缀 → 港股；us 前缀 → 美股
 */
fun inferMarket(code: String): String? {
    val t = code.trim()
    if (t.startsWith("hk", true) && t.length >= 3) return "hk"
    if (t.startsWith("us", true) && t.length >= 3) return "us"
    if (t.length == 6 && t.all { it.isDigit() }) {
        return when (t[0]) {
            '0', '1', '2', '3' -> "sz"
            '5', '6', '9' -> "sh"
            '4', '8' -> "bj"
            else -> null
        }
    }
    return null
}

/** 输入是否已经完整、值得触发查询 */
fun isValidCodeInput(input: String): Boolean {
    val t = input.trim()
    return when {
        t.length == 6 && t.all { it.isDigit() } -> true
        (t.startsWith("hk", true) || t.startsWith("us", true)) && t.length >= 3 -> true
        else -> false
    }
}

/**
 * 解析腾讯行情接口返回的文本（GBK 解码后），格式：
 * v_sz000001="51~平安银行~000001~10.52~10.53~10.50~...";
 * 字段：市场标识~名称~代码~现价~昨收~今开~...
 */
fun parseTencentQuote(raw: String): QuoteResult? {
    val start = raw.indexOf('"')
    val end = raw.lastIndexOf('"')
    if (start < 0 || end <= start) return null
    val parts = raw.substring(start + 1, end).split("~")
    if (parts.size < 4) return null
    val name = parts[1].trim()
    val code = parts[2].trim()
    if (name.isEmpty() || code.isEmpty()) return null
    return QuoteResult(
        code = code,
        name = name,
        price = parts.getOrNull(3)?.trim()?.toDoubleOrNull(),
        market = "",
        prevClose = parts.getOrNull(4)?.trim()?.toDoubleOrNull()
    )
}

/**
 * 腾讯行情接口（https://qt.gtimg.cn/q=sz000001）
 * 无需 key、国内直连快；返回 GBK 编码
 */
open class StockApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val gbk: Charset = Charset.forName("GBK")

    private suspend fun fetch(query: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://qt.gtimg.cn/q=$query").build()
            client.newCall(req).execute().use { resp ->
                resp.body?.bytes()?.let { String(it, gbk) }
            }
        }.getOrNull()
    }

    /** 按用户输入（如 000001 / hk00700 / usAAPL）查询股票名称与现价 */
    open suspend fun searchStock(input: String): QuoteResult? {
        val market = inferMarket(input) ?: return null
        val t = input.trim()
        val rawCode = if (market == "hk" || market == "us") t.substring(2) else t
        val text = fetch("$market$rawCode") ?: return null
        return parseTencentQuote(text)?.copy(market = market)
    }

    /** 查询某只股票的实时价格 */
    open suspend fun fetchPrice(stock: Stock): Double? {
        val text = fetch(stock.marketCode) ?: return null
        return parseTencentQuote(text)?.price
    }

    /** 查询某只股票的完整行情（现价 + 昨收，用于涨跌幅显示） */
    open suspend fun fetchQuote(stock: Stock): QuoteFields? =
        fetchRaw(stock.marketCode)?.let(::parseQuoteFields)

    /** 按完整代码取原始行情文本（预测模块用），失败返回 null */
    open suspend fun fetchRaw(query: String): String? = fetch(query)

    /**
     * 今日分时数据（分钟点列表，时间从 9:30 起）。
     * 数据行："0930 3.497 166308 58157907.60"（时间 价格 累计量 累计额）
     */
    open suspend fun fetchIntraday(stock: Stock): List<MinutePoint>? {
        val raw = fetchMinute(stock) ?: return null
        return parseMinuteData(raw)
    }

    private suspend fun fetchMinute(stock: Stock): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=${stock.marketCode}")
                .build()
            client.newCall(req).execute().use { resp -> resp.body?.string() }
        }.getOrNull()
    }

    /**
     * 日K线（前复权 qfq），用于未来一周趋势预测与历史回测。
     * 端点：web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={code},day,,,{count},qfq
     */
    open suspend fun fetchDailyKline(stock: Stock, count: Int = 300): List<DailyBar>? {
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=${stock.marketCode},day,,,$count,qfq")
                    .build()
                client.newCall(req).execute().use { resp -> resp.body?.string() }
            }.getOrNull()
        } ?: return null
        return parseDailyKline(raw)
    }
}

/**
 * 解析日K线（腾讯 fqkline/get，纯字符串/正则，不依赖 org.json 便于单测）。
 * 结构：data.{code}.qfqday = [["2026-08-17","11.200","11.100","11.220","11.070","929043.000"],...]
 * 每行 = [日期, 开, 收, 高, 低, 量, ...]（日期升序，多余字段忽略，兼容 7 字段变体）。
 */
fun parseDailyKline(raw: String): List<DailyBar>? {
    // 定位 qfqday 数组（按括号深度取整段，避免匹配到 qt/market 等其它数组），无则退回 day
    val block = extractArray(raw, "qfqday") ?: extractArray(raw, "day") ?: return null
    // 匹配以日期开头的行（字段数不限），取前 6 个字符串字段
    val rows = Regex("\\[\"\\d{4}-\\d{2}-\\d{2}\"(?:\\s*,\\s*\"[^\"]*\"\\s*)*]")
        .findAll(block)
    val bars = rows.mapNotNull { m ->
        val tokens = Regex("\"([^\"]*)\"").findAll(m.value).map { it.groupValues[1] }.toList()
        if (tokens.size < 6) return@mapNotNull null
        val open = tokens[1].toDoubleOrNull() ?: return@mapNotNull null
        val close = tokens[2].toDoubleOrNull() ?: return@mapNotNull null
        val high = tokens[3].toDoubleOrNull() ?: return@mapNotNull null
        val low = tokens[4].toDoubleOrNull() ?: return@mapNotNull null
        val vol = tokens[5].toDoubleOrNull() ?: return@mapNotNull null
        DailyBar(tokens[0], open, close, high, low, vol.toLong())
    }.toList()
    return bars.takeIf { it.isNotEmpty() }
}

/** 取 JSON 对象里 key 对应的数组整段文本（按括号深度匹配外层闭合，兼容嵌套数组） */
private fun extractArray(raw: String, key: String): String? {
    val idx = raw.indexOf("\"$key\"")
    if (idx < 0) return null
    val start = raw.indexOf('[', idx)
    if (start < 0) return null
    var depth = 0
    for (i in start until raw.length) {
        when (raw[i]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return raw.substring(start, i + 1)
            }
        }
    }
    return null
}

/**
 * 解析今日分时（腾讯 minute/query，纯字符串/正则，不依赖 org.json 便于单测）。
 * 结构：{"code":0,...,"data":{"sz159915":{"data":{"data":["0930 3.497 166308 58157907.60",...]}}}}
 */
fun parseMinuteData(raw: String): List<MinutePoint>? {
    // 取第一处 `"data": [ ... ]`（外层包裹对象与行情块都是 `{}`，会跳过）
    val arrMatch = Regex("\"data\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL).find(raw)
        ?: return null
    val tokens = Regex("\"(\\d{4})\\s+([\\d.]+)\\s+(\\d+)\\s+([\\d.]+)\"")
        .findAll(arrMatch.groupValues[1])
    val points = tokens.mapNotNull { m ->
        val g = m.groupValues
        val minute = parseMinuteIndex(g[1]) ?: return@mapNotNull null
        val price = g[2].toDoubleOrNull() ?: return@mapNotNull null
        val vol = g[3].toLongOrNull() ?: return@mapNotNull null
        val amt = g[4].toDoubleOrNull() ?: return@mapNotNull null
        MinutePoint(minute, price, vol, amt)
    }.toList()
    return points.takeIf { it.isNotEmpty() }
}

/** "0930" → 交易分钟索引（9:30=0；11:30=120；午休压缩：13:00 从 120 继续；15:00=240） */
fun parseMinuteIndex(hhmm: String): Int? {
    if (hhmm.length != 4) return null
    val h = hhmm.substring(0, 2).toIntOrNull() ?: return null
    val m = hhmm.substring(2, 4).toIntOrNull() ?: return null
    if (h < 9 || h > 15) return null
    val idx = if (h <= 11) (h - 9) * 60 + m - 30 else 120 + (h - 13) * 60 + m
    return if (idx in 0..240) idx else null
}
