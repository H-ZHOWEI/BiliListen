package com.bilisten.app.ui

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.bilisten.app.R
import com.bilisten.app.data.LyricStore
import com.bilisten.app.data.LyricTarget
import com.bilisten.app.data.Prefs
import com.bilisten.app.player.LyricEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 歌词置顶悬浮窗：独立于播放页，常驻在最上层显示当前正在唱的那句歌词。
 * 通过 SYSTEM_ALERT_WINDOW 把一个小药丸挂在屏幕上，可拖动；没有歌词时自动隐藏。
 *
 * 数据来自 LyricEngine（进程级常驻推进）——App 退后台 / 播放页关闭后歌词照样走，
 * 单行显示当前句；双行模式第一行当前句、第二行下一句（⋮ 菜单里切换）。
 */
class LyricOverlayService : Service() {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户在悬浮窗上点过 ×（或已在 App 里关掉）时不要再挂上来
        if (!Prefs.lyricOverlayOn) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) showOverlay()
        return START_STICKY
    }

    /**
     * 悬挂浮窗。整段包在 try 里：悬浮窗属于「锦上添花」，
     * 任何异常（主题属性缺失、权限被撤销、窗口 token 失效……）
     * 都不该连累 App 让用户闪退，失败就安静退出。
     */
    private fun showOverlay() {
        try {
            buildOverlay()
        } catch (e: Throwable) {
            try {
                java.io.File(filesDir, "overlay_error.txt").writeText(e.stackTraceToString())
            } catch (_: Throwable) {
            }
            removeOverlay()
            stopSelf()
        }
    }

    private fun buildOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_lyric, null)
        val text = v.findViewById<TextView>(R.id.lyric_text)
        val textNext = v.findViewById<TextView>(R.id.lyric_text_next)

        params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 160
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }

        var startX = 0f
        var startY = 0f
        var initX = 0
        var initY = 0
        v.findViewById<View>(R.id.lyric_root).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initX = params!!.x
                    initY = params!!.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val sx = resources.displayMetrics.widthPixels
                    val sy = resources.displayMetrics.heightPixels
                    val w = v.width.takeIf { it > 0 } ?: 0
                    val h = v.height.takeIf { it > 0 } ?: 0
                    params!!.x = (initX + (event.rawX - startX).toInt())
                        .coerceIn(0, (sx - w).coerceAtLeast(0))
                    params!!.y = (initY + (event.rawY - startY).toInt())
                        .coerceIn(0, (sy - h).coerceAtLeast(0))
                    wm.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }

        // 就地关闭：点 × 直接收起悬浮窗（同时把偏好关掉，和播放页菜单保持一致）
        v.findViewById<View>(R.id.lyric_close).setOnClickListener {
            Prefs.lyricOverlayOn = false
            Toast.makeText(
                this,
                "已关闭悬浮歌词，想再打开可在播放页「⋮」里开启",
                Toast.LENGTH_SHORT
            ).show()
            removeOverlay()
            stopSelf()
        }

        // ---------------- 歌词快慢（参考 QQ 音乐悬浮歌词工具条） ----------------
        // 点 ⇆ 展开调节条：提前/延后 0.5 秒一步，立即生效，按歌记忆（与手机歌词共用 PHONE 校准）
        val speedBtn = v.findViewById<View>(R.id.lyric_speed)
        val modeRow = v.findViewById<View>(R.id.lyric_mode_row)
        val modeBtn = v.findViewById<TextView>(R.id.lyric_mode)
        val modePos = v.findViewById<TextView>(R.id.lyric_mode_pos)
        val adjustRow = v.findViewById<View>(R.id.lyric_adjust_row)
        val tvOffset = v.findViewById<TextView>(R.id.lyric_offset)
        val tvReset = v.findViewById<TextView>(R.id.lyric_reset)

        // false=整首统一平移；true=从固定位置起分段平移（前半快、后半慢时用）
        var segmentMode = false
        var segmentStartMs = 0L

        fun fmtShift(ms: Long): String = when {
            ms > 0L -> "延后 ${ms / 1000.0} 秒"
            ms < 0L -> "提前 ${-ms / 1000.0} 秒"
            else -> "无偏移"
        }

        /** 当前分段位置实际吃到的累计偏移（含 3 秒前瞻，和 applySegments 一致） */
        fun activeSegmentOffset(bvid: String): Long =
            LyricStore.getSegments(bvid, LyricTarget.PHONE)
                .filter { it.start <= segmentStartMs + 3000 }
                .lastOrNull()?.offset ?: 0L

        fun refreshOffsetLabel() {
            val bvid = LyricEngine.currentBvid
            if (bvid.isNullOrBlank()) {
                tvOffset.text = "无偏移"
                return
            }
            val off = if (segmentMode) activeSegmentOffset(bvid)
            else LyricStore.getCalibOffset(bvid, LyricTarget.PHONE)
            tvOffset.text = if (segmentMode) "本段 ${fmtShift(off)}" else fmtShift(off)
        }

        fun refreshModeUi() {
            modeBtn.text = if (segmentMode) "范围：从这里起" else "范围：整首"
            modePos.text = if (segmentMode) "从 ${com.bilisten.app.util.Fmt.duration(segmentStartMs)} 起 · 再点重取位置"
            else "点一下改成「从当前位置起」"
            tvReset.text = if (segmentMode) "清分段" else "还原"
            refreshOffsetLabel()
        }

        fun shift(deltaMs: Long) {
            val bvid = LyricEngine.currentBvid
            if (bvid.isNullOrBlank() || LyricEngine.lines.value.isEmpty()) {
                Toast.makeText(this, "当前没有正在播放的歌词可调", Toast.LENGTH_SHORT).show()
                return
            }
            if (segmentMode) {
                // 分段：只影响从这个位置往后的歌词，前面保持原样
                LyricStore.addSegment(bvid, segmentStartMs, deltaMs, LyricTarget.PHONE)
                Toast.makeText(
                    this,
                    "手机歌词从 ${com.bilisten.app.util.Fmt.duration(segmentStartMs)} 起${fmtShift(deltaMs)}（已生效）",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                LyricStore.shiftCalibration(bvid, deltaMs, LyricTarget.PHONE)
            }
            // 只动校准：走 recalibrate 即时重算，不联网、不清锚点
            LyricEngine.recalibrate(bvid)
            refreshOffsetLabel()
        }

        fun reset() {
            val bvid = LyricEngine.currentBvid
            if (bvid.isNullOrBlank()) {
                Toast.makeText(this, "当前没有正在播放的歌词", Toast.LENGTH_SHORT).show()
                return
            }
            if (segmentMode) {
                LyricStore.clearSegments(bvid, LyricTarget.PHONE)
                Toast.makeText(this, "已清除这首歌的全部分段校准（整体偏移保留）", Toast.LENGTH_SHORT).show()
            } else {
                val rate = LyricStore.getCalibRate(bvid, LyricTarget.PHONE)
                LyricStore.setCalibration(bvid, 0L, rate, LyricTarget.PHONE)
            }
            LyricEngine.recalibrate(bvid)
            refreshOffsetLabel()
        }

        modeBtn.setOnClickListener {
            segmentMode = !segmentMode
            if (segmentMode) {
                segmentStartMs = com.bilisten.app.player.PlayerController.positionMs.value
                Toast.makeText(
                    this,
                    "已从 ${com.bilisten.app.util.Fmt.duration(segmentStartMs)} 起分段调整；调完这里，把进度拖到后面再点「范围」重取位置",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshModeUi()
        }
        speedBtn.setOnClickListener {
            val show = adjustRow.visibility != View.VISIBLE
            if (show) refreshModeUi()
            modeRow.visibility = if (show) View.VISIBLE else View.GONE
            adjustRow.visibility = if (show) View.VISIBLE else View.GONE
        }
        v.findViewById<View>(R.id.lyric_earlier).setOnClickListener { shift(-500L) }
        v.findViewById<View>(R.id.lyric_later).setOnClickListener { shift(500L) }
        v.findViewById<View>(R.id.lyric_reset).setOnClickListener { reset() }

        wm.addView(v, params)
        view = v

        // 双行模式在每次刷新时实时读偏好，用户切换单/双行即刻生效
        scope.launch {
            LyricEngine.currentLine.collectLatest { line ->
                val dual = Prefs.lyricOverlayDual
                text.text = line
                text.maxLines = if (dual) 1 else 2
                if (line.isBlank() && (!dual || LyricEngine.nextLine.value.isBlank())) {
                    v.visibility = View.GONE
                } else {
                    v.visibility = View.VISIBLE
                }
            }
        }
        scope.launch {
            LyricEngine.nextLine.collectLatest { next ->
                val dual = Prefs.lyricOverlayDual
                if (dual && next.isNotBlank()) {
                    textNext.text = "↓ $next"
                    textNext.visibility = View.VISIBLE
                    if (LyricEngine.currentLine.value.isNotBlank()) {
                        v.visibility = View.VISIBLE
                    }
                } else {
                    textNext.visibility = View.GONE
                }
            }
        }
    }

    /** 摘掉悬浮窗（可能已被移除，重复调用安全） */
    private fun removeOverlay() {
        val v = view ?: return
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
        view = null
        params = null
    }

    override fun onDestroy() {
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }
}
