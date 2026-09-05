package com.panakam.construction.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.ui.theme.GradientBottom
import com.panakam.construction.ui.theme.GradientMiddle
import com.panakam.construction.ui.theme.GradientTop
import com.panakam.construction.ui.theme.formTextFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    var name         by remember { mutableStateOf("") }
    var phone        by remember { mutableStateOf("") }
    var contactEmail by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var confirmPass  by remember { mutableStateOf("") }
    var showPass     by remember { mutableStateOf(false) }
    // Security question
    var secQuestion  by remember { mutableStateOf(AuthManager.SECURITY_QUESTIONS.first()) }
    var secAnswer    by remember { mutableStateOf("") }
    var secExpanded  by remember { mutableStateOf(false) }
    var errorMsg     by remember { mutableStateOf("") }
    var isLoading    by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientTop, GradientMiddle, GradientBottom)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Filled.Construction, contentDescription = null,
                modifier = Modifier.size(52.dp), tint = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("Create Account", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                color = Color.White)
            Text("Join the Construction team", fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(value = name, onValueChange = { name = it; errorMsg = "" },
                        label = { Text("Full Name") }, singleLine = true,
                        colors = formTextFieldColors(),
                        modifier = Modifier.fillMaxWidth())

                    OutlinedTextField(value = phone, onValueChange = { phone = it; errorMsg = "" },
                        label = { Text("Phone Number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = formTextFieldColors(),
                        modifier = Modifier.fillMaxWidth())

                    OutlinedTextField(value = contactEmail, onValueChange = { contactEmail = it; errorMsg = "" },
                        label = { Text("Email (optional — for notifications)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = formTextFieldColors(),
                        modifier = Modifier.fillMaxWidth())

                    OutlinedTextField(
                        value = password, onValueChange = { password = it; errorMsg = "" },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        colors = formTextFieldColors(),
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(if (showPass) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth())

                    OutlinedTextField(
                        value = confirmPass, onValueChange = { confirmPass = it; errorMsg = "" },
                        label = { Text("Confirm Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = formTextFieldColors(),
                        modifier = Modifier.fillMaxWidth())

                    // Role dropdown removed — self-registration always joins as
                    // Site Worker (lowest-privilege staff account). Only an existing
                    // Admin can grant Project Manager / Auditor / Sales Rep / Admin
                    // access afterward, from Team (User Management).
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "You'll join as a Site Worker. An Admin can grant you additional " +
                            "access (Project Manager, Auditor, etc.) afterward from Team settings.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    HorizontalDivider()
                    Text("Security Question", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Used to verify your identity if you forget your password",
                        fontSize = 11.sp, color = Color(0xFF42474E))

                    // Security question dropdown
                    ExposedDropdownMenuBox(
                        expanded = secExpanded,
                        onExpandedChange = { secExpanded = !secExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = secQuestion, onValueChange = {}, readOnly = true,
                            label = { Text("Security Question") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = secExpanded) },
                            maxLines = 2,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = secExpanded,
                            onDismissRequest = { secExpanded = false }) {
                            for (q in AuthManager.SECURITY_QUESTIONS) {
                                DropdownMenuItem(
                                    text = { Text(text = q, fontSize = 13.sp) },
                                    onClick = { secQuestion = q; secExpanded = false }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = secAnswer, onValueChange = { secAnswer = it; errorMsg = "" },
                        label = { Text("Your Answer") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.QuestionAnswer, null) },
                        colors = formTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMsg.isNotEmpty()) {
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            if (password != confirmPass) { errorMsg = "Passwords do not match"; return@Button }
                            if (secAnswer.isBlank()) { errorMsg = "Please provide an answer to the security question"; return@Button }
                            isLoading = true
                            AuthManager.register(
                                name        = name.trim(),
                                phone       = phone.trim(),
                                password    = password,
                                contactEmail = contactEmail.trim(),
                                // Ignored server-side for anyone but the very first bootstrap
                                // account — real role assignment happens via Admin > Team.
                                role        = UserRole.SITE_WORKER,
                                secQuestion = secQuestion,
                                secAnswer   = secAnswer.trim(),
                                onSuccess   = { isLoading = false; onRegisterSuccess() },
                                onFailure   = { msg -> isLoading = false; errorMsg = msg }
                            )
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GradientTop, contentColor = Color.White)
                    ) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp),
                            color = Color.White, strokeWidth = 2.dp)
                        else Text("Register", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onNavigateToLogin) {
                Text("Already have an account? Sign In", color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
