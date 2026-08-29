package com.panakam.construction.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.ui.theme.GradientBottom
import com.panakam.construction.ui.theme.GradientMiddle
import com.panakam.construction.ui.theme.GradientTop
import com.panakam.construction.ui.theme.formTextFieldColors

private enum class ResetStep { EMAIL, ANSWER, NEW_PASSWORD, DONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(onBack: () -> Unit) {

    var step        by remember { mutableStateOf(ResetStep.EMAIL) }
    var email       by remember { mutableStateOf("") }
    var question    by remember { mutableStateOf("") }
    var answer      by remember { mutableStateOf("") }
    var newPass     by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var showPass    by remember { mutableStateOf(false) }
    var errorMsg    by remember { mutableStateOf("") }
    var isLoading   by remember { mutableStateOf(false) }

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

        // Back button
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }

        Icon(Icons.Filled.LockReset, null,
            modifier = Modifier.size(64.dp), tint = Color.White)
        Spacer(Modifier.height(10.dp))
        Text("Reset Password", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
            color = Color.White)
        Text(
            text = when (step) {
                ResetStep.EMAIL       -> "Enter your email address"
                ResetStep.ANSWER      -> "Answer your security question"
                ResetStep.NEW_PASSWORD-> "Choose a new password"
                ResetStep.DONE        -> "Password reset successfully!"
            },
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f)
        )

        Spacer(Modifier.height(28.dp))

        // ── Step indicator ────────────────────────────────────────────────
        if (step != ResetStep.DONE) {
            StepIndicator(current = step.ordinal, total = 3)
            Spacer(Modifier.height(16.dp))
        }

        // ── Card ──────────────────────────────────────────────────────────
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
                when (step) {

                    // ── Step 1: enter email ───────────────────────────────
                    ResetStep.EMAIL -> {
                        Text("Find your account", fontWeight = FontWeight.Bold,
                            fontSize = 18.sp, color = GradientTop)
                        OutlinedTextField(
                            value = email, onValueChange = { email = it; errorMsg = "" },
                            label = { Text("Email address") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Email, null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = formTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (errorMsg.isNotEmpty()) ErrorText(errorMsg)
                        Button(
                            onClick = {
                                val q = AuthManager.getSecurityQuestion(email.trim())
                                if (q == null) {
                                    errorMsg = "No account found with this email"
                                } else {
                                    question = q
                                    errorMsg = ""
                                    step = ResetStep.ANSWER
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GradientTop, contentColor = Color.White)
                        ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
                    }

                    // ── Step 2: answer security question ─────────────────
                    ResetStep.ANSWER -> {
                        Text("Security Question", fontWeight = FontWeight.Bold,
                            fontSize = 18.sp, color = GradientTop)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.HelpOutline, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(question, fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                        OutlinedTextField(
                            value = answer, onValueChange = { answer = it; errorMsg = "" },
                            label = { Text("Your answer") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.QuestionAnswer, null) },
                            colors = formTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (errorMsg.isNotEmpty()) ErrorText(errorMsg)
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = ResetStep.EMAIL },
                                modifier = Modifier.weight(1f).height(48.dp)) {
                                Text("Back")
                            }
                            Button(
                                onClick = {
                                    // Verify the answer against a fake new-password
                                    // by re-using resetPassword with a temp value just to check
                                    val q = AuthManager.getSecurityQuestion(email.trim())
                                    if (q == null) { step = ResetStep.EMAIL; return@Button }
                                    // Just proceed to next step (answer verified on final submit)
                                    errorMsg = ""
                                    step = ResetStep.NEW_PASSWORD
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GradientTop, contentColor = Color.White)
                            ) { Text("Next", fontWeight = FontWeight.SemiBold) }
                        }
                    }

                    // ── Step 3: new password ──────────────────────────────
                    ResetStep.NEW_PASSWORD -> {
                        Text("New Password", fontWeight = FontWeight.Bold,
                            fontSize = 18.sp, color = GradientTop)
                        OutlinedTextField(
                            value = newPass, onValueChange = { newPass = it; errorMsg = "" },
                            label = { Text("New password") }, singleLine = true,
                            visualTransformation = if (showPass) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            colors = formTextFieldColors(),
                            trailingIcon = {
                                IconButton(onClick = { showPass = !showPass }) {
                                    Icon(if (showPass) Icons.Filled.Visibility
                                         else Icons.Filled.VisibilityOff, null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmPass, onValueChange = { confirmPass = it; errorMsg = "" },
                            label = { Text("Confirm new password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (errorMsg.isNotEmpty()) ErrorText(errorMsg)
                        Button(
                            onClick = {
                                if (newPass != confirmPass) {
                                    errorMsg = "Passwords do not match"; return@Button
                                }
                                isLoading = true
                                AuthManager.resetPassword(
                                    email     = email.trim(),
                                    secAnswer = answer.trim(),
                                    newPassword = newPass,
                                    onSuccess = { isLoading = false; step = ResetStep.DONE },
                                    onFailure = { msg -> isLoading = false; errorMsg = msg }
                                )
                            },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GradientTop, contentColor = Color.White)
                        ) {
                            if (isLoading) CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White, strokeWidth = 2.dp)
                            else Text("Reset Password", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // ── Done ──────────────────────────────────────────────
                    ResetStep.DONE -> {
                        Icon(Icons.Filled.CheckCircle, null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Password Reset!", fontWeight = FontWeight.Bold,
                            fontSize = 18.sp, color = GradientTop)
                        Text("You can now sign in with your new password.",
                            fontSize = 13.sp, textAlign = TextAlign.Center,
                            color = Color(0xFF42474E))
                        Button(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GradientTop, contentColor = Color.White)
                        ) { Text("Back to Sign In", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { idx ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (idx <= current) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(if (idx == current) 28.dp else 10.dp, 10.dp)
            ) {}
        }
    }
}

@Composable
private fun ErrorText(msg: String) {
    Text(msg, color = MaterialTheme.colorScheme.error,
        fontSize = 13.sp, textAlign = TextAlign.Center)
}

