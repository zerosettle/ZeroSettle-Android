# `:ui` — `io.zerosettle:zerosettle-android-ui`

Optional Compose + Material 3 drop-in components on top of `:core`. Material 2 /
View-based apps skip this artifact and drive the headless core directly.

```kotlin
dependencies {
    implementation("io.zerosettle:zerosettle-android:1.0.0")
    implementation("io.zerosettle:zerosettle-android-ui:1.0.0")
}
```

## Theming

```kotlin
ZeroSettleTheme(styles = ZeroSettleStyles(/* offerAccentColor, offerSurfaceColor, … */)) {
    // your content; the ZeroSettle composables read these via CompositionLocal
}
```

`styles` is optional — `ZeroSettleDefaults` derives sensible values from the ambient
`MaterialTheme.colorScheme`.

## Components

- **`ZeroSettleOfferTip(offerManager)`** — the drop-in offer banner. Renders the offer's
  server-provided title / message / CTA, calls `offerManager.acceptOffer()` /
  `dismiss()` for you (all bookkeeping internal), and hides itself when the manager isn't
  `PRESENTED`. A hoisted `ZeroSettleOfferTipContent(...)` variant exists for custom
  state-management.
- **`ZeroSettlePendingActionBanner(action, onDeepLink, onDismiss)`** — renders one
  `PendingAction`. `MigrationCompletedInfo` → an info card with "Got it" → `onDismiss`.
  `ManualPlayCancel` → "Cancel on Google Play" → `onDeepLink(action.deepLink)` (the host
  opens it via an `ACTION_VIEW` intent) and "Later" → `onDismiss` (hides the banner; it
  re-appears on the next poll until Play sends the cancel RTDN — so `onDismiss` here
  should NOT call `dismissPendingAction`).
- **`ZeroSettleCheckoutSheet` / `ZeroSettleCheckoutHost`** — a WebView host for the Stripe
  web checkout (Google Pay surfaces automatically inside the Payment Element — no bespoke
  GPay button). `ZeroSettleCheckoutResult` reports the outcome; `ZeroSettleCheckoutSheetNav`
  is the URL classifier. Wire it off `offerManager.pendingCheckoutUrl`.
- **`ZeroSettleCancelFlow`** — renders a server-driven retention survey + save offer
  (`fetchCancelFlowConfig()` → component → `onResult`).
- **`ZeroSettleUpgradeOffer`** — renders an upgrade offer (`fetchUpgradeOfferConfig()` →
  component → `onResult`).

## Web checkout return deep link

Add to the host app's `AndroidManifest.xml` (any activity that should receive the Stripe
return redirect):

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="zerosettle" android:host="checkout" />
</intent-filter>
```

…then call `ZeroSettle.completeWebCheckout(intent.data.toString())` from `onNewIntent`.
See `:sample` for a working example.
