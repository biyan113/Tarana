package app.tarana.pad.yt

import kotlinx.serialization.json.JsonElement

/**
 * 业务层 API：首页、探索、搜索、专辑、艺人、歌单、电台、资料库、账号。
 * 对应 radif `innertube/` 下的 home/search/album/artist/playlist/radio/library/account。
 */
class YtMusic(val api: InnerTube) {

    // ---------- 首页 / 通用 browse ----------

    suspend fun home(cursor: String? = null, chipParams: String? = null): Page {
        val json = when {
            cursor != null -> api.browseContinuation(cursor)
            else -> api.browse("FEmusic_home", chipParams)
        }
        return feedPage(json, cursor)
    }

    /** 首页 / 探索 / “更多” 链接等通用 browse 页 */
    suspend fun browsePage(browseId: String, params: String? = null, cursor: String? = null): Page {
        val json = if (cursor != null) api.browseContinuation(cursor) else api.browse(browseId, params)
        return feedPage(json, cursor)
    }

    private fun feedPage(json: JsonElement, cursor: String?): Page {
        val sections: List<JsonElement>
        var next: String? = null
        if (cursor == null) {
            val list = json.at("contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer")
            sections = list["contents"].arr
            next = list["continuations"].arr.firstNotNullOfOrNull { it.at("nextContinuationData", "continuation").str }
        } else {
            val cc = json.at("continuationContents", "sectionListContinuation")
            val grid = json.at("continuationContents", "gridContinuation")
            sections = when {
                cc != null -> { next = cc["continuations"].arr.firstNotNullOfOrNull { it.at("nextContinuationData", "continuation").str }; cc["contents"].arr }
                grid != null -> listOf(kotlinx.serialization.json.JsonObject(mapOf("gridRenderer" to grid)))
                else -> json["onResponseReceivedActions"].arr.flatMap { it.at("appendContinuationItemsAction", "continuationItems").arr }
            }
        }
        if (next == null) next = sections.firstNotNullOfOrNull {
            it.at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str
        }
        val tag = cursor?.hashCode()?.toString(36) ?: "init"
        return Page(Parsers.shelves(sections, "$tag-"), next, if (cursor == null) Parsers.chips(json) else emptyList())
    }

    suspend fun explore(): Page = browsePage("FEmusic_explore")
    suspend fun moods(): Page = browsePage("FEmusic_moods_and_genres")
    suspend fun charts(): Page = browsePage("FEmusic_charts")
    suspend fun newReleases(): Page = browsePage("FEmusic_new_releases")

    // ---------- 搜索 ----------

    enum class Filter(val params: String?) {
        ALL(null),
        SONGS("EgWKAQIIAWoQEAkQBRAKEAMQBBAQEBUQEQ=="),
        VIDEOS("EgWKAQIQAWoQEAkQBRAKEAMQBBAQEBUQEQ=="),
        ALBUMS("EgWKAQIYAWoQEAkQBRAKEAMQBBAQEBUQEQ=="),
        ARTISTS("EgWKAQIgAWoQEAkQBRAKEAMQBBAQEBUQEQ=="),
        PLAYLISTS("EgWKAQIoAWoQEAkQBRAKEAMQBBAQEBUQEQ=="),
    }

    suspend fun search(query: String, filter: Filter = Filter.ALL): SearchResults {
        if (query.isBlank()) return SearchResults(query)
        val json = api.search(query, filter.params)
        val sections = json.at("contents", "tabbedSearchResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents").arr
            .ifEmpty { json.at("contents", "sectionListRenderer", "contents").arr }

        if (filter != Filter.ALL) return SearchResults(query, shelves = Parsers.shelves(sections))

        // “全部”页：顶部卡片 + 若干 itemSection 平铺行，按类型分组（radif 的 buildAllResults）
        val card = sections.firstNotNullOfOrNull { it["musicCardShelfRenderer"] }
        val top = card?.let { Parsers.shelf(kotlinx.serialization.json.JsonObject(mapOf("musicCardShelfRenderer" to it)), 0) }
        val topItem = top?.items?.firstOrNull()
        val seen = HashSet<String>()
        val groups = LinkedHashMap<ItemKind, MutableList<ShelfItem>>()
        top?.items?.drop(1)?.forEach { seen += "${it.kind}:${it.id}"; groups.getOrPut(it.kind) { mutableListOf() } += it }
        topItem?.let { seen += "${it.kind}:${it.id}" }
        for (s in sections) {
            val rows = s.at("itemSectionRenderer", "contents").arr + s.at("musicShelfRenderer", "contents").arr
            for (c in rows) {
                val item = c["musicResponsiveListItemRenderer"]?.let(Parsers::responsive) ?: continue
                if (!seen.add("${item.kind}:${item.id}")) continue
                groups.getOrPut(item.kind) { mutableListOf() } += item
            }
        }
        val order = listOf(ItemKind.SONG, ItemKind.ARTIST, ItemKind.ALBUM, ItemKind.VIDEO, ItemKind.PLAYLIST)
        val titles = mapOf(
            ItemKind.SONG to "歌曲", ItemKind.ARTIST to "艺人", ItemKind.ALBUM to "专辑和单曲",
            ItemKind.VIDEO to "视频", ItemKind.PLAYLIST to "歌单",
        )
        val shelves = order.mapNotNull { k ->
            val items = groups[k] ?: return@mapNotNull null
            Shelf("all-$k", titles.getValue(k), items, if (k == ItemKind.SONG || k == ItemKind.VIDEO) ShelfDisplay.LIST else ShelfDisplay.CARD)
        }
        return SearchResults(query, topItem, shelves)
    }

    suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val json = api.post("music/get_search_suggestions", mapOf("input" to query), auth = false)
        return json.collect("searchSuggestionRenderer").map { it["suggestion"].runsText() }.filter { it.isNotBlank() }
    }

    // ---------- 专辑 ----------

    suspend fun album(id: String): AlbumPage {
        val json = api.browse(id)
        val header = json.at("header", "musicDetailHeaderRenderer") ?: json.findFirst("musicResponsiveHeaderRenderer")
        val title = header["title"].runsText()
        var thumbs = Parsers.thumbnails(
            header.at("thumbnail", "musicThumbnailRenderer", "thumbnail")
                ?: header.at("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail")
        )
        if (thumbs.isEmpty()) thumbs = Parsers.deepThumbnails(header["thumbnail"])
        val runs = header.at("subtitle", "runs").arr + header.at("straplineTextOne", "runs").arr
        val artists = mutableListOf<ArtistRef>()
        var year: String? = null
        for (r in runs) {
            val be = r.at("navigationEndpoint", "browseEndpoint")
            val text = r["text"].str.orEmpty()
            if (be != null && be.pageType()?.contains("ARTIST") == true) artists += ArtistRef(be["browseId"].str, text)
            else if (Regex("^\\d{4}$").matches(text.trim())) year = text.trim()
        }
        val seen = HashSet<String>()
        val tracks = json.collect("musicResponsiveListItemRenderer").mapNotNull(Parsers::responsive)
            .filter { it.isPlayable && seen.add(it.id) }
            .map { t ->
                t.copy(
                    kind = ItemKind.SONG, album = title, albumId = id,
                    artists = t.artists.ifEmpty { artists },
                    subtitle = (t.artists.ifEmpty { artists }).joinToString(", ") { it.name }.ifBlank { null },
                    thumbnails = thumbs.ifEmpty { t.thumbnails },
                )
            }
        val playlistId = json.findFirst("watchPlaylistEndpoint")?.get("playlistId").str
            ?: json.findFirst("audioPlaylistId").str
        return AlbumPage(id, title, artists, year, header["secondSubtitle"].runsText().ifBlank { null }, thumbs, tracks, playlistId)
    }

    // ---------- 艺人 ----------

    suspend fun artist(id: String): ArtistPage {
        val json = api.browse(id)
        val header = json.at("header", "musicImmersiveHeaderRenderer") ?: json.at("header", "musicVisualHeaderRenderer")
            ?: json.at("header", "musicDetailHeaderRenderer")
        val thumbs = Parsers.thumbnails(
            header.at("thumbnail", "musicThumbnailRenderer", "thumbnail")
                ?: header.at("foregroundThumbnail", "musicThumbnailRenderer", "thumbnail")
        )
        fun target(btn: JsonElement?): String? {
            val ep = btn.at("buttonRenderer", "navigationEndpoint")
            return ep.at("watchEndpoint", "playlistId").str ?: ep.at("watchPlaylistEndpoint", "playlistId").str
        }
        val sections = json.at("contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents").arr
        return ArtistPage(
            id = id,
            name = header["title"].runsText(),
            description = header["description"].runsText().ifBlank { null },
            audience = header["monthlyListenerCount"].runsText().ifBlank {
                header.at("subscriptionButton", "subscribeButtonRenderer", "subscriberCountText").runsText()
            }.ifBlank { null },
            thumbnails = thumbs,
            shufflePlaylistId = target(header["playButton"]),
            radioPlaylistId = target(header["startRadioButton"]),
            shelves = Parsers.shelves(sections),
        )
    }

    // ---------- 歌单 ----------

    suspend fun playlist(id: String): PlaylistPage {
        val browseId = if (id.startsWith("VL")) id else "VL$id"
        val rawId = browseId.drop(2)
        val json = api.browse(browseId)
        val header = json.findFirst("musicResponsiveHeaderRenderer") ?: json.findFirst("musicDetailHeaderRenderer")
        var thumbs = Parsers.thumbnails(
            header.at("thumbnail", "musicThumbnailRenderer", "thumbnail")
                ?: header.at("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail")
        )
        if (thumbs.isEmpty()) thumbs = Parsers.deepThumbnails(header["thumbnail"])

        // 只在歌单自己的 shelf 里收集曲目，避免把“推荐”混进来
        val shelf = json.findFirst("musicPlaylistShelfRenderer")
        val scope = shelf ?: json
        val seen = HashSet<String>()
        var tracks = scope.collect("musicResponsiveListItemRenderer").mapNotNull(Parsers::responsive)
            .filter { it.isPlayable && seen.add(it.id) }
        var cont = if (shelf != null) pagingToken(shelf) else Parsers.findContinuation(scope)

        if (tracks.isEmpty()) {
            // 电台式歌单（RDCLAK5…）只在 /next 里有曲目
            tracks = watchQueue(rawId).first
            cont = null
        }
        return PlaylistPage(
            id = rawId,
            title = header["title"].runsText(),
            author = (header["straplineTextOne"] ?: header["subtitle"]).runsText().ifBlank { null },
            meta = header["secondSubtitle"].runsText().ifBlank { null },
            description = header.at("description", "musicDescriptionShelfRenderer", "description").runsText()
                .ifBlank { header["description"].runsText() }.ifBlank { null },
            thumbnails = thumbs,
            tracks = tracks,
            continuation = cont,
        )
    }

    suspend fun playlistMore(token: String): Pair<List<ShelfItem>, String?> {
        val json = api.browseContinuation(token)
        val items = json.collect("musicResponsiveListItemRenderer").mapNotNull(Parsers::responsive).filter { it.isPlayable }
        val container = json.at("continuationContents", "musicPlaylistShelfContinuation")
        val next = if (container != null) pagingToken(container) else
            json["onResponseReceivedActions"].arr.flatMap { it.at("appendContinuationItemsAction", "continuationItems").arr }
                .lastOrNull()?.at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str
        return items to next?.takeIf { it != token }
    }

    private fun pagingToken(container: JsonElement?): String? {
        container["continuations"].arr.forEach { c ->
            c.at("nextContinuationData", "continuation").str?.let { return it }
            (c.at("continuationEndpoint", "continuationCommand", "token") ?: c.at("continuationCommand", "token")).str?.let { return it }
        }
        val items = (container["contents"] ?: container["items"]).arr
        return items.lastOrNull().at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str
    }

    // ---------- 电台 / 播放队列 ----------

    private fun parsePanel(json: JsonElement): Pair<List<ShelfItem>, String?> {
        val panel = json.at(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer",
            "tabs", 0, "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer",
        ) ?: json.at("continuationContents", "playlistPanelContinuation")
        val tracks = panel["contents"].arr.mapNotNull { c ->
            val row = c["playlistPanelVideoRenderer"]
                ?: c.at("playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer")
            row?.let(Parsers::panelVideo)
        }
        val token = panel["continuations"].arr.firstNotNullOfOrNull {
            it.at("nextContinuationData", "continuation").str ?: it.at("nextRadioContinuationData", "continuation").str
        }
        return tracks to token
    }

    /** 以一首歌为种子的电台（自动续播） */
    suspend fun radio(videoId: String): Pair<List<ShelfItem>, String?> =
        parsePanel(api.next(mapOf("videoId" to videoId, "playlistId" to "RDAMVM$videoId", "isAudioOnly" to true)))

    suspend fun watchQueue(playlistId: String, videoId: String? = null): Pair<List<ShelfItem>, String?> =
        parsePanel(api.next(mapOf("playlistId" to playlistId, "videoId" to videoId, "isAudioOnly" to true)))

    suspend fun watchQueueMore(token: String): Pair<List<ShelfItem>, String?> =
        parsePanel(api.next(mapOf("continuation" to token, "isAudioOnly" to true)))

    /** 单曲元数据（从链接或通知等只知道 videoId 的地方进入时用） */
    suspend fun track(videoId: String): ShelfItem? =
        parsePanel(api.next(mapOf("videoId" to videoId, "isAudioOnly" to true))).first.firstOrNull { it.id == videoId }

    // ---------- 资料库（需要登录） ----------

    suspend fun library(browseId: String): List<ShelfItem> {
        val json = api.browse(browseId)
        val sections = json.at("contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents").arr
        val out = mutableListOf<ShelfItem>()
        val nodes = Parsers.collectShelfNodes(sections)
        for (n in nodes) Parsers.shelf(n, 0)?.items?.let(out::addAll)
        // 跟随分页（库里超过 ~25 个歌单时）
        var token = nodes.firstNotNullOfOrNull { pagingToken(it["musicShelfRenderer"] ?: it["gridRenderer"]) }
            ?: sections.firstNotNullOfOrNull { pagingToken(it["gridRenderer"] ?: it["musicShelfRenderer"]) }
        val seenTokens = HashSet<String>()
        var pages = 0
        while (token != null && seenTokens.add(token) && pages++ < 40) {
            val page = runCatching { api.browseContinuation(token) }.getOrNull() ?: break
            val cc = page.at("continuationContents", "gridContinuation") ?: page.at("continuationContents", "musicShelfContinuation")
            val rows = (cc["items"] ?: cc["contents"]).arr
            val mapped = rows.mapNotNull { r -> r["musicTwoRowItemRenderer"]?.let(Parsers::twoRow) ?: r["musicResponsiveListItemRenderer"]?.let(Parsers::responsive) }
            if (mapped.isEmpty()) break
            out += mapped
            token = pagingToken(cc)
        }
        return out
    }

    suspend fun libraryPlaylists() = library("FEmusic_liked_playlists")
    suspend fun libraryAlbums() = library("FEmusic_liked_albums")
    suspend fun libraryArtists() = library("FEmusic_library_corpus_track_artists")

    suspend fun account(): Account? {
        val json = api.post("account/account_menu", emptyMap())
        val h = json.at("actions", 0, "openPopupAction", "popup", "multiPageMenuRenderer", "header", "activeAccountHeaderRenderer")
            ?: return null
        return Account(
            name = h["accountName"].runsText(),
            handle = h["channelHandle"].runsText().ifBlank { h["email"].runsText() }.ifBlank { null },
            photo = Parsers.thumbnails(h["accountPhoto"]).lastOrNull()?.url,
        )
    }

    /** 点赞 / 取消点赞 */
    suspend fun like(videoId: String, liked: Boolean) {
        api.post(if (liked) "like/like" else "like/removelike", mapOf("target" to kotlinx.serialization.json.buildJsonObject {
            put("videoId", kotlinx.serialization.json.JsonPrimitive(videoId))
        }))
    }
}
