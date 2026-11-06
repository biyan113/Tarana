package app.tarana.pad.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 把一次 googlevideo 的长连接拆成若干个 `&range=a-b` 小请求，逐段续读。
 *
 * googlevideo 会对长时间的单连接限速（约等于播放码率，很容易卡顿），而每个短请求都能拿到满速突发；
 * NewPipe 的 YoutubeHttpDataSource 也是这么做的。总长度取 URL 里的 `clen` 参数。
 * 非 googlevideo 地址原样透传。
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(private val upstream: DataSource) : DataSource {

    private var baseUri: Uri? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong() // 不含
    private var chunkOpen = false
    private var chunkRemaining = 0L
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val clen = uri.getQueryParameter("clen")?.toLongOrNull()
        if (uri.host?.endsWith("googlevideo.com") != true || clen == null) {
            passthrough = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        baseUri = uri
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length else clen
        openChunk()
        return end - dataSpec.position
    }

    private fun openChunk() {
        val to = minOf(position + CHUNK, end) - 1
        val uri = baseUri!!.buildUpon().appendQueryParameter("range", "$position-$to").build()
        upstream.open(DataSpec.Builder().setUri(uri).build())
        chunkOpen = true
        chunkRemaining = to - position + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (position >= end) return C.RESULT_END_OF_INPUT
        if (!chunkOpen || chunkRemaining <= 0) {
            if (chunkOpen) { upstream.close(); chunkOpen = false }
            openChunk()
        }
        val n = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (n == C.RESULT_END_OF_INPUT) {
            // 服务器提前结束本段：从当前位置开下一段
            upstream.close(); chunkOpen = false
            if (position >= end) return C.RESULT_END_OF_INPUT
            openChunk()
            return read(buffer, offset, length)
        }
        position += n
        chunkRemaining -= n
        return n
    }

    override fun getUri(): Uri? = if (passthrough) upstream.uri else baseUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (passthrough || chunkOpen) upstream.close()
        chunkOpen = false
        passthrough = false
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    companion object {
        /** 每段 1 MB：Opus 160k 约 50 秒音频 */
        const val CHUNK = 1L shl 20
    }
}
