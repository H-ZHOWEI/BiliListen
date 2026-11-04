package com.bilisten.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放状态中枢：界面只订阅这里的 StateFlow，所有操作转成 MediaController 调用。
 */
object PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var future: ListenableFuture<MediaController>? = null
    private var ticker: Job? = null

    private val trackMap = LinkedHashMap<String, Track>()

    /** 队列内唯一 key：bvid@cid（支持同一个视频的不同分 P 共存） */
    private fun keyOf(track: Track) = "${track.bvid}@${track.cid}"

    var controller: MediaController? = null
        private set

    val connected = MutableStateFlow(false)
    val current = MutableStateFlow<Track?>(null)
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val isPlaying = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val shuffleOn = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val speed = MutableStateFlow(1f)
    val buffering = MutableStateFlow(false)
    val errorText = MutableStateFlow<String?>(null)

    /** 视频画面模式：当前歌用整段 mp4 播放（找不到可靠歌词时的兜底展示） */
    val videoMode = MutableStateFlow(false)
    private var videoBvid = ""

    fun connect(context: Context) {
        if (future != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(playerListener)
                connected.value = true
                syncAll()
                startTicker()
                // 歌词引擎：进程级常驻，App 退后台也持续推进行（悬浮窗/车机依赖）
                LyricEngine.start()
                LyricEngine.onTrackChanged(current.value)
            } catch (e: Exception) {
                future = null
                errorText.value = "播放服务连接失败：${e.message}"
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            current.value = mediaItem?.mediaId?.let { trackMap[it] }
            // 歌词引擎跟着切歌（同 bvid 换分P/换画面时不重载）
            LyricEngine.onTrackChanged(current.value)
            // 切到别的歌就退出视频模式，回到纯音频
            if (mediaItem?.mediaId != videoBvid) {
                videoBvid = ""
                videoMode.value = false
            }
            val d = controller?.duration ?: 0L
            durationMs.value = if (d > 0) d else current.value?.durationMs ?: 0L
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
            if (playing) startTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering.value = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                val d = controller?.duration ?: 0L
                if (d > 0) durationMs.value = d
            }
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            shuffleOn.value = enabled
        }

        override fun onRepeatModeChanged(mode: Int) {
            repeatMode.value = mode
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            speed.value = playbackParameters.speed
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayError(error)
        }
    }

    /** 当前队列里连续失败过的歌，用于避免无限跳过 */
    private val failedBvids = LinkedHashSet<String>()
    private var skipJob: Job? = null

    private fun handlePlayError(error: PlaybackException) {
        val failed = current.value
        // 视频画面拉不动就退回纯音频，别跳歌
        if (videoMode.value && failed != null) {
            errorText.value = "视频画面加载失败，已切回纯音频"
            videoBvid = ""
            videoMode.value = false
            val c = controller
            if (c != null) {
                val pos = if (c.playbackState == Player.STATE_READY) c.currentPosition else 0L
                val idx = c.currentMediaItemIndex
                try {
                    c.replaceMediaItems(idx, idx + 1, listOf(failed.toMediaItem(false)))
                    c.seekTo(idx, pos)
                    c.prepare()
                    c.play()
                } catch (e: Exception) {
                    errorText.value = "切回音频失败：${e.message}"
                }
            }
            return
        }
        // 解析缓存里的地址可能已经废了，清掉下次重新换源
        failed?.let { BiliAudioResolver.invalidate(it.bvid) }

        val c = controller
        val total = c?.mediaItemCount ?: 0
        if (c != null && total > 1 && failed != null && failedBvids.add(failed.bvid)) {
            // 还有别的歌就自动跳过，避免一首坏歌卡住整个歌单
            if (failedBvids.size < total) {
                errorText.value = "《${failed.title}》播不了，已自动跳过"
                skipJob?.cancel()
                skipJob = scope.launch {
                    delay(700)
                    val idx = (c.currentMediaItemIndex + 1) % total
                    c.seekTo(idx, 0L)
                    c.prepare()
                    c.play()
                }
                return
            }
        }
        errorText.value = friendlyError(error)
    }

    private fun friendlyError(error: PlaybackException): String {
        val msg = error.message.orEmpty()
        val cause = error.cause?.message.orEmpty()
        return when {
            msg.contains("没有播放权限") || cause.contains("没有播放权限") -> cause
            msg.contains("大会员") || cause.contains("大会员") -> cause
            msg.contains("403") || cause.contains("403") ->
                "音频直链被拒绝（403），请检查登录状态或稍后重试"
            msg.contains("拿不到音频") -> msg
            msg.contains("Unable to connect") -> "网络连接失败"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "网络连接失败"
            else -> "播放失败：${(cause.ifBlank { msg }).take(80)}"
        }
    }

    private fun syncAll() {
        val c = controller ?: return
        shuffleOn.value = c.shuffleModeEnabled
        repeatMode.value = c.repeatMode
        isPlaying.value = c.isPlaying
        speed.value = c.playbackParameters.speed
        val item = c.currentMediaItem
        current.value = item?.mediaId?.let { trackMap[it] }
        durationMs.value = c.duration.coerceAtLeast(0)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    positionMs.value = c.currentPosition.coerceAtLeast(0)
                    val d = c.duration
                    if (d > 0) durationMs.value = d
                }
                delay(400)
            }
        }
    }

    // ---------------- 播放控制 ----------------

    fun playList(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller
        if (c == null) {
            errorText.value = "播放器还在准备，请稍候再点一次"
            return
        }
        if (tracks.isEmpty()) return
        failedBvids.clear()
        skipJob?.cancel()
        trackMap.clear()
        tracks.forEach { trackMap[keyOf(it)] = it }
        queue.value = tracks
        val items = tracks.map { it.toMediaItem() }
        val index = startIndex.coerceIn(0, items.size - 1)
        c.setMediaItems(items, index, 0L)
        c.shuffleModeEnabled = shuffle
        c.repeatMode = repeatMode.value
        c.prepare()
        c.play()
        current.value = tracks[index]
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until (c.mediaItemCount)) {
            c.seekTo(index, 0L)
            c.prepare()
            c.play()
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 4000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
        positionMs.value = ms
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /**
     * 用一个按钮循环切换播放模式（仿主流音乐 App）：
     * 顺序播放 → 随机播放 → 单曲循环 →（回到）顺序播放。
     */
    fun cyclePlayMode() {
        val c = controller ?: return
        val shuffle = c.shuffleModeEnabled
        val repeat = c.repeatMode
        when {
            !shuffle && repeat == Player.REPEAT_MODE_OFF -> {
                c.shuffleModeEnabled = true
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
            shuffle && repeat == Player.REPEAT_MODE_OFF -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ONE
            }
            else -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
    }

    /**
     * 视频画面模式：把当前 MediaItem 原地换成整段 mp4（bilivideo://），
     * 保留播放进度；关掉时换回纯音频。
     */
    fun setVideoMode(on: Boolean) {
        val c = controller ?: return
        val track = current.value ?: return
        if (on == videoMode.value && videoBvid == keyOf(track)) return

        val pos = if (c.playbackState == Player.STATE_READY) c.currentPosition else 0L
        val idx = c.currentMediaItemIndex
        videoBvid = if (on) keyOf(track) else ""
        videoMode.value = on
        try {
            c.replaceMediaItems(idx, idx + 1, listOf(track.toMediaItem(on)))
            c.seekTo(idx, pos)
            c.prepare()
            c.play()
        } catch (e: Exception) {
            videoBvid = ""
            videoMode.value = false
            errorText.value = "切换视频画面失败：${e.message}"
        }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun cycleSpeed() {
        val c = controller ?: return
        val steps = listOf(1f, 1.25f, 1.5f, 2f, 0.75f, 0.5f)
        val idx = steps.indexOfFirst { Math.abs(it - c.playbackParameters.speed) < 0.01f }
        val next = steps[(idx + 1) % steps.size]
        c.setPlaybackSpeed(next)
        speed.value = next
    }

    fun setSpeed(value: Float) {
        controller?.setPlaybackSpeed(value)
        speed.value = value
    }

    fun currentIndex(): Int = controller?.currentMediaItemIndex ?: 0

    fun stopAndClear() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        trackMap.clear()
        queue.value = emptyList()
        current.value = null
        positionMs.value = 0L
        durationMs.value = 0L
        isPlaying.value = false
    }

    private fun Track.toMediaItem(video: Boolean = false): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle("哔哩听")
            .apply { if (cover.isNotBlank()) setArtworkUri(Uri.parse(cover)) }
            .build()

        // 已下载的歌直接用本地文件，离线也能放（分P须一致；视频画面模式始终走在线整段 mp4）
        val local = if (video) null else Downloads.localUri(bvid, cid)
        val uri = local ?: Uri.parse(if (video) "bilivideo://$bvid/$cid" else "biliaudio://$bvid/$cid")

        return MediaItem.Builder()
            .setMediaId("${bvid}@$cid")
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * 把当前正在播的歌换成另一个 Track（同视频换分P等场景）：
     * 原地替换 MediaItem、保留进度由调用方决定；这里从 0 开始播新的分P。
     */
    fun replaceCurrentTrack(newTrack: Track) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        trackMap[keyOf(newTrack)] = newTrack
        queue.value = queue.value.mapIndexed { i, t -> if (i == idx) newTrack else t }
        try {
            c.replaceMediaItems(idx, idx + 1, listOf(newTrack.toMediaItem(false)))
            c.seekTo(idx, 0L)
            c.prepare()
            c.play()
            current.value = newTrack
        } catch (e: Exception) {
            errorText.value = "切换分P失败：${e.message}"
        }
    }
}
