package io.zerosettle.justone.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.zerosettle.justone.screens.auth.CreateUserScreen
import io.zerosettle.justone.screens.home.HomeScreen

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
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.HABIT_DETAIL,
            arguments = listOf(navArgument("habitId") { type = NavType.StringType }),
        ) { Text(Routes.HABIT_DETAIL) }
        composable(Routes.ADD_HABIT) { Text(Routes.ADD_HABIT) }
        composable(Routes.LAUNCH_PAYWALL) { Text(Routes.LAUNCH_PAYWALL) }
        composable(Routes.PREMIUM_UPSELL) { Text(Routes.PREMIUM_UPSELL) }
        composable(Routes.SETTINGS) { Text(Routes.SETTINGS) }
        composable(Routes.CONSUMABLE_SHOP) { Text(Routes.CONSUMABLE_SHOP) }
        composable(
            Routes.CANCEL_FLOW,
            arguments = listOf(navArgument("productId") { type = NavType.StringType }),
        ) { Text(Routes.CANCEL_FLOW) }
        composable(Routes.DEVELOPER) { Text(Routes.DEVELOPER) }
    }
}
