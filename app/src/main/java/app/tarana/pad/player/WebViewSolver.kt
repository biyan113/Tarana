package app.tarana.pad.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.tarana.pad.yt.JsonParser
import app.tarana.pad.yt.arr
import app.tarana.pad.yt.get
import app.tarana.pad.yt.obj
import app.tarana.pad.yt.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 在一个不可见的 WebView（系统 V8）里运行 yt-dlp-ejs 求解器。
 *
 * - 求解器脚本来自 assets/ejs，WebView 不加载任何网页，只执行这两份脚本。
 * - base.js 约 3 MB，通过 JavascriptInterface 交给 JS 一次；第一次求解时让 ejs 输出“预处理后的播放器”，
 *   以播放器 id 为键留在 JS 内存里，之后同一版本的求解都走预处理结果（每次约几十毫秒）。
 * - WebView 必须在主线程创建和调用，这里调用方在 IO 线程，用 latch 等结果。
 */
class WebViewSolver(context: Context) : ChallengeSolver {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    @Volatile private var pendingPlayer: String? = null
    private val ready = CountDownLatch(1)
    private val bootstrap by lazy {
        val lib = app.assets.open("ejs/yt.solver.lib.min.js").bufferedReader().readText()
        val core = app.assets.open("ejs/yt.solver.core.js").bufferedReader().readText()
        "$lib\n;window.meriyah=lib.meriyah;window.astring=lib.astring;\n$core\n;window.__jsc=jsc;window.__pre={};'ok'"
    }

    inner class Bridge {
        /** JS 拉取待预处理的 base.js 文本（避免把 3 MB 塞进 evaluateJavascript 的源码里） */
        @JavascriptInterface
        fun player(): String = pendingPlayer.orEmpty()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (ready.count == 0L) return
        main.post {
            if (web != null) return@post
            val w = WebView(app)
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = false
            w.settings.allowContentAccess = false
            w.settings.blockNetworkLoads = true // 求解器不需要联网
            w.addJavascriptInterface(Bridge(), "TaranaBridge")
            // JavascriptInterface 要在页面加载之后才生效：先载入空白页，再注入求解器
            w.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (ready.count == 0L) return
                    view.evaluateJavascript(bootstrap) { ready.countDown() }
                }
            }
            w.loadDataWithBaseURL("about:blank", "<html><body></body></html>", "text/html", "utf-8", null)
            web = w
        }
        if (!ready.await(20, TimeUnit.SECONDS)) throw IOException("JS 求解器初始化超时")
    }

    override fun solve(player: PlayerScript, sig: List<String>, n: List<String>): Map<String, String> {
        ensureWebView()
        val requests = JsonArray(buildList {
            if (sig.isNotEmpty()) add(buildJsonObject { put("type", "sig"); put("challenges", JsonArray(sig.map(::JsonPrimitive))) })
            if (n.isNotEmpty()) add(buildJsonObject { put("type", "n"); put("challenges", JsonArray(n.map(::JsonPrimitive))) })
        })
        val pid = JsonPrimitive(player.id).toString()
        // 页面可能被重新加载（JS 全局变量随之丢失），每次求解前检查，必要时重新注入求解器
        val script = """
            (function(){
              try {
                if (typeof window.__jsc !== 'function') { $bootstrap; }
                window.__pre = window.__pre || {};
                var reqs = $requests, pid = $pid, out;
                if (window.__pre[pid]) {
                  out = window.__jsc({type:'preprocessed', preprocessed_player: window.__pre[pid], requests: reqs});
                } else {
                  out = window.__jsc({type:'player', player: TaranaBridge.player(), requests: reqs, output_preprocessed: true});
                  window.__pre = {}; window.__pre[pid] = out.preprocessed_player; delete out.preprocessed_player;
                }
                return JSON.stringify(out);
              } catch (e) { return JSON.stringify({type:'error', error: String(e && e.stack || e)}); }
            })()
        """.trimIndent()

        val result = AtomicReference<String?>()
        val done = CountDownLatch(1)
        main.post {
            pendingPlayer = player.code
            web!!.evaluateJavascript(script) { v -> result.set(v); done.countDown() }
        }
        if (!done.await(30, TimeUnit.SECONDS)) throw IOException("JS 求解超时")
        // evaluateJavascript 回传的是 JSON 编码后的字符串，需要先解一层
        val raw = result.get()?.let { (JsonParser.parseToJsonElement(it) as? JsonPrimitive)?.content } ?: throw IOException("JS 求解无返回")
        return parseOutput(raw)
    }

    companion object {
        /** 解析 ejs main() 的输出：{type:'result', responses:[{type:'result', data:{challenge: answer}} | {type:'error', error}]} */
        fun parseOutput(raw: String): Map<String, String> {
            val out = JsonParser.parseToJsonElement(raw)
            if (out["type"].str != "result") throw IOException("JS 求解失败：${out["error"].str}")
            val map = HashMap<String, String>()
            for (r in out["responses"].arr) {
                if (r["type"].str != "result") throw IOException("JS 求解失败：${r["error"].str?.take(200)}")
                r["data"].obj?.forEach { (k, v) -> v.str?.let { map[k] = it } }
            }
            return map
        }
    }
}
