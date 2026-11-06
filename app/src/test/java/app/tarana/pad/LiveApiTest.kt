package app.tarana.pad

import app.tarana.pad.player.StreamResolver
import app.tarana.pad.yt.InnerTube
import app.tarana.pad.yt.ItemKind
import app.tarana.pad.yt.LyricsRepo
import app.tarana.pad.yt.Lyrics
import app.tarana.pad.yt.MemorySessionStore
import app.tarana.pad.yt.YtMusic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 连真实接口的冒烟测试：./gradlew :app:testDebugUnitTest -Plive */
class LiveApiTest {
    private val yt = YtMusic(InnerTube(MemorySessionStore()))

    init { assumeTrue(System.getProperty("live") == "1") }

    @Test fun endToEnd() = runBlocking {
        val r = yt.search("周杰伦 晴天")
        println("top=${r.top?.kind} ${r.top?.title}")
        r.shelves.forEach { s -> println("[${s.title}] " + s.items.take(4).joinToString(" | ") { "${it.kind}:${it.title}/${it.artistLine}/${it.duration}" }) }
        val song = (listOfNotNull(r.top) + r.shelves.flatMap { it.items }).first { it.kind == ItemKind.SONG }

        val home = yt.home()
        println("home shelves=${home.shelves.size} chips=${home.chips.map { it.title }} next=${home.nextCursor != null}")
        home.shelves.take(5).forEach { println("  [${it.title}] ${it.display} ${it.items.size} e.g. ${it.items.first().kind}:${it.items.first().title}") }
        assertTrue(home.shelves.isNotEmpty())
        home.nextCursor?.let { println("home page2 shelves=" + yt.home(it).shelves.size) }

        val albumId = r.shelves.flatMap { it.items }.first { it.kind == ItemKind.ALBUM }.id
        val album = yt.album(albumId)
        println("album ${album.title} ${album.artists} ${album.year} tracks=${album.tracks.size} pl=${album.playlistId} first=${album.tracks.firstOrNull()}")
        assertTrue(album.tracks.isNotEmpty())

        val artistId = r.shelves.flatMap { it.items }.first { it.kind == ItemKind.ARTIST }.id
        val artist = yt.artist(artistId)
        println("artist ${artist.name} aud=${artist.audience} shuffle=${artist.shufflePlaylistId} radio=${artist.radioPlaylistId} shelves=${artist.shelves.map { "${it.title}(${it.items.size},${it.display})" }}")

        val pl = yt.search("周杰伦", YtMusic.Filter.PLAYLISTS).shelves.flatMap { it.items }.first { it.kind == ItemKind.PLAYLIST }
        val page = yt.playlist(pl.id)
        println("playlist ${page.title} by ${page.author} meta=${page.meta} tracks=${page.tracks.size} cont=${page.continuation != null}")
        page.continuation?.let { val (more, n) = yt.playlistMore(it); println("  more=${more.size} next=${n != null}") }

        val (radio, rc) = yt.radio(song.id)
        println("radio ${radio.size} cont=${rc != null} ${radio.take(3).map { it.title }}")
        assertTrue(radio.size > 5)

        val moods = yt.moods()
        println("moods shelves=${moods.shelves.map { "${it.title}(${it.items.size},${it.display})" }}")

        val lyr = LyricsRepo(yt).lyrics(song)
        println("lyrics ${lyr?.source} ${when (lyr) { is Lyrics.Timed -> "timed ${lyr.lines.size} ${lyr.lines.take(3)}"; is Lyrics.Plain -> "plain ${lyr.text.take(60)}"; null -> "none" }}")
        println("sugg " + yt.suggestions("jay ch"))

        val t0 = System.currentTimeMillis()
        val url = StreamResolver(InnerTube.defaultHttp).resolveBlocking(song.id).url
        println("stream in ${System.currentTimeMillis() - t0}ms: ${url.take(120)}")
        val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-99999").header("User-Agent", StreamResolver.STREAM_UA).build()
        InnerTube.defaultHttp.newCall(req).execute().use { println("stream GET ${it.code} ${it.header("Content-Type")} len=${it.body?.bytes()?.size}") ; assertTrue(it.code in 200..206) }
        val req2 = okhttp3.Request.Builder().url(url).header("Range", "bytes=2000000-2099999").header("User-Agent", StreamResolver.STREAM_UA).build()
        InnerTube.defaultHttp.newCall(req2).execute().use { println("stream mid GET ${it.code} len=${it.body?.bytes()?.size}") }
    }
}
