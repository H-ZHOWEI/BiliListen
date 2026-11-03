package com.bilisten.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内更新：从 GitHub Releases 拿最新版本号，与本地版本比对，需要更新时下载 APK 并拉起系统安装器。
 */
object Updater {

    private const val API_LATEST = "https://api.github.com/repos/H-ZHOWEI/BiliListen/releases/latest"
    const val PAGE_URL = "https://github.com/H-ZHOWEI/BiliListen/releases/latest"

    data class Info(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val pageUrl: String
    )

    /** 下载进度 0f..1f，-1f 表示失败 */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val gson = Gson()

    private data class ReleaseDto(
        @SerializedName("tag_name") val tagName: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("body") val body: String? = null,
        @SerializedName("html_url") val htmlUrl: String? = null,
        @SerializedName("prerelease") val preRelease: Boolean = false,
        @SerializedName("assets") val assets: List<AssetDto>? = null
    )

    private data class AssetDto(
        @SerializedName("name") val name: String? = null,
        @SerializedName("browser_download_url") val url: String? = null,
        @SerializedName("size") val size: Long = 0L
    )

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** 有没有新版本可用；网络异常时返回 null（静默失败，不打扰用户） */
    suspend fun checkUpdate(context: Context): Info? = withContext(Dispatchers.IO) {
        try {
            val raw = Net.get(
                API_LATEST,
                mapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to "BiliListen-Updater"
                )
            )
            val dto = gson.fromJson(raw, ReleaseDto::class.java) ?: return@withContext null
            val latest = (dto.tagName ?: dto.name ?: "").trim().removePrefix("v").removePrefix("V")
            if (latest.isBlank()) return@withContext null
            if (compareVersion(latest, currentVersion(context)) <= 0) return@withContext null

            val apk = dto.assets?.firstOrNull {
                it.name?.endsWith(".apk", ignoreCase = true) == true && !it.url.isNullOrBlank()
            } ?: return@withContext null

            Info(
                version = latest,
                notes = (dto.body ?: "").trim(),
                apkUrl = apk.url!!,
                sizeBytes = apk.size,
                pageUrl = dto.htmlUrl ?: PAGE_URL
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 版本号比较：1.10 > 1.9，缺位按 0 处理 */
    fun compareVersion(a: String, b: String): Int {
        fun parts(s: String) = s.split('.', '-', '_')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * 下载安装包到缓存目录，返回本地文件。进度见 [progress]。
     * 已有同版本文件且大小一致时直接复用，避免重复下载。
     */
    suspend fun download(context: Context, info: Info): File? = withContext(Dispatchers.IO) {
        try {
            _progress.value = 0f
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val target = File(dir, "BiliListen-${info.version}.apk")
            if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) {
                _progress.value = 1f
                return@withContext target
            }

            val req = okhttp3.Request.Builder().url(info.apkUrl)
                .header("User-Agent", "BiliListen-Updater")
                .build()
            val resp = Net.http.newCall(req).execute()
            if (!resp.isSuccessful) {
                _progress.value = -1f
                return@withContext null
            }
            val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: info.sizeBytes
            resp.body?.byteStream()?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            if (target.length() < 1024) {
                target.delete()
                _progress.value = -1f
                return@withContext null
            }
            _progress.value = 1f
            target
        } catch (e: Exception) {
            _progress.value = -1f
            null
        }
    }

    /** 是否已授予「允许安装未知应用」权限（Android 8+） */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    /** 跳系统设置让用户打开「安装未知应用」开关 */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** 拉起系统安装器 */
    fun install(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
