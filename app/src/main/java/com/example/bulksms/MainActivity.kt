package com.example.bulksms

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.example.bulksms.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private var sessionUnlocked = false

private class OccRow(val text: String, val days: Int, val remove: () -> Unit)

class MainActivity : AppCompatActivity() {
    private lateinit var db: AppDatabase
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private var selectedSubscriptionId = -1

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) exportCsv(uri) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) askGroupAndImport(uri) }

    private val rtl = android.view.View.LAYOUT_DIRECTION_RTL

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", 0)) {
                1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        super.onCreate(savedInstanceState)
        db = AppDatabase.get(this)
        scheduleOccasionWorker()
        if (isLockEnabled() && !sessionUnlocked) showLock() else { sessionUnlocked = true; buildUi(); maybeWelcome() }
        if (!hasSmsPerm() || !hasPhonePerm() || !hasRecvPerm()) {
            permLauncher.launch(allPerms())
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private fun hasSmsPerm() = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun hasPhonePerm() = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val tabViews = mutableListOf<TextView>()

    private fun hasRecvPerm() = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

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
                val op = occPrefs()
                root.put("occasions", org.json.JSONObject()
                    .put("list", org.json.JSONArray(op.getString("list", "[]")))
                    .put("birthdays", org.json.JSONObject(op.getString("birthdays", "{}")))
                    .put("birthday_msg", op.getString("birthday_msg", "") ?: ""))
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
                val oc = root.optJSONObject("occasions")
                if (oc != null) {
                    val e = occPrefs().edit()
                    oc.optJSONArray("list")?.let { e.putString("list", it.toString()) }
                    oc.optJSONObject("birthdays")?.let { e.putString("birthdays", it.toString()) }
                    val bm = oc.optString("birthday_msg")
                    if (bm.isNotBlank()) e.putString("birthday_msg", bm)
                    e.apply()
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

    private fun col(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)

    // ---------- Jalali ----------
    private fun fmtJalali(ms: Long, withTime: Boolean = true): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val j = Jalali.toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        val d = "%04d/%02d/%02d".format(j[0], j[1], j[2])
        return if (withTime) d + "  " + "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) else d
    }

    private fun pickJalaliDate(onPicked: (Int, Int, Int) -> Unit) {
        val now = Calendar.getInstance()
        val j = Jalali.toJalali(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val months = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        val ny = NumberPicker(this).apply { minValue = j[0]; maxValue = j[0] + 5; value = j[0] }
        val nm = NumberPicker(this).apply { minValue = 1; maxValue = 12; displayedValues = months; value = j[1] }
        val nd = NumberPicker(this).apply { minValue = 1; maxValue = 31; value = j[2] }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(nd); addView(nm); addView(ny)
        }
        AlertDialog.Builder(this).setTitle("تاریخ شمسی (سال / ماه / روز)").setView(row)
            .setPositiveButton("ادامه") { _, _ ->
                val g = Jalali.toGregorian(ny.value, nm.value, nd.value)
                val back = Jalali.toJalali(g[0], g[1], g[2])
                if (back[1] != nm.value || back[2] != nd.value) toast("این تاریخ در تقویم وجود ندارد.")
                else onPicked(g[0], g[1], g[2])
            }
            .setNegativeButton("انصراف", null).show()
    }

    // ---------- theme / welcome ----------
    private fun themeDialog() {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        AlertDialog.Builder(this).setTitle("حالت نمایش")
            .setSingleChoiceItems(arrayOf("خودکار (مطابق گوشی)", "روشن", "تیره"), sp.getInt("theme", 0)) { d, i ->
                sp.edit().putInt("theme", i).apply()
                d.dismiss()
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    when (i) {
                        1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }.show()
    }

    private fun maybeWelcome() {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("welcomed", false)) return
        sp.edit().putBoolean("welcomed", true).apply()
        AlertDialog.Builder(this).setTitle("خوش آمدید 👋")
            .setMessage("۱) از «مخاطبین» شماره‌ها را وارد کنید (TXT، CSV یا مخاطبین گوشی).\n۲) در «ارسال» متن را بنویسید و ارسال را شروع کنید؛ ارسال در پس‌زمینه ادامه پیدا می‌کند.\n۳) در «تنظیمات» سقف روزانه، رمز برنامه، حالت تیره و پشتیبان‌گیری را تنظیم کنید.\n\nفقط برای مخاطبانی پیام بفرستید که رضایت دارند.")
            .setPositiveButton("متوجه شدم", null).show()
    }

    // ---------- app lock ----------
    private fun lockPrefs() = getSharedPreferences("lock", Context.MODE_PRIVATE)
    private fun isLockEnabled() = lockPrefs().getString("pin", null) != null
    private var stoppedAt = 0L

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (isLockEnabled() && sessionUnlocked && System.currentTimeMillis() - stoppedAt > 60_000L && stoppedAt > 0L) {
            sessionUnlocked = false
            showLock()
        }
    }

    private fun pinHash(p: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(("bulksms:" + toAsciiDigits(p).trim()).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun canBiometric(): Boolean = try {
        androidx.biometric.BiometricManager.from(this)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) { false }

    private fun showBiometric() {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val prompt = androidx.biometric.BiometricPrompt(this, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    unlockNow()
                }
            })
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("ورود به برنامه")
            .setNegativeButtonText("استفاده از رمز")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    private fun unlockNow() {
        sessionUnlocked = true
        buildUi()
    }

    private fun showLock() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = rtl
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(col(R.color.app_bg))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(box) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(28), b.top + dp(28), dp(28), b.bottom + dp(28))
            insets
        }
        box.addView(TextView(this).apply { text = "🔒"; textSize = 44f; gravity = Gravity.CENTER })
        box.addView(TextView(this).apply {
            text = "رمز برنامه را وارد کنید"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(col(R.color.app_t1)); setPadding(0, dp(10), 0, dp(14))
        })
        val pin = styledInput("رمز").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        box.addView(pin, LinearLayout.LayoutParams(-1, -2))
        fun tryUnlock() {
            if (pinHash(pin.text.toString()) == lockPrefs().getString("pin", "")) unlockNow()
            else { toast("رمز اشتباه است."); pin.setText("") }
        }
        box.addView(Button(this).apply {
            text = "ورود"; isAllCaps = false; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(BLUE) }
            setOnClickListener { tryUnlock() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        pin.setOnEditorActionListener { _, _, _ -> tryUnlock(); true }
        if (canBiometric()) {
            box.addView(Button(this).apply {
                text = "👆  ورود با اثر انگشت"; isAllCaps = false; setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(BLUE) }
                setOnClickListener { showBiometric() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        setContentView(box)
        if (canBiometric()) showBiometric()
    }

    private fun askPin(title: String, onOk: (String) -> Unit) {
        val input = styledInput("رمز").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("تأیید") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("انصراف", null).show()
    }

    private fun setNewPin() {
        askPin("رمز جدید (حداقل ۴ رقم)") { p ->
            val a = toAsciiDigits(p).trim()
            if (a.length < 4) toast("رمز باید حداقل ۴ رقم باشد.")
            else { lockPrefs().edit().putString("pin", pinHash(a)).apply(); toast("رمز فعال شد.") }
        }
    }

    private fun lockSettings() {
        if (!isLockEnabled()) { setNewPin(); return }
        AlertDialog.Builder(this).setTitle("رمز برنامه")
            .setItems(arrayOf("تغییر رمز", "غیرفعال کردن رمز")) { _, i ->
                askPin("رمز فعلی") { cur ->
                    if (pinHash(cur) != lockPrefs().getString("pin", "")) toast("رمز فعلی اشتباه است.")
                    else if (i == 1) { lockPrefs().edit().remove("pin").apply(); toast("رمز غیرفعال شد.") }
                    else setNewPin()
                }
            }.setNegativeButton("بستن", null).show()
    }

    // ---------- occasions & birthdays ----------
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

    private fun allPerms(): Array<String> {
        val l = mutableListOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.RECEIVE_SMS)
        if (android.os.Build.VERSION.SDK_INT >= 33) l.add("android.permission.POST_NOTIFICATIONS")
        return l.toTypedArray()
    }

    private var pollJob: kotlinx.coroutines.Job? = null

    private fun launchService() {
        if (!hasSmsPerm()) { toast("ابتدا مجوز ارسال پیامک را بدهید."); return }
        androidx.core.content.ContextCompat.startForegroundService(
            this, Intent(this, SendService::class.java).setAction(SendService.ACTION_START)
        )
    }

    private fun startCampaign(contacts: List<ContactEntity>, template: String, group: String, interval: Int) {
        if (contacts.isEmpty()) { toast("مخاطب واجد شرایطی پیدا نشد."); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val items = contacts.map { c ->
                QueueItemEntity(
                    phone = PhoneUtil.normalize(c.phone),
                    message = template.replace("{نام}", c.name.ifBlank { "دوست عزیز" }),
                    groupName = group,
                    subscriptionId = selectedSubscriptionId
                )
            }
            db.queueDao().clear()
            db.queueDao().insertAll(items)
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("interval", interval).apply()
            withContext(Dispatchers.Main) { launchService(); toast("ارسال در پس‌زمینه شروع شد.") }
        }
    }

    private fun resendFailed() {
        lifecycleScope.launch(Dispatchers.IO) {
            val failed = db.sendLogDao().failedLogs().distinctBy { it.phone }
            if (failed.isEmpty()) { withContext(Dispatchers.Main) { toast("ارسال ناموفقی وجود ندارد.") }; return@launch }
            db.queueDao().clear()
            db.queueDao().insertAll(failed.map {
                QueueItemEntity(phone = it.phone, message = it.message, groupName = it.groupName, subscriptionId = selectedSubscriptionId)
            })
            db.sendLogDao().markFailedRetried()
            withContext(Dispatchers.Main) { launchService(); toast("${failed.size} پیام دوباره در صف قرار گرفت.") }
        }
    }

    private fun cancelQueue() {
        startService(Intent(this, SendService::class.java).setAction(SendService.ACTION_STOP))
        lifecycleScope.launch(Dispatchers.IO) {
            db.queueDao().clear()
            withContext(Dispatchers.Main) { toast("صف ارسال لغو شد.") }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                val total = withContext(Dispatchers.IO) { db.queueDao().totalCount() }
                val done = withContext(Dispatchers.IO) { db.queueDao().doneCount() }
                val pend = withContext(Dispatchers.IO) { db.queueDao().pendingCount() }
                progress.max = maxOf(total, 1)
                progress.progress = done
                progressText.text = if (total == 0) "آماده ارسال" else "صف: $done از $total انجام شد  |  باقی‌مانده: $pend"
                delay(1500)
            }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun smsManager(subId: Int): SmsManager {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            val base = getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private fun normalizePhone(p: String): String {
        val sb = StringBuilder()
        for (ch in p) {
            when (ch) {
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '0'..'9', '+' -> sb.append(ch)
                else -> {}
            }
        }
        return sb.toString()
    }

    private fun buildUi() {
        tabViews.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtl
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(col(R.color.app_bg))
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(14), b.top + dp(12), dp(14), b.bottom + dp(10))
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = rtl
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.RIGHT }
        titleBox.addView(TextView(this).apply {
            text = "اف پیامک"
            textSize = 27f
            setTextColor(col(R.color.app_t1))
        })
        titleBox.addView(TextView(this).apply {
            text = "مدیریت هوشمند پیامک"
            textSize = 13f
            setTextColor(col(R.color.app_t4))
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "۲.۳"
            textSize = 13f
            setTextColor(col(R.color.app_t3))
            setPadding(dp(10),dp(7),dp(10),dp(7))
            setBackgroundResource(com.example.bulksms.R.drawable.card_bg)
        })
        root.addView(header)

        status = TextView(this).apply {
            text = "● آماده به کار"
            textSize = 13f
            setTextColor(col(R.color.app_green))
            gravity = Gravity.RIGHT
            setPadding(0, dp(10), 0, dp(7))
        }
        root.addView(status)

        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = rtl
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = rtl }
        listOf("🏠\nداشبورد","👥\nمخاطبین","✉️\nارسال","⏰\nزمان‌بندی","📊\nگزارش‌ها","⚙️\nتنظیمات").forEachIndexed { i,t ->
            val b = TextView(this).apply {
                text=t
                textSize=12f
                gravity=Gravity.CENTER
                setTextColor(col(R.color.app_t2))
                setPadding(dp(2),dp(9),dp(2),dp(9))
                maxLines=2
                setOnClickListener { showTab(i) }
            }
            tabViews.add(b)
            tabs.addView(b, LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(1),dp(3),dp(1),dp(3))})
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtl
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1,0,1f))

        tabs.setBackgroundResource(R.drawable.nav_bg)
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        setContentView(root)
        showTab(0)
    }

    private fun exportCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rows = db.sendLogDao().latest(100000)
                val sb = StringBuilder("\uFEFFphone,message,group,status\n")
                fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
                for (r in rows) {
                    sb.append(q(r.phone)).append(',').append(q(r.message)).append(',')
                        .append(q(r.groupName)).append(',').append(q(r.status)).append('\n')
                }
                contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
                withContext(Dispatchers.Main) { toast("ذخیره شد") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا: ${e.message}") }
            }
        }
    }

    private fun showTab(tab:Int) {
        pollJob?.cancel()
        tabViews.forEachIndexed { i, t ->
            if (i == tab) {
                t.setTextColor(Color.WHITE)
                t.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(BLUE) }
            } else {
                t.setTextColor(col(R.color.app_t2))
                t.background = null
            }
        }
        content.removeAllViews()
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(200).start()
        when(tab) {
            0 -> dashboard()
            1 -> contacts()
            2 -> sending()
            3 -> schedules()
            4 -> reports()
            5 -> settings()
        }
    }

    private fun dashboard() {
        addTitle("داشبورد")
        addText("خلاصه وضعیت برنامه و آخرین فعالیت‌ها")
        lifecycleScope.launch {
            val c=db.contactDao().count()
            val o=db.contactDao().optOutCount()
            val s=db.sendLogDao().sentCount()
            val f=db.sendLogDao().failedCount()

            val row1 = LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=rtl }
            row1.addView(statCard("مخاطبین", "$c", "👥"), weightParams())
            row1.addView(statCard("عدم دریافت", "$o", "⛔"), weightParams())
            content.addView(row1)

            val row2 = LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=rtl }
            row2.addView(statCard("موفق", "$s", "✓"), weightParams())
            row2.addView(statCard("ناموفق", "$f", "×"), weightParams())
            content.addView(row2)
        }
        addSection("دسترسی سریع")
        addActionCard("✉️", "ارسال پیامک", "شروع ارسال به مخاطبان مجاز") { showTab(2) }
        addActionCard("👥", "مخاطبین", "مدیریت، جستجو و گروه‌بندی") { showTab(1) }
        addActionCard("⏰", "زمان‌بندی", "مدیریت ارسال‌های آینده") { showTab(3) }
        addActionCard("📊", "گزارش‌ها", "مشاهده تاریخچه و خروجی") { showTab(4) }
    }

    private fun contacts() {
        addTitle("مخاطبین")
        addText("مخاطبین مجاز به دریافت پیام را مدیریت کنید.")
        val search=EditText(this).apply {
            hint="🔎 جستجوی نام، شماره یا گروه"
            setBackgroundResource(R.drawable.input_bg)
            layoutDirection=rtl
            setPadding(dp(14),dp(10),dp(14),dp(10))
        }
        content.addView(search, marginParams())
        addButton("＋  افزودن مخاطب"){contactDialog(null)}
        addButton("📥  وارد کردن مخاطبین (TXT / CSV / گوشی)"){importMenu()}
        addButton("🛠  ابزارهای گروهی و حذف"){bulkMenu()}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        content.addView(list)
        fun load(q:String="") {
            lifecycleScope.launch {
                val rows=if(q.isBlank()) db.contactDao().getAll() else db.contactDao().search(q)
                list.removeAllViews()
                if(rows.isEmpty()) addEmpty(list,"مخاطبی پیدا نشد.")
                rows.forEach { c ->
                    list.addView(contactCard(c))
                }
            }
        }
        search.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){load(s?.toString()?:"")}
            override fun afterTextChanged(e:android.text.Editable?){}
        })
        load()
    }

    private fun toAsciiDigits(t: String): String {
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

    private fun parseIranPhone(raw: String): String? {
        var d = toAsciiDigits(raw).filter { it in '0'..'9' }
        if (d.startsWith("0098")) d = d.substring(4)
        else if (d.startsWith("98") && d.length == 12) d = d.substring(2)
        if (d.length == 10 && d.startsWith("9")) d = "0$d"
        return if (d.length == 11 && d.startsWith("09")) d else null
    }

    private fun askGroupAndImport(uri: Uri) {
        val input = styledInput("نام گروه (اختیاری)")
        AlertDialog.Builder(this).setTitle("وارد کردن از فایل")
            .setMessage("گروه مخاطبین جدید را وارد کنید (می‌توانید خالی بگذارید).")
            .setView(input)
            .setPositiveButton("وارد کردن") { _, _ -> importTxt(uri, input.text.toString().trim()) }
            .setNegativeButton("انصراف", null).show()
    }

    private fun importTxt(uri: Uri, group: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }?.removePrefix("\uFEFF") ?: ""
                val tokens = text.split(Regex("[\\r\\n,;،؛]+")).map { it.trim() }.filter { it.isNotEmpty() }
                val existing = db.contactDao().getAll().map { parseIranPhone(it.phone) ?: it.phone }.toHashSet()
                var added = 0; var dup = 0; var bad = 0
                for (t in tokens) {
                    val ph = parseIranPhone(t)
                    if (ph == null) { bad++; continue }
                    if (!existing.add(ph)) { dup++; continue }
                    db.contactDao().upsert(ContactEntity(phone = ph, groupName = group))
                    added++
                }
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity).setTitle("نتیجه وارد کردن")
                        .setMessage("شماره‌های خوانده‌شده از فایل: ${tokens.size}\n✅ افزوده‌شده: $added\n🔁 تکراری: $dup\n⚠️ نامعتبر: $bad")
                        .setPositiveButton("تأیید") { _, _ -> showTab(1) }.show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا در خواندن فایل: ${e.message}") }
            }
        }
    }

    private fun contactCard(c:ContactEntity): View {
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            layoutDirection=rtl
            setPadding(dp(14),dp(12),dp(14),dp(12))
            setBackgroundResource(R.drawable.card_bg)
            setOnClickListener{contactDialog(c)}
        }
        box.addView(TextView(this).apply{text=c.name.ifBlank{"بدون نام"};textSize=17f;setTextColor(col(R.color.app_t1))})
        box.addView(TextView(this).apply{text=c.phone;textSize=14f;setTextColor(col(R.color.app_t3))})
        box.addView(TextView(this).apply{text="گروه: ${c.groupName.ifBlank{"بدون گروه"}}${if(c.optedOut) "  •  ⛔ عدم دریافت" else ""}";textSize=13f;setTextColor(if(c.optedOut)col(R.color.app_red) else col(R.color.app_t3))})
        return box
    }

    private fun contactDialog(existing:ContactEntity?) {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=rtl}
        val name=EditText(this).apply{hint="نام و نام خانوادگی";setText(existing?.name?:"");setBackgroundResource(R.drawable.input_bg)}
        val phone=EditText(this).apply{hint="شماره موبایل";setText(existing?.phone?:"");setBackgroundResource(R.drawable.input_bg)}
        val group=EditText(this).apply{hint="گروه";setText(existing?.groupName?:"");setBackgroundResource(R.drawable.input_bg)}
        box.addView(name,marginParams());box.addView(phone,marginParams());box.addView(group,marginParams())
        AlertDialog.Builder(this).setTitle(if(existing==null)"افزودن مخاطب" else "ویرایش مخاطب").setView(box)
            .setPositiveButton("ذخیره"){_,_->
                val p=phone.text.toString().trim()
                if(p.isBlank()){toast("شماره موبایل را وارد کنید.");return@setPositiveButton}
                lifecycleScope.launch{
                    db.contactDao().upsert(ContactEntity(existing?.id?:0,name.text.toString().trim(),p,group.text.toString().trim(),existing?.optedOut?:false))
                    contacts()
                }
            }
            .setNeutralButton(if(existing?.optedOut==true)"فعال‌سازی دریافت" else "عدم دریافت"){_,_->
                if(existing!=null) lifecycleScope.launch{db.contactDao().setOptOut(existing.phone,!existing.optedOut);contacts()}
            }
            .setNegativeButton(if(existing==null)"انصراف" else "حذف"){_,_->
                if(existing!=null) lifecycleScope.launch{db.contactDao().deleteById(existing.id);contacts()}
            }.show()
    }

    private fun sending() {
        addTitle("ارسال پیامک")
        addText("پیام خود را آماده کنید و قبل از ارسال تعداد گیرندگان را بررسی کنید.")
        val group=styledInput("گروه — خالی یعنی همه")
        val message=styledInput("متن پیام",5)
        val interval=styledInput("فاصله بین پیام‌ها به ثانیه").apply{setText(getSharedPreferences("settings",Context.MODE_PRIVATE).getInt("interval",3).toString())}
        content.addView(group);content.addView(message)
        val counter=TextView(this).apply{textSize=12f;gravity=Gravity.RIGHT;setTextColor(col(R.color.app_t4));text="0 کاراکتر"}
        content.addView(counter)
        val optTxt=android.widget.CheckBox(this).apply{text="افزودن «لغو۱۱» به انتهای پیام (مخاطب با پاسخ لغو۱۱ حذف می‌شود)";layoutDirection=rtl;textSize=13f}
        content.addView(optTxt)
        message.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){
                val t=s?.toString()?:""
                val uni=t.any{it.code>127}
                val single=if(uni)70 else 160
                val multi=if(uni)67 else 153
                val parts=if(t.isEmpty())0 else if(t.length<=single)1 else (t.length+multi-1)/multi
                counter.text="${t.length} کاراکتر  •  $parts پیامک"
            }
            override fun afterTextChanged(e:android.text.Editable?){}
        })
        content.addView(interval)
        addButton("📱  انتخاب سیم‌کارت"){selectSim()}
        addButton("📝  قالب‌های آماده"){templateDialog(message)}
        addButton("👁  پیش‌نمایش"){previewDialog(message)}
        addButton("🚀  شروع ارسال"){
            val text=message.text.toString()
            if(text.isBlank()){toast("متن پیام را وارد کنید.");return@addButton}
            confirmSend(group.text.toString().trim(),if(optTxt.isChecked) text+"\nلغو۱۱" else text,interval.text.toString().toIntOrNull()?.coerceAtLeast(0)?:3)
        }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0}
        content.addView(progress,marginParams())
        progressText=TextView(this).apply{text="آماده ارسال";gravity=Gravity.RIGHT}
        content.addView(progressText)
        addButton("▶  ادامه ارسال صف"){launchService()}
        addButton("🔁  ارسال مجدد به ناموفق‌ها"){resendFailed()}
        addButton("⏹  لغو صف باقی‌مانده"){cancelQueue()}
        startPolling()
        addText("متغیر شخصی‌سازی: {نام}  ← نام مخاطب را جایگزین می‌کند.")
        addText("⛔ مخاطبانی که عدم دریافت را انتخاب کرده‌اند، ارسال دریافت نمی‌کنند.")
    }

    private fun confirmSend(group:String,text:String,interval:Int) {
        if(!hasSmsPerm()){
            permLauncher.launch(arrayOf(android.Manifest.permission.SEND_SMS,android.Manifest.permission.READ_PHONE_STATE))
            AlertDialog.Builder(this).setTitle("مجوز ارسال پیامک")
                .setMessage("برای ارسال، مجوز «ارسال پیامک» باید روی «اجازه دادن» باشد. تنظیمات برنامه را باز کنید.")
                .setPositiveButton("باز کردن تنظیمات"){_,_->openAppSettings()}
                .setNegativeButton("بعداً",null).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val contacts=db.contactDao().eligible(group)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تأیید ارسال")
                    .setMessage("گیرندگان مجاز: ${contacts.size}\nفاصله: $interval ثانیه\n\nارسال شروع شود؟")
                    .setPositiveButton("شروع"){_,_->startCampaign(contacts,text,group,interval)}
                    .setNegativeButton("انصراف",null).show()
            }
        }
    }

    private suspend fun sendMessages(contacts:List<ContactEntity>,template:String,group:String,interval:Int) {
        if(contacts.isEmpty()){withContext(Dispatchers.Main){toast("مخاطب واجد شرایطی پیدا نشد.")};return}
        val sms=try{smsManager(selectedSubscriptionId)}catch(e:Exception){withContext(Dispatchers.Main){toast("خطا در سیم‌کارت: ${e.message}")};return}
        var ok=0;var fail=0
        withContext(Dispatchers.Main){progress.max=contacts.size;progress.progress=0}
        contacts.forEachIndexed{i,c->
            val text=template.replace("{نام}",c.name.ifBlank{"دوست عزیز"})
            try{val num=normalizePhone(c.phone);val parts=sms.divideMessage(text);if(parts.size>1)sms.sendMultipartTextMessage(num,null,parts,null,null)else sms.sendTextMessage(num,null,text,null,null);db.sendLogDao().insert(SendLogEntity(phone=c.phone,message=text,groupName=group,status="موفق"));ok++}
            catch(e:Exception){db.sendLogDao().insert(SendLogEntity(phone=c.phone,message=text,groupName=group,status="ناموفق: ${e.message?:"خطای نامشخص"}"));fail++}
            withContext(Dispatchers.Main){
                progress.progress=i+1
                progressText.text="در حال ارسال: ${i+1} از ${contacts.size}  |  موفق: $ok  |  ناموفق: $fail"
                status.text="● در حال ارسال"
                status.setTextColor(col(R.color.app_amber))
            }
            if(i<contacts.lastIndex&&interval>0)delay(interval*1000L)
        }
        withContext(Dispatchers.Main){
            status.text="● ارسال پایان یافت"
            status.setTextColor(col(R.color.app_green))
            progressText.text="ارسال کامل شد — موفق: $ok | ناموفق: $fail"
            toast("ارسال پایان یافت.")
        }
    }

    private fun selectSim() {
        if(android.os.Build.VERSION.SDK_INT<22){toast("انتخاب سیم‌کارت در این نسخه اندروید پشتیبانی نمی‌شود.");return}
        if(!hasPhonePerm()){
            permLauncher.launch(arrayOf(android.Manifest.permission.SEND_SMS,android.Manifest.permission.READ_PHONE_STATE))
            toast("مجوز را تأیید کنید و دوباره روی انتخاب سیم‌کارت بزنید.")
            return
        }
        val sm=getSystemService(SubscriptionManager::class.java)
        val list=try{sm.activeSubscriptionInfoList?:emptyList()}catch(_:SecurityException){emptyList()}
        if(list.isEmpty()){toast("سیم‌کارت فعالی پیدا نشد.");return}
        val labels=list.map{"${it.displayName} — ${it.number?:"شماره نامشخص"}"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب سیم‌کارت").setItems(labels){_,i->
            selectedSubscriptionId=list[i].subscriptionId;getSharedPreferences("occasions",Context.MODE_PRIVATE).edit().putInt("sub",selectedSubscriptionId).apply()
            status.text="● سیم‌کارت انتخاب شد: ${labels[i]}"
        }.show()
    }

    private fun previewDialog(message:EditText) {
        AlertDialog.Builder(this).setTitle("پیش‌نمایش")
            .setMessage(message.text.toString().replace("{نام}","ابوالفضل").ifBlank{"متنی وارد نشده است."})
            .setPositiveButton("تأیید",null).show()
    }

    private fun schedules() {
        addTitle("زمان‌بندی")
        addText("ارسال‌های آینده را مشاهده یا لغو کنید.")
        addButton("＋  زمان‌بندی جدید"){scheduleDialog()}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        content.addView(list)
        lifecycleScope.launch{
            val rows=db.scheduleDao().all()
            if(rows.isEmpty())addEmpty(list,"زمان‌بندی‌ای ثبت نشده است.")
            rows.forEach{s->
                val d=fmtJalali(s.scheduledAt)
                list.addView(scheduleCard(s,d))
            }
        }
    }

    private fun scheduleCard(s:ScheduleEntity,date:String):View {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=rtl;setPadding(dp(14),dp(12),dp(14),dp(12));setBackgroundResource(R.drawable.card_bg)}
        box.addView(TextView(this).apply{text="⏰ $date";textSize=17f})
        box.addView(TextView(this).apply{text="گروه: ${s.groupName.ifBlank{"همه"}}  •  وضعیت: ${scheduleStatus(s.status)}${repeatLabel(s.id)}";textSize=14f})
        val cancel=Button(this).apply{text="لغو این زمان‌بندی";isEnabled=s.status=="SCHEDULED";setOnClickListener{
            if(s.workRequestId.isNotBlank())try{WorkManager.getInstance(this@MainActivity).cancelWorkById(UUID.fromString(s.workRequestId))}catch(_:Exception){}
            lifecycleScope.launch{db.scheduleDao().setStatus(s.id,"CANCELLED");schedules()}
        }}
        box.addView(cancel)
        return box
    }

    private fun scheduleStatus(s:String)=when(s){"SCHEDULED"->"در انتظار";"COMPLETED"->"انجام‌شده";"CANCELLED"->"لغوشده";"FAILED"->"ناموفق";else->s}

    private fun scheduleDialog() {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=rtl}
        val group=styledInput("گروه — خالی یعنی همه")
        val msg=styledInput("متن پیام",4)
        val interval=styledInput("فاصله بین پیام‌ها به ثانیه").apply{setText("3")}
        val rep=android.widget.Spinner(this).apply{adapter=android.widget.ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("بدون تکرار","تکرار روزانه","تکرار هفتگی","تکرار ماهانه"));layoutDirection=rtl}
        box.addView(group);box.addView(msg);box.addView(interval);box.addView(rep)
        AlertDialog.Builder(this).setTitle("زمان‌بندی پیامک").setView(box)
            .setPositiveButton("انتخاب تاریخ و ساعت"){_,_->pickDateTime(group.text.toString(),msg.text.toString(),interval.text.toString().toIntOrNull()?:3,arrayOf("NONE","DAILY","WEEKLY","MONTHLY")[rep.selectedItemPosition])}
            .setNegativeButton("انصراف",null).show()
    }

    private fun pickDateTime(group:String,msg:String,interval:Int,repeat:String="NONE"){
        pickJalaliDate{gy,gm,gd->
            val now=Calendar.getInstance()
            TimePickerDialog(this,{_,h,min->
                val cal=Calendar.getInstance().apply{set(gy,gm-1,gd,h,min,0);set(Calendar.MILLISECOND,0)}
                val delayMs=(cal.timeInMillis-System.currentTimeMillis()).coerceAtLeast(1000L)
                lifecycleScope.launch(Dispatchers.IO){
                    val id=db.scheduleDao().insert(ScheduleEntity(groupName=group,message=msg,scheduledAt=cal.timeInMillis,intervalSeconds=interval,subscriptionId=selectedSubscriptionId))
                    val req=OneTimeWorkRequestBuilder<ScheduledSmsWorker>().setInputData(workDataOf("group" to group,"message" to msg,"interval" to interval,"subscriptionId" to selectedSubscriptionId,"scheduleId" to id)).setInitialDelay(delayMs,TimeUnit.MILLISECONDS).build()
                    getSharedPreferences("repeat",Context.MODE_PRIVATE).edit().putString("repeat_$id",repeat).apply()
                    db.scheduleDao().update(ScheduleEntity(id,group,msg,cal.timeInMillis,interval,selectedSubscriptionId,req.id.toString(),"SCHEDULED"))
                    WorkManager.getInstance(this@MainActivity).enqueue(req)
                    withContext(Dispatchers.Main){toast("زمان‌بندی با موفقیت ثبت شد.")}
                }
            },now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE),true).show()
        }
    }

    private fun reports(){
        addTitle("گزارش‌ها")
        val chart=SimpleBarChart(this).apply{textColor=col(R.color.app_t3)}
        content.addView(chart,LinearLayout.LayoutParams(-1,dp(170)).apply{setMargins(0,dp(6),0,dp(10))})
        val summary=TextView(this).apply{textSize=13f;setTextColor(col(R.color.app_t3));gravity=Gravity.RIGHT;setPadding(0,0,0,dp(6))}
        content.addView(summary)
        val spP=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("امروز","۷ روز اخیر","۳۰ روز اخیر","همه زمان‌ها"));setSelection(1);layoutDirection=rtl}
        val spS=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("همه وضعیت‌ها","ارسال‌شده و تحویل‌شده","فقط تحویل‌شده","ناموفق","لغو توسط مخاطب"));layoutDirection=rtl}
        val search=styledInput("🔎 جستجوی شماره یا متن")
        content.addView(spP);content.addView(spS);content.addView(search,marginParams())
        addButton("📄  ذخیره گزارش CSV (قابل باز شدن در اکسل)"){exportLauncher.launch("گزارش_پیامک.csv")}
        addButton("🗑  پاک کردن تاریخچه"){
            AlertDialog.Builder(this).setTitle("پاک کردن تاریخچه").setMessage("همه گزارش‌ها حذف شوند؟").setPositiveButton("بله"){_,_->lifecycleScope.launch{db.sendLogDao().clear();reports()}}.setNegativeButton("خیر",null).show()
        }
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        content.addView(list)
        lifecycleScope.launch{
            val all=withContext(Dispatchers.IO){db.sendLogDao().latest(20000)}
            val dayMs=86400000L
            val todayStart=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
            fun isOk(st:String)=st==Status.SENT||st==Status.DELIVERED||st==Status.UNDELIVERED||st=="موفق"
            val labels=ArrayList<String>();val okC=ArrayList<Int>();val failC=ArrayList<Int>()
            for(k in 6 downTo 0){
                val a=todayStart-k*dayMs;val b=a+dayMs
                val dayRows=all.filter{it.timestamp>=a&&it.timestamp<b}
                labels.add(fmtJalali(a,false).substring(5))
                okC.add(dayRows.count{isOk(it.status)})
                failC.add(dayRows.count{it.status.startsWith(Status.FAIL)})
            }
            chart.labels=labels;chart.ok=okC;chart.fail=failC;chart.invalidate()
            fun render(){
                val now=System.currentTimeMillis()
                val since=when(spP.selectedItemPosition){0->todayStart;1->now-7*dayMs;2->now-30*dayMs;else->0L}
                val st=spS.selectedItemPosition
                val q=toAsciiDigits(search.text.toString()).trim()
                val rows=all.filter{r->
                    r.timestamp>=since&&(when(st){
                        0->true
                        1->isOk(r.status)
                        2->r.status==Status.DELIVERED
                        3->r.status.startsWith(Status.FAIL)
                        else->r.status.startsWith("لغو")
                    })&&(q.isEmpty()||r.phone.contains(q)||r.message.contains(q))
                }
                summary.text="${rows.size} مورد  •  ✅ ${rows.count{isOk(it.status)}}  •  ❌ ${rows.count{it.status.startsWith(Status.FAIL)}}"
                list.removeAllViews()
                if(rows.isEmpty())addEmpty(list,"گزارشی با این فیلتر پیدا نشد.")
                rows.take(200).forEach{
                    list.addView(TextView(this@MainActivity).apply{text="${fmtJalali(it.timestamp)}\n${it.phone}  •  ${it.status}\n${it.message}";textSize=14f;setPadding(0,10,0,10)})
                }
                if(rows.size>200)list.addView(TextView(this@MainActivity).apply{text="۲۰۰ مورد اول نمایش داده شد؛ برای همه موارد، CSV بگیرید.";textSize=13f;setTextColor(col(R.color.app_t4));gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(10))})
            }
            spP.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){render()}
                override fun onNothingSelected(p:AdapterView<*>?){}
            }
            spS.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){render()}
                override fun onNothingSelected(p:AdapterView<*>?){}
            }
            search.addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){render()}
                override fun afterTextChanged(e:android.text.Editable?){}
            })
            render()
        }
    }

    private fun settings(){
        addTitle("تنظیمات")
        addSection("تنظیمات ارسال")
        addButton("📱  انتخاب سیم‌کارت"){selectSim()}
        addButton("↩  استفاده از سیم‌کارت پیش‌فرض"){selectedSubscriptionId=-1;getSharedPreferences("occasions",Context.MODE_PRIVATE).edit().putInt("sub",-1).apply();status.text="● سیم‌کارت پیش‌فرض فعال شد";toast("سیم‌کارت پیش‌فرض انتخاب شد.")}
        val sp=getSharedPreferences("settings",Context.MODE_PRIVATE)
        addText("حداکثر ارسال در روز (۰ = نامحدود)")
        val lim=styledInput("مثلاً 200").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(sp.getInt("daily_limit",200).toString())}
        content.addView(lim,marginParams())
        addText("حداکثر تأخیر تصادفی اضافه بین پیام‌ها (ثانیه)")
        val jit=styledInput("مثلاً 3").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(sp.getInt("jitter",3).toString())}
        content.addView(jit,marginParams())
        addButton("💾  ذخیره محدودیت‌ها"){
            sp.edit().putInt("daily_limit",toAsciiDigits(lim.text.toString()).toIntOrNull()?.coerceAtLeast(0)?:200)
                .putInt("jitter",toAsciiDigits(jit.text.toString()).toIntOrNull()?.coerceAtLeast(0)?:3).apply()
            toast("ذخیره شد.")
        }
        addButton("🎂  مناسبت‌ها و تولد"){occasionsMenu()}
        addButton("🌓  حالت نمایش (روشن / تیره / خودکار)"){themeDialog()}
        addButton("🔒  رمز و قفل برنامه"){lockSettings()}
        addButton("💽  گرفتن پشتیبان (مخاطبین، قالب‌ها، تنظیمات)"){backupLauncher.launch("bulksms_backup.json")}
        addButton("♻  بازیابی از فایل پشتیبان"){restoreLauncher.launch(arrayOf("*/*"))}
        addButton("🔐  مجوزها (باز کردن تنظیمات برنامه)"){openAppSettings()}
        addSection("اطلاعات برنامه")
        addText("زبان: فارسی")
        addText("جهت برنامه: راست‌به‌چپ")
        addText("نسخه: ۳.۰.۰")
        addText("سازنده: نوید بلانیان")
        addSection("حریم و رضایت")
        addText("ارسال فقط برای مخاطبانی انجام می‌شود که اجازه دریافت پیام دارند. مخاطبانِ دارای عدم دریافت از ارسال حذف می‌شوند.")
    }

    private fun addTitle(t:String){content.addView(TextView(this).apply{text=t;textSize=24f;setTextColor(col(R.color.app_t1));gravity=Gravity.RIGHT;setPadding(0,dp(5),0,dp(4))})}
    private fun addSection(t:String){content.addView(TextView(this).apply{text=t;textSize=17f;setTextColor(col(R.color.app_t2));gravity=Gravity.RIGHT;setPadding(0,dp(16),0,dp(7))})}
    private fun addText(t:String){content.addView(TextView(this).apply{text=t;textSize=15f;setTextColor(col(R.color.app_t3));gravity=Gravity.RIGHT;setPadding(0,dp(5),0,dp(5))})}
    private val BLUE = Color.rgb(37, 99, 235)
    private fun addButton(t:String,a:()->Unit){content.addView(Button(this).apply{
        text=t;textSize=15f;isAllCaps=false;setTextColor(Color.WHITE)
        background=android.graphics.drawable.GradientDrawable().apply{cornerRadius=dp(14).toFloat();setColor(BLUE)}
        setPadding(dp(14),dp(12),dp(14),dp(12))
        setOnClickListener{a()};layoutDirection=rtl},marginParams())}
    private fun statCard(label:String,value:String,icon:String):View{
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.RIGHT;setPadding(dp(14),dp(13),dp(14),dp(13));setBackgroundResource(R.drawable.card_bg)}
        box.addView(TextView(this).apply{text="$icon  $label";textSize=14f;setTextColor(col(R.color.app_t3));gravity=Gravity.RIGHT})
        box.addView(TextView(this).apply{text=value;textSize=26f;setTextColor(col(R.color.app_t1));gravity=Gravity.RIGHT})
        return box
    }
    private fun addActionCard(icon:String,title:String,desc:String,a:()->Unit){
        val box=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=rtl;setPadding(dp(12),dp(6),dp(12),dp(6));setBackgroundResource(R.drawable.card_bg);setOnClickListener{a()}}
        box.addView(TextView(this).apply{text=icon;textSize=27f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(44),dp(44)))
        val txt=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.RIGHT}
        txt.addView(TextView(this).apply{text=title;textSize=17f;setTextColor(col(R.color.app_t1));gravity=Gravity.RIGHT})
        txt.addView(TextView(this).apply{text=desc;textSize=13f;setTextColor(col(R.color.app_t4));gravity=Gravity.RIGHT})
        box.addView(txt,LinearLayout.LayoutParams(0,-2,1f))
        content.addView(box,marginParams())
    }
    private fun styledInput(hint:String,lines:Int=1)=EditText(this).apply{this.hint=hint;minLines=lines;setBackgroundResource(R.drawable.input_bg);layoutDirection=rtl;textDirection=View.TEXT_DIRECTION_RTL;setPadding(dp(12),dp(10),dp(12),dp(10))}
    private fun addEmpty(parent:LinearLayout,text:String){parent.addView(TextView(this).apply{this.text=text;textSize=15f;gravity=Gravity.CENTER;setPadding(0,dp(25),0,dp(25));setTextColor(col(R.color.app_t4))})}
    private fun marginParams()=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(5))}
    private fun weightParams()=LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(4),dp(4),dp(4),dp(4))}
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun toast(t:String)=Toast.makeText(this,t,Toast.LENGTH_SHORT).show()
}
