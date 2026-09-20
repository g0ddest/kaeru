# Shared OAuth and poster fixes — 2026-09-19

Ready for controller integration. Implemented in the existing `feat/ios-native-app` worktree. No Swift changes, commits, or subagents.

## Behavior

- OAuth exchange/refresh emits `OAuth failed (HTTP 400): invalid_grant.` or `OAuth failed (HTTP 401): invalid_grant.` only when the OAuth response has that status and its top-level JSON `error` is a string exactly equal to `invalid_grant`. The existing `@Throws(Exception::class)` facade carries this fixed message through the NSError bridge. Other failures retain their existing status-only classification; no body, description, token, or parser cause is attached or logged. Controller session handling remains controller-owned.
- Search, deduplicated discovery, details, and library cards receive GraphQL poster enrichment. Rate-write results inherit enriched details. Requests use the existing Shikimori limiter, omit user authorization, and batch distinct IDs in groups of 50. Results map by ID, prefer nonblank `mainUrl`, then `originalUrl`, and normalize image URLs. Missing posters and failed batches retain REST artwork; subsequent batches continue. Cancellation propagates.

## Files

- `shared/src/commonMain/kotlin/app/kaeru/shared/data/shikimori/ShikimoriClient.kt`
- `shared/src/commonTest/kotlin/app/kaeru/shared/NativeApiTest.kt`
- `shared/src/commonTest/kotlin/app/kaeru/shared/PosterEnrichmentTest.kt`
- This report.

## RED

Both runs used JDK 21 and the existing Android SDK:

```sh
export JAVA_HOME=/Users/vitaliy/Library/Java/JavaVirtualMachines/temurin-21.0.12/Contents/Home
export ANDROID_HOME=/Users/vitaliy/Library/Android/sdk
./gradlew :shared:testDebugUnitTest --tests app.kaeru.shared.NativeApiTest --console=plain
./gradlew :shared:testDebugUnitTest --tests app.kaeru.shared.PosterEnrichmentTest --console=plain
```

- OAuth: 14 tests, 1 expected failure before implementation. Expected the safe grant marker; received `Request failed (HTTP 400).` Log: `/tmp/kaeru-shared-oauth-red.log`.
- Posters: 7 tests, 5 expected failures before implementation. Search/discovery/details retained legacy URLs, library made no GraphQL requests, and the GraphQL cancellation case was never reached. Log: `/tmp/kaeru-shared-posters-red.log`.

## GREEN

```sh
ANDROID_HOME=/Users/vitaliy/Library/Android/sdk \
JAVA_HOME=/Users/vitaliy/Library/Java/JavaVirtualMachines/temurin-21.0.12/Contents/Home \
./gradlew :shared:allTests \
  :shared:linkDebugFrameworkIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosArm64 --console=plain
```

- OAuth-only intermediate run: 58 tests per target; both frameworks linked. Log: `/tmp/kaeru-shared-oauth-green.log`.
- Final combined run: **BUILD SUCCESSFUL**, 10 seconds; **65 tests each** on Android debug, Android release, and iOS simulator ARM64 (**195 executions**, zero failures/errors/skips). Both simulator and device debug frameworks linked. Log: `/tmp/kaeru-shared-auth-posters-green.log`.
- Existing expect/actual Beta warnings remain. Source whitespace checks passed.
- Added coverage includes exact OAuth string/status/endpoint gating, malformed error bodies, proxy `unknown client`, credential redaction, poster ID mapping and precedence, all catalogue entry points, 50/50/1 library batching with embedded/hydrated cards, independent batch fallback, rate limiting, GraphQL/HTTP/network failures, cancellation, and empty catalogues.

The final run executes common tests on native and JVM targets. It does not rerun live iPad artwork or Swift NSError smoke; those remain the controller's integration checks.
