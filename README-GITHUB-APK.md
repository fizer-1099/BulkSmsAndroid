# ساخت APK در GitHub

این نسخه برای Build شدن در GitHub Actions آماده شده است.

## روش استفاده

1. یک Repository جدید در GitHub بسازید، مثلاً:
   `BulkSmsAndroid`
2. تمام فایل‌های این پروژه را در ریشه Repository آپلود کنید.
3. در GitHub به بخش **Actions** بروید.
4. Workflow با نام **Android APK** را باز کنید.
5. در صورت نیاز روی **Run workflow** بزنید.
6. بعد از سبز شدن Build، وارد همان اجرای Workflow شوید.
7. در قسمت **Artifacts** فایل `BulkSmsAndroid-debug-apk` را دانلود کنید.
8. فایل ZIP دانلودشده را باز کنید؛ داخل آن `app-debug.apk` قرار دارد.

## نکته
این Workflow از Gradle نصب‌شده روی runner استفاده می‌کند و برای Build به
`gradle-wrapper.jar` داخل پروژه وابسته نیست.

## ساخت APK در کامپیوتر
اگر Gradle نصب باشد:
```bash
gradle assembleDebug
```

خروجی:
`app/build/outputs/apk/debug/app-debug.apk`
