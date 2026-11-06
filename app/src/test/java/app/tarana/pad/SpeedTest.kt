package app.tarana.pad

import app.tarana.pad.player.StreamResolver
import app.tarana.pad.yt.InnerTube
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpeedTest {
    @Test fun speed() {
        assumeTrue(System.getProperty("live") == "1")
        val url = StreamResolver(InnerTube.defaultHttp).resolveBlocking("oRdxUFDoQe0").url
        fun get(range: String): Pair<Int, Long> {
            val t = System.currentTimeMillis()
            val req = okhttp3.Request.Builder().url(url).header("Range", range).header("User-Agent", StreamResolver.STREAM_UA).build()
            val n = InnerTube.defaultHttp.newCall(req).execute().use { it.body!!.bytes().size }
            return n to System.currentTimeMillis() - t
        }
        println("open-ended: " + get("bytes=0-"))
        println("chunk 1MB: " + get("bytes=1000000-1999999"))
    }
}
