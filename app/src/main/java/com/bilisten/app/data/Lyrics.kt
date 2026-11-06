package com.bilisten.app.data

import com.bilisten.app.util.TitleParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

data class LyricResult(
    val lines: List<LyricLine> = emptyList(),
    val source: String = "",
    val keyword: String = ""
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 「手打歌词」用的底稿候选：手打前先挑一个最接近的现成版本，
 * 在它的基础上改文字/时间轴，省去从零敲一遍。
 * [lrc] 只有 B 站字幕会预先带上（本来就在内存里），其它源选中后再用
 * [LyricsRepository.fetchCandidateLrc] 拉取。
 */
data class LyricCandidate(
    val source: String,
    val id: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L,
    val similarity: Double = 0.0,
    val lrc: String = ""
)

/**
 * 歌词来源（按优先级）：
 * 1) B 站字幕：UP 主上传的 CC 字幕或 AI 字幕 —— 时间轴和视频完全对齐
 * 2) 网易云音乐：翻唱视频在 B 站没有字幕时，用清洗出的原曲歌名搜索网易云，
 *    原唱歌词 + 原曲时间轴（按时长校验匹配，避免拿错歌）
 * 3) LRCLIB（开源歌词库，免 key）
 * 4) 都没有时可在播放页手动改关键词再搜（走同一链路）
 * 播放页可打开「只用 B 站字幕」，彻底不用网络歌词。
 */
object LyricsRepository {

    private val gson = Gson()
    private val cache = HashMap<String, LyricResult>()

    /** 校准/手打后清掉这首歌的歌词缓存，强制重新加载 */
    fun invalidate(bvid: String) {
        synchronized(cache) { cache.keys.removeAll { it.startsWith("$bvid#") } }
    }

    suspend fun load(track: Track, forceKeyword: String? = null): LyricResult =
        withContext(Dispatchers.IO) {
            val keyword = forceKeyword?.takeIf { it.isNotBlank() } ?: track.searchKeyword
            val biliOnly = Prefs.lyricBiliOnly
            val key = "${track.bvid}#$keyword#${if (biliOnly) 1 else 0}"
            synchronized(cache) { cache[key] }?.let { return@withContext it }

            val segments = LyricStore.getSegments(track.bvid)
            val calibOffset = LyricStore.getCalibOffset(track.bvid)
            val calibRate = LyricStore.getCalibRate(track.bvid)
            val hasCalib = calibOffset != 0L || Math.abs(calibRate - 1.0) > 0.0001

            /** 依次应用：整体校准（偏移+速度倍率）→ 分段校准（局部平移） */
            fun finalize(r: LyricResult): LyricResult {
                var lines = r.lines
                if (hasCalib) {
                    lines = lines
                        .map {
                            LyricLine(
                                ((it.timeMs * calibRate).toLong() + calibOffset).coerceAtLeast(0),
                                it.text
                            )
                        }
                        .sortedBy { it.timeMs }
                }
                if (segments.isNotEmpty()) {
                    lines = LyricStore.applySegments(lines, segments)
                }
                return if (lines === r.lines) r else LyricResult(lines, r.source, r.keyword)
            }

            // 0. 用户手打歌词（最高优先级，持久保存）
            if (forceKeyword.isNullOrBlank()) {
                LyricStore.getCustomLrc(track.bvid)?.let { lrc ->
                    val lines = LyricStore.parseManual(lrc, track.durationMs)
                    if (lines.isNotEmpty()) {
                        val result = LyricResult(
                            lines,
                            LyricStore.getCustomSource(track.bvid) ?: "我的歌词",
                            keyword
                        )
                        synchronized(cache) { cache[key] = result }
                        return@withContext result
                    }
                }
            }

            // 1. B 站字幕（默认来源）
            if (forceKeyword.isNullOrBlank()) {
                val chosen = LyricStore.getChosenSubtitle(track.bvid)
                val sub = BiliRepository.fetchSubtitle(track.bvid, track.cid, track.aid, chosen)
                if (sub.lines.isNotEmpty()) {
                    val result = finalize(
                        LyricResult(sub.lines, sub.label.ifBlank { "B站字幕" }, keyword)
                    )
                    synchronized(cache) { cache[key] = result }
                    return@withContext result
                }
                // 纯音乐 / 伴奏：B 站也没字幕时，不去网上乱搜，避免匹配到别的歌的歌词
                if (chosen == null && isLikelyInstrumental(track.rawTitle)) {
                    return@withContext LyricResult(emptyList(), "", keyword)
                }
            }

            // 2. 用户要求只用 B 站字幕时，到此为止
            if (biliOnly) {
                return@withContext LyricResult(emptyList(), "", keyword)
            }

            // 3. 网易云（原曲歌词，翻唱视频的主要来源）
            val ne = fromNetease(track, keyword)
            if (!ne.isEmpty) {
                val result = finalize(ne)
                synchronized(cache) { cache[key] = result }
                return@withContext result
            }

            // 4. 扩展通道：QQ音乐 → 酷狗 → 酷我 → 咪咕
            for (q in neQueries(track, keyword)) {
                val ext = LyricSources.load(track, q)
                if (ext != null) {
                    val result = finalize(ext)
                    synchronized(cache) { cache[key] = result }
                    return@withContext result
                }
            }

            // 5. 兜底：LRCLIB
            val result = finalize(fromLrclib(track, keyword))
            if (!result.isEmpty) synchronized(cache) { cache[key] = result }
            result
        }

    // ---------------- 手打歌词：多版本底稿搜索 ----------------

    /**
     * 给「手打歌词」找底稿：把这首歌在各个源里的**多个版本**都列出来，
     * 由用户挑最接近的那一版，再在它上面改字/改时间轴。
     *
     * 和 [load] 的区别：这里**不**做歌名/时长硬过滤（同一个视频可能用的是翻唱、
     * 加速版、remix，只有用户自己知道哪版最像），只按相近度排序，最多 20 条。
     */
    suspend fun searchCandidates(track: Track, queryOverride: String? = null): List<LyricCandidate> =
        withContext(Dispatchers.IO) {
            val q = queryOverride?.takeIf { it.isNotBlank() } ?: track.searchKeyword
            val out = ArrayList<LyricCandidate>()

            // B 站字幕（时间轴和视频天然对齐，最省力，排最前）
            if (queryOverride.isNullOrBlank()) {
                try {
                    val sub = BiliRepository.fetchSubtitle(
                        track.bvid, track.cid, track.aid,
                        LyricStore.getChosenSubtitle(track.bvid)
                    )
                    if (sub.lines.isNotEmpty()) {
                        out.add(
                            LyricCandidate(
                                source = sub.label.ifBlank { "B站字幕" },
                                id = "",
                                title = track.title,
                                artist = track.artist,
                                durationMs = track.durationMs,
                                similarity = 1.0,
                                lrc = LrcParser.toText(sub.lines)
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }

            // 网易云
            try {
                val url = "https://music.163.com/api/search/get/web?s=" +
                    URLEncoder.encode(q, "UTF-8") + "&type=1&offset=0&total=true&limit=10"
                val json = Net.get(url, mapOf("Referer" to "https://music.163.com/"))
                gson.fromJson(json, NeSearchResp::class.java)
                    ?.result?.songs.orEmpty()
                    .filter { !it.name.isNullOrBlank() }
                    .forEach { s ->
                        out.add(
                            LyricCandidate(
                                "网易云", s.id.toString(), s.name.orEmpty(),
                                s.artists?.joinToString("/") { it.name.orEmpty() }.orEmpty(),
                                s.duration, score(q, s.name, s.duration, track.durationMs)
                            )
                        )
                    }
            } catch (_: Exception) {
            }

            // QQ 音乐 / 酷狗
            try {
                LyricSources.searchMeta(q).forEach {
                    out.add(
                        LyricCandidate(
                            it.source, it.id, it.name, it.artist.orEmpty(), it.durationMs,
                            score(q, it.name, it.durationMs, track.durationMs)
                        )
                    )
                }
            } catch (_: Exception) {
            }

            // LRCLIB（搜索结果本身就带歌词，直接预填，省一次请求）
            try {
                searchLrclib(q).take(6).forEach { item ->
                    val dur = (item.duration * 1000).toLong()
                    out.add(
                        LyricCandidate(
                            "LRCLIB", item.id.toString(), item.trackName.orEmpty(),
                            item.artistName.orEmpty(), dur,
                            score(q, item.trackName, dur, track.durationMs),
                            item.syncedLyrics.orEmpty()
                        )
                    )
                }
            } catch (_: Exception) {
            }

            // 去重（同名同艺人同时长算同一版），按相近度排序
            val seen = HashSet<String>()
            out.filter { c ->
                val key = "${c.title.lowercase()}|${c.artist.lowercase()}|${c.durationMs / 1000}"
                seen.add(key)
            }.sortedWith(
                compareByDescending<LyricCandidate> { it.similarity }
                    .thenBy { c ->
                        if (c.durationMs > 0 && track.durationMs > 0)
                            Math.abs(c.durationMs - track.durationMs) else Long.MAX_VALUE / 2
                    }
            ).take(20)
        }

    /** 相近度 = 歌名重合 60% + 时长接近 40%（时长缺失时给中间值） */
    private fun score(query: String, name: String?, durMs: Long, videoMs: Long): Double {
        val ns = nameSimilarity(query, name.orEmpty())
        val ds = if (durMs > 0 && videoMs > 0) {
            (1.0 - Math.abs(durMs - videoMs) / 60_000.0).coerceIn(0.0, 1.0)
        } else 0.5
        return ns * 0.6 + ds * 0.4
    }

    /** 拉取某个候选版本的 LRC 原文（B 站字幕直接就绪，其它源按需请求） */
    suspend fun fetchCandidateLrc(c: LyricCandidate): String = withContext(Dispatchers.IO) {
        if (c.lrc.isNotBlank()) return@withContext c.lrc
        try {
            when (c.source) {
                "网易云" -> {
                    val json = Net.get(
                        "https://music.163.com/api/song/lyric?id=${c.id}&lv=-1&kv=-1&tv=-1",
                        mapOf("Referer" to "https://music.163.com/")
                    )
                    gson.fromJson(json, NeLyricResp::class.java)?.lrc?.lyric.orEmpty()
                }
                "LRCLIB" -> searchLrclib("${c.title} ${c.artist}".trim())
                    .firstOrNull { it.id.toString() == c.id }
                    ?.syncedLyrics.orEmpty()
                else -> LyricSources.lyricText(c.source, c.id, c.title, c.durationMs).orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    // ---------------- 网易云 ----------------

    /**
     * 歌名相似度（0~1）：时长校验不够 —— 网易云里时长接近的另一首歌会被错配，
     * 出现「牛头不对马嘴」。这里要求搜到的歌名和关键词确实沾边（字符重合度），
     * 达不到阈值就放弃这首歌，宁可没有歌词也不显示错的。
     */
    internal fun nameSimilarity(a: String, b: String): Double {
        val na = a.filter { it.isLetterOrDigit() }.lowercase()
        val nb = b.filter { it.isLetterOrDigit() }.lowercase()
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na.contains(nb) || nb.contains(na)) return 1.0
        val setA = na.toCharArray().toSet()
        val setB = nb.toCharArray().toSet()
        val common = setA.intersect(setB).size
        return common.toDouble() / minOf(setA.size, setB.size).coerceAtLeast(1)
    }

    internal fun nameMatches(query: String, name: String?): Boolean =
        nameSimilarity(query, name.orEmpty()) >= 0.5

    /**
     * 判断标题是否像「纯音乐 / 伴奏 / BGM」——这类视频没有歌词，
     * 如果还去网易云 / QQ / 酷狗盲搜，往往会拿到同名的“带歌词版本”而张冠李戴。
     * 命中强信号词就判定为纯音乐；但若标题明显是翻唱/演唱类，则不拦截（它们通常有歌词）。
     */
    internal fun isLikelyInstrumental(rawTitle: String): Boolean {
        val lower = rawTitle.lowercase()
        val keywords = listOf(
            "纯音乐", "纯伴奏", "伴奏", "背景音乐", "bgm", "轻音乐", "间奏",
            "无歌词", "纯音", "纯享", "纯钢琴", "纯吉他", "纯小提琴",
            "钢琴曲", "吉他曲", "小提琴曲", "八音盒", "无人声", "纯器乐",
            "instrumental"
        )
        if (!keywords.any { lower.contains(it) }) return false
        // 翻唱 / 演唱类标题即使含“伴奏/钢琴”也通常不缺歌词，放行
        if (lower.contains("翻唱") || lower.contains("演唱") || lower.contains("女声")
            || lower.contains("男声") || lower.contains("合唱") || lower.contains("原唱")
            || lower.contains("清唱") || lower.contains("vocal") || lower.contains("歌词")
        ) return false
        return true
    }

    internal data class NeSearchResp(val result: NeResult? = null, val code: Int = -1)
    internal data class NeResult(val songs: List<NeSong>? = null)
    internal data class NeSong(
        val id: Long = 0,
        val name: String? = null,
        val duration: Long = 0,
        val artists: List<NeArtist>? = null
    )
    internal data class NeArtist(val name: String? = null)
    internal data class NeLyricResp(val lrc: NeLrc? = null)
    internal data class NeLrc(val lyric: String? = null)

    private fun neQueries(track: Track, keyword: String): List<String> {
        val list = LinkedHashSet<String>()
        // 《》内的原曲歌名最可靠（翻唱标题「翻唱《xxx》」）
        TitleParser.guessSongTitle(track.rawTitle).takeIf { it.isNotBlank() }?.let { list.add(it) }
        list.add(keyword)
        TitleParser.searchKeywords(track.title, track.artist).forEach { list.add(it) }
        return list.toList().filter { it.isNotBlank() }.distinct().take(4)
    }

    private fun fromNetease(track: Track, keyword: String): LyricResult {
        for (q in neQueries(track, keyword)) {
            try {
                val searchUrl = "https://music.163.com/api/search/get/web?s=" +
                    URLEncoder.encode(q, "UTF-8") +
                    "&type=1&offset=0&total=true&limit=8"
                val json = Net.get(
                    searchUrl,
                    mapOf("Referer" to "https://music.163.com/")
                )
                val songs = gson.fromJson(json, NeSearchResp::class.java)
                    ?.result?.songs.orEmpty()
                if (songs.isEmpty()) continue

                // 按视频时长挑最接近的候选（±15s 内才算同一首歌）
                val candidates = songs
                    .filter { !it.name.isNullOrBlank() }
                    .sortedBy { s ->
                        if (track.durationMs > 0 && s.duration > 0)
                            Math.abs(s.duration - track.durationMs) else Long.MAX_VALUE / 2
                    }
                for (song in candidates.take(3)) {
                    if (track.durationMs > 0 && song.duration > 0 &&
                        Math.abs(song.duration - track.durationMs) > 15_000
                    ) continue
                    // 歌名对不上就放弃：时长接近但完全是另一首歌的情况太坑
                    if (!nameMatches(q, song.name)) continue

                    val lyricJson = Net.get(
                        "https://music.163.com/api/song/lyric?id=${song.id}&lv=-1&kv=-1&tv=-1",
                        mapOf("Referer" to "https://music.163.com/")
                    )
                    val lrc = gson.fromJson(lyricJson, NeLyricResp::class.java)?.lrc?.lyric
                        ?: continue
                    val lines = LrcParser.parse(lrc)
                    if (lines.size >= 3) {
                        val artist = song.artists?.joinToString("/") { it.name.orEmpty() }
                            ?.takeIf { it.isNotBlank() } ?: "网易云"
                        return LyricResult(lines, "网易云 · $artist", q)
                    }
                }
            } catch (e: Exception) {
                // 单个关键词失败继续下一个
            }
        }
        return LyricResult(emptyList(), "", keyword)
    }

    private fun fromLrclib(track: Track, keyword: String): LyricResult {
        val queries = LinkedHashSet<String>()
        queries.add(keyword)
        TitleParser.searchKeywords(track.title, track.artist).forEach { queries.add(it) }
        queries.add("${track.title} ${track.artist}")

        for (q in queries) {
            val items = searchLrclib(q).filter { nameMatches(q, it.trackName) }
            if (items.isEmpty()) continue
            val withSynced = items.filter { !it.syncedLyrics.isNullOrBlank() }
            if (withSynced.isEmpty()) continue

            val best = if (track.durationMs > 0) {
                withSynced.minByOrNull { item ->
                    if (item.duration <= 0.0) 999.0
                    else Math.abs(item.duration * 1000 - track.durationMs)
                }
            } else withSynced.first()

            val lines = LrcParser.parse(best?.syncedLyrics.orEmpty())
            if (lines.isNotEmpty()) {
                return LyricResult(lines, "LRCLIB · ${best?.artistName.orEmpty()}", q)
            }
        }
        return LyricResult(emptyList(), "", keyword)
    }

    private fun searchLrclib(query: String): List<LrclibItem> {
        return try {
            val url = "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
            val json = Net.get(url)
            gson.fromJson(json, object : TypeToken<List<LrclibItem>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object LrcParser {

    private val timeTag = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val lines = ArrayList<LyricLine>()
        for (raw in lrc.split("\n")) {
            val matched = timeTag.findAll(raw).toList()
            if (matched.isEmpty()) continue
            val text = raw.replace(timeTag, "").trim()
            if (text.isEmpty()) continue
            for (m in matched) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val frac = when {
                    fracRaw.isEmpty() -> 0L
                    fracRaw.length == 1 -> fracRaw.toLong() * 100
                    fracRaw.length == 2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                lines.add(LyricLine(min * 60_000 + sec * 1000 + frac, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** 反向：把歌词行还原成 LRC 文本（「手打歌词」的底稿预填用） */
    fun toText(lines: List<LyricLine>): String = buildString {
        lines.sortedBy { it.timeMs }.forEach { line ->
            val ms = line.timeMs.coerceAtLeast(0)
            val total = ms / 1000
            append('[')
            append(String.format("%02d", total / 60))
            append(':')
            append(String.format("%02d", total % 60))
            append('.')
            append(String.format("%02d", (ms % 1000) / 10))
            append(']')
            append(line.text)
            append('\n')
        }
    }
}
