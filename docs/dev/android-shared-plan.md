# Android поверх `shared/`: план переезда

Долг известный: `shared/` писался под iOS и живёт только через `NativeApi`, а Android
держит вторую реализацию тех же клиентов — Shikimori на Retrofit и цепочку Kodik на
OkHttp. Обе написаны с одного описания, обе чинятся по отдельности (постеры с
`originalUrl` — свежий пример: правили в двух местах, и в shared тест так и остался
красным). Этот документ — что именно дублируется, где проходит шов, и в каком порядке
Android переезжает на общий модуль.

Эталон перед началом: Android — 2382 unit-теста зелёные, `assembleRelease` собирается;
shared — 78 тестов, один красный ещё на master
(`PosterEnrichmentTest.searchMapsPostersByIdPrefersMain…` ждёт `mainUrl`, а код после
2142dd7 предпочитает `originalUrl`).

## 1. Что дублируется

### Kodik

| Android (`android/.../data/kodik`) | shared (`shared/.../data/kodik`) | Вердикт |
|---|---|---|
| `KodikHtmlParser.kt` (226) | `KodikHtmlParser.kt` (238) | Копия; у shared ещё разбор `urlParams`. Android-копия удаляется. |
| `KodikLinkDecoder.kt` (68) | `KodikLinkDecoder.kt` (69) | Копия; shared строже к схеме. Удаляется. |
| `KodikApi.kt` + `KodikDtos.kt` — `get-player` через Retrofit | `KodikClient.getPlayer` | Дубль. Удаляется. |
| `KodikLinkExtractor.kt` (155) — страница плеера, `/ftor`, сборка URL | `KodikClient.loadPage/playerUrl/resolve` | Дубль. Удаляется. |
| `KodikTokenProvider.kt` (131) — токен из настроек → BuildConfig → скрейп `add-players.min.js` с кэшем в DataStore на 24 ч | `KodikClient.token()` — `configureToken` из Swift, скрейп с кэшем в памяти на 24 ч | Одна логика, у Android два лишних источника и персистентный кэш. Логика — в shared, источники и кэш — точки расширения. |
| `KodikSourceProvider.kt` (259) — оркестрация цепочки, кэш каталога на 6 ч, `listedEpisodes`, `forget`, сезон, перевод ошибок в `domain.error` | `KodikClient.translations/resolve` | Цепочка — дубль. Кэш, списки серий, `forget`, сезон — нужны Android и не мешают iOS: переезжают в shared. Перевод ошибок остаётся тонким адаптером. |
| `KodikErrors.kt` — `NoToken`, `NotFound(id, reason)`, `ParserBroken`, `Network`, `Rejected(code)` | `KodikError` — `ParserBroken`, `NoToken`, `NotFound` | shared получает причину у `NotFound` и типизированную сетевую ошибку; «отказ хоста» — это уже `ApiException(status)`. |
| `KodikConstants.kt` | константы в `KodikClient` | Дубль; `PlaybackModule` берёт UA/хост из shared. |

### Shikimori

| Android (`android/.../data/shikimori`) | shared (`shared/.../data/shikimori`) | Вердикт |
|---|---|---|
| `ShikimoriApi.kt` — Retrofit-маршруты | `ShikimoriClient.request()` + ad-hoc `JsonObject` | Одни и те же маршруты и параметры. В shared появляется публичный клиент уровня эндпоинтов; Android зовёт его. |
| `ShikimoriDtos.kt` — `@Serializable` DTO + `shikimoriJson()` | разбор по ключам | DTO переезжают в shared как есть (kotlinx.serialization — мультиплатформенная), обе стороны читают один разбор. |
| `ShikimoriMappers.kt` — DTO → `domain.Anime/UserRate` | DTO → wire-модель для Swift | Две цели, один источник. Android-мапперы остаются (они про Room и `java.time`), но читают shared-DTO. |
| `PosterEnricher.kt` — GraphQL-постеры батчами по 50, `originalUrl` → `mainUrl` | `withRealPosters()` — то же самое | **Сходятся в одну**: `ShikimoriClient.posters(ids)` в shared; на Android остаётся применение карты постеров к `domain.Anime`. |
| `RateLimitInterceptor.kt` — 5/с и 90/мин на потоке OkHttp | `ShikimoriRateLimiter` — то же, на корутинах | Дубль. Остаётся shared, один на процесс. |
| `AuthInterceptor.kt` + `TokenAuthenticator.kt` — подстановка bearer из `TokenStore`, один refresh по 401 с CAS | нет: на iOS этим владеет Swift | Android-специфика. Переписывается поверх shared без OkHttp: `ShikimoriSession.authorized { token -> … }` с той же дисциплиной (одна попытка, CAS на снимок, явная кандидат-личность никогда не рефрешится). |
| `ShikimoriOAuthApi.kt` + `UnconfiguredOAuthApi` | `ShikimoriClient.token()` через прокси-воркер | Дубль. Секрет как не было в приложении, так и нет: обмен идёт на `AUTH_PROXY_URL`. `SignInUnavailable` для сборки без прокси остаётся Android-семантикой поверх `client.oauthConfigured`. |
| `ApiErrors.kt` — `HttpException/IOException` → `domain.error` | `ApiException(status)`, сетевая ошибка как `Exception(message)` | Перевод остаётся на Android, но читает типы shared: `ApiException` → `HttpError`, `NetworkException` → `NetworkUnavailable`. |
| `UserAgentInterceptor.kt` | заголовок на каждый запрос | Интерцептор нужен остальным OkHttp-клиентам (GitHub, AniSkip, relay); переезжает в `data/network`. Shikimori-клиент в shared получает параметр `userAgent`, Android передаёт `Kaeru/<версия>` — как сейчас. |

### Что у Android есть, а в shared нет (и остаётся на Android)

- `AccountSession`, `TokenStore`/`DataStoreTokenStore`, `SessionFence` — хранение сессии и
  её поколения. На iOS то же самое делает Swift; в shared этому не место.
- `ShikimoriLibraryRepository` — Room как источник правды, слияние коротких карточек с
  деталями, лимит на 25 деталей за синк, очередь отложенных записей и её разбор
  (`ShikimoriOutboxSyncer`). Всё это про локальную базу.
- `ShikimoriDiscoverRepository` — кэш двух рядов на 6 ч в памяти. Политика экрана, а не
  клиента; остаётся, но зовёт shared.
- `ShikimoriAuthRepository` — `state` авторизации, сопряжение с ТВ, OOB-код. Остаётся,
  обмен кода и `whoami` кандидата идут через shared.
- Разбор `description` от BBCode — на iOS это делает Swift (`Models.swift`), на Android
  маппер. Остаётся по месту.
- `screenshots` — Room их хранит, iOS не просит; эндпоинт добавляется в shared, зовёт
  его только Android.

### Точки входа, через которые всё это используется

- Hilt: `NetworkModule` (`@PlainClient`, `@ShikimoriClient` OkHttp, `ShikimoriApi`,
  `ShikimoriOAuthApi`, `TokenStore`, `AuthRepository`), `KodikModule`
  (`@KodikPlayerClient`, `KodikApi`, `KodikTokenProvider`, `EpisodeSourceProvider`),
  `RepositoryModule` (`LibraryRepository`, `DiscoverRepository`), `PlaybackModule`
  (константы Kodik для источника данных плеера).
- Интерфейсы домена, которые не меняются: `EpisodeSourceProvider`, `LibraryRepository`,
  `DiscoverRepository`, `AuthRepository`, `AccountRepository`, `OutboxSyncer`.
- Тесты, которые ходят в эти слои: `KodikSourceProviderTest`, `KodikTokenProviderTest`,
  `KodikLinkExtractorTest`, `KodikHtmlParserTest`, `KodikLinkDecoderTest`,
  `KodikModuleTest`, `ShikimoriApiTest`, `ShikimoriMappersTest`, `ApiErrorsTest`,
  `RateLimitInterceptorTest`, `TokenAuthenticatorTest`, `NetworkModuleTest`,
  `FakeShikimoriApi` и все тесты репозиториев/сессии, что им пользуются.

## 2. Шов

Shared становится **клиентом уровня эндпоинтов** с публичным API, а `NativeApi` — тонкой
фасадной обёрткой над ним для Swift (JSON-строки, wire-модели). Контракт `NativeApi`
не меняется: сигнатуры, ключи JSON, тексты ошибок, `ApiException.status/oauthError`.

```
shared/commonMain
  data/network/HttpTransport      публичный; NetworkException для сетевых сбоев
  data/shikimori/ShikimoriDtos    UserDto, AnimeDto, ScreenshotDto, UserRateDto, TokenResponseDto, …
  data/shikimori/ShikimoriClient  whoami, libraryRates, animesByIds, catalogue, anime, screenshots,
                                  search, posters, createUserRate, updateUserRate, token
  data/shikimori/ShikimoriFacade  внутренний: search/discover/seasonal/details/account/library/setRate
                                  в wire-модели — то, что зовёт NativeApi
  data/kodik/KodikClient          translations/resolve(+season)/listedEpisodes/forget,
                                  кэш каталога 6 ч, токен: override → configured → кэш → скрейп
  data/kodik/KodikTokenCache      интерфейс; в памяти по умолчанию, DataStore на Android
  NativeApi                       как было (+ forgetTranslations, аддитивно)

android
  data/network/UserAgentInterceptor
  data/shikimori/ShikimoriApi     Android-интерфейс «что нужно репозиториям, токен подставлен»
  data/shikimori/ShikimoriSession  bearer из TokenStore, один refresh по 401, CAS
  data/shikimori/ShikimoriMappers  shared-DTO → domain
  data/shikimori/ApiErrors         shared-исключения → domain.error
  data/kodik/KodikSourceProvider   EpisodeSourceProvider поверх KodikClient: Quality, resolvedAt,
                                   перевод ошибок
  data/kodik/DataStoreKodikTokenCache, KodikTokenKeys
  di/NetworkModule, di/KodikModule  собирают Ktor(OkHttp) → HttpTransport → клиенты
```

Транспорт на Android — Ktor с движком OkHttp поверх собственного `OkHttpClient` с
логированием; `User-Agent` shared ставит сам на каждый запрос (Shikimori — `Kaeru/<версия>`,
Kodik — браузерный), поэтому UA-интерцептор на этот клиент не вешается.

## 3. Порядок

1. **План** — этот файл.
2. **shared: Kodik.** Публичный `KodikClient` с причинами у `NotFound`, `NetworkException`,
   сезоном, кэшем каталога, `listedEpisodes`/`forget`, источниками токена и его кэшем.
   Тесты `KodikSourceProviderTest`/`KodikTokenProviderTest`/`KodikLinkExtractorTest`
   переезжают по смыслу в `commonTest`. `NativeApiTest`/`KodikNetworkTest` — зелёные.
3. **android: Kodik.** `:shared` в зависимостях, Ktor(OkHttp) в `NetworkModule`,
   `KodikSourceProvider` — адаптер. Удаляются Retrofit/OkHttp-половина Kodik и её тесты.
4. **shared: Shikimori.** DTO, `ShikimoriClient` уровня эндпоинтов, `posters()`,
   `ShikimoriFacade` для `NativeApi`. Красный тест постеров чинится (ожидание
   `originalUrl`). Тесты `ShikimoriApiTest`/`RateLimitInterceptorTest` переезжают.
5. **android: Shikimori** — каталог, библиотека, оценки, вход. `ShikimoriApi` как
   Android-интерфейс поверх shared, `ShikimoriSession` вместо интерцептора и
   аутентификатора, репозитории читают shared-DTO. Удаляются Retrofit-клиенты Shikimori,
   OAuth-API, интерцепторы, `ShikimoriDtos.kt`. `TokenAuthenticatorTest` переписывается
   как `ShikimoriSessionTest` с теми же сценариями.
6. **Хвосты**: README, лишние зависимости, проверка iOS (`generate_project.rb` + 241 тест).

Каталог и библиотека Shikimori не делятся на два коммита: `ShikimoriLibraryRepository`
делает и поиск, и оценки через один интерфейс, и токен у них общий — разделить можно
только оставив на время две живые цепочки токена.

## 4. Тесты: куда что уезжает

| Было на Android | Станет |
|---|---|
| `KodikHtmlParserTest`, `KodikLinkDecoderTest` | Удаляются: в `commonTest` лежат те же тесты один в один. |
| `KodikLinkExtractorTest` | В `KodikNetworkTest`: заголовки, referer, `season/episode`, `ftor`-форма, отказ хоста, сеть. |
| `KodikTokenProviderTest` | Источники токена и 24-часовой кэш — в `commonTest` через фейковый `KodikTokenCache`; DataStore-кэш — маленький Android-тест. |
| `KodikSourceProviderTest` | Кэш 6 ч, списки серий, `forget`, сезон, причины — в `commonTest` (`KodikClientTest`); на Android остаётся адаптер: перевод ошибок, `Quality`, `resolvedAt`. |
| `KodikModuleTest` | Удаляется (проверял UA-интерцептор OkHttp и константы, которых больше нет). |
| `ShikimoriApiTest` | В `commonTest` (`ShikimoriClientTest`) с теми же фикстурами `shikimori/*.json`. |
| `RateLimitInterceptorTest` | Граничные случаи добавляются в `ShikimoriRateLimiterTest`. |
| `TokenAuthenticatorTest` | `ShikimoriSessionTest`: те же сценарии, MockWebServer через настоящий OkHttp-движок Ktor. |
| `NetworkModuleTest` | Тест про `noBackupFilesDir` остаётся; прокси-тесты переписываются на новые провайдеры; тест про диспетчер OkHttp уходит — блокирующего аутентификатора больше нет. |
| `ApiErrorsTest`, `ShikimoriMappersTest` | Остаются, читают shared-типы. |
| `FakeShikimoriApi` и тесты репозиториев/сессии | Остаются, фейк реализует Android-интерфейс; батчи по 50 и постраничность проверяет shared. |

## 5. Что меняется в поведении, и почему это осознанно

- **429 от Shikimori.** shared ждёт `Retry-After` (1–60 с) и повторяет один раз; Android
  сразу отдавал `HttpError(429)`. Принимается политика shared: один и тот же клиент
  ведёт себя одинаково на обеих платформах, и лишняя попытка — только в плюс.
- **Отказ по refresh-токену.** Android завершал сессию на любом JSON-ответе 400/401 с полем
  `error`; shared и Swift — только на `invalid_grant`. Принимается политика shared: она
  же по RFC 6749 единственная, что означает «войти заново»; `invalid_client` — это
  сломанный деплой воркера, а не сессия. Тесты Android пишут только `invalid_grant`.
- **Кэш каталога Kodik на 6 ч и кэш токена** появляются и у iOS — это то, что просили:
  «делай доступной обоим». `NativeApi` получает `forgetTranslations` аддитивно.

DDoS-Guard: специальной обработки редиректов нет ни на одной стороне; OkHttp и Ktor
(`followRedirects = true`) ходят за редиректом сами, а HTML вместо JSON — «Invalid API
response», как и раньше — общая ошибка.

## 6. `PlaybackRules.kt`

Остаётся. На него ссылается `ios/Tests/KaeruTests/LiveServiceTests.swift`, а `ios/` не
трогаем. Подключать к Android нельзя: там порог просмотренности настраиваемый
(`watchedThreshold`), а здесь он зашит в 0.9. Пометка в отчёте.

## 7. Как вышло

Всё по плану, с тремя уточнениями по ходу:

- Публичные эндпоинты каталога в shared (`animesByIds`, `catalogue`, `anime`, `screenshots`,
  `search`, `posters`) получили необязательный `token`. Android всегда слал bearer на каждый
  вызов Shikimori, включая GraphQL, и провод не должен меняться оттого, что код переехал;
  iOS как ходил анонимно, так и ходит.
- Ktor 3.6 тянет OkHttp 5.5, чей Android-артефакт требует compileSdk 37 — шаг за пределы
  AGP 8.13. OkHttp прижат к версии из каталога (5.3.0) ограничением в `android/` и правилом
  разрешения в `shared/`; движку Ktor хватает любой 5.x.
- Адрес прокси без схемы (`kaeru-relay.workers.dev`) Retrofit отвергал, а парсер Ktor
  принимает за хост. Shared теперь требует явную `http(s)://`, иначе «прокси не настроен» —
  ровно то, что Android делал раньше.

Единственный тест, где пришлось поменять форму, а не смысл: интеграция сессии ждала
`whoami` тиком тестового планировщика, потому что обмен кода был синхронной заглушкой; через
движок Ktor он уходит на свой поток, и тест теперь ждёт сам вход в `whoami`. Проверка
«токены не опубликованы до верификации личности» на месте.
