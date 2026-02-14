package com.zerosettle.sdk.core

import android.util.Log

/**
 * Structured logging utility wrapping android.util.Log with category tags.
 */
internal object ZSLogger {

    enum class Category(val tag: String) {
        AUTH("ZS-Auth"),
        BALANCE("ZS-Balance"),
        SIGNING("ZS-Signing"),
        NETWORK("ZS-Network"),
        WALLET("ZS-Wallet"),
        BLOCKCHAIN("ZS-Blockchain"),
        ESCROW("ZS-Escrow"),
        IAP("ZS-IAP"),
        GENERAL("ZS-General"),
    }

    fun debug(message: String, category: Category = Category.GENERAL) {
        Log.d(category.tag, message)
    }

    fun info(message: String, category: Category = Category.GENERAL) {
        Log.i(category.tag, message)
    }

    fun error(message: String, category: Category = Category.GENERAL) {
        Log.e(category.tag, message)
    }

    fun warn(message: String, category: Category = Category.GENERAL) {
        Log.w(category.tag, message)
    }
}
