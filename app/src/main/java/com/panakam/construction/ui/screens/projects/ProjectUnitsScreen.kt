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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
                // Quick-tap availability
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectUnit.AVAILABILITIES.forEach { a ->
                        val fg = if (availability == a) Color.White else availColor(a)
                        val bg = if (availability == a) availColor(a) else availBgColor(a)
                        Surface(
                            shape   = RoundedCornerShape(8.dp),
                            color   = bg,
                            onClick = { availability = a },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(a, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mapOf("availability" to availability, "status" to status)) }) { Text("Save") }
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
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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

