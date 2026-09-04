package com.panakam.construction.ui.screens.projects

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.data.S3FileManager
import com.panakam.construction.database.DatabaseManager
import com.panakam.construction.ui.components.DateField
import com.panakam.construction.ui.components.todayAsIsoDate
import org.json.JSONArray
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerPaymentsScreen(
    customerId: String,
    customerName: String,
    projectId: String,
    unitId: String,
    onBack: () -> Unit
) {
    val currentUser = AuthManager.getCurrentUser()
    val canWrite    = currentUser?.role != UserRole.SITE_WORKER   // admin, pm, customer can add payments
    val isCustomer  = currentUser?.role == UserRole.CUSTOMER
    val canAudit    = currentUser?.role == UserRole.ADMIN || currentUser?.role == UserRole.AUDITOR
    val context     = androidx.compose.ui.platform.LocalContext.current
    var receiptError by remember { mutableStateOf("") }

    var payments      by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading     by remember { mutableStateOf(true) }
    var errorMsg      by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var paymentToDelete by remember { mutableStateOf<Map<String, Any>?>(null) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getPayments(customerId,
            onSuccess = { list -> payments = list; isLoading = false },
            onFailure = { e  -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }
    LaunchedEffect(Unit) { load() }

    // Summary
    val totalPaid = payments.sumOf { it["amount"].toString().toDoubleOrNull() ?: 0.0 }

    // Delete confirmation
    paymentToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { paymentToDelete = null },
            title = { Text("Delete Payment") },
            text  = { Text("Delete payment of ₹ ${"%,.2f".format(p["amount"].toString().toDoubleOrNull() ?: 0.0)}?") },
            confirmButton = {
                TextButton(onClick = {
                    val pid = p["paymentId"].toString()
                    paymentToDelete = null
                    DatabaseManager.deletePayment(pid, onSuccess = { load() }, onFailure = { e -> errorMsg = e.message ?: "Delete failed" })
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { paymentToDelete = null }) { Text("Cancel") } }
        )
    }

    if (showAddDialog) {
        AddPaymentDialog(
            customerId  = customerId,
            projectId   = projectId,
            unitId      = unitId,
            createdBy   = currentUser?.id ?: "",
            isCustomer  = isCustomer,
            onDismiss   = { showAddDialog = false },
            onSaved     = { showAddDialog = false; load() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Payments", fontWeight = FontWeight.Bold)
                        Text(customerName, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            if (canWrite) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, "Add Payment")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (receiptError.isNotBlank()) {
                LaunchedEffect(receiptError) { kotlinx.coroutines.delay(3000); receiptError = "" }
            }
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (receiptError.isNotBlank()) {
                        item {
                            Surface(modifier = Modifier.fillMaxWidth().padding(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                                Text(receiptError, modifier = Modifier.padding(10.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                            }
                        }
                    }
                    // Summary
                    item {
                        Surface(modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Row(modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Total Paid", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("₹ ${"%,.2f".format(totalPaid)}",
                                        fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Text("${payments.size} payment${if (payments.size != 1) "s" else ""}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }

                    if (payments.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize()) {
                                Column(modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Payments, null, modifier = Modifier.size(56.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Text("No payments recorded yet",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (canWrite) {
                                        Spacer(Modifier.height(12.dp))
                                        Button(onClick = { showAddDialog = true }) { Text("Add First Payment") }
                                    }
                                }
                            }
                        }
                    } else {
                    items(payments) { p ->
                        PaymentCard(
                            payment   = p,
                            canDelete = canWrite && !isCustomer,
                            canAudit  = canAudit,
                            auditorId = currentUser?.id ?: "",
                            onDelete  = { paymentToDelete = p },
                            onAudited = { load() },
                            onViewReceipt = { s3Key ->
                                if (s3Key.isNotBlank()) {
                                    receiptError = ""
                                    DatabaseManager.getReceiptDownloadUrl(s3Key,
                                        onSuccess = { url ->
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            } catch (e: Exception) {
                                                receiptError = "No app found to open this file"
                                            }
                                        },
                                        onFailure = { e -> receiptError = e.message ?: "Could not load receipt" }
                                    )
                                }
                            }
                        )
                    }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}

// ── Payment card ──────────────────────────────────────────────────────────────

@Composable
private fun PaymentCard(
    payment: Map<String, Any>,
    canDelete: Boolean,
    canAudit: Boolean,
    auditorId: String,
    onDelete: () -> Unit,
    onAudited: () -> Unit,
    onViewReceipt: (String) -> Unit
) {
    val amount       = payment["amount"].toString().toDoubleOrNull() ?: 0.0
    val date         = payment["paymentDate"].toString()
    val txnType      = payment["transactionType"].toString()
    val txnId        = payment["transactionId"].toString()
    val auditStatus  = payment["auditStatus"]?.toString() ?: "PENDING"
    val chequeNo     = payment["chequeNumber"]?.toString() ?: ""
    val chequeDate   = payment["chequeDate"]?.toString() ?: ""
    val rejectReason = payment["rejectReason"]?.toString() ?: ""
    val receiptKey   = payment["receiptS3Key"].toString()
    val isCash       = txnType.equals("cash", ignoreCase = true)
    var showAuditDialog by remember { mutableStateOf(false) }

    if (showAuditDialog) {
        AuditDialog(
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
    val typeColor = when (txnType.uppercase()) {
        "UPI"               -> Color(0xFF1B5E20)
        "NEFT"              -> Color(0xFF0D47A1)
        "RTGS"              -> Color(0xFF4A148C)
        "IMPS"              -> Color(0xFFE65100)
        "CHEQUE",
        "POST-DATED CHEQUE" -> Color(0xFF37474F)
        "DD"                -> Color(0xFF1A237E)
        "CASH"              -> Color(0xFF2E7D32)
        else                -> MaterialTheme.colorScheme.secondary
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("₹ ${"%,.2f".format(amount)}", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.weight(1f))
                if (txnType.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(4.dp), color = typeColor.copy(alpha = 0.12f)) {
                        Text(txnType, fontSize = 11.sp, color = typeColor, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Surface(shape = RoundedCornerShape(4.dp), color = auditBg) {
                    Text(auditLabel, fontSize = 10.sp, color = auditColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
            if (date.isNotBlank()) Text("📅 $date", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (txnId.isNotBlank()) Text(
                "${if (isCash) "Voucher/Receipt No:" else "Ref:"} $txnId",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chequeNo.isNotBlank()) Text(
                "Cheque No: $chequeNo${if (chequeDate.isNotBlank()) "  •  Date: $chequeDate" else ""}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val payerName = payment["payerName"].toString()
            val beneName  = payment["beneficiaryName"].toString()
            if (payerName.isNotBlank() || beneName.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (payerName.isNotBlank()) Text(
                        "${if (isCash) "Paid By:" else "From:"} $payerName",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (beneName.isNotBlank()) Text(
                        "${if (isCash) "Received By:" else "To:"} $beneName",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            if (auditStatus == "REJECTED" && rejectReason.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Reason: $rejectReason", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            val notes = payment["notes"].toString()
            if (notes.isNotBlank()) Text(notes, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically) {
                if (receiptKey.isNotBlank()) {
                    TextButton(onClick = { onViewReceipt(receiptKey) }) {
                        Icon(Icons.Filled.Receipt, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp)); Text("Receipt", fontSize = 11.sp)
                    }
                }
                if (canAudit && auditStatus != "AUDITED") {
                    TextButton(onClick = { showAuditDialog = true }) {
                        Icon(Icons.Filled.FactCheck, null, modifier = Modifier.size(15.dp), tint = Color(0xFF2E7D32))
                        Spacer(Modifier.width(4.dp)); Text("Audit", fontSize = 11.sp, color = Color(0xFF2E7D32))
                    }
                }
                if (canDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.DeleteOutline, "Delete", modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ── Audit dialog ──────────────────────────────────────────────────────────────

@Composable
private fun AuditDialog(
    payment: Map<String, Any>,
    auditorId: String,
    onDismiss: () -> Unit,
    onAudited: () -> Unit
) {
    var rejectReason by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf("AUDITED") }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
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
                HorizontalDivider()
                Text("Set audit status:", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AUDITED" to "✅ Approve", "REJECTED" to "❌ Reject").forEach { (status, label) ->
                        val selected = selectedStatus == status
                        FilterChip(
                            selected = selected,
                            onClick  = { selectedStatus = status },
                            label    = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (selectedStatus == "REJECTED") {
                    OutlinedTextField(
                        value = rejectReason, onValueChange = { rejectReason = it },
                        label = { Text("Reason for rejection *") }, maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
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

// ── Add payment dialog ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPaymentDialog(
    customerId: String, projectId: String, unitId: String,
    createdBy: String, isCustomer: Boolean,
    onDismiss: () -> Unit, onSaved: () -> Unit
) {
    val context = LocalContext.current

    var amount          by remember { mutableStateOf("") }
    var paymentDate     by remember { mutableStateOf(todayAsIsoDate()) }
    // Tracks whether the user has manually picked a date — so receipt extraction
    // can still safely fill in the real payment date while it's still the default.
    var paymentDateTouched by remember { mutableStateOf(false) }
    var transactionId   by remember { mutableStateOf("") }
    var transactionType by remember { mutableStateOf("") }
    var payerName       by remember { mutableStateOf("") }
    var payerBank       by remember { mutableStateOf("") }
    var payerAccount    by remember { mutableStateOf("") }
    var beneficiaryName by remember { mutableStateOf("") }
    var beneficiaryBank by remember { mutableStateOf("") }
    var beneficiaryAcc  by remember { mutableStateOf("") }
    var chequeNumber    by remember { mutableStateOf("") }
    var chequeDate      by remember { mutableStateOf("") }
    var notes           by remember { mutableStateOf("") }
    var receiptS3Key    by remember { mutableStateOf("") }
    var receiptFileId   by remember { mutableStateOf("") }
    var draftId         by remember { mutableStateOf("") }
    var extractWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var missingFields   by remember { mutableStateOf<List<String>>(emptyList()) }
    var extractConfidence by remember { mutableStateOf(0.0) }
    var needsReview     by remember { mutableStateOf(false) }

    var txTypeExpanded  by remember { mutableStateOf(false) }
    var uploadProgress  by remember { mutableStateOf(-1) }
    var isExtracting    by remember { mutableStateOf(false) }
    var saving          by remember { mutableStateOf(false) }
    var errorMsg        by remember { mutableStateOf("") }

    val txTypes = listOf("UPI", "NEFT", "RTGS", "IMPS", "Cheque", "Post-dated Cheque", "DD", "Cash", "Screenshot")
    val isCash  = transactionType.equals("cash", ignoreCase = true)

    // Receipt picker (photo or PDF)
    val receiptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploadProgress = 0
        val (fileName, mime) = S3FileManager.getFileInfo(context, uri)
        DatabaseManager.getReceiptUploadUrl(customerId, fileName, mime,
            onSuccess = { resp ->
                val uploadUrl = resp["uploadUrl"]?.toString() ?: return@getReceiptUploadUrl
                val s3Key     = resp["s3Key"]?.toString() ?: ""
                val fid       = resp["fileId"]?.toString() ?: ""
                S3FileManager.uploadToPresignedUrl(
                    context = context, uri = uri, uploadUrl = uploadUrl, contentType = mime,
                    onProgress = { pct -> uploadProgress = pct },
                    onSuccess  = {
                        uploadProgress = -1
                        receiptS3Key  = s3Key
                        receiptFileId = fid
                        // Auto-extract payment details from uploaded receipt (PDF/image).
                        isExtracting = true
                        DatabaseManager.extractPaymentDraft(
                            customerId = customerId,
                            projectId = projectId,
                            unitId = unitId,
                            s3Key = s3Key,
                            declaredType = "AUTO",
                            onSuccess = { parsed ->
                                isExtracting = false
                                draftId = parsed["draftId"]?.toString() ?: ""
                                needsReview = parsed["needsReview"]?.toString()?.equals("true", true) == true
                                extractConfidence = parsed["confidence"]?.toString()?.toDoubleOrNull() ?: 0.0
                                extractWarnings = runCatching {
                                    val arr = JSONArray(parsed["warnings"]?.toString() ?: "[]")
                                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                                }.getOrElse { emptyList() }
                                missingFields = runCatching {
                                    val arr = JSONArray(parsed["missingFields"]?.toString() ?: "[]")
                                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                                }.getOrElse { emptyList() }

                                if (parsed["amount"].toString().isNotBlank() && amount.isBlank())
                                    amount = parsed["amount"].toString()
                                if (parsed["paymentDate"].toString().isNotBlank() && !paymentDateTouched)
                                    paymentDate = parsed["paymentDate"].toString()
                                if (parsed["transactionId"].toString().isNotBlank() && transactionId.isBlank())
                                    transactionId = parsed["transactionId"].toString()
                                if (parsed["transactionType"].toString().isNotBlank() && transactionType.isBlank())
                                    transactionType = parsed["transactionType"].toString()
                                if (parsed["payerName"].toString().isNotBlank() && payerName.isBlank())
                                    payerName = parsed["payerName"].toString()
                                if (parsed["payerBank"].toString().isNotBlank() && payerBank.isBlank())
                                    payerBank = parsed["payerBank"].toString()
                                if (parsed["payerAccount"].toString().isNotBlank() && payerAccount.isBlank())
                                    payerAccount = parsed["payerAccount"].toString()
                                if (parsed["beneficiaryName"].toString().isNotBlank() && beneficiaryName.isBlank())
                                    beneficiaryName = parsed["beneficiaryName"].toString()
                                if (parsed["beneficiaryBank"].toString().isNotBlank() && beneficiaryBank.isBlank())
                                    beneficiaryBank = parsed["beneficiaryBank"].toString()
                                if (parsed["beneficiaryAccount"].toString().isNotBlank() && beneficiaryAcc.isBlank())
                                    beneficiaryAcc = parsed["beneficiaryAccount"].toString()
                                if (parsed["chequeNumber"].toString().isNotBlank() && chequeNumber.isBlank())
                                    chequeNumber = parsed["chequeNumber"].toString()
                                if (parsed["chequeDate"].toString().isNotBlank() && chequeDate.isBlank())
                                    chequeDate = parsed["chequeDate"].toString()
                            },
                            onFailure = { e -> isExtracting = false; errorMsg = e.message ?: "Extraction failed" }
                        )
                    },
                    onFailure  = { e -> uploadProgress = -1; errorMsg = "Upload failed: ${e.message}" }
                )
            },
            onFailure = { e -> errorMsg = e.message ?: "Could not get upload URL" }
        )
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text("Record Payment", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))

                Column(modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {

                if (errorMsg.isNotBlank())
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

                // Receipt upload
                OutlinedButton(
                    onClick = { receiptPicker.launch(arrayOf("application/pdf", "image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.UploadFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (receiptS3Key.isNotBlank()) "✅ Receipt uploaded" else "Upload Receipt / PDF")
                }
                if (uploadProgress >= 0) LinearProgressIndicator(progress = { uploadProgress / 100f },
                    modifier = Modifier.fillMaxWidth())
                if (isExtracting) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Extracting details from receipt…", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (draftId.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (needsReview) MaterialTheme.colorScheme.secondaryContainer else Color(0xFFE8F5E9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (needsReview) "Review required before submit"
                                else "Extraction complete",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Confidence: ${(extractConfidence * 100).toInt()}%", fontSize = 10.sp)
                            if (missingFields.isNotEmpty()) {
                                Text("Missing: ${missingFields.joinToString(", ")}", fontSize = 10.sp)
                            }
                            extractWarnings.forEach { msg ->
                                Text("- $msg", fontSize = 10.sp)
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("Payment Details", fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it },
                        label = { Text("Amount (₹) *") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    DateField(value = paymentDate, onValueChange = { paymentDate = it; paymentDateTouched = true },
                        label = "Date", modifier = Modifier.weight(1f))
                }

                ExposedDropdownMenuBox(expanded = txTypeExpanded, onExpandedChange = { txTypeExpanded = !txTypeExpanded }) {
                    OutlinedTextField(value = transactionType, onValueChange = {},
                        readOnly = true, label = { Text("Transaction Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(txTypeExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = txTypeExpanded, onDismissRequest = { txTypeExpanded = false }) {
                        txTypes.forEach { t ->
                            DropdownMenuItem(text = { Text(t) }, onClick = { transactionType = t; txTypeExpanded = false })
                        }
                    }
                }
                OutlinedTextField(value = transactionId, onValueChange = { transactionId = it },
                    label = { Text(if (isCash) "Cash Voucher / Receipt No." else "Transaction / UTR / Ref No.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())

                if (transactionType.equals("Cheque", true) || transactionType.equals("Post-dated Cheque", true) || transactionType.equals("DD", true)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = chequeNumber,
                            onValueChange = { chequeNumber = it },
                            label = { Text(if (transactionType.equals("DD", true)) "DD Number" else "Cheque Number") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        DateField(
                            value = chequeDate.ifBlank { todayAsIsoDate() },
                            onValueChange = { chequeDate = it },
                            label = if (transactionType.equals("DD", true)) "DD Date" else "Cheque Date",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider()
                Text(if (isCash) "Cash Details" else "Bank Details",
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(value = payerName, onValueChange = { payerName = it },
                    label = { Text(if (isCash) "Paid By (Name)" else "Payer Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())

                if (!isCash) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = payerBank, onValueChange = { payerBank = it },
                            label = { Text("Payer Bank") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = payerAccount, onValueChange = { payerAccount = it },
                            label = { Text("A/C No.") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }

                OutlinedTextField(value = beneficiaryName, onValueChange = { beneficiaryName = it },
                    label = { Text(if (isCash) "Received By (Name)" else "Beneficiary Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())

                if (!isCash) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = beneficiaryBank, onValueChange = { beneficiaryBank = it },
                            label = { Text("Beneficiary Bank") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = beneficiaryAcc, onValueChange = { beneficiaryAcc = it },
                            label = { Text("A/C No.") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }

                if (isCash) {
                    // Cash-specific info banner
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color(0xFFE8F5E9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "💵 Cash payment — upload a signed cash receipt / voucher as proof.",
                            fontSize = 11.sp, color = Color(0xFF2E7D32),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                OutlinedTextField(value = notes, onValueChange = { notes = it },
                    label = { Text("Notes") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { if (!saving) onDismiss() }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = amount.isNotBlank() && paymentDate.isNotBlank() && !saving,
                        onClick = {
                            saving = true
                            val txType = transactionType.ifBlank { "Unknown" }
                            val fields = mapOf(
                                "amount"             to amount.trim(),
                                "paymentDate"        to paymentDate.trim(),
                                "transactionId"      to transactionId.trim(),
                                "transactionType"    to txType,
                                "payerName"          to payerName.trim(),
                                "payerBank"          to payerBank.trim(),
                                "payerAccount"       to payerAccount.trim(),
                                "beneficiaryName"    to beneficiaryName.trim(),
                                "beneficiaryBank"    to beneficiaryBank.trim(),
                                "beneficiaryAccount" to beneficiaryAcc.trim(),
                                "chequeNumber"       to chequeNumber.trim(),
                                "chequeDate"         to chequeDate.trim(),
                                "notes"              to notes.trim()
                            )
                            DatabaseManager.confirmPayment(
                                customerId = customerId,
                                projectId = projectId,
                                unitId = unitId,
                                receiptS3Key = receiptS3Key,
                                receiptFileId = receiptFileId,
                                confirmedBy = createdBy,
                                declaredType = txType,
                                draftId = draftId,
                                fields = fields,
                                onSuccess = { saving = false; onSaved() },
                                onFailure = { e -> saving = false; errorMsg = e.message ?: "Save failed" }
                            )
                        }
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    }
}

