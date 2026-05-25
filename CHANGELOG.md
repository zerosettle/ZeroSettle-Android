# Changelog

## 1.1.1 — Soft-deprecate `eclAvailabilityOverride` — 2026-05-25

### SDK (`:core`)

- **Deprecated `ZeroSettle.eclAvailabilityOverride`.** The flag only affected
  the Switch & Save offer-gate visibility — the real `launchSwitchAndSave()`
  flow still queried Play Billing for ECL availability, producing a "live" tip
  whose CTA errored with `SwitchAndSaveUnavailable` on non-enrolled devices.
  Use `ZeroSettle.switchAndSaveTestMode` instead — it gates the offer AND fakes
  the launch-side Play ECL plumbing so the full flow runs end-to-end on a
  non-ECL device. The property still works; callers receive a deprecation
  warning. Will be removed in a future major.

### Sample app (`:sample`)

- Removed the "Force ECL available" toggle from the Sign-in screen. The remaining
  "Switch & Save full test mode" toggle is the single end-to-end Switch & Save
  testing switch.

## 1.1.0 — Play entitlement transfer + Switch & Save developer test-mode — 2026-05-22

### SDK (`:core`)

- **New public API — `ZeroSettle.switchAndSaveTestMode: Boolean`:** Testing override
  that runs the entire Switch & Save (Play→web ECL migration) flow on a device/account
  NOT enrolled in Google's External Content Link program. When `true`, the Play ECL
  plumbing — availability check, attribution token, and the disclosure dialog — is faked,
  while the backend session mint and the Chrome Custom Tab web checkout run for real. It
  also implies ECL-available for offer eligibility, so the Switch & Save tip surfaces
  without separately setting `eclAvailabilityOverride`. Leave `false` in production.
- **Play entitlement transfer:** `PendingClaim` now carries `purchaseToken`; the Play
  purchase token is carried through the cross-account sync-conflict path; and
  `transferPlayOwnershipToCurrentUser(productId)` claims a Play-sourced entitlement for
  the current user.
- **Reconcile owned Play purchases on start:** `PlayBillingCoordinator.start()` (and
  every `identify()`) queries Play for owned SUBS + INAPP purchases and runs each through
  the existing sync path, catching renewals and state changes that completed while the
  app was not running (the `PurchasesUpdatedListener` only fires for the live session).
  Idempotent and best-effort — a failed query is logged and skipped.

### Sample app (`:sample` module)

- New "Switch & Save full test mode" developer toggle on the Sign-in screen, mirroring
  the existing "Force ECL available" toggle.
- Per-environment publishable keys — each backend environment carries its own sandbox
  publishable key.

## 1.0.0 — JustOne sample app + UCB SDK fixes — 2026-05-18

### SDK (`:core`)

- **Bug fix — `purchaseViaPlayBilling()` cancel-hang:** Previously, dismissing the Google
  choice screen or cancelling the Play billing sheet left the pending purchase deferred
  unresolved, causing the call to hang indefinitely. Terminal billing-listener results are
  now handled: `USER_CANCELED` resolves the deferred with
  `Result.failure(ZeroSettleError.PurchaseCancelled)`; other non-OK result codes resolve
  with `Result.failure(ZeroSettleError.PlayBillingError)`; `OK`+empty purchases list also
  resolves as `PurchaseCancelled` (user dismissed before completing).
- **New public API — `ZeroSettle.isUcbEnabled: StateFlow<Boolean>`:** Exposes whether
  Google User Choice Billing is active for the current app and market, reflecting the
  server `PlayBillingConfig`. Tenant- and market-scoped: reset on `configure()`, not on
  `logout()`. Host apps can collect this flow to adapt their purchase UI (see the sample's
  `DualPriceButtons` for a reference implementation).

### Sample app (`:sample` module)

The `:sample` module has been rebuilt from a 7-tab SDK debug harness into **JustOne**,
a functional habit-tracker reference app (~24 Compose screens) demonstrating a realistic
ZeroSettle SDK integration. The former debug harness is preserved under
**Settings → Developer** (always visible in debug builds; gated by a 7-tap gesture in
release builds).

**Domain layer** (habit-tracker — no SDK dependency): Room database (`Habit`,
`Completion`, DAOs, `AppDatabase`), DataStore (`UserPrefs`), WorkManager EOD reminders,
pure `HabitCalc` functions (unit-tested via `HabitCalcTest` — 9 methods).

**SDK surfaces exercised:**
- *Identity* — `identify()`, `logout()`, `currentUserId`
- *Catalog* — `products()`, `product()`
- *Purchase* — `purchase()` (web checkout / Custom Tab), `purchaseViaPlayBilling()`,
  `isUcbEnabled` (UCB-aware buy widget — see `DualPriceButtons` below)
- *Entitlements* — `entitlements`, `hasActiveEntitlement()`, `restoreEntitlements()`
- *Subscription management* — `cancelSubscription()`, `pauseSubscription()`,
  `resumeSubscription()`, `acceptSaveOffer()`, `fetchCancelFlowConfig()`
- *Upgrade* — `fetchUpgradeOfferConfig()`
- *Offers* — `offerManager`, `ZeroSettleOfferTip`, `ZeroSettleCheckoutHost`
- *Pending actions* — `pendingActions`, `dismissPendingAction()`,
  `transferPlayOwnershipToCurrentUser()`, `ZeroSettlePendingActionBanner`
- *Cancel flow UI* — `ZeroSettleCancelFlow`

**Deferred / not exercised** (APIs not present on the Android SDK):
`trackEvent`, `CheckoutSheet.warmUp`/`warmUpAll` (substituted by the `preloadCheckout`
config flag), `isWebCheckoutEnabled`, `newConsumableEntitlements`, `entitlementUpdates`,
`submitCancelFlowResponse`.

**UCB-aware buy widget (`DualPriceButtons`):** The paywall's buy widget adapts at runtime
based on `ZeroSettle.isUcbEnabled`. When UCB is enabled, it renders a single unified
"Buy" button that calls `purchaseViaPlayBilling()` — Google's system choice screen then
routes the purchase to web checkout or Google Play. When UCB is disabled (the default for
most apps/markets), it falls back to the two-button web / Google Play layout.

---

## v1.0.0 (in-place edits, untagged) — Flutter Android Parity Prerequisites — 2026-05-12

Six small edits to v1.0.0 on `main` (untagged, in-place — v1.0.0 was never tagged/released).
These are prerequisites for the Flutter plugin's Android-parity work in
`ZeroSettle-Flutter` 1.5.0. To publish to Maven Central with these changes folded in
before the Flutter package tags 1.5.0.

### Added
- `ZeroSettle.currentUserId: StateFlow<String?>` — public StateFlow exposing the active
  user id. Updates on `identify()` / `logout()`. Replaces the private `activeUserId` +
  `internal currentUserIdOrNull()` pair so cross-platform bridges can read the canonical
  source without reflection.
- `ZeroSettle.isOfferPermanentlyDismissed(userId)`, `setOfferDismissed(userId, dismissed)`,
  `resetOfferDismissedState()` — static dismissal helpers wrapping `OfferDismissalStore`
  for the Flutter plugin's `zerosettle/offer_manager_static` channel. Userid-keyed for
  the first two; no-arg reset matches iOS / Dart contracts.
- `OfferDismissalStore.undismiss(userId)` — per-user un-dismissal (today only
  `resetAll()` existed).
- `ZeroSettleError.NotFound(message: String)` — new sealed-class variant for
  entity-not-found semantics. Plugin maps to Flutter error code `not_found`.
- `ZeroSettle.dismissPendingAction(transactionId: String): Result<Unit>` — overload that
  resolves the `PendingAction` sealed-class variant internally; returns
  `Result.failure(NotFound)` when the id isn't in the active `_pendingActions` list.
- `OfferManager.onWebCheckoutSucceeded(transactionId: String?)` — overload for
  Dart-wire-shape parity (the id is currently unused; the existing arg-less impl handles
  the actual work).
- `ZeroSettleError.CheckoutInFlight` — sealed-class variant returned by `purchase()` and
  `purchaseViaPlayBilling()` when called concurrently while another checkout is awaiting.
- `Backend.fetchTransaction(transactionId)` — typed `GET /v1/iap/transactions/<id>/`
  helper returning `Result<CheckoutTransaction>`. Used by the new awaitable purchase
  flows to refetch the canonical transaction record after checkout completion.

### Changed
- `ZeroSettle.purchase(activity, productId)` return type: `Result<String>` →
  `Result<CheckoutTransaction>`. The function is now truly awaitable end-to-end —
  launches the Custom Tab, suspends on an internal `CompletableDeferred<String>`, then
  refetches the canonical transaction via `Backend.fetchTransaction`. Concurrent calls
  fail-fast with `CheckoutInFlight`. The URL is no longer returned from this call (use
  `OfferManager.checkoutUrl()` as the explicit custom-WebView escape hatch).
- `ZeroSettle.purchaseViaPlayBilling(activity, productId)` return type: `Result<Unit>` →
  `Result<CheckoutTransaction>`. Mirror of `purchase()`'s deferred-bridge for the
  listener-driven Play Billing flow — `PurchaseSyncProcessor.process()` resolves the
  bridge with the backend's transactionId; caller refetches the hydrated record.
  Concurrent calls fail-fast with `CheckoutInFlight`.
- `PurchaseSyncProcessor` gained `onPurchaseSynced` / `onPurchaseFailed` callbacks
  (default no-op) so the listener-driven Play flow can resolve the new deferred bridge.
  Callbacks fire only from `process()` (the live listener path), never from
  `retryQueued()` — prior-session queue replays don't resolve the current awaiter.

### Notes
- Sample app and existing tests updated for the new return shapes.
- 175 tests across the `:core` suite, all passing.
- These edits land in-place on v1.0.0; no version bump.

## v1.0.0 — Android SDK rebuild — 2026-05-XX

Ground-up rewrite of the Android SDK at v1.0.0. **v0.15.x is yanked from Maven Central.**

- New artifacts: `io.zerosettle:zerosettle-android:1.0.0` (headless core) +
  `io.zerosettle:zerosettle-android-ui:1.0.0` (optional Compose components).
- `Identity` sealed class (`User` / `Anonymous` / `Deferred`); `identify()`-then-parameterless
  API surface (no per-userId overloads).
- Unified `OfferManager` with auto-bookkeeping (migration + upgrade, incl. the Android-new
  `PLAY_TO_WEB` Switch & Save flow). No migration-specific manager; no `present()` /
  `markCheckoutSucceeded()`.
- Native Play Billing origination with a persistent `PlaySyncQueue` (the Android
  `StoreKitSyncQueue`); SDK-led acknowledgement after backend confirmation; defensive ack
  after 24h when `strictAck = false` (default).
- First consumer of the `pending_actions[]` surface on `GET /v1/iap/entitlements/`
  (`PendingAction.MigrationCompletedInfo` / `PendingAction.ManualPlayCancel`).
- Deterministic UUIDv5 `appAccountToken` derivation, byte-for-byte parity with iOS and the
  backend (golden-vector locked).
- Structured `events: SharedFlow<ZeroSettleEvent>` + pluggable `ZeroSettleLogger`.
- minSdk 23; Kotlin 2.1; coroutines/Flow async model.
- Google Pay surfaces automatically inside the Stripe Payment Element on web checkout (no
  bespoke GPay button).
- `:sample` app demonstrating configure → identify → fetch → purchase (web + Play) →
  observe entitlements → accept an offer → render a pending-action banner, plus a debug screen.

### Known deviations from the design spec

- `activeEntitlements` is a derived `get()` accessor (`List<Entitlement>`), not a `StateFlow`.
- A separate `entitlementUpdates: SharedFlow<List<Entitlement>>` stream was not shipped;
  `entitlements` (a `StateFlow`) is the supported observable.
- `transferPlayOwnershipToCurrentUser` takes the OTID explicitly (2 args).
- `presentCancelFlow` / `presentUpgradeOffer` are split into a config-fetch on `:core` plus
  a `:ui` Composable (a headless core can't present a Compose sheet).
