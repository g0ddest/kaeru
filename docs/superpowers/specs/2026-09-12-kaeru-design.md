# Kaeru — дизайн приложения

Дата: 2026-09-12
Статус: утверждено к планированию

## 1. Назначение

Kaeru — клиент для Android и Android TV, который за одно нажатие запускает
следующую серию аниме. Источник списков и прогресса — Shikimori. Источник
видео — Kodik. На телефоне обязательна поддержка Chromecast. Уровень
проработки интерфейса — как у Netflix и Crunchyroll.

Пользователи: автор и друзья. Распространение — APK в GitHub Releases.
Google Play не рассматривается.

### Критерии успеха

- Холодный старт до интерактивной главной из кэша: менее 1 секунды.
- От запуска до начала воспроизведения последней недосмотренной или новой
  серии: одно нажатие OK на ТВ, один тап на телефоне.
- Прогресс по сериям виден одинаково на телефоне, ТВ и на сайте Shikimori.
- Поломка парсера Kodik локализована в одном пакете и чинится правкой одной
  функции и обновлением фикстуры.

## 2. Решения, принятые при проектировании

| Вопрос | Решение | Почему |
|---|---|---|
| Стек | Kotlin, Jetpack Compose, Compose for TV, Media3, Cast SDK | Нативные Cast и D-pad, один язык |
| Структура | Один Gradle-модуль `app`, пакеты `domain` / `data` / `player` / `ui.mobile` / `ui.tv` | Минимум обвязки для команды из 1–2 человек; вынести в модули можно позже |
| Бэкенд | Нет. Shikimori — хранилище прогресса и авторизация | Не усложнять |
| Синк | Двусторонний с Shikimori по эпизодам; позиция внутри серии локально | Телефон и ТВ видят одно и то же |
| Источник видео | Kodik напрямую, поиск по `shikimori_id` | Kodik сам публикует механизм поиска по shikimori_id |
| Токен Kodik | Настройки → BuildConfig → автоизвлечение из `kodik-add.com/add-players.min.js` | Регистрация у Kodik без сайта невозможна; публичный токен обновляет сам Kodik |
| Фолбэк поиска | AnimeGO как вторая реализация `EpisodeSourceProvider`, не в первой версии | Ещё один хрупкий парсер, нужен только если Kodik-путь отвалится |
| Тема | Только тёмная | Контент как оформление, как у Netflix |

## 3. Факты об внешних сервисах, проверенные 2026-09-12

- Домен `kodikapi.com` не резолвится нигде. Актуальный домен API —
  `kodik-api.com`. Хост плеера — `kodikplayer.com`.
- Публичная страница `https://kodikplayer.com/find-player?shikimoriID=<id>&types=anime,anime-serial`
  работает без регистрации. Она подгружает `//kodik-add.com/add-players.min.js?v=2`,
  который содержит `token="<...>"` и вызывает `POST https://kodik-api.com/get-player`
  с параметрами `shikimoriID`, `types`, `translations`, `season`, `episode`.
- Гуляющий токен из примеров в интернете (`q8p5...`) мёртв: `search`
  отвечает 401 «Отсутствует или неверный токен».
- Ссылка на видео добывается со страницы плеера: из HTML берутся `urlParams`
  (`d`, `d_sign`, `pd`, `pd_sign`, `ref_sign`), `hash`, `id`, `type` и путь
  служебного POST; ответ расшифровывается сдвигом ROT (перебор 0–25) с
  последующим base64; результат содержит `mp4:hls:manifest`. Kodik отдаёт
  отдельный манифест на каждое качество (360/480/720, иногда 1080), не
  адаптивный мастер-плейлист.
- Лимиты Shikimori: 5 rps, 90 rpm, обязательный заголовок `User-Agent`.

## 4. Архитектура

```
app/
  domain/        чистый Kotlin: сущности, интерфейсы репозиториев, use-cases
  data/
    shikimori/   Retrofit API, OAuth, маппинг DTO → domain
    kodik/       KodikApi, KodikTokenProvider, KodikLinkExtractor, парсер HTML
    local/       Room (кэш аниме, user_rates, WatchState, PendingSync, KodikSourceCache), DataStore
    sync/        WorkManager-джобы, очередь отложенных записей
  player/        PlaybackController, ExoPlayer, CastPlayer, MediaSessionService
  ui/
    common/      тема, токены дизайна, общие composable (постер, прогресс, скелетоны)
    mobile/      Material 3 экраны, нижняя навигация, жесты плеера
    tv/          Compose for TV экраны, drawer, D-pad плеер
  MainActivity          обычный launcher
  TvActivity            LEANBACK_LAUNCHER
```

DI — Hilt. Асинхронность — coroutines и Flow. Один APK на оба форм-фактора;
форм-фактор определяется по `UI_MODE_TYPE_TELEVISION`, но каждая activity
привязана к своему набору экранов явно, без ветвлений внутри composable.

Правила границ:
- `ui.*` зависит только от `domain` и `player`.
- `data.kodik` не знает про Shikimori, кроме числа `shikimoriId`.
- Парсеры и декодеры в `data.kodik` — чистые функции `String -> Result<T>`.

## 5. Домен

### Сущности

- `Anime`: `id`, `nameRu`, `nameRomaji`, `poster`, `screenshots`, `status`
  (`ongoing | released | anons`), `episodes`, `episodesAired`, `nextEpisodeAt`,
  `score`, `year`, `studio`.
- `UserRate`: `animeId`, `status` (`watching | planned | completed | onHold | dropped`),
  `episodes`, `updatedAt`. Источник правды по просмотренным сериям — Shikimori.
- `WatchState` (локально): `animeId`, `episode`, `positionMs`, `durationMs`,
  `translationId`, `kodikSeason`, `updatedAt`.
- `Translation`: `id`, `title`, `type` (`voice | subtitles`), `episodesCount`, `playerLink`.
- `EpisodeStream`: `Map<Quality, HlsUrl>`, `resolvedAt`.
- `Preferences`: приоритет озвучек, токен Kodik, автовоспроизведение,
  качество по умолчанию, порог просмотра (по умолчанию 90%).

### Производные списки (вычисляются, не хранятся)

- **Продолжить**: есть `WatchState` с `positionMs / durationMs` ниже порога.
- **Новые серии**: `status = watching`, `anime.status = ongoing`,
  `episodesAired > userRate.episodes`. Сортировка по дате выхода серии.
- **Следующая серия**: `status = watching`, не онгоинг, `userRate.episodes < episodes`.
- **Скоро**: онгоинги из watching с `nextEpisodeAt` в ближайшие 7 дней.
- **Верхняя карточка главной**: первый элемент из «Продолжить», иначе первый
  из «Новые серии», иначе из «Следующая серия».

### Use-cases

`GetHomeFeed`, `GetAnimeDetails`, `ResolveEpisodeStream(animeId, episode, translationId?)`,
`ReportPlaybackProgress`, `MarkEpisodeWatched`, `ChangeListStatus`,
`SearchAnime`, `SyncUserLists`, `PrefetchTopCardStream`.

## 6. Shikimori

### Авторизация

OAuth2 Authorization Code. `client_id` и `client_secret` — из
`local.properties` в BuildConfig. Scope: `user_rates`.

- Телефон: Custom Tabs, redirect `kaeru://oauth`.
- ТВ: экран с QR-кодом и короткой ссылкой на страницу авторизации Shikimori
  с `redirect_uri=urn:ietf:wg:oauth:2.0:oob`; пользователь вводит показанный
  код с пульта. Если телефон с Kaeru в той же сети — передача сессии через
  локальный QR не делается в первой версии, ввод кода достаточен.
- Токены в DataStore Preferences в приватной директории приложения;
  `allowBackup=false`. Refresh — в OkHttp `Authenticator`.

### Запросы

- `GET /api/users/whoami`
- `GET /api/v2/user_rates?user_id&target_type=Anime&status=...`
- `GET /api/animes?ids=...&limit=50` для деталей и `episodes_aired`
- `GET /api/animes/{id}` и `/screenshots` для карточки
- `GET /api/animes?search=...`
- `PATCH /api/v2/user_rates/{id}` — эпизоды и статус
- `POST /api/v2/user_rates` — добавление в список

Общий rate-limiter в OkHttp-интерцепторе: не более 5 запросов в секунду и
90 в минуту. `User-Agent: Kaeru/<version>`.

### Синк

- При старте и возврате на главную: `user_rates` со статусом watching и
  planned, затем `animes?ids=` пачками по 50. Главная сначала показывается из
  Room, обновление приходит через Flow.
- Раз в сутки WorkManager: полный ресинк всех статусов.
- Раз в 3 часа WorkManager (телефон): проверка `episodes_aired` для watching-
  онгоингов; при появлении новой серии — локальное уведомление с действием
  «Смотреть». На ТВ фоновые джобы не ставятся.
- Запись прогресса: при достижении порога просмотра — `PATCH episodes + 1`.
  Первая серия из planned переводит в watching. Последняя серия — диалог
  «Перевести в завершённые?». Все записи проходят через очередь `PendingSync`
  в Room и отправляются при наличии сети; повтор с экспоненциальной паузой.
- Конфликт: побеждает большее значение `episodes`. `WatchState` для серий
  ниже `userRate.episodes` считается устаревшим и очищается.

## 7. Kodik

### Интерфейс

```kotlin
interface EpisodeSourceProvider {
    suspend fun translations(shikimoriId: Int): Result<List<Translation>>
    suspend fun resolve(translation: Translation, season: Int, episode: Int): Result<EpisodeStream>
}
```

Первая реализация — `KodikSourceProvider`. Место для `AnimeGoSourceProvider`
зарезервировано; `CompositeSourceProvider` перебирает провайдеров по порядку.

### KodikTokenProvider

Порядок: значение из настроек → `BuildConfig.KODIK_TOKEN` (может быть пустым)
→ автоизвлечение. Автоизвлечение: GET `https://kodik-add.com/add-players.min.js?v=2`,
регулярка `token="([a-z0-9]+)"`, кэш в DataStore на 24 часа. При ответе API
401 кэш сбрасывается и извлечение повторяется один раз; вторая неудача —
ошибка `NoToken`.

### KodikApi

`POST https://kodik-api.com/search` с `token`, `shikimori_id`,
`with_episodes=true`, `limit=100`. Результаты группируются по `translation.id`;
для каждой озвучки — `link`, `episodes_count`, `last_season`, `seasons`.
Кэш `KodikSourceCache` в Room: TTL 6 часов для онгоингов, 7 дней для
завершённых, принудительное обновление при pull-to-refresh и при отсутствии
нужной серии в кэше.

### KodikLinkExtractor

1. Собрать URL страницы: хост `kodikplayer.com`, путь из `link`,
   `?season=N&episode=M`.
2. GET с заголовком `User-Agent` браузера и `Referer: https://kodikplayer.com/`.
3. `KodikHtmlParser.parse(html)` — чистая функция: `urlParams`, `hash`, `id`,
   `type`, путь POST-эндпоинта, список озвучек на странице.
4. POST формой (`hash`, `id`, `type`, `d`, `d_sign`, `pd`, `pd_sign`, `ref=""`,
   `ref_sign`, `bad_user=true`, `cdn_is_working=true`) с заголовками
   `X-Requested-With: XMLHttpRequest`, `Origin`, `Referer`.
5. `KodikLinkDecoder.decode(json)` — чистая функция: для каждого качества
   перебор ROT 0–25, base64, проверка на `mp4:hls:manifest`, нормализация
   `//` → `https://`.

Тесты: HTML-страница и JSON-ответ сохраняются в `src/test/resources/kodik/` и
покрывают парсер и декодер. При смене вёрстки обновляется фикстура и одна
функция.

### Выбор озвучки и сезона

- Ранжирование: приоритетный список пользователя → озвучка с наибольшим
  `episodes_count` → первая по списку. Выбор сохраняется в `WatchState.translationId`.
- Сезон: `last_season` из ответа. Если в ответе несколько сезонов и нумерация
  не совпадает с Shikimori (`episodes_count` сезона меньше `anime.episodes`),
  показывается выбор сезона один раз, результат сохраняется в `WatchState.kodikSeason`.

### Фильмы

Страница плеера для фильма несёт `vInfo.type = 'video'`, а не `'seria'`, и не
содержит ни списка серий, ни списка сезонов; блок озвучек лежит в
`movie-translations-box` вместо `serial-translations-box`, но использует тот
же формат `<option>`. Резолв для фильма ходит на `/video/<mediaId>/<mediaHash>/720p`
без `season`/`episode`, а `EpisodeStream.episode` фиксирован в 1 независимо от
того, какая серия была запрошена. Проверено на фикстуре
`app/src/test/resources/kodik/movie.html` (план 2, Задача 2, 2026-09-12).

### Ошибки

`NoToken`, `NotFound`, `ParserBroken(step)`, `Network`. UI показывает
человеческий текст и кнопку «Повторить»; для `ParserBroken` — текст
«Источник обновился, ждите обновления приложения» и ссылку на GitHub Releases.

## 8. Плеер и Chromecast

### PlaybackController

Обёртка над `androidx.media3.common.Player`. Внутри `ExoPlayer` (локально) и
`CastPlayer` из `media3-cast` (при активной сессии). Переключение при
`SessionManagerListener.onSessionStarted/Ended` с переносом текущего
`MediaItem` и позиции. UI, синк и автопереход работают с контроллером и не
знают, какой плеер активен.

Обязанности контроллера:
- Загрузка `EpisodeStream`, выбор качества (по умолчанию максимальное,
  на касте — максимальное доступное), смена качества заменой источника с
  сохранением позиции.
- Каждые 5 секунд и при паузе — `WatchState` в Room.
- При достижении порога просмотра — `MarkEpisodeWatched` ровно один раз на серию.
- За 30 секунд до конца — событие `NextEpisodeAvailable`; при включённом
  автовоспроизведении следующая серия резолвится заранее и добавляется в
  очередь `MediaItem`.

### Локальное воспроизведение

- `MediaSessionService` в foreground, уведомление с управлением, поддержка
  наушников и Bluetooth.
- HLS через `HlsMediaSource`, заголовки `Referer`/`User-Agent` в `DataSource.Factory`.
- Телефон: ландшафт, double-tap ±10 с по краям, вертикальные свайпы яркости
  (слева) и громкости (справа), кнопки «+85 с» и «Следующая серия».
- ТВ: любая кнопка показывает панель; влево/вправо ±10 с с ускорением при
  удержании; вверх — полоса серий и озвучек; вниз — качество; Back сначала
  прячет панель, потом выходит с сохранением позиции.

### Chromecast (телефон)

- `CastOptionsProvider` с `DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`.
- `MediaRouteButton` на главной, на экране тайтла и в плеере.
- `MediaInfo`: `MediaMetadata.MEDIA_TYPE_TV_SHOW`, название аниме, номер и
  название серии, постер; `contentType = application/x-mpegURL`.
- Позиция и завершение читаются из `CastPlayer`; синк с Shikimori работает
  одинаково.
- При касте экран телефона становится пультом: постер, таймлайн, серии,
  озвучка, громкость, отключение.

### Риск и первый spike

Неизвестно, воспроизведёт ли Default Media Receiver HLS Kodik: приёмник
требует CORS на манифесте и сегментах; ссылки подписаны и могут быть
привязаны к IP. Веб-плеер Kodik играет через hls.js, для которого CORS тоже
обязателен, поэтому ожидание положительное. Проверяется первым spike-ом
реализации: резолв ссылки скриптом и запуск на Chromecast. Запасной путь —
собственный CAF-приёмник на GitHub Pages с подстановкой заголовков через
`playbackConfig.manifestRequestHandler`.

Результат spike от 2026-09-12: резолв остановился до получения HLS-
ссылки: публичный токен, извлечённый из `add-players.min.js?v=2`,
отклонён `kodik-api.com/search` с ошибкой «Отсутствует или неверный
токен». Ожидаемый формат `https://cloud.kodik-storage.com/.../hls:manifest.m3u8`
не был получен; CORS-заголовки манифеста и сегмента, локальное
воспроизведение и каст не проверялись. Риск Default Media Receiver остаётся
незакрытым.

Дополнение от 2026-09-12 (вечер): публичный токен из `add-players.min.js`
работает с методом `POST kodik-api.com/get-player` (параметры `shikimoriID`,
`types=anime,anime-serial`, опционально `translations`), который сам скрипт
Kodik и использует, и отклоняется только методом `search`. `get-player`
возвращает `found`, `quality`, `translation` и `link` на страницу плеера.
Страница плеера (`kodikplayer.com/serial/<id>/<hash>/720p?season=N&episode=M`)
содержит `urlParams`, полный список озвучек с числом серий (`<option data-id
data-translation-type>`) и список серий с `data-id`/`data-hash` на каждую
серию. Следовательно, поиск по `shikimori_id` без частного токена возможен:
`get-player` → страница плеера → выбор озвучки/серии → POST за HLS. План 2
должен строиться на `get-player`, а не на `search`.

Результат дорезки spike (2026-09-12, поздно вечером): цепочка доведена до HLS
без частного токена. Страница плеера содержит `vInfo.type='seria'`,
`vInfo.hash`, `vInfo.id` для текущей серии и переменные `domain`, `d_sign`,
`pd`, `pd_sign`, `ref`, `ref_sign`. `POST kodikplayer.com/ftor` (путь берётся
из `atob("L2Z0b3I=")` в `app.serial.*.js`) с полями `d, d_sign, pd, pd_sign,
ref (декодированный), ref_sign, type=seria, hash, id, bad_user=false,
cdn_is_working=true` и заголовками `Referer`/`Origin`/`X-Requested-With`
возвращает JSON `links` с качествами 360/480/720; `src` расшифровывается
перебором ROT 0–25 + base64. Ссылка вида
`https://cloud.solodcdn.com/useruploads/<uuid>/<sig>:<YYYYMMDDHH>/720.mp4:hls:manifest.m3u8`
делает 302 на `harmony.cloud.solodcdn.com`, манифест — VOD HLS v3 с
`.ts`-сегментами по 6 с. CORS: `Access-Control-Allow-Origin: *` на редиректе
и на сегментах. Подпись содержит час истечения (~несколько часов), ответ
`/ftor` содержит поле `ip` — ссылки, вероятно, привязаны к IP клиента:
резолвить надо на устройстве, которое будет играть, либо в одной сети с
Chromecast. Не проверено: воспроизведение на Chromecast (нет устройства) и
кодеки через ffprobe (сеть песочницы). Фикстуры для парсера сохранены в
`tools/fixtures/kodik_player.html` и `tools/fixtures/kodik_links.json`
(поле `ip` обезличено).

### Реализация плана 2 (2026-09-13)

Плеер, каст и ТВ-интерфейс из этого раздела реализованы (Задачи 4–6 плана 2,
ветка `feat/kaeru-playback`). `PlaybackController` — единый конечный автомат
над интерфейсом-швом `PlaybackEngine` (`prepare`/`play`/`pause`/`seekTo`/
`release`); `ExoPlaybackEngine` (локально) и `CastPlaybackEngine`
(`media3-cast` `CastPlayer` поверх Default Media Receiver) реализуют его
одинаково, и UI, синк с Shikimori и автопереход не знают, какой из них
активен — ровно то разделение, которое этот раздел предполагал изначально.
`CastSessionBridge` — единственный класс, слушающий события каста; он вызывает
`PlaybackController.switchEngine`, который дожидается сохранения позиции
старого плеера на диск, освобождает его и передаёт новому те же серию,
озвучку, качество и позицию. Отдельно от исходного плана: если сессия каста
завершается уже после того, как экран плеера закрыт (пользователь нажал
Back и ушёл), контроллер не поднимает воспроизведение заново на телефоне без
открытого экрана — переносит позицию на диск и остаётся в состоянии ожидания,
а не запускает беззвучный плейбек в кармане.

Не проверено на реальном оборудовании: ни один каст ни разу не состоялся — в
среде разработки нет ни телефона с Google Play services, ни Chromecast.
Главный риск раздела, «воспроизведёт ли Default Media Receiver HLS Kodik»,
поэтому остаётся открытым буквально, а не только предположительно; доводы в
его пользу из «Риск и первый spike» не изменились (CORS `*`, тип
`application/x-mpegURL`, резолв на устройстве непосредственно перед плеем), но
ни один из них не был проверен воспроизведением. Пока первая ручная проверка
не пройдена, собственный CAF-приёмник на GitHub Pages
(`playbackConfig.manifestRequestHandler`) остаётся не гипотетическим
резервным путём, а вероятным следующим шагом, если Default Media Receiver
откажется грузить манифест. Раскладка клавиш D-pad ТВ-плеера (Задача 6) в
похожем положении: она проверена как чистая функция
(`TvPlayerKeyHandlerTest`, 20 тестов), но фокус, долгое нажатие и поведение
реального пульта не наблюдались ни на телевизоре, ни на эмуляторе.

## 9. Интерфейс

### Визуальный язык

- Только тёмная тема. Фон `#0B0C10`, поверхность `#15171E`, приподнятая
  поверхность `#1E212B`, текст `#F2F3F5` и вторичный `#9AA0AA`. Один акцент
  (тёплый янтарный `#F5A524`) для главной кнопки и прогресса.
- Контент как оформление: скриншоты и постеры Shikimori размытым фоном с
  градиентной маской снизу и слева.
- Шрифт Manrope (заголовки с плотным трекингом), 8dp сетка, скругление 12dp.
- Анимации: shared-element постер → тайтл; плавная смена фона при смене
  фокуса на ТВ; масштаб сфокусированной карточки 1.08 со свечением.

### Правило одного нажатия

Главная показывается из кэша сразу. Первая карточка (телефон) и элемент в
фокусе при старте (ТВ) — результат правила «Верхняя карточка главной».
`PrefetchTopCardStream` резолвит HLS для неё в фоне, поэтому нажатие
запускает видео без ожидания.

### Телефон

Навигация: нижняя панель — Главная, Мой список, Поиск. Аватар в шапке →
Настройки. Кнопка Cast в шапке.

- **Главная.** Hero-карточка: фон-скриншот, название, строка состояния
  («6 серия · осталось 14 мин» или «Вышла 7 серия · AniLibria»), кнопка
  «Смотреть». Ряды: «Новые серии», «Продолжить», «Скоро», «В планах».
  Pull-to-refresh.
- **Мой список.** Табы по статусам, сетка постеров с прогрессом «5/12»,
  сортировка по обновлению и названию.
- **Поиск.** Поиск Shikimori, недавние запросы, «В планы» на карточке.
- **Тайтл.** Фон на треть экрана, постер, название, чипы (год, серий, оценка,
  студия). Главная кнопка «Продолжить 6 серию» / «Смотреть 1 серию», рядом
  смена статуса и выбор озвучки. Список серий с галочками и прогрессом.
  Сворачиваемое описание.
- **Плеер.** Как описано в разделе 8. Панель прячется через 3 секунды.
  Карточка следующей серии с отсчётом за 30 секунд до конца.
- **Режим пульта** при касте.
- **Настройки.** Аккаунт, приоритет озвучек перетаскиванием, токен Kodik,
  автовоспроизведение, качество, порог просмотра, проверка обновлений с
  GitHub Releases.

### Android TV

Навигация: левый drawer с иконками, подписи при фокусе — Главная, Мой список,
Поиск, Настройки. При старте фокус на контенте.

- **Главная.** Immersive-раскладка: верх — фон и описание сфокусированного
  элемента, низ — ряды. Первый ряд объединяет «Продолжить» и «Новые серии».
  Долгое нажатие OK — быстрое меню (отметить просмотренной, озвучка, тайтл).
- **Тайтл.** Две колонки: слева постер, кнопки, описание; справа сетка серий.
- **Плеер.** Как в разделе 8.
- **Поиск.** Системная клавиатура и голосовой ввод, результаты сеткой.
- **Вход.** QR-код и короткая ссылка, ввод кода с пульта.

### Состояния

Каждый экран имеет пустое, загрузочное и ошибочное состояния: скелетоны
вместо крутилок; «В списке "Смотрю" пусто» с кнопкой в поиск; «Источник
временно недоступен» с повтором. Тап-цели от 48dp, фокус на ТВ всегда виден.

## 10. Хранение и конфигурация

- Room: `anime`, `user_rate`, `watch_state`, `pending_sync`, `kodik_source_cache`.
- DataStore: `Preferences`, кэш токена Kodik, время последних синков.
- DataStore Preferences в приватной директории приложения: OAuth-токены
  Shikimori; резервное копирование приложения отключено.
- `local.properties`: `SHIKIMORI_CLIENT_ID`, `SHIKIMORI_CLIENT_SECRET`,
  `KODIK_TOKEN` (опционально). Пример в `local.properties.example`.
- Обновления: проверка последнего релиза GitHub через API, ссылка на APK.

## 11. Тестирование

- Unit: доменные списки и правило верхней карточки; `KodikHtmlParser` и
  `KodikLinkDecoder` на фикстурах; ранжирование озвучек; логика отметки
  просмотра и очередь `PendingSync`; rate-limiter.
- Интеграционные с MockWebServer: Shikimori OAuth refresh, `KodikTokenProvider`
  цепочка и обработка 401.
- Инструментальные (по возможности): D-pad навигация главной ТВ и плеера;
  запуск воспроизведения по первому нажатию.
- Ручной чек-лист перед релизом: каст реальной серии, автопереход, синк на
  сайте Shikimori.

## 12. Вне первой версии

- AnimeGO как фолбэк-источник.
- Пропуск опенинга по таймкодам.
- Передача сессии с телефона на ТВ по локальной сети.
- Свой CAF-приёмник — обязательная задача плана 2, пока повторный
  spike не подтвердит воспроизведение HLS Kodik через Default Media Receiver.
- Уведомления на ТВ, виджеты, светлая тема.

## 13. Порядок реализации

1. Spike: скрипт резолва HLS с Kodik и проверка на Chromecast и ExoPlayer.
2. Каркас проекта, тема, DI, две activity, навигация.
3. Shikimori: OAuth, API, Room, главная из кэша с реальными списками.
4. Kodik: токен, поиск, извлечение ссылки, тесты на фикстурах.
5. Плеер: локальный, синк прогресса, автопереход.
6. Chromecast и режим пульта.
7. Экраны ТВ и полировка интерфейса, состояния.
8. Фоновая проверка новых серий, настройки, проверка обновлений, релиз.
