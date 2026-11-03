package com.bilisten.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bilisten.app.R
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.Track
import com.bilisten.app.databinding.FragmentTracksBinding
import com.bilisten.app.player.PlayerController
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class TrackFragment : Fragment(), MainActivity.Reloadable {

    private var _binding: FragmentTracksBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: TrackAdapter
    private var tracks: List<Track> = emptyList()

    private val folderId: Long by lazy { requireArguments().getLong(ARG_ID) }
    private val folderTitle: String by lazy { requireArguments().getString(ARG_TITLE).orEmpty() }
    private val folderCount: Int by lazy { requireArguments().getInt(ARG_COUNT) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTracksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvFolderTitle.text = folderTitle
        binding.tvFolderSub.text = "$folderCount 个视频"

        adapter = TrackAdapter(
            onClick = { index ->
                if (tracks.isNotEmpty()) {
                    (activity as? MainActivity)?.startPlayback(tracks, index, false)
                }
            },
            onDownload = { track -> startDownload(track) },
            onRemoveDownload = { track -> confirmRemoveDownload(track) },
            onLongPress = { track, index -> showTrackMenu(track, index) }
        )
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter
        binding.swipe.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.bili_pink)
        )
        binding.swipe.setOnRefreshListener { load() }

        binding.btnPlayAll.setOnClickListener {
            if (tracks.isEmpty()) return@setOnClickListener
            (activity as? MainActivity)?.startPlayback(tracks, 0, false)
        }
        binding.btnShuffleAll.setOnClickListener {
            if (tracks.isEmpty()) return@setOnClickListener
            val start = tracks.indices.random()
            (activity as? MainActivity)?.startPlayback(tracks, start, true)
        }

        // 高亮正在播放的那一首
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlayerController.current.collect { track -> adapter.setPlaying(track?.bvid) } }
                launch { Downloads.states.collect { adapter.setDownloads(it) } }
            }
        }

        load()
    }

    private fun startDownload(track: Track) {
        val started = Downloads.enqueue(track)
        val msg = when {
            started -> "开始下载：${track.title}"
            Downloads.isDownloaded(track.bvid) -> "这首已经下载好了"
            else -> "这首正在下载中"
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemoveDownload(track: Track) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除下载？")
            .setMessage("将从本机删除《${track.title}》的音频文件。")
            .setPositiveButton("删除") { _, _ -> Downloads.remove(track.bvid) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按歌曲：删除 / 移入待删除收藏夹 */
    private fun showTrackMenu(track: Track, index: Int) {
        val items = arrayOf("从收藏夹删除", "移入「待删除」收藏夹")
        AlertDialog.Builder(requireContext())
            .setTitle(track.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmDelete(track, index)
                    1 -> moveToPending(track, index)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(track: Track, index: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("从收藏夹删除？")
            .setMessage("《${track.title}》将从这个收藏夹移除（视频仍在 B 站，可重新收藏）。")
            .setPositiveButton("删除") { _, _ ->
                removeAndRefresh(track) {
                    BiliRepository.removeFromFolder(folderId, listOf(track.aid))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveToPending(track: Track, index: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tarId = BiliRepository.ensurePendingFolder()
                removeAndRefresh(track) {
                    BiliRepository.moveToFolder(folderId, tarId, listOf(track.aid))
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "操作失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 先在本地列表移除并显示 loading，再执行远程操作；失败则恢复列表 */
    private fun removeAndRefresh(track: Track, action: suspend () -> Unit) {
        val original = tracks
        val remaining = original.filterNot { it.bvid == track.bvid }
        tracks = remaining
        adapter.submitList(remaining)
        binding.tvFolderSub.text = "${remaining.size} 首 · 点按播放 · 长按可删除"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                action()
                Toast.makeText(requireContext(), "已处理《${track.title}》", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // 远程失败：恢复原列表
                tracks = original
                adapter.submitList(original)
                Toast.makeText(
                    requireContext(),
                    "操作失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun reload() = load()

    private fun load() {
        if (_binding == null) return
        binding.loading.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val scope = viewLifecycleOwner.lifecycleScope
                val list = BiliRepository.fetchAllTracks(folderId) { count ->
                    scope.launch(Dispatchers.Main) {
                        _binding?.tvFolderSub?.text = "已加载 $count 首…"
                    }
                }
                tracks = list
                adapter.submitList(list)
                binding.tvFolderSub.text = "${list.size} 首 · 点按播放 · 长按可删除"
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyText.text = getString(R.string.empty_tracks)
            } catch (e: Exception) {
                binding.empty.visibility = View.VISIBLE
                binding.emptyText.text = "加载失败：${e.message}\n下拉可重试"
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipe.isRefreshing = false
            }
        }
    }

    companion object {
        private const val ARG_ID = "folder_id"
        private const val ARG_TITLE = "folder_title"
        private const val ARG_COUNT = "folder_count"

        fun newInstance(id: Long, title: String, count: Int): TrackFragment {
            val fragment = TrackFragment()
            fragment.arguments = Bundle().apply {
                putLong(ARG_ID, id)
                putString(ARG_TITLE, title)
                putInt(ARG_COUNT, count)
            }
            return fragment
        }
    }
}
