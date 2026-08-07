package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：手续费计算、含费浮动/已实现盈亏、费用分摊、总览聚合、费率设置更新 */
class FeeTest {

    private val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L

    private fun newVm(config: FeeConfig = FeeConfig()) =
        StockViewModel(initialFeeConfig = config)

    // ---------------- 费用计算 ----------------

    @Test
    fun 费用_小额触发最低佣金() {
        val c = FeeConfig()
        // 300×万2.5=0.075 < 5 → 佣金按 5；过户费 300×万0.1=0.003
        assertEquals(5.003, c.buyFee(300.0), 0.0001)
        // 卖出再加印花税 300×0.0005=0.15
        assertEquals(5.153, c.sellFee(300.0), 0.0001)
    }

    @Test
    fun 费用_大额按比例() {
        val c = FeeConfig()
        // 100000×万2.5=25 > 5 → 佣金 25；过户费 100000×万0.1=1
        assertEquals(26.0, c.buyFee(100000.0), 0.0001)
        assertEquals(76.0, c.sellFee(100000.0), 0.0001) // +印花税50
    }

    // ---------------- 买入记录费用 ----------------

    @Test
    fun 买入_记录批次费与累计买入费() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100)
        val acc = vm.state.value.selected!!
        assertEquals(5.003, acc.holdings[0].fee, 0.0001)
        assertEquals(5.003, acc.totalBuyFee, 0.0001)
        assertEquals(5.003, acc.trades[0].fee, 0.0001)
        assertEquals(TradeType.BUY, acc.trades[0].type)
    }

    // ---------------- 含费盈亏 ----------------

    @Test
    fun 浮动盈亏_扣除持仓批次分摊买入费() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100)
        vm.setCurrentPrice(3.3)
        val acc = vm.state.value.selected!!
        // 裸盈亏 30，扣买入费 5.003 → 24.997
        assertEquals(24.997, acc.totalPnl, 0.001)
        assertEquals(305.003, acc.totalCostWithFee, 0.001) // 裸成本 300 + 持有分摊费 5.003
    }

    @Test
    fun 已实现盈亏_扣卖出费与卖出批次分摊买入费() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100, time = yesterday)
        vm.sell(3.5, 100)
        val acc = vm.state.value.selected!!
        // 裸卖盈 50；买入费 5.003 全部随卖出实现（costFee）；卖出费 = 5+0.175+0.0035 = 5.1785
        assertEquals(50.0 - 5.003 - 5.1785, acc.realizedPnlFee, 0.001)
        assertEquals(50.0 - 5.003 - 5.1785, acc.trades.last().netProfit, 0.001)
        assertTrue(acc.holdings.isEmpty())
        assertEquals(0.0, acc.heldBuyFee, 0.001)
    }

    @Test
    fun 部分卖出_费用按剩余比例分摊() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(1.0, 100, time = yesterday) // 费 5.001
        vm.buy(3.0, 100, time = yesterday) // 费 5.003
        vm.sell(2.0, 50)                   // 抵 1 元 50 股；卖出费 5.051
        val acc = vm.state.value.selected!!
        // 卖出 1 元 50 股 → costFee = 5.001×0.5 = 2.5005；剩余批次持有费 2.5005+5.003
        val heldBuyFee = 5.001 * 0.5 + 5.003
        assertEquals(heldBuyFee, acc.heldBuyFee, 0.001)
        assertEquals(2.5005, acc.trades.last().costFee, 0.001)
        val realized = 50.0 - 5.051 - 2.5005
        assertEquals(realized, acc.realizedPnlFee, 0.001)
    }

    @Test
    fun 账户总盈亏_含费恒等式成立() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100, time = yesterday)
        vm.sell(3.5, 100)
        vm.addStock(QuoteResult("600519", "贵州茅台", null, "sh"))
        vm.buy(2.0, 100, time = yesterday)
        vm.setCurrentPrice(2.2)
        val s = vm.state.value
        assertEquals(s.floatingPnl + s.realizedPnl, s.totalPnl, 0.0001)
    }

    @Test
    fun 零费率_含费盈亏退化为裸盈亏() {
        val vm = newVm(FeeConfig(0.0, 0.0, 0.0, 0.0))
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100)
        vm.setCurrentPrice(3.3)
        assertEquals(30.0, vm.state.value.selected!!.totalPnl, 0.0001)
    }

    @Test
    fun 更新费率_影响之后的操作() {
        val vm = newVm()
        vm.updateFeeConfig(FeeConfig(0.0, 0.0, 0.0, 0.0))
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100)
        assertEquals(0.0, vm.state.value.selected!!.holdings[0].fee, 0.0001)
        assertEquals(0.0, vm.state.value.selected!!.totalBuyFee, 0.0001)
    }

    // ---------------- 账户总览聚合 ----------------

    @Test
    fun 总览_浮盈市值已实现三口径() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100, time = yesterday)
        vm.setCurrentPrice(3.3)
        vm.addStock(QuoteResult("600519", "贵州茅台", null, "sh"))
        vm.buy(2.0, 100, time = yesterday)
        vm.sell(2.5, 50)
        vm.setCurrentPrice(2.2)
        val accounts = vm.state.value.accounts

        val float = overviewEntries(accounts, OverviewTab.FLOAT)
        assertEquals(2, float.size)
        assertEquals("平安银行", float[0].name)
        assertEquals(accounts[0].floatingPnlFee, float[0].value, 0.0001)

        val value = overviewEntries(accounts, OverviewTab.VALUE)
        assertEquals(accounts[0].marketValue, value[0].value, 0.0001)
        assertEquals(accounts[1].marketValue, value[1].value, 0.0001)

        val realized = overviewEntries(accounts, OverviewTab.REALIZED)
        assertEquals(accounts[0].realizedPnlFee, realized[0].value, 0.0001)
        assertEquals(accounts[1].realizedPnlFee, realized[1].value, 0.0001)

        val shares = overviewEntries(accounts, OverviewTab.SHARES)
        assertEquals(accounts[0].totalQty.toDouble(), shares[0].value, 0.0001)
        assertEquals(accounts[1].totalQty.toDouble(), shares[1].value, 0.0001)
    }

    @Test
    fun 持仓明细浮盈_扣该批分摊买入费() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(3.0, 100) // 费 5.003，全部持有
        vm.setCurrentPrice(3.3)
        val lp = vm.state.value.selected!!.lotPnls[0]
        // 裸浮盈 30，扣买入费 5.003 → 24.997；收益率分母含费成本 305.003
        assertEquals(24.997, lp.pnl, 0.001)
        assertEquals(24.997 / 305.003 * 100, lp.pnlPercent, 0.001)
    }

    @Test
    fun 持仓明细浮盈_部分卖出后按剩余比例分摊() {
        val vm = newVm()
        vm.addStock(QuoteResult("000001", "平安银行", null, "sz"))
        vm.buy(1.0, 100, time = yesterday) // 费 5.001
        vm.sell(2.0, 50)                   // 剩 50 股
        vm.setCurrentPrice(1.2)
        val lp = vm.state.value.selected!!.lotPnls[0]
        // 剩余 50 股分摊费 = 5.001×0.5 = 2.5005
        val expected = (1.2 - 1.0) * 50 - 2.5005
        assertEquals(expected, lp.pnl, 0.001)
    }

    @Test
    fun 总览_空账户四口径均为空() {
        assertTrue(overviewEntries(emptyList(), OverviewTab.FLOAT).isEmpty())
        assertTrue(overviewEntries(emptyList(), OverviewTab.VALUE).isEmpty())
        assertTrue(overviewEntries(emptyList(), OverviewTab.REALIZED).isEmpty())
        assertTrue(overviewEntries(emptyList(), OverviewTab.SHARES).isEmpty())
    }

    // ---------------- 历史手续费重算迁移 ----------------

    @Test
    fun 迁移_旧数据手续费为零_重放补齐批次交易费与costFee() {
        // 构造旧数据：昨买两批 + 今日卖 50 股，fee 全为 0
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            holdings = listOf(
                BuyLot(1, 1.0, 100, 50, 0L, 0.0),   // 卖剩 50
                BuyLot(2, 3.0, 100, 100, 0L, 0.0)
            ),
            trades = listOf(
                TradeRecord(1, TradeType.BUY, 1.0, 100, fee = 0.0, time = yesterday),
                TradeRecord(2, TradeType.BUY, 3.0, 100, fee = 0.0, time = yesterday),
                TradeRecord(3, TradeType.SELL, 2.0, 50, profit = 50.0, fee = 0.0)
            ),
            currentPrice = 3.0,
            totalBuyFee = 0.0
        )
        val old = StockState(accounts = listOf(acc), selectedIndex = 0)
        val migrated = recalcHistoricalFees(old, FeeConfig())
        val m = migrated.accounts[0]
        // 重放：买入建批次带费，卖出按最低价匹配 50 股 → 1 元批剩 50
        assertEquals(2, m.holdings.size)
        assertEquals(1.0, m.holdings[0].price, 0.0001)
        assertEquals(50, m.holdings[0].remainingQty)
        assertEquals(5.001, m.holdings[0].fee, 0.0001) // max(100×万2.5,5)+100×万0.1
        assertEquals(5.003, m.holdings[1].fee, 0.0001) // max(300×万2.5,5)+300×万0.1
        // 交易费补齐（买入 buyFee、卖出 sellFee）
        assertEquals(5.001, m.trades[0].fee, 0.0001)
        assertEquals(5.003, m.trades[1].fee, 0.0001)
        assertEquals(5.051, m.trades[2].fee, 0.0001) // 卖 100 元：佣金5+印花0.05+过户0.001
        // 卖出 costFee：被卖 1 元批 50 股分摊 = 5.001×0.5
        assertEquals(2.5005, m.trades[2].costFee, 0.001)
        // 累计买入费 = Σ 买入交易费（含已卖出批次）
        assertEquals(5.001 + 5.003, m.totalBuyFee, 0.0001)
        // 已实现含费
        assertEquals(50.0 - 5.051 - 2.5005, m.realizedPnlFee, 0.001)
        // 其他字段不变
        assertEquals(3.0, m.currentPrice!!, 0.0001)
        assertEquals(150, m.totalQty)
    }

    @Test
    fun 迁移_含费盈亏正确() {
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            holdings = listOf(BuyLot(1, 3.0, 100, 100, 0L, 0.0)),
            trades = listOf(TradeRecord(1, TradeType.BUY, 3.0, 100, fee = 0.0)),
            currentPrice = 3.3,
            totalBuyFee = 0.0
        )
        val migrated = recalcHistoricalFees(StockState(listOf(acc), 0), FeeConfig())
        val m = migrated.accounts[0]
        // 浮盈 = 330 - 300 - 5.003（买入费） = 24.997
        assertEquals(24.997, m.floatingPnlFee, 0.001)
        assertEquals(5.003, m.totalBuyFee, 0.0001)
        assertEquals(0.0, m.realizedPnlFee, 0.0001)
    }

    @Test
    fun 迁移_同日买入也参与匹配_按最低价抵扣() {
        // 今日买入 100 股 + 今日卖出 50 股（T+1 不再限制，同日买入也参与）
        val acc = StockAccount(
            stock = Stock("000001", "平安银行", "sz"),
            holdings = listOf(BuyLot(1, 3.0, 100, 100, 0L, 0.0)),
            trades = listOf(
                TradeRecord(1, TradeType.BUY, 3.0, 100, fee = 0.0),
                TradeRecord(2, TradeType.SELL, 3.2, 50, profit = 10.0, fee = 0.0)
            ),
            totalBuyFee = 0.0
        )
        val migrated = recalcHistoricalFees(StockState(listOf(acc), 0), FeeConfig())
        val m = migrated.accounts[0]
        // 同日买入参与匹配 → 买入费分摊进 costFee，持仓按卖出减少
        assertEquals(5.003, m.trades[0].fee, 0.0001)   // buyFee(300元) = 5 + 0.003
        assertEquals(5.0816, m.trades[1].fee, 0.0001)  // sellFee(160元) = 5 + 0.08 + 0.0016
        assertEquals(2.5015, m.trades[1].costFee, 0.001) // buyFee(300)× 50/100
        assertEquals(50, m.holdings[0].remainingQty)
    }

    @Test
    fun 净盈亏_卖出扣卖出费与被卖批次买入费_买入为零() {
        val buy = TradeRecord(1, TradeType.BUY, 3.0, 100, fee = 5.003)
        assertEquals(0.0, buy.netProfit, 0.0001)
        val sell = TradeRecord(2, TradeType.SELL, 3.5, 100, profit = 50.0, fee = 5.1785, costFee = 5.003)
        assertEquals(50.0 - 5.1785 - 5.003, sell.netProfit, 0.0001)
    }
}
