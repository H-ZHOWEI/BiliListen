package com.bilisten.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

/**
 * 歌词 DIY 仓库（持久化，下次打开依然生效）：
 * 1) customLrc：用户手打 / 粘贴的歌词（优先级最高，车机同样显示）
 * 2) segments：分段时间校准 —— 「歌词从第 x 秒起慢了/快了」，
 *    从当前位置起给后面的歌词整体提前 / 延后若干毫秒，可多次叠加
 */
object LyricStore {

    data class Segment(val start: Long = 0, val offset: Long = 0)

    data class Entry(
        val customLrc: String? = null,
        val customSource: String? = null,
        val segments: List<Segment>? = null
    )

    private data class Store(val entries: MutableMap<String, Entry> = LinkedHashMap())

    private val gson = Gson()
    private var store: Store? = null
    private var file: File? = null

    /** BiliApp 启动时调用一次 */
    fun init(context: Context) {
        val f = File(context.filesDir, "lyrics_store.json")
        file = f
        if (f.exists()) {
            try {
                store = gson.fromJson(f.readText(), Store::class.java)
            } catch (e: JsonSyntaxException) {
                store = null
            }
        }
        if (store == null) store = Store()
    }

    private fun ensure(): File {
        file?.let { f ->
            if (store == null) {
                if (f.exists()) {
                    try {
                        store = gson.fromJson(f.readText(), Store::class.java)
                    } catch (e: JsonSyntaxException) {
                        store = Store()
                    }
                } else store = Store()
            }
            return f
        }
        val f = File("lyrics_store.json")
        file = f
        store = Store()
        return f
    }

    private fun st(): Store = store ?: Store().also { store = it }

    private fun save() {
        val f = file ?: return
        try {
            f.writeText(gson.toJson(store))
        } catch (_: Exception) {
        }
    }

    private fun entryOf(bvid: String): Entry = st().entries[bvid] ?: Entry()

    // ---------------- 手打歌词 ----------------

    fun getCustomLrc(bvid: String): String? = entryOf(bvid).customLrc

    fun getCustomSource(bvid: String): String? = entryOf(bvid).customSource

    fun setCustomLrc(bvid: String, lrc: String, source: String = "我的歌词") {
        ensure()
        val s = st()
        val old = s.entries[bvid] ?: Entry()
        s.entries[bvid] = old.copy(customLrc = lrc, customSource = source)
        save()
    }

    fun clearCustomLrc(bvid: String) {
        ensure()
        val s = st()
        val old = s.entries[bvid] ?: return
        s.entries[bvid] = old.copy(customLrc = null, customSource = null)
        save()
    }

    // ---------------- 分段校准 ----------------

    fun getSegments(bvid: String): List<Segment> = entryOf(bvid).segments.orEmpty()

    /** 在 startMs 处加一个校准点：之后（含当前句）的歌词整体平移 offsetMs（负数=提前） */
    fun addSegment(bvid: String, startMs: Long, offsetMs: Long) {
        if (offsetMs == 0L) return
        ensure()
        val s = st()
        val old = s.entries[bvid] ?: Entry()
        val merged = (old.segments.orEmpty() + Segment(startMs, offsetMs))
            .sortedBy { it.start }
        s.entries[bvid] = old.copy(segments = merged)
        save()
    }

    fun clearSegments(bvid: String) {
        ensure()
        val s = st()
        val old = s.entries[bvid] ?: return
        s.entries[bvid] = old.copy(segments = null)
        save()
    }

    /**
     * 把校准段应用到手打以外的时间轴上：
     * 每行歌词的偏移 = 它前面最近的那个校准点的偏移（绝对偏移，非累计）。
     * 边界往前多含 3 秒，让「当前正在显示的这句慢了」也能被纠正。
     */
    fun applySegments(lines: List<LyricLine>, segments: List<Segment>): List<LyricLine> {
        if (segments.isEmpty() || lines.isEmpty()) return lines
        return lines.map { line ->
            var offset = 0L
            for (seg in segments) {
                if (line.timeMs >= seg.start - 3000) offset = seg.offset else break
            }
            if (offset == 0L) line
            else LyricLine((line.timeMs + offset).coerceAtLeast(0), line.text)
        }.sortedBy { it.timeMs }
    }

    /** 手打歌词解析：支持 [mm:ss.xx]、mm:ss.xx 前缀；完全无时间戳时按行均分 */
    fun parseManual(lrc: String, durationMs: Long): List<LyricLine> {
        val rawLines = lrc.split("\n")
            .map { it.replace("\r", "").trim() }
            .filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return emptyList()

        val timeRegex = Regex("^(?:\\[|\\()?((?:\\d{1,2}):)?(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?(?:\\]|\\))?\\s*")
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        for (raw in rawLines) {
            val m = timeRegex.find(raw)
            if (m != null && (m.groupValues[1].isNotEmpty() || raw.startsWith("[") || raw.contains(":"))) {
                val minPart = if (m.groupValues[1].isEmpty()) 0L
                else m.groupValues[1].dropLast(1).toLongOrNull() ?: 0L
                val min = minPart + (m.groupValues[2].toLongOrNull() ?: 0L)
                val sec = m.groupValues[3].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[4]
                val frac = when {
                    fracRaw.isEmpty() -> 0L
                    fracRaw.length == 1 -> fracRaw.toLong() * 100
                    fracRaw.length == 2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                val text = raw.substring(m.value.length).trim()
                if (text.isNotEmpty()) {
                    timed.add(LyricLine(min * 60_000 + sec * 1000 + frac, text))
                }
            } else {
                plain.add(raw)
            }
        }

        // 一条时间戳都没有：全部均分（首行从 2 秒起，给歌名留点空间）
        if (timed.isEmpty()) {
            val total = if (durationMs > 5000) durationMs - 3000 else rawLines.size * 5000L
            val step = (total / plain.size.coerceAtLeast(1)).coerceAtLeast(1200)
            return plain.mapIndexed { i, text -> LyricLine(2000 + i * step, text) }
        }
        return timed.sortedBy { it.timeMs }
    }
}
