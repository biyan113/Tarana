package app.tarana.pad.player

import android.util.Log
import app.tarana.pad.yt.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * videoId → 可直接播放的 googlevideo 音频地址。
 *
 * radif 在桌面上用 yt-dlp + deno 解出签名和 n 参数；Android 上换成 NewPipeExtractor
 * （纯 Java，内置 JS 引擎处理签名），YouTube 改版时升级依赖版本即可。
 * 结果按 videoId 缓存 5 小时（googlevideo 地址约 6 小时过期），同一首歌并发请求只解析一次。
 */
class StreamResolver(
    base: OkHttpClient,
    session: SessionStore? = null,
    solver: ChallengeSolver? = null,
    cacheDir: java.io.File? = null,
) {

    val http: OkHttpClient = base.newBuilder().build()
    private val signedIn = if (session != null && solver != null) SignedInStreams(http, session, PlayerJs(http, cacheDir), solver) else null

    private data class Entry(val stream: ResolvedStream, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 匿名通道撞到机器人验证后，本次运行期间优先走登录通道，省掉每首歌白试一次匿名 */
    @Volatile private var preferSignedIn = false

    init {
        initNewPipe(http)
    }

    fun resolveBlocking(videoId: String): ResolvedStream {
        cache[videoId]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.stream }
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            cache[videoId]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.stream }
            val stream = resolve(videoId)
            cache[videoId] = Entry(stream, System.currentTimeMillis())
            lastError = null
            Log.i(TAG, "$videoId via=${stream.via} itag=${stream.itag} ${stream.bitrate}k")
            return stream
        }
    }

    /**
     * 先走匿名 NewPipe；失败（尤其是“确认你不是机器人”）时回落到 TV / 嵌入式客户端通道：
     * 已登录就带 cookie（最稳），没登录也匿名试一次（部分网络下 TV 客户端匿名也能拿到流）。
     */
    private fun resolve(videoId: String): ResolvedStream {
        val fallback = signedIn
        val authed = fallback?.available == true
        if (fallback != null && preferSignedIn) {
            runCatching { return fallback.resolve(videoId, requireAuth = false) }
                .onFailure { Log.w(TAG, "fallback-first failed: ${it.message}") }
        }
        val anonError = try {
            return extractAnonymous(videoId)
        } catch (e: Exception) {
            e
        }
        val bot = isBotCheck(anonError)
        if (fallback == null) {
            lastError = if (bot) BOT_MESSAGE_LOGIN else anonError.message
            throw IOException(lastError, anonError)
        }
        Log.i(TAG, "anonymous failed (${anonError.javaClass.simpleName}), trying ${if (authed) "signed-in" else "anonymous"} TV fallback")
        return try {
            fallback.resolve(videoId, requireAuth = false).also { if (bot) preferSignedIn = true }
        } catch (e: Exception) {
            lastError = when {
                bot && !authed -> BOT_MESSAGE_LOGIN
                bot -> "$BOT_MESSAGE_SIGNED_IN\n${e.message}"
                else -> e.message
            }
            throw IOException(lastError, e)
        }
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    fun prefetch(videoId: String) {
        if (cache.containsKey(videoId)) return
        scope.launch { runCatching { resolveBlocking(videoId) } }
    }

    private fun extractAnonymous(videoId: String): ResolvedStream {
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        ex.fetchPage()
        val pick = pickBest(ex.audioStreams) ?: throw IOException("没有可用的音频流：$videoId")
        return ResolvedStream(pick.content, STREAM_UA, pick.itag, pick.averageBitrate, "anonymous")
    }

    companion object {
        private const val TTL = 5 * 60 * 60 * 1000L
        const val STREAM_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val TAG = "StreamResolver"
        const val BOT_MESSAGE_LOGIN = "YouTube 限制了当前网络 IP 的匿名播放（要求“确认你不是机器人”）。请点左下角登录 Google 账号，或切换网络 / 代理节点。"
        const val BOT_MESSAGE_SIGNED_IN = "当前网络 IP 被限制匿名播放，登录取流也失败了。请切换网络 / 代理节点后重试。"

        /** 最近一次解析失败的可读原因（播放器跨进程传错误时会丢掉 cause，所以单独存一份给界面） */
        @Volatile var lastError: String? = null

        fun isBotCheck(e: Throwable?): Boolean {
            var c = e
            while (c != null) {
                if (c is SignInConfirmNotBotException || c.message?.contains("不是机器人") == true) return true
                c = c.cause
            }
            return false
        }

        @Volatile private var inited = false

        @Synchronized
        fun initNewPipe(http: OkHttpClient) {
            if (inited) return
            NewPipe.init(OkHttpDownloader(http), Localization("en", "US"), ContentCountry("US"))
            inited = true
        }

        /**
         * 选音质：只要原始音轨（不要配音/AI 翻译轨）的直链流，码率最高者优先；
         * 同码率下 opus(webm) 优先于 m4a。
         */
        fun pickBest(streams: List<AudioStream>): AudioStream? =
            streams.asSequence()
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }
                .sortedWith(
                    compareByDescending<AudioStream> { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate / 1000 }
                        .thenByDescending { it.format?.name == "WEBMA_OPUS" || it.format?.name == "OPUS" }
                )
                .firstOrNull()
                ?: streams.firstOrNull { it.isUrl }
    }
}

/** NewPipeExtractor 的网络层，用 OkHttp 实现。 */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val b = okhttp3.Request.Builder().url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", StreamResolver.STREAM_UA)
        for ((name, values) in request.headers()) {
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }
        client.newCall(b.build()).execute().use { res ->
            if (res.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(res.code, res.message, res.headers.toMultimap(), res.body?.string(), res.request.url.toString())
        }
    }
}
