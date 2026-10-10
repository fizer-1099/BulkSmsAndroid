package com.example.bulksms

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    const val DEFAULT_REPO = "fizer-1099/BulkSmsAndroid"

    data class Info(val tag: String, val notes: String, val apkUrl: String?, val pageUrl: String)

    /** Blocking. Returns (info, error). */
    fun fetchLatest(repo: String): Pair<Info?, String?> {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "BulkSms-Updater")
            val code = c.responseCode
            if (code == 404) return Pair(null, "ریلیزی پیدا نشد (هنوز منتشر نشده یا ریپو خصوصی است).")
            if (code !in 200..299) return Pair(null, "خطای GitHub: HTTP $code")
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val j = JSONObject(text)
            var apk: String? = null
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val u = assets.getJSONObject(i).optString("browser_download_url")
                    if (u.startsWith("https://") && u.endsWith(".apk")) { apk = u; break }
                }
            }
            val page = j.optString("html_url")
            Pair(Info(j.optString("tag_name"), j.optString("body"), apk, if (page.startsWith("https://")) page else "https://github.com/$repo/releases"), null)
        } catch (e: Exception) {
            Pair(null, "خطای شبکه: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split(".")
            .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
