package app.tarana.pad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tarana.pad.TaranaApp
import app.tarana.pad.player.PlayerController
import app.tarana.pad.yt.ArtistRef
import app.tarana.pad.yt.ShelfItem
import app.tarana.pad.yt.bestThumb
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** 专辑 / 歌单共用的版式：宽屏左右分栏（左封面信息、右曲目），窄屏上下排列 */
@Composable
private fun CollectionLayout(
    cover: String?,
    kicker: String,
    title: String,
    artists: List<ArtistRef>,
    byline: String?,
    meta: String?,
    description: String?,
    tracks: List<ShelfItem>,
    numbered: Boolean,
    showArt: Boolean,
    onPlay: (shuffle: Boolean) -> Unit,
    footer: LazyListScope.() -> Unit = {},
    onNearEnd: (() -> Unit)? = null,
) {
    val nav = LocalNav.current
    val click = rememberItemClick(tracks)
    val headerContent: @Composable ColumnScope.(Boolean) -> Unit = { wide ->
        Artwork(cover, Modifier.then(if (wide) Modifier.fillMaxWidth() else Modifier.size(220.dp)).aspectRatio(1f), RoundedCornerShape(12.dp))
        Spacer(Modifier.height(20.dp))
        Text(kicker, style = MaterialTheme.typography.labelLarge, color = TaranaColors.TextDim)
        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (artists.isNotEmpty()) Row {
            artists.forEachIndexed { i, a ->
                Text(a.name + if (i < artists.lastIndex) ", " else "", fontWeight = FontWeight.SemiBold,
                    modifier = if (a.id != null) Modifier.clickable { nav.push(Route.Artist(a.id)) } else Modifier)
            }
        } else byline?.let { Text(it, fontWeight = FontWeight.SemiBold, maxLines = 2) }
        meta?.let { Text(it, color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onPlay(false) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("播放") }
            FilledTonalButton(onClick = { onPlay(true) }, enabled = tracks.isNotEmpty()) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("随机") }
            IconButton(onClick = { PlayerController.enqueueAll(tracks) }, enabled = tracks.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "加入队列") }
        }
        description?.let { Text(it, color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp)) }
    }
    val list = rememberLazyListState()
    if (onNearEnd != null) {
        val near by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 10 } }
        LaunchedEffect(near) { if (near) onNearEnd() }
    }
    val trackItems: LazyListScope.() -> Unit = {
        itemsIndexed(tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
            SongRow(t, click, Modifier.fillMaxWidth(), index = if (numbered) i + 1 else null, showArt = showArt)
        }
        footer()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > 860.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(340.dp).fillMaxHeight().padding(start = 28.dp, top = 28.dp, end = 12.dp)) { headerContent(true) }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)) { trackItems() }
            }
        } else {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
                item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) { headerContent(false) }; Spacer(Modifier.height(16.dp)) }
                trackItems()
            }
        }
    }
}

@Composable
fun AlbumScreen(r: Route.Album) {
    val (load, retry) = rememberLoad(r.key) { TaranaApp.instance.yt.album(r.id) }
    when (val l = load.value) {
        is Load.Ok -> {
            val a = l.value
            val nav = LocalNav.current
            CollectionLayout(
                cover = bestThumb(a.thumbnails, 720), kicker = "专辑", title = a.title, artists = a.artists, byline = null,
                meta = listOfNotNull(a.year, a.meta).joinToString(" • ").ifBlank { null }, description = null,
                tracks = a.tracks, numbered = true, showArt = false,
                onPlay = { shuffle -> PlayerController.play(if (shuffle) a.tracks.shuffled() else a.tracks) },
                footer = {
                    a.artists.firstOrNull()?.id?.let { id ->
                        item { TextButton(onClick = { nav.push(Route.Artist(id)) }, modifier = Modifier.padding(top = 12.dp)) { Text("更多 ${a.artists.first().name} 的作品") } }
                    }
                },
            )
        }
        else -> LoadBox(l, retry)
    }
}

@Composable
fun PlaylistScreen(r: Route.Playlist) {
    val yt = TaranaApp.instance.yt
    val (load, retry) = rememberLoad(r.key) { yt.playlist(r.id) }
    when (val l = load.value) {
        is Load.Ok -> {
            val p = l.value
            val tracks = remember(p) { mutableStateListOf<ShelfItem>().apply { addAll(p.tracks) } }
            var cont by remember(p) { mutableStateOf(p.continuation) }
            var busy by remember { mutableStateOf(false) }
            CollectionLayout(
                cover = bestThumb(p.thumbnails, 720), kicker = "歌单", title = p.title, artists = emptyList(), byline = p.author,
                meta = p.meta, description = p.description, tracks = tracks, numbered = false, showArt = true,
                onPlay = { shuffle -> PlayerController.play(if (shuffle) tracks.shuffled() else tracks.toList()) },
                footer = {
                    if (cont != null) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
                },
                onNearEnd = {
                    val token = cont
                    if (token != null && !busy) {
                        busy = true
                        PlaylistPager.loadMore(token) { items, next ->
                            val seen = tracks.map { it.id }.toHashSet()
                            tracks.addAll(items.filter { it.id !in seen })
                            cont = next
                            busy = false
                        }
                    }
                },
            )
        }
        else -> LoadBox(l, retry)
    }
}

/** 歌单翻页（放在 Compose 外面，避免重组取消请求） */
object PlaylistPager {
    private val scope = kotlinx.coroutines.MainScope()
    fun loadMore(token: String, done: (List<ShelfItem>, String?) -> Unit) {
        scope.launch {
            val (items, next) = runCatching { TaranaApp.instance.yt.playlistMore(token) }.getOrElse { emptyList<ShelfItem>() to null }
            done(items, next)
        }
    }
}

@Composable
fun ArtistScreen(r: Route.Artist) {
    val (load, retry) = rememberLoad(r.key) { TaranaApp.instance.yt.artist(r.id) }
    when (val l = load.value) {
        is Load.Ok -> {
            val a = l.value
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().height(340.dp)) {
                        AsyncImage(bestThumb(a.thumbnails, 1440), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, TaranaColors.Bg))))
                        Column(Modifier.align(Alignment.BottomStart).padding(PagePadding).padding(bottom = 8.dp)) {
                            Text(a.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            a.audience?.let { Text(it, color = TaranaColors.TextDim) }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                a.shufflePlaylistId?.let { id -> Button(onClick = { PlayerController.playQueue(id) }) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("随机播放") } }
                                a.radioPlaylistId?.let { id -> FilledTonalButton(onClick = { PlayerController.playQueue(id) }) { Icon(Icons.Default.Radio, null); Spacer(Modifier.width(6.dp)); Text("电台") } }
                            }
                        }
                    }
                }
                a.description?.let { d -> item { Text(d, color = TaranaColors.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(PagePadding).padding(top = 8.dp).widthIn(max = 900.dp)) } }
                a.shelves.forEach { s -> item(key = s.id) { ShelfSection(s, PagePadding) } }
            }
        }
        else -> LoadBox(l, retry)
    }
}

@Composable
fun EmptyHint(title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, color = TaranaColors.TextDim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).widthIn(max = 460.dp))
        if (action != null && onAction != null) Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) { Text(action) }
    }
}
