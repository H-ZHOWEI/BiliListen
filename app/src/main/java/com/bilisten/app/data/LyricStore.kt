package com.bilisten.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import kotlin.math.abs

/**
 * 歌词显示目标：手机的播放页/悬浮窗 与 车机通知/AVRCP 是两条独立时间轴。
 * 车机链路（蓝牙/投屏）通常比手机慢几百毫秒，且慢的程度取决于车机本身，
 * 所以车机校准是【全局】的（对所有歌曲生效）；手机校准是【按歌】保存的。
 */
enum class LyricTarget { PHONE, CAR }

/**
 * 歌词 DIY 仓库（持久化，下次打开依然生效）：
 * 1) customLrc：用户手打 / 粘贴的歌词（优先级最高）
 * 2) chosenLrc：用户从多个版本里指定的歌词（按歌记住，下次播放仍用这一版）
 * 3) 整体校准（偏移 + 速度倍率）与分段校准：按 [LyricTarget] 分别保存
 */
object LyricStore {

    data class Segment(val start: Long = 0, val offset: Long = 0)

    data class Entry(
        val customLrc: String? = null,
        val customSource: String? = null,
        /** 用户指定使用的歌词版本（记住选择） */
        val chosenLrc: String? = null,
        val chosenLabel: String? = null,
        val segments: List<Segment>? = null,
        /** 用户在「字幕/语音转文字」里指定使用的字幕语言轨（如 zh-CN / ai-zh） */
        val chosenSubtitle: String? = null,
        /** 手机：整体校准偏移（毫秒，正=延后显示，负=提前显示）——按歌保存 */
        val calibOffset: Long? = null,
        /** 手机：整体校准速度倍率（>1 显示越来越慢，<1 越来越快）——按歌保存 */
        val calibRate: Double? = null,
        /**
         * 车机：独立的一套校准。v1.17 起车机校准是全局的，这三个字段
         * 只写在 [CAR_KEY] 这个保留条目里，普通歌曲条目不再使用（旧数据启动时迁移）。
         */
        val carSegments: List<Segment>? = null,
        val carCalibOffset: Long? = null,
        val carCalibRate: Double? = null
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
        migrateGlobalCar()
    }

    /**
     * v1.17 之前车机校准按歌保存；用户反馈所有车机普遍慢于手机，
     * 车机校准改为全局。启动时把散落在各歌条目里的车机校准
     * 合并成一份全局（优先取分段校准最多的那份），并清掉旧位置。
     */
    private fun migrateGlobalCar() {
        val s = st()
        var best: Entry? = null
        var bestSegs = -1
        var touched = false
        for ((k, e) in s.entries) {
            if (k == CAR_KEY) continue
            val hasCar = e.carCalibOffset != null || e.carCalibRate != null || !e.carSegments.isNullOrEmpty()
            if (!hasCar) continue
            val segs = e.carSegments?.size ?: 0
            if (best == null || segs > bestSegs) { best = e; bestSegs = segs }
            s.entries[k] = e.copy(carCalibOffset = null, carCalibRate = null, carSegments = null)
            touched = true
        }
        if (!touched) return
        val global = s.entries[CAR_KEY]
        val globalHasCar = global != null && (
            global.carCalibOffset != null || global.carCalibRate != null ||
                !global.carSegments.isNullOrEmpty()
            )
        if (global == null || !globalHasCar) {
            best?.let { s.entries[CAR_KEY] = it }
        }
        save()
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

    /** 车机校准的全局保留条目 key：所有歌曲共用同一份车机校准 */
    private const val CAR_KEY = "@car_global@"

    /** 取某个设备对应的存储条目：车机 → 全局条目，手机 → 按歌条目 */
    private fun entryFor(bvid: String, target: LyricTarget): Entry =
        entryOf(if (target == LyricTarget.CAR) CAR_KEY else bvid)

    private fun save() {
        val f = file ?: return
        try {
            f.writeText(gson.toJson(store))
        } catch (_: Exception) {
        }
    }

    private fun entryOf(bvid: String): Entry = st().entries[bvid] ?: Entry()

    private fun update(bvid: String, block: (Entry) -> Entry) {
        ensure()
        val s = st()
        s.entries[bvid] = block(s.entries[bvid] ?: Entry())
        save()
    }

    // ---------------- 手打歌词 ----------------

    fun getCustomLrc(bvid: String): String? = entryOf(bvid).customLrc

    fun getCustomSource(bvid: String): String? = entryOf(bvid).customSource

    fun setCustomLrc(bvid: String, lrc: String, source: String = "我的歌词") {
        update(bvid) { it.copy(customLrc = lrc, customSource = source) }
    }

    fun clearCustomLrc(bvid: String) {
        update(bvid) { it.copy(customLrc = null, customSource = null) }
    }

    // ---------------- 指定歌词版本（按歌记住） ----------------

    fun getChosenLrc(bvid: String): String? = entryOf(bvid).chosenLrc

    fun getChosenLabel(bvid: String): String? = entryOf(bvid).chosenLabel

    fun setChosenVersion(bvid: String, label: String, lrc: String) {
        update(bvid) { it.copy(chosenLabel = label, chosenLrc = lrc) }
    }

    fun clearChosenVersion(bvid: String) {
        update(bvid) { it.copy(chosenLabel = null, chosenLrc = null) }
    }

    // ---------------- 指定字幕轨（语音转文字来源） ----------------

    fun getChosenSubtitle(bvid: String): String? = entryOf(bvid).chosenSubtitle

    fun setChosenSubtitle(bvid: String, lan: String?) {
        update(bvid) { it.copy(chosenSubtitle = lan) }
    }

    // ---------------- 整体校准（偏移 + 速度倍率）：手机按歌，车机全局 ----------------

    fun getCalibOffset(bvid: String, target: LyricTarget = LyricTarget.PHONE): Long {
        val e = entryFor(bvid, target)
        return if (target == LyricTarget.CAR) e.carCalibOffset ?: 0L else e.calibOffset ?: 0L
    }

    fun getCalibRate(bvid: String, target: LyricTarget = LyricTarget.PHONE): Double {
        val e = entryFor(bvid, target)
        return if (target == LyricTarget.CAR) e.carCalibRate ?: 1.0 else e.calibRate ?: 1.0
    }

    fun getSegments(bvid: String, target: LyricTarget = LyricTarget.PHONE): List<Segment> {
        val e = entryFor(bvid, target)
        return if (target == LyricTarget.CAR) e.carSegments.orEmpty() else e.segments.orEmpty()
    }

    fun hasCalibration(bvid: String, target: LyricTarget = LyricTarget.PHONE): Boolean {
        val e = entryFor(bvid, target)
        val off = if (target == LyricTarget.CAR) e.carCalibOffset else e.calibOffset
        val rate = if (target == LyricTarget.CAR) e.carCalibRate else e.calibRate
        val segs = if (target == LyricTarget.CAR) e.carSegments else e.segments
        return off != null || rate != null || !segs.isNullOrEmpty()
    }

    /** 保存整体校准；offset=0 且 rate≈1 时视为重置（手机只影响这首歌，车机影响所有歌） */
    fun setCalibration(
        bvid: String,
        offsetMs: Long,
        rate: Double,
        target: LyricTarget = LyricTarget.PHONE
    ) {
        val reset = offsetMs == 0L && abs(rate - 1.0) < 0.0001
        val off: Long? = if (reset) null else offsetMs
        val r: Double? = if (reset) null else rate
        update(if (target == LyricTarget.CAR) CAR_KEY else bvid) { e ->
            if (target == LyricTarget.CAR) e.copy(carCalibOffset = off, carCalibRate = r)
            else e.copy(calibOffset = off, calibRate = r)
        }
    }

    /**
     * 「当前歌词应该在哪」：整条时间轴平移 deltaMs（正=歌词整体延后，负=提前）。
     * 走整体偏移字段，避免和分段校准的绝对值语义打架；同一设备多次对齐自动累加。
     */
    fun shiftCalibration(bvid: String, deltaMs: Long, target: LyricTarget) {
        if (deltaMs == 0L) return
        val rate = getCalibRate(bvid, target)
        setCalibration(bvid, getCalibOffset(bvid, target) + deltaMs, rate, target)
    }

    // ---------------- 分段校准：手机按歌，车机全局 ----------------

    /**
     * 在 startMs 处加一个校准点：之后（含当前句）的歌词整体平移 offsetMs（负数=提前）。
     * 同一个位置附近反复调整按「叠加」处理（调 0.5 秒再调 0.1 秒 = 0.6 秒），
     * 符合用户「一点点修准」的直觉；旧实现是覆盖，会让多次调整互相打架。
     */
    fun addSegment(
        bvid: String,
        startMs: Long,
        offsetMs: Long,
        target: LyricTarget = LyricTarget.PHONE
    ) {
        if (offsetMs == 0L) return
        val current = getSegments(bvid, target)
        val last = current.lastOrNull()
        val base = last?.offset ?: 0L
        val merged = if (last != null && abs(last.start - startMs) < 2500) {
            current.dropLast(1) + Segment(last.start, base + offsetMs)
        } else {
            current + Segment(startMs, base + offsetMs)
        }.filter { it.offset != 0L }.sortedBy { it.start }
        update(if (target == LyricTarget.CAR) CAR_KEY else bvid) { e ->
            if (target == LyricTarget.CAR) e.copy(carSegments = merged.ifEmpty { null })
            else e.copy(segments = merged.ifEmpty { null })
        }
    }

    fun clearSegments(bvid: String, target: LyricTarget = LyricTarget.PHONE) {
        update(if (target == LyricTarget.CAR) CAR_KEY else bvid) { e ->
            if (target == LyricTarget.CAR) e.copy(carSegments = null) else e.copy(segments = null)
        }
    }

    /**
     * 重置校准：手机只清这首歌的，车机是全局的、清了影响所有歌
     * （调用方 UI 会提示这一点）。
     */
    fun clearAllCalibration(bvid: String) {
        update(bvid) {
            it.copy(calibOffset = null, calibRate = null, segments = null)
        }
        update(CAR_KEY) {
            it.copy(carCalibOffset = null, carCalibRate = null, carSegments = null)
        }
    }

    /**
     * 把校准应用到原始时间轴：整体缩放/偏移 → 分段平移。
     * 手机和车机各调用一次，得到两条互不干扰的时间轴
     * （车机参数来自全局条目，对所有歌一样）。
     */
    fun applyCalibration(
        lines: List<LyricLine>,
        bvid: String,
        target: LyricTarget
    ): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val offset = getCalibOffset(bvid, target)
        val rate = getCalibRate(bvid, target)
        val hasCalib = offset != 0L || abs(rate - 1.0) > 0.0001
        var out = if (hasCalib) {
            lines.map {
                LyricLine(((it.timeMs * rate).toLong() + offset).coerceAtLeast(0), it.text)
            }.sortedBy { it.timeMs }
        } else lines
        val segs = getSegments(bvid, target)
        if (segs.isNotEmpty()) out = applySegments(out, segs)
        return out
    }

    /**
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
