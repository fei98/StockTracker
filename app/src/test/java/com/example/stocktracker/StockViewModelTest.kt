package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖：买入、均价、最低价优先卖出抵扣、卖出盈亏、现价浮动盈亏、清空及各类边界条件
 */
class StockViewModelTest {

    private fun newVm() = StockViewModel()

    /** 连续多次买入，返回 vm */
    private fun vmWithBuys(vararg buys: Pair<Double, Int>): StockViewModel {
        val vm = newVm()
        buys.forEach { (p, q) -> vm.buy(p, q) }
        return vm
    }

    // ---------------- 买入 ----------------

    @Test
    fun 买入_创建批次并正确计算成本均价() {
        val vm = newVm()
        vm.buy(5.0, 200)
        val s = vm.state.value
        assertEquals(1, s.holdings.size)
        val lot = s.holdings[0]
        assertEquals(5.0, lot.price, 0.0001)
        assertEquals(200, lot.remainingQty)
        assertEquals(200, lot.originalQty)
        assertEquals(200, s.totalQty)
        assertEquals(1000.0, s.totalCost, 0.0001)
        assertEquals(5.0, s.avgPrice, 0.0001)
        assertEquals(1, s.trades.size)
        assertEquals(TradeType.BUY, s.trades[0].type)
        assertTrue(s.message!!.contains("买入成功"))
    }

    @Test
    fun 买入_多次不同价_均价等于总成本除总数量() {
        val vm = vmWithBuys(5.0 to 200, 3.0 to 300)
        val s = vm.state.value
        assertEquals(500, s.totalQty)
        assertEquals(1900.0, s.totalCost, 0.0001)
        assertEquals(3.8, s.avgPrice, 0.0001)
        assertEquals(2, s.holdings.size)
    }

    @Test
    fun 买入_小数价格_成本精确() {
        val vm = vmWithBuys(3.41 to 2900)
        val s = vm.state.value
        assertEquals(9889.0, s.totalCost, 0.0001)
        assertEquals(3.41, s.avgPrice, 0.0001)
    }

    @Test
    fun 买入_边界值极小_零_负数_均被拒绝() {
        val vm = newVm()
        vm.buy(0.0, 100)
        vm.buy(-1.0, 100)
        vm.buy(3.0, 0)
        vm.buy(3.0, -5)
        assertEquals(0, vm.state.value.holdings.size)
        assertEquals(0, vm.state.value.trades.size)
        assertTrue(vm.state.value.message!!.contains("正确"))
    }

    @Test
    fun 买入_超大数量与价格_正常() {
        val vm = vmWithBuys(100000.5 to 1000000)
        val s = vm.state.value
        assertEquals(1000000, s.totalQty)
        assertEquals(100000.5, s.avgPrice, 0.001)
        assertEquals(100000500000.0, s.totalCost, 1.0)
    }

    // ---------------- 卖出匹配（最低价优先） ----------------

    @Test
    fun 卖出_用户示例_最低价优先抵扣_剩3元100股_盈利500() {
        val vm = vmWithBuys(5.0 to 200, 3.0 to 300, 1.0 to 100)
        vm.sell(4.0, 300)
        val s = vm.state.value
        // 1元100股全扣、3元扣200股，3元剩100股；5元200股不动
        val byPrice = s.holdings.sortedBy { it.price }
        assertEquals(2, byPrice.size)
        assertEquals(3.0, byPrice[0].price, 0.0001)
        assertEquals(100, byPrice[0].remainingQty)
        assertEquals(5.0, byPrice[1].price, 0.0001)
        assertEquals(200, byPrice[1].remainingQty)
        assertEquals(300, s.totalQty)
        // 均价 = (3*100 + 5*200) / 300
        assertEquals(4.3333, s.avgPrice, 0.001)
        // 本次盈利 = 1元100股赚(4-1)*100 + 3元200股赚(4-3)*200 = 300 + 200
        val sell = s.trades.last()
        assertEquals(TradeType.SELL, sell.type)
        assertEquals(500.0, sell.profit, 0.0001)
        assertEquals(300, sell.qty)
        assertTrue(s.message!!.contains("本次盈利"))
    }

    @Test
    fun 卖出_跨多个批次_含亏损批次_盈亏合并() {
        val vm = vmWithBuys(1.0 to 50, 2.0 to 50, 3.0 to 100)
        vm.sell(2.5, 120)
        val s = vm.state.value
        // 抵1元50(+75) → 抵2元50(+25) → 抵3元20(-10) = +90
        assertEquals(90.0, s.trades.last().profit, 0.0001)
        val left = s.holdings.sortedBy { it.price }
        assertEquals(1, left.size)
        assertEquals(3.0, left[0].price, 0.0001)
        assertEquals(80, left[0].remainingQty)
    }

    @Test
    fun 卖出_恰好全部卖出_持仓清空均价归零() {
        val vm = vmWithBuys(3.0 to 100, 4.0 to 100)
        vm.sell(5.0, 200)
        val s = vm.state.value
        assertTrue(s.holdings.isEmpty())
        assertEquals(0, s.totalQty)
        assertEquals(0.0, s.totalCost, 0.0001)
        assertEquals(0.0, s.avgPrice, 0.0001)
        // 盈利 = 100*(5-3) + 100*(5-4) = 300
        assertEquals(300.0, s.trades.last().profit, 0.0001)
    }

    @Test
    fun 卖出_部分抵扣单个批次() {
        val vm = vmWithBuys(4.0 to 500)
        vm.sell(5.0, 300)
        val s = vm.state.value
        assertEquals(1, s.holdings.size)
        assertEquals(200, s.holdings[0].remainingQty)
        assertEquals(500, s.holdings[0].originalQty)
        assertEquals(300.0, s.trades.last().profit, 0.0001)
        assertEquals(4.0, s.avgPrice, 0.0001)
    }

    @Test
    fun 卖出_亏损时盈利为负() {
        val vm = vmWithBuys(5.0 to 100)
        vm.sell(3.0, 100)
        assertEquals(-200.0, vm.state.value.trades.last().profit, 0.0001)
        assertTrue(vm.state.value.holdings.isEmpty())
    }

    @Test
    fun 卖出_平价卖出_盈利为零() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.0, 100)
        assertEquals(0.0, vm.state.value.trades.last().profit, 0.0001)
    }

    @Test
    fun 卖出_同价多个批次_合并抵扣正确() {
        val vm = vmWithBuys(3.0 to 100, 3.0 to 200)
        vm.sell(3.5, 150)
        val s = vm.state.value
        assertEquals(75.0, s.trades.last().profit, 0.0001)
        assertEquals(150, s.totalQty)
        assertEquals(1, s.holdings.size)
        assertEquals(150, s.holdings[0].remainingQty)
    }

    @Test
    fun 卖出_多笔依次操作_批次不断演算正确() {
        val vm = vmWithBuys(5.0 to 200, 3.0 to 300, 1.0 to 100)
        vm.sell(4.0, 300)   // 剩 3元100 + 5元200
        vm.sell(4.0, 250)   // 抵3元100(+100) + 5元150(-150) = -50
        val s = vm.state.value
        assertEquals(-50.0, s.trades.last().profit, 0.0001)
        assertEquals(50, s.totalQty)
        assertEquals(5.0, s.holdings[0].price, 0.0001)
        assertEquals(50, s.holdings[0].remainingQty)
        assertEquals(5.0, s.avgPrice, 0.0001)
    }

    // ---------------- 卖出边界 ----------------

    @Test
    fun 卖出_无持仓_被拒绝() {
        val vm = newVm()
        vm.sell(4.0, 100)
        assertTrue(vm.state.value.holdings.isEmpty())
        assertTrue(vm.state.value.message!!.contains("无持仓"))
    }

    @Test
    fun 卖出_数量超过持仓_被拒绝且数据不变() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(4.0, 101)
        val s = vm.state.value
        assertEquals(100, s.totalQty)
        assertEquals(1, s.holdings.size)
        assertEquals(1, s.trades.size)
        assertEquals(TradeType.BUY, s.trades[0].type)
        assertTrue(s.message!!.contains("超过持仓"))
    }

    @Test
    fun 卖出_参数非法_零_负数_被拒绝() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(0.0, 100)
        vm.sell(-2.0, 100)
        vm.sell(3.0, 0)
        vm.sell(3.0, -10)
        val s = vm.state.value
        assertEquals(1, s.holdings.size)
        assertEquals(100, s.totalQty)
        assertEquals(1, s.trades.size)
    }

    @Test
    fun 卖出_小数价格_盈亏精确() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.333, 100)
        assertEquals(33.3, vm.state.value.trades.last().profit, 0.0001)
    }

    // ---------------- 现价浮动盈亏 ----------------

    @Test
    fun 现价_各批次盈亏按价差乘以剩余数量() {
        val vm = vmWithBuys(1.0 to 100, 3.0 to 300, 5.0 to 200)
        vm.setCurrentPrice(2.0)
        val s = vm.state.value
        val pnl = s.lotPnls.associateBy { it.lot.price }
        assertEquals(100.0, pnl[1.0]!!.pnl, 0.0001)    // (2-1)*100
        assertEquals(-300.0, pnl[3.0]!!.pnl, 0.0001)   // (2-3)*300
        assertEquals(-600.0, pnl[5.0]!!.pnl, 0.0001)   // (2-5)*200
        assertEquals(100.0, pnl[1.0]!!.pnlPercent, 0.001)
        assertEquals(-33.3333, pnl[3.0]!!.pnlPercent, 0.001)
        assertEquals(-60.0, pnl[5.0]!!.pnlPercent, 0.001)
        // 总市值 2*600=1200，总成本 100+900+1000=2000，总盈亏 -800
        assertEquals(1200.0, s.marketValue, 0.0001)
        assertEquals(2000.0, s.totalCost, 0.0001)
        assertEquals(-800.0, s.totalPnl, 0.0001)
    }

    @Test
    fun 现价_等于持仓均价时_总盈亏为零() {
        val vm = vmWithBuys(3.41 to 2900)
        vm.setCurrentPrice(3.41)
        assertEquals(0.0, vm.state.value.totalPnl, 0.0001)
    }

    @Test
    fun 现价_零_合法_全部亏损() {
        val vm = vmWithBuys(3.0 to 100)
        vm.setCurrentPrice(0.0)
        assertEquals(-300.0, vm.state.value.totalPnl, 0.0001)
        assertEquals(0.0, vm.state.value.marketValue, 0.0001)
    }

    @Test
    fun 现价_负数_被拒绝() {
        val vm = vmWithBuys(3.0 to 100)
        vm.setCurrentPrice(-1.0)
        assertTrue(vm.state.value.currentPrice == null)
        assertTrue(vm.state.value.message!!.contains("不能为负"))
    }

    @Test
    fun 现价_更新与清除() {
        val vm = vmWithBuys(3.0 to 100)
        vm.setCurrentPrice(4.0)
        assertEquals(4.0, vm.state.value.currentPrice!!, 0.0001)
        vm.setCurrentPrice(null)
        assertTrue(vm.state.value.currentPrice == null)
        assertTrue(vm.state.value.message!!.contains("清除"))
    }

    @Test
    fun 现价_卖出部分后_浮动盈亏按剩余数量计算() {
        val vm = vmWithBuys(1.0 to 100, 3.0 to 300)
        vm.sell(2.0, 150)            // 抵1元100 + 3元50，剩3元250
        vm.setCurrentPrice(3.0)
        val s = vm.state.value
        val pnl = s.lotPnls.associateBy { it.lot.price }
        assertEquals(1, pnl.size)
        assertEquals(0.0, pnl[3.0]!!.pnl, 0.0001)  // (3-3)*250 = 0
        assertEquals(750.0, s.marketValue, 0.0001) // 3*250
        assertEquals(750.0, s.totalCost, 0.0001)
        assertEquals(0.0, s.totalPnl, 0.0001)
    }

    // ---------------- 交易记录 ----------------

    @Test
    fun 交易记录_买卖均入历史_卖出带盈亏() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.5, 100)
        val trades = vm.state.value.trades
        assertEquals(2, trades.size)
        assertEquals(TradeType.BUY, trades[0].type)
        assertEquals(TradeType.SELL, trades[1].type)
        assertEquals(50.0, trades[1].profit, 0.0001)
    }

    // ---------------- 清空 ----------------

    @Test
    fun 清空_清掉持仓_记录_现价() {
        val vm = vmWithBuys(1.0 to 100, 3.0 to 300)
        vm.sell(2.0, 50)
        vm.setCurrentPrice(2.5)
        vm.clearAll()
        val s = vm.state.value
        assertTrue(s.holdings.isEmpty())
        assertTrue(s.trades.isEmpty())
        assertTrue(s.currentPrice == null)
        assertEquals(0, s.totalQty)
        assertEquals(0.0, s.avgPrice, 0.0001)
        assertTrue(s.message!!.contains("已清空"))
    }

    @Test
    fun 清空_空数据时调用_不崩溃() {
        val vm = newVm()
        vm.clearAll()
        assertTrue(vm.state.value.holdings.isEmpty())
    }

    // ---------------- 其他 ----------------

    @Test
    fun 卖完再买_新批次均价正确() {
        val vm = vmWithBuys(3.0 to 100)
        vm.sell(3.5, 100)
        vm.buy(2.0, 50)
        val s = vm.state.value
        assertEquals(1, s.holdings.size)
        assertEquals(2.0, s.avgPrice, 0.0001)
        assertEquals(50, s.totalQty)
    }

    @Test
    fun 消息_会随下次操作被覆盖() {
        val vm = newVm()
        vm.buy(3.0, 100)
        vm.buy(2.0, 100)
        assertTrue(vm.state.value.message!!.contains("2.00"))
    }
}
