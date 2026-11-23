package com.stas.castremote

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object CrashCapture {
    const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(context.applicationContext, previous)
        )
    }

    fun readSaved(context: Context): String? {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    private class CrashHandler(
        private val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "Last crash: $stamp\nThread: ${t.name}\n${e.javaClass.name}: ${e.message}\n$sw"
                File(appContext.filesDir, FILE_NAME).writeText(text)
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }
}

object DiagLog {
    const val FILE_NAME = "diag_log.txt"
    const val FULL_FILE_NAME = "diag_full.txt"
    private const val MAX_CHARS = 65536

    @Synchronized
    fun log(context: Context, msg: String) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val f = File(context.filesDir, FILE_NAME)
            val existing = if (f.exists()) f.readText() else ""
            var text = existing + "[$stamp] $msg\n"
            if (text.length > MAX_CHARS) text = text.takeLast(MAX_CHARS)
            f.writeText(text)
        } catch (_: Exception) {
        }
    }

    fun readSaved(context: Context): String? {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    // Full diagnostic output lives in its own file: it is far too large
    // for the 64KB rolling log above (DiagLog.log would truncate it),
    // and far too large for the clipboard. The on-screen summary points
    // here; Copy log still copies the rolling log + crash log only.
    @Synchronized
    fun writeFull(context: Context, text: String) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val f = File(context.filesDir, FULL_FILE_NAME)
            var t = "[$stamp] Full diagnostic output (${text.length} chars)\n" + text
            if (t.length > 4_000_000) t = t.take(4_000_000)
            f.writeText(t)
        } catch (_: Exception) {
        }
    }

    fun readFull(context: Context): String? {
        return try {
            val f = File(context.filesDir, FULL_FILE_NAME)
            if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }
}

// v2.8: never put a huge string into a TextView. Setting ~1.8M chars
// of diagnostic output on the main thread blocked it for 8-9s
// (watchdog: "home output shown") and the app looked frozen. Show the
// head of the text plus a note; the full text stays in the log file.
fun cappedForDisplay(text: String, maxChars: Int = 8192): String {
    if (text.length <= maxChars) return text
    val head = text.take(maxChars)
    val cut = head.lastIndexOf('\n')
    val shown = if (cut > maxChars / 2) head.substring(0, cut) else head
    return shown + "\n\n[... showing " + shown.length + " of " + text.length + " chars on screen - full output is in the saved log, use Copy log to get it all.]"
}

class CastRemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashCapture.install(this)
        MainThreadWatchdog.install(this)
    }
}

object MainThreadWatchdog {
    private var started = false
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastMainTick = 0L
    @Volatile private var lastLocation = "app start"
    private var appContext: Context? = null

    @Synchronized
    fun install(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        lastMainTick = System.currentTimeMillis()
        val checker = object : Thread("main-watchdog") {
            override fun run() {
                while (true) {
                    try {
                        sleep(2000)
                    } catch (e: InterruptedException) {
                        return
                    }
                    val ctx = appContext ?: continue
                    val now = System.currentTimeMillis()
                    val tick = lastMainTick
                    if (now - tick > 8000) {
                        DiagLog.log(ctx, "WATCHDOG: main thread blocked for " + ((now - tick) / 1000) + "s, last location: " + lastLocation)
                        lastMainTick = now
                    }
                    mainHandler.post { lastMainTick = System.currentTimeMillis() }
                }
            }
        }
        checker.isDaemon = true
        checker.start()
    }

    fun mark(location: String) {
        lastLocation = location
        mainHandler.post { lastMainTick = System.currentTimeMillis() }
    }
}

fun copyTextToClipboard(context: Context, text: String, toastMessage: String = "Log copied") {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Cast Remote log", text))
        showToastOnMain(context, toastMessage)
    } catch (e: Exception) {
        showToastOnMain(context, "Copy failed: " + e.message)
    }
}

// Toasts need the main thread: callers may run on Dispatchers.IO
// (v2.9 copies a large diagnostic off the main thread), so post the
// toast to the main looper instead of crashing on a thread without one.
private fun showToastOnMain(context: Context, msg: String) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    } else {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
