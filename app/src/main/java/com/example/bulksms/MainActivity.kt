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

    private val rtl = android.view.View.LAYOUT_DIRECTION_RTL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.get(this)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtl
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(246,248,252))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = rtl
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.RIGHT }
        titleBox.addView(TextView(this).apply {
            text = "پیامک‌یار"
            textSize = 27f
            setTextColor(Color.rgb(15,23,42))
        })
        titleBox.addView(TextView(this).apply {
            text = "مدیریت هوشمند پیامک"
            textSize = 13f
            setTextColor(Color.rgb(100,116,139))
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "۲.۳"
            textSize = 13f
            setTextColor(Color.rgb(71,85,105))
            setPadding(dp(10),dp(7),dp(10),dp(7))
            setBackgroundResource(com.example.bulksms.R.drawable.card_bg)
        })
        root.addView(header)

        status = TextView(this).apply {
            text = "● آماده به کار"
            textSize = 13f
            setTextColor(Color.rgb(22,101,52))
            gravity = Gravity.RIGHT
            setPadding(0, dp(10), 0, dp(7))
        }
        root.addView(status)

        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = rtl
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = rtl }
        listOf("داشبورد","مخاطبین","ارسال","زمان‌بندی","گزارش‌ها","تنظیمات").forEachIndexed { i,t ->
            val b = TextView(this).apply {
                text=t
                textSize=14f
                gravity=Gravity.CENTER
                setTextColor(Color.rgb(30,41,59))
                setPadding(dp(14),dp(10),dp(14),dp(10))
                setOnClickListener { showTab(i) }
            }
            tabs.addView(b)
        }
        tabsScroll.addView(tabs)
        root.addView(tabsScroll)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtl
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1,0,1f))

        setContentView(root)
        showTab(0)
    }

    private fun showTab(tab:Int) {
        content.removeAllViews()
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

    private fun contactCard(c:ContactEntity): View {
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            layoutDirection=rtl
            setPadding(dp(14),dp(12),dp(14),dp(12))
            setBackgroundResource(R.drawable.card_bg)
            setOnClickListener{contactDialog(c)}
        }
        box.addView(TextView(this).apply{text=c.name.ifBlank{"بدون نام"};textSize=17f;setTextColor(Color.rgb(15,23,42))})
        box.addView(TextView(this).apply{text=c.phone;textSize=14f;setTextColor(Color.rgb(71,85,105))})
        box.addView(TextView(this).apply{text="گروه: ${c.groupName.ifBlank{"بدون گروه"}}${if(c.optedOut) "  •  ⛔ عدم دریافت" else ""}";textSize=13f;setTextColor(if(c.optedOut)Color.rgb(185,28,28)Color.rgb(71,85,105))})
        return box
    }

    private fun contactDialog(existing: ContactEntity?) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = rtl
    }

    val name = EditText(this).apply {
        hint = "نام و نام خانوادگی"
        setText(existing?.name ?: "")
        setBackgroundResource(R.drawable.input_bg)
    }

    val phone = EditText(this).apply {
        hint = "شماره موبایل"
        setText(existing?.phone ?: "")
        setBackgroundResource(R.drawable.input_bg)
    }

    val group = EditText(this).apply {
        hint = "گروه"
        setText(existing?.groupName ?: "")
        setBackgroundResource(R.drawable.input_bg)
    }

    box.addView(name, marginParams())
    box.addView(phone, marginParams())
    box.addView(group, marginParams())

    val dialog = AlertDialog.Builder(this)
        .setTitle(if (existing == null) "افزودن مخاطب" else "ویرایش مخاطب")
        .setView(box)
        .setPositiveButton("ذخیره") { _, _ ->
            val p = phone.text.toString().trim()
            if (p.isBlank()) {
                toast("شماره موبایل را وارد کنید.")
                return@setPositiveButton
            }

            lifecycleScope.launch {
                db.contactDao().upsert(
                    ContactEntity(
                        existing?.id ?: 0,
                        name.text.toString().trim(),
                        p,
                        group.text.toString().trim(),
                        existing?.optedOut ?: false
                    )
                )
                showTab(1)
            }
        }
        .setNeutralButton(
            if (existing?.optedOut == true) "فعال‌سازی دریافت" else "عدم دریافت"
        ) { _, _ ->
            if (existing != null) {
                lifecycleScope.launch {
                    db.contactDao().setOptOut(
                        existing.phone,
                        !existing.optedOut
                    )
                    showTab(1)
                }
            }
        }
        .setNegativeButton(
            if (existing == null) "انصراف" else "حذف"
        ) { _, _ ->
            if (existing != null) {
                lifecycleScope.launch {
                    db.contactDao().deleteById(existing.id)
                    showTab(1)
                }
            }
        }
        .create()

    dialog.show()
}

private fun sending() {
        addTitle("ارسال پیامک")
        addText("پیام خود را آماده کنید و قبل از ارسال تعداد گیرندگان را بررسی کنید.")
        val group=styledInput("گروه — خالی یعنی همه")
        val message=styledInput("متن پیام",5)
        val interval=styledInput("فاصله بین پیام‌ها به ثانیه").apply{setText("3")}
        content.addView(group);content.addView(message);content.addView(interval)
        addButton("📱  انتخاب سیم‌کارت"){selectSim()}
        addButton("📝  قالب‌های آماده"){templateDialog(message)}
        addButton("👁  پیش‌نمایش"){previewDialog(message)}
        addButton("🚀  شروع ارسال"){
            val text=message.text.toString()
            if(text.isBlank()){toast("متن پیام را وارد کنید.");return@addButton}
            confirmSend(group.text.toString().trim(),text,interval.text.toString().toIntOrNull()?.coerceAtLeast(0)?:3)
        }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0}
        content.addView(progress,marginParams())
        progressText=TextView(this).apply{text="آماده ارسال";gravity=Gravity.RIGHT}
        content.addView(progressText)
        addText("متغیر شخصی‌سازی: {نام}  ← نام مخاطب را جایگزین می‌کند.")
        addText("⛔ مخاطبانی که عدم دریافت را انتخاب کرده‌اند، ارسال دریافت نمی‌کنند.")
    }

    private fun confirmSend(group:String,text:String,interval:Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val contacts=db.contactDao().eligible(group)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تأیید ارسال")
                    .setMessage("گیرندگان مجاز: ${contacts.size}\nفاصله: $interval ثانیه\n\nارسال شروع شود؟")
                    .setPositiveButton("شروع"){_,_->lifecycleScope.launch(Dispatchers.IO){sendMessages(contacts,text,group,interval)}}
                    .setNegativeButton("انصراف",null).show()
            }
        }
    }

    private suspend fun sendMessages(contacts:List<ContactEntity>,template:String,group:String,interval:Int) {
        if(contacts.isEmpty()){withContext(Dispatchers.Main){toast("مخاطب واجد شرایطی پیدا نشد.")};return}
        val sms=if(selectedSubscriptionId>=0)SmsManager.getSmsManagerForSubscriptionId(selectedSubscriptionId)else SmsManager.getDefault()
        var ok=0;var fail=0
        withContext(Dispatchers.Main){progress.max=contacts.size;progress.progress=0}
        contacts.forEachIndexed{i,c->
            val text=template.replace("{نام}",c.name.ifBlank{"دوست عزیز"})
            try{sms.sendTextMessage(c.phone,null,text,null,null);db.sendLogDao().insert(SendLogEntity(phone=c.phone,message=text,groupName=group,status="موفق"));ok++}
            catch(e:Exception){db.sendLogDao().insert(SendLogEntity(phone=c.phone,message=text,groupName=group,status="ناموفق: ${e.message?:"خطای نامشخص"}"));fail++}
            withContext(Dispatchers.Main){
                progress.progress=i+1
                progressText.text="در حال ارسال: ${i+1} از ${contacts.size}  |  موفق: $ok  |  ناموفق: $fail"
                status.text="● در حال ارسال"
                status.setTextColor(Color.rgb(180,83,9))
            }
            if(i<contacts.lastIndex&&interval>0)delay(interval*1000L)
        }
        withContext(Dispatchers.Main){
            status.text="● ارسال پایان یافت"
            status.setTextColor(Color.rgb(22,101,52))
            progressText.text="ارسال کامل شد — موفق: $ok | ناموفق: $fail"
            toast("ارسال پایان یافت.")
        }
    }

    private fun selectSim() {
        if(android.os.Build.VERSION.SDK_INT<22){toast("انتخاب سیم‌کارت در این نسخه اندروید پشتیبانی نمی‌شود.");return}
        val sm=getSystemService(SubscriptionManager::class.java)
        val list=try{sm.activeSubscriptionInfoList?:emptyList()}catch(_:SecurityException){emptyList()}
        if(list.isEmpty()){toast("سیم‌کارت فعالی پیدا نشد.");return}
        val labels=list.map{"${it.displayName} — ${it.number?:"شماره نامشخص"}"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب سیم‌کارت").setItems(labels){_,i->
            selectedSubscriptionId=list[i].subscriptionId
            status.text="● سیم‌کارت انتخاب شد: ${labels[i]}"
        }.show()
    }

    private fun templateDialog(message:EditText) {
        val templates=arrayOf(
            "سلام {نام}، تخفیف ویژه امروز ما را از دست ندهید.",
            "سلام {نام}، برای شما یک پیشنهاد ویژه داریم.",
            "مشتری گرامی {نام}، از خرید شما سپاسگزاریم."
        )
        AlertDialog.Builder(this).setTitle("قالب‌های آماده").setItems(templates){_,i->message.setText(templates[i])}.show()
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
                val d=SimpleDateFormat("yyyy/MM/dd — HH:mm",Locale.getDefault()).format(Date(s.scheduledAt))
                list.addView(scheduleCard(s,d))
            }
        }
    }

    private fun scheduleCard(s:ScheduleEntity,date:String):View {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=rtl;setPadding(dp(14),dp(12),dp(14),dp(12));setBackgroundResource(R.drawable.card_bg)}
        box.addView(TextView(this).apply{text="⏰ $date";textSize=17f})
        box.addView(TextView(this).apply{text="گروه: ${s.groupName.ifBlank{"همه"}}  •  وضعیت: ${scheduleStatus(s.status)}";textSize=14f})
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
        box.addView(group);box.addView(msg);box.addView(interval)
        AlertDialog.Builder(this).setTitle("زمان‌بندی پیامک").setView(box)
            .setPositiveButton("انتخاب تاریخ و ساعت"){_,_->pickDateTime(group.text.toString(),msg.text.toString(),interval.text.toString().toIntOrNull()?:3)}
            .setNegativeButton("انصراف",null).show()
    }

    private fun pickDateTime(group:String,msg:String,interval:Int){
        val now=Calendar.getInstance()
        DatePickerDialog(this,{_,y,m,d->
            TimePickerDialog(this,{_,h,min->
                val cal=Calendar.getInstance().apply{set(y,m,d,h,min,0);set(Calendar.MILLISECOND,0)}
                val delayMs=(cal.timeInMillis-System.currentTimeMillis()).coerceAtLeast(1000L)
                lifecycleScope.launch(Dispatchers.IO){
                    val id=db.scheduleDao().insert(ScheduleEntity(groupName=group,message=msg,scheduledAt=cal.timeInMillis,intervalSeconds=interval,subscriptionId=selectedSubscriptionId))
                    val req=OneTimeWorkRequestBuilder<ScheduledSmsWorker>().setInputData(workDataOf("group" to group,"message" to msg,"interval" to interval,"subscriptionId" to selectedSubscriptionId,"scheduleId" to id)).setInitialDelay(delayMs,TimeUnit.MILLISECONDS).build()
                    db.scheduleDao().update(ScheduleEntity(id,group,msg,cal.timeInMillis,interval,selectedSubscriptionId,req.id.toString(),"SCHEDULED"))
                    WorkManager.getInstance(this@MainActivity).enqueue(req)
                    withContext(Dispatchers.Main){toast("زمان‌بندی با موفقیت ثبت شد.")}
                }
            },now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE),true).show()
        },now.get(Calendar.YEAR),now.get(Calendar.MONTH),now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun reports(){
        addTitle("گزارش‌ها")
        addButton("📄  ذخیره گزارش CSV"){exportLauncher.launch("گزارش_پیامک.csv")}
        addButton("🗑  پاک کردن تاریخچه"){
            AlertDialog.Builder(this).setTitle("پاک کردن تاریخچه").setMessage("همه گزارش‌ها حذف شوند؟").setPositiveButton("بله"){_,_->lifecycleScope.launch{db.sendLogDao().clear();reports()}}.setNegativeButton("خیر",null).show()
        }
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        content.addView(list)
        lifecycleScope.launch{
            val rows=db.sendLogDao().latest(300)
            if(rows.isEmpty())addEmpty(list,"هنوز گزارشی ثبت نشده است.")
            rows.forEach{
                val d=SimpleDateFormat("yyyy/MM/dd HH:mm:ss",Locale.getDefault()).format(Date(it.timestamp))
                list.addView(TextView(this@MainActivity).apply{text="$d\n${it.phone}  •  ${it.status}\n${it.message}";textSize=14f;setPadding(0,10,0,10)})
            }
        }
    }

    private fun settings(){
        addTitle("تنظیمات")
        addSection("تنظیمات ارسال")
        addButton("📱  انتخاب سیم‌کارت"){selectSim()}
        addButton("↩  استفاده از سیم‌کارت پیش‌فرض"){selectedSubscriptionId=-1;status.text="● سیم‌کارت پیش‌فرض فعال شد";toast("سیم‌کارت پیش‌فرض انتخاب شد.")}
        addSection("اطلاعات برنامه")
        addText("زبان: فارسی")
        addText("جهت برنامه: راست‌به‌چپ")
        addText("نسخه: ۲.۳.۰")
        addSection("حریم و رضایت")
        addText("ارسال فقط برای مخاطبانی انجام می‌شود که اجازه دریافت پیام دارند. مخاطبانِ دارای عدم دریافت از ارسال حذف می‌شوند.")
    }

    private fun addTitle(t:String){content.addView(TextView(this).apply{text=t;textSize=24f;setTextColor(Color.rgb(15,23,42));gravity=Gravity.RIGHT;setPadding(0,dp(5),0,dp(4))})}
    private fun addSection(t:String){content.addView(TextView(this).apply{text=t;textSize=17f;setTextColor(Color.rgb(51,65,85));gravity=Gravity.RIGHT;setPadding(0,dp(16),0,dp(7))})}
    private fun addText(t:String){content.addView(TextView(this).apply{text=t;textSize=15f;setTextColor(Color.rgb(71,85,105));gravity=Gravity.RIGHT;setPadding(0,dp(5),0,dp(5))})}
    private fun addButton(t:String,a:()->Unit){content.addView(Button(this).apply{text=t;textSize=15f;setOnClickListener{a()};layoutDirection=rtl},marginParams())}
    private fun statCard(label:String,value:String,icon:String):View{
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.RIGHT;setPadding(dp(14),dp(13),dp(14),dp(13));setBackgroundResource(R.drawable.card_bg)}
        box.addView(TextView(this).apply{text="$icon  $label";textSize=14f;setTextColor(Color.rgb(71,85,105));gravity=Gravity.RIGHT})
        box.addView(TextView(this).apply{text=value;textSize=26f;setTextColor(Color.rgb(15,23,42));gravity=Gravity.RIGHT})
        return box
    }
    private fun addActionCard(icon:String,title:String,desc:String,a:()->Unit){
        val box=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=rtl;setPadding(dp(14),dp(12),dp(14),dp(12));setBackgroundResource(R.drawable.card_bg);setOnClickListener{a()}}
        box.addView(TextView(this).apply{text=icon;textSize=27f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(48),dp(55)))
        val txt=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.RIGHT}
        txt.addView(TextView(this).apply{text=title;textSize=17f;setTextColor(Color.rgb(15,23,42));gravity=Gravity.RIGHT})
        txt.addView(TextView(this).apply{text=desc;textSize=13f;setTextColor(Color.rgb(100,116,139));gravity=Gravity.RIGHT})
        box.addView(txt,LinearLayout.LayoutParams(0,-2,1f))
        content.addView(box,marginParams())
    }
    private fun styledInput(hint:String,lines:Int=1)=EditText(this).apply{this.hint=hint;minLines=lines;setBackgroundResource(R.drawable.input_bg);layoutDirection=rtl;textDirection=View.TEXT_DIRECTION_RTL;setPadding(dp(12),dp(10),dp(12),dp(10))}
    private fun addEmpty(parent:LinearLayout,text:String){parent.addView(TextView(this).apply{this.text=text;textSize=15f;gravity=Gravity.CENTER;setPadding(0,dp(25),0,dp(25));setTextColor(Color.rgb(100,116,139))})}
    private fun marginParams()=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(5))}
    private fun weightParams()=LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(4),dp(4),dp(4),dp(4))}
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun toast(t:String)=Toast.makeText(this,t,Toast.LENGTH_SHORT).show()
}
