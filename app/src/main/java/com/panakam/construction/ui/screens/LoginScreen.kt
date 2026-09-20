package com.panakam.construction.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.panakam.construction.R
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.ui.theme.GradientBottom
import com.panakam.construction.ui.theme.GradientMiddle
import com.panakam.construction.ui.theme.GradientTop
import com.panakam.construction.ui.theme.formTextFieldColors

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onForgotPassword: (isCustomer: Boolean) -> Unit = {}
) {
    val context = LocalContext.current

    var phone     by remember { mutableStateOf("") }
    var password  by remember { mutableStateOf("") }
    var showPass  by remember { mutableStateOf(false) }
    var errorMsg  by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isCustomerLogin by remember { mutableStateOf(false) }

    // Biometric availability
    val biometricManager = remember { BiometricManager.from(context) }
    val lastEmail        = remember { AuthManager.getLastEmail() }
    val canBiometric     = remember {
        lastEmail != null &&
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun launchBiometric() {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    AuthManager.loginWithBiometric(
                        phone     = lastEmail!!,
                        onSuccess = { onLoginSuccess() },
                        onFailure = { msg -> errorMsg = msg }
                    )
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        errorMsg = errString.toString()
                    }
                }
                override fun onAuthenticationFailed() {
                    errorMsg = "Biometric authentication failed"
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sign in to Construction App")
            .setSubtitle("Use biometrics to sign in as ${lastEmail!!}")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientTop, GradientMiddle, GradientBottom)))
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(48.dp))

        // ── Hero ──────────────────────────────────────────────────────────
        Image(
            painter = painterResource(id = R.drawable.jagadabhi_logo),
            contentDescription = "Jagadhabi Constructions",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Spacer(Modifier.height(10.dp))
        Text("Jagadhabi Constructions", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
            color = Color.White, letterSpacing = 0.5.sp, textAlign = TextAlign.Center)
        Text("Building A Better Tomorrow", fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.8f), letterSpacing = 2.sp)

        Spacer(Modifier.height(32.dp))

        // ── Login card ────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Welcome Back", fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, color = GradientTop)
                Text("Sign in to your account", fontSize = 13.sp,
                    color = Color(0xFF42474E))

                // Staff / Customer toggle
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center) {
                    FilterChip(selected = !isCustomerLogin, onClick = { isCustomerLogin = false; errorMsg = "" },
                        label = { Text("Staff Login", fontSize = 12.sp) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = isCustomerLogin, onClick = { isCustomerLogin = true; errorMsg = "" },
                        label = { Text("Customer Portal", fontSize = 12.sp) })
                }

                // Phone / Email toggle (customer only)
                if (isCustomerLogin) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center) {
                        FilterChip(selected = true, onClick = {},
                            label = { Text("📱 Phone", fontSize = 11.sp) })
                    }
                }

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; errorMsg = "" },
                    label = { Text("Phone Number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = formTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )


                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = "" },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = formTextFieldColors(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Forgot password
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { onForgotPassword(isCustomerLogin) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Forgot Password?", fontSize = 13.sp, color = GradientTop)
                    }
                }

                if (errorMsg.isNotEmpty()) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp, textAlign = TextAlign.Center)
                    // The backend tells us explicitly when a phone number belongs to a
                    // Customer account but was searched under the Staff tab (e.g. a
                    // former staff member whose staff account was deleted but who also
                    // owns a unit) — offer a one-tap fix instead of leaving the user
                    // stuck on the wrong tab.
                    if (!isCustomerLogin && errorMsg.contains("Customer Portal", ignoreCase = true)) {
                        TextButton(onClick = { isCustomerLogin = true; errorMsg = "" }) {
                            Text("Switch to Customer Portal login", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Button(
                    onClick = {
                        isLoading = true
                        if (isCustomerLogin) {
                            AuthManager.loginAsCustomerByPhone(
                                phone    = phone.trim(),
                                password = password,
                                onSuccess = { isLoading = false; onLoginSuccess() },
                                onFailure = { msg -> isLoading = false; errorMsg = msg }
                            )
                        } else {
                            AuthManager.login(
                                phone    = phone.trim(),
                                password = password,
                                onSuccess = { isLoading = false; onLoginSuccess() },
                                onFailure = { msg -> isLoading = false; errorMsg = msg }
                            )
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
                    else Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                // Biometric button
                if (canBiometric) {
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = { launchBiometric() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Fingerprint, null,
                            modifier = Modifier.size(22.dp),
                            tint = GradientTop)
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Biometrics",
                            color = GradientTop, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}
