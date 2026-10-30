package com.kosign.minibank

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sends a bridge reply for one call: (ok, payload). */
typealias BridgeReply = (ok: Boolean, payload: JSONObject) -> Unit

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("minibank", Context.MODE_PRIVATE)

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
    var appSession by mutableStateOf<AppSession?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set

    /** One-shot snackbar text; the UI clears it after showing. */
    var message by mutableStateOf<String?>(null)

    var consentRequest by mutableStateOf<ConsentRequest?>(null)
        private set
    var paymentRequest by mutableStateOf<PaymentRequest?>(null)
        private set
    var receipt by mutableStateOf<Receipt?>(null)
        private set

    // ---------- Login / home ----------

    fun login(username: String, pin: String) {
        viewModelScope.launch {
            busy = "Signing in…"
            try {
                val r = Api.login(username.trim(), pin, deviceId)
                session = BankSession(
                    sessionToken = r.getString("sessionToken"),
                    customerId = r.optString("customerId"),
                    name = r.optString("name"),
                    phone = r.optString("phone"),
                    deviceSecret = r.getString("deviceSecret"),
                )
                accounts = Account.list(r.optJSONArray("accounts"))
                screen = Screen.Home
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Login failed"
            } finally {
                busy = null
            }
        }
    }

    fun logout() {
        session = null
        accounts = emptyList()
        miniApps = null
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

    // ---------- Native mini apps list (steps 1–5) ----------

    fun openMiniApps() {
        Api.trace(1, "Tap \"Mini apps\"", null, session?.customerId)
        screen = Screen.MiniApps
        loadMiniApps()
    }

    fun backToHome() {
        screen = Screen.Home
    }

    private fun loadMiniApps() {
        // 1) Show the cached list immediately if it is still within cacheTtlSeconds.
        val cachedJson = prefs.getString(KEY_APPS_JSON, null)
        val savedAt = prefs.getLong(KEY_APPS_SAVED_AT, 0L)
        val cached = cachedJson?.let { runCatching { MiniAppsData.parse(JSONObject(it), "cache") }.getOrNull() }
        val fresh = cached != null && System.currentTimeMillis() - savedAt < cached.cacheTtlSeconds * 1000
        miniApps = if (fresh) cached else null

        // 2) Always refresh in the background with a fresh JWT (steps 2–4 happen on the portal).
        val s = session ?: return
        viewModelScope.launch {
            miniAppsLoading = true
            try {
                val json = Api.miniApps(Api.jwt(s.sessionToken))
                val data = MiniAppsData.parse(json, "network")
                Api.trace(
                    4, "Return sections, apps, banners",
                    "${data.sections.size} sections, ${data.allApps.size} apps, ${data.banners.size} banners, ttl ${data.cacheTtlSeconds}s",
                )
                prefs.edit()
                    .putString(KEY_APPS_JSON, json.toString())
                    .putLong(KEY_APPS_SAVED_AT, System.currentTimeMillis())
                    .apply()
                miniApps = data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Could not load mini apps"
            } finally {
                miniAppsLoading = false
            }
        }
    }

    /** Called by the list screen once a list has actually been composed (step 5). */
    fun onMiniAppsRendered(data: MiniAppsData) {
        Api.trace(5, "Render native list", "from ${data.source}: ${data.allApps.joinToString { it.appId }}")
    }

    // ---------- Open a mini app (steps 6–8) ----------

    fun openApp(app: MiniApp) {
        if (!app.enabled) {
            message = "Coming soon"
            return
        }
        val s = session ?: return
        if (busy != null) return
        viewModelScope.launch {
            Api.trace(6, "Tap mini app", app.name, app.appId)
            busy = "Opening ${app.name}…"
            try {
                val r = Api.createSession(Api.jwt(s.sessionToken), app.appId)
                val origins = r.optJSONArray("allowedOrigins") ?: JSONArray()
                val opened = AppSession(
                    sessionId = r.getString("sessionId"),
                    appId = r.optString("appId", app.appId),
                    appName = r.optString("appName", app.name),
                    url = r.getString("url"),
                    allowedOrigins = (0 until origins.length()).map {
                        val o = origins.getString(it)
                        originOf(o) ?: o
                    },
                )
                Api.trace(
                    8, "Return partner URL + allowed origins",
                    "${opened.url} · allowed ${opened.allowedOrigins.joinToString()}", opened.appId,
                )
                appSession = opened
                screen = Screen.WebApp
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Could not open ${app.name}"
            } finally {
                busy = null
            }
        }
    }

    fun openBanner(banner: Banner) {
        val app = miniApps?.allApps?.firstOrNull { it.appId == banner.appId }
        if (app != null) openApp(app) else message = "Coming soon"
    }

    /** Close the WebView and go back to the native list (step 62). */
    fun closeWebApp(reason: String) {
        val current = appSession ?: return
        // Resolve anything still waiting on the user so those coroutines finish.
        consentRequest?.result?.complete(false)
        paymentRequest?.result?.complete(null)
        receipt?.done?.complete(Unit)
        consentRequest = null
        paymentRequest = null
        receipt = null
        busy = null
        appSession = null
        screen = Screen.MiniApps
        Api.trace(62, "Close WebView → list", reason, current.appId)
    }

    // ---------- JS bridge ----------

    /**
     * Entry point for every `KosignNative.postMessage(json)` call. Must be called on the main thread
     * with the WebView's current page origin (computed on the main thread too).
     */
    fun onBridgeMessage(
        json: String,
        pageOrigin: String?,
        reply: (id: String, ok: Boolean, payload: JSONObject) -> Unit,
    ) {
        val msg = runCatching { JSONObject(json) }.getOrNull()
        if (msg == null) {
            Log.w(TAG, "Ignoring malformed bridge message: $json")
            return
        }
        val id = msg.optString("id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val app = appSession ?: return

        // Only reply while the same mini app session is still open.
        val send: BridgeReply = { ok, payload ->
            if (appSession?.sessionId == app.sessionId) reply(id, ok, payload)
        }

        if (pageOrigin == null || pageOrigin !in app.allowedOrigins) {
            Log.w(TAG, "Bridge call '$method' refused from origin $pageOrigin")
            send(false, bridgeError("ORIGIN_NOT_ALLOWED", "Origin $pageOrigin is not allowed to use the bridge"))
            return
        }

        when (method) {
            "close" -> {
                Api.trace(61, "Bridge close()", null, app.appId)
                send(true, JSONObject())
                closeWebApp("bridge close()")
            }
            "login" -> runBridge(send) { handleLogin(app, params) }
            "pay" -> runBridge(send) { handlePay(params, pageOrigin) }
            else -> send(false, bridgeError("ERROR", "Unknown method '$method'"))
        }
    }

    private fun runBridge(send: BridgeReply, block: suspend () -> JSONObject) {
        viewModelScope.launch {
            try {
                val payload = block()
                send(true, payload)
            } catch (e: BridgeError) {
                send(false, bridgeError(e.code, e.message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "bridge call failed", e)
                send(false, bridgeError("ERROR", e.message ?: e.toString()))
            } finally {
                busy = null
            }
        }
    }

    private fun bridgeError(code: String, message: String): JSONObject =
        JSONObject().put("code", code).put("message", message)

    private fun requireSession(): BankSession = session ?: throw BridgeError("ERROR", "Not logged in")

    /** login(scopes) — steps 10–15. */
    private suspend fun handleLogin(app: AppSession, params: JSONObject): JSONObject {
        val s = requireSession()
        val arr = params.optJSONArray("scopes") ?: JSONArray()
        val scopes = (0 until arr.length()).map { arr.getString(it) }.ifEmpty { listOf("profile") }
        Api.trace(10, "Bridge login(scopes)", scopes.joinToString(), app.appId)

        busy = "Checking consent…"
        val c = Api.consents(Api.jwt(s.sessionToken), app.appId, scopes)
        busy = null
        if (!c.optBoolean("granted", false)) {
            val labelsArr = c.optJSONArray("scopes") ?: JSONArray()
            val labels = (0 until labelsArr.length()).map {
                val o = labelsArr.getJSONObject(it)
                o.optString("label", o.optString("scope"))
            }.ifEmpty { scopes }
            val req = ConsentRequest(c.optString("partnerName", app.appName), labels)
            consentRequest = req
            Api.trace(11, "Show consent sheet", labels.joinToString(), app.appId)
            val allowed = req.result.await()
            consentRequest = null
            if (!allowed) throw BridgeError("USER_DENIED", "User denied consent")
            Api.trace(12, "Tap Allow", scopes.joinToString(), app.appId)
        }

        busy = "Signing in to ${app.appName}…"
        val r = Api.authCode(Api.jwt(s.sessionToken), app.appId, app.sessionId, scopes)
        val authCode = r.getString("authCode")
        Api.trace(15, "Return authCode", "expiresIn ${r.optInt("expiresIn", 60)}s", app.appId)
        return JSONObject().put("authCode", authCode)
    }

    /** pay(paymentId) — steps 26–40. */
    private suspend fun handlePay(params: JSONObject, pageOrigin: String): JSONObject {
        val s = requireSession()
        val paymentId = params.optString("paymentId")
        if (paymentId.isBlank()) throw BridgeError("ERROR", "paymentId is required")
        Api.trace(26, "Bridge pay(paymentId)", null, paymentId)

        busy = "Loading payment…"
        val p = Api.payment(Api.jwt(s.sessionToken), paymentId, pageOrigin)
        val amount = p.optDouble("amount", 0.0)
        val currency = p.optString("currency", "USD")
        val merchant = p.optString("merchantName")
        Api.trace(28, "Return amount, merchant", "${money(amount, currency)} · $merchant", paymentId)
        busy = null

        val req = PaymentRequest(
            paymentId = paymentId,
            merchantName = merchant,
            description = p.optString("description"),
            amount = amount,
            currency = currency,
            accounts = accounts,
        )
        paymentRequest = req
        Api.trace(29, "Show native payment sheet", "${money(amount, currency)} · $merchant", paymentId)
        val debitAccount = req.result.await()
        paymentRequest = null

        if (debitAccount == null) {
            Api.trace(30, "Tap Cancel", null, paymentId)
            busy = "Cancelling…"
            try {
                Api.cancelPayment(Api.jwt(s.sessionToken), paymentId)
            } catch (e: ApiException) {
                // The user cancelled either way; the portal will expire the payment later.
                Log.w(TAG, "cancel failed: ${e.message}")
            }
            return JSONObject().put("status", "CANCELLED")
        }

        // The PIN was verified by the payment sheet. It stands in for a biometric prompt that
        // unlocks a hardware Keystore key; here the "key" is the deviceSecret received at login.
        val signature = hmacSha256Hex(s.deviceSecret, paymentId)
        Api.trace(33, "Biometrics/PIN signs paymentId", "debit $debitAccount", paymentId)

        busy = "Processing payment…"
        val r = Api.approve(s.sessionToken, paymentId, debitAccount, signature)
        val status = r.optString("status", "FAILED")
        val bankTxnId = r.str("bankTxnId")
        val reason = r.str("reason")
        Api.trace(40, "Return Done + bankTxnId", listOfNotNull(status, bankTxnId, reason).joinToString(" · "), paymentId)
        busy = null

        val rec = Receipt(status, bankTxnId, reason, r.optDouble("amount", amount), r.optString("currency", currency))
        receipt = rec
        rec.done.await()
        receipt = null
        refreshAccounts()

        val out = JSONObject().put("status", status)
        if (bankTxnId != null) out.put("bankTxnId", bankTxnId)
        if (reason != null) out.put("reason", reason)
        return out
    }

    // ---------- sheet callbacks from UI ----------

    fun answerConsent(allow: Boolean) {
        consentRequest?.result?.complete(allow)
    }

    fun answerPayment(debitAccount: String?) {
        paymentRequest?.result?.complete(debitAccount)
    }

    fun dismissReceipt() {
        receipt?.done?.complete(Unit)
    }

    companion object {
        private const val TAG = "AppViewModel"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_APPS_JSON = "miniAppsJson"
        private const val KEY_APPS_SAVED_AT = "miniAppsSavedAt"

        /** Mock PIN that "unlocks the signing key" (stands in for biometrics/Keystore). */
        const val MOCK_PIN = "1234"
    }
}
