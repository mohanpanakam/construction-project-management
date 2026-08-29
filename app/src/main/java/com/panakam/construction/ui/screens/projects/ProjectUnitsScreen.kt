package com.panakam.construction.ui.screens.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        val TYPES          = listOf("1 BHK", "2 BHK", "2.5 BHK", "3 BHK", "3.5 BHK", "4 BHK", "Penthouse", "Studio", "Other")
        val STATUSES       = listOf("Under Construction", "Ready to Move", "Possession Given", "On Hold")
        val AVAILABILITIES = listOf("Available", "Blocked", "Sold")
        val OWNERS         = listOf("Builder", "LandOwner")

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

// ── Availability colours ──────────────────────────────────────────────────────

@Composable
private fun availColor(availability: String) = when (availability) {
    "Available" -> Color(0xFF2E7D32)   // dark green
    "Blocked"   -> Color(0xFFE65100)   // dark orange
    "Sold"      -> Color(0xFFC62828)   // dark red
    else        -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun availBgColor(availability: String) = when (availability) {
    "Available" -> Color(0xFFE8F5E9)
    "Blocked"   -> Color(0xFFFFF3E0)
    "Sold"      -> Color(0xFFFFEBEE)
    else        -> MaterialTheme.colorScheme.surfaceVariant
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

    var units        by remember { mutableStateOf<List<ProjectUnit>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var errorMsg     by remember { mutableStateOf("") }
    var uploadStatus by remember { mutableStateOf("") }

    // ── Search & Filters ─────────────────────────────────────────────────────
    var searchQuery        by remember { mutableStateOf("") }
    var filterAvailability by remember { mutableStateOf("All") }
    var filterFloor        by remember { mutableStateOf("All") }
    var filterType         by remember { mutableStateOf("All") }
    var filterOwner        by remember { mutableStateOf(if (isJointDevelopment) "Builder" else "All") }
    var filterExpanded     by remember { mutableStateOf(false) }

    var showAddDialog  by remember { mutableStateOf(false) }
    var unitToDelete   by remember { mutableStateOf<ProjectUnit?>(null) }
    var unitToEdit     by remember { mutableStateOf<ProjectUnit?>(null) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getUnits(projectId,
            onSuccess = { maps -> units = maps.map { ProjectUnit.fromMap(it) }; isLoading = false },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }
    LaunchedEffect(Unit) { load() }

    // Derived filter options from data
    val floors = remember(units) { listOf("All") + units.map { it.floor }.filter { it.isNotBlank() }.distinct().sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })) }
    val types  = remember(units) { listOf("All") + units.map { it.type }.filter { it.isNotBlank() }.distinct().sorted() }

    // Apply search + all active filters
    val displayed = remember(units, searchQuery, filterAvailability, filterFloor, filterType, filterOwner) {
        val q = searchQuery.trim().lowercase()
        units.filter { u ->
            (q.isEmpty() || u.unitNumber.lowercase().contains(q) ||
                u.floor.lowercase().contains(q) ||
                u.type.lowercase().contains(q) ||
                u.status.lowercase().contains(q)) &&
            (filterAvailability == "All" || u.availability == filterAvailability) &&
            (filterFloor        == "All" || u.floor        == filterFloor) &&
            (filterType         == "All" || u.type         == filterType) &&
            (filterOwner        == "All" || u.owner        == filterOwner)
        }
    }

    // Summary
    val total          = units.size
    val available      = units.count { it.availability == "Available" }
    val blocked        = units.count { it.availability == "Blocked" }
    val sold           = units.count { it.availability == "Sold" }
    val builderUnits   = if (isJointDevelopment) units.count { it.owner == "Builder" }   else 0
    val landOwnerUnits = if (isJointDevelopment) units.count { it.owner == "LandOwner" } else 0

    // ── Excel picker ──────────────────────────────────────────────────────────
    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploadStatus = "Uploading…"
        DatabaseManager.uploadUnitsExcel(context, projectId, uri,
            onSuccess = { count -> uploadStatus = "✓ $count units imported"; load() },
            onFailure = { e   -> uploadStatus = "Upload failed: ${e.message}" }
        )
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
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
            canRevert = user?.role == UserRole.ADMIN,
            onDismiss = { unitToEdit = null },
            onConfirm = { unitData, customerData, revertReason ->
                unitToEdit = null

                // ── Case 1: Admin reverts Sold → Available ────────────────────
                if (revertReason != null) {
                    DatabaseManager.revertUnitToAvailable(
                        projectId  = projectId,
                        unitId     = u.unitId,
                        reason     = revertReason,
                        revertedBy = user?.id ?: "",
                        onSuccess  = { _, collected ->
                            val msg = if (collected > 0)
                                "Unit reverted. ₹${"%,.0f".format(collected)} moved to Suspense."
                            else "Unit reverted to Available."
                            uploadStatus = msg
                            load()
                        },
                        onFailure  = { e -> errorMsg = "Revert failed: ${e.message}" }
                    )
                    return@UnitAvailabilityDialog
                }

                val saleDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())

                fun doUpdateUnit() {
                    DatabaseManager.updateUnit(projectId, u.unitId, unitData,
                        onSuccess = { load() },
                        onFailure = { e -> errorMsg = e.message ?: "Update failed" }
                    )
                }

                fun doCreateCollection(customerId: String) {
                    val cData = mapOf(
                        "projectId"    to projectId,
                        "unitId"       to u.unitId,
                        "unitNumber"   to u.unitNumber,
                        "floor"        to u.floor,
                        "unitType"     to u.type,
                        "sba"          to u.sba,
                        "customerName" to (customerData?.get("name")?.toString() ?: ""),
                        "customerPhone" to (customerData?.get("phone")?.toString() ?: ""),
                        "perSftPrice"  to (customerData?.get("perSftPrice")?.toString() ?: "0"),
                        "gstPercentage" to (customerData?.get("gstPercentage")?.toString() ?: "0"),
                        "baseAmount"   to (customerData?.get("_baseAmount")?.toString() ?: "0"),
                        "gstAmount"    to (customerData?.get("_gstAmount")?.toString() ?: "0"),
                        "totalAmount"  to (customerData?.get("_totalAmount")?.toString() ?: "0"),
                        "saleDate"     to saleDate,
                        "soldBy"       to (user?.id ?: ""),
                        "notes"        to ""
                    )
                    DatabaseManager.addCollection(cData,
                        onSuccess = { _ -> },
                        onFailure = { _ -> }
                    )
                }

                // ── Case 2: New sale (Sold + customer data) ───────────────────
                if (customerData != null) {
                    val cData = customerData + mapOf(
                        "unitId"    to u.unitId,
                        "projectId" to projectId,
                        "createdBy" to (user?.id ?: "")
                    )
                    DatabaseManager.addCustomer(cData,
                        onSuccess = { customerId ->
                            doCreateCollection(customerId)
                            doUpdateUnit()
                        },
                        onFailure = { e -> errorMsg = "Customer save failed: ${e.message}" }
                    )
                } else {
                    // ── Case 3: Simple availability change ────────────────────
                    doUpdateUnit()
                }
            }
        )
    }
    if (showAddDialog) {
        AddUnitDialog(
            isJointDevelopment = isJointDevelopment,
            onDismiss = { showAddDialog = false },
            onConfirm = { data ->
                showAddDialog = false
                DatabaseManager.addUnit(projectId, data + mapOf("unitId" to UUID.randomUUID().toString()),
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (canWrite) {
                        IconButton(onClick = {
                            excelPicker.launch(arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel", "application/octet-stream"
                            ))
                        }) { Icon(Icons.Filled.UploadFile, "Import Excel") }
                    }
                    IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            if (canWrite) FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, "Add Unit")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Upload / error banners ─────────────────────────────────────
            if (uploadStatus.isNotEmpty()) {
                LaunchedEffect(uploadStatus) { kotlinx.coroutines.delay(4000); uploadStatus = "" }
                Surface(modifier = Modifier.fillMaxWidth().padding(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(uploadStatus, modifier = Modifier.padding(10.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                }
            }
            if (errorMsg.isNotEmpty()) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp)
            }

            when {
                isLoading -> Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    // ── Search bar (compact) ──────────────────────────────
                    val focusManager = LocalFocusManager.current
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search unit, floor, type…", fontSize = 12.sp) },
                        leadingIcon  = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(horizontal = 10.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    // ── Summary bar ───────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Availability row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SummaryBadge("$total Total",         MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
                            SummaryBadge("$available Available", Color(0xFF2E7D32), Color(0xFFE8F5E9))
                            SummaryBadge("$blocked Blocked",     Color(0xFFE65100), Color(0xFFFFF3E0))
                            SummaryBadge("$sold Sold",           Color(0xFFC62828), Color(0xFFFFEBEE))
                        }
                        // Share row (only for Joint Development)
                        if (isJointDevelopment) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Share:", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold)
                                SummaryBadge("🏗 Builder: $builderUnits",       Color(0xFF1565C0), Color(0xFFE3F2FD))
                                SummaryBadge("🤝 Land Owner: $landOwnerUnits",  Color(0xFF6A1B9A), Color(0xFFF3E5F5))
                            }
                        }
                    }

                    // ── Filter row (horizontal scroll) ────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Availability dropdown
                        AvailabilityDropdown(filterAvailability) { filterAvailability = it }

                        if (floors.size > 1) {
                            VerticalDivider(modifier = Modifier.height(28.dp))
                            FloorDropdown(floors, filterFloor) { filterFloor = it }
                        }
                        if (types.size > 1) {
                            TypeDropdown(types, filterType) { filterType = it }
                        }
                        // Share dropdown (Joint Development only)
                        if (isJointDevelopment) {
                            VerticalDivider(modifier = Modifier.height(28.dp))
                            ShareDropdown(filterOwner) { filterOwner = it }
                        }
                        // Clear all filters
                        if (filterAvailability != "All" || filterFloor != "All" || filterType != "All" || filterOwner != "All" || searchQuery.isNotEmpty()) {
                            VerticalDivider(modifier = Modifier.height(28.dp))
                            AssistChip(
                                onClick = { filterAvailability = "All"; filterFloor = "All"; filterType = "All"; filterOwner = "All"; searchQuery = "" },
                                label   = { Text("Clear", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(14.dp)) }
                            )
                        }
                    }
                    HorizontalDivider()

                    // ── Column headers ────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Unit",   fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                        Text("Floor",  fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                        Text("Type",   fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("SBA",    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
                        Text("Status", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(80.dp))
                    }
                    HorizontalDivider()

                    // ── Unit rows ─────────────────────────────────────────
                    if (displayed.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.FilterAltOff, null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("No units match the current filters",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(displayed, key = { it.unitId }) { unit ->
                                UnitRow(
                                    unit        = unit,
                                    isJD        = isJointDevelopment,
                                    canWrite    = canWrite,
                                    onEdit      = { unitToEdit   = unit },
                                    onDelete    = { unitToDelete = unit }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Compact unit row ──────────────────────────────────────────────────────────

@Composable
private fun UnitRow(
    unit: ProjectUnit,
    isJD: Boolean,
    canWrite: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCustomer: (() -> Unit)? = null
) {
    val fg = availColor(unit.availability)
    val bg = availBgColor(unit.availability)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Unit number
        Text(unit.unitNumber, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(56.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Floor
        if (unit.floor.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.width(40.dp)
            ) {
                Text(unit.floor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    maxLines = 1)
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(44.dp))
        }
        // Type
        Text(unit.type.ifBlank { "—" }, fontSize = 12.sp, modifier = Modifier.weight(1f),
            color = if (unit.type.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        // SBA
        val sbaDisplay = unit.sba.toDoubleOrNull()
            ?.let { if (it == 0.0) "" else "${it.toInt()} sqft" } ?: unit.sba
        Text(sbaDisplay, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp), maxLines = 1)
        // Availability badge
        Surface(shape = RoundedCornerShape(6.dp), color = bg,
            modifier = Modifier.width(76.dp)) {
            Text(unit.availability, fontSize = 10.sp, color = fg, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                maxLines = 1)
        }
        // Actions
        if (canWrite || onCustomer != null) {
            Spacer(Modifier.width(4.dp))
            if (onCustomer != null) {
                IconButton(onClick = onCustomer, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Person, null, modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (canWrite) {
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.DeleteOutline, null, modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    // JD owner tag shown below on a second micro-line if needed
    if (isJD) {
        Text("  ${unit.owner}  •  ${unit.status}", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
    } else if (unit.status.isNotBlank()) {
        Text("  ${unit.status}", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
    }
}

// ── Summary badge ─────────────────────────────────────────────────────────────

@Composable
private fun SummaryBadge(label: String, textColor: Color, bgColor: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = bgColor) {
        Text(label, fontSize = 11.sp, color = textColor, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

// ── Filter dropdowns ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvailabilityDropdown(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("All", "Available", "Blocked", "Sold")
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        FilterChip(
            selected = selected != "All",
            onClick  = { expanded = true },
            label    = { Text(if (selected == "All") "Availability ▾" else selected, fontSize = 12.sp) },
            leadingIcon = if (selected != "All") {{ Icon(Icons.Filled.Check, null, Modifier.size(14.dp)) }} else null,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text    = { Text(if (opt == "All") "All Availability" else opt) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareDropdown(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("All", "Builder", "LandOwner")
    var expanded by remember { mutableStateOf(false) }
    val label = when (selected) {
        "Builder"   -> "🏗 Builder Share"
        "LandOwner" -> "🤝 Land Owner Share"
        else        -> "Share ▾"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        FilterChip(
            selected = selected != "All",
            onClick  = { expanded = true },
            label    = { Text(label, fontSize = 12.sp) },
            leadingIcon = if (selected != "All") {{ Icon(Icons.Filled.Check, null, Modifier.size(14.dp)) }} else null,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All Shares") },           onClick = { onSelect("All");       expanded = false })
            DropdownMenuItem(text = { Text("🏗 Builder Share") },     onClick = { onSelect("Builder");   expanded = false })
            DropdownMenuItem(text = { Text("🤝 Land Owner Share") },  onClick = { onSelect("LandOwner"); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloorDropdown(floors: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        FilterChip(
            selected = selected != "All",
            onClick  = { expanded = true },
            label    = { Text(if (selected == "All") "Floor ▾" else "Floor: $selected", fontSize = 12.sp) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            floors.forEach { f ->
                DropdownMenuItem(
                    text    = { Text(if (f == "All") "All Floors" else "Floor $f") },
                    onClick = { onSelect(f); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(types: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        FilterChip(
            selected = selected != "All",
            onClick  = { expanded = true },
            label    = { Text(if (selected == "All") "Type ▾" else selected, fontSize = 12.sp) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            types.forEach { t ->
                DropdownMenuItem(
                    text    = { Text(if (t == "All") "All Types" else t) },
                    onClick = { onSelect(t); expanded = false }
                )
            }
        }
    }
}

// ── Availability update dialog ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitAvailabilityDialog(
    unit: ProjectUnit,
    canRevert: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (unitData: Map<String, Any>, customerData: Map<String, Any>?, revertReason: String?) -> Unit
) {
    var availability   by remember { mutableStateOf(unit.availability) }
    var status         by remember { mutableStateOf(unit.status) }
    var statusExpanded by remember { mutableStateOf(false) }

    // Revert case: Admin changes Sold → Available
    val isRevertToAvailable = availability == "Available" && unit.availability == "Sold"
    var revertReason by remember { mutableStateOf("") }

    // Customer fields — shown & required when newly marking as Sold
    val isNewlySold = availability == "Sold" && unit.availability != "Sold"
    var custName     by remember { mutableStateOf("") }
    var custPhone    by remember { mutableStateOf("") }
    var custEmail    by remember { mutableStateOf("") }
    var custAddress  by remember { mutableStateOf("") }
    var custPerSft   by remember { mutableStateOf("") }
    var custGst      by remember { mutableStateOf("0") }

    val sbaNum    = unit.sba.toDoubleOrNull() ?: 0.0
    val perSftNum = custPerSft.toDoubleOrNull() ?: 0.0
    val gstNum    = custGst.toDoubleOrNull() ?: 0.0
    val base      = perSftNum * sbaNum
    val gstAmt    = base * gstNum / 100
    val totalCost = base + gstAmt

    val canSave = when {
        isRevertToAvailable -> canRevert && revertReason.isNotBlank()
        isNewlySold         -> custName.isNotBlank() && custPhone.isNotBlank()
        else                -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Unit ${unit.unitNumber}") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 580.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Availability quick-tap
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectUnit.AVAILABILITIES.forEach { a ->
                        // Disable "Available" tap for non-admin when unit is Sold
                        val isRevertOption = a == "Available" && unit.availability == "Sold"
                        val isDisabled     = isRevertOption && !canRevert
                        val fg = when {
                            isDisabled       -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            availability == a -> Color.White
                            else             -> availColor(a)
                        }
                        val bg = when {
                            isDisabled       -> MaterialTheme.colorScheme.surfaceVariant
                            availability == a -> availColor(a)
                            else             -> availBgColor(a)
                        }
                        Surface(
                            shape    = RoundedCornerShape(8.dp),
                            color    = bg,
                            onClick  = { if (!isDisabled) availability = a },
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(a, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center)
                                if (isRevertOption && canRevert) {
                                    Text("Admin", fontSize = 9.sp, color = fg.copy(alpha = 0.8f))
                                }
                            }
                        }
                    }
                }

                ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                    OutlinedTextField(
                        value = status, onValueChange = {}, readOnly = true,
                        label = { Text("Construction Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                        ProjectUnit.STATUSES.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusExpanded = false })
                        }
                    }
                }

                // ── REVERT SECTION (Sold → Available, Admin only) ─────────────
                if (isRevertToAvailable) {
                    HorizontalDivider()
                    // Warning banner
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.Warning, null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp))
                                Text("Admin Action: Revert to Available",
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Text(
                                "This will:\n" +
                                "• Mark this unit as Available again\n" +
                                "• Archive the current sale record\n" +
                                "• Move any collected payments to the Suspense Account\n" +
                                "• The customer record is retained for audit history",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    OutlinedTextField(
                        value = revertReason,
                        onValueChange = { revertReason = it },
                        label = { Text("Reason for Reverting *") },
                        placeholder = { Text("e.g. Customer cancelled, deal fell through…") },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── NEW SALE SECTION (→ Sold) ─────────────────────────────────
                if (isNewlySold) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Person, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Customer Details", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("⚠ Required — unit cannot be sold without customer details",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }

                    OutlinedTextField(value = custName, onValueChange = { custName = it },
                        label = { Text("Customer Name *") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = custPhone, onValueChange = { custPhone = it },
                        label = { Text("Phone Number *") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        supportingText = {
                            Text("📱 Login key — default password = phone number", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = custEmail, onValueChange = { custEmail = it },
                        label = { Text("Email (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = custAddress, onValueChange = { custAddress = it },
                        label = { Text("Address") },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))

                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.CurrencyRupee, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Pricing", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("SBA: ${if (sbaNum > 0) "${sbaNum.toInt()} sqft" else "not set — add in unit details"}",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = custPerSft, onValueChange = { custPerSft = it },
                            label = { Text("Rate per SFT (₹) *") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = custGst, onValueChange = { custGst = it },
                            label = { Text("GST (%)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    if (perSftNum > 0) {
                        Surface(shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("💰 Sale Summary", fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Base (₹${"%,.0f".format(perSftNum)} × ${sbaNum.toInt()} sqft)", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("₹ ${"%,.2f".format(base)}", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                if (gstNum > 0) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("GST (${"%.1f".format(gstNum)}%)", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("+ ₹ ${"%,.2f".format(gstAmt)}", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Total", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("₹ ${"%,.2f".format(totalCost)}", fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Info, null,
                                tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                            Text("Customer portal: default password = phone number. They'll be asked to change it on first login.",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val unitData = mapOf("availability" to availability, "status" to status)
                    when {
                        isRevertToAvailable -> onConfirm(unitData, null, revertReason.trim())
                        isNewlySold -> {
                            val customerData = mapOf(
                                "name"          to custName.trim(),
                                "phone"         to custPhone.trim(),
                                "contactEmail"  to custEmail.trim(),
                                "loginEmail"    to custEmail.trim().lowercase(),
                                "address"       to custAddress.trim(),
                                "perSftPrice"   to custPerSft.trim().ifBlank { "0" },
                                "gstPercentage" to custGst.trim().ifBlank { "0" },
                                "totalCost"     to totalCost.toString(),
                                "password"      to "",
                                "_baseAmount"   to base.toString(),
                                "_gstAmount"    to gstAmt.toString(),
                                "_totalAmount"  to totalCost.toString()
                            )
                            onConfirm(unitData, customerData, null)
                        }
                        else -> onConfirm(unitData, null, null)
                    }
                }
            ) {
                Text(when {
                    isRevertToAvailable -> "Revert Unit"
                    else -> "Save"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
    var availability   by remember { mutableStateOf(unit.availability) }
    var status         by remember { mutableStateOf(unit.status) }
    var statusExpanded by remember { mutableStateOf(false) }

    // Customer fields — shown & required when newly marking as Sold
    val isNewlySold = availability == "Sold" && unit.availability != "Sold"
    var custName     by remember { mutableStateOf("") }
    var custPhone    by remember { mutableStateOf("") }
    var custEmail    by remember { mutableStateOf("") }
    var custAddress  by remember { mutableStateOf("") }
    var custPerSft   by remember { mutableStateOf("") }
    var custGst      by remember { mutableStateOf("0") }

    // Auto-calculated pricing
    val sbaNum    = unit.sba.toDoubleOrNull() ?: 0.0
    val perSftNum = custPerSft.toDoubleOrNull() ?: 0.0
    val gstNum    = custGst.toDoubleOrNull() ?: 0.0
    val base      = perSftNum * sbaNum
    val gstAmt    = base * gstNum / 100
    val totalCost = base + gstAmt

    val canSave = !isNewlySold || (custName.isNotBlank() && custPhone.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Unit ${unit.unitNumber}") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Availability quick-tap
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectUnit.AVAILABILITIES.forEach { a ->
                        val fg = if (availability == a) Color.White else availColor(a)
                        val bg = if (availability == a) availColor(a) else availBgColor(a)
                        Surface(
                            shape    = RoundedCornerShape(8.dp),
                            color    = bg,
                            onClick  = { availability = a },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(a, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center)
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                    OutlinedTextField(
                        value = status, onValueChange = {}, readOnly = true,
                        label = { Text("Construction Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                        ProjectUnit.STATUSES.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusExpanded = false })
                        }
                    }
                }

                // ── Customer + Pricing details (required when marking as Sold) ──
                if (isNewlySold) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Person, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Customer Details", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("⚠ Required — unit cannot be sold without customer details",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }

                    OutlinedTextField(value = custName, onValueChange = { custName = it },
                        label = { Text("Customer Name *") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = custPhone, onValueChange = { custPhone = it },
                        label = { Text("Phone Number *") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        supportingText = {
                            Text("📱 Login key — default password = phone number", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = custEmail, onValueChange = { custEmail = it },
                        label = { Text("Email (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = custAddress, onValueChange = { custAddress = it },
                        label = { Text("Address") },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))

                    // ── Pricing ──────────────────────────────────────────────
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.CurrencyRupee, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Pricing", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }

                    // SBA info chip
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("SBA: ${if (sbaNum > 0) "${sbaNum.toInt()} sqft" else "not set — add in unit details"}",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = custPerSft, onValueChange = { custPerSft = it },
                            label = { Text("Rate per SFT (₹) *") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = custGst, onValueChange = { custGst = it },
                            label = { Text("GST (%)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }

                    // Auto-calculated total preview
                    if (perSftNum > 0) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("💰 Sale Summary", fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Base (₹${"%,.0f".format(perSftNum)} × ${sbaNum.toInt()} sqft)", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("₹ ${"%,.2f".format(base)}", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                if (gstNum > 0) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("GST (${"%.1f".format(gstNum)}%)", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("+ ₹ ${"%,.2f".format(gstAmt)}", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Total", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("₹ ${"%,.2f".format(totalCost)}", fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }

                    // Portal access info
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Info, null,
                                tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                            Text("Customer portal: default password = phone number. They'll be asked to change it on first login.",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val unitData = mapOf("availability" to availability, "status" to status)
                    val customerData = if (isNewlySold) mapOf(
                        "name"          to custName.trim(),
                        "phone"         to custPhone.trim(),
                        "contactEmail"  to custEmail.trim(),
                        "loginEmail"    to custEmail.trim().lowercase(),
                        "address"       to custAddress.trim(),
                        "perSftPrice"   to custPerSft.trim().ifBlank { "0" },
                        "gstPercentage" to custGst.trim().ifBlank { "0" },
                        "totalCost"     to totalCost.toString(),
                        "password"      to "",   // auto-set = phone on backend
                        // Pricing for collection
                        "_baseAmount"   to base.toString(),
                        "_gstAmount"    to gstAmt.toString(),
                        "_totalAmount"  to totalCost.toString()
                    ) else null
                    onConfirm(unitData, customerData)
                }
            ) { Text("Save") }
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
                        label = { Text("Type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        ProjectUnit.TYPES.forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { type = t; typeExpanded = false }) }
                    }
                }
                OutlinedTextField(value = sba, onValueChange = { sba = it },
                    label = { Text("SBA (sqft)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // Availability quick-tap
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectUnit.AVAILABILITIES.forEach { a ->
                        val fg = if (availability == a) Color.White else availColor(a)
                        val bg = if (availability == a) availColor(a) else availBgColor(a)
                        Surface(shape = RoundedCornerShape(8.dp), color = bg,
                            onClick = { availability = a }, modifier = Modifier.weight(1f)) {
                            Text(a, fontSize = 11.sp, color = fg, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center)
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                    OutlinedTextField(value = status, onValueChange = {}, readOnly = true,
                        label = { Text("Construction Status") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                        ProjectUnit.STATUSES.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusExpanded = false }) }
                    }
                }
                if (isJointDevelopment) {
                    ExposedDropdownMenuBox(expanded = ownerExpanded, onExpandedChange = { ownerExpanded = !ownerExpanded }) {
                        OutlinedTextField(value = owner, onValueChange = {}, readOnly = true,
                            label = { Text("Owner") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ownerExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                        ExposedDropdownMenu(expanded = ownerExpanded, onDismissRequest = { ownerExpanded = false }) {
                            ProjectUnit.OWNERS.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { owner = o; ownerExpanded = false }) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (unitNumber.isNotBlank()) onConfirm(mapOf(
                        "unitNumber" to unitNumber.trim(), "floor" to floor.trim(),
                        "type" to type, "sba" to sba.trim(), "status" to status,
                        "availability" to availability, "owner" to owner
                    ))
                },
                enabled = unitNumber.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

