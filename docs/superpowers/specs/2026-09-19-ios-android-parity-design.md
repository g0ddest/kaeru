# Native iOS feature parity with Android 0.6.0

## Scope and authority

User approved continuing autonomously through full Android feature parity after
confirming video playback and Shikimori sign-in on a physical iPad Air M3.
Android reference is main commit `30b8c82` (0.6.0); iOS baseline is `24c1ccc`.
Work stays in `feat/ios-native-app`, in the existing monorepo `ios/` and `shared/`.
Do not merge, push, publish, or change the Android release.

## Experience

The user explicitly requires Apple Human Interface Guidelines and an iMovie-like
experience. Feature parity means equivalent capabilities, not Android layouts.
Use SwiftUI system navigation, iPhone tabs, an adaptive iPad sidebar, semantic
colors, Dynamic Type, SF Symbols, native sheets/forms/context menus, and AVKit.
Artwork and video lead; controls stay restrained and familiar. Support light and
dark appearance, rotation, narrow iPad windows, VoiceOver labels, and keyboard
playback controls. iOS/iPadOS 17+ remains the deployment floor.
Neither device is secondary: iPhone and iPad have equal implementation and
verification priority, including compact one-handed use and resizable iPad windows.

Primary references: Apple's [Playing video](https://developer.apple.com/design/human-interface-guidelines/playing-video),
[Split views](https://developer.apple.com/design/human-interface-guidelines/split-views),
and [Layout](https://developer.apple.com/design/human-interface-guidelines/layout).

## Architecture and approach

Extend the existing SwiftUI/SwiftData/Keychain application. Keep Shikimori/Kodik
transport and portable domain contracts in KMP; add independent Swift services
for Apple playback, background downloads, notifications, and device integration.
Use the existing relay protocol for cross-platform sessions. Prefer native Apple
facilities where they provide the capability; do not replace Chromecast parity
with AirPlay or cross-platform Watch Together with SharePlay.

Alternatives considered: sharing Android presentation logic would weaken native
interaction; rewriting the full network core in Swift would duplicate existing
contracts. Incremental service extension keeps working authorization and playback
intact while allowing each subsystem to be verified independently.

## Functional acceptance

- Catalog/search/library/detail: match actual Android filters, sorting, metadata,
  manual library changes, per-episode progress, related titles, and account flows.
- Playback: persistent per-title translation choice and preference ordering,
  quality and speed, watched threshold, next-episode prompt/countdown/cancel,
  AniSkip opening/ending marks and automatic/manual skipping, picture in picture,
  background/media controls, and state-preserving transitions.
- Offline: durable downloads with quality/Wi-Fi/storage policies, pause/resume/
  retry/delete, expired URL resolution, downloaded playback without network,
  visible connectivity and durable synchronized library changes.
- Social/device: compatible Watch Together creation/join/leave/reconnect and
  playback synchronization; Chromecast discovery/control and TV login pairing.
- Settings/home: equivalent meaningful preferences and new-episode notifications
  supported by iOS scheduling/background constraints.

The implementation plan and parity matrix enumerate concrete Android behavior.
No unsupported function may be presented as working. Platform restrictions and
unverified external-device scenarios must be recorded separately from completed
automated or user-confirmed checks.

## Data integrity and verification

Preserve existing on-device sessions and snapshots. New fields decode old data.
Keep per-account progress/outbox isolation and fence asynchronous results against
account changes. Do not use the user's real account in write tests. Test state
machines and migrations with deterministic fixtures; build both simulator and
device targets, exercise native UI flows, and install the new build on the
already-authorized physical iPad after checks pass. Maintain a truthful feature
matrix and evidence in `ios/VERIFICATION.md`.
