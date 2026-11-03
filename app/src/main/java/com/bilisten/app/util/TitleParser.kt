package com.bilisten.app.util

/**
 * 把 B 站视频标题清洗成“歌名”。
 * 例：【4K修复】周杰伦 - 晴天（官方MV）→ 周杰伦 - 晴天
 *     【无损音质】《起风了》- 买辣椒也用券 → 《起风了》- 买辣椒也用券
 */
object TitleParser {

    private val noiseWords = listOf(
        "4k", "8k", "1080p", "720p", "2160", "高清", "超清", "蓝光", "无损", "hires", "hi-res",
        "hq", "mv", "pv", "官方", "正式版", "字幕", "完整版", "修复", "重制", "重置", "合集",
        "高音质", "音频", "纯音乐", "收藏", "必听", "循环", "无损音质", "附歌词", "带歌词",
        "动态歌词", "完整歌词", "原版", "现场版", "live版", "中文字幕", "中英字幕", "测试",
        "耳机", "沉浸", "环绕", "8d", "asmr", "无损版",
        "翻唱", "cover", "弹唱", "清唱", "伴奏", "伴奏版", "inst", "ver", "版本"
    )

    private val bracketRegex = Regex("([【\\[（(])([^】\\]）)]*)([】\\]）)])")

    fun clean(raw: String): String {
        var text = raw.trim()
        // 去掉纯噪音的括号内容
        text = bracketRegex.replace(text) { m ->
            val inner = m.groupValues[2]
            val lower = inner.lowercase()
            if (lower.isBlank() || noiseWords.any { lower.contains(it) }) "" else "（$inner）"
        }
        // 翻唱类标题：'翻唱' 'cover' 这类词不是歌名的一部分，整词去掉
        text = text.replace(Regex("(?i)[\\s|/|·、\\-]*[【\\[]?(翻唱|covers?|弹唱|清唱)[版）\\]】]?:?"), " ")
        // 去掉开头的常见前缀
        text = text.replace(Regex("^[\\s\\-_|/、]+"), "")
        text = text.replace(Regex("\\s+"), " ").trim()
        // 去掉结尾的 - 或者 |
        text = text.trim('-', '|', '、', ' ')
        return text.ifBlank { raw.trim() }
    }

    /**
     * 猜测“歌名”（区别于 guessArtist）：
     * 1) 《》里的内容最可靠（翻唱标题几乎都是「翻唱《xxx》」的形态）
     * 2) “A - B” 里取不是歌手的那一侧
     * 3) 兜底用整串清洗结果
     */
    fun guessSongTitle(cleaned: String): String {
        Regex("《([^》]+)》").find(cleaned)?.groupValues?.getOrNull(1)?.let {
            if (it.isNotBlank()) return it.trim()
        }
        val parts = cleaned.split(" - ", " — ", " – ", "-")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val left = parts.first()
            val right = parts.drop(1).joinToString(" ")
            return if (left.length <= 16 && left.length < right.length) right else left.take(24)
        }
        return cleaned
    }

    /** 猜测歌手名：优先取 “A - B” 里的 A，取不到就用 UP 主 */
    fun guessArtist(cleaned: String, uploader: String): String {
        val parts = cleaned.split(" - ", " — ", " – ", "-")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val left = parts.first()
            val right = parts.drop(1).joinToString(" ")
            // 短的一侧更可能是歌手
            return if (left.length <= 16 && left.length < right.length) left else right.take(24)
        }
        return uploader.ifBlank { "哔哩哔哩" }
    }

    /** 组装几个送进歌词库的搜索词，按优先级排列 */
    fun searchKeywords(cleaned: String, uploader: String): List<String> {
        val list = ArrayList<String>()
        list.add(cleaned.replace("（", " ").replace("）", " ").replace(Regex("\\s+"), " ").trim())
        val byDash = cleaned.split(" - ", " — ", " – ").map { it.trim() }.filter { it.isNotEmpty() }
        if (byDash.size >= 2) {
            list.add("${byDash[0]} ${byDash[1]}")
            list.add("${byDash[1]} ${byDash[0]}")
        }
        val bookTitle = Regex("《([^》]+)》").find(cleaned)?.groupValues?.getOrNull(1)
        if (!bookTitle.isNullOrBlank()) {
            list.add(bookTitle)
            if (uploader.isNotBlank()) list.add("$bookTitle $uploader")
        }
        return list.distinct().filter { it.isNotBlank() }
    }
}
