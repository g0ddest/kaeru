# Android 0.6.0 → iPhone and iPad

Reference: Android `30b8c82`; iOS starting point `24c1ccc`. Both Apple device
families have equal priority. Native SwiftUI/AVKit presentation follows Apple HIG
and the artwork-led, restrained interaction of iMovie. This matrix describes
implementation scope; verification is recorded separately in `VERIFICATION.md`.

| Capability | Android source reference | iOS work |
|---|---|---|
| OAuth, durable library sync, six statuses | `data/auth`, `data/library`, `domain/sync` | Baseline implemented; physical sign-in user-confirmed |
| Per-episode resume, accidental-start cutoff, contiguous completion, rewatch/waiting | `domain/playback/ContinueTarget.kt` | Episode snapshot migration and target policy implemented; six pure tests pass |
| Watched threshold, status preservation, finale confirmation, manual unwatch/undo | `MarkEpisodeWatched.kt`, `MarkEpisodeUnwatched.kt` | Implemented in AppModel with durable outbox and undo |
| Per-title dub, user ordering, usage, availability fallback | `TranslationRanker.kt` | Durable preferences and player/detail chooser implemented |
| Quality Auto/remember, next card/countdown/cancel, PiP, +85s | `player/EpisodeQueue.kt`, mobile player | Native playback worker active |
| Opening/ending marks and skip-ending setting | `domain/playback/SkipMarks.kt` | Native playback worker active |
| Home hero/partitioned shelves, seasonal discovery | `HomeFeedBuilder.kt`, `HomeDiscover.kt` | Native iPhone/iPad shelves and shared seasonal service implemented |
| Search history/add planned, library sorting/counts/progress | common/mobile search/library | Native UI implemented |
| Detail metadata/progress/unaired tiles/dub chooser/retry | mobile details | Native UI implemented |
| Offline HLS, recovery, storage/Wi-Fi/deletion policy | `data/download`, `domain/download` | Native background transfer and settings implemented |
| New-episode checks, deduplication and notification actions | `domain/notify`, `data/notify` | Native notifications and background scheduling implemented |
| Watch Together relay/LAN, crypto, sync, chat/reactions | `domain/together`, `data/together` | Native protocol, manager, player adapter and settings UI implemented; voice capture remains device validation |
| TV login pairing | `data/pairing`, mobile pairing | Native bounded LAN client and settings UI implemented |
| Chromecast sender and media remote | `player/Cast*`, `MediaItemFactory.kt` | Official iOS SDK sender, player handoff and remote controls implemented |
| Settings, studio order, source override, about | common/mobile settings | Native UI worker active |

Not Android parity requirements: general catalog filtering, paginated search,
related-title navigation, or a visible playback-speed setting (not exposed by the
audited Android screens). Useful native playback controls may still include speed.
Android APK self-updates and D-pad television navigation do not apply to the iOS
binary; Chromecast and TV login interoperability do apply.

Protocol corrections from the source audit: Together has two symmetric peers,
sequence ordering with host tie-break, AES-128-GCM frames, and no REST room API.
TV pairing sends an OAuth code to the TV LAN endpoint; tokens stay on their owning
devices. Chromecast uses the existing Styled Media Receiver without a custom
header channel. Android new-episode notifications check actual availability;
announced release dates alone are not sufficient evidence of a new playable episode.
