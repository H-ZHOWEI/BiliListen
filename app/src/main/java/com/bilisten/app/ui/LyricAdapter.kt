package com.bilisten.app.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bilisten.app.R
import com.bilisten.app.data.LyricLine
import com.bilisten.app.databinding.ItemLyricBinding

class LyricAdapter(
    private val onSeek: (Long) -> Unit
) : ListAdapter<LyricLine, LyricAdapter.VH>(DIFF) {

    private var currentIndex = -1

    fun setCurrent(index: Int) {
        if (index == currentIndex) return
        val old = currentIndex
        currentIndex = index
        if (old in 0 until itemCount) notifyItemChanged(old)
        if (index in 0 until itemCount) notifyItemChanged(index)
    }

    class VH(val binding: ItemLyricBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLyricBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val line = getItem(position)
        val tv = holder.binding.tvLine
        val active = position == currentIndex
        tv.text = line.text
        val context = holder.itemView.context
        if (active) {
            tv.setTextColor(ContextCompat.getColor(context, R.color.bili_pink))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            tv.alpha = 1f
        } else {
            tv.setTextColor(ContextCompat.getColor(context, R.color.on_bg_dim))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            tv.alpha = 0.85f
        }
        holder.itemView.setOnClickListener { onSeek(line.timeMs) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LyricLine>() {
            override fun areItemsTheSame(a: LyricLine, b: LyricLine) = a.timeMs == b.timeMs
            override fun areContentsTheSame(a: LyricLine, b: LyricLine) =
                a.timeMs == b.timeMs && a.text == b.text
        }
    }
}
