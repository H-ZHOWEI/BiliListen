package com.bilisten.app.player

import com.bilisten.app.data.LyricLine
import com.bilisten.app.data.LyricsRepository
import com.bilisten.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 歌词引擎（进程级常驻）：
 * 之前歌词行的计算放在播放页 Activity 的生命周期协程里，App 一退后台就停止，
 * 导致悬浮窗 / 车机歌词「卡住不动」。现在统一挪到这里 —— 只要 App 进程活着
 * （后台播放时必然活着），歌词就会持续推进，悬浮窗、通知、车机共享同一份数据。
 *
 * 职责：
 * 1) 持有当前歌的歌词行，随 PlayerController.positionMs 实时计算当前句 / 下一句
 * 2) 切歌时自动加载歌词（LyricsRepository 无需 UI 上下文）
 * 3) 对外广播：currentIndex（播放页高亮滚动）、currentLine / nextLine（悬浮窗单双行）
 */
object LyricEngine {

    /** 一次歌词加载完成的事件（播放页用它刷新列表和提示） */
    data class Snapshot(
        val bvid: String,
        val lines: List<LyricLine>,
        val source: String,
        val empty: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var loadJob: Job? = null
    private var currentBvid = ""

    /** 当前歌词行列表 */
    val lines = MutableStateFlow<List<LyricLine>>(emptyList())

    /** 当前唱到的行下标（-1 = 还没开始唱） */
    val currentIndex = MutableStateFlow(-1)

    /** 当前句（供悬浮窗单行 / 双行第一行、视频字幕兜底、车机通知） */
    val currentLine = MutableStateFlow("")

    /** 下一句（悬浮窗双行的第二行） */
    val nextLine = MutableStateFlow("")

    /** 最近一次加载的歌词来源标签 */
    val source = MutableStateFlow("")

    private val _events = MutableSharedFlow<Snapshot>(extraBufferCapacity = 4)
    val events: SharedFlow<Snapshot> = _events

    /** PlayerController.connect 成功后调用一次；进程内只启动一个推进协程 */
    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            combine(PlayerController.positionMs, lines) { pos, ls -> pos to ls }
                .collect { (pos, ls) -> compute(pos, ls) }
        }
    }

    /** 切歌回调：清空旧歌词并自动加载新歌词 */
    fun onTrackChanged(track: Track?) {
        val bvid = track?.bvid.orEmpty()
        if (bvid == currentBvid) return
        currentBvid = bvid
        lines.value = emptyList()
        currentIndex.value = -1
        currentLine.value = ""
        nextLine.value = ""
        source.value = ""
        if (track != null) load(track, null)
    }

    /**
     * 主动（重新）加载歌词：播放页的「重新加载 / 搜索关键词 / 指定字幕轨 /
     * 校准 / 手打」都走这里。完成后发 events 快照。
     */
    fun load(track: Track, keyword: String?) {
        currentBvid = track.bvid
        val target = track.bvid
        loadJob?.cancel()
        loadJob = scope.launch {
            val result = LyricsRepository.load(track, keyword)
            if (currentBvid != target) return@launch
            lines.value = result.lines
            source.value = result.source
            currentIndex.value = -1
            _events.tryEmit(Snapshot(target, result.lines, result.source, result.isEmpty))
        }
    }

    private suspend fun compute(pos: Long, ls: List<LyricLine>) {
        if (ls.isEmpty()) {
            if (currentIndex.value != -1) currentIndex.value = -1
            setCurrent("")
            setNext("")
            return
        }
        var lo = 0
        var hi = ls.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (ls[mid].timeMs <= pos + 150) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found != currentIndex.value) currentIndex.value = found
        setCurrent(if (found >= 0) ls[found].text else "")
        setNext(if (found + 1 < ls.size) ls[found + 1].text else "")
    }

    private suspend fun setCurrent(text: String) {
        currentLine.value = text
        // 车机通知 / AVRCP / 悬浮窗共用的广播通道
        LyricBroadcaster.update(text)
    }

    private suspend fun setNext(text: String) {
        if (nextLine.value != text) nextLine.value = text
    }
}
