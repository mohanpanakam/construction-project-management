package com.panakam.construction.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.panakam.construction.auth.UserRole
import com.panakam.construction.database.DatabaseManager

/**
 * Unified notification inbox. A device is queried with whichever identifiers
 * apply to the logged-in session — a staff account passes its userId, a
 * customer account passes its customerId/phone, and an account that is BOTH
 * (a staff member who also personally bought a unit) passes all of them, so
 * the same list naturally contains both "needs audit" (admin/auditor) and
 * "payment approved/rejected" (customer) alerts side by side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val user = AuthManager.getCurrentUser() ?: return

    var notifications by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading      by remember { mutableStateOf(true) }
    var errorMsg       by remember { mutableStateOf("") }

    // A pure customer's `user.id` IS the customerId (no real staff Users row) —
    // only pass userId for staff roles so it's never mistaken for a customerId.
    val userId     = user.id.takeIf { user.role != UserRole.CUSTOMER }
    val customerId = user.customerId.takeIf { it.isNotBlank() }
    val phone      = user.phone.takeIf { it.isNotBlank() }

    fun load() {
        isLoading = true; errorMsg = ""
        DatabaseManager.getNotifications(
            userId = userId, customerId = customerId, phone = phone,
            onSuccess = { list -> notifications = list; isLoading = false },
            onFailure = { e -> errorMsg = e.message ?: "Failed to load notifications"; isLoading = false }
        )
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (notifications.any { it["isRead"] != "true" }) {
                        TextButton(onClick = {
                            DatabaseManager.markAllNotificationsRead(
                                userId = userId, customerId = customerId, phone = phone,
                                onSuccess = { load() }, onFailure = {}
                            )
                        }) { Text("Mark all read") }
                    }
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
                notifications.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.NotificationsNone, null, modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No notifications yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notifications) { n ->
                        NotificationCard(n, onClick = {
                            if (n["isRead"] != "true") {
                                DatabaseManager.markNotificationRead(
                                    n["notificationId"].toString(),
                                    onSuccess = { load() }, onFailure = {}
                                )
                            }
                        })
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(n: Map<String, Any>, onClick: () -> Unit) {
    val type    = n["type"]?.toString() ?: ""
    val title   = n["title"]?.toString() ?: "Notification"
    val body    = n["body"]?.toString() ?: ""
    val isRead  = n["isRead"]?.toString() == "true"
    val createdAt = n["createdAt"]?.toString()?.toLongOrNull() ?: 0L

    val (icon, tint) = when {
        type.startsWith("PAYMENT_CREATED")  -> Icons.Filled.Payments to Color(0xFF1565C0)
        type.startsWith("PAYMENT_AUDITED")  -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        type.startsWith("PAYMENT_REJECTED") -> Icons.Filled.Cancel to Color(0xFFC62828)
        else                                -> Icons.Filled.Notifications to MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isRead) MaterialTheme.colorScheme.surface
                             else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box {
                Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
                if (!isRead) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.TopEnd)
                            .background(Color.Red, CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = if (isRead) FontWeight.Normal else FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (createdAt > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        android.text.format.DateFormat.format("dd MMM yyyy, hh:mm a", createdAt).toString(),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}



