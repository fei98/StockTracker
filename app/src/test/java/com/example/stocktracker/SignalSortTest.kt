package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：全部持仓盘中信号按"最需要操作优先"排序 */
class SignalSortTest {

    private val R_FREEZE = "今日买入 T+1 冻结，当日无法卖出"

    private fun h(
        name: String,
        action: IntradayAction? = null,
        pending: String? = null,
        score: Double = 0.0,
        reasons: List<String> = listOf("示例原因"),
        hasPosition: Boolean = true,
        canSell: Boolean = true
    ) = PredictionViewModel.HoldingSignal(
        stock = Stock("X1", name, "sz"),
        signal = if (action == null && pending == null) null
        else IntradaySignal(action ?: IntradayAction.NO_TRADE, score, reasons, pending = pending),
        prevClose = null,
        hasPosition = hasPosition,
        canSell = canSell
    )

    @Test
    fun 买入卖出并列最前_持有次之_观望再后_不交易与收集与失败最后() {
        val rows = listOf(
            h("观", IntradayAction.WATCH),
            h("持", IntradayAction.HOLD),
            h("收集中", pending = "数据积累中"),
            h("买", IntradayAction.BUY),
            h("失败"),
            h("卖", IntradayAction.SELL)
        )
        val sorted = sortHoldingSignals(rows).map { it.stock.name }
        assertEquals(listOf("买", "卖", "持", "观", "收集中", "失败"), sorted)
    }

    @Test
    fun 同优先级内按分数降序() {
        val rows = listOf(
            h("持有低", IntradayAction.HOLD, score = 2.0),
            h("持有高", IntradayAction.HOLD, score = 8.4),
            h("买入低", IntradayAction.BUY, score = 1.0),
            h("买入高", IntradayAction.BUY, score = 3.0)
        )
        val sorted = sortHoldingSignals(rows).map { it.stock.name }
        // 买入组(priority 0)在前且组内高分在前；持有组(1)随后同样高分在前
        assertEquals(listOf("买入高", "买入低", "持有高", "持有低"), sorted)
    }

    @Test
    fun 空列表() {
        assertEquals(emptyList<PredictionViewModel.HoldingSignal>(), sortHoldingSignals(emptyList()))
    }

    @Test
    fun 冻结卖出无可卖股_视为SELL最前且可识别() {
        val rows = listOf(
            h("持A", IntradayAction.HOLD, score = 7.0, reasons = listOf("示例原因")),
            // 原为 SELL 但因 T+1 冻结降级为 HOLD、无可卖股：应提到 SELL 同档（最前）
            h("冻B", IntradayAction.HOLD, score = 3.0, reasons = listOf(R_FREEZE), canSell = false)
        )
        val sorted = sortHoldingSignals(rows)
        assertEquals(listOf("冻B", "持A"), sorted.map { it.stock.name })
        assertTrue(isFreezeBarredSell(sorted.first()))
        assertFalse(isFreezeBarredSell(sorted.last()))
    }
}