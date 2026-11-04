package com.bilisten.app.ui

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
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.bilisten.app.R
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.LyricStore
import com.bilisten.app.data.PageStore
import com.bilisten.app.data.PlaylistStore
import com.bilisten.app.data.Prefs
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
        binding.btnLyric.setOnClickListener { showLyricSearchDialog() }
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
        AlertDialog.Builder(this)
            .setTitle("搜索歌词")
            .setView(container)
            .setPositiveButton("搜索") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) loadLyrics(track, kw)
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun showMoreMenu(anchor: View) {
        val track = currentTrack ?: return
        val downloaded = Downloads.isDownloaded(track.bvid)
        val running = Downloads.isRunning(track.bvid)

        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "在哔哩哔哩打开视频")
        popup.menu.add(0, 2, 1, "重新加载歌词")
        popup.menu.add(
            0, 3, 2,
            when {
                running -> "取消下载"
                downloaded -> "删除下载"
                else -> "下载这首（离线听）"
            }
        )
        popup.menu.add(0, 4, 3, "歌词只使用 B 站字幕").apply {
            isCheckable = true
            isChecked = Prefs.lyricBiliOnly
        }
        popup.menu.add(0, 5, 4, "显示视频画面（仅这首）").apply {
            isCheckable = true
            isChecked = PlayerController.videoMode.value
        }
        popup.menu.add(0, 6, 5, "校准歌词（从这里开始）")
        popup.menu.add(0, 12, 6, "歌词整体微调（偏移 + 速度）")
        popup.menu.add(0, 7, 7, "手打歌词")
        if (LyricStore.getCustomLrc(track.bvid).isNullOrBlank().not()) {
            popup.menu.add(0, 8, 8, "清除我的歌词")
        }
        if (LyricStore.getSegments(track.bvid).isNotEmpty() || LyricStore.hasCalibration(track.bvid)) {
            popup.menu.add(0, 9, 9, "重置全部歌词校准")
        }
        popup.menu.add(0, 10, 10, "歌词悬浮窗（置顶显示）").apply {
            isCheckable = true
            isChecked = Prefs.lyricOverlayOn
        }
        popup.menu.add(0, 16, 11, "悬浮歌词双行（当前句+下一句）").apply {
            isCheckable = true
            isChecked = Prefs.lyricOverlayDual
        }
        popup.menu.add(0, 11, 12, "字幕 / 语音转文字")
        popup.menu.add(0, 13, 13, "选择分P（只保留某个分P）")
        popup.menu.add(0, 14, 14, "加入我的歌单")
        popup.menu.add(0, 15, 15, "屏幕常亮设置")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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
                    loadLyrics(track, null)
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
                    loadLyrics(track, null)
                    true
                }
                5 -> {
                    PlayerController.setVideoMode(!PlayerController.videoMode.value)
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
                    LyricEngine.load(track, null)
                    Toast.makeText(this, "已清除我的歌词", Toast.LENGTH_SHORT).show()
                    true
                }
                9 -> {
                    LyricStore.clearSegments(track.bvid)
                    LyricStore.setCalibration(track.bvid, 0L, 1.0)
                    LyricEngine.load(track, null)
                    Toast.makeText(this, "已重置全部校准，恢复原始时间轴", Toast.LENGTH_SHORT).show()
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
                else -> false
            }
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        syncOverlay()
        applyScreenKeepOn()
    }

    override fun onPause() {
        super.onPause()
        screenHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        // 离开播放页时把画面卸掉，避免播放器持有已销毁的 Surface
        try {
            PlayerController.controller?.setVideoSurfaceView(null)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** 歌词快了/慢了：从当前位置起，给后面的歌词整体提前 / 延后（持久保存） */
    private fun showCalibrateDialog(track: Track) {
        val pos = PlayerController.positionMs.value
        val options = arrayOf(
            "后面歌词提前 0.5 秒", "后面歌词提前 0.1 秒",
            "后面歌词延后 0.1 秒", "后面歌词延后 0.5 秒"
        )
        val offsets = longArrayOf(-500L, -100L, 100L, 500L)
        AlertDialog.Builder(this)
            .setTitle("从 ${Fmt.duration(pos)} 开始校准")
            .setMessage(
                "歌词显示比演唱慢就选「提前」，快了就选「延后」。\n" +
                    "只影响这首歌后面的部分，可多次叠加，永久保存。\n" +
                    "「歌词整体微调」可以修「越到后面偏差越大」的速度问题。"
            )
            .setItems(options) { _, which ->
                LyricStore.addSegment(track.bvid, pos, offsets[which])
                LyricEngine.load(track, null)
                Toast.makeText(this, "已校准，即刻生效", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 歌词整体微调：两个滑杆精确调整 ——
     * 偏移（±5 秒，步长 0.05 秒）+ 速度倍率（±10%，步长 0.1%），
     * 修「越到后面歌词偏得越多」的速度类偏差。立即生效，永久保存。
     */
    private fun showFineTuneDialog(track: Track) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 8)
        }
        val tvOffset = TextView(this).apply { textSize = 13f }
        val sliderOffset = Slider(this).apply {
            valueFrom = -5000f
            valueTo = 5000f
            stepSize = 50f
            value = (LyricStore.getCalibOffset(track.bvid) / 50L * 50L)
                .toFloat().coerceIn(-5000f, 5000f)
        }
        val tvRate = TextView(this).apply { textSize = 13f }
        val sliderRate = Slider(this).apply {
            valueFrom = -100f
            valueTo = 100f
            stepSize = 1f
            value = ((LyricStore.getCalibRate(track.bvid) - 1.0) * 1000.0)
                .roundToInt().coerceIn(-100, 100).toFloat()
        }

        fun refreshLabels() {
            val off = sliderOffset.value.toLong()
            val rate = 1.0 + sliderRate.value / 1000.0
            val offText = if (off == 0L) "0 秒" else String.format("%+.2f 秒", off / 1000.0)
            tvOffset.text = "整体偏移：$offText（正=歌词延后显示，负=提前）"
            tvRate.text = String.format("速度倍率：%.3f（越到后面越慢就调大于 1）", rate)
        }
        refreshLabels()
        sliderOffset.addOnChangeListener { _, _, _ -> refreshLabels() }
        sliderRate.addOnChangeListener { _, _, _ -> refreshLabels() }

        container.addView(tvOffset)
        container.addView(sliderOffset)
        container.addView(tvRate)
        container.addView(sliderRate)

        AlertDialog.Builder(this)
            .setTitle("歌词整体微调")
            .setView(container)
            .setPositiveButton("应用") { _, _ ->
                LyricStore.setCalibration(
                    track.bvid,
                    sliderOffset.value.toLong(),
                    1.0 + sliderRate.value / 1000.0
                )
                LyricEngine.load(track, null)
                Toast.makeText(this, "已应用整体微调，即刻生效", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("重置") { _, _ ->
                LyricStore.setCalibration(track.bvid, 0L, 1.0)
                LyricEngine.load(track, null)
                Toast.makeText(this, "已重置整体微调", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手打歌词：支持时间戳行，也支持整段无时间戳按行均分 */
    private fun showManualLyricDialog(track: Track) {
        val input = EditText(this).apply {
            setText(LyricStore.getCustomLrc(track.bvid).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minHeight = (resources.displayMetrics.heightPixels * 0.4).toInt()
            setPadding(56, 36, 56, 36)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply { addView(input) }
        AlertDialog.Builder(this)
            .setTitle("手打歌词")
            .setMessage(
                "每行一句歌词。可以带时间戳（如 [00:12.34]歌词 或 00:12 歌词），\n" +
                    "也可以不带（按行数均分整首歌的时间轴）。\n" +
                    "保存后永久生效，并同步显示到车机通知。"
            )
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "内容为空，未保存", Toast.LENGTH_SHORT).show()
                } else {
                    LyricStore.setCustomLrc(track.bvid, text, "我的歌词")
                    LyricEngine.load(track, null)
                    Toast.makeText(this, "已保存我的歌词", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
                        LyricEngine.load(track, null)
                        Toast.makeText(
                            this@PlayerActivity,
                            if (lan == null) "已恢复自动选择字幕" else "已指定字幕来源，重新加载中…",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
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
