package app.tarana.pad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tarana.pad.R
import app.tarana.pad.TaranaApp
import app.tarana.pad.player.PlayerController
import app.tarana.pad.yt.Account
import androidx.compose.ui.res.painterResource

@Composable
fun TaranaRoot() {
    val nav = remember { Navigator() }
    val app = TaranaApp.instance
    val state by PlayerController.state.collectAsState()
    val signedIn by app.session.signedIn.collectAsState()
    var account by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(signedIn) {
        account = if (signedIn) runCatching { app.yt.account() }.getOrNull() else null
    }
    BackHandler(nav.canGoBack) { nav.back() }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize().background(TaranaColors.Bg)) {
            Row(Modifier.fillMaxSize()) {
                Rail(nav, account, signedIn)
                Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                    Box(Modifier.weight(1f)) { Content(nav) }
                    Box(Modifier.navigationBarsPadding()) { MiniPlayer(state) { nav.nowPlayingOpen = true } }
                }
            }
            AnimatedVisibility(
                nav.nowPlayingOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { NowPlaying(state) { nav.nowPlayingOpen = false } }
            if (nav.loginOpen) Box(Modifier.fillMaxSize().background(TaranaColors.Bg).systemBarsPadding()) {
                LoginScreen { nav.loginOpen = false }
            }
        }
    }
}

@Composable
private fun Rail(nav: Navigator, account: Account?, signedIn: Boolean) {
    val app = TaranaApp.instance
    var menu by remember { mutableStateOf(false) }
    NavigationRail(containerColor = TaranaColors.Surface, modifier = Modifier.systemBarsPadding(), header = {
        Icon(painterResource(R.drawable.ic_tarana), null, Modifier.padding(vertical = 16.dp).size(36.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
    }) {
        val back = nav.current !is Route.Home && nav.current !is Route.Explore && nav.current !is Route.Search && nav.current !is Route.Library
        if (back) NavigationRailItem(false, { nav.back() }, { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }, label = { Text("返回") })
        val items = listOf(
            Triple(Tab.HOME, Icons.Filled.Home, Icons.Outlined.Home),
            Triple(Tab.EXPLORE, Icons.Filled.Explore, Icons.Outlined.Explore),
            Triple(Tab.SEARCH, Icons.Filled.Search, Icons.Filled.Search),
            Triple(Tab.LIBRARY, Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
        )
        Spacer(Modifier.height(8.dp))
        items.forEach { (t, on, off) ->
            NavigationRailItem(
                selected = nav.tab == t,
                onClick = { nav.nowPlayingOpen = false; nav.select(t) },
                icon = { Icon(if (nav.tab == t) on else off, null) },
                label = { Text(t.label) },
            )
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(bottom = 16.dp)) {
            IconButton(onClick = { if (signedIn) menu = true else nav.loginOpen = true }) {
                if (account?.photo != null) Artwork(account.photo, Modifier.size(36.dp), CircleShape)
                else Icon(if (signedIn) Icons.Default.AccountCircle else Icons.Default.Login, if (signedIn) "账号" else "登录")
            }
            DropdownMenu(menu, { menu = false }) {
                account?.let { a ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                        a.handle?.let { Text(it, color = TaranaColors.TextDim, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text("退出登录") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) }, onClick = {
                    menu = false; app.session.signOut(); PageCache.clear()
                })
            }
        }
    }
}

@Composable
private fun Content(nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    val r = nav.current
    holder.SaveableStateProvider(nav.tab.name + r.key) {
        when (r) {
            Route.Home -> HomeScreen()
            Route.Explore -> ExploreScreen()
            Route.Search -> SearchScreen()
            Route.Library -> LibraryScreen()
            is Route.Album -> AlbumScreen(r)
            is Route.Artist -> ArtistScreen(r)
            is Route.Playlist -> PlaylistScreen(r)
            is Route.Browse -> BrowseScreen(r)
        }
    }
}

