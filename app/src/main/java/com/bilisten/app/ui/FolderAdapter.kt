package com.bilisten.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.bilisten.app.R
import com.bilisten.app.data.FavFolder
import com.bilisten.app.databinding.ItemFolderBinding

class FolderAdapter(
    private val onClick: (FavFolder) -> Unit
) : ListAdapter<FavFolder, FolderAdapter.VH>(DIFF) {

    class VH(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFolderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val folder = getItem(position)
        holder.binding.tvTitle.text = folder.title
        holder.binding.tvCount.text = "${folder.mediaCount} 个视频"
        val iv = holder.binding.ivCover
        if (!folder.cover.isNullOrBlank()) {
            iv.load(folder.cover) {
                placeholder(R.drawable.ic_music)
                error(R.drawable.ic_music)
            }
            iv.clearColorFilter()
        } else {
            iv.setImageResource(R.drawable.ic_music)
            iv.setColorFilter(iv.context.getColor(R.color.bili_pink))
        }
        holder.binding.root.setOnClickListener { onClick(folder) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FavFolder>() {
            override fun areItemsTheSame(a: FavFolder, b: FavFolder) = a.id == b.id
            override fun areContentsTheSame(a: FavFolder, b: FavFolder) = a == b
        }
    }
}
