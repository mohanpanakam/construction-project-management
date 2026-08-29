package com.panakam.construction.ui.screens.projects

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.data.S3FileManager
import com.panakam.construction.database.DatabaseManager
import com.panakam.construction.model.ProjectFile
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFilesScreen(
    projectId: String,
    projectName: String,
    onBack: () -> Unit
) {
    val user    = AuthManager.getCurrentUser()
    val context = LocalContext.current
    val canWrite = user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER

    // ── State ─────────────────────────────────────────────────────────────────
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        Triple("Photos",       Icons.Filled.PhotoLibrary, "photos"),
        Triple("Documents",    Icons.Filled.Description,   "documents"),
        Triple("Transactions", Icons.Filled.Receipt,       "transactions")
    )
    val currentFolder = tabs[selectedTab].third

    var files       by remember { mutableStateOf<List<ProjectFile>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf("") }
    var showUploadSheet by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableIntStateOf(-1) } // -1 = idle
    var uploadStatus by remember { mutableStateOf("") }
    var fileToDelete by remember { mutableStateOf<ProjectFile?>(null) }

    fun loadFiles() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getProjectFiles(
            projectId = projectId,
            folder    = currentFolder,
            onSuccess = { maps ->
                files     = maps.map { ProjectFile.fromMap(it) }
                isLoading = false
            },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }

    LaunchedEffect(selectedTab) { loadFiles() }

    // ── Photo picker ──────────────────────────────────────────────────────────
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uploadProgress = 0
        uris.forEachIndexed { idx, uri ->
            val (name, mime) = S3FileManager.getFileInfo(context, uri)
            DatabaseManager.getUploadUrl(
                projectId   = projectId,
                fileName    = name,
                folder      = "photos",
                contentType = mime,
                onSuccess   = { resp ->
                    val uploadUrl = resp["uploadUrl"]?.toString() ?: return@getUploadUrl
                    S3FileManager.uploadToPresignedUrl(
                        context     = context,
                        uri         = uri,
                        uploadUrl   = uploadUrl,
                        contentType = mime,
                        onProgress  = { pct ->
                            // average progress across all files
                            uploadProgress = (idx * 100 + pct) / uris.size
                        },
                        onSuccess   = {
                            if (idx == uris.lastIndex) {
                                uploadProgress = -1
                                uploadStatus   = "${uris.size} photo(s) uploaded"
                                loadFiles()
                            }
                        },
                        onFailure   = { e ->
                            uploadProgress = -1
                            errorMsg = "Upload failed: ${e.message}"
                        }
                    )
                },
                onFailure = { e -> errorMsg = e.message ?: "Could not get upload URL" }
            )
        }
    }

    // ── File picker (documents / transactions) ────────────────────────────────
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uploadProgress = 0
        uris.forEachIndexed { idx, uri ->
            val (name, mime) = S3FileManager.getFileInfo(context, uri)
            DatabaseManager.getUploadUrl(
                projectId   = projectId,
                fileName    = name,
                folder      = currentFolder,
                contentType = mime,
                onSuccess   = { resp ->
                    val uploadUrl = resp["uploadUrl"]?.toString() ?: return@getUploadUrl
                    S3FileManager.uploadToPresignedUrl(
                        context     = context,
                        uri         = uri,
                        uploadUrl   = uploadUrl,
                        contentType = mime,
                        onProgress  = { pct ->
                            uploadProgress = (idx * 100 + pct) / uris.size
                        },
                        onSuccess   = {
                            if (idx == uris.lastIndex) {
                                uploadProgress = -1
                                uploadStatus   = "${uris.size} file(s) uploaded"
                                loadFiles()
                            }
                        },
                        onFailure   = { e ->
                            uploadProgress = -1
                            errorMsg = "Upload failed: ${e.message}"
                        }
                    )
                },
                onFailure = { e -> errorMsg = e.message ?: "Could not get upload URL" }
            )
        }
    }

    // ── Delete confirmation ────────────────────────────────────────────────────
    fileToDelete?.let { pf ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete File") },
            text  = { Text("Delete \"${pf.fileName}\" from S3?") },
            confirmButton = {
                TextButton(onClick = {
                    fileToDelete = null
                    DatabaseManager.deleteProjectFile(
                        projectId = projectId,
                        fileId    = pf.fileId,
                        onSuccess = { loadFiles() },
                        onFailure = { e -> errorMsg = e.message ?: "Delete failed" }
                    )
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { fileToDelete = null }) { Text("Cancel") } }
        )
    }

    // ── Upload progress snackbar area ─────────────────────────────────────────
    if (uploadStatus.isNotEmpty()) {
        LaunchedEffect(uploadStatus) {
            kotlinx.coroutines.delay(3000)
            uploadStatus = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Files", fontWeight = FontWeight.Bold)
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
                    IconButton(onClick = { loadFiles() }) {
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
                ExtendedFloatingActionButton(
                    onClick = { showUploadSheet = true },
                    icon    = { Icon(Icons.Filled.Upload, null) },
                    text    = { Text("Upload") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Tabs ──────────────────────────────────────────────────────
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { idx, (label, icon, _) ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick  = { selectedTab = idx },
                        text     = { Text(label, fontSize = 12.sp) },
                        icon     = { Icon(icon, null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            // ── Upload progress bar ───────────────────────────────────────
            if (uploadProgress >= 0) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Uploading… $uploadProgress%", fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { uploadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (uploadStatus.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(uploadStatus, modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (errorMsg.isNotEmpty()) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 13.sp)
            }

            // ── Content ───────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center))
                    files.isEmpty() -> EmptyState(
                        folder   = currentFolder,
                        canWrite = canWrite,
                        onUpload = { showUploadSheet = true }
                    )
                    currentFolder == "photos" ->
                        PhotoGrid(
                            files       = files,
                            onTap       = { file ->
                                DatabaseManager.getDownloadUrl(projectId, file.fileId,
                                    onSuccess = { url ->
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    },
                                    onFailure = { e -> errorMsg = e.message ?: "Download failed" }
                                )
                            },
                            onLongPress = { if (canWrite) fileToDelete = it }
                        )
                    else ->
                        FileList(
                            files       = files,
                            onTap       = { file ->
                                DatabaseManager.getDownloadUrl(projectId, file.fileId,
                                    onSuccess = { url ->
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    },
                                    onFailure = { e -> errorMsg = e.message ?: "Download failed" }
                                )
                            },
                            onDelete    = { if (canWrite) fileToDelete = it }
                        )
                }
            }
        }
    }

    // ── Upload bottom sheet ───────────────────────────────────────────────────
    if (showUploadSheet) {
        ModalBottomSheet(onDismissRequest = { showUploadSheet = false }) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Add to ${tabs[selectedTab].first}",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Divider()

                if (currentFolder == "photos") {
                    UploadOption(Icons.Filled.AddPhotoAlternate, "Pick Photos from Gallery") {
                        showUploadSheet = false
                        photoPicker.launch(PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    UploadOption(Icons.Filled.CameraAlt, "Take a Photo") {
                        showUploadSheet = false
                        // Use same picker – camera option available in system UI
                        photoPicker.launch(PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                } else {
                    UploadOption(Icons.Filled.AttachFile, "Pick File from Device") {
                        showUploadSheet = false
                        filePicker.launch(arrayOf("*/*"))
                    }
                    UploadOption(Icons.Filled.PictureAsPdf, "Pick PDF") {
                        showUploadSheet = false
                        filePicker.launch(arrayOf("application/pdf"))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Photo grid ──────────────────────────────────────────────────────────────

@Composable
private fun PhotoGrid(
    files: List<ProjectFile>,
    onTap: (ProjectFile) -> Unit,
    onLongPress: (ProjectFile) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(files) { file ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onTap(file) }
            ) {
                // Show placeholder with a cloud icon (actual image loads via download URL)
                Icon(Icons.Filled.Image, null,
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                // File name overlay
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = file.fileName,
                        fontSize = 10.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ── File list ───────────────────────────────────────────────────────────────

@Composable
private fun FileList(
    files: List<ProjectFile>,
    onTap: (ProjectFile) -> Unit,
    onDelete: (ProjectFile) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(files) { file ->
            FileRow(file = file, onTap = onTap, onDelete = onDelete)
        }
    }
}

@Composable
private fun FileRow(
    file: ProjectFile,
    onTap: (ProjectFile) -> Unit,
    onDelete: (ProjectFile) -> Unit
) {
    val icon = when {
        file.contentType == "application/pdf"   -> Icons.Filled.PictureAsPdf
        file.contentType.startsWith("image/")   -> Icons.Filled.Image
        file.contentType.contains("sheet")      -> Icons.Filled.TableChart
        file.contentType.contains("word")       -> Icons.Filled.Description
        file.contentType.contains("text")       -> Icons.Filled.Article
        else                                    -> Icons.Filled.InsertDriveFile
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick  = { onTap(file) }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.fileName, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = file.uploadedAt.toLongOrNull()?.let {
                    SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date(it))
                } ?: file.uploadedAt
                Text(date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onDelete(file) }) {
                Icon(Icons.Filled.DeleteOutline, "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Empty state ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(folder: String, canWrite: Boolean, onUpload: () -> Unit) {
    val (icon, msg) = when (folder) {
        "photos"       -> Pair(Icons.Filled.PhotoLibrary, "No photos yet")
        "documents"    -> Pair(Icons.Filled.Description,   "No documents yet")
        "transactions" -> Pair(Icons.Filled.Receipt,       "No transaction files yet")
        else           -> Pair(Icons.Filled.Folder,        "No files yet")
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(msg, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (canWrite) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onUpload) {
                Icon(Icons.Filled.Upload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Upload First File")
            }
        }
    }
}

// ── Upload option row ────────────────────────────────────────────────────────

@Composable
private fun UploadOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp)
    }
}

