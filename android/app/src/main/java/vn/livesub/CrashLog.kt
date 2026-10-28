package vn.livesub

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lưu lỗi làm app dừng đột ngột để hiện lại ở lần mở sau: bản cài tay thường
 * không có adb để xem logcat.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_log"
    private const val KEY_DISMISSED_AT = "dismissed_at"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file(app).writeText(header(app, System.currentTimeMillis()) + "Thread: ${thread.name}\n\n" + e.stackTraceToString()) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Lỗi lần trước: lỗi Kotlin/Java từ file, lỗi native (vd. sherpa-onnx) từ lịch sử thoát của hệ thống. */
    fun read(context: Context): String? {
        file(context).takeIf { it.exists() }?.let { return it.readText() }
        if (Build.VERSION.SDK_INT < 30) return null
        val exit = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull() ?: return null
        if (exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE || exit.timestamp <= dismissedAt(context)) return null
        return header(context, exit.timestamp) + "Lỗi native (thư viện C/C++): ${exit.description ?: "không có mô tả"}"
    }

    fun dismiss(context: Context) {
        file(context).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DISMISSED_AT, System.currentTimeMillis()).apply()
    }

    private fun dismissedAt(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DISMISSED_AT, 0L)

    private fun header(context: Context, time: Long): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
        return "LiveSub $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n$date\n"
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
