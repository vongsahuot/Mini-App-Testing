@file:OptIn(ExperimentalMaterial3Api::class)

package com.kosign.minibank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kosign.minibank.AppViewModel
import com.kosign.minibank.ConsentRequest
import com.kosign.minibank.LinkRequest
import com.kosign.minibank.Notice
import com.kosign.minibank.PinPrompt
import com.kosign.minibank.Screen
import com.kosign.minibank.money

@Composable
fun AppRoot(vm: AppViewModel) {
    MiniBankTheme {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            for (msg in vm.messages) {
                val long = msg.startsWith("🔑")
                snackbar.showSnackbar(
                    msg,
                    withDismissAction = true,
                    duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (vm.screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm)
                Screen.MiniApps -> MiniAppsScreen(vm)
                Screen.WebApp -> WebAppScreen(vm)
                Screen.NativeApp -> NativeAppScreen(vm)
                Screen.Sdk -> SdkHostScreen(vm)
            }

            // Blocking progress overlay for network calls.
            val busy = vm.busy
            if (busy != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.size(16.dp))
                            Text(busy)
                        }
                    }
                }
            }

            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }

        // Native sheets and dialogs, shown above whatever screen is open (WebView, SDK, native, list…).
        vm.consentRequest?.let { ConsentSheet(it, vm) }
        vm.payment.sheet?.let { PaymentSheet(it, vm) }
        vm.payment.receipt?.let { ReceiptDialog(it, vm) }
        vm.linkRequest?.let { LinkDialog(it, vm) }
        vm.pinPrompt?.let { PinPromptDialog(it, vm) }
        vm.notice?.let { NoticeDialog(it, vm) }
    }
}

@Composable
private fun LoginScreen(vm: AppViewModel) {
    var username by rememberSaveable { mutableStateOf("alice") }
    var pin by rememberSaveable { mutableStateOf("1234") }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🏦", fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text("MB App", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = BankPurple)
            Text("Mock mobile banking · Mini App Payment Flow", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.login(username, pin) },
                enabled = username.isNotBlank() && pin.isNotBlank() && vm.busy == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text("Log in") }
            Spacer(Modifier.height(16.dp))
            Text(
                "Test users: alice / 1234 (USD 500.00, PREMIUM), bob / 1234 (USD 3.00)\n" +
                    "App ${com.kosign.minibank.Api.APP_VERSION} · android · device ${vm.deviceId.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel) {
    // Runs every time Home is (re-)entered, e.g. after returning from a payment.
    LaunchedEffect(Unit) { vm.refreshAccounts() }
    val session = vm.session

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MB App") },
                actions = { TextButton(onClick = { vm.logout() }) { Text("Log out") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Hello, ${session?.name ?: "customer"} 👋", style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(session?.customerId, session?.phone, session?.segment).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            vm.accounts.forEach { acc ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(listOf(BankPurple, BankPurpleLight)))
                            .padding(20.dp),
                    ) {
                        Text(acc.name, color = Color.White.copy(alpha = 0.85f))
                        Text(acc.accountNo, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Text("Available", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        Text(
                            money(acc.available, acc.currency),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Balance ${money(acc.balance, acc.currency)} · Held ${money(acc.held, acc.currency)}",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (vm.accounts.isEmpty()) Text("No accounts")

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.openMiniApps() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text("🧩  Mini Apps", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
            vm.latestOtp?.let {
                Text(
                    "Latest OTP: ${it.code}" + (it.ref?.let { r -> " · $r" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Consent sheet (2:9 → 2:10)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ConsentSheet(req: ConsentRequest, vm: AppViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Swipe-down / scrim tap / back = Decline.
    ModalBottomSheet(onDismissRequest = { vm.answerConsent(false) }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(req.icon, fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "${req.appName} by ${req.merchantName} wants to use",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            req.scopeLabels.forEach { label ->
                Text("•  $label", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "MB App shares only this information with ${req.merchantName} and registers you with them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.answerConsent(false) }, modifier = Modifier.weight(1f)) { Text("Decline") }
                Button(onClick = { vm.answerConsent(true) }, modifier = Modifier.weight(1f)) { Text("Accept") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// App-to-App dialogs (2:A6, 3:D31, 3:D35d) and the SDK error screen (2:25a)
// ---------------------------------------------------------------------------------------------

@Composable
private fun LinkDialog(req: LinkRequest, vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.answerLink(false) },
        icon = { Text(req.icon, fontSize = 32.sp) },
        title = { Text("Link to ${req.appName}?") },
        text = {
            Text("${req.merchantName} will be able to sign you in with MB App and send you payment requests.")
        },
        confirmButton = { TextButton(onClick = { vm.answerLink(true) }) { Text("Link") } },
        dismissButton = { TextButton(onClick = { vm.answerLink(false) }) { Text("Don't link") } },
    )
}

@Composable
private fun PinPromptDialog(prompt: PinPrompt, vm: AppViewModel) {
    var pin by remember(prompt) { mutableStateOf("") }
    val error = prompt.error
    AlertDialog(
        onDismissRequest = { vm.answerPin(null) },
        icon = { Text("🔒", fontSize = 32.sp) },
        title = { Text(prompt.title) },
        text = {
            Column {
                Text(prompt.message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "Mock biometrics: your login PIN (1234)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.answerPin(pin) }, enabled = pin.length >= 4) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = { vm.answerPin(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun NoticeDialog(notice: Notice, vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismissNotice() },
        icon = { Text("⚠️", fontSize = 32.sp) },
        title = { Text(notice.title) },
        text = { Text(notice.message) },
        confirmButton = { TextButton(onClick = { vm.dismissNotice() }) { Text("OK") } },
    )
}
