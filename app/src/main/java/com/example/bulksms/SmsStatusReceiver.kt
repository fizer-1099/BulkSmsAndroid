package com.example.bulksms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.bulksms.db.AppDatabase
import com.example.bulksms.db.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SENT = "com.example.bulksms.SMS_SENT"
        const val ACTION_DELIVERED = "com.example.bulksms.SMS_DELIVERED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val logId = intent.getLongExtra("logId", -1L)
        if (logId < 0) return
        val result = resultCode
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).sendLogDao()
                when (action) {
                    ACTION_SENT -> {
                        if (result == Activity.RESULT_OK) dao.markSent(logId, Status.SENT)
                        else dao.setStatus(logId, Status.FAIL + ": " + reason(result))
                    }
                    ACTION_DELIVERED -> {
                        if (result == Activity.RESULT_OK) dao.markDelivered(logId, Status.DELIVERED)
                        else dao.markDelivered(logId, Status.UNDELIVERED)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    private fun reason(code: Int): String = when (code) {
        1 -> "خطای عمومی (مجوز یا محدودیت اپراتور/سیستم)"
        2 -> "رادیو خاموش یا حالت پرواز"
        3 -> "قالب پیام نامعتبر"
        4 -> "نبود آنتن یا سرویس"
        5 -> "سقف ارسال پیامک سیستم"
        7, 8 -> "ارسال به این شماره مجاز نیست"
        else -> "کد خطا $code"
    }
}
