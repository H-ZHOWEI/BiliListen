package com.bilisten.app.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.Prefs
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放列表里存的是 biliaudio://bvid/cid 这样的虚拟地址，
 * 真正开始缓冲时才去换一个新鲜的音频 CDN 直链（B 站直链约 2 小时过期）。
 *
 * 一次播放会触发很多次 DataSource.open（拖动进度、分片缓冲），
 * 所以解析结果缓存 15 分钟，避免每片都去请求 playurl。
 */
object BiliAudioResolver : ResolvingDataSource.Resolver {

    private const val TTL_MS = 15 * 60 * 1000L

    private data class Cached(val url: String, val expireAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun invalidate(bvid: String) {
        cache.keys.filter { it.startsWith("$bvid|") }.forEach { cache.remove(it) }
    }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        val isAudio = "biliaudio".equals(uri.scheme, ignoreCase = true)
        val isVideo = "bilivideo".equals(uri.scheme, ignoreCase = true)
        if (!isAudio && !isVideo) return dataSpec

        val bvid = uri.host.orEmpty()
        val cid = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: 0L
        val quality = Prefs.quality
        val key = "$bvid|$cid|$quality${if (isVideo) "|v" else ""}"

        val cached = cache[key]
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return dataSpec.withUri(Uri.parse(cached.url))
        }

        val url = try {
            if (isVideo) BiliRepository.resolveVideoUrl(bvid, cid)
            else BiliRepository.resolveAudioUrl(bvid, cid, quality)
        } catch (e: BiliRepository.BiliPlayException) {
            throw IOException(e.message, e)
        } catch (e: Exception) {
            throw IOException("解析播放地址失败：${e.message}", e)
        }
        if (url.isBlank()) throw IOException("拿不到播放流，可能需要重新登录")

        cache[key] = Cached(url, System.currentTimeMillis() + TTL_MS)
        return dataSpec.withUri(Uri.parse(url))
    }
}
