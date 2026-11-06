package app.tarana.pad.yt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs

/**
 * 歌词：先用 YouTube Music 自己的（按 videoId，不会张冠李戴），再回落到 LRCLIB。
 * 移植自 radif `lyrics/ytmusic.ts`、`lyrics/lrclib.ts`、`lyrics/parse-lrc.ts`。
 */
class LyricsRepo(private val yt: YtMusic) {

    private val http get() = yt.api.http
    private val cache = object : LinkedHashMap<String, Lyrics?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 64
    }

    suspend fun lyrics(track: ShelfItem): Lyrics? {
        synchronized(cache) { if (cache.containsKey(track.id)) return cache[track.id] }
        val ytm = runCatching { fetchYtMusic(track.id) }.getOrNull()
        // YTM 有同步歌词就直接用；只有纯文本时再看 LRCLIB 能否给出同步版
        val result = if (ytm is Lyrics.Timed) ytm else {
            val lr = runCatching { fetchLrclib(track) }.getOrNull()
            when {
                lr is Lyrics.Timed -> lr
                ytm != null -> ytm
                else -> lr
            }
        }
        synchronized(cache) { cache[track.id] = result }
        return result
    }

    // ---------- YouTube Music ----------

    /**
     * 两跳：/next 找到 MUSIC_PAGE_TYPE_TRACK_LYRICS 标签页的 browseId，
     * 再以 ANDROID_MUSIC 客户端匿名 /browse —— 只有移动端上下文才返回逐行时间轴。
     */
    private suspend fun fetchYtMusic(videoId: String): Lyrics? {
        val next = yt.api.next(mapOf("videoId" to videoId))
        var browseId: String? = null
        for (tab in next.collect("tabRenderer")) {
            val be = tab.at("endpoint", "browseEndpoint") ?: continue
            if (be.pageType() != "MUSIC_PAGE_TYPE_TRACK_LYRICS") continue
            if (tab["unselectable"].bool == true) return null
            browseId = be["browseId"].str
            break
        }
        browseId ?: return null
        val body = buildJsonObject {
            put("browseId", browseId)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", "7.21.50")
                    put("androidSdkVersion", 34)
                    put("hl", yt.api.hl)
                    put("gl", yt.api.gl)
                }
            }
        }
        val json = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.21.50")
                .header("Origin", "https://music.youtube.com")
                .header("User-Agent", InnerTube.DESKTOP_UA)
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("YTM lyrics HTTP ${r.code}")
                JsonParser.parseToJsonElement(r.body!!.string())
            }
        }
        return parseTimed(json) ?: parsePlain(json)
    }

    private fun parseTimed(json: JsonElement): Lyrics? {
        val rows = json.collect("timedLyricsData").firstOrNull { it.arr.isNotEmpty() }?.arr ?: return null
        val lines = rows.mapNotNull { row ->
            val start = row.at("cueRange", "startTimeMilliseconds").num ?: return@mapNotNull null
            val text = row["lyricLine"].str.orEmpty().trim()
            LyricLine(start, row.at("cueRange", "endTimeMilliseconds").num, if (text == "♪") "" else text)
        }.sortedBy { it.startMs }
        if (lines.isNotEmpty()) return Lyrics.Timed(lines, "YouTube Music")
        val text = rows.map { it["lyricLine"].str.orEmpty().trim() }.filter { it.isNotEmpty() && it != "♪" }.joinToString("\n")
        return if (text.isNotBlank()) Lyrics.Plain(text, "YouTube Music") else null
    }

    private fun parsePlain(json: JsonElement): Lyrics? {
        for (shelf in json.collect("musicDescriptionShelfRenderer")) {
            val text = shelf["description"].runsText().trim()
            if (text.isNotEmpty()) return Lyrics.Plain(text, "YouTube Music")
        }
        return null
    }

    // ---------- LRCLIB ----------

    private suspend fun lrclibJson(path: String, params: Map<String, String?>): JsonElement? = withContext(Dispatchers.IO) {
        val url = "https://lrclib.net/api/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("User-Agent", "Tarana (Android; port of github.com/yuvrajangadsingh/radif)").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) error("LRCLIB HTTP ${r.code}")
            JsonParser.parseToJsonElement(r.body!!.string())
        }
    }

    private suspend fun fetchLrclib(t: ShelfItem): Lyrics? {
        val title = cleanTitle(t.title)
        val artist = t.artists.firstOrNull()?.name ?: t.subtitle?.split(",", "&", "•")?.firstOrNull()?.trim()
        val dur = t.duration
        val got = if (artist != null) runCatching {
            lrclibJson("get", mapOf("track_name" to title, "artist_name" to artist, "duration" to dur?.toString()))
        }.getOrNull() else null
        val candidates = mutableListOf<JsonElement>()
        got?.let(candidates::add)
        runCatching { lrclibJson("search", mapOf("track_name" to title, "artist_name" to artist)) }.getOrNull()?.arr?.let(candidates::addAll)
        if (candidates.isEmpty()) runCatching { lrclibJson("search", mapOf("q" to "$title ${artist.orEmpty()}")) }.getOrNull()?.arr?.let(candidates::addAll)

        val nt = norm(title)
        val ok = candidates.filter { c ->
            val cd = c["duration"].num ?: (c["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
            val durOk = dur == null || cd == null || abs(cd - dur) <= 4
            val ct = norm(c["trackName"].str.orEmpty())
            durOk && (ct.contains(nt) || nt.contains(ct)) && c["instrumental"].bool != true
        }
        val pick = ok.firstOrNull { !it["syncedLyrics"].str.isNullOrBlank() } ?: ok.firstOrNull { !it["plainLyrics"].str.isNullOrBlank() }
            ?: return null
        pick["syncedLyrics"].str?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = parseLrc(lrc)
            if (lines.isNotEmpty()) return Lyrics.Timed(lines, "LRCLIB")
        }
        return pick["plainLyrics"].str?.takeIf { it.isNotBlank() }?.let { Lyrics.Plain(it.trim(), "LRCLIB") }
    }

    companion object {
        private val NOISE = Regex(
            "\\s*[(\\[【]([^)\\]】]*(official|video|audio|lyric|lyrics|mv|m/v|hd|4k|remaster|visualizer|官方|歌词|動態|完整版)[^)\\]】]*)[)\\]】]",
            RegexOption.IGNORE_CASE,
        )

        fun cleanTitle(t: String): String = t.replace(NOISE, "").replace(Regex("\\s*-\\s*Topic$"), "").trim()

        fun norm(s: String) = s.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

        /** [mm:ss.xx] LRC → 行；支持一行多个时间戳和 [offset:+ms] */
        fun parseLrc(lrc: String): List<LyricLine> {
            val ts = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
            val offset = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE).find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val out = mutableListOf<LyricLine>()
            for (raw in lrc.lines()) {
                val ms = ts.findAll(raw).toList()
                if (ms.isEmpty()) continue
                val text = raw.substring(ms.last().range.last + 1).trim()
                for (m in ms) {
                    val mm = m.groupValues[1].toLong()
                    val ss = m.groupValues[2].toLong()
                    val frac = m.groupValues[3].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toLong() ?: 0
                    out += LyricLine(maxOf(0, mm * 60_000 + ss * 1000 + frac - offset), null, text)
                }
            }
            out.sortBy { it.startMs }
            return out.mapIndexed { i, l -> l.copy(endMs = out.getOrNull(i + 1)?.startMs) }
        }
    }
}
