package com.i5autolock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.i5autolock.ui.help.HelpScreen
import com.i5autolock.ui.home.HomeScreen
import com.i5autolock.ui.login.LoginScreen
import com.i5autolock.ui.onboarding.OnboardingScreen
import com.i5autolock.ui.onboarding.OnboardingViewModel
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
    // Show the first-run wizard until it's completed; then the normal app.
    val onboardingVm: OnboardingViewModel = hiltViewModel()
    val completed by onboardingVm.completed.collectAsStateWithLifecycle()
    when (completed) {
        null -> Unit // brief loading; render nothing to avoid a flash
        false -> OnboardingScreen(onFinish = onboardingVm::complete)
        else -> AppNavHost(navController)
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHelp = { navController.navigate(Routes.HELP) },
                onOpenLogin = { navController.navigate(Routes.LOGIN) },
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
