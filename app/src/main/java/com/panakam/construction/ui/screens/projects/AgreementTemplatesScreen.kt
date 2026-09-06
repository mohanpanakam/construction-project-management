package com.panakam.construction.ui.screens.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.data.S3FileManager
import com.panakam.construction.database.DatabaseManager
import java.util.UUID

/**
 * Admin screen — per-project agreement templates (SPEC item #3). Admin can upload a
 * template file (PDF/TXT) containing placeholders like {{CUSTOMER_NAME}},
 * {{AADHAR_NUMBER}}, {{ADDRESS}}, {{UNIT_NUMBER}}, {{FLOOR}}, {{SBA}},
 * {{PROJECT_NAME}}, {{TOTAL_AMOUNT}}, {{DATE}} — auto-substituted when a draft
 * agreement is generated for a specific unit/customer (see AgreementRoutes.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgreementTemplatesScreen(
    projectId: String,
    projectName: String,
    onBack: () -> Unit
) {
    val user = AuthManager.getCurrentUser()
    val context = LocalContext.current

    var templates by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableIntStateOf(-1) }
    var showTextDialog by remember { mutableStateOf(false) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getAgreementTemplates(projectId,
            onSuccess = { list -> templates = list; isLoading = false },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }
    LaunchedEffect(Unit) { load() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (fname, mime) = S3FileManager.getFileInfo(context, uri)
        DatabaseManager.getAgreementTemplateUploadUrl(projectId, fname, mime,
            onSuccess = { resp ->
                val uploadUrl = resp["uploadUrl"]?.toString() ?: return@getAgreementTemplateUploadUrl
                val s3Key = resp["s3Key"]?.toString() ?: ""
                val templateId = resp["templateId"]?.toString() ?: UUID.randomUUID().toString()
                uploadProgress = 0
                S3FileManager.uploadToPresignedUrl(
                    context = context, uri = uri, uploadUrl = uploadUrl, contentType = mime,
                    onProgress = { pct -> uploadProgress = pct },
                    onSuccess = {
                        uploadProgress = -1
                        DatabaseManager.registerAgreementTemplate(
                            projectId = projectId, templateId = templateId, name = fname, s3Key = s3Key,
                            contentType = mime, uploadedBy = user?.id ?: "",
                            onSuccess = { load() },
                            onFailure = { e -> errorMsg = e.message ?: "Save failed" }
                        )
                    },
                    onFailure = { e -> uploadProgress = -1; errorMsg = e.message ?: "Upload failed" }
                )
            },
            onFailure = { e -> errorMsg = e.message ?: "Could not start upload" }
        )
    }

    if (showTextDialog) {
        TypeTemplateDialog(
            onDismiss = { showTextDialog = false },
            onSaved = { showTextDialog = false; load() },
            projectId = projectId, uploadedBy = user?.id ?: ""
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agreement Templates", fontWeight = FontWeight.Bold)
                        Text(projectName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = { showTextDialog = true }) { Icon(Icons.Filled.Edit, "Type Template") }
                Spacer(Modifier.height(8.dp))
                ExtendedFloatingActionButton(
                    onClick = { filePicker.launch(arrayOf("application/pdf", "text/plain")) },
                    icon = { Icon(Icons.Filled.Upload, null) },
                    text = { Text("Upload") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Use placeholders like {{CUSTOMER_NAME}}, {{AADHAR_NUMBER}}, {{ADDRESS}}, {{UNIT_NUMBER}}, " +
                    "{{FLOOR}}, {{SBA}}, {{PROJECT_NAME}}, {{TOTAL_AMOUNT}}, {{DATE}} — auto-filled per unit.",
                    fontSize = 11.sp, modifier = Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (uploadProgress >= 0) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Uploading… $uploadProgress%", fontSize = 12.sp)
                    LinearProgressIndicator(progress = { uploadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (errorMsg.isNotBlank()) Text(errorMsg, color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp, modifier = Modifier.padding(12.dp))

            Box(Modifier.fillMaxSize()) {
                when {
                    isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    templates.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.Description, null, modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("No templates yet — upload or type one", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(templates) { t ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(t["name"]?.toString() ?: "Template", fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val len = t["templateText"]?.toString()?.length ?: 0
                                        Text("$len characters", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        DatabaseManager.deleteAgreementTemplate(projectId, t["templateId"].toString(),
                                            onSuccess = { load() }, onFailure = { e -> errorMsg = e.message ?: "Delete failed" }
                                        )
                                    }) { Icon(Icons.Filled.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeTemplateDialog(
    projectId: String, uploadedBy: String,
    onDismiss: () -> Unit, onSaved: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Type Agreement Template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Template Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text("Template Text (use {{PLACEHOLDER}})") },
                    minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth())
                if (errorMsg.isNotBlank()) Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && text.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    DatabaseManager.registerAgreementTemplate(
                        projectId = projectId, templateId = UUID.randomUUID().toString(),
                        name = name.trim(), s3Key = "", contentType = "text/plain",
                        uploadedBy = uploadedBy, templateText = text,
                        onSuccess = { saving = false; onSaved() },
                        onFailure = { e -> saving = false; errorMsg = e.message ?: "Save failed" }
                    )
                }
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = { if (!saving) onDismiss() }) { Text("Cancel") } }
    )
}

