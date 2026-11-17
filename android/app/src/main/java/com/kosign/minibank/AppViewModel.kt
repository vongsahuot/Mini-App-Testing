package com.kosign.minibank

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kosign.minibank.sdk.HostApi
import com.kosign.minibank.sdk.HostException
import com.kosign.minibank.sdk.StarlightSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Sends a bridge reply for one call: (ok, payload). */
typealias BridgeReply = (ok: Boolean, payload: JSONObject) -> Unit

/** The open Link WebView Mini App. [id] changes on every open, so late replies never reach a newer page. */
data class WebSession(val detail: MiniAppDetail, val id: String = UUID.randomUUID().toString())

/** The open SDK Mini App and the host API instance MB App gave to it. */
class SdkLaunch(val detail: MiniAppDetail, val host: AppViewModel.SdkHost)

/**
 * MB App (lane APP). Single ViewModel with Compose snapshot state; the shared payment UI lives in
 * [PaymentFlow] and App-to-App links in [A2aLinks]. Every APP / U step is reported with [Api.trace].
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    internal val prefs = app.getSharedPreferences("mbapp", Context.MODE_PRIVATE)

    /** Stable per-install device id (sent at login). */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    // ---------- UI state (Compose snapshot state) ----------
    var screen by mutableStateOf<Screen>(Screen.Login)
        private set
    var session by mutableStateOf<BankSession?>(null)
        private set
    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    var miniApps by mutableStateOf<MiniAppsData?>(null)
        private set
    var miniAppsLoading by mutableStateOf(false)
        private set

    /** Blocking progress overlay text, or null. */
    var busy by mutableStateOf<String?>(null)
        internal set

    var webApp by mutableStateOf<WebSession?>(null)
        private set
    var nativeApp by mutableStateOf<NativeAppState?>(null)
        private set
    var sdkApp by mutableStateOf<SdkLaunch?>(null)
        private set

    var consentRequest by mutableStateOf<ConsentRequest?>(null)
        private set
    var linkRequest by mutableStateOf<LinkRequest?>(null)
        internal set
    var pinPrompt by mutableStateOf<PinPrompt?>(null)
        private set
    var notice by mutableStateOf<Notice?>(null)
        private set

    /** Latest OTP from a push notification; shown as a hint on the OTP dialog. */
    var latestOtp by mutableStateOf<Otp?>(null)
        private set

    /** Snackbar queue (notifications, errors). */
    val messages = Channel<String>(Channel.UNLIMITED)

    val payment = PaymentFlow(this)
    val links = A2aLinks(this)

    private var pollJob: Job? = null
    private val pollNow = Channel<Unit>(Channel.CONFLATED)
    private var lastNotificationId: String? = null

    fun toast(text: String) {
        messages.trySend(text)
    }

    internal fun requireToken(): String =
        session?.sessionToken ?: throw ApiException(401, "NOT_SIGNED_IN", "Not signed in")

    // =============================================================================================
    // Login / home / notifications
    // =============================================================================================

    fun login(username: String, pin: String) {
        if (busy != null) return
        viewModelScope.launch {
            busy = "Signing in…"
            try {
                val r = Api.login(username.trim(), pin, deviceId)
                applyLogin(username.trim(), r)
                screen = Screen.Home
                startPolling()
                links.onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                toast(if (e.code == "INVALID_CREDENTIALS") "Wrong username or PIN" else e.message)
            } catch (e: Exception) {
                toast(e.message ?: "Login failed")
            } finally {
                busy = null
            }
        }
    }

    private fun applyLogin(username: String, r: JSONObject) {
        val s = BankSession(
            sessionToken = r.getString("sessionToken"),
            username = username,
            customerId = r.optString("customerId"),
            name = r.optString("name"),
            phone = r.optString("phone"),
            segment = r.optString("segment"),
        )
        // Per-customer WebView data store: a different customer never sees the previous one's partner cookies.
        val previous = prefs.getString(KEY_LAST_CUSTOMER, null)
        if (previous != null && previous != s.customerId) {
            Log.i(TAG, "Customer changed ($previous → ${s.customerId}): clearing WebView cookies and storage")
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        prefs.edit().putString(KEY_LAST_CUSTOMER, s.customerId).apply()
        session = s
        r.optJSONArray("accounts")?.let { accounts = Account.list(it) }
    }

    fun logout() {
        closeEverything()
        pollJob?.cancel()
        pollJob = null
        session = null
        accounts = emptyList()
        miniApps = null
        latestOtp = null
        screen = Screen.Login
    }

    fun refreshAccounts() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                accounts = Account.list(Api.accounts(s.sessionToken))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refresh accounts failed: ${e.message}")
            }
        }
    }

    /** Polls `GET /notifications?after=<lastId>` every 3 s while signed in. */
    private fun startPolling() {
        pollJob?.cancel()
        lastNotificationId = null
        pollJob = viewModelScope.launch {
            var first = true
            while (isActive && session != null) {
                pollOnce(silent = first)
                first = false
                withTimeoutOrNull(POLL_MS) { pollNow.receive() }
            }
        }
    }

    /** Ask for an early poll (e.g. right after an OTP payment was initiated). */
    fun pollNotificationsSoon() {
        pollNow.trySend(Unit)
    }

    private suspend fun pollOnce(silent: Boolean) {
        val s = session ?: return
        try {
            val list = Api.notifications(s.sessionToken, lastNotificationId)
            for (i in 0 until list.length()) {
                val n = list.getJSONObject(i)
                n.str("id")?.let { lastNotificationId = it }
                if (silent) continue // first poll after login only sets the cursor
                val kind = n.optString("kind")
                val title = n.optString("title")
                val body = n.optString("body")
                if (kind == "OTP") {
                    val code = OTP_REGEX.find(body)?.value ?: OTP_REGEX.find(title)?.value
                    if (code != null) latestOtp = Otp(code, n.str("ref"))
                    toast(if (code != null) "🔑 OTP $code · $title" else "🔑 $title: $body")
                } else {
                    toast(listOf(title, body).filter { it.isNotBlank() }.joinToString(" — "))
                    refreshAccounts()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 401 && session?.sessionToken == s.sessionToken) {
                toast("Session expired — sign in again")
                logout()
            } else {
                Log.w(TAG, "notifications poll failed: ${e.message}")
            }
        } catch (e: JSONException) {
            Log.w(TAG, "bad notifications payload: ${e.message}")
        }
    }

    // =============================================================================================
    // Mini App list (1:1, 1:8, 1:9)
    // =============================================================================================

    fun openMiniApps() {
        Api.trace("APP", "1:1", null, "Open Mini App feature", null, session?.customerId)
        screen = Screen.MiniApps
        loadMiniApps()
    }

    fun backToHome() {
        screen = Screen.Home
    }

    private fun cacheKey(what: String) = "miniApps.$what.${session?.customerId}"

    private fun loadMiniApps() {
        val s = session ?: return
        val cached = prefs.getString(cacheKey("json"), null)?.let {
            runCatching { MiniAppsData.parse(JSONObject(it), prefs.getString(cacheKey("etag"), null), "cache") }.getOrNull()
        }
        miniApps = cached
        viewModelScope.launch {
            miniAppsLoading = true
            try {
                var r = Api.miniApps(s.sessionToken, cached?.etag)
                if (r.status == 304 && cached == null) r = Api.miniApps(s.sessionToken, null)
                if (r.status == 304 && cached != null) {
                    Api.trace("APP", "1:8", null, "Mini App list + ETag (or 304 Not Modified)", "304", cached.etag)
                    miniApps = cached.copy(source = "cache (304)")
                } else {
                    val body = r.body ?: JSONObject()
                    val data = MiniAppsData.parse(body, r.etag, "network (200)")
                    Api.trace("APP", "1:8", null, "Mini App list + ETag (or 304 Not Modified)", "200", data.etag)
                    prefs.edit()
                        .putString(cacheKey("json"), body.toString())
                        .putString(cacheKey("etag"), data.etag)
                        .apply()
                    miniApps = data
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Could not load Mini Apps")
            } finally {
                miniAppsLoading = false
            }
        }
    }

    /** Called by the list screen once a list from the server (200 or 304) has been composed (1:9). */
    fun onMiniAppsRendered(data: MiniAppsData) {
        if (data.source == "cache") return
        Api.trace("U", "1:9", null, "Display Mini App list", "${data.allApps.size} apps · ${data.source}", null)
    }

    // =============================================================================================
    // Open a Mini App: detail (2:1, 2:8) → consent (2:9–2:23) → launch by type
    // =============================================================================================

    fun openApp(app: MiniApp) {
        if (busy != null || session == null) return
        viewModelScope.launch {
            Api.trace("APP", "2:1", app.type, "Tap Mini App", app.name, app.appId)
            val d = loadDetail(app.appId, app.type) ?: return@launch
            if (!ensureConsent(d)) return@launch
            launchApp(d)
        }
    }

    /** GET /mini-apps/{appId} with the busy overlay; shows the error and returns null on failure. */
    internal suspend fun loadDetail(appId: String, flowHint: String?): MiniAppDetail? {
        busy = "Opening…"
        try {
            return fetchDetail(appId, trace = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == "REGISTRATION_FAILED") {
                Api.trace("APP", "2:19d", flowHint, "Error", "${e.code}: ${e.message}", appId)
                Api.trace("APP", "2:19e", flowHint, "Show “Can't open right now, try again”; stop here", e.code, appId)
                toast("Can't open right now, try again")
            } else {
                toast(detailErrorText(e))
            }
            return null
        } catch (e: JSONException) {
            toast("Bad Mini App detail: ${e.message}")
            return null
        } finally {
            busy = null
        }
    }

    internal suspend fun fetchDetail(appId: String, trace: Boolean): MiniAppDetail {
        val d = MiniAppDetail.parse(Api.miniApp(requireToken(), appId))
        if (d.type == Types.A2A) d.returnLink?.let { rememberReturnLink(d.appId, it) }
        if (trace) {
            val title = when (d.type) {
                Types.API -> "Type, native screen config, consent status"
                Types.SDK -> "Type, sdkModule, minSdkVersion, consent status"
                Types.A2A -> "Type, appLink, storeUrl, consent status"
                else -> "Type, entryUrl, allowedOrigins, consent status"
            }
            Api.trace("APP", "2:8", d.type, title, "type=${d.type}, consent=${d.consent.status}, registered=${d.registered}", d.appId)
        }
        return d
    }

    private fun detailErrorText(e: ApiException): String = when (e.code) {
        "APP_DISABLED" -> "This Mini App is not available right now"
        "NOT_ELIGIBLE" -> "This Mini App isn't available for your account"
        else -> if (e.status == 404) "Mini App not found" else e.message
    }

    /** Native consent sheet when consent is NOT_ACCEPTED. Returns true when the app may open. */
    internal suspend fun ensureConsent(d: MiniAppDetail): Boolean {
        if (d.consent.accepted) return true
        val req = ConsentRequest(d.name, d.icon, d.merchantName, d.consent.scopes.map { it.label })
        consentRequest = req
        Api.trace("U", "2:9", d.type, "Show consent screen", req.scopeLabels.joinToString(), d.appId)
        val accepted = try {
            req.result.await()
        } finally {
            if (consentRequest === req) consentRequest = null
        }
        if (!accepted) {
            Api.trace("APP", "2:10", d.type, "Accept consent (decline → close)", "declined → close", d.appId)
            return false
        }
        Api.trace("APP", "2:10", d.type, "Accept consent (decline → close)", "accepted v${d.consent.currentVersion}", d.appId)
        busy = "Connecting to ${d.merchantName}…"
        return try {
            val r = Api.consent(requireToken(), d.appId, d.consent.currentVersion)
            Api.trace("APP", "2:23", d.type, "OK (consent ACCEPTED)", "registered=${r.optBoolean("registered", true)}", d.appId)
            true
        } catch (e: ApiException) {
            Api.trace("APP", "2:19d", d.type, "Error", "${e.code}: ${e.message}", d.appId)
            Api.trace("APP", "2:19e", d.type, "Show “Can't open right now, try again”; stop here", e.code, d.appId)
            toast("Can't open right now, try again")
            false
        } finally {
            busy = null
        }
    }

    fun answerConsent(accept: Boolean) {
        consentRequest?.result?.complete(accept)
    }

    private suspend fun launchApp(d: MiniAppDetail) {
        when (d.type) {
            Types.WEBVIEW -> openWebView(d)
            Types.API -> openNative(d)
            Types.SDK -> openSdk(d)
            Types.A2A -> links.openPartnerApp(d)
            else -> toast("Unsupported Mini App type '${d.type}'")
        }
    }

    // =============================================================================================
    // Link WebView (type webview)
    // =============================================================================================

    private fun openWebView(d: MiniAppDetail) {
        if (d.entryUrl == null) {
            toast("This Mini App has no entry URL")
            return
        }
        Api.trace(
            "APP", "2:24", Types.WEBVIEW,
            "Open WebView: per-customer data store; bridges (login, openPayment, close) only for allowedOrigins, main frame",
            "${d.entryUrl} · allowed ${d.allowedOrigins.joinToString()}", d.appId,
        )
        webApp = WebSession(d)
        screen = Screen.WebApp
    }

    /** The entry page did not load (error or ~15 s timeout): the screen shows a native error + Retry (2:25a). */
    fun onWebLoadFailed(reason: String) {
        val web = webApp ?: return
        Api.trace("APP", "2:25a", Types.WEBVIEW, "Native error screen + Retry", reason, web.detail.appId)
    }

    /**
     * Entry point for every `MBAppNative.postMessage(json)` call. Called on the main thread with the origin of
     * the WebView's current main-frame URL (read on the main thread too).
     *
     * Limitation: `addJavascriptInterface` is exposed to every frame, and without androidx.webkit
     * (`WebViewCompat.addWebMessageListener`) the host cannot tell which frame called. The origin check uses
     * the main frame's URL; see README "Limitations".
     */
    fun onBridgeMessage(json: String, pageOrigin: String?, reply: (id: String, ok: Boolean, payload: JSONObject) -> Unit) {
        val msg = runCatching { JSONObject(json) }.getOrNull()
        if (msg == null) {
            Log.w(TAG, "Ignoring malformed bridge message: $json")
            return
        }
        val id = msg.optString("id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val web = webApp ?: return
        val d = web.detail

        // Only reply while the same Mini App session is still open.
        val send: BridgeReply = { ok, payload -> if (webApp?.id == web.id) reply(id, ok, payload) }
        val originOk = pageOrigin != null && pageOrigin in d.allowedOrigins

        fun refuse() {
            Api.trace("APP", null, Types.WEBVIEW, "Bridge call refused", "$method from ${pageOrigin ?: "unknown origin"}: ORIGIN_NOT_ALLOWED", d.appId)
            send(false, bridgeError("ORIGIN_NOT_ALLOWED", "Origin $pageOrigin is not allowed to use the bridge"))
        }

        when (method) {
            "login" -> {
                Api.trace("APP", "2:26", Types.WEBVIEW, "JS bridge: login()", pageOrigin, d.appId)
                if (!originOk) return refuse()
                Api.trace("APP", "2:27", Types.WEBVIEW, "Check caller: allowedOrigin + main frame",
                    "origin $pageOrigin allowed (main-frame URL)", d.appId)
                runBridge(send) { JSONObject().put("authCode", hostLogin(d, Types.WEBVIEW)) }
            }
            "openPayment" -> {
                val order = params.optJSONObject("order")
                val checksum = params.optString("checksum")
                Api.trace("APP", "3:5", Types.WEBVIEW, "JS Bridge openPayment(order, checksum)", pageOrigin, order?.optString("orderId"))
                if (!originOk) return refuse()
                if (order == null || checksum.isBlank()) {
                    send(false, bridgeError("ERROR", "order and checksum are required"))
                    return
                }
                runBridge(send) { hostOpenPayment(d, Types.WEBVIEW, order, checksum).toJson() }
            }
            "close" -> {
                if (!originOk) return refuse()
                send(true, JSONObject())
                closeMiniApp("bridge close()")
            }
            else -> send(false, bridgeError("ERROR", "Unknown method '$method'"))
        }
    }

    private fun runBridge(send: BridgeReply, block: suspend () -> JSONObject) {
        viewModelScope.launch {
            try {
                send(true, block())
            } catch (e: BridgeError) {
                send(false, bridgeError(e.code, e.message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "bridge call failed", e)
                send(false, bridgeError("ERROR", e.message ?: e.toString()))
            }
        }
    }

    private fun bridgeError(code: String, message: String): JSONObject =
        JSONObject().put("code", code).put("message", message)

    // =============================================================================================
    // Host side shared by the WebView bridge and the SDK host API
    // =============================================================================================

    /** login(): POST /mini-apps/{appId}/auth-code {} → authCode (2:34). Throws [BridgeError]. */
    private suspend fun hostLogin(d: MiniAppDetail, flow: String): String {
        busy = "Signing in to ${d.name}…"
        try {
            val r = Api.authCode(requireToken(), d.appId)
            val code = r.getString("authCode")
            Api.trace("APP", "2:34", flow, "authCode", "expiresIn ${r.optInt("expiresIn", 60)}s", d.appId)
            return code
        } catch (e: ApiException) {
            val code = if (e.code == "CONSENT_REQUIRED" || e.code == "NOT_REGISTERED") "CONSENT_REQUIRED" else "ERROR"
            throw BridgeError(code, e.message)
        } finally {
            busy = null
        }
    }

    /**
     * openPayment(order, checksum): one sheet at a time (3:5a, else SHEET_OPEN), verify with the appId of the
     * open Mini App (3:13, or 3:57 → FAILED), then the shared payment UI.
     */
    private suspend fun hostOpenPayment(d: MiniAppDetail, flow: String, order: JSONObject, checksum: String): PayResult {
        val orderId = order.optString("orderId").ifBlank { null }
        if (!payment.reserve()) {
            Api.trace("APP", null, flow, "openPayment refused", "SHEET_OPEN: a payment sheet is already open", orderId)
            throw BridgeError("SHEET_OPEN", "A payment sheet is already open")
        }
        try {
            val check = if (flow == Types.SDK) "Check caller: SDK instance of the open Mini App; one sheet at a time; take appId from the open Mini App"
            else "Check caller: allowedOrigin + main frame; one sheet at a time; take appId from the open Mini App"
            Api.trace("APP", "3:5a", flow, check, "appId ${d.appId} (from the open Mini App)", orderId)
            busy = "Checking order…"
            val info = try {
                PaymentInfo.from(Api.verify(requireToken(), d.appId, order, checksum))
            } catch (e: ApiException) {
                Api.trace("APP", "3:57", flow, "Error", "${e.code}: ${e.message}", orderId)
                return PayResult("FAILED", null, e.code)
            } catch (e: JSONException) {
                Api.trace("APP", "3:57", flow, "Error", "bad PaymentInfo: ${e.message}", orderId)
                return PayResult("FAILED", null, "ERROR")
            } finally {
                busy = null
            }
            Api.trace("APP", "3:13", flow, "Payment info", "${money(info.amount, info.currency)} · ${info.merchantName}", info.paymentRef)
            return payment.run(info)
        } finally {
            payment.release()
        }
    }

    /** Closes the open WebView / SDK Mini App (4:20, 4:21, 4:22). [reason] e.g. "bridge close()", "native X". */
    fun closeMiniApp(reason: String) {
        val web = webApp
        val sdk = sdkApp
        if (web == null && sdk == null) {
            if (nativeApp != null) closeNative()
            return
        }
        val flow = if (web != null) Types.WEBVIEW else Types.SDK
        val appId = web?.detail?.appId ?: sdk?.detail?.appId
        Api.trace("APP", "4:20", flow,
            if (web != null) "JS bridge: close() (or user taps native X)" else "Host API: close() (or user taps native X)",
            reason, appId)
        payment.cancelAll()
        sdk?.host?.release()
        webApp = null
        sdkApp = null
        busy = null
        screen = Screen.MiniApps
        Api.trace("APP", "4:21", flow,
            if (web != null) "Close WebView, remove bridges; keep the customer's data store"
            else "Close SDK screens, release the host API; SDK keeps no bank data",
            null, appId)
        Api.trace("U", "4:22", flow, "Back to native Mini App list", null, appId)
    }

    private fun closeEverything() {
        consentRequest?.result?.complete(false)
        linkRequest?.result?.complete(false)
        pinPrompt?.result?.complete(null)
        notice?.done?.complete(Unit)
        payment.cancelAll()
        sdkApp?.host?.release()
        webApp = null
        sdkApp = null
        nativeApp = null
        busy = null
    }

    // =============================================================================================
    // SDK embedding (type sdk)
    // =============================================================================================

    private suspend fun openSdk(d: MiniAppDetail) {
        val bundled = StarlightSdk.VERSION
        val moduleOk = d.sdkModule == null || d.sdkModule == StarlightSdk.MODULE
        val versionOk = compareVersions(bundled, d.minSdkVersion) >= 0
        val ok = d.sdkEnabled && moduleOk && versionOk
        Api.trace(
            "APP", "2:24", Types.SDK,
            "Check kill switch + bundled SDK ≥ minSdkVersion; start SDK with host API only (login, openPayment, close)",
            "sdkEnabled=${d.sdkEnabled}, module=${d.sdkModule}, bundled $bundled, min ${d.minSdkVersion} → ${if (ok) "start" else "refuse"}",
            d.appId,
        )
        if (!ok) {
            val why = when {
                !d.sdkEnabled -> "${d.name} is switched off for now (kill switch)."
                !moduleOk -> "This version of MB App doesn't include ${d.sdkModule}."
                else -> "Update MB App: ${d.name} needs SDK ${d.minSdkVersion} (bundled $bundled)."
            }
            Api.trace("APP", "2:25a", Types.SDK, "Native error screen; crash reported; ops can switch the SDK off", why, d.appId)
            showNotice("Can't open ${d.name}", why)
            return
        }
        sdkApp = SdkLaunch(d, SdkHost(d, UUID.randomUUID().toString()))
        screen = Screen.Sdk
    }

    /** Trace sink handed to the SDK (it records its own SDK / U steps). */
    fun sdkTrace(actor: String, step: String?, title: String, detail: String?, ref: String?) {
        Api.trace(actor, step, Types.SDK, title, detail, ref)
    }

    /** The host API given to exactly one SDK instance; released when that Mini App closes. */
    inner class SdkHost(private val detail: MiniAppDetail, val instanceId: String) : HostApi {
        @Volatile
        private var active = true

        fun release() {
            active = false
        }

        private fun current(): Boolean = active && sdkApp?.host === this

        override suspend fun login(): String {
            Api.trace("APP", "2:26", Types.SDK, "Host API: login()", null, detail.appId)
            if (!current()) {
                Api.trace("APP", null, Types.SDK, "Host API call refused", "login() from a closed SDK instance", detail.appId)
                throw HostException("ERROR", "This SDK instance is closed")
            }
            Api.trace("APP", "2:27", Types.SDK, "Check caller: the SDK instance MB App started for this appId",
                "instance ${instanceId.take(8)} · ${detail.appId}", detail.appId)
            // Runs in the ViewModel scope so the SDK leaving composition doesn't cut a call in half.
            return viewModelScope.async {
                try {
                    hostLogin(detail, Types.SDK)
                } catch (e: BridgeError) {
                    throw HostException(e.code, e.message)
                }
            }.await()
        }

        override suspend fun openPayment(order: JSONObject, checksum: String): JSONObject {
            val orderId = order.optString("orderId").ifBlank { null }
            Api.trace("APP", "3:5", Types.SDK, "Host API: openPayment(order, checksum)", null, orderId)
            if (!current()) {
                Api.trace("APP", null, Types.SDK, "Host API call refused", "openPayment() from a closed SDK instance", orderId)
                throw HostException("ERROR", "This SDK instance is closed")
            }
            return viewModelScope.async {
                try {
                    hostOpenPayment(detail, Types.SDK, order, checksum).toJson()
                } catch (e: BridgeError) {
                    throw HostException(e.code, e.message)
                }
            }.await()
        }

        override fun close() {
            if (current()) closeMiniApp("host close()")
        }
    }

    // =============================================================================================
    // API linkage (type api): bank-built native screens, partner content is data only
    // =============================================================================================

    private fun openNative(d: MiniAppDetail) {
        Api.trace("APP", "2:L1", Types.API,
            "Open the bank-built native screens for this Mini App; no WebView, no partner code on the device",
            d.screens.joinToString(), d.appId)
        nativeApp = NativeAppState(d)
        screen = Screen.NativeApp
        nativeLoad("catalog")
    }

    fun nativeLoad(operation: String) {
        val st = nativeApp ?: return
        st.tab = operation
        st.loading = true
        st.error = null
        viewModelScope.launch {
            val appId = st.detail.appId
            try {
                val r = Api.content(requireToken(), appId, operation)
                val rows = contentRows(r.opt("data"), operation)
                if (operation == "orders") st.orders = rows.map { OrderRow.from(it) } else st.catalog = rows.map { CatalogRow.from(it) }
                st.cached = r.optBoolean("cached", false)
                Api.trace("APP", "2:L13", Types.API, "Content",
                    "$operation · ${rows.size} rows${if (st.cached) " (cached)" else ""}", appId)
                Api.trace("U", "2:L14", Types.API, "Show native screen", operation, appId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? ApiException)?.code ?: "BAD_CONTENT"
                Api.trace("APP", "2:L8d", Types.API, "Error", "$operation · $code: ${e.message}", appId)
                Api.trace("APP", "2:L8e", Types.API, "Show “Service unavailable, try again”; stay in MB App", code, appId)
                st.error = "Service unavailable, try again"
            } finally {
                st.loading = false
            }
        }
    }

    fun nativeBuy(item: CatalogRow) {
        val st = nativeApp ?: return
        st.confirm = item
        st.orderError = null
        st.idempotencyKey = UUID.randomUUID().toString() // reused if the customer retries this confirmation
    }

    fun nativeConfirm() {
        val st = nativeApp ?: return
        val item = st.confirm ?: return
        if (st.ordering) return
        val appId = st.detail.appId
        Api.trace("APP", "3:L15", Types.API, "Confirm order on native screen", "${item.itemId} · ${item.title}", appId)
        if (!payment.reserve()) {
            toast("A payment is already open")
            return
        }
        st.ordering = true
        st.orderError = null
        viewModelScope.launch {
            try {
                val info = try {
                    PaymentInfo.from(Api.createOrder(requireToken(), appId, item.itemId, st.idempotencyKey))
                } catch (e: ApiException) {
                    Api.trace("APP", "3:L24d", Types.API, "Error", "${e.code}: ${e.message}", appId)
                    st.orderError = when (e.code) {
                        "PARTNER_UNAVAILABLE" -> "The cinema is not answering right now. Try again."
                        "BAD_CHECKSUM" -> "The cinema sent an invalid order. Try again later."
                        else -> e.message
                    } + " (${e.code})"
                    Api.trace("U", "3:L24e", Types.API, "Show error on native screen", e.code, appId)
                    return@launch
                } finally {
                    st.ordering = false
                }
                Api.trace("APP", "3:L28", Types.API, "Payment info",
                    "${money(info.amount, info.currency)} · ${info.merchantName}", info.paymentRef)
                val result = payment.run(info)
                st.confirm = null
                st.result = NativeResult(info, result)
                Api.trace("U", "3:L29", Types.API, "Show native result screen (SUCCESS / FAILED / PROCESSING)", result.status, info.paymentRef)
                Api.trace("APP", "3:L30", Types.API,
                    "Result comes from the bank's own Confirm / Get Payment Status response; no callback to partner code",
                    result.status, info.paymentRef)
            } finally {
                payment.release()
            }
        }
    }

    /** Back inside the native Mini App: result → list, confirmation → list, list → close. */
    fun nativeBack() {
        val st = nativeApp ?: return
        when {
            st.result != null -> nativeDone(st.tab)
            st.confirm != null && !st.ordering -> st.confirm = null
            else -> closeNative()
        }
    }

    /** Leave the result screen and show [tab] ("catalog" or "orders"). */
    fun nativeDone(tab: String) {
        val st = nativeApp ?: return
        st.result = null
        nativeLoad(tab)
    }

    fun closeNative() {
        payment.cancelAll()
        nativeApp = null
        screen = Screen.MiniApps
    }

    // =============================================================================================
    // Dialog helpers (PIN unlock, notices, link confirmation) and external links
    // =============================================================================================

    /** PIN unlock for App-to-App (the mock "PIN / biometric"): signs the same customer in again. */
    internal suspend fun unlockWithPin(title: String, message: String): Boolean {
        val s = session ?: return false
        var error: String? = null
        while (true) {
            val p = PinPrompt(title, message, error)
            pinPrompt = p
            val pin = try {
                p.result.await()
            } finally {
                if (pinPrompt === p) pinPrompt = null
            }
            if (pin == null) return false
            busy = "Checking PIN…"
            try {
                applyLogin(s.username, Api.login(s.username, pin, deviceId))
                refreshAccounts()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = if (e.code == "INVALID_CREDENTIALS") "Wrong PIN" else e.message
            } finally {
                busy = null
            }
        }
    }

    fun answerPin(pin: String?) {
        pinPrompt?.result?.complete(pin)
    }

    fun answerLink(link: Boolean) {
        linkRequest?.result?.complete(link)
    }

    internal suspend fun showNotice(title: String, message: String) {
        val n = Notice(title, message)
        notice = n
        try {
            n.done.await()
        } finally {
            if (notice === n) notice = null
        }
    }

    fun dismissNotice() {
        notice?.done?.complete(Unit)
    }

    /** Shows the login screen (a link arrived while signed out). */
    internal fun showLogin() {
        closeEverything()
        screen = Screen.Login
    }

    /** Opens an http(s) URL in another app (browser / partner app). Returns false when nothing can handle it. */
    internal fun openExternal(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") {
            Log.w(TAG, "Refusing non-http(s) link $url")
            return false
        }
        return try {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $url")
            false
        }
    }

    private fun rememberReturnLink(appId: String, link: String) {
        prefs.edit()
            .putString("$KEY_RETURN_LINK.$appId", link)
            .putString("$KEY_RETURN_LINK.last", link)
            .apply()
    }

    /** returnLink of [appId] (GET /mini-apps/{appId}, no trace), else the last one seen, else any a2a app's. */
    internal suspend fun returnLinkFor(appId: String?): String? {
        if (!appId.isNullOrBlank()) {
            try {
                fetchDetail(appId, trace = false).returnLink?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "returnLink lookup for $appId failed: ${e.message}")
            }
            prefs.getString("$KEY_RETURN_LINK.$appId", null)?.let { return it }
        }
        prefs.getString("$KEY_RETURN_LINK.last", null)?.let { return it }
        val a2a = miniApps?.allApps?.firstOrNull { it.type == Types.A2A } ?: return null
        return try {
            fetchDetail(a2a.appId, trace = false).returnLink
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AppViewModel"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_LAST_CUSTOMER = "lastCustomerId"
        private const val KEY_RETURN_LINK = "a2a.returnLink"
        private const val POLL_MS = 3_000L
        private val OTP_REGEX = Regex("\\b\\d{6}\\b")
    }
}

// =================================================================================================
// API linkage screen state
// =================================================================================================

/** A catalog row from the partner (data only: rendered as plain text, never as HTML). */
data class CatalogRow(val itemId: String, val title: String, val showtime: String, val price: Double, val currency: String) {
    companion object {
        fun from(o: JSONObject) = CatalogRow(
            itemId = o.optString("itemId"),
            title = o.optString("title"),
            showtime = o.optString("showtime"),
            price = if (o.has("price")) o.optDouble("price", 0.0) else o.optDouble("amount", 0.0),
            currency = o.optString("currency", "USD"),
        )
    }
}

data class OrderRow(
    val orderId: String,
    val title: String,
    val amount: Double,
    val currency: String,
    val status: String,
    val ticket: String?,
) {
    companion object {
        fun from(o: JSONObject): OrderRow {
            val t = o.optJSONObject("ticket")
            return OrderRow(
                orderId = o.optString("orderId"),
                title = o.optString("title"),
                amount = o.optDouble("amount", 0.0),
                currency = o.optString("currency", "USD"),
                status = o.optString("status"),
                ticket = t?.let { "${it.optString("code")} · seat ${it.optString("seat")}" },
            )
        }
    }
}

data class NativeResult(val info: PaymentInfo, val result: PayResult)

class NativeAppState(val detail: MiniAppDetail) {
    var tab by mutableStateOf("catalog")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var cached by mutableStateOf(false)
    var catalog by mutableStateOf<List<CatalogRow>>(emptyList())
    var orders by mutableStateOf<List<OrderRow>>(emptyList())
    var confirm by mutableStateOf<CatalogRow?>(null)
    var idempotencyKey: String = ""
    var ordering by mutableStateOf(false)
    var orderError by mutableStateOf<String?>(null)
    var result by mutableStateOf<NativeResult?>(null)
}

/** `data` of a content response: an array, or an object holding one (`items`, `<operation>`, `orders`…). */
private fun contentRows(data: Any?, operation: String): List<JSONObject> {
    val arr: JSONArray? = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("items") ?: data.optJSONArray(operation) ?: data.optJSONArray("orders")
            ?: data.keys().asSequence().mapNotNull { data.optJSONArray(it) }.firstOrNull()
        else -> null
    }
    if (arr == null) {
        if (data == null || data == JSONObject.NULL) return emptyList()
        throw JSONException("Unexpected content for '$operation'")
    }
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}
