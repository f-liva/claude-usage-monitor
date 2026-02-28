package com.claudemonitor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.claudemonitor.app.data.model.LoginState
import com.claudemonitor.app.ui.screens.DashboardScreen
import com.claudemonitor.app.ui.screens.LoginScreen
import com.claudemonitor.app.ui.screens.SettingsScreen
import com.claudemonitor.app.ui.theme.ClaudeMonitorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Exception) { }
        requestNotificationPermission()

        setContent {
            ClaudeMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkServiceStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val sessionState by viewModel.sessionState.collectAsState()

    // Show loading while checking saved session
    if (sessionState.loginState == LoginState.LOADING) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val navController = rememberNavController()
    val usageData by viewModel.usageData.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val refreshInterval by viewModel.refreshInterval.collectAsState()
    val notificationEnabled by viewModel.notificationEnabled.collectAsState()
    val accountEmail by viewModel.accountEmail.collectAsState()

    val startDestination = if (sessionState.loginState == LoginState.LOGGED_IN) "dashboard" else "login"

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("dashboard") {
            DashboardScreen(
                usageData = usageData,
                isServiceRunning = isServiceRunning,
                onRefresh = { viewModel.refreshUsage() },
                onToggleService = { viewModel.toggleService() },
                onNavigateToLogin = { navController.navigate("login") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }

        composable("login") {
            val canGoBack = navController.previousBackStackEntry != null
            LoginScreen(
                onLoginSuccess = { cookies ->
                    viewModel.onLoginSuccess(cookies)
                    navController.navigate("dashboard") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = if (canGoBack) {{ navController.popBackStack() }} else null
            )
        }

        composable("settings") {
            SettingsScreen(
                refreshIntervalMinutes = refreshInterval,
                notificationEnabled = notificationEnabled,
                accountEmail = accountEmail,
                onRefreshIntervalChange = { viewModel.updateRefreshInterval(it) },
                onNotificationToggle = { viewModel.updateNotificationEnabled(it) },
                onLogout = {
                    viewModel.logout()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
