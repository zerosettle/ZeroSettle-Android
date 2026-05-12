package com.zerosettle.sample

import android.app.Application
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.ZeroSettleConfig

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ZeroSettle.configure(
            this,
            ZeroSettleConfig(
                publishableKey = SampleConfig.PUBLISHABLE_KEY,
                baseUrlOverride = SampleConfig.BASE_URL_OVERRIDE,
                syncPlayPurchases = true,
            ),
        )
    }
}
