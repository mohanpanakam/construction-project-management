package com.panakam.construction.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.auth.UserRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onLogout: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val user = AuthManager.getCurrentUser() ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Construction App", fontWeight = FontWeight.Bold)
                        Text(user.role.displayName, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WelcomeCard(name = user.name, role = user.role)
            }
            item {
                Text("Quick Access", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Info, null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Select a project to access Inventory, Financials and Files.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            // Menu items based on role
            items(getMenuItems(user.role).size) { idx ->
                val item = getMenuItems(user.role)[idx]
                DashboardCard(
                    icon = item.first,
                    title = item.second,
                    subtitle = item.third,
                    onClick = {
                        when (item.second) {
                            "Projects", "My Projects" -> onNavigate("projects")
                            "Team"                    -> onNavigate("users")
                            "Payments Audit"          -> onNavigate("auditor/payments")
                            "Collections"             -> onNavigate("collections")
                            "Suspense Account"        -> onNavigate("suspense")
                            "My Units"                -> onNavigate("customer/portal")
                            "My Unit"                 -> onNavigate("customer/portal")
                            else -> { }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun WelcomeCard(name: String, role: UserRole) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AccountCircle, contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Welcome back,", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = role.displayName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Role-based menu items: (icon, title, subtitle)
fun getMenuItems(role: UserRole): List<Triple<ImageVector, String, String>> = when (role) {
    UserRole.ADMIN -> listOf(
        Triple(Icons.Filled.Business,             "Projects",         "Create, edit and manage all projects"),
        Triple(Icons.Filled.People,               "Team",             "Manage workers and managers"),
        Triple(Icons.Filled.AccountBalanceWallet, "Collections",      "All unit sale collections & revenue"),
        Triple(Icons.Filled.AccountBalance,       "Suspense Account", "Funds from reverted unit sales"),
        Triple(Icons.Filled.FactCheck,            "Payments Audit",   "Review and audit all payment records")
    )
    UserRole.PROJECT_MANAGER -> listOf(
        Triple(Icons.Filled.Business,             "Projects",         "View and update assigned projects"),
        Triple(Icons.Filled.AccountBalanceWallet, "Collections",      "View unit sale collections")
    )
    UserRole.SITE_WORKER -> listOf(
        Triple(Icons.Filled.Business,             "My Projects",      "View assigned projects")
    )
    UserRole.AUDITOR -> listOf(
        Triple(Icons.Filled.FactCheck,            "Payments Audit",   "Review & audit payment transactions"),
        Triple(Icons.Filled.Business,             "Projects",         "Browse projects (read-only)")
    )
    UserRole.SALES_REP -> listOf(
        Triple(Icons.Filled.Business,             "Projects",         "View units you can sell"),
        Triple(Icons.Filled.AccountBalanceWallet, "Collections",      "Add/update payments for units you sold")
    )
    UserRole.CUSTOMER -> listOf(
        Triple(Icons.Filled.Home,                 "My Units",         "View all your purchased units and payments")
    )
}
