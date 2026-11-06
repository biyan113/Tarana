package app.tarana.pad.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tarana.pad.TaranaApp
import app.tarana.pad.yt.ItemKind
import app.tarana.pad.yt.ShelfItem
import app.tarana.pad.yt.YtMusic
import kotlinx.coroutines.delay

// ---------------- 搜索 ----------------

private object SearchState {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var filter by mutableStateOf(YtMusic.Filter.ALL)
    val history = mutableStateListOf<String>()
}

@Composable
fun SearchScreen() {
    val yt = TaranaApp.instance.yt
    val s = SearchState
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var editing by remember { mutableStateOf(s.submitted.isEmpty()) }

    fun submit(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        s.query = t; s.submitted = t; editing = false; kb?.hide()
        s.history.remove(t); s.history.add(0, t); if (s.history.size > 12) s.history.removeAt(s.history.lastIndex)
    }

    LaunchedEffect(Unit) { if (s.submitted.isEmpty()) focus.requestFocus() }
    LaunchedEffect(s.query, editing) {
        if (!editing) return@LaunchedEffect
        delay(200)
        suggestions = runCatching { yt.suggestions(s.query) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = s.query,
            onValueChange = { s.query = it; editing = true },
            modifier = Modifier.padding(PagePadding).padding(top = 24.dp).fillMaxWidth().widthIn(max = 900.dp).focusRequester(focus),
            placeholder = { Text("搜索歌曲、艺人、专辑、歌单") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (s.query.isNotEmpty()) IconButton(onClick = { s.query = ""; editing = true; focus.requestFocus() }) { Icon(Icons.Default.Close, "清除") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(s.query) }),
        )
        if (editing || s.submitted.isEmpty()) {
            val rows = if (s.query.isBlank()) s.history.map { it to true } else suggestions.map { it to false }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp)) {
                items(rows) { (t, hist) ->
                    Row(Modifier.fillMaxWidth().clickable { submit(t) }.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (hist) Icons.Default.History else Icons.Default.Search, null, tint = TaranaColors.TextDim)
                        Spacer(Modifier.width(16.dp)); Text(t)
                    }
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = listOf("全部", "歌曲", "视频", "专辑", "艺人", "歌单")
                items(YtMusic.Filter.entries.toList()) { f ->
                    FilterChip(selected = s.filter == f, onClick = { s.filter = f }, label = { Text(labels[f.ordinal]) })
                }
            }
            SearchResultsView(s.submitted, s.filter)
        }
    }
}

@Composable
private fun SearchResultsView(q: String, filter: YtMusic.Filter) {
    val yt = TaranaApp.instance.yt
    val (load, retry) = rememberLoad("search:$filter:$q") { yt.search(q, filter) }
    when (val l = load.value) {
        is Load.Ok -> {
            val r = l.value
            if (r.top == null && r.shelves.isEmpty()) { EmptyHint("没有结果", "换个关键词试试"); return }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                r.top?.let { top ->
                    item {
                        val click = rememberItemClick()
                        SectionHeader("最佳结果")
                        Row(
                            Modifier.padding(PagePadding).clickableRound { click(top) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(top.thumb(300), Modifier.size(112.dp), if (top.round) CircleShape else RoundedCornerShape(8.dp))
                            Spacer(Modifier.width(20.dp))
                            Column {
                                Text(top.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                top.subtitle?.let { Text(it, color = TaranaColors.TextDim) }
                            }
                        }
                    }
                }
                for (shelf in r.shelves) {
                    when {
                        // 歌曲 / 视频：竖向列表（“全部”页每组最多 6 行）
                        shelf.items.all { it.isPlayable } -> {
                            val rows = if (filter == YtMusic.Filter.ALL) shelf.items.take(6) else shelf.items
                            item(key = shelf.id + "h") { Box(Modifier.padding(PagePadding)) { SectionHeader(shelf.title) } }
                            items(rows, key = { shelf.id + it.id }) { SongRow(it, rememberItemClick(shelf.items), Modifier.padding(PagePadding).fillMaxWidth()) }
                        }
                        filter == YtMusic.Filter.ALL -> item(key = shelf.id) { ShelfSection(shelf, PagePadding) }
                        else -> item(key = shelf.id) { ResultGrid(shelf.items) }
                    }
                }
            }
        }
        else -> LoadBox(l, retry)
    }
}

@Composable
private fun ResultGrid(items: List<ShelfItem>) {
    val click = rememberItemClick()
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.padding(PagePadding).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { ItemCard(it, click, 180.dp) }
    }
}

fun Modifier.clickableRound(onClick: () -> Unit) = this.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)

// ---------------- 资料库 ----------------

@Composable
fun LibraryScreen() {
    val app = TaranaApp.instance
    val signedIn by app.session.signedIn.collectAsState()
    val nav = LocalNav.current
    if (!signedIn) {
        EmptyHint("登录后查看资料库", "登录你的 Google 账号，同步 YouTube Music 里的歌单、收藏的专辑和艺人。", "登录") { nav.loginOpen = true }
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("歌单", "专辑", "艺人")
    Column(Modifier.fillMaxSize()) {
        Text("资料库", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(PagePadding).padding(top = 28.dp, bottom = 8.dp))
        PrimaryTabRow(selectedTabIndex = tab, containerColor = TaranaColors.Bg, modifier = Modifier.widthIn(max = 520.dp).padding(start = 12.dp)) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        val key = "lib:$tab"
        val (load, retry) = rememberLoad(key) {
            when (tab) { 0 -> app.yt.libraryPlaylists(); 1 -> app.yt.libraryAlbums(); else -> app.yt.libraryArtists() }
        }
        when (val l = load.value) {
            is Load.Ok -> {
                val items = l.value.filter { it.kind != ItemKind.CATEGORY }
                    .let { list -> if (tab == 0 && list.none { it.id == "VLLM" || it.id == "LM" }) listOf(LIKED) + list else list }
                val click = rememberItemClick()
                if (items.isEmpty()) EmptyHint("这里还是空的", "在 YouTube Music 里收藏后会出现在这里")
                else LazyVerticalGrid(GridCells.Adaptive(176.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp)) {
                    items(items, key = { it.kind.name + it.id }) { ItemCard(it, click, 176.dp) }
                }
            }
            else -> LoadBox(l, retry)
        }
    }
}

private val LIKED = ShelfItem(ItemKind.PLAYLIST, "LM", "喜欢的歌曲", "自动歌单")

// ---------------- 登录 ----------------

/**
 * 用 WebView 打开 Google 登录页，回到 music.youtube.com 且拿到 SAPISID 后，
 * 把 cookie 存下来供 InnerTube 使用（对应 radif 的登录窗口 + authfs）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val app = TaranaApp.instance
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("登录 YouTube Music", style = MaterialTheme.typography.titleMedium)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 去掉 WebView 标识，避免 Google 以“此浏览器不安全”拦截
                    settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!url.startsWith("https://music.youtube.com")) return
                            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                            if (cookie.contains("SAPISID=")) {
                                app.session.save(cookie)
                                PageCache.clear()
                                onDone()
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F")
                }
            },
        )
    }
}
