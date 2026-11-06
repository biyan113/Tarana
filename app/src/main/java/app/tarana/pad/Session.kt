package app.tarana.pad

import android.content.Context
import android.webkit.CookieManager
import app.tarana.pad.yt.CookieUtil
import app.tarana.pad.yt.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * cookie 存在应用私有 SharedPreferences（沙盒内，其他应用读不到）。
 * Google 会轮换 SIDCC / __Secure-*PSIDCC，每次响应的 Set-Cookie 都要合并回来，
 * 否则会话会在数小时内被吊销（radif v0.2.0 的“资料库消失” bug）。
 */
class PrefsSessionStore(ctx: Context) : SessionStore {
    private val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _signedIn = MutableStateFlow(hasAuth(prefs.getString("cookie", "").orEmpty()))
    val signedIn: StateFlow<Boolean> = _signedIn

    @Volatile private var cached: String = prefs.getString("cookie", "").orEmpty()

    override fun cookie(): String = cached

    @Synchronized
    override fun mergeSetCookies(lines: List<String>) {
        val merged = CookieUtil.merge(cached, lines)
        if (merged != cached) save(merged)
    }

    @Synchronized
    fun save(cookie: String) {
        cached = cookie
        prefs.edit().putString("cookie", cookie).apply()
        _signedIn.value = hasAuth(cookie)
    }

    fun signOut() {
        save("")
        pageId = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    override var visitorData: String?
        get() = prefs.getString("visitor", null)
        set(v) { prefs.edit().putString("visitor", v).apply() }

    override var pageId: String?
        get() = prefs.getString("pageId", null)
        set(v) { prefs.edit().putString("pageId", v).apply() }

    companion object {
        fun hasAuth(c: String) = CookieUtil.get(c, "SAPISID") != null || CookieUtil.get(c, "__Secure-3PAPISID") != null
    }
}
