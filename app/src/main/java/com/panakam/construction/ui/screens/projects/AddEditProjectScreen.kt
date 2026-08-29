package com.panakam.construction.ui.screens.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.panakam.construction.data.LocalProjectStorage
import com.panakam.construction.database.DatabaseManager
import com.panakam.construction.model.Project
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditProjectScreen(
    existingProject: Project? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val isEdit = existingProject != null

    var name        by remember { mutableStateOf(existingProject?.name        ?: "") }
    var location    by remember { mutableStateOf(existingProject?.location    ?: "") }
    var status      by remember { mutableStateOf(existingProject?.status      ?: "Planning") }
    var startDate   by remember { mutableStateOf(existingProject?.startDate   ?: "") }
    var endDate     by remember { mutableStateOf(existingProject?.endDate     ?: "") }
    var budget      by remember { mutableStateOf(existingProject?.budget      ?: "") }
    var description by remember { mutableStateOf(existingProject?.description ?: "") }
    var mapLocation by remember { mutableStateOf(existingProject?.mapLocation ?: "") }
    var partnerName by remember { mutableStateOf(existingProject?.partnerName ?: "") }
    var partnerPhone by remember { mutableStateOf(existingProject?.partnerPhone ?: "") }
    var partnerEmail by remember { mutableStateOf(existingProject?.partnerEmail ?: "") }

    // Photos – pre-load from local storage if editing
    var photoUris by remember {
        mutableStateOf<List<Uri>>(
            if (isEdit && existingProject != null)
                LocalProjectStorage.getPhotoUris(existingProject.projectId).map { Uri.parse(it) }
            else emptyList<Uri>()
        )
    }

    var statusExpanded by remember { mutableStateOf(false) }
    var errorMsg       by remember { mutableStateOf("") }
    var isLoading      by remember { mutableStateOf(false) }

    // Photo picker (API 33+, no permission needed)
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris -> if (uris.isNotEmpty()) photoUris = (photoUris + uris).takeLast(5) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit Project" else "New Project", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Basic info ─────────────────────────────────────────────────
            SectionHeader("Basic Information")

            OutlinedTextField(value = name, onValueChange = { name = it; errorMsg = "" },
                label = { Text("Project Name *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = location, onValueChange = { location = it },
                label = { Text("Site Address / Location") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.LocationOn, null) },
                modifier = Modifier.fillMaxWidth())

            // Status dropdown
            ExposedDropdownMenuBox(expanded = statusExpanded,
                onExpandedChange = { statusExpanded = !statusExpanded },
                modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = status, onValueChange = {}, readOnly = true,
                    label = { Text("Status") },
                    leadingIcon = { Icon(Icons.Filled.Flag, null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                    for (s in Project.STATUS_OPTIONS) {
                        DropdownMenuItem(text = { Text(text = s) },
                            onClick = { status = s; statusExpanded = false })
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startDate, onValueChange = { startDate = it },
                    label = { Text("Start Date") }, placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = endDate, onValueChange = { endDate = it },
                    label = { Text("End Date") }, placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }

            OutlinedTextField(value = budget, onValueChange = { budget = it },
                label = { Text("Budget") }, placeholder = { Text("e.g. \$500,000") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.AttachMoney, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = description, onValueChange = { description = it },
                label = { Text("Description") }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth())

            // ── Map Location ───────────────────────────────────────────────
            SectionHeader("Map Location")
            OutlinedTextField(value = mapLocation, onValueChange = { mapLocation = it },
                label = { Text("Google Maps Address / Coordinates") },
                placeholder = { Text("e.g. 123 Main St, City  or  12.9716,77.5946") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Map, null) },
                modifier = Modifier.fillMaxWidth())

            // ── Partner Details ────────────────────────────────────────────
            SectionHeader("Partner / Contractor Details")
            OutlinedTextField(value = partnerName, onValueChange = { partnerName = it },
                label = { Text("Partner / Company Name") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Business, null) },
                modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = partnerPhone, onValueChange = { partnerPhone = it },
                    label = { Text("Phone") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Phone, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f))
                OutlinedTextField(value = partnerEmail, onValueChange = { partnerEmail = it },
                    label = { Text("Email") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Email, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.weight(1f))
            }

            // ── Project Photos ─────────────────────────────────────────────
            SectionHeader("Project Photos (max 5)")
            if (photoUris.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photoUris) { uri ->
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.LightGray)
                            )
                            IconButton(
                                onClick = { photoUris = photoUris - uri },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                            ) {
                                Icon(Icons.Filled.Cancel, contentDescription = "Remove",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, null)
                Spacer(Modifier.width(8.dp))
                Text("Add Photos")
            }

            if (errorMsg.isNotEmpty()) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (name.isBlank()) { errorMsg = "Project name is required"; return@Button }
                    isLoading = true
                    val projectId = existingProject?.projectId ?: UUID.randomUUID().toString()
                    val data: Map<String, Any> = mapOf(
                        "name"         to name.trim(),
                        "location"     to location.trim(),
                        "status"       to status,
                        "startDate"    to startDate.trim(),
                        "endDate"      to endDate.trim(),
                        "budget"       to budget.trim(),
                        "description"  to description.trim(),
                        "mapLocation"  to mapLocation.trim(),
                        "partnerName"  to partnerName.trim(),
                        "partnerPhone" to partnerPhone.trim(),
                        "partnerEmail" to partnerEmail.trim()
                    )
                    val onSuccess: () -> Unit = {
                        LocalProjectStorage.savePhotoUris(projectId, photoUris.map { it.toString() })
                        isLoading = false
                        onSaved()
                    }
                    val onFailure: (Exception) -> Unit = { e ->
                        isLoading = false; errorMsg = e.message ?: "Save failed"
                    }
                    if (isEdit) {
                        DatabaseManager.updateProject(projectId, data, onSuccess, onFailure)
                    } else {
                        DatabaseManager.addProject(projectId, data, onSuccess, onFailure)
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text(if (isEdit) "Update Project" else "Save Project", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
}

