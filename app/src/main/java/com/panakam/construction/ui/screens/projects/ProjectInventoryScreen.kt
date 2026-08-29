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
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager
import java.util.UUID

data class InventoryItem(
    val itemId: String,
    val projectId: String,
    val name: String,
    val quantity: String,
    val availableQuantity: String,
    val unit: String,
    val category: String,
    val status: String,
    val notes: String
) {
    companion object {
        val CATEGORIES = listOf("Materials", "Equipment", "Tools", "Other")
        val UNITS      = listOf("pcs", "kg", "tons", "liters", "m", "m²", "m³", "bags", "boxes")
        val STATUSES   = listOf("Available", "In Use", "Damaged", "Depleted")

        fun fromMap(map: Map<String, Any>) = InventoryItem(
            itemId            = map["itemId"]?.toString()            ?: "",
            projectId         = map["projectId"]?.toString()         ?: "",
            name              = map["name"]?.toString()              ?: "",
            quantity          = map["quantity"]?.toString()          ?: "0",
            availableQuantity = map["availableQuantity"]?.toString() ?: "0",
            unit              = map["unit"]?.toString()              ?: "pcs",
            category          = map["category"]?.toString()          ?: "Materials",
            status            = map["status"]?.toString()            ?: "Available",
            notes             = map["notes"]?.toString()             ?: ""
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectInventoryScreen(
    projectId: String,
    projectName: String,
    onBack: () -> Unit
) {
    val user     = AuthManager.getCurrentUser()
    val canWrite = user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER

    var items       by remember { mutableStateOf<List<InventoryItem>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf("") }
    var showAddDialog  by remember { mutableStateOf(false) }
    var itemToDelete   by remember { mutableStateOf<InventoryItem?>(null) }
    var itemToUpdate   by remember { mutableStateOf<InventoryItem?>(null) }

    fun loadItems() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getInventory(projectId,
            onSuccess = { maps ->
                items     = maps.map { InventoryItem.fromMap(it) }
                isLoading = false
            },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }

    LaunchedEffect(Unit) { loadItems() }

    // ── Delete confirmation ───────────────────────────────────────────────────
    itemToDelete?.let { inv ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Item") },
            text  = { Text("Delete \"${inv.name}\" from inventory?") },
            confirmButton = {
                TextButton(onClick = {
                    itemToDelete = null
                    DatabaseManager.deleteInventory(projectId, inv.itemId,
                        onSuccess = { loadItems() },
                        onFailure = { e -> errorMsg = e.message ?: "Delete failed" }
                    )
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { itemToDelete = null }) { Text("Cancel") } }
        )
    }

    // ── Update status dialog ──────────────────────────────────────────────────
    itemToUpdate?.let { inv ->
        UpdateStatusDialog(
            item      = inv,
            onDismiss = { itemToUpdate = null },
            onConfirm = { data ->
                itemToUpdate = null
                DatabaseManager.updateInventoryItem(projectId, inv.itemId, data,
                    onSuccess = { loadItems() },
                    onFailure = { e -> errorMsg = e.message ?: "Update failed" }
                )
            }
        )
    }

    if (showAddDialog) {
        AddInventoryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { data ->
                showAddDialog = false
                val itemId = UUID.randomUUID().toString()
                val payload = data + mapOf("itemId" to itemId)
                DatabaseManager.addInventory(projectId, payload,
                    onSuccess = { loadItems() },
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
                        Text("Inventory", fontWeight = FontWeight.Bold)
                        Text(projectName, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadItems() }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            if (canWrite) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, "Add Item")
                }
            }
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
                    Button(onClick = { loadItems() }) { Text("Retry") }
                }
                items.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Inventory2, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("No inventory items yet", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (canWrite) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showAddDialog = true }) { Text("Add First Item") }
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Summary chip row
                    item {
                        val byCategory = items.groupBy { it.category }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            byCategory.forEach { (cat, catItems) ->
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("$cat: ${catItems.size}", fontSize = 12.sp) }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    items(items) { item ->
                        InventoryItemCard(
                            item     = item,
                            canWrite = canWrite,
                            onDelete = { itemToDelete = item },
                            onEdit   = { itemToUpdate = item }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryItemCard(item: InventoryItem, canWrite: Boolean, onDelete: () -> Unit, onEdit: () -> Unit) {
    val statusColor = when (item.status) {
        "Available" -> MaterialTheme.colorScheme.tertiary
        "In Use"    -> MaterialTheme.colorScheme.primary
        "Damaged"   -> MaterialTheme.colorScheme.error
        "Depleted"  -> MaterialTheme.colorScheme.onSurfaceVariant
        else        -> MaterialTheme.colorScheme.secondary
    }
    val categoryColor = when (item.category) {
        "Equipment" -> MaterialTheme.colorScheme.tertiary
        "Tools"     -> MaterialTheme.colorScheme.secondary
        else        -> MaterialTheme.colorScheme.primary
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Inventory2, null,
                    modifier = Modifier.size(36.dp),
                    tint = categoryColor)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(item.category, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Status badge
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(item.status, fontSize = 11.sp, color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            // Quantity row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                QuantityChip(
                    label = "Total",
                    value = "${item.quantity} ${item.unit}",
                    color = MaterialTheme.colorScheme.primary
                )
                QuantityChip(
                    label = "Available",
                    value = "${item.availableQuantity} ${item.unit}",
                    color = statusColor
                )
            }
            if (item.notes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(item.notes, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canWrite) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Update Status", fontSize = 12.sp)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.DeleteOutline, "Delete",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuantityChip(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.12f)
        ) {
            Text(value, fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateStatusDialog(
    item: InventoryItem,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any>) -> Unit
) {
    var availableQty by remember { mutableStateOf(item.availableQuantity) }
    var status       by remember { mutableStateOf(item.status) }
    var statusExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update: ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Total: ${item.quantity} ${item.unit}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = availableQty, onValueChange = { availableQty = it },
                    label = { Text("Available Quantity") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = statusExpanded,
                    onExpandedChange = { statusExpanded = !statusExpanded }
                ) {
                    OutlinedTextField(
                        value = status, onValueChange = {},
                        readOnly = true, label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = statusExpanded,
                        onDismissRequest = { statusExpanded = false }
                    ) {
                        InventoryItem.STATUSES.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s) },
                                onClick = { status = s; statusExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(mapOf(
                    "availableQuantity" to availableQty,
                    "status"            to status
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddInventoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any>) -> Unit
) {
    var name     by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var unit     by remember { mutableStateOf(InventoryItem.UNITS.first()) }
    var category by remember { mutableStateOf(InventoryItem.CATEGORIES.first()) }
    var status   by remember { mutableStateOf("Available") }
    var notes    by remember { mutableStateOf("") }

    var unitExpanded     by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var statusExpanded   by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Inventory Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Item Name *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity, onValueChange = { quantity = it },
                        label = { Text("Quantity *") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    ExposedDropdownMenuBox(
                        expanded = unitExpanded,
                        onExpandedChange = { unitExpanded = !unitExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = unit, onValueChange = {},
                            readOnly = true, label = { Text("Unit") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                            InventoryItem.UNITS.forEach { u ->
                                DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; unitExpanded = false })
                            }
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
                    OutlinedTextField(
                        value = category, onValueChange = {},
                        readOnly = true, label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        InventoryItem.CATEGORIES.forEach { c ->
                            DropdownMenuItem(text = { Text(c) }, onClick = { category = c; categoryExpanded = false })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                    OutlinedTextField(
                        value = status, onValueChange = {},
                        readOnly = true, label = { Text("Initial Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                        InventoryItem.STATUSES.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusExpanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") }, maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && quantity.isNotBlank()) {
                        onConfirm(mapOf(
                            "name"              to name.trim(),
                            "quantity"          to quantity.trim(),
                            "availableQuantity" to quantity.trim(), // starts fully available
                            "unit"              to unit,
                            "category"          to category,
                            "status"            to status,
                            "notes"             to notes.trim()
                        ))
                    }
                },
                enabled = name.isNotBlank() && quantity.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

