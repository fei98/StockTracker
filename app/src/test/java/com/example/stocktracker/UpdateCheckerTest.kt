package com.example.stocktracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖：GitHub 版本号对比（容忍 v 前缀与缺失段） */
class UpdateCheckerTest {

    @Test
    fun 版本对比_新版本判定() {
        assertTrue(UpdateChecker.isNewer("v2.10", "2.9"))
        assertTrue(UpdateChecker.isNewer("v3.0", "2.9"))
        assertTrue(UpdateChecker.isNewer("v2.9.1", "2.9"))
        assertTrue(UpdateChecker.isNewer("2.9", "2.8"))
    }

    @Test
    fun 版本对比_相同或更旧判定() {
        assertFalse(UpdateChecker.isNewer("v2.9", "2.9"))
        assertFalse(UpdateChecker.isNewer("v2.8", "2.9"))
        assertFalse(UpdateChecker.isNewer("v2.9", "2.9.1"))
        assertFalse(UpdateChecker.isNewer("v2.9", "3.0"))
    }
}
