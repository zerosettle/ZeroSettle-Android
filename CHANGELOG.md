# Changelog

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
