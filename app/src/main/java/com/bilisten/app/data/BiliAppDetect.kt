package com.bilisten.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 检测本机是否装有哔哩哔哩客户端，并构造「唤起扫一扫」的 Intent。
 *
 * 说明：Android 沙箱不允许第三方读取别的 App 的私有数据（Cookie 存在 B 站 App 私有目录里），
 * 所以做不到「直接偷」它的登录态。这里走的是官方允许的路径：
 * 生成本机二维码 → 唤起 B 站扫一扫 → 用户在 B 站里点「确认登录」→ 我们拿到网页端 Cookie。
 * 对用户来说依然是「点一下授权、不用输密码」。
 */
object BiliAppDetect {

    /** 哔哩哔哩 / 哔哩哔哩 HD / 概念版 */
    private val PACKAGES = listOf(
        "tv.danmaku.bili",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.bili40"
    )

    /** 不同版本用过的扫一扫 deeplink，逐个试 */
    private val SCAN_SCHEMES = listOf(
        "bilibili://qrscan",
        "bilibili://qrcode",
        "bilibili://scan"
    )

    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in PACKAGES) {
            try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // 继续试下一个
            }
        }
        return null
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * 优先用 deeplink 直接打开扫一扫；不支持时退化为「打开 B 站首页」。
     * 未安装返回 null。
     */
    fun scanIntent(context: Context): Intent? {
        val pkg = installedPackage(context) ?: return null
        val pm = context.packageManager

        for (scheme in SCAN_SCHEMES) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                return intent
            }
        }

        return pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
