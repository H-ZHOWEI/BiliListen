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
            fun finalize(r: LyricResult): LyricResult =
                if (segments.isEmpty()) r
                else LyricResult(
                    LyricStore.applySegments(r.lines, segments),
                    r.source, r.keyword
                )

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
}
