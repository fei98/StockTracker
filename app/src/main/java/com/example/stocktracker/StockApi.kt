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
    return QuoteResult(code, name, parts[3].trim().toDoubleOrNull(), "")
}

/**
 * 腾讯行情接口（https://qt.gtimg.cn/q=sz000001）
 * 无需 key、国内直连快；返回 GBK 编码
 */
class StockApi(
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
    suspend fun searchStock(input: String): QuoteResult? {
        val market = inferMarket(input) ?: return null
        val t = input.trim()
        val rawCode = if (market == "hk" || market == "us") t.substring(2) else t
        val text = fetch("$market$rawCode") ?: return null
        return parseTencentQuote(text)?.copy(market = market)
    }

    /** 查询某只股票的实时价格 */
    suspend fun fetchPrice(stock: Stock): Double? {
        val text = fetch(stock.marketCode) ?: return null
        return parseTencentQuote(text)?.price
    }
}
