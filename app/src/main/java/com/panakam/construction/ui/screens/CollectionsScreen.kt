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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    filterProjectId: String? = null,
    filterProjectName: String? = null,
    onBack: () -> Unit
) {
    val currentUser = AuthManager.getCurrentUser()
    val canView = currentUser?.role == UserRole.ADMIN ||
                  currentUser?.role == UserRole.PROJECT_MANAGER ||
                  currentUser?.role == UserRole.AUDITOR

    if (!canView) {
        Box(Modifier.fillMaxSize()) {
            Text("Access denied", modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error)
        }
        return
    }

    var collections by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var summary     by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var bySalesRep  by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var viewMode    by remember { mutableStateOf("Total") } // "Total" | "By Sales Rep"

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getCollections(
            projectId = filterProjectId,
            onSuccess = { list ->
                collections = list
                // Compute summary client-side
                val totalUnits  = list.size
                val totalBase   = list.sumOf { it["baseAmount"]?.toString()?.toDoubleOrNull()  ?: 0.0 }
                val totalGst    = list.sumOf { it["gstAmount"]?.toString()?.toDoubleOrNull()   ?: 0.0 }
                val totalAmount = list.sumOf { it["totalAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
                summary = mapOf(
                    "totalUnits"  to totalUnits.toString(),
                    "totalBase"   to totalBase.toString(),
                    "totalGst"    to totalGst.toString(),
                    "totalAmount" to totalAmount.toString()
                )
                isLoading = false
            },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
        DatabaseManager.getCollectionSummaryBySalesRep(
            projectId = filterProjectId,
            onSuccess = { list -> bySalesRep = list },
            onFailure = { }
        )
    }
    LaunchedEffect(filterProjectId) { load() }

    val displayed = remember(collections, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) collections
        else collections.filter { c ->
            c["unitNumber"]?.toString()?.lowercase()?.contains(q) == true ||
            c["customerName"]?.toString()?.lowercase()?.contains(q) == true ||
            c["customerPhone"]?.toString()?.contains(q) == true ||
            c["floor"]?.toString()?.contains(q) == true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Collections", fontWeight = FontWeight.Bold)
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
                    // ── Summary cards ─────────────────────────────────────────
                    val totalUnits   = summary["totalUnits"]?.toString()?.toIntOrNull()      ?: 0
                    val totalBase    = summary["totalBase"]?.toString()?.toDoubleOrNull()     ?: 0.0
                    val totalGst     = summary["totalGst"]?.toString()?.toDoubleOrNull()      ?: 0.0
                    val totalAmount  = summary["totalAmount"]?.toString()?.toDoubleOrNull()   ?: 0.0
                    val totalPaid    = summary["totalPaid"]?.toString()?.toDoubleOrNull()     ?: 0.0
                    val totalPending = summary["totalPending"]?.toString()?.toDoubleOrNull()  ?: 0.0

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CollectionSummaryCard(
                                    label = "Units Sold",
                                    value = totalUnits.toString(),
                                    icon  = Icons.Filled.Home,
                                    modifier = Modifier.weight(1f)
                                )
                                CollectionSummaryCard(
                                    label = "Sale Value",
                                    value = "₹ ${formatAmount(totalAmount)}",
                                    icon  = Icons.Filled.CurrencyRupee,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CollectionSummaryCard(
                                    label = "Collected",
                                    value = "₹ ${formatAmount(totalPaid)}",
                                    icon  = Icons.Filled.CheckCircle,
                                    modifier = Modifier.weight(1f),
                                    highlight = true
                                )
                                CollectionSummaryCard(
                                    label = "Balance Due",
                                    value = "₹ ${formatAmount(totalPending)}",
                                    icon  = Icons.Filled.PendingActions,
                                    modifier = Modifier.weight(1f),
                                    highlightError = totalPending > 0
                                )
                            }
                            // Overall collection progress bar
                            if (totalAmount > 0) {
                                val progress = (totalPaid / totalAmount).coerceIn(0.0, 1.0).toFloat()
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Collection Progress", fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("${"%.1f".format(progress * 100)}%", fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        strokeCap = StrokeCap.Round,
                                        color = if (progress >= 1f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }
                    }

                    // ── View mode toggle: Total vs By Sales Rep ────────────────
                    if (bySalesRep.isNotEmpty()) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            listOf("Total", "By Sales Rep").forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = viewMode == label,
                                    onClick  = { viewMode = label },
                                    shape    = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                                ) { Text(label, fontSize = 12.sp) }
                            }
                        }
                    }

                    if (viewMode == "By Sales Rep") {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(bySalesRep, key = { it["soldBy"]?.toString() ?: "" }) { rep ->
                                SalesRepSummaryCard(rep)
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                        return@Column
                    }

                    // ── Search ────────────────────────────────────────────────
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search unit, customer, phone…", fontSize = 12.sp) },
                        leadingIcon  = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
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
                                Icon(Icons.Filled.Inbox, null, modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (searchQuery.isNotEmpty()) "No results for \"$searchQuery\""
                                    else "No collection records yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(displayed, key = { it["collectionId"]?.toString() ?: "" }) { c ->
                                CollectionCard(c)
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Summary card ──────────────────────────────────────────────────────────────

@Composable
private fun CollectionSummaryCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    highlightError: Boolean = false
) {
    val bgColor   = when {
        highlight      -> MaterialTheme.colorScheme.primary
        highlightError -> MaterialTheme.colorScheme.errorContainer
        else           -> MaterialTheme.colorScheme.surface
    }
    val textColor = when {
        highlight      -> MaterialTheme.colorScheme.onPrimary
        highlightError -> MaterialTheme.colorScheme.onErrorContainer
        else           -> MaterialTheme.colorScheme.onSurface
    }
    val subColor  = textColor.copy(alpha = 0.75f)

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlight || highlightError) 4.dp else 1.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = textColor.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            Column {
                Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor)
                Text(label, fontSize = 10.sp, color = subColor)
            }
        }
    }
}

// ── Collection record card ────────────────────────────────────────────────────

@Composable
private fun CollectionCard(c: Map<String, Any>) {
    val unitNumber     = c["unitNumber"]?.toString()      ?: "—"
    val floor          = c["floor"]?.toString()           ?: ""
    val unitType       = c["unitType"]?.toString()        ?: ""
    val sba            = c["sba"]?.toString()?.toDoubleOrNull()?.let { if (it > 0) "${it.toInt()} sqft" else "" } ?: ""
    val custName       = c["customerName"]?.toString()    ?: "—"
    val custPhone      = c["customerPhone"]?.toString()   ?: ""
    val perSft         = c["perSftPrice"]?.toString()?.toDoubleOrNull()    ?: 0.0
    val gstPct         = c["gstPercentage"]?.toString()?.toDoubleOrNull()  ?: 0.0
    val baseAmt        = c["baseAmount"]?.toString()?.toDoubleOrNull()     ?: 0.0
    val gstAmt         = c["gstAmount"]?.toString()?.toDoubleOrNull()      ?: 0.0
    val totalAmt       = c["totalAmount"]?.toString()?.toDoubleOrNull()    ?: 0.0
    val paidAmt        = c["paidAmount"]?.toString()?.toDoubleOrNull()     ?: 0.0
    val pendingAmt     = c["pendingAmount"]?.toString()?.toDoubleOrNull()  ?: 0.0
    val paymentStatus  = c["paymentStatus"]?.toString()                    ?: "Unpaid"
    val lastPayDate    = c["lastPaymentDate"]?.toString()                  ?: ""
    val saleDate       = c["saleDate"]?.toString()                         ?: ""

    val progress = if (totalAmt > 0) (paidAmt / totalAmt).coerceIn(0.0, 1.0).toFloat() else 0f

    val (statusBg, statusFg) = when (paymentStatus) {
        "Fully Paid" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        "Partial"    -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        else         -> Color(0xFFFFEBEE) to Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // Header: unit badge + payment status + sale date
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFFFEBEE)) {
                        Text("Unit $unitNumber", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    if (floor.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("F-$floor", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(6.dp), color = statusBg) {
                    Text(paymentStatus, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = statusFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }

            if (unitType.isNotBlank() || sba.isNotBlank()) {
                Text(listOf(unitType, sba).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            // Customer
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text(custName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                if (saleDate.isNotBlank()) {
                    Text(saleDate, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (custPhone.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Phone, null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(custPhone, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider()

            // Pricing breakdown
            Surface(shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Rate/SFT", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹ ${"%,.0f".format(perSft)}", fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Base Amount", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹ ${"%,.2f".format(baseAmt)}", fontSize = 11.sp)
                    }
                    if (gstPct > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("GST (${"%.1f".format(gstPct)}%)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹ ${"%,.2f".format(gstAmt)}", fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sale Value", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("₹ ${"%,.2f".format(totalAmt)}", fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Payment reconciliation section
            Surface(shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("💳 Payment Status", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Collected", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            Text("₹ ${"%,.2f".format(paidAmt)}", fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Balance Due", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            Text("₹ ${"%,.2f".format(pendingAmt)}", fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pendingAmt > 0) MaterialTheme.colorScheme.error
                                        else Color(0xFF2E7D32))
                        }
                    }
                    // Progress bar
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            strokeCap = StrokeCap.Round,
                            color = if (progress >= 1f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                        if (lastPayDate.isNotBlank()) {
                            Text("Last payment: $lastPayDate", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

private fun formatAmount(amount: Double): String {
    return when {
        amount >= 10_000_000 -> "${"%.2f".format(amount / 10_000_000)} Cr"
        amount >= 100_000    -> "${"%.2f".format(amount / 100_000)} L"
        else                 -> "%,.0f".format(amount)
    }
}

// ── Per sales rep summary card (Admin: total vs per sales guy) ───────────────

@Composable
private fun SalesRepSummaryCard(rep: Map<String, Any>) {
    val soldBy       = rep["soldBy"]?.toString() ?: "Admin"
    val totalUnits   = rep["totalUnits"]?.toString()?.toIntOrNull() ?: 0
    val totalAmount  = rep["totalAmount"]?.toString()?.toDoubleOrNull() ?: 0.0
    val totalPaid    = rep["totalPaid"]?.toString()?.toDoubleOrNull() ?: 0.0
    val totalPending = rep["totalPending"]?.toString()?.toDoubleOrNull() ?: 0.0
    val progress     = if (totalAmount > 0) (totalPaid / totalAmount).coerceIn(0.0, 1.0).toFloat() else 0f
    val isAdmin      = soldBy.equals("Admin", ignoreCase = true)

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (isAdmin) Icons.Filled.AdminPanelSettings else Icons.Filled.Badge, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(soldBy, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("$totalUnits unit${if (totalUnits != 1) "s" else ""}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Sale Value", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹ ${formatAmount(totalAmount)}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Collected", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹ ${formatAmount(totalPaid)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Balance Due", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹ ${formatAmount(totalPending)}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (totalPending > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32))
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round,
                color = if (progress >= 1f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

