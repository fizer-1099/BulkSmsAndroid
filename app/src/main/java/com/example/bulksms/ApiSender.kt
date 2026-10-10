package com.example.bulksms

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ApiSender {
    private const val PREFS = "api_sender"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isApiMode(ctx: Context) = prefs(ctx).getString("mode", "SIM") == "API"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun jsonEsc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun fill(t: String, v: Map<String, String>, esc: (String) -> String): String {
        var r = t
        for ((k, x) in v) r = r.replace("{" + k + "}", esc(x))
        return r
    }

    private fun norm(s: String): String = s.filter { !it.isWhitespace() }

    /** Blocking call: run on a background thread. Returns (success, info). */
    fun send(ctx: Context, phone: String, message: String): Pair<Boolean, String> {
        val p = prefs(ctx)
        val urlT = (p.getString("url", "") ?: "").trim()
        if (urlT.isEmpty()) return Pair(false, "آدرس API تنظیم نشده است.")
        if (!urlT.startsWith("https://")) return Pair(false, "فقط آدرس HTTPS مجاز است.")
        val method = p.getString("method", "POST") ?: "POST"
        val bodyType = p.getString("body_type", "JSON") ?: "JSON"
        val headersT = p.getString("headers", "") ?: ""
        val bodyT = p.getString("body", "") ?: ""
        val sender = p.getString("sender", "") ?: ""
        val success = p.getString("success", "") ?: ""

        val mobile = PhoneUtil.toIranMobile(phone) ?: PhoneUtil.normalize(phone)
        val digits = if (mobile.startsWith("0")) mobile.substring(1) else mobile
        val vals = mapOf(
            "phone" to mobile,
            "phone98" to "98$digits",
            "phoneplus98" to "+98$digits",
            "message" to message,
            "sender" to sender
        )

        var conn: HttpURLConnection? = null
        return try {
            val url = fill(urlT, vals) { enc(it) }
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 20000
                requestMethod = method
                setRequestProperty("User-Agent", "BulkSms/1.0")
            }
            conn = c
            var hasContentType = false
            for (line in headersT.lines()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val value = fill(line.substring(idx + 1).trim(), vals) { it }
                    if (name.equals("content-type", ignoreCase = true)) hasContentType = true
                    c.setRequestProperty(name, value)
                }
            }
            if (method == "POST") {
                val body = when (bodyType) {
                    "JSON" -> fill(bodyT, vals) { jsonEsc(it) }
                    "FORM" -> fill(bodyT, vals) { enc(it) }
                    else -> fill(bodyT, vals) { it }
                }
                if (!hasContentType) {
                    val ct = when (bodyType) {
                        "JSON" -> "application/json; charset=utf-8"
                        "FORM" -> "application/x-www-form-urlencoded; charset=utf-8"
                        else -> "text/plain; charset=utf-8"
                    }
                    c.setRequestProperty("Content-Type", ct)
                }
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val okHttp = code in 200..299
            val kws = success.split("|").map { norm(it) }.filter { it.isNotEmpty() }
            val okBody = kws.isEmpty() || kws.any { norm(text).contains(it) }
            Pair(okHttp && okBody, "HTTP $code — ${text.trim().take(140)}")
        } catch (e: Exception) {
            Pair(false, "خطای شبکه: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}
