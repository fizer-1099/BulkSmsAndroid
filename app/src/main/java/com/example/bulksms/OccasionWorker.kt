package com.example.bulksms

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.bulksms.db.AppDatabase
import com.example.bulksms.db.ContactEntity
import com.example.bulksms.db.SendLogEntity
import com.example.bulksms.db.Status
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class OccasionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private fun smsManager(subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = applicationContext.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val sp = ctx.getSharedPreferences("occasions", Context.MODE_PRIVATE)
        val force = inputData.getBoolean("force", false)
        val now = Calendar.getInstance()
        val todayKey = "%04d-%02d-%02d".format(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )
        if (!force && sp.getString("last_run", "") == todayKey) return Result.success()
        if (!ApiSender.isApiMode(ctx) && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }

        val j = Jalali.toJalali(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val db = AppDatabase.get(ctx)
        val jobs = mutableListOf<Triple<ContactEntity, String, String>>()

        try {
            val arr = JSONArray(sp.getString("list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optInt("m") == j[1] && o.optInt("d") == j[2]) {
                    val group = o.optString("group")
                    for (c in db.contactDao().eligible(group).filter { !ExtraDb.get(ctx).isBlocked(it.phone) }) {
                        jobs.add(Triple(c, o.optString("message"), group))
                    }
                }
            }
            val bd = JSONObject(sp.getString("birthdays", "{}") ?: "{}")
            if (bd.length() > 0) {
                val key = "${j[1]}/${j[2]}"
                val msg = sp.getString("birthday_msg", null) ?: "سلام {نام}، تولدت مبارک! 🎂"
                for (c in db.contactDao().eligible("").filter { !ExtraDb.get(ctx).isBlocked(it.phone) }) {
                    val p = PhoneUtil.toIranMobile(c.phone) ?: PhoneUtil.normalize(c.phone)
                    if (bd.optString(p) == key) jobs.add(Triple(c, msg, ""))
                }
            }
        } catch (e: Exception) {
            return Result.failure()
        }

        sp.edit().putString("last_run", todayKey).apply()
        if (jobs.isEmpty()) return Result.success()

        val sms = smsManager(sp.getInt("sub", -1))
        val seen = HashSet<String>()
        var sentCount = 0
        for ((c, template, group) in jobs) {
            val num = PhoneUtil.normalize(c.phone)
            val text = template.replace("{نام}", c.name.ifBlank { "دوست عزیز" })
            if (!seen.add("$num|$text")) continue
            try {
                if (ApiSender.isApiMode(applicationContext)) {
                    val r = ApiSender.send(applicationContext, num, text)
                    if (!r.first) throw Exception(r.second)
                } else {
                    val parts = sms.divideMessage(text)
                    if (parts.size > 1) sms.sendMultipartTextMessage(num, null, parts, null, null)
                    else sms.sendTextMessage(num, null, text, null, null)
                }
                db.sendLogDao().insert(SendLogEntity(phone = num, message = text, groupName = group, status = Status.SENT))
            } catch (e: Exception) {
                db.sendLogDao().insert(
                    SendLogEntity(phone = num, message = text, groupName = group, status = "ناموفق: ${e.message ?: "خطای نامشخص"}")
                )
            }
            sentCount++
            delay(4000L)
        }
        return Result.success()
    }
}
