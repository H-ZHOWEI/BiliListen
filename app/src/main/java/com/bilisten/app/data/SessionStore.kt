package com.bilisten.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 播放会话记忆：上次听到哪一首、听到第几秒、什么播放模式 —— 下次打开静默恢复，
 * 主界面底部的迷你播放条会直接出现上次那首歌，点一下就能接着听。
 *
 * 拆成两处存储是为了省 IO：
 *  - 队列（可能上百首）写独立 JSON 文件，只在「换队列 / 换分P」时写一次
 *  - 进度、当前下标、播放模式写 SharedPreferences，几十字节，几秒一次也很轻
 */
object SessionStore {

    private const val NAME = "bilisten_session"
    private const val QUEUE_FILE = "session_queue.json"

    data class Snapshot(
        val tracks: List<Track>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
        val speed: Float
    )

    private lateinit var sp: SharedPreferences
    private var queueFile: File? = null

    @Volatile
    private var queueCache: List<Track>? = null
    private var lastPosAt = 0L
    private var lastSavedPos = -1L

    /** 所有落盘操作串行化，避免在主线程写大文件 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-store").apply { isDaemon = true }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        sp = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        queueFile = File(app.filesDir, QUEUE_FILE)
    }

    /** 启动时在后台预读队列文件，避免恢复那一刻在主线程解析 JSON 卡一下 */
    fun preload() {
        io.execute { loadQueue() }
    }

    /** 没有可恢复的现场时返回 null */
    fun restore(): Snapshot? {
        val tracks = loadQueue()
        if (tracks.isEmpty()) return null
        val idx = sp.getInt("index", 0).coerceIn(0, tracks.size - 1)
        val speed = sp.getFloat("speed", 1f).let { if (it in 0.25f..4f) it else 1f }
        return Snapshot(
            tracks = tracks,
            index = idx,
            positionMs = sp.getLong("position", 0L).coerceAtLeast(0L),
            shuffle = sp.getBoolean("shuffle", false),
            repeatMode = sp.getInt("repeat", 0),
            speed = speed
        )
    }

    private fun loadQueue(): List<Track> {
        queueCache?.let { return it }
        val f = queueFile ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("tracks") ?: JSONArray()
            val out = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { toTrack(it) }?.let { out.add(it) }
            }
            out.also { queueCache = it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 队列变了（开新歌单 / 换分P）时写一次 */
    fun saveQueue(tracks: List<Track>, index: Int) {
        queueCache = tracks
        sp.edit().putInt("index", index).apply()
        io.execute {
            val f = queueFile ?: return@execute
            try {
                val arr = JSONArray()
                tracks.forEach { arr.put(toJson(it)) }
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(JSONObject().put("tracks", arr).toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun saveIndex(index: Int) {
        sp.edit().putInt("index", index).apply()
    }

    /** 进度节流保存：位置没明显变化就跳过，避免频繁写盘 */
    fun savePosition(ms: Long, force: Boolean = false) {
        val pos = ms.coerceAtLeast(0L)
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastPosAt < 3000) return
            if (kotlin.math.abs(pos - lastSavedPos) < 1000) return
            lastPosAt = now
        }
        lastSavedPos = pos
        sp.edit().putLong("position", pos).apply()
    }

    fun savePlayback(shuffle: Boolean, repeatMode: Int, speed: Float) {
        sp.edit()
            .putBoolean("shuffle", shuffle)
            .putInt("repeat", repeatMode)
            .putFloat("speed", speed)
            .apply()
    }

    fun clear() {
        queueCache = emptyList()
        lastSavedPos = -1L
        sp.edit().clear().apply()
        io.execute { queueFile?.delete() }
    }

    // ---------------- JSON 编解码（手写，缺字段也有默认值，不怕旧版本留下的文件） ----------------

    private fun toJson(t: Track): JSONObject = JSONObject().apply {
        put("bvid", t.bvid)
        put("cid", t.cid)
        put("aid", t.aid)
        put("rawTitle", t.rawTitle)
        put("title", t.title)
        put("artist", t.artist)
        put("cover", t.cover)
        put("durationMs", t.durationMs)
        put("searchKeyword", t.searchKeyword)
    }

    private fun toTrack(o: JSONObject): Track? {
        val bvid = o.optString("bvid")
        val cid = o.optLong("cid")
        if (bvid.isBlank() || cid <= 0L) return null
        val title = o.optString("title")
        return Track(
            bvid = bvid,
            cid = cid,
            aid = o.optLong("aid"),
            rawTitle = o.optString("rawTitle", title),
            title = title,
            artist = o.optString("artist"),
            cover = o.optString("cover"),
            durationMs = o.optLong("durationMs"),
            searchKeyword = o.optString("searchKeyword", title)
        )
    }
}
