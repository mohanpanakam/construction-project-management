package com.panakam.construction.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(onBack: () -> Unit) {

    var users      by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var isLoading  by remember { mutableStateOf(true) }
    var errorMsg   by remember { mutableStateOf("") }
    var userToDelete by remember { mutableStateOf<Map<String, String>?>(null) }
    var userToEdit   by remember { mutableStateOf<Map<String, String>?>(null) }
    val currentUser  = AuthManager.getCurrentUser()

    fun load() {
        isLoading = true; errorMsg = ""
        AuthManager.getAllUsers(
            onSuccess = { users = it; isLoading = false },
            onFailure = { msg -> errorMsg = msg; isLoading = false }
        )
    }

    LaunchedEffect(Unit) { load() }

    // ── Delete confirmation ───────────────────────────────────────────────────
    userToDelete?.let { u ->
        AlertDialog(
            onDismissRequest = { userToDelete = null },
            title = { Text("Delete User") },
            text  = { Text("Delete \"${u["name"]}\" (${u["email"]})? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    userToDelete = null
                    AuthManager.deleteUser(u["userId"] ?: "",
                        onSuccess = { load() },
                        onFailure = { msg -> errorMsg = msg }
                    )
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { userToDelete = null }) { Text("Cancel") } }
        )
    }

    // ── Change role dialog ────────────────────────────────────────────────────
    userToEdit?.let { u ->
        ChangeRoleDialog(
            user      = u,
            onDismiss = { userToEdit = null },
            onConfirm = { newRole ->
                userToEdit = null
                AuthManager.updateUserRole(u["userId"] ?: "", newRole,
                    onSuccess = { load() },
                    onFailure = { msg -> errorMsg = msg }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Management", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { load() }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { load() }) { Text("Retry") }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        // Summary chips
                        val byRole = users.groupBy { it["role"] ?: "" }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SuggestionChip(onClick = {},
                                label = { Text("Total: ${users.size}", fontSize = 12.sp) })
                            byRole.forEach { (role, list) ->
                                SuggestionChip(onClick = {},
                                    label = { Text("${roleName(role)}: ${list.size}", fontSize = 12.sp) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    items(users) { u ->
                        UserCard(
                            user          = u,
                            isCurrentUser = u["email"] == currentUser?.email,
                            onEdit        = { userToEdit   = u },
                            onDelete      = { userToDelete = u }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserCard(
    user: Map<String, String>,
    isCurrentUser: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val roleColor = when (user["role"]) {
        "ADMIN"           -> MaterialTheme.colorScheme.error
        "PROJECT_MANAGER" -> MaterialTheme.colorScheme.primary
        else              -> MaterialTheme.colorScheme.secondary
    }
    val dateStr = user["createdAt"]?.toLongOrNull()?.let {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))
    } ?: ""

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AccountCircle, null,
                modifier = Modifier.size(44.dp),
                tint = if (isCurrentUser) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(user["name"] ?: "", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (isCurrentUser) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text("You", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(user["email"] ?: "", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dateStr.isNotEmpty())
                    Text("Joined $dateStr", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = roleColor.copy(alpha = 0.12f)
                ) {
                    Text(roleName(user["role"] ?: ""), fontSize = 11.sp, color = roleColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if (!isCurrentUser) {
                    Row {
                        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Edit, "Change Role",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.DeleteOutline, "Delete",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeRoleDialog(
    user: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedRole   by remember { mutableStateOf(user["role"] ?: "SITE_WORKER") }
    var roleExpanded   by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Role: ${user["name"]}") },
        text = {
            ExposedDropdownMenuBox(expanded = roleExpanded,
                onExpandedChange = { roleExpanded = !roleExpanded }) {
                OutlinedTextField(
                    value = roleName(selectedRole), onValueChange = {}, readOnly = true,
                    label = { Text("Role") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = roleExpanded,
                    onDismissRequest = { roleExpanded = false }) {
                    UserRole.entries.forEach { r ->
                        DropdownMenuItem(
                            text = { Text(r.displayName) },
                            onClick = { selectedRole = r.name; roleExpanded = false }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedRole) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun roleName(role: String) = when (role) {
    "ADMIN"           -> "Admin"
    "PROJECT_MANAGER" -> "Project Manager"
    "SITE_WORKER"     -> "Site Worker"
    else              -> role
}

