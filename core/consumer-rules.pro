# ── ZeroSettle core consumer rules ───────────────────────────────────────────
# Keep the public API surface.
-keep public class com.zerosettle.sdk.** { public *; }
-keep public enum com.zerosettle.sdk.** { *; }

# kotlinx.serialization: keep @Serializable classes + their generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static <1>$Companion Companion;
    static **$* *;
    *** Companion;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Do NOT add -keep for com.android.billingclient.** — Play Billing ships its own consumer rules.
# Do NOT add -keep for okhttp / okio — they ship their own.
