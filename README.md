# ZeroSettle Android SDK

Merchant-of-Record web checkout + native Play Billing for Android apps. v1.0.0 — a
ground-up rewrite mirroring the current iOS ZeroSettleKit API.

## Artifacts

```kotlin
dependencies {
    implementation("io.zerosettle:zerosettle-android:1.0.0")        // headless core
    implementation("io.zerosettle:zerosettle-android-ui:1.0.0")     // optional Compose components
}
```

minSdk 23 · Kotlin 2.1 · coroutines/Flow async model.

## Quickstart

```kotlin
// Application.onCreate()
ZeroSettle.configure(this, ZeroSettleConfig(publishableKey = "zs_pk_live_…"))

// When you know who the user is:
ZeroSettle.identify(Identity.User(id = appUserId, name = …, email = …))

// Fetch + purchase:
val catalog = ZeroSettle.fetchProducts().getOrThrow()
ZeroSettle.purchase(activity, productId = "pro_monthly")               // web checkout (Custom Tab)
ZeroSettle.purchaseViaPlayBilling(activity, productId = "pro_monthly")  // native Play Billing

// Observe:
ZeroSettle.entitlements.collect { … }
ZeroSettle.pendingActions.collect { … }   // backend-initiated prompts (chunk-3 surface)
ZeroSettle.events.collect { … }           // structured analytics events

// Switch & Save offers (auto-bookkeeping):
val mgr = ZeroSettle.offerManager()
mgr.evaluate()
mgr.acceptOffer()                          // creates web checkout, hands off
```

See [`core/README.md`](core/README.md) and [`ui/README.md`](ui/README.md) for details,
and the docs site (<https://docs.zerosettle.io/sdk/android>) for the full reference.

## Modules

- `:core` — `io.zerosettle:zerosettle-android`. Headless: Kotlin + coroutines + OkHttp +
  Play Billing + DataStore. No Compose dependency.
- `:ui` — `io.zerosettle:zerosettle-android-ui`. Optional Compose + Material 3 drop-in
  components (offer tip, pending-action banner, checkout sheet, cancel flow, upgrade offer).
- `:sample` — a runnable demo wiring every flow (see [`sample/README.md`](sample/README.md)).

## Building

`./gradlew check` — JDK 17 (foojay-provisioned). `./gradlew :sample:assembleDebug` builds
the demo. `./gradlew :core:assembleRelease :ui:assembleRelease` builds release AARs.

## Releases

Tagged release from `main` (`vX.Y.Z`); publishes to Maven Central via the Central Portal
(`com.vanniktech.maven.publish`). v0.15.x is yanked.

## v1.0 known deviations from the spec

- `activeEntitlements` is a derived `get()` accessor (`List<Entitlement>`), not a
  `StateFlow`. Collect `entitlements` and filter for a reactive stream.
- A separate `entitlementUpdates: SharedFlow<List<Entitlement>>` event stream was not
  shipped in 1.0; `entitlements` (a `StateFlow`) is the supported observable.
- `transferPlayOwnershipToCurrentUser(productId, originalTransactionId)` takes the OTID
  explicitly (it lives on `PendingClaim`), disambiguating multiple same-product claims.
