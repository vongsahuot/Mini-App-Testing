@file:OptIn(ExperimentalMaterial3Api::class)

package com.kosign.minibank.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kosign.minibank.AppSession
import com.kosign.minibank.AppViewModel
import com.kosign.minibank.Bridge
import com.kosign.minibank.ConsentRequest
import com.kosign.minibank.PaymentRequest
import com.kosign.minibank.Receipt
import com.kosign.minibank.money
import com.kosign.minibank.originOf
import org.json.JSONObject

private const val TAG = "WebAppScreen"

@Composable
fun WebAppScreen(vm: AppViewModel) {
    val app = vm.appSession ?: return
    val context = LocalContext.current
    val webView = remember(app.sessionId) { createWebView(context, vm, app) }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.removeJavascriptInterface("KosignNative")
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else vm.closeWebApp("back button")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app.appName) },
                actions = {
                    IconButton(onClick = { vm.closeWebApp("X button") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    vm.consentRequest?.let { ConsentSheet(it, vm) }
    vm.paymentRequest?.let { PaymentSheet(it, vm) }
    vm.receipt?.let { ReceiptDialog(it, vm) }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun createWebView(context: Context, vm: AppViewModel, app: AppSession): WebView {
    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
            val origin = originOf(target.toString())
            if (origin != null && origin in app.allowedOrigins) return false
            // Navigation to a non-allowed origin (or a non-http scheme): open outside the WebView.
            Log.i(TAG, "Opening externally: $target")
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No app to open $target")
            }
            return true
        }
    }

    // Every call is posted to the main thread by Bridge; the origin is read from the WebView there.
    webView.addJavascriptInterface(Bridge { json ->
        val pageOrigin = originOf(webView.url)
        vm.onBridgeMessage(json, pageOrigin) { id, ok, payload -> reply(webView, id, ok, payload) }
    }, "KosignNative")

    webView.loadUrl(app.url)
    return webView
}

private fun reply(webView: WebView, id: String, ok: Boolean, payload: JSONObject) {
    val js = "window.__kosignBridgeCallback(${JSONObject.quote(id)}, $ok, $payload)"
    webView.evaluateJavascript(js, null)
}

// ---------------------------------------------------------------------------------------------
// Consent sheet (steps 11–12)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ConsentSheet(req: ConsentRequest, vm: AppViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Swipe-down / scrim tap / back = Deny.
    ModalBottomSheet(onDismissRequest = { vm.answerConsent(false) }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("🔐", fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "${req.partnerName} wants to access",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            req.scopeLabels.forEach { label ->
                Text("•  $label", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "KB Bank shares only this information with ${req.partnerName}. You can revoke it later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.answerConsent(false) }, modifier = Modifier.weight(1f)) { Text("Deny") }
                Button(onClick = { vm.answerConsent(true) }, modifier = Modifier.weight(1f)) { Text("Allow") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Payment sheet (steps 29–33)
// ---------------------------------------------------------------------------------------------

@Composable
private fun PaymentSheet(req: PaymentRequest, vm: AppViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember(req) { mutableStateOf(req.accounts.firstOrNull()?.accountNo) }
    var askPin by remember(req) { mutableStateOf(false) }

    // Swipe-down / scrim tap / back = Cancel.
    ModalBottomSheet(onDismissRequest = { vm.answerPayment(null) }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Confirm payment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(req.merchantName, style = MaterialTheme.typography.titleMedium)
            if (req.description.isNotBlank()) {
                Text(req.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                money(req.amount, req.currency),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = BankPurple,
            )
            Text(
                "Payment ${req.paymentId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Pay from", style = MaterialTheme.typography.labelLarge)
            req.accounts.forEach { acc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = acc.accountNo }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == acc.accountNo, onClick = { selected = acc.accountNo })
                    Column {
                        Text("${acc.name} · ${acc.accountNo}")
                        Text(
                            "Balance ${money(acc.balance, acc.currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (req.accounts.isEmpty()) Text("No accounts available", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.answerPayment(null) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { askPin = true },
                    enabled = selected != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Approve") }
            }
        }
    }

    if (askPin) {
        PinDialog(
            onDismiss = { askPin = false },
            onVerified = {
                askPin = false
                vm.answerPayment(selected)
            },
        )
    }
}

/**
 * Mock of the biometric / device-PIN prompt that would unlock a hardware Keystore key used to sign
 * the paymentId. Here we just compare to the fixed mock PIN; the "key" is the login deviceSecret.
 */
@Composable
private fun PinDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN to sign") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter(Char::isDigit).take(6)
                    error = false
                },
                label = { Text("PIN") },
                singleLine = true,
                isError = error,
                supportingText = { Text(if (error) "Wrong PIN" else "Mock biometrics — PIN is ${AppViewModel.MOCK_PIN}") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (pin == AppViewModel.MOCK_PIN) onVerified() else error = true }) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

// ---------------------------------------------------------------------------------------------
// Receipt (after step 40)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ReceiptDialog(receipt: Receipt, vm: AppViewModel) {
    val paid = receipt.status == "PAID"
    AlertDialog(
        onDismissRequest = { vm.dismissReceipt() },
        icon = { Text(if (paid) "✅" else "❌", fontSize = 32.sp) },
        title = { Text(if (paid) "Payment successful" else "Payment failed") },
        text = {
            Column {
                Text(
                    money(receipt.amount, receipt.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (paid) BankPurple else Color(0xFFB3261E),
                )
                Spacer(Modifier.height(8.dp))
                Text("Status: ${receipt.status}")
                receipt.bankTxnId?.let { Text("Bank txn: $it") }
                receipt.reason?.let { Text("Reason: $it") }
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissReceipt() }) { Text("Done") } },
    )
}
