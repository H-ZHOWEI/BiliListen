package com.bilisten.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.Prefs
import com.bilisten.app.databinding.ActivityWebLoginBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 用 WebView 打开 B 站官方登录页，登录成功后自动抓 Cookie 保存。
 * 支持账号密码 / 短信 / 扫码，全部在官方页面上完成，不接触密码。
 */
class WebLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebLoginBinding
    private var verifying = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }

        val web = binding.web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = Net_UA
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                checkCookie()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }

        web.loadUrl("https://passport.bilibili.com/login")

        // 登录成功后页面可能不跳转，靠轮询兜底
        lifecycleScope.launch {
            while (isActive && !Prefs.isLogin) {
                checkCookie()
                delay(1500)
            }
        }
    }

    private fun checkCookie() {
        if (verifying || Prefs.isLogin) return
        val cookie = CookieManager.getInstance().getCookie("https://www.bilibili.com")
            ?: CookieManager.getInstance().getCookie("https://api.bilibili.com")
            ?: return
        if (!cookie.contains("SESSDATA=")) return
        verifying = true
        Prefs.cookie = cookie

        lifecycleScope.launch {
            val nav = try {
                BiliRepository.fetchNav()
            } catch (e: Exception) {
                null
            }
            if (nav != null && nav.isLogin) {
                Prefs.mid = nav.mid
                Prefs.userName = nav.uname.orEmpty()
                Prefs.face = nav.face.orEmpty()
                finish()
            } else {
                Prefs.clearLogin()
                verifying = false
            }
        }
    }

    companion object {
        private const val Net_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
