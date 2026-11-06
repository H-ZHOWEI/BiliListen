package com.bilisten.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bilisten.app.R
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.PlaylistStore
import com.bilisten.app.databinding.FragmentFoldersBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class FolderFragment : Fragment(), MainActivity.Reloadable {

    private var _binding: FragmentFoldersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FolderAdapter

    /** 最近一次拉到的全部收藏夹（含被隐藏的），设置对话框直接用它 */
    private var latestFolders: List<com.bilisten.app.data.FavFolder> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFoldersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FolderAdapter { folder ->
            (activity as? MainActivity)?.openFolder(folder)
        }
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter
        binding.swipe.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.bili_pink)
        )
        binding.swipe.setOnRefreshListener { load() }

        // 「我喜欢」入口：点开本地收藏歌单（和播放页 ❤ 是同一个地方）
        binding.favEntry.setOnClickListener {
            val name = PlaylistStore.byId(PlaylistStore.FAV_ID)?.name ?: "我喜欢"
            (activity as? MainActivity)?.openPlaylistDetail(PlaylistStore.FAV_ID, name)
        }
        // 设置显示哪些收藏夹
        binding.btnFolderFilter.setOnClickListener { showFolderFilterDialog() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaylistStore.changed.collect {
                    val n = PlaylistStore.byId(PlaylistStore.FAV_ID)?.tracks?.size ?: 0
                    binding.tvFavCount.text =
                        if (n > 0) "$n 首歌 · 点开播放" else "播放页右上角 ❤ 收藏的歌"
                }
            }
        }

        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun reload() = load()

    /** 按用户隐藏规则刷新列表与空态文案 */
    private fun applyFilter() {
        if (_binding == null) return
        val hidden = com.bilisten.app.data.Prefs.hiddenFolders
        val shown = latestFolders.filterNot { hidden.contains(it.id) }
        adapter.submitList(shown)
        binding.empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            latestFolders.isEmpty() -> getString(R.string.empty_folders)
            shown.isEmpty() -> "所有收藏夹都被隐藏了\n点上方「设置显示哪些收藏夹」重新开启"
            else -> getString(R.string.empty_folders)
        }
    }

    /**
     * 设置显示哪些收藏夹：勾选 = 显示，取消勾选 = 首页不再显示。
     * 默认全选，用户把非音乐夹的勾去掉即可，只存本机不影响 B 站。
     */
    private fun showFolderFilterDialog() {
        if (latestFolders.isEmpty()) {
            Toast.makeText(requireContext(), "收藏夹还没加载出来，先等列表出现（或下拉刷新）", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = context ?: return
        val hidden = com.bilisten.app.data.Prefs.hiddenFolders

        val selectAll = android.widget.CheckBox(ctx).apply {
            text = "全选"
            textSize = 14f
        }
        val checks = latestFolders.map { f ->
            android.widget.CheckBox(ctx).apply {
                text = "${f.title}（${f.mediaCount}）"
                textSize = 14f
                isChecked = !hidden.contains(f.id)
            }
        }

        var syncing = false
        // 注意：先赋初值再挂监听，否则赋值会触发监听把子项勾选状态刷掉
        selectAll.isChecked = checks.all { it.isChecked }
        selectAll.setOnCheckedChangeListener { _, c ->
            if (syncing) return@setOnCheckedChangeListener
            syncing = true
            checks.forEach { it.isChecked = c }
            syncing = false
        }
        checks.forEach { cb ->
            cb.setOnCheckedChangeListener { _, _ ->
                if (syncing) return@setOnCheckedChangeListener
                syncing = true
                selectAll.isChecked = checks.all { it.isChecked }
                syncing = false
            }
        }

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 36, 56, 0)
            addView(selectAll)
            addView(View(ctx).apply { minimumHeight = 16 })
            checks.forEach { addView(it) }
        }

        val scroll = android.widget.ScrollView(ctx).apply { addView(container) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("选择要显示的收藏夹（勾选=显示）")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val newHidden = latestFolders.mapIndexed { i, f ->
                    if (checks[i].isChecked) null else f.id
                }.filterNotNull().toSet()
                com.bilisten.app.data.Prefs.hiddenFolders = newHidden
                applyFilter()
                Toast.makeText(
                    ctx,
                    "已保存：显示 ${latestFolders.size - newHidden.size} / ${latestFolders.size} 个收藏夹",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun load() {
        if (_binding == null) return
        binding.loading.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val folders = BiliRepository.fetchFolders(com.bilisten.app.data.Prefs.mid)
                latestFolders = folders
                applyFilter()

                // 「created/list-all」返回的 media_count 经常不准（含失效视频/缓存滞后），
                // 这里并发用资源列表接口的 info.media_count 校准一遍，跟B站App显示对齐
                val hidden = com.bilisten.app.data.Prefs.hiddenFolders
                val shown = folders.filterNot { hidden.contains(it.id) }
                if (shown.isNotEmpty()) {
                    val corrected = kotlinx.coroutines.coroutineScope {
                        shown.map { f ->
                            async {
                                try {
                                    val info = BiliRepository.fetchFavPage(f.id, 1).info
                                    val real = info?.mediaCount ?: f.mediaCount
                                    if (real != f.mediaCount) f.copy(mediaCount = real) else f
                                } catch (e: Exception) {
                                    f
                                }
                            }
                        }.awaitAll()
                    }
                    val byId = corrected.associateBy { it.id }
                    latestFolders = latestFolders.map { byId[it.id] ?: it }
                    _binding?.let { applyFilter() }
                }
            } catch (e: Exception) {
                binding.empty.visibility = View.VISIBLE
                binding.emptyText.text = "加载失败：${e.message}\n下拉可重试"
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipe.isRefreshing = false
            }
        }
    }
}
