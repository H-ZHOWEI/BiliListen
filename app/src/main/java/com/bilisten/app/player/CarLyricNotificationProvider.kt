package com.bilisten.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import androidx.core.app.NotificationCompat

/**
 * 车机歌词通知：在默认媒体通知的基础上，把当前歌词行写进 subText，
 * Android Auto、车机投屏、部分蓝牙车机的通知区都会显示。
 *
 * DefaultMediaNotificationProvider.createNotification 是 final，无法继承改写，
 * 所以这里用组合：内部持有一个默认 provider 做真正的通知构建，外层负责注入歌词行。
 * 歌词每变一句就重建一次通知（低频，几秒一次，开销可忽略）。
 */
@OptIn(UnstableApi::class)
class CarLyricNotificationProvider(context: Context) : MediaNotification.Provider {

    private val delegate = DefaultMediaNotificationProvider(context)

    private var session: MediaSession? = null
    private var preferences: ImmutableList<androidx.media3.session.CommandButton> =
        ImmutableList.of()
    private var actionFactory: MediaNotification.ActionFactory? = null
    private var callback: MediaNotification.Provider.Callback? = null
    private var currentLine = ""

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        session = mediaSession
        preferences = mediaButtonPreferences
        this.actionFactory = actionFactory
        callback = onNotificationChangedCallback
        return inject(
            delegate.createNotification(
                mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback
            )
        )
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: android.os.Bundle
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        MediaNotification.Provider.NotificationChannelInfo("bilisten_playback", "正在播放")

    private fun inject(notification: MediaNotification): MediaNotification {
        if (currentLine.isNotBlank()) {
            notification.notification.extras.putCharSequence(
                NotificationCompat.EXTRA_SUB_TEXT, currentLine
            )
            notification.notification.extras.putCharSequence(
                NotificationCompat.EXTRA_TEXT, currentLine
            )
        }
        return notification
    }

    /** 歌词行变化时刷新通知（由 PlaybackService 收集 LyricBroadcaster 调用） */
    fun updateLyric(text: String) {
        if (text == currentLine) return
        currentLine = text
        val cb = callback ?: return
        val s = session ?: return
        val factory = actionFactory ?: return
        try {
            val n = delegate.createNotification(s, preferences, factory, cb)
            inject(n)
            cb.onNotificationChanged(n)
        } catch (_: Exception) {
            // 通知刷新失败不影响播放，歌词仍在 App 内显示
        }
    }
}
