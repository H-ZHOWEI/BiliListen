package com.bilisten.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 一条已下载的记录 */
data class DlEntry(
    val bvid: String,
    val cid: Long,
    val rawTitle: String,
    val title: String,
    val artist: String,
    val cover: String,
    val durationMs: Long,
    val keyword: String,
    val file: String,
    val size: Long,
    val createdAt: Long
) {
    fun toTrack(): Track = Track(
        bvid = bvid,
        cid = cid,
        rawTitle = rawTitle,
        title = title,
        artist = artist,
        cover = cover,
        durationMs = durationMs,
        searchKeyword = keyword
    )
}

sealed class DlState {
    object Queued : DlState()
    data class Running(val percent: Int, val bytes: Long) : DlState()
    object Done : DlState()
    data class Failed(val message: String) : DlState()
}

/**
 * 歌曲下载：把音频流落到 App 私有目录，播放时优先用本地文件（真正离线可用）。
 */
object Downloads {

    private const val DIR_NAME = "audio"
    private const val INDEX_NAME = "index.json"
    private const val CHANNEL_ID = "downloads"
    private const val NOTIFY_ID = 20301

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private lateinit var app: Context
    private lateinit var dir: File
    private lateinit var indexFile: File

    private val _entries = MutableStateFlow<List<DlEntry>>(emptyList())
    val entries: StateFlow<List<DlEntry>> = _entries

    private val _states = MutableStateFlow<Map<String, DlState>>(emptyMap())
    val states: StateFlow<Map<String, DlState>> = _states

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        dir = File(app.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        indexFile = File(dir, INDEX_NAME)
        _entries.value = readIndex()
        createChannel()
    }

    // ---------------- 查询 ----------------

    fun stateOf(bvid: String): DlState? = _states.value[bvid]

    fun localFile(bvid: String): File? {
        val entry = _entries.value.firstOrNull { it.bvid == bvid } ?: return null
        val f = File(entry.file)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun localUri(bvid: String): Uri? = localFile(bvid)?.let { Uri.fromFile(it) }

    fun isDownloaded(bvid: String): Boolean = localFile(bvid) != null

    fun entryOf(bvid: String): DlEntry? = _entries.value.firstOrNull { it.bvid == bvid }

    fun totalSize(): Long = _entries.value.sumOf { it.size }

    fun isRunning(bvid: String): Boolean = jobs[bvid]?.isActive == true

    // ---------------- 下载 ----------------

    fun enqueue(track: Track): Boolean {
        if (!::app.isInitialized) return false
        if (isDownloaded(track.bvid)) return false
        if (isRunning(track.bvid)) return false

        setState(track.bvid, DlState.Queued)
        jobs[track.bvid] = scope.launch {
            val tmp = File(dir, "${track.bvid}.part")
            var target: File? = null
            try {
                val cid = if (track.cid > 0L) track.cid
                else BiliRepository.fetchFirstCidBlocking(track.bvid)
                val url = BiliRepository.resolveAudioUrl(track.bvid, cid, Prefs.quality)
                setState(track.bvid, DlState.Running(0, 0))

                val request = Request.Builder().url(url).get().build()
                Net.http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("响应为空")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var written = 0L
                            var lastPercent = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                                val percent = if (total > 0) (written * 100 / total).toInt() else 0
                                if (percent != lastPercent && percent % 2 == 0) {
                                    lastPercent = percent
                                    setState(track.bvid, DlState.Running(percent, written))
                                    notifyProgress(track.title, percent)
                                }
                            }
                        }
                    }
                }

                if (!tmp.exists() || tmp.length() <= 0L) throw IOException("下载内容为空")
                val file = File(dir, "${track.bvid}_$cid.m4a")
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) throw IOException("保存文件失败")
                target = file

                addEntry(
                    DlEntry(
                        bvid = track.bvid,
                        cid = cid,
                        rawTitle = track.rawTitle,
                        title = track.title,
                        artist = track.artist,
                        cover = track.cover,
                        durationMs = track.durationMs,
                        keyword = track.searchKeyword,
                        file = file.absolutePath,
                        size = file.length(),
                        createdAt = System.currentTimeMillis()
                    )
                )
                setState(track.bvid, DlState.Done)
                notifyDone(track.title)
            } catch (e: Exception) {
                tmp.delete()
                target?.delete()
                setState(track.bvid, DlState.Failed(e.message ?: "下载失败"))
                notifyDone(track.title, failed = true)
            } finally {
                jobs.remove(track.bvid)
            }
        }
        return true
    }

    fun cancel(bvid: String) {
        jobs.remove(bvid)?.cancel()
        setState(bvid, DlState.Failed("已取消"))
        File(dir, "$bvid.part").delete()
    }

    fun remove(bvid: String) {
        jobs.remove(bvid)?.cancel()
        entryOf(bvid)?.let { File(it.file).delete() }
        _entries.value = _entries.value.filterNot { it.bvid == bvid }
        writeIndex()
        val map = _states.value.toMutableMap()
        map.remove(bvid)
        _states.value = map
        File(dir, "$bvid.part").delete()
    }

    // ---------------- 内部 ----------------

    private fun setState(bvid: String, state: DlState) {
        _states.value = _states.value.toMutableMap().apply { put(bvid, state) }
    }

    private fun addEntry(entry: DlEntry) {
        _entries.value = _entries.value.filterNot { it.bvid == entry.bvid } + entry
        writeIndex()
    }

    private fun readIndex(): List<DlEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val list: List<DlEntry>? = gson.fromJson(
                indexFile.readText(),
                object : TypeToken<List<DlEntry>>() {}.type
            )
            list.orEmpty().filter { File(it.file).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex() {
        try {
            indexFile.writeText(gson.toJson(_entries.value))
        } catch (e: Exception) {
            // 索引写失败不影响已下载文件
        }
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "歌曲下载", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notifyProgress(title: String, percent: Int) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(com.bilisten.app.R.drawable.ic_download)
                .setContentTitle("正在下载：$title")
                .setContentText("$percent%")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (e: Exception) {
            // 没有通知权限就忽略
        }
    }

    private fun notifyDone(title: String, failed: Boolean = false) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(com.bilisten.app.R.drawable.ic_download)
                .setContentTitle(if (failed) "下载失败：$title" else "下载完成：$title")
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (e: Exception) {
            // 忽略
        }
    }
}
