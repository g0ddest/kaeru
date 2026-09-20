# iOS Android parity implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Track checked steps and evidence.

**Goal:** Reach Android 0.6.0 user-visible feature parity in a native iPhone/iPad application.
**Architecture:** Extend existing SwiftUI application and KMP online services. Apple platform subsystems remain independent native services; existing relay protocols provide cross-platform interoperability.
**Tech Stack:** SwiftUI, AVKit, SwiftData, Keychain, URLSession, UserNotifications, KMP/Ktor.
**Spec:** `docs/superpowers/specs/2026-09-19-ios-android-parity-design.md`

## Global constraints

- iOS/iPadOS 17+, native Apple HIG and iMovie-like experience.
- Existing worktree/branch, monorepo `ios/` + `shared/`; no Android changes, merge, push, or publish.
- Keep installed sessions and persisted data compatible; no real account writes in automated tests.
- No placeholders presented as features; record unresolved external dependencies honestly.

## Task 1: Reference audit and durable state

Files: `ios/PARITY.md`, `ios/Core/Models.swift`, `ios/Core/AppModel.swift`, `ios/Core/Preferences.swift`, `ios/Tests/KaeruTests/ParityStateTests.swift`.

- [ ] Build a source-linked Android feature inventory and mark existing/gap/verified states.
- [ ] Write migration/episode-isolation tests: save episode 1 at 120s, episode 2 at 30s, restore both after restarting; decode an old snapshot and retain its latest position.
- [ ] Add `progressFor(animeID:episode:)`, atomic episode history, durable playback preferences and per-title translation preferences; retain `progress` as latest-per-title index.
- [ ] Verify account switch and outbox regression tests; update physical-device evidence as user-confirmed.

## Task 2: Native playback parity

Files: `ios/Features/PlayerScreen.swift`, new files under `ios/Playback/`, playback tests.

- [ ] Extract testable skip/next-episode policy, including unknown duration, seek away, cancellation, last available episode, and no repeated automatic seek.
- [ ] Implement AniSkip integration following Android 0.6 protocol, configurable speed/threshold, native PiP/background controls, remembered translation choice and per-episode resume.
- [ ] Use AVKit controls plus focused native menus/sheets; add next episode and skip controls without replacing the system timeline.
- [ ] Verify playback state preservation, build, and exercise on device without changing real account data.

## Task 3: Catalog, library and detail parity

Files: `shared/src/commonMain/kotlin/app/kaeru/shared/`, `ios/Core/Service.swift`, native catalog/detail/settings feature files, corresponding tests.

- [ ] Extend portable request/response contracts for audited filters/metadata/library operations with wire tests.
- [ ] Add recent search queries, result library actions, separate seasonal discovery and native library sorting.
- [ ] Add detail metadata/episode actions and complete library editing, preserving durable outbox/account fencing.
- [ ] Verify network contracts and stale-response tests; exercise compact and regular layout.

## Task 4: Offline downloads and notifications

Files: new `ios/Offline/`, `ios/Notifications/`, `ios/Features/DownloadsView.swift`, app delegate integration, tests.

- [ ] Implement native background HLS/progressive downloads, persisted catalog, task reconciliation, progress, pause/retry/remove and storage policies.
- [ ] Expose `localAsset(animeID:episode:translation:)` for network-independent playback and enqueue entry points in detail/player.
- [ ] Add connectivity observation and retry durable writes on reconnection; add opt-in episode notification scheduling and deep-link routing.
- [ ] Verify persistence/reconciliation/storage/notification policies with local fixtures and native build.

## Task 5: Watch Together and TV pairing

Files: new `ios/Together/`, `ios/Device/`, native sheets and routing, protocol tests.

- [ ] Implement the exact existing relay HTTP/WebSocket DTOs, two-peer sequence ordering, AES-GCM frames, clock adjustment, reconnect and leave behavior.
- [ ] Add native create/join/share/participants controls and symmetric player synchronization with host tie-break and no feedback loop, chat/reactions/voice.
- [ ] Add TV pairing URL/code confirmation using the TV LAN HTTP endpoint, explicit account confirmation before transfer.
- [ ] Verify protocol fixtures, malformed links, reconnect/peer-return and stale message rejection.

## Task 6: Chromecast

Files: new `ios/Cast/`, dependency/project configuration and integration tests.

- [ ] Integrate official Google Cast iOS sender SDK and existing receiver ID; implement HLS source and media metadata per Styled Media Receiver contract (no custom header channel).
- [ ] Add native discovery/controller presentation, local-to-remote position handoff and remote-to-local return.
- [ ] Share progress/next-episode semantics, verify build and installed-device behavior with available receiver.

## Task 7: Integration and review

- [ ] Generate Xcode project, run shared/iOS tests, build simulator and physical device.
- [ ] Review all new data/race/error paths and native accessibility/adaptive layouts; fix material findings.
- [ ] Update parity/evidence documents with actual results and external restrictions.
- [ ] Install signed build on the connected iPad, preserve data, verify launch; commit reviewed work in the existing branch.
