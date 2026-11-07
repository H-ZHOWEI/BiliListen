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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 应用内更新：从 GitHub Releases 拿最新版本号，与本地版本比对，需要更新时下载 APK 并拉起系统安装器。
 */
object Updater {

    private const val API_LATEST = "https://api.github.com/repos/H-ZHOWEI/BiliListen/releases/latest"
    const val PAGE_URL = "https://github.com/H-ZHOWEI/BiliListen/releases/latest"

    /**
     * 版本检查的候选地址：直连优先，后面是国内可直连的代理。
     * 刚开机 / 刚点开 App 时网络往往还没就绪，直连偶发超时，多试几个地址能救回来；
     * 否则启动这一次静默失败，用户就以为「从来不自动提示」。
     */
    private val CHECK_URLS = listOf(
        API_LATEST,
        "https://ghproxy.net/$API_LATEST",
        "https://gh-proxy.com/$API_LATEST",
        "https://gh.api.99988866.xyz/$API_LATEST"
    )

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

    /**
     * 版本检查专用客户端：超时短、失败快。
     * 以前复用 [Net.http]（读超时 30s），启动瞬间网络没就绪时会一直卡着不返回，
     * 用户早就进 App 了，弹窗自然「不出现」；手动点检查时网络已就绪所以又能弹。
     */
    private val checkClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.header("User-Agent") == null) {
                    b.header("User-Agent", "BiliListen-Updater")
                }
                chain.proceed(b.build())
            }
            .build()
    }

    /**
     * 有没有新版本可用。
     * - 明确拿到「已是最新」/「有新版但没 APK」→ 返回 null，不再重试；
     * - 网络异常、被限流、响应不是合法 Release JSON → 换下一个地址重试；
     * - 全部失败 → 返回 null（自动检查静默，下次打开再试，绝不写死冷却时间）。
     *
     * @param attempts 最多尝试次数；启动自动检查给 3 次求稳，手动点「检查更新」给 1 次求快。
     */
    suspend fun checkUpdate(context: Context, attempts: Int = 3): Info? = withContext(Dispatchers.IO) {
        val current = currentVersion(context)
        val n = attempts.coerceIn(1, CHECK_URLS.size * 2)
        for (i in 0 until n) {
            try {
                return@withContext fetchRelease(CHECK_URLS[i % CHECK_URLS.size], current)
            } catch (_: Exception) {
                if (i < n - 1) delay(1000L)
            }
        }
        null
    }

    /** 请求单个地址并解析：返回 null = 确认无更新；抛异常 = 这次请求不算数，换地址重试 */
    private fun fetchRelease(url: String, current: String): Info? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BiliListen-Updater")
            .get()
            .build()
        checkClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            if (!body.trimStart().startsWith("{")) throw IllegalStateException("not json")
            val dto = gson.fromJson(body, ReleaseDto::class.java)
                ?: throw IllegalStateException("bad json")
            val latest = (dto.tagName ?: dto.name ?: "").trim().removePrefix("v").removePrefix("V")
            if (latest.isBlank()) throw IllegalStateException("no tag")
            if (compareVersion(latest, current) <= 0) return null
            val apk = dto.assets?.firstOrNull {
                it.name?.endsWith(".apk", ignoreCase = true) == true && !it.url.isNullOrBlank()
            } ?: return null
            return Info(
                version = latest,
                notes = (dto.body ?: "").trim(),
                apkUrl = apk.url!!,
                sizeBytes = apk.size,
                pageUrl = dto.htmlUrl ?: PAGE_URL
            )
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
     * 更新专用的独立 OkHttp 客户端：加长超时 + 跟随重定向。
     * 默认 [Net.http] 读超时仅 30s，8.8MB 的 APK 在弱网/跨境 CDN 上很容易超时；
     * 这里把读超时放宽到 120s，并允许连接失败自动重试。
     */
    private val updateClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.header("User-Agent") == null) {
                    b.header("User-Agent", "BiliListen-Updater")
                }
                chain.proceed(b.build())
            }
            .build()
    }

    /**
     * 生成下载地址候选列表：原始地址排在首位，后面是若干国内可直连的 GitHub 镜像加速，
     * 用于绕过 release-assets.githubusercontent.com 在部分网络下被墙/限速的问题。
     */
    private fun candidateUrls(original: String): List<String> {
        val t = original.trim()
        return listOf(
            t,
            "https://ghproxy.net/$t",
            "https://mirror.ghproxy.com/$t",
            "https://gh.api.99988866.xyz/$t"
        ).distinct()
    }

    /**
     * 下载安装包到缓存目录，返回本地文件。进度见 [progress]。
     * 已有同版本文件且大小一致时直接复用，避免重复下载。
     * 下载会依次尝试原始地址与各镜像加速，每个地址最多重试 2 次，任一成功即返回。
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

            val urls = candidateUrls(info.apkUrl)
            var lastErr: Exception? = null
            for (url in urls) {
                repeat(2) {
                    try {
                        if (doDownload(url, target, info.sizeBytes)) {
                            _progress.value = 1f
                            return@withContext target
                        }
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
            }
            lastErr?.printStackTrace()
            _progress.value = -1f
            null
        } catch (e: Exception) {
            _progress.value = -1f
            null
        }
    }

    /** 单个地址下载；成功返回 true，失败（含校验不通过）返回 false 并清理半截文件 */
    private fun doDownload(url: String, target: File, expectedSize: Long): Boolean {
        val req = okhttp3.Request.Builder().url(url).get().build()
        val resp = updateClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            try { resp.body?.close() } catch (_: Exception) { }
            return false
        }
        val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: expectedSize
        var ok = false
        try {
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
                    out.flush()
                }
            }
            ok = target.length() >= 1024L &&
                (expectedSize <= 0L || target.length() == expectedSize)
        } catch (e: Exception) {
            ok = false
        } finally {
            if (!ok) try { target.delete() } catch (_: Exception) { }
        }
        return ok
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
