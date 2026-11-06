package app.tarana.pad

import android.app.Application
import app.tarana.pad.player.StreamResolver
import app.tarana.pad.player.WebViewSolver
import app.tarana.pad.yt.InnerTube
import app.tarana.pad.yt.LyricsRepo
import app.tarana.pad.yt.YtMusic
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import java.util.Locale

/** 全局单例容器：InnerTube 客户端、歌词、取流器、图片加载器。 */
class TaranaApp : Application(), SingletonImageLoader.Factory {

    lateinit var session: PrefsSessionStore
        private set
    lateinit var yt: YtMusic
        private set
    lateinit var lyrics: LyricsRepo
        private set
    lateinit var resolver: StreamResolver
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = PrefsSessionStore(this)
        val loc = Locale.getDefault()
        val hl = if (loc.language == "zh") (if (loc.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN") else loc.toLanguageTag()
        yt = YtMusic(InnerTube(session, hl = hl))
        lyrics = LyricsRepo(yt)
        resolver = StreamResolver(InnerTube.defaultHttp, session, WebViewSolver(this), filesDir.resolve("player"))
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { InnerTube.defaultHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(200L shl 20).build() }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: TaranaApp
            private set
    }
}
