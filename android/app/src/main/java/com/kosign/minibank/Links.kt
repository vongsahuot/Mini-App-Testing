package com.kosign.minibank

import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONException

/**
 * App-to-App (type `a2a`). MB App ↔ partner app over verified http links only (never a custom scheme):
 *
 * - MB App → partner app: `launch.appLink` (2:A1), or `launch.storeUrl` when nothing can open it (2:A2a).
 * - partner app → MB App: `http://localhost:3000/links/mbapp/link?appId&state&codeChallenge` (2:A5–2:A13)
 *   and `http://localhost:3000/links/mbapp/pay?paymentRef` (3:D30–3:D39), received by MainActivity.
 * - MB App → partner app: `returnLink?authCode&state` / `returnLink?error=access_denied&state` /
 *   `returnLink?paymentRef&status[&errorCode]`.
 */
class A2aLinks(private val vm: AppViewModel) {

    /** A link that arrived while signed out; handled right after login. */
    private var pending: Uri? = null

    // ---------- MB App → partner app (2:A1, 2:A2a) ----------

    fun openPartnerApp(d: MiniAppDetail) {
        val link = d.appLink
        Api.trace("APP", "2:A1", Types.A2A,
            "Build the registered appLink (verified HTTPS link); no code, no customer data; never a custom URL scheme",
            link, d.appId)
        if (link == null || !vm.openExternal(link)) {
            val store = d.storeUrl
            Api.trace("APP", "2:A2a", Types.A2A, "Link opens the registered store / web page", store, d.appId)
            if (store == null || !vm.openExternal(store)) vm.toast("Can't open ${d.name} right now")
        }
    }

    // ---------- partner app → MB App ----------

    /** Called by MainActivity for every VIEW intent (launch intent and onNewIntent). */
    fun handle(uri: Uri) {
        val path = uri.path.orEmpty()
        val isOurs = uri.scheme == "http" && uri.host == "localhost" && uri.port == 3000
        val kind = when {
            !isOurs -> null
            path == "/links/mbapp/link" || path == "/links/mbapp/link/" -> "link"
            path == "/links/mbapp/pay" || path == "/links/mbapp/pay/" -> "pay"
            else -> null
        }
        if (kind == null) {
            Log.w(TAG, "Ignoring unknown link $uri")
            vm.toast("Unknown link ignored: $path")
            return
        }
        if (kind == "link") {
            Api.trace("APP", "2:A5", Types.A2A, "Open MB App via verified linkLink (appId, state, codeChallenge)",
                "state=${uri.getQueryParameter("state")}", uri.getQueryParameter("appId"))
        } else {
            Api.trace("APP", "3:D30", Types.A2A, "Open MB App via verified payLink (paymentRef)", null, uri.getQueryParameter("paymentRef"))
        }
        if (vm.session == null) {
            pending = uri
            vm.showLogin()
            vm.toast("Sign in to continue")
            return
        }
        run(uri, justSignedIn = false)
    }

    /** After a successful login: continue a link that arrived while signed out (the login PIN counts as the unlock). */
    fun onSignedIn() {
        val uri = pending ?: return
        pending = null
        run(uri, justSignedIn = true)
    }

    private fun run(uri: Uri, justSignedIn: Boolean) {
        vm.viewModelScope.launch {
            try {
                if (uri.path.orEmpty().startsWith("/links/mbapp/link")) linkFlow(uri, justSignedIn) else payFlow(uri, justSignedIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "link flow failed", e)
                vm.busy = null
                vm.toast(e.message ?: "Something went wrong")
            }
        }
    }

    // ---------- Account linking (2:A5 → 2:A6 → 2:A13 → returnLink) ----------

    private suspend fun linkFlow(uri: Uri, justSignedIn: Boolean) {
        val appId = uri.getQueryParameter("appId")
        val state = uri.getQueryParameter("state")
        val codeChallenge = uri.getQueryParameter("codeChallenge")
        if (appId.isNullOrBlank() || state.isNullOrBlank() || codeChallenge.isNullOrBlank()) {
            vm.toast("Invalid link request (appId, state and codeChallenge are required)")
            return
        }
        Api.trace("APP", "2:A6", Types.A2A,
            "Sign the customer in (PIN / biometric); run consent & registration (phases 2–3) if not done; ask “Link to this partner?”",
            if (justSignedIn) "signed in with PIN at login" else "PIN unlock", appId)
        if (!justSignedIn && !vm.unlockWithPin("Confirm it's you", "A partner app wants to link with MB App. Enter your PIN to continue.")) {
            vm.toast("Linking cancelled")
            return
        }

        val d = vm.loadDetail(appId, Types.A2A) ?: return
        val returnLink = d.returnLink
        if (d.type != Types.A2A || returnLink == null) {
            vm.toast("${d.name} can't be linked from another app")
            return
        }
        if (!vm.ensureConsent(d)) {
            open(returnLink, "error" to "access_denied", "state" to state)
            return
        }

        val req = LinkRequest(d.name, d.icon, d.merchantName)
        vm.linkRequest = req
        val yes = try {
            req.result.await()
        } finally {
            if (vm.linkRequest === req) vm.linkRequest = null
        }
        if (!yes) {
            open(returnLink, "error" to "access_denied", "state" to state)
            return
        }

        vm.busy = "Linking ${d.name}…"
        val authCode = try {
            val r = Api.authCode(vm.requireToken(), appId, codeChallenge)
            val code = r.getString("authCode")
            Api.trace("APP", "2:A13", Types.A2A, "authCode", "expiresIn ${r.optInt("expiresIn", 60)}s · S256", appId)
            code
        } catch (e: ApiException) {
            vm.toast("Couldn't link ${d.name}: ${e.message}")
            open(returnLink, "error" to "server_error", "state" to state)
            return
        } catch (e: JSONException) {
            vm.toast("Couldn't link ${d.name}")
            open(returnLink, "error" to "server_error", "state" to state)
            return
        } finally {
            vm.busy = null
        }
        open(returnLink, "authCode" to authCode, "state" to state)
    }

    // ---------- Pay link (3:D30 → 3:D31 → 3:D38 → payment UI → 3:D39 → returnLink) ----------

    private suspend fun payFlow(uri: Uri, justSignedIn: Boolean) {
        val ref = uri.getQueryParameter("paymentRef")
        if (ref.isNullOrBlank()) {
            vm.toast("Invalid payment link (paymentRef is required)")
            return
        }
        if (!justSignedIn && !vm.unlockWithPin("Unlock MB App", "Enter your PIN to review a payment request.")) {
            vm.returnLinkFor(null)?.let { open(it, "paymentRef" to ref, "status" to "CANCELLED") }
            return
        }
        Api.trace("APP", "3:D31", Types.A2A, "Sign the customer in first (PIN / biometric); show nothing from the link itself",
            if (justSignedIn) "signed in with PIN at login" else "PIN unlock", ref)

        if (!vm.payment.reserve()) {
            vm.toast("Another payment is already open")
            return
        }
        try {
            vm.busy = "Loading payment…"
            val info = try {
                PaymentInfo.from(Api.payment(vm.requireToken(), ref))
            } catch (e: ApiException) {
                vm.busy = null
                Api.trace("APP", "3:D35c", Types.A2A, "Error", "${e.code}: ${e.message}", ref)
                Api.trace("APP", "3:D35d", Types.A2A,
                    "Show native error; return to partner app with FAILED (errorCode) via returnLink", e.code, ref)
                vm.showNotice("Can't pay", "${payErrorText(e)} (${e.code})")
                val rl = vm.returnLinkFor(null)
                if (rl != null) open(rl, "paymentRef" to ref, "status" to "FAILED", "errorCode" to e.code)
                else vm.toast("No return link known for this partner")
                return
            } finally {
                vm.busy = null
            }
            Api.trace("APP", "3:D38", Types.A2A, "Payment info", "${money(info.amount, info.currency)} · ${info.merchantName}", ref)

            vm.busy = "Loading payment…"
            val returnLink = try {
                vm.returnLinkFor(info.appId)
            } finally {
                vm.busy = null
            }

            val result = vm.payment.run(info)
            Api.trace("APP", "3:D39", Types.A2A,
                "Build the registered returnLink (verified HTTPS link) + paymentRef + status hint",
                listOfNotNull(result.status, result.errorCode).joinToString(" "), ref)
            if (returnLink != null) {
                open(returnLink, "paymentRef" to ref, "status" to result.status, "errorCode" to result.errorCode)
            } else {
                vm.toast("Payment ${result.status}. No return link known for this partner.")
            }
        } finally {
            vm.payment.release()
        }
    }

    private fun payErrorText(e: ApiException): String = when (e.code) {
        "NOT_FOUND" -> "This payment request doesn't exist."
        "NOT_PENDING" -> "This payment request was already used."
        "EXPIRED" -> "This payment request has expired."
        else -> e.message
    }

    /** Opens `base?k=v&…` (null values skipped) in the browser / partner app. */
    private fun open(base: String, vararg params: Pair<String, String?>) {
        val b = Uri.parse(base).buildUpon()
        params.forEach { (k, v) -> if (v != null) b.appendQueryParameter(k, v) }
        val url = b.build().toString()
        if (!vm.openExternal(url)) vm.toast("Can't open the partner app ($url)")
    }

    companion object {
        private const val TAG = "A2aLinks"
    }
}
