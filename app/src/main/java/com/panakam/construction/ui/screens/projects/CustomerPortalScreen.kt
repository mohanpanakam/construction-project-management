package com.panakam.construction.ui.screens.projects

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.database.DatabaseManager

/**
 * Customer-facing portal screen.
 * Shows all units purchased by the logged-in customer (identified by phone number).
 * If the customer logged in by email/single-unit, falls back to showing only that unit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerPortalScreen(
    onBack: () -> Unit,
    onViewUnit: (projectId: String, unitId: String, unitNumber: String,
                 floor: String, type: String, sba: String) -> Unit
) {
    val user = AuthManager.getCurrentUser() ?: return

    var units     by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }

    fun load() {
        isLoading = true; errorMsg = ""
        if (user.phone.isNotBlank()) {
            // Multi-unit: fetch all units by phone
            DatabaseManager.getCustomersByPhone(user.phone,
                onSuccess = { list -> units = list; isLoading = false },
                onFailure = { e   -> errorMsg = e.message ?: "Load failed"; isLoading = false }
            )
        } else {
            // Fallback: single unit from session — fetch real unit + pricing data
            if (user.unitId.isNotBlank() && user.projectId.isNotBlank()) {
                DatabaseManager.getUnitById(user.projectId, user.unitId,
                    onSuccess = { unit ->
                        DatabaseManager.getCustomerForUnit(user.unitId,
                            onSuccess = { customer ->
                                units = listOf(mapOf(
                                    "customerId"    to user.customerId,
                                    "projectId"     to user.projectId,
                                    "unitId"        to user.unitId,
                                    "unitNumber"    to (unit["unitNumber"]?.toString() ?: "—"),
                                    "floor"         to (unit["floor"]?.toString() ?: ""),
                                    "type"          to (unit["type"]?.toString() ?: ""),
                                    "sba"           to (customer?.get("sba")?.toString() ?: unit["sba"]?.toString() ?: "0"),
                                    "availability"  to (unit["availability"]?.toString() ?: "Sold"),
                                    "totalCost"     to (customer?.get("totalCost")?.toString() ?: "0"),
                                    "totalAmount"   to (customer?.get("totalAmount")?.toString() ?: customer?.get("totalCost")?.toString() ?: "0"),
                                    "paidAmount"    to (customer?.get("paidAmount")?.toString() ?: "0"),
                                    "pendingAmount" to (customer?.get("pendingAmount")?.toString() ?: customer?.get("totalCost")?.toString() ?: "0"),
                                    "paymentStatus" to (customer?.get("paymentStatus")?.toString() ?: "Unpaid"),
                                    "name"          to user.name
                                ))
                                isLoading = false
                            },
                            onFailure = { _ ->
                                units = listOf(mapOf(
                                    "customerId"   to user.customerId,
                                    "projectId"    to user.projectId,
                                    "unitId"       to user.unitId,
                                    "unitNumber"   to (unit["unitNumber"]?.toString() ?: "—"),
                                    "floor"        to (unit["floor"]?.toString() ?: ""),
                                    "type"         to (unit["type"]?.toString() ?: ""),
                                    "sba"          to (unit["sba"]?.toString() ?: "0"),
                                    "availability" to (unit["availability"]?.toString() ?: "Sold"),
                                    "name"         to user.name
                                ))
                                isLoading = false
                            }
                        )
                    },
                    onFailure = { e -> errorMsg = e.message ?: "Load failed"; isLoading = false }
                )
            } else {
                isLoading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My Units", fontWeight = FontWeight.Bold)
                        Text(user.name, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Text(errorMsg, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
                units.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Home, null, modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No units found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Surface(modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium) {
                            Text(
                                "${units.size} unit${if (units.size != 1) "s" else ""} purchased",
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    items(units) { u ->
                        CustomerUnitCard(
                            unit = u,
                            onClick = {
                                onViewUnit(
                                    u["projectId"]?.toString() ?: "",
                                    u["unitId"]?.toString() ?: "",
                                    u["unitNumber"]?.toString() ?: "",
                                    u["floor"]?.toString() ?: "",
                                    u["type"]?.toString() ?: "",
                                    u["sba"]?.toString() ?: "0"
                                )
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CustomerUnitCard(unit: Map<String, Any>, onClick: () -> Unit) {
    val unitNumber   = unit["unitNumber"]?.toString() ?: "—"
    val floor        = unit["floor"]?.toString() ?: ""
    val type         = unit["type"]?.toString() ?: ""
    val sba          = unit["sba"]?.toString()?.toDoubleOrNull()?.let { if (it > 0) "${it.toInt()} sqft" else "" } ?: ""
    val availability = unit["availability"]?.toString() ?: "Sold"
    val totalCost    = (unit["totalAmount"]?.toString() ?: unit["totalCost"]?.toString())?.toDoubleOrNull() ?: 0.0
    val paidAmount   = unit["paidAmount"]?.toString()?.toDoubleOrNull() ?: 0.0
    val pendingAmount = unit["pendingAmount"]?.toString()?.toDoubleOrNull() ?: (totalCost - paidAmount).coerceAtLeast(0.0)
    val paymentStatus = unit["paymentStatus"]?.toString() ?: "Unpaid"
    val progress = if (totalCost > 0) (paidAmount / totalCost).coerceIn(0.0, 1.0).toFloat() else 0f

    val availColor = when (availability) {
        "Available" -> Color(0xFF2E7D32)
        "Blocked"   -> Color(0xFFE65100)
        "Sold"      -> Color(0xFFC62828)
        else        -> Color(0xFF607D8B)
    }
    val availBg = when (availability) {
        "Available" -> Color(0xFFE8F5E9)
        "Blocked"   -> Color(0xFFFFF3E0)
        "Sold"      -> Color(0xFFFFEBEE)
        else        -> Color(0xFFECEFF1)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Apartment, null, modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Unit $unitNumber", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (floor.isNotBlank() || type.isNotBlank()) {
                        Text(listOf(if (floor.isNotBlank()) "Floor $floor" else null, type, sba)
                            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = availBg) {
                        Text(availability, fontSize = 10.sp, color = availColor, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }

            if (totalCost > 0) {
                HorizontalDivider()
                Surface(shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Cost", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                            Text("₹ ${"%,.2f".format(totalCost)}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Paid", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                Text("₹ ${"%,.2f".format(paidAmount)}", fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("To Be Paid", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                Text("₹ ${"%,.2f".format(pendingAmount)}", fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (pendingAmount > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32))
                            }
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            color = if (progress >= 1f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                        Text(paymentStatus, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

