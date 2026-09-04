package com.panakam.construction.ui.screens.projects

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager
import com.panakam.construction.model.Project
import com.panakam.construction.model.ProjectFile
import com.panakam.construction.ui.theme.GradientBottom
import com.panakam.construction.ui.theme.GradientTop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onBack: () -> Unit,
    onAddProject: () -> Unit,
    onViewProject: (String) -> Unit
) {
    val user = AuthManager.getCurrentUser()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }

    fun loadProjects() {
        isLoading = true
        errorMsg = ""
        DatabaseManager.getAllProjects(
            onSuccess = { maps ->
                projects = maps.map { Project.fromMap(it) }
                isLoading = false
            },
            onFailure = { e ->
                errorMsg = "Failed to load projects: ${e.message}"
                isLoading = false
            }
        )
    }

    LaunchedEffect(Unit) { loadProjects() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadProjects() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            if (user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER) {
                FloatingActionButton(onClick = onAddProject) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Project")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.WifiOff, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(errorMsg, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { loadProjects() }) { Text("Retry") }
                }
                projects.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No projects yet", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onAddProject) { Text("Add First Project") }
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projects) { project ->
                        ProjectCard(project = project, onClick = { onViewProject(project.projectId) })
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectCard(project: Project, onClick: () -> Unit) {
    val statusColor = when (project.status) {
        "In Progress" -> MaterialTheme.colorScheme.primary
        "Completed"   -> MaterialTheme.colorScheme.tertiary
        "On Hold"     -> MaterialTheme.colorScheme.error
        else          -> MaterialTheme.colorScheme.secondary
    }

    // First uploaded project photo (from S3, via backend), used as the card's cover image.
    var coverUrl by remember(project.projectId) { mutableStateOf<String?>(null) }
    LaunchedEffect(project.projectId) {
        DatabaseManager.getProjectFiles(
            projectId = project.projectId,
            folder    = "photos",
            onSuccess = { list ->
                val first = list.map { ProjectFile.fromMap(it) }.firstOrNull()
                if (first == null) { coverUrl = null; return@getProjectFiles }
                DatabaseManager.getDownloadUrl(
                    projectId = project.projectId,
                    fileId    = first.fileId,
                    onSuccess = { url -> coverUrl = url },
                    onFailure = { coverUrl = null }
                )
            },
            onFailure = { coverUrl = null }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column {
            // ── Cover photo / placeholder ─────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model            = coverUrl,
                        contentDescription = "Cover photo",
                        contentScale     = ContentScale.Crop,
                        modifier         = Modifier.fillMaxSize()
                    )
                } else {
                    // Gradient placeholder with icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(listOf(GradientTop, GradientBottom))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Business,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }

                // Status badge overlaid on photo
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = statusColor.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    Text(
                        text = project.status,
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // Bottom gradient scrim so title text is readable on photos
                if (coverUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                                )
                            )
                    )
                    Text(
                        text = project.name.ifBlank { "Unnamed Project" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // ── Text details below photo ──────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (coverUrl == null) {
                    // No photo — show name in text area
                    Text(
                        text = project.name.ifBlank { "Unnamed Project" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (project.location.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(project.location, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (project.startDate.isNotEmpty() || project.endDate.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DateRange, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("${project.startDate} → ${project.endDate}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (project.budget.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AttachMoney, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("Budget: ${project.budget}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

