package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖：买入、均价、最低价优先卖出抵扣、卖出盈亏、现价浮动盈亏、
 * 多股票隔离、股票添加、行情解析、市场判断及各类边界条件
 */
class StockViewModelTest {

    private fun newVm() = StockViewModel()

    private fun quote(code: String = "000001", name: String = "平安银行", price: Double? = null) =
        QuoteResult(code, name, price, "sz")

    /** 添加一只股票并选中（index 0） */
    private fun vmWithStock(code: String = "000001", name: String = "平安银行"): StockViewModel {
        val vm = newVm()
        vm.addStock(quote(code, name))
        return vm
    }

    /** 已添加股票，连续多次买入 */
    private fun vmWithBuys(vararg buys: Pair<Double, Int>): StockViewModel {
        val vm = vmWithStock()
        buys.forEach { (p, q) -> vm.buy(p, q) }
        return vm
    }

    // ---------------- 股票添加 ----------------

    @Test
    fun 添加股票_自动选中_记录查询现价() {
        val vm = newVm()
        vm.addStock(quote("600519", "贵州茅台", 1500.0))
        val s = vm.state.value
        assertEquals(1, s.accounts.size)
        assertEquals(0, s.selectedIndex)
        assertEquals("贵州茅台", s.selected!!.stock.name)
        assertEquals("sz", s.selected!!.stock.market)
        assertEquals(1500.0, s.selected!!.currentPrice!!, 0.0001)
    }

    @Test
    fun 添加股票_再添加会自动切换到新股() {
        val vm = newVm()
        vm.addStock(quote("000001", "平安银行"))
        vm.addStock(quote("600519", "贵州茅台"))
        val s = vm.state.value
        assertEquals(2, s.accounts.size)
        assertEquals(1, s.selectedIndex)
        assertEquals("贵州茅台", s.selected!!.stock.name)
    }

    @Test
    fun 添加重复股票_被拒绝() {
        val vm = vmWithStock("000001", "平安银行")
        vm.addStock(quote("000001", "平安银行"))
        assertEquals(1, vm.state.value.accounts.size)
        assertTrue(vm.state.value.message!!.contains("已在列表"))
    }

    @Test
    fun 无选中股票_买入卖出被拒绝() {
        val vm = newVm()
        vm.buy(3.0, 100)
        vm.sell(3.5, 100)
        assertTrue(vm.state.value.message!!.contains("请先添加"))
        assertTrue(vm.state.value.accounts.isEmpty())
    }

    // ---------------- 买入 ----------------

    @Test
    fun 买入_创建批次并正确计算成本均价() {
        val vm = vmWithStock()
        vm.buy(5.0, 200)
        val acc = vm.state.value.selected!!
        assertEquals(1, acc.holdings.size)
        val lot = acc.holdings[0]
        assertEquals(5.0, lot.price, 0.0001)
        assertEquals(200, lot.remainingQty)
        assertEquals(200, lot.originalQty)
        assertEquals(200, acc.totalQty)
        assertEquals(1000.0, acc.totalCost, 0.0001)
        assertEquals(5.0, acc.avgPrice, 0.0001)
        assertEquals(1, acc.trades.size)
        assertEquals(TradeType.BUY, acc.trades[0].type)
        assertTrue(vm.state.value.message!!.contains("买入成功"))
    }

    @Test
    fun 买入_多次不同价_均价等于总成本除总数量() {
        val vm = vmWithBuys(5.0 to 200, 3.0 to 300)
        val acc = vm.state.value.selected!!
        assertEquals(500, acc.totalQty)
        assertEquals(1900.0, acc.totalCost, 0.0001)
        assertEquals(3.8, acc.avgPrice, 0.0001)
        assertEquals(2, acc.holdings.size)
    }

    @Test
    fun 买入_小数价格_成本精确() {
        val vm = vmWithBuys(3.41 to 2900)
        val acc = vm.state.value.selected!!
        assertEquals(9889.0, acc.totalCost, 0.0001)
        assertEquals(3.41, acc.avgPrice, 0.0001)
    }

    @Test
    fun 买入_边界值极小_零_负数_均被拒绝() {
        val vm = vmWithStock()
        vm.buy(0.0, 100)
        vm.buy(-1.0, 100)
        vm.buy(3.0, 0)
        vm.buy(3.0, -5)
        assertEquals(0, vm.state.value.selected!!.holdings.size)
        assertEquals(0, vm.state.value.selected!!.trades.size)
        assertTrue(vm.state.value.message!!.contains("正确"))
    }

    @Test
    fun 买入_超大数量与价格_正常() {
        val vm = vmWithBuys(100000.5 to 1000000)
        val acc = vm.state.value.selected!!
        assertEquals(1000000, acc.totalQty)
        assertEquals(100000.5, acc.avgPrice, 0.001)
        assertEquals(100000500000.0, acc.totalCost, 1.0)
    }

    // ---------------- 卖出匹配（最低价优先） ----------------

    @Test
    fun 卖出_用户示例_最低价优先抵扣_剩3元100股_盈利500() {
        val vm = vmWithBuys(5.0 to 200, 3.0 to 300, 1.0 to 100)
        vm.sell(4.0, 300)
        val acc = vm.state.value.selected!!
        // 1元100股全扣、3元扣200股，3元剩100股；5元200股不动
        val byPrice = acc.holdings.sortedBy { it.price }
        assertEquals(2, byPrice.size)
        assertEquals(3.0, byPrice[0].price, 0.0001)
        assertEquals(100, byPrice[0].remainingQty)
        assertEquals(5.0, byPrice[1].price, 0.0001)
        assertEquals(200, byPrice[1].remainingQty)
        assertEquals(300, acc.totalQty)
        // 均价 = (3*100 + 5*200) / 300
        assertEquals(4.3333, acc.avgPrice, 0.001)
        // 本次盈利 = 1元100股赚(4-1)*100 + 3元200股赚(4-3)*200 = 300 + 200
        val sell = acc.trades.last()
        assertEquals(TradeType.SELL, sell.type)
        assertEquals(500.0, sell.profit, 0.0001)
        assertEquals(300, sell.qty)
        assertTrue(vm.state.value.message!!.contains("本次盈利"))
    }

    @Test
    fun 卖出_跨多个批次_含亏损批次_盈亏合并() {
        val vm = vmWithBuys(1.0 to 50, 2.0 to 50, 3.0 to 100)
        vm.sell(2.5, 120)
        val acc = vm.state.value.selected!!
        // 抵1元50(+75) → 抵2元50(+25) → 抵3元20(-10) = +90
        assertEquals(90.0, acc.trades.last().profit, 0.0001)
        val left = acc.holdings.sortedBy { it.price }
        assertEquals(1, left.size)
        assertEquals(3.0, left[0].price, 0.0001)
        assertEquals(80, left[0].remainingQty)
    }

    @Test
    fun 卖出_恰好全部卖出_持仓清空均价归零() {
        val vm = vmWithBuys(3.0 to 100, 4.0 to 100)
        vm.sell(5.0, 200)
        val acc = vm.state.value.selected!!
        assertTrue(acc.holdings.isEmpty())
        assertEquals(0, acc.totalQty)
        assertEquals(0.0, acc.totalCost, 0.0001)
        assertEquals(0.0, acc.avgPrice, 0.0001)
        // 盈利 = 100*(5-3) + 100*(5-4) = 300
        assertEquals(300.0, acc.trades.last().profit, 0.0001)
    }

    @Test
    fun 卖出_部分抵扣单个批次() {
        val vm = vmWithBuys(4.0 to 500)
        vm.sell(5.0, 300)
        val acc = vm.state.value.selected!!
        assertEquals(1, acc.holdings.size)
        assertEquals(200, acc.holdings[0].remainingQty)
        assertEquals(500, acc.holdings[0].originalQty)
        assertEquals(300.0, acc.trades.last().profit, 0.0001)
        assertEquals(4.0, acc.avgPrice, 0.0001)
    }

    @Test
    fun 卖出_亏损时盈利为负() {
        val vm = vmWithBuys(5.0 to 100)
        vm.sell(3.0, 100)
        assertEquals(-200.0, vm.state.value.selected!!.trades.last().profit, 0.0001)
        assertTrue(vm.state.value.selected!!.holdings.isEmpty())
    }

    @Test
    fun 卖出_平价卖出_盈利为零() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.0, 100)
        assertEquals(0.0, vm.state.value.selected!!.trades.last().profit, 0.0001)
    }

    @Test
    fun 卖出_同价多个批次_合并抵扣正确() {
        val vm = vmWithBuys(3.0 to 100, 3.0 to 200)
        vm.sell(3.5, 150)
        val acc = vm.state.value.selected!!
        assertEquals(75.0, acc.trades.last().profit, 0.0001)
        assertEquals(150, acc.totalQty)
        assertEquals(1, acc.holdings.size)
        assertEquals(150, acc.holdings[0].remainingQty)
    }

    @Test
    fun 卖出_多笔依次操作_批次不断演算正确() {
        val vm = vmWithBuys(5.0 to 200, 3.0 to 300, 1.0 to 100)
        vm.sell(4.0, 300)   // 剩 3元100 + 5元200
        vm.sell(4.0, 250)   // 抵3元100(+100) + 5元150(-150) = -50
        val acc = vm.state.value.selected!!
        assertEquals(-50.0, acc.trades.last().profit, 0.0001)
        assertEquals(50, acc.totalQty)
        assertEquals(5.0, acc.holdings[0].price, 0.0001)
        assertEquals(50, acc.holdings[0].remainingQty)
        assertEquals(5.0, acc.avgPrice, 0.0001)
    }

    // ---------------- 卖出边界 ----------------

    @Test
    fun 卖出_无持仓_被拒绝() {
        val vm = vmWithStock()
        vm.sell(4.0, 100)
        assertTrue(vm.state.value.selected!!.holdings.isEmpty())
        assertTrue(vm.state.value.message!!.contains("无持仓"))
    }

    @Test
    fun 卖出_数量超过持仓_被拒绝且数据不变() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(4.0, 101)
        val acc = vm.state.value.selected!!
        assertEquals(100, acc.totalQty)
        assertEquals(1, acc.holdings.size)
        assertEquals(1, acc.trades.size)
        assertEquals(TradeType.BUY, acc.trades[0].type)
        assertTrue(vm.state.value.message!!.contains("超过持仓"))
    }

    @Test
    fun 卖出_参数非法_零_负数_被拒绝() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(0.0, 100)
        vm.sell(-2.0, 100)
        vm.sell(3.0, 0)
        vm.sell(3.0, -10)
        val acc = vm.state.value.selected!!
        assertEquals(1, acc.holdings.size)
        assertEquals(100, acc.totalQty)
        assertEquals(1, acc.trades.size)
    }

    @Test
    fun 卖出_小数价格_盈亏精确() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.333, 100)
        assertEquals(33.3, vm.state.value.selected!!.trades.last().profit, 0.0001)
    }

    // ---------------- 现价浮动盈亏 ----------------

    @Test
    fun 现价_各批次盈亏按价差乘以剩余数量() {
        val vm = vmWithBuys(1.0 to 100, 3.0 to 300, 5.0 to 200)
        vm.setCurrentPrice(2.0)
        val acc = vm.state.value.selected!!
        val pnl = acc.lotPnls.associateBy { it.lot.price }
        assertEquals(100.0, pnl[1.0]!!.pnl, 0.0001)    // (2-1)*100
        assertEquals(-300.0, pnl[3.0]!!.pnl, 0.0001)   // (2-3)*300
        assertEquals(-600.0, pnl[5.0]!!.pnl, 0.0001)   // (2-5)*200
        assertEquals(100.0, pnl[1.0]!!.pnlPercent, 0.001)
        assertEquals(-33.3333, pnl[3.0]!!.pnlPercent, 0.001)
        assertEquals(-60.0, pnl[5.0]!!.pnlPercent, 0.001)
        // 总市值 2*600=1200，总成本 100+900+1000=2000，总盈亏 -800
        assertEquals(1200.0, acc.marketValue, 0.0001)
        assertEquals(2000.0, acc.totalCost, 0.0001)
        assertEquals(-800.0, acc.totalPnl, 0.0001)
    }

    @Test
    fun 现价_等于持仓均价时_总盈亏为零() {
        val vm = vmWithBuys(3.41 to 2900)
        vm.setCurrentPrice(3.41)
        assertEquals(0.0, vm.state.value.selected!!.totalPnl, 0.0001)
    }

    @Test
    fun 现价_零_合法_全部亏损() {
        val vm = vmWithBuys(3.0 to 100)
        vm.setCurrentPrice(0.0)
        assertEquals(-300.0, vm.state.value.selected!!.totalPnl, 0.0001)
        assertEquals(0.0, vm.state.value.selected!!.marketValue, 0.0001)
    }

    @Test
    fun 现价_负数_被拒绝() {
        val vm = vmWithBuys(3.0 to 100)
        vm.setCurrentPrice(-1.0)
        assertTrue(vm.state.value.selected!!.currentPrice == null)
        assertTrue(vm.state.value.message!!.contains("不能为负"))
    }

    @Test
    fun 现价_更新与清除() {
        val vm = vmWithBuys(3.0 to 100)
        vm.setCurrentPrice(4.0)
        assertEquals(4.0, vm.state.value.selected!!.currentPrice!!, 0.0001)
        vm.setCurrentPrice(null)
        assertTrue(vm.state.value.selected!!.currentPrice == null)
        assertTrue(vm.state.value.message!!.contains("清除"))
    }

    @Test
    fun 现价_卖出部分后_浮动盈亏按剩余数量计算() {
        val vm = vmWithBuys(1.0 to 100, 3.0 to 300)
        vm.sell(2.0, 150)            // 抵1元100 + 3元50，剩3元250
        vm.setCurrentPrice(3.0)
        val acc = vm.state.value.selected!!
        val pnl = acc.lotPnls.associateBy { it.lot.price }
        assertEquals(1, pnl.size)
        assertEquals(0.0, pnl[3.0]!!.pnl, 0.0001)  // (3-3)*250 = 0
        assertEquals(750.0, acc.marketValue, 0.0001) // 3*250
        assertEquals(750.0, acc.totalCost, 0.0001)
        assertEquals(0.0, acc.totalPnl, 0.0001)
    }

    // ---------------- 多股票隔离 ----------------

    @Test
    fun 多股票_买卖数据互不影响() {
        val vm = newVm()
        vm.addStock(quote("000001", "平安银行"))
        vm.buy(5.0, 200)
        vm.addStock(quote("600519", "贵州茅台")) // 自动切到新股
        vm.buy(3.0, 300)

        vm.selectStock(0) // 切回平安银行
        val a = vm.state.value.selected!!
        assertEquals(200, a.totalQty)
        assertEquals(5.0, a.avgPrice, 0.0001)
        assertEquals(1, a.trades.size)

        vm.selectStock(1) // 贵州茅台
        val b = vm.state.value.selected!!
        assertEquals(300, b.totalQty)
        assertEquals(3.0, b.avgPrice, 0.0001)
        assertEquals(1, b.trades.size)
    }

    @Test
    fun 多股票_卖出匹配只在当前股票内进行() {
        val vm = newVm()
        vm.addStock(quote("000001", "平安银行"))
        vm.buy(5.0, 200)
        vm.addStock(quote("600519", "贵州茅台"))
        vm.buy(1.0, 100)
        vm.sell(2.0, 100) // 在茅台内：1元100股全部卖出，盈利100

        val maotai = vm.state.value.selected!!
        assertEquals(0, maotai.totalQty)
        assertEquals(100.0, maotai.trades.last().profit, 0.0001)

        vm.selectStock(0)
        val pingan = vm.state.value.selected!!
        assertEquals(200, pingan.totalQty)
        assertEquals(1, pingan.trades.size)
    }

    @Test
    fun 多股票_现价各自独立() {
        val vm = newVm()
        vm.addStock(quote("000001", "平安银行"))
        vm.setCurrentPrice(10.0)
        vm.addStock(quote("600519", "贵州茅台"))
        assertEquals(10.0, vm.state.value.accounts[0].currentPrice!!, 0.0001)
        assertTrue(vm.state.value.accounts[1].currentPrice == null)
    }

    @Test
    fun 清空当前股票_不影响其他股票() {
        val vm = newVm()
        vm.addStock(quote("000001", "平安银行"))
        vm.buy(5.0, 200)
        vm.addStock(quote("600519", "贵州茅台"))
        vm.buy(3.0, 100)

        vm.selectStock(0)
        vm.clearSelected()
        val s = vm.state.value
        assertTrue(s.accounts[0].holdings.isEmpty())
        assertEquals(0, s.accounts[0].totalQty)
        assertEquals(100, s.accounts[1].totalQty)
        assertTrue(s.message!!.contains("平安银行"))
    }

    @Test
    fun 清空全部_重置所有股票() {
        val vm = newVm()
        vm.addStock(quote("000001", "平安银行"))
        vm.buy(5.0, 200)
        vm.setCurrentPrice(6.0)
        vm.addStock(quote("600519", "贵州茅台"))
        vm.buy(3.0, 100)

        vm.clearAll()
        val s = vm.state.value
        assertTrue(s.accounts.isEmpty())
        assertEquals(-1, s.selectedIndex)
        assertTrue(s.message!!.contains("已清空"))
    }

    // ---------------- 行情解析与市场判断 ----------------

    @Test
    fun 解析腾讯行情_正常() {
        val raw = "v_sz000001=\"51~平安银行~000001~10.52~10.53~10.50~10.52~10.53~10.50~2681329~28209633292.000~10.52\";"
        val r = parseTencentQuote(raw)
        assertEquals("000001", r!!.code)
        assertEquals("平安银行", r.name)
        assertEquals(10.52, r.price!!, 0.0001)
    }

    @Test
    fun 解析腾讯行情_无引号返回null() {
        assertNull(parseTencentQuote("v_sz000001=;"))
    }

    @Test
    fun 解析腾讯行情_空名称返回null() {
        val raw = "v_sh600519=\"1~~~600519~1500.00\";"
        assertNull(parseTencentQuote(raw))
    }

    @Test
    fun 市场判断_沪市_深市_北交所() {
        assertEquals("sh", inferMarket("600519"))
        assertEquals("sh", inferMarket("688981"))
        assertEquals("sz", inferMarket("000001"))
        assertEquals("sz", inferMarket("300750"))
        assertEquals("bj", inferMarket("830799"))
    }

    @Test
    fun 市场判断_ETF基金代码() {
        assertEquals("sz", inferMarket("159915"))  // 深市 ETF
        assertEquals("sz", inferMarket("161725"))  // 深市 LOF
        assertEquals("sh", inferMarket("510300"))  // 沪市 ETF
        assertEquals("sh", inferMarket("588000"))  // 沪市科创 ETF
    }

    @Test
    fun 市场判断_港股_美股_不区分大小写() {
        assertEquals("hk", inferMarket("hk00700"))
        assertEquals("hk", inferMarket("HK00700"))
        assertEquals("us", inferMarket("usAAPL"))
        assertEquals("us", inferMarket("USaapl"))
    }

    @Test
    fun 市场判断_非法输入返回null() {
        assertNull(inferMarket(""))
        assertNull(inferMarket("12345"))
        assertNull(inferMarket("1234567"))
        assertNull(inferMarket("abcdef"))
        assertNull(inferMarket("hk"))
        assertNull(inferMarket("us"))
    }

    @Test
    fun 代码输入有效性判断() {
        assertTrue(isValidCodeInput("600519"))
        assertTrue(isValidCodeInput("000001"))
        assertTrue(isValidCodeInput("hk00700"))
        assertTrue(isValidCodeInput("usAAPL"))
        assertTrue(!isValidCodeInput("12345"))
        assertTrue(!isValidCodeInput("abcdef"))
        assertTrue(!isValidCodeInput(""))
        assertTrue(!isValidCodeInput("hk"))
    }

    // ---------------- 交易记录 ----------------

    @Test
    fun 交易记录_买卖均入历史_卖出带盈亏() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.5, 100)
        val trades = vm.state.value.selected!!.trades
        assertEquals(2, trades.size)
        assertEquals(TradeType.BUY, trades[0].type)
        assertEquals(TradeType.SELL, trades[1].type)
        assertEquals(50.0, trades[1].profit, 0.0001)
    }

    // ---------------- 其他 ----------------

    @Test
    fun 卖完再买_新批次均价正确() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.5, 100)
        vm.buy(2.0, 50)
        val acc = vm.state.value.selected!!
        assertEquals(1, acc.holdings.size)
        assertEquals(2.0, acc.avgPrice, 0.0001)
        assertEquals(50, acc.totalQty)
    }

    @Test
    fun 消息_会随下次操作被覆盖() {
        val vm = vmWithStock()
        vm.buy(3.0, 100)
        vm.buy(2.0, 100)
        assertTrue(vm.state.value.message!!.contains("2.00"))
    }
}
