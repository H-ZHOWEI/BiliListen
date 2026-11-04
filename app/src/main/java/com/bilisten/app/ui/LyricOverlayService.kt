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
import com.bilisten.app.R
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
        if (view == null) showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
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
                    params!!.x = initX + (event.rawX - startX).toInt()
                    params!!.y = initY + (event.rawY - startY).toInt()
                    wm.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }

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

    override fun onDestroy() {
        scope.cancel()
        view?.let { wm?.removeView(it) }
        view = null
        params = null
        super.onDestroy()
    }
}
