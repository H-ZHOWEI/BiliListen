package com.bilisten.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.bilisten.app.databinding.ActivityMainBinding
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
                R.id.action_logout -> {
                    confirmLogout()
                    true
                }
                else -> false
            }
        }

        setupMiniPlayer()

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

    override fun onResume() {
        super.onResume()
        if (!Prefs.isLogin) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
