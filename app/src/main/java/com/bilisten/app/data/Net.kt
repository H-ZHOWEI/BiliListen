package com.bilisten.app.data

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
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
     * B 站的音频/图片 CDN 域名一直在扩，除了 *.bilivideo.com 还有
     * mcdn.bilivideo.cn、upos-*.solseed.cn、akamaized 等，全部要当作 B 站域处理。
     */
    private val BILI_HOSTS = listOf(
        "bilibili", "hdslb", "bilivideo", "solseed", "biliimg", "akamaized", "biliapi"
    )

    fun isBiliHost(host: String): Boolean =
        BILI_HOSTS.any { host.contains(it, ignoreCase = true) }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            val builder = original.newBuilder()
            if (isBiliHost(host)) {
                builder.header("User-Agent", UA)
                builder.header("Referer", REFERER)
                builder.header("Origin", "https://www.bilibili.com")
                val cookie = Prefs.cookie
                if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
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
