# ZeroSettle Android Sample

A minimal Compose app exercising every v1.0 SDK flow.

## Setup

1. Edit `sample/src/main/kotlin/com/zerosettle/sample/SampleConfig.kt`:
   - `PUBLISHABLE_KEY` — a sandbox key (`zs_pk_test_…`) from the ZeroSettle dashboard.
   - `TEST_USER_ID` — any non-empty string.
   - `BASE_URL_OVERRIDE` — set to your local backend / ngrok tunnel for dev, else leave `null`.
2. For the **native Play Billing** demo: register the products in a Play Console app you
   own, mark your test account as a license tester, and install the sample from an
   internal-test track build. (Web checkout works without Play Console setup.)
3. Run: `./gradlew :sample:installDebug`.

## What it demonstrates

- `configure()` at `Application.onCreate()` (`SampleApplication`)
- `identify(Identity.User(...))` on launch (`SampleActivity`)
- Fetch products + purchase (web checkout via Custom Tab, native Play Billing) — Products screen
- Observe `entitlements` / `pendingClaims` StateFlows — Entitlements screen
- `offerManager().evaluate()` + the drop-in `ZeroSettleOfferTip` — Offers screen
- `pendingActions` StateFlow + the drop-in `ZeroSettlePendingActionBanner` — Pending screen
- Internal state inspector (sync-queue depth, `recommendedAppAccountToken()`, events log, logout) — Debug screen
- Web checkout return deep link (`zerosettle://checkout/return`) handled in `SampleActivity.onNewIntent`

## Notes

- The screens collect SDK `StateFlow`s with Compose's `collectAsState()`; in your own
  app you'd typically use `collectAsStateWithLifecycle()` (add
  `androidx.lifecycle:lifecycle-runtime-compose`).
- `ZeroSettle.playSyncQueueDepthForDebug()` is a debug-only inspector, not part of the
  supported product surface.
