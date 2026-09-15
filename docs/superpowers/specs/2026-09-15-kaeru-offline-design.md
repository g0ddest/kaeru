# Kaeru — план 4: офлайн-загрузки и офлайн-режим (дизайн)

Дата: 2026-09-15. Автор решений: контроллер по поручению пользователя («оффлайн загрузку и правки
ты можешь самостоятельно закрыть без моего участия; оффлайн загрузки должен включать в себя и
оффлайн режим приложения»). Совместный просмотр (план «watch-together») намеренно вынесен: его
дизайн прорабатывается вместе с пользователем позже.

Базовая спека: `docs/superpowers/specs/2026-09-12-kaeru-design.md` (§3 Kodik, §6 лимиты Shikimori,
§8 плеер, §9 главная, §12a дорожная карта). Всё ниже дополняет её, не переписывая.

## 1. Цель

Телефон умеет скачивать серии и смотреть их без сети, а приложение в целом переживает отсутствие
сети: главная, тайтлы, плеер и прогресс работают на локальных данных, отметки «просмотрено» и смены
статуса копятся и уезжают на Shikimori при появлении связи. Android TV загрузок не получает, но в
офлайне не ломается.

Критерии готовности:
1. В самолёте открыл приложение → главная показывает «Скачано» и остальные ряды из кэша, без
   ошибок; нажал скачанную серию → она играет; отметка «просмотрено» ставится локально; после
   включения сети она оказывается на Shikimori без участия пользователя.
2. Загрузка серии стартует одним действием из сетки серий или из плеера, идёт в фоне с
   уведомлением, переживает выход из приложения и истечение подписанной ссылки Kodik.
3. В настройках виден объём загрузок по тайтлам и сериям, удаление по одной и целиком, качество
   загрузки, «Только по Wi‑Fi», «Удалять просмотренные», лимит места.

## 2. Решения и почему

| Вопрос | Решение | Почему |
| --- | --- | --- |
| Движок загрузок | media3 `DownloadManager` + `DownloadService` + `SimpleCache` (`NoOpCacheEvictor`) | HLS уже в проекте (`media3-exoplayer-hls`); `DownloadHelper` сам обходит плейлист и сегменты; тот же кэш служит источником для офлайн-воспроизведения через `CacheDataSource`. Никаких новых зависимостей: `offline`/`cache` пакеты лежат в `media3-exoplayer`/`media3-datasource`. |
| Истечение ссылки Kodik | Ключ кэша = URL без query (`CacheKeyFactory`), id загрузки = `anime:episode:translation`; на HTTP 403/410 загрузка ставится на паузу, ссылка резолвится заново и загрузка перезапускается с тем же id и теми же ключами кэша | Подписи живут часы, сегменты те же; нормализованный ключ позволяет продолжить с места обрыва, а не с нуля. |
| Качество загрузки | Отдельная настройка «Качество загрузки» (360/480/720/как при просмотре, по умолчанию 720) — выбирается один плейлист Kodik нужного качества | У Kodik каждое качество — отдельный m3u8; адаптивных вариантов внутри нет, выбор один. |
| Хранилище | `context.getExternalFilesDir("downloads")`, при недоступности — internal `filesDir/downloads` | App-specific, без разрешений, чистится при удалении приложения; размер считается из `SimpleCache.cacheSpace` и `Download.bytesDownloaded`. |
| Ограничения загрузок | `Requirements(NETWORK_UNMETERED)` при «Только по Wi‑Fi» (по умолчанию включено), иначе `NETWORK`; `PlatformScheduler` для добора при возврате сети | Стандартный механизм media3, без WorkManager. |
| Уведомление | Foreground-уведомление `DownloadService` на `NotificationCompat` (канал «Загрузки»), прогресс суммарный, тап открывает раздел «Загрузки» | `DownloadNotificationHelper` требует `media3-ui`, который не подключён; NotificationCompat достаточно. |
| Офлайн-состояние | `Connectivity.online: Flow<Boolean>` (domain-интерфейс) над `ConnectivityManager.NetworkCallback` + `NetworkCapabilities.VALIDATED`; `HomeUiState.offline`, `PlayerUiState.offline` | Один источник правды для баннера, гейтинга загрузок и запуска синхронизации. |
| Отложенные записи Shikimori | Outbox в Room: `rate_outbox(id, animeId, kind{STATUS,EPISODES}, value, createdAt)`; `setStatus/setEpisodes` при `NetworkUnavailable` применяют изменение локально (optimistic) и кладут запись; `OutboxSyncer` проигрывает очередь по порядку при появлении сети, на старте и перед `refresh()`; `refresh()` не перетирает локальный rate тайтла, пока по нему есть неотправленные записи | Прогресс и отметки не должны теряться в самолёте; порядок записей = порядок действий пользователя; полная синхронизация не откатывает то, что ещё не доехало. |
| Конфликты с Shikimori | Последняя локальная запись побеждает при отправке; после успешной отправки локальный rate обновляется ответом сервера | Один пользователь, одно устройство пишет офлайн; сложнее не нужно. |
| Прогресс просмотра | Без изменений: `episode_progress`/`watch_state` уже локальные | Уже офлайн-безопасно. |
| Плеер офлайн | Локальный движок играет через `CacheDataSource` поверх того же `SimpleCache`; если серия скачана — резолв Kodik пропускается, играет `download.request.toMediaItem()`; если сети нет и серии нет — понятная ошибка «Нет сети. Скачайте серию заранее» | Скачанное играет мгновенно и без сети; сетевые ошибки не маскируются. |
| Chromecast и загрузки | Каст всегда резолвит ссылку заново; офлайн каст недоступен (приёмник не видит телефон) | Приёмник качает с CDN сам; локальный кэш ему недоступен. |
| Постеры офлайн | Coil: `diskCachePolicy ENABLED`, `networkCachePolicy` по умолчанию; для скачанных тайтлов постер прогревается в диск-кэш при постановке в очередь | Без сети главная должна быть с картинками хотя бы для скачанного. |
| Удаление просмотренных | Настройка «Удалять просмотренные» (по умолчанию выключена): при успешной отметке серии по порогу загрузка удаляется | Экономит место без лишних вопросов. |
| Лимит места | Настройка «Лимит места» (2/5/10/20 ГБ/без лимита, по умолчанию 5 ГБ): новая загрузка отклоняется с подсказкой, если `used + оценка > limit`; оценка — по среднему размеру уже скачанных серий, иначе 400 МБ | Простой предохранитель; жёсткого evict-а нет, чтобы не удалять втихую. |
| Android TV | Загрузок нет; офлайн-баннер на главной и понятная ошибка в плеере | Спека §12a: на ТВ загрузки не нужны. |

## 3. Архитектура

```
domain
  connectivity/Connectivity                      online: Flow<Boolean>
  download/DownloadRepository                    observe(): Flow<List<EpisodeDownload>>, observe(animeId),
                                                 enqueue(animeId, episode, quality?), remove(animeId, episode),
                                                 removeAll(animeId), removeAll(), usedBytes(): Flow<Long>
  download/EpisodeDownload(animeId, episode, translationId, quality, state{QUEUED, DOWNLOADING,
                           PAUSED_WIFI, RESOLVING, FAILED, COMPLETED}, bytes, progress, updatedAt)
  download/DownloadPolicy(limitBytes?, wifiOnly, deleteWatched, quality)   + pure «fits(used, estimate)»
  sync/RateOutbox(op)  RateOutboxRepository: enqueue, pending(animeId): Flow, all(), remove
  sync/OutboxSyncer    replay(): Result<Int>  (сколько отправлено)
  feed/HomeFeedBuilder  + ряд DOWNLOADED (скачанные незавершённые серии, по updatedAt)
data
  connectivity/AndroidConnectivity
  download/KaeruDownloadService (media3 DownloadService), DownloadCache (SimpleCache singleton),
           DownloadKeys (id ↔ anime/episode/translation; CacheKeyFactory без query),
           Media3DownloadRepository (DownloadManager listener → Flow; enqueue = resolve → DownloadRequest),
           DownloadRefresher (403/410 → пере-резолв → addDownload с тем же id)
  local/RateOutboxEntity + Dao; KaeruDatabase v4 (миграция 3→4 явная)
  library/ShikimoriLibraryRepository: setStatus/setEpisodes → optimistic + outbox при NetworkUnavailable;
           refresh() пропускает тайтлы с pending outbox; OutboxSyncerImpl
player
  MediaItemFactory: локальный DataSource = CacheDataSource(SimpleCache, upstream = http, keyFactory)
  PlaybackController.open: если есть COMPLETED download для (anime, episode) → без резолва
ui.common
  home: offline + downloaded row; details: download states/actions; player: download action, offline error;
  settings: раздел «Загрузки» (DownloadsViewModel)
ui.mobile: сетка серий с состояниями, лист выбора серий, экран «Загрузки», баннер офлайн
ui.tv: баннер офлайн; ничего про загрузки
```

Слои прежние: `domain` без android/androidx/data и без русских строк; `ui.*` → domain/player/ui.common.

## 4. Потоки

**Скачать серию.** UI → `DownloadRepository.enqueue(anime, ep, quality?)` → `DownloadPolicy.fits`
(отказ с `DownloadLimitReached`) → `ResolveEpisodeStream` (та же память озвучек) → URL нужного
качества → `DownloadRequest(id, uri, mimeType=HLS, data=json{anime, ep, translation, quality})` →
`DownloadService.sendAddDownload`. Состояние идёт из `DownloadManager.Listener` в `Flow` через
`callbackFlow`, id разбирается обратно в `EpisodeDownload`.

**Истёкшая ссылка.** `Download.STATE_FAILED` с `failureReason` HTTP 403/410 (или любая ошибка после
прогресса > 0) → `DownloadRefresher`: пере-резолв (`ResolveEpisodeStream` с `translationOverride`
из id) → `sendAddDownload` с тем же id; лимит попыток 3 за час, дальше `FAILED` с сообщением
«Ссылка устарела, попробуйте позже». Кэш-ключи без query → уже скачанные сегменты не качаются
повторно.

**Офлайн-воспроизведение.** `PlaybackController.open`: `downloads.completed(anime, ep)` → если
есть, `Opening` получает `MediaItem` из `DownloadRequest` и заголовки не нужны; иначе прежний резолв.
`ExoPlaybackEngine` использует `CacheDataSource` всегда (онлайн тоже читает из кэша, если сегменты
есть). Ошибка при отсутствии сети и загрузки → `SourceUnavailable(OFFLINE)` → «Нет сети. Скачайте
серию заранее» с кнопкой «Повторить».

**Отметка офлайн.** `MarkEpisodeWatched` → `setEpisodes` → HTTP падает `NetworkUnavailable` →
репозиторий пишет rate локально (`episodes = N`, `updatedAt = now`) и `rate_outbox(EPISODES, N)`,
возвращает `success`. `OutboxSyncer` (app-scoped, слушает `Connectivity.online` и вызывается из
`refresh()` первым шагом): для каждой записи по порядку — `updateUserRate`; успех → удалить запись и
обновить локальный rate ответом; `NetworkUnavailable` → остановиться, остальное ждёт; другой HTTP
(4xx) → запись удаляется, тайтл помечается на `refreshAnime` (правда сервера). `refresh()` при
слиянии списка пропускает `animeId` с pending-записями.

**Автоудаление.** `MarkEpisodeWatched` при успехе и `policy.deleteWatched` → `downloads.remove`.

**Выход из аккаунта.** Outbox очищается вместе с аккаунтными таблицами; загрузки — устройство-
специфичные и остаются (серии не привязаны к аккаунту), но ряд «Скачано» строится только по
загрузкам, у которых есть тайтл в Room; после входа в другой аккаунт `refreshAnime` дотягивает
метаданные для скачанных id.

## 5. UI (телефон)

- **Сетка серий (тайтл):** у каждой вышедшей серии маленькая пиктограмма состояния в углу ячейки
  (нет / стрелка «в очереди» / кольцо прогресса / галочка «скачано»); долгое нажатие на ячейку —
  лист действий: «Скачать» / «Удалить загрузку» / «Смотреть». В заголовке раздела «Серии» —
  `TextAction` «Скачать…», открывающий лист выбора: чекбоксы по вышедшим сериям, «Все вышедшие»,
  «Непросмотренные», итог «Скачать N серий (~X МБ)», качество — из настроек с возможностью сменить
  здесь же.
- **Плеер:** `IconAction` «Скачать» в верхней панели (состояние: скачать / качается N % / скачано);
  при офлайне и отсутствии загрузки — `ErrorState` «Нет сети. Скачайте серию заранее» + «Повторить».
- **Главная:** при офлайне тонкая полоса под шапкой «Нет сети — доступны скачанные серии» (без
  анимаций, исчезает при возврате сети); ряд «Скачано» первым (карточки серий с `episodeLine`,
  тап → плеер); ряды открытий скрыты в офлайне; pull-to-refresh офлайн показывает ту же полосу,
  не ошибку.
- **Настройки → «Загрузки»:** «Занято X ГБ из Y» + полоса; список по тайтлам (постер, название,
  «N серий, X МБ», «Удалить всё») с раскрытием по сериям («7 серия, 320 МБ», состояние, «Удалить»);
  настройки: «Качество загрузки» (чипы), «Только по Wi‑Fi» (switch), «Удалять просмотренные»
  (switch), «Лимит места» (чипы); «Очистить все загрузки» — `DestructiveButton` с подтверждением.
- **Уведомление:** заголовок «Загрузка серий», текст «<тайтл>, 7 серия, 42 %» (без «·»);
  при нескольких — «Загружается 3 серии»; завершение — «Скачано: <тайтл>, 7 серия»; ошибка — «Не удалось
  скачать <тайтл>, 7 серия».
- Копирайтинг: sentence case, без ALL CAPS и «·», акцент только для прогресса/активного.

## 6. Android TV

Только офлайн-баннер на главной («Нет сети») и ошибка плеера «Нет сети» с «Повторить». Загрузок,
экрана «Загрузки» и действий «Скачать» на ТВ нет.

## 7. Тесты

- Pure: `DownloadKeys` (id ↔ поля), `CacheKeyFactory` (query отрезается, path остаётся),
  `DownloadPolicy.fits`, ряд DOWNLOADED в `HomeFeedBuilder`, состояния ячеек серий, outbox-порядок и
  правило пропуска в `refresh()`, `OutboxSyncer` (успех / сеть упала посередине / 4xx).
- Data: Room v4 миграция; `RateOutboxDao`; `ShikimoriLibraryRepository` офлайн-ветка с
  MockWebServer (IOException → локальная запись + outbox → ответ 200 при replay → локальный rate из
  ответа); `Media3DownloadRepository` с фейковым `DownloadManager`-слоем (через интерфейс
  `DownloadCommands`), `DownloadRefresher` (403 → пере-резолв → тот же id).
- Player: `PlaybackController.open` берёт COMPLETED без резолва; офлайн без загрузки → `OFFLINE`.
- UI: ViewModel-тесты на состояния (офлайн баннер, ряд «Скачано», раздел «Загрузки»), превью.
- Устройство (контроллер): скачать серию, включить авиарежим, посмотреть, отметить, выключить
  авиарежим, проверить Shikimori.

## 8. Вне охвата

Совместный просмотр (план watch-together, проектируется с пользователем); загрузки на ТВ; DRM;
экспорт файлов; фоновые загрузки по расписанию.

## 9. Разрешения и манифест

`FOREGROUND_SERVICE_DATA_SYNC` + `<service android:name=".data.download.KaeruDownloadService"
android:foregroundServiceType="dataSync" android:exported="false">`; `POST_NOTIFICATIONS` уже есть.
`network_security_config` без изменений (CDN Kodik по HTTPS).
