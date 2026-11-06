package app.tarana.pad.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import app.tarana.pad.TaranaApp
import app.tarana.pad.player.PlayerController
import app.tarana.pad.player.PlayerState
import app.tarana.pad.yt.Lyrics
import app.tarana.pad.yt.ShelfItem
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import coil3.SingletonImageLoader
import coil3.request.SuccessResult

// ---------------- 底部迷你播放条 ----------------

@Composable
fun MiniPlayer(state: PlayerState, onOpen: () -> Unit) {
    val cur = state.current ?: return
    val nav = LocalNav.current
    Column(Modifier.fillMaxWidth().background(TaranaColors.Surface)) {
        val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(2.dp), color = TaranaColors.Accent, trackColor = TaranaColors.Line, drawStopIndicator = {})
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(cur.thumb(160), Modifier.size(52.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (state.error != null) Text(state.error, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                else Text(cur.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(enabled = cur.artists.firstOrNull()?.id != null) { nav.push(Route.Artist(cur.artists.first().id!!)) })
            }
            Text("${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}", color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            IconButton(onClick = PlayerController::previous) { Icon(Icons.Default.SkipPrevious, "上一首") }
            PlayPauseButton(state, 48)
            IconButton(onClick = PlayerController::next) { Icon(Icons.Default.SkipNext, "下一首") }
        }
    }
}

@Composable
private fun PlayPauseButton(state: PlayerState, size: Int) {
    FilledIconButton(onClick = PlayerController::toggle, modifier = Modifier.size(size.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
        if (state.buffering && !state.isPlaying) CircularProgressIndicator(Modifier.size((size * 0.45).dp), color = Color.Black, strokeWidth = 2.dp)
        else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "暂停" else "播放", Modifier.size((size * 0.55).dp))
    }
}

// ---------------- 全屏播放页 ----------------

/** 从封面取主色，用于背景 */
@Composable
private fun rememberCoverColor(url: String?): Color {
    val ctx = LocalContext.current
    var color by remember { mutableStateOf(TaranaColors.AccentDeep) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val res = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url).size(96).allowHardware(false).build())
        val bmp = (res as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val p = Palette.from(bmp).generate()
        val sw = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
        sw?.let { color = Color(it.rgb) }
    }
    return animateColorAsState(color, tween(600), label = "cover").value
}

@Composable
fun NowPlaying(state: PlayerState, onClose: () -> Unit) {
    val cur = state.current
    val cover = cur?.thumb(1200)
    val tint = rememberCoverColor(cur?.thumb(200))
    var panel by rememberSaveableInt(0) // 0 歌词 1 队列

    Box(Modifier.fillMaxSize().background(TaranaColors.Bg)) {
        // 背景：封面模糊（Android 12+）+ 主色渐变，切歌时淡入淡出
        AnimatedContent(cover, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "bg") { url ->
            if (url != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AsyncImage(url, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }.blur(90.dp), contentScale = ContentScale.Crop)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f)))))

        if (cur == null) {
            EmptyHint("没有在播放", "从首页或搜索里挑一首歌吧")
        } else BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val wide = maxWidth > maxHeight && maxWidth > 840.dp
            // 封面边长按可用高度计算，给标题/进度条/按钮留出约 230dp
            val coverByHeight = (maxHeight - 290.dp).coerceIn(200.dp, 560.dp)
            val coverPortrait = minOf(420.dp, maxHeight * 0.32f)
            if (wide) Row(Modifier.fillMaxSize().padding(start = 64.dp, end = 48.dp, top = 40.dp, bottom = 16.dp)) {
                Column(Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    PlayerControls(state, cur, Modifier.fillMaxWidth(), coverMax = coverByHeight)
                }
                Spacer(Modifier.width(56.dp))
                Column(Modifier.weight(0.55f).fillMaxHeight()) {
                    PanelTabs(panel) { panel = it }
                    Box(Modifier.weight(1f)) { if (panel == 0) LyricsPanel(cur, state) else QueuePanel(state) }
                }
            } else if (maxWidth >= 600.dp) {
                // 平板竖屏：上方“封面 | 标题+进度+按钮”横排，下方整块宽度留给歌词 / 队列，左右边缘对齐
                val cover = minOf(360.dp, (maxWidth - 96.dp) * 0.42f, maxHeight * 0.3f)
                Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverArt(state, cur, Modifier.size(cover))
                        Spacer(Modifier.width(40.dp))
                        TrackInfo(state, cur, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(20.dp))
                    PanelTabs(panel) { panel = it }
                    Box(Modifier.weight(1f)) { if (panel == 0) LyricsPanel(cur, state) else QueuePanel(state) }
                }
            } else Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                // 手机竖屏：上下堆叠
                Spacer(Modifier.height(40.dp))
                PlayerControls(state, cur, Modifier.fillMaxWidth(), coverMax = coverPortrait)
                PanelTabs(panel) { panel = it }
                Box(Modifier.weight(1f)) { if (panel == 0) LyricsPanel(cur, state) else QueuePanel(state) }
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.systemBarsPadding().padding(12.dp).align(Alignment.TopStart)) {
            Icon(Icons.Default.KeyboardArrowDown, "收起", Modifier.size(32.dp))
        }
    }
}

@Composable
private fun rememberSaveableInt(init: Int): MutableIntState =
    androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.Saver({ it.intValue }, { mutableIntStateOf(it) })) { mutableIntStateOf(init) }

@Composable
private fun PanelTabs(panel: Int, onChange: (Int) -> Unit) {
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(panel == 0, { onChange(0) }, { Text("歌词") }, leadingIcon = { Icon(Icons.Default.Lyrics, null, Modifier.size(18.dp)) })
        FilterChip(panel == 1, { onChange(1) }, { Text("播放队列") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp)) })
    }
}

@Composable
private fun PlayerControls(state: PlayerState, cur: ShelfItem, modifier: Modifier, coverMax: androidx.compose.ui.unit.Dp = 560.dp) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CoverArt(state, cur, Modifier.widthIn(max = coverMax).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(28.dp))
        TrackInfo(state, cur, Modifier.widthIn(max = coverMax).fillMaxWidth())
    }
}

@Composable
private fun CoverArt(state: PlayerState, cur: ShelfItem, modifier: Modifier) {
    val scale by animateFloatAsState(if (state.isPlaying) 1f else 0.92f, tween(400), label = "coverScale")
    AnimatedContent(cur.thumb(1200), modifier, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }, label = "cover") { url ->
        Artwork(url, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }, RoundedCornerShape(14.dp))
    }
}

/** 标题、艺人 / 专辑、进度条、播放按钮 */
@Composable
private fun TrackInfo(state: PlayerState, cur: ShelfItem, modifier: Modifier) {
    val nav = LocalNav.current
    Column(modifier) {
        Text(cur.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row {
            val artists = cur.artists.ifEmpty { listOf(app.tarana.pad.yt.ArtistRef(null, cur.artistLine)) }
            artists.forEachIndexed { i, a ->
                Text(a.name + if (i < artists.lastIndex) ", " else "", color = TaranaColors.TextDim, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    modifier = if (a.id != null) Modifier.clickable { nav.push(Route.Artist(a.id)) } else Modifier)
            }
            cur.album?.let { al ->
                Text(" • $al", color = TaranaColors.TextDim, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (cur.albumId != null) Modifier.clickable { nav.push(Route.Album(cur.albumId)) } else Modifier)
            }
        }
        Spacer(Modifier.height(16.dp))
        SeekBar(state)
        state.error?.let { Text("播放出错：$it", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = PlayerController::toggleShuffle) {
                Icon(Icons.Default.Shuffle, "随机", tint = if (state.shuffle) TaranaColors.Accent else TaranaColors.TextDim)
            }
            IconButton(onClick = PlayerController::previous, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(36.dp)) }
            PlayPauseButton(state, 72)
            IconButton(onClick = PlayerController::next, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(36.dp)) }
            IconButton(onClick = PlayerController::cycleRepeat) {
                Icon(
                    if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "循环",
                    tint = if (state.repeat != Player.REPEAT_MODE_OFF) TaranaColors.Accent else TaranaColors.TextDim,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: PlayerState) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = state.durationMs.coerceAtLeast(1)
    val value = dragging ?: (state.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.22f))
    Slider(
        value = value,
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { PlayerController.seekTo((it * dur).toLong()) }; dragging = null },
        colors = colors,
        thumb = {
            val size by animateFloatAsState(if (dragging != null) 18f else 12f, tween(150), label = "thumb")
            Box(Modifier.size(size.dp).background(Color.White, CircleShape))
        },
        track = { st ->
            SliderDefaults.Track(
                sliderState = st, colors = colors, modifier = Modifier.height(4.dp),
                drawStopIndicator = null, thumbTrackGapSize = 0.dp,
            )
        },
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatMs(((dragging ?: value) * dur).toLong()), color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Text(formatMs(state.durationMs), color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- 歌词 ----------------

@Composable
private fun LyricsPanel(cur: ShelfItem, state: PlayerState) {
    val lyricsRepo = TaranaApp.instance.lyrics
    val load by produceState<Load<Lyrics?>>(Load.Loading, cur.id) {
        value = Load.Loading
        // 歌单行有时没有时长，补上播放器的时长给 LRCLIB 匹配用
        val dur = (state.durationMs / 1000).toInt().takeIf { it > 0 }
        value = runCatching { lyricsRepo.lyrics(if (cur.duration == null && dur != null) cur.copy(duration = dur) else cur) }
            .fold({ Load.Ok(it) }, { Load.Err(it) })
    }
    when (val l = load) {
        is Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        is Load.Err, is Load.Ok -> {
            val ly = (l as? Load.Ok)?.value
            when (ly) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无歌词", color = TaranaColors.TextDim, style = MaterialTheme.typography.titleMedium) }
                is Lyrics.Plain -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
                    Text(ly.text, style = MaterialTheme.typography.titleLarge.copy(lineHeight = 40.sp), color = Color.White.copy(alpha = 0.85f))
                    Text("来源：${ly.source}", color = TaranaColors.TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 24.dp))
                }
                is Lyrics.Timed -> TimedLyrics(ly, state.positionMs)
            }
        }
    }
}

@Composable
private fun TimedLyrics(ly: Lyrics.Timed, positionMs: Long) {
    val list = rememberLazyListState()
    // 提前 150ms 高亮，抵消渲染延迟
    val active = remember(ly, positionMs) { ly.lines.indexOfLast { it.startMs <= positionMs + 150 } }
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) userScrolling = true
        else { kotlinx.coroutines.delay(2500); userScrolling = false }
    }
    LaunchedEffect(active, userScrolling) {
        if (active >= 0 && !userScrolling) {
            val h = list.layoutInfo.viewportSize.height
            list.animateScrollToItem(active, -(h / 3))
        }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 120.dp)) {
        itemsIndexed(ly.lines) { i, line ->
            val isActive = i == active
            val alpha by animateFloatAsState(if (isActive) 1f else if (i < active) 0.35f else 0.5f, tween(300), label = "a")
            val sc by animateFloatAsState(if (isActive) 1f else 0.94f, tween(300), label = "s")
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, lineHeight = 36.sp),
                color = Color.White.copy(alpha = alpha),
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { PlayerController.seekTo(line.startMs) }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
            )
        }
        item { Text("来源：${ly.source}", color = TaranaColors.TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 24.dp, start = 6.dp)) }
    }
}

// ---------------- 队列 ----------------

@Composable
private fun QueuePanel(state: PlayerState) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.index.coerceAtLeast(0))
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        itemsIndexed(state.queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
            val isCur = i == state.index
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (isCur) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { PlayerController.skipTo(i) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(t.thumb(120), Modifier.size(44.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                    Text(t.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall)
                }
                Text(formatDuration(t.duration), color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall)
                if (!isCur) IconButton(onClick = { PlayerController.remove(i) }) { Icon(Icons.Default.Close, "移除", tint = TaranaColors.TextDim) }
                else Spacer(Modifier.width(48.dp))
            }
        }
    }
}

