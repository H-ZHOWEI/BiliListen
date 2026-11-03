package com.bilisten.app.data

import com.bilisten.app.util.Fmt
import com.google.gson.annotations.SerializedName

/**
 * 免登录演示歌单：几首公开音乐视频，登录前可以先听听看，
 * 也方便用户确认设备和网络一切正常。
 */
object DemoPlaylist {

    private val BVIDS = listOf(
        "BV1GJ411x7h7",
        "BV1fx411N7bU",
        "BV1Js411o76u",
        "BV1xx411c79H",
        "BV1tx411S7de"
    )

    suspend fun load(): List<Track> = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        val list = ArrayList<Track>()
        for (bvid in BVIDS) {
            try {
                val info = videoInfo(bvid) ?: continue
                list.add(info)
            } catch (e: Exception) {
                // 单个失败不影响其余
            }
        }
        list
    }

    private data class ViewResp(
        val code: Int = -1,
        val data: VideoData? = null
    )

    private data class VideoData(
        val bvid: String? = null,
        val title: String = "",
        val pic: String? = null,
        val cid: Long = 0,
        val owner: VideoOwner? = null,
        val duration: Int = 0
    )

    private data class VideoOwner(val name: String? = null, val mid: Long = 0)

    private suspend fun videoInfo(bvid: String): Track? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val json = Net.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            val resp = try {
                com.google.gson.Gson().fromJson(json, ViewResp::class.java)
            } catch (e: Exception) {
                null
            }
            val data = resp?.data ?: return@withContext null
            if (resp.code != 0 || data.cid <= 0) return@withContext null
            val cleaned = com.bilisten.app.util.TitleParser.clean(data.title)
            val uploader = data.owner?.name.orEmpty()
            Track(
                bvid = data.bvid ?: bvid,
                cid = data.cid,
                rawTitle = data.title,
                title = cleaned,
                artist = com.bilisten.app.util.TitleParser.guessArtist(cleaned, uploader),
                cover = Fmt.image(data.pic),
                durationMs = data.duration * 1000L,
                searchKeyword = com.bilisten.app.util.TitleParser.searchKeywords(cleaned, uploader)
                    .firstOrNull() ?: cleaned
            )
        }
}
