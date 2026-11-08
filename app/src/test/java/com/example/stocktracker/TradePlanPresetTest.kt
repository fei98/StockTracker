package com.example.stocktracker

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TradePlanPresetTest {
    private fun entry(code: String = "sz123456", lower: Double = 1000.0) = """
        {"marketCode":"$code","name":"Example","lowerCost":$lower,"upperCost":12000,
         "orderAmount":2300,"stepPercent":2.5,"capital":20000}
    """.trimIndent()

    @Test fun bundledRecommendationOnlyAppliesToItsExactMarketCode() {
        val path = listOf("src/main/res/raw/trade_plan_presets.json", "app/src/main/res/raw/trade_plan_presets.json")
            .map(::File).first { it.isFile }
        val presets = TradePlanPresetStore.parse(path.readText(Charsets.UTF_8))
        val recommended = TradePlanPresetStore.find(presets, "sz002396")!!
        assertEquals(10000.0, recommended.lowerCost, 0.0)
        assertEquals(30000.0, recommended.upperCost, 0.0)
        assertEquals(8000.0, recommended.orderAmount, 0.0)
        assertEquals(8.0, recommended.stepPercent, 0.0)
        assertEquals(50000.0, recommended.capital, 0.0)
        assertNull(TradePlanPresetStore.find(presets, "sh002396"))
        assertNull(TradePlanPresetStore.find(presets, "sz159915"))
    }

    @Test fun genericParserAllowsDifferentStocksAndParameterValues() {
        val presets = TradePlanPresetStore.parse("""{"version":1,"presets":[${entry()}]}""")
        val result = TradePlanPresetStore.find(presets, "sz123456")!!
        assertEquals(2300.0, result.orderAmount, 0.0)
        assertEquals(2.5, result.stepPercent, 0.0)
        assertEquals(1000.0, result.lowerCost, 0.0)
    }

    @Test fun malformedEntryDoesNotHideOtherValidSuggestions() {
        val presets = TradePlanPresetStore.parse("""{"version":1,"presets":[{},${entry()}]}""")
        assertEquals(1, presets.size)
        assertEquals("sz123456", presets.single().marketCode)
    }

    @Test fun invalidBoundsDoNotBecomeSuggestions() {
        assertTrue(TradePlanPresetStore.parse("""{"version":1,"presets":[${entry(lower = -1.0)}]}""").isEmpty())
    }

    @Test fun futureSchemaIsNotGuessed() {
        assertTrue(TradePlanPresetStore.parse("""{"version":2,"presets":[${entry()}]}""").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateMarketCodesAreRejected() {
        TradePlanPresetStore.parse("""{"version":1,"presets":[${entry()},${entry()}]}""")
    }

    @Test fun readingRecommendationDoesNotMutateIndependentSettings() {
        val presets = TradePlanPresetStore.parse("""{"version":1,"presets":[${entry()}]}""")
        val modified = presets.single().copy(orderAmount = 3700.0, stepPercent = 6.0)
        assertEquals(3700.0, modified.orderAmount, 0.0)
        assertEquals(2300.0, presets.single().orderAmount, 0.0)
    }
}
