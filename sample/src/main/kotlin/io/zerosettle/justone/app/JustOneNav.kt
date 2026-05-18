package io.zerosettle.justone.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.zerosettle.justone.screens.auth.CreateUserScreen
import io.zerosettle.justone.screens.cancel.CancelFlowScreen
import io.zerosettle.justone.screens.habit.AddHabitScreen
import io.zerosettle.justone.screens.habit.HabitDetailScreen
import io.zerosettle.justone.screens.home.HomeScreen
import io.zerosettle.justone.screens.paywall.LaunchPaywallScreen
import io.zerosettle.justone.screens.paywall.PremiumUpsellSheet
import io.zerosettle.justone.screens.developer.DeveloperScreen
import io.zerosettle.justone.screens.settings.SettingsScreen
import io.zerosettle.justone.screens.shop.ConsumableShopScreen

object Routes {
    const val CREATE_USER = "create-user"
    const val HOME = "home"
    const val HABIT_DETAIL = "habit/{habitId}"
    const val ADD_HABIT = "add-habit"
    const val LAUNCH_PAYWALL = "launch-paywall"
    const val PREMIUM_UPSELL = "premium-upsell"
    const val SETTINGS = "settings"
    const val CONSUMABLE_SHOP = "consumable-shop"
    const val CANCEL_FLOW = "cancel-flow/{productId}"
    const val DEVELOPER = "developer"

    fun habitDetail(habitId: String) = "habit/$habitId"
    fun cancelFlow(productId: String) = "cancel-flow/$productId"
}

@Composable
fun JustOneNav(nav: NavHostController, startDestination: String) {
    NavHost(nav, startDestination = startDestination) {
        composable(Routes.CREATE_USER) {
            CreateUserScreen(onCreated = {
                nav.navigate(Routes.HOME) { popUpTo(Routes.CREATE_USER) { inclusive = true } }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenHabit = { nav.navigate(Routes.habitDetail(it)) },
                onAddHabit = { nav.navigate(Routes.ADD_HABIT) },
                onShowUpsell = { nav.navigate(Routes.PREMIUM_UPSELL) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.HABIT_DETAIL,
            arguments = listOf(navArgument("habitId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val habitId = backStackEntry.arguments?.getString("habitId").orEmpty()
            HabitDetailScreen(habitId = habitId, onBack = { nav.popBackStack() })
        }
        composable(Routes.ADD_HABIT) {
            AddHabitScreen(onAdded = { nav.popBackStack() }, onCancel = { nav.popBackStack() })
        }
        composable(Routes.LAUNCH_PAYWALL) {
            LaunchPaywallScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.PREMIUM_UPSELL) {
            PremiumUpsellSheet(onDismiss = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onSignedOut = {
                    nav.navigate(Routes.CREATE_USER) { popUpTo(0) { inclusive = true } }
                },
                onOpenShop = { nav.navigate(Routes.CONSUMABLE_SHOP) },
                onOpenCancel = { productId -> nav.navigate(Routes.cancelFlow(productId)) },
                onOpenDeveloper = { nav.navigate(Routes.DEVELOPER) },
                onShowUpsell = { nav.navigate(Routes.PREMIUM_UPSELL) },
            )
        }
        composable(Routes.CONSUMABLE_SHOP) {
            ConsumableShopScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.CANCEL_FLOW,
            arguments = listOf(navArgument("productId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId").orEmpty()
            CancelFlowScreen(productId = productId, onDone = { nav.popBackStack() })
        }
        composable(Routes.DEVELOPER) {
            DeveloperScreen(onBack = { nav.popBackStack() })
        }
    }
}
