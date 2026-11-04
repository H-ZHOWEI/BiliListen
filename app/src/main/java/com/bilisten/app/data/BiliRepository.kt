package com.bilisten.app.data

import com.bilisten.app.util.Fmt
import com.bilisten.app.util.TitleParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * B 站接口封装。
 * 所有请求都走 Net（自动带 Referer/UA/Cookie），涉及 WBI 的接口失败会自动刷新签名重试一次。
 */
object BiliRepository {

    private val gson = Gson()

    private const val NAV = "https://api.bilibili.com/x/web-interface/nav"
    private const val FOLDERS = "https://api.bilibili.com/x/v3/fav/folder/created/list-all"
    private const val FAV_LIST = "https://api.bilibili.com/x/v3/fav/resource/list"
    private const val PAGELIST = "https://api.bilibili.com/x/player/pagelist"
    private const val PLAYURL = "https://api.bilibili.com/x/player/wbi/playurl"
    private const val PLAYER_V2 = "https://api.bilibili.com/x/player/wbi/v2"
    private const val FAV_DEL = "https://api.bilibili.com/x/v3/fav/resource/batch-del"
    private const val FAV_MOVE = "https://api.bilibili.com/x/v3/fav/resource/move"
    private const val FOLDER_ADD = "https://api.bilibili.com/x/v3/fav/folder/add"

    private inline fun <reified T> parse(json: String): ApiResp<T>? = try {
        gson.fromJson(json, object : TypeToken<ApiResp<T>>() {}.type)
    } catch (e: Exception) {
        null
    }

    private fun isWbiError(body: String): Boolean =
        body.contains("\"code\":-403") || body.contains("\"code\":-352") ||
            body.contains("\"code\":-412") || body.contains("\"code\":-401")

    // ---------------- 账号 ----------------

    /** 拉取登录状态 + WBI 密钥 */
    suspend fun fetchNav(): NavData? = withContext(Dispatchers.IO) {
        val resp = parse<NavData>(Net.get(NAV))
        resp?.data?.let { Wbi.update(it.wbiImg) }
        resp?.data
    }

    fun fetchNavBlocking(): NavData? {
        val resp = parse<NavData>(Net.get(NAV))
        resp?.data?.let { Wbi.update(it.wbiImg) }
        return resp?.data
    }

    private fun ensureWbi() {
        if (!Wbi.hasKeys()) fetchNavBlocking()
    }

    private fun getSigned(base: String, params: Map<String, String>): String {
        ensureWbi()
        var body = Net.get("$base?${Wbi.sign(params)}")
        if (isWbiError(body)) {
            Wbi.invalidate()
            fetchNavBlocking()
            body = Net.get("$base?${Wbi.sign(params)}")
        }
        return body
    }

    // ---------------- 收藏夹 ----------------

    suspend fun fetchFolders(mid: Long): List<FavFolder> = withContext(Dispatchers.IO) {
        val url = "$FOLDERS?up_mid=$mid&web_location=333.1387"
        val resp = parse<FavFolderList>(Net.get(url))
        resp?.data?.list.orEmpty().sortedByDescending { it.mediaCount }
    }

    suspend fun fetchFavPage(mediaId: Long, page: Int): FavPage = withContext(Dispatchers.IO) {
        val url = "$FAV_LIST?media_id=$mediaId&pn=$page&ps=20&keyword=&order=mtime&type=0&tid=0&platform=web"
        parse<FavPage>(Net.get(url))?.data ?: FavPage()
    }

    /** 拉取整个收藏夹（自动翻页） */
    suspend fun fetchAllTracks(mediaId: Long, onProgress: (Int) -> Unit = {}): List<Track> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<Track>()
            var page = 1
            while (page <= 50) {
                val data = fetchFavPage(mediaId, page)
                val medias = data.medias.orEmpty()
                for (m in medias) {
                    toTrack(m)?.let { result.add(it) }
                }
                onProgress(result.size)
                if (!data.hasMore || medias.isEmpty()) break
                page++
            }
            result
        }

    private fun toTrack(m: FavMedia): Track? {
        val bvid = m.bvid ?: return null
        if (m.attr != 0 || m.title.isBlank() || m.title == "已失效视频") return null
        val cleaned = TitleParser.clean(m.title)
        val uploader = m.upper?.name.orEmpty()
        val keyword = TitleParser.searchKeywords(cleaned, uploader).firstOrNull() ?: cleaned
        return Track(
            bvid = bvid,
            cid = m.ugc?.firstCid ?: 0L,
            aid = m.id,
            rawTitle = m.title,
            title = cleaned,
            artist = TitleParser.guessArtist(cleaned, uploader),
            cover = Fmt.image(m.cover),
            durationMs = m.duration * 1000L,
            searchKeyword = keyword
        )
    }

    // ---------------- 收藏夹管理（删除 / 移动） ----------------

    /** csrf token 存在 cookie 的 bili_jct 里 */
    private fun csrf(): String {
        val m = Regex("bili_jct=([^;\\s]+)").find(Prefs.cookie)
        return m?.groupValues?.getOrNull(1).orEmpty()
    }

    private class BiliWriteException(message: String) : Exception(message)

    private fun checkWrite(body: String) {
        val resp = parse<Any>(body)
        if (resp == null) throw BiliWriteException("B 站返回异常")
        when (resp.code) {
            0 -> return
            -101 -> throw BiliWriteException("登录状态已失效，请重新登录")
            -111 -> throw BiliWriteException("csrf 校验失败，请重新登录")
            else -> throw BiliWriteException(resp.message?.takeIf { it.isNotBlank() }
                ?: "操作失败（${resp.code}）")
        }
    }

    /**
     * 从收藏夹删除（取消收藏）。type=2 是视频稿件。
     * 注意：如果该视频同时收藏在其他收藏夹，只从这一个收藏夹移除。
     */
    suspend fun removeFromFolder(mediaId: Long, aids: List<Long>) = withContext(Dispatchers.IO) {
        val resources = aids.filter { it > 0 }.joinToString(",") { "$it:2" }
        if (resources.isBlank()) throw BiliWriteException("这首歌缺少视频 id，无法操作")
        checkWrite(
            Net.postForm(
                FAV_DEL,
                mapOf(
                    "resources" to resources,
                    "media_id" to mediaId.toString(),
                    "platform" to "web",
                    "csrf" to csrf()
                )
            )
        )
    }

    /** 把收藏内容移动到另一个收藏夹（自己的） */
    suspend fun moveToFolder(srcMediaId: Long, tarMediaId: Long, aids: List<Long>) =
        withContext(Dispatchers.IO) {
            val resources = aids.filter { it > 0 }.joinToString(",") { "$it:2" }
            if (resources.isBlank()) throw BiliWriteException("这首歌缺少视频 id，无法操作")
            checkWrite(
                Net.postForm(
                    FAV_MOVE,
                    mapOf(
                        "src_media_id" to srcMediaId.toString(),
                        "tar_media_id" to tarMediaId.toString(),
                        "mid" to Prefs.mid.toString(),
                        "resources" to resources,
                        "platform" to "web",
                        "csrf" to csrf()
                    )
                )
            )
        }

    /** 新建收藏夹，返回新夹的 mlid */
    suspend fun createFolder(title: String, private: Boolean = true): Long =
        withContext(Dispatchers.IO) {
            val form = LinkedHashMap<String, String>()
            form["title"] = title
            form["privacy"] = if (private) "1" else "0"
            form["csrf"] = csrf()
            val body = Net.postForm(FOLDER_ADD, form)
            val resp = parse<FolderAddData>(body)
            checkWrite(if (resp == null) "{}" else "{\"code\":${resp.code},\"message\":\"${resp.message.orEmpty()}\"}")
            resp?.data?.id ?: 0L
        }

    /**
     * 找到（没有则创建）一个名为「待删除」的私密收藏夹，返回 mlid。
     */
    suspend fun ensurePendingFolder(): Long = withContext(Dispatchers.IO) {
        val folders = fetchFolders(Prefs.mid)
        folders.firstOrNull { it.title.trim() == "待删除" }?.id?.takeIf { it > 0 }
            ?: createFolder("待删除", private = true)
    }

    // ---------------- 播放 ----------------

    /** 拿不到可播放音轨时抛出，message 直接给用户看 */
    class BiliPlayException(message: String) : Exception(message)

    suspend fun fetchFirstCid(bvid: String): Long = withContext(Dispatchers.IO) {
        fetchFirstCidBlocking(bvid)
    }

    fun fetchFirstCidBlocking(bvid: String): Long = try {
        val resp = parse<PageListData>(Net.get("$PAGELIST?bvid=$bvid"))
        resp?.data?.pages?.firstOrNull()?.cid ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun friendlyPlayCode(code: Int, msg: String?): String = when (code) {
        -403, -10403 -> "没有播放权限（付费 / 会员 / 地区限制）"
        -404 -> "视频不存在或已被删除"
        -87008 -> "该视频需要大会员"
        -352, -412 -> "被 B 站风控拦截了，稍后重试"
        else -> "拿不到播放地址（${code}）${msg.orEmpty()}"
    }

    /**
     * 取音频流地址（阻塞，供播放器的 DataSource 在播放前解析）。
     * quality: 0 自动最高 / 1 64K / 2 132K / 3 192K
     *
     * B 站会给出若干条 CDN 直链（baseUrl + backupUrl），单条经常抽风 403，
     * 所以这里挨个探测，返回第一个真正能拉的地址。
     */
    fun resolveAudioUrl(bvid: String, cid: Long, quality: Int = 0): String {
        // 收藏夹接口偶尔不带 first_cid，这里补一次
        val realCid = if (cid > 0L) cid else fetchFirstCidBlocking(bvid)
        if (realCid <= 0L) throw BiliPlayException("拿不到这个视频的分P信息")

        val params = mapOf(
            "bvid" to bvid,
            "cid" to realCid.toString(),
            "fnval" to "16",
            "fnver" to "0",
            "fourk" to "1",
            "high_quality" to "1"
        )
        val body = getSigned(PLAYURL, params)
        val resp = parse<PlayUrlData>(body) ?: throw BiliPlayException("播放地址解析失败")
        if (resp.code != 0) throw BiliPlayException(friendlyPlayCode(resp.code, resp.message))
        val data = resp.data ?: throw BiliPlayException("播放地址为空")

        val urls = buildCandidates(data, quality)
        if (urls.isEmpty()) throw BiliPlayException("这个视频没有可用音轨")

        // 探测前 6 条，挑一条真的能读的
        for (u in urls.distinct().take(6)) {
            val code = Net.probe(u)
            if (code in 200..299) return u
        }
        // 全部探测失败：仍返回首选，让播放器自己再试一次（可能是临时抖动）
        return urls.first()
    }

    private fun buildCandidates(data: PlayUrlData, quality: Int): List<String> {
        val dash = data.dash
        val normal = dash?.audio.orEmpty().filter { !it.url.isNullOrBlank() }
        val extras = ArrayList<AudioTrack>()
        dash?.dolby?.audio?.forEach { a -> if (!a.url.isNullOrBlank()) extras.add(a) }
        dash?.flac?.audio?.let { a -> if (!a.url.isNullOrBlank()) extras.add(a) }

        val targetId = when (quality) {
            1 -> 30216
            2 -> 30232
            3 -> 30280
            else -> 0
        }
        val primary = if (targetId != 0) {
            normal.firstOrNull { it.id == targetId } ?: normal.maxByOrNull { it.bandwidth }
        } else {
            normal.maxByOrNull { it.bandwidth }
        }

        val ordered = ArrayList<AudioTrack>()
        primary?.let { ordered.add(it) }
        ordered.addAll(normal.sortedByDescending { it.bandwidth })
        // 杜比 / Hi-Res 通常要大会员，放最后兜底
        if (quality == 0) ordered.addAll(extras)

        val urls = ArrayList<String>()
        for (a in ordered) {
            a.url?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
            a.backupUrl?.forEach { b -> if (b.isNotBlank()) urls.add(b) }
        }
        // 极少数老视频只有 durl（整段 mp4）
        data.durl?.forEach { d -> d.url?.takeIf { it.isNotBlank() }?.let { urls.add(it) } }
        return urls
    }

    suspend fun resolveAudioUrlAsync(bvid: String, cid: Long, quality: Int): String =
        withContext(Dispatchers.IO) { resolveAudioUrl(bvid, cid, quality) }

    /**
     * 取一段可直接播放的整段 mp4（视频+音频），用于「找不到可靠歌词时显示视频画面」的兜底。
     * 走 html5 播放端点（免 WBI、不要求大会员），返回 durl 整段流。
     */
    fun resolveVideoUrl(bvid: String, cid: Long): String {
        val realCid = if (cid > 0L) cid else fetchFirstCidBlocking(bvid)
        if (realCid <= 0L) throw BiliPlayException("拿不到这个视频的分P信息")

        var urls = emptyList<String>()
        try {
            val html5 = "https://api.bilibili.com/x/player/playurl" +
                "?bvid=$bvid&cid=$realCid&qn=64&type=mp4&platform=html5&high_quality=1"
            val resp = parse<PlayUrlData>(Net.get(html5))
            if (resp != null && resp.code != 0) {
                throw BiliPlayException(friendlyPlayCode(resp.code, resp.message))
            }
            urls = resp?.data?.durl.orEmpty()
                .mapNotNull { it.url?.takeIf { u -> u.isNotBlank() } }
        } catch (e: BiliPlayException) {
            throw e
        } catch (e: Exception) {
            // 网络抖动等，继续走 WBI 兜底
        }

        if (urls.isEmpty()) {
            // 退化：WBI playurl 不带 fnval=16，拿整段流而不是 DASH
            val body = getSigned(
                PLAYURL,
                mapOf(
                    "bvid" to bvid,
                    "cid" to realCid.toString(),
                    "qn" to "64",
                    "fnval" to "0",
                    "platform" to "html5",
                    "high_quality" to "1"
                )
            )
            val resp = parse<PlayUrlData>(body) ?: throw BiliPlayException("播放地址解析失败")
            if (resp.code != 0) throw BiliPlayException(friendlyPlayCode(resp.code, resp.message))
            urls = resp.data?.durl.orEmpty()
                .mapNotNull { it.url?.takeIf { u -> u.isNotBlank() } }
        }
        if (urls.isEmpty()) throw BiliPlayException("这个视频没有可播放的画面")

        // 同音频一样，直链经常单条抽风，先探测
        for (u in urls.distinct().take(6)) {
            val code = Net.probe(u)
            if (code in 200..299) return u
        }
        return urls.first()
    }

    // ---------------- 字幕（歌词默认来源） ----------------

    data class SubtitleResult(val lines: List<LyricLine>, val label: String)

    /**
     * 中文断句：含中文且较长的句子按标点拆成多行，时间在前一句与下一句之间插值，
     * 避免「一句歌词占满半屏」；英文/短句原样保留。这样 B 站 AI 字幕的长段也能整齐显示。
     */
    private fun improveCjkLines(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val punct = Regex("(?<=[，。！？、；：!?,.;:…])")
        val out = ArrayList<LyricLine>()
        for (i in lines.indices) {
            val line = lines[i]
            val t = line.text
            val hasCjk = t.any { it in '\u4E00'..'\u9FFF' }
            if (hasCjk && t.length > 12 && punct.containsMatchIn(t)) {
                val parts = t.split(punct).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    val next = if (i + 1 < lines.size) lines[i + 1].timeMs else line.timeMs + 4000
                    val span = (next - line.timeMs).coerceAtLeast(0)
                    val step = span / parts.size
                    parts.forEachIndexed { j, p -> out.add(LyricLine(line.timeMs + j * step, p)) }
                    continue
                }
            }
            out.add(line)
        }
        return out.sortedBy { it.timeMs }
    }

    /**
     * 取视频自带字幕（用户上传的 CC 字幕 / B 站 AI 字幕）。
     * 未登录时 B 站不返回字幕列表，所以这个功能依赖登录态。
     * preferLan 非空时只取指定语言轨（用户在「字幕/语音转文字」里手动选中的那条）。
     */
    suspend fun fetchSubtitle(bvid: String, cid: Long, aid: Long = 0L, preferLan: String? = null): SubtitleResult =
        withContext(Dispatchers.IO) {
            val subs = listSubtitles(bvid, cid, aid)
            if (subs.isEmpty()) return@withContext SubtitleResult(emptyList(), "")
            val pick = if (preferLan != null) {
                subs.firstOrNull { it.lan == preferLan || it.lanDoc == preferLan }
            } else {
                pickSubtitle(subs)
            } ?: return@withContext SubtitleResult(emptyList(), "")
            loadSubtitleResult(pick)
        }

    /** 列出所有可用字幕轨（给「字幕/语音转文字」选择界面用），已过滤掉空地址的 */
    suspend fun listSubtitles(bvid: String, cid: Long, aid: Long = 0L): List<SubtitleItem> =
        withContext(Dispatchers.IO) {
            if (cid <= 0L && bvid.isBlank()) return@withContext emptyList()
            try {
                val realCid = if (cid > 0L) cid else fetchFirstCidBlocking(bvid)
                val attempts = buildList {
                    if (bvid.isNotBlank() && realCid > 0L) {
                        add(mapOf("bvid" to bvid, "cid" to realCid.toString()))
                    }
                    if (bvid.isNotBlank()) add(mapOf("bvid" to bvid))
                    if (aid > 0L && realCid > 0L) {
                        add(mapOf("aid" to aid.toString(), "cid" to realCid.toString()))
                    }
                }
                for (params in attempts) {
                    val body = getSigned(PLAYER_V2, params)
                    val subs = parse<PlayerV2Data>(body)?.data?.subtitle?.subtitles.orEmpty()
                    if (subs.isNotEmpty()) return@withContext subs.filter { !it.subtitleUrl.isNullOrBlank() }
                }
            } catch (_: Exception) {
            }
            emptyList()
        }

    private fun loadSubtitleResult(item: SubtitleItem): SubtitleResult {
        val raw = item.subtitleUrl ?: return SubtitleResult(emptyList(), "")
        val url = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            else -> raw
        }
        return try {
            val json = Net.get(url)
            val bodyLines = gson.fromJson(json, SubtitleBody::class.java)?.body.orEmpty()
            val lines = bodyLines.mapNotNull { line ->
                val text = line.content
                    ?.replace("\r", "")
                    ?.replace("\n", " ")
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) null else LyricLine((line.from * 1000).toLong(), text)
            }.sortedBy { it.timeMs }
            val improved = improveCjkLines(lines)
            val label = if ((item.lan ?: "").startsWith("ai")) "B站AI字幕" else "B站字幕"
            SubtitleResult(improved, label)
        } catch (e: Exception) {
            SubtitleResult(emptyList(), "")
        }
    }

    /** 人工中文 > AI 中文 > 其它中文 > 第一个（lan 与 lan_doc 双重判断，参考 bilibili-subtitle-fetcher） */
    private fun pickSubtitle(subs: List<SubtitleItem>): SubtitleItem? {
        val usable = subs.filter { !it.subtitleUrl.isNullOrBlank() }
        if (usable.isEmpty()) return null
        fun isAi(s: SubtitleItem) = (s.lan ?: "").startsWith("ai")
        fun looksZh(s: SubtitleItem) =
            (s.lan ?: "").startsWith("zh") ||
                s.lanDoc.orEmpty().contains("中文") ||
                s.lanDoc.orEmpty().contains("普通话")
        return usable.firstOrNull { !isAi(it) && looksZh(it) }
            ?: usable.firstOrNull { isAi(it) && looksZh(it) }
            ?: usable.firstOrNull { (it.lan ?: "").startsWith("zh") }
            ?: usable.firstOrNull { isAi(it) }
            ?: usable.first()
    }

    suspend fun fetchSubtitleLines(bvid: String, cid: Long): List<LyricLine> =
        fetchSubtitle(bvid, cid).lines

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
