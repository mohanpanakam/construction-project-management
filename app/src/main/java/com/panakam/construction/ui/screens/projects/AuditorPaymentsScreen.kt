package com.panakam.construction.ui.screens.projects

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.database.DatabaseManager


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditorPaymentsScreen(onBack: () -> Unit) {
    val currentUser = AuthManager.getCurrentUser()
    val auditorId   = currentUser?.id ?: ""

    var payments     by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var errorMsg     by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("PENDING") }  // default: show pending

    fun load() {
        isLoading = true; errorMsg = ""
        val filter = if (statusFilter == "ALL") null else statusFilter
        DatabaseManager.getAllPayments(
            statusFilter = filter,
            onSuccess    = { list -> payments = list; isLoading = false },
            onFailure    = { e   -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }
    LaunchedEffect(statusFilter) { load() }

    // Counts per status (from full list when ALL is selected)
    val pendingCount  = payments.count { it["auditStatus"] == "PENDING" }
    val auditedCount  = payments.count { it["auditStatus"] == "AUDITED" }
    val rejectedCount = payments.count { it["auditStatus"] == "REJECTED" }
    val totalAmount   = payments.sumOf { it["amount"].toString().toDoubleOrNull() ?: 0.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Payments Audit", fontWeight = FontWeight.Bold)
                        Text("${payments.size} records", fontSize = 11.sp,
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

            // ── Summary strip ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuditSummaryBadge("⏳ $pendingCount",  Color(0xFFE65100), Color(0xFFFFF3E0))
                AuditSummaryBadge("✅ $auditedCount",  Color(0xFF2E7D32), Color(0xFFE8F5E9))
                AuditSummaryBadge("❌ $rejectedCount", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                Spacer(Modifier.weight(1f))
                Text("₹ ${"%,.0f".format(totalAmount)}", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Status filter chips ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("PENDING" to "⏳ Pending", "AUDITED" to "✅ Audited",
                       "REJECTED" to "❌ Rejected", "ALL" to "All").forEach { (status, label) ->
                    FilterChip(
                        selected = statusFilter == status,
                        onClick  = { if (statusFilter != status) statusFilter = status },
                        label    = { Text(label, fontSize = 12.sp) },
                        leadingIcon = if (statusFilter == status) {{
                            Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
                        }} else null
                    )
                }
            }
            HorizontalDivider()

            // ── Content ────────────────────────────────────────────────────
            when {
                isLoading -> Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMsg.isNotEmpty() -> Box(Modifier.fillMaxSize()) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp))
                }
                payments.isEmpty() -> Box(Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FactCheck, null, modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (statusFilter) {
                                "PENDING"  -> "No pending payments to audit 🎉"
                                "AUDITED"  -> "No audited payments yet"
                                "REJECTED" -> "No rejected payments"
                                else       -> "No payments found"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(payments) { p ->
                        AuditPaymentCard(payment = p, auditorId = auditorId, onAudited = { load() })
                    }
                }
            }
        }
    }
}

// ── Single payment card for auditor view ──────────────────────────────────────

@Composable
private fun AuditPaymentCard(
    payment: Map<String, Any>,
    auditorId: String,
    onAudited: () -> Unit
) {
    val context      = LocalContext.current
    val amount       = payment["amount"].toString().toDoubleOrNull() ?: 0.0
    val auditStatus  = payment["auditStatus"]?.toString() ?: "PENDING"
    val txnType      = payment["transactionType"].toString()
    val txnId        = payment["transactionId"].toString()
    val date         = payment["paymentDate"].toString()
    val customerName = payment["customerName"]?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown customer"
    val unitNumber   = payment["unitNumber"]?.toString()?.takeIf { it.isNotBlank() }
    val floor        = payment["floor"]?.toString()?.takeIf { it.isNotBlank() }
    val sba          = payment["sba"]?.toString()?.toDoubleOrNull()?.takeIf { it > 0 }
    val unitLabel    = listOfNotNull(
        unitNumber?.let { "Unit $it" },
        floor?.let { "Floor $it" },
        sba?.let { "${it.toInt()} sqft" }
    ).joinToString(" · ").ifBlank { "Unit details unavailable" }
    val chequeNo     = payment["chequeNumber"]?.toString() ?: ""
    val chequeDate   = payment["chequeDate"]?.toString() ?: ""
    val rejectReason = payment["rejectReason"]?.toString() ?: ""
    val payerName    = payment["payerName"]?.toString() ?: ""
    val beneName     = payment["beneficiaryName"]?.toString() ?: ""
    val receiptKey   = payment["receiptS3Key"]?.toString() ?: ""
    val isCash       = txnType.equals("cash", ignoreCase = true)
    var showAuditDialog by remember { mutableStateOf(false) }
    var receiptError by remember { mutableStateOf("") }


    if (showAuditDialog) {
        AuditDialogGlobal(
            payment   = payment,
            auditorId = auditorId,
            onDismiss = { showAuditDialog = false },
            onAudited = { showAuditDialog = false; onAudited() }
        )
    }

    val (auditColor, auditBg, auditLabel) = when (auditStatus) {
        "AUDITED"  -> Triple(Color(0xFF2E7D32), Color(0xFFE8F5E9), "✅ Audited")
        "REJECTED" -> Triple(
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer,
            "❌ Rejected"
        )
        else -> Triple(Color(0xFFE65100), Color(0xFFFFF3E0), "⏳ Pending")
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("₹ ${"%,.2f".format(amount)}", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    modifier = Modifier.weight(1f))
                if (txnType.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(txnType, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Surface(shape = RoundedCornerShape(4.dp), color = auditBg) {
                    Text(auditLabel, fontSize = 10.sp, color = auditColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (date.isNotBlank()) Text("📅 $date  |  $customerName", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("🏠 $unitLabel", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary)
            if (txnId.isNotBlank()) Text(
                "${if (isCash) "Voucher/Receipt No:" else "Ref:"} $txnId",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chequeNo.isNotBlank()) Text("Cheque: $chequeNo${if (chequeDate.isNotBlank()) " (Date: $chequeDate)" else ""}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isCash && (payerName.isNotBlank() || beneName.isNotBlank())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (payerName.isNotBlank()) Text("Paid By: $payerName", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (beneName.isNotBlank()) Text("Received By: $beneName", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            if (auditStatus == "REJECTED" && rejectReason.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Reason: $rejectReason", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            if (receiptError.isNotBlank()) {
                Text(receiptError, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            // Action row — view receipt + audit button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (receiptKey.isNotBlank()) {
                    TextButton(onClick = {
                        receiptError = ""
                        DatabaseManager.getReceiptDownloadUrl(receiptKey,
                            onSuccess = { url ->
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: Exception) {
                                    receiptError = "No app found to open this file"
                                }
                            },
                            onFailure = { e -> receiptError = e.message ?: "Could not load receipt" }
                        )
                    }) {
                        Icon(Icons.Filled.Receipt, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View Receipt", fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                }
                // Audit button — only shown for non-audited payments
                if (auditStatus != "AUDITED") {
                    Button(
                        onClick = { showAuditDialog = true },
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Icon(Icons.Filled.FactCheck, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Audit Now", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── Summary badge ─────────────────────────────────────────────────────────────

@Composable
private fun AuditSummaryBadge(label: String, textColor: Color, bgColor: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = bgColor) {
        Text(label, fontSize = 11.sp, color = textColor, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

// ── Standalone audit dialog (for auditor screen) ──────────────────────────────

@Composable
private fun AuditDialogGlobal(
    payment: Map<String, Any>,
    auditorId: String,
    onDismiss: () -> Unit,
    onAudited: () -> Unit
) {
    var rejectReason   by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf("AUDITED") }
    var saving         by remember { mutableStateOf(false) }
    var errorMsg       by remember { mutableStateOf("") }
    val amount = payment["amount"].toString().toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Audit Payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Amount: ₹ ${"%,.2f".format(amount)}", fontWeight = FontWeight.SemiBold)
                payment["transactionId"].toString().takeIf { it.isNotBlank() }?.let {
                    Text("Ref: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                payment["transactionType"].toString().takeIf { it.isNotBlank() }?.let {
                    Text("Type: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AUDITED" to "✅ Approve", "REJECTED" to "❌ Reject").forEach { (status, label) ->
                        FilterChip(
                            selected = selectedStatus == status,
                            onClick  = { selectedStatus = status },
                            label    = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (selectedStatus == "REJECTED") {
                    OutlinedTextField(
                        value = rejectReason, onValueChange = { rejectReason = it },
                        label = { Text("Reason *") }, maxLines = 3, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (errorMsg.isNotBlank()) Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && (selectedStatus == "AUDITED" || rejectReason.isNotBlank()),
                onClick = {
                    saving = true
                    DatabaseManager.auditPayment(
                        paymentId    = payment["paymentId"].toString(),
                        auditStatus  = selectedStatus,
                        auditedBy    = auditorId,
                        rejectReason = rejectReason,
                        onSuccess    = { saving = false; onAudited() },
                        onFailure    = { e -> saving = false; errorMsg = e.message ?: "Failed" }
                    )
                }
            ) { Text(if (saving) "Saving…" else "Confirm") }
        },
        dismissButton = { TextButton(onClick = { if (!saving) onDismiss() }) { Text("Cancel") } }
    )
}

