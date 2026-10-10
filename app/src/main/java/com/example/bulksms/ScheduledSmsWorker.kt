package com.example.bulksms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.bulksms.db.AppDatabase
import com.example.bulksms.db.ScheduleEntity
import com.example.bulksms.db.SendLogEntity
import com.example.bulksms.db.Status
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ScheduledSmsWorker(
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
        val db = AppDatabase.get(applicationContext)
        val group = inputData.getString("group") ?: ""
        val message = inputData.getString("message") ?: ""
        val interval = inputData.getInt("interval", 3).coerceAtLeast(0)
        val subscriptionId = inputData.getInt("subscriptionId", -1)
        val scheduleId = inputData.getLong("scheduleId", -1L)

        val sched = if (scheduleId >= 0) db.scheduleDao().find(scheduleId) else null
        if (sched != null && sched.status == "CANCELLED") return Result.success()

        return try {
            val contacts = db.contactDao().eligible(group).filter { !ExtraDb.get(applicationContext).isBlocked(it.phone) }
            val sms = smsManager(subscriptionId)

            for ((index, contact) in contacts.withIndex()) {
                val num = PhoneUtil.normalize(contact.phone)
                val text = message.replace("{نام}", contact.name.ifBlank { "دوست عزیز" })
                try {
                    if (ApiSender.isApiMode(applicationContext)) {
                        val r = ApiSender.send(applicationContext, num, text)
                        if (!r.first) throw Exception(r.second)
                    } else {
                        val parts = sms.divideMessage(text)
                        if (parts.size > 1) sms.sendMultipartTextMessage(num, null, parts, null, null)
                        else sms.sendTextMessage(num, null, text, null, null)
                    }
                    db.sendLogDao().insert(
                        SendLogEntity(phone = num, message = text, groupName = group, status = Status.SENT)
                    )
                } catch (e: Exception) {
                    db.sendLogDao().insert(
                        SendLogEntity(
                            phone = num, message = text, groupName = group,
                            status = "ناموفق: ${e.message ?: "خطای نامشخص"}"
                        )
                    )
                }
                if (index < contacts.lastIndex && interval > 0) {
                    delay(interval * 1000L)
                }
            }

            val repeat = applicationContext.getSharedPreferences("repeat", Context.MODE_PRIVATE)
                .getString("repeat_$scheduleId", "NONE") ?: "NONE"
            if (sched != null && repeat != "NONE") {
                reschedule(db, sched, repeat)
            } else if (scheduleId >= 0) {
                db.scheduleDao().setStatus(scheduleId, "COMPLETED")
            }
            Result.success()
        } catch (e: Exception) {
            if (scheduleId >= 0) db.scheduleDao().setStatus(scheduleId, "FAILED")
            Result.failure()
        }
    }

    private suspend fun reschedule(db: AppDatabase, s: ScheduleEntity, repeat: String) {
        val cal = Calendar.getInstance().apply { timeInMillis = s.scheduledAt }
        val now = System.currentTimeMillis()
        do {
            when (repeat) {
                "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                else -> cal.add(Calendar.MONTH, 1)
            }
        } while (cal.timeInMillis <= now)
        val req = OneTimeWorkRequestBuilder<ScheduledSmsWorker>()
            .setInputData(inputData)
            .setInitialDelay(cal.timeInMillis - now, TimeUnit.MILLISECONDS)
            .build()
        db.scheduleDao().update(
            s.copy(scheduledAt = cal.timeInMillis, workRequestId = req.id.toString(), status = "SCHEDULED")
        )
        WorkManager.getInstance(applicationContext).enqueue(req)
    }
}
