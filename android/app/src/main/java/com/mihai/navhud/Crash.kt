package com.mihai.navhud

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash so it can be read afterwards.
 *
 * A sideloaded app on someone else's head unit has no Play Console and no
 * logcat — a crash is simply "it closed". Writing the trace to a file and
 * showing it on the next launch turns "it crashes for some reason" into an
 * exact line number, which is the difference between fixing it and guessing.
 *
 * Nothing is sent anywhere. The file lives in the app's private storage and is
 * cleared once you have read it.
 */
object Crash {

    private const val FILE = "last_crash.txt"

    fun save(ctx: Context, thread: Thread, e: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { e.printStackTrace(it) }
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        val text = buildString {
            append("NavHUD ").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append(when_).append("  thread: ").append(thread.name).append('\n')
            append("Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")  ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
            append(sw.toString())
        }
        runCatching { File(ctx.filesDir, FILE).writeText(text) }
    }

    fun last(ctx: Context): String? {
        val text = runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) f.readText() else null
        }.getOrNull() ?: return null
        // A crash from a version that is no longer installed is history, not
        // news. The one that started this -- a BootReceiver reading
        // SharedPreferences before the device was unlocked -- was fixed in
        // 1.17, but the file it wrote back in 1.15 outlived the fix and came
        // up on every fresh install for months, reporting a bug that no longer
        // exists and burying anything that does.
        if (!isFromThisBuild(text)) {
            clear(ctx)
            return null
        }
        return text
    }

    /** The version code the report was written by, or null if unreadable. */
    fun versionCodeOf(text: String): Int? {
        // First line is "NavHUD <name> (<code>)".
        val line = text.lineSequence().firstOrNull() ?: return null
        val open = line.lastIndexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close <= open + 1) return null
        return line.substring(open + 1, close).trim().toIntOrNull()
    }

    fun isFromThisBuild(text: String): Boolean =
        versionCodeOf(text) == BuildConfig.VERSION_CODE

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    /** The first few lines: enough to identify the fault at a glance. */
    fun summary(text: String, lines: Int = 8): String =
        text.lineSequence().take(lines).joinToString("\n")
}

class NavHudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Crash.save(this, t, e) }
            // Then let Android do what it was going to do: swallowing the
            // crash would leave the app running in an unknown state, which is
            // worse than the crash.
            previous?.uncaughtException(t, e)
        }
    }
}
