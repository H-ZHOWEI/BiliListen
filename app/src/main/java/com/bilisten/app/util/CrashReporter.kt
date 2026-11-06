package com.bilisten.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃留档：未捕获异常时把堆栈写进 files/crash_last.txt，
 * 下次启动时弹窗展示（可一键复制），这样用户不用连电脑也能把崩溃原因发出来。
 *
 * 只保留最近一次，且不改变原有崩溃行为（仍交给系统默认处理器）。
 */
object CrashReporter {

    private const val FILE = "crash_last.txt"
    private const val MAX_CHARS = 12000

    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val head = buildString {
                    append("时间：")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append('\n')
                    append("版本：").append(versionName(app)).append('\n')
                    append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" / Android ").append(Build.VERSION.SDK_INT).append('\n')
                    append("线程：").append(thread.name).append("\n\n")
                }
                File(app.filesDir, FILE).writeText(head + sw.toString())
            } catch (_: Throwable) {
                // 写日志失败也不能影响原有崩溃流程
            }
            prev?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? {
        return try {
            val f = File(context.applicationContext.filesDir, FILE)
            if (!f.exists()) return null
            f.readText().take(MAX_CHARS).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE).delete()
        } catch (_: Exception) {
        }
    }

    /** 启动时调用：有崩溃记录就弹出来，支持复制 */
    fun maybeShow(activity: Activity) {
        val text = lastCrash(activity) ?: return
        if (activity.isFinishing) return

        val tv = TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(48, 36, 48, 36)
        }
        val sv = ScrollView(activity).apply {
            addView(
                tv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(activity)
            .setTitle("上次启动时闪退了")
            .setMessage("下面是崩溃详情。点「复制详情」贴给我，就能定位问题。")
            .setView(sv)
            .setPositiveButton("复制详情") { _, _ ->
                try {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("BiliListen 崩溃详情", text))
                    Toast.makeText(activity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { clear(activity) }
            .show()
    }

    private fun versionName(context: Context): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: Exception) {
        "unknown"
    }
}
