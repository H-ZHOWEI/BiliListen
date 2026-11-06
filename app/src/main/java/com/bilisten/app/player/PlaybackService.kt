package com.bilisten.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bilisten.app.data.Net
import com.bilisten.app.data.SessionStore
import com.bilisten.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台播放服务：通知栏、锁屏、蓝牙耳机按键都由 MediaSession 自动接管。
 *
 * 注意：B 站音频 CDN 会校验 Referer/UA/Cookie，用默认的 HttpDataSource 会大面积 403，
 * 所以 HTTP 层走 OkHttp（复用 Net 里带 B 站头的拦截器），本地文件仍由 DefaultDataSource 处理。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var lyricProvider: CarLyricNotificationProvider? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val httpFactory = OkHttpDataSource.Factory(Net.http)
            .setUserAgent(Net.UA)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to Net.REFERER,
                    "Origin" to "https://www.bilibili.com"
                )
            )

        // DefaultDataSource：file:// 走本地文件，http(s) 交给 OkHttp
        val upstream = DefaultDataSource.Factory(this, httpFactory)
        val dataSourceFactory = ResolvingDataSource.Factory(upstream, BiliAudioResolver)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        // 车机歌词：当前歌词行写进媒体通知，Android Auto / 车机投屏可见
        lyricProvider = CarLyricNotificationProvider(this).also { setMediaNotificationProvider(it) }
        scope.launch {
            LyricBroadcaster.line.collect { line ->
                lyricProvider?.updateLyric(line)
                // 蓝牙车机（AVRCP）通道：QQ音乐同款做法 —— 每唱一句，
                // 把歌词行写进 MediaSession 元数据的歌手字段，车机随之刷新。
                // replaceMediaItem 在「同 URL、只改元数据」时无缝继续播放，不重新缓冲。
                updateAvrcpLyric(line)
            }
        }
    }

    private fun updateAvrcpLyric(line: String) {
        val player = mediaSession?.player ?: return
        val item = player.currentMediaItem ?: return
        val baseArtist = PlayerController.current.value?.artist.orEmpty()
        val artist = if (line.isBlank()) baseArtist else line
        val newMeta = item.mediaMetadata.buildUpon().setArtist(artist).build()
        if (newMeta == item.mediaMetadata) return
        try {
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon().setMediaMetadata(newMeta).build()
            )
        } catch (_: Exception) {
            // 元数据刷新失败不影响播放
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // 被从最近任务划掉前把进度钉死，下次打开才能精确回到原处
        val pos = if (player != null && player.playbackState != Player.STATE_IDLE) {
            player.currentPosition
        } else {
            PlayerController.positionMs.value
        }
        SessionStore.savePosition(pos, force = true)
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 释放播放器前最后再记一次进度
        mediaSession?.player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE) {
                SessionStore.savePosition(p.currentPosition, force = true)
            }
        }
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
