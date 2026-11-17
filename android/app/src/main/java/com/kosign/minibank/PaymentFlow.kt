package com.kosign.minibank

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** What the customer did in the payment sheet / PIN-OTP dialog. */
sealed interface PayAction {
    data class Pay(val account: String) : PayAction
    data class Auth(val code: String) : PayAction
    data object Cancel : PayAction
}

/** PIN / OTP dialog (3:28) shown on top of the sheet once init succeeded. */
data class AuthPrompt(val method: String, val attemptsLeft: Int?, val error: String?, val locked: Boolean = false)

/** State of the payment bottom sheet (3:14). Amount and merchant come from the server's PaymentInfo only. */
class PaySheet(val info: PaymentInfo, accounts: List<Account>) {
    var accounts by mutableStateOf(accounts)
    var selected by mutableStateOf(
        (accounts.firstOrNull { it.available >= info.amount } ?: accounts.firstOrNull())?.accountNo,
    )
    /** Non-null while a call is running (buttons disabled, spinner with this text). */
    var working by mutableStateOf<String?>(null)
    /** Init error shown inside the sheet (3:22e); the customer may retry or cancel. */
    var error by mutableStateOf<String?>(null)
    var auth by mutableStateOf<AuthPrompt?>(null)

    internal val actions = Channel<PayAction>(Channel.UNLIMITED)

    fun send(action: PayAction) {
        if (working != null && action !is PayAction.Cancel) return
        actions.trySend(action)
    }
}

/**
 * Shared payment UI for every integration type (phase "init" + "confirm" of the diagram):
 * sheet (3:14) → Pay (3:15) → init (3:27 / 3:22d-e) → PIN/OTP (3:28, 3:29) → confirm
 * (3:44-45 / 3:38e-f / 3:38j-k / 3:32b-c, or 3:45f-g after a network error) → receipt.
 * These steps are shared, so they are traced with flow = null.
 *
 * Only one payment may be open at a time: callers [reserve] before verifying and [release] in a finally.
 */
class PaymentFlow(private val vm: AppViewModel) {
    var sheet by mutableStateOf<PaySheet?>(null)
        private set
    var receipt by mutableStateOf<Receipt?>(null)
        private set

    private var reserved = false

    /** One sheet at a time (3:5a). Returns false when another payment is already in progress. */
    fun reserve(): Boolean {
        if (reserved) return false
        reserved = true
        return true
    }

    fun release() {
        reserved = false
    }

    /** The Mini App closed: cancel a sheet still waiting on the customer and drop the receipt. */
    fun cancelAll() {
        sheet?.actions?.trySend(PayAction.Cancel)
        receipt?.done?.complete(Unit)
    }

    fun dismissReceipt() {
        receipt?.done?.complete(Unit)
    }

    /** Runs the whole payment UI for [info] and returns `{status, paymentRef, errorCode?}` for the caller. */
    suspend fun run(info: PaymentInfo): PayResult {
        val ref = info.paymentRef
        val s = PaySheet(info, vm.accounts)
        sheet = s
        Api.trace("U", "3:14", null, "Open payment bottom sheet", "${money(info.amount, info.currency)} · ${info.merchantName}", ref)
        var receiptToShow: Receipt? = null
        try {
            // ---- Select account & Pay → init ----
            var authMethod: String? = null
            var lastInitError: String? = null
            while (authMethod == null) {
                when (val a = s.actions.receive()) {
                    PayAction.Cancel -> {
                        // Before init: nothing was held, no API call (CANCELLED). After a failed init: FAILED + code.
                        return if (lastInitError == null) PayResult("CANCELLED", ref)
                        else PayResult("FAILED", ref, lastInitError)
                    }
                    is PayAction.Pay -> {
                        Api.trace("APP", "3:15", null, "Select account & Pay", a.account, ref)
                        s.working = "Preparing payment…"
                        s.error = null
                        try {
                            val token = vm.requireToken()
                            val r = Api.initPayment(token, ref, a.account)
                            val method = r.optString("authMethod", "PIN").ifBlank { "PIN" }
                            Api.trace("APP", "3:27", null, "Txn info, auth method", method, ref)
                            authMethod = method
                        } catch (e: ApiException) {
                            lastInitError = e.code
                            Api.trace("APP", "3:22d", null, "Failed", "${e.code}: ${e.message}", ref)
                            Api.trace("APP", "3:22e", null, "Show error in bottom sheet; customer may retry", e.code, ref)
                            s.error = initErrorText(e)
                        } finally {
                            s.working = null
                        }
                    }
                    is PayAction.Auth -> Unit
                }
            }

            // ---- PIN / OTP → confirm ----
            val method = authMethod!!
            if (method == "OTP") vm.pollNotificationsSoon()
            s.auth = AuthPrompt(method, null, null)
            Api.trace("U", "3:28", null, "PIN / OTP screen", method, ref)

            var result: PayResult? = null
            while (result == null) {
                when (val a = s.actions.receive()) {
                    // After init the hold stays until the payment expires (expiry job); the caller gets CANCELLED.
                    PayAction.Cancel -> result = PayResult("CANCELLED", ref)
                    is PayAction.Pay -> Unit
                    is PayAction.Auth -> {
                        Api.trace("APP", "3:29", null, "Complete authentication", method, ref)
                        s.working = "Processing payment…"
                        try {
                            val r = Api.confirm(vm.requireToken(), ref, method, a.code)
                            val status = r.optString("status", "FAILED")
                            val reason = r.str("failReason")
                            val txn = r.str("bankTxnId")
                            traceConfirmResult(status, txn, reason, ref)
                            receiptToShow = Receipt(status, ref, info.merchantName, info.amount, info.currency, txn, reason)
                            result = PayResult(status, ref, if (status == "FAILED") reason ?: "FAILED" else null)
                        } catch (e: ApiException) {
                            when {
                                e.code == "AUTH_FAILED" -> {
                                    val left = e.body?.optInt("attemptsLeft", -1)?.takeIf { it >= 0 }
                                    Api.trace("APP", "3:32b", null, "Auth failed", "attemptsLeft ${left ?: "?"}", ref)
                                    Api.trace("APP", "3:32c", null, "Let customer retry; txn stays INITIATED; lock after N tries", "attemptsLeft ${left ?: "?"}", ref)
                                    val what = if (method == "OTP") "Wrong OTP" else "Wrong PIN"
                                    s.auth = AuthPrompt(method, left, if (left != null) "$what — $left attempt(s) left" else what)
                                }
                                e.code == "AUTH_LOCKED" -> {
                                    Api.trace("APP", "3:32b", null, "Auth failed", "AUTH_LOCKED: payment authentication locked", ref)
                                    s.auth = null
                                    receiptToShow = Receipt("FAILED", ref, info.merchantName, info.amount, info.currency, null,
                                        "Payment authentication is locked after too many wrong entries (AUTH_LOCKED)")
                                    result = PayResult("FAILED", ref, "AUTH_LOCKED")
                                }
                                e.isNetworkOrServer -> {
                                    // Outcome unknown: ask the bank (3:45a–45e), then show that status.
                                    val status = queryStatus(ref)
                                    receiptToShow = Receipt(status.first, ref, info.merchantName, info.amount, info.currency, status.second, status.third)
                                    result = PayResult(status.first, ref, if (status.first == "FAILED") status.third ?: e.code else null)
                                }
                                else -> {
                                    // e.g. 409 NOT_PENDING / EXPIRED: the payment can no longer complete.
                                    Api.trace("APP", "3:38e", null, "Failed", "${e.code}: ${e.message}", ref)
                                    Api.trace("APP", "3:38f", null, "Show failure, close bottom sheet", e.code, ref)
                                    receiptToShow = Receipt("FAILED", ref, info.merchantName, info.amount, info.currency, null, e.message)
                                    result = PayResult("FAILED", ref, e.code)
                                }
                            }
                        } finally {
                            s.working = null
                        }
                    }
                }
            }
            return result!!
        } finally {
            s.actions.close()
            if (sheet === s) sheet = null
            val rec = receiptToShow
            if (rec != null) {
                receipt = rec
                try {
                    rec.done.await()
                } catch (e: CancellationException) {
                    Log.d(TAG, "receipt wait cancelled")
                } finally {
                    if (receipt === rec) receipt = null
                    vm.refreshAccounts()
                }
            }
        }
    }

    private fun traceConfirmResult(status: String, txn: String?, reason: String?, ref: String) {
        when (status) {
            "SUCCESS" -> {
                Api.trace("APP", "3:44", null, "Success", txn, ref)
                Api.trace("APP", "3:45", null, "Show success, close bottom sheet", txn, ref)
            }
            "PROCESSING" -> {
                Api.trace("APP", "3:38j", null, "Processing", null, ref)
                Api.trace("APP", "3:38k", null, "Show \"Payment is being processed\", close bottom sheet", null, ref)
            }
            else -> {
                Api.trace("APP", "3:38e", null, "Failed", reason, ref)
                Api.trace("APP", "3:38f", null, "Show failure, close bottom sheet", reason, ref)
            }
        }
    }

    /** GET /payments/{ref}/status after a lost confirm answer. Returns (status hint, bankTxnId, failReason). */
    private suspend fun queryStatus(ref: String): Triple<String, String?, String?> {
        return try {
            val r = Api.paymentStatus(vm.requireToken(), ref)
            val raw = r.optString("status", "PROCESSING")
            val status = when (raw) {
                "SUCCESS", "FAILED" -> raw
                "EXPIRED" -> "FAILED"
                else -> "PROCESSING" // PROCESSING, or INITIATED while the confirm is still in flight
            }
            Api.trace("APP", "3:45f", null, "Status", raw, ref)
            Api.trace("APP", "3:45g", null, "Show result for that status; PROCESSING → \"being processed\"", status, ref)
            Triple(status, r.str("bankTxnId"), r.str("failReason") ?: if (raw == "EXPIRED") "EXPIRED" else null)
        } catch (e: ApiException) {
            // Still no answer: the payment may have gone through; the recovery job will settle it.
            Api.trace("APP", "3:45f", null, "Status", "unknown (${e.code})", ref)
            Api.trace("APP", "3:45g", null, "Show result for that status; PROCESSING → \"being processed\"", "PROCESSING", ref)
            Triple("PROCESSING", null, null)
        }
    }

    private fun initErrorText(e: ApiException): String {
        val text = when (e.code) {
            "INSUFFICIENT_FUNDS" -> "Insufficient funds in this account."
            "ACCOUNT_NOT_OWNED" -> "This account can't be used."
            "CURRENCY_MISMATCH" -> "This account's currency doesn't match the payment."
            "LIMIT_EXCEEDED" -> "The amount is over your payment limit."
            "NOT_PENDING" -> "This payment is no longer open."
            "EXPIRED" -> "This payment has expired."
            "INIT_FAILED" -> "The bank couldn't reserve the amount. Try again."
            "INIT_TIMEOUT" -> "The bank took too long to answer. Try again."
            else -> e.message
        }
        return "$text (${e.code})"
    }

    companion object {
        private const val TAG = "PaymentFlow"
    }
}
