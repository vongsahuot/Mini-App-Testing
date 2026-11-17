package com.kosign.minibank

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Error returned by the app gateway as `{ "code", "message", ... }` (or a transport failure: status 0, code NETWORK).
 * [body] keeps the extra fields (e.g. `attemptsLeft` on 401 AUTH_FAILED).
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val body: JSONObject? = null,
) : Exception(message) {
    /** True when the outcome of the call is unknown (no answer, or a gateway/server failure). */
    val isNetworkOrServer: Boolean get() = status == 0 || status >= 500
}

/** Result of `GET /mini-apps`: 200 with a body, or 304 Not Modified. */
class MiniAppsResponse(val status: Int, val body: JSONObject?, val etag: String?)

/**
 * Thin OkHttp wrapper for the bank's app gateway (`/gw/app/v1`, see docs/CONTRACT.md) and the portal trace.
 * Every gateway call (except login) carries `Authorization: Bearer <sessionToken>`, plus
 * `X-App-Version` and `X-Platform: android`.
 */
object Api {
    private const val TAG = "Api"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    /** Sent as X-App-Version (5.2.0, set in app/build.gradle.kts). */
    val APP_VERSION: String get() = BuildConfig.APP_VERSION
    const val PLATFORM = "android"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gw get() = "${BuildConfig.BANK_URL}/gw/app/v1"
    private val portal get() = BuildConfig.PORTAL_URL

    private fun seg(s: String): String = Uri.encode(s)

    // ---------- Session / home ----------

    suspend fun login(username: String, pin: String, deviceId: String): JSONObject =
        obj(post("$gw/login", null, JSONObject()
            .put("username", username).put("pin", pin).put("deviceId", deviceId)).text)

    suspend fun accounts(token: String): JSONArray = arr(get("$gw/accounts", token).text)

    suspend fun notifications(token: String, after: String?): JSONArray {
        val b = "$gw/notifications".toHttpUrl().newBuilder()
        if (!after.isNullOrBlank()) b.addQueryParameter("after", after)
        return arr(get(b.build().toString(), token).text)
    }

    // ---------- Mini Apps ----------

    /** `GET /mini-apps` with `If-None-Match`; 304 is returned (not thrown). */
    suspend fun miniApps(token: String, etag: String?): MiniAppsResponse {
        val headers = if (etag.isNullOrBlank()) emptyMap() else mapOf("If-None-Match" to etag)
        val r = execute(Request.Builder().url("$gw/mini-apps").get(), token, headers, allow304 = true)
        return if (r.code == 304) MiniAppsResponse(304, null, r.etag ?: etag)
        else MiniAppsResponse(r.code, obj(r.text), r.etag)
    }

    suspend fun miniApp(token: String, appId: String): JSONObject =
        obj(get("$gw/mini-apps/${seg(appId)}", token).text)

    suspend fun consent(token: String, appId: String, consentVersion: String): JSONObject =
        obj(post("$gw/mini-apps/${seg(appId)}/consent", token, JSONObject().put("consentVersion", consentVersion)).text)

    /** `{}` for the WebView / SDK login; `{codeChallenge, codeChallengeMethod: "S256"}` for App-to-App. */
    suspend fun authCode(token: String, appId: String, codeChallenge: String? = null): JSONObject {
        val body = JSONObject()
        if (codeChallenge != null) body.put("codeChallenge", codeChallenge).put("codeChallengeMethod", "S256")
        return obj(post("$gw/mini-apps/${seg(appId)}/auth-code", token, body).text)
    }

    suspend fun content(token: String, appId: String, operation: String, params: JSONObject = JSONObject()): JSONObject =
        obj(post("$gw/mini-apps/${seg(appId)}/content", token, JSONObject()
            .put("operation", operation).put("params", params)).text)

    suspend fun createOrder(token: String, appId: String, itemId: String, idempotencyKey: String): JSONObject =
        obj(post("$gw/mini-apps/${seg(appId)}/orders", token, JSONObject()
            .put("items", JSONArray().put(JSONObject().put("itemId", itemId).put("qty", 1)))
            .put("idempotencyKey", idempotencyKey)).text)

    // ---------- Payments ----------

    suspend fun verify(token: String, appId: String, order: JSONObject, checksum: String): JSONObject =
        obj(post("$gw/payments/verify", token, JSONObject()
            .put("appId", appId).put("order", order).put("checksum", checksum)).text)

    suspend fun payment(token: String, paymentRef: String): JSONObject =
        obj(get("$gw/payments/${seg(paymentRef)}", token).text)

    suspend fun paymentStatus(token: String, paymentRef: String): JSONObject =
        obj(get("$gw/payments/${seg(paymentRef)}/status", token).text)

    suspend fun initPayment(token: String, paymentRef: String, account: String): JSONObject =
        obj(post("$gw/payments/${seg(paymentRef)}/init", token, JSONObject().put("account", account)).text)

    /** [authMethod] "PIN" sends `{pin}`, "OTP" sends `{otp}`. */
    suspend fun confirm(token: String, paymentRef: String, authMethod: String, code: String): JSONObject {
        val body = if (authMethod == "OTP") JSONObject().put("otp", code) else JSONObject().put("pin", code)
        return obj(post("$gw/payments/${seg(paymentRef)}/confirm", token, body).text)
    }

    // ---------- Trace (fire-and-forget) ----------

    /**
     * Records one step on the portal trace. [actor] is the lane ("APP", "U", "SDK"), [step] the diagram key
     * ("3:16", "2:A5") or null for an un-numbered note, [flow] the integration type or null for shared steps.
     */
    fun trace(actor: String, step: String?, flow: String?, title: String, detail: String? = null, ref: String? = null) {
        val body = JSONObject()
            .put("actor", actor)
            .put("step", step ?: JSONObject.NULL)
            .put("flow", flow ?: JSONObject.NULL)
            .put("title", title)
            .put("detail", detail ?: JSONObject.NULL)
            .put("ref", ref ?: JSONObject.NULL)
        Log.d(TAG, "trace [$actor] ${step ?: "-"} $title ${detail ?: ""} ${ref ?: ""} ${flow ?: ""}")
        val req = Request.Builder()
            .url("$portal/api/v1/admin/trace")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "trace ${step ?: "-"} failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    // ---------- plumbing ----------

    private class Raw(val code: Int, val text: String, val etag: String?)

    private fun obj(text: String): JSONObject = if (text.isBlank()) JSONObject() else JSONObject(text)
    private fun arr(text: String): JSONArray = if (text.isBlank()) JSONArray() else JSONArray(text)

    private suspend fun get(url: String, bearer: String?): Raw =
        execute(Request.Builder().url(url).get(), bearer, emptyMap())

    private suspend fun post(url: String, bearer: String?, body: JSONObject): Raw =
        execute(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)), bearer, emptyMap())

    private suspend fun execute(
        builder: Request.Builder,
        bearer: String?,
        headers: Map<String, String>,
        allow304: Boolean = false,
    ): Raw = withContext(Dispatchers.IO) {
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        builder.header("X-App-Version", APP_VERSION)
        builder.header("X-Platform", PLATFORM)
        builder.header("Accept", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(0, "NETWORK", "Cannot reach ${request.url.host}:${request.url.port} (${e.message}). Did you run adb reverse?")
        }
        response.use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ApiException(0, "NETWORK", "Connection lost while reading the answer (${e.message})")
            }
            val etag = resp.header("ETag")
            if (allow304 && resp.code == 304) return@use Raw(304, "", etag)
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(text) }.getOrNull()
                val code = err?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_${resp.code}"
                val msg = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                throw ApiException(resp.code, code, msg, err)
            }
            Raw(resp.code, text, etag)
        }
    }
}

/** `optString` returns "null" for JSON null; this returns a real Kotlin null instead (and null for ""). */
fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

/** scheme://host[:port] of a URL, or null. */
fun originOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return if (uri.port != -1) "$scheme://$host:${uri.port}" else "$scheme://$host"
}
