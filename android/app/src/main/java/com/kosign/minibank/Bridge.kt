package com.kosign.minibank

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Injected as `window.MBAppNative`. The page calls
 * `MBAppNative.postMessage(JSON.stringify({ id, method, params }))`; MB App answers with
 * `window.__mbappBridgeCallback(id, ok, payload)`.
 *
 * JavaScript interface methods run on a WebView binder thread, so every message is posted to the
 * main thread before anything (origin check, UI, WebView access) happens.
 *
 * Note: Android exposes a JavaScript interface to every frame of the page (iframes included) and does not
 * say which frame called. The origin check therefore uses the WebView's main-frame URL (see README).
 */
class Bridge(private val onMessage: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String) {
        main.post { onMessage(json) }
    }

    companion object {
        const val JS_NAME = "MBAppNative"
        const val JS_CALLBACK = "__mbappBridgeCallback"
    }
}
