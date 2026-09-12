# Kaeru — план 2: Kodik, плеер, прогресс, Chromecast

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Нажатие «Смотреть» на телефоне и ТВ запускает нужную серию из Kodik в выбранной озвучке, прогресс внутри серии сохраняется локально, досмотренная серия отмечается в Shikimori, следующая серия стартует автоматически, на телефоне работает Chromecast.

**Architecture:** `domain` получает `EpisodeSourceProvider` (Kodik — первая реализация) и use-cases воспроизведения; `data.kodik` — токен, `get-player`, парсер страницы плеера, `/ftor`, декодер; `player` — `PlaybackController` над Media3 (`ExoPlayer` локально, `CastPlayer` при касте) + `MediaSessionService`; `ui.mobile.player` / `ui.tv.player` — экраны. Ссылки Kodik резолвятся на устройстве непосредственно перед запуском (подписи живут часы и, вероятно, привязаны к IP).

**Tech Stack:** как в плане 1 плюс Media3 1.11.0 (`media3-exoplayer`, `media3-exoplayer-hls`, `media3-session`, `media3-ui-compose`, `media3-cast`), `play-services-cast-framework` 22.3.1, `mediarouter` 1.8.1 (все уже в `gradle/libs.versions.toml`). Kotlin 2.3.21, AGP 8.13.2, JDK 21.

**Spec:** `docs/superpowers/specs/2026-09-12-kaeru-design.md` (разделы 3, 7, 8, 9). Проверенные факты о Kodik — раздел 3 и подраздел «Риск и первый spike» (дополнения от 2026-09-12). Фикстуры: `tools/fixtures/kodik_player.html`, `tools/fixtures/kodik_links.json`.

**Режим плана:** пользователь ждёт рабочий плеер; задачи крупнее обычного, полный код дан для хрупких частей (парсер/декодер Kodik, контракт контроллера, ранжирование озвучек), UI описан интерфейсами и требованиями. Исполнители — модели уровня opus; каждая задача заканчивается зелёным `:app:testDebugUnitTest` + `:app:assembleDebug` и коммитом.

## Global Constraints

- Всё из плана 1: пакет `app.kaeru`, minSdk 26, compileSdk/targetSdk 36; версии в `gradle/libs.versions.toml` не поднимать (Compose BOM 2025.08.01, Hilt 2.58, media3 1.11.0 — если media3 1.11.0 требует compileSdk 37, понизить до последней версии с API 36, а не поднимать SDK); `ui.*` зависит только от `domain` и `player`; `domain` без android/androidx; `Clock` инжектится, лямбды нет.
- Базовый URL Shikimori — `https://shikimori.io/`.
- Kodik: `KODIK_ADD_PLAYERS_URL = "https://kodik-add.com/add-players.min.js?v=2"`, `KODIK_API_URL = "https://kodik-api.com/"`, `KODIK_PLAYER_HOST = "https://kodikplayer.com"`. Все запросы к плееру с `User-Agent` браузера (Chrome), `Referer: https://kodikplayer.com/`; POST `/ftor` дополнительно `Origin: https://kodikplayer.com`, `X-Requested-With: XMLHttpRequest`. Токен из скрипта кэшируется 24 ч; при `401`/ошибке «Отсутствует или неверный токен» — сброс и одно повторное извлечение.
- HLS-запросы плеера (манифест и сегменты) с тем же `User-Agent` и `Referer`.
- Порог «просмотрено» — `AppPreferences.watchedThreshold` (по умолчанию 0.9). Отметка в Shikimori делается ровно один раз на серию; переход `planned → watching` при первой серии; после последней серии — предложение `completed` (диалог, не автоматически).
- Позиция сохраняется в `watch_state` каждые 5 с и при паузе/выходе. `WatchState.translationId` и `kodikSeason` запоминают выбор на аниме.
- Тексты ошибок только через `Throwable.toUserMessage()`; новые типы ошибок Kodik (`NoToken`, `NotFound`, `ParserBroken`, `Network`) получают свои русские тексты.
- Секреты и токены в логи не пишем.

## Структура файлов (новое)

```
app/src/main/java/app/kaeru/
  domain/model/{Translation,EpisodeStream,PlaybackTarget}.kt
  domain/source/EpisodeSourceProvider.kt
  domain/playback/{TranslationRanker,ResolveEpisodeStream,WatchProgress,MarkEpisodeWatched}.kt
  domain/repository/{WatchStateRepository,SourceRepository}.kt
  data/kodik/{KodikConstants,KodikTokenProvider,KodikApi,KodikDtos,KodikHtmlParser,KodikLinkDecoder,KodikLinkExtractor,KodikSourceProvider,KodikErrors}.kt
  data/playback/{RoomWatchStateRepository}.kt
  di/{KodikModule,PlaybackModule}.kt
  player/{PlaybackController,PlaybackState,EpisodeQueue,KaeruPlaybackService,MediaItemFactory,CastOptionsProvider,CastSessionBridge}.kt
  ui/common/player/{PlayerViewModel,PlayerUiState}.kt
  ui/mobile/player/{PlayerScreen,PlayerControls,TranslationSheet,RemoteControlScreen,CastButton}.kt
  ui/tv/player/{TvPlayerScreen,TvPlayerControls}.kt
app/src/test/java/app/kaeru/...                     # зеркально
app/src/test/resources/kodik/{player.html,links.json,add-players.js}
```

---

### Task 1: Kodik — чистые парсеры на фикстурах

**Files:**
- Create: `data/kodik/KodikConstants.kt`, `KodikErrors.kt`, `KodikHtmlParser.kt`, `KodikLinkDecoder.kt`
- Create: `app/src/test/resources/kodik/player.html` (копия `tools/fixtures/kodik_player.html`), `links.json` (копия `tools/fixtures/kodik_links.json`), `add-players.js` (фрагмент `r.token="0000000000000000000000000000abcd"` внутри минимального JS)
- Test: `data/kodik/KodikHtmlParserTest.kt`, `KodikLinkDecoderTest.kt`
- Modify: `app/build.gradle.kts` — подключить `libs.media3.exoplayer`, `libs.media3.exoplayer.hls`, `libs.media3.session`, `libs.media3.ui.compose`, `libs.media3.cast`, `libs.cast.framework`, `libs.mediarouter` (проверить сборку; если артефакт требует API 37 — понизить версию в каталоге и записать в отчёт).

**Interfaces (Produces):**
```kotlin
package app.kaeru.data.kodik

object KodikConstants {
    const val ADD_PLAYERS_URL = "https://kodik-add.com/add-players.min.js?v=2"
    const val API_URL = "https://kodik-api.com/"
    const val PLAYER_HOST = "https://kodikplayer.com"
    const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
}

sealed class KodikError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoToken(cause: Throwable? = null) : KodikError("Kodik public token unavailable", cause)
    class NotFound(val shikimoriId: Int) : KodikError("Kodik has no player for shikimori $shikimoriId")
    class ParserBroken(val step: String, cause: Throwable? = null) : KodikError("Kodik parser broke at $step", cause)
    class Network(cause: Throwable) : KodikError("Kodik network failure", cause)
}

data class KodikTranslationOption(val id: Int, val title: String, val type: TranslationType, val episodesCount: Int?, val mediaId: String, val mediaHash: String)
enum class TranslationType { VOICE, SUBTITLES }
data class KodikEpisodeOption(val number: Int, val mediaId: String, val mediaHash: String, val title: String?)
data class KodikPlayerPage(
    val domain: String, val dSign: String, val pd: String, val pdSign: String, val ref: String, val refSign: String,
    val currentType: String, val currentHash: String, val currentId: String,
    val translations: List<KodikTranslationOption>, val episodes: List<KodikEpisodeOption>, val ftorPath: String = "/ftor",
)

object KodikHtmlParser {
    /** Throws KodikError.ParserBroken(step) naming the first missing piece. */
    fun parse(html: String): KodikPlayerPage
    fun extractPublicToken(addPlayersJs: String): String?          // regex token="([a-z0-9]+)"
}

object KodikLinkDecoder {
    /** links JSON from /ftor → quality(Int) → https URL of the HLS manifest. Throws ParserBroken("links") if nothing decodes. */
    fun decode(linksJson: String): Map<Int, String>
    internal fun decodeSrc(encoded: String): String?               // ROT 0..25 + base64, must contain "manifest"
}
```

**Что парсить (см. фикстуру):**
- `var domain = "..."`, `var d_sign = "..."`, `var pd = "..."`, `var pd_sign = "..."`, `var ref = "..."`, `var ref_sign = "..."` (регулярка `\b<name>\s*=\s*"([^"]*)"`; `ref` уже декодирован, отправлять как есть).
- `vInfo.type = '...'`, `vInfo.hash = '...'`, `vInfo.id = '...'`.
- Озвучки: `<option ... value="<mediaId>" data-id="<translationId>" data-translation-type="voice|subtitles" data-media-id="..." data-media-hash="..." ...>Название (28 эп.)</option>` внутри `<div class="serial-translations-box">`/`select`. Разобрать регуляркой по `<option` с атрибутами в любом порядке; число серий — из `\((\d+) эп\.\)` в тексте, если есть. Проверить точные имена атрибутов по фикстуре и поправить парсер, а не фикстуру.
- Серии: `<option value="<number>" data-id="<mediaId>" data-hash="<mediaHash>" data-title="...">` в `serial-series-box`.
- `ftorPath` по умолчанию `/ftor`; парсер также ищет `atob("...")` в inline-скриптах и, если декодированное значение начинается с `/`, использует его (для будущей смены пути; внешний JS не грузим).

**Steps:** (1) скопировать фикстуры; (2) failing tests: `parse` даёт `currentType == "seria"`, `currentId == "1211482"`, 33 озвучки, у первой `title` начинается с `#студияБУБНЯЖА`, `episodesCount == 28`, серий 28 с номерами 1..28; `decode` даёт ключи 360/480/720 и URL, начинающиеся с `https://` и содержащие `manifest.m3u8`; `decodeSrc` на мусоре → null; `extractPublicToken` на фрагменте → `0000000000000000000000000000abcd`; (3) реализовать; (4) зелёные тесты; (5) commit `feat(kodik): html parser and link decoder on fixtures`.

---

### Task 2: Kodik — токен, get-player, /ftor, провайдер источника

**Files:**
- Create: `domain/model/Translation.kt`, `EpisodeStream.kt`; `domain/source/EpisodeSourceProvider.kt`
- Create: `data/kodik/KodikDtos.kt`, `KodikApi.kt`, `KodikTokenProvider.kt`, `KodikLinkExtractor.kt`, `KodikSourceProvider.kt`; `di/KodikModule.kt`
- Test: `KodikTokenProviderTest`, `KodikLinkExtractorTest`, `KodikSourceProviderTest` (MockWebServer; страница плеера из фикстуры с подменёнными хостами; ответы `get-player` и `/ftor` из фикстур)

**Interfaces:**
```kotlin
// domain
data class Translation(val id: Int, val title: String, val type: TranslationKind, val episodesCount: Int?, val season: Int = 1)
enum class TranslationKind { VOICE, SUBTITLES }
enum class Quality(val height: Int) { P360(360), P480(480), P720(720), P1080(1080) }
data class EpisodeStream(val animeId: Int, val episode: Int, val translation: Translation, val urls: Map<Quality, String>, val resolvedAt: Instant) {
    val best: Quality get() = urls.keys.maxBy { it.height }
}
interface EpisodeSourceProvider {
    suspend fun translations(shikimoriId: Int): Result<List<Translation>>
    suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?): Result<EpisodeStream>   // translation null → провайдер берёт первую
}

// data.kodik
interface KodikTokenProvider { suspend fun token(forceRefresh: Boolean = false): String }   // настройки → BuildConfig.KODIK_TOKEN → add-players.min.js; кэш 24 ч в @Named("prefs") DataStore (ключи kodik_token, kodik_token_at)
interface KodikApi { @FormUrlEncoded @POST("get-player") suspend fun getPlayer(@Field("token") token: String, @Field("shikimoriID") shikimoriId: Int, @Field("types") types: String = "anime,anime-serial", @Field("translations") translations: String? = null): KodikGetPlayerDto }
@Serializable data class KodikGetPlayerDto(val found: Boolean = false, val allowed: Int = 1, val quality: String? = null, val translation: String? = null, val link: String? = null, val error: String? = null)
class KodikLinkExtractor(client: OkHttpClient /*PlainClient*/, playerHost: String = PLAYER_HOST) {
    suspend fun loadPage(link: String, season: Int?, episode: Int?): KodikPlayerPage           // link вида //kodikplayer.com/serial/ID/HASH/720p → https + ?season=&episode=
    suspend fun loadPageForMedia(page: KodikPlayerPage, mediaId: String, mediaHash: String, type: String = "seria"): KodikPlayerPage   // смена озвучки: GET /serial/<mediaId>/<mediaHash>/720p?season=&episode=
    suspend fun resolveLinks(page: KodikPlayerPage): Map<Int, String>                            // POST ftorPath, поля как в спеке, bad_user=false, cdn_is_working=true
}
class KodikSourceProvider(api, tokenProvider, extractor, clock) : EpisodeSourceProvider
```

**Поведение `KodikSourceProvider`:**
- `translations(id)`: `get-player` → `found=false` → `NotFound`; `link` → `loadPage(link, null, null)` → список озвучек из страницы (`Translation(id, title без « (N эп.)», type, episodesCount)`). Кэш в памяти на 6 ч по `shikimoriId`.
- `resolve(id, episode, translation)`: страница для выбранной озвучки (`loadPageForMedia` по её `mediaId/mediaHash`, `season=1`, `episode=N`; если серии нет в `episodes` → `NotFound`), затем `resolveLinks` → `EpisodeStream`. Ответ «Отсутствует или неверный токен» → `tokenProvider.token(forceRefresh = true)` и одна повторная попытка. `IOException` → `Network`.
- Ошибки Kodik → `toUserMessage()`: `NoToken` → «Kodik недоступен: не удалось получить ключ», `NotFound` → «Серия ещё не появилась в Kodik», `ParserBroken` → «Источник обновился, ждите обновления приложения», `Network` → как сеть.
- `KodikModule`: `@Singleton` провайдер, `KodikApi` на `@PlainClient` с базой `API_URL`, отдельный `OkHttpClient` для страниц плеера с `UserAgentInterceptor(BROWSER_UA)` и `Referer`.

**Tests:** токен: настройки > BuildConfig > скрипт, кэш, forceRefresh; extractor: правильный URL страницы и поля формы `/ftor` (проверить тело запроса в MockWebServer, `ref` не URL-кодирован дважды), декодированные ссылки; provider: `NotFound` при `found=false`, повтор при ошибке токена (два запроса `get-player`), кэш озвучек. Commit `feat(kodik): public-token get-player source provider`.

---

### Task 3: Домен воспроизведения — прогресс, ранжирование, отметка в Shikimori

**Files:**
- Create: `domain/model/PlaybackTarget.kt`, `domain/repository/WatchStateRepository.kt`, `domain/playback/TranslationRanker.kt`, `ResolveEpisodeStream.kt`, `WatchProgress.kt`, `MarkEpisodeWatched.kt`
- Create: `data/playback/RoomWatchStateRepository.kt`; `AppPreferences`: `preferredTranslations: Flow<List<String>>` (названия студий по приоритету, по умолчанию `["AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza", "AniMaunt", "JAM", "Dream Cast", "SHIZA Project"]`) и `autoplayNext: Flow<Boolean>` (по умолчанию true), `defaultQuality`
- Modify: `di/PlaybackModule.kt` (bind + provide use-cases)
- Test: `TranslationRankerTest`, `ResolveEpisodeStreamTest`, `MarkEpisodeWatchedTest`, `RoomWatchStateRepositoryTest` (Robolectric)

**Interfaces:**
```kotlin
data class PlaybackTarget(val animeId: Int, val episode: Int, val startPositionMs: Long, val translation: Translation?)
interface WatchStateRepository {
    fun observe(animeId: Int): Flow<WatchState?>
    suspend fun save(state: WatchState)
    suspend fun clear(animeId: Int)
}
object TranslationRanker {
    /** rememberedId (из WatchState) > первая студия из preferred, чьё название содержится в title без учёта регистра > VOICE с max episodesCount > первая. */
    fun pick(available: List<Translation>, preferred: List<String>, rememberedId: Int?): Translation?
    fun sort(available: List<Translation>, preferred: List<String>, rememberedId: Int?): List<Translation>   // для меню выбора
}
class ResolveEpisodeStream(source: EpisodeSourceProvider, watchStates: WatchStateRepository, prefs: AppPreferences) {
    suspend operator fun invoke(animeId: Int, episode: Int, translationOverride: Translation? = null): Result<EpisodeStream>   // выбирает озвучку через ranker, запоминает translationId в WatchState
    suspend fun translations(animeId: Int): Result<List<Translation>>
}
class WatchProgress(watchStates: WatchStateRepository, clock: Clock) {
    suspend fun report(animeId: Int, episode: Int, positionMs: Long, durationMs: Long, translationId: Int?)   // upsert
}
data class WatchedOutcome(val markedEpisode: Int, val movedToWatching: Boolean, val suggestCompleted: Boolean)
class MarkEpisodeWatched(library: LibraryRepository, watchStates: WatchStateRepository, clock: Clock) {
    /** Идемпотентно: если rate.episodes >= episode — ничего не шлёт. planned/on_hold → watching перед отметкой. Последняя серия (episode >= anime.episodes > 0) → suggestCompleted = true. */
    suspend operator fun invoke(animeId: Int, episode: Int): Result<WatchedOutcome>
}
```

Tests покрывают ранжирование (remembered > preferred > voice max eps), идемпотентность отметки, переход planned→watching, suggestCompleted на последней серии, сохранение позиции. Commit `feat(playback): progress, translation ranking and shikimori marking`.

---

### Task 4: Плеер на телефоне — Media3, контроллер, экран, кнопки «Смотреть»

**Files:**
- Create: `player/PlaybackState.kt`, `PlaybackController.kt`, `EpisodeQueue.kt`, `MediaItemFactory.kt`, `KaeruPlaybackService.kt`
- Create: `ui/common/player/PlayerUiState.kt`, `PlayerViewModel.kt`
- Create: `ui/mobile/player/PlayerScreen.kt`, `PlayerControls.kt`, `TranslationSheet.kt`
- Modify: `AndroidManifest.xml` (service `KaeruPlaybackService` с `foregroundServiceType="mediaPlayback"`, intent-filter `androidx.media3.session.MediaSessionService`), `ui/mobile/Routes.kt` (`player/{animeId}/{episode}`), `MobileShell.kt`, `HomeScreen.kt` (кнопка «Смотреть» на hero → player; карточка ряда → details), `DetailsScreen.kt` (кнопка «Продолжить N серию»/«Смотреть 1 серию», список серий 1..availableEpisodes с галочками, тап по серии → player), `ui/common/theme` при необходимости
- Test: `EpisodeQueueTest`, `PlaybackControllerTest` (с фейковым `Player` из media3 `SimpleBasePlayer`/`ForwardingPlayer` или `TestExoPlayerBuilder` из `media3-test-utils`, добавить в `testImplementation`), `PlayerViewModelTest`

**Контракт контроллера:**
```kotlin
data class PlaybackState(
    val target: PlaybackTarget?, val stream: EpisodeStream?, val quality: Quality?,
    val isPlaying: Boolean, val isBuffering: Boolean, val positionMs: Long, val durationMs: Long,
    val nextEpisodeAvailable: Boolean, val autoplayCountdownSec: Int?, val error: String?, val isCasting: Boolean,
)
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    suspend fun play(target: PlaybackTarget)                 // resolve → MediaItem → prepare → seekTo(start) → play; prefetch следующей серии за 60 с до конца
    fun togglePlayPause(); fun seekTo(ms: Long); fun seekBy(ms: Long)
    suspend fun changeTranslation(translation: Translation)  // resolve той же серии, сохранить позицию
    fun changeQuality(quality: Quality)                       // замена источника с сохранением позиции
    suspend fun playNext(); fun cancelAutoplay()
    fun release()
}
```
Обязанности реализации (`DefaultPlaybackController`, `@Singleton`, живёт в `KaeruPlaybackService` через `MediaSession`): `HlsMediaSource` с `DefaultHttpDataSource.Factory().setUserAgent(BROWSER_UA).setDefaultRequestProperties(mapOf("Referer" to PLAYER_HOST + "/"))`; тикер каждые 5 с и на паузе → `WatchProgress.report`; при `position/duration >= threshold` один раз → `MarkEpisodeWatched` (результат `suggestCompleted` пробрасывается в state как событие); за 30 с до конца `nextEpisodeAvailable = true`, при `autoplayNext` — обратный отсчёт 10 с и `playNext()`; `playNext` резолвит `episode + 1` в той же озвучке (если серии нет → `NotFound` → тост «Следующая серия ещё не вышла»).

**Экран телефона (`PlayerScreen`):** полноэкранный, ландшафт (`requestedOrientation` в отдельной `PlayerActivity` либо через `LocalActivity` с возвратом), `PlayerSurface` из `media3-ui-compose` (или `AndroidView { PlayerView }` с `useController=false`), свои контролы: верх — название, «N серия», кнопки озвучки/качества/каста; низ — таймлайн `Slider`, `-10/+10`, `+85 с`, «Следующая серия»; контролы прячутся через 3 с; двойной тап по краям ±10 с; карточка следующей серии за 30 с до конца с отсчётом и кнопками «Смотреть сейчас / Отмена». Диалог «Перевести в завершённые?» при `suggestCompleted`. Ошибки — текст + «Повторить» + «Сменить озвучку».

**Навигация:** `Routes.PLAYER = "player/{animeId}/{episode}"`; hero «Смотреть» → `player(top.entry.anime.id, top.episode)`; details: главная кнопка → `nextEpisode(threshold)`; серия из списка → её номер. Иконки Material.

Commit `feat(player): media3 playback controller and phone player screen`.

---

### Task 5: Chromecast и режим пульта

**Files:**
- Create: `player/CastOptionsProvider.kt` (`DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`), `player/CastSessionBridge.kt`, `ui/mobile/player/CastButton.kt` (`MediaRouteButton` через `AndroidView`), `ui/mobile/player/RemoteControlScreen.kt`
- Modify: `AndroidManifest.xml` (`<meta-data android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME" android:value="app.kaeru.player.CastOptionsProvider"/>`), `PlaybackController` (переключение `ExoPlayer` ⇄ `CastPlayer` с переносом `MediaItem` и позиции; `MediaItem` с `MediaMetadata` `MEDIA_TYPE_TV_SHOW`, title, subtitle «N серия · озвучка», artworkUri = постер; `mimeType = application/x-mpegURL`), `PlayerScreen` (при `isCasting` показывать `RemoteControlScreen`: постер, таймлайн, серии, озвучка, громкость системная), `HomeScreen`/`DetailsScreen` — кнопка каста в шапке.
- Test: `CastSessionBridgeTest` (логика переноса позиции на фейковых плеерах), остальное — ручная проверка на устройстве: `adb logcat -s CastPlayer:* MediaRouter:*`.

Условие приёмки: серия играет на Chromecast в той же Wi-Fi-сети; при отключении каста воспроизведение продолжается локально с той же позиции; отметка серии в Shikimori срабатывает и при касте. Если Default Media Receiver не играет HLS Kodik (ошибка загрузки) — задокументировать в спеке и завести задачу на свой CAF-приёмник.

Commit `feat(cast): chromecast playback with remote-control screen`.

---

### Task 6: Плеер на Android TV

**Files:**
- Create: `ui/tv/player/TvPlayerScreen.kt`, `TvPlayerControls.kt`
- Modify: `TvApp.kt` (OK на карточке → плеер; карточка тайтла → кнопка «Смотреть N серию» и список серий), `TvHomeScreen` при необходимости
- Test: `TvPlayerKeyHandlerTest` — чистая функция `onKey(event, state): PlayerCommand?` (влево/вправо ±10 с с ускорением при удержании, центр — пауза/показать панель, вверх — полоса серий/озвучек, вниз — качество, Back — скрыть панель, затем выход с сохранением позиции).

Тот же `PlayerViewModel`/`PlaybackController`, `PlayerSurface`, панель появляется на любую кнопку и прячется через 4 с; фокус всегда виден. Commit `feat(tv): d-pad player`.

---

### Task 7: Приёмка плеера

- Обновить `docs/superpowers/manual/2026-09-12-foundation-checklist.md` разделом «Плеер» (запуск серии с главной за одно нажатие; смена озвучки; сохранение позиции после выхода; +1 серия на Shikimori после 90%; автопереход; Chromecast; ТВ D-pad) — отмечать только реально выполненное.
- Обновить спеку раздел 8 фактами о Chromecast после реальной проверки.
- README: раздел «Воспроизведение» и заметка про Kodik (публичный токен, ссылки живут часы).
- Полная проверка: `clean testDebugUnitTest lintDebug assembleDebug assembleRelease`.
- Commit `docs: playback acceptance`.
