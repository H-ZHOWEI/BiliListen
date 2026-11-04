package com.bilisten.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bilisten.app.R
import com.bilisten.app.data.PlaylistStore
import com.bilisten.app.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onClick: (PlaylistStore.Playlist) -> Unit,
    private val onLongPress: (PlaylistStore.Playlist) -> Unit = { _ -> }
) : ListAdapter<PlaylistStore.Playlist, PlaylistAdapter.VH>(DIFF) {

    class VH(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = getItem(position)
        holder.binding.tvName.text = p.name
        holder.binding.tvSub.text = when {
            p.builtin -> "${p.tracks.size} 首 · 默认歌单"
            else -> "${p.tracks.size} 首"
        }
        holder.binding.ivIcon.setImageResource(
            if (p.builtin) R.drawable.ic_eq else R.drawable.ic_music
        )
        holder.binding.root.setOnClickListener { onClick(p) }
        holder.binding.root.setOnLongClickListener {
            onLongPress(p)
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaylistStore.Playlist>() {
            override fun areItemsTheSame(a: PlaylistStore.Playlist, b: PlaylistStore.Playlist) =
                a.id == b.id
            override fun areContentsTheSame(a: PlaylistStore.Playlist, b: PlaylistStore.Playlist) =
                a == b
        }
    }
}
