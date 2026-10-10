package com.example.bulksms

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                CrashLog.write(applicationContext, t, e)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}

object CrashLog {
    fun dir(ctx: Context): File = File(ctx.filesDir, "crashes").apply { mkdirs() }

    fun write(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val txt = "time: ${Date()}\nversion: $ver\nandroid: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "device: ${Build.MANUFACTURER} ${Build.MODEL}\nthread: ${t.name}\n\n$sw"
        File(dir(ctx), "crash_${System.currentTimeMillis()}.txt").writeText(txt)
        dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
}
