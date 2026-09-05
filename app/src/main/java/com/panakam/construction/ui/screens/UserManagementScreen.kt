package com.panakam.construction.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var userToLink   by remember { mutableStateOf<Map<String, String>?>(null) }
    var userToEditContact by remember { mutableStateOf<Map<String, String>?>(null) }
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

    // ── Link to customer/unit dialog ─────────────────────────────────────────
    userToLink?.let { u ->
        LinkCustomerDialog(
            user      = u,
            onDismiss = { userToLink = null },
            onConfirm = { customerId ->
                userToLink = null
                AuthManager.linkUserToCustomer(u["userId"] ?: "", customerId,
                    onSuccess = { load() },
                    onFailure = { msg -> errorMsg = msg }
                )
            }
        )
    }

    // ── Edit contact email dialog ─────────────────────────────────────────────
    userToEditContact?.let { u ->
        ContactEmailDialog(
            user      = u,
            onDismiss = { userToEditContact = null },
            onConfirm = { newEmail ->
                userToEditContact = null
                AuthManager.updateUserContactEmail(u["userId"] ?: "", newEmail,
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
                            onDelete      = { userToDelete = u },
                            onLink        = { userToLink   = u },
                            onEditContact = { userToEditContact = u }
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
    onDelete: () -> Unit,
    onLink: () -> Unit,
    onEditContact: () -> Unit
) {
    val roleColor = when (user["role"]) {
        "ADMIN"           -> MaterialTheme.colorScheme.error
        "PROJECT_MANAGER" -> MaterialTheme.colorScheme.primary
        else              -> MaterialTheme.colorScheme.secondary
    }
    val dateStr = user["createdAt"]?.toLongOrNull()?.let {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))
    } ?: ""
    val linkedUnit = user["linkedUnitNumber"]?.takeIf { it.isNotBlank() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (!user["contactEmail"].isNullOrBlank())
                        Text("✉ ${user["contactEmail"]}", fontSize = 11.sp,
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
                    Row {
                        IconButton(onClick = onEditContact, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.AlternateEmail, "Edit Contact Email",
                                modifier = Modifier.size(18.dp),
                                tint = if (!user["contactEmail"].isNullOrBlank()) Color(0xFF2E7D32)
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!isCurrentUser) {
                            IconButton(onClick = onLink, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Apartment, "Link to Unit/Customer",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (linkedUnit != null) Color(0xFF2E7D32)
                                           else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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
            if (linkedUnit != null) {
                Spacer(Modifier.height(6.dp))
                Surface(shape = MaterialTheme.shapes.small,
                    color = Color(0xFFE8F5E9)) {
                    Text("🏠 Also a customer — linked to Unit $linkedUnit",
                        fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

// ── Link staff account to an existing customer/unit ───────────────────────────

@Composable
private fun LinkCustomerDialog(
    user: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (customerId: String) -> Unit
) {
    var query    by remember { mutableStateOf("") }
    var results  by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var selected by remember { mutableStateOf<Map<String, String>?>(null) }
    val alreadyLinked = user["linkedCustomerId"]?.isNotBlank() == true

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300) // light debounce
        AuthManager.searchCustomers(query.trim(),
            onSuccess = { results = it },
            onFailure = { results = emptyList() }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link ${user["name"]} to a Unit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "If this staff member also personally bought a unit, link their " +
                    "account to that customer record — one login will then show both " +
                    "their staff dashboard and their own unit/payments.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it; selected = null },
                    label = { Text("Search name, phone or unit no.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    results.forEach { c ->
                        val isSelected = selected?.get("customerId") == c["customerId"]
                        Surface(
                            onClick = { selected = c },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("${c["name"]} · Unit ${c["unitNumber"]}",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(c["phone"] ?: "", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (alreadyLinked) {
                    Text("Currently linked to Unit ${user["linkedUnitNumber"]}. " +
                        "Selecting a new unit above will replace this, or use Unlink below.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { onConfirm(selected?.get("customerId") ?: "") }
            ) { Text("Link") }
        },
        dismissButton = {
            Row {
                if (alreadyLinked) {
                    TextButton(onClick = { onConfirm("") }) {
                        Text("Unlink", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ── Edit optional contact email (for future notifications only) ──────────────

@Composable
private fun ContactEmailDialog(
    user: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var email by remember { mutableStateOf(user["contactEmail"] ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contact Email: ${user["name"]}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Optional — used only for future notifications (e.g. payment alerts). " +
                    "This is never used for login; the phone number remains the login ID.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(email.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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

