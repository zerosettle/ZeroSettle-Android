# JustOne — ZeroSettle Android Sample

**JustOne** is a functional habit-tracker reference app that demonstrates how to integrate
the ZeroSettle Android SDK into a real application. It replaces the former 7-tab SDK debug
harness with ~24 Compose screens backed by a proper habit-tracking domain layer.

## What's in the app

**Habit-tracker domain** (Room / DataStore / WorkManager):
- `Habit` + `Completion` entities, DAOs, and `AppDatabase`
- `UserPrefs` (DataStore) — stores the user's ZeroSettle identity and preferences
- WorkManager EOD reminder notifications
- Pure `HabitCalc` functions (unit-tested — see `HabitCalcTest`)

**ZeroSettle SDK surfaces exercised:**
- Identity: `identify()`, `logout()`, `currentUserId`
- Catalog: `products()`, `product()`
- Purchase: `purchase()` (web checkout / Custom Tab), `purchaseViaPlayBilling()`,
  `isUcbEnabled` (UCB-aware buy widget — see Launch paywall screen)
- Entitlements: `entitlements`, `hasActiveEntitlement()`, `restoreEntitlements()`
- Subscription management: `cancelSubscription()`, `pauseSubscription()`,
  `resumeSubscription()`, `acceptSaveOffer()`, `fetchCancelFlowConfig()`
- Upgrade: `fetchUpgradeOfferConfig()`
- Offers: `offerManager`, `ZeroSettleOfferTip`, `ZeroSettleCheckoutHost`
- Pending actions: `pendingActions`, `dismissPendingAction()`,
  `transferPlayOwnershipToCurrentUser()`, `ZeroSettlePendingActionBanner`
- Cancel flow UI: `ZeroSettleCancelFlow`

**Debug harness** — the former 7-tab SDK harness is preserved under
**Settings → Developer**. It is always visible in debug builds. In release builds it is
hidden behind a 7-tap gesture on the app-version line of the Account card in Settings.

## Setup

1. Edit `sample/src/main/kotlin/io/zerosettle/justone/SampleConfig.kt`:
   - `PUBLISHABLE_KEY` — a sandbox key (`zs_pk_test_…`) from the ZeroSettle dashboard.
     Ships as the clearly-marked placeholder `zs_pk_test_REPLACE_ME` (backend calls return
     401 until replaced).
   - Backend environment (production / staging / local / custom) is chosen at runtime
     via **Settings → Developer → Env Switcher** and persisted across launches.
2. For the **native Play Billing** demo: register the products in a Play Console app you
   own, mark your test account as a license tester, and install the sample from an
   internal-test track build. (Web checkout works without Play Console setup.)
3. If you are working against an unreleased SDK commit, publish the local SDK artifacts
   first so Gradle picks them up:
   ```bash
   ./gradlew :core:publishToMavenLocal :ui:publishToMavenLocal
   ```
4. Run: `./gradlew :sample:installDebug`

## Running tests

The habit-domain unit tests (`HabitCalcTest`) run without a device:

```bash
./gradlew :sample:testDebugUnitTest
```

## Key screens

| Screen | What it does |
|---|---|
| **Onboarding** (`CreateUserScreen`) | Collects a user name to bootstrap the ZeroSettle identity. |
| **Home** | Greeting, aggregated heatmap across all habits, per-habit heatmaps, habit list with check-off buttons. |
| **Habit detail** | Per-habit calendar, current streak, completion history. |
| **Add habit** | Form to create a new habit with emoji + color picker. |
| **Launch paywall** | Premium gate — product cards with a UCB-aware `DualPriceButtons` widget: a single "Buy" button when `ZeroSettle.isUcbEnabled` is `true` (Google's choice screen routes web-vs-Play via `purchaseViaPlayBilling()`), or separate "Buy — Web" / "Buy — Google Play" buttons when UCB is disabled; embeds `ZeroSettleOfferTip` + `ZeroSettleCheckoutHost`. |
| **Premium upsell sheet** | Bottom-sheet upsell with upgrade-offer integration. |
| **Settings** | Account, Subscription (cancel/pause/resume), StreakSaver, Reminder, and Offer cards. |
| **Cancel flow** | `fetchCancelFlowConfig()` → `ZeroSettleCancelFlow`; result routes to `acceptSaveOffer` / `pauseSubscription` / `cancelSubscription`, with confetti on save. |
| **Consumable shop** | Demonstrates consumable purchases via `purchase()`. |
| **Settings → Developer** | Full debug harness: env switcher, raw entitlements, offer state, pending actions, cancel/upgrade debug, SDK event log. |

Web checkout return deep link (`zerosettle://checkout/return`) is handled in
`MainActivity.onNewIntent` → `completeWebCheckout`.

## Notes

- Screens collect SDK `StateFlow`s with Compose's `collectAsState()`; a production app
  would prefer `collectAsStateWithLifecycle()`.
- `ZeroSettle.playSyncQueueDepthForDebug()` / `recommendedAppAccountToken()` are
  debug-only inspectors, not the supported product surface.
- This module has `HabitCalcTest` unit tests; `./gradlew :sample:assembleDebug` must
  also build cleanly.
