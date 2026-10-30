package com.kosign.minibank.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BankPurple = Color(0xFF6A4FD0)
val BankPurpleLight = Color(0xFFA592FF)

private val Colors = lightColorScheme(
    primary = BankPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF),
    onPrimaryContainer = Color(0xFF22135F),
    secondary = BankPurpleLight,
    onSecondary = Color.White,
    background = Color(0xFFF7F5FC),
    surface = Color.White,
)

@Composable
fun MiniBankTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

/** Parses "#RRGGBB" / "#AARRGGBB", falling back to the bank purple. */
fun parseColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(BankPurple)
