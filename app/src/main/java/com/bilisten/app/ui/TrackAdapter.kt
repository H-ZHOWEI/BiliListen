package com.bilisten.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.bilisten.app.R
import com.bilisten.app.data.DlState
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.Track
import com.bilisten.app.databinding.ItemTrackBinding
import com.bilisten.app.util.Fmt

class TrackAdapter(
    private val onClick: (Int) -> Unit,
    private val onDownload: (Track) -> Unit = {},
    private val onRemoveDownload: (Track) -> Unit = {},
    private val onLongPress: (Track, Int) -> Unit = { _, _ -> }
) : ListAdapter<Track, TrackAdapter.VH>(DIFF) {

    private var playingBvid: String? = null
    private var dlStates: Map<String, DlState> = emptyMap()
    /** 已点「我喜欢」的 bvid 集合，命中就在标题旁画小红心 */
    private var favBvids: Set<String> = emptySet()

    fun setPlaying(bvid: String?) {
        if (playingBvid == bvid) return
        playingBvid = bvid
        notifyDataSetChanged()
    }

    fun setDownloads(states: Map<String, DlState>) {
        if (dlStates == states) return
        dlStates = states
        notifyDataSetChanged()
    }

    fun setFavs(bvids: Set<String>) {
        if (favBvids == bvids) return
        favBvids = bvids
        notifyDataSetChanged()
    }

    class VH(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val track = getItem(position)
        val isPlaying = track.bvid == playingBvid
        val ctx = holder.itemView.context

        b.tvIndex.text = (position + 1).toString()
        b.tvIndex.visibility = if (isPlaying) View.GONE else View.VISIBLE
        b.ivPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE

        b.tvTitle.text = track.title
        b.tvArtist.text = track.artist
        b.tvDuration.text = Fmt.duration(track.durationMs)
        b.ivFav.visibility = if (favBvids.contains(track.bvid)) View.VISIBLE else View.GONE

        val highlight = ctx.getColor(R.color.bili_pink)
        val normal = ctx.getColor(R.color.on_bg)
        b.tvTitle.setTextColor(if (isPlaying) highlight else normal)

        if (track.cover.isNotBlank()) {
            b.ivCover.load(track.cover)
        } else {
            b.ivCover.setImageResource(R.drawable.ic_music)
        }

        bindDownload(b, track, highlight, ctx.getColor(R.color.bili_accent))

        b.root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        b.root.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongPress(getItem(pos), pos)
            true
        }
    }

    private fun bindDownload(b: ItemTrackBinding, track: Track, pink: Int, green: Int) {
        val dim = ContextCompat.getColor(b.root.context, R.color.on_bg_dim)
        val state = dlStates[track.bvid]
        b.tvDl.visibility = View.GONE

        when {
            state is DlState.Running -> {
                b.btnDl.setImageResource(R.drawable.ic_download)
                b.btnDl.setColorFilter(pink)
                b.tvDl.visibility = View.VISIBLE
                b.tvDl.text = "${state.percent}%"
                b.tvDl.setTextColor(pink)
            }
            state is DlState.Queued -> {
                b.btnDl.setImageResource(R.drawable.ic_download)
                b.btnDl.setColorFilter(pink)
                b.tvDl.visibility = View.VISIBLE
                b.tvDl.text = "等待"
                b.tvDl.setTextColor(pink)
            }
            state is DlState.Failed -> {
                b.btnDl.setImageResource(R.drawable.ic_download)
                b.btnDl.setColorFilter(dim)
                b.tvDl.visibility = View.VISIBLE
                b.tvDl.text = "重试"
                b.tvDl.setTextColor(dim)
            }
            Downloads.isDownloaded(track.bvid) -> {
                b.btnDl.setImageResource(R.drawable.ic_downloaded)
                b.btnDl.setColorFilter(green)
            }
            else -> {
                b.btnDl.setImageResource(R.drawable.ic_download)
                b.btnDl.setColorFilter(dim)
            }
        }

        b.btnDl.setOnClickListener {
            when {
                Downloads.isRunning(track.bvid) -> Downloads.cancel(track.bvid)
                Downloads.isDownloaded(track.bvid) -> onRemoveDownload(track)
                else -> onDownload(track)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(a: Track, b: Track) = a.bvid == b.bvid
            override fun areContentsTheSame(a: Track, b: Track) = a.bvid == b.bvid && a.title == b.title
        }
    }
}
