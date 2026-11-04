package com.bilisten.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.bilisten.app.R
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.FavFolder
import com.bilisten.app.data.Prefs
import com.bilisten.app.data.Track
import com.bilisten.app.data.Updater
import com.bilisten.app.databinding.ActivityMainBinding
import com.bilisten.app.databinding.DialogUpdateBinding
import com.bilisten.app.player.PlayerController
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!Prefs.isLogin) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        askNotificationPermission()

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    (currentFragment() as? Reloadable)?.reload()
                    true
                }
                R.id.action_downloads -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.container, DownloadsFragment())
                        .addToBackStack("downloads")
                        .commit()
                    true
                }
                R.id.action_update -> {
                    checkUpdate(manual = true)
                    true
                }
                R.id.action_logout -> {
                    confirmLogout()
                    true
                }
                else -> false
            }
        }

        setupMiniPlayer()

        // 启动时静默检查更新（12 小时最多一次，用户点过「跳过此版本」的不再打扰）
        checkUpdate(manual = false)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, FolderFragment())
                .commit()
        }
    }

    private fun currentFragment(): androidx.fragment.app.Fragment? {
        val primary = supportFragmentManager.findFragmentById(R.id.container)
        if (primary is Reloadable) return primary
        val backStack = supportFragmentManager.backStackEntryCount
        return if (backStack > 0) {
            val tag = supportFragmentManager.getBackStackEntryAt(backStack - 1).name
            supportFragmentManager.findFragmentByTag(tag)
        } else null
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupMiniPlayer() {
        val mini = binding.mini
        mini.root.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        mini.miniPlay.setOnClickListener { PlayerController.toggle() }
        mini.miniNext.setOnClickListener { PlayerController.next() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PlayerController.current.collect { track ->
                        if (track == null) {
                            mini.root.visibility = android.view.View.GONE
                        } else {
                            mini.root.visibility = android.view.View.VISIBLE
                            mini.miniTitle.text = track.title
                            mini.miniArtist.text = track.artist
                            if (track.cover.isNotBlank()) {
                                mini.miniCover.load(track.cover)
                            }
                        }
                    }
                }
                launch {
                    PlayerController.isPlaying.collect { playing ->
                        mini.miniPlay.setImageResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }
            }
        }
    }

    fun openFolder(folder: FavFolder) {
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.container,
                TrackFragment.newInstance(folder.id, folder.title, folder.mediaCount)
            )
            .addToBackStack("tracks")
            .commit()
    }

    fun startPlayback(tracks: List<Track>, index: Int, shuffle: Boolean) {
        PlayerController.playList(tracks, index, shuffle)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录？")
            .setMessage("退出后将无法读取收藏夹，本地不会有任何数据损失。")
            .setPositiveButton("退出") { _, _ ->
                PlayerController.stopAndClear()
                Prefs.clearLogin()
                val cm = android.webkit.CookieManager.getInstance()
                cm.removeAllCookies(null)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 供 Fragment 触发刷新 */
    interface Reloadable {
        fun reload()
    }

    // ---------------- 应用内更新 ----------------

    private var updateDialogShowing = false

    /**
     * @param manual true=用户点「检查更新」，无论有无新版本都给反馈；
     *               false=启动自动检查，静默失败、12 小时内只查一次、跳过此版本则不弹
     */
    private fun checkUpdate(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual) {
            if (now - Prefs.updateCheckedAt < 12 * 3600_000L) return
        }
        Prefs.updateCheckedAt = now
        lifecycleScope.launch {
            val info = Updater.checkUpdate(this@MainActivity)
            if (info == null) {
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        "已是最新版本 v${Updater.currentVersion(this@MainActivity)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            if (!manual && Prefs.skippedVersion == info.version) return@launch
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: Updater.Info) {
        if (updateDialogShowing || isFinishing) return
        val v = DialogUpdateBinding.inflate(layoutInflater)
        v.tvUpdateVersion.text =
            "新版本 v${info.version}（当前 v${Updater.currentVersion(this)}）"
        v.tvUpdateNotes.text = info.notes.ifBlank { "修复已知问题，优化使用体验。" }

        var downloading = false
        var failed = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setView(v.root)
            .setPositiveButton("立即更新", null)
            .setNegativeButton("以后再说", null)
            .setNeutralButton("跳过此版本", null)
            .create()

        dialog.setOnShowListener {
            val btnUpdate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val btnNeutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            btnNeutral.setOnClickListener {
                if (failed) {
                    // 失败状态下：这个按钮变成「重试」
                    failed = false
                    btnUpdate.text = "立即更新"
                    btnNeutral.text = "跳过此版本"
                    btnUpdate.performClick()
                    return@setOnClickListener
                }
                Prefs.skippedVersion = info.version
                Toast.makeText(this, "已跳过 v${info.version}，下次版本发布再提醒", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            btnUpdate.setOnClickListener {
                if (downloading) return@setOnClickListener
                if (failed) {
                    // 失败状态下：这个按钮变成「用浏览器下载」
                    openBrowser(Updater.PAGE_URL)
                    return@setOnClickListener
                }
                // Android 8+ 需要用户单独允许「安装未知应用」
                if (!Updater.canInstall(this)) {
                    Toast.makeText(
                        this,
                        "请在本页打开「允许安装未知应用」，返回后再点一次「立即更新」",
                        Toast.LENGTH_LONG
                    ).show()
                    Updater.openInstallPermission(this)
                    return@setOnClickListener
                }
                downloading = true
                failed = false
                btnUpdate.isEnabled = false
                btnNeutral.isEnabled = false
                v.updateProgress.visibility = View.VISIBLE
                v.tvUpdateStatus.visibility = View.VISIBLE
                v.tvUpdateStatus.text = "正在下载…"
                btnUpdate.text = "下载中"

                lifecycleScope.launch {
                    val progressJob = launch {
                        Updater.progress.collect { p ->
                            if (p >= 0f) {
                                v.updateProgress.progress = (p * 100).toInt()
                                v.tvUpdateStatus.text = "正在下载 ${(p * 100).toInt()}%"
                            }
                        }
                    }
                    val file = Updater.download(this@MainActivity, info)
                    progressJob.cancel()
                    if (file == null) {
                        downloading = false
                        failed = true
                        btnUpdate.isEnabled = true
                        btnNeutral.isEnabled = true
                        btnUpdate.text = "浏览器下载"
                        btnNeutral.text = "重试"
                        v.updateProgress.visibility = View.GONE
                        v.tvUpdateStatus.text = "下载失败：CDN 连接超时/被拦截，点「浏览器下载」手动安装"
                        return@launch
                    }
                    v.tvUpdateStatus.text = "下载完成，正在打开安装程序…"
                    if (!Updater.install(this@MainActivity, file)) {
                        Toast.makeText(
                            this@MainActivity,
                            "无法打开安装程序，请手动到 GitHub 下载：${Updater.PAGE_URL}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener { updateDialogShowing = false }
        updateDialogShowing = true
        dialog.show()
    }

    /** 用系统浏览器打开指定网址（下载兜底 / 手动安装用） */
    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开浏览器：$url", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isLogin) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
