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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kosign.minibank.AppViewModel
import com.kosign.minibank.Banner
import com.kosign.minibank.MiniApp

private const val COLUMNS = 4

@Composable
fun MiniAppsScreen(vm: AppViewModel) {
    BackHandler { vm.backToHome() }
    val data = vm.miniApps

    // Step 5 is reported once the list (cached or fresh) has been composed.
    LaunchedEffect(data) {
        if (data != null) vm.onMiniAppsRendered(data)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mini apps") },
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
                    Text("Could not load mini apps")
                }
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    data.banners.forEach { banner ->
                        BannerCard(banner) { vm.openBanner(banner) }
                        Spacer(Modifier.height(12.dp))
                    }
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "List from ${data.source} · cache TTL ${data.cacheTtlSeconds}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerCard(banner: Banner, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(parseColor(banner.color))
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Text(banner.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (banner.subtitle.isNotBlank()) {
            Text(banner.subtitle, color = Color.White.copy(alpha = 0.85f))
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
        Box {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .size(56.dp)
                    .alpha(if (app.enabled) 1f else 0.5f),
            ) {
                Box(contentAlignment = Alignment.Center) { Text(app.icon, fontSize = 28.sp) }
            }
            val badge = app.badge
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE5484D))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
