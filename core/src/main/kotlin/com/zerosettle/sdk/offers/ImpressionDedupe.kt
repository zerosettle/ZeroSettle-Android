package com.zerosettle.sdk.offers
/** Tracks impression keys reported this session; once-per-key. */
public class ImpressionDedupe {
    private val seen = mutableSetOf<String>()
    public fun shouldReport(key: String): Boolean = seen.add(key)
}
