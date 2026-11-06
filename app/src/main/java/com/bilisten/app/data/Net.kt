package com.bilisten.app.data

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 全局网络层。B 站接口/音频/封面都要求带 Referer 与浏览器 UA，
 * 音频 CDN 还会校验 Referer + Cookie（实测不带 Referer 时 12/14 的直链直接 403），
 * 所以统一在拦截器里补头。
 */
object Net {

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    const val REFERER = "https://www.bilibili.com/"

    /**
     * 请求头里的「免 Cookie」标记：带这个头的请求不会附加登录 Cookie。
     * B 站的 html5 mp4 播放接口带着登录 Cookie 反而经常被风控（-352），
     * 匿名请求反而稳定，视频画面解析走这条路。
     */
    const val NO_COOKIE = "X-BiliListen-NoCookie"

    /**
     * B 站的音频/图片 CDN 域名一直在扩，除了 *.bilivideo.com 还有
     * mcdn.bilivideo.cn、upos-*.solseed.cn、akamaized 等，全部要当作 B 站域处理。
     */
    private val BILI_HOSTS = listOf(
        "bilibili", "hdslb", "bilivideo", "solseed", "biliimg", "akamaized", "biliapi"
    )

    fun isBiliHost(host: String): Boolean =
        BILI_HOSTS.any { host.contains(it, ignoreCase = true) }

    /**
     * buvid3 是 B 站的设备指纹：扫码登录拿到的 Cookie 只有 SESSDATA/bili_jct，
     * 缺 buvid3 的登录请求会被风控直接回 412 HTML 页（表现为"返回不是合法 JSON"）。
     * 这里保证每个 B 站请求的 Cookie 里都带 buvid3：优先官方 finger/spi 换取真实值，
     * 拿不到就本地生成（B 站接受 UUID 格式的 buvid3）。
     */
    @Volatile
    private var buvidCache: String? = null

    private fun ensureBuvid3(): String {
        buvidCache?.let { return it }
        synchronized(this) {
            buvidCache?.let { return it }
            Prefs.buvid3.takeIf { it.isNotEmpty() }?.let {
                buvidCache = it
                return it
            }
            try {
                val body = get("https://api.bilibili.com/x/frontend/finger/spi", mapOf(NO_COOKIE to "1"))
                val b3 = Regex("\"b_3\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                if (!b3.isNullOrBlank()) {
                    val b4 = Regex("\"b_4\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                    Prefs.buvid3 = b3
                    if (b4.isNotEmpty()) Prefs.buvid4 = b4
                    buvidCache = b3
                    return b3
                }
            } catch (_: Exception) {
                // spi 拉不到就本地生成，别挡住正常请求
            }
            val gen = UUID.randomUUID().toString() + "infoc"
            Prefs.buvid3 = gen
            buvidCache = gen
            return gen
        }
    }

    /** 组装请求 Cookie：登录态 + 必要的 buvid3 指纹 */
    private fun buildCookie(noCookie: Boolean): String? {
        if (noCookie) return null
        var cookie = Prefs.cookie
        if (!cookie.contains("buvid3=")) {
            val buvid = ensureBuvid3()
            cookie = if (cookie.isEmpty()) "buvid3=$buvid" else "$cookie; buvid3=$buvid"
        }
        return cookie.ifEmpty { null }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            val noCookie = original.header(NO_COOKIE) == "1"
            val builder = original.newBuilder().removeHeader(NO_COOKIE)
            if (isBiliHost(host)) {
                builder.header("User-Agent", UA)
                builder.header("Referer", REFERER)
                builder.header("Origin", "https://www.bilibili.com")
                if (!noCookie) buildCookie(false)?.let { builder.header("Cookie", it) }
            } else if (original.header("User-Agent") == null) {
                builder.header(
                    "User-Agent",
                    "BiliListen/1.0 (Android ${Build.VERSION.RELEASE})"
                )
            }
            chain.proceed(builder.build())
        }
        .build()

    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).get()
        for ((k, v) in extraHeaders) builder.header(k, v)
        http.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && body.isEmpty()) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            return body
        }
    }

    /** 提交表单（B 站写操作都是 x-www-form-urlencoded POST + csrf） */
    fun postForm(url: String, form: Map<String, String>): String {
        val bodyBuilder = okhttp3.FormBody.Builder()
        for ((k, v) in form) bodyBuilder.add(k, v)
        val req = Request.Builder().url(url).post(bodyBuilder.build()).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && body.isEmpty()) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            return body
        }
    }

    /** 探测直链是否还能用（只取 1 字节）。返回 HTTP 状态码，异常返回 -1 */
    fun probe(url: String): Int = try {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        http.newCall(req).execute().use { it.code }
    } catch (e: Exception) {
        -1
    }
}
