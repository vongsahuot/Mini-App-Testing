@file:OptIn(ExperimentalMaterial3Api::class)

package com.kosign.minibank.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kosign.minibank.AppViewModel
import com.kosign.minibank.Bridge
import com.kosign.minibank.WebSession
import com.kosign.minibank.originOf
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val TAG = "WebAppScreen"
private const val LOAD_TIMEOUT_MS = 15_000L

/** Load state of the entry page (native error + Retry, step 2:25a). */
private class LoadState {
    var attempt by mutableStateOf(0)
    var finished by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun retry() {
        error = null
        finished = false
        attempt++
    }
}

@Composable
fun WebAppScreen(vm: AppViewModel) {
    val web = vm.webApp ?: return
    val context = LocalContext.current
    val load = remember(web.id) { LoadState() }
    val webView = remember(web.id) { createWebView(context, vm, web, load) }

    DisposableEffect(webView) {
        onDispose {
            // 4:21: remove the bridge with the WebView. Cookies / storage (the customer's data store) are kept.
            webView.stopLoading()
            webView.removeJavascriptInterface(Bridge.JS_NAME)
            webView.destroy()
        }
    }

    // Watchdog: the entry page must finish loading in ~15 s.
    LaunchedEffect(webView, load.attempt) {
        delay(LOAD_TIMEOUT_MS)
        if (!load.finished && load.error == null) {
            load.error = "The page didn't load within ${LOAD_TIMEOUT_MS / 1000} s"
            webView.stopLoading()
        }
    }
    LaunchedEffect(load.error) {
        load.error?.let { vm.onWebLoadFailed(it) }
    }

    BackHandler {
        if (load.error == null && webView.canGoBack()) webView.goBack() else vm.closeMiniApp("back button")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${web.detail.icon} ${web.detail.name}") },
                actions = {
                    IconButton(onClick = { vm.closeMiniApp("native X") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

            val err = load.error
            if (err == null && !load.finished) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (err != null) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("😕", fontSize = 48.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Can't load ${web.detail.name}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = {
                            load.retry()
                            web.detail.entryUrl?.let { webView.loadUrl(it) }
                        }) { Text("Retry") }
                        TextButton(onClick = { vm.closeMiniApp("native X") }) { Text("Close") }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun createWebView(context: Context, vm: AppViewModel, web: WebSession, load: LoadState): WebView {
    val allowed = web.detail.allowedOrigins
    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // No pop-up windows: a window.open() target can't get the bridge or escape the origin check.
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
            val origin = originOf(target.toString())
            if (origin != null && origin in allowed) return false
            if (!request.isForMainFrame) {
                Log.w(TAG, "Blocked sub-frame navigation to $target")
                return true
            }
            // Navigation to another origin (or a non-http scheme): open outside the WebView.
            Log.i(TAG, "Opening externally: $target")
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No app to open $target")
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            Log.d(TAG, "page started $url")
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (load.error == null) load.finished = true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                load.error = "${error.description} (error ${error.errorCode})"
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                load.error = "The page answered HTTP ${errorResponse.statusCode}"
            }
        }
    }

    // Every call is posted to the main thread by Bridge; the origin is read from the WebView there.
    webView.addJavascriptInterface(Bridge { json ->
        vm.onBridgeMessage(json, originOf(webView.url)) { id, ok, payload -> reply(webView, allowed, id, ok, payload) }
    }, Bridge.JS_NAME)

    web.detail.entryUrl?.let { webView.loadUrl(it) }
    return webView
}

/** `window.__mbappBridgeCallback(id, ok, payload)`. Successful payloads only go to an allowed origin. */
private fun reply(webView: WebView, allowed: List<String>, id: String, ok: Boolean, payload: JSONObject) {
    val origin = originOf(webView.url)
    if (ok && (origin == null || origin !in allowed)) {
        Log.w(TAG, "Dropping bridge reply $id: page is now at $origin")
        return
    }
    val cb = "window.${Bridge.JS_CALLBACK}"
    webView.evaluateJavascript("$cb && $cb(${JSONObject.quote(id)}, $ok, $payload)", null)
}
