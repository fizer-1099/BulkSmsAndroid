package com.example.bulksms

import android.content.Context
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.bulksms.db.AppDatabase
import com.example.bulksms.db.SendLogEntity
import kotlinx.coroutines.delay

class ScheduledSmsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val group = inputData.getString("group") ?: ""
        val message = inputData.getString("message") ?: ""
        val interval = inputData.getInt("interval", 3).coerceAtLeast(0)
        val subscriptionId = inputData.getInt("subscriptionId", -1)
        val scheduleId = inputData.getLong("scheduleId", -1L)

        return try {
            val contacts = db.contactDao().eligible(group)
            val sms = if (subscriptionId >= 0) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }

            for ((index, contact) in contacts.withIndex()) {
                try {
                    sms.sendTextMessage(contact.phone, null, message, null, null)
                    db.sendLogDao().insert(
                        SendLogEntity(
                            phone = contact.phone,
                            message = message,
                            groupName = group,
                            status = "موفق"
                        )
                    )
                } catch (e: Exception) {
                    db.sendLogDao().insert(
                        SendLogEntity(
                            phone = contact.phone,
                            message = message,
                            groupName = group,
                            status = "ناموفق: ${e.message ?: "خطای نامشخص"}"
                        )
                    )
                }
                if (index < contacts.lastIndex && interval > 0) {
                    delay(interval * 1000L)
                }
            }
            if (scheduleId >= 0) db.scheduleDao().setStatus(scheduleId, "COMPLETED")
            Result.success()
        } catch (e: Exception) {
            if (scheduleId >= 0) db.scheduleDao().setStatus(scheduleId, "FAILED")
            Result.failure()
        }
    }
}
