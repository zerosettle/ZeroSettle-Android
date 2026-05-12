# Keep kotlinx.serialization metadata for @Serializable models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep @Serializable models
-keep,includedescriptorclasses class com.zerosettle.sdk.**$$serializer { *; }
-keepclassmembers class com.zerosettle.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class com.zerosettle.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep public SDK API surface (best-effort; consumers usually disable R8 for the SDK).
-keep public class com.zerosettle.sdk.ZeroSettle { public *; }
-keep public class com.zerosettle.sdk.ZeroSettleConfig { *; }
-keep public class com.zerosettle.sdk.Identity { *; }
-keep public class com.zerosettle.sdk.Identity$* { *; }
-keep public class com.zerosettle.sdk.models.** { public *; }
-keep public class com.zerosettle.sdk.offers.OfferManager { public *; }
