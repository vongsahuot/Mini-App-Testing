@file:OptIn(ExperimentalMaterial3Api::class)

package com.kosign.minibank.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kosign.minibank.AppViewModel
import com.kosign.minibank.CatalogRow
import com.kosign.minibank.NativeAppState
import com.kosign.minibank.NativeResult
import com.kosign.minibank.money
import com.kosign.minibank.sdk.StarlightSdk

// =================================================================================================
// API linkage (type `api`): screens built by the bank. The partner only supplies DATA through the bank
// (content / orders); titles and prices are rendered as plain Text, never as HTML or code.
// =================================================================================================

@Composable
fun NativeAppScreen(vm: AppViewModel) {
    val st = vm.nativeApp ?: return
    BackHandler { vm.nativeBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${st.detail.icon} ${st.detail.name}") },
                navigationIcon = {
                    IconButton(onClick = { vm.nativeBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.closeNative() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
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
            val result = st.result
            val confirm = st.confirm
            when {
                result != null -> ResultScreen(vm, st, result)
                confirm != null -> ConfirmScreen(vm, st, confirm)
                else -> ListScreens(vm, st)
            }
        }
    }
}

@Composable
private fun ListScreens(vm: AppViewModel, st: NativeAppState) {
    val tabs = st.detail.screens.ifEmpty { listOf("catalog", "orders") }
    val index = tabs.indexOf(st.tab).coerceAtLeast(0)
    TabRow(selectedTabIndex = index) {
        tabs.forEach { op ->
            Tab(
                selected = st.tab == op,
                onClick = { vm.nativeLoad(op) },
                text = { Text(if (op == "catalog") "Movies" else if (op == "orders") "My orders" else op) },
            )
        }
    }
    if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

    val error = st.error
    if (error != null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("📡", fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.nativeLoad(st.tab) }, enabled = !st.loading) { Text("Try again") }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (st.tab == "orders") {
            st.orders.forEach { o ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(o.title, fontWeight = FontWeight.SemiBold)
                        Text("${money(o.amount, o.currency)} · ${o.status}", style = MaterialTheme.typography.bodySmall)
                        o.ticket?.let { Text("🎟️ $it", style = MaterialTheme.typography.bodySmall) }
                        Text(
                            o.orderId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (st.orders.isEmpty() && !st.loading) Text("No orders yet")
        } else {
            st.catalog.forEach { item ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            if (item.showtime.isNotBlank()) Text(item.showtime, style = MaterialTheme.typography.bodySmall)
                            Text(money(item.price, item.currency), color = BankPurple, fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = { vm.nativeBuy(item) }) { Text("Buy") }
                    }
                }
            }
            if (st.catalog.isEmpty() && !st.loading) Text("Nothing to show")
        }
        if (st.cached) {
            Text(
                "Showing saved content (the partner is not answering right now)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 3:L15: the customer confirms the order on a bank screen before the bank asks the partner to create it. */
@Composable
private fun ConfirmScreen(vm: AppViewModel, st: NativeAppState, item: CatalogRow) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("Confirm your order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        if (item.showtime.isNotBlank()) Text(item.showtime, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("1 × ${money(item.price, item.currency)}")
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            "Sold by ${st.detail.merchantName}. The final amount is confirmed by the bank on the next screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        st.orderError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        if (st.ordering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text("Creating your order…")
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.nativeBack() }, enabled = !st.ordering, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = { vm.nativeConfirm() }, enabled = !st.ordering, modifier = Modifier.weight(1f)) {
                Text(if (st.orderError != null) "Try again" else "Confirm & pay")
            }
        }
    }
}

/** 3:L29: native result from the bank's own confirm / status answer (no callback to partner code). */
@Composable
private fun ResultScreen(vm: AppViewModel, st: NativeAppState, r: NativeResult) {
    val status = r.result.status
    val (icon, title, color) = when (status) {
        "SUCCESS" -> Triple("✅", "Payment successful", BankPurple)
        "PROCESSING" -> Triple("⏳", "Payment is being processed", Color(0xFF8A6D00))
        "CANCELLED" -> Triple("↩️", "Payment cancelled", Color.Gray)
        else -> Triple("❌", "Payment failed", Color(0xFFB3261E))
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(money(r.info.amount, r.info.currency), style = MaterialTheme.typography.headlineSmall)
            Text(r.info.merchantName)
            Spacer(Modifier.height(8.dp))
            Text("Ref ${r.info.paymentRef}", style = MaterialTheme.typography.bodySmall)
            r.result.errorCode?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
            if (status == "SUCCESS") {
                Text(
                    "Your ticket will appear in My orders once ${st.detail.merchantName} confirms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.nativeDone("catalog") }) { Text("Back to Movies") }
                Button(onClick = { vm.nativeDone("orders") }) { Text("My orders") }
            }
        }
    }
}

// =================================================================================================
// SDK embedding (type `sdk`): MB App chrome + the partner SDK screen, which gets only the host API.
// =================================================================================================

@Composable
fun SdkHostScreen(vm: AppViewModel) {
    val launch = vm.sdkApp ?: return
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${launch.detail.icon} ${launch.detail.name}") },
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
            StarlightSdk.Screen(
                host = launch.host,
                appId = launch.detail.appId,
                locale = "en",
                onTrace = vm::sdkTrace,
            )
        }
    }
}
