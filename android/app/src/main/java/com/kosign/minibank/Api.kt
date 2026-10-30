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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Error returned by a mock backend as `{ "code", "message" }` (or a transport failure). */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

/** Thin OkHttp wrapper for the Bank Server and KOSIGN Portal (see docs/CONTRACT.md). */
object Api {
    private const val TAG = "Api"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val bank get() = BuildConfig.BANK_URL
    private val portal get() = BuildConfig.PORTAL_URL

    private fun seg(s: String): String = Uri.encode(s)

    // ---------- Bank app API ----------

    suspend fun login(username: String, pin: String, deviceId: String): JSONObject =
        obj(post("$bank/api/v1/bank-app/login", null, JSONObject()
            .put("username", username).put("pin", pin).put("deviceId", deviceId)))

    /** Fresh bank-signed JWT (RS256, 300 s) for calls to the portal. */
    suspend fun jwt(sessionToken: String): String =
        obj(post("$bank/api/v1/bank-app/jwt", sessionToken, JSONObject())).getString("jwt")

    suspend fun accounts(sessionToken: String): JSONArray =
        JSONArray(get("$bank/api/v1/bank-app/accounts", sessionToken))

    suspend fun approve(sessionToken: String, paymentId: String, debitAccount: String, signature: String): JSONObject =
        obj(post("$bank/api/v1/bank-app/payments/${seg(paymentId)}/approve", sessionToken, JSONObject()
            .put("debitAccount", debitAccount).put("signature", signature)))

    // ---------- Portal client API (Bearer bank JWT) ----------

    suspend fun miniApps(jwt: String): JSONObject =
        obj(get("$portal/api/v1/client/mini-apps", jwt))

    suspend fun createSession(jwt: String, appId: String): JSONObject =
        obj(post("$portal/api/v1/client/mini-apps/${seg(appId)}/sessions", jwt, JSONObject()))

    suspend fun consents(jwt: String, appId: String, scopes: List<String>): JSONObject {
        val url = "$portal/api/v1/client/mini-apps/${seg(appId)}/consents".toHttpUrl().newBuilder()
            .addQueryParameter("scopes", scopes.joinToString(","))
            .build().toString()
        return obj(get(url, jwt))
    }

    suspend fun authCode(jwt: String, appId: String, sessionId: String, scopes: List<String>): JSONObject =
        obj(post("$portal/api/v1/client/mini-apps/${seg(appId)}/auth-codes", jwt, JSONObject()
            .put("sessionId", sessionId)
            .put("scopes", JSONArray(scopes))
            .put("consentGiven", true)))

    suspend fun payment(jwt: String, paymentId: String, pageOrigin: String): JSONObject =
        obj(get("$portal/api/v1/client/payments/${seg(paymentId)}", jwt, mapOf("X-Page-Origin" to pageOrigin)))

    suspend fun cancelPayment(jwt: String, paymentId: String): JSONObject =
        obj(post("$portal/api/v1/client/payments/${seg(paymentId)}/cancel", jwt, JSONObject()))

    // ---------- Trace (fire-and-forget) ----------

    fun trace(step: Int, title: String, detail: String? = null, ref: String? = null) {
        val body = JSONObject()
            .put("actor", "APP")
            .put("step", step)
            .put("title", title)
            .put("detail", detail ?: JSONObject.NULL)
            .put("ref", ref ?: JSONObject.NULL)
        Log.d(TAG, "trace #$step $title ${detail ?: ""} ${ref ?: ""}")
        val req = Request.Builder()
            .url("$portal/api/v1/admin/trace")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "trace #$step failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    // ---------- plumbing ----------

    private fun obj(text: String): JSONObject = if (text.isBlank()) JSONObject() else JSONObject(text)

    private suspend fun get(url: String, bearer: String?, headers: Map<String, String> = emptyMap()): String =
        execute(Request.Builder().url(url).get(), bearer, headers)

    private suspend fun post(url: String, bearer: String?, body: JSONObject): String =
        execute(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)), bearer, emptyMap())

    private suspend fun execute(builder: Request.Builder, bearer: String?, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.header("Accept", "application/json")
            val request = builder.build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw ApiException(0, "NETWORK", "Cannot reach ${request.url.host}:${request.url.port} (${e.message}). Did you run adb reverse?")
            }
            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = runCatching { JSONObject(text) }.getOrNull()
                    val code = err?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_${resp.code}"
                    val msg = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                    throw ApiException(resp.code, code, "$code: $msg")
                }
                text
            }
        }
}

/** Lowercase hex HMAC-SHA256 (used for the device-signed approval, step 33). */
fun hmacSha256Hex(secret: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
}

/** `optString` returns "null" for JSON null; this returns a real Kotlin null instead. */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

/** scheme://host[:port] of a URL, or null. */
fun originOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return if (uri.port != -1) "$scheme://$host:${uri.port}" else "$scheme://$host"
}
