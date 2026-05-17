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

# JS bridge — methods annotated with @JavascriptInterface are called
# reflectively by the WebView and must not be renamed or stripped, even
# in adopter apps with aggressive R8 minification. The bridge class
# itself sits inside ZeroSettleWebViewActivity (already covered by the
# public-API keep above) but its methods would otherwise be obfuscated
# because they're not part of the public Kotlin surface.
-keepclassmembers class com.zerosettle.sdk.checkout.ZeroSettleWebViewActivity$CheckoutJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Do NOT add -keep for com.android.billingclient.** — Play Billing ships its own consumer rules.
# Do NOT add -keep for okhttp / okio — they ship their own.
