package com.panakam.construction.ui.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Forced-black text colors for OutlinedTextField fields that sit inside white Cards
 * (Login, Register, ForgotPassword screens).  Keeps labels/borders blue-branded.
 */
@Composable
fun formTextFieldColors() = OutlinedTextFieldDefaults.colors(
    // Input text – always solid black regardless of dark/light mode
    focusedTextColor     = Color(0xFF0D0D0D),
    unfocusedTextColor   = Color(0xFF0D0D0D),
    disabledTextColor    = Color(0xFF555555),
    // Container – always white so text is readable inside white Cards
    focusedContainerColor   = Color.White,
    unfocusedContainerColor = Color.White,
    disabledContainerColor  = Color(0xFFF5F5F5),
    // Placeholder / hint
    focusedPlaceholderColor   = Color(0xFF888888),
    unfocusedPlaceholderColor = Color(0xFF888888),
    // Label
    focusedLabelColor    = GradientTop,
    unfocusedLabelColor  = Color(0xFF444444),
    // Border
    focusedBorderColor   = GradientTop,
    unfocusedBorderColor = Color(0xFF999999),
    // Cursor
    cursorColor          = GradientTop,
    // Leading / trailing icons
    focusedLeadingIconColor    = GradientTop,
    unfocusedLeadingIconColor  = Color(0xFF666666),
    focusedTrailingIconColor   = GradientTop,
    unfocusedTrailingIconColor = Color(0xFF666666),
)

