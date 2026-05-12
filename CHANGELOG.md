# Changelog

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
