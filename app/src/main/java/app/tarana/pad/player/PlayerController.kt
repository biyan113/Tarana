package app.tarana.pad.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tarana.pad.TaranaApp
import app.tarana.pad.yt.ArtistRef
import app.tarana.pad.yt.ItemKind
import app.tarana.pad.yt.ShelfItem
import app.tarana.pad.yt.Thumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val current: ShelfItem? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<ShelfItem> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
)

/**
 * 进程内单例，持有 MediaController，把播放状态暴露成 StateFlow 给 Compose。
 * 队列逻辑对应 radif 的 store/player：单曲点播 → 以它为种子开电台；
 * 列表播放到末尾 → 用最后一首续电台（自动播放）。
 */
object PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** videoId → 完整元数据（MediaItem 里只放了一部分） */
    private val meta = HashMap<String, ShelfItem>()
    private var radioToken: String? = null
    private var extending: Job? = null
    var autoplay = true

    private val pending = mutableListOf<(MediaController) -> Unit>()

    fun connect(ctx: Context) {
        if (controller != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        scope.launch {
            val c = MediaController.Builder(ctx, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            })
            pending.forEach { it(c) }
            pending.clear()
            sync()
            // 播放中每 250ms 刷新进度（歌词同步用）
            while (isActive) {
                delay(if (c.isPlaying) 250 else 1000)
                val s = _state.value
                if (s.current != null) _state.value = s.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0))
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    private fun sync() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { toShelf(c.getMediaItemAt(it)) }
        val idx = c.currentMediaItemIndex
        _state.value = PlayerState(
            current = queue.getOrNull(idx),
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0),
            queue = queue,
            index = idx,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            error = c.playerError?.let { StreamResolver.lastError ?: it.cause?.message ?: it.errorCodeName },
        )
        if (autoplay && queue.isNotEmpty() && idx >= queue.size - 3 && c.repeatMode == Player.REPEAT_MODE_OFF) extendRadio()
    }

    // ---------- 播放入口 ----------

    /** 播放一组曲目，从 [start] 开始。列表只有一首时自动开电台。 */
    fun play(items: List<ShelfItem>, start: Int = 0, shuffle: Boolean = false) {
        val playable = items.filter { it.isPlayable }
        if (playable.isEmpty()) return
        val startIdx = playable.indexOf(items.getOrNull(start)).coerceAtLeast(0)
        radioToken = null
        extending?.cancel()
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(playable.map(::toMediaItem), if (shuffle) 0 else startIdx, 0)
            c.prepare()
            c.play()
        }
        if (playable.size == 1) startRadio(playable[0], keepCurrent = true)
    }

    /** 以 [seed] 开电台。keepCurrent=true 时保留正在播的那首，只替换后面的队列。 */
    fun startRadio(seed: ShelfItem, keepCurrent: Boolean = false) {
        extending?.cancel()
        if (!keepCurrent) withController { c ->
            c.setMediaItems(listOf(toMediaItem(seed)), 0, 0); c.prepare(); c.play()
        }
        extending = scope.launch {
            val (tracks, token) = runCatching { TaranaApp.instance.yt.radio(seed.id) }.getOrElse { return@launch }
            radioToken = token
            val c = controller ?: return@launch
            // 去掉种子本身，替换当前之后的内容
            val idx = c.currentMediaItemIndex
            if (c.mediaItemCount > idx + 1) c.removeMediaItems(idx + 1, c.mediaItemCount)
            c.addMediaItems(tracks.filter { it.id != seed.id }.map(::toMediaItem))
        }
    }

    /** 播放一个服务端队列（艺人的随机播放 / 电台、专辑的 OLAK… 歌单） */
    fun playQueue(playlistId: String, shuffle: Boolean = false) {
        scope.launch {
            val (tracks, token) = runCatching { TaranaApp.instance.yt.watchQueue(playlistId) }.getOrElse { return@launch }
            play(if (shuffle) tracks.shuffled() else tracks, 0)
            radioToken = token
        }
    }

    private fun extendRadio() {
        if (extending?.isActive == true) return
        val c = controller ?: return
        val last = _state.value.queue.lastOrNull() ?: return
        extending = scope.launch {
            val yt = TaranaApp.instance.yt
            val token = radioToken
            val (tracks, next) = runCatching {
                if (token != null) yt.watchQueueMore(token) else yt.radio(last.id)
            }.getOrElse { return@launch }
            radioToken = next
            val have = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toHashSet()
            val fresh = tracks.filter { it.id !in have }
            if (fresh.isNotEmpty()) c.addMediaItems(fresh.map(::toMediaItem))
            else if (next == null) radioToken = null
        }
    }

    fun playNext(item: ShelfItem) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(item)) else c.addMediaItem(c.currentMediaItemIndex + 1, toMediaItem(item))
    }

    fun enqueue(item: ShelfItem) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(item)) else c.addMediaItem(toMediaItem(item))
    }

    fun enqueueAll(items: List<ShelfItem>) = withController { c ->
        if (c.mediaItemCount == 0) play(items) else c.addMediaItems(items.filter { it.isPlayable }.map(::toMediaItem))
    }

    // ---------- 控制 ----------

    fun toggle() = withController { c ->
        if (c.playerError != null) { c.prepare(); c.play() } else if (c.isPlaying) c.pause() else c.play()
    }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { c -> if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { c -> c.seekTo(index, 0); c.play() }
    fun remove(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---------- ShelfItem <-> MediaItem ----------

    private fun toMediaItem(item: ShelfItem): MediaItem {
        meta[item.id] = item
        val md = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artistLine)
            .setAlbumTitle(item.album)
            .setArtworkUri(item.thumb(720)?.let(Uri::parse))
            .setIsPlayable(true)
            .setExtras(
                bundleOf(
                    "albumId" to item.albumId,
                    "artistIds" to item.artists.map { it.id.orEmpty() }.toTypedArray(),
                    "artistNames" to item.artists.map { it.name }.toTypedArray(),
                    "duration" to (item.duration ?: -1),
                    "video" to (item.kind == ItemKind.VIDEO),
                )
            )
            .build()
        return PlaybackService.withUri(MediaItem.Builder().setMediaId(item.id).setMediaMetadata(md).build())
    }

    private fun toShelf(m: MediaItem): ShelfItem {
        meta[m.mediaId]?.let { return it }
        val md = m.mediaMetadata
        val ex: Bundle? = md.extras
        val ids = ex?.getStringArray("artistIds").orEmpty()
        val names = ex?.getStringArray("artistNames").orEmpty()
        return ShelfItem(
            kind = if (ex?.getBoolean("video") == true) ItemKind.VIDEO else ItemKind.SONG,
            id = m.mediaId,
            title = md.title?.toString().orEmpty(),
            subtitle = md.artist?.toString(),
            thumbnails = listOfNotNull(md.artworkUri?.toString()?.let { Thumb(it, 720, 720) }),
            artists = names.mapIndexed { i, n -> ArtistRef(ids.getOrNull(i)?.ifEmpty { null }, n) },
            album = md.albumTitle?.toString(),
            albumId = ex?.getString("albumId"),
            duration = ex?.getInt("duration")?.takeIf { it > 0 },
        ).also { meta[m.mediaId] = it }
    }
}
