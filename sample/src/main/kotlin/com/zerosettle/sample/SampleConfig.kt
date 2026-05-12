package com.zerosettle.sample

/**
 * Fill these in with a sandbox publishable key (`zs_pk_test_…`) from the ZeroSettle
 * dashboard, a test user id, and a Play Console subscription product id from a
 * license-tested app. See the `:sample` README for the Play Console setup.
 *
 * The value below is a clearly-marked placeholder — `ZeroSettleConfig` will accept
 * it (the `zs_pk_test_` prefix is the only validation), but every backend call will
 * 401 until you replace it with a real key.
 */
internal object SampleConfig {
    const val PUBLISHABLE_KEY = "zs_pk_test_REPLACE_ME"
    const val TEST_USER_ID = "sample-user-1"

    /** Optional: point at a local backend / ngrok tunnel. Leave null for production. */
    val BASE_URL_OVERRIDE: String? = null
}
