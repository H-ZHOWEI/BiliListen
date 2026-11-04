package com.bilisten.app.data

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 多P视频的「只保留某个分P」持久化：
 * bvid -> 用户选定的分P（页码、cid、分P标题、时长）。
 * 收藏夹列表和播放页都会应用这个选择，只改本地展示/播放，不影响 B 站任何数据。
 */
object PageStore {

    data class PageChoice(
        val page: Int = 1,
        val cid: Long = 0,
        val part: String = "",
        val durationMs: Long = 0
    )

    private data class Store(val choices: MutableMap<String, PageChoice> = LinkedHashMap())

    private val gson = Gson()
    private var store: Store? = null
    private var file: File? = null

    /** BiliApp 启动时调用一次 */
    fun init(context: Context) {
        val f = File(context.filesDir, "page_store.json")
        file = f
        if (f.exists()) {
            try {
                store = gson.fromJson(f.readText(), Store::class.java)
            } catch (_: Exception) {
                store = null
            }
        }
        if (store == null) store = Store()
    }

    private fun st(): Store = store ?: Store().also { store = it }

    private fun save() {
        val f = file ?: return
        try {
            f.writeText(gson.toJson(st()))
        } catch (_: Exception) {
        }
    }

    fun get(bvid: String): PageChoice? = st().choices[bvid]

    fun save(bvid: String, choice: PageChoice) {
        st().choices[bvid] = choice
        save()
    }

    fun clear(bvid: String) {
        st().choices.remove(bvid)
        save()
    }

    /**
     * 把已保存的分P选择应用到一个 Track 上（列表加载时调用，无需额外网络请求）。
     * 标题后面会带上「·P页码 分P名」让用户一眼看出这是哪个分P。
     */
    fun apply(track: Track): Track {
        val c = get(track.bvid) ?: return track
        if (c.cid <= 0L || c.cid == track.cid) return track
        val suffix = buildString {
            append(" · P").append(c.page)
            if (c.part.isNotBlank() && c.part != track.title) append(" ").append(c.part)
        }
        return track.copy(
            cid = c.cid,
            durationMs = c.durationMs,
            title = (track.title + suffix).take(120)
        )
    }
}
