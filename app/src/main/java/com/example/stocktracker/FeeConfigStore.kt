package com.example.stocktracker

import android.content.Context

/** 交易费率设置持久化 */
class FeeConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fee_prefs", Context.MODE_PRIVATE)

    fun load(): FeeConfig = FeeConfig(
        commissionRate = prefs.getFloat(KEY_COMMISSION, FeeConfig().commissionRate.toFloat()).toDouble(),
        minCommission = prefs.getFloat(KEY_MIN_COMMISSION, FeeConfig().minCommission.toFloat()).toDouble(),
        stampTaxRate = prefs.getFloat(KEY_STAMP, FeeConfig().stampTaxRate.toFloat()).toDouble(),
        transferRate = prefs.getFloat(KEY_TRANSFER, FeeConfig().transferRate.toFloat()).toDouble()
    )

    fun save(c: FeeConfig) {
        prefs.edit()
            .putFloat(KEY_COMMISSION, c.commissionRate.toFloat())
            .putFloat(KEY_MIN_COMMISSION, c.minCommission.toFloat())
            .putFloat(KEY_STAMP, c.stampTaxRate.toFloat())
            .putFloat(KEY_TRANSFER, c.transferRate.toFloat())
            .apply()
    }

    /** 是否需要对历史交易做一次性手续费重算迁移（V2：补 costFee） */
    fun needsFeeMigration(): Boolean = !prefs.getBoolean(KEY_FEE_MIGRATED, false)

    fun markFeeMigrated() {
        prefs.edit().putBoolean(KEY_FEE_MIGRATED, true).apply()
    }

    private companion object {
        const val KEY_COMMISSION = "commissionRate"
        const val KEY_MIN_COMMISSION = "minCommission"
        const val KEY_STAMP = "stampTaxRate"
        const val KEY_TRANSFER = "transferRate"
        const val KEY_FEE_MIGRATED = "feeMigratedV2" // V2：重放补 costFee
    }
}
