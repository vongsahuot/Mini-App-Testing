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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.kosign.minibank.Screen
import com.kosign.minibank.money

@Composable
fun AppRoot(vm: AppViewModel) {
    MiniBankTheme {
        val snackbar = remember { SnackbarHostState() }
        val msg = vm.message
        LaunchedEffect(msg) {
            if (msg != null) {
                vm.message = null
                snackbar.showSnackbar(msg)
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (vm.screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm)
                Screen.MiniApps -> MiniAppsScreen(vm)
                Screen.WebApp -> WebAppScreen(vm)
            }

            // Blocking progress overlay for network calls (not shown while the WebView is waiting on a sheet).
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
            Text("KB Bank", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = BankPurple)
            Text("Mock bank app · Mini App Payment Flow", style = MaterialTheme.typography.bodyMedium)
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
                "Test users: alice / 1234 (USD 500.00), bob / 1234 (USD 3.00)\nDevice: ${vm.deviceId.take(8)}…",
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
                title = { Text("KB Bank") },
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
                "${session?.customerId.orEmpty()} · ${session?.phone.orEmpty()}",
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
                        Text(
                            money(acc.balance, acc.currency),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
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
                Text("🧩  Mini apps", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
