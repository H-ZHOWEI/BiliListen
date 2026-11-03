package com.bilisten.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bilisten.app.R
import com.bilisten.app.data.BiliAppDetect
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.Prefs
import com.bilisten.app.data.QrLogin
import com.bilisten.app.databinding.ActivityQrLoginBinding
import com.bilisten.app.util.QrCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 扫码快捷登录页。
 * 用本机哔哩哔哩 App 扫一下二维码并确认，即为免密登录。
 */
class QrLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrLoginBinding
    private var pollJob: Job? = null
    private var currentKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMaskRefresh.setOnClickListener { loadQr() }
        binding.btnOpenBili.setOnClickListener { openBiliScanner() }
        binding.btnAltLogin.setOnClickListener {
            startActivity(Intent(this, WebLoginActivity::class.java))
            finish()
        }

        // 没装 B 站：隐藏「打开扫一扫」，提示改用网页登录
        if (!BiliAppDetect.isInstalled(this)) {
            binding.btnOpenBili.visibility = View.GONE
        }

        loadQr()
    }

    // ---------------- 二维码 ----------------

    private fun loadQr() {
        pollJob?.cancel()
        binding.layQrMask.visibility = View.GONE
        binding.layQrLoading.visibility = View.VISIBLE
        binding.ivQr.setImageDrawable(null)
        setState(getString(R.string.qr_loading), R.color.bili_blue, true)

        lifecycleScope.launch {
            val ticket = QrLogin.generate()
            if (ticket == null) {
                binding.layQrLoading.visibility = View.GONE
                showMask(getString(R.string.qr_net_error))
                setState(getString(R.string.qr_net_error), R.color.bili_pink, false)
                return@launch
            }
            currentKey = ticket.key
            val bmp = QrCode.bitmap(ticket.url)
            binding.layQrLoading.visibility = View.GONE
            if (bmp == null) {
                showMask(getString(R.string.qr_net_error))
                setState(getString(R.string.qr_net_error), R.color.bili_pink, false)
                return@launch
            }
            binding.ivQr.setImageBitmap(bmp)
            setState(getString(R.string.qr_waiting), R.color.bili_blue, true)
            startPolling(ticket.key)
        }
    }

    private fun startPolling(key: String) {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            QrLogin.pollUntilDone(key) { result ->
                runOnUiThread { onPollResult(result) }
            }
        }
    }

    private fun onPollResult(result: QrLogin.Result) {
        when (result.state) {
            QrLogin.State.WAITING ->
                setState(getString(R.string.qr_waiting), R.color.bili_blue, true)

            QrLogin.State.SCANNED ->
                setState(getString(R.string.qr_scanned), R.color.bili_accent, true)

            QrLogin.State.SUCCESS -> {
                binding.pbState.visibility = View.GONE
                setState(getString(R.string.qr_success), R.color.bili_pink, false)
                finishLogin(result)
            }

            QrLogin.State.EXPIRED -> {
                setState(getString(R.string.qr_expired), R.color.on_bg_dim, false)
                showMask(getString(R.string.qr_expired))
            }

            QrLogin.State.ERROR -> {
                setState(getString(R.string.qr_fail), R.color.bili_pink, false)
                showMask(getString(R.string.qr_retry_hint))
            }
        }
    }

    /** 拿到 Cookie 后再跟服务器确认一次，确认有效才保存 */
    private fun finishLogin(result: QrLogin.Result) {
        Prefs.cookie = result.cookie
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
                Toast.makeText(
                    this@QrLoginActivity,
                    getString(R.string.qr_welcome, nav.uname.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Prefs.clearLogin()
                setState(getString(R.string.qr_cookie_invalid), R.color.bili_pink, false)
                showMask(getString(R.string.qr_retry_hint))
            }
        }
    }

    // ---------------- 界面辅助 ----------------

    private fun setState(text: String, colorRes: Int, spinning: Boolean) {
        binding.tvState.text = text
        binding.tvState.setTextColor(getColor(colorRes))
        binding.pbState.visibility = if (spinning) View.VISIBLE else View.GONE
    }

    private fun showMask(text: String) {
        binding.tvMaskText.text = text
        binding.layQrMask.visibility = View.VISIBLE
    }

    // ---------------- 唤起哔哩哔哩 ----------------

    private fun openBiliScanner() {
        val intent = BiliAppDetect.scanIntent(this)
        if (intent == null) {
            Toast.makeText(this, R.string.qr_no_bili_app, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.qr_no_bili_app, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }
}
