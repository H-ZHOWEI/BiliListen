package com.bilisten.app.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 把列表的某个位置滚动到「屏幕垂直居中」（与播放列表对话框的行为一致）。
 *
 * 做法：临时创建并绑定一个 ViewHolder 量出该行真实高度（标题有的一行、有的两行，高度不同），
 * 再用 scrollToPositionWithOffset 让这一行落在 (列表高度/2 - 行高/2) 处。
 */
fun RecyclerView.centerOnPosition(position: Int) {
    val lm = layoutManager as? LinearLayoutManager ?: return
    val adapter = adapter ?: return
    if (position !in 0 until adapter.itemCount) return
    post {
        if (!isAttachedToWindow) return@post
        val itemHeight = try {
            val type = adapter.getItemViewType(position)
            val vh = adapter.createViewHolder(this, type)
            adapter.onBindViewHolder(vh, position)
            vh.itemView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            vh.itemView.measuredHeight
        } catch (_: Exception) {
            0
        }
        val offset = height / 2 - itemHeight / 2
        lm.scrollToPositionWithOffset(position, offset.coerceAtLeast(0))
    }
}

/** 该位置是否已在屏幕可见范围内（可见就不打扰用户滚动，只高亮） */
fun RecyclerView.isVisibleInList(position: Int): Boolean {
    val lm = layoutManager as? LinearLayoutManager ?: return true
    if (position < 0) return false
    return position in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()
}
