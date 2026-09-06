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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager
import java.text.SimpleDateFormat
import java.util.*

data class FinancialRecord(
    val recordId: String,
    val projectId: String,
    val type: String,        // "Income" | "Expense"
    val category: String,
    val amount: String,
    val description: String,
    val date: String
) {
    companion object {
        val TYPES      = listOf("Expense", "Income")
        val CATEGORIES = listOf("Labor", "Materials", "Equipment", "Subcontractor", "Permits", "Utilities", "Other")

        fun fromMap(map: Map<String, Any>) = FinancialRecord(
            recordId    = map["recordId"]?.toString()    ?: "",
            projectId   = map["projectId"]?.toString()   ?: "",
            type        = map["type"]?.toString()        ?: "Expense",
            category    = map["category"]?.toString()    ?: "Other",
            amount      = map["amount"]?.toString()      ?: "0",
            description = map["description"]?.toString() ?: "",
            date        = map["date"]?.toString()        ?: ""
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFinancialsScreen(
    projectId: String,
    projectName: String,
    onBack: () -> Unit
) {
    val user     = AuthManager.getCurrentUser()
    val canWrite = user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER

    var records       by remember { mutableStateOf<List<FinancialRecord>>(emptyList()) }
    var isLoading     by remember { mutableStateOf(true) }
    var errorMsg      by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var recordToDelete by remember { mutableStateOf<FinancialRecord?>(null) }

    fun loadRecords() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getFinancialData(projectId,
            onSuccess = { maps ->
                records   = maps.map { FinancialRecord.fromMap(it) }
                isLoading = false
            },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }

    LaunchedEffect(Unit) { loadRecords() }

    // Summaries
    val totalIncome  = records.filter { it.type == "Income" }
        .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    val totalExpense = records.filter { it.type == "Expense" }
        .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    val net = totalIncome - totalExpense
    // Auto-recorded unit sale revenue (net of any reverted sales and discounts) —
    // surfaced separately so it's clear how much of the income is from unit sales.
    val salesRevenue = records.filter { it.category == "Unit Sale" }
        .sumOf { it.amount.toDoubleOrNull() ?: 0.0 } -
        records.filter { it.category == "Unit Sale Reversal" }
            .sumOf { it.amount.toDoubleOrNull() ?: 0.0 } -
        records.filter { it.category == "Discount" }
            .sumOf { it.amount.toDoubleOrNull() ?: 0.0 } +
        records.filter { it.category == "Discount Reversal" }
            .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    // Total discounts currently granted (net of any reductions).
    val totalDiscounts = records.filter { it.category == "Discount" }
        .sumOf { it.amount.toDoubleOrNull() ?: 0.0 } -
        records.filter { it.category == "Discount Reversal" }
            .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }

    // ── Delete confirmation ───────────────────────────────────────────────────
    recordToDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text("Delete Record") },
            text  = { Text("Delete \"${rec.description.ifBlank { rec.type }}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    recordToDelete = null
                    DatabaseManager.deleteFinancialData(projectId, rec.recordId,
                        onSuccess = { loadRecords() },
                        onFailure = { e -> errorMsg = e.message ?: "Delete failed" }
                    )
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { recordToDelete = null }) { Text("Cancel") } }
        )
    }

    if (showAddDialog) {
        AddFinancialDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { data ->
                showAddDialog = false
                val recordId = UUID.randomUUID().toString()
                val payload  = data + mapOf("recordId" to recordId)
                DatabaseManager.addFinancialData(projectId, payload,
                    onSuccess = { loadRecords() },
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
                        Text("Financials", fontWeight = FontWeight.Bold)
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
                    IconButton(onClick = { loadRecords() }) {
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
                    Icon(Icons.Filled.Add, "Add Record")
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
                    Button(onClick = { loadRecords() }) { Text("Retry") }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Summary card
                    item {
                        FinancialSummaryCard(
                            totalIncome  = totalIncome,
                            totalExpense = totalExpense,
                            net          = net,
                            salesRevenue = salesRevenue,
                            totalDiscounts = totalDiscounts
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (records.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.Receipt, null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                Text("No financial records yet", fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (canWrite) {
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = { showAddDialog = true }) { Text("Add First Record") }
                                }
                            }
                        }
                    } else {
                        items(records) { rec ->
                            FinancialRecordCard(
                                record   = rec,
                                canWrite = canWrite,
                                onDelete = { recordToDelete = rec }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinancialSummaryCard(totalIncome: Double, totalExpense: Double, net: Double, salesRevenue: Double = 0.0, totalDiscounts: Double = 0.0) {
    val netColor = if (net >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryItem("Income", "₹%.2f".format(totalIncome), MaterialTheme.colorScheme.tertiary)
                VerticalDivider(modifier = Modifier.height(48.dp))
                SummaryItem("Expense", "₹%.2f".format(totalExpense), MaterialTheme.colorScheme.error)
                VerticalDivider(modifier = Modifier.height(48.dp))
                SummaryItem("Net", "₹%.2f".format(net), netColor)
            }
            if (salesRevenue > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Home, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        Text("Unit Sales Revenue", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("₹ ${"%,.2f".format(salesRevenue)}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (totalDiscounts > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Sell, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        Text("Discounts Given", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("− ₹ ${"%,.2f".format(totalDiscounts)}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RowScope.SummaryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun FinancialRecordCard(record: FinancialRecord, canWrite: Boolean, onDelete: () -> Unit) {
    val isIncome = record.type == "Income"
    val typeColor = if (isIncome) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val typeIcon  = if (isIncome) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown
    val isAutoRecorded = record.category == "Unit Sale" || record.category == "Unit Sale Reversal" ||
        record.category == "Discount" || record.category == "Discount Reversal"

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(typeIcon, null, modifier = Modifier.size(36.dp), tint = typeColor)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.category, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (isAutoRecorded) {
                            Surface(shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("Auto", fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Text("₹${record.amount}", fontWeight = FontWeight.Bold,
                        fontSize = 15.sp, color = typeColor)
                }
                if (record.description.isNotEmpty()) {
                    Text(record.description, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (record.date.isNotEmpty()) {
                    Text(record.date, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canWrite && !isAutoRecorded) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, "Delete",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFinancialDialog(
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any>) -> Unit
) {
    var type        by remember { mutableStateOf(FinancialRecord.TYPES.first()) }
    var category    by remember { mutableStateOf(FinancialRecord.CATEGORIES.first()) }
    var amount      by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date        by remember { mutableStateOf(
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
    ) }

    var typeExpanded     by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Financial Record") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Type dropdown
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded }
                ) {
                    OutlinedTextField(
                        value = type, onValueChange = {},
                        readOnly = true, label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        FinancialRecord.TYPES.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = { type = t; typeExpanded = false }
                            )
                        }
                    }
                }
                // Category dropdown
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = !categoryExpanded }
                ) {
                    OutlinedTextField(
                        value = category, onValueChange = {},
                        readOnly = true, label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        FinancialRecord.CATEGORIES.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = { category = c; categoryExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Amount (₹) *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description") }, maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = date, onValueChange = { date = it },
                    label = { Text("Date (dd/MM/yyyy)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (amount.isNotBlank()) {
                        onConfirm(mapOf(
                            "type"        to type,
                            "category"    to category,
                            "amount"      to amount.trim(),
                            "description" to description.trim(),
                            "date"        to date.trim()
                        ))
                    }
                },
                enabled = amount.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

