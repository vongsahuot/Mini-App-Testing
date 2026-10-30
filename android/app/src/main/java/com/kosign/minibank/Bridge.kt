package com.kosign.minibank

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Injected as `window.KosignNative`. The page calls
 * `KosignNative.postMessage(JSON.stringify({ id, method, params }))`.
 *
 * JavaScript interface methods run on a WebView binder thread, so every message is posted to the
 * main thread before anything (origin check, UI, WebView access) happens.
 */
class Bridge(private val onMessage: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String) {
        main.post { onMessage(json) }
    }
}
