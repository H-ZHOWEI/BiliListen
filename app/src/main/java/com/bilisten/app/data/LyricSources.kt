package com.bilisten.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Base64

/**
 * 扩展歌词源：QQ音乐 / 酷狗（全部免 key，2026-11-03 实测可用；挂了自动跳过）。
 * 咪咕/酷我的公开接口已被反爬拦截、汽水/波点/番茄是字节系签名接口，均不可直连。
 * 每个源都要求「歌名对得上 + 时长接近」才采用，避免再出现张冠李戴。
 */
object LyricSources {

    private val gson = Gson()

    internal data class SourceSong(
        val id: String,
        val name: String,
        val artist: String?,
        val durationMs: Long
    )

    private class Rated(val lines: List<LyricLine>, val song: SourceSong)

    /** 主入口：按顺序尝试所有源，返回第一个可靠命中 */
    suspend fun load(track: Track, query: String): LyricResult? = withContext(Dispatchers.IO) {
        val sources = listOf(
            "QQ音乐" to ::fromQq,
            "酷狗" to ::fromKugou
        )
        for ((label, fetcher) in sources) {
            try {
                val hit = fetcher(track, query) ?: continue
                return@withContext LyricResult(
                    hit.lines,
                    "$label · ${hit.song.artist ?: hit.song.name}",
                    query
                )
            } catch (_: Exception) {
                // 单个源失败不影响其它源
            }
        }
        null
    }

    private fun accepted(track: Track, song: SourceSong, query: String): Boolean {
        if (!LyricsRepository.nameMatches(query, song.name)) return false
        if (track.durationMs > 0 && song.durationMs > 0 &&
            Math.abs(song.durationMs - track.durationMs) > 15_000
        ) return false
        return true
    }

    private fun parseLrc(lrc: String): List<LyricLine> = LrcParser.parse(lrc)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- QQ 音乐 ----------------

    private class QqSearchResp {
        @SerializedName("data") var data: QqSearchData? = null
    }

    private class QqSearchData {
        @SerializedName("song") var song: QqSongWrap? = null
    }

    private class QqSongWrap {
        @SerializedName("list") var list: List<QqSong> = emptyList()
    }

    private class QqSong {
        @SerializedName("songmid") var songmid: String? = null
        @SerializedName("songname") var songname: String? = null
        @SerializedName("singer") var singer: List<QqSinger> = emptyList()
        @SerializedName("interval") var interval: Int = 0
    }

    private class QqSinger {
        @SerializedName("name") var name: String? = null
    }

    private suspend fun qqSearch(query: String): List<SourceSong> {
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?w=${enc(query)}&format=json&n=8&cr=1"
        val json = Net.get(
            searchUrl,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to Net.UA)
        )
        return gson.fromJson(json, QqSearchResp::class.java)
            ?.data?.song?.list.orEmpty()
            .filter { !it.songmid.isNullOrBlank() }
            .map {
                SourceSong(
                    it.songmid.orEmpty(), it.songname.orEmpty(),
                    it.singer.firstOrNull()?.name, it.interval * 1000L
                )
            }
    }

    private suspend fun qqLyric(songmid: String): String? {
        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$songmid&g_tk=5381&format=json&nobase64=0"
        val body = Net.get(
            lyricUrl,
            mapOf("Referer" to "https://c.y.qq.com/", "User-Agent" to Net.UA)
        )
        // 返回可能是 jsonp：MusicJsonCallback({...})
        val jsonPart = body
            .removePrefix("MusicJsonCallback(")
            .removeSuffix(")")
            .trim()
        val obj = JsonParser.parseString(jsonPart).asJsonObject
        val b64 = obj.get("lyric")?.asString ?: return null
        return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    }

    private suspend fun fromQq(track: Track, query: String): Rated? {
        for (song in qqSearch(query)) {
            if (!accepted(track, song, query)) continue
            val lrc = qqLyric(song.id) ?: continue
            val lines = parseLrc(lrc)
            if (lines.size >= 3) return Rated(lines, song)
        }
        return null
    }

    // ---------------- 酷狗 ----------------

    private class KgSearchResp {
        @SerializedName("data") var data: KgSearchData? = null
    }

    private class KgSearchData {
        @SerializedName("info") var info: List<KgSong> = emptyList()
    }

    private class KgSong {
        @SerializedName("hash") var hash: String? = null
        @SerializedName("songname") var songname: String? = null
        @SerializedName("singername") var singername: String? = null
        @SerializedName("duration") var duration: Int = 0
    }

    /** 注意：candidates 在根节点，不在 data 下 */
    private class KgLyricCandidates {
        @SerializedName("candidates") var candidates: List<KgCandidate> = emptyList()
    }

    private class KgCandidate {
        @SerializedName("id") var id: String? = null
        @SerializedName("accesskey") var accesskey: String? = null
    }

    private class KgDownload {
        @SerializedName("content") var content: String? = null
    }

    private suspend fun kugouSearch(query: String): List<SourceSong> {
        // https 证书域名不匹配，走 http（manifest 已允许 cleartext）
        val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song" +
            "?format=json&keyword=${enc(query)}&page=1&pagesize=8"
        val json = Net.get(searchUrl, mapOf("User-Agent" to Net.UA))
        return gson.fromJson(json, KgSearchResp::class.java)
            ?.data?.info.orEmpty()
            .filter { !it.hash.isNullOrBlank() }
            .map {
                SourceSong(
                    it.hash.orEmpty(), it.songname.orEmpty(),
                    it.singername, it.duration * 1000L
                )
            }
    }

    private suspend fun kugouLyric(hash: String, keyword: String, durationMs: Long): String? {
        val findUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi" +
            "&keyword=${enc(keyword)}&hash=$hash&duration=$durationMs"
        val findJson = Net.get(findUrl, mapOf("User-Agent" to Net.UA))
        val cand = gson.fromJson(findJson, KgLyricCandidates::class.java)
            ?.candidates?.firstOrNull() ?: return null

        val dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc" +
            "&id=${cand.id}&accesskey=${cand.accesskey}&fmt=lrc&charset=utf8"
        val dlJson = Net.get(dlUrl, mapOf("User-Agent" to Net.UA))
        val b64 = gson.fromJson(dlJson, KgDownload::class.java)?.content ?: return null
        return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    }

    private suspend fun fromKugou(track: Track, query: String): Rated? {
        for (song in kugouSearch(query)) {
            if (!accepted(track, song, query)) continue
            val lrc = kugouLyric(song.id, query, track.durationMs) ?: continue
            val lines = parseLrc(lrc)
            if (lines.size >= 3) return Rated(lines, song)
        }
        return null
    }

    // ---------------- 「手打歌词」用：多版本候选 ----------------

    data class CandidateMeta(
        val source: String,
        val id: String,
        val name: String,
        val artist: String?,
        val durationMs: Long,
        val lrc: String? = null
    )

    /**
     * 只搜元信息（不拉歌词），列表能秒出；用户选中某个版本后再用 [lyricText] 拉它的 LRC。
     * 这里**不做**歌名/时长过滤 —— 手打歌词就是让用户自己挑最接近的版本。
     */
    suspend fun searchMeta(query: String, limit: Int = 6): List<CandidateMeta> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<CandidateMeta>()
            try {
                qqSearch(query).take(limit).forEach {
                    out.add(CandidateMeta("QQ音乐", it.id, it.name, it.artist, it.durationMs))
                }
            } catch (_: Exception) {
            }
            try {
                kugouSearch(query).take(limit).forEach {
                    out.add(CandidateMeta("酷狗", it.id, it.name, it.artist, it.durationMs))
                }
            } catch (_: Exception) {
            }
            out
        }

    suspend fun lyricText(
        source: String,
        id: String,
        title: String,
        durationMs: Long
    ): String? = withContext(Dispatchers.IO) {
        try {
            when (source) {
                "QQ音乐" -> qqLyric(id)
                "酷狗" -> kugouLyric(id, title, durationMs)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
