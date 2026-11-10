package com.bilisten.app.ui

import android.app.Dialog
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.bilisten.app.R
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.LyricCandidate
import com.bilisten.app.data.LyricStore
import com.bilisten.app.data.LyricTarget
import com.bilisten.app.data.LrcParser
import com.bilisten.app.data.LyricsRepository
import com.bilisten.app.data.PageStore
import com.bilisten.app.data.PlaylistStore
import com.bilisten.app.data.Prefs
import com.bilisten.app.data.SessionStore
import com.bilisten.app.data.Track
import com.bilisten.app.databinding.ActivityPlayerBinding
import com.bilisten.app.player.LyricEngine
import com.bilisten.app.player.PlayerController
import com.bilisten.app.util.Fmt
import com.bilisten.app.util.TitleParser
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var lyricAdapter: LyricAdapter

    private var userSeeking = false
    private var loadedBvid = ""
    private var currentTrack: Track? = null

    private val screenHandler = Handler(Looper.getMainLooper())
    private val adjustHandler = Handler(Looper.getMainLooper())
    private var adjustDepth = 0
    private var resumeAfterAdjust = false
    private var pendingResume: Runnable? = null

    /**
     * 调整歌词时先暂停，防止边播边改时间轴导致歌词乱跳；
     * 所有调整对话框关掉后自动恢复播放，方便立刻试听有没有调准。
     * depth 计数 + 延迟恢复是为了兼容「换底稿 / 换个关键词」这种
     * 先 dismiss 旧框再开新框的嵌套场景，中间不会闪一下声音。
     */
    private fun beginLyricAdjust() {
        pendingResume?.let { adjustHandler.removeCallbacks(it) }
        pendingResume = null
        if (adjustDepth == 0 && !resumeAfterAdjust) {
            // 嵌套对话框（菜单→校准框）接续时 resumeAfterAdjust 仍为 true，
            // 此时播放器已被我们暂停，不能重新取样，否则会记成「本来就没播」
            resumeAfterAdjust = PlayerController.controller?.isPlaying == true
            if (resumeAfterAdjust) PlayerController.controller?.pause()
        }
        adjustDepth++
    }

    private fun endLyricAdjust() {
        adjustDepth = (adjustDepth - 1).coerceAtLeast(0)
        if (adjustDepth > 0) return
        val r = Runnable {
            pendingResume = null
            if (adjustDepth == 0) {
                if (resumeAfterAdjust) {
                    val c = PlayerController.controller
                    if (c != null) {
                        if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare()
                        c.play()
                    }
                }
                resumeAfterAdjust = false
            }
        }
        pendingResume = r
        adjustHandler.postDelayed(r, 250L)
    }

    /** 挂在歌词相关对话框上；这些对话框没有自定义 dismiss 回调 */
    private fun <D : Dialog> D.holdPlayback(): D {
        setOnDismissListener { endLyricAdjust() }
        beginLyricAdjust()
        return this
    }

    /** Builder 直接弹出的歌词对话框统一走这里 */
    private fun AlertDialog.Builder.showWhileAdjusting(): AlertDialog {
        val d = create()
        d.holdPlayback()
        d.show()
        return d
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Prefs.lyricOverlayOn = true
            startLyricOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        volumeControlStream = AudioManager.STREAM_MUSIC

        setupLyrics()
        setupControls()
        observe()
    }

    private fun setupLyrics() {
        lyricAdapter = LyricAdapter { timeMs ->
            PlayerController.seekTo(timeMs)
        }
        binding.rvLyrics.layoutManager = LinearLayoutManager(this)
        binding.rvLyrics.adapter = lyricAdapter
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { showMoreMenu(it) }
        binding.btnFav.setOnClickListener { toggleFav() }

        binding.btnPlay.setOnClickListener { PlayerController.toggle() }
        binding.btnPrev.setOnClickListener { PlayerController.previous() }
        binding.btnNext.setOnClickListener { PlayerController.next() }
        binding.btnMode.setOnClickListener {
            PlayerController.cyclePlayMode()
            Toast.makeText(this, playModeLabel(), Toast.LENGTH_SHORT).show()
        }
        binding.btnSpeed.setOnClickListener {
            PlayerController.cycleSpeed()
            binding.btnSpeed.text = speedLabel(PlayerController.speed.value)
        }
        binding.btnQuality.setOnClickListener { showQualityDialog() }
        binding.btnLyric.setOnClickListener { currentTrack?.let { showLyricTools(it) } }
        binding.btnSearchLyric.setOnClickListener { showLyricSearchDialog() }

        binding.seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                PlayerController.seekTo(fractionToMs(slider.value))
            }
        })
        binding.seek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.tvPosition.text = Fmt.duration(fractionToMs(value))
        }

        updateQualityLabel()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PlayerController.current.collect { onTrackChanged(it) }
                }
                launch {
                    PlayerController.isPlaying.collect { playing ->
                        binding.btnPlay.setImageResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }
                launch {
                    combine(
                        PlayerController.positionMs,
                        PlayerController.durationMs
                    ) { p, d -> p to d }.collect { (pos, dur) ->
                        updateProgress(pos, dur)
                    }
                }
                launch {
                    PlayerController.speed.collect {
                        binding.btnSpeed.text = speedLabel(it)
                    }
                }
                launch {
                    PlayerController.shuffleOn.collect { applyPlayMode() }
                }
                launch {
                    PlayerController.repeatMode.collect { applyPlayMode() }
                }
                launch {
                    PlayerController.errorText.collect { msg ->
                        if (!msg.isNullOrBlank()) {
                            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
                            PlayerController.errorText.value = null
                        }
                    }
                }
                launch {
                    PlayerController.videoMode.collect { on -> applyVideoMode(on) }
                }

                // 「我喜欢」变动（播放页内点了心、其他页面改了歌单）都同步按钮状态
                launch {
                    PlaylistStore.changed.collect { updateFavIcon(currentTrack) }
                }

                // 歌词：全部来自进程级常驻的 LyricEngine（App 退后台也持续推进）
                launch {
                    LyricEngine.lines.collect { lyricAdapter.submitList(it) }
                }
                launch {
                    LyricEngine.currentIndex.collect { idx ->
                        lyricAdapter.setCurrent(idx)
                        if (idx >= 0) {
                            val lm = binding.rvLyrics.layoutManager as LinearLayoutManager
                            val offset = binding.rvLyrics.height / 2 - 40
                            lm.scrollToPositionWithOffset(idx, offset.coerceAtLeast(0))
                        }
                    }
                }
                launch {
                    LyricEngine.currentLine.collect { line ->
                        if (binding.tvVideoCaption.visibility == View.VISIBLE) {
                            binding.tvVideoCaption.text = line
                        }
                    }
                }
                launch {
                    LyricEngine.events.collect { snap ->
                        if (snap.bvid != loadedBvid) return@collect
                        if (snap.autoAlignNote.isNotBlank()) {
                            Toast.makeText(
                                this@PlayerActivity,
                                snap.autoAlignNote,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (snap.empty) {
                            binding.lyricEmpty.visibility = View.VISIBLE
                            binding.tvLyricTip.text =
                                if (Prefs.lyricBiliOnly) "这个视频没有 B 站字幕" else "没找到匹配的歌词，已为你显示视频画面"
                            binding.btnSearchLyric.visibility = View.VISIBLE
                            binding.btnLyric.text = "歌词"
                            // 宁可没有歌词也不显示错的：直接放视频画面
                            PlayerController.setVideoMode(true)
                        } else {
                            binding.lyricEmpty.visibility = View.GONE
                            PlayerController.setVideoMode(false)
                            binding.btnLyric.text = when {
                                snap.source.startsWith("B站") -> "歌词·B站字幕"
                                snap.source.startsWith("LRCLIB") -> "歌词·LRCLIB"
                                snap.source == "我的歌词" -> "歌词·我的"
                                else -> "歌词"
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 歌词悬浮窗 ----------------

    private fun hasOverlayPerm(): Boolean = Settings.canDrawOverlays(this)

    private fun startLyricOverlay() {
        if (!hasOverlayPerm()) return
        try {
            startService(Intent(this, LyricOverlayService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLyricOverlay() {
        stopService(Intent(this, LyricOverlayService::class.java))
    }

    private fun toggleLyricOverlay() {
        if (Prefs.lyricOverlayOn) {
            Prefs.lyricOverlayOn = false
            stopLyricOverlay()
            Toast.makeText(this, "已关闭歌词悬浮窗", Toast.LENGTH_SHORT).show()
        } else {
            if (hasOverlayPerm()) {
                Prefs.lyricOverlayOn = true
                startLyricOverlay()
            } else {
                Toast.makeText(this, "请允许「显示在其他应用上层」后再次开启", Toast.LENGTH_LONG).show()
                overlayPermLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    /** 回到播放页时，若之前开着悬浮窗且权限仍在，则重新挂上 */
    private fun syncOverlay() {
        if (Prefs.lyricOverlayOn && hasOverlayPerm()) startLyricOverlay()
    }

    // ---------------- 屏幕常亮 ----------------

    /**
     * 歌曲详情页（歌词页）屏幕常亮：
     * 0=一直亮（默认） 1=5分钟 2=10分钟 3=30分钟 4=跟随系统。
     * FLAG_KEEP_SCREEN_ON 只在本页面窗口可见时生效，离开页面自动恢复系统息屏。
     */
    private fun applyScreenKeepOn() {
        screenHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (Prefs.screenOn) {
            0 -> window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            1, 2, 3 -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val minutes = when (Prefs.screenOn) {
                    1 -> 5L
                    2 -> 10L
                    else -> 30L
                }
                screenHandler.postDelayed({
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }, minutes * 60_000L)
            }
            // 4 = 跟随系统，不加 flag
        }
    }

    private fun showScreenOnDialog() {
        val labels = arrayOf("一直常亮（默认）", "5 分钟后息屏", "10 分钟后息屏", "30 分钟后息屏", "跟随系统")
        AlertDialog.Builder(this)
            .setTitle("歌曲详情页屏幕常亮")
            .setSingleChoiceItems(labels, Prefs.screenOn) { dialog, which ->
                Prefs.screenOn = which
                applyScreenKeepOn()
                dialog.dismiss()
                Toast.makeText(this, "已设置：${labels[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onTrackChanged(track: Track?) {
        if (track == null) return
        currentTrack = track
        binding.tvTitle.text = track.title
        binding.tvArtist.text = track.artist
        if (track.cover.isNotBlank()) binding.ivCover.load(track.cover)

        val c = PlayerController.controller
        val index = (c?.currentMediaItemIndex ?: 0) + 1
        val total = c?.mediaItemCount ?: 1
        binding.tvHeader.text = if (total > 1) "$index / $total" else "正在播放"

        if (track.bvid != loadedBvid) {
            loadedBvid = track.bvid
            binding.tvDuration.text = Fmt.duration(track.durationMs)
            loadLyrics(track, null)
        }
        updateFavIcon(track)
    }

    // ---------------- 「我喜欢」一键收藏 ----------------

    /** 心形按钮状态：已喜欢=实心粉红，未喜欢=描边 */
    private fun updateFavIcon(track: Track?) {
        if (track == null) {
            binding.btnFav.setImageResource(R.drawable.ic_fav_off)
            binding.btnFav.setColorFilter(getColor(R.color.on_bg_dim))
            return
        }
        if (PlaylistStore.isFav(track.bvid)) {
            binding.btnFav.setImageResource(R.drawable.ic_fav_on)
            binding.btnFav.setColorFilter(getColor(R.color.bili_pink))
        } else {
            binding.btnFav.setImageResource(R.drawable.ic_fav_off)
            binding.btnFav.setColorFilter(getColor(R.color.on_bg))
        }
    }

    private fun toggleFav() {
        val track = currentTrack
        if (track == null) {
            Toast.makeText(this, "先播放一首歌再收藏", Toast.LENGTH_SHORT).show()
            return
        }
        val added = PlaylistStore.toggleFav(track)
        Toast.makeText(
            this,
            if (added) "❤ 已加入「我喜欢」" else "已从「我喜欢」移除",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 根据当前 shuffle / repeat 状态刷新「播放模式」按钮的图标与颜色 */
    private fun applyPlayMode() {
        val shuffle = PlayerController.shuffleOn.value
        val repeat = PlayerController.repeatMode.value
        val (icon, color, desc) = when {
            shuffle -> Triple(R.drawable.ic_shuffle, R.color.bili_pink, "随机播放")
            repeat == androidx.media3.common.Player.REPEAT_MODE_ONE ->
                Triple(R.drawable.ic_repeat_one, R.color.bili_pink, "单曲循环")
            else -> Triple(R.drawable.ic_play_order, R.color.on_bg_dim, "顺序播放")
        }
        binding.btnMode.setImageResource(icon)
        binding.btnMode.setColorFilter(getColor(color))
        binding.btnMode.contentDescription = desc
    }

    private fun playModeLabel(): String {
        return when {
            PlayerController.shuffleOn.value -> "随机播放"
            PlayerController.repeatMode.value == androidx.media3.common.Player.REPEAT_MODE_ONE -> "单曲循环"
            else -> "顺序播放"
        }
    }

    private fun updateProgress(pos: Long, dur: Long) {
        val total = if (dur > 0) dur else (currentTrack?.durationMs ?: 0L)
        if (!userSeeking && total > 0) {
            val fraction = (pos.toFloat() / total * 1000f).coerceIn(0f, 1000f)
            binding.seek.value = fraction
            binding.tvPosition.text = Fmt.duration(pos)
            binding.tvDuration.text = Fmt.duration(total)
        }
    }

    /** 视频画面模式：封面换成播放画面，底部一行字幕 */
    private fun applyVideoMode(on: Boolean) {
        if (on) {
            binding.ivCover.visibility = View.GONE
            binding.surfaceVideo.visibility = View.VISIBLE
            binding.tvVideoCaption.visibility =
                if (LyricEngine.lines.value.isEmpty()) View.GONE else View.VISIBLE
            try {
                PlayerController.controller?.setVideoSurfaceView(binding.surfaceVideo)
            } catch (e: Exception) {
                Toast.makeText(this, "当前设备不支持视频画面：${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                PlayerController.controller?.setVideoSurfaceView(null)
            } catch (_: Exception) {
            }
            binding.surfaceVideo.visibility = View.GONE
            binding.tvVideoCaption.visibility = View.GONE
            binding.ivCover.visibility = View.VISIBLE
        }
    }

    private fun loadLyrics(track: Track, keyword: String?) {
        binding.lyricEmpty.visibility = View.VISIBLE
        binding.tvLyricTip.text = "正在找歌词…"
        binding.btnSearchLyric.visibility = View.GONE
        binding.btnLyric.text = "歌词"
        LyricEngine.load(track, keyword)
    }

    /**
     * 「换个关键词找歌词」：以前只是偷偷重载一次歌词，网络没命中就什么反馈都没有，
     * 用户以为点了没反应。现在输完关键词会把各来源的版本列出来让用户挑，
     * 也保留「直接自动匹配」这个原来的行为。
     */
    private fun showLyricSearchDialog() {
        val track = currentTrack ?: return
        val input = EditText(this).apply {
            setText(track.searchKeyword)
            hint = "歌名 歌手"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(56, 36, 56, 36)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply { addView(input) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("换个关键词找歌词")
            .setView(container)
            .setPositiveButton("列出版本") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) showLyricVersionDialog(track, kw)
                else Toast.makeText(this, "关键词为空", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("直接自动匹配") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) loadLyrics(track, kw)
                else Toast.makeText(this, "关键词为空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.holdPlayback()
        dialog.show()
    }

    private fun showQualityDialog() {
        val labels = arrayOf("自动（最高音质）", "64K（省流量）", "132K", "192K（推荐）")
        AlertDialog.Builder(this)
            .setTitle("选择音质")
            .setSingleChoiceItems(labels, Prefs.quality) { dialog, which ->
                Prefs.quality = which
                updateQualityLabel()
                Toast.makeText(this, "音质已切换，下一首生效", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateQualityLabel() {
        binding.btnQuality.text = when (Prefs.quality) {
            1 -> "音质 64K"
            2 -> "音质 132K"
            3 -> "音质 192K"
            else -> "音质 自动"
        }
    }

    private data class MenuAction(val title: String, val hint: String = "", val run: () -> Unit)
    private data class MenuSection(val title: String, val actions: List<MenuAction>)

    /** 分类菜单：分组标题 + 每行右侧状态说明，替代原来一长条平铺的 ⋮ 菜单 */
    private fun showGroupedMenu(title: String, sections: List<MenuSection>): AlertDialog {
        lateinit var dialog: AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 8)
        }
        sections.forEach { sec ->
            box.addView(TextView(this).apply {
                text = sec.title
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.bili_pink))
                setPadding(12, 26, 12, 8)
            })
            sec.actions.forEach { act ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 22, 24, 22)
                    isClickable = true
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.argb(40, 251, 114, 153)
                        ), null, null
                    )
                    setOnClickListener { dialog.dismiss(); act.run() }
                }
                row.addView(TextView(this).apply {
                    text = act.title
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (act.hint.isNotEmpty()) {
                    row.addView(TextView(this).apply {
                        text = act.hint
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg_dim))
                        gravity = Gravity.END
                    })
                }
                box.addView(row)
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        return dialog
    }

    /** 右上角 ⋮：只放非歌词的设置，按类别分组 */
    private fun showMoreMenu(anchor: View) {
        val track = currentTrack ?: return
        val downloaded = Downloads.isDownloaded(track.bvid)
        val running = Downloads.isRunning(track.bvid)
        val videoOn = PlayerController.videoMode.value || Prefs.videoBvids.contains(track.bvid)

        showGroupedMenu(
            "更多设置",
            listOf(
                MenuSection(
                    "歌词与字幕",
                    listOf(
                        MenuAction(
                            "歌词工具箱", "版本 / 校准 / 手打 / 悬浮"
                        ) { showLyricTools(track) }
                    )
                ),
                MenuSection(
                    "画面与显示",
                    listOf(
                        MenuAction(
                            "显示视频画面（记住这首）",
                            if (videoOn) "已开启" else "默认关闭"
                        ) { handleMenuAction(5, track) },
                        MenuAction("屏幕常亮设置", when (Prefs.screenOn) {
                            1 -> "5 分钟"; 2 -> "10 分钟"; 3 -> "30 分钟"; 4 -> "跟随系统"
                            else -> "常亮"
                        }) { handleMenuAction(15, track) }
                    )
                ),
                MenuSection(
                    "音乐与文件",
                    listOf(
                        MenuAction(
                            when {
                                running -> "取消下载"
                                downloaded -> "删除下载"
                                else -> "下载这首（离线听）"
                            },
                            if (downloaded) "已下载" else ""
                        ) { handleMenuAction(3, track) },
                        MenuAction("加入我的歌单", "仅本地") { handleMenuAction(14, track) },
                        MenuAction("选择分P（只保留某个分P）") { handleMenuAction(13, track) },
                        MenuAction("在哔哩哔哩打开视频") { handleMenuAction(1, track) }
                    )
                ),
                MenuSection(
                    "播放",
                    listOf(
                        MenuAction(
                            "启动时自动续播上次的歌",
                            if (Prefs.resumeAutoPlay) "已开启" else "已关闭"
                        ) { handleMenuAction(17, track) }
                    )
                )
            )
        )
    }

    /** 播放页底部「歌词」按钮：所有歌词相关功能集中在这里，不再挤在 ⋮ */
    private fun showLyricTools(track: Track) {
        val chosen = LyricStore.getChosenLabel(track.bvid)
        val hasCustom = !LyricStore.getCustomLrc(track.bvid).isNullOrBlank()
        val calibHint = buildString {
            if (LyricStore.hasCalibration(track.bvid, LyricTarget.PHONE)) append("手机已调 ")
            if (LyricStore.hasCalibration(track.bvid, LyricTarget.CAR)) append("车机已调(全局)")
            val n = LyricStore.mergedAnchors(track.bvid).size
            if (n > 0) append(" · 对齐锚点${n}处")
        }.trim().ifBlank { "手机按歌调 · 车机全局调" }

        showGroupedMenu(
            "歌词工具箱",
            listOf(
                MenuSection(
                    "歌词来源",
                    listOf(
                        MenuAction("选择歌词版本（记住这首）", chosen ?: "自动匹配") {
                            showLyricVersionDialog(track)
                        },
                        MenuAction("换个关键词找歌词（可选版本）") { showLyricSearchDialog() },
                        MenuAction("重新加载歌词", LyricEngine.source.value) {
                            handleMenuAction(2, track)
                        },
                        MenuAction(
                            "歌词只使用 B 站字幕",
                            if (Prefs.lyricBiliOnly) "已开启" else "已关闭"
                        ) { handleMenuAction(4, track) },
                        MenuAction("字幕 / 语音转文字") { handleMenuAction(11, track) }
                    )
                ),
                MenuSection(
                    "歌词校准（调整时自动暂停，关掉后继续播放试听）",
                    listOf(
                        MenuAction("当前歌词应该在哪？", "选此刻真正在唱的那一句") {
                            showAlignLyricDialog(track)
                        },
                        MenuAction(
                            "自动对齐（用B站字幕校准其它版本）",
                            if (Prefs.lyricAutoAlign) {
                                val n = LyricStore.getAutoAnchors(track.bvid).size
                                if (n > 0) "已开启 · 匹配$n 处" else "已开启"
                            } else "已关闭"
                        ) { handleMenuAction(18, track) },
                        MenuAction("从这里开始校准", calibHint) { showCalibrateDialog(track) },
                        MenuAction("整体微调（偏移 + 速度）", "精确到 0.01 秒") {
                            showFineTuneDialog(track)
                        },
                        MenuAction("重置全部歌词校准") { handleMenuAction(9, track) }
                    )
                ),
                MenuSection(
                    "手打歌词",
                    listOf(
                        MenuAction("手打歌词（可先选版本当底稿）") { showManualLyricDialog(track) },
                        MenuAction(
                            "清除我的歌词",
                            if (hasCustom) "当前在用" else "无"
                        ) {
                            if (hasCustom) handleMenuAction(8, track)
                            else Toast.makeText(this, "这首歌没有手打歌词", Toast.LENGTH_SHORT).show()
                        }
                    )
                ),
                MenuSection(
                    "悬浮歌词",
                    listOf(
                        MenuAction(
                            "歌词悬浮窗（置顶显示）",
                            if (Prefs.lyricOverlayOn) "已开启" else "已关闭"
                        ) { handleMenuAction(10, track) },
                        MenuAction(
                            "悬浮歌词双行（当前句+下一句）",
                            if (Prefs.lyricOverlayDual) "双行" else "单行"
                        ) { handleMenuAction(16, track) }
                    )
                )
            )
        ).holdPlayback()
    }

    /** 改动歌词「源」后必须清缓存再重载，否则会读回内存里的旧歌词 */
    private fun reloadLyrics(track: Track, keyword: String? = null) {
        LyricsRepository.invalidate(track.bvid)
        loadLyrics(track, keyword)
    }

    /** 从多个来源里挑一个歌词版本，按歌记住，下次播放仍用这一版 */
    private fun showLyricVersionDialog(track: Track, queryOverride: String? = null) {
        var cancelled = false
        val loading = AlertDialog.Builder(this)
            .setTitle("正在找可用的歌词版本")
            .setMessage("正在 B站字幕 / 网易云 / QQ音乐 / 酷狗 / LRCLIB 里翻…（最多约 10 秒）")
            .setCancelable(true)
            .setNegativeButton("取消") { _, _ -> cancelled = true }
            .create()
        loading.setOnCancelListener { cancelled = true }
        loading.holdPlayback()
        loading.show()

        lifecycleScope.launch {
            val cands = try {
                LyricsRepository.searchCandidates(track, queryOverride)
            } catch (e: Exception) {
                emptyList()
            }
            if (isFinishing || isDestroyed) return@launch
            loading.dismiss()
            if (cancelled) return@launch

            val chosen = LyricStore.getChosenLabel(track.bvid)
            val labels = ArrayList<String>()
            val actions = ArrayList<() -> Unit>()
            labels.add("自动匹配（默认，恢复自动选择）")
            actions.add {
                LyricStore.clearChosenVersion(track.bvid)
                reloadLyrics(track)
                Toast.makeText(this@PlayerActivity, "已恢复自动匹配歌词", Toast.LENGTH_SHORT).show()
            }

            // 不依赖网络的保底：当前正在显示的歌词永远能直接沿用。
            // 以前几个歌词源全被网络挡住时，列表只剩「自动匹配」一项，
            // 看起来就像「点了没有可以选的内容」。
            val currentLines = LyricEngine.lines.value
            if (currentLines.isNotEmpty()) {
                labels.add("当前显示的歌词（${currentLines.size} 行 · 直接记住这版）")
                actions.add {
                    LyricStore.setChosenVersion(
                        track.bvid, "当前歌词", LrcParser.toText(currentLines)
                    )
                    reloadLyrics(track)
                    Toast.makeText(
                        this@PlayerActivity,
                        if (hasCustomLrc(track)) "已记住；但手打歌词优先级更高，需先清除它"
                        else "已记住当前这份歌词",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            cands.forEach { c ->
                val sim = (c.similarity * 100).roundToInt()
                val artist = c.artist.takeIf { it.isNotBlank() } ?: "未知艺人"
                val dur = if (c.durationMs > 0) Fmt.duration(c.durationMs) else "--:--"
                labels.add("${c.title} — $artist · $dur · ${c.source} · $sim%")
                actions.add {
                    lifecycleScope.launch {
                        val lrc = try {
                            LyricsRepository.fetchCandidateLrc(c)
                        } catch (e: Exception) {
                            ""
                        }
                        if (lrc.isBlank()) {
                            Toast.makeText(this@PlayerActivity, "这版没有可用歌词，换一个试试", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        if (LrcParser.parse(lrc).isEmpty()) {
                            Toast.makeText(
                                this@PlayerActivity,
                                "这版歌词没有时间轴，无法显示（换一个带时间戳的版本）",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val label = "${c.source} · ${c.title}"
                        LyricStore.setChosenVersion(track.bvid, label, lrc)
                        reloadLyrics(track)
                        Toast.makeText(
                            this@PlayerActivity,
                            "已使用「$label」，以后听到这首都用这版",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            if (cands.isEmpty()) {
                Toast.makeText(
                    this@PlayerActivity,
                    "网络上暂时没搜到其它版本（可能被网络挡住），仍可选「当前显示的歌词」或换个关键词",
                    Toast.LENGTH_LONG
                ).show()
            }

            // 不加 setMessage：说明文字会把单选列表挤到看不见，标题里已带当前状态
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("选择歌词版本（当前：${chosen ?: "自动匹配"}）")
                .setSingleChoiceItems(labels.toTypedArray(), -1) { d, which ->
                    d.dismiss()
                    actions.getOrNull(which)?.invoke()
                }
                .setNeutralButton("换个关键词") { _, _ ->
                    val input = EditText(this@PlayerActivity).apply {
                        hint = "原唱歌名 / 歌手名"
                        setText(TitleParser.guessSongTitle(track.rawTitle).ifBlank { track.searchKeyword })
                        setSelection(text.length)
                        setPadding(56, 36, 56, 36)
                    }
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("换个关键词搜版本")
                        .setView(FrameLayout(this@PlayerActivity).apply { addView(input) })
                        .setPositiveButton("搜索") { _, _ ->
                            val kw = input.text.toString().trim()
                            if (kw.isNotEmpty()) showLyricVersionDialog(track, kw)
                        }
                        .setNegativeButton("取消", null)
                        .showWhileAdjusting()
                }
                .setNegativeButton("取消", null)
                .showWhileAdjusting()
        }
    }

    private fun hasCustomLrc(track: Track): Boolean =
        !LyricStore.getCustomLrc(track.bvid).isNullOrBlank()

    private fun handleMenuAction(id: Int, track: Track): Boolean {
        return when (id) {
                1 -> {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.bilibili.com/video/${track.bvid}")
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                2 -> {
                    reloadLyrics(track)
                    true
                }
                3 -> {
                    when {
                        Downloads.isRunning(track.bvid) -> {
                            Downloads.cancel(track.bvid)
                            Toast.makeText(this, "已取消下载", Toast.LENGTH_SHORT).show()
                        }
                        Downloads.isDownloaded(track.bvid) -> {
                            AlertDialog.Builder(this)
                                .setTitle("删除下载？")
                                .setMessage("将从本机删除《${track.title}》的音频文件。")
                                .setPositiveButton("删除") { _, _ ->
                                    Downloads.remove(track.bvid)
                                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        else -> {
                            Downloads.enqueue(track)
                            Toast.makeText(this, "开始下载，可在「已下载」里查看", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                4 -> {
                    Prefs.lyricBiliOnly = !Prefs.lyricBiliOnly
                    Toast.makeText(
                        this,
                        if (Prefs.lyricBiliOnly) "歌词只用 B 站字幕" else "已允许网络歌词兜底",
                        Toast.LENGTH_SHORT
                    ).show()
                    reloadLyrics(track)
                    true
                }
                5 -> {
                    PlayerController.setVideoMode(!PlayerController.videoMode.value)
                    Toast.makeText(
                        this,
                        if (PlayerController.videoMode.value)
                            "视频画面已开启，以后听到这首会自动显示画面"
                        else "已关闭视频画面（这首的记住也一并取消）",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                6 -> {
                    showCalibrateDialog(track)
                    true
                }
                7 -> {
                    showManualLyricDialog(track)
                    true
                }
                8 -> {
                    LyricStore.clearCustomLrc(track.bvid)
                    reloadLyrics(track)
                    Toast.makeText(this, "已清除我的歌词", Toast.LENGTH_SHORT).show()
                    true
                }
                9 -> {
                    LyricStore.clearAllCalibration(track.bvid)
                    LyricEngine.recalibrate(track.bvid)
                    Toast.makeText(
                        this,
                        "已重置：本歌的手机校准 + 车机全局校准（车机对所有歌生效）",
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
                10 -> {
                    toggleLyricOverlay()
                    true
                }
                11 -> {
                    showSubtitlePicker(track)
                    true
                }
                12 -> {
                    showFineTuneDialog(track)
                    true
                }
                13 -> {
                    showPagePicker(track)
                    true
                }
                14 -> {
                    showPlaylistPicker(track)
                    true
                }
                15 -> {
                    showScreenOnDialog()
                    true
                }
                16 -> {
                    Prefs.lyricOverlayDual = !Prefs.lyricOverlayDual
                    Toast.makeText(
                        this,
                        if (Prefs.lyricOverlayDual) "悬浮歌词：双行显示" else "悬浮歌词：单行显示",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                17 -> {
                    Prefs.resumeAutoPlay = !Prefs.resumeAutoPlay
                    Toast.makeText(
                        this,
                        if (Prefs.resumeAutoPlay) "下次打开 App 会自动接着播上次那首"
                        else "下次打开只恢复进度，点播放才出声",
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
                18 -> {
                    Prefs.lyricAutoAlign = !Prefs.lyricAutoAlign
                    if (Prefs.lyricAutoAlign) {
                        LyricsRepository.invalidate(track.bvid)
                        LyricEngine.load(track, null)
                        Toast.makeText(
                            this,
                            "已开启自动对齐：正在用 B 站字幕校准这首歌的歌词",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        // 只停用自动锚点；用户手动校的锚点保留
                        LyricEngine.recalibrate(track.bvid)
                        Toast.makeText(
                            this,
                            "已关闭自动对齐（你手动校准的点保留生效）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    true
                }
                else -> false
            }
    }

    override fun onResume() {
        super.onResume()
        syncOverlay()
        applyScreenKeepOn()
    }

    override fun onPause() {
        super.onPause()
        screenHandler.removeCallbacksAndMessages(null)
        // 立刻把进度落盘，防止用户从最近任务划掉后回不到原处
        SessionStore.savePosition(PlayerController.realPosition(), force = true)
    }

    override fun onDestroy() {
        // 离开播放页时把画面卸掉，避免播放器持有已销毁的 Surface
        try {
            PlayerController.controller?.setVideoSurfaceView(null)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** 两个校准目标共用的选择器：手机按歌保存，车机全局（所有歌共用） */
    private fun targetRadio(): RadioGroup {
        val phone = RadioButton(this).apply {
            text = "手机显示（只这首）"
            id = View.generateViewId()
            isChecked = true
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg))
        }
        val car = RadioButton(this).apply {
            text = "车机显示（所有歌）"
            id = View.generateViewId()
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg))
        }
        return RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(phone)
            addView(car)
            setPadding(0, 8, 0, 8)
        }
    }

    private fun RadioGroup.selectedTarget(): LyricTarget =
        if (indexOfChild(findViewById<RadioButton>(checkedRadioButtonId)) == 1) LyricTarget.CAR
        else LyricTarget.PHONE

    /** 小按钮：校准对话框里反复点也不会关掉对话框，方便一点点修准 */
    private fun tuneButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(6, 20, 6, 20)
        setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg))
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.argb(40, 251, 114, 153)
            ), null, null
        )
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun tuneRow(vararg buttons: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it) }
    }

    /**
     * 「当前歌词应该在哪？」：暂停后把整首歌词列出来，用户点此刻真正在唱的那一句，
     * 直接把该句校准到当前播放位置，不用自己试「提前 / 延后多少毫秒」。
     * 例：屏幕显示「一只没有眼睛」，实际唱的是第一个「两只老虎」→ 选第一个「两只老虎」。
     */
    private fun showAlignLyricDialog(track: Track, target: LyricTarget = LyricTarget.PHONE) {
        val pos = PlayerController.positionMs.value
        val lines = LyricEngine.linesFor(target)
        if (lines.isEmpty()) {
            Toast.makeText(this, "这首歌还没有可用歌词，先加载歌词再校准", Toast.LENGTH_LONG).show()
            return
        }
        val nowIdx = LyricEngine.indexFor(target, pos)
        val who = if (target == LyricTarget.CAR) "车机 · 所有歌生效" else "手机 · 只这首"
        val labels = lines.mapIndexed { i, l ->
            val mark = if (i == nowIdx) "▶ 现在显示 · " else "${i + 1}. "
            "$mark${Fmt.duration(l.timeMs)}  ${l.text}"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("当前应该唱到哪一句？\n已暂停在 ${Fmt.duration(pos)} · $who · 选完自动继续播放")
            .setSingleChoiceItems(labels, nowIdx) { d, which ->
                val chosen = lines[which]
                val delta = pos - chosen.timeMs
                if (kotlin.math.abs(delta) < 20L) {
                    Toast.makeText(this, "这句已经对齐当前时间，无需调整", Toast.LENGTH_SHORT).show()
                } else if (target == LyricTarget.CAR) {
                    LyricStore.shiftCalibration(track.bvid, delta, target)
                    LyricEngine.recalibrate(track.bvid)
                    // 分段校准的生效边界会跟着时间轴移动，可能留一点残差，再补一次
                    val after = LyricEngine.linesFor(target)
                    if (which < after.size && after[which].text == chosen.text) {
                        val residual = pos - after[which].timeMs
                        if (kotlin.math.abs(residual) > 30L) {
                            LyricStore.shiftCalibration(track.bvid, residual, target)
                            LyricEngine.recalibrate(track.bvid)
                        }
                    }
                    Toast.makeText(
                        this,
                        "已把「${chosen.text.take(14)}」对齐到 ${Fmt.duration(pos)}，" +
                            "$who：整体${if (delta > 0) "延后" else "提前"} " +
                            "${String.format("%.2f", kotlin.math.abs(delta) / 1000.0)} 秒，继续播放试试",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // 手机：写入时间锚点（src=原始歌词时间，dst=实际唱到的时间）。
                    // 每次校准都永久记住，多个锚点之间逐段线性插值 —— 校得越多越准。
                    val rawIdx = LyricEngine.rawIndexOf(target, which)
                    val srcRaw = LyricEngine.rawTimeAt(rawIdx)
                    if (srcRaw < 0) {
                        LyricStore.shiftCalibration(track.bvid, delta, target)
                        LyricEngine.recalibrate(track.bvid)
                    } else {
                        LyricStore.addManualAnchor(track.bvid, srcRaw, pos)
                        LyricEngine.recalibrate(track.bvid)
                    }
                    val total = LyricStore.mergedAnchors(track.bvid).size
                    Toast.makeText(
                        this,
                        "已把「${chosen.text.take(14)}」对齐到 ${Fmt.duration(pos)}，" +
                            "校准点已记住（共 $total 处，校得越多越准），继续播放试试",
                        Toast.LENGTH_LONG
                    ).show()
                }
                d.dismiss()
            }
            .setNeutralButton(if (target == LyricTarget.PHONE) "改调车机" else "改调手机") { _, _ ->
                showAlignLyricDialog(
                    track,
                    if (target == LyricTarget.PHONE) LyricTarget.CAR else LyricTarget.PHONE
                )
            }
            .setNegativeButton("取消（继续播放）", null)
            .create()
        dialog.setOnShowListener {
            // 自动滚到现在显示的句子附近，往回找前几句很省力
            dialog.listView?.setSelection((nowIdx - 3).coerceAtLeast(0))
        }
        dialog.holdPlayback()
        dialog.show()
    }

    /**
     * 从当前播放位置开始校准：手机按歌保存、车机全局保存，最小 0.05 秒，
     * 同一位置反复调整自动叠加（调 0.5 再调 0.1 = 0.6），并即时生效。
     */
    private fun showCalibrateDialog(track: Track) {
        val pos = PlayerController.positionMs.value
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 28, 56, 8)
        }
        box.addView(TextView(this).apply {
            text = "这次调整作用于："
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg_dim))
        })
        val radio = targetRadio()
        box.addView(radio)
        box.addView(TextView(this).apply {
            text = "车机歌词普遍比手机慢 → 选「车机显示（所有歌）」再点「提前」。\n" +
                "手机只调这首歌；车机对所有歌通用（调一次全生效）。\n" +
                "从 ${Fmt.duration(pos)} 起的歌词生效，可反复点叠加。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg_dim))
            setPadding(0, 12, 0, 16)
        })

        fun applyOffset(ms: Long) {
            val target = radio.selectedTarget()
            LyricStore.addSegment(track.bvid, pos, ms, target)
            LyricEngine.recalibrate(track.bvid)
            val who = if (target == LyricTarget.CAR) "车机" else "手机"
            Toast.makeText(
                this,
                "$who 歌词${if (ms < 0) "提前" else "延后"} ${kotlin.math.abs(ms)} 毫秒（已生效）",
                Toast.LENGTH_SHORT
            ).show()
        }

        val steps = listOf(-1000L, -500L, -200L, -50L, 50L, 200L, 500L, 1000L)
        val labels = steps.map { ms ->
            val v = if (kotlin.math.abs(ms) >= 1000) "${kotlin.math.abs(ms) / 1000} 秒"
            else "${kotlin.math.abs(ms) / 1000.0} 秒"
            (if (ms < 0) "提前 " else "延后 ") + v
        }
        box.addView(tuneRow(*steps.take(4).mapIndexed { i, ms ->
            tuneButton(labels[i]) { applyOffset(ms) }
        }.toTypedArray()))
        box.addView(tuneRow(*steps.drop(4).mapIndexed { i, ms ->
            tuneButton(labels[i + 4]) { applyOffset(ms) }
        }.toTypedArray()))

        val custom = EditText(this).apply {
            hint = "自定义毫秒（负=提前，如 -350）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(24, 24, 24, 16)
        }
        box.addView(custom)

        AlertDialog.Builder(this)
            .setTitle("校准歌词 · 从 ${Fmt.duration(pos)} 起")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("应用自定义值") { _, _ ->
                val ms = custom.text.toString().trim().toLongOrNull()
                if (ms == null || ms == 0L) {
                    Toast.makeText(this, "请输入非零毫秒数", Toast.LENGTH_SHORT).show()
                } else applyOffset(ms)
            }
            .setNegativeButton("完成", null)
            .showWhileAdjusting()
    }

    /**
     * 歌词整体微调：两个滑杆（偏移精确到 0.01 秒 + 速度倍率 0.1%），
     * 手机按歌保存、车机全局保存；修「越到后面偏差越大」的速度类问题。
     */
    private fun showFineTuneDialog(track: Track) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 28, 56, 8)
        }
        val radio = targetRadio()
        container.addView(radio)
        container.addView(TextView(this).apply {
            text = "手机调的只对这首歌生效；车机调的对所有歌生效。\n切换设备会显示该设备已保存的参数；应用只影响当前选中的设备。"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg_dim))
            setPadding(0, 8, 0, 16)
        })

        val tvOffset = TextView(this).apply { textSize = 13f }
        val sliderOffset = Slider(this).apply {
            valueFrom = -5000f
            valueTo = 5000f
            stepSize = 1f
        }
        val tvRate = TextView(this).apply { textSize = 13f }
        val sliderRate = Slider(this).apply {
            valueFrom = -100f
            valueTo = 100f
            stepSize = 1f
        }

        fun refreshLabels() {
            val off = sliderOffset.value.toLong()
            val rate = 1.0 + sliderRate.value / 1000.0
            tvOffset.text = "整体偏移：${String.format("%+.2f 秒", off / 1000.0)}（正=延后，负=提前）"
            tvRate.text = String.format("速度倍率：%.3f（越到后面越慢就调大于 1）", rate)
        }

        fun loadTargetValues() {
            val t = radio.selectedTarget()
            // 「当前歌词应该在哪」一次可能平移几十秒，滑杆范围要跟着扩展，
            // 否则在这里一点「应用」就把已保存的大偏移裁回 ±5 秒。
            val off = LyricStore.getCalibOffset(track.bvid, t)
            val lo = minOf(-5000L, off - 1000L)
            val hi = maxOf(5000L, off + 1000L)
            sliderOffset.value = 0f
            sliderOffset.valueFrom = lo.toFloat()
            sliderOffset.valueTo = hi.toFloat()
            sliderOffset.value = off.toFloat().coerceIn(lo.toFloat(), hi.toFloat())
            sliderRate.value = ((LyricStore.getCalibRate(track.bvid, t) - 1.0) * 1000.0)
                .roundToInt().coerceIn(-100, 100).toFloat()
            refreshLabels()
        }

        radio.setOnCheckedChangeListener { _, _ -> loadTargetValues() }
        sliderOffset.addOnChangeListener { _, _, _ -> refreshLabels() }
        sliderRate.addOnChangeListener { _, _, _ -> refreshLabels() }
        loadTargetValues()

        container.addView(tvOffset)
        container.addView(sliderOffset)
        container.addView(
            tuneRow(
                tuneButton("−100ms") { sliderOffset.value = (sliderOffset.value - 100f).coerceIn(-5000f, 5000f) },
                tuneButton("−10ms") { sliderOffset.value = (sliderOffset.value - 10f).coerceIn(-5000f, 5000f) },
                tuneButton("+10ms") { sliderOffset.value = (sliderOffset.value + 10f).coerceIn(-5000f, 5000f) },
                tuneButton("+100ms") { sliderOffset.value = (sliderOffset.value + 100f).coerceIn(-5000f, 5000f) }
            )
        )
        container.addView(tvRate)
        container.addView(sliderRate)

        fun apply(reset: Boolean) {
            val t = radio.selectedTarget()
            if (reset) {
                LyricStore.setCalibration(track.bvid, 0L, 1.0, t)
            } else {
                LyricStore.setCalibration(
                    track.bvid,
                    sliderOffset.value.toLong(),
                    1.0 + sliderRate.value / 1000.0,
                    t
                )
            }
            LyricEngine.recalibrate(track.bvid)
            val who = if (t == LyricTarget.CAR) "车机（所有歌生效）" else "手机（只这首）"
            Toast.makeText(
                this,
                if (reset) "已重置${who}的整体微调" else "已应用${who}的整体微调，即刻生效",
                Toast.LENGTH_SHORT
            ).show()
        }

        AlertDialog.Builder(this)
            .setTitle("歌词整体微调（手机按歌 · 车机全局）")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("应用") { _, _ -> apply(false) }
            .setNeutralButton("重置该设备") { _, _ -> apply(true) }
            .setNegativeButton("取消", null)
            .showWhileAdjusting()
    }

    /**
     * 手打歌词第一步：先把这首歌在各个源里的**其它版本**列出来，
     * 用户挑一个最接近的当底稿，在它的基础上改字/改时间，省去从头敲一遍。
     */
    private fun showManualLyricDialog(track: Track, queryOverride: String? = null) {
        var cancelled = false
        val loading = AlertDialog.Builder(this)
            .setTitle(if (queryOverride.isNullOrBlank()) "正在找可用的歌词版本" else "正在搜索「$queryOverride」")
            .setMessage("正在 B站字幕 / 网易云 / QQ音乐 / 酷狗 / LRCLIB 里翻…（最多约 10 秒）")
            .setCancelable(true)
            .setNegativeButton("取消") { _, _ -> cancelled = true }
            .create()
        loading.setOnCancelListener { cancelled = true }
        loading.holdPlayback()
        loading.show()

        lifecycleScope.launch {
            val cands = try {
                LyricsRepository.searchCandidates(track, queryOverride)
            } catch (e: Exception) {
                emptyList()
            }
            if (isFinishing || isDestroyed) return@launch
            loading.dismiss()
            if (cancelled) return@launch

            val existing = LyricStore.getCustomLrc(track.bvid)
            val labels = ArrayList<String>()
            val actions = ArrayList<() -> Unit>()

            labels.add("空白手打（从零开始）")
            actions.add { openLyricEditor(track, "", "我的歌词") }

            if (!existing.isNullOrBlank()) {
                val lines = existing.split("\n").count { it.isNotBlank() }
                labels.add("当前我的歌词（$lines 行，接着改）")
                actions.add {
                    openLyricEditor(
                        track, existing,
                        LyricStore.getCustomSource(track.bvid) ?: "我的歌词"
                    )
                }
            }

            // 网络全不通时也要有能用的底稿：当前正在显示的歌词直接拿来改
            val currentLines = LyricEngine.lines.value
            if (currentLines.isNotEmpty()) {
                labels.add("当前显示的歌词（${currentLines.size} 行，拿来改）")
                actions.add {
                    openLyricEditor(track, LrcParser.toText(currentLines), "当前歌词")
                }
            }

            cands.forEach { c ->
                val sim = (c.similarity * 100).roundToInt()
                val artist = c.artist.takeIf { it.isNotBlank() } ?: "未知艺人"
                val dur = if (c.durationMs > 0) Fmt.duration(c.durationMs) else "--:--"
                labels.add("${c.title} — $artist · $dur · ${c.source} · $sim%")
                actions.add {
                    loadCandidateThenEdit(track, c)
                }
            }

            if (cands.isEmpty()) {
                Toast.makeText(
                    this@PlayerActivity,
                    "网络上暂时没搜到其它版本（可能被网络挡住），仍可选「当前显示的歌词」当底稿，或换个关键词",
                    Toast.LENGTH_LONG
                ).show()
            }

            AlertDialog.Builder(this@PlayerActivity)
                .setTitle(
                    if (cands.isEmpty()) "直接手打歌词"
                    else "选个底稿再改（找到 ${cands.size} 个版本）"
                )
                .setSingleChoiceItems(labels.toTypedArray(), -1) { d, which ->
                    d.dismiss()
                    actions.getOrNull(which)?.invoke()
                }
                .setNeutralButton("换个关键词") { _, _ -> askKeywordThenSearch(track) }
                .setNegativeButton("取消", null)
                .showWhileAdjusting()
        }
    }

    /** 用户自己输入歌名/关键词，重新翻一遍版本（搜不到想要的版本时用） */
    private fun askKeywordThenSearch(track: Track) {
        val input = EditText(this).apply {
            hint = "原唱歌名 / 动画名 或 歌手名"
            setText(TitleParser.guessSongTitle(track.rawTitle).ifBlank { track.searchKeyword })
            setSelection(text.length)
            setPadding(56, 36, 56, 36)
        }
        AlertDialog.Builder(this)
            .setTitle("换个关键词搜版本")
            .setView(FrameLayout(this).apply { addView(input) })
            .setPositiveButton("搜索") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isEmpty()) {
                    Toast.makeText(this, "关键词为空", Toast.LENGTH_SHORT).show()
                } else {
                    showManualLyricDialog(track, kw)
                }
            }
            .setNegativeButton("取消", null)
            .showWhileAdjusting()
    }

    /** 选中某个版本后拉它的 LRC 原文，再打开编辑器 */
    private fun loadCandidateThenEdit(track: Track, c: LyricCandidate) {
        val loading = AlertDialog.Builder(this)
            .setTitle("正在读取歌词")
            .setMessage("${c.title} · ${c.source}")
            .setCancelable(false)
            .create()
        loading.holdPlayback()
        loading.show()

        lifecycleScope.launch {
            val lrc = try {
                LyricsRepository.fetchCandidateLrc(c)
            } catch (e: Exception) {
                ""
            }
            if (isFinishing || isDestroyed) return@launch
            loading.dismiss()
            if (lrc.isBlank()) {
                Toast.makeText(
                    this@PlayerActivity,
                    "这个版本没有可用歌词（可能是纯音乐版），换一个试试",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val label = "${c.source} · ${c.artist.ifBlank { c.title }}"
            openLyricEditor(track, lrc, label)
        }
    }

    /**
     * 手打歌词编辑器。
     * 「保存并应用」以前放在 AlertDialog 的系统按钮栏里，软键盘一弹出就被顶到屏幕外，
     * 用户点不到。现在把三个按钮做成内容区顶部的固定工具条，键盘怎么弹都在。
     */
    private fun openLyricEditor(track: Track, prefill: String, sourceLabel: String) {
        val input = EditText(this).apply {
            setText(prefill)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minHeight = (resources.displayMetrics.heightPixels * 0.24).toInt()
            setPadding(40, 24, 40, 24)
            setSelection(text.length)
        }

        val btnSave = android.widget.Button(this).apply {
            text = "保存并应用"
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@PlayerActivity, R.color.bili_pink)
            )
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.bg))
        }
        val btnChange = android.widget.Button(this).apply { text = "换底稿" }
        val btnCancel = android.widget.Button(this).apply { text = "取消" }
        listOf(btnChange, btnCancel).forEach {
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@PlayerActivity, R.color.surface_high)
            )
            it.setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg))
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 12, 20, 12)
            addView(btnSave, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f))
            addView(btnChange, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnCancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
        }
        val tip = TextView(this).apply {
            text = "每行一句；带 [00:12.34] 时间戳按时间显示，不带则按行数均分整首歌。\n" +
                "底稿：$sourceLabel · 改完点上方「保存并应用」立刻生效并永久记住。"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.on_bg_dim))
            setPadding(24, 8, 24, 8)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tools)
            addView(tip)
            addView(
                ScrollView(this@PlayerActivity).apply { addView(input) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑歌词")
            .setView(box)
            .setCancelable(true)
            .create()
        // 打开时先不弹键盘；点到输入框后键盘弹出、窗口随之缩放，顶部工具条始终可见
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )

        val save: () -> Unit = {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "内容为空，未保存（可继续编辑）", Toast.LENGTH_SHORT).show()
            } else {
                LyricStore.setCustomLrc(track.bvid, text, sourceLabel)
                reloadLyrics(track)
                Toast.makeText(this, "已保存并应用，当前正在使用这份歌词", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        val change: () -> Unit = {
            val go = { dialog.dismiss(); showManualLyricDialog(track) }
            if (input.text.toString() != prefill) {
                AlertDialog.Builder(this)
                    .setTitle("放弃当前修改？")
                    .setMessage("换底稿会丢掉这次没保存的修改。")
                    .setPositiveButton("换底稿") { _, _ -> go() }
                    .setNegativeButton("继续编辑", null)
                    .showWhileAdjusting()
            } else go()
            Unit
        }
        btnSave.setOnClickListener { save() }
        btnChange.setOnClickListener { change() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.holdPlayback()
        dialog.show()
    }

    /** 选择字幕轨作为歌词来源：B 站 AI 字幕本质是「语音转文字」，上传者开启后才可用 */
    private fun showSubtitlePicker(track: Track) {
        lifecycleScope.launch {
            val subs = try {
                BiliRepository.listSubtitles(track.bvid, track.cid, track.aid)
            } catch (_: Exception) {
                emptyList()
            }
            val chosen = LyricStore.getChosenSubtitle(track.bvid)
            val labels = ArrayList<String>()
            val lans = ArrayList<String?>()
            labels.add("自动（默认，优先人工字幕）")
            lans.add(null)
            subs.forEach { s ->
                val isAi = (s.lan ?: "").startsWith("ai")
                val doc = s.lanDoc?.takeIf { it.isNotBlank() } ?: s.lan.orEmpty()
                labels.add(if (isAi) "AI 字幕（语音转文字）· $doc" else "字幕 · $doc")
                lans.add(s.lan)
            }
            withContext(Dispatchers.Main) {
                if (labels.size == 1) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "这个视频没有可用字幕，无法使用语音转文字（需上传者开启 B 站 AI 字幕）",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                var checked = 0
                lans.forEachIndexed { i, l -> if (l == chosen) checked = i }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("选择字幕（歌词 / 语音转文字来源）")
                    .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                        d.dismiss()
                        val lan = lans[which]
                        LyricStore.setChosenSubtitle(track.bvid, lan)
                        reloadLyrics(track)
                        Toast.makeText(
                            this@PlayerActivity,
                            if (lan == null) "已恢复自动选择字幕" else "已指定字幕来源，重新加载中…",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("取消", null)
                    .showWhileAdjusting()
            }
        }
    }

    /**
     * 多P视频「只保留某个分P」：列出所有分P，选定后立即切换播放，
     * 并持久保存 —— 收藏夹列表里的这首歌从此只显示/播放选中的分P。
     */
    private fun showPagePicker(track: Track) {
        lifecycleScope.launch {
            val pages = try {
                BiliRepository.fetchPages(track.bvid)
            } catch (_: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (pages.size <= 1) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "这个视频只有一个分P（或分P信息读取失败）",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                val labels = pages.map { p ->
                    val name = p.part?.takeIf { it.isNotBlank() } ?: "分P"
                    "P${p.page} $name · ${Fmt.duration(p.duration * 1000L)}"
                }
                val saved = PageStore.get(track.bvid)
                var checked = pages.indexOfFirst { it.cid == (saved?.cid ?: track.cid) }
                if (checked < 0) checked = 0
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("选择分P（只保留选中的这个）")
                    .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                        d.dismiss()
                        val p = pages[which]
                        PageStore.save(
                            track.bvid,
                            PageStore.PageChoice(
                                page = p.page,
                                cid = p.cid,
                                part = p.part.orEmpty(),
                                durationMs = p.duration * 1000L
                            )
                        )
                        val part = p.part?.takeIf { it.isNotBlank() }.orEmpty()
                        val newTrack = track.copy(
                            cid = p.cid,
                            durationMs = p.duration * 1000L,
                            title = ("${TitleParser.clean(track.rawTitle)} · P${p.page} $part").take(120)
                        )
                        PlayerController.replaceCurrentTrack(newTrack)
                        Toast.makeText(
                            this@PlayerActivity,
                            "已切换到 P${p.page}，收藏夹里这首歌也会只保留这个分P",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    /**
     * 加入我的歌单：本地歌单不影响 B 站收藏夹数据。
     * 勾选/取消多个歌单一次保存；也可现场新建歌单。
     */
    private fun showPlaylistPicker(track: Track) {
        val playlists = PlaylistStore.all()
        val inLists = PlaylistStore.playlistsOf(track)
        val checked = BooleanArray(playlists.size) { inLists.contains(playlists[it].id) }
        val labels = playlists.map { "${it.name}（${it.tracks.size} 首）" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("加入我的歌单（仅本地，不影响B站）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                var added = 0
                playlists.forEachIndexed { i, p ->
                    val wasIn = inLists.contains(p.id)
                    if (checked[i] && !wasIn && PlaylistStore.add(p.id, track)) added++
                    if (!checked[i] && wasIn) PlaylistStore.remove(p.id, track)
                }
                Toast.makeText(
                    this,
                    if (added > 0) "已加入 $added 个歌单" else "歌单已更新",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("新建歌单并加入") { _, _ ->
                val input = EditText(this).apply {
                    hint = "歌单名称"
                    inputType = InputType.TYPE_CLASS_TEXT
                    setPadding(56, 36, 56, 36)
                }
                AlertDialog.Builder(this)
                    .setTitle("新建歌单")
                    .setView(input)
                    .setPositiveButton("创建") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            Toast.makeText(this, "名称为空，未创建", Toast.LENGTH_SHORT).show()
                        } else {
                            val p = PlaylistStore.create(name)
                            PlaylistStore.add(p.id, track)
                            Toast.makeText(this, "已创建「${p.name}」并加入这首歌", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fractionToMs(fraction: Float): Long {
        val total = if (PlayerController.durationMs.value > 0) {
            PlayerController.durationMs.value
        } else currentTrack?.durationMs ?: 0L
        return (total * (fraction / 1000f)).toLong()
    }

    private fun speedLabel(v: Float): String {
        val text = if (v % 1f == 0f) String.format("%.1f", v) else String.format("%.2f", v)
        return "${text}x"
    }
}
