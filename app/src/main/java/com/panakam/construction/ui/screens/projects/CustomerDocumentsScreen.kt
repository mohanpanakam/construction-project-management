package com.panakam.construction.ui.screens.projects

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import com.panakam.construction.database.DatabaseManager

/**
 * Customer portal — "Documents" tab (SPEC item #4): lists agreement drafts sent by
 * the builder for this customer's unit(s), lets them view/download the PDF, and
 * accept the draft (with or without comments — see AgreementRoutes.kt for the
 * accept/revision-requested/sign status flow). Also surfaces KYC status with a
 * shortcut into [KycUploadScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDocumentsScreen(
    onBack: () -> Unit,
    onUpdateKyc: () -> Unit
) {
    val user = AuthManager.getCurrentUser()
    val customerId = user?.customerId ?: ""
    val context = LocalContext.current

    var agreements by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading  by remember { mutableStateOf(true) }
    var errorMsg   by remember { mutableStateOf("") }
    var kycStatus  by remember { mutableStateOf("NONE") }
    var respondingTo by remember { mutableStateOf<Map<String, Any>?>(null) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getAgreementsForCustomer(customerId,
            onSuccess = { list -> agreements = list; isLoading = false },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
        DatabaseManager.getKyc(customerId,
            onSuccess = { m -> kycStatus = m["kycStatus"]?.toString() ?: "NONE" },
            onFailure = { }
        )
    }
    LaunchedEffect(Unit) { load() }

    respondingTo?.let { agreement ->
        AcceptAgreementDialog(
            agreement = agreement,
            onDismiss = { respondingTo = null },
            onResponded = { respondingTo = null; load() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // KYC status banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (kycStatus == "VERIFIED") Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (kycStatus == "VERIFIED") Icons.Filled.VerifiedUser else Icons.Filled.Warning,
                        null,
                        tint = if (kycStatus == "VERIFIED") Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (kycStatus == "VERIFIED") "KYC Verified" else "KYC Pending — tap to upload Aadhaar",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = onUpdateKyc) { Text(if (kycStatus == "VERIFIED") "Update" else "Upload") }
                }
            }
            HorizontalDivider()

            Box(Modifier.fillMaxSize()) {
                when {
                    isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    errorMsg.isNotEmpty() -> Text(errorMsg, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp))
                    agreements.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.Description, null, modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("No agreement documents yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(agreements) { a ->
                            AgreementCard(
                                agreement = a,
                                onView = {
                                    DatabaseManager.getAgreementDownloadUrl(a["agreementId"].toString(),
                                        onSuccess = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                        onFailure = { e -> errorMsg = e.message ?: "Could not open document" }
                                    )
                                },
                                onRespond = { respondingTo = a }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgreementCard(
    agreement: Map<String, Any>,
    onView: () -> Unit,
    onRespond: () -> Unit
) {
    val status = agreement["status"]?.toString() ?: "SENT"
    val unitNumber = agreement["unitNumber"]?.toString() ?: ""
    val createdAt = agreement["createdAt"]?.toString()?.toLongOrNull()
    val (label, color, bg) = when (status) {
        "SENT"                -> Triple("⏳ Awaiting Your Review", Color(0xFFE65100), Color(0xFFFFF3E0))
        "ACCEPTED"             -> Triple("✅ Accepted — awaiting builder signature", Color(0xFF2E7D32), Color(0xFFE8F5E9))
        "REVISION_REQUESTED"   -> Triple("✏️ Revision Requested", Color(0xFFC62828), Color(0xFFFFEBEE))
        "SIGNED"               -> Triple("✅ Signed", Color(0xFF1565C0), Color(0xFFE3F2FD))
        else                   -> Triple(status, Color.Gray, Color(0xFFECEFF1))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Sale Agreement" + if (unitNumber.isNotBlank()) " — Unit $unitNumber" else "",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
            Surface(shape = RoundedCornerShape(6.dp), color = bg) {
                Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
            if (agreement["customerComments"]?.toString()?.isNotBlank() == true) {
                Text("Your comments: ${agreement["customerComments"]}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onView) {
                    Icon(Icons.Filled.Visibility, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("View", fontSize = 12.sp)
                }
                if (status == "SENT") {
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onRespond) { Text("Review & Respond", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun AcceptAgreementDialog(
    agreement: Map<String, Any>,
    onDismiss: () -> Unit,
    onResponded: () -> Unit
) {
    val user = AuthManager.getCurrentUser()
    var comments by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Review Agreement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("If you accept without comments, the builder will proceed to sign this agreement. " +
                    "If you have concerns, add comments below to request a revision instead.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = comments, onValueChange = { comments = it },
                    label = { Text("Comments (optional)") }, minLines = 3, maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg.isNotBlank()) Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    DatabaseManager.acceptAgreement(
                        agreementId = agreement["agreementId"].toString(),
                        comments = comments.trim(),
                        respondedBy = user?.id ?: "",
                        onSuccess = { saving = false; onResponded() },
                        onFailure = { e -> saving = false; errorMsg = e.message ?: "Failed" }
                    )
                }
            ) { Text(if (saving) "Submitting…" else if (comments.isBlank()) "Accept" else "Request Revision") }
        },
        dismissButton = { TextButton(onClick = { if (!saving) onDismiss() }) { Text("Cancel") } }
    )
}




