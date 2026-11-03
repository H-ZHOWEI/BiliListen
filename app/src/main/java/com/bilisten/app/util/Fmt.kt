package com.bilisten.app.util

object Fmt {

    fun duration(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    fun count(n: Int): String = when {
        n >= 10000 -> String.format("%.1f万", n / 10000.0)
        else -> n.toString()
    }

    /** 文件大小，如 "3.4 MB" */
    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** B 站图片 URL 补全（有些字段带 // 前缀） */
    fun image(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> "https://$url"
        }
    }
}
