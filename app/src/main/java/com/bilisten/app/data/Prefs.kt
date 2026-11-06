package com.bilisten.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 极简本地存储：Cookie、用户信息、音质偏好。
 */
object Prefs {
    private const val NAME = "bilisten"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var cookie: String
        get() = sp.getString("cookie", "").orEmpty()
        set(value) = sp.edit().putString("cookie", value).apply()

    var mid: Long
        get() = sp.getLong("mid", 0L)
        set(value) = sp.edit().putLong("mid", value).apply()

    var userName: String
        get() = sp.getString("uname", "").orEmpty()
        set(value) = sp.edit().putString("uname", value).apply()

    var face: String
        get() = sp.getString("face", "").orEmpty()
        set(value) = sp.edit().putString("face", value).apply()

    /** 0=自动最高 1=64K 2=132K 3=192K */
    var quality: Int
        get() = sp.getInt("quality", 0)
        set(value) = sp.edit().putInt("quality", value).apply()

    var lastFolderId: Long
        get() = sp.getLong("last_folder", 0L)
        set(value) = sp.edit().putLong("last_folder", value).apply()

    /** 歌词是否只使用 B 站字幕（不用网络歌词兜底） */
    var lyricBiliOnly: Boolean
        get() = sp.getBoolean("lyric_bili_only", false)
        set(value) = sp.edit().putBoolean("lyric_bili_only", value).apply()

    /** 是否开启歌词置顶悬浮窗 */
    var lyricOverlayOn: Boolean
        get() = sp.getBoolean("lyric_overlay", false)
        set(value) = sp.edit().putBoolean("lyric_overlay", value).apply()

    /** 悬浮歌词双行显示：第一行当前句，第二行下一句 */
    var lyricOverlayDual: Boolean
        get() = sp.getBoolean("lyric_overlay_dual", false)
        set(value) = sp.edit().putBoolean("lyric_overlay_dual", value).apply()

    /**
     * 播放页（歌曲详情）屏幕常亮：
     * 0=不息屏一直亮（默认） 1=5分钟 2=10分钟 3=30分钟 4=跟随系统
     */
    var screenOn: Int
        get() = sp.getInt("screen_on", 0)
        set(value) = sp.edit().putInt("screen_on", value).apply()

    /**
     * 启动时自动续播上次那首歌：
     * 默认关——只把上次的歌和进度摆到迷你播放条上，用户点播放才出声。
     */
    var resumeAutoPlay: Boolean
        get() = sp.getBoolean("resume_autoplay", false)
        set(value) = sp.edit().putBoolean("resume_autoplay", value).apply()

    /** 上次检查更新的时间戳，用于控制自动检查频率 */
    var updateCheckedAt: Long
        get() = sp.getLong("update_checked_at", 0L)
        set(value) = sp.edit().putLong("update_checked_at", value).apply()

    /** 用户点了「跳过此版本」的版本号，同一版本不再自动提醒 */
    var skippedVersion: String
        get() = sp.getString("skipped_version", "").orEmpty()
        set(value) = sp.edit().putString("skipped_version", value).apply()

    val isLogin: Boolean get() = cookie.contains("SESSDATA=") && mid > 0L

    fun clearLogin() {
        sp.edit()
            .remove("cookie").remove("mid").remove("uname").remove("face").remove("last_folder")
            .apply()
    }
}
