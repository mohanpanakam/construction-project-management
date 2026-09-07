package com.panakam.construction.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager

/**
 * Customer/unit payment status report — scoped to a single project (opened from
 * that project's detail screen, same pattern as Collections/Suspense):
 * customer name, unit id, total cost, paid (audited/unaudited) and balance
 * (audited/unaudited) — with an "All" vs "By Sales Rep" filter for Admin/Auditor/
 * Project Manager, and an automatically self-scoped view for a Sales Rep (only
 * their own sold units in this project). Supports downloading a CSV of whatever's
 * currently shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    projectId: String? = null,
    projectName: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentUser = AuthManager.getCurrentUser()
    val isSalesRep = currentUser?.role == UserRole.SALES_REP
    val canPickRep  = currentUser?.role == UserRole.ADMIN ||
                       currentUser?.role == UserRole.AUDITOR ||
                       currentUser?.role == UserRole.PROJECT_MANAGER

    var rowsRaw     by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var selectedRep by remember { mutableStateOf("All") }
    var repMenuExpanded by remember { mutableStateOf(false) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getCustomerPaymentReport(
            projectId = projectId,
            soldBy    = if (isSalesRep) currentUser?.name else null,
            onSuccess = { list -> rowsRaw = list; isLoading = false },
            onFailure = { e -> errorMsg = e.message ?: "Failed to load report"; isLoading = false }
        )
    }
    LaunchedEffect(projectId) { load() }

    // Distinct sales reps present in the data (Admin/Auditor/PM only) for the filter.
    val repOptions = remember(rowsRaw) {
        listOf("All") + rowsRaw.mapNotNull { it["soldBy"]?.toString() }.distinct().sorted()
    }

    val rows = remember(rowsRaw, selectedRep, canPickRep) {
        if (!canPickRep || selectedRep == "All") rowsRaw
        else rowsRaw.filter { it["soldBy"]?.toString() == selectedRep }
    }

    val totals = remember(rows) {
        mapOf(
            "totalCost"        to rows.sumOf { it["totalCost"]?.toString()?.toDoubleOrNull() ?: 0.0 },
            "paidAudited"      to rows.sumOf { it["paidAudited"]?.toString()?.toDoubleOrNull() ?: 0.0 },
            "paidUnaudited"    to rows.sumOf { it["paidUnaudited"]?.toString()?.toDoubleOrNull() ?: 0.0 },
            "balanceAudited"   to rows.sumOf { it["balanceAudited"]?.toString()?.toDoubleOrNull() ?: 0.0 },
            "balanceUnaudited" to rows.sumOf { it["balanceUnaudited"]?.toString()?.toDoubleOrNull() ?: 0.0 }
        )
    }

    fun download() {
        isDownloading = true
        DatabaseManager.downloadCustomerPaymentReportCsv(
            context   = context,
            projectId = projectId,
            soldBy    = if (isSalesRep) currentUser?.name else selectedRep.takeIf { canPickRep && it != "All" },
            onSuccess = { _ ->
                isDownloading = false
                Toast.makeText(context, "Report saved to Downloads", Toast.LENGTH_LONG).show()
            },
            onFailure = { e ->
                isDownloading = false
                Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Payment Report", fontWeight = FontWeight.Bold)
                        Text(projectName ?: "All Projects", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { if (!isDownloading) download() }) {
                        if (isDownloading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Download, "Download CSV")
                    }
                },
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
                    // ── Sales rep filter (Admin/Auditor/PM only) ───────────────
                    if (canPickRep && repOptions.size > 1) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                            OutlinedButton(onClick = { repMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Badge, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (selectedRep == "All") "All Sales Reps" else selectedRep, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = repMenuExpanded, onDismissRequest = { repMenuExpanded = false }) {
                                repOptions.forEach { rep ->
                                    DropdownMenuItem(
                                        text = { Text(if (rep == "All") "All Sales Reps" else rep) },
                                        onClick = { selectedRep = rep; repMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }

                    // ── Summary ─────────────────────────────────────────────────
                    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReportSummaryCard("Total Cost", totals["totalCost"] ?: 0.0, Icons.Filled.CurrencyRupee, Modifier.weight(1f))
                                ReportSummaryCard("Units", rows.size.toDouble(), Icons.Filled.Home, Modifier.weight(1f), isCount = true)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReportSummaryCard("Paid (Audited)", totals["paidAudited"] ?: 0.0, Icons.Filled.CheckCircle, Modifier.weight(1f), highlight = true)
                                ReportSummaryCard("Paid (Unaudited)", totals["paidUnaudited"] ?: 0.0, Icons.Filled.PendingActions, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReportSummaryCard("Balance (Audited)", totals["balanceAudited"] ?: 0.0, Icons.Filled.AccountBalanceWallet, Modifier.weight(1f), highlightError = (totals["balanceAudited"] ?: 0.0) > 0)
                                ReportSummaryCard("Balance (Unaudited)", totals["balanceUnaudited"] ?: 0.0, Icons.Filled.AccountBalance, Modifier.weight(1f))
                            }
                        }
                    }

                    if (rows.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Inbox, null, modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("No records found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(rows, key = { it["unitId"]?.toString() ?: it.hashCode().toString() }) { row ->
                                ReportRowCard(row)
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSummaryCard(
    label: String, value: Double, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier, highlight: Boolean = false, highlightError: Boolean = false, isCount: Boolean = false
) {
    val bgColor   = when { highlight -> MaterialTheme.colorScheme.primary; highlightError -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surface }
    val textColor = when { highlight -> MaterialTheme.colorScheme.onPrimary; highlightError -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onSurface }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlight || highlightError) 4.dp else 1.dp)) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = textColor.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            Column {
                Text(if (isCount) value.toInt().toString() else "₹ ${formatReportAmount(value)}",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textColor)
                Text(label, fontSize = 10.sp, color = textColor.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun ReportRowCard(row: Map<String, Any>) {
    val customerName     = row["customerName"]?.toString()     ?: "—"
    val unitNumber        = row["unitNumber"]?.toString()        ?: "—"
    val soldBy            = row["soldBy"]?.toString()             ?: "Admin"
    val totalCost         = row["totalCost"]?.toString()?.toDoubleOrNull()         ?: 0.0
    val paidAudited       = row["paidAudited"]?.toString()?.toDoubleOrNull()       ?: 0.0
    val paidUnaudited     = row["paidUnaudited"]?.toString()?.toDoubleOrNull()     ?: 0.0
    val balanceAudited    = row["balanceAudited"]?.toString()?.toDoubleOrNull()    ?: 0.0
    val balanceUnaudited  = row["balanceUnaudited"]?.toString()?.toDoubleOrNull()  ?: 0.0

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(customerName, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Unit $unitNumber", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(soldBy, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Cost", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("₹ ${"%,.2f".format(totalCost)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Paid (Audited)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹ ${"%,.2f".format(paidAudited)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Paid (Unaudited)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹ ${"%,.2f".format(paidUnaudited)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Balance (Audited)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹ ${"%,.2f".format(balanceAudited)}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (balanceAudited > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Balance (Unaudited)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹ ${"%,.2f".format(balanceUnaudited)}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (balanceUnaudited > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32))
                }
            }
        }
    }
}

private fun formatReportAmount(amount: Double): String = when {
    amount >= 10_000_000 -> "${"%.2f".format(amount / 10_000_000)} Cr"
    amount >= 100_000    -> "${"%.2f".format(amount / 100_000)} L"
    else                 -> "%,.0f".format(amount)
}




