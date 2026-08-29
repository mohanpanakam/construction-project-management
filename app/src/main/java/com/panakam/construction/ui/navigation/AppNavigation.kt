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
    const val LOGIN           = "login"
    const val REGISTER        = "register"
    const val FORGOT_PASSWORD = "forgot_password"
    const val HOME            = "home"
    const val USERS           = "users"
    const val PROJECTS        = "projects"
    const val ADD_PROJECT     = "projects/add"
    const val EDIT_PROJECT    = "projects/edit"
    const val PROJECT_DETAIL  = "projects/detail/{projectId}"
    const val PROJECT_FILES   = "projects/{projectId}/files/{projectName}"
    const val PROJECT_INVENTORY  = "projects/{projectId}/inventory/{projectName}"
    const val PROJECT_FINANCIALS = "projects/{projectId}/financials/{projectName}"
    const val PROJECT_UNITS      = "projects/{projectId}/units/{projectName}/{isJD}"
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
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Routes.REGISTER) },
                onForgotPassword     = { navController.navigate(Routes.FORGOT_PASSWORD) }
            )
        }
        composable(Routes.FORGOT_PASSWORD) {
            ForgotPasswordScreen(onBack = { navController.popBackStack() })
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
                onBack             = { navController.popBackStack() }
            )
        }
    }
}
