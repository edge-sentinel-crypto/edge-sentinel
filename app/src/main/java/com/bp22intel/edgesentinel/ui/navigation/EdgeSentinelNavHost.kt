/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bp22intel.edgesentinel.ui.about.AboutScreen
import com.bp22intel.edgesentinel.ui.alerts.AlertDetailScreen
import com.bp22intel.edgesentinel.ui.alerts.AlertListScreen
import com.bp22intel.edgesentinel.ui.baseline.BaselineScreen
import com.bp22intel.edgesentinel.ui.bluetooth.BluetoothScreen
import com.bp22intel.edgesentinel.ui.cellinfo.CellInfoScreen
import com.bp22intel.edgesentinel.ui.dashboard.DashboardScreen
import com.bp22intel.edgesentinel.ui.map.ThreatMapScreen
import com.bp22intel.edgesentinel.ui.components.PermissionGate
import com.bp22intel.edgesentinel.ui.mesh.MeshScreen
import com.bp22intel.edgesentinel.ui.network.NetworkIntegrityScreen
import com.bp22intel.edgesentinel.ui.onboarding.OnboardingScreen
import com.bp22intel.edgesentinel.ui.settings.CalibrationScreen
import com.bp22intel.edgesentinel.ui.settings.SettingsScreen
import com.bp22intel.edgesentinel.ui.settings.TowerDatabaseScreen
import com.bp22intel.edgesentinel.ui.sweep.SweepModeScreen
import com.bp22intel.edgesentinel.ui.travel.TravelModeScreen
import com.bp22intel.edgesentinel.ui.travel.TravelModeViewModel
import com.bp22intel.edgesentinel.ui.wifi.WifiScreen
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Navigation routes for the app.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val ALERTS = "alerts"
    const val ALERT_DETAIL = "alert_detail/{alertId}"
    const val THREAT_MAP = "threat_map"
    const val CELL_INFO = "cell_info"
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val TRAVEL = "travel"
    const val MESH = "mesh"
    const val NETWORK = "network"
    const val SETTINGS = "settings"
    const val BASELINE = "baseline"
    const val ABOUT = "about"
    const val TOWER_DATABASE = "tower_database"
    const val CALIBRATION = "calibration"
    const val SWEEP_MODE = "sweep_mode"

    fun alertDetail(alertId: Long): String = "alert_detail/$alertId"
}

/**
 * Bottom navigation — 4 primary tabs only.
 * Sensor-specific screens (Cell, WiFi, BLE, Network, Mesh, Baseline) are
 * accessible from the Dashboard sensor icons and from Settings.
 */
enum class BottomNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD(Routes.DASHBOARD, "Home", Icons.Default.Home),
    ALERTS(Routes.ALERTS, "Alerts", Icons.Default.Notifications),
    RADAR(Routes.THREAT_MAP, "Radar", Icons.Default.Map),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Default.Settings)
}

@Composable
fun EdgeSentinelNavHost(initialAlertId: Long? = null) {
    val navController = rememberNavController()

    // Navigate to alert detail if launched from notification deep link
    androidx.compose.runtime.LaunchedEffect(initialAlertId) {
        if (initialAlertId != null && initialAlertId > 0) {
            // Small delay to let NavHost initialize
            kotlinx.coroutines.delay(300)
            navController.navigate(Routes.alertDetail(initialAlertId)) {
                launchSingleTop = true
            }
        }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Hide bottom bar on onboarding, about, baseline, and detail screens
    val showBottomBar = currentDestination?.route?.let { route ->
        route != Routes.ONBOARDING &&
        route != Routes.ABOUT &&
        route != Routes.ALERT_DETAIL &&
        route != Routes.SWEEP_MODE
    } ?: true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = Surface,
                    contentColor = TextPrimary
                ) {
                    BottomNavTab.entries.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = currentDestination?.hierarchy?.any {
                                it.route == tab.route
                            } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = StatusClear,
                                selectedTextColor = StatusClear,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = StatusClear.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.ONBOARDING,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn() + slideInHorizontally { it / 4 } },
            exitTransition = { fadeOut() + slideOutHorizontally { -it / 4 } },
            popEnterTransition = { fadeIn() + slideInHorizontally { -it / 4 } },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 4 } }
        ) {
            // Onboarding
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onOnboardingComplete = {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            // Primary tabs
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onAlertClick = { alert ->
                        navController.navigate(Routes.alertDetail(alert.id))
                    },
                    onNavigate = { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Routes.ALERTS) {
                AlertListScreen(
                    onAlertClick = { alert ->
                        navController.navigate(Routes.alertDetail(alert.id))
                    },
                    onNavigate = { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Routes.TRAVEL) {
                val viewModel: TravelModeViewModel = hiltViewModel()
                val travelState by viewModel.travelState.collectAsState()
                val checkedItems by viewModel.checkedItems.collectAsState()
                TravelModeScreen(
                    travelState = travelState,
                    checkedItems = checkedItems,
                    onBack = { navController.popBackStack() },
                    onActivate = viewModel::activate,
                    onDeactivate = viewModel::deactivate,
                    onExportData = viewModel::exportData,
                    onWipeTravelData = viewModel::wipeTravelData,
                    onPanicWipe = viewModel::panicWipe,
                    onChecklistItemToggle = viewModel::toggleChecklistItem
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateToAbout = {
                        navController.navigate(Routes.ABOUT)
                    },
                    onNavigateToTravel = {
                        navController.navigate(Routes.TRAVEL) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToTowerDatabase = {
                        navController.navigate(Routes.TOWER_DATABASE)
                    },
                    onNavigateToCalibration = {
                        navController.navigate(Routes.CALIBRATION)
                    }
                )
            }

            // Detail screens (navigated from Dashboard sensor icons or Settings)
            composable(
                route = Routes.ALERT_DETAIL,
                arguments = listOf(navArgument("alertId") { type = NavType.LongType })
            ) { backStackEntry ->
                val alertId = backStackEntry.arguments?.getLong("alertId") ?: 0L
                AlertDetailScreen(
                    alertId = alertId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.THREAT_MAP) {
                PermissionGate(
                    permissions = listOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    icon = Icons.Filled.Map,
                    title = "Location Required",
                    rationale = "The Threat Radar needs your location to plot detected threats on the tactical map relative to your position."
                ) {
                    ThreatMapScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToSweep = {
                            navController.navigate(Routes.SWEEP_MODE) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }

            composable(Routes.CELL_INFO) {
                CellInfoScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.WIFI) {
                val wifiPerms = buildList {
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                }
                PermissionGate(
                    permissions = wifiPerms,
                    icon = Icons.Filled.Wifi,
                    title = "WiFi Permissions Required",
                    rationale = "Edge Sentinel needs Location and WiFi access to scan for evil twin networks, rogue access points, and deauth attacks."
                ) {
                    WifiScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable(Routes.BLUETOOTH) {
                val blePerms = buildList {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        add(android.Manifest.permission.BLUETOOTH_SCAN)
                        add(android.Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
                PermissionGate(
                    permissions = blePerms,
                    icon = Icons.Filled.Bluetooth,
                    title = "Bluetooth Permissions Required",
                    rationale = "Edge Sentinel needs Bluetooth and Location access to detect BLE trackers like AirTags, SmartTags, and Tile devices following you."
                ) {
                    BluetoothScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable(Routes.NETWORK) {
                NetworkIntegrityScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.MESH) {
                val meshPerms = buildList {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        add(android.Manifest.permission.BLUETOOTH_SCAN)
                        add(android.Manifest.permission.BLUETOOTH_CONNECT)
                        add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                    }
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
                PermissionGate(
                    permissions = meshPerms,
                    icon = Icons.Filled.People,
                    title = "Bluetooth Permissions Required",
                    rationale = "Mesh networking uses Bluetooth LE to discover nearby Edge Sentinel devices and share threat alerts across your team."
                ) {
                    MeshScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToSweep = {
                            navController.navigate(Routes.SWEEP_MODE) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }

            composable(Routes.BASELINE) {
                BaselineScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.TOWER_DATABASE) {
                TowerDatabaseScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.CALIBRATION) {
                CalibrationScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.SWEEP_MODE) {
                val meshPerms = buildList {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        add(android.Manifest.permission.BLUETOOTH_SCAN)
                        add(android.Manifest.permission.BLUETOOTH_CONNECT)
                        add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                    }
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
                PermissionGate(
                    permissions = meshPerms,
                    icon = Icons.Filled.Map,
                    title = "Permissions Required",
                    rationale = "Sweep Mode needs Bluetooth and Location to coordinate with nearby Edge Sentinel devices and triangulate suspicious towers."
                ) {
                    SweepModeScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
