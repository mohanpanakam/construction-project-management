package com.panakam.construction.ui.screens.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailScreen(
    unitId: String,
    unitNumber: String,
    floor: String,
    unitType: String,
    sba: String,
    projectId: String,
    onBack: () -> Unit,
    onViewPayments: (customerId: String, customerName: String) -> Unit
) {
    val currentUser = AuthManager.getCurrentUser()
    val canWrite    = currentUser?.role == UserRole.ADMIN || currentUser?.role == UserRole.PROJECT_MANAGER
    val isCustomer  = currentUser?.role == UserRole.CUSTOMER

    var customer   by remember { mutableStateOf<Map<String, Any>?>(null) }
    var isLoading  by remember { mutableStateOf(true) }
    var errorMsg   by remember { mutableStateOf("") }
    var showForm   by remember { mutableStateOf(false) }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getCustomerForUnit(unitId,
            onSuccess = { c -> customer = c; isLoading = false; showForm = c == null && canWrite },
            onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
        )
    }
    LaunchedEffect(Unit) { load() }

    // Customer form dialog
    if (showForm) {
        CustomerFormDialog(
            existing   = customer,
            unitId     = unitId,
            unitNumber = unitNumber,
            floor      = floor,
            unitType   = unitType,
            sba        = sba,
            projectId  = projectId,
            createdBy  = currentUser?.id ?: "",
            onDismiss  = { showForm = false; if (customer == null) onBack() },
            onSaved    = { load(); showForm = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Customer Details", fontWeight = FontWeight.Bold)
                        Text("Unit $unitNumber · Floor $floor", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (canWrite && customer != null)
                        IconButton(onClick = { showForm = true }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
                customer == null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.PersonOff, null, modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("No customer assigned yet", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (canWrite) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showForm = true }) { Text("Add Customer") }
                    }
                }
                else -> {
                    val c = customer!!
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        // ── Unit info card ──────────────────────────────────
                        InfoCard("Unit Information") {
                            InfoRow("Unit",  unitNumber)
                            InfoRow("Floor", floor)
                            InfoRow("Type",  unitType)
                            InfoRow("SBA",   "${sba.toDoubleOrNull()?.toInt() ?: sba} sq.ft")
                        }

                        // ── Customer info card ──────────────────────────────
                        InfoCard("Customer Information") {
                            InfoRow("Name",    c["name"].toString())
                            if (c["phone"].toString().isNotBlank()) InfoRow("Phone", c["phone"].toString())
                            if (c["contactEmail"].toString().isNotBlank()) InfoRow("Email", c["contactEmail"].toString())
                            if (c["address"].toString().isNotBlank()) InfoRow("Address", c["address"].toString())
                        }

                        // ── Pricing card ────────────────────────────────────
                        val perSft = c["perSftPrice"].toString().toDoubleOrNull() ?: 0.0
                        val gst    = c["gstPercentage"].toString().toDoubleOrNull() ?: 0.0
                        val total  = c["totalCost"].toString().toDoubleOrNull() ?: 0.0
                        val sbaNum = sba.toDoubleOrNull() ?: 0.0
                        val baseAmount = perSft * sbaNum
                        val gstAmount  = baseAmount * gst / 100

                        InfoCard("Pricing Details") {
                            InfoRow("Per sq.ft Price", "₹ ${"%,.2f".format(perSft)}")
                            InfoRow("SBA",             "${sbaNum.toInt()} sq.ft")
                            InfoRow("Base Amount",     "₹ ${"%,.2f".format(baseAmount)}")
                            if (gst > 0) {
                                InfoRow("GST (${"%.1f".format(gst)}%)", "+ ₹ ${"%,.2f".format(gstAmount)}")
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            InfoRow("Total Cost", "₹ ${"%,.2f".format(if (total > 0) total else baseAmount + gstAmount)}",
                                valueWeight = FontWeight.Bold)
                        }

                        // ── Portal access ───────────────────────────────────
                        val hasPortal = c["hasPortalAccess"] == "true"
                        InfoCard("Portal Access") {
                            InfoRow("Customer Portal",
                                if (hasPortal) "✅ Active (${c["loginEmail"]})" else "❌ Not set up")
                        }

                        // ── Payments button ─────────────────────────────────
                        Button(
                            onClick = { onViewPayments(c["customerId"].toString(), c["name"].toString()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Payments, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("View / Add Payments")
                        }

                        if (c["notes"].toString().isNotBlank()) {
                            InfoCard("Notes") { Text(c["notes"].toString(), fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}

// ── Customer form dialog ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerFormDialog(
    existing: Map<String, Any>?,
    unitId: String, unitNumber: String, floor: String, unitType: String, sba: String,
    projectId: String, createdBy: String,
    onDismiss: () -> Unit, onSaved: () -> Unit
) {
    var name         by remember { mutableStateOf(existing?.get("name")?.toString() ?: "") }
    var phone        by remember { mutableStateOf(existing?.get("phone")?.toString() ?: "") }
    var contactEmail by remember { mutableStateOf(existing?.get("contactEmail")?.toString() ?: "") }
    var address      by remember { mutableStateOf(existing?.get("address")?.toString() ?: "") }
    var loginEmail   by remember { mutableStateOf(existing?.get("loginEmail")?.toString() ?: "") }
    var perSft       by remember { mutableStateOf(existing?.get("perSftPrice")?.toString() ?: "") }
    var gst          by remember { mutableStateOf(existing?.get("gstPercentage")?.toString() ?: "0") }
    var notes        by remember { mutableStateOf(existing?.get("notes")?.toString() ?: "") }
    var saving       by remember { mutableStateOf(false) }
    var errorMsg     by remember { mutableStateOf("") }

    // Auto-calculate total cost
    val perSftNum = perSft.toDoubleOrNull() ?: 0.0
    val gstNum    = gst.toDoubleOrNull() ?: 0.0
    val sbaNum    = sba.toDoubleOrNull() ?: 0.0
    val base      = perSftNum * sbaNum
    val totalCost = base + base * gstNum / 100

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    if (existing == null) "Add Customer – Unit $unitNumber" else "Edit Customer",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                if (errorMsg.isNotBlank())
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

                Text("Personal Details", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Full Name *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())

                // Phone — PRIMARY login key, shown prominently
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone Number *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    supportingText = {
                        Text("📱 Used for customer portal login — must be unique", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                )

                OutlinedTextField(value = contactEmail, onValueChange = { contactEmail = it },
                    label = { Text("Contact Email") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))

                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address") },
                    minLines = 3, maxLines = 5,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
                )

                HorizontalDivider()
                Text("Pricing", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = perSft, onValueChange = { perSft = it },
                        label = { Text("Per sq.ft (₹)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = gst, onValueChange = { gst = it },
                        label = { Text("GST %") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                if (perSftNum > 0)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Base: ₹ ${"%,.2f".format(base)}", fontSize = 12.sp)
                            if (gstNum > 0) Text("GST (${"%.1f".format(gstNum)}%): ₹ ${"%,.2f".format(base * gstNum / 100)}", fontSize = 12.sp)
                            Text("Total: ₹ ${"%,.2f".format(totalCost)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                HorizontalDivider()
                Text("Customer Portal Access", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary)

                // Info banner: phone is the login key; password defaults to phone
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🔑 Login: ${if (phone.isNotBlank()) phone else "phone number (enter above)"}",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("🔒 Default password = phone number",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Customer will be prompted to change the password on first login.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                // Optional: email login as fallback
                OutlinedTextField(value = loginEmail, onValueChange = { loginEmail = it },
                    label = { Text("Portal Email (optional — fallback login)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))

                OutlinedTextField(value = notes, onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 2, maxLines = 3,
                    modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { if (!saving) onDismiss() }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = name.isNotBlank() && !saving,
                        onClick = {
                            saving = true; errorMsg = ""
                            val data = mutableMapOf<String, Any>(
                                "projectId"    to projectId, "unitId" to unitId,
                                "name"         to name.trim(), "address" to address.trim(),
                                "phone"        to phone.trim(), "contactEmail" to contactEmail.trim(),
                                "loginEmail"   to loginEmail.trim(), "password" to "",
                                "perSftPrice"  to (perSft.trim().ifBlank { "0" }),
                                "gstPercentage" to (gst.trim().ifBlank { "0" }),
                                "totalCost"    to totalCost.toString(),
                                "notes"        to notes.trim(), "createdBy" to createdBy
                            )
                            if (existing != null) data["updatedBy"] = createdBy
                            val customerId = existing?.get("customerId")?.toString() ?: ""

                            if (existing == null) {
                                DatabaseManager.addCustomer(data,
                                    onSuccess = { saving = false; onSaved() },
                                    onFailure = { e -> saving = false; errorMsg = e.message ?: "Save failed" })
                            } else {
                                DatabaseManager.updateCustomer(customerId, data,
                                    onSuccess = { saving = false; onSaved() },
                                    onFailure = { e -> saving = false; errorMsg = e.message ?: "Save failed" })
                            }
                        }
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    }
}

// ── Small reusable composables ────────────────────────────────────────────────

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueWeight: FontWeight = FontWeight.Normal) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = valueWeight,
            modifier = Modifier.weight(1.5f))
    }
}

