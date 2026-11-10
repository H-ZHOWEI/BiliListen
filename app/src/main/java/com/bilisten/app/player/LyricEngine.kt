package com.bilisten.app.player

import com.bilisten.app.data.LyricAutoAlign
import com.bilisten.app.data.LyricLine
import com.bilisten.app.data.LyricResult
import com.bilisten.app.data.LyricStore
import com.bilisten.app.data.LyricTarget
import com.bilisten.app.data.LyricsRepository
import com.bilisten.app.data.Prefs
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
 * 只要 App 进程活着（后台播放时必然活着），歌词就会持续推进。
 *
 * 双时间轴：车机通知 / AVRCP 走蓝牙或投屏，通常比手机屏幕慢几百毫秒，
 * 所以同一份原始歌词会分别套用「手机」和「车机」两套校准参数，
 * 生成 lines（手机播放页 + 悬浮窗）与 carLines（车机），互不干扰。
 * 校准改动只重算时间轴，不重新联网、不闪屏。
 */
object LyricEngine {

    /** 一次歌词加载完成的事件（播放页用它刷新列表和提示） */
    data class Snapshot(
        val bvid: String,
        val lines: List<LyricLine>,
        val source: String,
        val empty: Boolean,
        /** 非空 = 刚完成一次自动对齐，播放页用 Toast 提醒用户 */
        val autoAlignNote: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var loadJob: Job? = null
    private var alignJob: Job? = null
    private var currentBvid = ""

    /** 未经校准的原始歌词（来源歌词 / 手打 / 指定版本） */
    private val rawLines = MutableStateFlow<List<LyricLine>>(emptyList())

    /** 手机时间轴：播放页歌词列表、悬浮窗、视频字幕 */
    val lines = MutableStateFlow<List<LyricLine>>(emptyList())

    /** 车机时间轴：媒体通知 / AVRCP 歌词 */
    private val carLines = MutableStateFlow<List<LyricLine>>(emptyList())

    /** 当前唱到的行下标（手机，-1 = 还没开始唱） */
    val currentIndex = MutableStateFlow(-1)

    /** 当前句（悬浮窗第一行 / 视频字幕） */
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
            combine(PlayerController.positionMs, lines, carLines) { pos, ls, cls ->
                Triple(pos, ls, cls)
            }.collect { (pos, ls, cls) -> compute(pos, ls, cls) }
        }
    }

    /** 切歌回调：清空旧歌词并自动加载新歌词 */
    fun onTrackChanged(track: Track?) {
        val bvid = track?.bvid.orEmpty()
        if (bvid == currentBvid) return
        currentBvid = bvid
        rawLines.value = emptyList()
        lines.value = emptyList()
        carLines.value = emptyList()
        currentIndex.value = -1
        currentLine.value = ""
        nextLine.value = ""
        source.value = ""
        LyricBroadcaster.update("")
        if (track != null) load(track, null)
    }

    /**
     * 主动（重新）加载歌词：重新加载 / 搜索关键词 / 指定字幕轨 /
     * 手打 / 切换版本走这里。注意：调用方若改动了 LyricStore 里的「源」，
     * 必须先 LyricsRepository.invalidate(bvid) 清缓存，否则会读回旧歌词。
     */
    fun load(track: Track, keyword: String?) {
        currentBvid = track.bvid
        val target = track.bvid
        loadJob?.cancel()
        loadJob = scope.launch {
            val result = LyricsRepository.load(track, keyword)
            if (currentBvid != target) return@launch
            rawLines.value = result.lines
            source.value = result.source
            currentIndex.value = -1
            recalibrate(target)
            _events.tryEmit(
                Snapshot(target, lines.value, result.source, result.lines.isEmpty())
            )
            maybeAutoAlign(track, result)
        }
    }

    /**
     * 自动对齐（v1.18）：歌词不是 B 站字幕本身时，后台拿 B 站字幕做文本匹配，
     * 匹配够了就生成逐句时间锚点并立即重算 —— 用户无感，歌词自动贴上这首歌。
     * 同一来源只算一次（按 autoAnchorKey 缓存），换版本 / 换字幕轨会重新对齐。
     */
    private fun maybeAutoAlign(track: Track, result: LyricResult) {
        alignJob?.cancel()
        if (!Prefs.lyricAutoAlign) return
        val bvid = track.bvid
        if (result.lines.isEmpty()) return
        val key = result.source
        if (key.isBlank()) return
        if (LyricStore.getAutoAnchorKey(bvid) == key &&
            LyricStore.getAutoAnchors(bvid).isNotEmpty()
        ) return
        alignJob = scope.launch {
            try {
                val outcome = LyricAutoAlign.compute(track, result.lines, key)
                    ?: return@launch
                if (currentBvid != bvid) return@launch
                LyricStore.setAutoAnchors(bvid, outcome.anchors, key)
                recalibrate(bvid)
                val note = if (outcome.viaSubtitle) {
                    "已按 B 站字幕自动对齐歌词（匹配 ${outcome.matched} 处）"
                } else {
                    "已按歌曲时长自动校正歌词速度"
                }
                _events.tryEmit(Snapshot(bvid, lines.value, source.value, false, note))
            } catch (_: Exception) {
            }
        }
    }

    /** 校准参数变化后：用已有的原始歌词重算两套时间轴，立即生效、不联网 */
    fun recalibrate(bvid: String = currentBvid) {
        val raw = rawLines.value
        lines.value = LyricStore.applyCalibration(raw, bvid, LyricTarget.PHONE)
        carLines.value = LyricStore.applyCalibration(raw, bvid, LyricTarget.CAR)
        compute(PlayerController.positionMs.value, lines.value, carLines.value)
    }

    /** 给「当前歌词应该在哪」用：取指定设备当前生效的时间轴 */
    fun linesFor(target: LyricTarget): List<LyricLine> =
        if (target == LyricTarget.CAR) carLines.value else lines.value

    /** 指定设备此刻正显示到第几句（-1 = 还没开始） */
    fun indexFor(target: LyricTarget, posMs: Long = PlayerController.positionMs.value): Int =
        indexAt(linesFor(target), posMs)

    /** 「当前歌词应该在哪」用：校准后列表的下标 → 原始歌词的下标（先按下标、文本不符再按文本找） */
    fun rawIndexOf(target: LyricTarget, index: Int): Int {
        val ls = linesFor(target)
        if (index < 0 || index >= ls.size) return -1
        val raw = rawLines.value
        if (raw.isEmpty()) return -1
        if (index < raw.size && raw[index].text == ls[index].text) return index
        val t = ls[index].timeMs
        var best = -1
        var bestD = Long.MAX_VALUE
        raw.forEachIndexed { i, r ->
            if (r.text == ls[index].text) {
                val d = Math.abs(r.timeMs - t)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
        }
        return best
    }

    /** 原始歌词（未校准）里第 index 句的时间；「当前歌词应该在哪」写锚点用 */
    fun rawTimeAt(index: Int): Long = rawLines.value.getOrNull(index)?.timeMs ?: -1L

    private fun compute(
        pos: Long,
        ls: List<LyricLine>,
        cls: List<LyricLine>
    ) {
        // 手机：二分找当前句 + 下一句
        val found = indexAt(ls, pos)
        if (found != currentIndex.value) currentIndex.value = found
        setCurrent(if (found >= 0) ls[found].text else "")
        setNext(if (found + 1 < ls.size) ls[found + 1].text else "")

        // 车机：独立时间轴，只广播当前句
        val carFound = indexAt(cls, pos)
        LyricBroadcaster.update(if (carFound >= 0) cls[carFound].text else "")
    }

    private fun indexAt(ls: List<LyricLine>, pos: Long): Int {
        if (ls.isEmpty()) return -1
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
        return found
    }

    private fun setCurrent(text: String) {
        currentLine.value = text
    }

    private fun setNext(text: String) {
        if (nextLine.value != text) nextLine.value = text
    }
}
