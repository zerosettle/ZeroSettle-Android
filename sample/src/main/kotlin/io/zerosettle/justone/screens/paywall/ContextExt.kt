package io.zerosettle.justone.screens.paywall

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Walks the [ContextWrapper] chain to the host [Activity]. Needed because
 * `LocalContext.current` inside a ModalBottomSheet (or any Dialog) is the
 * dialog's ContextThemeWrapper, not the Activity — a direct `as Activity`
 * cast there throws ClassCastException.
 */
internal tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No Activity found in the Context chain")
}
