package com.bilisten.app.data

import com.google.gson.annotations.SerializedName

data class ApiResp<T>(
    val code: Int = -1,
    val message: String? = null,
    val data: T? = null
)

data class NavData(
    val mid: Long = 0,
    val uname: String? = null,
    val face: String? = null,
    val isLogin: Boolean = false,
    @SerializedName("wbi_img") val wbiImg: WbiImg? = null
)

data class WbiImg(
    @SerializedName("img_url") val imgUrl: String? = null,
    @SerializedName("sub_url") val subUrl: String? = null
)

data class FavFolder(
    val id: Long = 0,
    val fid: Long = 0,
    val mid: Long = 0,
    val attr: Int = 0,
    val title: String = "",
    val cover: String? = null,
    @SerializedName("media_count") val mediaCount: Int = 0
)

data class FavFolderList(
    val list: List<FavFolder>? = null
)

data class FolderAddData(val id: Long = 0)

data class FavPage(
    val info: FavInfo? = null,
    val medias: List<FavMedia>? = null,
    @SerializedName("has_more") val hasMore: Boolean = false
)

data class FavInfo(
    val id: Long = 0,
    val title: String = "",
    @SerializedName("media_count") val mediaCount: Int = 0,
    val cover: String? = null
)

data class FavMedia(
    val id: Long = 0,
    val bvid: String? = null,
    val title: String = "",
    val cover: String? = null,
    /** 秒 */
    val duration: Int = 0,
    val attr: Int = 0,
    val upper: Upper? = null,
    val ugc: Ugc? = null,
    val cnt_info: CntInfo? = null
)

data class Upper(val mid: Long = 0, val name: String? = null)

data class Ugc(@SerializedName("first_cid") val firstCid: Long = 0)

data class CntInfo(val play: Int = 0, val collect: Int = 0)

data class PageListData(val cid: Long = 0, val pages: List<VideoPage>? = null)

data class VideoPage(val cid: Long = 0, val page: Int = 0, val part: String? = null, val duration: Int = 0)

data class PlayUrlData(
    val dash: Dash? = null,
    val durl: List<Durl>? = null
)

data class Dash(
    val audio: List<AudioTrack>? = null,
    val dolby: Dolby? = null,
    val flac: Flac? = null
)

data class Dolby(val audio: List<AudioTrack>? = null)
data class Flac(val audio: AudioTrack? = null)

data class AudioTrack(
    val id: Int = 0,
    val bandwidth: Long = 0,
    val codecs: String? = null,
    val mimeType: String? = null,
    @SerializedName("baseUrl") val baseUrl: String? = null,
    @SerializedName("base_url") val baseUrlAlt: String? = null,
    val backupUrl: List<String>? = null
) {
    val url: String? get() = baseUrl ?: baseUrlAlt
}

data class Durl(val url: String? = null, val length: Long = 0, val size: Long = 0)

data class PlayerV2Data(val subtitle: SubtitleWrap? = null)

data class SubtitleWrap(
    val subtitles: List<SubtitleItem>? = null,
    @SerializedName("need_login_subtitle") val needLogin: Boolean = false
)

data class SubtitleItem(
    val id: Long = 0,
    val lan: String? = null,
    @SerializedName("lan_doc") val lanDoc: String? = null,
    @SerializedName("subtitle_url") val subtitleUrl: String? = null
)

data class SubtitleBody(val body: List<SubtitleLine>? = null)

data class SubtitleLine(
    val from: Double = 0.0,
    val to: Double = 0.0,
    val content: String? = null
)

data class LrclibItem(
    val id: Long = 0,
    @SerializedName("trackName") val trackName: String? = null,
    @SerializedName("artistName") val artistName: String? = null,
    @SerializedName("albumName") val albumName: String? = null,
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    @SerializedName("plainLyrics") val plainLyrics: String? = null,
    @SerializedName("syncedLyrics") val syncedLyrics: String? = null
)
