package com.panakam.construction.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager

/**
 * A "type your password to confirm" gate for destructive actions (delete
 * project/unit/user/sales rep/file/inventory item/financial record/etc.).
 *
 * This is deliberately a SEPARATE, reusable component rather than baked into
 * each screen's own delete dialog, so every delete flow in the app gets the
 * exact same protection: the entered password is verified against the CURRENT
 * logged-in user's own password via `AuthManager.verifyPassword` (server-side
 * check — never just a client-side comparison) before [onConfirmed] runs. If
 * the password is wrong, an inline error is shown and nothing happens.
 *
 * Callers should use this INSTEAD OF a plain "Are you sure?" AlertDialog for
 * any delete/remove action — put the actual delete API call inside
 * [onConfirmed].
 */
@Composable
fun PasswordConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm & Delete",
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    var password     by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg     by remember { mutableStateOf("") }
    var isVerifying  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = "" },
                    label = { Text("Your Password") },
                    singleLine = true,
                    isError = errorMsg.isNotEmpty(),
                    enabled = !isVerifying,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg.isNotEmpty()) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank() && !isVerifying,
                onClick = {
                    errorMsg = ""; isVerifying = true
                    AuthManager.verifyPassword(
                        password,
                        onSuccess = { isVerifying = false; onConfirmed() },
                        onFailure = { msg -> isVerifying = false; errorMsg = msg }
                    )
                }
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirmLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isVerifying) { Text("Cancel") }
        }
    )
}

