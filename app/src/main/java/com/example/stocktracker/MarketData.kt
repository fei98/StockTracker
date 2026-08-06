package com.example.stocktracker

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

/** 权重股行情 */
data class SectorStock(val code: String, val name: String, val gapPct: Double?)

/** 竞价预测所需的全部行情快照 */
data class MarketSnapshot(
    val target: QuoteFields?,       // 目标股票
    val indexPct: Double?,          // 指数涨幅%（合成强度）
    val sector: List<SectorStock>,  // 板块联动名单行情
    val externalPct: List<Double>   // 隔夜美股涨幅%（DJI/IXIC/INX）
)

/** 候选池单只行情（联动学习用） */
data class UniverseGap(val code: String, val name: String, val industry: String?, val gapPct: Double?)

/** 每日候选池快照（联动学习基线） */
data class UniverseDailySnapshot(val date: String, val gaps: List<UniverseGap>)

interface MarketDataApi {
    suspend fun fetchSnapshot(stock: Stock): MarketSnapshot
    suspend fun fetchTargetQuote(stock: Stock): QuoteFields?
}

/** 单只股票的预测配置：合成强度用的指数 + 板块联动名单 */
data class StockPredConfig(
    val indexCode: String,                    // 指数代码（合成强度因子）
    val sector: List<Pair<String, String>>    // 板块联动标的 (代码, 名称)
)

/**
 * 腾讯行情实现。
 * 恒指夜期 / 富时A50 在腾讯公开接口无可用代码，故外围因子仅使用隔夜美股（数据已验证）。
 */
class TencentMarketDataApi(
    private val api: StockApi,
    private val store: SnapshotStore? = null   // 联动学习/手选行业配置（可空）
) : MarketDataApi {

    override suspend fun fetchTargetQuote(stock: Stock): QuoteFields? =
        api.fetchRaw(stock.marketCode)?.let(::parseQuoteFields)

    override suspend fun fetchSnapshot(stock: Stock): MarketSnapshot = coroutineScope {
        val cfg = configFor(stock)
        val target = async { api.fetchRaw(stock.marketCode)?.let(::parseQuoteFields) }
        val index = async { api.fetchRaw(cfg.indexCode)?.let(::parseQuoteFields) }
        val sector = async {
            cfg.sector.map { (code, _) ->
                async { api.fetchRaw(code)?.let(::parseQuoteFields) }
            }.awaitAll().mapNotNull { qf ->
                if (qf != null && qf.code.isNotEmpty()) SectorStock(qf.code, qf.name.ifEmpty { qf.code }, qf.pct) else null
            }
        }
        val external = async {
            EXTERNAL_CODES.mapNotNull { code -> api.fetchRaw(code)?.let(::parseQuoteFields)?.pct }
        }
        MarketSnapshot(
            target = target.await(),
            indexPct = index.await()?.pct,
            sector = sector.await(),
            externalPct = external.await()
        )
    }

    /** 板块联动决策链：手选 > 自动学习 > 名称关键词 > 内置 > 大盘兜底 */
    fun configFor(stock: Stock): StockPredConfig = SectorResolver.resolve(stock, store).config

    /** 批量抓取候选池 + 追加标的（一条请求），联动学习基线 */
    suspend fun fetchUniverse(extraCodes: List<String> = emptyList()): List<UniverseGap> {
        val codes = (UNIVERSE_CODES + extraCodes).distinct()
        if (codes.isEmpty()) return emptyList()
        val raw = api.fetchRaw(codes.joinToString(",")) ?: return emptyList()
        return raw.split(";")
            .mapNotNull { line ->
                // 响应行 v_sz000001="..."; 提取完整代码（市场+代码）
                val key = line.substringBefore('=').substringAfter("v_").trim()
                val qf = parseQuoteFields(line) ?: return@mapNotNull null
                if (qf.code.isEmpty() || qf.name.isEmpty()) return@mapNotNull null
                UniverseGap(key, qf.name, UNIVERSE_INDUSTRY[key], qf.pct)
            }
    }

    companion object {
        /** 行业联动候选池：20 行业 × 5 龙头（代码已实测验证），联动学习用 */
        val INDUSTRY_SECTOR_LISTS: LinkedHashMap<String, List<Pair<String, String>>> = linkedMapOf(
            "白酒" to listOf("sh600519" to "贵州茅台", "sz000858" to "五粮液", "sz000568" to "泸州老窖", "sh600809" to "山西汾酒", "sz002304" to "洋河股份"),
            "银行" to listOf("sh600036" to "招商银行", "sh601398" to "工商银行", "sh601939" to "建设银行", "sz002142" to "宁波银行", "sh601166" to "兴业银行"),
            "券商" to listOf("sh600030" to "中信证券", "sz300059" to "东方财富", "sh601688" to "华泰证券", "sh601211" to "国泰君安", "sz300033" to "同花顺"),
            "保险" to listOf("sh601318" to "中国平安", "sh601628" to "中国人寿", "sh601601" to "中国太保", "sh601336" to "新华保险", "sh601319" to "中国人保"),
            "医药" to listOf("sh600276" to "恒瑞医药", "sh603259" to "药明康德", "sz300760" to "迈瑞医疗", "sh600196" to "复星医药", "sh600436" to "片仔癀"),
            "芯片" to listOf("sh688981" to "中芯国际", "sz002371" to "北方华创", "sh603501" to "韦尔股份", "sh603986" to "兆易创新", "sh688041" to "海光信息"),
            "新能源" to listOf("sz300750" to "宁德时代", "sz300014" to "亿纬锂能", "sz002460" to "赣锋锂业", "sz002466" to "天齐锂业", "sz300274" to "阳光电源"),
            "光伏" to listOf("sh601012" to "隆基绿能", "sh600438" to "通威股份", "sz002129" to "TCL中环", "sz002459" to "晶澳科技", "sh688223" to "晶科能源"),
            "算力" to listOf("sz300308" to "中际旭创", "sz300502" to "新易盛", "sh601138" to "工业富联", "sz000977" to "浪潮信息", "sz002463" to "沪电股份"),
            "军工" to listOf("sh600760" to "中航沈飞", "sh600893" to "航发动力", "sz002179" to "中航光电", "sz002049" to "紫光国微", "sh600038" to "中直股份"),
            "煤炭" to listOf("sh601088" to "中国神华", "sh601225" to "陕西煤业", "sh600188" to "兖矿能源", "sz000983" to "山西焦煤", "sh600546" to "山煤国际"),
            "有色" to listOf("sh601899" to "紫金矿业", "sh600111" to "北方稀土", "sh603993" to "洛阳钼业", "sh603799" to "华友钴业", "sh601168" to "西部矿业"),
            "消费" to listOf("sz000333" to "美的集团", "sz000651" to "格力电器", "sh600690" to "海尔智家", "sh600887" to "伊利股份", "sh603288" to "海天味业"),
            "汽车" to listOf("sz002594" to "比亚迪", "sz000625" to "长安汽车", "sh601127" to "赛力斯", "sh601633" to "长城汽车", "sh600660" to "福耀玻璃"),
            "地产" to listOf("sh600048" to "保利发展", "sz000002" to "万科A", "sz001979" to "招商蛇口", "sh600383" to "金地集团", "sz002244" to "滨江集团"),
            "机器人" to listOf("sz300124" to "汇川技术", "sh601689" to "拓普集团", "sz002050" to "三花智控", "sh688017" to "绿的谐波", "sz002747" to "埃斯顿"),
            "电力" to listOf("sh600900" to "长江电力", "sh600011" to "华能国际", "sh600406" to "国电南瑞", "sh600905" to "三峡能源", "sh600674" to "川投能源"),
            "航空机场" to listOf("sh601111" to "中国国航", "sh600029" to "南方航空", "sh600009" to "上海机场", "sh600004" to "白云机场", "sh603885" to "吉祥航空"),
            "物流" to listOf("sz002352" to "顺丰控股", "sh601919" to "中远海控", "sh600233" to "圆通速递", "sz002120" to "韵达股份", "sz002468" to "申通快递"),
            "传媒" to listOf("sz002027" to "分众传媒", "sz300413" to "芒果超媒", "sz002555" to "三七互娱", "sz002624" to "完美世界", "sz300251" to "光线传媒")
        )

        /** 行业 → 名称关键词（长词优先匹配，作启动期即时默认） */
        val INDUSTRY_KEYWORDS: Map<String, List<String>> = mapOf(
            "白酒" to listOf("茅台", "五粮液", "泸州老窖", "汾酒", "洋河", "古井", "舍得", "今世缘", "水井坊", "酒鬼", "金种子", "酒"),
            "银行" to listOf("银行", "招商银行", "平安银行", "兴业银行", "浦发银行", "民生银行", "宁波", "常熟", "邮储", "交通"),
            "券商" to listOf("证券", "券商", "招商证券", "中信证券", "国泰君安", "华泰", "国泰", "广发", "国信", "申万", "方正", "国金", "同花顺", "指南针", "东方财富"),
            "保险" to listOf("保险", "人寿", "平安", "太保", "新华", "人保", "众安"),
            "医药" to listOf("医药", "药业", "制药", "医疗", "生物", "恒瑞", "药明", "复星", "健康元", "迈瑞", "爱尔", "智飞", "长春高新", "片仔癀", "云南白药", "同仁堂", "白云山", "以岭", "三九"),
            "芯片" to listOf("芯片", "半导体", "中芯", "华创", "韦尔", "兆易", "海光", "寒武纪", "澜起", "中微", "长电", "通富", "汇顶", "卓胜微", "圣邦"),
            "新能源" to listOf("新能源", "宁德", "亿纬", "赣锋", "天齐", "国轩", "欣旺达", "孚能", "德方", "容百", "恩捷", "璞泰来"),
            "光伏" to listOf("光伏", "隆基", "通威", "中环", "晶澳", "晶科", "天合", "正泰", "福斯特", "锦浪", "固德威"),
            "算力" to listOf("算力", "中际旭创", "新易盛", "工业富联", "浪潮", "沪电", "中兴", "烽火", "紫光", "光迅", "天孚", "中科曙光", "科大讯飞", "海康"),
            "军工" to listOf("军工", "中航", "航发", "沈飞", "成飞", "紫光国微", "内蒙一机", "航天电子", "中国卫星", "卫士通"),
            "煤炭" to listOf("煤炭", "神华", "陕煤", "兖矿", "焦煤", "潞安", "平煤", "山煤"),
            "有色" to listOf("有色", "紫金", "北方稀土", "洛阳钼业", "华友", "铜陵", "云铝", "中金黄金", "山东黄金", "江西铜业", "西部矿业"),
            "消费" to listOf("美的", "格力", "海尔", "伊利", "海天", "双汇", "安琪", "涪陵", "洽洽", "金龙鱼", "牧原", "温氏", "新希望"),
            "汽车" to listOf("汽车", "比亚迪", "长安", "赛力斯", "长城", "福耀", "广汽", "上汽", "江淮", "北汽"),
            "地产" to listOf("地产", "保利", "万科", "招商蛇口", "金地", "绿地", "华发", "滨江", "新城", "华侨城"),
            "机器人" to listOf("机器人", "汇川", "拓普", "三花", "绿的谐波", "埃斯顿", "双环传动"),
            "电力" to listOf("电力", "长江电力", "华能", "国电南瑞", "三峡", "川投", "华电", "国投", "中国核电", "龙源", "大唐"),
            "航空机场" to listOf("航空", "国航", "南航", "东航", "机场", "上海机场", "白云", "吉祥", "春秋", "海航"),
            "物流" to listOf("物流", "顺丰", "中远海控", "圆通", "韵达", "申通", "中通", "德邦"),
            "传媒" to listOf("传媒", "分众", "芒果", "三七", "完美", "光线", "万达", "华谊", "中文在线", "视觉中国", "昆仑万维")
        )

        /** 候选池扁平代码表 + 行业映射（去重） */
        val UNIVERSE_CODES: List<String> = INDUSTRY_SECTOR_LISTS.values.flatten().map { it.first }.distinct()
        val UNIVERSE_INDUSTRY: Map<String, String> = INDUSTRY_SECTOR_LISTS.entries
            .flatMap { (ind, list) -> list.map { it.first to ind } }
            .toMap()

        /** 每只预测目标的静态配置（内置名单优先于学习推荐，用户已认可） */
        val SECTOR_MAP = mapOf(
            "sz159915" to StockPredConfig(
                indexCode = "sz399006",   // 创业板指
                sector = listOf(
                    "sz300750" to "宁德时代", "sz300059" to "东方财富", "sz300760" to "迈瑞医疗",
                    "sz300124" to "汇川技术", "sz300274" to "阳光电源", "sz300015" to "爱尔眼科",
                    "sz300308" to "中际旭创", "sz300498" to "温氏股份", "sz300014" to "亿纬锂能", "sz300122" to "智飞生物"
                )
            ),
            "sz002396" to StockPredConfig(
                indexCode = "sz399001",   // 深证成指（主板深市个股）
                sector = listOf(          // 通信设备/算力产业链
                    "sz300308" to "中际旭创", "sz300502" to "新易盛", "sz000938" to "紫光股份",
                    "sz000063" to "中兴通讯", "sh600498" to "烽火通信", "sz002463" to "沪电股份", "sh601138" to "工业富联"
                )
            )
        )

        /** 未配置股票兜底：大盘指数联动 */
        val FALLBACK_CONFIG = StockPredConfig(
            indexCode = "sz399001",
            sector = listOf(
                "sh000001" to "上证指数",
                "sz399001" to "深证成指",
                "sh000300" to "沪深300"
            )
        )

        /** 隔夜外围：道指/纳指/标普（凌晨收盘，9:25 决策时已定局） */
        val EXTERNAL_CODES = listOf("usDJI", "usIXIC", "usINX")

        /**
         * 盘中信号相对强弱基准（v9）：内置配置 > 按市场/板块映射；北交所暂无分钟指数支持 → null（信号降级）。
         * 修复此前未配置股票一律用深证成指导致沪市/科创板基准错配的问题。
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
    }
}

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
