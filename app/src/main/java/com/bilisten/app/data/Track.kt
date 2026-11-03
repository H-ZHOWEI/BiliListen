package com.bilisten.app.data

/** 一首歌 = 一个 B 站视频（取其音频流） */
data class Track(
    val bvid: String,
    val cid: Long,
    /** 视频 avid，收藏夹删除/移动接口要用 */
    val aid: Long = 0,
    /** 原始标题 */
    val rawTitle: String,
    /** 清洗后的歌名 */
    val title: String,
    /** UP 主，作为“歌手” */
    val artist: String,
    val cover: String,
    val durationMs: Long,
    /** 歌词搜索用的关键词，可在播放页手动修改 */
    val searchKeyword: String
)
