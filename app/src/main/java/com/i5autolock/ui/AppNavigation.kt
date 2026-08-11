package com.i5autolock.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.i5autolock.ui.help.HelpScreen
import com.i5autolock.ui.home.HomeScreen
import com.i5autolock.ui.login.LoginScreen
import com.i5autolock.ui.settings.SettingsScreen
import com.i5autolock.ui.stats.StatsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val STATS = "stats"
    const val HELP = "help"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHelp = { navController.navigate(Routes.HELP) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLogin = { navController.navigate(Routes.LOGIN) },
                onStats = { navController.navigate(Routes.STATS) },
                onHelp = { navController.navigate(Routes.HELP) },
            )
        }
        composable(Routes.STATS) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HELP) {
            HelpScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onBack = { navController.popBackStack() },
                onSuccess = { navController.popBackStack() },
            )
        }
    }
}
