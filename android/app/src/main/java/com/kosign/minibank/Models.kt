package com.kosign.minibank

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data object MiniApps : Screen
    /** Link WebView (type `webview`). */
    data object WebApp : Screen
    /** Bank-built native screens (type `api`). */
    data object NativeApp : Screen
    /** Partner SDK screen (type `sdk`). */
    data object Sdk : Screen
}

/** Integration type ids (`type` on a Mini App, `flow` on trace events). */
object Types {
    const val WEBVIEW = "webview"
    const val API = "api"
    const val SDK = "sdk"
    const val A2A = "a2a"

    fun label(type: String): String = when (type) {
        WEBVIEW -> "WebView"
        API -> "Native"
        SDK -> "SDK"
        A2A -> "App-to-App"
        else -> type
    }
}

data class Account(
    val accountNo: String,
    val name: String,
    val balance: Double,
    val held: Double,
    val available: Double,
    val currency: String,
) {
    companion object {
        fun from(o: JSONObject): Account {
            val balance = o.optDouble("balance", 0.0)
            return Account(
                accountNo = o.optString("accountNo"),
                name = o.optString("name"),
                balance = balance,
                held = o.optDouble("held", 0.0),
                available = o.optDouble("available", balance),
                currency = o.optString("currency", "USD"),
            )
        }

        fun list(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { from(a!!.getJSONObject(it)) }
    }
}

data class BankSession(
    val sessionToken: String,
    /** Kept so the PIN unlock (App-to-App) can sign the same customer in again. */
    val username: String,
    val customerId: String,
    val name: String,
    val phone: String,
    val segment: String,
)

data class MiniApp(val appId: String, val name: String, val icon: String, val type: String)
data class Section(val id: String, val title: String, val apps: List<MiniApp>)

data class MiniAppsData(
    val sections: List<Section>,
    val etag: String?,
    /** "cache", "network (200)" or "cache (304)" — trace detail and footer only. */
    val source: String,
) {
    val allApps: List<MiniApp> get() = sections.flatMap { it.apps }

    companion object {
        fun parse(o: JSONObject, etag: String?, source: String): MiniAppsData {
            val secs = o.optJSONArray("sections") ?: JSONArray()
            return MiniAppsData(
                sections = (0 until secs.length()).map { i ->
                    val s = secs.getJSONObject(i)
                    val apps = s.optJSONArray("apps") ?: JSONArray()
                    Section(
                        id = s.optString("id"),
                        title = s.optString("title"),
                        apps = (0 until apps.length()).map { j ->
                            val a = apps.getJSONObject(j)
                            MiniApp(
                                appId = a.optString("appId"),
                                name = a.optString("name"),
                                icon = a.optString("icon", "📦"),
                                type = a.optString("type", Types.WEBVIEW),
                            )
                        },
                    )
                },
                etag = etag ?: o.str("etag"),
                source = source,
            )
        }
    }
}

data class Scope(val scope: String, val label: String)

data class Consent(
    val status: String,
    val currentVersion: String,
    val acceptedVersion: String?,
    val scopes: List<Scope>,
) {
    val accepted: Boolean get() = status != "NOT_ACCEPTED"
}

/** `GET /mini-apps/{appId}` (MiniAppDetail). [launch] depends on [type]. */
data class MiniAppDetail(
    val appId: String,
    val name: String,
    val icon: String,
    val type: String,
    val merchantName: String,
    val consent: Consent,
    val registered: Boolean,
    val launch: JSONObject,
) {
    // webview
    val entryUrl: String? get() = launch.str("entryUrl")
    val allowedOrigins: List<String>
        get() {
            val a = launch.optJSONArray("allowedOrigins")
            if (a != null) return (0 until a.length()).map { a.getString(it).let { o -> originOf(o) ?: o } }
            // Tolerate a single string; default to the entry URL's origin.
            return listOfNotNull(launch.str("allowedOrigins")?.let { originOf(it) ?: it } ?: originOf(entryUrl))
        }

    // api
    val screens: List<String>
        get() {
            val a = launch.optJSONArray("screens") ?: return listOf("catalog", "orders")
            return (0 until a.length()).map { a.getString(it) }
        }

    // sdk
    val sdkModule: String? get() = launch.str("sdkModule")
    val minSdkVersion: String get() = launch.str("minSdkVersion") ?: "0"
    val sdkEnabled: Boolean get() = launch.optBoolean("sdkEnabled", true)

    // a2a
    val appLink: String? get() = launch.str("appLink")
    val returnLink: String? get() = launch.str("returnLink")
    val storeUrl: String? get() = launch.str("storeUrl")

    companion object {
        fun parse(o: JSONObject): MiniAppDetail {
            val c = o.optJSONObject("consent") ?: JSONObject()
            val sc = c.optJSONArray("scopes") ?: JSONArray()
            return MiniAppDetail(
                appId = o.optString("appId"),
                name = o.optString("name"),
                icon = o.optString("icon", "📦"),
                type = o.optString("type", Types.WEBVIEW),
                merchantName = o.optString("merchantName", o.optString("name")),
                consent = Consent(
                    status = c.optString("status", "ACCEPTED"),
                    currentVersion = c.optString("currentVersion", "1"),
                    acceptedVersion = c.str("acceptedVersion"),
                    scopes = (0 until sc.length()).map { i ->
                        val s = sc.optJSONObject(i)
                        if (s == null) Scope(sc.optString(i), sc.optString(i))
                        else Scope(s.optString("scope"), s.optString("label", s.optString("scope")))
                    },
                ),
                registered = o.optBoolean("registered", false),
                launch = o.optJSONObject("launch") ?: JSONObject(),
            )
        }
    }
}

/** PaymentInfo from verify / create order / get payment. Amount and merchant always come from the server. */
data class PaymentInfo(
    val paymentRef: String,
    val appId: String,
    val orderId: String?,
    val merchantName: String,
    val amount: Double,
    val currency: String,
    val status: String,
    val expiresAt: String?,
) {
    companion object {
        fun from(o: JSONObject) = PaymentInfo(
            paymentRef = o.getString("paymentRef"),
            appId = o.optString("appId"),
            orderId = o.str("orderId"),
            merchantName = o.optString("merchantName"),
            amount = o.optDouble("amount", 0.0),
            currency = o.optString("currency", "USD"),
            status = o.optString("status", "PENDING"),
            expiresAt = o.str("expiresAt"),
        )
    }
}

/** What the payment UI hands back to its caller (bridge, SDK host API, native screen, returnLink). */
data class PayResult(val status: String, val paymentRef: String?, val errorCode: String? = null) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("status", status)
        if (paymentRef != null) o.put("paymentRef", paymentRef)
        if (errorCode != null) o.put("errorCode", errorCode)
        return o
    }
}

/** Latest OTP seen in a push notification (kind OTP). */
data class Otp(val code: String, val ref: String?)

/** Native consent sheet (2:9). Completes with true = Accept, false = Decline. */
class ConsentRequest(
    val appName: String,
    val icon: String,
    val merchantName: String,
    val scopeLabels: List<String>,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
)

/** "Link to <app>?" (App-to-App, 2:A6). Completes with true = Link, false = Don't link. */
class LinkRequest(
    val appName: String,
    val icon: String,
    val merchantName: String,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
)

/** PIN unlock prompt (App-to-App sign-in, 2:A6 / 3:D31). Completes with the PIN or null = Cancel. */
class PinPrompt(
    val title: String,
    val message: String,
    error: String? = null,
    val result: CompletableDeferred<String?> = CompletableDeferred(),
) {
    var error by mutableStateOf(error)
}

/** A blocking native message (error screens 2:25a, 3:D35d). Completes when dismissed. */
class Notice(
    val title: String,
    val message: String,
    val done: CompletableDeferred<Unit> = CompletableDeferred(),
)

/** Receipt dialog at the end of the shared payment UI. Completes when the user taps Done. */
class Receipt(
    val status: String,
    val paymentRef: String,
    val merchantName: String,
    val amount: Double,
    val currency: String,
    val bankTxnId: String?,
    val reason: String?,
    val done: CompletableDeferred<Unit> = CompletableDeferred(),
)

/** A bridge / host-API failure that is reported to the caller with a specific code. */
class BridgeError(val code: String, override val message: String) : Exception(message)

fun money(amount: Double, currency: String) = "%,.2f %s".format(amount, currency)

/** Compares dotted versions numerically ("2.1.0" vs "2.0.0"); missing parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.trim().toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.trim().toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
