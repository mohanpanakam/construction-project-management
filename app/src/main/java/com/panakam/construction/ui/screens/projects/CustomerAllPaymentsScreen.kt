package com.panakam.construction.ui.screens.projects

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.database.DatabaseManager
import com.panakam.construction.util.sortedByPaymentDateAscending

/**
 * Consolidated "My Payments" screen for the customer portal.
 * Shows payment history across ALL units owned by this customer (identified
 * by phone number, falling back to single unit via customerId), with each
 * entry's date, amount and audit status (audited / unaudited / rejected),
 * plus running totals at the bottom (grand total, audited, pending audit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerAllPaymentsScreen(onBack: () -> Unit) {
    val user = AuthManager.getCurrentUser() ?: return

    var payments  by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") } // ALL | AUDITED | PENDING | REJECTED

    fun load() {
        isLoading = true; errorMsg = ""
        if (user.phone.isNotBlank()) {
            DatabaseManager.getPaymentsByPhone(user.phone,
                onSuccess = { list -> payments = list.sortedByPaymentDateAscending(); isLoading = false },
                onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
            )
        } else if (user.customerId.isNotBlank()) {
            DatabaseManager.getPayments(user.customerId,
                onSuccess = { list -> payments = list.sortedByPaymentDateAscending(); isLoading = false },
                onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
            )
        } else {
            isLoading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val filtered = when (statusFilter) {
        "ALL" -> payments
        else  -> payments.filter { (it["auditStatus"]?.toString() ?: "PENDING") == statusFilter }
    }

    val grandTotal   = payments.sumOf { it["amount"].toString().toDoubleOrNull() ?: 0.0 }
    val auditedTotal = payments.filter { (it["auditStatus"]?.toString() ?: "") == "AUDITED" }
        .sumOf { it["amount"].toString().toDoubleOrNull() ?: 0.0 }
    val pendingTotal = payments.filter { (it["auditStatus"]?.toString() ?: "PENDING") == "PENDING" }
        .sumOf { it["amount"].toString().toDoubleOrNull() ?: 0.0 }
    val rejectedTotal = payments.filter { (it["auditStatus"]?.toString() ?: "") == "REJECTED" }
        .sumOf { it["amount"].toString().toDoubleOrNull() ?: 0.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My Payments", fontWeight = FontWeight.Bold)
                        Text(user.name, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
                payments.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Payments, null, modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No payments recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // Filter chips
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ALL" to "All", "AUDITED" to "Audited", "PENDING" to "Unaudited", "REJECTED" to "Rejected")
                            .forEach { (value, label) ->
                                FilterChip(
                                    selected = statusFilter == value,
                                    onClick = { statusFilter = value },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                    }

                    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                        item {
                            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.medium) {
                                Text(
                                    "${filtered.size} payment${if (filtered.size != 1) "s" else ""}",
                                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No payments in this filter", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            items(filtered) { p -> AllPaymentCard(p) }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    // ── Totals footer ─────────────────────────────────────────
                    HorizontalDivider()
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Audited", fontSize = 11.sp, color = Color(0xFF2E7D32))
                                Text("₹ ${"%,.2f".format(auditedTotal)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Pending Audit", fontSize = 11.sp, color = Color(0xFFE65100))
                                Text("₹ ${"%,.2f".format(pendingTotal)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE65100))
                            }
                            if (rejectedTotal > 0) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Rejected", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                    Text("₹ ${"%,.2f".format(rejectedTotal)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Total Value", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("₹ ${"%,.2f".format(grandTotal)}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllPaymentCard(payment: Map<String, Any>) {
    val amount      = payment["amount"].toString().toDoubleOrNull() ?: 0.0
    val date        = payment["paymentDate"].toString()
    val txnType     = payment["transactionType"].toString()
    val auditStatus = payment["auditStatus"]?.toString() ?: "PENDING"
    val unitNumber  = payment["unitNumber"]?.toString() ?: ""
    val floor       = payment["floor"]?.toString() ?: ""
    val txnId       = payment["transactionId"].toString()
    val rejectReason = payment["rejectReason"]?.toString() ?: ""

    val (auditColor, auditBg, auditLabel) = when (auditStatus) {
        "AUDITED"  -> Triple(Color(0xFF2E7D32), Color(0xFFE8F5E9), "✅ Audited")
        "REJECTED" -> Triple(Color(0xFFC62828), Color(0xFFFFEBEE), "❌ Rejected")
        else       -> Triple(Color(0xFFE65100), Color(0xFFFFF3E0), "⏳ Unaudited")
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("₹ ${"%,.2f".format(amount)}", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.weight(1f))
                if (txnType.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)) {
                        Text(txnType, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Surface(shape = RoundedCornerShape(4.dp), color = auditBg) {
                    Text(auditLabel, fontSize = 10.sp, color = auditColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unitNumber.isNotBlank()) {
                    Icon(Icons.Filled.Apartment, null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("Unit $unitNumber${if (floor.isNotBlank()) " · Floor $floor" else ""}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            if (date.isNotBlank()) Text("📅 $date", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (txnId.isNotBlank()) Text("Ref: $txnId", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (auditStatus == "REJECTED" && rejectReason.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Reason: $rejectReason", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

