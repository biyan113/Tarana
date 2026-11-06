package app.tarana.pad.yt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * InnerTube 渲染器 → 领域模型。逐个移植自 radif `innertube/shared.ts`。
 * 分类全部依据 pageType / musicVideoType，不依赖界面语言里的“歌曲/视频”字样。
 */
object Parsers {

    fun thumbnails(node: JsonElement?): List<Thumb> {
        if (node == null) return emptyList()
        val arr = node["thumbnails"] ?: node.at("thumbnail", "thumbnails") ?: return emptyList()
        return arr.arr.mapNotNull { t ->
            val url = t["url"].str ?: return@mapNotNull null
            Thumb(fixUrl(url), t["width"].num?.toInt() ?: 0, t["height"].num?.toInt() ?: 0)
        }
    }

    private fun fixUrl(u: String) = if (u.startsWith("//")) "https:$u" else u

    /** 在子树里找第一组 thumbnails（用户歌单的拼贴封面渲染器很多种）。 */
    fun deepThumbnails(node: JsonElement?): List<Thumb> {
        if (node == null) return emptyList()
        for (t in node.collect("thumbnails")) {
            val mapped = thumbnails(JsonObject(mapOf("thumbnails" to t)))
            if (mapped.isNotEmpty()) return mapped
        }
        return emptyList()
    }

    fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(":").map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    fun explicit(raw: JsonElement?): Boolean {
        for (bucket in listOf(raw["badges"], raw["subtitleBadges"])) for (b in bucket.arr) {
            val r = b["musicInlineBadgeRenderer"] ?: continue
            if (r.at("icon", "iconType").str?.contains("EXPLICIT") == true) return true
        }
        return false
    }

    fun pageKind(pageType: String?): ItemKind? = when {
        pageType == null -> null
        pageType.contains("ARTIST") -> ItemKind.ARTIST
        pageType.contains("ALBUM") -> ItemKind.ALBUM
        pageType.contains("PLAYLIST") || pageType.contains("PODCAST_SHOW") -> ItemKind.PLAYLIST
        else -> null
    }

    private fun kindForVideoType(t: String?): ItemKind =
        if (t == null || t == "MUSIC_VIDEO_TYPE_ATV") ItemKind.SONG else ItemKind.VIDEO

    private fun menuNav(raw: JsonElement?): Pair<String?, String?> {
        var album: String? = null
        var artist: String? = null
        for (it in raw.at("menu", "menuRenderer", "items").arr) {
            val b = it.at("menuNavigationItemRenderer", "navigationEndpoint", "browseEndpoint") ?: continue
            val id = b["browseId"].str ?: continue
            val pt = b.pageType() ?: continue
            if (pt.contains("ALBUM") && album == null) album = id
            else if (pt.contains("ARTIST") && artist == null) artist = id
        }
        return album to artist
    }

    private data class RunInfo(val artists: List<ArtistRef>, val album: String?, val albumId: String?)

    private fun parseRuns(runs: List<JsonElement>): RunInfo {
        val artists = mutableListOf<ArtistRef>()
        var album: String? = null
        var albumId: String? = null
        for (run in runs) {
            val be = run.at("navigationEndpoint", "browseEndpoint") ?: continue
            val id = be["browseId"].str ?: continue
            val pt = be.pageType() ?: continue
            if (pt.contains("ARTIST")) artists += ArtistRef(id, run["text"].str.orEmpty())
            else if (pt.contains("ALBUM")) { album = run["text"].str; albumId = id }
        }
        return RunInfo(artists, album, albumId)
    }

    /** musicTwoRowItemRenderer：轮播卡片 */
    fun twoRow(raw: JsonElement): ShelfItem? {
        val ep = raw["navigationEndpoint"]
        val watch = ep["watchEndpoint"]
        val browse = ep["browseEndpoint"]
        val title = raw["title"].runsText()
        val subtitle = raw["subtitle"].runsText()
        val runInfo = parseRuns(raw.at("subtitle", "runs").arr)

        var thumbs = thumbnails(
            raw.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail")
                ?: raw.at("thumbnail", "musicThumbnailRenderer", "thumbnail") ?: raw["thumbnail"]
        )
        if (thumbs.isEmpty()) thumbs = deepThumbnails(raw["thumbnailRenderer"]).ifEmpty { deepThumbnails(raw["thumbnail"]) }

        val overlayVideo = (raw["thumbnailOverlay"] ?: raw["overlay"]).at(
            "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
            "playNavigationEndpoint", "watchEndpoint", "videoId",
        ).str

        var kind = ItemKind.SONG
        var id: String? = null
        val vid = watch["videoId"].str
        if (vid != null) {
            id = vid
            val mvt = watch.at("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str
            kind = if (mvt != null) kindForVideoType(mvt) else {
                val w = thumbs.maxByOrNull { it.width }
                if (w != null && w.height > 0 && w.width.toFloat() / w.height > 1.4f) ItemKind.VIDEO else ItemKind.SONG
            }
        } else if (browse != null) {
            val bid = browse["browseId"].str
            val mapped = pageKind(browse.pageType())
            if (bid != null && mapped != null) { id = bid; kind = mapped }
            else id = overlayVideo ?: bid?.takeIf { it.startsWith("MPED") }?.drop(4)
        }
        if (id.isNullOrEmpty()) return null

        val (menuAlbum, menuArtist) = menuNav(raw)
        val artists = runInfo.artists.ifEmpty {
            if (menuArtist != null) listOf(ArtistRef(menuArtist, subtitle)) else emptyList()
        }
        return ShelfItem(
            kind = kind, id = id, title = title, subtitle = subtitle.ifBlank { null },
            thumbnails = thumbs, artists = artists, albumId = menuAlbum ?: runInfo.albumId,
            explicit = explicit(raw), round = kind == ItemKind.ARTIST,
            playableVideoId = if (kind == ItemKind.PLAYLIST) overlayVideo else null,
        )
    }

    /** musicResponsiveListItemRenderer：列表行 */
    fun responsive(raw: JsonElement): ShelfItem? {
        val flex = raw["flexColumns"].arr
        val fixed = raw["fixedColumns"].arr
        val titleText = flex.getOrNull(0).at("musicResponsiveListItemFlexColumnRenderer", "text")
        val titleRun = titleText.at("runs", 0)
        val title = titleText.runsText()

        val artists = mutableListOf<ArtistRef>()
        var album: String? = null
        var albumId: String? = null
        for (i in 1 until flex.size) {
            val col = flex[i].at("musicResponsiveListItemFlexColumnRenderer", "text") ?: continue
            val info = parseRuns(col["runs"].arr)
            artists += info.artists
            if (info.albumId != null) { album = info.album; albumId = info.albumId }
        }

        var duration = parseDuration(fixed.getOrNull(0).at("musicResponsiveListItemFixedColumnRenderer", "text").runsText())
        if (duration == null) {
            val runs = flex.getOrNull(1).at("musicResponsiveListItemFlexColumnRenderer", "text", "runs").arr
            duration = parseDuration(runs.lastOrNull()?.get("text").str)
        }
        val (menuAlbum, _) = menuNav(raw)
        if (albumId == null) albumId = menuAlbum

        val overlayWatch = raw.at(
            "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
            "playNavigationEndpoint", "watchEndpoint",
        )
        val titleWatch = titleRun.at("navigationEndpoint", "watchEndpoint")
        val videoId = titleWatch["videoId"].str ?: overlayWatch["videoId"].str
            ?: raw.at("playlistItemData", "videoId").str ?: raw.at("navigationEndpoint", "watchEndpoint", "videoId").str
        val mvt = (titleWatch ?: overlayWatch).at("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str

        var thumbs = thumbnails(raw.at("thumbnail", "musicThumbnailRenderer", "thumbnail"))
        if (thumbs.isEmpty() && videoId != null) {
            thumbs = listOf(
                Thumb("https://i.ytimg.com/vi/$videoId/mqdefault.jpg", 320, 180),
                Thumb("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", 480, 360),
            )
        }

        val navBrowse = titleRun.at("navigationEndpoint", "browseEndpoint") ?: raw.at("navigationEndpoint", "browseEndpoint")
        val navId = navBrowse["browseId"].str
        val navKind = pageKind(navBrowse.pageType())
        val sub = artists.joinToString(", ") { it.name }
            .ifBlank { flex.getOrNull(1).at("musicResponsiveListItemFlexColumnRenderer", "text").runsText() }

        if (navId != null && navKind != null) {
            return ShelfItem(
                kind = navKind, id = navId, title = title, subtitle = sub.ifBlank { null },
                thumbnails = thumbs, round = navKind == ItemKind.ARTIST, explicit = explicit(raw),
            )
        }
        if (videoId != null) {
            return ShelfItem(
                kind = kindForVideoType(mvt), id = videoId, title = title, subtitle = sub.ifBlank { null },
                thumbnails = thumbs, artists = artists, album = album, albumId = albumId,
                duration = duration, explicit = explicit(raw),
            )
        }
        return null
    }

    /** playlistPanelVideoRenderer：/next 返回的播放队列行 */
    fun panelVideo(raw: JsonElement): ShelfItem? {
        val watch = raw.at("navigationEndpoint", "watchEndpoint")
        val videoId = watch["videoId"].str ?: return null
        val byline = raw["longBylineText"] ?: raw["shortBylineText"]
        val info = parseRuns(byline["runs"].arr)
        val (menuAlbum, _) = menuNav(raw)
        val mvt = watch.at("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str
        val fallback = byline.runsText().split("•").firstOrNull()?.trim()
        return ShelfItem(
            kind = kindForVideoType(mvt), id = videoId, title = raw["title"].runsText(),
            subtitle = info.artists.joinToString(", ") { it.name }.ifBlank { fallback },
            thumbnails = thumbnails(raw["thumbnail"]), artists = info.artists,
            album = info.album, albumId = info.albumId ?: menuAlbum,
            duration = parseDuration(raw["lengthText"].runsText()), explicit = explicit(raw),
        )
    }

    /** musicNavigationButtonRenderer：心情/流派彩色方块 */
    fun navButton(raw: JsonElement): ShelfItem? {
        val title = raw["buttonText"].runsText().ifBlank { return null }
        val be = raw.at("clickCommand", "browseEndpoint") ?: return null
        val id = be["browseId"].str ?: return null
        val color = raw.at("solid", "leftStripeColor").num?.let { it and 0xFFFFFF }
        return ShelfItem(ItemKind.CATEGORY, id, title, categoryParams = be["params"].str, tint = color)
    }

    fun collectShelfNodes(sections: List<JsonElement>): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        fun walk(n: JsonElement?) {
            if (n == null) return
            if (n["musicCarouselShelfRenderer"] != null || n["musicShelfRenderer"] != null ||
                n["musicCardShelfRenderer"] != null || n["musicPlaylistShelfRenderer"] != null
            ) { out += n; return }
            n.at("itemSectionRenderer", "contents").arr.forEach(::walk)
            n.at("sectionListRenderer", "contents").arr.forEach(::walk)
            n["gridRenderer"]?.let { g ->
                out += JsonObject(
                    mapOf(
                        "musicShelfRenderer" to JsonObject(
                            buildMap {
                                g.at("header", "gridHeaderRenderer", "title")?.let { put("title", it) }
                                put("contents", g["items"] ?: JsonArray(emptyList()))
                            }
                        )
                    )
                )
            }
        }
        sections.forEach(::walk)
        return out
    }

    private fun shelfMore(music: JsonElement): ShelfMore? {
        val header = music.at("header", "musicCarouselShelfBasicHeaderRenderer")
        val nav = header.at("moreContentButton", "buttonRenderer", "navigationEndpoint")
            ?: music["bottomEndpoint"]
            ?: header.at("title", "runs", 0, "navigationEndpoint")
            ?: music.at("title", "runs", 0, "navigationEndpoint")
        val be = nav["browseEndpoint"] ?: return null
        val id = be["browseId"].str ?: return null
        return ShelfMore(id, be["params"].str, be.pageType())
    }

    private fun cardFeatured(card: JsonElement): ShelfItem? {
        val title = card["title"].runsText().ifBlank { return null }
        val tap = card["onTap"] ?: card.at("title", "runs", 0, "navigationEndpoint")
        val thumbs = thumbnails(card.at("thumbnail", "musicThumbnailRenderer", "thumbnail"))
        val sub = card["subtitle"].runsText()
        tap.at("watchEndpoint", "videoId").str?.let { vid ->
            val mvt = tap.at("watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str
            return ShelfItem(kindForVideoType(mvt), vid, title, sub.ifBlank { null }, thumbs)
        }
        val be = tap["browseEndpoint"] ?: return null
        val kind = pageKind(be.pageType()) ?: return null
        val id = be["browseId"].str ?: return null
        return ShelfItem(kind, id, title, sub.ifBlank { null }, thumbs, round = kind == ItemKind.ARTIST)
    }

    fun shelf(wrapper: JsonElement, index: Int, idPrefix: String = ""): Shelf? {
        val card = wrapper["musicCardShelfRenderer"]
        val music = wrapper["musicCarouselShelfRenderer"] ?: wrapper["musicShelfRenderer"]
            ?: wrapper["musicPlaylistShelfRenderer"] ?: card ?: return null
        val title = if (card != null) card.at("header", "musicCardShelfHeaderBasicRenderer", "title").runsText()
        else (music.at("header", "musicCarouselShelfBasicHeaderRenderer", "title") ?: music["title"]).runsText()

        val items = mutableListOf<ShelfItem>()
        var sawResponsive = false
        var sawTwoRow = false
        var sawNav = false
        for (c in music["contents"].arr) {
            c["musicTwoRowItemRenderer"]?.let { sawTwoRow = true; twoRow(it)?.let(items::add) }
            c["musicResponsiveListItemRenderer"]?.let { sawResponsive = true; responsive(it)?.let(items::add) }
            c["musicNavigationButtonRenderer"]?.let { sawNav = true; navButton(it)?.let(items::add) }
        }
        if (card != null) cardFeatured(card)?.let { items.add(0, it) }
        if (items.isEmpty()) return null
        val display = when {
            sawNav && !sawTwoRow && !sawResponsive -> ShelfDisplay.GRID
            sawResponsive && !sawTwoRow -> ShelfDisplay.LIST
            else -> ShelfDisplay.CARD
        }
        val t = title.ifBlank { "" }
        return Shelf("$idPrefix$index-$t", t, items, display, if (card == null) shelfMore(music) else null)
    }

    fun shelves(sections: List<JsonElement>, idPrefix: String = ""): List<Shelf> =
        collectShelfNodes(sections).mapIndexedNotNull { i, w -> shelf(w, i, idPrefix) }

    /** 遍历响应找第一个续页 token */
    fun findContinuation(root: JsonElement?): String? {
        root.findFirst("nextContinuationData")?.let { it["continuation"].str?.let { t -> return t } }
        return root.findFirst("continuationCommand")?.get("token").str
    }

    fun chips(root: JsonElement?): List<Chip> =
        root.collect("chipCloudChipRenderer").mapNotNull { c ->
            val title = c["text"].runsText().ifBlank { return@mapNotNull null }
            val params = c.at("navigationEndpoint", "browseEndpoint", "params").str
            Chip(title, params, c["isSelected"].bool == true)
        }
}
