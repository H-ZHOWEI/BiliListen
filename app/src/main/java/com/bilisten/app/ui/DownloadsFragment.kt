package com.bilisten.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bilisten.app.data.DlEntry
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.PlaylistStore
import com.bilisten.app.data.Track
import com.bilisten.app.databinding.FragmentTracksBinding
import com.bilisten.app.player.PlayerController
import com.bilisten.app.util.Fmt
import kotlinx.coroutines.launch

/**
 * 已下载歌曲列表：可播放（离线也能放）、可删除。
 */
class DownloadsFragment : Fragment() {

    private var _binding: FragmentTracksBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TrackAdapter
    private var entries: List<DlEntry> = emptyList()
    private var tracks: List<Track> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTracksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvFolderTitle.text = "已下载"
        binding.tvFolderSub.text = "离线也能听"

        binding.swipe.isEnabled = false
        binding.loading.visibility = View.GONE

        adapter = TrackAdapter(
            onClick = { index -> if (tracks.isNotEmpty()) play(index) },
            onRemoveDownload = { track -> confirmRemove(track) }
        )
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter

        binding.btnPlayAll.setOnClickListener {
            if (tracks.isNotEmpty()) play(0)
        }
        binding.btnShuffleAll.setOnClickListener {
            if (tracks.isNotEmpty()) {
                (activity as? MainActivity)?.startPlayback(tracks, tracks.indices.random(), true)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlayerController.current.collect { adapter.setPlaying(it?.bvid) } }
                launch { Downloads.states.collect { adapter.setDownloads(it) } }
                // 标出已点「我喜欢」的歌
                launch { PlaylistStore.changed.collect { adapter.setFavs(PlaylistStore.likedBvids()) } }
                launch {
                    Downloads.entries.collect {
                        render(it)
                    }
                }
            }
        }
    }

    private fun render(list: List<DlEntry>) {
        if (_binding == null) return
        entries = list.sortedByDescending { it.createdAt }
        // 只把文件还在的算作已下载
        tracks = entries.map { it.toTrack() }
        adapter.submitList(tracks)

        val size = Downloads.totalSize()
        binding.tvFolderSub.text =
            if (tracks.isEmpty()) "离线也能听"
            else "共 ${tracks.size} 首 · 占用 ${Fmt.size(size)}"

        val empty = tracks.isEmpty()
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.emptyText.text = "还没有下载的歌曲\n在歌曲列表点右侧的 ↓ 按钮即可下载"
    }

    private fun play(index: Int) {
        (activity as? MainActivity)?.startPlayback(tracks, index, false)
    }

    private fun confirmRemove(track: Track) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除下载？")
            .setMessage("将从本机删除《${track.title}》的音频文件。")
            .setPositiveButton("删除") { _, _ ->
                Downloads.remove(track.bvid)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
