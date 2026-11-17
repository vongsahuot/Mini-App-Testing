@file:OptIn(ExperimentalMaterial3Api::class)

package com.kosign.minibank.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kosign.minibank.AppViewModel
import com.kosign.minibank.AuthPrompt
import com.kosign.minibank.PayAction
import com.kosign.minibank.PaySheet
import com.kosign.minibank.Receipt
import com.kosign.minibank.money

// ---------------------------------------------------------------------------------------------
// Shared payment bottom sheet (3:14 → 3:15) + PIN / OTP dialog (3:28 → 3:29) + receipt.
// Everything shown here comes from the bank's PaymentInfo, never from the partner's order.
// ---------------------------------------------------------------------------------------------

@Composable
fun PaymentSheet(sheet: PaySheet, vm: AppViewModel) {
    val info = sheet.info
    val working = sheet.working
    // The sheet can't be swiped away while init / confirm is running.
    val canHide by rememberUpdatedState(working == null)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || canHide },
    )

    // Swipe-down / scrim tap / back = Cancel (ignored while a call is running).
    ModalBottomSheet(
        onDismissRequest = { if (working == null) sheet.send(PayAction.Cancel) },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Confirm payment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(info.merchantName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                money(info.amount, info.currency),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = BankPurple,
            )
            Text(
                "Ref ${info.paymentRef}" + (info.orderId?.let { " · order $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Pay from", style = MaterialTheme.typography.labelLarge)
            sheet.accounts.forEach { acc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = working == null && sheet.auth == null) { sheet.selected = acc.accountNo }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = sheet.selected == acc.accountNo,
                        onClick = { sheet.selected = acc.accountNo },
                        enabled = working == null && sheet.auth == null,
                    )
                    Column {
                        Text("${acc.name} · ${acc.accountNo}")
                        Text(
                            "Available ${money(acc.available, acc.currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (sheet.accounts.isEmpty()) Text("No accounts available", color = MaterialTheme.colorScheme.error)

            sheet.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (working != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(working)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { sheet.send(PayAction.Cancel) },
                    enabled = working == null,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                Button(
                    onClick = { sheet.selected?.let { sheet.send(PayAction.Pay(it)) } },
                    enabled = working == null && sheet.selected != null && sheet.auth == null,
                    modifier = Modifier.weight(1f),
                ) { Text(if (sheet.error != null) "Try again" else "Pay") }
            }
        }
    }

    sheet.auth?.let { AuthDialog(sheet, it, vm) }
}

/** PIN or OTP entry (3:28). The latest OTP from the push notifications is shown as a hint. */
@Composable
private fun AuthDialog(sheet: PaySheet, prompt: AuthPrompt, vm: AppViewModel) {
    val isOtp = prompt.method == "OTP"
    var code by remember(prompt) { mutableStateOf("") }
    val working = sheet.working
    val otp = vm.latestOtp?.takeIf { it.ref == null || it.ref == sheet.info.paymentRef }

    AlertDialog(
        onDismissRequest = { if (working == null) sheet.send(PayAction.Cancel) },
        title = { Text(if (isOtp) "Enter the OTP" else "Enter your PIN") },
        text = {
            Column {
                Text(
                    if (isOtp) "We sent a 6-digit code to your phone (push notification) for ${money(sheet.info.amount, sheet.info.currency)}."
                    else "Confirm ${money(sheet.info.amount, sheet.info.currency)} to ${sheet.info.merchantName}.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(if (isOtp) "OTP" else "PIN") },
                    singleLine = true,
                    isError = prompt.error != null,
                    supportingText = {
                        Text(prompt.error ?: if (isOtp) "Valid for 5 minutes" else "Mock PIN: 1234")
                    },
                    visualTransformation = if (isOtp) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (isOtp) KeyboardType.Number else KeyboardType.NumberPassword),
                    enabled = working == null,
                )
                if (isOtp) {
                    if (otp != null) {
                        Text(
                            "Latest OTP: ${otp.code}  (tap to fill)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BankPurple,
                            modifier = Modifier
                                .clickable(enabled = working == null) { code = otp.code }
                                .padding(vertical = 6.dp),
                        )
                    } else {
                        Text(
                            "Waiting for the OTP notification…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (working != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(working)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { sheet.send(PayAction.Auth(code)) },
                enabled = working == null && code.length >= (if (isOtp) 6 else 4),
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = { sheet.send(PayAction.Cancel) }, enabled = working == null) { Text("Cancel") }
        },
    )
}

@Composable
fun ReceiptDialog(receipt: Receipt, vm: AppViewModel) {
    val (icon, title, color) = when (receipt.status) {
        "SUCCESS" -> Triple("✅", "Payment successful", BankPurple)
        "PROCESSING" -> Triple("⏳", "Payment is being processed", Color(0xFF8A6D00))
        else -> Triple("❌", "Payment failed", Color(0xFFB3261E))
    }
    AlertDialog(
        onDismissRequest = { vm.payment.dismissReceipt() },
        icon = { Text(icon, fontSize = 32.sp) },
        title = { Text(title) },
        text = {
            Column {
                Text(
                    money(receipt.amount, receipt.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Text(receipt.merchantName)
                Spacer(Modifier.height(8.dp))
                Text("Status: ${receipt.status}")
                Text("Ref: ${receipt.paymentRef}", style = MaterialTheme.typography.bodySmall)
                receipt.bankTxnId?.let { Text("Bank txn: $it", style = MaterialTheme.typography.bodySmall) }
                receipt.reason?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
                if (receipt.status == "PROCESSING") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "We'll notify you when the bank confirms. No need to pay again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.payment.dismissReceipt() }) { Text("Done") } },
    )
}
