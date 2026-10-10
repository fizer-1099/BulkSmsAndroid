package com.example.bulksms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.bulksms.db.AppDatabase
import com.example.bulksms.db.QueueItemEntity
import com.example.bulksms.db.SendLogEntity
import com.example.bulksms.db.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

class SendService : Service() {
    companion object {
        const val ACTION_START = "com.example.bulksms.START"
        const val ACTION_STOP = "com.example.bulksms.STOP"
        private const val CHANNEL = "send_channel"
        private const val NOTIF_ID = 1001
        private const val DONE_ID = 1002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var finalText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finalText = "ارسال متوقف شد. از تب «ارسال» می‌توانید ادامه دهید."
            if (job?.isActive == true) job?.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("در حال آماده‌سازی…"), type)
        if (job?.isActive != true) {
            acquireWakeLock()
            job = scope.launch {
                try {
                    runQueue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    finalText = "خطا در ارسال: ${e.message}"
                } finally {
                    releaseWakeLock()
                    finish()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        finalText = "زمان مجاز سرویس تمام شد. از تب «ارسال» ادامه دهید."
        job?.cancel()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        val db = AppDatabase.get(applicationContext)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        while (currentCoroutineContext().isActive) {
            val item = db.queueDao().nextPending()
            if (item == null) {
                val done = db.queueDao().doneCount()
                finalText = "✅ ارسال تمام شد: $done پیام"
                break
            }
            if (ExtraDb.get(applicationContext).isBlocked(item.phone)) {
                db.queueDao().setState(item.id, "DONE")
                continue
            }
            val limit = prefs.getInt("daily_limit", 200)
            if (limit > 0 && db.sendLogDao().countSince(startOfDay()) >= limit) {
                finalText = "سقف ارسال روزانه ($limit) پر شد. ارسال متوقف شد؛ بعداً از تب «ارسال» ادامه دهید."
                break
            }
            val hf = prefs.getInt("hour_from", 0)
            val ht = prefs.getInt("hour_to", 24)
            val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hf < ht && (hr < hf || hr >= ht)) {
                finalText = "خارج از ساعت مجاز ارسال ($hf تا $ht). بعداً از تب «ارسال» ادامه دهید."
                break
            }
            var useSub = item.subscriptionId
            val rotate = !ApiSender.isApiMode(applicationContext) && prefs.getString("sim_mode", "SINGLE") == "ROTATE"
            if (rotate) {
                val pick = SimRotation.pick(applicationContext)
                if (pick == null) {
                    finalText = "همه سیم‌کارت‌ها به سقف روزانه رسیدند (یا سیم‌کارتی پیدا نشد). بعداً ادامه دهید."
                    break
                }
                useSub = pick
            }
            sendOne(db, item, useSub)
            if (rotate) SimRotation.count(applicationContext, useSub)
            db.queueDao().setState(item.id, "DONE")
            val left = db.queueDao().pendingCount()
            val done = db.queueDao().doneCount()
            notifyText("ارسال‌شده: $done | باقی‌مانده: $left")
            if (left > 0) {
                val base = prefs.getInt("interval", 3).coerceAtLeast(0)
                val jitter = prefs.getInt("jitter", 3).coerceAtLeast(0)
                val wait = base + (if (jitter > 0) Random.nextInt(jitter + 1) else 0)
                if (wait > 0) delay(wait * 1000L)
            }
        }
    }

    private suspend fun sendOne(db: AppDatabase, item: QueueItemEntity, subId: Int = item.subscriptionId) {
        val num = PhoneUtil.normalize(item.phone)
        val logId = db.sendLogDao().insert(
            SendLogEntity(phone = num, message = item.message, groupName = item.groupName, status = Status.SENDING)
        )
        val cid = getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("active_campaign", 0L)
        if (cid > 0L) {
            try { ExtraDb.get(applicationContext).addLog(cid, logId) } catch (_: Exception) { }
        }
        if (ApiSender.isApiMode(applicationContext)) {
            val r = ApiSender.send(applicationContext, num, item.message)
            db.sendLogDao().setStatus(logId, if (r.first) Status.SENT else Status.FAIL + ": " + r.second)
            return
        }
        try {
            val sms = smsManager(subId)
            val parts = sms.divideMessage(item.message)
            if (parts.size > 1) {
                val sentList = ArrayList<PendingIntent>()
                val delList = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    sentList.add(pi(SmsStatusReceiver.ACTION_SENT, logId, i))
                    delList.add(pi(SmsStatusReceiver.ACTION_DELIVERED, logId, i))
                }
                sms.sendMultipartTextMessage(num, null, parts, sentList, delList)
            } else {
                sms.sendTextMessage(
                    num, null, item.message,
                    pi(SmsStatusReceiver.ACTION_SENT, logId, 0),
                    pi(SmsStatusReceiver.ACTION_DELIVERED, logId, 0)
                )
            }
        } catch (e: Exception) {
            db.sendLogDao().setStatus(logId, Status.FAIL + ": " + (e.message ?: "خطای نامشخص"))
        }
    }

    private fun pi(action: String, logId: Long, part: Int): PendingIntent {
        val i = Intent(applicationContext, SmsStatusReceiver::class.java)
            .setAction(action).putExtra("logId", logId)
        val code = (logId.toInt() * 31 + part) * 2 + (if (action == SmsStatusReceiver.ACTION_SENT) 0 else 1)
        return PendingIntent.getBroadcast(
            applicationContext, code, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun smsManager(subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private fun startOfDay(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ارسال پیامک", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SendService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("پیامک‌یار")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "توقف", stop)
            .build()
    }

    private fun notifyText(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bulksms:send").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val text = finalText
        if (text != null) {
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("پیامک‌یار")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                )
                .build()
            getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
        }
        stopSelf()
    }
}
