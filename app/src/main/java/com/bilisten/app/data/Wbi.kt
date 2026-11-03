package com.bilisten.app.data

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B 站 WBI 签名。
 * 2023 年起 playurl / player 等 Web 接口需要 wts + w_rid，否则返回 -403。
 * 算法：取 img_key + sub_key 按固定表重排得 mixin_key，
 * 参数按 key 排序拼成 query，md5(query + mixin_key) 即 w_rid。
 */
object Wbi {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    @Volatile private var imgKey = ""
    @Volatile private var subKey = ""
    @Volatile private var stamp = 0L

    fun update(wbi: WbiImg?) {
        if (wbi == null) return
        val img = keyFromUrl(wbi.imgUrl)
        val sub = keyFromUrl(wbi.subUrl)
        if (img.isNotEmpty() && sub.isNotEmpty()) {
            imgKey = img
            subKey = sub
            stamp = System.currentTimeMillis()
        }
    }

    fun hasKeys(): Boolean =
        imgKey.isNotEmpty() && subKey.isNotEmpty() &&
            System.currentTimeMillis() - stamp < 6 * 3600_000L

    fun invalidate() {
        stamp = 0L
    }

    private fun keyFromUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val name = url.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun mixinKey(): String {
        val raw = imgKey + subKey
        val sb = StringBuilder()
        for (i in MIXIN_KEY_ENC_TAB) {
            if (i < raw.length) sb.append(raw[i])
        }
        return sb.toString().take(32)
    }

    /** 返回已带 wts 与 w_rid 的 query string（不含 ?） */
    fun sign(params: Map<String, String>): String {
        val mixin = mixinKey()
        val wts = System.currentTimeMillis() / 1000
        val all = LinkedHashMap<String, String>()
        all.putAll(params)
        all["wts"] = wts.toString()

        val query = all.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v.replace(Regex("[!'()*]"), ""))}"
            }
        val wRid = md5(query + mixin)
        return "$query&w_rid=$wRid"
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        val sb = StringBuilder()
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
