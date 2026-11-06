package app.tarana.pad

import app.tarana.pad.player.PlayerJs
import app.tarana.pad.player.SignedInStreams
import app.tarana.pad.yt.InnerTube
import app.tarana.pad.yt.MemorySessionStore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 登录通道的完整链路：TV 客户端 /player → 下载 base.js → ejs 解 sig/n → 下载 1 MB。
 * 主机上没有用户 cookie，用匿名身份跑同一套代码（TV 客户端匿名也能返回加密地址）。
 */
class SignedInStreamsTest {
    @Test fun resolveAndDownload() {
        assumeTrue(System.getProperty("live") == "1")
        val http = InnerTube.defaultHttp
        val cache = File(System.getProperty("java.io.tmpdir"), "tarana-player-cache")
        val s = SignedInStreams(http, MemorySessionStore(), PlayerJs(http, cache), NodeSolver(File("src/main/assets/ejs")))
        var ok = 0
        for (id in listOf("oRdxUFDoQe0", "tdS3ijuqxW4", "dQw4w9WgXcQ")) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { s.resolve(id, requireAuth = false) }.getOrElse { println("$id FAILED ${it.message}"); continue }
            println("$id via=${r.via} itag=${r.itag} ${r.bitrate}k resolved in ${System.currentTimeMillis() - t0}ms clen=${"clen=" in r.url}")
            for ((label, ua) in listOf(
                "resolved" to r.userAgent,
            )) {
                val req = okhttp3.Request.Builder().url(r.url + "&range=0-999999").header("User-Agent", ua).build()
                val t1 = System.currentTimeMillis()
                http.newCall(req).execute().use {
                    val n = it.body!!.bytes().size
                    println("  [$label] GET ${it.code} $n bytes in ${System.currentTimeMillis() - t1}ms")
                    if (label == "resolved" && it.code == 200 && n == 1_000_000) ok++
                }
            }
        }
        assertTrue("至少两首要能下载", ok >= 2)
    }
}
