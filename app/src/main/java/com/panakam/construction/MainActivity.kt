package com.panakam.construction

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.panakam.construction.auth.AuthManager
import com.panakam.construction.data.LocalProjectStorage
import com.panakam.construction.ui.navigation.AppNavigation
import com.panakam.construction.ui.theme.ConstructionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.init(this)
        LocalProjectStorage.init(this)
        enableEdgeToEdge()
        setContent {
            ConstructionTheme {
                val navController = rememberNavController()
                AppNavigation(navController = navController)
            }
        }
    }
}
