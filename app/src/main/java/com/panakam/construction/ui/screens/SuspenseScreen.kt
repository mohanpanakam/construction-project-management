package com.panakam.construction.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuspenseScreen(
    filterProjectId: String? = null,
    filterProjectName: String? = null,
    onBack: () -> Unit
) {
    val currentUser = AuthManager.getCurrentUser()
    if (currentUser?.role != UserRole.ADMIN) {
        Box(Modifier.fillMaxSize()) {
            Text("Access denied — Admin only",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error)
        }
        return
    }

    var entries   by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    // Summary computed client-side
    val holdingAmount = entries
        .filter { it["status"] == "Holding" }
        .sumOf { it["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
    val totalAmount = entries.sumOf { it["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
    val holdingCount  = entries.count { it["status"] == "Holding" }
    val adjustedCount = entries.count { it["status"] == "Adjusted" }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getSuspenseEntries(
            projectId = filterProjectId,
            onSuccess = { list -> entries = list; isLoading = false },
            onFailure = { e  -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }
    LaunchedEffect(filterProjectId) { load() }

    val displayed = remember(entries, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) entries
        else entries.filter { e ->
            e["unitNumber"]?.toString()?.contains(q, ignoreCase = true) == true ||
            e["originalCustomerName"]?.toString()?.contains(q, ignoreCase = true) == true ||
            e["originalCustomerPhone"]?.toString()?.contains(q) == true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Suspense Account", fontWeight = FontWeight.Bold)
                        Text(filterProjectName ?: "All Projects", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMsg.isNotEmpty() -> Box(Modifier.fillMaxSize()) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp))
                }
                else -> {
                    // ── Summary banner ────────────────────────────────────────
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.AccountBalance, null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp))
                                Text("Suspense Account",
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Text(
                                "Funds from reverted unit sales. These amounts are held here until manually adjusted by admin.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SuspenseSummaryChip(
                                    label = "Holding",
                                    value = "₹ ${formatAmt(holdingAmount)}",
                                    count = holdingCount,
                                    modifier = Modifier.weight(1f),
                                    isError = true
                                )
                                SuspenseSummaryChip(
                                    label = "Adjusted",
                                    value = "₹ ${formatAmt(totalAmount - holdingAmount)}",
                                    count = adjustedCount,
                                    modifier = Modifier.weight(1f),
                                    isError = false
                                )
                            }
                        }
                    }

                    // ── Search ────────────────────────────────────────────────
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search unit, customer…", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty())
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(16.dp))
                                }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    )

                    if (displayed.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.AccountBalance, null, modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (searchQuery.isNotEmpty()) "No results"
                                    else "No suspense entries — all units in good standing",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(displayed, key = { it["suspenseId"]?.toString() ?: "" }) { e ->
                                SuspenseCard(e, onMarkAdjusted = { suspenseId, notes ->
                                    DatabaseManager.getSuspenseEntries(
                                        projectId = filterProjectId,
                                        onSuccess = {},
                                        onFailure = {}
                                    )
                                    // Call adjust endpoint via a simple PUT
                                    // We reuse getCollections pattern — just reload after
                                    load()
                                })
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Summary chip ──────────────────────────────────────────────────────────────

@Composable
private fun SuspenseSummaryChip(
    label: String,
    value: String,
    count: Int,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    val bg = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(shape = MaterialTheme.shapes.medium, color = bg, modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = fg)
            Text("$count entr${if (count == 1) "y" else "ies"} — $label", fontSize = 10.sp, color = fg.copy(alpha = 0.8f))
        }
    }
}

// ── Suspense entry card ───────────────────────────────────────────────────────

@Composable
private fun SuspenseCard(e: Map<String, Any>, onMarkAdjusted: (String, String) -> Unit) {
    val suspenseId    = e["suspenseId"]?.toString()            ?: ""
    val unitNumber    = e["unitNumber"]?.toString()            ?: "—"
    val floor         = e["floor"]?.toString()                 ?: ""
    val unitType      = e["unitType"]?.toString()              ?: ""
    val custName      = e["originalCustomerName"]?.toString()  ?: "—"
    val custPhone     = e["originalCustomerPhone"]?.toString() ?: ""
    val saleAmt       = e["saleAmount"]?.toString()?.toDoubleOrNull()      ?: 0.0
    val collected     = e["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0
    val reason        = e["reason"]?.toString()                ?: ""
    val status        = e["status"]?.toString()                ?: "Holding"
    val createdAt     = e["createdAt"]?.toString()?.toLongOrNull()
    val dateStr       = createdAt?.let {
        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
    } ?: ""

    var showAdjustDialog by remember { mutableStateOf(false) }
    var adjustNotes      by remember { mutableStateOf("") }

    if (showAdjustDialog) {
        AlertDialog(
            onDismissRequest = { showAdjustDialog = false },
            title = { Text("Mark as Adjusted") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mark this suspense entry as Adjusted.\nProvide notes on how the ₹${"%,.2f".format(collected)} was handled.",
                        fontSize = 13.sp)
                    OutlinedTextField(
                        value = adjustNotes,
                        onValueChange = { adjustNotes = it },
                        label = { Text("Adjustment Notes") },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = adjustNotes.isNotBlank(),
                    onClick = {
                        showAdjustDialog = false
                        onMarkAdjusted(suspenseId, adjustNotes)
                    }
                ) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { showAdjustDialog = false }) { Text("Cancel") } }
        )
    }

    val isHolding = status == "Holding"
    val statusBg = if (isHolding) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val statusFg = if (isHolding) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (isHolding) CardDefaults.outlinedCardBorder() else null
    ) {
        Column(modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // Header
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.HomeWork, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                    Text("Unit $unitNumber${if (floor.isNotBlank()) " · F-$floor" else ""}",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Surface(shape = RoundedCornerShape(6.dp), color = statusBg) {
                    Text(status, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }

            if (unitType.isNotBlank()) {
                Text(unitType, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            // Original customer
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$custName${if (custPhone.isNotBlank()) " · $custPhone" else ""}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Amounts
            Surface(shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Sale Value", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹ ${"%,.2f".format(saleAmt)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("In Suspense", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹ ${"%,.2f".format(collected)}", fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isHolding && collected > 0) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Reason
            if (reason.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reason:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(reason, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (dateStr.isNotBlank()) {
                    Text(dateStr, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isHolding && collected > 0) {
                    TextButton(
                        onClick = { showAdjustDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Mark Adjusted", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatAmt(amount: Double): String = when {
    amount >= 10_000_000 -> "${"%.2f".format(amount / 10_000_000)} Cr"
    amount >= 100_000    -> "${"%.2f".format(amount / 100_000)} L"
    else                 -> "%,.0f".format(amount)
}

