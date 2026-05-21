package com.zerosettle.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Walks the [ContextWrapper] chain to the host [Activity]. Returns `null` if no
 * [Activity] is found (e.g. Service context, application-only context). The caller
 * must handle the null case gracefully — do not throw.
 *
 * Needed because `LocalContext.current` inside a ModalBottomSheet (or any Dialog)
 * is the dialog's ContextThemeWrapper, not the Activity — a direct `as Activity`
 * cast throws ClassCastException.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
