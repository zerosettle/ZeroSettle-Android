package com.zerosettle.sample

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector

/** Route constants + bottom-nav definitions for the sample harness. */
object Routes {
    const val SIGN_IN = "signin"
    const val HOME = "home"
    const val ENTITLEMENTS = "entitlements"
    const val OFFERS = "offers"
    const val PENDING = "pending"
    const val CANCEL = "cancel"
    const val UPGRADE = "upgrade"
    const val DEBUG = "debug"
}

data class BottomTab(val route: String, val label: String, val icon: ImageVector)

val BOTTOM_TABS: List<BottomTab> = listOf(
    BottomTab(Routes.HOME, "Home", Icons.Filled.Home),
    BottomTab(Routes.ENTITLEMENTS, "Entitle", Icons.Filled.VerifiedUser),
    BottomTab(Routes.OFFERS, "Offers", Icons.Filled.CardGiftcard),
    BottomTab(Routes.PENDING, "Pending", Icons.Filled.Notifications),
    BottomTab(Routes.CANCEL, "Cancel", Icons.Filled.RemoveCircle),
    BottomTab(Routes.UPGRADE, "Upgrade", Icons.Filled.Upgrade),
    BottomTab(Routes.DEBUG, "Debug", Icons.Filled.BugReport),
)
