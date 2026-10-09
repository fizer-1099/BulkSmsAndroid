package com.example.bulksms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.bulksms.db.AppDatabase
import com.example.bulksms.db.SendLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(context)
                for (m in msgs) {
                    val body = PhoneUtil.asciiDigits(m.messageBody ?: "")
                        .replace(Regex("\\s+"), "").lowercase()
                    val isStop = body == "لغو" || body == "لغو11" || body == "stop" ||
                        body == "unsubscribe" || body == "cancel"
                    if (!isStop) continue
                    val mobile = PhoneUtil.toIranMobile(m.originatingAddress ?: "") ?: continue
                    val n = db.contactDao().optOutMany(PhoneUtil.variants(mobile))
                    if (n > 0) {
                        db.sendLogDao().insert(
                            SendLogEntity(
                                phone = mobile,
                                message = m.messageBody ?: "",
                                status = "لغو دریافت توسط مخاطب"
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
