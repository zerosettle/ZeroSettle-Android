package com.zerosettle.sample

import android.content.Context
import android.content.SharedPreferences

/**
 * Represents the different IAP environments available.
 * Maps to iOS StoreFront `IAPEnvironment`.
 */
enum class IAPEnvironment(
    val displayName: String,
    val description: String,
    val publishableKey: String,
    val baseUrlOverride: String?,
) {
    SANDBOX(
        displayName = "Sandbox",
        description = "Stripe test mode with production URLs",
        publishableKey = "zs_pk_test_0e20a7a5e31fa3ea13ebc100ff42d0993fd2baa9cfccdc6a",
        baseUrlOverride = null, // uses default (api.zerosettle.io/v1)
    ),
    LIVE(
        displayName = "Live",
        description = "Production environment with live payments",
        publishableKey = "zs_pk_live_4b96dfe517998cda605845f6562e70c17fa9cd914073b1cd",
        baseUrlOverride = null,
    ),
    INTERNAL_SANDBOX(
        displayName = "Sandbox (Internal)",
        description = "Internal development with ngrok URLs (sandbox)",
        publishableKey = "zs_pk_test_9ea585f147db0483b60edf628eb75610114d02432c76e801",
        baseUrlOverride = "https://api.zerosettle.ngrok.app/v1",
    ),
    INTERNAL_LIVE(
        displayName = "Live (Internal)",
        description = "Internal development with ngrok URLs (live)",
        publishableKey = "zs_pk_live_1bc267a2d156fd9e70f475adf5a89fb8db8cdee203b604a7",
        baseUrlOverride = "https://api.zerosettle.ngrok.app/v1",
    );

    companion object {
        private const val PREFS_NAME = "com.zerosettle.sample.environment"
        private const val KEY_ENVIRONMENT = "selected_environment"

        /** Load persisted environment or default to SANDBOX. */
        fun load(context: Context): IAPEnvironment {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_ENVIRONMENT, null)
            return entries.firstOrNull { it.name == name } ?: SANDBOX
        }

        /** Persist the selected environment. */
        fun save(context: Context, environment: IAPEnvironment) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENVIRONMENT, environment.name)
                .apply()
        }
    }
}
