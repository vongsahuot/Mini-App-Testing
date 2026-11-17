@file:OptIn(ExperimentalMaterial3Api::class)

package com.kosign.minibank.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kosign.minibank.AppViewModel
import com.kosign.minibank.MiniApp
import com.kosign.minibank.Types

private const val COLUMNS = 4

@Composable
fun MiniAppsScreen(vm: AppViewModel) {
    BackHandler { vm.backToHome() }
    val data = vm.miniApps

    // 1:9 is reported once a list from the server (200 or 304) has been composed.
    LaunchedEffect(data) {
        if (data != null) vm.onMiniAppsRendered(data)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mini Apps") },
                navigationIcon = {
                    IconButton(onClick = { vm.backToHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (vm.miniAppsLoading && data != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                data == null && vm.miniAppsLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Could not load Mini Apps")
                }
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    data.sections.forEach { section ->
                        Spacer(Modifier.height(8.dp))
                        Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        section.apps.chunked(COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { app ->
                                    AppTile(app, Modifier.weight(1f)) { vm.openApp(app) }
                                }
                                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (data.allApps.isEmpty()) Text("No Mini Apps for you yet")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "List from ${data.source}" + (data.etag?.let { " · ETag $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: MiniApp, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text(app.icon, fontSize = 28.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            Types.label(app.type),
            color = typeColor(app.type),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(typeColor(app.type).copy(alpha = 0.12f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

private fun typeColor(type: String): Color = when (type) {
    Types.WEBVIEW -> Color(0xFF1E6FD9)
    Types.API -> Color(0xFF15803D)
    Types.SDK -> Color(0xFFB45309)
    Types.A2A -> Color(0xFF7C3AED)
    else -> Color.Gray
}
