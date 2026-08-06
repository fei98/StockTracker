package com.example.stocktracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** GitHub 最新 Release 信息 */
data class UpdateInfo(
    val latestTag: String,
    val apkUrl: String?,
    val notes: String
)

/** 检查更新：读取 GitHub Releases API，与本地版本对比 */
object UpdateChecker {

    private const val API_URL = "https://api.github.com/repos/fei98/StockTracker/releases/latest"

    /** 拉取最新 Release；失败返回 null */
    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient()
            val req = Request.Builder().url(API_URL).header("Accept", "application/vnd.github+json").build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return@runCatching null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) return@runCatching null
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url").takeIf { it.isNotEmpty() }
                        break
                    }
                }
            }
            UpdateInfo(tag, apkUrl, json.optString("body"))
        }.getOrNull()
    }

    /** 远程版本号是否比本地新（逐段数字比较，容忍 v 前缀与缺失段） */
    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        val l = latestTag.trim().trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
        val c = currentVersion.trim().split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
