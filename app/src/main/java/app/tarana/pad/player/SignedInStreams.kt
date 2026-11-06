package app.tarana.pad.player

import app.tarana.pad.yt.CookieUtil
import app.tarana.pad.yt.JsonParser
import app.tarana.pad.yt.SessionStore
import app.tarana.pad.yt.arr
import app.tarana.pad.yt.at
import app.tarana.pad.yt.bool
import app.tarana.pad.yt.get
import app.tarana.pad.yt.num
import app.tarana.pad.yt.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/** 解析出来的流：地址 + 下载时要带的 User-Agent */
data class ResolvedStream(val url: String, val userAgent: String, val itag: Int, val bitrate: Int, val via: String)

/**
 * 登录取流：带用户 cookie 直接调 InnerTube `/player`。
 *
 * 匿名的 NewPipe 通道遇到“确认你不是机器人”（当前 IP 被限制匿名访问）时，用登录身份就能绕过——
 * radif 也是这样做的（它的 yt-dlp 总是带 cookie 解析）。
 * 客户端顺序参照 yt-dlp 的登录默认值：tv_samsung → web_embedded，两者都不需要 PO token。
 * 签名（signatureCipher）和 n 参数由 yt-dlp-ejs 求解（NewPipe 自带的解密已跟不上当前播放器）；
 * 下载 googlevideo 时用 Safari UA（见 DOWNLOAD_UA）。
 */
class SignedInStreams(
    private val http: OkHttpClient,
    private val session: SessionStore,
    private val playerJs: PlayerJs,
    private val solver: ChallengeSolver,
) {

    private data class Client(
        val key: String,
        val name: String,
        val version: String,
        val id: Int,
        val userAgent: String,
        val extra: Map<String, String> = emptyMap(),
        val embedded: Boolean = false,
    )

    private val clients = listOf(
        Client(
            "tv", "TVHTML5", "5.20260707", 7,
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
            mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0"),
        ),
        Client(
            "web_embedded", "WEB_EMBEDDED_PLAYER", "2.20260708.00.00", 56,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)",
            embedded = true,
        ),
    )

    val available: Boolean get() = sapisid(session.cookie()) != null

    /** 依次试各个客户端，返回第一个拿到音频流的；全部失败抛出最后的原因。 */
    fun resolve(videoId: String, requireAuth: Boolean = true): ResolvedStream {
        if (requireAuth && !available) throw IOException("未登录")
        val script = playerJs.get()
        val reasons = mutableListOf<String>()
        for (c in clients) {
            try {
                val json = player(c, videoId, script.sts)
                val status = json.at("playabilityStatus", "status").str
                if (status != "OK") {
                    reasons += "${c.key}: $status ${json.at("playabilityStatus", "reason").str.orEmpty()}".trim()
                    continue
                }
                pick(json, c, script)?.let { return warmUp(it) }
                reasons += "${c.key}: 无音频流"
            } catch (e: Exception) {
                reasons += "${c.key}: ${e.message}"
            }
        }
        throw IOException("登录取流失败（${reasons.joinToString("；")}）")
    }

    @Volatile private var webVisitor: String? = null

    /** 没有 visitorData 时 TV 客户端会回 “The page needs to be reloaded”；从 youtube.com 首页取一个 */
    private fun visitorData(): String? = session.visitorData ?: webVisitor ?: runCatching {
        val req = Request.Builder().url("$ORIGIN/").header("User-Agent", StreamResolver.STREAM_UA).build()
        http.newCall(req).execute().use { r -> Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(r.body?.string().orEmpty())?.groupValues?.get(1) }
    }.getOrNull()?.also { webVisitor = it }

    private fun player(c: Client, videoId: String, sts: Int?): JsonElement {
        val visitor = visitorData()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", c.name)
                    put("clientVersion", c.version)
                    put("userAgent", c.userAgent)
                    put("hl", "en")
                    put("timeZone", "UTC")
                    put("utcOffsetMinutes", 0)
                    c.extra.forEach { (k, v) -> put(k, v) }
                    visitor?.let { put("visitorData", it) }
                }
                // 嵌入式播放器要求 embedUrl 是任意非 YouTube 页面（同 yt-dlp）
                if (c.embedded) putJsonObject("thirdParty") { put("embedUrl", EMBED_URL) }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    sts?.let { put("signatureTimestamp", it) }
                }
            }
        }
        val rb = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", c.userAgent)
            .header("X-YouTube-Client-Name", c.id.toString())
            .header("X-YouTube-Client-Version", c.version)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", if (c.embedded) EMBED_URL else "$ORIGIN/")
        visitor?.let { rb.header("X-Goog-Visitor-Id", it) }
        val cookie = session.cookie()
        if (cookie.isNotEmpty()) {
            rb.header("Cookie", cookie)
            authorization(cookie)?.let { rb.header("Authorization", it) }
            rb.header("X-Goog-AuthUser", "0")
            session.pageId?.let { rb.header("X-Goog-PageId", it) }
        }
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            return JsonParser.parseToJsonElement(text)
        }
    }

    private fun pick(json: JsonElement, c: Client, script: PlayerScript): ResolvedStream? {
        val formats = json.at("streamingData", "adaptiveFormats").arr
            .filter { it["mimeType"].str?.startsWith("audio/") == true }
            // 只要原始音轨，不要配音 / AI 翻译轨
            .filter { f -> f["audioTrack"] == null || f.at("audioTrack", "audioIsDefault").bool != false }
        // 271k Opus（Premium，774）> 普通 Opus（251）> 其他，按码率排
        val best = formats.sortedWith(
            compareByDescending<JsonElement> { it["itag"].num == 774L }
                .thenByDescending { it["bitrate"].num ?: 0 }
                .thenByDescending { it["mimeType"].str?.contains("opus") == true }
        ).firstOrNull() ?: return null

        // 地址可能带 signatureCipher（要解 sig），n 参数总要解（不解会被限速到接近播放码率）
        var sigChallenge: String? = null
        var sp = "sig"
        val base = best["url"].str ?: run {
            val cipher = best["signatureCipher"].str ?: best["cipher"].str ?: return null
            val q = ("https://x/?$cipher").toHttpUrl()
            sigChallenge = q.queryParameter("s") ?: return null
            sp = q.queryParameter("sp") ?: "signature"
            q.queryParameter("url") ?: return null
        }
        val nChallenge = base.toHttpUrl().queryParameter("n")
        val solved = solver.solve(script, listOfNotNull(sigChallenge), listOfNotNull(nChallenge))
        val b = base.toHttpUrl().newBuilder()
        sigChallenge?.let { b.setQueryParameter(sp, solved[it] ?: throw IOException("sig 未解出")) }
        nChallenge?.let { b.setQueryParameter("n", solved[it] ?: throw IOException("n 未解出")) }
        var url = b.build().toString()
        // ChunkedDataSource 依赖 clen 做分段；少数响应地址里没有，就用 contentLength 补上
        val clen = best["contentLength"].str
        if (clen != null && "clen=" !in url) url = url.toHttpUrl().newBuilder().addQueryParameter("clen", clen).build().toString()
        return ResolvedStream(url, DOWNLOAD_UA, best["itag"].num?.toInt() ?: 0, ((best["bitrate"].num ?: 0) / 1000).toInt(), c.key)
    }

    /**
     * 实测（2026-11）：新解出的地址，开头一两秒内的请求会 403，之后同一地址就稳定 200（与 UA 无关）。
     * 交给播放器之前先发 1 KB 的探测请求“预热”，最多 5 次、逐次退避（平板上通常 1～4 次通过）。
     * 注意 range=0-0（1 字节）会一直 403，所以用 0-1023。
     */
    private fun warmUp(s: ResolvedStream): ResolvedStream {
        var last = 0
        for (attempt in 0 until 5) {
            if (attempt > 0) Thread.sleep(300L * attempt)
            val req = Request.Builder().url(s.url.toHttpUrl().newBuilder().setQueryParameter("range", "0-1023").build())
                .header("User-Agent", s.userAgent).build()
            last = http.newCall(req).execute().use { it.code }
            android.util.Log.i("SignedInStreams", "warm-up #$attempt itag=${s.itag} -> $last")
            if (last in 200..299) return s
        }
        throw IOException("流地址预热失败（HTTP $last）")
    }

    private fun sapisid(cookie: String) = CookieUtil.get(cookie, "SAPISID") ?: CookieUtil.get(cookie, "__Secure-3PAPISID")

    /** 与 yt-dlp 一致：SAPISIDHASH + SAPISID1PHASH + SAPISID3PHASH */
    private fun authorization(cookie: String): String? {
        val ts = System.currentTimeMillis() / 1000
        fun hash(scheme: String, sid: String) = "$scheme ${ts}_${sha1("$ts $sid $ORIGIN")}"
        val parts = listOfNotNull(
            sapisid(cookie)?.let { hash("SAPISIDHASH", it) },
            CookieUtil.get(cookie, "__Secure-1PAPISID")?.let { hash("SAPISID1PHASH", it) },
            CookieUtil.get(cookie, "__Secure-3PAPISID")?.let { hash("SAPISID3PHASH", it) },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val ORIGIN = "https://www.youtube.com"
        private const val EMBED_URL = "https://www.reddit.com/"

        /**
         * 下载这些地址用的 UA。实测（2026-11）：TV 的 UA 和 Chrome 桌面 UA 都会被 googlevideo 403，
         * Safari UA 稳定 200。
         */
        const val DOWNLOAD_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15"
    }
}
