package app.tarana.pad.yt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 登录态存储（由 Android 层用 SharedPreferences 实现；JVM 测试里可以用内存实现）。
 * 对应 radif 里 Rust 侧的加密 cookie jar。
 */
interface SessionStore {
    /** music.youtube.com 的完整 Cookie 头，未登录返回空串 */
    fun cookie(): String
    /** 把响应里的 Set-Cookie 合并回去（Google 会轮换 SIDCC 等，不回写会被判定盗用而吊销会话） */
    fun mergeSetCookies(lines: List<String>)
    var visitorData: String?
    /** 品牌频道的 pageId，可空 */
    var pageId: String?
}

class MemorySessionStore(private var cookies: String = "") : SessionStore {
    override fun cookie() = cookies
    override fun mergeSetCookies(lines: List<String>) {
        cookies = CookieUtil.merge(cookies, lines)
    }
    override var visitorData: String? = null
    override var pageId: String? = null
}

object CookieUtil {
    /** 合并 Set-Cookie 到 Cookie 头字符串；Max-Age=0 或过期则删除。 */
    fun merge(cookieHeader: String, setCookies: List<String>): String {
        val map = LinkedHashMap<String, String>()
        cookieHeader.split(";").map { it.trim() }.filter { it.contains('=') }.forEach {
            val i = it.indexOf('=')
            map[it.substring(0, i)] = it.substring(i + 1)
        }
        for (line in setCookies) {
            val parts = line.split(";").map { it.trim() }
            val kv = parts.firstOrNull() ?: continue
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val name = kv.substring(0, i)
            val value = kv.substring(i + 1)
            val expired = parts.drop(1).any {
                it.equals("Max-Age=0", ignoreCase = true) ||
                    (it.startsWith("Expires=", true) && it.contains("1970"))
            }
            if (expired || value.isEmpty()) map.remove(name) else map[name] = value
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun get(cookieHeader: String, name: String): String? =
        cookieHeader.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

class InnerTubeException(val status: Int, msg: String) : Exception(msg)

/**
 * YouTube Music 的 InnerTube 客户端（WEB_REMIX），对应 radif 的 `innertube/shared.ts`。
 */
class InnerTube(
    val session: SessionStore,
    val http: OkHttpClient = defaultHttp,
    var hl: String = "zh-CN",
    var gl: String = "US",
) {
    companion object {
        const val WEB_REMIX_VERSION = "1.20260510.02.00"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val ORIGIN = "https://music.youtube.com"
        private val JSON_TYPE = "application/json".toMediaType()

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val signedIn: Boolean get() = sapisid(session.cookie()) != null

    private fun sapisid(cookie: String): String? =
        CookieUtil.get(cookie, "__Secure-3PAPISID") ?: CookieUtil.get(cookie, "SAPISID")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun context(): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", WEB_REMIX_VERSION)
            put("hl", hl)
            put("gl", gl)
            put("platform", "DESKTOP")
            put("userAgent", "$DESKTOP_UA,gzip(gfe)")
            put("originalUrl", "$ORIGIN/")
            session.visitorData?.let { put("visitorData", it) }
        }
        putJsonObject("user") { put("lockedSafetyMode", false) }
        putJsonObject("request") { put("useSsl", true) }
    }

    suspend fun post(endpoint: String, body: Map<String, Any?>, auth: Boolean = true): JsonElement =
        withContext(Dispatchers.IO) {
            val sep = if (endpoint.contains('?')) "&" else "?"
            val url = "$ORIGIN/youtubei/v1/$endpoint${sep}prettyPrint=false"
            val payload = buildJsonObject {
                put("context", context())
                for ((k, v) in body) when (v) {
                    null -> Unit
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    is JsonElement -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
            val rb = Request.Builder().url(url)
                .post(payload.toString().toRequestBody(JSON_TYPE))
                .header("Content-Type", "application/json")
                .header("User-Agent", DESKTOP_UA)
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
                .header("Origin", ORIGIN)
                .header("X-Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
                .header("Accept-Language", "$hl,en;q=0.8")
                .header("X-Goog-AuthUser", "0")
            session.visitorData?.let { rb.header("X-Goog-Visitor-Id", it) }
            if (auth) {
                val cookie = session.cookie()
                val sid = sapisid(cookie)
                if (cookie.isNotEmpty() && sid != null) {
                    rb.header("Cookie", cookie)
                    val ts = System.currentTimeMillis() / 1000
                    rb.header("Authorization", "SAPISIDHASH ${ts}_${sha1("$ts $sid $ORIGIN")}")
                    session.pageId?.let { rb.header("X-Goog-PageId", it) }
                }
            }
            http.newCall(rb.build()).execute().use { res -> handle(endpoint, res, auth) }
        }

    private fun handle(endpoint: String, res: Response, auth: Boolean): JsonElement {
        if (auth) {
            val sc = res.headers("Set-Cookie")
            if (sc.isNotEmpty() && session.cookie().isNotEmpty()) session.mergeSetCookies(sc)
        }
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw InnerTubeException(res.code, "InnerTube $endpoint → HTTP ${res.code}: ${text.take(200)}")
        val json = JsonParser.parseToJsonElement(text)
        json.at("responseContext", "visitorData").str?.takeIf { it.isNotEmpty() }?.let { session.visitorData = it }
        return json
    }

    suspend fun browse(browseId: String, params: String? = null) =
        post("browse", mapOf("browseId" to browseId, "params" to params))

    suspend fun browseContinuation(token: String) = post("browse", mapOf("continuation" to token))

    suspend fun search(query: String, params: String? = null) =
        post("search", mapOf("query" to query, "params" to params))

    suspend fun next(body: Map<String, Any?>) = post("next", body)
}
