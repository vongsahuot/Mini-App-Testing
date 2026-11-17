package com.kosign.minibank

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kosign.minibank.ui.AppRoot

/**
 * Single activity. Also receives the App-to-App links `http://localhost:3000/links/mbapp/*`
 * (intent filter in AndroidManifest.xml, launchMode singleTask → [onNewIntent] while running).
 */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        setContent { AppRoot(vm) }
        // Launched by a link (cold start). Not again after a recreation: the ViewModel already handled it.
        if (savedInstanceState == null) handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        vm.links.handle(uri)
    }
}
