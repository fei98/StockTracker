package com.example.stocktracker

/**
 * 板块联动自学习（纯函数，可单测）：
 * 用目标股票与候选池各股的历史竞价涨幅相关性，自动推荐 TopK 联动名单；
 * 积累不足时回落名称关键词匹配作启动期默认。
 */
object SectorLearner {

    const val MIN_LEARN_DAYS = 10   // 相关计算所需最少天数
    const val TOP_K = 6             // 推荐数量
    const val MIN_CORR = 0.30       // 推荐最低相关系数（过滤噪声）
    const val MAX_HISTORY = 40      // 学习窗口

    /** 自动推荐结果 */
    data class Recommendation(
        val stocks: List<UniverseGap>,
        val avgCorr: Double,
        val days: Int
    )

    /** 皮尔逊相关系数；样本不足或方差为 0 返回 null */
    fun pearson(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < MIN_LEARN_DAYS) return null
        val n = a.size
        val meanA = a.sum() / n
        val meanB = b.sum() / n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        if (varA == 0.0 || varB == 0.0) return null
        return cov / kotlin.math.sqrt(varA * varB)
    }

    /**
     * 按日期对齐目标股票与候选股票的竞价涨幅（任一为 null 的日期跳过）。
     * 对齐后不足 MIN_LEARN_DAYS 返回 null。
     */
    private fun aligned(targetGaps: Map<String, Double?>, candGaps: Map<String, Double?>): Pair<List<Double>, List<Double>>? {
        val pairs = targetGaps.mapNotNull { (date, g) ->
            val c = candGaps[date] ?: return@mapNotNull null
            if (g == null || c == null) null else g to c
        }
        if (pairs.size < MIN_LEARN_DAYS) return null
        return pairs.map { it.first } to pairs.map { it.second }
    }

    /** 目标股票每日竞价涨幅（日期 → 涨幅%） */
    private fun gapByDate(snaps: List<UniverseDailySnapshot>, code: String): Map<String, Double?> =
        snaps.associate { snap -> snap.date to (snap.gaps.find { it.code == code }?.gapPct) }

    /**
     * 自动推荐：目标股票 vs 候选池各股（排除自身）竞价涨幅相关 → TopK。
     * 积累 <10 天或可推荐标的不足 → null（调用方回落关键词/兜底）。
     */
    fun recommend(stock: Stock, snaps: List<UniverseDailySnapshot>): Recommendation? {
        val recent = snaps.takeLast(MAX_HISTORY)
        if (recent.size < MIN_LEARN_DAYS) return null
        val target = gapByDate(recent, stock.marketCode)
        if (target.values.none { it != null }) return null

        val scored = mutableListOf<Pair<UniverseGap, Double>>()
        // 候选池去重（跨行业重复代码只取一次）
        val seen = mutableSetOf<String>()
        for (snap in recent) {
            for (g in snap.gaps) {
                if (g.code == stock.marketCode || !seen.add(g.code)) continue
                val cand = gapByDate(recent, g.code)
                val aligned = aligned(target, cand) ?: continue
                val corr = pearson(aligned.first, aligned.second) ?: continue
                scored += g to corr
            }
        }
        if (scored.isEmpty()) return null
        val top = scored.sortedByDescending { it.second }
            .filter { it.second >= MIN_CORR }
            .take(TOP_K)
        if (top.isEmpty()) return null
        return Recommendation(top.map { it.first }, top.map { it.second }.average(), recent.size)
    }

    /** 名称关键词匹配（长词优先），返回行业名；无匹配返回 null */
    fun nameKeywordMatch(name: String): String? {
        val entries = TencentMarketDataApi.INDUSTRY_KEYWORDS.entries
            .flatMap { (ind, words) -> words.map { it to ind } }
            .sortedByDescending { it.first.length }
        for ((word, ind) in entries) {
            if (word.length >= 2 && name.contains(word)) return ind
        }
        return null
    }
}
