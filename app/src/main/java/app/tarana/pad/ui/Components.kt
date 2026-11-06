package app.tarana.pad.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tarana.pad.player.PlayerController
import app.tarana.pad.yt.ItemKind
import app.tarana.pad.yt.Shelf
import app.tarana.pad.yt.ShelfDisplay
import app.tarana.pad.yt.ShelfItem
import coil3.compose.AsyncImage

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.clip(shape).background(TaranaColors.SurfaceHi), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Default.MusicNote, null, tint = TaranaColors.TextFaint)
        else AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

fun formatDuration(sec: Int?): String = sec?.let {
    if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60)
} ?: ""
fun formatMs(ms: Long): String = formatDuration((ms / 1000).toInt())

@Composable
fun LoadBox(load: Load<*>, retry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (load) {
            is Load.Loading -> CircularProgressIndicator(color = TaranaColors.Accent)
            is Load.Err -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败", style = MaterialTheme.typography.titleMedium)
                Text(load.error.message?.take(160) ?: load.error.javaClass.simpleName, style = DimText, modifier = Modifier.padding(8.dp).widthIn(max = 480.dp))
                FilledTonalButton(onClick = retry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重试") }
            }
            else -> Unit
        }
    }
}

/** 点击：可播放则播放（单曲点播自动开电台），否则打开详情页 */
@Composable
fun rememberItemClick(siblings: List<ShelfItem>? = null): (ShelfItem) -> Unit {
    val nav = LocalNav.current
    return remember(siblings) {
        { item ->
            if (item.isPlayable) {
                val list = siblings?.filter { it.isPlayable }
                if (list != null && list.size > 1) PlayerController.play(list, list.indexOf(item))
                else PlayerController.play(listOf(item))
            } else nav.open(item)
        }
    }
}

@Composable
fun ItemMenu(item: ShelfItem, expanded: Boolean, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    DropdownMenu(expanded, onDismiss) {
        @Composable
        fun entry(text: String, icon: ImageVector, act: () -> Unit) =
            DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { onDismiss(); act() })
        if (item.isPlayable) {
            entry("下一首播放", Icons.AutoMirrored.Filled.QueueMusic) { PlayerController.playNext(item) }
            entry("加入播放队列", Icons.AutoMirrored.Filled.PlaylistAdd) { PlayerController.enqueue(item) }
            entry("开始电台", Icons.Default.Radio) { PlayerController.startRadio(item) }
            item.albumId?.let { id -> entry("前往专辑", Icons.Default.Album) { nav.push(Route.Album(id)) } }
            item.artists.filter { it.id != null }.take(3).forEach { a ->
                entry("前往 ${a.name}", Icons.Default.Person) { nav.push(Route.Artist(a.id!!)) }
            }
        } else {
            entry("打开", Icons.Default.Album) { nav.open(item) }
        }
    }
}

/** 轮播卡片（专辑 / 歌单 / 艺人 / 视频） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemCard(item: ShelfItem, onClick: (ShelfItem) -> Unit, width: Dp = 172.dp) {
    var menu by remember { mutableStateOf(false) }
    val wide = item.kind == ItemKind.VIDEO
    Column(
        Modifier.width(if (wide) width * 1.6f else width)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = { onClick(item) }, onLongClick = { menu = true })
            .padding(6.dp)
    ) {
        Box {
            Artwork(
                item.thumb(if (wide) 640 else 400),
                Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f),
                if (item.round) CircleShape else RoundedCornerShape(8.dp),
            )
            ItemMenu(item, menu) { menu = false }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            modifier = if (item.round) Modifier.fillMaxWidth() else Modifier)
        item.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TaranaColors.TextDim) }
    }
}

/** 列表行（歌曲） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    item: ShelfItem,
    onClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val current by PlayerController.state.collectAsState()
    val playing = current.current?.id == item.id
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = { onClick(item) }, onLongClick = { menu = true })
            .background(if (playing) TaranaColors.AccentDeep.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (playing) Icon(Icons.Default.GraphicEq, null, tint = TaranaColors.Accent, modifier = Modifier.size(18.dp))
                else Text("$index", color = TaranaColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (showArt) {
            Artwork(item.thumb(120), Modifier.size(48.dp), if (item.round) CircleShape else RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = if (playing) TaranaColors.Accent else TaranaColors.Text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.explicit) Icon(Icons.Default.Explicit, null, Modifier.size(14.dp).padding(end = 2.dp), tint = TaranaColors.TextDim)
                Text(listOfNotNull(item.artistLine.ifBlank { null }, item.album).joinToString(" • "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TaranaColors.TextDim)
            }
        }
        if (item.duration != null) Text(formatDuration(item.duration), color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        trailing?.invoke()
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多", tint = TaranaColors.TextDim) }
            ItemMenu(item, menu) { menu = false }
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = TaranaColors.TextDim) }
    }
}

@Composable
fun CategoryTile(item: ShelfItem, onClick: (ShelfItem) -> Unit, modifier: Modifier = Modifier) {
    val tint = item.tint?.let { Color(0xFF000000 or it) } ?: TaranaColors.Accent
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(10.dp))
            .background(TaranaColors.SurfaceHi)
            .combinedClickableCompat { onClick(item) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(tint))
        Text(item.title, Modifier.padding(horizontal = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

/** 一个 shelf：卡片横滑 / 歌曲多行横滑网格 / 分类块 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfSection(shelf: Shelf, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val nav = LocalNav.current
    val click = rememberItemClick(shelf.items)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(contentPadding)) {
            SectionHeader(shelf.title.ifBlank { " " }, if (shelf.more != null) "更多" else null) {
                shelf.more?.let { nav.openMore(it, shelf.title) }
            }
        }
        when (shelf.display) {
            ShelfDisplay.CARD -> LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shelf.items, key = { it.kind.name + it.id }) { ItemCard(it, click) }
            }
            ShelfDisplay.LIST -> {
                val rows = if (shelf.items.size >= 8) 4 else shelf.items.size.coerceAtMost(4).coerceAtLeast(1)
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(rows),
                    modifier = Modifier.height((64 * rows).dp),
                    contentPadding = contentPadding,
                ) {
                    items(shelf.items, key = { it.kind.name + it.id }) { SongRow(it, click, Modifier.width(380.dp)) }
                }
            }
            ShelfDisplay.GRID -> FlowRow(
                Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 5,
            ) {
                shelf.items.forEach { CategoryTile(it, click, Modifier.weight(1f)) }
            }
        }
    }
}

/** 详情页头部的大背景渐变 */
fun headerGradient(top: Color) = Brush.verticalGradient(listOf(top.copy(alpha = 0.55f), Color.Transparent))
