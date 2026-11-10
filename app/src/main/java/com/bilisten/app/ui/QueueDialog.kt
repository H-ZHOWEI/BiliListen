package com.bilisten.app.ui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bilisten.app.R
import com.bilisten.app.player.PlayerController
import kotlinx.coroutines.launch

/**
 * 播放列表对话框（播放页「列表」按钮 / 主页迷你播放条共用）：
 * - 正在播放的一首：B站粉加粗 + ▶ 标记 + 背景加深，高亮实时跟随自动切歌
 * - 队列被整体替换（回主页点了别的收藏夹）时列表内容实时刷新
 * - 点任意一首立即播放；长按可把某首移出队列（不影响收藏夹和已下载）
 */
fun showQueueDialog(activity: AppCompatActivity) {
    var tracks = PlayerController.queue.value
    if (tracks.isEmpty()) {
        Toast.makeText(activity, "当前没有播放队列", Toast.LENGTH_SHORT).show()
        return
    }
    var curIdx = PlayerController.currentIndex().coerceIn(0, tracks.size - 1)

    val accent = ContextCompat.getColor(activity, R.color.bili_pink)
    val normal = ContextCompat.getColor(activity, R.color.on_bg)
    val hiBg = ContextCompat.getColor(activity, R.color.surface_higher)

    val list = ListView(activity).apply { dividerHeight = 0 }
    val adapter = object : BaseAdapter() {
        override fun getCount() = tracks.size
        override fun getItem(position: Int) = tracks[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val t = tracks[position]
            val tv = (convertView as? TextView) ?: TextView(parent.context).apply {
                textSize = 14f
                maxLines = 2
                setLineSpacing(0f, 1.1f)
                setPadding(48, 30, 48, 30)
            }
            val isCur = position == curIdx
            tv.text = buildString {
                append(if (isCur) "▶ " else "${position + 1}. ")
                append(t.title)
                if (t.artist.isNotBlank()) append("  ·  ${t.artist}")
            }
            tv.setTextColor(if (isCur) accent else normal)
            tv.setBackgroundColor(if (isCur) hiBg else Color.TRANSPARENT)
            tv.paint.isFakeBoldText = isCur
            return tv
        }
    }
    list.adapter = adapter

    val dialog = AlertDialog.Builder(activity)
        .setTitle("播放列表（${tracks.size} 首）")
        .setView(list)
        .setNegativeButton("关闭", null)
        .create()

    fun refreshTitle() {
        dialog.setTitle("播放列表（${tracks.size} 首）")
    }

    val job = activity.lifecycleScope.launch {
        // 队列本身变了（长按移除 / 回主页重新点了一个歌单）→ 整体刷新
        launch {
            PlayerController.queue.collect { q ->
                if (q != tracks) {
                    if (q.isEmpty()) {
                        dialog.dismiss()
                        return@collect
                    }
                    tracks = q
                    curIdx = PlayerController.currentIndex().coerceIn(0, q.size - 1)
                    adapter.notifyDataSetChanged()
                    refreshTitle()
                    list.setSelection(curIdx)
                }
            }
        }
        // 自动切歌 → 高亮实时跳到当前歌并滚动过去
        launch {
            PlayerController.current.collect {
                val idx = PlayerController.currentIndex()
                if (idx in tracks.indices && idx != curIdx) {
                    curIdx = idx
                    adapter.notifyDataSetChanged()
                    list.smoothScrollToPosition(idx)
                }
            }
        }
    }

    list.setOnItemClickListener { _, _, position, _ ->
        PlayerController.playAt(position)
        curIdx = position
        adapter.notifyDataSetChanged()
        dialog.dismiss()
    }
    list.setOnItemLongClickListener { _, _, position, _ ->
        val t = tracks.getOrNull(position) ?: return@setOnItemLongClickListener true
        AlertDialog.Builder(activity)
            .setTitle("从播放列表移除")
            .setMessage("把「${t.title}」从当前播放队列里移除？\n只移出队列，不影响收藏夹和已下载。")
            .setPositiveButton("移除") { _, _ -> PlayerController.removeAt(position) }
            .setNegativeButton("取消", null)
            .show()
        true
    }
    dialog.setOnDismissListener { job.cancel() }
    dialog.show()
    list.setSelection(curIdx)
}
