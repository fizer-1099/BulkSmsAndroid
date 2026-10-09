import pathlib
p = pathlib.Path("app/src/main/java/com/example/bulksms/MainActivity.kt")
s = p.read_text()
def rep(old, new):
    global s
    assert old in s, "NOT FOUND: " + old[:60]
    s = s.replace(old, new, 1)

# permissions incl. notifications
rep("permLauncher.launch(arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE))",
    "permLauncher.launch(allPerms())")
rep("    private fun openAppSettings() {", r'''    private fun allPerms(): Array<String> {
        val l = mutableListOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE)
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

    private fun openAppSettings() {''')

rep('{_,_->lifecycleScope.launch(Dispatchers.IO){sendMessages(contacts,text,group,interval)}}', '{_,_->startCampaign(contacts,text,group,interval)}')

# sending tab UI
rep('val interval=styledInput("فاصله بین پیام‌ها به ثانیه").apply{setText("3")}',
    'val interval=styledInput("فاصله بین پیام‌ها به ثانیه").apply{setText(getSharedPreferences("settings",Context.MODE_PRIVATE).getInt("interval",3).toString())}')
rep("        content.addView(group);content.addView(message);content.addView(interval)", r'''        content.addView(group);content.addView(message)
        val counter=TextView(this).apply{textSize=12f;gravity=Gravity.RIGHT;setTextColor(Color.rgb(100,116,139));text="0 کاراکتر"}
        content.addView(counter)
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
        content.addView(interval)''')
rep('''        content.addView(progressText)
''', '''        content.addView(progressText)
        addButton("▶  ادامه ارسال صف"){launchService()}
        addButton("🔁  ارسال مجدد به ناموفق‌ها"){resendFailed()}
        addButton("⏹  لغو صف باقی‌مانده"){cancelQueue()}
        startPolling()
''')
rep("    private fun showTab(tab:Int) {\n", "    private fun showTab(tab:Int) {\n        pollJob?.cancel()\n")

# settings
rep('        addButton("🔐  مجوزها (باز کردن تنظیمات برنامه)"){openAppSettings()}', r'''        val sp=getSharedPreferences("settings",Context.MODE_PRIVATE)
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
        addButton("🔐  مجوزها (باز کردن تنظیمات برنامه)"){openAppSettings()}''')
s=s.replace('addText("نسخه: ۲.۳.۰")','addText("نسخه: ۳.۰.۰")')
p.write_text(s)

b = pathlib.Path("app/build.gradle.kts")
t = b.read_text()
t = t.replace("versionCode = 24", "versionCode = 26").replace('versionName = "2.4.0"', 'versionName = "2.6.0"')
b.write_text(t)
print("OK")
