package com.bilisten.app.data

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * B 站网页端扫码登录。
 *
 *  1. GET  .../qrcode/generate  → 拿到二维码内容 url + qrcode_key
 *  2. 把 url 画成二维码，用哔哩哔哩 App 扫
 *  3. GET  .../qrcode/poll?qrcode_key=xxx 轮询结果
 *     86101 未扫码 / 86090 已扫码待确认 / 0 成功（返回带 Cookie 的 url）/ 86038 已失效
 */
object QrLogin {

    private const val GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
    private const val POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="

    /** 轮询间隔 */
    const val POLL_INTERVAL_MS = 2000L

    enum class State { WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }

    data class Ticket(val key: String, val url: String)

    data class Result(
        val state: State,
        /** 仅在 SUCCESS 时非空：SESSDATA=...; bili_jct=...; DedeUserID=... */
        val cookie: String = "",
        val mid: Long = 0L,
        val message: String = ""
    )

    private val gson = Gson()

    private data class GenResp(val code: Int = -1, val message: String? = null, val data: GenData? = null)
    private data class GenData(
        val url: String? = null,
        @SerializedName("qrcode_key") val key: String? = null
    )

    private data class PollResp(val code: Int = -1, val message: String? = null, val data: PollData? = null)
    private data class PollData(
        val url: String? = null,
        val code: Int = -1,
        val message: String? = null
    )

    suspend fun generate(): Ticket? = withContext(Dispatchers.IO) {
        try {
            val resp = gson.fromJson(Net.get(GENERATE), GenResp::class.java)
            val key = resp?.data?.key
            val url = resp?.data?.url
            if (resp?.code == 0 && !key.isNullOrBlank() && !url.isNullOrBlank()) {
                Ticket(key, url)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun poll(key: String): Result = withContext(Dispatchers.IO) {
        val resp = try {
            gson.fromJson(Net.get(POLL + Uri.encode(key)), PollResp::class.java)
        } catch (e: Exception) {
            return@withContext Result(State.ERROR, message = e.message.orEmpty())
        }
        if (resp == null || resp.code != 0) {
            return@withContext Result(State.ERROR, message = resp?.message.orEmpty())
        }
        val data = resp.data ?: return@withContext Result(State.ERROR, message = "空响应")
        when (data.code) {
            0 -> {
                val cookie = cookieFromUrl(data.url.orEmpty())
                if (cookie.isBlank()) {
                    Result(State.ERROR, message = "登录成功但没有解析到 Cookie")
                } else {
                    Result(State.SUCCESS, cookie = cookie, mid = uidFrom(cookie))
                }
            }
            86090 -> Result(State.SCANNED)
            86038 -> Result(State.EXPIRED)
            86101 -> Result(State.WAITING)
            else -> Result(State.ERROR, message = data.message.orEmpty())
        }
    }

    /**
     * 一直轮询到成功 / 过期 / 出错。onState 在每次状态变化时回调（主线程外）。
     */
    suspend fun pollUntilDone(key: String, onState: (Result) -> Unit) {
        var last: State? = null
        while (true) {
            val r = poll(key)
            if (r.state != last || r.state == State.SUCCESS) {
                last = r.state
                onState(r)
            }
            if (r.state == State.SUCCESS || r.state == State.EXPIRED || r.state == State.ERROR) return
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 从 crossDomain 回调地址里取出 Cookie */
    fun cookieFromUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val uri = try {
            Uri.parse(raw)
        } catch (e: Exception) {
            return ""
        }
        fun q(name: String): String = uri.getQueryParameter(name).orEmpty()

        val sess = q("SESSDATA")
        if (sess.isBlank()) return ""

        val parts = mutableListOf("SESSDATA=$sess")
        val jct = q("bili_jct")
        if (jct.isNotBlank()) parts += "bili_jct=$jct"
        val uid = q("DedeUserID")
        if (uid.isNotBlank()) parts += "DedeUserID=$uid"
        val ckMd5 = q("DedeUserID__ckMd5")
        if (ckMd5.isNotBlank()) parts += "DedeUserID__ckMd5=$ckMd5"
        return parts.joinToString("; ")
    }

    private fun uidFrom(cookie: String): Long {
        return cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("DedeUserID=") }
            ?.substringAfter("DedeUserID=")
            ?.toLongOrNull() ?: 0L
    }
}
