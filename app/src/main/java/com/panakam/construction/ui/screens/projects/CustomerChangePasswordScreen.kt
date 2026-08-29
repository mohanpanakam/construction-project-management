package com.panakam.construction.ui.screens.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.ui.theme.GradientBottom
import com.panakam.construction.ui.theme.GradientMiddle
import com.panakam.construction.ui.theme.GradientTop
import com.panakam.construction.ui.theme.formTextFieldColors

/**
 * Mandatory password-change screen shown to customers on their first login.
 * The back button is intercepted — the customer cannot skip this step.
 * After a successful change, [onPasswordChanged] is called to navigate to the portal.
 * [onLogout] is called if the customer taps "Sign Out" instead.
 */
@Composable
fun CustomerChangePasswordScreen(
    onPasswordChanged: () -> Unit,
    onLogout: () -> Unit
) {
    val user = AuthManager.getCurrentUser() ?: return

    var newPassword     by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showNew         by remember { mutableStateOf(false) }
    var showConfirm     by remember { mutableStateOf(false) }
    var isLoading       by remember { mutableStateOf(false) }
    var errorMsg        by remember { mutableStateOf("") }

    // Block back navigation — this step is mandatory
    BackHandler(enabled = true) { /* do nothing */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientTop, GradientMiddle, GradientBottom)))
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(48.dp))

        Icon(Icons.Filled.Lock, contentDescription = null,
            modifier = Modifier.size(64.dp), tint = Color.White)
        Spacer(Modifier.height(10.dp))
        Text("Set Your Password", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
            color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Welcome, ${user.name}!\nFor security, please set a new password before continuing.",
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center, lineHeight = 18.sp
        )

        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Info chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Info, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Text(
                            "Your current password is your phone number (${user.phone.ifBlank { "registered number" }}).\n" +
                            "Choose a strong new password to keep your account secure.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; errorMsg = "" },
                    label = { Text("New Password") },
                    singleLine = true,
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = formTextFieldColors(),
                    trailingIcon = {
                        IconButton(onClick = { showNew = !showNew }) {
                            Icon(if (showNew) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                    supportingText = { Text("Minimum 6 characters", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; errorMsg = "" },
                    label = { Text("Confirm New Password") },
                    singleLine = true,
                    visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = formTextFieldColors(),
                    trailingIcon = {
                        IconButton(onClick = { showConfirm = !showConfirm }) {
                            Icon(if (showConfirm) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg.isNotEmpty()) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp, textAlign = TextAlign.Center)
                }

                Button(
                    onClick = {
                        when {
                            newPassword.length < 6 -> errorMsg = "Password must be at least 6 characters"
                            newPassword != confirmPassword -> errorMsg = "Passwords do not match"
                            else -> {
                                isLoading = true
                                AuthManager.changeCustomerPassword(
                                    customerId  = user.customerId,
                                    newPassword = newPassword,
                                    onSuccess   = { isLoading = false; onPasswordChanged() },
                                    onFailure   = { msg -> isLoading = false; errorMsg = msg }
                                )
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GradientTop, contentColor = Color.White)
                ) {
                    if (isLoading) CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Set Password & Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = {
            AuthManager.logout()
            onLogout()
        }) {
            Text("Sign Out", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        Spacer(Modifier.height(48.dp))
    }
}

