import pathlib
root = pathlib.Path(".")
J = root / "app/src/main/java/com/example/bulksms"
out = {}

def load(path):
    return path.read_text()

# ---------- Manifest ----------
mf = root / "app/src/main/AndroidManifest.xml"
m = load(mf)
for perm in ["RECEIVE_SMS", "READ_CONTACTS"]:
    if f'android.permission.{perm}"' not in m:
        assert "<application" in m
        m = m.replace("<application", f'<uses-permission android:name="android.permission.{perm}" />\n\n    <application', 1)
if ".IncomingSmsReceiver" not in m:
    assert "</application>" in m
    m = m.replace("</application>", '''    <receiver
            android:name=".IncomingSmsReceiver"
            android:exported="true"
            android:permission="android.permission.BROADCAST_SMS">
            <intent-filter>
                <action android:name="android.provider.Telephony.SMS_RECEIVED" />
            </intent-filter>
        </receiver>
    </application>''', 1)
out[mf] = m

# ---------- DAO ----------
dp = J / "db/Daos.kt"
d = load(dp)
anchor = '    @Query("SELECT COUNT(*) FROM contacts")\n    suspend fun count(): Int'
assert anchor in d
if "fun renameGroup" not in d:
    d = d.replace(anchor, '''    @Query("SELECT DISTINCT groupName FROM contacts ORDER BY groupName")
    suspend fun groups(): List<String>

    @Query("UPDATE contacts SET groupName = :newName WHERE groupName = :oldName")
    suspend fun renameGroup(oldName: String, newName: String)

    @Query("DELETE FROM contacts WHERE groupName = :g")
    suspend fun deleteGroup(g: String)

    @Query("DELETE FROM contacts WHERE id NOT IN (SELECT MIN(id) FROM contacts GROUP BY phone)")
    suspend fun deleteDuplicates()

    @Query("DELETE FROM contacts WHERE optedOut = 1")
    suspend fun deleteOptedOut()

    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE contacts SET groupName = :g WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, g: String)

    @Query("UPDATE contacts SET optedOut = 1 WHERE phone IN (:phones)")
    suspend fun optOutMany(phones: List<String>): Int

''' + anchor, 1)
out[dp] = d

# ---------- PhoneUtil ----------
out[J / "PhoneUtil.kt"] = r'''package com.example.bulksms

object PhoneUtil {
    fun asciiDigits(t: String): String {
        val sb = StringBuilder()
        for (ch in t) {
            when (ch) {
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalize(p: String): String {
        val sb = StringBuilder()
        for (ch in asciiDigits(p)) {
            if (ch in '0'..'9' || ch == '+') sb.append(ch)
        }
        return sb.toString()
    }

    fun toIranMobile(raw: String): String? {
        var d = normalize(raw).filter { it in '0'..'9' }
        if (d.startsWith("0098")) d = d.substring(4)
        else if (d.startsWith("98") && d.length == 12) d = d.substring(2)
        if (d.length == 10 && d.startsWith("9")) d = "0$d"
        return if (d.length == 11 && d.startsWith("09")) d else null
    }

    fun variants(mobile: String): List<String> {
        val t = mobile.substring(1)
        return listOf(mobile, t, "+98$t", "98$t", "0098$t")
    }
}
'''

# ---------- Incoming SMS receiver ----------
out[J / "IncomingSmsReceiver.kt"] = r'''package com.example.bulksms

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
'''

# ---------- Worker (repeat schedules) ----------
out[J / "ScheduledSmsWorker.kt"] = r'''package com.example.bulksms

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
            val contacts = db.contactDao().eligible(group)
            val sms = smsManager(subscriptionId)

            for ((index, contact) in contacts.withIndex()) {
                val num = PhoneUtil.normalize(contact.phone)
                val text = message.replace("{نام}", contact.name.ifBlank { "دوست عزیز" })
                try {
                    val parts = sms.divideMessage(text)
                    if (parts.size > 1) sms.sendMultipartTextMessage(num, null, parts, null, null)
                    else sms.sendTextMessage(num, null, text, null, null)
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
'''

# ---------- MainActivity ----------
mp = J / "MainActivity.kt"
s = load(mp)

def rep(old, new):
    global s
    assert old in s, "NOT FOUND: " + old[:70]
    s = s.replace(old, new, 1)

# permissions
rep("val l = mutableListOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE)",
    "val l = mutableListOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.RECEIVE_SMS)")
rep("if (!hasSmsPerm() || !hasPhonePerm()) {", "if (!hasSmsPerm() || !hasPhonePerm() || !hasRecvPerm()) {")

NEW_FUNCS = r'''    private fun hasRecvPerm() = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importCsv(uri) }
    private val contactsPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) importPhoneContacts() else toast("مجوز مخاطبین داده نشد.") }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) writeBackup(uri) }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readBackup(uri) }

    // ---------- templates ----------
    private fun loadTemplates(): MutableList<String> {
        val sp = getSharedPreferences("templates", Context.MODE_PRIVATE)
        val raw = sp.getString("list", null)
        val out = mutableListOf<String>()
        if (raw == null) {
            out.addAll(listOf(
                "سلام {نام}، تخفیف ویژه امروز ما را از دست ندهید.",
                "سلام {نام}، برای شما یک پیشنهاد ویژه داریم.",
                "مشتری گرامی {نام}، از خرید شما سپاسگزاریم."
            ))
            saveTemplates(out)
        } else {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        }
        return out
    }

    private fun saveTemplates(l: List<String>) {
        val arr = org.json.JSONArray()
        l.forEach { arr.put(it) }
        getSharedPreferences("templates", Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }

    private fun templateDialog(message: EditText) {
        val list = loadTemplates()
        val labels = (listOf("➕  ذخیره متن فعلی به‌عنوان قالب جدید") + list.map { if (it.length > 50) it.take(50) + "…" else it }).toTypedArray()
        AlertDialog.Builder(this).setTitle("قالب‌های پیام").setItems(labels) { _, i ->
            if (i == 0) {
                val t = message.text.toString().trim()
                if (t.isEmpty()) toast("ابتدا متن پیام را بنویسید.")
                else { list.add(t); saveTemplates(list); toast("قالب ذخیره شد.") }
            } else templateActions(message, list, i - 1)
        }.setNegativeButton("بستن", null).show()
    }

    private fun templateActions(message: EditText, list: MutableList<String>, idx: Int) {
        AlertDialog.Builder(this).setTitle("قالب").setMessage(list[idx])
            .setPositiveButton("استفاده") { _, _ -> message.setText(list[idx]) }
            .setNeutralButton("ویرایش") { _, _ ->
                val input = styledInput("متن قالب", 4).apply { setText(list[idx]) }
                AlertDialog.Builder(this).setTitle("ویرایش قالب").setView(input)
                    .setPositiveButton("ذخیره") { _, _ ->
                        val t = input.text.toString().trim()
                        if (t.isNotEmpty()) { list[idx] = t; saveTemplates(list); toast("ذخیره شد.") }
                    }
                    .setNegativeButton("انصراف", null).show()
            }
            .setNegativeButton("حذف") { _, _ -> list.removeAt(idx); saveTemplates(list); toast("حذف شد.") }
            .show()
    }

    // ---------- import ----------
    private fun importMenu() {
        AlertDialog.Builder(this).setTitle("وارد کردن مخاطبین")
            .setItems(arrayOf("📄  فایل TXT (هر شماره در یک خط)", "📊  فایل CSV (نام، شماره، گروه)", "📱  مخاطبین گوشی")) { _, i ->
                when (i) {
                    0 -> importLauncher.launch(arrayOf("*/*"))
                    1 -> importCsvLauncher.launch(arrayOf("*/*"))
                    else -> {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) importPhoneContacts()
                        else contactsPermLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                    }
                }
            }.setNegativeButton("بستن", null).show()
    }

    private fun importRows(rows: List<Triple<String, String, String>>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val existing = db.contactDao().getAll().map { parseIranPhone(it.phone) ?: it.phone }.toHashSet()
            var added = 0; var dup = 0; var bad = 0
            for ((name, phone, group) in rows) {
                val ph = parseIranPhone(phone)
                if (ph == null) { bad++; continue }
                if (!existing.add(ph)) { dup++; continue }
                db.contactDao().upsert(ContactEntity(name = name.trim(), phone = ph, groupName = group.trim()))
                added++
            }
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity).setTitle("نتیجه وارد کردن")
                    .setMessage("ردیف‌های خوانده‌شده: ${rows.size}\n✅ افزوده‌شده: $added\n🔁 تکراری: $dup\n⚠️ نامعتبر: $bad")
                    .setPositiveButton("تأیید") { _, _ -> showTab(1) }.show()
            }
        }
    }

    private fun importCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }?.removePrefix("\uFEFF") ?: ""
                val rows = mutableListOf<Triple<String, String, String>>()
                for (line in text.split(Regex("[\\r\\n]+"))) {
                    if (line.isBlank()) continue
                    val cells = line.split(Regex("[,;\\t]")).map { it.trim().trim('"').trim() }
                    val phone = cells.firstOrNull { parseIranPhone(it) != null }
                    if (phone == null) {
                        if (cells.none { c -> c.any { ch -> ch.isDigit() } }) continue
                        rows.add(Triple("", cells.firstOrNull { it.isNotEmpty() } ?: line, ""))
                        continue
                    }
                    val others = cells.filter { it != phone && it.isNotEmpty() }
                    rows.add(Triple(others.getOrElse(0) { "" }, phone, others.getOrElse(1) { "" }))
                }
                importRows(rows)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا در خواندن فایل: ${e.message}") }
            }
        }
    }

    private fun importPhoneContacts() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rows = mutableListOf<Triple<String, String, String>>()
                val uriC = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val cols = arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                contentResolver.query(uriC, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) rows.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", ""))
                }
                importRows(rows)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا در خواندن مخاطبین: ${e.message}") }
            }
        }
    }

    // ---------- bulk tools ----------
    private fun confirm(msg: String, action: suspend () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("بله") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    action()
                    withContext(Dispatchers.Main) { toast("انجام شد."); showTab(1) }
                }
            }
            .setNegativeButton("انصراف", null).show()
    }

    private fun pickGroup(onPick: (String) -> Unit) {
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { db.contactDao().groups() }.filter { it.isNotBlank() }
            if (groups.isEmpty()) { toast("گروهی وجود ندارد."); return@launch }
            AlertDialog.Builder(this@MainActivity).setTitle("انتخاب گروه")
                .setItems(groups.toTypedArray()) { _, i -> onPick(groups[i]) }.show()
        }
    }

    private fun bulkMenu() {
        val items = arrayOf(
            "☑  انتخاب چندتایی (حذف / تغییر گروه)",
            "✏  تغییر نام یک گروه",
            "🗑  حذف همه مخاطبین یک گروه",
            "🧹  حذف شماره‌های تکراری",
            "🚫  حذف مخاطبین «عدم دریافت»",
            "⚠  حذف همه مخاطبین"
        )
        AlertDialog.Builder(this).setTitle("ابزارهای گروهی").setItems(items) { _, i ->
            when (i) {
                0 -> multiSelectDialog()
                1 -> pickGroup { g ->
                    val input = styledInput("نام جدید گروه")
                    AlertDialog.Builder(this).setTitle("نام جدید برای «$g»").setView(input)
                        .setPositiveButton("تغییر") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.contactDao().renameGroup(g, input.text.toString().trim())
                                withContext(Dispatchers.Main) { toast("انجام شد."); showTab(1) }
                            }
                        }
                        .setNegativeButton("انصراف", null).show()
                }
                2 -> pickGroup { g -> confirm("همه مخاطبین گروه «$g» حذف شوند؟") { db.contactDao().deleteGroup(g) } }
                3 -> confirm("شماره‌های تکراری حذف شوند؟ (اولین مورد نگه داشته می‌شود)") { db.contactDao().deleteDuplicates() }
                4 -> confirm("همه مخاطبین عدم‌دریافت حذف شوند؟") { db.contactDao().deleteOptedOut() }
                else -> confirm("همه مخاطبین حذف شوند؟ این کار قابل بازگشت نیست.") { db.contactDao().deleteAll() }
            }
        }.setNegativeButton("بستن", null).show()
    }

    private fun multiSelectDialog() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { db.contactDao().getAll() }
            if (rows.isEmpty()) { toast("مخاطبی وجود ندارد."); return@launch }
            val labels = rows.map { "${it.name.ifBlank { "بدون نام" }} — ${it.phone}" + (if (it.optedOut) " ⛔" else "") }.toTypedArray()
            val checked = BooleanArray(rows.size)
            fun ids(): List<Long> = rows.indices.filter { checked[it] }.map { rows[it].id }
            AlertDialog.Builder(this@MainActivity).setTitle("انتخاب مخاطبین")
                .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("حذف انتخاب‌شده") { _, _ ->
                    val sel = ids()
                    if (sel.isEmpty()) toast("چیزی انتخاب نشده.")
                    else lifecycleScope.launch(Dispatchers.IO) {
                        sel.chunked(500).forEach { db.contactDao().deleteByIds(it) }
                        withContext(Dispatchers.Main) { toast("${sel.size} مخاطب حذف شد."); showTab(1) }
                    }
                }
                .setNeutralButton("تغییر گروه") { _, _ ->
                    val sel = ids()
                    if (sel.isEmpty()) toast("چیزی انتخاب نشده.")
                    else {
                        val input = styledInput("نام گروه")
                        AlertDialog.Builder(this@MainActivity).setTitle("گروه جدید برای ${sel.size} مخاطب").setView(input)
                            .setPositiveButton("ذخیره") { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    sel.chunked(500).forEach { db.contactDao().setGroup(it, input.text.toString().trim()) }
                                    withContext(Dispatchers.Main) { toast("انجام شد."); showTab(1) }
                                }
                            }
                            .setNegativeButton("انصراف", null).show()
                    }
                }
                .setNegativeButton("انصراف", null).show()
        }
    }

    // ---------- backup ----------
    private fun writeBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val root = org.json.JSONObject()
                val arr = org.json.JSONArray()
                db.contactDao().getAll().forEach { c ->
                    arr.put(org.json.JSONObject().put("name", c.name).put("phone", c.phone).put("group", c.groupName).put("optedOut", c.optedOut))
                }
                root.put("contacts", arr)
                val t = org.json.JSONArray()
                loadTemplates().forEach { t.put(it) }
                root.put("templates", t)
                val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
                root.put("settings", org.json.JSONObject()
                    .put("interval", sp.getInt("interval", 3))
                    .put("jitter", sp.getInt("jitter", 3))
                    .put("daily_limit", sp.getInt("daily_limit", 200)))
                root.put("version", 1)
                contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
                withContext(Dispatchers.Main) { toast("پشتیبان ذخیره شد: ${arr.length()} مخاطب") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا: ${e.message}") }
            }
        }
    }

    private fun readBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                val root = org.json.JSONObject(text)
                val existing = db.contactDao().getAll().map { it.phone }.toHashSet()
                var added = 0
                val arr = root.optJSONArray("contacts")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val ph = o.optString("phone")
                        if (ph.isBlank() || !existing.add(ph)) continue
                        db.contactDao().upsert(ContactEntity(name = o.optString("name"), phone = ph, groupName = o.optString("group"), optedOut = o.optBoolean("optedOut", false)))
                        added++
                    }
                }
                val ta = root.optJSONArray("templates")
                if (ta != null) {
                    val l = mutableListOf<String>()
                    for (i in 0 until ta.length()) l.add(ta.getString(i))
                    if (l.isNotEmpty()) saveTemplates(l)
                }
                val so = root.optJSONObject("settings")
                if (so != null) {
                    getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                        .putInt("interval", so.optInt("interval", 3))
                        .putInt("jitter", so.optInt("jitter", 3))
                        .putInt("daily_limit", so.optInt("daily_limit", 200)).apply()
                }
                withContext(Dispatchers.Main) { toast("بازیابی شد: $added مخاطب جدید"); showTab(5) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("فایل پشتیبان معتبر نیست: ${e.message}") }
            }
        }
    }

    private fun repeatLabel(id: Long): String {
        val r = getSharedPreferences("repeat", Context.MODE_PRIVATE).getString("repeat_$id", "NONE")
        return when (r) {
            "DAILY" -> "  •  تکرار روزانه"
            "WEEKLY" -> "  •  تکرار هفتگی"
            "MONTHLY" -> "  •  تکرار ماهانه"
            else -> ""
        }
    }

'''
rep("    private fun allPerms(): Array<String> {", NEW_FUNCS + "    private fun allPerms(): Array<String> {")

# contacts tab buttons
rep('addButton("📄  وارد کردن شماره‌ها از فایل TXT"){importLauncher.launch(arrayOf("*/*"))}',
    'addButton("📥  وارد کردن مخاطبین (TXT / CSV / گوشی)"){importMenu()}\n        addButton("🛠  ابزارهای گروهی و حذف"){bulkMenu()}')

# templates: remove old templateDialog
i1 = s.index("    private fun templateDialog(message:EditText) {")
i2 = s.index("    private fun previewDialog")
s = s[:i1] + s[i2:]

# sending: opt-out checkbox
rep("        content.addView(counter)\n", '''        content.addView(counter)
        val optTxt=android.widget.CheckBox(this).apply{text="افزودن «لغو۱۱» به انتهای پیام (مخاطب با پاسخ لغو۱۱ حذف می‌شود)";layoutDirection=rtl;textSize=13f}
        content.addView(optTxt)
''')
rep("confirmSend(group.text.toString().trim(),text,", 'confirmSend(group.text.toString().trim(),if(optTxt.isChecked) text+"\\nلغو۱۱" else text,')

# schedules: repeat spinner
rep("        box.addView(group);box.addView(msg);box.addView(interval)", '''        val rep=android.widget.Spinner(this).apply{adapter=android.widget.ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("بدون تکرار","تکرار روزانه","تکرار هفتگی","تکرار ماهانه"));layoutDirection=rtl}
        box.addView(group);box.addView(msg);box.addView(interval);box.addView(rep)''')
rep("pickDateTime(group.text.toString(),msg.text.toString(),interval.text.toString().toIntOrNull()?:3)",
    'pickDateTime(group.text.toString(),msg.text.toString(),interval.text.toString().toIntOrNull()?:3,arrayOf("NONE","DAILY","WEEKLY","MONTHLY")[rep.selectedItemPosition])')
rep("private fun pickDateTime(group:String,msg:String,interval:Int){", 'private fun pickDateTime(group:String,msg:String,interval:Int,repeat:String="NONE"){')
rep('db.scheduleDao().update(ScheduleEntity(id,group,msg,cal.timeInMillis,interval,selectedSubscriptionId,req.id.toString(),"SCHEDULED"))',
    'getSharedPreferences("repeat",Context.MODE_PRIVATE).edit().putString("repeat_$id",repeat).apply()\n                    db.scheduleDao().update(ScheduleEntity(id,group,msg,cal.timeInMillis,interval,selectedSubscriptionId,req.id.toString(),"SCHEDULED"))')
rep('وضعیت: ${scheduleStatus(s.status)}"', 'وضعیت: ${scheduleStatus(s.status)}${repeatLabel(s.id)}"')

# settings: backup buttons
rep('        addButton("🔐  مجوزها (باز کردن تنظیمات برنامه)"){openAppSettings()}', '''        addButton("💽  گرفتن پشتیبان (مخاطبین، قالب‌ها، تنظیمات)"){backupLauncher.launch("bulksms_backup.json")}
        addButton("♻  بازیابی از فایل پشتیبان"){restoreLauncher.launch(arrayOf("*/*"))}
        addButton("🔐  مجوزها (باز کردن تنظیمات برنامه)"){openAppSettings()}''')

out[mp] = s
for path, text in out.items():
    path.write_text(text)
print("OK")
