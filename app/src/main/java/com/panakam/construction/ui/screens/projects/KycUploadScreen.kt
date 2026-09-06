package com.panakam.construction.ui.screens.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.data.S3FileManager
import com.panakam.construction.database.DatabaseManager

/**
 * Customer portal — "Update KYC" screen (SPEC item #2).
 * Upload an Aadhaar card photo → backend OCRs it → customer reviews/edits the
 * extracted name/Aadhaar number/address → confirm persists it as the customer's
 * KYC record, later used to auto-fill agreement drafts (see AgreementRoutes.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KycUploadScreen(onBack: () -> Unit) {
    val user = AuthManager.getCurrentUser()
    val customerId = user?.customerId ?: ""
    val context = LocalContext.current

    var kycStatus   by remember { mutableStateOf("NONE") }
    var isLoading   by remember { mutableStateOf(true) }
    var uploadedS3Key by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableIntStateOf(-1) }
    var isExtracting by remember { mutableStateOf(false) }
    var isSaving    by remember { mutableStateOf(false) }
    var errorMsg    by remember { mutableStateOf("") }
    var infoMsg     by remember { mutableStateOf("") }

    var name         by remember { mutableStateOf("") }
    var aadharNumber by remember { mutableStateOf("") }
    var address      by remember { mutableStateOf("") }
    var warnings     by remember { mutableStateOf<List<String>>(emptyList()) }
    var reviewReady  by remember { mutableStateOf(false) }

    fun load() {
        if (customerId.isBlank()) { isLoading = false; return }
        isLoading = true
        DatabaseManager.getKyc(customerId,
            onSuccess = { m ->
                kycStatus = m["kycStatus"]?.toString() ?: "NONE"
                name = m["name"]?.toString() ?: ""
                aadharNumber = m["aadharNumber"]?.toString() ?: ""
                address = m["address"]?.toString() ?: ""
                isLoading = false
            },
            onFailure = { isLoading = false }
        )
    }
    LaunchedEffect(Unit) { load() }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        errorMsg = ""; infoMsg = ""; reviewReady = false
        val (fname, mime) = S3FileManager.getFileInfo(context, uri)
        DatabaseManager.getKycUploadUrl(customerId, fname, mime,
            onSuccess = { resp ->
                val uploadUrl = resp["uploadUrl"]?.toString() ?: return@getKycUploadUrl
                val s3Key = resp["s3Key"]?.toString() ?: ""
                uploadProgress = 0
                S3FileManager.uploadToPresignedUrl(
                    context = context, uri = uri, uploadUrl = uploadUrl, contentType = mime,
                    onProgress = { pct -> uploadProgress = pct },
                    onSuccess = {
                        uploadProgress = -1
                        uploadedS3Key = s3Key
                        isExtracting = true
                        DatabaseManager.extractKyc(customerId, s3Key,
                            onSuccess = { extracted ->
                                isExtracting = false
                                name = extracted["name"]?.toString()?.ifBlank { name } ?: name
                                aadharNumber = extracted["aadharNumber"]?.toString()?.ifBlank { aadharNumber } ?: aadharNumber
                                address = extracted["address"]?.toString()?.ifBlank { address } ?: address
                                warnings = org.json.JSONArray(extracted["warnings"]?.toString() ?: "[]")
                                    .let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                                reviewReady = true
                            },
                            onFailure = { e -> isExtracting = false; errorMsg = e.message ?: "Extraction failed" }
                        )
                    },
                    onFailure = { e -> uploadProgress = -1; errorMsg = e.message ?: "Upload failed" }
                )
            },
            onFailure = { e -> errorMsg = e.message ?: "Could not start upload" }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Update KYC (Aadhaar)", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = if (kycStatus == "VERIFIED") MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kycStatus == "VERIFIED") Icons.Filled.VerifiedUser else Icons.Filled.Warning, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (kycStatus == "VERIFIED") "KYC verified — details will be used to auto-fill your agreement"
                        else "KYC not yet submitted — upload your Aadhaar card to complete KYC",
                        fontSize = 13.sp
                    )
                }
            }

            Button(
                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = uploadProgress < 0 && !isExtracting && !isSaving
            ) {
                Icon(Icons.Filled.AddAPhoto, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Upload Aadhaar Card Photo")
            }

            if (uploadProgress >= 0) {
                Column {
                    Text("Uploading… $uploadProgress%", fontSize = 12.sp)
                    LinearProgressIndicator(progress = { uploadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (isExtracting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Reading Aadhaar card…", fontSize = 13.sp)
                }
            }
            if (errorMsg.isNotBlank()) Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (infoMsg.isNotBlank()) Text(infoMsg, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            warnings.forEach { w -> Text("⚠️ $w", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }

            if (reviewReady || kycStatus == "VERIFIED") {
                HorizontalDivider()
                Text("Review & Confirm Details", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Full Name (as per Aadhaar) *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = aadharNumber, onValueChange = { aadharNumber = it },
                    label = { Text("Aadhaar Number *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it },
                    label = { Text("Address *") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())

                Button(
                    onClick = {
                        isSaving = true; errorMsg = ""
                        DatabaseManager.confirmKyc(customerId, name.trim(), aadharNumber.trim(), address.trim(), uploadedS3Key,
                            onSuccess = {
                                isSaving = false; reviewReady = false; kycStatus = "VERIFIED"
                                infoMsg = "KYC saved successfully"
                            },
                            onFailure = { e -> isSaving = false; errorMsg = e.message ?: "Save failed" }
                        )
                    },
                    enabled = name.isNotBlank() && aadharNumber.isNotBlank() && address.isNotBlank() && !isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isSaving) "Saving…" else "Confirm & Save KYC") }
            }
        }
    }
}

