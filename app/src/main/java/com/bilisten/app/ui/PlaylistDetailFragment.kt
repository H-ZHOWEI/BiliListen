package com.bilisten.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bilisten.app.R
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.PlaylistStore
import com.bilisten.app.data.Track
import com.bilisten.app.databinding.FragmentTracksBinding
import com.bilisten.app.player.PlayerController
import kotlinx.coroutines.launch

/**
 * 歌单详情：复用收藏夹歌曲列表布局。
 * 点按播放、长按移出歌单（只动本地歌单，不影响 B 站任何数据）。
 */
class PlaylistDetailFragment : Fragment() {

    private var _binding: FragmentTracksBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: TrackAdapter
    private var tracks: List<Track> = emptyList()

    private val playlistId: String by lazy { requireArguments().getString(ARG_ID).orEmpty() }
    private val playlistName: String by lazy { requireArguments().getString(ARG_NAME).orEmpty() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTracksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvFolderTitle.text = playlistName
        binding.tvFolderSub.text = "加载中…"

        adapter = TrackAdapter(
            onClick = { index ->
                if (tracks.isNotEmpty()) {
                    (activity as? MainActivity)?.startPlayback(tracks, index, false)
                }
            },
            onDownload = { track ->
                val started = Downloads.enqueue(track)
                Toast.makeText(
                    requireContext(),
                    when {
                        started -> "开始下载：${track.title}"
                        Downloads.isDownloaded(track.bvid) -> "这首已经下载好了"
                        else -> "这首正在下载中"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onRemoveDownload = { track -> Downloads.remove(track.bvid) },
            onLongPress = { track, _ -> showTrackMenu(track) }
        )
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter
        binding.swipe.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.bili_pink)
        )
        binding.swipe.setOnRefreshListener { refresh() }

        binding.btnPlayAll.setOnClickListener {
            if (tracks.isNotEmpty()) {
                (activity as? MainActivity)?.startPlayback(tracks, 0, false)
            }
        }
        binding.btnShuffleAll.setOnClickListener {
            if (tracks.isNotEmpty()) {
                val start = tracks.indices.random()
                (activity as? MainActivity)?.startPlayback(tracks, start, true)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlayerController.current.collect { adapter.setPlaying(it?.bvid) } }
                launch { Downloads.states.collect { adapter.setDownloads(it) } }
                // 标出已点「我喜欢」的歌
                launch { PlaylistStore.changed.collect { adapter.setFavs(PlaylistStore.likedBvids()) } }
                launch {
                    PlaylistStore.changed.collect {
                        // 在别的界面改了歌单内容时同步
                        if (_binding != null) refresh()
                    }
                }
            }
        }

        refresh()
    }

    private fun showTrackMenu(track: Track) {
        val ctx = context ?: return
        val removeLabel =
            if (playlistId == PlaylistStore.FAV_ID) "从「我喜欢」移除" else "从歌单移除"
        AlertDialog.Builder(ctx)
            .setTitle(track.title)
            .setItems(arrayOf(removeLabel)) { _, _ ->
                PlaylistStore.remove(playlistId, track)
                refresh()
                Toast.makeText(ctx, "已移除（不影响B站收藏）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refresh() {
        if (_binding == null) return
        val playlist = PlaylistStore.byId(playlistId) ?: return
        tracks = playlist.tracks
        adapter.submitList(tracks)
        binding.tvFolderTitle.text = playlist.name
        binding.tvFolderSub.text = "${tracks.size} 首 · 点按播放 · 长按移出歌单"
        binding.empty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text =
            if (playlistId == PlaylistStore.FAV_ID) "还没有喜欢的歌\n播放页右上角点 ❤ 即可收藏"
            else "歌单还是空的：到收藏夹长按歌曲「加入我的歌单」"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ID = "playlist_id"
        private const val ARG_NAME = "playlist_name"

        fun newInstance(id: String, name: String): PlaylistDetailFragment {
            val fragment = PlaylistDetailFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_ID, id)
                putString(ARG_NAME, name)
            }
            return fragment
        }
    }
}
