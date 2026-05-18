package io.zerosettle.justone

import android.app.Application
import io.zerosettle.justone.sdk.configureSdk

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Configure against the backend env persisted by the Sign-in screen
        // (defaults to staging — that's where in-flight backend changes live).
        configureSdk(this)
    }
}
