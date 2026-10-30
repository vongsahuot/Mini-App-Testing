package com.kosign.minibank

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data object MiniApps : Screen
    data object WebApp : Screen
}

data class Account(val accountNo: String, val name: String, val balance: Double, val currency: String) {
    companion object {
        fun from(o: JSONObject) = Account(
            accountNo = o.optString("accountNo"),
            name = o.optString("name"),
            balance = o.optDouble("balance", 0.0),
            currency = o.optString("currency", "USD"),
        )

        fun list(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { from(a!!.getJSONObject(it)) }
    }
}

data class BankSession(
    val sessionToken: String,
    val customerId: String,
    val name: String,
    val phone: String,
    val deviceSecret: String,
)

data class MiniApp(val appId: String, val name: String, val icon: String, val badge: String?, val enabled: Boolean)
data class Section(val id: String, val title: String, val apps: List<MiniApp>)
data class Banner(val id: String, val title: String, val subtitle: String, val appId: String?, val color: String)

data class MiniAppsData(
    val sections: List<Section>,
    val banners: List<Banner>,
    val cacheTtlSeconds: Long,
    /** "cache" or "network" — only for trace detail. */
    val source: String,
) {
    val allApps: List<MiniApp> get() = sections.flatMap { it.apps }

    companion object {
        fun parse(o: JSONObject, source: String): MiniAppsData {
            val secs = o.optJSONArray("sections") ?: JSONArray()
            val bans = o.optJSONArray("banners") ?: JSONArray()
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
                                badge = a.str("badge"),
                                enabled = a.optBoolean("enabled", true),
                            )
                        },
                    )
                },
                banners = (0 until bans.length()).map { i ->
                    val b = bans.getJSONObject(i)
                    Banner(
                        id = b.optString("id"),
                        title = b.optString("title"),
                        subtitle = b.optString("subtitle"),
                        appId = b.str("appId"),
                        color = b.optString("color", "#6A4FD0"),
                    )
                },
                cacheTtlSeconds = o.optLong("cacheTtlSeconds", 60),
                source = source,
            )
        }
    }
}

/** Result of POST /client/mini-apps/{appId}/sessions. */
data class AppSession(
    val sessionId: String,
    val appId: String,
    val appName: String,
    val url: String,
    val allowedOrigins: List<String>,
)

/** Consent sheet (step 11). Completes with true = Allow, false = Deny. */
class ConsentRequest(
    val partnerName: String,
    val scopeLabels: List<String>,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
)

/** Payment sheet (step 29). Completes with the chosen debit account, or null = Cancel. */
class PaymentRequest(
    val paymentId: String,
    val merchantName: String,
    val description: String,
    val amount: Double,
    val currency: String,
    val accounts: List<Account>,
    val result: CompletableDeferred<String?> = CompletableDeferred(),
)

/** Receipt dialog after step 40. Completes when the user taps Done. */
class Receipt(
    val status: String,
    val bankTxnId: String?,
    val reason: String?,
    val amount: Double,
    val currency: String,
    val done: CompletableDeferred<Unit> = CompletableDeferred(),
)

/** A bridge failure that is reported to the page with a specific code. */
class BridgeError(val code: String, override val message: String) : Exception(message)

fun money(amount: Double, currency: String) = "%,.2f %s".format(amount, currency)
