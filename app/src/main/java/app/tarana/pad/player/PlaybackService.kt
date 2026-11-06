package app.tarana.pad.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tarana.pad.MainActivity
import app.tarana.pad.TaranaApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException

/**
 * 后台播放服务。对应 radif 的本地 stream proxy：
 *  - MediaItem 的 uri 是 `ytm://<videoId>`，真正的 googlevideo 地址在加载时才解析（ResolvingDataSource）。
 *  - 外层 CacheDataSource 以 videoId 为缓存键，听过的歌再播直接走磁盘，无需解析。
 *  - 流地址过期（403）时作废缓存的地址并重试一次。
 * 通知栏 / 锁屏 / 蓝牙耳机控制由 MediaSession 自动提供。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val retried = HashSet<String>()
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        val app = application as TaranaApp
        val resolver = app.resolver

        val http = OkHttpDataSource.Factory(app.resolver.http)
            .setUserAgent(StreamResolver.STREAM_UA)
        val resolving = ResolvingDataSource.Factory(ChunkedDataSource.Factory(http)) { spec ->
            if (spec.uri.scheme != "ytm") return@Factory spec
            val id = spec.uri.host ?: throw IOException("bad uri ${spec.uri}")
            val stream = resolver.resolveBlocking(id)
            spec.buildUpon().setUri(Uri.parse(stream.url)).build()
                .withAdditionalHeaders(mapOf("User-Agent" to stream.userAgent))
        }
        val cached = CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(resolving)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cached))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId ?: return
                // YouTube 封了当前 IP 的匿名访问：换哪首都一样，直接停下来报错
                if (StreamResolver.isBotCheck(error)) return
                // 流地址过期 / 被拒：作废后重新解析一次；仍失败则跳下一首，连续 3 首失败就停下
                if (retried.add(id)) {
                    resolver.invalidate(id)
                    player.prepare()
                    player.play()
                } else if (player.hasNextMediaItem() && ++consecutiveFailures < 3) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    consecutiveFailures = 0
                    player.currentMediaItem?.mediaId?.let(retried::remove)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 预解析下一首，切歌时不用等
                if (player.hasNextMediaItem()) {
                    val next = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
                    resolver.prefetch(next)
                }
            }
        })

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // 控制器跨进程传来的 MediaItem 不带 uri，这里补回
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map(::withUri).toMutableList())
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        fun withUri(item: MediaItem): MediaItem =
            if (item.localConfiguration != null) item
            else item.buildUpon()
                .setUri("ytm://${item.mediaId}")
                .setCustomCacheKey(item.mediaId)
                .build()

        @Volatile private var simpleCache: SimpleCache? = null

        @Synchronized
        fun cache(app: TaranaApp): SimpleCache = simpleCache ?: SimpleCache(
            app.cacheDir.resolve("audio"),
            LeastRecentlyUsedCacheEvictor(1L shl 30), // 1 GB
            StandaloneDatabaseProvider(app),
        ).also { simpleCache = it }
    }
}
