package com.bilisten.app

import android.app.Application
import coil.ImageLoader
import coil.Coil
import com.bilisten.app.data.Downloads
import com.bilisten.app.data.LyricStore
import com.bilisten.app.data.Net
import com.bilisten.app.data.Prefs
import com.bilisten.app.player.PlayerController

class BiliApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Downloads.init(this)
        LyricStore.init(this)

        // B 站图片有防盗链，复用同一个带 Referer 的 OkHttp 客户端
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient { Net.http }
                .crossfade(true)
                .build()
        )

        PlayerController.connect(this)
    }

    companion object {
        lateinit var instance: BiliApp
            private set
    }
}
