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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager
import java.util.UUID

// ── Model ─────────────────────────────────────────────────────────────────────

data class ProjectUnit(
    val unitId: String,
    val projectId: String,
    val unitNumber: String,
    val floor: String,
    val type: String,
    val sba: String,
    val status: String,
    val availability: String,
    val owner: String
) {
    companion object {
        val TYPES         = listOf("1 BHK", "2 BHK", "2.5 BHK", "3 BHK", "3.5 BHK", "4 BHK", "Penthouse", "Studio", "Other")
        val STATUSES      = listOf("Under Construction", "Ready to Move", "Possession Given", "On Hold")
        val AVAILABILITIES = listOf("Available", "Blocked", "Sold")
        val OWNERS        = listOf("Builder", "LandOwner")

        fun fromMap(map: Map<String, Any>) = ProjectUnit(
            unitId       = map["unitId"]?.toString()       ?: "",
            projectId    = map["projectId"]?.toString()    ?: "",
            unitNumber   = map["unitNumber"]?.toString()   ?: "",
            floor        = map["floor"]?.toString()        ?: "",
            type         = map["type"]?.toString()         ?: "",
            sba          = map["sba"]?.toString()          ?: "0",
            status       = map["status"]?.toString()       ?: "Under Construction",
            availability = map["availability"]?.toString() ?: "Available",
            owner        = map["owner"]?.toString()        ?: "Builder"
        )
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectUnitsScreen(
    projectId: String,
    projectName: String,
    isJointDevelopment: Boolean,
    onBack: () -> Unit
) {
    val context  = LocalContext.current
    val user     = AuthManager.getCurrentUser()
    val canWrite = user?.role == UserRole.ADMIN || user?.role == UserRole.PROJECT_MANAGER

    var units          by remember { mutableStateOf<List<ProjectUnit>>(emptyList()) }
    var isLoading      by remember { mutableStateOf(true) }
    var errorMsg       by remember { mutableStateOf("") }
    var uploadStatus   by remember { mutableStateOf("") }
    var selectedTab    by remember { mutableIntStateOf(0) }
    val tabs = listOf("All", "Available", "Blocked", "Sold")

    var showAddDialog    by remember { mutableStateOf(false) }
    var unitToDelete     by remember { mutableStateOf<ProjectUnit?>(null) }
    var unitToEdit       by remember { mutableStateOf<ProjectUnit?>(null) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getUnits(projectId,
            onSuccess = { maps ->
                units     = maps.map { ProjectUnit.fromMap(it) }
                isLoading = false
            },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }

    LaunchedEffect(Unit) { load() }

    // Filtered list by tab
    val displayed = when (selectedTab) {
        1    -> units.filter { it.availability == "Available" }
        2    -> units.filter { it.availability == "Blocked" }
        3    -> units.filter { it.availability == "Sold" }
        else -> units
    }

    // Summary
    val total     = units.size
    val available = units.count { it.availability == "Available" }
    val blocked   = units.count { it.availability == "Blocked" }
    val sold      = units.count { it.availability == "Sold" }
    val builderCount   = units.count { it.owner == "Builder" }
    val landOwnerCount = units.count { it.owner == "LandOwner" }

    // ── Excel file picker ────────────────────────────────────────────────────
    val excelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploadStatus = "Uploading…"
        DatabaseManager.uploadUnitsExcel(context, projectId, uri,
            onSuccess = { count ->
                uploadStatus = "✓ $count units imported"
                load()
            },
            onFailure = { e ->
                uploadStatus = "Upload failed: ${e.message}"
            }
        )
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────
    unitToDelete?.let { u ->
        AlertDialog(
            onDismissRequest = { unitToDelete = null },
            title = { Text("Delete Unit") },
            text  = { Text("Delete unit \"${u.unitNumber}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    unitToDelete = null
                    DatabaseManager.deleteUnit(projectId, u.unitId,
                        onSuccess = { load() },
                        onFailure = { e -> errorMsg = e.message ?: "Delete failed" }
                    )
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { unitToDelete = null }) { Text("Cancel") } }
        )
    }

    unitToEdit?.let { u ->
        UnitAvailabilityDialog(
            unit      = u,
            onDismiss = { unitToEdit = null },
            onConfirm = { data ->
                unitToEdit = null
                DatabaseManager.updateUnit(projectId, u.unitId, data,
                    onSuccess = { load() },
                    onFailure = { e -> errorMsg = e.message ?: "Update failed" }
                )
            }
        )
    }

    if (showAddDialog) {
        AddUnitDialog(
            isJointDevelopment = isJointDevelopment,
            onDismiss = { showAddDialog = false },
            onConfirm = { data ->
                showAddDialog = false
                val unitId = UUID.randomUUID().toString()
                DatabaseManager.addUnit(projectId, data + mapOf("unitId" to unitId),
                    onSuccess = { load() },
                    onFailure = { e -> errorMsg = e.message ?: "Add failed" }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Units", fontWeight = FontWeight.Bold)
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
                    if (canWrite) {
                        IconButton(onClick = {
                            excelPicker.launch(arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "application/octet-stream"
                            ))
                        }) {
                            Icon(Icons.Filled.UploadFile, "Import Excel")
                        }
                    }
                    IconButton(onClick = { load() }) {
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
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, "Add Unit")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Upload status banner
            if (uploadStatus.isNotEmpty()) {
                LaunchedEffect(uploadStatus) {
                    kotlinx.coroutines.delay(4000); uploadStatus = ""
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    color    = MaterialTheme.colorScheme.primaryContainer,
                    shape    = MaterialTheme.shapes.small
                ) {
                    Text(uploadStatus, modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (errorMsg.isNotEmpty()) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp)
            }

            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
                else -> {
                    // Summary card
                    UnitSummaryCard(
                        total = total, available = available, blocked = blocked, sold = sold,
                        builderCount = builderCount, landOwnerCount = landOwnerCount,
                        isJD = isJointDevelopment
                    )

                    // Excel hint
                    if (canWrite && units.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.TableChart, null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Tip: Use the ↑ button in the toolbar to bulk import units from an Excel file.",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }

                    // Filter tabs
                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { i, label ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                                text = { Text("$label${if (i > 0) " (${when(i){1->available;2->blocked;else->sold}})" else " ($total)"}", fontSize = 12.sp) })
                        }
                    }

                    if (displayed.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Filled.Apartment, null, modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("No units ${if (selectedTab > 0) "in this category" else "yet"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(displayed) { unit ->
                                UnitCard(
                                    unit     = unit,
                                    isJD     = isJointDevelopment,
                                    canWrite = canWrite,
                                    onEdit   = { unitToEdit   = unit },
                                    onDelete = { unitToDelete = unit }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Summary card ──────────────────────────────────────────────────────────────

@Composable
private fun UnitSummaryCard(
    total: Int, available: Int, blocked: Int, sold: Int,
    builderCount: Int, landOwnerCount: Int, isJD: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryPill("Total",     total.toString(),     MaterialTheme.colorScheme.onPrimaryContainer)
                SummaryPill("Available", available.toString(), MaterialTheme.colorScheme.tertiary)
                SummaryPill("Blocked",   blocked.toString(),   MaterialTheme.colorScheme.secondary)
                SummaryPill("Sold",      sold.toString(),      MaterialTheme.colorScheme.error)
            }
            if (isJD && (builderCount > 0 || landOwnerCount > 0)) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryPill("Builder",    builderCount.toString(),   MaterialTheme.colorScheme.primary)
                    SummaryPill("Land Owner", landOwnerCount.toString(), MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun RowScope.SummaryPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

// ── Unit card ─────────────────────────────────────────────────────────────────

@Composable
private fun UnitCard(
    unit: ProjectUnit,
    isJD: Boolean,
    canWrite: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val availColor = when (unit.availability) {
        "Available" -> MaterialTheme.colorScheme.tertiary
        "Blocked"   -> MaterialTheme.colorScheme.secondary
        "Sold"      -> MaterialTheme.colorScheme.error
        else        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Apartment, null, modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Unit ${unit.unitNumber}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (unit.floor.isNotEmpty())
                            Text("• Floor ${unit.floor}", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (unit.type.isNotEmpty())
                            AssistChip(onClick = {}, label = { Text(unit.type, fontSize = 11.sp) })
                        if (unit.sba != "0" && unit.sba.isNotEmpty())
                            AssistChip(onClick = {}, label = { Text("${unit.sba} sqft", fontSize = 11.sp) })
                    }
                }
                // Availability badge
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = availColor.copy(alpha = 0.15f)
                    ) {
                        Text(unit.availability, fontSize = 11.sp, color = availColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    if (isJD) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(unit.owner, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            if (unit.status.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(unit.status, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canWrite) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Update", fontSize = 12.sp)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.DeleteOutline, null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ── Availability update dialog ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitAvailabilityDialog(
    unit: ProjectUnit,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any>) -> Unit
) {
    var availability   by remember { mutableStateOf(unit.availability) }
    var status         by remember { mutableStateOf(unit.status) }
    var availExpanded  by remember { mutableStateOf(false) }
    var statusExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Unit ${unit.unitNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(expanded = availExpanded, onExpandedChange = { availExpanded = !availExpanded }) {
                    OutlinedTextField(
                        value = availability, onValueChange = {}, readOnly = true,
                        label = { Text("Availability") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(availExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = availExpanded, onDismissRequest = { availExpanded = false }) {
                        ProjectUnit.AVAILABILITIES.forEach { a ->
                            DropdownMenuItem(text = { Text(a) }, onClick = { availability = a; availExpanded = false })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                    OutlinedTextField(
                        value = status, onValueChange = {}, readOnly = true,
                        label = { Text("Construction Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                        ProjectUnit.STATUSES.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusExpanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(mapOf("availability" to availability, "status" to status))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Add unit dialog ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddUnitDialog(
    isJointDevelopment: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any>) -> Unit
) {
    var unitNumber   by remember { mutableStateOf("") }
    var floor        by remember { mutableStateOf("") }
    var type         by remember { mutableStateOf(ProjectUnit.TYPES.first()) }
    var sba          by remember { mutableStateOf("") }
    var status       by remember { mutableStateOf("Under Construction") }
    var availability by remember { mutableStateOf("Available") }
    var owner        by remember { mutableStateOf("Builder") }

    var typeExpanded   by remember { mutableStateOf(false) }
    var availExpanded  by remember { mutableStateOf(false) }
    var statusExpanded by remember { mutableStateOf(false) }
    var ownerExpanded  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Unit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = unitNumber, onValueChange = { unitNumber = it },
                        label = { Text("Unit No. *") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = floor, onValueChange = { floor = it },
                        label = { Text("Floor") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = !typeExpanded }) {
                    OutlinedTextField(value = type, onValueChange = {}, readOnly = true,
                        label = { Text("Unit Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        ProjectUnit.TYPES.forEach { t ->
                            DropdownMenuItem(text = { Text(t) }, onClick = { type = t; typeExpanded = false })
                        }
                    }
                }
                OutlinedTextField(value = sba, onValueChange = { sba = it },
                    label = { Text("SBA (sqft)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                    OutlinedTextField(value = status, onValueChange = {}, readOnly = true,
                        label = { Text("Construction Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                        ProjectUnit.STATUSES.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusExpanded = false })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = availExpanded, onExpandedChange = { availExpanded = !availExpanded }) {
                    OutlinedTextField(value = availability, onValueChange = {}, readOnly = true,
                        label = { Text("Availability") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(availExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = availExpanded, onDismissRequest = { availExpanded = false }) {
                        ProjectUnit.AVAILABILITIES.forEach { a ->
                            DropdownMenuItem(text = { Text(a) }, onClick = { availability = a; availExpanded = false })
                        }
                    }
                }
                if (isJointDevelopment) {
                    ExposedDropdownMenuBox(expanded = ownerExpanded, onExpandedChange = { ownerExpanded = !ownerExpanded }) {
                        OutlinedTextField(value = owner, onValueChange = {}, readOnly = true,
                            label = { Text("Owner") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ownerExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = ownerExpanded, onDismissRequest = { ownerExpanded = false }) {
                            ProjectUnit.OWNERS.forEach { o ->
                                DropdownMenuItem(text = { Text(o) }, onClick = { owner = o; ownerExpanded = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (unitNumber.isNotBlank()) {
                        onConfirm(mapOf(
                            "unitNumber"   to unitNumber.trim(),
                            "floor"        to floor.trim(),
                            "type"         to type,
                            "sba"          to sba.trim(),
                            "status"       to status,
                            "availability" to availability,
                            "owner"        to owner
                        ))
                    }
                },
                enabled = unitNumber.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

