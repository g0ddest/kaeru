# Kaeru Plan 4 — Offline Downloads and Offline Mode: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone downloads episodes and plays them without a network, and the whole app survives being offline: home, titles, player and progress run on local data, while «watched» marks and status changes queue up and reach Shikimori once the network is back.

**Architecture:** media3 `DownloadManager`/`DownloadService` over one `SimpleCache` with query-stripped cache keys (so re-resolved Kodik links resume instead of restarting); the local player always reads through `CacheDataSource` and skips the Kodik resolve when a completed download exists. A domain `Connectivity` flow drives the offline banners and an `OutboxSyncer` that replays a Room `rate_outbox` of optimistic Shikimori writes. All new state reaches the UI through the existing ViewModels (`HomeViewModel`, `DetailsViewModel`, `PlayerViewModel`, `SettingsViewModel`) plus one new `DownloadsViewModel`.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose (Material 3 phone, tv-material TV), Hilt 2.58, Room 2.8.5 (v3 → v4), DataStore, media3 1.11.0 (`exoplayer`, `exoplayer-hls`, `datasource`), Coil 3.3.0, OkHttp 5.3.0, MockWebServer, Robolectric + `compose-ui-test-junit4` (already on the test classpath).

**Spec:** `docs/superpowers/specs/2026-09-15-kaeru-offline-design.md` (this plan argues from it; base spec `docs/superpowers/specs/2026-09-12-kaeru-design.md`).

## Global Constraints

- Layering: `domain` has no `android`/`androidx`/`data` imports and no Russian strings; `ui.mobile`/`ui.tv` import only `domain`, `player`, `ui.common`; feature packages never import each other; data implementations live in `app.kaeru.data.*`; Hilt bindings in `app.kaeru.di`.
- No dependency version bumps (Compose BOM 2025.08.01, Hilt 2.58, media3 1.11.0, Room 2.8.5 pinned). No new third-party libraries; `androidx.media3:media3-datasource` may be added explicitly at 1.11.0 only if the cache classes are not already on the classpath transitively.
- Gate for every task: `JAVA_HOME=/Users/vitaliy/Library/Java/JavaVirtualMachines/temurin-21.0.12/Contents/Home ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` — all tests green, lint 0 errors.
- Copy: Russian, sentence case, no ALL CAPS, no «·» separators; accent colour only for progress / «Смотреть» / active states / TV focus; plural helpers from `ui.common.design.Format` (`pluralEpisodes`, `pluralEpisodesAccusative`); sizes formatted by a new `formatBytes` helper («320 МБ», «1,2 ГБ»).
- Room migrations are explicit (`MIGRATION_3_4`), exported schema `app/schemas/app.kaeru.data.local.KaeruDatabase/4.json` committed, no destructive path for known versions; account-scoped tables (`rate_outbox`) are wiped on logout with the rest of the account cache; downloads are device-scoped and survive logout.
- Downloads and their UI exist on the phone only; the TV gets the offline banner and the offline player error, nothing else.
- Shikimori limits (5 rps / 90 rpm) still apply: the outbox replays sequentially, one request at a time.
- media3 unstable APIs need member-level `@OptIn(UnstableApi::class)`.
- Spec defaults: download quality 720p; «Только по Wi‑Fi» on; «Удалять просмотренные» off; storage limit 5 GB (options 2/5/10/20 GB/без лимита); size estimate for the limit check = average of completed downloads, else 400 MB; link refresh at most 3 attempts per hour per download.

---

## File structure

```
app/src/main/java/app/kaeru/
  domain/connectivity/Connectivity.kt                 interface Connectivity { val online: Flow<Boolean> }
  domain/download/EpisodeDownload.kt                  EpisodeDownload, DownloadState
  domain/download/DownloadPolicy.kt                   DownloadPolicy(limitBytes, wifiOnly, deleteWatched, quality) + fits(...)
  domain/download/DownloadRepository.kt               interface (observe/enqueue/remove/usedBytes/policy)
  domain/download/DownloadKey.kt                      DownloadKey(animeId, episode, translationId, quality) ↔ id string
  domain/error/Errors.kt                              + DownloadLimitReached, SourceUnavailableReason.OFFLINE
  domain/sync/RateOutbox.kt                           RateOp(id, animeId, kind, value, createdAt), RateOpKind
  domain/sync/RateOutboxRepository.kt                 interface
  domain/sync/OutboxSyncer.kt                         interface OutboxSyncer { suspend fun replay(): Result<Int> }
  domain/feed/HomeFeedBuilder.kt                      + DOWNLOADED row
  data/connectivity/AndroidConnectivity.kt
  data/local/RateOutboxEntity.kt, RateOutboxDao.kt, KaeruDatabase.kt (v4, MIGRATION_3_4)
  data/library/ShikimoriLibraryRepository.kt          optimistic + outbox branch; refresh() skips pending anime
  data/library/RoomRateOutboxRepository.kt
  data/library/ShikimoriOutboxSyncer.kt
  data/library/AppPreferences.kt                      + download policy keys
  data/download/DownloadCache.kt                      SimpleCache singleton + cache dir + CacheKeyFactory
  data/download/DownloadCommands.kt                   interface over DownloadService static API (testable seam)
  data/download/Media3DownloadRepository.kt           DownloadManager listener → Flow; enqueue = resolve → DownloadRequest
  data/download/DownloadRefresher.kt                  expired-link refresh with the same id
  data/download/KaeruDownloadService.kt               DownloadService + NotificationCompat foreground notification
  data/download/DownloadNotifications.kt              channel + notification builder (pure-ish text via DownloadNotificationText)
  di/OfflineModule.kt                                 bindings for Connectivity, DownloadRepository, RateOutboxRepository, OutboxSyncer, DownloadCommands, SimpleCache
  player/MediaItemFactory.kt                          CacheDataSource for the local engine
  player/PlaybackController.kt                        open() prefers a completed download
  ui/common/design/Format.kt                          + formatBytes
  ui/common/home/HomeViewModel.kt, HomeUiState.kt     + offline, downloaded row
  ui/common/details/DetailsViewModel.kt               + download states/actions, selection
  ui/common/details/EpisodeGrid.kt                    EpisodeCell + download: DownloadState?, progress
  ui/common/player/PlayerViewModel.kt                 + download action state, offline
  ui/common/downloads/DownloadsViewModel.kt, DownloadsUiState.kt
  ui/common/settings/SettingsViewModel.kt             + download policy setters
  ui/mobile/home/HomeScreen.kt                        offline strip, «Скачано» row
  ui/mobile/details/EpisodeSection.kt, DownloadSheet.kt
  ui/mobile/player/PlayerScreen.kt, PlayerControls.kt  download IconAction, offline ErrorState
  ui/mobile/downloads/DownloadsScreen.kt
  ui/mobile/settings/SettingsScreen.kt                «Загрузки» section entry + policy rows
  ui/mobile/MobileShell.kt                            Routes.DOWNLOADS
  ui/tv/home/TvHomeScreen.kt, ui/tv/player/TvPlayerScreen.kt   offline banner / error only
app/src/main/AndroidManifest.xml                      FOREGROUND_SERVICE_DATA_SYNC + service
app/schemas/app.kaeru.data.local.KaeruDatabase/4.json
```

---

### Task 1: Connectivity, rate outbox and offline-safe Shikimori writes

**Files:**
- Create: `domain/connectivity/Connectivity.kt`, `domain/sync/RateOutbox.kt`, `domain/sync/RateOutboxRepository.kt`, `domain/sync/OutboxSyncer.kt`, `data/connectivity/AndroidConnectivity.kt`, `data/local/RateOutboxEntity.kt`, `data/local/RateOutboxDao.kt`, `data/library/RoomRateOutboxRepository.kt`, `data/library/ShikimoriOutboxSyncer.kt`, `di/OfflineModule.kt`, `app/schemas/.../4.json`
- Modify: `data/local/KaeruDatabase.kt` (v4 + `MIGRATION_3_4` + entity), `di/DatabaseModule.kt` (register migration), `data/library/ShikimoriLibraryRepository.kt` (`setStatus`, `setEpisodes`, `refresh`), `data/auth/AccountSession.kt` or the account-wipe path (clear `rate_outbox` on logout), `KaeruApp.kt` (start the syncer on connectivity)
- Test: `domain/sync/OutboxReplayPlanTest.kt`, `data/local/RateOutboxMigrationTest.kt`, `data/library/ShikimoriLibraryRepositoryOfflineTest.kt`, `data/library/ShikimoriOutboxSyncerTest.kt`, `data/connectivity/AndroidConnectivityTest.kt` (Robolectric shadow)

**Interfaces:**
- Consumes: `ShikimoriApi.updateUserRate/createUserRate`, `UserRateDao`, `AccountSession.withAccount`, `toDomainFailure()`, `Clock`.
- Produces:
  ```kotlin
  interface Connectivity { val online: Flow<Boolean> }                       // distinctUntilChanged, starts with the current state
  enum class RateOpKind { STATUS, EPISODES }
  data class RateOp(val id: Long, val animeId: Int, val kind: RateOpKind, val value: String, val createdAt: Instant)
  interface RateOutboxRepository {
      fun observeAll(): Flow<List<RateOp>>            // ordered by id
      fun observePendingAnimeIds(): Flow<Set<Int>>
      suspend fun enqueue(animeId: Int, kind: RateOpKind, value: String): Long
      suspend fun remove(id: Long)
      suspend fun clear()
  }
  interface OutboxSyncer { suspend fun replay(): Result<Int> }              // returns how many ops reached Shikimori
  ```

- [ ] **Step 1: Write the failing test for the pure replay plan**

`app/src/test/java/app/kaeru/domain/sync/OutboxReplayPlanTest.kt`:
```kotlin
class OutboxReplayPlanTest {
    private fun op(id: Long, anime: Int, kind: RateOpKind, value: String) =
        RateOp(id, anime, kind, value, Instant.EPOCH.plusSeconds(id))

    @Test
    fun `ops are replayed in id order and the last value per anime and kind wins`() {
        val plan = OutboxReplayPlan.of(listOf(
            op(1, 10, RateOpKind.EPISODES, "5"),
            op(2, 10, RateOpKind.EPISODES, "6"),
            op(3, 11, RateOpKind.STATUS, "completed"),
        ))
        assertEquals(listOf(op(2, 10, RateOpKind.EPISODES, "6"), op(3, 11, RateOpKind.STATUS, "completed")), plan.toSend)
        assertEquals(setOf(1L), plan.superseded)
    }

    @Test
    fun `a status change and an episode count for the same anime are both kept, in order`() {
        val plan = OutboxReplayPlan.of(listOf(op(1, 10, RateOpKind.STATUS, "watching"), op(2, 10, RateOpKind.EPISODES, "3")))
        assertEquals(listOf(1L, 2L), plan.toSend.map { it.id })
    }
}
```

- [ ] **Step 2: Run it** — `./gradlew :app:testDebugUnitTest --tests '*OutboxReplayPlanTest*'` → FAIL (unresolved `OutboxReplayPlan`).

- [ ] **Step 3: Implement `OutboxReplayPlan` in `domain/sync/RateOutbox.kt`**

```kotlin
/** Which queued writes still have to reach Shikimori: the newest per (anime, kind), in the order they were made. */
data class OutboxReplayPlan(val toSend: List<RateOp>, val superseded: Set<Long>) {
    companion object {
        fun of(ops: List<RateOp>): OutboxReplayPlan {
            val newest = ops.groupBy { it.animeId to it.kind }.mapValues { (_, same) -> same.maxBy { it.id }.id }
            val send = ops.filter { newest[it.animeId to it.kind] == it.id }.sortedBy { it.id }
            return OutboxReplayPlan(send, ops.map { it.id }.toSet() - send.map { it.id }.toSet())
        }
    }
}
```

- [ ] **Step 4: Room entity, DAO, migration, schema** — `RateOutboxEntity(@PrimaryKey(autoGenerate = true) id: Long, animeId: Int, kind: String, value: String, createdAt: Instant)`, table `rate_outbox`, index on `animeId`; `RateOutboxDao` with `observeAll()` ordered by id, `observePendingAnimeIds(): Flow<List<Int>>` (`SELECT DISTINCT animeId`), `insert(entity): Long`, `deleteById(id)`, `deleteAll()`; `KaeruDatabase` version 4, `MIGRATION_3_4 = CREATE TABLE rate_outbox (...)` + index; write `RateOutboxMigrationTest` in the style of `WatchStateMigrationTest` (migrate 3→4 with `MigrationTestHelper`, insert a row, read it back). Commit: `feat(sync): rate outbox table`.

- [ ] **Step 5: `RoomRateOutboxRepository`** (data.library) mapping entity ↔ `RateOp`; wired into the account wipe (find where `userRateDao.deleteAll()` runs on logout and add `rateOutboxDao.deleteAll()`); test with the in-memory Room used by other repository tests.

- [ ] **Step 6: Optimistic writes** — in `ShikimoriLibraryRepository.setEpisodes/setStatus`: on `NetworkUnavailable` from the API call, apply the change to the local `UserRateEntity` (`episodes`/`status`, `updatedAt = clock.instant()`; for `setStatus` on an anime without a rate, create a local rate row with a synthetic negative id `-animeId` and `episodes = 0`), `enqueue` the op, and return `Result.success(Unit)`. Test `ShikimoriLibraryRepositoryOfflineTest` with MockWebServer returning a socket failure (`SocketPolicy.DISCONNECT_AT_START`): the local rate changes, the outbox holds the op, the result is success; a non-network error (HTTP 422) still returns failure and enqueues nothing.

- [ ] **Step 7: `refresh()` respects pending ops** — before merging server rates, read `observePendingAnimeIds().first()`; rows for those anime keep their local `UserRateEntity` (skip upsert for them). Test: pending op for anime 10 + server says episodes 2 → local stays 6.

- [ ] **Step 8: `ShikimoriOutboxSyncer.replay()`** — sequential: `OutboxReplayPlan.of(all)`; delete superseded ids first; for each op: `STATUS` → existing rate id ≥ 0 ? `updateUserRate(id, status)` : `createUserRate(...)`; `EPISODES` → `updateUserRate(rateId, episodes)`; on success delete the op and upsert the server's rate; on `NetworkUnavailable` stop and return success with the count so far; on other failures delete the op, remember the anime, and after the loop call `refreshAnime(animeId)` for each remembered id (server truth wins); a `Mutex` prevents concurrent replays. Test with MockWebServer: two ops → two PATCHes in order; disconnect on the second → first removed, second stays; 422 on an op → op removed and `refreshAnime` requested.

- [ ] **Step 9: `AndroidConnectivity`** — `callbackFlow` over `ConnectivityManager.registerDefaultNetworkCallback`; emit true only when capabilities include `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`; initial value from `activeNetwork`; `distinctUntilChanged()`; Robolectric test with `ShadowConnectivityManager`/`ShadowNetworkCapabilities` (online → offline → online).

- [ ] **Step 10: Wiring** — `OfflineModule` binds `Connectivity`, `RateOutboxRepository`, `OutboxSyncer`; `KaeruApp` (application scope) launches `connectivity.online.filter { it }.collect { syncer.replay() }`; `ShikimoriLibraryRepository.refresh()` calls `syncer.replay()` first (inject `OutboxSyncer` lazily via `Provider` to avoid a cycle, or have `HomeViewModel.refresh()` call it — choose the repository so every refresh path benefits). Gate green. Commit: `feat(sync): optimistic Shikimori writes with a replayed outbox`.

---

### Task 2: Download engine (media3) and repository

**Files:**
- Create: `domain/download/{EpisodeDownload,DownloadKey,DownloadPolicy,DownloadRepository}.kt`, `data/download/{DownloadCache,DownloadCommands,Media3DownloadRepository,DownloadRefresher,KaeruDownloadService,DownloadNotifications}.kt`, `di/OfflineModule.kt` (+ bindings)
- Modify: `domain/error/Errors.kt` (`DownloadLimitReached(limitBytes, usedBytes)`), `data/library/AppPreferences.kt` (+ policy keys), `domain/settings/SettingsStore.kt` (+ `downloadPolicy: Flow<DownloadPolicy>`, setters), `AndroidManifest.xml`
- Test: `domain/download/DownloadKeyTest.kt`, `DownloadPolicyTest.kt`, `data/download/DownloadCacheKeyTest.kt`, `Media3DownloadRepositoryTest.kt`, `DownloadRefresherTest.kt`, `DownloadNotificationTextTest.kt`

**Interfaces:**
- Consumes: `ResolveEpisodeStream(animeId, episode, translationOverride)` → `EpisodeStream(urls: Map<Quality, String>, translation, headers)`, `SettingsStore`, `Clock`.
- Produces:
  ```kotlin
  enum class DownloadState { QUEUED, DOWNLOADING, WAITING_FOR_WIFI, RESOLVING, FAILED, COMPLETED, REMOVING }
  data class DownloadKey(val animeId: Int, val episode: Int, val translationId: Int, val quality: Quality) {
      val id: String get() = "$animeId:$episode:$translationId:${quality.height}"
      companion object { fun parse(id: String): DownloadKey? }
  }
  data class EpisodeDownload(val key: DownloadKey, val state: DownloadState, val bytes: Long, val progress: Float, val failure: String?, val updatedAt: Instant)
  data class DownloadPolicy(val limitBytes: Long?, val wifiOnly: Boolean, val deleteWatched: Boolean, val quality: Quality?) {
      fun fits(usedBytes: Long, estimateBytes: Long): Boolean = limitBytes == null || usedBytes + estimateBytes <= limitBytes
      companion object { val DEFAULT = DownloadPolicy(5L * 1024 * 1024 * 1024, true, false, Quality.P720); const val FALLBACK_ESTIMATE = 400L * 1024 * 1024 }
  }
  interface DownloadRepository {
      fun observeAll(): Flow<List<EpisodeDownload>>
      fun observe(animeId: Int): Flow<List<EpisodeDownload>>
      suspend fun completed(animeId: Int, episode: Int): EpisodeDownload?
      suspend fun enqueue(animeId: Int, episode: Int, quality: Quality? = null): Result<Unit>   // DownloadLimitReached, resolve failures
      suspend fun remove(animeId: Int, episode: Int)
      suspend fun removeAll(animeId: Int)
      suspend fun removeAll()
      val usedBytes: Flow<Long>
  }
  ```
  `DownloadCommands` (data): `add(request: DownloadRequest)`, `remove(id)`, `removeAll()`, `setRequirements(Requirements)`, `resume(id)` — implemented over `DownloadService.sendAddDownload(...)` etc.; the repository depends on the interface so tests use a fake.

- [ ] **Step 1: Tests first** — `DownloadKeyTest` (round trip, parse rejects junk), `DownloadPolicyTest` (fits at the boundary; null limit always fits), `DownloadCacheKeyTest` (`https://h/seg.ts?d_sign=x&pd=1` → `https://h/seg.ts`; a URL without query unchanged), `DownloadNotificationTextTest` («Тайтл, 7 серия, 42 %»; «Загружается 3 серии»; «Скачано: Тайтл, 7 серия»). Run → RED.

- [ ] **Step 2: Domain types + `Errors.kt`** — implement the interfaces above; `DownloadLimitReached(limitBytes: Long, usedBytes: Long) : Exception()`; add `SourceUnavailableReason.OFFLINE` if the enum exists (else a new `Offline` error) and its user message «Нет сети. Скачайте серию заранее» in `ui.common.ErrorMessages`. Commit `feat(download): domain model and policy`.

- [ ] **Step 3: Settings** — `AppPreferences`: keys `download_limit_bytes` (long, −1 = unlimited), `download_wifi_only` (bool, default true), `download_delete_watched` (bool, default false), `download_quality` (int height, 0 = same as playback); `SettingsStore.downloadPolicy: Flow<DownloadPolicy>` + `setDownloadPolicy(policy)`; tests for defaults and round trip.

- [ ] **Step 4: `DownloadCache`** — `@Singleton` provider of `SimpleCache(File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))`; `object DownloadCacheKeys : CacheKeyFactory { override fun buildCacheKey(dataSpec) = dataSpec.uri.buildUpon().clearQuery().build().toString() }`; `downloadHttpFactory(headers)` = `DefaultHttpDataSource.Factory().setUserAgent(headers.userAgent).setDefaultRequestProperties(mapOf("Referer" to ..., "Origin" to ...))` mirroring `MediaItemFactory.mediaSource`.

- [ ] **Step 5: `KaeruDownloadService`** — extends `DownloadService(FOREGROUND_NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL_ID, R.string.downloads_channel, 0)`; `getDownloadManager()` from a Hilt entry point (`EntryPointAccessors.fromApplication`); `getScheduler()` = `PlatformScheduler(this, JOB_ID)`; `getForegroundNotification(downloads, notMetRequirements)` = `DownloadNotifications.progress(...)`; manifest: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>` and the service with `foregroundServiceType="dataSync"`, `exported="false"`. `DownloadNotifications` creates the channel «Загрузки» (LOW importance) and builds NotificationCompat notifications with a content intent to `MainActivity` extra `route=downloads`. Pure text in `DownloadNotificationText` (tested).

- [ ] **Step 6: `Media3DownloadRepository`** — holds the `DownloadManager` (built in `OfflineModule`: `DownloadManager(context, StandaloneDatabaseProvider, cache, httpFactory, Executors.newFixedThreadPool(2))`, `maxParallelDownloads = 2`, requirements from the policy); `observeAll()` = `callbackFlow` on `DownloadManager.Listener` (`onDownloadChanged/onDownloadRemoved/onIdle`) emitting `currentDownloads` mapped by `DownloadKey.parse(download.request.id)` (unknown ids ignored), state mapping: `STATE_QUEUED` → QUEUED (or WAITING_FOR_WIFI when `notMetRequirements != 0`), `STATE_DOWNLOADING` → DOWNLOADING, `STATE_COMPLETED` → COMPLETED, `STATE_FAILED` → FAILED with `failure = «Ссылка устарела, попробуйте позже»` after the refresher gives up, `STATE_REMOVING` → REMOVING; `bytes = download.bytesDownloaded`, `progress = percentDownloaded / 100`; `usedBytes` = sum of bytes of all downloads (`distinctUntilChanged`); `enqueue`: policy → `fits(used, estimate)` else `DownloadLimitReached`; `ResolveEpisodeStream(animeId, episode)`; pick `quality ?: policy.quality ?: stream.best` (fallback to the nearest available); `DownloadRequest.Builder(key.id, Uri.parse(url)).setMimeType(MimeTypes.APPLICATION_M3U8).setData(json)`; `commands.add(request)`; if a download for the same anime+episode with another quality/translation already exists, remove it first. `completed(animeId, episode)` reads the manager's current downloads. Tests with a fake `DownloadCommands` and a fake `DownloadManager` seam (wrap the manager behind a small `DownloadsSource` interface: `current(): List<Download>`, `addListener/removeListener`) so the repository is unit-testable without media3 runtime.

- [ ] **Step 7: `DownloadRefresher`** — listens to FAILED downloads whose `failureReason` maps to an HTTP 403/410 (`HttpDataSource.InvalidResponseCodeException`) or any failure with `bytesDownloaded > 0`; at most 3 refreshes per download per hour (`Clock`); re-resolve with `translationOverride` from the key, rebuild the request with the SAME id and new URI, `commands.add(request)` (media3 merges by id; cache keys without query keep the segments). Tests: 403 → one re-add with same id; fourth failure within an hour → left FAILED with the expiry message.

- [ ] **Step 8: Gate, commit** `feat(download): media3 download engine and repository`.

---

### Task 3: Offline playback in the player

**Files:**
- Modify: `player/MediaItemFactory.kt` (`CacheDataSource.Factory` for the local engine), `player/ExoPlaybackEngine.kt` (use the cache factory), `player/PlaybackController.kt` (`open()` prefers a completed download; offline without download → `Offline` failure), `ui/common/player/PlayerViewModel.kt` + `PlayerUiState.kt` (`offline`, `download: EpisodeDownload?`, `download()`/`removeDownload()` actions), `ui/common/ErrorMessages.kt`
- Test: `player/MediaItemFactoryCacheTest.kt`, `player/PlaybackControllerOfflineTest.kt`, `ui/common/player/PlayerViewModelDownloadTest.kt`

**Interfaces:**
- Consumes: `DownloadRepository.completed/observe/enqueue/remove`, `Connectivity.online`, `SimpleCache` + `DownloadCacheKeys` from Task 2.
- Produces: `PlayerUiState.offline: Boolean`, `PlayerUiState.download: EpisodeDownload?`, `PlayerViewModel.download()`, `PlayerViewModel.removeDownload()`.

- [ ] **Step 1: Tests first** — `MediaItemFactoryCacheTest`: the local media source's data source factory is a `CacheDataSource.Factory` with the shared cache and `DownloadCacheKeys`; `PlaybackControllerOfflineTest` (fake engine, fake `DownloadRepository`, fake `Connectivity`): (a) completed download → `open` never calls the resolver and the engine receives the download's media item; (b) offline + no download → failure `Offline` and no resolve call; (c) online + no download → resolve as before; `PlayerViewModelDownloadTest`: state exposes the download for the current episode; `download()` calls `enqueue` with the current translation's quality preference; `removeDownload()` calls `remove`.

- [ ] **Step 2: Implement** — `MediaItemFactory.mediaSource(item, headers, cache)`: `CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http).setCacheKeyFactory(DownloadCacheKeys).setFlags(FLAG_IGNORE_CACHE_ON_ERROR)`; `PlaybackController.open`: `downloads.completed(target.animeId, target.episode)?.let { play its MediaItem (from the `DownloadRequest` data: uri + mime) with the translation from the key }` else if `!connectivity.online.first()` → `fail(Offline)` else the existing resolve; the cast engine path is untouched (always resolves; when offline the cast open fails with `Offline` too). `PlayerViewModel`: combine the download flow for the current target; actions; `offline` from `Connectivity`.

- [ ] **Step 3: Gate, commit** `feat(player): play completed downloads from the cache; clear offline error`.

---

### Task 4: Offline mode on home and titles; downloads UI; settings

**Files:**
- Create: `ui/common/downloads/{DownloadsViewModel,DownloadsUiState}.kt`, `ui/mobile/downloads/DownloadsScreen.kt`, `ui/mobile/details/DownloadSheet.kt`, `ui/common/design/OfflineStrip.kt`
- Modify: `domain/feed/HomeFeedBuilder.kt` (+ `FeedKind.DOWNLOADED`, input `downloads: List<EpisodeDownload>`), `domain/model/HomeFeed.kt`, `ui/common/home/{HomeViewModel,HomeUiState,HomeContent}.kt`, `ui/mobile/home/HomeScreen.kt`, `ui/tv/home/TvHomeScreen.kt` (banner only), `ui/common/details/{DetailsViewModel,EpisodeGrid}.kt`, `ui/mobile/details/{DetailsContent,EpisodeSection}.kt`, `ui/mobile/player/{PlayerScreen,PlayerControls}.kt`, `ui/common/settings/SettingsViewModel.kt`, `ui/mobile/settings/SettingsScreen.kt`, `ui/mobile/MobileShell.kt`, `ui/common/design/Format.kt` (`formatBytes`), `MainActivity.kt` (route extra from the notification), `domain/playback/MarkEpisodeWatched.kt` (delete-watched hook via an injected `DownloadRepository` + policy), Coil image loader setup (disk cache policy; poster warm-up on enqueue in `Media3DownloadRepository` or a small `PosterWarmer` in data)
- Test: `domain/feed/HomeFeedBuilderDownloadedTest.kt`, `ui/common/details/EpisodeGridDownloadTest.kt`, `ui/common/downloads/DownloadsViewModelTest.kt`, `ui/common/home/HomeViewModelOfflineTest.kt`, `ui/common/design/FormatBytesTest.kt`, `domain/playback/MarkEpisodeWatchedDeleteTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `FeedKind.DOWNLOADED`; `EpisodeCell.download: DownloadState?`, `EpisodeCell.downloadProgress: Float?`; `DetailsViewModel.download(episodes: List<Int>, quality: Quality?)`, `removeDownload(episode)`; `DownloadsUiState(usedBytes, limitBytes, titles: List<DownloadedTitle(anime, episodes: List<EpisodeDownload>, bytes)>, policy)`; `Routes.DOWNLOADS`; `formatBytes(bytes: Long): String`.

- [ ] **Step 1: Tests first** — `HomeFeedBuilderDownloadedTest`: completed downloads of unfinished episodes make a `DOWNLOADED` row ordered by `updatedAt` desc, finished-per-threshold episodes excluded, empty when none; `EpisodeGridDownloadTest`: cells carry `download` state/progress by episode; `DownloadsViewModelTest`: grouping by title with per-title bytes, total, policy setters delegate, remove actions; `HomeViewModelOfflineTest`: `offline=true` → discovery hidden, refresh does not surface an error message; `FormatBytesTest` («320 МБ», «1,2 ГБ», «0 Б»); `MarkEpisodeWatchedDeleteTest`: with `deleteWatched` on and a completed download → `remove` called after success; off → not called.

- [ ] **Step 2: Implement domain + ViewModels** per the interfaces; `HomeViewModel` combines `Connectivity.online`, `DownloadRepository.observeAll()`, and hides discovery when offline; `DetailsViewModel` exposes per-episode download state and the selection sheet actions; `DownloadsViewModel` groups downloads by anime using `LibraryRepository`/`AnimeDao` metadata (titles outside Room → «Тайтл №id» until `refreshAnime` fills it).

- [ ] **Step 3: Phone UI** (load the `frontend-design:frontend-design` skill first) — `OfflineStrip` (one-line, surface colour, no animation) under the home top bar and on the details screen; «Скачано» row first on home; episode cells with a corner glyph (queued arrow / progress ring / check) and long-press action sheet («Скачать» / «Удалить загрузку» / «Смотреть»); `TextAction` «Скачать…» in the episodes header opening `DownloadSheet` (checkbox list of aired episodes, «Все вышедшие», «Непросмотренные», quality chips prefilled from the policy, summary «Скачать N серий (~X)», `PrimaryButton`); player top-bar `IconAction` download (states: скачать / N % / скачано) and the offline `ErrorState`; `DownloadsScreen` (`KaeruTopBar` «Загрузки», usage line + `KaeruSeekBar`-like static bar, titles with expandable episodes, per-episode and per-title delete, `DestructiveButton` «Очистить все загрузки» with confirmation, policy rows: quality chips, «Только по Wi‑Fi», «Удалять просмотренные», limit chips); settings gets a «Загрузки» row navigating to `Routes.DOWNLOADS`; `MainActivity` opens the downloads route from the notification extra. TV: `OfflineStrip` on the TV home only.

- [ ] **Step 4: Previews** for the downloads screen (empty / filled / over limit), the sheet, the offline home, episode cells in every download state.

- [ ] **Step 5: Gate, commit** in units: `feat(home): offline strip and downloaded row`, `feat(details): download states and selection sheet`, `feat(player): download action`, `feat(downloads): downloads screen and policy settings`.

---

### Task 5: Device QA and release 0.2.0

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode = 2`, `versionName = "0.2.0"`), `README.md` (offline section), `docs/superpowers/specs/2026-09-12-kaeru-design.md` §12a (mark offline done)
- Test: controller device checks (adb): download an episode on the phone, enable airplane mode, open the app (home shows «Скачано», no error), play the downloaded episode, mark it watched, disable airplane mode, confirm the mark on Shikimori via `api/v2/user_rates`; TV: airplane mode → banner, player error copy.

- [ ] **Step 1:** Bump version, update README/spec, gate, commit `chore(release): 0.2.0`.
- [ ] **Step 2:** Controller: build debug, install on both devices, run the checklist, file fix rounds as tasks 5a… until clean; then release build, tag `v0.2.0`, GitHub pre-release with the APK.
