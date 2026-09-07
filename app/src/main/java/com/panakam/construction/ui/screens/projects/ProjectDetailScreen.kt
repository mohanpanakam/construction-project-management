package com.panakam.construction.ui.screens.projects

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager
import com.panakam.construction.model.Project
import com.panakam.construction.model.ProjectFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onEdit: (Project) -> Unit,
    onDeleted: () -> Unit,
    onViewFiles:       (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewInventory:   (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewFinancials:  (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewUnits:       (projectId: String, projectName: String, isJD: Boolean) -> Unit = { _, _, _ -> },
    onViewCollections: (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewSuspense:    (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewSalesReps:   (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewPaymentHistory: (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewAgreementTemplates: (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onViewReports: (projectId: String, projectName: String) -> Unit = { _, _ -> }
) {
    val user    = AuthManager.getCurrentUser()
    val context = LocalContext.current

    var project by remember { mutableStateOf<Project?>(null) }
    var photos  by remember { mutableStateOf<List<ProjectFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getProject(
            projectId = projectId,
            onSuccess = { map ->
                project   = map?.let { Project.fromMap(it) }
                isLoading = false
            },
            onFailure = { e -> errorMsg = e.message ?: "Failed to load"; isLoading = false }
        )
        // Photos are uploaded to S3 during onboarding — load them from the backend
        // (not local storage) so they show up correctly on any device/session.
        DatabaseManager.getProjectFiles(
            projectId = projectId,
            folder    = "photos",
            onSuccess = { list -> photos = list.map { ProjectFile.fromMap(it) } },
            onFailure = { /* non-fatal — just show no photos */ }
        )
    }

    LaunchedEffect(projectId) { load() }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Project") },
            text  = { Text("Delete \"${project?.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false; isDeleting = true
                    DatabaseManager.deleteProject(projectId,
                        onSuccess = {
                            isDeleting = false; onDeleted()
                        },
                        onFailure = { e -> isDeleting = false; errorMsg = e.message ?: "Delete failed" }
                    )
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "Project", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!isLoading && project != null) {
                        if (user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER) {
                            IconButton(onClick = { project?.let { onEdit(it) } }) {
                                Icon(Icons.Filled.Edit, "Edit")
                            }
                        }
                        if (user?.role == UserRole.ADMIN) {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Filled.Delete, "Delete",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading || isDeleting ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { load() }) { Text("Retry") }
                }
                project != null -> {
                    val p = project!!

                    // Cover photo download URL (first uploaded project photo, if any).
                    var coverUrl by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(photos) {
                        val first = photos.firstOrNull()
                        if (first == null) { coverUrl = null; return@LaunchedEffect }
                        DatabaseManager.getDownloadUrl(
                            projectId = projectId,
                            fileId    = first.fileId,
                            onSuccess = { url -> coverUrl = url },
                            onFailure = { coverUrl = null }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Cover / background image from the uploaded project photos
                        if (coverUrl != null) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = "Project cover photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.LightGray)
                            )
                        }

                        // Status badge
                        val statusColor = statusColor(p.status)
                        Surface(shape = RoundedCornerShape(20.dp),
                            color = statusColor.copy(alpha = 0.15f)) {
                            Text(p.status, fontSize = 13.sp, color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }

                        // Basic info
                        DetailCard {
                            DetailRow(Icons.Filled.Business,    "Project Name", p.name)
                            if (p.location.isNotEmpty())
                                DetailRow(Icons.Filled.LocationOn, "Location",  p.location)
                            if (p.startDate.isNotEmpty())
                                DetailRow(Icons.Filled.CalendarToday, "Start Date", p.startDate)
                            if (p.endDate.isNotEmpty())
                                DetailRow(Icons.Filled.EventAvailable, "End Date", p.endDate)
                            if (p.budget.isNotEmpty())
                                DetailRow(Icons.Filled.AttachMoney, "Budget", p.budget)
                        }

                        if (p.description.isNotEmpty()) {
                            DetailCard {
                                Text("Description", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                Text(p.description, fontSize = 14.sp)
                            }
                        }

                        // Map location
                        if (p.mapLocation.isNotEmpty()) {
                            SectionHeader("Map Location")
                            DetailCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Map, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(p.mapLocation, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val uri = Uri.parse("geo:0,0?q=${Uri.encode(p.mapLocation)}")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.OpenInNew, null,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open in Google Maps")
                                }
                            }
                        }

                        // Partner details
                        if (p.partnerName.isNotEmpty() || p.partnerPhone.isNotEmpty() || p.partnerEmail.isNotEmpty()) {
                            SectionHeader("Partner / Contractor")
                            DetailCard {
                                if (p.partnerName.isNotEmpty())
                                    DetailRow(Icons.Filled.Business, "Company", p.partnerName)
                                if (p.partnerPhone.isNotEmpty())
                                    DetailRow(Icons.Filled.Phone, "Phone", p.partnerPhone,
                                        onClick = {
                                            context.startActivity(Intent(Intent.ACTION_DIAL,
                                                Uri.parse("tel:${p.partnerPhone}")))
                                        })
                                if (p.partnerEmail.isNotEmpty())
                                    DetailRow(Icons.Filled.Email, "Email", p.partnerEmail,
                                        onClick = {
                                            context.startActivity(Intent(Intent.ACTION_SENDTO,
                                                Uri.parse("mailto:${p.partnerEmail}")))
                                        })
                            }
                        }

                        // ── Project type info ──────────────────────────────
                        DetailCard {
                            DetailRow(Icons.Filled.Business, "Project Type", p.projectType)
                            if (p.isJointDevelopment) {
                                if (p.landOwnerName.isNotEmpty())
                                    DetailRow(Icons.Filled.Person, "Land Owner", p.landOwnerName)
                                if (p.landOwnerShare.isNotEmpty())
                                    DetailRow(Icons.Filled.Percent, "Land Owner's Share", p.landOwnerShare)
                            }
                        }

                        // ── Project Sections ───────────────────────────────
                        Spacer(Modifier.height(4.dp))
                        SectionHeader("Project Sections")
                        Spacer(Modifier.height(4.dp))
                        // Row 1
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ProjectSectionCard(
                                icon     = Icons.Filled.Apartment,
                                title    = "Units",
                                subtitle = if (p.isJointDevelopment) "JD unit allocation" else "All units",
                                modifier = Modifier.weight(1f),
                                onClick  = { onViewUnits(p.projectId, p.name, p.isJointDevelopment) }
                            )
                            ProjectSectionCard(
                                icon     = Icons.Filled.Inventory2,
                                title    = "Inventory",
                                subtitle = "Materials & equipment",
                                modifier = Modifier.weight(1f),
                                onClick  = { onViewInventory(p.projectId, p.name) }
                            )
                        }
                        // Row 2 — Financials is Admin-only; Files visible to everyone
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (user?.role == UserRole.ADMIN) {
                                ProjectSectionCard(
                                    icon     = Icons.Filled.AttachMoney,
                                    title    = "Financials",
                                    subtitle = "Budgets & expenses",
                                    modifier = Modifier.weight(1f),
                                    onClick  = { onViewFinancials(p.projectId, p.name) }
                                )
                            }
                            ProjectSectionCard(
                                icon     = Icons.Filled.CloudUpload,
                                title    = "Files",
                                subtitle = "Photos, docs & receipts",
                                modifier = Modifier.weight(1f),
                                onClick  = { onViewFiles(p.projectId, p.name) }
                            )
                        }
                        // Row 3 — Collections (Admin, PM & Sales Rep — sales reps only see their own sales)
                        if (user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER ||
                            user?.role == UserRole.SALES_REP) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ProjectSectionCard(
                                    icon     = Icons.Filled.AccountBalanceWallet,
                                    title    = "Collections",
                                    subtitle = "Unit sale records & revenue",
                                    modifier = Modifier.weight(1f),
                                    onClick  = { onViewCollections(p.projectId, p.name) }
                                )
                                if (user.role == UserRole.ADMIN) {
                                    ProjectSectionCard(
                                        icon     = Icons.Filled.AccountBalance,
                                        title    = "Suspense",
                                        subtitle = "Reverted sale funds",
                                        modifier = Modifier.weight(1f),
                                        onClick  = { onViewSuspense(p.projectId, p.name) }
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                            // Payment History — project-wise history across all units/customers
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ProjectSectionCard(
                                    icon     = Icons.Filled.Payments,
                                    title    = "Payment History",
                                    subtitle = "All payments for this project",
                                    modifier = Modifier.weight(1f),
                                    onClick  = { onViewPaymentHistory(p.projectId, p.name) }
                                )
                                ProjectSectionCard(
                                    icon     = Icons.Filled.Assessment,
                                    title    = "Reports",
                                    subtitle = "Payment status, by sales rep",
                                    modifier = Modifier.weight(1f),
                                    onClick  = { onViewReports(p.projectId, p.name) }
                                )
                            }
                        }
                        // Auditor doesn't get the Collections block above (no "sell units"
                        // permissions) but still needs to see the payment status report.
                        if (user?.role == UserRole.AUDITOR) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ProjectSectionCard(
                                    icon     = Icons.Filled.Assessment,
                                    title    = "Reports",
                                    subtitle = "Payment status, by sales rep",
                                    modifier = Modifier.weight(1f),
                                    onClick  = { onViewReports(p.projectId, p.name) }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        // Row 4 — Sales Team (Admin only): manage sales reps who can sell units
                        if (user?.role == UserRole.ADMIN) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ProjectSectionCard(
                                    icon     = Icons.Filled.Groups,
                                    title    = "Sales Team",
                                    subtitle = "Manage sales reps",
                                    modifier = Modifier.weight(1f),
                                    onClick  = { onViewSalesReps(p.projectId, p.name) }
                                )
                                ProjectSectionCard(
                                    icon     = Icons.Filled.Description,
                                    title    = "Agreement Templates",
                                    subtitle = "Sale agreement drafts",
                                    modifier = Modifier.weight(1f),
                                    onClick  = { onViewAgreementTemplates(p.projectId, p.name) }
                                )
                            }
                        }

                        // Photos — uploaded during onboarding / editing (stored in S3, same
                        // store the Files → Photos tab reads from).
                        if (photos.isNotEmpty()) {
                            SectionHeader("Photos")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(photos, key = { it.fileId }) { file ->
                                    ProjectPhotoThumb(
                                        projectId = projectId,
                                        file      = file,
                                        onClick   = { onViewFiles(p.projectId, p.name) }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick  = onClick,
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(subtitle, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                maxLines = 1)
        }
    }
}

@Composable
private fun ProjectPhotoThumb(
    projectId: String,
    file: ProjectFile,
    onClick: () -> Unit
) {
    var imageUrl by remember(file.fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.fileId) {
        DatabaseManager.getDownloadUrl(
            projectId = projectId,
            fileId    = file.fileId,
            onSuccess = { url -> imageUrl = url },
            onFailure = { /* keep placeholder */ }
        )
    }
    AsyncImage(
        model = imageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.LightGray)
            .then(Modifier)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun statusColor(status: String) = when (status) {
    "In Progress" -> MaterialTheme.colorScheme.primary
    "Completed"   -> MaterialTheme.colorScheme.tertiary
    "On Hold"     -> MaterialTheme.colorScheme.error
    else          -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) {
                TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                    Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

