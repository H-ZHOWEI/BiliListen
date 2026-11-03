package com.bilisten.app.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 歌词行广播：播放页每唱到一句就把当前行推到这里，
 * 后台服务收集后写进媒体通知，车机 / Android Auto / 蓝牙投屏即可同步显示。
 * 手打歌词、校准过的歌词同样走这里。
 */
object LyricBroadcaster {
    val line = MutableStateFlow("")

    fun update(text: String) {
        if (line.value != text) line.value = text
    }
}
