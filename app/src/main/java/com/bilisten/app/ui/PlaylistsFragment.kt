package com.bilisten.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bilisten.app.R
import com.bilisten.app.data.PlaylistStore
import com.bilisten.app.databinding.FragmentPlaylistsBinding
import kotlinx.coroutines.launch

/**
 * 我的歌单：本地歌单列表 + 新建/重命名/删除。
 * 歌单数据只存在本机（playlists.json），完全不影响 B 站收藏夹。
 */
class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = PlaylistAdapter(
            onClick = { playlist ->
                (activity as? MainActivity)?.openPlaylistDetail(playlist.id, playlist.name)
            },
            onLongPress = { playlist -> showPlaylistMenu(playlist) }
        )
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter
        binding.swipe.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.bili_pink)
        )

        binding.btnNew.setOnClickListener { showCreateDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaylistStore.changed.collect { refresh() }
            }
        }
        refresh()
    }

    private fun refresh() {
        if (_binding == null) return
        val list = PlaylistStore.all()
        adapter.submitList(list)
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showCreateDialog() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            hint = "歌单名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(56, 36, 56, 36)
        }
        AlertDialog.Builder(ctx)
            .setTitle("新建歌单")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "名称为空，未创建", Toast.LENGTH_SHORT).show()
                } else {
                    PlaylistStore.create(name)
                    Toast.makeText(ctx, "已创建「$name」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按歌单：重命名 / 删除（默认歌单可改名不可删） */
    private fun showPlaylistMenu(playlist: PlaylistStore.Playlist) {
        val ctx = context ?: return
        val items = mutableListOf("重命名")
        if (!playlist.builtin) items.add("删除歌单")
        AlertDialog.Builder(ctx)
            .setTitle(playlist.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "重命名" -> showRenameDialog(playlist)
                    "删除歌单" -> AlertDialog.Builder(ctx)
                        .setTitle("删除歌单？")
                        .setMessage("「${playlist.name}」里的 ${playlist.tracks.size} 首歌将从本机歌单移除。\n只影响本地歌单，不影响 B 站收藏夹。")
                        .setPositiveButton("删除") { _, _ ->
                            PlaylistStore.delete(playlist.id)
                            Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(playlist: PlaylistStore.Playlist) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            setText(playlist.name)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(56, 36, 56, 36)
            setSelection(text.length)
        }
        AlertDialog.Builder(ctx)
            .setTitle("重命名歌单")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) PlaylistStore.rename(playlist.id, name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
