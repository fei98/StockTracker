package com.example.stocktracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 覆盖：盘中信号通知正文拼接（含原因截取与无原因空串） */
class NotificationTest {

    @Test
    fun 盘中正文_带原因() {
        val body = PredictionNotifier.intradayBody(3.2, listOf("30分钟动量上行", "站上均价"))
        assertEquals("评分 +3.2 · 30分钟动量上行；站上均价", body)
    }

    @Test
    fun 盘中正文_只取前两条原因() {
        val reasons = listOf("动量", "放量", "板块强势")
        assertEquals("评分 +1.0 · 动量；放量", PredictionNotifier.intradayBody(1.0, reasons))
    }

    @Test
    fun 盘中正文_无原因() {
        assertEquals("评分 -2.5", PredictionNotifier.intradayBody(-2.5, emptyList()))
    }
}