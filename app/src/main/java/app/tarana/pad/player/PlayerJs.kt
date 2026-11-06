package app.tarana.pad.player

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** YouTube 播放器脚本（base.js）：签名时间戳 sts 和 sig / n 的解密都依赖它。 */
class PlayerScript(val id: String, val sts: Int, val code: String)

/**
 * 解 sig / n 挑战。Android 上由 WebView（V8）跑 yt-dlp-ejs，主机测试里由 node 跑同一份脚本。
 * 返回 challenge → 结果；任一类型失败抛异常。
 */
interface ChallengeSolver {
    fun solve(player: PlayerScript, sig: List<String>, n: List<String>): Map<String, String>
}

/**
 * 获取并缓存当前播放器脚本：先从 iframe_api 拿播放器 id（6 小时刷新一次），
 * 再下载 main 变体的 base.js（约 3 MB，按 id 落盘缓存，换版本才重新下载）。
 * 变体选择与 yt-dlp 一致：所有客户端都用 player_ias.vflset/en_US/base.js。
 */
class PlayerJs(private val http: OkHttpClient, private val dir: File?) {

    @Volatile private var current: PlayerScript? = null
    @Volatile private var checkedAt = 0L

    @Synchronized
    fun get(): PlayerScript {
        val now = System.currentTimeMillis()
        current?.takeIf { now - checkedAt < ID_TTL }?.let { return it }
        val id = fetchPlayerId()
        checkedAt = now
        current?.takeIf { it.id == id }?.let { return it }
        val code = dir?.resolve("player-$id.js")?.takeIf { it.exists() }?.readText() ?: download(id).also { saveToDisk(id, it) }
        val sts = Regex("signatureTimestamp[:=](\\d+)").find(code)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("播放器脚本里找不到 signatureTimestamp")
        return PlayerScript(id, sts, code).also { current = it }
    }

    private fun fetchPlayerId(): String {
        val text = fetch("https://www.youtube.com/iframe_api").replace("\\/", "/")
        return Regex("/s/player/([0-9a-zA-Z_-]+)/").find(text)?.groupValues?.get(1)
            ?: current?.id ?: throw IOException("拿不到播放器 id")
    }

    private fun download(id: String) = fetch("https://www.youtube.com/s/player/$id/player_ias.vflset/en_US/base.js")

    private fun saveToDisk(id: String, code: String) {
        val d = dir ?: return
        d.mkdirs()
        d.listFiles { f -> f.name.startsWith("player-") }?.forEach { it.delete() } // 只留最新一版
        d.resolve("player-$id.js").writeText(code)
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", StreamResolver.STREAM_UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: $url")
            return r.body!!.string()
        }
    }

    companion object {
        private const val ID_TTL = 6 * 60 * 60 * 1000L
    }
}
