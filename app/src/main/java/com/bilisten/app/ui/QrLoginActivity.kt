package com.bilisten.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bilisten.app.R
import com.bilisten.app.data.BiliAppDetect
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.Prefs
import com.bilisten.app.data.QrLogin
import com.bilisten.app.databinding.ActivityQrLoginBinding
import com.bilisten.app.util.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫码快捷登录页。
 * 用本机哔哩哔哩 App 扫一下二维码并确认，即为免密登录。
 */
class QrLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrLoginBinding
    private var pollJob: Job? = null
    private var currentKey: String? = null
    private var qrBitmap: Bitmap? = null
    private var expired = false

    private val writePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveAndOpenBili() else {
            Toast.makeText(this, R.string.qr_save_failed, Toast.LENGTH_LONG).show()
            openBiliHome()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMaskRefresh.setOnClickListener { loadQr() }
        binding.btnOpenBili.setOnClickListener { onSaveAndOpenClick() }
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
            qrBitmap = bmp
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
                expired = true
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

    // ---------------- 保存二维码 + 打开哔哩哔哩 ----------------

    /**
     * 同一部手机上没法「用 B 站扫自己屏幕」，所以改成：
     * 把二维码存进相册 → 打开哔哩哔哩 → 扫一扫右上角「相册」选中它 → 确认登录。
     */
    private fun onSaveAndOpenClick() {
        if (qrBitmap == null) {
            Toast.makeText(this, R.string.qr_loading, Toast.LENGTH_SHORT).show()
            loadQr()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                writePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        saveAndOpenBili()
    }

    private fun saveAndOpenBili() {
        val bmp = qrBitmap ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { saveToGallery(bmp) }
            Toast.makeText(
                this@QrLoginActivity,
                if (ok) R.string.qr_saved else R.string.qr_save_failed,
                Toast.LENGTH_LONG
            ).show()
            openBiliHome()
        }
    }

    private fun saveToGallery(bmp: Bitmap): Boolean = try {
        val name = "bilisten_login_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/哔哩听")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } != null
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.insertImage(
                contentResolver, bmp, name, "哔哩听登录二维码"
            ) != null
        }
    } catch (e: Exception) {
        false
    }

    /** 打开哔哩哔哩（深链失效时退回普通启动），用户自己点「扫一扫 → 相册」 */
    private fun openBiliHome() {
        val intent = BiliAppDetect.openApp(this)
        if (intent == null) {
            Toast.makeText(this, R.string.qr_open_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.qr_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从哔哩哔哩切回来时，若二维码已过期就自动换一张，省得用户再点
        if (expired) {
            expired = false
            loadQr()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }
}
