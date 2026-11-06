package app.tarana.pad.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tarana.pad.TaranaApp
import app.tarana.pad.yt.Page
import app.tarana.pad.yt.Shelf

val PagePadding = PaddingValues(horizontal = 28.dp)

/** 可无限下拉的 shelf 流：首页、心情分类、“更多”页共用 */
@Composable
fun ShelfFeed(
    key: String,
    title: String?,
    load: suspend (cursor: String?) -> Page,
    header: (@Composable () -> Unit)? = null,
) {
    val (first, retry) = rememberLoad(key) { load(null) }
    when (val f = first.value) {
        is Load.Ok -> {
            val shelves = remember(key, f.value) { mutableStateListOf<Shelf>().apply { addAll(f.value.shelves) } }
            var cursor by remember(key, f.value) { mutableStateOf(f.value.nextCursor) }
            var loadingMore by remember(key) { mutableStateOf(false) }
            val list = rememberLazyListState()
            val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= list.layoutInfo.totalItemsCount - 3 } }
            LaunchedEffect(nearEnd, cursor) {
                val c = cursor
                if (nearEnd && c != null && !loadingMore) {
                    loadingMore = true
                    runCatching { load(c) }.onSuccess { p ->
                        val seen = shelves.map { it.id }.toHashSet()
                        shelves.addAll(p.shelves.filter { it.id !in seen })
                        cursor = p.nextCursor
                    }.onFailure { cursor = null }
                    loadingMore = false
                }
            }
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                if (title != null) item { Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(PagePadding).padding(top = 28.dp)) }
                header?.let { h -> item { h() } }
                items(shelves, key = { it.id }) { ShelfSection(it, PagePadding) }
                if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
        else -> LoadBox(f, retry)
    }
}

@Composable
fun HomeScreen() {
    val yt = TaranaApp.instance.yt
    val signedIn by TaranaApp.instance.session.signedIn.collectAsState()
    var chip by remember { mutableStateOf<String?>(null) }
    var chipsCache by remember { mutableStateOf(emptyList<app.tarana.pad.yt.Chip>()) }
    ShelfFeed(
        key = "home:$signedIn:$chip",
        title = null,
        load = { c -> yt.home(c, chip).also { if (c == null && chip == null && it.chips.isNotEmpty()) chipsCache = it.chips } },
        header = {
            Column {
                Text(greeting(), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(PagePadding).padding(top = 28.dp))
                if (chipsCache.isNotEmpty()) LazyRow(contentPadding = PagePadding, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    items(chipsCache, key = { it.title }) { c ->
                        FilterChip(selected = chip == c.params && chip != null, onClick = { chip = if (chip == c.params) null else c.params }, label = { Text(c.title) })
                    }
                }
            }
        },
    )
}

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) { in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..18 -> "下午好"; else -> "晚上好" }
}

@Composable
fun ExploreScreen() {
    val yt = TaranaApp.instance.yt
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("探索", "新发行", "排行榜", "心情和流派")
    Column(Modifier.fillMaxSize()) {
        Text("探索", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(PagePadding).padding(top = 28.dp, bottom = 8.dp))
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 28.dp, containerColor = TaranaColors.Bg) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> ShelfFeed("explore", null, { c -> if (c == null) yt.explore() else yt.browsePage("", cursor = c) })
            1 -> ShelfFeed("new", null, { c -> if (c == null) yt.newReleases() else yt.browsePage("", cursor = c) })
            2 -> ShelfFeed("charts", null, { yt.charts() })
            else -> ShelfFeed("moods", null, { yt.moods() })
        }
    }
}

@Composable
fun BrowseScreen(r: Route.Browse) {
    val yt = TaranaApp.instance.yt
    ShelfFeed(r.key, r.title, { c -> yt.browsePage(r.id, r.params, c) })
}

@Composable
private fun rememberSaveable(init: () -> MutableIntState): MutableIntState =
    androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.Saver({ it.intValue }, { mutableIntStateOf(it) })) { init() }
