package app.tarana.pad.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.tarana.pad.yt.ItemKind
import app.tarana.pad.yt.ShelfItem
import app.tarana.pad.yt.ShelfMore

sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data object Explore : Route { override val key = "explore" }
    data object Search : Route { override val key = "search" }
    data object Library : Route { override val key = "library" }
    data class Album(val id: String) : Route { override val key = "album:$id" }
    data class Artist(val id: String) : Route { override val key = "artist:$id" }
    data class Playlist(val id: String) : Route { override val key = "playlist:$id" }
    data class Browse(val id: String, val params: String?, val title: String) : Route { override val key = "browse:$id:$params" }
}

enum class Tab(val root: Route, val label: String) {
    HOME(Route.Home, "首页"), EXPLORE(Route.Explore, "探索"), SEARCH(Route.Search, "搜索"), LIBRARY(Route.Library, "资料库")
}

/** 极简导航栈：每个 Tab 一条独立的栈，切换 Tab 时保留各自位置。 */
class Navigator {
    var tab by mutableStateOf(Tab.HOME)
        private set
    private val stacks = Tab.entries.associateWith { mutableStateListOf(it.root) }
    var nowPlayingOpen by mutableStateOf(false)
    var loginOpen by mutableStateOf(false)

    val current: Route get() = stacks.getValue(tab).last()
    val canGoBack: Boolean get() = nowPlayingOpen || loginOpen || stacks.getValue(tab).size > 1 || tab != Tab.HOME

    fun select(t: Tab) {
        if (t == tab) { val s = stacks.getValue(t); while (s.size > 1) s.removeAt(s.lastIndex) }
        tab = t
    }

    fun push(r: Route) {
        nowPlayingOpen = false
        val s = stacks.getValue(tab)
        if (s.last() != r) s.add(r)
    }

    fun back(): Boolean {
        when {
            loginOpen -> loginOpen = false
            nowPlayingOpen -> nowPlayingOpen = false
            stacks.getValue(tab).size > 1 -> stacks.getValue(tab).removeAt(stacks.getValue(tab).lastIndex)
            tab != Tab.HOME -> tab = Tab.HOME
            else -> return false
        }
        return true
    }

    fun open(item: ShelfItem) {
        when (item.kind) {
            ItemKind.ALBUM -> push(Route.Album(item.id))
            ItemKind.ARTIST -> push(Route.Artist(item.id))
            ItemKind.PLAYLIST -> push(Route.Playlist(item.id))
            ItemKind.CATEGORY -> push(Route.Browse(item.id, item.categoryParams, item.title))
            else -> Unit
        }
    }

    fun openMore(more: ShelfMore, title: String) {
        val pt = more.pageType.orEmpty()
        when {
            pt.contains("PLAYLIST") || (more.browseId.startsWith("VL") && more.params == null) -> push(Route.Playlist(more.browseId))
            pt.contains("ALBUM") -> push(Route.Album(more.browseId))
            pt.contains("ARTIST") && more.params == null -> push(Route.Artist(more.browseId))
            else -> push(Route.Browse(more.browseId, more.params, title))
        }
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val error: Throwable) : Load<Nothing>
}

/** 页面数据的进程内缓存，返回上一页不用重新请求。 */
object PageCache {
    private val map = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 40
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> get(k: String): T? = synchronized(map) { map[k] as T? }
    fun put(k: String, v: Any) = synchronized(map) { map[k] = v }
    fun clear() = synchronized(map) { map.clear() }
}

/** 加载一次数据（带缓存与重试）。返回 state 和 reload 函数。 */
@Composable
fun <T : Any> rememberLoad(key: String, loader: suspend () -> T): Pair<State<Load<T>>, () -> Unit> {
    var attempt by remember(key) { mutableStateOf(0) }
    val state = produceState<Load<T>>(PageCache.get<T>(key)?.let { Load.Ok(it) } ?: Load.Loading, key, attempt) {
        if (attempt == 0 && value is Load.Ok) return@produceState
        value = Load.Loading
        value = try {
            Load.Ok(loader().also { PageCache.put(key, it) })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Load.Err(e)
        }
    }
    return state to { attempt++ }
}

@Composable
fun OnStart(block: () -> Unit) = LaunchedEffect(Unit) { block() }
