package com.bilisten.app.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/**
 * 本地歌单：以 B 站收藏夹为源数据，用户可以在软件里自由二改 ——
 * 创建歌单、把收藏夹里的歌加进来、调整归属，全部只存在本地，
 * 不会影响 B 站账号里的任何数据。
 *
 * 默认自带一个「我喜欢」歌单（不可删除，可改名）。
 */
object PlaylistStore {

    data class Playlist(
        val id: String,
        val name: String,
        val tracks: List<Track> = emptyList(),
        val createdAt: Long = 0,
        /** 默认歌单标记：不可删除 */
        val builtin: Boolean = false
    )

    private data class Store(val playlists: MutableList<Playlist> = mutableListOf())

    private val gson = Gson()
    private var store: Store? = null
    private var file: File? = null

    private val _changed = MutableStateFlow(0)

    /** 任何增删改都会 +1，界面订阅它刷新 */
    val changed: StateFlow<Int> = _changed

    /** BiliApp 启动时调用一次 */
    fun init(context: Context) {
        val f = File(context.filesDir, "playlists.json")
        file = f
        if (f.exists()) {
            try {
                store = gson.fromJson(f.readText(), Store::class.java)
            } catch (_: Exception) {
                store = null
            }
        }
        if (store == null) store = Store()
        ensureDefault()
    }

    private fun st(): Store = store ?: Store().also { store = it }

    private fun save() {
        val f = file ?: return
        try {
            f.writeText(gson.toJson(st()))
        } catch (_: Exception) {
        }
        _changed.value += 1
    }

    private fun ensureDefault() {
        val s = st()
        if (s.playlists.none { it.builtin }) {
            s.playlists.add(
                Playlist(
                    id = "fav",
                    name = "我喜欢",
                    tracks = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    builtin = true
                )
            )
            save()
        }
    }

    fun all(): List<Playlist> = st().playlists.toList()

    fun byId(id: String): Playlist? = st().playlists.firstOrNull { it.id == id }

    fun create(name: String): Playlist {
        val p = Playlist(
            id = UUID.randomUUID().toString().take(8),
            name = name.take(24).ifBlank { "未命名歌单" },
            tracks = emptyList(),
            createdAt = System.currentTimeMillis()
        )
        st().playlists.add(p)
        save()
        return p
    }

    fun delete(id: String) {
        val s = st()
        s.playlists.removeAll { it.id == id && !it.builtin }
        save()
    }

    fun rename(id: String, name: String) {
        val s = st()
        val idx = s.playlists.indexOfFirst { it.id == id }
        if (idx >= 0 && name.isNotBlank()) {
            s.playlists[idx] = s.playlists[idx].copy(name = name.take(24))
            save()
        }
    }

    /**
     * 把歌加入歌单（同一分P去重：bvid+cid 相同即视为已在歌单里）。
     * @return false = 已经在歌单里，未重复添加
     */
    fun add(playlistId: String, track: Track): Boolean {
        val s = st()
        val idx = s.playlists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return true
        val p = s.playlists[idx]
        if (p.tracks.any { it.bvid == track.bvid && it.cid == track.cid }) return false
        s.playlists[idx] = p.copy(tracks = p.tracks + track)
        save()
        return true
    }

    /** 从歌单移除（按 bvid+cid 精确移除一个分P条目） */
    fun remove(playlistId: String, track: Track) {
        val s = st()
        val idx = s.playlists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return
        val p = s.playlists[idx]
        s.playlists[idx] = p.copy(tracks = p.tracks.filterNot {
            it.bvid == track.bvid && it.cid == track.cid
        })
        save()
    }

    /** 这个歌（bvid+cid）在哪些歌单里 */
    fun playlistsOf(track: Track): Set<String> =
        st().playlists.filter { p -> p.tracks.any { it.bvid == track.bvid && it.cid == track.cid } }
            .map { it.id }.toSet()

    fun count(): Int = st().playlists.size
}
