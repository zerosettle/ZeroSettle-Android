package com.zerosettle.sdk.internal

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.zerosettle.sdk.core.ZSLogger

/**
 * Opens the Stripe customer portal in a Chrome Custom Tab.
 * Maps to iOS `CustomerPortalFlow`.
 */
internal class CustomerPortalFlow {

    /**
     * Open the customer portal URL in a Chrome Custom Tab.
     * On Android, Custom Tabs don't provide a dismiss callback;
     * the calling code should refresh entitlements in onResume().
     */
    fun presentPortal(activity: Activity, url: String) {
        ZSLogger.info("Opening customer portal in Custom Tab", ZSLogger.Category.IAP)

        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(activity, Uri.parse(url))
    }
}
