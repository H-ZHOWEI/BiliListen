package com.bilisten.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bilisten.app.R
import com.bilisten.app.data.BiliAppDetect
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.DemoPlaylist
import com.bilisten.app.data.Prefs
import com.bilisten.app.databinding.ActivityLoginBinding
import com.bilisten.app.player.PlayerController
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupQuickLogin()

        binding.btnLogin.setOnClickListener {
            startActivity(Intent(this, WebLoginActivity::class.java))
        }

        binding.btnCookie.setOnClickListener { showCookieDialog() }

        binding.btnDemo.setOnClickListener {
            binding.btnDemo.isEnabled = false
            binding.btnDemo.text = "准备试听…"
            lifecycleScope.launch {
                val tracks = try {
                    DemoPlaylist.load()
                } catch (e: Exception) {
                    emptyList()
                }
                binding.btnDemo.isEnabled = true
                binding.btnDemo.text = getString(com.bilisten.app.R.string.demo_listen)
                if (tracks.isEmpty()) {
                    Toast.makeText(this@LoginActivity, "演示歌单加载失败，请检查网络", Toast.LENGTH_SHORT).show()
                } else {
                    PlayerController.playList(tracks, 0, false)
                    startActivity(Intent(this@LoginActivity, PlayerActivity::class.java))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isLogin) goMain()
    }

    /**
     * 快捷登录：本机装了哔哩哔哩就直接引导扫码授权（免密码），没装则文案切换成「扫码登录」。
     */
    private fun setupQuickLogin() {
        val installed = BiliAppDetect.isInstalled(this)
        binding.tvQuickDesc.setText(
            if (installed) R.string.quick_desc_installed else R.string.quick_desc_not_installed
        )
        binding.btnQuick.setText(
            if (installed) R.string.quick_btn else R.string.quick_btn_qr
        )
        binding.btnQuick.setOnClickListener {
            startActivity(Intent(this, QrLoginActivity::class.java))
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showCookieDialog() {
        val input = EditText(this).apply {
            hint = "SESSDATA=xxx; bili_jct=xxx; DedeUserID=xxx"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(Prefs.cookie)
            setPadding(48, 32, 48, 32)
        }
        val container = FrameLayout(this).apply { addView(input) }

        AlertDialog.Builder(this)
            .setTitle("粘贴 Cookie")
            .setMessage("在电脑浏览器登录 bilibili.com，按 F12 → Application → Cookies 复制整串 Cookie 粘贴到这里。")
            .setView(container)
            .setPositiveButton("登录") { _, _ ->
                val raw = input.text.toString().trim()
                if (!raw.contains("SESSDATA=")) {
                    Toast.makeText(this, "Cookie 里没有 SESSDATA，可能复制不完整", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                verifyAndSave(raw)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun verifyAndSave(cookie: String) {
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
                Toast.makeText(this@LoginActivity, "欢迎回来，${nav.uname}", Toast.LENGTH_SHORT).show()
                goMain()
            } else {
                Prefs.clearLogin()
                Toast.makeText(this@LoginActivity, "Cookie 无效或已过期", Toast.LENGTH_LONG).show()
            }
        }
    }
}
