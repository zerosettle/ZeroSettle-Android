# CLAUDE.md - ZeroSettle Android SDK

## Overview
`zerosettle-android` is the native Android/Kotlin SDK. It provides the public API for configuration, product catalog, payment sheet presentation, entitlement management, and subscription lifecycle.

## Key Files
* `sdk/src/main/kotlin/com/zerosettle/sdk/ZeroSettle.kt` — Singleton entry point
* `sdk/src/main/kotlin/com/zerosettle/sdk/model/` — All public model types (`Product`, `Price`, `Entitlement`, `ZSTransaction`, etc.)
* `sdk/src/main/kotlin/com/zerosettle/sdk/internal/` — Internal implementation details

## Cross-Framework API Compatibility
**This is a source SDK.** Changes to its public API surface affect the Flutter wrapper:

| Wrapper | Bridge File |
|---------|-------------|
| Flutter (`zerosettle`) | `ZeroSettle-Flutter/android/src/main/kotlin/com/zerosettle/flutter/ZeroSettlePlugin.kt` |

### Before changing any public API:
1. **Audit impact** — Identify which wrapper bridge files reference the type/method being changed
2. **Update wrappers** — Modify bridge code and Dart models to match
3. **Build all targets** — Verify the Flutter wrapper compiles against the updated SDK
4. **Run all tests** — `./gradlew test` for SDK, `flutter test` for Flutter wrapper

## Backward Compatibility
**Never introduce breaking changes unless explicitly approved by the user.** The SDK is consumed by third-party apps — breaking their builds or runtime behavior is unacceptable.

Safe (non-breaking) changes:
* Adding new optional properties with defaults (`null`/`false`/`0`)
* Adding new enum entries (when serialized with `@SerialName` and decoded leniently)
* Adding new API response fields (old clients ignore unknown keys via `ignoreUnknownKeys`)
* Adding new methods or types
* Adding new optional parameters with defaults to existing methods

Breaking changes (require explicit approval):
* Removing or renaming public types, methods, properties, or enum entries
* Changing method signatures (parameter types, return types, suspend vs non-suspend)
* Changing `@SerialName` values (breaks wire compatibility)
* Removing API response fields that clients depend on
* Changing default values in ways that alter existing behavior

## Coding Standards
* **Serialization:** kotlinx.serialization with `@SerialName` for wire format keys
* **Error Handling:** Use `ZSException` sealed hierarchy. Never crash silently.
* **Neutral Language:** Use "External Purchase," "Alternative Billing," or "Web Checkout" — never "bypass" or "evade."
