# ZeroSettle Android Sample

A multi-screen Compose test harness exercising every v1.0 SDK flow. Modeled on the
iOS `JustOne` test app's ZeroSettle integration surface (no habit-tracking — just the
purchase / SDK plumbing).

## Setup

1. Edit `sample/src/main/kotlin/com/zerosettle/sample/SampleConfig.kt`:
   - `PUBLISHABLE_KEY` — a sandbox key (`zs_pk_test_…`) from the ZeroSettle dashboard.
     Ships as the clearly-marked placeholder `zs_pk_test_REPLACE_ME` (backend calls 401
     until replaced).
   - `TEST_USER_ID` — any non-empty string; prefills the Sign-in screen's user-id field.
   - `BASE_URL_OVERRIDE` — set to your local backend / ngrok tunnel for dev, else leave
     `null`. (Note: `ZeroSettleConfig` is immutable; the Debug screen *displays* this
     value but can't change it at runtime — edit `SampleConfig` + reinstall.)
2. For the **native Play Billing** demo: register the products in a Play Console app you
   own, mark your test account as a license tester, and install the sample from an
   internal-test track build. (Web checkout works without Play Console setup.)
3. Run: `./gradlew :sample:installDebug`.

## Screens (bottom-nav)

| Screen | JustOne reference | What it does |
|---|---|---|
| **Sign in** | `LoginView` | user-id text field → `identify(Identity.User(...))` / `Identity.Anonymous`; logout; shows configured/bootstrapped state. Navigates to Home on success. |
| **Home / Paywall** | `LaunchPaywallView` + home premium gate | premium gate via `activeEntitlements`; product cards with "Buy — Web" (`purchase()`) / "Buy — Google Play" (`purchaseViaPlayBilling()`); embeds `ZeroSettleOfferTip` + `ZeroSettleCheckoutHost` bound to the shared `OfferManager`. |
| **Entitlements** | settings `SubscriptionCardView` | `restoreEntitlements()` + render `entitlements` (source/status/renewal/expiry/trial/pause) + `pendingClaims` with a Claim button (`transferPlayOwnershipToCurrentUser`). |
| **Offers** | `PrivateOfferDemoView` | explicit `offerManager.evaluate()`; raw `OfferState` + `OfferData` dump; `ZeroSettleOfferTip` + `ZeroSettleCheckoutHost`; direct Accept/Dismiss/Re-evaluate buttons; surfaces `checkoutError`. |
| **Pending** | (Android-only `PendingAction` API) | renders every `pendingActions` entry via `ZeroSettlePendingActionBanner`; `onDeepLink` → `ACTION_VIEW`, `onDismiss` → `dismissPendingAction` (info banners only). |
| **Cancel** | `CancelFlowView` + `CancelFlow/*` | "Start cancel flow" → `fetchCancelFlowConfig()` → `ZeroSettleCancelFlow`; result routed to `acceptSaveOffer` / `pauseSubscription` / `cancelSubscription`. Plus raw Cancel/Pause/Resume buttons. |
| **Upgrade** | `PremiumUpsellView` + settings `OfferCardView` | "Check upgrade offer" → `fetchUpgradeOfferConfig()` → `ZeroSettleUpgradeOffer`; `Accepted` → `executeUpgradeOffer(from, to)`. |
| **Debug** | `DebugSettingsView` | masked publishable key, base URL, sdkVersion, configured/bootstrapped; `recommendedAppAccountToken()` (copyable); live `playSyncQueueDepthForDebug()`; scrolling `ZeroSettle.events` log with timestamps; buttons: re-evaluate offer / force restore / fetch txn history / fetch products / clear log / logout; current products + entitlements mini-dump. |

The shared `OfferManager` (one instance per identity) lives in `OfferHolder` so the
Home and Offers screens — both of which render `ZeroSettleOfferTip` + `ZeroSettleCheckoutHost`
— observe the same `pendingCheckoutUrl`. It's reset on logout / identity switch.

Web checkout return deep link (`zerosettle://checkout/return`) is handled in
`SampleActivity.onNewIntent` → `completeWebCheckout`.

## Notes

- Screens collect SDK `StateFlow`s with Compose's `collectAsState()`; a production app
  would prefer `collectAsStateWithLifecycle()`.
- `ZeroSettle.playSyncQueueDepthForDebug()` / `recommendedAppAccountToken()` are
  debug-only inspectors, not the supported product surface.
- This module has no unit tests (it's a manual harness); `./gradlew :sample:assembleDebug`
  must build.
