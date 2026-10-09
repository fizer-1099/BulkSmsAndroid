import pathlib
root = pathlib.Path(".")
J = root / "app/src/main/java/com/example/bulksms"
R = root / "app/src/main/res"
out = {}

# ---------------- resources ----------------
colors_light = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="app_bg">#F6F8FC</color>
    <color name="app_card">#FFFFFF</color>
    <color name="app_card_stroke">#E2E8F0</color>
    <color name="app_input_fill">#F8FAFC</color>
    <color name="app_input_stroke">#CBD5E1</color>
    <color name="app_t1">#0F172A</color>
    <color name="app_t2">#334155</color>
    <color name="app_t3">#475569</color>
    <color name="app_t4">#64748B</color>
    <color name="app_green">#166534</color>
    <color name="app_amber">#B45309</color>
    <color name="app_red">#B91C1C</color>
</resources>
'''
colors_dark = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="app_bg">#0B1220</color>
    <color name="app_card">#162032</color>
    <color name="app_card_stroke">#2A3A52</color>
    <color name="app_input_fill">#111B2E</color>
    <color name="app_input_stroke">#3B4C66</color>
    <color name="app_t1">#F1F5F9</color>
    <color name="app_t2">#CBD5E1</color>
    <color name="app_t3">#A8B5C8</color>
    <color name="app_t4">#8696AD</color>
    <color name="app_green">#4ADE80</color>
    <color name="app_amber">#FBBF24</color>
    <color name="app_red">#F87171</color>
</resources>
'''
out[R / "values/colors.xml"] = colors_light
out[R / "values-night/colors.xml"] = colors_dark
out[R / "drawable/card_bg.xml"] = '''<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="18dp"/>
    <solid android:color="@color/app_card"/>
    <stroke android:width="1dp" android:color="@color/app_card_stroke"/>
    <padding android:left="16dp" android:top="14dp" android:right="16dp" android:bottom="14dp"/>
</shape>
'''
out[R / "drawable/input_bg.xml"] = '''<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="14dp"/>
    <solid android:color="@color/app_input_fill"/>
    <stroke android:width="1dp" android:color="@color/app_input_stroke"/>
    <padding android:left="12dp" android:top="10dp" android:right="12dp" android:bottom="10dp"/>
</shape>
'''
out[R / "drawable/nav_bg.xml"] = '''<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="20dp"/>
    <solid android:color="@color/app_card"/>
    <stroke android:width="1dp" android:color="@color/app_card_stroke"/>
    <padding android:left="4dp" android:top="2dp" android:right="4dp" android:bottom="2dp"/>
</shape>
'''

# ---------------- Jalali ----------------
out[J / "Jalali.kt"] = r'''package com.example.bulksms

object Jalali {
    fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    fun toGregorian(jy0: Int, jm: Int, jd: Int): IntArray {
        val jy = jy0 + 1595
        var days = -355668 + (365 * jy) + ((jy / 33) * 8) + (((jy % 33) + 3) / 4) + jd +
            (if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186)
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            days -= 1
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days += 1
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val leap = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)
        val sal = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 13) {
            val v = sal[gm]
            if (gd <= v) break
            gd -= v
            gm++
        }
        return intArrayOf(gy, gm, gd)
    }
}
'''

# ---------------- Bar chart ----------------
out[J / "SimpleBarChart.kt"] = r'''package com.example.bulksms

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class SimpleBarChart(ctx: Context) : View(ctx) {
    var labels: List<String> = emptyList()
    var ok: List<Int> = emptyList()
    var fail: List<Int> = emptyList()
    var textColor: Int = Color.GRAY
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = labels.size
        if (n == 0 || ok.size < n || fail.size < n) return
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        var maxV = 1
        for (i in 0 until n) maxV = maxOf(maxV, ok[i] + fail[i])
        val topPad = 18 * d
        val bottomPad = 24 * d
        val chartH = h - topPad - bottomPad
        val slot = w / n
        val barW = slot * 0.5f
        p.textSize = 11 * d
        p.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val okH = chartH * ok[i] / maxV
            val failH = chartH * fail[i] / maxV
            val base = topPad + chartH
            p.color = Color.rgb(37, 99, 235)
            c.drawRoundRect(cx - barW / 2, base - okH, cx + barW / 2, base, 5 * d, 5 * d, p)
            p.color = Color.rgb(220, 38, 38)
            c.drawRect(cx - barW / 2, base - okH - failH, cx + barW / 2, base - okH, p)
            p.color = textColor
            c.drawText(labels[i], cx, h - 6 * d, p)
            val total = ok[i] + fail[i]
            if (total > 0) c.drawText(total.toString(), cx, base - okH - failH - 4 * d, p)
        }
        p.color = textColor
        p.strokeWidth = d
        c.drawLine(0f, topPad + chartH, w, topPad + chartH, p)
    }
}
'''

# ---------------- gradle ----------------
gp = root / "app/build.gradle.kts"
g = gp.read_text()
if "androidx.biometric" not in g:
    a = 'implementation("androidx.work:work-runtime-ktx:2.10.0")'
    assert a in g, "NOT FOUND: gradle work dependency"
    g = g.replace(a, a + '\n    implementation("androidx.biometric:biometric:1.1.0")', 1)
out[gp] = g

# ---------------- MainActivity ----------------
mp = J / "MainActivity.kt"
s = mp.read_text()

def rep(old, new):
    global s
    assert old in s, "NOT FOUND: " + old[:70]
    s = s.replace(old, new, 1)

rep("class MainActivity : AppCompatActivity() {", "private var sessionUnlocked = false\n\nclass MainActivity : AppCompatActivity() {")

rep("""    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.get(this)
        buildUi()
""", """    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", 0)) {
                1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        super.onCreate(savedInstanceState)
        db = AppDatabase.get(this)
        if (isLockEnabled() && !sessionUnlocked) showLock() else { sessionUnlocked = true; buildUi(); maybeWelcome() }
""")

rep("    private fun buildUi() {\n", "    private fun buildUi() {\n        tabViews.clear()\n")

# bottom navigation
rep('listOf("داشبورد","مخاطبین","ارسال","زمان‌بندی","گزارش‌ها","تنظیمات")',
    'listOf("🏠\\nداشبورد","👥\\nمخاطبین","✉️\\nارسال","⏰\\nزمان‌بندی","📊\\nگزارش‌ها","⚙️\\nتنظیمات")')
rep("                maxLines=1\n                setOnClickListener { showTab(i) }", "                maxLines=2\n                setOnClickListener { showTab(i) }")
rep("        root.addView(tabs)\n\n        content = ", "\n        content = ")
rep("        setContentView(root)\n        showTab(0)", """        tabs.setBackgroundResource(R.drawable.nav_bg)
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        setContentView(root)
        showTab(0)""")

rep("        content.removeAllViews()\n", "        content.removeAllViews()\n        content.alpha = 0f\n        content.animate().alpha(1f).setDuration(200).start()\n")

# Jalali dates in schedules
rep('SimpleDateFormat("yyyy/MM/dd — HH:mm",Locale.getDefault()).format(Date(s.scheduledAt))', 'fmtJalali(s.scheduledAt)')

# pickDateTime (Jalali)
i1 = s.index("    private fun pickDateTime(")
i2 = s.index("    private fun reports(){")
s = s[:i1] + r'''    private fun pickDateTime(group:String,msg:String,interval:Int,repeat:String="NONE"){
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

''' + s[i2:]

# reports: filters + chart
rep('        addButton("📄  ذخیره گزارش CSV"){exportLauncher.launch("گزارش_پیامک.csv")}', r'''        val chart=SimpleBarChart(this).apply{textColor=col(R.color.app_t3)}
        content.addView(chart,LinearLayout.LayoutParams(-1,dp(170)).apply{setMargins(0,dp(6),0,dp(10))})
        val summary=TextView(this).apply{textSize=13f;setTextColor(col(R.color.app_t3));gravity=Gravity.RIGHT;setPadding(0,0,0,dp(6))}
        content.addView(summary)
        val spP=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("امروز","۷ روز اخیر","۳۰ روز اخیر","همه زمان‌ها"));setSelection(1);layoutDirection=rtl}
        val spS=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("همه وضعیت‌ها","ارسال‌شده و تحویل‌شده","فقط تحویل‌شده","ناموفق","لغو توسط مخاطب"));layoutDirection=rtl}
        val search=styledInput("🔎 جستجوی شماره یا متن")
        content.addView(spP);content.addView(spS);content.addView(search,marginParams())
        addButton("📄  ذخیره گزارش CSV (قابل باز شدن در اکسل)"){exportLauncher.launch("گزارش_پیامک.csv")}''')

j1 = s.index('        lifecycleScope.launch{\n            val rows=db.sendLogDao().latest(300)')
j2 = s.index("    private fun settings(){")
s = s[:j1] + r'''        lifecycleScope.launch{
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

''' + s[j2:]

# settings buttons
rep('        addButton("💽  گرفتن پشتیبان (مخاطبین، قالب‌ها، تنظیمات)")', '''        addButton("🌓  حالت نمایش (روشن / تیره / خودکار)"){themeDialog()}
        addButton("🔒  رمز و قفل برنامه"){lockSettings()}
        addButton("💽  گرفتن پشتیبان (مخاطبین، قالب‌ها، تنظیمات)")''')

# helper block
HELPERS = r'''    private fun col(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)

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

'''
rep("    private fun allPerms(): Array<String> {", HELPERS + "    private fun allPerms(): Array<String> {")

# colors -> resources (global)
m = {
 "Color.rgb(15,23,42)": "col(R.color.app_t1)",
 "Color.rgb(51,65,85)": "col(R.color.app_t2)",
 "Color.rgb(30,41,59)": "col(R.color.app_t2)",
 "Color.rgb(71,85,105)": "col(R.color.app_t3)",
 "Color.rgb(100,116,139)": "col(R.color.app_t4)",
 "Color.rgb(246,248,252)": "col(R.color.app_bg)",
 "Color.rgb(22,101,52)": "col(R.color.app_green)",
 "Color.rgb(180,83,9)": "col(R.color.app_amber)",
 "Color.rgb(185,28,28)": "col(R.color.app_red)",
}
for k, v in m.items():
    s = s.replace(k, v)
out[mp] = s

for path, text in out.items():
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
print("OK")
