package com.zerosettle.sdk

import kotlinx.serialization.Serializable

/**
 * Trivial `@Serializable` type used to smoke-test that the consumer ProGuard rules
 * keep generated serializers in a minified build. Not part of the public contract;
 * safe to remove if it ever causes friction.
 */
@Serializable
internal data class KeepProbe(val a: Int, val b: String)
