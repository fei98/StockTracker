package com.example.stocktracker

import org.json.JSONObject

data class TradePlanPreset(
    val marketCode: String,
    val name: String,
    val lowerCost: Double,
    val upperCost: Double,
    val orderAmount: Double,
    val stepPercent: Double,
    val capital: Double
) {
    val isValid: Boolean get() = marketCode.isNotBlank() && name.isNotBlank() &&
        listOf(lowerCost, upperCost, orderAmount, stepPercent, capital).all { it.isFinite() } &&
        lowerCost >= 0 && upperCost > lowerCost && orderAmount > 0 &&
        stepPercent > 0 && stepPercent < 100 && capital > 0
}

/** Parses optional suggestions without changing saved strategy parameters. */
object TradePlanPresetStore {
    fun parse(raw: String): List<TradePlanPreset> {
        val root = JSONObject(raw)
        if (root.optInt("version", 0) != 1) return emptyList()
        val entries = root.optJSONArray("presets") ?: return emptyList()
        val presets = (0 until entries.length()).mapNotNull { index ->
            runCatching {
                val item = entries.getJSONObject(index)
                TradePlanPreset(
                    marketCode = item.getString("marketCode"), name = item.getString("name"),
                    lowerCost = item.getDouble("lowerCost"), upperCost = item.getDouble("upperCost"),
                    orderAmount = item.getDouble("orderAmount"), stepPercent = item.getDouble("stepPercent"),
                    capital = item.getDouble("capital")
                ).takeIf { it.isValid }
            }.getOrNull()
        }
        require(presets.map { it.marketCode }.distinct().size == presets.size) { "Duplicate preset market code." }
        return presets
    }

    fun find(presets: List<TradePlanPreset>, marketCode: String): TradePlanPreset? =
        presets.firstOrNull { it.marketCode == marketCode }
}
