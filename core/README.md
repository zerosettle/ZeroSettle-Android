# `:core` — `io.zerosettle:zerosettle-android`

Headless ZeroSettle SDK. No Compose dependency — pure Kotlin + coroutines + OkHttp +
Play Billing + DataStore. The `ZeroSettle` object is a process-wide singleton.

## Configuration

```kotlin
ZeroSettle.configure(
    context,
    ZeroSettleConfig(
        publishableKey = "zs_pk_live_…",   // `zs_pk_test_…` = sandbox; the prefix is the only env signal
        playLicenseKey = null,             // optional PEM RSA key for advisory Purchase.signature checks
        syncPlayPurchases = true,          // install a PurchasesUpdatedListener + forward to the backend
        preloadCheckout = false,           // eagerly fetch web checkout config after identify()
        strictAck = false,                 // false → defensive-ack a Play purchase after 24h of failed sync
        baseUrlOverride = null,            // dev only — local backend / ngrok tunnel
        logger = LogcatLogger,             // pluggable ZeroSettleLogger
    ),
)
```

`ZeroSettle.sdkVersion` exposes the published version (wired from `BuildConfig`).

## Identity

```kotlin
sealed class Identity {
    data class User(val id: String, val name: String? = null, val email: String? = null) : Identity()
    object Anonymous : Identity()   // stable per-install UUID
    object Deferred : Identity()    // records intent only; suppresses the "no identity" warning
}

suspend fun ZeroSettle.identify(identity: Identity): Result<ProductCatalog?>
fun ZeroSettle.setCustomer(name: String? = null, email: String? = null)
fun ZeroSettle.logout()   // clears identity + customer metadata + the Play sync queue
```

`identify(User|Anonymous)` bootstraps: sync Play purchases (if enabled), fetch products,
restore entitlements, start the entitlement poller. `id` need not be a UUID — the SDK
derives a deterministic UUIDv5 `appAccountToken` from it (`recommendedAppAccountToken()`),
matching the iOS and backend derivation byte-for-byte.

## Observables

```kotlin
val products:          StateFlow<List<Product>>
val entitlements:      StateFlow<List<Entitlement>>
val activeEntitlements: List<Entitlement>            // derived accessor (not a Flow)
val pendingClaims:     StateFlow<List<PendingClaim>>  // cross-account ownership conflicts
val pendingActions:    StateFlow<List<PendingAction>> // backend-initiated prompts
val pendingCheckout:   StateFlow<Boolean>
val isConfigured:      StateFlow<Boolean>
val isBootstrapped:    StateFlow<Boolean>
val events:            SharedFlow<ZeroSettleEvent>    // structured analytics events
```

## Purchase + sync

```kotlin
suspend fun ZeroSettle.purchase(activity, productId): Result<String>          // web checkout URL
suspend fun ZeroSettle.purchaseViaPlayBilling(activity, productId): Result<Unit>  // native Play
suspend fun ZeroSettle.completeWebCheckout(callbackUrl: String): Result<Unit>     // call from the return deep link
suspend fun ZeroSettle.fetchProducts(): Result<ProductCatalog>
suspend fun ZeroSettle.restoreEntitlements(): Result<List<Entitlement>>
suspend fun ZeroSettle.fetchTransactionHistory(): Result<String>
fun        ZeroSettle.product(referenceId: String): Product?
fun        ZeroSettle.hasActiveEntitlement(referenceId: String): Boolean
```

**Play acknowledgement model.** Play auto-refunds an *unacknowledged* purchase after 3
days (unlike Apple's `Transaction.finish()`). The SDK forwards each purchase to
`POST /v1/iap/play-store-transactions/` and **only acknowledges after the backend
confirms ownership**. On backend failure it enqueues to a DataStore-backed
`PlaySyncQueue` (the Android `StoreKitSyncQueue`) — leaving the purchase unacknowledged so
Play redelivers — and retries on `[1s, 5s, 30s, 5m]` with a 5-attempt cap. After the
schedule exhausts: with `strictAck = false` (default) it defensive-acks 24h in (the user
already paid); with `strictAck = true` it never acks without backend validation and emits
a terminal `ZeroSettleEvent.SyncFailed`. The queue drains on launch, on network regain,
and after each purchase event; `logout()` clears it.

## Subscription management

```kotlin
suspend fun ZeroSettle.cancelSubscription(productId, immediate: Boolean = false): Result<Unit>
suspend fun ZeroSettle.pauseSubscription(productId, durationDays: Int? = null): Result<…>
suspend fun ZeroSettle.resumeSubscription(productId): Result<Unit>
suspend fun ZeroSettle.fetchCancelFlowConfig(): Result<CancelFlow.Config>     // server-driven retention survey + save offer
suspend fun ZeroSettle.acceptSaveOffer(productId): Result<CancelFlow.SaveOfferResult>
suspend fun ZeroSettle.fetchUpgradeOfferConfig(productId: String? = null): Result<UpgradeOffer.Config>
suspend fun ZeroSettle.transferPlayOwnershipToCurrentUser(productId, originalTransactionId): Result<Unit>
```

(`:ui` ships `ZeroSettleCancelFlow` / `ZeroSettleUpgradeOffer` Composables that render
these configs and await a result; headless callers fetch the config and present it
themselves.)

## Offers (unified migration + upgrade)

```kotlin
fun ZeroSettle.offerManager(stripeCustomerId: String? = null): OfferManager
suspend fun ZeroSettle.fetchUserOffer(productId: String? = null): Result<UserOffer.Response>
suspend fun ZeroSettle.trackMigrationConversion(source: Offer.SourceStorefront): Result<Unit>
```

`OfferManager` is a pure state machine (`LOADING → INELIGIBLE | PRESENTED → ACCEPTED → COMPLETED | DISMISSED`)
with auto-bookkeeping: `evaluate()` checks eligibility from `GET /v1/iap/user-offer/`
(dismissals persisted per-userId); `acceptOffer()` creates the web checkout (carrying the
active Play purchase token for a Play→web Switch & Save so the backend can cancel that
sub), or — for a web→web upgrade — executes the server-side plan switch and jumps straight
to `COMPLETED`; `onWebCheckoutSucceeded()` records the conversion and waits on the source
store's auto-renew to flip off before `COMPLETED`. A `503 PLAY_API_UNREACHABLE` from the
checkout surfaces as `ZeroSettleError.PlayApiUnreachable` and leaves the manager
`PRESENTED` so the host can retry. `checkoutUrl()` is the headless escape hatch.

## Pending actions

`pendingActions` carries backend-initiated prompts decoded from `pending_actions[]` on
`GET /v1/iap/entitlements/`: `PendingAction.MigrationCompletedInfo` (one-time info card)
and `PendingAction.ManualPlayCancel` (deep link to the Play subscriptions page). The
entitlement poller refreshes on foreground, after a purchase, and every ~5 min. Unknown
action types are logged and ignored (forward-compatible). `dismissPendingAction(action)`
acknowledges one.

## Errors

`ZeroSettleError` is a sealed hierarchy (18 cases) covering configuration, identity,
network, backend, checkout, Play Billing, and offer-eligibility failures. Notable:
`NotConfigured`, `UserNotIdentified`, `NetworkError`, `BackendError(statusCode, body)`,
`PlayApiUnreachable`, `CheckoutFailed`, `PurchasePending`, `OfferIneligible`.

## Events

`events: SharedFlow<ZeroSettleEvent>` — `PurchaseSucceeded`, `PurchaseFailed`,
`EntitlementsRefreshed`, `OfferShown` / `OfferAccepted` / `OfferDismissed` /
`MigrationCompleted`, `SyncFailed`, etc. Pluggable `ZeroSettleLogger` (default
`LogcatLogger`) carries the SDK's diagnostic logging.
