# Kaeru Plan 5 — Watch Together: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two phones watch one episode in sync over a link and talk over the video (ephemeral chat, six reactions, voice clips), with symmetric control and honest waits.

**Architecture:** A transport-agnostic `TogetherSession` in `domain.together` drives `PlaybackController` from encrypted `TogetherMessage` frames (JSON + AES-GCM, key from the link fragment). Transports: `LanSocketTransport` (TCP in one network, reusing the pairing server pattern) first, `RelayTransport` (OkHttp WebSocket to a Cloudflare Durable Object) second. The phone UI adds a share action, a join screen and a player overlay; App Links on the existing GitHub Pages domain carry the https link.

**Tech Stack:** Kotlin 2.3.21, Compose (Material 3), Hilt, media3 1.11.0, OkHttp 5.3.0 (WebSocket), kotlinx.serialization, `javax.crypto` AES-GCM, `MediaRecorder` (OGG/Opus), Cloudflare Workers + Durable Objects (TypeScript, `infra/relay/`), MockWebServer, Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-16-kaeru-watch-together-design.md` (research: `docs/superpowers/research/2026-09-15-watch-together-{tech,ux}.md`).

## Global Constraints

- Layering: `domain` has no `android`/`androidx`/`data` imports and no Russian strings (`java.*` and `javax.crypto` are fine); `ui.mobile` imports only `domain`, `player`, `ui.common`; data/sockets/OkHttp/MediaRecorder in `app.kaeru.data.together`; Hilt in `di`.
- No dependency version bumps; no new Android libraries (WebRTC explicitly rejected). `kotlinx-serialization-json` and OkHttp are already present.
- Gate: `JAVA_HOME=/Users/vitaliy/Library/Java/JavaVirtualMachines/temurin-21.0.12/Contents/Home ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` — green, lint 0 errors.
- Copy: Russian, sentence case, no ALL CAPS, no «·»; accent only for progress/primary/active; every wait state has a 30 s timeout and a visible exit («Смотреть дальше» / «Смотреть одному»).
- Security: roomId 64-bit random (base64url), key 128-bit in the URL fragment only; AES-GCM with a fresh 96-bit nonce per frame; frames > 64 KB rejected (voice clips chunked ≤ 32 KB); LAN transport accepts private-range hosts only; no PII in links; `RECORD_AUDIO` requested lazily.
- Sync numbers (spec §2): drift < 0.5 s ignore; 0.5–2 s → playback rate 0.97/1.03 until converged; 2–10 s → seek; > 10 s → seek + notice; `State` every 1 s, policy every 2 s; clock offset = RTT/2 from Ping/Pong (median of last 5).
- TV: nothing in v1 (no buttons); the TV may act as a LAN peer for device tests only.
- Cloudflare deployment is gated on the user's account; the Worker code and the client are written and tested regardless.

---

## File structure

```
app/src/main/java/app/kaeru/
  domain/together/RoomLink.kt                 RoomLink(roomId, key, lan: LanEndpoint?) parse/format
  domain/together/TogetherMessage.kt          sealed messages + seq
  domain/together/TogetherCodec.kt            JSON + AES-GCM (encode/decode), FrameTooLarge/Tampered errors
  domain/together/SyncPolicy.kt               decide(local, remote, offsetMs): SyncAction
  domain/together/ClockOffset.kt              ping/pong median estimator
  domain/together/WatchTogetherTransport.kt   interface + ConnectionState + LanEndpoint
  data/together/TogetherSession.kt            host()/join()/leave(); applies remote actions to PlaybackController via a small PlaybackPort interface (lives in data: owns a scope, transports and logging)
  domain/together/TogetherSessionApi.kt       TogetherSessionApi, SessionState, TogetherEvent (Notice, ChatItem, ReactionEvent, VoiceClip), NoticeKind
  data/together/LanSocketTransport.kt         ServerSocket/Socket, length-prefixed frames, private-range check
  data/together/RelayTransport.kt             OkHttp WebSocket, join by roomId, backoff reconnect
  data/together/VoiceRecorder.kt, VoicePlayer.kt
  player/TogetherPlaybackPort.kt              PlaybackPort over PlaybackController (remote-tagged actions, no echo)
  di/TogetherModule.kt
  ui/common/together/TogetherViewModel.kt, TogetherUiState.kt, TogetherCopy.kt (Russian strings)
  ui/mobile/together/JoinScreen.kt, TogetherOverlay.kt (chat stack, reactions, voice control, notices), ShareTogether.kt
  ui/mobile/player/PlayerScreen.kt / PlayerControls.kt   share action + overlay slot
  ui/mobile/MobileShell.kt, MainActivity.kt   Routes.WATCH, kaeru://watch and https App Link
infra/relay/  wrangler.toml, src/index.ts (Worker + RoomDO), README.md, deploy notes
docs/cast/w/index.html, docs/cast/.well-known/assetlinks.json   landing + App Links (published with the cast skin)
app/src/main/res/xml/network_security_config.xml   unchanged (relay is wss; LAN uses raw sockets)
```

---

### Task 1: Protocol, codec, sync policy, transports

**Files:** create `domain/together/{RoomLink,TogetherMessage,TogetherCodec,SyncPolicy,ClockOffset,WatchTogetherTransport}.kt`, `data/together/{LanSocketTransport,RelayTransport}.kt`, `di/TogetherModule.kt`; tests `domain/together/*Test.kt`, `data/together/{LanSocketTransportTest,RelayTransportTest}.kt`.

**Interfaces (Produces):**
```kotlin
data class LanEndpoint(val host: String, val port: Int)
data class RoomLink(val roomId: String, val key: ByteArray, val lan: LanEndpoint? = null) {
    fun toHttps(base: String = "https://kaeru.vitaliy.velikodniy.name/w/"): String   // "$base$roomId#<key b64url>"
    fun toLan(): String   // "kaeru://watch?h=…&p=…&r=…#<key>"
    companion object { fun parse(uri: String): Result<RoomLink>; fun random(random: SecureRandom): RoomLink }
}
sealed interface TogetherMessage { val seq: Long }  // Hello, Play, Pause, Seek, Episode, State, Chat, Reaction, Voice(chunk index/total), Ping, Pong, Bye
object TogetherCodec { fun encode(msg: TogetherMessage, key: ByteArray, nonce: ByteArray): ByteArray; fun decode(frame: ByteArray, key: ByteArray): Result<TogetherMessage> }
sealed interface SyncAction { data object None; data class Rate(val factor: Float); data class SeekTo(val positionMs: Long); data class SeekAndNotify(val positionMs: Long) }
object SyncPolicy { fun decide(localMs: Long, remoteMs: Long, remotePlaying: Boolean, localPlaying: Boolean, offsetMs: Long): SyncAction }
class ClockOffset { fun record(sentAt: Long, peerReceived: Long, peerSent: Long, receivedAt: Long); val offsetMs: Long; val rttMs: Long }
enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, CLOSED }
interface WatchTogetherTransport {
    val state: Flow<ConnectionState>
    fun connect(link: RoomLink, asHost: Boolean): Flow<TogetherMessage>   // decoded frames; errors as Result-failures inside the flow
    suspend fun send(message: TogetherMessage)
    suspend fun close()
    fun hostEndpoint(): LanEndpoint?   // LAN host only
}
```

- [ ] Tests first: `RoomLinkTest` (round trip https/lan; rejects public hosts in the lan form; key length; fragment not in the path), `TogetherCodecTest` (round trip every message; tamper → failure; frame > 64 KB → failure; nonce uniqueness), `SyncPolicyTest` (every boundary incl. offset; paused remote → local pause? no: playing state is applied by explicit Play/Pause messages, `State` only feeds drift), `ClockOffsetTest` (median of 5), `LanSocketTransportTest` (host + client on localhost exchange Hello/State; client refuses non-private host; close semantics), `RelayTransportTest` (MockWebServer WebSocket: join frame `{"room":id}`, frames forwarded, reconnect with backoff after the server closes, `state` transitions).
- [ ] Implement; `TogetherModule` binds both transports behind a `TransportFactory { fun forLink(link): WatchTogetherTransport }` (lan when `link.lan != null` and the host is private, else relay).
- [ ] Gate; commits `feat(together): protocol and codec`, `feat(together): sync policy`, `feat(together): lan and relay transports`.

### Task 2: Session engine on the player

**Files:** create `data/together/TogetherSession.kt` (contract types in `domain/together/TogetherSessionApi.kt`), `player/TogetherPlaybackPort.kt`; modify `player/PlaybackController.kt` (remote-tagged actions: `play/pause/seekTo/playEpisode` with an `origin` parameter or a parallel `applyRemote…` API that suppresses local echo), `di/TogetherModule.kt`; tests `data/together/TogetherSessionTest.kt` (fake transport + fake port), `player/TogetherPlaybackPortTest.kt`.

**Produces:**
```kotlin
interface PlaybackPort {   // domain view of the player
    val state: Flow<PortState>   // positionMs, playing, buffering, animeId, episode, translationId
    suspend fun play(); suspend fun pause(); suspend fun seekTo(ms: Long); suspend fun setRate(f: Float)
    suspend fun openEpisode(animeId: Int, episode: Int, translationId: Int?, positionMs: Long)
    val localActions: Flow<LocalAction>   // Play/Pause/Seek/Episode made by THIS viewer (not by the session)
}
class TogetherSession(transport factory, port, clock, scope) {
    val state: StateFlow<SessionState>   // Idle | Hosting(link, waiting) | Joining(link, hello?) | Live(peerName, offset, drift) | Lost(reason) | Ended
    val events: SharedFlow<TogetherEvent>   // Notice, ChatItem, ReactionEvent, VoiceClip, PeerJoined, PeerLeft
    suspend fun host(name: String): RoomLink; suspend fun join(link: RoomLink, name: String); suspend fun leave()
    suspend fun sendChat(text: String); suspend fun sendReaction(kind: ReactionKind); suspend fun sendVoice(bytes: ByteArray, durationMs: Int)
    fun watchAlone()   // exits any wait state, keeps playing locally
}
```
- [ ] Tests first: last-action-wins by seq; remote Play/Pause/Seek applied through the port with no echo; local actions sent once; `State` every 1 s and `SyncPolicy` every 2 s (virtual time); join seeks to `hello.position + offset`; episode change propagates; buffering on one side does not pause the other; waits time out at 30 s → `Lost(WAIT_TIMEOUT)`; reconnect within 5 min resumes; `Bye` → `Ended`; voice chunks reassembled in order.
- [ ] Implement; gate; commit `feat(together): session engine`.

### Task 3: Phone UI — share, join, overlay, voice; App Links

**Files:** create `ui/common/together/{TogetherViewModel,TogetherUiState,TogetherCopy}.kt`, `ui/mobile/together/{JoinScreen,TogetherOverlay,ShareTogether,VoiceButton,ReactionBurst}.kt`, `data/together/{VoiceRecorder,VoicePlayer}.kt`, `docs/cast/w/index.html`, `docs/cast/.well-known/assetlinks.json`; modify `ui/mobile/player/{PlayerScreen,PlayerControls}.kt` (top-bar «Смотреть вместе» action; overlay slot; two round buttons 😀/🎤 while live), `ui/mobile/MobileShell.kt` (`Routes.WATCH`), `MainActivity.kt` (`kaeru://watch` + `https://kaeru.vitaliy.velikodniy.name/w/*` intent filters with `android:autoVerify="true"`), `AndroidManifest.xml`, `ui/tv/**` untouched.
- Copy (from the UX report, binding): «Смотреть вместе», share text «Смотрим «<тайтл>», <N серию>. Открой в Kaeru: <ссылка>», join screen «<имя> смотрит <тайтл>, <N серия>, <мин:сек>» + «Присоединиться» / «Не сейчас», waits «Ждём друга…» / «Подключаемся…» / «<имя> догоняет…» with «Смотреть дальше», notices «<имя> поставил(а) на паузу» / «включил(а)» / «перемотал(а) на <мин:сек>» / «включил(а) <N серию>» / «подключился(ась)» / «вышел(ла)», chat placeholder «Написать…», presets, voice hint «Удерживайте, чтобы записать», errors «Связь с другом потеряна» / «Не удалось подключиться» / «Сессия закончилась» with «Смотреть одному», «У тебя другая озвучка».
- Overlay rules: bottom-left column, ≤ 3 items, 7 s life, tap expands the session history sheet; reactions ≤ 3 concurrent, 1.2 s, static when animations are off; notices top centre, one line, 3 s; voice: hold to record (≤ 30 s, waveform level), swipe left cancels, swipe up locks; incoming clip auto-plays with `AudioFocus` DUCK and shows «▶ 0:07» with replay; TalkBack `liveRegion` for incoming items; keyboard: `IME_FLAG_NO_FULLSCREEN`, input row above the timeline, presets always visible.
- [ ] Tests first: ViewModel mapping (stack expiry with virtual time, reaction cap, connection states → copy, voice permission flow), `RoomLink` handling in `MainActivity` (route extra), landing page + assetlinks content test (a unit test asserting the JSON has the release cert SHA-256 `77:4C:B7:B0:FF:31:BF:E7:DA:AC:DB:9F:05:81:8D:EE:D4:35:41:A1:07:41:7C:96:7F:B1:96:92:C6:EB:39:46` and the debug cert). Previews for the overlay (chat + reactions + voice), join screen, waits, errors.
- [ ] Implement; gate; commits `feat(together): share and join`, `feat(together): overlay chat, reactions, voice`, `feat(together): app links and landing`.

### Task 4: Relay worker

**Files:** create `infra/relay/{wrangler.toml,package.json,tsconfig.json,src/index.ts,README.md}`; Worker routes `GET /w/:room` (upgrade to WebSocket; the DO broadcasts frames to the other peer; max 2 peers; idle expiry 6 h; frames ≤ 64 KB), `GET /health`. Tests: `infra/relay/test/room.test.ts` with `vitest` + `@cloudflare/vitest-pool-workers` if installable offline via npm; otherwise a documented manual `wrangler dev` check. No Android code changes besides the relay base URL constant in `RelayTransport` (`wss://kaeru-relay.<account>.workers.dev` placeholder read from `BuildConfig.TOGETHER_RELAY_URL`, default empty → relay transport reports «Сервер не настроен»).
- [ ] Deployment gated on the user's Cloudflare account: README lists `npm i`, `npx wrangler login`, `npx wrangler deploy`, then set `TOGETHER_RELAY_URL` in `local.properties`.

### Task 5: Device QA and release 0.3.0

- Phone ↔ TV as a LAN peer (the TV runs the session engine headless for tests only): measure Kodik timeline alignment for one episode/voice (spec risk 1) and `setPlaybackParameters` behaviour on HLS (risk 2); phone ↔ phone over the relay once deployed; App Link verification (`adb shell pm get-app-links app.kaeru`); release notes; tag `v0.3.0`; GitHub pre-release.
