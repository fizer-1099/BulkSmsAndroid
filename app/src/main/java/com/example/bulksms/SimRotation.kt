package com.example.bulksms

import android.content.Context
import android.telephony.SubscriptionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SimRotation {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sim_rotation", Context.MODE_PRIVATE)
    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun key(subId: Int) = "c_" + today() + "_" + subId

    fun activeSubIds(ctx: Context): List<Int> = try {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        sm.activeSubscriptionInfoList?.map { it.subscriptionId } ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun countOf(ctx: Context, subId: Int): Int = prefs(ctx).getInt(key(subId), 0)

    fun count(ctx: Context, subId: Int) {
        if (subId < 0) return
        prefs(ctx).edit().putInt(key(subId), countOf(ctx, subId) + 1).apply()
    }

    /** Next SIM (round-robin) that is still under its daily limit, or null if none. */
    fun pick(ctx: Context): Int? {
        val ids = activeSubIds(ctx)
        if (ids.isEmpty()) return null
        val limit = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("sim_limit", 0)
        val last = prefs(ctx).getInt("last", -1)
        val startIdx = (ids.indexOf(last) + 1) % ids.size
        for (k in ids.indices) {
            val id = ids[(startIdx + k) % ids.size]
            if (limit <= 0 || countOf(ctx, id) < limit) {
                prefs(ctx).edit().putInt("last", id).apply()
                return id
            }
        }
        return null
    }

    fun status(ctx: Context): String = try {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val list = sm.activeSubscriptionInfoList ?: emptyList()
        if (list.isEmpty()) "سیم‌کارت فعالی پیدا نشد."
        else list.joinToString("\n") { "${it.displayName}: ${countOf(ctx, it.subscriptionId)} پیام" }
    } catch (e: SecurityException) {
        "مجوز وضعیت گوشی داده نشده است."
    } catch (e: Exception) {
        "خطا: ${e.message}"
    }
}
