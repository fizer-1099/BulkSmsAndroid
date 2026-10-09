import pathlib
root = pathlib.Path(".")
J = root / "app/src/main/java/com/example/bulksms"
out = {}

# ---------------- worker ----------------
out[J / "OccasionWorker.kt"] = r'''package com.example.bulksms

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
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
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
                    for (c in db.contactDao().eligible(group)) {
                        jobs.add(Triple(c, o.optString("message"), group))
                    }
                }
            }
            val bd = JSONObject(sp.getString("birthdays", "{}") ?: "{}")
            if (bd.length() > 0) {
                val key = "${j[1]}/${j[2]}"
                val msg = sp.getString("birthday_msg", null) ?: "سلام {نام}، تولدت مبارک! 🎂"
                for (c in db.contactDao().eligible("")) {
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
                val parts = sms.divideMessage(text)
                if (parts.size > 1) sms.sendMultipartTextMessage(num, null, parts, null, null)
                else sms.sendTextMessage(num, null, text, null, null)
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
'''

# ---------------- MainActivity ----------------
mp = J / "MainActivity.kt"
s = mp.read_text()

def rep(old, new):
    global s
    assert old in s, "NOT FOUND: " + old[:70]
    s = s.replace(old, new, 1)

rep("private var sessionUnlocked = false\n", "private var sessionUnlocked = false\n\nprivate class OccRow(val text: String, val days: Int, val remove: () -> Unit)\n")

rep("        db = AppDatabase.get(this)\n        if (isLockEnabled() && !sessionUnlocked)", "        db = AppDatabase.get(this)\n        scheduleOccasionWorker()\n        if (isLockEnabled() && !sessionUnlocked)")

# persist selected SIM for the background worker
rep("selectedSubscriptionId=list[i].subscriptionId", 'selectedSubscriptionId=list[i].subscriptionId;getSharedPreferences("occasions",Context.MODE_PRIVATE).edit().putInt("sub",selectedSubscriptionId).apply()')
rep("{selectedSubscriptionId=-1;status.text=", '{selectedSubscriptionId=-1;getSharedPreferences("occasions",Context.MODE_PRIVATE).edit().putInt("sub",-1).apply();status.text=')

# settings entry
rep('        addButton("🌓  حالت نمایش (روشن / تیره / خودکار)"){themeDialog()}', '        addButton("🎂  مناسبت‌ها و تولد"){occasionsMenu()}\n        addButton("🌓  حالت نمایش (روشن / تیره / خودکار)"){themeDialog()}')

# backup integration
rep('root.put("version", 1)', '''val op = occPrefs()
                root.put("occasions", org.json.JSONObject()
                    .put("list", org.json.JSONArray(op.getString("list", "[]")))
                    .put("birthdays", org.json.JSONObject(op.getString("birthdays", "{}")))
                    .put("birthday_msg", op.getString("birthday_msg", "") ?: ""))
                root.put("version", 1)''')
rep('val so = root.optJSONObject("settings")', '''val oc = root.optJSONObject("occasions")
                if (oc != null) {
                    val e = occPrefs().edit()
                    oc.optJSONArray("list")?.let { e.putString("list", it.toString()) }
                    oc.optJSONObject("birthdays")?.let { e.putString("birthdays", it.toString()) }
                    val bm = oc.optString("birthday_msg")
                    if (bm.isNotBlank()) e.putString("birthday_msg", bm)
                    e.apply()
                }
                val so = root.optJSONObject("settings")''')

HELP = r'''    // ---------- occasions & birthdays ----------
    private val jMonths = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    private fun occPrefs() = getSharedPreferences("occasions", Context.MODE_PRIVATE)

    private fun scheduleOccasionWorker() {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val req = PeriodicWorkRequestBuilder<OccasionWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("occasions", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun occasionsMenu() {
        AlertDialog.Builder(this).setTitle("مناسبت‌ها و تولد")
            .setItems(arrayOf(
                "➕  افزودن مناسبت (تاریخ شمسی)",
                "🎂  ثبت تولد برای مخاطب",
                "✏  متن پیام تولد",
                "📋  مشاهده و حذف (نزدیک‌ترین اول)",
                "▶  بررسی و ارسال همین حالا (تست)",
                "ℹ  راهنما"
            )) { _, i ->
                when (i) {
                    0 -> addOccasionDialog()
                    1 -> pickBirthdayContact()
                    2 -> birthdayMessageDialog()
                    3 -> listOccasions()
                    4 -> runOccasionsNow()
                    else -> occasionHelp()
                }
            }.setNegativeButton("بستن", null).show()
    }

    private fun addOccasionDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = rtl }
        val name = styledInput("نام مناسبت (مثلاً نوروز)")
        val group = styledInput("گروه — خالی یعنی همه")
        val msg = styledInput("متن پیام (می‌توانید {نام} بنویسید)", 4)
        box.addView(name); box.addView(group); box.addView(msg)
        AlertDialog.Builder(this).setTitle("مناسبت جدید").setView(box)
            .setPositiveButton("انتخاب تاریخ") { _, _ ->
                if (name.text.isBlank() || msg.text.isBlank()) toast("نام و متن پیام را وارد کنید.")
                else pickJalaliDate { gy, gm, gd ->
                    val j = Jalali.toJalali(gy, gm, gd)
                    val arr = org.json.JSONArray(occPrefs().getString("list", "[]"))
                    arr.put(
                        org.json.JSONObject()
                            .put("id", System.currentTimeMillis())
                            .put("name", name.text.toString().trim())
                            .put("m", j[1]).put("d", j[2])
                            .put("group", group.text.toString().trim())
                            .put("message", msg.text.toString().trim())
                    )
                    occPrefs().edit().putString("list", arr.toString()).apply()
                    toast("ثبت شد: ${j[2]} ${jMonths[j[1] - 1]} (هر سال)")
                }
            }
            .setNegativeButton("انصراف", null).show()
    }

    private fun pickBirthdayContact() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { db.contactDao().getAll() }
            if (rows.isEmpty()) { toast("ابتدا مخاطب اضافه کنید."); return@launch }
            val labels = rows.map { "${it.name.ifBlank { "بدون نام" }} — ${it.phone}" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity).setTitle("انتخاب مخاطب").setItems(labels) { _, i ->
                val c = rows[i]
                pickJalaliDate { gy, gm, gd ->
                    val j = Jalali.toJalali(gy, gm, gd)
                    val key = PhoneUtil.toIranMobile(c.phone) ?: PhoneUtil.normalize(c.phone)
                    val bd = org.json.JSONObject(occPrefs().getString("birthdays", "{}"))
                    bd.put(key, "${j[1]}/${j[2]}")
                    occPrefs().edit().putString("birthdays", bd.toString()).apply()
                    toast("تولد ثبت شد: ${j[2]} ${jMonths[j[1] - 1]}")
                }
            }.setNegativeButton("بستن", null).show()
        }
    }

    private fun birthdayMessageDialog() {
        val input = styledInput("متن پیام تولد", 4).apply {
            setText(occPrefs().getString("birthday_msg", null) ?: "سلام {نام}، تولدت مبارک! 🎂")
        }
        AlertDialog.Builder(this).setTitle("متن پیام تولد").setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { occPrefs().edit().putString("birthday_msg", t).apply(); toast("ذخیره شد.") }
            }
            .setNegativeButton("انصراف", null).show()
    }

    private fun listOccasions() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { db.contactDao().getAll() }
            val names = HashMap<String, String>()
            contacts.forEach { names[PhoneUtil.toIranMobile(it.phone) ?: PhoneUtil.normalize(it.phone)] = it.name.ifBlank { it.phone } }
            val today = Calendar.getInstance()
            val tj = Jalali.toJalali(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))
            fun doy(m: Int, d: Int) = if (m <= 6) (m - 1) * 31 + d else 186 + (m - 7) * 30 + d
            fun daysUntil(m: Int, d: Int): Int = (doy(m, d) - doy(tj[1], tj[2]) + 366) % 366
            fun label(m: Int, d: Int): String {
                val n = daysUntil(m, d)
                return "$d ${jMonths[m - 1]} — " + (if (n == 0) "امروز" else if (n == 1) "فردا" else "$n روز دیگر")
            }
            val rows = mutableListOf<OccRow>()
            val arr = org.json.JSONArray(occPrefs().getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val m = o.optInt("m"); val d = o.optInt("d")
                if (m < 1 || m > 12) continue
                val grp = o.optString("group")
                rows.add(OccRow("🎉 ${o.optString("name")} — ${label(m, d)}" + (if (grp.isBlank()) "" else "\nگروه: $grp"), daysUntil(m, d)) {
                    val a = org.json.JSONArray(occPrefs().getString("list", "[]"))
                    val na = org.json.JSONArray()
                    for (k in 0 until a.length()) {
                        if (a.getJSONObject(k).optLong("id") != o.optLong("id")) na.put(a.getJSONObject(k))
                    }
                    occPrefs().edit().putString("list", na.toString()).apply()
                })
            }
            val bd = org.json.JSONObject(occPrefs().getString("birthdays", "{}"))
            val keys = bd.keys()
            while (keys.hasNext()) {
                val phone = keys.next()
                val md = bd.optString(phone).split("/")
                if (md.size != 2) continue
                val m = md[0].toIntOrNull() ?: continue
                val d = md[1].toIntOrNull() ?: continue
                if (m < 1 || m > 12) continue
                rows.add(OccRow("🎂 ${names[phone] ?: phone} — ${label(m, d)}", daysUntil(m, d)) {
                    val b2 = org.json.JSONObject(occPrefs().getString("birthdays", "{}"))
                    b2.remove(phone)
                    occPrefs().edit().putString("birthdays", b2.toString()).apply()
                })
            }
            rows.sortBy { it.days }
            if (rows.isEmpty()) { toast("چیزی ثبت نشده است."); return@launch }
            AlertDialog.Builder(this@MainActivity).setTitle("مناسبت‌ها و تولدها")
                .setItems(rows.map { it.text }.toTypedArray()) { _, i ->
                    AlertDialog.Builder(this@MainActivity).setMessage("حذف شود؟\n${rows[i].text}")
                        .setPositiveButton("حذف") { _, _ -> rows[i].remove(); toast("حذف شد.") }
                        .setNegativeButton("انصراف", null).show()
                }.setNegativeButton("بستن", null).show()
        }
    }

    private fun runOccasionsNow() {
        AlertDialog.Builder(this).setTitle("بررسی همین حالا")
            .setMessage("مناسبت‌ها و تولدهای «امروز» الان بررسی و ارسال می‌شوند. اگر امروز قبلاً ارسال شده باشد، دوباره برای همان افراد ارسال می‌شود. ادامه می‌دهید؟")
            .setPositiveButton("ارسال") { _, _ ->
                if (!hasSmsPerm()) toast("ابتدا مجوز ارسال پیامک را بدهید.")
                else {
                    WorkManager.getInstance(this).enqueue(
                        OneTimeWorkRequestBuilder<OccasionWorker>().setInputData(workDataOf("force" to true)).build()
                    )
                    toast("بررسی شروع شد؛ نتیجه در گزارش‌ها ثبت می‌شود.")
                }
            }
            .setNegativeButton("انصراف", null).show()
    }

    private fun occasionHelp() {
        AlertDialog.Builder(this).setTitle("راهنما")
            .setMessage("• هر روز حدود ساعت ۹ صبح، برنامه مناسبت‌ها و تولدهای همان روز (تاریخ شمسی) را بررسی و پیام می‌فرستد.\n• تولد: از «ثبت تولد» مخاطب را انتخاب کنید؛ متن پیام را از «متن پیام تولد» تغییر دهید.\n• مخاطبین «عدم دریافت» پیام نمی‌گیرند.\n• ارسال خودکار با سیم‌کارتی انجام می‌شود که آخرین بار در تنظیمات انتخاب کرده‌اید.\n• برای ارسال، مجوز پیامک باید فعال باشد و بهتر است برای برنامه «بهینه‌سازی باتری» را خاموش کنید.\n• ارسال خودکار برای گروه‌های بزرگ (بیش از حدود ۱۵۰ نفر) ممکن است کامل نشود.")
            .setPositiveButton("متوجه شدم", null).show()
    }

'''
rep("    private fun allPerms(): Array<String> {", HELP + "    private fun allPerms(): Array<String> {")
out[mp] = s

for path, text in out.items():
    path.write_text(text)
print("OK")
