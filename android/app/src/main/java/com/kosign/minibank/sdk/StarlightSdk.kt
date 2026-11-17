package com.kosign.minibank.sdk

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kosign.minibank.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// =================================================================================================
// Starlight Cinema SDK — the partner's library "built into" MB App (integration type `sdk`).
//
// Everything in this package is partner code. It never sees the bank session, accounts or customer data:
// MB App gives it only a [HostApi] (login, openPayment, close). It talks only to its own registered server
// (BuildConfig.PARTNER_URL, "pinned") and keeps its partner session token in memory.
// =================================================================================================

/** The only thing MB App gives the SDK (docs/CONTRACT.md, "SDK host API"). */
interface HostApi {
    /** Returns a one-time authCode for this Mini App (MB App steps 2:26–2:34). */
    suspend fun login(): String

    /** Opens MB App's payment UI. Returns `{status: SUCCESS|FAILED|PROCESSING|CANCELLED, paymentRef?, errorCode?}`. */
    suspend fun openPayment(order: JSONObject, checksum: String): JSONObject

    /** Closes the SDK screen (MB App steps 4:20–4:22). */
    fun close()
}

/** A host API call refused by MB App (e.g. `SHEET_OPEN`, `CONSENT_REQUIRED`, `ERROR`). */
class HostException(val code: String, message: String) : Exception(message)

/** Trace sink provided by the host app: (actor "SDK" or "U", step, title, detail, ref). */
typealias SdkTrace = (actor: String, step: String?, title: String, detail: String?, ref: String?) -> Unit

object StarlightSdk {
    const val VERSION = "2.1.0"
    const val MODULE = "starlight-sdk"

    /** SDK entry screen. MB App calls this with the host API of the instance it started for [appId]. */
    @Composable
    fun Screen(host: HostApi, appId: String, locale: String = "en", onTrace: SdkTrace = { _, _, _, _, _ -> }) {
        val scope = rememberCoroutineScope()
        val state = remember(host) { SdkState(host, appId, onTrace, StarlightClient(BuildConfig.PARTNER_URL)) }

        LaunchedEffect(state) {
            onTrace("SDK", "2:25", "Start SDK entry screen (appId, locale; no customer data)", "appId=$appId, locale=$locale, v$VERSION", appId)
            state.signIn()
        }
        BackHandler {
            when {
                state.result != null -> state.result = null
                state.tab != 0 -> state.tab = 0
                else -> host.close()
            }
        }
        SdkContent(state, scope)
    }
}

// ---------------------------------------------------------------------------------------------
// Partner client (talks only to the registered partner host)
// ---------------------------------------------------------------------------------------------

internal class SdkApiException(val status: Int, val code: String, message: String) : Exception(message)

internal class StarlightClient(baseUrl: String) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Mock certificate pinning: any host other than the registered one is refused before connecting. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val u = chain.request().url
                if (u.scheme != base.scheme || u.host != base.host || u.port != base.port) {
                    throw IOException("Pinning: refusing ${u.scheme}://${u.host}:${u.port} (only ${base.host}:${base.port})")
                }
                return chain.proceed(chain.request())
            }
        })
        .build()

    /** Partner session token: memory only, gone when the SDK screen closes. */
    var sessionToken: String? = null
        private set

    suspend fun login(authCode: String): JSONObject {
        val r = JSONObject(call("POST", "/api/login", JSONObject().put("authCode", authCode)))
        sessionToken = r.optString("sessionToken").ifBlank { null }
        if (sessionToken == null) throw SdkApiException(200, "NO_SESSION", "Partner login returned no sessionToken")
        return r
    }

    suspend fun catalog(): JSONArray = JSONArray(call("GET", "/api/catalog", null))
    suspend fun createOrder(itemId: String): JSONObject = JSONObject(call("POST", "/api/orders", JSONObject().put("itemId", itemId)))
    suspend fun order(orderId: String): JSONObject = JSONObject(call("GET", "/api/orders/${Uri.encode(orderId)}", null))
    suspend fun orders(): JSONArray = JSONArray(call("GET", "/api/orders", null))

    /** amount null = refund everything that is left. */
    suspend fun refund(orderId: String, amount: Double?): JSONObject =
        JSONObject(call("POST", "/api/orders/${Uri.encode(orderId)}/refund",
            JSONObject().put("amount", amount ?: JSONObject.NULL)))

    private suspend fun call(method: String, path: String, body: JSONObject?): String = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url(base.newBuilder().encodedPath(path).build())
            .header("Accept", "application/json")
            .header("X-Client-Kind", "sdk")
        sessionToken?.let { b.header("Authorization", "Bearer $it") }
        if (method == "POST") b.post((body ?: JSONObject()).toString().toRequestBody(json)) else b.get()
        val resp = try {
            http.newCall(b.build()).execute()
        } catch (e: IOException) {
            throw SdkApiException(0, "NETWORK", "Cannot reach Starlight (${e.message})")
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val err = runCatching { JSONObject(text) }.getOrNull()
                val code = err?.optString("code")?.ifBlank { null } ?: err?.optString("error")?.ifBlank { null } ?: "HTTP_${it.code}"
                val msg = err?.optString("message")?.ifBlank { null } ?: "HTTP ${it.code}"
                throw SdkApiException(it.code, code, msg)
            }
            text
        }
    }
}

// ---------------------------------------------------------------------------------------------
// State + flow
// ---------------------------------------------------------------------------------------------

internal data class CatalogItem(val itemId: String, val title: String, val showtime: String, val price: Double, val currency: String)

internal data class SdkOrder(
    val orderId: String,
    val title: String,
    val amount: Double,
    val currency: String,
    val status: String,
    val refundedAmount: Double,
    val paymentRef: String?,
    val ticket: String?,
) {
    companion object {
        fun from(o: JSONObject): SdkOrder {
            val t = o.optJSONObject("ticket")
            return SdkOrder(
                orderId = o.optString("orderId"),
                title = o.optString("title"),
                amount = o.optDouble("amount", 0.0),
                currency = o.optString("currency", "USD"),
                status = o.optString("status"),
                refundedAmount = o.optDouble("refundedAmount", 0.0),
                paymentRef = if (o.isNull("paymentRef")) null else o.optString("paymentRef").ifBlank { null },
                ticket = t?.let { "${it.optString("code")} · seat ${it.optString("seat")}" },
            )
        }
    }
}

/** Outcome screen after a purchase: kind = "ticket" | "failed" | "processing" | "cancelled". */
internal data class SdkResult(val kind: String, val title: String, val message: String, val order: SdkOrder?)

internal class SdkState(
    val host: HostApi,
    val appId: String,
    val trace: SdkTrace,
    val client: StarlightClient,
) {
    var signedInAs by mutableStateOf<String?>(null)
    var working by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var tab by mutableStateOf(0)
    var catalog by mutableStateOf<List<CatalogItem>>(emptyList())
    var orders by mutableStateOf<List<SdkOrder>>(emptyList())
    var result by mutableStateOf<SdkResult?>(null)

    private fun t(step: String?, title: String, detail: String? = null, ref: String? = null) = trace("SDK", step, title, detail, ref)

    /** host.login() → POST /api/login (2:35, 2:43, 2:44); on error 2:39c, 2:39d and one retry with a new code. */
    suspend fun signIn() {
        error = null
        working = "Signing in…"
        try {
            for (attempt in 1..2) {
                val authCode = try {
                    host.login()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "MB App could not sign you in: ${e.message}"
                    return
                }
                t("2:35", "Return authCode to login() callback", "attempt $attempt", appId)
                try {
                    val r = client.login(authCode)
                    val profile = r.optJSONObject("profile")
                    signedInAs = profile?.optString("name")?.ifBlank { null } ?: r.optString("partnerUserId")
                    t("2:43", "Partner session token (SDK memory only)", r.optString("partnerUserId"), appId)
                    loadCatalog()
                    trace("U", "2:44", "Show signed-in SDK screen", signedInAs, appId)
                    return
                } catch (e: SdkApiException) {
                    t("2:39c", "Error", "${e.code}: ${e.message}", appId)
                    if (attempt == 1) {
                        t("2:39d", "Call login() again (new code)", null, appId)
                    } else {
                        error = "Sign-in failed (${e.code}). Try again."
                    }
                }
            }
        } finally {
            working = null
        }
    }

    suspend fun loadCatalog() {
        try {
            val a = client.catalog()
            catalog = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                CatalogItem(
                    itemId = o.optString("itemId"),
                    title = o.optString("title"),
                    showtime = o.optString("showtime"),
                    price = o.optDouble("price", 0.0),
                    currency = o.optString("currency", "USD"),
                )
            }
        } catch (e: SdkApiException) {
            error = "Could not load movies (${e.code})"
        }
    }

    suspend fun loadOrders() {
        working = "Loading orders…"
        try {
            val a = client.orders()
            orders = (0 until a.length()).map { SdkOrder.from(a.getJSONObject(it)) }
        } catch (e: SdkApiException) {
            error = "Could not load orders (${e.code})"
        } finally {
            working = null
        }
    }

    /** 3:1 → create order (3:4) → host.openPayment → 3:46 (or 3:58 + 3:59) → poll own server (3:48). */
    suspend fun buy(item: CatalogItem) {
        error = null
        t("3:1", "Complete transaction", "${item.itemId} ${item.title}", appId)
        working = "Creating order…"
        val created = try {
            client.createOrder(item.itemId)
        } catch (e: SdkApiException) {
            working = null
            error = "Could not create the order (${e.code})"
            return
        }
        val order = created.optJSONObject("order")
        val checksum = created.optString("checksum")
        if (order == null || checksum.isBlank()) {
            working = null
            error = "Partner server returned no order"
            return
        }
        val orderId = order.optString("orderId")
        t("3:4", "Order info + checksum", "${order.opt("amount")} ${order.optString("currency")}", orderId)
        working = null

        val pay: JSONObject = try {
            host.openPayment(order, checksum)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostException) {
            JSONObject().put("status", "FAILED").put("errorCode", e.code)
        } catch (e: Exception) {
            JSONObject().put("status", "FAILED").put("errorCode", "ERROR")
        }
        val status = pay.optString("status", "FAILED")
        val paymentRef = if (pay.isNull("paymentRef")) null else pay.optString("paymentRef").ifBlank { null }
        val errorCode = if (pay.isNull("errorCode")) null else pay.optString("errorCode").ifBlank { null }

        if (status == "FAILED" && paymentRef == null) {
            // Refused before a payment existed (verify error, SHEET_OPEN…).
            t("3:58", "Host API callback (FAILED, errorCode)", errorCode, orderId)
            result = SdkResult("failed", "Payment failed", "MB App could not start the payment (${errorCode ?: "ERROR"}).", null)
            trace("U", "3:59", "Display transaction result", "FAILED ${errorCode ?: ""}".trim(), orderId)
            return
        }
        t("3:46", "Host API callback (status, paymentRef)", status, paymentRef ?: orderId)

        // The callback is only a hint: ask our own server for the real order status.
        working = if (status == "CANCELLED") "Checking order…" else "Waiting for confirmation…"
        var last: SdkOrder? = null
        try {
            val tries = if (status == "CANCELLED") 1 else 20
            for (i in 1..tries) {
                last = try {
                    SdkOrder.from(client.order(orderId))
                } catch (e: SdkApiException) {
                    last
                }
                val current = last
                if (current != null && current.status != "PENDING_PAYMENT") break
                if (i < tries) delay(1500)
            }
        } finally {
            working = null
        }
        val final: SdkOrder? = last
        val finalStatus = final?.status ?: "unknown"
        t("3:48", "Order status", finalStatus, orderId)
        result = when (finalStatus) {
            "DELIVERED" -> SdkResult("ticket", "Enjoy the movie! 🎉", "Your ticket: ${final?.ticket ?: "—"}", final)
            "FAILED", "EXPIRED" -> SdkResult("failed", "Payment failed", "Order ${finalStatus.lowercase()}${errorCode?.let { " ($it)" } ?: ""}.", final)
            else -> if (status == "CANCELLED") SdkResult("cancelled", "Payment cancelled", "You can try again any time.", final)
            else SdkResult("processing", "Payment is being processed", "We'll issue your ticket as soon as the bank confirms.", final)
        }
    }

    suspend fun refund(order: SdkOrder, amount: Double?) {
        working = "Requesting refund…"
        error = null
        try {
            client.refund(order.orderId, amount)
        } catch (e: SdkApiException) {
            error = "Refund refused (${e.code}: ${e.message})"
        } finally {
            working = null
        }
        loadOrders()
    }
}

// ---------------------------------------------------------------------------------------------
// UI (partner branding)
// ---------------------------------------------------------------------------------------------

private val StarlightGold = Color(0xFFE0A526)

@Composable
private fun SdkContent(state: SdkState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🍿", fontSize = 28.sp)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Starlight Cinema", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "SDK v${StarlightSdk.VERSION}" + (state.signedInAs?.let { " · signed in as $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { state.host.close() }) { Text("Close") }
        }

        val err = state.error
        if (err != null) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                    if (state.signedInAs == null && state.working == null) {
                        TextButton(onClick = { scope.launch { state.signIn() } }) { Text("Retry sign-in") }
                    }
                }
            }
        }

        val result = state.result
        when {
            state.working != null && state.signedInAs == null -> Busy(state.working!!)
            result != null -> ResultView(state, result, scope)
            state.signedInAs == null -> Unit
            else -> {
                TabRow(selectedTabIndex = state.tab) {
                    Tab(selected = state.tab == 0, onClick = { state.tab = 0 }, text = { Text("Movies") })
                    Tab(selected = state.tab == 1, onClick = {
                        state.tab = 1
                        scope.launch { state.loadOrders() }
                    }, text = { Text("My orders") })
                }
                val working = state.working
                if (working != null) Busy(working)
                else if (state.tab == 0) CatalogView(state, scope) else OrdersView(state, scope)
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.size(12.dp))
            Text(text)
        }
    }
}

@Composable
private fun CatalogView(state: SdkState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        state.catalog.forEach { item ->
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        if (item.showtime.isNotBlank()) Text(item.showtime, style = MaterialTheme.typography.bodySmall)
                        Text("%.2f %s".format(item.price, item.currency), color = StarlightGold, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { scope.launch { state.buy(item) } }) { Text("Buy") }
                }
            }
        }
        if (state.catalog.isEmpty()) Text("No movies right now")
    }
}

@Composable
private fun OrdersView(state: SdkState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        state.orders.forEach { o ->
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(o.title, fontWeight = FontWeight.SemiBold)
                    Text("%.2f %s · %s".format(o.amount, o.currency, o.status), style = MaterialTheme.typography.bodySmall)
                    if (o.refundedAmount > 0) Text("Refunded %.2f".format(o.refundedAmount), style = MaterialTheme.typography.bodySmall)
                    o.ticket?.let { Text("🎟️ $it", style = MaterialTheme.typography.bodySmall) }
                    Text(o.orderId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (o.status == "DELIVERED" || o.status == "PARTIALLY_REFUNDED") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch { state.refund(o, null) } }) { Text("Refund all") }
                            OutlinedButton(onClick = { scope.launch { state.refund(o, 1.0) } }) { Text("Refund 1.00") }
                        }
                    }
                }
            }
        }
        if (state.orders.isEmpty()) Text("No orders yet")
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { scope.launch { state.loadOrders() } }) { Text("Refresh") }
    }
}

@Composable
private fun ResultView(state: SdkState, result: SdkResult, scope: CoroutineScope) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when (result.kind) {
                "ticket" -> "🎟️"
                "processing" -> "⏳"
                "cancelled" -> "↩️"
                else -> "❌"
            },
            fontSize = 48.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(result.message)
        result.order?.let { Text("${it.title} · ${it.orderId}", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { state.result = null; state.tab = 0 }) { Text("Movies") }
            Button(onClick = {
                state.result = null
                state.tab = 1
                scope.launch { state.loadOrders() }
            }) { Text("My orders") }
        }
    }
}
