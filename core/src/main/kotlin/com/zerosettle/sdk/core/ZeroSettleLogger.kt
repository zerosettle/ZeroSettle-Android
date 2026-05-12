package com.zerosettle.sdk.core

import android.util.Log

/**
 * Pluggable logger for the SDK. Default is [LogcatLogger] which writes to logcat
 * with tag `ZeroSettle`. Host apps can supply their own implementation in
 * [com.zerosettle.sdk.ZeroSettleConfig.logger] to forward to their own logging
 * pipeline (Crashlytics, Sentry, Bugsnag, etc).
 */
public interface ZeroSettleLogger {
    public fun verbose(tag: String, message: String, throwable: Throwable? = null)
    public fun debug(tag: String, message: String, throwable: Throwable? = null)
    public fun info(tag: String, message: String, throwable: Throwable? = null)
    public fun warn(tag: String, message: String, throwable: Throwable? = null)
    public fun error(tag: String, message: String, throwable: Throwable? = null)
}

/** Default logger: writes to Android logcat with tag prefix `ZeroSettle/<tag>`. */
public object LogcatLogger : ZeroSettleLogger {
    private const val ROOT = "ZeroSettle"
    override fun verbose(tag: String, message: String, throwable: Throwable?) { Log.v("$ROOT/$tag", message, throwable) }
    override fun debug(tag: String, message: String, throwable: Throwable?) { Log.d("$ROOT/$tag", message, throwable) }
    override fun info(tag: String, message: String, throwable: Throwable?) { Log.i("$ROOT/$tag", message, throwable) }
    override fun warn(tag: String, message: String, throwable: Throwable?) { Log.w("$ROOT/$tag", message, throwable) }
    override fun error(tag: String, message: String, throwable: Throwable?) { Log.e("$ROOT/$tag", message, throwable) }
}
