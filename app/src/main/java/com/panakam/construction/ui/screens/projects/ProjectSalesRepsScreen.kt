package com.panakam.construction.ui.screens.projects

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
import com.panakam.construction.database.DatabaseManager

/**
 * Admin-only screen: manage the list of "Sales Reps" (sales guys) attached to a project.
 * These names populate the "Sold By" dropdown when marking a unit as Sold, and are used
 * to group Collections by who sold each unit.
 *
 * Where possible, prefer LINKING an entry here to an actual Sales Rep staff login
 * account (via the dropdown in [AddSalesRepDialog]) rather than typing a free-text
 * name — this is what lets that Sales Rep's own "My Sales"/payment history be
 * reliably scoped to them later (see backend security/SalesRepScope.kt). An
 * unlinked free-text entry still works for attribution/reporting, but that Sales
 * Rep's own account (if they have one) won't see payments for it unless their
 * login name happens to match this entry's name exactly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSalesRepsScreen(
    projectId: String,
    projectName: String,
    onBack: () -> Unit
) {
    val user = AuthManager.getCurrentUser()

    var reps      by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var repToDelete   by remember { mutableStateOf<Map<String, Any>?>(null) }
    // Existing Sales Rep staff accounts, for the "link to account" picker below —
    // loaded once up front so the Add dialog can show it immediately.
    var salesRepUsers by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getSalesReps(projectId,
            onSuccess = { list -> reps = list; isLoading = false },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }
    LaunchedEffect(Unit) {
        load()
        AuthManager.getAllUsers(
            onSuccess = { list -> salesRepUsers = list.filter { it["role"] == "SALES_REP" } },
            onFailure = { /* non-fatal — Add dialog just falls back to free-text-only */ }
        )
    }

    repToDelete?.let { r ->
        com.panakam.construction.ui.components.PasswordConfirmDialog(
            title        = "Remove Sales Rep",
            message      = "Remove \"${r["name"]}\" from this project? Enter your password to confirm.",
            confirmLabel = "Confirm & Remove",
            onDismiss = { repToDelete = null },
            onConfirmed = {
                repToDelete = null
                DatabaseManager.removeSalesRep(projectId, r["salesRepId"]?.toString() ?: "",
                    onSuccess = { load() },
                    onFailure = { e -> errorMsg = e.message ?: "Remove failed" }
                )
            }
        )
    }

    if (showAddDialog) {
        AddSalesRepDialog(
            existingUsers = salesRepUsers,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, phone, userId ->
                showAddDialog = false
                DatabaseManager.addSalesRep(projectId, name, phone, user?.id ?: "", userId,
                    onSuccess = { load() },
                    onFailure = { e -> errorMsg = e.message ?: "Add failed" }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sales Team", fontWeight = FontWeight.Bold)
                        Text(projectName, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, "Add Sales Rep")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
                reps.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Groups, null, modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No sales reps yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap + to add one", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(reps, key = { it["salesRepId"]?.toString() ?: "" }) { rep ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Person, null, modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rep["name"]?.toString() ?: "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    val phone = rep["phone"]?.toString() ?: ""
                                    if (phone.isNotBlank())
                                        Text(phone, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { repToDelete = rep }) {
                                    Icon(Icons.Filled.DeleteOutline, "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSalesRepDialog(
    existingUsers: List<Map<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, userId: String) -> Unit
) {
    var name  by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    // When non-blank, this entry is LINKED to that staff account — name/phone
    // fields above are disabled (the account's own identity is authoritative)
    // and the backend will take name/phone straight from that account instead.
    var linkedUserId by remember { mutableStateOf("") }
    var pickerExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Sales Rep") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (existingUsers.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = pickerExpanded,
                        onExpandedChange = { pickerExpanded = !pickerExpanded }) {
                        OutlinedTextField(
                            value = if (linkedUserId.isBlank()) "Type a name manually below"
                                    else existingUsers.firstOrNull { it["userId"] == linkedUserId }?.get("name") ?: "",
                            onValueChange = {}, readOnly = true,
                            label = { Text("Link to existing Sales Rep account (recommended)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(pickerExpanded) },
                            supportingText = { Text(
                                "Linking lets that person's own login correctly see their own sales/payments later.",
                                fontSize = 11.sp
                            ) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Type a name manually") },
                                onClick = { linkedUserId = ""; pickerExpanded = false }
                            )
                            existingUsers.forEach { u ->
                                DropdownMenuItem(
                                    text = { Text("${u["name"]}${u["email"]?.let { if (it.isNotBlank()) " ($it)" else "" } ?: ""}") },
                                    onClick = {
                                        linkedUserId = u["userId"] ?: ""
                                        name = u["name"] ?: ""; phone = u["email"] ?: ""
                                        pickerExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Name *") }, singleLine = true, enabled = linkedUserId.isBlank(),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it },
                    label = { Text("Phone (optional)") }, singleLine = true, enabled = linkedUserId.isBlank(),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), phone.trim(), linkedUserId) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

