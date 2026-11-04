package com.bilisten.app.ui

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
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
import com.bilisten.app.data.LyricLine
import com.bilisten.app.data.LyricStore
import com.bilisten.app.data.LyricsRepository
import com.bilisten.app.data.Prefs
import com.bilisten.app.data.Track
import com.bilisten.app.databinding.ActivityPlayerBinding
import com.bilisten.app.player.LyricBroadcaster
import com.bilisten.app.player.PlayerController
import com.bilisten.app.util.Fmt
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var lyricAdapter: LyricAdapter

    private var lyricLines: List<LyricLine> = emptyList()
    private var currentLyricIndex = -1
    private var userSeeking = false
    private var loadedBvid = ""
    private var currentTrack: Track? = null

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
        updateLyricHighlight(pos)
    }

    private fun updateLyricHighlight(pos: Long) {
        if (lyricLines.isEmpty()) {
            LyricBroadcaster.update("")
            return
        }
        var lo = 0
        var hi = lyricLines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lyricLines[mid].timeMs <= pos + 150) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found != currentLyricIndex) {
            currentLyricIndex = found
            lyricAdapter.setCurrent(found)
            if (found >= 0) {
                val lm = binding.rvLyrics.layoutManager as LinearLayoutManager
                val offset = binding.rvLyrics.height / 2 - 40
                lm.scrollToPositionWithOffset(found, offset.coerceAtLeast(0))
            }
        }
        val currentText = if (found >= 0) lyricLines[found].text else ""
        // 同步给后台服务 → 媒体通知，车机/Android Auto 显示当前这句
        LyricBroadcaster.update(currentText)
        // 视频画面模式下，画面底部只保留当前这一行字幕
        if (binding.tvVideoCaption.visibility == View.VISIBLE) {
            binding.tvVideoCaption.text = currentText
        }
    }

    /** 视频画面模式：封面换成播放画面，底部一行字幕 */
    private fun applyVideoMode(on: Boolean) {
        if (on) {
            binding.ivCover.visibility = View.GONE
            binding.surfaceVideo.visibility = View.VISIBLE
            binding.tvVideoCaption.visibility =
                if (lyricLines.isEmpty()) View.GONE else View.VISIBLE
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
        lyricLines = emptyList()
        currentLyricIndex = -1
        lyricAdapter.submitList(emptyList())
        binding.lyricEmpty.visibility = View.VISIBLE
        binding.tvLyricTip.text = "正在找歌词…"
        binding.btnSearchLyric.visibility = View.GONE
        binding.btnLyric.text = "歌词"

        lifecycleScope.launch {
            val result = LyricsRepository.load(track, keyword)
            if (loadedBvid != track.bvid) return@launch
            lyricLines = result.lines
            currentLyricIndex = -1
            lyricAdapter.submitList(result.lines)
            if (result.isEmpty) {
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
                    result.source.startsWith("B站") -> "歌词·B站字幕"
                    result.source.startsWith("LRCLIB") -> "歌词·LRCLIB"
                    result.source == "我的歌词" -> "歌词·我的"
                    else -> "歌词"
                }
            }
        }
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
        popup.menu.add(0, 7, 6, "手打歌词")
        if (LyricStore.getCustomLrc(track.bvid).isNullOrBlank().not()) {
            popup.menu.add(0, 8, 7, "清除我的歌词")
        }
        if (LyricStore.getSegments(track.bvid).isNotEmpty()) {
            popup.menu.add(0, 9, 8, "重置歌词校准")
        }
        popup.menu.add(0, 10, 9, "歌词悬浮窗（置顶显示）").apply {
            isCheckable = true
            isChecked = Prefs.lyricOverlayOn
        }
        popup.menu.add(0, 11, 10, "字幕 / 语音转文字").apply {
            isCheckable = false
        }
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
                    LyricsRepository.invalidate(track.bvid)
                    Toast.makeText(this, "已清除我的歌词", Toast.LENGTH_SHORT).show()
                    loadLyrics(track, null)
                    true
                }
                9 -> {
                    LyricStore.clearSegments(track.bvid)
                    LyricsRepository.invalidate(track.bvid)
                    Toast.makeText(this, "已重置校准，恢复原始时间轴", Toast.LENGTH_SHORT).show()
                    loadLyrics(track, null)
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
                else -> false
            }
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        syncOverlay()
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
                    "只影响这首歌后面的部分，可多次叠加，永久保存。"
            )
            .setItems(options) { _, which ->
                LyricStore.addSegment(track.bvid, pos, offsets[which])
                LyricsRepository.invalidate(track.bvid)
                Toast.makeText(this, "已校准，即刻生效", Toast.LENGTH_SHORT).show()
                loadLyrics(track, null)
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
                    LyricsRepository.invalidate(track.bvid)
                    Toast.makeText(this, "已保存我的歌词", Toast.LENGTH_SHORT).show()
                    loadLyrics(track, null)
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
                        LyricsRepository.invalidate(track.bvid)
                        Toast.makeText(
                            this@PlayerActivity,
                            if (lan == null) "已恢复自动选择字幕" else "已指定字幕来源，重新加载中…",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadLyrics(track, null)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
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
