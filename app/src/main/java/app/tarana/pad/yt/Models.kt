package app.tarana.pad.yt

enum class ItemKind { SONG, VIDEO, ALBUM, ARTIST, PLAYLIST, CATEGORY }

data class Thumb(val url: String, val width: Int = 0, val height: Int = 0)

data class ArtistRef(val id: String?, val name: String)

data class ShelfItem(
    val kind: ItemKind,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnails: List<Thumb> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    /** 秒 */
    val duration: Int? = null,
    val explicit: Boolean = false,
    val round: Boolean = false,
    /** 分类卡片的 browse params */
    val categoryParams: String? = null,
    /** 分类卡片的颜色 0xRRGGBB */
    val tint: Long? = null,
    /** 歌单卡片自带的可播放 videoId */
    val playableVideoId: String? = null,
) {
    val isPlayable get() = kind == ItemKind.SONG || kind == ItemKind.VIDEO

    /** 选一张接近 [px] 宽度的缩略图，并把 Google 图片 URL 改到目标尺寸。 */
    fun thumb(px: Int = 544): String? = bestThumb(thumbnails, px)

    /** 艺人名；没有结构化艺人时从副标题里挑，去掉“视频 / 歌曲”等类型词、时长和播放量 */
    val artistLine: String
        get() = artists.joinToString(", ") { it.name }.ifBlank { cleanByline(subtitle.orEmpty()) }
}

private val BYLINE_NOISE = Regex(
    "^(\\d{1,2}:)?\\d{1,2}:\\d{2}$|^(歌曲|视频|影片|單曲|单曲|专辑|專輯|播放列表|分集|Song|Video|Single|Album|EP|Playlist|Episode)$|(次观看|次觀看|views|plays|播放)$|^(还剩|還剩|剩余|剩餘)|(remaining|left)$|正在观看$|正在觀看$|watching$",
    RegexOption.IGNORE_CASE,
)

fun cleanByline(s: String): String =
    s.split("•").map { it.trim() }.filter { it.isNotEmpty() && !BYLINE_NOISE.containsMatchIn(it) }.joinToString(" • ")

fun bestThumb(list: List<Thumb>, px: Int): String? {
    if (list.isEmpty()) return null
    val pick = list.firstOrNull { it.width >= px } ?: list.maxByOrNull { it.width } ?: list.last()
    return upscaleThumb(pick.url, px)
}

/** lh3.googleusercontent.com 的 =w120-h120 形式可以直接改尺寸。 */
fun upscaleThumb(url: String, px: Int): String {
    val m = Regex("=w\\d+-h\\d+").find(url) ?: return url
    return url.replaceRange(m.range, "=w$px-h$px")
}

data class ShelfMore(val browseId: String, val params: String?, val pageType: String?)

enum class ShelfDisplay { LIST, CARD, GRID }

data class Shelf(
    val id: String,
    val title: String,
    val items: List<ShelfItem>,
    val display: ShelfDisplay,
    val more: ShelfMore? = null,
)

data class Page(val shelves: List<Shelf>, val nextCursor: String? = null, val chips: List<Chip> = emptyList())

data class Chip(val title: String, val params: String?, val selected: Boolean)

data class SearchResults(
    val query: String,
    val top: ShelfItem? = null,
    val shelves: List<Shelf> = emptyList(),
)

data class AlbumPage(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val year: String?,
    val meta: String?,
    val thumbnails: List<Thumb>,
    val tracks: List<ShelfItem>,
    val playlistId: String?,
)

data class ArtistPage(
    val id: String,
    val name: String,
    val description: String?,
    val audience: String?,
    val thumbnails: List<Thumb>,
    val shufflePlaylistId: String?,
    val radioPlaylistId: String?,
    val shelves: List<Shelf>,
)

data class PlaylistPage(
    val id: String,
    val title: String,
    val author: String?,
    val meta: String?,
    val description: String?,
    val thumbnails: List<Thumb>,
    val tracks: List<ShelfItem>,
    val continuation: String?,
)

data class Account(val name: String, val handle: String?, val photo: String?)

/** 一行歌词。start/end 单位为毫秒。 */
data class LyricLine(val startMs: Long, val endMs: Long?, val text: String)

sealed interface Lyrics {
    val source: String

    data class Timed(val lines: List<LyricLine>, override val source: String) : Lyrics
    data class Plain(val text: String, override val source: String) : Lyrics
}
