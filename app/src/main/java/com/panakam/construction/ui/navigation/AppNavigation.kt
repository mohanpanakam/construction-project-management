package com.panakam.construction.ui.navigation

import android.net.Uri
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.model.Project
import com.panakam.construction.ui.screens.*
import com.panakam.construction.ui.screens.projects.*
object Routes {
    // ...existing routes...
    const val LOGIN                    = "login"
    const val REGISTER                 = "register"
    const val FORGOT_PASSWORD          = "forgot_password"
    const val CUSTOMER_FORGOT_PASSWORD = "customer_forgot_password"
    const val HOME                     = "home"
    const val USERS                    = "users"
    const val PROJECTS                 = "projects"
    const val ADD_PROJECT              = "projects/add"
    const val EDIT_PROJECT             = "projects/edit"
    const val PROJECT_DETAIL           = "projects/detail/{projectId}"
    const val PROJECT_FILES            = "projects/{projectId}/files/{projectName}"
    const val PROJECT_INVENTORY        = "projects/{projectId}/inventory/{projectName}"
    const val PROJECT_FINANCIALS       = "projects/{projectId}/financials/{projectName}"
    const val PROJECT_UNITS            = "projects/{projectId}/units/{projectName}/{isJD}"
    const val CUSTOMER_DETAIL          = "customer/{projectId}/{unitId}/{unitNumber}/{floor}/{type}/{sba}"
    const val CUSTOMER_PAYMENTS        = "customer/{customerId}/payments/{customerName}/{projectId}/{unitId}"
    const val AUDITOR_PAYMENTS         = "auditor/payments"
    const val CUSTOMER_PORTAL          = "customer/portal"
    const val CUSTOMER_ALL_PAYMENTS    = "customer/portal/payments"
    const val CUSTOMER_CHANGE_PASSWORD = "customer/change-password"
    const val COLLECTIONS              = "collections"
    const val PROJECT_COLLECTIONS      = "collections/{projectId}/{projectName}"
    const val SUSPENSE                 = "suspense"
    const val PROJECT_SUSPENSE         = "suspense/{projectId}/{projectName}"
    const val PROJECT_SALES_REPS       = "sales-reps/{projectId}/{projectName}"
    const val PROJECT_PAYMENTS_AUDIT   = "payments-audit/{projectId}/{projectName}"
}

@Composable
fun AppNavigation(navController: NavHostController) {
    val start = if (AuthManager.isLoggedIn()) Routes.HOME else Routes.LOGIN

    // Shared state to pass Project object to edit screen without serialization
    var projectToEdit by remember { mutableStateOf<Project?>(null) }

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    val user = AuthManager.getCurrentUser()
                    val dest = if (user?.mustChangePassword == true)
                        Routes.CUSTOMER_CHANGE_PASSWORD else Routes.HOME
                    navController.navigate(dest) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Routes.REGISTER) },
                onForgotPassword     = { isCustomer ->
                    navController.navigate(if (isCustomer) Routes.CUSTOMER_FORGOT_PASSWORD else Routes.FORGOT_PASSWORD)
                }
            )
        }
        composable(Routes.FORGOT_PASSWORD) {
            ForgotPasswordScreen(isCustomer = false, onBack = { navController.popBackStack() })
        }
        composable(Routes.CUSTOMER_FORGOT_PASSWORD) {
            ForgotPasswordScreen(isCustomer = true, onBack = { navController.popBackStack() })
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onLogout = {
                    AuthManager.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onNavigate = { route: String -> navController.navigate(route) }
            )
        }

        composable(Routes.USERS) {
            UserManagementScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PROJECTS) {
            ProjectListScreen(
                onBack = { navController.popBackStack() },
                onAddProject = { navController.navigate(Routes.ADD_PROJECT) },
                onViewProject = { id -> navController.navigate("projects/detail/$id") }
            )
        }

        composable(Routes.ADD_PROJECT) {
            AddEditProjectScreen(
                existingProject = null,
                onBack = { navController.popBackStack() },
                onSaved = {
                    navController.navigate(Routes.PROJECTS) {
                        popUpTo(Routes.PROJECTS) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.EDIT_PROJECT) {
            AddEditProjectScreen(
                existingProject = projectToEdit,
                onBack = { navController.popBackStack() },
                onSaved = {
                    navController.navigate(Routes.PROJECTS) {
                        popUpTo(Routes.PROJECTS) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PROJECT_DETAIL) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            ProjectDetailScreen(
                projectId = projectId,
                onBack    = { navController.popBackStack() },
                onEdit    = { project ->
                    projectToEdit = project
                    navController.navigate(Routes.EDIT_PROJECT)
                },
                onDeleted = {
                    navController.navigate(Routes.PROJECTS) {
                        popUpTo(Routes.PROJECTS) { inclusive = true }
                    }
                },
                onViewFiles = { id, name ->
                    navController.navigate(
                        "projects/${Uri.encode(id)}/files/${Uri.encode(name)}"
                    )
                },
                onViewInventory = { id, name ->
                    navController.navigate(
                        "projects/${Uri.encode(id)}/inventory/${Uri.encode(name)}"
                    )
                },
                onViewFinancials = { id, name ->
                    navController.navigate(
                        "projects/${Uri.encode(id)}/financials/${Uri.encode(name)}"
                    )
                },
                onViewUnits = { id, name, isJD ->
                    navController.navigate(
                        "projects/${Uri.encode(id)}/units/${Uri.encode(name)}/$isJD"
                    )
                },
                onViewCollections = { id, name ->
                    navController.navigate(
                        "collections/${Uri.encode(id)}/${Uri.encode(name)}"
                    )
                },
                onViewSuspense = { id, name ->
                    navController.navigate(
                        "suspense/${Uri.encode(id)}/${Uri.encode(name)}"
                    )
                },
                onViewSalesReps = { id, name ->
                    navController.navigate(
                        "sales-reps/${Uri.encode(id)}/${Uri.encode(name)}"
                    )
                },
                onViewPaymentHistory = { id, name ->
                    navController.navigate(
                        "payments-audit/${Uri.encode(id)}/${Uri.encode(name)}"
                    )
                }
            )
        }

        composable(Routes.PROJECT_FILES) { backStackEntry ->
            val projectId   = backStackEntry.arguments?.getString("projectId")   ?: ""
            val projectName = backStackEntry.arguments?.getString("projectName") ?: ""
            ProjectFilesScreen(
                projectId   = Uri.decode(projectId),
                projectName = Uri.decode(projectName),
                onBack      = { navController.popBackStack() }
            )
        }

        composable(Routes.PROJECT_INVENTORY) { backStackEntry ->
            val projectId   = backStackEntry.arguments?.getString("projectId")   ?: ""
            val projectName = backStackEntry.arguments?.getString("projectName") ?: ""
            ProjectInventoryScreen(
                projectId   = Uri.decode(projectId),
                projectName = Uri.decode(projectName),
                onBack      = { navController.popBackStack() }
            )
        }

        composable(Routes.PROJECT_FINANCIALS) { backStackEntry ->
            val projectId   = backStackEntry.arguments?.getString("projectId")   ?: ""
            val projectName = backStackEntry.arguments?.getString("projectName") ?: ""
            ProjectFinancialsScreen(
                projectId   = Uri.decode(projectId),
                projectName = Uri.decode(projectName),
                onBack      = { navController.popBackStack() }
            )
        }

        composable(Routes.PROJECT_UNITS) { backStackEntry ->
            val projectId   = backStackEntry.arguments?.getString("projectId")   ?: ""
            val projectName = backStackEntry.arguments?.getString("projectName") ?: ""
            val isJD        = backStackEntry.arguments?.getString("isJD") == "true"
            ProjectUnitsScreen(
                projectId          = Uri.decode(projectId),
                projectName        = Uri.decode(projectName),
                isJointDevelopment = isJD,
                onBack             = { navController.popBackStack() },
                onViewUnit         = { unitId, unitNumber, floor, type, sba ->
                    navController.navigate(
                        "customer/${Uri.encode(Uri.decode(projectId))}/${Uri.encode(unitId)}/${Uri.encode(unitNumber)}/${Uri.encode(floor)}/${Uri.encode(type)}/${Uri.encode(sba)}"
                    )
                }
            )
        }

        composable(Routes.CUSTOMER_DETAIL) { backStackEntry ->
            val projectId  = Uri.decode(backStackEntry.arguments?.getString("projectId")  ?: "")
            val unitId     = Uri.decode(backStackEntry.arguments?.getString("unitId")     ?: "")
            val unitNumber = Uri.decode(backStackEntry.arguments?.getString("unitNumber") ?: "")
            val floor      = Uri.decode(backStackEntry.arguments?.getString("floor")      ?: "")
            val type       = Uri.decode(backStackEntry.arguments?.getString("type")       ?: "")
            val sba        = Uri.decode(backStackEntry.arguments?.getString("sba")        ?: "")
            CustomerDetailScreen(
                unitId      = unitId,
                unitNumber  = unitNumber,
                floor       = floor,
                unitType    = type,
                sba         = sba,
                projectId   = projectId,
                onBack      = { navController.popBackStack() },
                onViewPayments = { customerId, customerName ->
                    navController.navigate(
                        "customer/${Uri.encode(customerId)}/payments/${Uri.encode(customerName)}/${Uri.encode(projectId)}/${Uri.encode(unitId)}"
                    )
                }
            )
        }

        composable(Routes.CUSTOMER_PAYMENTS) { backStackEntry ->
            val customerId   = Uri.decode(backStackEntry.arguments?.getString("customerId")   ?: "")
            val customerName = Uri.decode(backStackEntry.arguments?.getString("customerName") ?: "")
            val projectId    = Uri.decode(backStackEntry.arguments?.getString("projectId")    ?: "")
            val unitId       = Uri.decode(backStackEntry.arguments?.getString("unitId")       ?: "")
            CustomerPaymentsScreen(
                customerId   = customerId,
                customerName = customerName,
                projectId    = projectId,
                unitId       = unitId,
                onBack       = { navController.popBackStack() }
            )
        }
        composable(Routes.AUDITOR_PAYMENTS) {
            AuditorPaymentsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CUSTOMER_PORTAL) {
            CustomerPortalScreen(
                onBack = { navController.popBackStack() },
                onViewUnit = { projectId, unitId, unitNumber, floor, type, sba ->
                    navController.navigate(
                        "customer/${Uri.encode(projectId)}/${Uri.encode(unitId)}/${Uri.encode(unitNumber)}/${Uri.encode(floor)}/${Uri.encode(type)}/${Uri.encode(sba)}"
                    )
                },
                onViewAllPayments = { navController.navigate(Routes.CUSTOMER_ALL_PAYMENTS) }
            )
        }

        composable(Routes.CUSTOMER_ALL_PAYMENTS) {
            CustomerAllPaymentsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CUSTOMER_CHANGE_PASSWORD) {
            CustomerChangePasswordScreen(
                onPasswordChanged = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.CUSTOMER_CHANGE_PASSWORD) { inclusive = true }
                    }
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.COLLECTIONS) {
            CollectionsScreen(
                filterProjectId   = null,
                filterProjectName = null,
                onBack = { navController.popBackStack() },
                onAddPayment = { customerId, customerName, projectId, unitId ->
                    navController.navigate(
                        "customer/${Uri.encode(customerId)}/payments/${Uri.encode(customerName)}/${Uri.encode(projectId)}/${Uri.encode(unitId)}"
                    )
                }
            )
        }

        composable(Routes.PROJECT_COLLECTIONS) { backStackEntry ->
            val projectId   = Uri.decode(backStackEntry.arguments?.getString("projectId")   ?: "")
            val projectName = Uri.decode(backStackEntry.arguments?.getString("projectName") ?: "")
            CollectionsScreen(
                filterProjectId   = projectId,
                filterProjectName = projectName,
                onBack = { navController.popBackStack() },
                onAddPayment = { customerId, customerName, pId, unitId ->
                    navController.navigate(
                        "customer/${Uri.encode(customerId)}/payments/${Uri.encode(customerName)}/${Uri.encode(pId)}/${Uri.encode(unitId)}"
                    )
                }
            )
        }

        composable(Routes.SUSPENSE) {
            SuspenseScreen(
                filterProjectId   = null,
                filterProjectName = null,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PROJECT_SUSPENSE) { backStackEntry ->
            val projectId   = Uri.decode(backStackEntry.arguments?.getString("projectId")   ?: "")
            val projectName = Uri.decode(backStackEntry.arguments?.getString("projectName") ?: "")
            SuspenseScreen(
                filterProjectId   = projectId,
                filterProjectName = projectName,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PROJECT_SALES_REPS) { backStackEntry ->
            val projectId   = Uri.decode(backStackEntry.arguments?.getString("projectId")   ?: "")
            val projectName = Uri.decode(backStackEntry.arguments?.getString("projectName") ?: "")
            ProjectSalesRepsScreen(
                projectId   = projectId,
                projectName = projectName,
                onBack      = { navController.popBackStack() }
            )
        }

        composable(Routes.PROJECT_PAYMENTS_AUDIT) { backStackEntry ->
            val projectId   = Uri.decode(backStackEntry.arguments?.getString("projectId")   ?: "")
            val projectName = Uri.decode(backStackEntry.arguments?.getString("projectName") ?: "")
            AuditorPaymentsScreen(
                onBack            = { navController.popBackStack() },
                filterProjectId   = projectId,
                filterProjectName = projectName
            )
        }
    }
}
