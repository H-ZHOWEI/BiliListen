package com.bilisten.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 歌词自动对齐（v1.18）：
 * 有些歌用的是网上找来的其它版本歌词（网易云 / LRCLIB / QQ…），
 * 它们的时间轴和 B 站这个视频对不上 —— 要么整体快、要么整体慢、
 * 甚至越到后面偏差越大（不同压制速度）。
 *
 * 原理：B 站字幕（CC 或 AI）的时间轴和视频音频**天然对齐**。
 * 把当前歌词的每一句和 B 站字幕做文本模糊匹配，匹配上的句子
 * 就是一对「歌词时间 ↔ 真实时间」的锚点；这些锚点交给
 * [LyricStore.applyAnchors] 做逐段线性插值，整条时间轴就被
 * 自动纠正到这首歌上 —— 不用用户动手，每句都贴着唱。
 *
 * 用户每次用「当前歌词应该在哪」手动纠正，也会存成手动锚点
 * （优先级高于自动的），所以听得越多、校得越多，歌词就越准。
 */
object LyricAutoAlign {

    data class Outcome(
        val anchors: List<LyricStore.Anchor>,
        val matched: Int,
        val viaSubtitle: Boolean
    )

    /** 单句最少要有 2 个有效字符才参与匹配（「啊」「嘿」这类短句误配率高） */
    private const val MIN_TEXT_LEN = 2

    /** 文本相似度达到这个值才算同一句 */
    private const val MATCH_SCORE = 0.62

    /** 只在时间窗 ±25 秒内找匹配对象，避免全文两两比较又慢又容易乱配 */
    private const val TIME_WINDOW_MS = 25_000L

    /**
     * 计算自动对齐锚点。返回 null = 没条件或没把握（宁可不对齐也不乱对齐）。
     * @param raw 当前生效的原始歌词（未经校准）
     * @param source 歌词来源标签；B站字幕本身就是对齐的，直接跳过
     */
    suspend fun compute(track: Track, raw: List<LyricLine>, source: String): Outcome? =
        withContext(Dispatchers.IO) {
            if (raw.size < 4) return@withContext null
            if (source.isBlank() || source.startsWith("B站字幕")) return@withContext null
            // 手打歌词是用户自己校过时间的，不自动动它
            val customSource = LyricStore.getCustomSource(track.bvid)
            if (source == "我的歌词" || (customSource != null && source == customSource)) {
                return@withContext null
            }

            // 1) B站字幕锚定：唯一可靠的「真实时间」来源
            try {
                val sub = BiliRepository.fetchSubtitle(
                    track.bvid, track.cid, track.aid,
                    LyricStore.getChosenSubtitle(track.bvid)
                )
                if (sub.lines.size >= 4) {
                    val pairs = matchLines(raw, sub.lines)
                    val cleaned = removeOutliers(pairs)
                    if (cleaned.size >= 3) {
                        return@withContext Outcome(
                            cleaned.map { LyricStore.Anchor(it.first, it.second) },
                            cleaned.size,
                            true
                        )
                    }
                }
            } catch (_: Exception) {
            }

            // 2) 没有字幕时的兜底：歌词时间跨度 vs 实际时长差得离谱时，
            //    按「歌词结尾 → 歌曲结尾前 2%」做整体缩放（只修越播越偏的速度差）。
            //    只在偏差 > 8% 时启用、缩放上限 ±12%，宁缺毋滥。
            if (track.durationMs > 30_000) {
                val last = raw.maxOfOrNull { it.timeMs } ?: 0L
                if (last > 20_000) {
                    val drift = (last - track.durationMs).toDouble() / track.durationMs
                    if (abs(drift) > 0.08 && abs(drift) < 0.6) {
                        val target = track.durationMs * 0.98
                        val rate = (target / last).coerceIn(0.88, 1.12)
                        // 两个锚点：第一句不动 + 末句缩放，中间线性过渡
                        val first = raw.first()
                        return@withContext Outcome(
                            listOf(
                                LyricStore.Anchor(first.timeMs, first.timeMs),
                                LyricStore.Anchor(last, (last * rate).toLong())
                            ),
                            2,
                            false
                        )
                    }
                }
            }

            null
        }

    // ---------------- 文本匹配 ----------------

    private fun normalize(s: String): String =
        s.filter { it.isLetterOrDigit() }.lowercase()

    /** 相似度：2×LCS / (lenA+lenB)，0~1 */
    internal fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) return 0.95
        val lcs = lcsLen(na, nb)
        return 2.0 * lcs / (na.length + nb.length)
    }

    private fun lcsLen(a: String, b: String): Int {
        if (a.length > 80) return lcsLen(a.take(80), b)   // 超长行截断，省时省内存
        if (b.length > 80) return lcsLen(a, b.take(80))
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1
                else maxOf(prev[j], cur[j - 1])
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** 逐句找最佳匹配（一对一），返回 (歌词原始时间, 字幕真实时间) 对 */
    private fun matchLines(
        lrc: List<LyricLine>,
        sub: List<LyricLine>
    ): List<Pair<Long, Long>> {
        val normSub = sub.map { normalize(it.text) }
        val used = BooleanArray(sub.size)
        val out = ArrayList<Pair<Long, Long>>()
        for (l in lrc) {
            val nl = normalize(l.text)
            if (nl.length < MIN_TEXT_LEN) continue
            var best = -1
            var bestScore = 0.0
            for (j in sub.indices) {
                if (used[j]) continue
                if (abs(sub[j].timeMs - l.timeMs) > TIME_WINDOW_MS) continue
                if (normSub[j].length < MIN_TEXT_LEN) continue
                val sc = similarity(nl, normSub[j])
                if (sc > bestScore) {
                    bestScore = sc
                    best = j
                }
            }
            if (best >= 0 && bestScore >= MATCH_SCORE) {
                used[best] = true
                out.add(l.timeMs to sub[best].timeMs)
            }
        }
        return out
    }

    /**
     * 离群点剔除：个别句可能匹配到相邻句（副歌歌词重复）。
     * 用 Theil–Sen 稳健拟合「偏移量随时间的变化」，偏离拟合线 > 4 秒的丢弃。
     * （普通中位数法会把「整体存在速度差」的正常端点也当离群点删掉。）
     */
    private fun removeOutliers(pairs: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (pairs.size <= 3) return pairs
        val sorted = pairs.sortedBy { it.first }
        val deltas = sorted.map { it.second - it.first }

        // Theil–Sen 斜率：抽样两两斜率取中位数（对离群点稳健）
        val slopes = ArrayList<Double>()
        val n = sorted.size
        val step = if (n > 40) n / 40 else 1
        var i = 0
        while (i < n) {
            var j = i + 1
            while (j < n) {
                if (sorted[j].first - sorted[i].first > 5000) {
                    slopes.add(
                        ((deltas[j] - deltas[i]).toDouble()) /
                            (sorted[j].first - sorted[i].first).toDouble()
                    )
                }
                j += step
            }
            i++
        }
        val slope = if (slopes.isEmpty()) 0.0 else median(slopes)
        val medianDelta = median(deltas.map { it.toDouble() })
        val midSrc = sorted[n / 2].first.toDouble()

        return sorted.filterIndexed { idx, p ->
            val expected = medianDelta + slope * (p.first - midSrc)
            abs(deltas[idx] - expected) <= 4000.0
        }
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.isEmpty()) 0.0
        else if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
