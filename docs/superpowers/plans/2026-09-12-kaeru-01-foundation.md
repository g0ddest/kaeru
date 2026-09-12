# Kaeru Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Работающее приложение для телефона и Android TV, которое логинится в Shikimori, синхронизирует списки в Room и показывает главную с рядами «Новые серии / Продолжить / Скоро / В планах» из кэша, плюс подтверждённый spike «HLS Kodik играет на Chromecast».

**Architecture:** Один Gradle-модуль `app`, пакеты `domain` (чистый Kotlin, без Android), `data` (Retrofit + Room + DataStore), `ui.common` / `ui.mobile` / `ui.tv` (Compose), две activity в манифесте. Данные текут `Shikimori API → Room → Flow → ViewModel → Compose`; главная всегда рендерится из Room, сеть только обновляет Room.

**Tech Stack:** Kotlin 2.3.21, AGP 8.13.2, Gradle 8.13, JDK 21, Compose BOM 2025.08.01, tv-material 1.1.0, Hilt 2.58 + AndroidX Hilt 1.3.0 (KSP 2.3.12), Room 2.8.5, DataStore 1.2.1, Retrofit 3.0.0 + kotlinx-serialization 1.11.0, OkHttp 5.3.0, Coil 3.3.0, navigation-compose 2.9.8, browser 1.10.0 (Custom Tabs), zxing 3.5.3, JUnit 4, MockK 1.14.11, Turbine 1.2.1, Robolectric 4.17, MockWebServer 5.3.0.

**Spec:** `docs/superpowers/specs/2026-09-12-kaeru-design.md`

**Планы после этого:** план 2 — Kodik, плеер, Chromecast; план 3 — полные ТВ-экраны, фоновые джобы, настройки, релиз.

## Global Constraints

- `applicationId` и корневой пакет: `app.kaeru`. `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`.
- Не повышать Compose BOM выше `2025.08.01`, Lifecycle выше `2.10.0` и Navigation выше `2.9.8` в плане 1: Compose 1.12-артефакты собраны с API 37 и требуют AGP не ниже 9.1, тогда как этот план закрепляет AGP 8.13.2.
- Только тёмная тема. Цвета из спеки: фон `#0B0C10`, поверхность `#15171E`, приподнятая `#1E212B`, текст `#F2F3F5`, вторичный `#9AA0AA`, акцент `#F5A524`. Шрифт Manrope (variable TTF в `res/font`). Сетка 8dp, скругление карточек 12dp.
- Shikimori: базовый URL `https://shikimori.one/`, обязательный заголовок `User-Agent: Kaeru/<versionName>`, не более 5 запросов в секунду и 90 в минуту.
- Секреты только в `local.properties` → `BuildConfig`: `SHIKIMORI_CLIENT_ID`, `SHIKIMORI_CLIENT_SECRET`, `KODIK_TOKEN` (пустая строка по умолчанию). В git попадает только `local.properties.example`.
- `ui.*` зависит только от `domain` и `player`. `domain` не импортирует ничего из `android.*` и `androidx.*`.
- Все времена — `java.time.Instant`. В Room хранятся как epoch millis.
- Отклонение от спеки, принятое в этом плане: OAuth-токены хранятся в DataStore Preferences в приватной директории приложения, а не в EncryptedSharedPreferences, потому что Jetpack Security Crypto объявлен deprecated. Спека обновлена соответствующим образом.
- До появления суточного WorkManager в плане 3 ручной/startup `refresh()` загружает все статусы Shikimori, чтобы «Мой список» был полным. План 3 разделит быстрый startup-sync (`watching`, `planned`) и суточный full-sync.
- Каждый шаг с кодом заканчивается зелёным `./gradlew :app:testDebugUnitTest` и коммитом. Команды Gradle запускаются с `JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.

## Предусловия, которые делает человек

1. Зарегистрировать OAuth-приложение на https://shikimori.one/oauth/applications с именем `Kaeru` и redirect URI в две строки: `kaeru://oauth` и `urn:ietf:wg:oauth:2.0:oob`. Скопировать `client_id` и `client_secret` в `local.properties`.
2. Для Task 1 иметь Chromecast в той же Wi-Fi-сети, что и Mac.

## Структура файлов после плана

```
kaeru/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.jar
  gradle/wrapper/gradle-wrapper.properties
  local.properties.example
  tools/kodik_probe.py                       # spike, throwaway, не входит в APK
  app/build.gradle.kts
  app/proguard-rules.pro
  app/src/main/AndroidManifest.xml
  app/src/main/res/font/manrope.ttf
  app/src/main/assets/licenses/OFL-Manrope.txt
  app/src/main/res/values/{strings,themes,colors}.xml
  app/src/main/res/drawable/{ic_launcher_foreground,tv_banner}.xml
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
  app/src/main/res/xml/network_security_config.xml
  app/src/main/java/app/kaeru/
    KaeruApp.kt
    MainActivity.kt                           # телефон
    TvActivity.kt                             # Android TV
    di/{NetworkModule,DatabaseModule,RepositoryModule}.kt
    domain/model/{Anime,UserRate,WatchState,LibraryEntry,HomeFeed}.kt
    domain/feed/HomeFeedBuilder.kt
    domain/repository/{LibraryRepository,AuthRepository}.kt
    data/shikimori/{ShikimoriApi,ShikimoriDtos,ShikimoriMappers,ShikimoriOAuthApi}.kt
    data/shikimori/{RateLimitInterceptor,UserAgentInterceptor,AuthInterceptor,TokenAuthenticator}.kt
    data/auth/{TokenStore,DataStoreTokenStore,ShikimoriAuthRepository}.kt
    data/local/{KaeruDatabase,Converters,AnimeEntity,UserRateEntity,WatchStateEntity,AnimeDao,UserRateDao,WatchStateDao}.kt
    data/library/{ShikimoriLibraryRepository,AppPreferences}.kt
    ui/common/theme/{Color,Type,Theme}.kt
    ui/common/{Poster,ProgressStrip,Skeleton}.kt
    ui/common/auth/AuthViewModel.kt
    ui/common/home/{HomeUiState,HomeViewModel}.kt
    ui/mobile/{MobileApp,MobileShell,Routes}.kt
    ui/mobile/auth/LoginScreen.kt
    ui/mobile/home/HomeScreen.kt
    ui/mobile/library/{LibraryViewModel,LibraryScreen}.kt
    ui/mobile/search/{SearchViewModel,SearchScreen}.kt
    ui/mobile/details/{DetailsViewModel,DetailsScreen}.kt
    ui/tv/{TvApp}.kt
    ui/tv/auth/TvLoginScreen.kt
    ui/tv/home/{TvHomeRows,TvHomeScreen}.kt
  app/src/test/java/app/kaeru/domain/feed/HomeFeedBuilderTest.kt
  app/src/test/java/app/kaeru/data/auth/{InMemoryTokenStore,ShikimoriAuthRepositoryTest}.kt
  app/src/test/java/app/kaeru/data/shikimori/{ShikimoriApiTest,RateLimitInterceptorTest,ShikimoriMappersTest,TokenAuthenticatorTest}.kt
  app/src/test/java/app/kaeru/data/local/KaeruDatabaseTest.kt
  app/src/test/java/app/kaeru/data/library/{FakeShikimoriApi,ShikimoriLibraryRepositoryTest}.kt
  app/src/test/java/app/kaeru/test/MainDispatcherRule.kt
  app/src/test/java/app/kaeru/ui/common/{auth/AuthViewModelTest,home/HomeViewModelTest}.kt
  app/src/test/java/app/kaeru/ui/mobile/{details/DetailsViewModelTest,library/LibraryViewModelTest,search/SearchViewModelTest}.kt
  app/src/test/java/app/kaeru/ui/tv/home/TvHomeRowsTest.kt
  app/src/test/resources/robolectric.properties
  app/src/test/resources/shikimori/{animes_list,anime_details,user_rates}.json
  README.md
  docs/superpowers/manual/2026-09-12-foundation-checklist.md
```

---

### Task 1: Spike — HLS из Kodik играет на Chromecast

Цель: получить ответ «да/нет» на риск из раздела 8 спеки до того, как писать плеер. Код throwaway, живёт в `tools/`, в APK не входит.

**Files:**
- Create: `tools/kodik_probe.py`
- Create: `tools/README.md`
- Modify: `docs/superpowers/specs/2026-09-12-kaeru-design.md` (раздел 8, результат spike)

**Interfaces:**
- Produces: файл `tools/fixtures/kodik_player.html` и `tools/fixtures/kodik_links.json` — их использует план 2 как тестовые фикстуры парсера.

- [ ] **Step 1: Подготовить Python-окружение**

```bash
/Applications/Python\ 3.13/Install\ Certificates.command || pip3 install --upgrade certifi
pip3 install --user requests
pipx install catt || pip3 install --user catt
```

`catt` (Cast All The Things) отправляет URL на Chromecast через стандартный Default Media Receiver, то есть проверяет ровно то, что будет делать приложение.

- [ ] **Step 2: Написать скрипт резолва**

Файл `tools/kodik_probe.py`:

```python
#!/usr/bin/env python3
"""Throwaway spike: shikimori_id -> Kodik HLS URL -> CORS check -> Chromecast.

Usage: python3 tools/kodik_probe.py <shikimori_id> [episode] [--cast]
"""
import base64
import json
import os
import re
import subprocess
import sys

import requests

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0 Safari/537.36")
API = "https://kodik-api.com"
PLAYER_HOST = "https://kodikplayer.com"
ADD_PLAYERS_JS = "https://kodik-add.com/add-players.min.js?v=2"
FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")

s = requests.Session()
s.headers["User-Agent"] = UA


def public_token() -> str:
    env = os.environ.get("KODIK_TOKEN")
    if env:
        return env
    js = s.get(ADD_PLAYERS_JS, timeout=20).text
    m = re.search(r'token="([a-z0-9]+)"', js)
    if not m:
        sys.exit("token not found in add-players.min.js — mechanism changed")
    return m.group(1)


def search(token: str, shikimori_id: int) -> list[dict]:
    r = s.post(f"{API}/search", data={
        "token": token, "shikimori_id": shikimori_id,
        "with_episodes": "true", "limit": 100}, timeout=20)
    data = r.json()
    if "error" in data:
        sys.exit(f"kodik search error: {data['error']}")
    return data["results"]


def player_page(link: str, season: int, episode: int) -> str:
    url = link if link.startswith("http") else "https:" + link
    url = re.sub(r"https://[^/]+", PLAYER_HOST, url)
    url = f"{url}?season={season}&episode={episode}"
    r = s.get(url, headers={"Referer": PLAYER_HOST + "/"}, timeout=20)
    r.raise_for_status()
    return r.text


def parse_page(html: str) -> dict:
    url_params = json.loads(re.search(r"urlParams\s*=\s*'([^']+)'", html).group(1))
    info = {k: re.search(rf"videoInfo\.{k}\s*=\s*'([^']+)'", html).group(1)
            for k in ("type", "hash", "id")}
    script = re.search(r'src="(/assets/js/app\.player_single\.[^"]+\.js)"', html).group(1)
    js = s.get(PLAYER_HOST + script, timeout=20).text
    post_path = base64.b64decode(
        re.search(r'atob\("([^"]+)"\)', js).group(1)).decode()
    return {"url_params": url_params, "info": info, "post_path": post_path}


def rot(text: str, n: int) -> str:
    out = []
    for ch in text:
        if "a" <= ch <= "z":
            out.append(chr((ord(ch) - 97 + n) % 26 + 97))
        elif "A" <= ch <= "Z":
            out.append(chr((ord(ch) - 65 + n) % 26 + 65))
        else:
            out.append(ch)
    return "".join(out)


def decode_src(enc: str) -> str:
    for n in range(26):
        candidate = rot(enc, n)
        candidate += "=" * (-len(candidate) % 4)
        try:
            url = base64.b64decode(candidate).decode()
        except Exception:
            continue
        if "mp4:hls:manifest" in url:
            return url if url.startswith("http") else "https:" + url
    raise RuntimeError("could not decode src")


def resolve_links(page: dict) -> dict[str, str]:
    p = page["url_params"]
    r = s.post(PLAYER_HOST + page["post_path"], data={
        "hash": page["info"]["hash"], "id": page["info"]["id"],
        "type": page["info"]["type"], "d": p["d"], "d_sign": p["d_sign"],
        "pd": p["pd"], "pd_sign": p["pd_sign"], "ref": "",
        "ref_sign": p["ref_sign"], "bad_user": "true", "cdn_is_working": "true",
    }, headers={"Referer": PLAYER_HOST + "/", "Origin": PLAYER_HOST,
                "X-Requested-With": "XMLHttpRequest"}, timeout=20)
    r.raise_for_status()
    os.makedirs(FIXTURES, exist_ok=True)
    with open(os.path.join(FIXTURES, "kodik_links.json"), "w") as f:
        f.write(r.text)
    links = r.json()["links"]
    return {q: decode_src(v[0]["src"]) for q, v in links.items()}


def cors_check(url: str) -> None:
    r = s.get(url, headers={"Origin": "https://example.com"}, timeout=20)
    print(f"  manifest HTTP {r.status_code}, "
          f"Access-Control-Allow-Origin={r.headers.get('Access-Control-Allow-Origin')}")
    first_seg = next((l for l in r.text.splitlines() if l and not l.startswith("#")), None)
    if first_seg:
        seg = first_seg if first_seg.startswith("http") else url.rsplit("/", 1)[0] + "/" + first_seg
        rs = s.head(seg, headers={"Origin": "https://example.com"}, timeout=20)
        print(f"  segment  HTTP {rs.status_code}, "
              f"Access-Control-Allow-Origin={rs.headers.get('Access-Control-Allow-Origin')}")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    shikimori_id = int(args[0])
    episode = int(args[1]) if len(args) > 1 else 1
    token = public_token()
    print(f"token: {token[:4]}…{token[-4:]}")
    results = search(token, shikimori_id)
    if not results:
        sys.exit("nothing found on kodik for this shikimori_id")
    by_translation = {r["translation"]["id"]: r for r in results}
    for r in by_translation.values():
        print(f"- [{r['translation']['id']}] {r['translation']['title']} "
              f"eps={r.get('episodes_count')} last_season={r.get('last_season')} {r['link']}")
    chosen = max(by_translation.values(), key=lambda r: r.get("episodes_count") or 0)
    season = chosen.get("last_season") or 1
    print(f"chosen: {chosen['translation']['title']} season={season} episode={episode}")
    html = player_page(chosen["link"], season, episode)
    os.makedirs(FIXTURES, exist_ok=True)
    with open(os.path.join(FIXTURES, "kodik_player.html"), "w") as f:
        f.write(html)
    page = parse_page(html)
    links = resolve_links(page)
    for q, u in sorted(links.items(), key=lambda kv: int(kv[0])):
        print(f"{q}p: {u}")
    best = links[max(links, key=int)]
    print("CORS check:")
    cors_check(best)
    if "--cast" in sys.argv:
        print("casting via catt…")
        subprocess.run(["catt", "cast", best], check=False)


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Запустить резолв без каста и сохранить выбранный URL для следующих проверок**

Run:

```bash
python3 tools/kodik_probe.py 52991 1 | tee /tmp/kaeru-kodik-probe.log
KODIK_HLS_URL="$(sed -n 's/^720p: //p' /tmp/kaeru-kodik-probe.log | tail -n 1)"
test -n "$KODIK_HLS_URL"
```

Expected: список озвучек, ссылки `360p/480p/720p` вида `https://cloud.kodik-storage.com/.../hls:manifest.m3u8`, у манифеста и сегмента `HTTP 200` и непустой `Access-Control-Allow-Origin`. Если токен не найден или `search` вернул ошибку, зафиксировать текст ошибки в отчёте и остановиться: план 2 будет зависеть от этого.

- [ ] **Step 4: Проверить локальное воспроизведение**

Run:

```bash
KODIK_HLS_URL="$(sed -n 's/^720p: //p' /tmp/kaeru-kodik-probe.log | tail -n 1)"
ffprobe -v error -show_entries stream=codec_name,width,height "$KODIK_HLS_URL"
```

Если `ffprobe` отсутствует, сначала выполнить `brew install ffmpeg`.
Expected: видеопоток `h264`, аудио `aac`.

- [ ] **Step 5: Кастануть на Chromecast**

Run: `catt scan` затем `python3 tools/kodik_probe.py 52991 1 --cast`.
Expected: серия начинает играть на первом найденном Chromecast в течение 10 секунд. Если устройств несколько, временно отключить остальные от сети и повторить; текст ошибки `catt` записать в результат spike.

- [ ] **Step 6: Записать результат в спеку**

В `docs/superpowers/specs/2026-09-12-kaeru-design.md`, раздел 8, подраздел «Риск и первый spike», дописать абзац с датой, форматом ссылки, наличием CORS-заголовков и результатом каста. Если каст не сработал, добавить в раздел 12 задачу «свой CAF-приёмник» как обязательную для плана 2.

- [ ] **Step 7: README и коммит**

Файл `tools/README.md`:

```markdown
# tools

Throwaway-скрипты, не входят в APK.

- `kodik_probe.py` — резолв HLS-ссылки Kodik по shikimori_id и проверка каста через `catt`.
  Сохраняет `fixtures/kodik_player.html` и `fixtures/kodik_links.json` для тестов парсера.
```

```bash
git add tools docs/superpowers/specs/2026-09-12-kaeru-design.md
git commit -m "spike: kodik hls resolve and chromecast probe"
```

---

### Task 2: Каркас Android-проекта

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `local.properties.example`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/app/kaeru/KaeruApp.kt`, `MainActivity.kt`, `TvActivity.kt`
- Create: `app/src/main/res/values/strings.xml`, `themes.xml`, `colors.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/drawable/tv_banner.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/xml/network_security_config.xml`
- Create: `app/src/test/java/app/kaeru/SmokeTest.kt`

**Interfaces:**
- Produces: `BuildConfig.SHIKIMORI_CLIENT_ID: String`, `BuildConfig.SHIKIMORI_CLIENT_SECRET: String`, `BuildConfig.KODIK_TOKEN: String`, `BuildConfig.VERSION_NAME`. Hilt-приложение `KaeruApp`. Две activity без содержимого (заполняются в Task 9 и Task 11).

- [ ] **Step 1: JDK 21 и wrapper**

```bash
brew install openjdk@21 gradle
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
/usr/libexec/java_home -v 21
gradle wrapper --gradle-version 8.13 --distribution-type bin
```

Expected: `java_home -v 21` печатает путь; появились `gradlew`, `gradlew.bat`, `gradle/wrapper/`.

- [ ] **Step 2: Корневые Gradle-файлы**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "kaeru"
include(":app")
```

`build.gradle.kts` (root):

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.13.2"
kotlin = "2.3.21"
ksp = "2.3.12"
hilt = "2.58"
androidxHilt = "1.3.0"
composeBom = "2025.08.01"
tvMaterial = "1.1.0"
activityCompose = "1.13.0"
lifecycle = "2.10.0"
navigation = "2.9.8"
coreKtx = "1.17.0"
splashscreen = "1.2.0"
browser = "1.10.0"
room = "2.8.5"
datastore = "1.2.1"
media3 = "1.11.0"
castFramework = "22.3.1"
mediarouter = "1.8.1"
retrofit = "3.0.0"
okhttp = "5.3.0"
serialization = "1.11.0"
coroutines = "1.11.0"
coil = "3.3.0"
zxing = "3.5.3"
junit = "4.13.2"
mockk = "1.14.11"
turbine = "1.2.1"
robolectric = "4.17"
androidxTestCore = "1.7.0"

[libraries]
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
core-splashscreen = { group = "androidx.core", name = "core-splashscreen", version.ref = "splashscreen" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
browser = { group = "androidx.browser", name = "browser", version.ref = "browser" }

compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
tv-material = { group = "androidx.tv", name = "tv-material", version.ref = "tvMaterial" }

hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-lifecycle-viewmodel-compose = { group = "androidx.hilt", name = "hilt-lifecycle-viewmodel-compose", version.ref = "androidxHilt" }

room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }

media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-exoplayer-hls = { group = "androidx.media3", name = "media3-exoplayer-hls", version.ref = "media3" }
media3-ui-compose = { group = "androidx.media3", name = "media3-ui-compose", version.ref = "media3" }
media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
media3-cast = { group = "androidx.media3", name = "media3-cast", version.ref = "media3" }
cast-framework = { group = "com.google.android.gms", name = "play-services-cast-framework", version.ref = "castFramework" }
mediarouter = { group = "androidx.mediarouter", name = "mediarouter", version.ref = "mediarouter" }

junit = { group = "junit", name = "junit", version.ref = "junit" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

Media3 и Cast заранее в каталоге, но в `app` подключаются только в плане 2.

`local.properties.example`:

```properties
sdk.dir=/Users/vitaliy/Library/Android/sdk
SHIKIMORI_CLIENT_ID=
SHIKIMORI_CLIENT_SECRET=
KODIK_TOKEN=
```

Скопировать в `local.properties` и заполнить (`sdk.dir=/Users/vitaliy/Library/Android/sdk`).

- [ ] **Step 3: `app/build.gradle.kts`**

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = "\"" + (localProps.getProperty(name) ?: "") + "\""

android {
    namespace = "app.kaeru"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kaeru"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SHIKIMORI_CLIENT_ID", secret("SHIKIMORI_CLIENT_ID"))
        buildConfigField("String", "SHIKIMORI_CLIENT_SECRET", secret("SHIKIMORI_CLIENT_SECRET"))
        buildConfigField("String", "KODIK_TOKEN", secret("KODIK_TOKEN"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.generateKotlin", "true") }

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

`app/proguard-rules.pro`:

```
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class app.kaeru.**$$serializer { *; }
-keepclassmembers class app.kaeru.** { *** Companion; }
-keepclasseswithmembers class app.kaeru.** { kotlinx.serialization.KSerializer serializer(...); }
```

- [ ] **Step 4: Манифест, ресурсы, Application и activity**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <uses-feature android:name="android.software.leanback" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:name=".KaeruApp"
        android:allowBackup="false"
        android:banner="@drawable/tv_banner"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/Theme.Kaeru">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:theme="@style/Theme.Kaeru.Splash"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="kaeru" android:host="oauth" />
            </intent-filter>
        </activity>

        <activity
            android:name=".TvActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:theme="@style/Theme.Kaeru">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

`res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Kaeru</string>
</resources>
```

`res/values/colors.xml`:

```xml
<resources>
    <color name="kaeru_background">#0B0C10</color>
    <color name="kaeru_accent">#F5A524</color>
</resources>
```

`res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.Kaeru" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/kaeru_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
    <style name="Theme.Kaeru.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/kaeru_background</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <item name="postSplashScreenTheme">@style/Theme.Kaeru</item>
    </style>
</resources>
```

`res/drawable/ic_launcher_foreground.xml` (стилизованная буква K, временная):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#F5A524"
        android:pathData="M36,30h10v20l16,-20h12L56,52l20,26H64L46,56v22H36z"/>
</vector>
```

`res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/kaeru_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

`res/drawable/tv_banner.xml` (320×180):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp" android:height="180dp"
    android:viewportWidth="320" android:viewportHeight="180">
    <path android:fillColor="#0B0C10" android:pathData="M0,0h320v180H0z"/>
    <path android:fillColor="#F5A524"
        android:pathData="M120,50h14v32l26,-32h18L148,86l32,44H162L134,92v38H120z"/>
</vector>
```

`KaeruApp.kt`:

```kotlin
package app.kaeru

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KaeruApp : Application()
```

`MainActivity.kt` (временное содержимое, заменяется в Task 9):

```kotlin
package app.kaeru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Text("Kaeru mobile") }
    }
}
```

`TvActivity.kt` (временное содержимое, заменяется в Task 11):

```kotlin
package app.kaeru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Kaeru TV") }
    }
}
```

- [ ] **Step 5: Smoke-тест и сборка**

`app/src/test/java/app/kaeru/SmokeTest.kt`:

```kotlin
package app.kaeru

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun buildConfigHasPackage() {
        assertEquals("app.kaeru", BuildConfig.APPLICATION_ID)
    }
}
```

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, APK в `app/build/outputs/apk/debug/app-debug.apk`. Если KSP 2.3.12 несовместим с Kotlin 2.3.21, остановить Task 2 и обновить зафиксированную пару Kotlin/KSP в этом плане по официальным release notes; не подставлять динамическую версию.

- [ ] **Step 6: Установить на устройство, если подключено**

Run: `adb devices` → если есть устройство, `adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n app.kaeru/.MainActivity`
Expected: на экране «Kaeru mobile». Если устройств нет, пропустить и отметить в отчёте.

- [ ] **Step 7: Коммит**

```bash
git add -A
git commit -m "chore: android project skeleton with compose, hilt, room, retrofit"
```

---

### Task 3: Домен — модели и HomeFeedBuilder

Чистый Kotlin. Здесь живёт вся логика «что показать первым», покрытая тестами без Android.

**Files:**
- Create: `app/src/main/java/app/kaeru/domain/model/Anime.kt`, `UserRate.kt`, `WatchState.kt`, `LibraryEntry.kt`, `HomeFeed.kt`
- Create: `app/src/main/java/app/kaeru/domain/feed/HomeFeedBuilder.kt`
- Create: `app/src/main/java/app/kaeru/domain/repository/LibraryRepository.kt`, `AuthRepository.kt`
- Test: `app/src/test/java/app/kaeru/domain/feed/HomeFeedBuilderTest.kt`

**Interfaces:**
- Produces:
  - `data class Anime(id: Int, nameRu: String, nameRomaji: String, posterUrl: String?, screenshotUrls: List<String>, status: AnimeStatus, episodes: Int, episodesAired: Int, nextEpisodeAt: Instant?, score: Double?, year: Int?, studio: String?, description: String?)`
  - `enum class AnimeStatus { ONGOING, RELEASED, ANONS }`
  - `enum class ListStatus(val apiValue: String) { WATCHING("watching"), PLANNED("planned"), COMPLETED("completed"), ON_HOLD("on_hold"), DROPPED("dropped"), REWATCHING("rewatching") }`
  - `data class UserRate(id: Long, animeId: Int, status: ListStatus, episodes: Int, updatedAt: Instant)`
  - `data class WatchState(animeId: Int, episode: Int, positionMs: Long, durationMs: Long, translationId: Int?, kodikSeason: Int?, updatedAt: Instant)`
  - `data class LibraryEntry(anime: Anime, rate: UserRate, watch: WatchState?)` с `fun nextEpisode(watchedThreshold: Float): Int` и `fun progressFraction(watchedThreshold: Float): Float?`
  - `data class FeedItem(entry: LibraryEntry, episode: Int, kind: FeedKind)`; `enum class FeedKind { CONTINUE, NEW_EPISODE, NEXT_UP, UPCOMING, PLANNED }`
  - `data class HomeFeed(top: FeedItem?, continueWatching: List<FeedItem>, newEpisodes: List<FeedItem>, nextUp: List<FeedItem>, upcoming: List<FeedItem>, planned: List<FeedItem>)`
  - `class HomeFeedBuilder(watchedThreshold: Float = 0.9f, upcomingWindow: Duration = Duration.ofDays(7))` с `fun build(entries: List<LibraryEntry>, now: Instant): HomeFeed`
  - `interface LibraryRepository { fun observeLibrary(): Flow<List<LibraryEntry>>; fun observeAnime(id: Int): Flow<LibraryEntry?>; suspend fun refresh(): Result<Unit>; suspend fun refreshAnime(id: Int): Result<Unit>; suspend fun search(query: String): Result<List<Anime>>; suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit>; suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> }`
  - `interface AuthRepository { val isLoggedIn: Flow<Boolean>; fun authorizeUrl(redirectUri: String): String; suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit>; suspend fun logout() }`
  - `const val OOB_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"`, `const val MOBILE_REDIRECT = "kaeru://oauth"` в `AuthRepository.kt`

- [ ] **Step 1: Модели**

`domain/model/Anime.kt`:

```kotlin
package app.kaeru.domain.model

import java.time.Instant

enum class AnimeStatus { ONGOING, RELEASED, ANONS }

data class Anime(
    val id: Int,
    val nameRu: String,
    val nameRomaji: String,
    val posterUrl: String?,
    val screenshotUrls: List<String>,
    val status: AnimeStatus,
    val episodes: Int,
    val episodesAired: Int,
    val nextEpisodeAt: Instant?,
    val score: Double?,
    val year: Int?,
    val studio: String?,
    val description: String?,
) {
    val title: String get() = nameRu.ifBlank { nameRomaji }
    /** Сколько серий реально доступно: у онгоинга — вышедшие, у завершённого — все. */
    val availableEpisodes: Int get() = if (status == AnimeStatus.ONGOING) episodesAired else if (episodes > 0) episodes else episodesAired
}
```

`domain/model/UserRate.kt`:

```kotlin
package app.kaeru.domain.model

import java.time.Instant

enum class ListStatus(val apiValue: String) {
    WATCHING("watching"), PLANNED("planned"), COMPLETED("completed"),
    ON_HOLD("on_hold"), DROPPED("dropped"), REWATCHING("rewatching");

    companion object {
        fun fromApi(value: String): ListStatus = entries.firstOrNull { it.apiValue == value } ?: PLANNED
    }
}

data class UserRate(
    val id: Long,
    val animeId: Int,
    val status: ListStatus,
    val episodes: Int,
    val updatedAt: Instant,
)
```

`domain/model/WatchState.kt`:

```kotlin
package app.kaeru.domain.model

import java.time.Instant

data class WatchState(
    val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val translationId: Int?,
    val kodikSeason: Int?,
    val updatedAt: Instant,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
```

`domain/model/LibraryEntry.kt`:

```kotlin
package app.kaeru.domain.model

data class LibraryEntry(
    val anime: Anime,
    val rate: UserRate,
    val watch: WatchState?,
) {
    /**
     * Серия, которую надо запустить по кнопке «Смотреть»:
     * недосмотренная из WatchState, если она новее прогресса Shikimori, иначе следующая после просмотренных.
     */
    fun nextEpisode(watchedThreshold: Float): Int {
        val w = watch
        if (w != null && w.episode > rate.episodes) {
            return if (w.fraction < watchedThreshold) w.episode else w.episode + 1
        }
        return rate.episodes + 1
    }

    /** Прогресс внутри текущей серии для полоски под постером, null если серия ещё не начата. */
    fun progressFraction(watchedThreshold: Float): Float? {
        val w = watch ?: return null
        return if (w.episode == nextEpisode(watchedThreshold) && w.fraction in 0.01f..watchedThreshold) w.fraction else null
    }
}
```

`domain/model/HomeFeed.kt`:

```kotlin
package app.kaeru.domain.model

enum class FeedKind { CONTINUE, NEW_EPISODE, NEXT_UP, UPCOMING, PLANNED }

data class FeedItem(
    val entry: LibraryEntry,
    val episode: Int,
    val kind: FeedKind,
)

data class HomeFeed(
    val top: FeedItem?,
    val continueWatching: List<FeedItem>,
    val newEpisodes: List<FeedItem>,
    val nextUp: List<FeedItem>,
    val upcoming: List<FeedItem>,
    val planned: List<FeedItem>,
) {
    val isEmpty: Boolean get() = top == null && planned.isEmpty() && upcoming.isEmpty()
    companion object { val EMPTY = HomeFeed(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) }
}
```

- [ ] **Step 2: Интерфейсы репозиториев**

`domain/repository/LibraryRepository.kt`:

```kotlin
package app.kaeru.domain.repository

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryEntry>>
    fun observeAnime(id: Int): Flow<LibraryEntry?>
    /** user_rates всех статусов + аниме к ним. Дорого, вызывается при старте и pull-to-refresh. */
    suspend fun refresh(): Result<Unit>
    /** Детали одного аниме (описание, скриншоты, next_episode_at). */
    suspend fun refreshAnime(id: Int): Result<Unit>
    suspend fun search(query: String): Result<List<Anime>>
    suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit>
    suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit>
}
```

`domain/repository/AuthRepository.kt`:

```kotlin
package app.kaeru.domain.repository

import kotlinx.coroutines.flow.Flow

const val OOB_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
const val MOBILE_REDIRECT = "kaeru://oauth"

interface AuthRepository {
    val isLoggedIn: Flow<Boolean>
    fun authorizeUrl(redirectUri: String): String
    suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit>
    suspend fun logout()
}
```

- [ ] **Step 3: Failing test для HomeFeedBuilder**

`app/src/test/java/app/kaeru/domain/feed/HomeFeedBuilderTest.kt`:

```kotlin
package app.kaeru.domain.feed

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HomeFeedBuilderTest {
    private val now: Instant = Instant.parse("2026-09-12T12:00:00Z")
    private val builder = HomeFeedBuilder()

    private fun anime(
        id: Int, status: AnimeStatus = AnimeStatus.RELEASED, episodes: Int = 12,
        aired: Int = episodes, next: Instant? = null,
    ) = Anime(id, "Аниме $id", "Anime $id", null, emptyList(), status, episodes, aired, next, null, 2026, null, null)

    private fun entry(
        anime: Anime, status: ListStatus = ListStatus.WATCHING, watched: Int = 0,
        updatedAt: Instant = now, watch: WatchState? = null,
    ) = LibraryEntry(anime, UserRate(anime.id.toLong(), anime.id, status, watched, updatedAt), watch)

    private fun watching(animeId: Int, episode: Int, fraction: Float, at: Instant = now) =
        WatchState(animeId, episode, (fraction * 1_000_000).toLong(), 1_000_000, null, null, at)

    @Test
    fun `continue watching comes first and points at unfinished episode`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 4, watch = watching(1, 5, 0.4f))), now)
        assertEquals(1, feed.continueWatching.size)
        assertEquals(5, feed.continueWatching[0].episode)
        assertEquals(FeedKind.CONTINUE, feed.top?.kind)
        assertEquals(5, feed.top?.episode)
    }

    @Test
    fun `episode watched past threshold advances locally before shikimori sync`() {
        val a = anime(1)
        val feed = builder.build(listOf(entry(a, watched = 4, watch = watching(1, 5, 0.95f))), now)
        assertTrue(feed.continueWatching.isEmpty())
        assertEquals(FeedKind.NEXT_UP, feed.top?.kind)
        assertEquals(6, feed.top?.episode)
    }

    @Test
    fun `new episodes are ongoing titles with aired ahead of watched`() {
        val ongoing = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val caughtUp = anime(3, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val feed = builder.build(listOf(entry(ongoing, watched = 6), entry(caughtUp, watched = 7)), now)
        assertEquals(listOf(2), feed.newEpisodes.map { it.entry.anime.id })
        assertEquals(7, feed.newEpisodes[0].episode)
        assertEquals(FeedKind.NEW_EPISODE, feed.newEpisodes[0].kind)
    }

    @Test
    fun `new episode beats next up for the top card when nothing is in progress`() {
        val ongoing = anime(2, AnimeStatus.ONGOING, episodes = 24, aired = 7)
        val released = anime(1)
        val feed = builder.build(listOf(entry(released, watched = 3), entry(ongoing, watched = 6)), now)
        assertEquals(2, feed.top?.entry?.anime?.id)
        assertEquals(FeedKind.NEW_EPISODE, feed.top?.kind)
    }

    @Test
    fun `next up lists released titles with unwatched episodes sorted by recent activity`() {
        val old = anime(1)
        val fresh = anime(2)
        val feed = builder.build(
            listOf(
                entry(old, watched = 3, updatedAt = now.minus(Duration.ofDays(3))),
                entry(fresh, watched = 1, updatedAt = now.minus(Duration.ofHours(1))),
            ), now,
        )
        assertEquals(listOf(2, 1), feed.nextUp.map { it.entry.anime.id })
        assertEquals(2, feed.nextUp[0].episode)
    }

    @Test
    fun `upcoming is ongoing watching with next episode within window`() {
        val soon = anime(2, AnimeStatus.ONGOING, 24, aired = 7, next = now.plus(Duration.ofDays(2)))
        val far = anime(3, AnimeStatus.ONGOING, 24, aired = 7, next = now.plus(Duration.ofDays(20)))
        val feed = builder.build(listOf(entry(soon, watched = 7), entry(far, watched = 7)), now)
        assertEquals(listOf(2), feed.upcoming.map { it.entry.anime.id })
        assertEquals(8, feed.upcoming[0].episode)
    }

    @Test
    fun `planned titles fill the planned row and never the top card`() {
        val feed = builder.build(listOf(entry(anime(9), status = ListStatus.PLANNED)), now)
        assertEquals(1, feed.planned.size)
        assertEquals(1, feed.planned[0].episode)
        assertNull(feed.top)
    }

    @Test
    fun `completed and dropped titles are ignored`() {
        val feed = builder.build(
            listOf(entry(anime(1), ListStatus.COMPLETED, watched = 12), entry(anime(2), ListStatus.DROPPED, watched = 2)), now,
        )
        assertTrue(feed.isEmpty)
    }

    @Test
    fun `fully watched released title without shikimori update is not next up`() {
        val a = anime(1, episodes = 12)
        val feed = builder.build(listOf(entry(a, watched = 12)), now)
        assertTrue(feed.nextUp.isEmpty())
    }
}
```

- [ ] **Step 4: Запустить, убедиться, что падает**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.domain.feed.HomeFeedBuilderTest' --console=plain`
Expected: компиляция падает с `Unresolved reference: HomeFeedBuilder`.

- [ ] **Step 5: Реализация HomeFeedBuilder**

`domain/feed/HomeFeedBuilder.kt`:

```kotlin
package app.kaeru.domain.feed

import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import java.time.Duration
import java.time.Instant

class HomeFeedBuilder(
    private val watchedThreshold: Float = 0.9f,
    private val upcomingWindow: Duration = Duration.ofDays(7),
) {
    fun build(entries: List<LibraryEntry>, now: Instant): HomeFeed {
        val active = entries.filter { it.rate.status == ListStatus.WATCHING || it.rate.status == ListStatus.REWATCHING }

        val continueWatching = active
            .filter { e ->
                val w = e.watch ?: return@filter false
                w.episode > e.rate.episodes && w.fraction in 0.01f..<watchedThreshold
            }
            .sortedByDescending { it.watch!!.updatedAt }
            .map { FeedItem(it, it.watch!!.episode, FeedKind.CONTINUE) }
        val inProgressIds = continueWatching.map { it.entry.anime.id }.toSet()

        val newEpisodes = active
            .filter {
                it.anime.status == AnimeStatus.ONGOING &&
                    it.nextEpisode(watchedThreshold) <= it.anime.episodesAired &&
                    it.anime.id !in inProgressIds
            }
            .sortedByDescending { it.anime.nextEpisodeAt ?: it.rate.updatedAt }
            .map { FeedItem(it, it.nextEpisode(watchedThreshold), FeedKind.NEW_EPISODE) }

        val nextUp = active
            .filter {
                it.anime.status != AnimeStatus.ONGOING &&
                    it.nextEpisode(watchedThreshold) <= it.anime.availableEpisodes &&
                    it.anime.id !in inProgressIds
            }
            .sortedByDescending { it.rate.updatedAt }
            .map { FeedItem(it, it.nextEpisode(watchedThreshold), FeedKind.NEXT_UP) }

        val horizon = now.plus(upcomingWindow)
        val upcoming = active
            .filter { e ->
                val next = e.anime.nextEpisodeAt ?: return@filter false
                e.anime.status == AnimeStatus.ONGOING && next.isAfter(now) && next.isBefore(horizon)
            }
            .sortedBy { it.anime.nextEpisodeAt }
            .map { FeedItem(it, it.anime.episodesAired + 1, FeedKind.UPCOMING) }

        val planned = entries
            .filter { it.rate.status == ListStatus.PLANNED }
            .sortedByDescending { it.rate.updatedAt }
            .map { FeedItem(it, 1, FeedKind.PLANNED) }

        val top = continueWatching.firstOrNull() ?: newEpisodes.firstOrNull() ?: nextUp.firstOrNull()
        return HomeFeed(top, continueWatching, newEpisodes, nextUp, upcoming, planned)
    }
}
```

- [ ] **Step 6: Прогнать тесты**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.domain.feed.HomeFeedBuilderTest' --console=plain`
Expected: 9 тестов PASS. Тест «episode watched past threshold» подтверждает,
что локально завершённая 5-я серия сразу переводит карточку на 6-ю, даже если
Shikimori ещё хранит `episodes=4`; план 2 затем синхронизирует этот прогресс.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/java/app/kaeru/domain app/src/test/java/app/kaeru/domain
git commit -m "feat(domain): models, repositories and HomeFeedBuilder"
```

---

### Task 4: Shikimori REST API — DTO, Retrofit, лимитер

**Files:**
- Create: `app/src/main/java/app/kaeru/data/shikimori/ShikimoriDtos.kt`, `ShikimoriApi.kt`, `ShikimoriMappers.kt`, `RateLimitInterceptor.kt`, `UserAgentInterceptor.kt`
- Test: `app/src/test/java/app/kaeru/data/shikimori/ShikimoriApiTest.kt`, `RateLimitInterceptorTest.kt`, `ShikimoriMappersTest.kt`
- Test resources: `app/src/test/resources/shikimori/animes_list.json`, `anime_details.json`, `user_rates.json`

**Interfaces:**
- Produces:
  - `interface ShikimoriApi` с методами `whoami()`, `userRates(userId, status, page, limit)`, `animesByIds(ids: String, limit)`, `anime(id)`, `screenshots(id)`, `search(query, limit)`, `createUserRate(body)`, `updateUserRate(id, body)`
  - DTO: `UserDto(id, nickname, avatar)`, `AnimeShortDto`, `AnimeDetailsDto`, `ImageDto(original, preview)`, `ScreenshotDto(original, preview)`, `StudioDto(name)`, `UserRateDto(id, targetId, status, episodes, updatedAt)`, `UserRateRequest(userRate: UserRatePayload)`, `UserRatePayload(userId?, targetId?, targetType = "Anime", status?, episodes?)`
  - `fun AnimeShortDto.toDomain(): Anime`, `fun AnimeDetailsDto.toDomain(): Anime`, `fun UserRateDto.toDomain(): UserRate`
  - `class RateLimitInterceptor(perSecond: Int = 5, perMinute: Int = 90, clock: () -> Long = System::nanoTime, sleeper: (Long) -> Unit = Thread::sleep) : Interceptor`
  - `class UserAgentInterceptor(userAgent: String) : Interceptor`
  - `const val SHIKIMORI_BASE_URL = "https://shikimori.one/"`; `fun shikimoriJson(): Json`

- [ ] **Step 1: Фикстуры**

`app/src/test/resources/shikimori/animes_list.json` (реальная форма ответа `GET /api/animes?ids=`):

```json
[
  {
    "id": 52991,
    "name": "Sousou no Frieren",
    "russian": "Провожающая в последний путь Фрирен",
    "image": {"original": "/system/animes/original/52991.jpg", "preview": "/system/animes/preview/52991.jpg", "x96": "/system/animes/x96/52991.jpg", "x48": "/system/animes/x48/52991.jpg"},
    "url": "/animes/52991-sousou-no-frieren",
    "kind": "tv",
    "score": "9.3",
    "status": "released",
    "episodes": 28,
    "episodes_aired": 28,
    "aired_on": "2023-09-29",
    "released_on": "2024-03-22"
  },
  {
    "id": 60000,
    "name": "Ongoing Show",
    "russian": "",
    "image": {"original": "/system/animes/original/60000.jpg", "preview": "/system/animes/preview/60000.jpg", "x96": "/system/animes/x96/60000.jpg", "x48": "/system/animes/x48/60000.jpg"},
    "url": "/animes/60000-ongoing-show",
    "kind": "tv",
    "score": "0.0",
    "status": "ongoing",
    "episodes": 0,
    "episodes_aired": 7,
    "aired_on": "2026-07-05",
    "released_on": null
  }
]
```

`app/src/test/resources/shikimori/anime_details.json`:

```json
{
  "id": 60000,
  "name": "Ongoing Show",
  "russian": "Онгоинг",
  "image": {"original": "/system/animes/original/60000.jpg", "preview": "/system/animes/preview/60000.jpg", "x96": "/system/animes/x96/60000.jpg", "x48": "/system/animes/x48/60000.jpg"},
  "url": "/animes/60000-ongoing-show",
  "kind": "tv",
  "score": "7.8",
  "status": "ongoing",
  "episodes": 12,
  "episodes_aired": 7,
  "aired_on": "2026-07-05",
  "released_on": null,
  "rating": "pg_13",
  "english": ["Ongoing Show"],
  "japanese": ["進行中"],
  "synonyms": [],
  "license_name_ru": null,
  "duration": 24,
  "description": "Описание [character=1]персонажа[/character].",
  "description_html": "<p>Описание</p>",
  "description_source": null,
  "franchise": null,
  "favoured": false,
  "anons": false,
  "ongoing": true,
  "thread_id": 1,
  "topic_id": 1,
  "myanimelist_id": 60000,
  "rates_scores_stats": [],
  "rates_statuses_stats": [],
  "updated_at": "2026-09-10T10:00:00.000+03:00",
  "next_episode_at": "2026-09-14T17:00:00.000+03:00",
  "fansubbers": [],
  "fandubbers": [],
  "licensors": [],
  "genres": [{"id": 1, "name": "Action", "russian": "Экшен", "kind": "genre"}],
  "studios": [{"id": 10, "name": "MAPPA", "filtered_name": "MAPPA", "real": true, "image": null}],
  "videos": [],
  "screenshots": [
    {"original": "/system/screenshots/original/1.jpg", "preview": "/system/screenshots/x332/1.jpg"},
    {"original": "/system/screenshots/original/2.jpg", "preview": "/system/screenshots/x332/2.jpg"}
  ],
  "user_rate": null
}
```

`app/src/test/resources/shikimori/user_rates.json`:

```json
[
  {"id": 111, "user_id": 42, "target_id": 52991, "target_type": "Anime", "score": 10, "status": "watching", "rewatches": 0, "episodes": 20, "volumes": 0, "chapters": 0, "text": null, "text_html": "", "created_at": "2026-01-01T00:00:00.000+03:00", "updated_at": "2026-09-11T21:30:00.000+03:00"},
  {"id": 112, "user_id": 42, "target_id": 60000, "target_type": "Anime", "score": 0, "status": "watching", "rewatches": 0, "episodes": 6, "volumes": 0, "chapters": 0, "text": null, "text_html": "", "created_at": "2026-07-06T00:00:00.000+03:00", "updated_at": "2026-09-05T18:00:00.000+03:00"}
]
```

- [ ] **Step 2: Failing tests**

`app/src/test/java/app/kaeru/data/shikimori/ShikimoriApiTest.kt`:

```kotlin
package app.kaeru.data.shikimori

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinxserialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

class ShikimoriApiTest {
    private val server = MockWebServer()
    private lateinit var api: ShikimoriApi

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("shikimori/$name")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ShikimoriApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `parses anime list with unknown fields ignored`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("animes_list.json")))
        val list = api.animesByIds(ids = "52991,60000", limit = 50)
        assertEquals(2, list.size)
        assertEquals("Провожающая в последний путь Фрирен", list[0].russian)
        assertEquals(28, list[0].episodes)
        assertEquals("ongoing", list[1].status)
        assertEquals("/api/animes?ids=52991%2C60000&limit=50", server.takeRequest().path)
    }

    @Test
    fun `parses details with studios screenshots and next episode`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("anime_details.json")))
        val d = api.anime(60000)
        assertEquals("MAPPA", d.studios.first().name)
        assertEquals(2, d.screenshots.size)
        assertEquals("2026-09-14T17:00:00.000+03:00", d.nextEpisodeAt)
    }

    @Test
    fun `parses user rates`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("user_rates.json")))
        val rates = api.userRates(userId = 42, status = "watching", page = 1, limit = 1000)
        assertEquals(2, rates.size)
        assertEquals(52991, rates[0].targetId)
        assertEquals(20, rates[0].episodes)
        val url = server.takeRequest().requestUrl!!
        assertEquals("Anime", url.queryParameter("target_type"))
        assertEquals("42", url.queryParameter("user_id"))
        assertEquals("watching", url.queryParameter("status"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("1000", url.queryParameter("limit"))
    }

    @Test
    fun `update user rate sends wrapped payload`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("user_rates.json").removePrefix("[").substringBefore("},") + "}"))
        api.updateUserRate(111, UserRateRequest(UserRatePayload(episodes = 21)))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/v2/user_rates/111", req.path)
        assertEquals("""{"user_rate":{"episodes":21}}""", req.body.readUtf8())
    }
}
```

`RateLimitInterceptorTest.kt`:

```kotlin
package app.kaeru.data.shikimori

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitInterceptorTest {
    private var nowNanos = 0L
    private val sleeps = mutableListOf<Long>()
    private val interceptor = RateLimitInterceptor(
        perSecond = 5, perMinute = 90,
        clock = { nowNanos },
        sleeper = { ms -> sleeps += ms; nowNanos += ms * 1_000_000 },
    )

    private fun call() {
        val req = Request.Builder().url("https://shikimori.one/api/x").build()
        val chain = object : Interceptor.Chain {
            override fun request() = req
            override fun proceed(request: Request) =
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").build()
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }
        interceptor.intercept(chain)
    }

    @Test
    fun `five requests in one second pass without sleeping`() {
        repeat(5) { call() }
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun `sixth request within a second waits for the window`() {
        repeat(6) { call() }
        assertEquals(1, sleeps.size)
        assertEquals(1000L, sleeps[0])
    }

    @Test
    fun `ninety first request within a minute waits`() {
        repeat(90) { call(); nowNanos += 250_000_000 } // 4 rps → минутный лимит раньше секундного
        val before = sleeps.size
        call()
        assertEquals(before + 1, sleeps.size)
    }
}
```

`ShikimoriMappersTest.kt`:

```kotlin
package app.kaeru.data.shikimori

import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ShikimoriMappersTest {
    @Test
    fun `short dto maps poster to absolute url and status`() {
        val dto = AnimeShortDto(
            id = 1, name = "Name", russian = "Имя",
            image = ImageDto("/system/animes/original/1.jpg", "/system/animes/preview/1.jpg"),
            score = "8.1", status = "ongoing", episodes = 0, episodesAired = 3, airedOn = "2026-07-05",
        )
        val a = dto.toDomain()
        assertEquals("https://shikimori.one/system/animes/original/1.jpg", a.posterUrl)
        assertEquals(AnimeStatus.ONGOING, a.status)
        assertEquals(2026, a.year)
        assertEquals(8.1, a.score!!, 0.001)
    }

    @Test
    fun `details dto keeps screenshots studio and next episode`() {
        val dto = AnimeDetailsDto(
            id = 1, name = "Name", russian = "", image = ImageDto("/o.jpg", "/p.jpg"),
            score = "0.0", status = "anons", episodes = 12, episodesAired = 0, airedOn = null,
            description = "text", nextEpisodeAt = "2026-09-14T17:00:00.000+03:00",
            studios = listOf(StudioDto("MAPPA")), screenshots = listOf(ScreenshotDto("/s1.jpg", "/s1p.jpg")),
        )
        val a = dto.toDomain()
        assertEquals(Instant.parse("2026-09-14T14:00:00Z"), a.nextEpisodeAt)
        assertEquals("MAPPA", a.studio)
        assertEquals(listOf("https://shikimori.one/s1.jpg"), a.screenshotUrls)
        assertEquals(null, a.score)
        assertEquals(AnimeStatus.ANONS, a.status)
    }

    @Test
    fun `user rate dto maps status and time`() {
        val r = UserRateDto(id = 5, targetId = 9, status = "on_hold", episodes = 3, updatedAt = "2026-09-11T21:30:00.000+03:00").toDomain()
        assertEquals(ListStatus.ON_HOLD, r.status)
        assertEquals(Instant.parse("2026-09-11T18:30:00Z"), r.updatedAt)
    }
}
```

- [ ] **Step 3: Запустить, убедиться, что падает**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.data.shikimori.*' --console=plain`
Expected: ошибки компиляции `Unresolved reference`.

- [ ] **Step 4: DTO и API**

`data/shikimori/ShikimoriDtos.kt`:

```kotlin
package app.kaeru.data.shikimori

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SHIKIMORI_BASE_URL = "https://shikimori.one/"

fun shikimoriJson(): Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable
data class UserDto(val id: Long, val nickname: String, val avatar: String? = null)

@Serializable
data class ImageDto(val original: String? = null, val preview: String? = null)

@Serializable
data class ScreenshotDto(val original: String? = null, val preview: String? = null)

@Serializable
data class StudioDto(val name: String)

@Serializable
data class AnimeShortDto(
    val id: Int,
    val name: String = "",
    val russian: String? = null,
    val image: ImageDto? = null,
    val score: String? = null,
    val status: String = "released",
    val episodes: Int = 0,
    @SerialName("episodes_aired") val episodesAired: Int = 0,
    @SerialName("aired_on") val airedOn: String? = null,
)

@Serializable
data class AnimeDetailsDto(
    val id: Int,
    val name: String = "",
    val russian: String? = null,
    val image: ImageDto? = null,
    val score: String? = null,
    val status: String = "released",
    val episodes: Int = 0,
    @SerialName("episodes_aired") val episodesAired: Int = 0,
    @SerialName("aired_on") val airedOn: String? = null,
    val description: String? = null,
    @SerialName("next_episode_at") val nextEpisodeAt: String? = null,
    val studios: List<StudioDto> = emptyList(),
    val screenshots: List<ScreenshotDto> = emptyList(),
)

@Serializable
data class UserRateDto(
    val id: Long,
    @SerialName("target_id") val targetId: Int,
    val status: String,
    val episodes: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class UserRatePayload(
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("target_id") val targetId: Int? = null,
    @SerialName("target_type") val targetType: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
)

@Serializable
data class UserRateRequest(@SerialName("user_rate") val userRate: UserRatePayload)
```

`data/shikimori/ShikimoriApi.kt`:

```kotlin
package app.kaeru.data.shikimori

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ShikimoriApi {
    @GET("api/users/whoami")
    suspend fun whoami(): UserDto

    @GET("api/v2/user_rates?target_type=Anime")
    suspend fun userRates(
        @Query("user_id") userId: Long,
        @Query("status") status: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 1000,
    ): List<UserRateDto>

    @GET("api/animes")
    suspend fun animesByIds(@Query("ids") ids: String, @Query("limit") limit: Int = 50): List<AnimeShortDto>

    @GET("api/animes/{id}")
    suspend fun anime(@Path("id") id: Int): AnimeDetailsDto

    @GET("api/animes/{id}/screenshots")
    suspend fun screenshots(@Path("id") id: Int): List<ScreenshotDto>

    @GET("api/animes")
    suspend fun search(@Query("search") query: String, @Query("limit") limit: Int = 30): List<AnimeShortDto>

    @POST("api/v2/user_rates")
    suspend fun createUserRate(@Body body: UserRateRequest): UserRateDto

    @PATCH("api/v2/user_rates/{id}")
    suspend fun updateUserRate(@Path("id") id: Long, @Body body: UserRateRequest): UserRateDto
}
```

`data/shikimori/ShikimoriMappers.kt`:

```kotlin
package app.kaeru.data.shikimori

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import java.time.Instant
import java.time.OffsetDateTime

internal fun absolute(path: String?): String? =
    path?.let { if (it.startsWith("http")) it else SHIKIMORI_BASE_URL.dropLast(1) + it }

internal fun parseStatus(s: String): AnimeStatus = when (s) {
    "ongoing" -> AnimeStatus.ONGOING
    "anons" -> AnimeStatus.ANONS
    else -> AnimeStatus.RELEASED
}

internal fun parseScore(s: String?): Double? = s?.toDoubleOrNull()?.takeIf { it > 0.0 }

internal fun parseYear(airedOn: String?): Int? = airedOn?.take(4)?.toIntOrNull()

internal fun parseInstant(s: String?): Instant? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

fun AnimeShortDto.toDomain(): Anime = Anime(
    id = id,
    nameRu = russian.orEmpty(),
    nameRomaji = name,
    posterUrl = absolute(image?.original),
    screenshotUrls = emptyList(),
    status = parseStatus(status),
    episodes = episodes,
    episodesAired = episodesAired,
    nextEpisodeAt = null,
    score = parseScore(score),
    year = parseYear(airedOn),
    studio = null,
    description = null,
)

fun AnimeDetailsDto.toDomain(): Anime = Anime(
    id = id,
    nameRu = russian.orEmpty(),
    nameRomaji = name,
    posterUrl = absolute(image?.original),
    screenshotUrls = screenshots.mapNotNull { absolute(it.original) },
    status = parseStatus(status),
    episodes = episodes,
    episodesAired = episodesAired,
    nextEpisodeAt = parseInstant(nextEpisodeAt),
    score = parseScore(score),
    year = parseYear(airedOn),
    studio = studios.firstOrNull()?.name,
    description = description?.replace(Regex("\\[/?[a-z_]+(=[^\\]]*)?]"), ""),
)

fun UserRateDto.toDomain(): UserRate = UserRate(
    id = id,
    animeId = targetId,
    status = ListStatus.fromApi(status),
    episodes = episodes,
    updatedAt = parseInstant(updatedAt) ?: Instant.EPOCH,
)
```

- [ ] **Step 5: Интерцепторы**

`data/shikimori/RateLimitInterceptor.kt`:

```kotlin
package app.kaeru.data.shikimori

import okhttp3.Interceptor
import okhttp3.Response
import java.util.ArrayDeque

/**
 * Скользящие окна: не больше [perSecond] запросов за последнюю секунду и [perMinute] за минуту.
 * Блокирует поток OkHttp через [sleeper]; в тестах подменяются часы и сон.
 */
class RateLimitInterceptor(
    private val perSecond: Int = 5,
    private val perMinute: Int = 90,
    private val clock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : Interceptor {
    private val lock = Any()
    private val second = ArrayDeque<Long>()
    private val minute = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        awaitSlot()
        return chain.proceed(chain.request())
    }

    private fun awaitSlot() {
        synchronized(lock) {
            while (true) {
                val now = clock()
                trim(second, now, 1_000_000_000L)
                trim(minute, now, 60_000_000_000L)
                val waitSec = if (second.size >= perSecond) 1_000_000_000L - (now - second.first) else 0
                val waitMin = if (minute.size >= perMinute) 60_000_000_000L - (now - minute.first) else 0
                val wait = maxOf(waitSec, waitMin)
                if (wait <= 0) {
                    second.addLast(now); minute.addLast(now)
                    return
                }
                sleeper((wait + 999_999) / 1_000_000)
            }
        }
    }

    private fun trim(q: ArrayDeque<Long>, now: Long, window: Long) {
        while (q.isNotEmpty() && now - q.first >= window) q.removeFirst()
    }
}
```

`data/shikimori/UserAgentInterceptor.kt`:

```kotlin
package app.kaeru.data.shikimori

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}
```

- [ ] **Step 6: Прогнать тесты**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.data.shikimori.*' --console=plain`
Expected: все PASS. В `sixth request within a second waits` ожидание ровно 1000 мс, потому что часы в тесте не двигаются между вызовами.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/java/app/kaeru/data/shikimori app/src/test
git commit -m "feat(data): shikimori api, dtos, mappers and rate limiter"
```

---

### Task 5: OAuth Shikimori — хранение токенов, refresh, репозиторий

**Files:**
- Create: `app/src/main/java/app/kaeru/data/shikimori/ShikimoriOAuthApi.kt`, `AuthInterceptor.kt`, `TokenAuthenticator.kt`
- Create: `app/src/main/java/app/kaeru/data/auth/TokenStore.kt`, `DataStoreTokenStore.kt`, `ShikimoriAuthRepository.kt`
- Create: `app/src/main/java/app/kaeru/di/NetworkModule.kt`
- Test: `app/src/test/java/app/kaeru/data/shikimori/TokenAuthenticatorTest.kt`, `app/src/test/java/app/kaeru/data/auth/ShikimoriAuthRepositoryTest.kt`

**Interfaces:**
- Consumes: `ShikimoriApi`, `shikimoriJson()`, `RateLimitInterceptor`, `UserAgentInterceptor` из Task 4; `AuthRepository`, `MOBILE_REDIRECT`, `OOB_REDIRECT` из Task 3; `BuildConfig` из Task 2.
- Produces:
  - `data class AuthTokens(accessToken: String, refreshToken: String, expiresAtEpochSec: Long)`
  - `interface TokenStore { val tokens: Flow<AuthTokens?>; suspend fun get(): AuthTokens?; suspend fun set(tokens: AuthTokens?) }`
  - `class InMemoryTokenStore : TokenStore` (в `src/test`)
  - `interface ShikimoriOAuthApi { suspend fun token(grantType, clientId, clientSecret, code?, redirectUri?, refreshToken?): TokenResponseDto }`
  - `class AuthInterceptor(store: TokenStore)`, `class TokenAuthenticator(store, oauthApi, clientId, clientSecret, clock: Clock)`
  - Hilt-квалификаторы `@ShikimoriClient`, `@PlainClient`; `NetworkModule` предоставляет `OkHttpClient`, `ShikimoriApi`, `ShikimoriOAuthApi`, `Json`
  - `class ShikimoriAuthRepository @Inject constructor(oauthApi, store, clientId, clientSecret, clock: Clock) : AuthRepository`

- [ ] **Step 1: Failing test для Authenticator**

`app/src/test/java/app/kaeru/data/auth/InMemoryTokenStore.kt`:

```kotlin
package app.kaeru.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private val state = MutableStateFlow(initial)
    override val tokens: Flow<AuthTokens?> = state
    override suspend fun get(): AuthTokens? = state.value
    override suspend fun set(tokens: AuthTokens?) { state.value = tokens }
}
```

`app/src/test/java/app/kaeru/data/shikimori/TokenAuthenticatorTest.kt`:

```kotlin
package app.kaeru.data.shikimori

import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinxserialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TokenAuthenticatorTest {
    private val server = MockWebServer()
    private val store = InMemoryTokenStore(AuthTokens("old", "refresh-1", expiresAtEpochSec = 0))
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        val oauth = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ShikimoriOAuthApi::class.java)
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(
                TokenAuthenticator(
                    store, oauth, clientId = "cid", clientSecret = "sec",
                    clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC),
                ),
            )
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `on 401 refreshes token once and retries with new bearer`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"new","token_type":"Bearer","expires_in":86400,"refresh_token":"refresh-2","scope":"user_rates","created_at":1757600000}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val resp = client.newCall(Request.Builder().url(server.url("/api/users/whoami")).build()).execute()

        assertEquals(200, resp.code)
        val first = server.takeRequest(); assertEquals("Bearer old", first.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/oauth/token", refresh.path)
        val body = refresh.body.readUtf8()
        assert(body.contains("grant_type=refresh_token")) { body }
        assert(body.contains("refresh_token=refresh-1")) { body }
        val retry = server.takeRequest(); assertEquals("Bearer new", retry.getHeader("Authorization"))
        assertEquals("refresh-2", runBlocking { store.get() }!!.refreshToken)
    }

    @Test
    fun `when refresh fails tokens are cleared and 401 is returned`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        val resp = client.newCall(Request.Builder().url(server.url("/api/users/whoami")).build()).execute()

        assertEquals(401, resp.code)
        assertEquals(null, runBlocking { store.get() })
    }

    @Test
    fun `oauth token endpoint itself is never retried`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val resp = client.newCall(
            Request.Builder().url(server.url("/oauth/token")).post(okhttp3.FormBody.Builder().add("a", "b").build()).build(),
        ).execute()
        assertEquals(401, resp.code)
        assertEquals(1, server.requestCount)
    }
}
```

- [ ] **Step 2: Failing test для репозитория**

`app/src/test/java/app/kaeru/data/auth/ShikimoriAuthRepositoryTest.kt`:

```kotlin
package app.kaeru.data.auth

import app.cash.turbine.test
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.data.shikimori.TokenResponseDto
import app.kaeru.domain.repository.MOBILE_REDIRECT
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ShikimoriAuthRepositoryTest {
    private val oauth = mockk<ShikimoriOAuthApi>()
    private val store = InMemoryTokenStore()
    private val repo = ShikimoriAuthRepository(
        oauth,
        store,
        clientId = "cid",
        clientSecret = "sec",
        clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC),
    )

    @Test
    fun `authorize url contains client id redirect and scope`() {
        val url = repo.authorizeUrl(MOBILE_REDIRECT)
        assertEquals(
            "https://shikimori.one/oauth/authorize?client_id=cid&redirect_uri=kaeru%3A%2F%2Foauth&response_type=code&scope=user_rates",
            url,
        )
    }

    @Test
    fun `exchange code stores tokens and flips isLoggedIn`() = runTest {
        coEvery { oauth.token(grantType = "authorization_code", clientId = "cid", clientSecret = "sec", code = "abc", redirectUri = MOBILE_REDIRECT) } returns
            TokenResponseDto("acc", "Bearer", 86400, "ref", "user_rates", 1_000L)
        repo.isLoggedIn.test {
            assertEquals(false, awaitItem())
            assertTrue(repo.exchangeCode("abc", MOBILE_REDIRECT).isSuccess)
            assertEquals(true, awaitItem())
        }
        assertEquals(AuthTokens("acc", "ref", 1_000L + 86400), store.get())
    }

    @Test
    fun `logout clears tokens`() = runTest {
        store.set(AuthTokens("a", "r", 5))
        repo.logout()
        assertEquals(null, store.get())
    }
}
```

- [ ] **Step 3: Запустить, убедиться, что падает**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.data.shikimori.TokenAuthenticatorTest' --tests 'app.kaeru.data.auth.*' --console=plain`
Expected: `Unresolved reference`.

- [ ] **Step 4: Реализация**

`data/auth/TokenStore.kt`:

```kotlin
package app.kaeru.data.auth

import kotlinx.coroutines.flow.Flow

data class AuthTokens(val accessToken: String, val refreshToken: String, val expiresAtEpochSec: Long)

interface TokenStore {
    val tokens: Flow<AuthTokens?>
    suspend fun get(): AuthTokens?
    suspend fun set(tokens: AuthTokens?)
}
```

`data/auth/DataStoreTokenStore.kt`:

```kotlin
package app.kaeru.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DataStoreTokenStore @Inject constructor(
    @Named("auth") private val dataStore: DataStore<Preferences>,
) : TokenStore {
    private val access = stringPreferencesKey("access_token")
    private val refresh = stringPreferencesKey("refresh_token")
    private val expires = longPreferencesKey("expires_at")

    override val tokens: Flow<AuthTokens?> = dataStore.data.map { it.read() }

    override suspend fun get(): AuthTokens? = dataStore.data.first().read()

    override suspend fun set(tokens: AuthTokens?) {
        dataStore.edit { p ->
            if (tokens == null) { p.remove(access); p.remove(refresh); p.remove(expires) } else {
                p[access] = tokens.accessToken; p[refresh] = tokens.refreshToken; p[expires] = tokens.expiresAtEpochSec
            }
        }
    }

    private fun Preferences.read(): AuthTokens? {
        val a = this[access] ?: return null
        val r = this[refresh] ?: return null
        return AuthTokens(a, r, this[expires] ?: 0L)
    }
}
```

`data/shikimori/ShikimoriOAuthApi.kt`:

```kotlin
package app.kaeru.data.shikimori

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 86400,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

interface ShikimoriOAuthApi {
    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun token(
        @Field("grant_type") grantType: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String? = null,
        @Field("redirect_uri") redirectUri: String? = null,
        @Field("refresh_token") refreshToken: String? = null,
    ): TokenResponseDto
}
```

`data/shikimori/AuthInterceptor.kt`:

```kotlin
package app.kaeru.data.shikimori

import app.kaeru.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.encodedPath.startsWith("/oauth/")) return chain.proceed(req)
        val token = runBlocking { store.get() }?.accessToken ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
```

`data/shikimori/TokenAuthenticator.kt`:

```kotlin
package app.kaeru.data.shikimori

import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.time.Clock

/** На 401 один раз обновляет access token по refresh token и повторяет запрос. */
class TokenAuthenticator(
    private val store: TokenStore,
    private val oauthApi: ShikimoriOAuthApi,
    private val clientId: String,
    private val clientSecret: String,
    private val clock: Clock,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.startsWith("/oauth/")) return null
        if (response.priorResponse != null) return null // уже повторяли
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = synchronized(lock) {
            val current = runBlocking { store.get() } ?: return null
            if (current.accessToken != failedToken) return@synchronized current // кто-то уже обновил
            refresh(current)
        } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
    }

    private fun refresh(current: AuthTokens): AuthTokens? = runBlocking {
        runCatching {
            oauthApi.token(grantType = "refresh_token", clientId = clientId, clientSecret = clientSecret, refreshToken = current.refreshToken)
        }.map { AuthTokens(it.accessToken, it.refreshToken, clock.instant().epochSecond + it.expiresIn) }
            .onSuccess { store.set(it) }
            .onFailure { store.set(null) }
            .getOrNull()
    }
}
```

`data/auth/ShikimoriAuthRepository.kt`:

```kotlin
package app.kaeru.data.auth

import app.kaeru.data.shikimori.SHIKIMORI_BASE_URL
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.net.URLEncoder
import java.time.Clock
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ShikimoriAuthRepository @Inject constructor(
    private val oauthApi: ShikimoriOAuthApi,
    private val store: TokenStore,
    @Named("shikimoriClientId") private val clientId: String,
    @Named("shikimoriClientSecret") private val clientSecret: String,
    private val clock: Clock,
) : AuthRepository {

    override val isLoggedIn: Flow<Boolean> = store.tokens.map { it != null }.distinctUntilChanged()

    override fun authorizeUrl(redirectUri: String): String {
        val redirect = URLEncoder.encode(redirectUri, "UTF-8")
        return "${SHIKIMORI_BASE_URL}oauth/authorize?client_id=$clientId&redirect_uri=$redirect&response_type=code&scope=user_rates"
    }

    override suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit> = runCatching {
        val t = oauthApi.token(grantType = "authorization_code", clientId = clientId, clientSecret = clientSecret, code = code, redirectUri = redirectUri)
        store.set(AuthTokens(t.accessToken, t.refreshToken, clock.instant().epochSecond + t.expiresIn))
    }

    override suspend fun logout() = store.set(null)
}
```

Время передаётся через `java.time.Clock`: production-граф получает системные
UTC-часы, а тесты — `Clock.fixed(...)`. Так Hilt не приходится инжектить
Kotlin-лямбду с параметром по умолчанию.

`di/NetworkModule.kt`:

```kotlin
package app.kaeru.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.kaeru.BuildConfig
import app.kaeru.data.auth.DataStoreTokenStore
import app.kaeru.data.auth.ShikimoriAuthRepository
import app.kaeru.data.auth.TokenStore
import app.kaeru.data.shikimori.AuthInterceptor
import app.kaeru.data.shikimori.RateLimitInterceptor
import app.kaeru.data.shikimori.SHIKIMORI_BASE_URL
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.data.shikimori.TokenAuthenticator
import app.kaeru.data.shikimori.UserAgentInterceptor
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.repository.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinxserialization.asConverterFactory
import java.time.Clock
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ShikimoriClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PlainClient

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore("auth")
private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore("prefs")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private val USER_AGENT = "Kaeru/${BuildConfig.VERSION_NAME}"

    @Provides @Named("shikimoriClientId") fun clientId(): String = BuildConfig.SHIKIMORI_CLIENT_ID
    @Provides @Named("shikimoriClientSecret") fun clientSecret(): String = BuildConfig.SHIKIMORI_CLIENT_SECRET

    @Provides @Singleton @Named("auth")
    fun authDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.authDataStore

    @Provides @Singleton @Named("prefs")
    fun prefsDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.prefsDataStore

    @Provides @Singleton fun json(): Json = shikimoriJson()
    @Provides @Singleton fun clock(): Clock = Clock.systemUTC()

    private fun logging() = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    /** Клиент без авторизации: OAuth-обмен, Kodik, картинки. */
    @Provides @Singleton @PlainClient
    fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(UserAgentInterceptor(USER_AGENT))
        .addInterceptor(logging())
        .build()

    @Provides @Singleton
    fun oauthApi(@PlainClient client: OkHttpClient, json: Json): ShikimoriOAuthApi = Retrofit.Builder()
        .baseUrl(SHIKIMORI_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ShikimoriOAuthApi::class.java)

    @Provides @Singleton @ShikimoriClient
    fun shikimoriClient(
        @PlainClient plain: OkHttpClient,
        store: TokenStore,
        oauthApi: ShikimoriOAuthApi,
        @Named("shikimoriClientId") clientId: String,
        @Named("shikimoriClientSecret") clientSecret: String,
        clock: Clock,
    ): OkHttpClient = plain.newBuilder()
        .addInterceptor(RateLimitInterceptor())
        .addInterceptor(AuthInterceptor(store))
        .authenticator(TokenAuthenticator(store, oauthApi, clientId, clientSecret, clock))
        .build()

    @Provides @Singleton
    fun shikimoriApi(@ShikimoriClient client: OkHttpClient, json: Json): ShikimoriApi = Retrofit.Builder()
        .baseUrl(SHIKIMORI_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ShikimoriApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindings {
    @Binds abstract fun tokenStore(impl: DataStoreTokenStore): TokenStore
    @Binds abstract fun authRepository(impl: ShikimoriAuthRepository): AuthRepository
}
```

- [ ] **Step 5: Прогнать тесты и сборку**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: все тесты PASS, Hilt-граф собирается (ошибки Hilt проявляются на `assembleDebug`, не на unit-тестах).

- [ ] **Step 6: Коммит**

```bash
git add app/src
git commit -m "feat(auth): shikimori oauth, token store, refresh authenticator, network module"
```

---

### Task 6: Room — сущности, DAO, база

**Files:**
- Create: `app/src/main/java/app/kaeru/data/local/Converters.kt`, `AnimeEntity.kt`, `UserRateEntity.kt`, `WatchStateEntity.kt`, `AnimeDao.kt`, `UserRateDao.kt`, `WatchStateDao.kt`, `KaeruDatabase.kt`
- Create: `app/src/main/java/app/kaeru/di/DatabaseModule.kt`
- Test: `app/src/test/java/app/kaeru/data/local/KaeruDatabaseTest.kt`
- Modify: `app/src/test/resources/robolectric.properties` (create)

**Interfaces:**
- Consumes: доменные модели из Task 3.
- Produces:
  - `AnimeEntity(id, nameRu, nameRomaji, posterUrl, screenshots: List<String>, status: AnimeStatus, episodes, episodesAired, nextEpisodeAt: Instant?, score, year, studio, description, detailsFetchedAt: Instant?)` + `toDomain()` / `Anime.toEntity(detailsFetchedAt)`
  - `UserRateEntity(id, animeId, status: ListStatus, episodes, updatedAt)` + мапперы
  - `WatchStateEntity(animeId @PrimaryKey, episode, positionMs, durationMs, translationId, kodikSeason, updatedAt)` + мапперы
  - `AnimeDao { upsertAll(List<AnimeEntity>); observeAll(): Flow<List<AnimeEntity>>; observeById(id): Flow<AnimeEntity?>; getById(id): AnimeEntity?; getByIds(ids): List<AnimeEntity> }`
  - `UserRateDao { upsertAll(List); observeAll(); getByAnimeId(animeId); replaceAll(List) @Transaction; deleteByAnimeId(animeId) }`
  - `WatchStateDao { upsert(WatchStateEntity); observeAll(); getByAnimeId(animeId); deleteByAnimeId(animeId) }`
  - `abstract class KaeruDatabase : RoomDatabase` с `animeDao()`, `userRateDao()`, `watchStateDao()`; `DatabaseModule` предоставляет базу и DAO.

- [ ] **Step 1: Failing test**

`app/src/test/resources/robolectric.properties`:

```properties
sdk=34
```

`app/src/test/java/app/kaeru/data/local/KaeruDatabaseTest.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class KaeruDatabaseTest {
    private lateinit var db: KaeruDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun anime(id: Int) = AnimeEntity(
        id = id, nameRu = "Имя $id", nameRomaji = "Name $id", posterUrl = null, screenshots = listOf("a", "b"),
        status = AnimeStatus.ONGOING, episodes = 12, episodesAired = 3, nextEpisodeAt = Instant.ofEpochSecond(100),
        score = 8.0, year = 2026, studio = "MAPPA", description = null, detailsFetchedAt = null,
    )

    @Test
    fun `anime round trip keeps lists enums and instants`() = runTest {
        db.animeDao().upsertAll(listOf(anime(1)))
        val loaded = db.animeDao().getById(1)!!
        assertEquals(listOf("a", "b"), loaded.screenshots)
        assertEquals(AnimeStatus.ONGOING, loaded.status)
        assertEquals(Instant.ofEpochSecond(100), loaded.nextEpisodeAt)
    }

    @Test
    fun `replaceAll removes rates that disappeared`() = runTest {
        val dao = db.userRateDao()
        dao.upsertAll(listOf(UserRateEntity(1, 10, ListStatus.WATCHING, 2, Instant.EPOCH), UserRateEntity(2, 20, ListStatus.PLANNED, 0, Instant.EPOCH)))
        dao.replaceAll(listOf(UserRateEntity(1, 10, ListStatus.WATCHING, 3, Instant.EPOCH)))
        dao.observeAll().test {
            val rates = awaitItem()
            assertEquals(1, rates.size)
            assertEquals(3, rates[0].episodes)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(dao.getByAnimeId(20))
    }

    @Test
    fun `watch state upsert overwrites by anime id`() = runTest {
        val dao = db.watchStateDao()
        dao.upsert(WatchStateEntity(5, 1, 1000, 100000, null, null, Instant.EPOCH))
        dao.upsert(WatchStateEntity(5, 2, 500, 100000, 7, 1, Instant.ofEpochSecond(9)))
        val w = dao.getByAnimeId(5)!!
        assertEquals(2, w.episode)
        assertEquals(7, w.translationId)
        dao.observeAll().test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Запустить, убедиться, что падает**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.data.local.*' --console=plain`
Expected: `Unresolved reference: KaeruDatabase`.

- [ ] **Step 3: Реализация**

`data/local/Converters.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.TypeConverter
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import java.time.Instant

class Converters {
    @TypeConverter fun instantToLong(v: Instant?): Long? = v?.toEpochMilli()
    @TypeConverter fun longToInstant(v: Long?): Instant? = v?.let(Instant::ofEpochMilli)
    @TypeConverter fun animeStatusToString(v: AnimeStatus): String = v.name
    @TypeConverter fun stringToAnimeStatus(v: String): AnimeStatus = AnimeStatus.valueOf(v)
    @TypeConverter fun listStatusToString(v: ListStatus): String = v.name
    @TypeConverter fun stringToListStatus(v: String): ListStatus = ListStatus.valueOf(v)
    @TypeConverter fun listToString(v: List<String>): String = v.joinToString("\n")
    @TypeConverter fun stringToList(v: String): List<String> = if (v.isEmpty()) emptyList() else v.split("\n")
}
```

`data/local/AnimeEntity.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import java.time.Instant

@Entity(tableName = "anime")
data class AnimeEntity(
    @PrimaryKey val id: Int,
    val nameRu: String,
    val nameRomaji: String,
    val posterUrl: String?,
    val screenshots: List<String>,
    val status: AnimeStatus,
    val episodes: Int,
    val episodesAired: Int,
    val nextEpisodeAt: Instant?,
    val score: Double?,
    val year: Int?,
    val studio: String?,
    val description: String?,
    /** Когда последний раз загружали /api/animes/{id}; null — только короткая карточка. */
    val detailsFetchedAt: Instant?,
) {
    fun toDomain() = Anime(id, nameRu, nameRomaji, posterUrl, screenshots, status, episodes, episodesAired, nextEpisodeAt, score, year, studio, description)
}

fun Anime.toEntity(detailsFetchedAt: Instant?) = AnimeEntity(
    id, nameRu, nameRomaji, posterUrl, screenshotUrls, status, episodes, episodesAired, nextEpisodeAt, score, year, studio, description, detailsFetchedAt,
)

/** Короткий ответ списка не должен затирать детали, полученные раньше. */
fun AnimeEntity.mergeShort(fresh: Anime): AnimeEntity = copy(
    nameRu = fresh.nameRu, nameRomaji = fresh.nameRomaji, posterUrl = fresh.posterUrl ?: posterUrl,
    status = fresh.status, episodes = fresh.episodes, episodesAired = fresh.episodesAired,
    score = fresh.score ?: score, year = fresh.year ?: year,
)
```

`data/local/UserRateEntity.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import java.time.Instant

@Entity(tableName = "user_rate", indices = [Index("animeId", unique = true)])
data class UserRateEntity(
    @PrimaryKey val id: Long,
    val animeId: Int,
    val status: ListStatus,
    val episodes: Int,
    val updatedAt: Instant,
) {
    fun toDomain() = UserRate(id, animeId, status, episodes, updatedAt)
}

fun UserRate.toEntity() = UserRateEntity(id, animeId, status, episodes, updatedAt)
```

`data/local/WatchStateEntity.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.kaeru.domain.model.WatchState
import java.time.Instant

@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val translationId: Int?,
    val kodikSeason: Int?,
    val updatedAt: Instant,
) {
    fun toDomain() = WatchState(animeId, episode, positionMs, durationMs, translationId, kodikSeason, updatedAt)
}

fun WatchState.toEntity() = WatchStateEntity(animeId, episode, positionMs, durationMs, translationId, kodikSeason, updatedAt)
```

`data/local/AnimeDao.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimeDao {
    @Upsert suspend fun upsertAll(items: List<AnimeEntity>)
    @Query("SELECT * FROM anime") fun observeAll(): Flow<List<AnimeEntity>>
    @Query("SELECT * FROM anime WHERE id = :id") fun observeById(id: Int): Flow<AnimeEntity?>
    @Query("SELECT * FROM anime WHERE id = :id") suspend fun getById(id: Int): AnimeEntity?
    @Query("SELECT * FROM anime WHERE id IN (:ids)") suspend fun getByIds(ids: List<Int>): List<AnimeEntity>
}
```

`data/local/UserRateDao.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserRateDao {
    @Upsert suspend fun upsertAll(items: List<UserRateEntity>)
    @Query("SELECT * FROM user_rate") fun observeAll(): Flow<List<UserRateEntity>>
    @Query("SELECT * FROM user_rate WHERE animeId = :animeId") suspend fun getByAnimeId(animeId: Int): UserRateEntity?
    @Query("DELETE FROM user_rate") suspend fun deleteAll()
    @Query("DELETE FROM user_rate WHERE animeId = :animeId") suspend fun deleteByAnimeId(animeId: Int)

    @Transaction
    suspend fun replaceAll(items: List<UserRateEntity>) {
        deleteAll()
        upsertAll(items)
    }
}
```

`data/local/WatchStateDao.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchStateDao {
    @Upsert suspend fun upsert(item: WatchStateEntity)
    @Query("SELECT * FROM watch_state") fun observeAll(): Flow<List<WatchStateEntity>>
    @Query("SELECT * FROM watch_state WHERE animeId = :animeId") suspend fun getByAnimeId(animeId: Int): WatchStateEntity?
    @Query("DELETE FROM watch_state WHERE animeId = :animeId") suspend fun deleteByAnimeId(animeId: Int)
}
```

`data/local/KaeruDatabase.kt`:

```kotlin
package app.kaeru.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [AnimeEntity::class, UserRateEntity::class, WatchStateEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class KaeruDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun userRateDao(): UserRateDao
    abstract fun watchStateDao(): WatchStateDao
}
```

`di/DatabaseModule.kt`:

```kotlin
package app.kaeru.di

import android.content.Context
import androidx.room.Room
import app.kaeru.data.local.AnimeDao
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.WatchStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): KaeruDatabase =
        Room.databaseBuilder(ctx, KaeruDatabase::class.java, "kaeru.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun animeDao(db: KaeruDatabase): AnimeDao = db.animeDao()
    @Provides fun userRateDao(db: KaeruDatabase): UserRateDao = db.userRateDao()
    @Provides fun watchStateDao(db: KaeruDatabase): WatchStateDao = db.watchStateDao()
}
```

Пока приложение не выпущено, `fallbackToDestructiveMigration` допустим; с первого релиза в плане 3 переходим на миграции.

- [ ] **Step 4: Прогнать тесты**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.data.local.*' --console=plain`
Expected: 3 теста PASS. Первый запуск Robolectric скачивает `android-all` jar, это минута-две.

- [ ] **Step 5: Коммит**

```bash
git add app/src
git commit -m "feat(data): room database with anime, user_rate and watch_state"
```

---

### Task 7: LibraryRepository — синк Shikimori → Room

**Files:**
- Create: `app/src/main/java/app/kaeru/data/library/AppPreferences.kt`, `ShikimoriLibraryRepository.kt`
- Create: `app/src/main/java/app/kaeru/di/RepositoryModule.kt`
- Test: `app/src/test/java/app/kaeru/data/library/ShikimoriLibraryRepositoryTest.kt`, `app/src/test/java/app/kaeru/data/library/FakeShikimoriApi.kt`

**Interfaces:**
- Consumes: `ShikimoriApi` и мапперы (Task 4), DAO и entity (Task 6), `LibraryRepository` (Task 3), `@Named("prefs") DataStore<Preferences>` (Task 5).
- Produces:
  - `class AppPreferences(dataStore)`: `suspend fun userId(): Long?`, `suspend fun setUserId(Long)`, `suspend fun lastFullSync(): Instant?`, `suspend fun setLastFullSync(Instant)`, `val watchedThreshold: Flow<Float>` (по умолчанию 0.9f)
  - `class ShikimoriLibraryRepository @Inject constructor(api, animeDao, userRateDao, watchStateDao, prefs, io: CoroutineDispatcher, clock: Clock) : LibraryRepository`
  - `RepositoryModule` биндит `LibraryRepository`, предоставляет `HomeFeedBuilder` и `@IoDispatcher CoroutineDispatcher`

- [ ] **Step 1: Fake API и failing tests**

`app/src/test/java/app/kaeru/data/library/FakeShikimoriApi.kt`:

```kotlin
package app.kaeru.data.library

import app.kaeru.data.shikimori.AnimeDetailsDto
import app.kaeru.data.shikimori.AnimeShortDto
import app.kaeru.data.shikimori.ImageDto
import app.kaeru.data.shikimori.ScreenshotDto
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.UserDto
import app.kaeru.data.shikimori.UserRateDto
import app.kaeru.data.shikimori.UserRateRequest

class FakeShikimoriApi : ShikimoriApi {
    val rates = mutableMapOf<String, MutableList<UserRateDto>>()
    val animes = mutableMapOf<Int, AnimeShortDto>()
    val details = mutableMapOf<Int, AnimeDetailsDto>()
    val screenshots = mutableMapOf<Int, List<ScreenshotDto>>()
    val calls = mutableListOf<String>()
    val updates = mutableListOf<Pair<Long, UserRateRequest>>()
    val creates = mutableListOf<UserRateRequest>()
    var nextId = 1000L

    fun short(id: Int, status: String = "released", episodes: Int = 12, aired: Int = episodes) =
        AnimeShortDto(id, "Name $id", "Имя $id", ImageDto("/o$id.jpg", "/p$id.jpg"), "7.0", status, episodes, aired, "2026-01-01")

    fun rate(id: Long, animeId: Int, status: String, episodes: Int) =
        UserRateDto(id, animeId, status, episodes, "2026-09-01T00:00:00.000+03:00")

    override suspend fun whoami() = UserDto(42, "vitaliy").also { calls += "whoami" }
    override suspend fun userRates(userId: Long, status: String, page: Int, limit: Int): List<UserRateDto> {
        calls += "rates:$status"
        return rates[status].orEmpty()
    }
    override suspend fun animesByIds(ids: String, limit: Int): List<AnimeShortDto> {
        calls += "animes:$ids"
        return ids.split(",").mapNotNull { animes[it.toInt()] }
    }
    override suspend fun anime(id: Int): AnimeDetailsDto { calls += "anime:$id"; return details.getValue(id) }
    override suspend fun screenshots(id: Int): List<ScreenshotDto> {
        calls += "screenshots:$id"
        return screenshots[id].orEmpty()
    }
    override suspend fun search(query: String, limit: Int): List<AnimeShortDto> { calls += "search:$query"; return animes.values.filter { it.name.contains(query, true) } }
    override suspend fun createUserRate(body: UserRateRequest): UserRateDto {
        creates += body
        val p = body.userRate
        return rate(nextId++, p.targetId!!, p.status ?: "planned", p.episodes ?: 0)
    }
    override suspend fun updateUserRate(id: Long, body: UserRateRequest): UserRateDto {
        updates += id to body
        val p = body.userRate
        return rate(id, p.targetId ?: 0, p.status ?: "watching", p.episodes ?: 0)
    }
}
```

`app/src/test/java/app/kaeru/data/library/ShikimoriLibraryRepositoryTest.kt`:

```kotlin
package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.shikimori.AnimeDetailsDto
import app.kaeru.data.shikimori.ImageDto
import app.kaeru.data.shikimori.ScreenshotDto
import app.kaeru.data.shikimori.StudioDto
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShikimoriLibraryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var db: KaeruDatabase
    private lateinit var prefsStore: DataStore<Preferences>
    private val api = FakeShikimoriApi()
    private lateinit var repo: ShikimoriLibraryRepository
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .setQueryCoroutineContext(dispatcher).build()
        prefsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "prefs.preferences_pb") }
        repo = ShikimoriLibraryRepository(
            api,
            db.animeDao(),
            db.userRateDao(),
            db.watchStateDao(),
            AppPreferences(prefsStore),
            dispatcher,
            Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun seedWatching() {
        api.rates["watching"] = mutableListOf(api.rate(1, 100, "watching", 3), api.rate(2, 200, "watching", 5))
        api.rates["planned"] = mutableListOf(api.rate(3, 300, "planned", 0))
        api.animes[100] = api.short(100, "ongoing", episodes = 0, aired = 4)
        api.animes[200] = api.short(200)
        api.animes[300] = api.short(300)
        api.details[100] = AnimeDetailsDto(
            100, "Name 100", "Имя 100", ImageDto("/o.jpg", "/p.jpg"), "7.0", "ongoing", 0, 4, "2026-01-01",
            description = "desc", nextEpisodeAt = "2026-09-14T17:00:00.000+03:00",
            studios = listOf(StudioDto("MAPPA")), screenshots = emptyList(),
        )
        api.screenshots[100] = listOf(ScreenshotDto("/s.jpg", "/sp.jpg"))
    }

    @Test
    fun `refresh loads rates animes and details for ongoing watching`() = scope.runTest {
        seedWatching()
        assertTrue(repo.refresh().isSuccess)
        repo.observeLibrary().test {
            val lib = awaitItem().sortedBy { it.anime.id }
            assertEquals(listOf(100, 200, 300), lib.map { it.anime.id })
            assertEquals(3, lib[0].rate.episodes)
            assertEquals(ListStatus.PLANNED, lib[2].rate.status)
            // детали подтянуты только для онгоинга 100
            assertEquals("MAPPA", lib[0].anime.studio)
            assertEquals(listOf("https://shikimori.one/s.jpg"), lib[0].anime.screenshotUrls)
            assertEquals(Instant.parse("2026-09-14T14:00:00Z"), lib[0].anime.nextEpisodeAt)
            assertEquals(null, lib[1].anime.studio)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("anime:100"), api.calls.filter { it.startsWith("anime:") })
        assertEquals(listOf("screenshots:100"), api.calls.filter { it.startsWith("screenshots:") })
        assertTrue(api.calls.count { it == "whoami" } == 1)
    }

    @Test
    fun `second refresh does not refetch details fetched recently`() = scope.runTest {
        seedWatching()
        repo.refresh(); repo.refresh()
        assertEquals(1, api.calls.count { it == "anime:100" })
    }

    @Test
    fun `refresh removes rates deleted on server`() = scope.runTest {
        seedWatching()
        repo.refresh()
        api.rates["planned"]!!.clear()
        repo.refresh()
        repo.observeLibrary().test {
            assertEquals(listOf(100, 200), awaitItem().map { it.anime.id }.sorted())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setEpisodes patches existing rate and updates cache immediately`() = scope.runTest {
        seedWatching(); repo.refresh()
        assertTrue(repo.setEpisodes(200, 6).isSuccess)
        assertEquals(2L, api.updates.single().first)
        assertEquals(6, api.updates.single().second.userRate.episodes)
        assertEquals(6, db.userRateDao().getByAnimeId(200)!!.episodes)
    }

    @Test
    fun `setStatus on unknown anime creates a rate and caches the anime`() = scope.runTest {
        api.animes[400] = api.short(400)
        assertTrue(repo.setStatus(400, ListStatus.PLANNED).isSuccess)
        assertEquals(400, api.creates.single().userRate.targetId)
        assertEquals("planned", api.creates.single().userRate.status)
        assertEquals(42L, api.creates.single().userRate.userId)
        assertEquals(ListStatus.PLANNED, db.userRateDao().getByAnimeId(400)!!.status)
        assertEquals("Имя 400", db.animeDao().getById(400)!!.nameRu)
    }

    @Test
    fun `search maps results without touching cache`() = scope.runTest {
        api.animes[100] = api.short(100)
        val res = repo.search("name 1").getOrThrow()
        assertEquals(listOf(100), res.map { it.id })
        assertEquals(null, db.animeDao().getById(100))
    }
}
```

- [ ] **Step 2: Запустить, убедиться, что падает**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.data.library.*' --console=plain`
Expected: `Unresolved reference`.

- [ ] **Step 3: Реализация**

`data/library/AppPreferences.kt`:

```kotlin
package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(@Named("prefs") private val dataStore: DataStore<Preferences>) {
    private val userIdKey = longPreferencesKey("user_id")
    private val lastFullSyncKey = longPreferencesKey("last_full_sync")
    private val watchedThresholdKey = floatPreferencesKey("watched_threshold")

    suspend fun userId(): Long? = dataStore.data.first()[userIdKey]
    suspend fun setUserId(id: Long) { dataStore.edit { it[userIdKey] = id } }

    suspend fun lastFullSync(): Instant? = dataStore.data.first()[lastFullSyncKey]?.let(Instant::ofEpochMilli)
    suspend fun setLastFullSync(at: Instant) { dataStore.edit { it[lastFullSyncKey] = at.toEpochMilli() } }

    val watchedThreshold: Flow<Float> = dataStore.data.map { it[watchedThresholdKey] ?: 0.9f }

    suspend fun clear() { dataStore.edit { it.clear() } }
}
```

`data/library/ShikimoriLibraryRepository.kt`:

```kotlin
package app.kaeru.data.library

import app.kaeru.data.local.AnimeDao
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.WatchStateDao
import app.kaeru.data.local.mergeShort
import app.kaeru.data.local.toEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.UserRatePayload
import app.kaeru.data.shikimori.UserRateRequest
import app.kaeru.data.shikimori.toDomain
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShikimoriLibraryRepository @Inject constructor(
    private val api: ShikimoriApi,
    private val animeDao: AnimeDao,
    private val userRateDao: UserRateDao,
    private val watchStateDao: WatchStateDao,
    private val prefs: AppPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) : LibraryRepository {

    private val refreshLock = Mutex()
    private val detailsTtl: Duration = Duration.ofHours(6)

    override fun observeLibrary(): Flow<List<LibraryEntry>> = combine(
        animeDao.observeAll(), userRateDao.observeAll(), watchStateDao.observeAll(),
    ) { animes, rates, watches ->
        val animeById = animes.associateBy { it.id }
        val watchById = watches.associateBy { it.animeId }
        rates.mapNotNull { r ->
            val a = animeById[r.animeId] ?: return@mapNotNull null
            LibraryEntry(a.toDomain(), r.toDomain(), watchById[r.animeId]?.toDomain())
        }
    }

    override fun observeAnime(id: Int): Flow<LibraryEntry?> = observeLibrary().map { list -> list.firstOrNull { it.anime.id == id } }

    override suspend fun refresh(): Result<Unit> = withContext(io) {
        runCatching {
            refreshLock.withLock {
                val userId = ensureUserId()
                val statuses = listOf(ListStatus.WATCHING, ListStatus.REWATCHING, ListStatus.PLANNED, ListStatus.ON_HOLD, ListStatus.COMPLETED, ListStatus.DROPPED)
                val rates = statuses.flatMap { api.userRates(userId, it.apiValue) }.map { it.toDomain() }
                val ids = rates.map { it.animeId }.distinct()
                val cached = animeDao.getByIds(ids).associateBy { it.id }
                val fresh = ids.chunked(50).flatMap { api.animesByIds(it.joinToString(",")).map { d -> d.toDomain() } }
                animeDao.upsertAll(fresh.map { a -> cached[a.id]?.mergeShort(a) ?: a.toEntity(detailsFetchedAt = null) })
                userRateDao.replaceAll(rates.map { it.toEntity() })

                // Детали (next_episode_at, скриншоты) — только для онгоингов из «Смотрю», и не чаще TTL.
                val watchingIds = rates.filter { it.status == ListStatus.WATCHING || it.status == ListStatus.REWATCHING }.map { it.animeId }.toSet()
                val staleBefore = clock.instant().minus(detailsTtl)
                fresh.filter { it.id in watchingIds && it.status == AnimeStatus.ONGOING }
                    .filter { a -> cached[a.id]?.detailsFetchedAt?.isAfter(staleBefore) != true }
                    .forEach { fetchDetails(it.id) }
                prefs.setLastFullSync(clock.instant())
            }
        }
    }

    override suspend fun refreshAnime(id: Int): Result<Unit> = withContext(io) { runCatching { fetchDetails(id) } }

    private suspend fun fetchDetails(id: Int) {
        val dto = api.anime(id)
        val details = dto.copy(screenshots = api.screenshots(id)).toDomain()
        animeDao.upsertAll(listOf(details.toEntity(detailsFetchedAt = clock.instant())))
    }

    override suspend fun search(query: String): Result<List<Anime>> = withContext(io) {
        runCatching { api.search(query).map { it.toDomain() } }
    }

    override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> = withContext(io) {
        runCatching {
            val existing = userRateDao.getByAnimeId(animeId)
            val dto = if (existing == null) {
                api.createUserRate(UserRateRequest(UserRatePayload(userId = ensureUserId(), targetId = animeId, targetType = "Anime", status = status.apiValue)))
            } else {
                api.updateUserRate(existing.id, UserRateRequest(UserRatePayload(status = status.apiValue)))
            }
            if (animeDao.getById(animeId) == null) {
                api.animesByIds(animeId.toString()).firstOrNull()?.let { animeDao.upsertAll(listOf(it.toDomain().toEntity(null))) }
            }
            userRateDao.upsertAll(listOf(dto.toDomain().copy(status = status, updatedAt = clock.instant()).toEntity()))
        }
    }

    override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> = withContext(io) {
        runCatching {
            val existing = userRateDao.getByAnimeId(animeId) ?: error("no user_rate for anime $animeId")
            val dto = api.updateUserRate(existing.id, UserRateRequest(UserRatePayload(episodes = episodes)))
            userRateDao.upsertAll(listOf(existing.copy(episodes = dto.episodes, updatedAt = clock.instant())))
        }
    }

    private suspend fun ensureUserId(): Long = prefs.userId() ?: api.whoami().id.also { prefs.setUserId(it) }
}
```

`di/RepositoryModule.kt`:

```kotlin
package app.kaeru.di

import app.kaeru.data.library.ShikimoriLibraryRepository
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.repository.LibraryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @Singleton fun homeFeedBuilder(): HomeFeedBuilder = HomeFeedBuilder()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun libraryRepository(impl: ShikimoriLibraryRepository): LibraryRepository
}
```

Аннотация `@IoDispatcher` объявлена в `di`, а используется в `data.library` — добавить `import app.kaeru.di.IoDispatcher` в репозиторий.

- [ ] **Step 4: Прогнать тесты и сборку**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: все PASS, Hilt-граф собирается; Room 2.8.5 принимает `setQueryCoroutineContext(dispatcher)`.

- [ ] **Step 5: Коммит**

```bash
git add app/src
git commit -m "feat(data): library repository syncing shikimori lists into room"
```

---

### Task 8: Общая тема, компоненты и HomeViewModel

**Files:**
- Create: `app/src/main/res/font/manrope.ttf`, `app/src/main/assets/licenses/OFL-Manrope.txt`
- Create: `app/src/main/java/app/kaeru/ui/common/theme/Color.kt`, `Type.kt`, `Theme.kt`
- Create: `app/src/main/java/app/kaeru/ui/common/Poster.kt`, `ProgressStrip.kt`, `Skeleton.kt`
- Create: `app/src/main/java/app/kaeru/ui/common/home/HomeUiState.kt`, `HomeViewModel.kt`
- Create: `app/src/test/java/app/kaeru/test/MainDispatcherRule.kt`
- Test: `app/src/test/java/app/kaeru/ui/common/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `LibraryRepository`, `HomeFeedBuilder`, `HomeFeed` и системный `Clock` из Tasks 3, 5 и 7.
- Produces:
  - `data class HomeUiState(feed, isLoading, isRefreshing, errorMessage)`;
  - `@HiltViewModel class HomeViewModel` с `val uiState: StateFlow<HomeUiState>` и идемпотентным `fun refresh()`;
  - `KaeruTheme`, `KaeruTvTheme`, `Poster`, `ProgressStrip`, `Skeleton` — общие строительные блоки без навигации и доступа к data-слою.

- [ ] **Step 1: Добавить Manrope и лицензию**

```bash
mkdir -p app/src/main/res/font app/src/main/assets/licenses
curl -L 'https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf' -o app/src/main/res/font/manrope.ttf
curl -L 'https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/OFL.txt' -o app/src/main/assets/licenses/OFL-Manrope.txt
test -s app/src/main/res/font/manrope.ttf
grep -q 'SIL OPEN FONT LICENSE' app/src/main/assets/licenses/OFL-Manrope.txt
```

Expected: обе команды проверки завершаются с кодом 0. Бинарный шрифт и его
лицензия коммитятся вместе.

- [ ] **Step 2: Написать failing tests HomeViewModel**

`app/src/test/java/app/kaeru/test/MainDispatcherRule.kt`:

```kotlin
package app.kaeru.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

`app/src/test/java/app/kaeru/ui/common/home/HomeViewModelTest.kt`:

```kotlin
package app.kaeru.ui.common.home

import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    private class FakeLibraryRepository : LibraryRepository {
        val entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
        var refreshResult: Result<Unit> = Result.success(Unit)
        var refreshCalls = 0
        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries
        override fun observeAnime(id: Int): Flow<LibraryEntry?> =
            MutableStateFlow(entries.value.firstOrNull { it.anime.id == id })
        override suspend fun refresh(): Result<Unit> { refreshCalls++; return refreshResult }
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus) = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    private fun entry(id: Int = 7) = LibraryEntry(
        anime = Anime(id, "Фрирен", "Sousou no Frieren", null, emptyList(), AnimeStatus.ONGOING, 28, 24, null, 9.1, 2023, "Madhouse", null),
        rate = UserRate(11, id, ListStatus.WATCHING, 20, now),
        watch = null,
    )

    @Test
    fun `cached room content is exposed before refresh completes`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.entries.value = listOf(entry()) }
        val vm = HomeViewModel(repo, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(7, vm.uiState.value.feed.top?.entry?.anime?.id)
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `failed refresh keeps cached feed and exposes retryable error`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also {
            it.entries.value = listOf(entry())
            it.refreshResult = Result.failure(IllegalStateException("offline"))
        }
        val vm = HomeViewModel(repo, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC))
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.feed.top?.entry?.anime?.id)
        assertEquals("offline", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `manual refresh clears previous error`() = runTest(main.dispatcher) {
        val repo = FakeLibraryRepository().also { it.refreshResult = Result.failure(Exception("first")) }
        val vm = HomeViewModel(repo, HomeFeedBuilder(), Clock.fixed(now, ZoneOffset.UTC))
        advanceUntilIdle()
        assertEquals("first", vm.uiState.value.errorMessage)
        repo.refreshResult = Result.success(Unit)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage == null)
        assertEquals(2, repo.refreshCalls)
    }
}
```

- [ ] **Step 3: Запустить, убедиться, что тесты падают**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.ui.common.home.HomeViewModelTest' --console=plain`
Expected: FAIL с `Unresolved reference: HomeViewModel`.

- [ ] **Step 4: Реализовать состояние главной**

`ui/common/home/HomeUiState.kt`:

```kotlin
package app.kaeru.ui.common.home

import app.kaeru.domain.model.HomeFeed

data class HomeUiState(
    val feed: HomeFeed = HomeFeed.EMPTY,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)
```

`ui/common/home/HomeViewModel.kt`:

```kotlin
package app.kaeru.ui.common.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

private data class RefreshState(val active: Boolean = false, val error: String? = null)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val feedBuilder: HomeFeedBuilder,
    private val clock: Clock,
) : ViewModel() {
    private val refreshState = MutableStateFlow(RefreshState())
    private var refreshJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeLibrary(),
        refreshState,
    ) { entries, refresh ->
        HomeUiState(
            feed = feedBuilder.build(entries, clock.instant()),
            isLoading = false,
            isRefreshing = refresh.active,
            errorMessage = refresh.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(feed = HomeFeed.EMPTY),
    )

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshState.value = RefreshState(active = true)
            val result = repository.refresh()
            refreshState.value = RefreshState(
                active = false,
                error = result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.let { "Не удалось обновить данные" },
            )
        }
    }
}
```

- [ ] **Step 5: Реализовать обе темы**

`ui/common/theme/Color.kt`:

```kotlin
package app.kaeru.ui.common.theme

import androidx.compose.ui.graphics.Color

val KaeruBackground = Color(0xFF0B0C10)
val KaeruSurface = Color(0xFF15171E)
val KaeruElevated = Color(0xFF1E212B)
val KaeruText = Color(0xFFF2F3F5)
val KaeruSecondary = Color(0xFF9AA0AA)
val KaeruAccent = Color(0xFFF5A524)
```

`ui/common/theme/Type.kt`:

```kotlin
package app.kaeru.ui.common.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kaeru.R

val Manrope = FontFamily(Font(R.font.manrope, weight = FontWeight.Normal))

val KaeruTypography = Typography(
    displaySmall = TextStyle(Manrope, FontWeight.Bold, 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(Manrope, FontWeight.Bold, 26.sp, letterSpacing = (-0.25).sp),
    titleLarge = TextStyle(Manrope, FontWeight.SemiBold, 20.sp),
    titleMedium = TextStyle(Manrope, FontWeight.SemiBold, 16.sp),
    bodyLarge = TextStyle(Manrope, FontWeight.Normal, 16.sp),
    bodyMedium = TextStyle(Manrope, FontWeight.Normal, 14.sp),
    labelLarge = TextStyle(Manrope, FontWeight.Bold, 14.sp),
)
```

`ui/common/theme/Theme.kt`:

```kotlin
package app.kaeru.ui.common.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

private val MobileColors = darkColorScheme(
    primary = KaeruAccent, onPrimary = Color.Black,
    background = KaeruBackground, onBackground = KaeruText,
    surface = KaeruSurface, onSurface = KaeruText,
    surfaceVariant = KaeruElevated, onSurfaceVariant = KaeruSecondary,
)

private val TvColors = tvDarkColorScheme(
    primary = KaeruAccent, onPrimary = Color.Black,
    background = KaeruBackground, onBackground = KaeruText,
    surface = KaeruSurface, onSurface = KaeruText,
)

@Composable
fun KaeruTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MobileColors,
        typography = KaeruTypography,
        shapes = MaterialTheme.shapes.copy(medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp)),
        content = content,
    )
}

@Composable
fun KaeruTvTheme(content: @Composable () -> Unit) {
    KaeruTheme { TvMaterialTheme(colorScheme = TvColors, content = content) }
}
```

- [ ] **Step 6: Реализовать общие визуальные компоненты**

`ui/common/Poster.kt`:

```kotlin
package app.kaeru.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Text(title.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
        } else {
            AsyncImage(
                model = url,
                contentDescription = "Постер: $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

`ui/common/ProgressStrip.kt`:

```kotlin
package app.kaeru.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruAccent

@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(KaeruAccent))
    }
}
```

`ui/common/Skeleton.kt`:

```kotlin
package app.kaeru.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = alpha)))
}
```

- [ ] **Step 7: Прогнать тесты и сборку**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: все тесты PASS, обе Material-темы и Coil собираются в одном APK.

- [ ] **Step 8: Коммит**

```bash
git add app/src
git commit -m "feat(ui): shared theme, media components and home state"
```

---

### Task 9: Телефон — OAuth, главная из кэша и экран тайтла

**Files:**
- Create: `app/src/main/java/app/kaeru/ui/mobile/Routes.kt`, `MobileApp.kt`
- Create: `app/src/main/java/app/kaeru/ui/common/auth/AuthViewModel.kt`
- Create: `app/src/main/java/app/kaeru/ui/mobile/auth/LoginScreen.kt`
- Create: `app/src/main/java/app/kaeru/ui/mobile/home/HomeScreen.kt`
- Create: `app/src/main/java/app/kaeru/ui/mobile/details/DetailsViewModel.kt`, `DetailsScreen.kt`
- Modify: `app/src/main/java/app/kaeru/MainActivity.kt`
- Test: `app/src/test/java/app/kaeru/ui/common/auth/AuthViewModelTest.kt`
- Test: `app/src/test/java/app/kaeru/ui/mobile/details/DetailsViewModelTest.kt`

**Interfaces:**
- Consumes: `AuthRepository`, `LibraryRepository`, `HomeViewModel` и общие composable из Tasks 3, 5, 7 и 8.
- Produces:
  - `AuthUiState(loggedIn: Boolean?, exchanging: Boolean, errorMessage: String?)`;
  - `AuthViewModel.mobileAuthorizeUrl`, `tvAuthorizeUrl`, `exchangeMobileCode`, `exchangeTvCode`, `logout` — тот же ViewModel использует TV в Task 11;
  - route `home` и `details/{animeId}`;
  - mobile OAuth через Custom Tabs и обработку deep link `kaeru://oauth?code=...`;
  - главную, которая сначала показывает Room, затем индикатор фонового refresh, и экран тайтла с изменением статуса списка.

- [ ] **Step 1: Failing tests для AuthViewModel**

`app/src/test/java/app/kaeru/ui/common/auth/AuthViewModelTest.kt`:

```kotlin
package app.kaeru.ui.common.auth

import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeAuthRepository : AuthRepository {
        val loggedIn = MutableStateFlow(false)
        var exchangeResult: Result<Unit> = Result.success(Unit)
        var exchange: Pair<String, String>? = null
        override val isLoggedIn: Flow<Boolean> = loggedIn
        override fun authorizeUrl(redirectUri: String) = "https://auth.test/?redirect=$redirectUri"
        override suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit> {
            exchange = code to redirectUri
            exchangeResult.onSuccess { loggedIn.value = true }
            return exchangeResult
        }
        override suspend fun logout() { loggedIn.value = false }
    }

    @Test
    fun `mobile code is exchanged with mobile redirect and consumed once`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo)
        var consumed = 0
        vm.exchangeMobileCode("abc") { consumed++ }
        advanceUntilIdle()
        assertEquals("abc" to MOBILE_REDIRECT, repo.exchange)
        assertEquals(1, consumed)
        assertTrue(vm.uiState.value.loggedIn == true)
        assertFalse(vm.uiState.value.exchanging)
    }

    @Test
    fun `exchange error remains visible without logging in`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository().also { it.exchangeResult = Result.failure(Exception("bad code")) }
        val vm = AuthViewModel(repo)
        vm.exchangeMobileCode("bad") {}
        advanceUntilIdle()
        assertEquals("bad code", vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.loggedIn == false)
    }
}
```

- [ ] **Step 2: Failing test для DetailsViewModel**

`app/src/test/java/app/kaeru/ui/mobile/details/DetailsViewModelTest.kt`:

```kotlin
package app.kaeru.ui.mobile.details

import androidx.lifecycle.SavedStateHandle
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class DetailsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val item = LibraryEntry(
        Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.ONGOING, 28, 24, null, 9.1, 2023, "Madhouse", "Описание"),
        UserRate(1, 7, ListStatus.WATCHING, 20, Instant.EPOCH),
        null,
    )

    private class FakeRepository(initial: LibraryEntry) : LibraryRepository {
        val entry = MutableStateFlow<LibraryEntry?>(initial)
        var refreshed: Int? = null
        var statusChange: Pair<Int, ListStatus>? = null
        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(listOfNotNull(entry.value))
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entry
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int): Result<Unit> { refreshed = id; return Result.success(Unit) }
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            statusChange = animeId to status
            entry.value = entry.value?.copy(rate = entry.value!!.rate.copy(status = status))
            return Result.success(Unit)
        }
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    @Test
    fun `loads requested anime and changes list status`() = runTest(main.dispatcher) {
        val repo = FakeRepository(item)
        val vm = DetailsViewModel(SavedStateHandle(mapOf("animeId" to 7)), repo)
        advanceUntilIdle()
        assertEquals(7, repo.refreshed)
        assertEquals("Фрирен", vm.uiState.value.entry?.anime?.title)
        vm.setStatus(ListStatus.PLANNED)
        advanceUntilIdle()
        assertEquals(7 to ListStatus.PLANNED, repo.statusChange)
        assertFalse(vm.uiState.value.updatingStatus)
    }
}
```

- [ ] **Step 3: Запустить, убедиться, что тесты падают**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.ui.common.auth.*' --tests 'app.kaeru.ui.mobile.details.*' --console=plain`
Expected: FAIL с `Unresolved reference: AuthViewModel` и `DetailsViewModel`.

- [ ] **Step 4: Реализовать AuthViewModel и экран входа**

`ui/common/auth/AuthViewModel.kt`:

```kotlin
package app.kaeru.ui.common.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val loggedIn: Boolean? = null,
    val exchanging: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val exchanging = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    val mobileAuthorizeUrl: String = repository.authorizeUrl(MOBILE_REDIRECT)
    val tvAuthorizeUrl: String = repository.authorizeUrl(OOB_REDIRECT)

    val uiState: StateFlow<AuthUiState> = combine(
        repository.isLoggedIn.map<Boolean, Boolean?> { it },
        exchanging,
        error,
    ) { loggedIn, busy, message -> AuthUiState(loggedIn, busy, message) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthUiState())

    fun exchangeMobileCode(code: String, consumed: () -> Unit) = exchange(code, MOBILE_REDIRECT, consumed)
    fun exchangeTvCode(code: String) = exchange(code.trim(), OOB_REDIRECT) {}

    private fun exchange(code: String, redirect: String, consumed: () -> Unit) {
        if (code.isBlank() || exchanging.value) return
        consumed()
        viewModelScope.launch {
            exchanging.value = true
            error.value = null
            val result = repository.exchangeCode(code, redirect)
            error.value = result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.let { "Не удалось войти" }
            exchanging.value = false
        }
    }

    fun logout() { viewModelScope.launch { repository.logout() } }
}
```

`ui/mobile/auth/LoginScreen.kt`:

```kotlin
package app.kaeru.ui.mobile.auth

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.auth.AuthUiState

@Composable
fun LoginScreen(authorizeUrl: String, state: AuthUiState) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kaeru", style = MaterialTheme.typography.displaySmall)
        Text(
            "Войдите через Shikimori, чтобы синхронизировать список и просмотренные серии.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        Button(
            onClick = { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authorizeUrl)) },
            enabled = !state.exchanging,
        ) { Text(if (state.exchanging) "Проверяем код…" else "Войти через Shikimori") }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
    }
}
```

- [ ] **Step 5: Реализовать DetailsViewModel**

`ui/mobile/details/DetailsViewModel.kt`:

```kotlin
package app.kaeru.ui.mobile.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailsUiState(
    val entry: LibraryEntry? = null,
    val refreshing: Boolean = true,
    val updatingStatus: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
) : ViewModel() {
    private val animeId: Int = checkNotNull(savedStateHandle["animeId"])
    private val work = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = combine(repository.observeAnime(animeId), work) { entry, state ->
        state.copy(entry = entry)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailsUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            work.value = work.value.copy(refreshing = true, errorMessage = null)
            val result = repository.refreshAnime(animeId)
            work.value = work.value.copy(refreshing = false, errorMessage = result.exceptionOrNull()?.message)
        }
    }

    fun setStatus(status: ListStatus) {
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null)
            val result = repository.setStatus(animeId, status)
            work.value = work.value.copy(updatingStatus = false, errorMessage = result.exceptionOrNull()?.message)
        }
    }
}
```

- [ ] **Step 6: Реализовать мобильную главную**

`ui/mobile/home/HomeScreen.kt`:

```kotlin
package app.kaeru.ui.mobile.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.FeedItem
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.ProgressStrip
import app.kaeru.ui.common.Skeleton
import app.kaeru.ui.common.home.HomeUiState
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, onRefresh: () -> Unit, onAnime: (Int) -> Unit) {
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
        when {
            state.isLoading -> HomeSkeleton()
            state.feed.isEmpty -> EmptyHome(onRefresh)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.feed.top?.let { top -> item(key = "hero") { Hero(top, onAnime) } }
                state.errorMessage?.let { message ->
                    item(key = "error") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            Button(onClick = onRefresh) { Text("Повторить") }
                        }
                    }
                }
                if (state.feed.newEpisodes.isNotEmpty()) item { FeedRow("Новые серии", state.feed.newEpisodes, onAnime) }
                if (state.feed.continueWatching.isNotEmpty()) item { FeedRow("Продолжить", state.feed.continueWatching, onAnime) }
                if (state.feed.nextUp.isNotEmpty()) item { FeedRow("Следующая серия", state.feed.nextUp, onAnime) }
                if (state.feed.upcoming.isNotEmpty()) item { FeedRow("Скоро", state.feed.upcoming, onAnime) }
                if (state.feed.planned.isNotEmpty()) item { FeedRow("В планах", state.feed.planned, onAnime) }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun Hero(item: FeedItem, onAnime: (Int) -> Unit) {
    val anime = item.entry.anime
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(
            model = anime.screenshotUrls.firstOrNull() ?: anime.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(anime.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
            Text("${item.episode} серия", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = { onAnime(anime.id) }) { Text("Подробнее") }
        }
    }
}

@Composable
private fun FeedRow(title: String, feed: List<FeedItem>, onAnime: (Int) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(feed, key = { "${it.kind}-${it.entry.anime.id}" }) { item ->
                Column(Modifier.size(width = 132.dp, height = 230.dp).clickable { onAnime(item.entry.anime.id) }) {
                    Poster(item.entry.anime.posterUrl, item.entry.anime.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    item.entry.progressFraction(0.9f)?.let { ProgressStrip(it) }
                    Text(item.entry.anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable private fun HomeSkeleton() = Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Skeleton(Modifier.fillMaxWidth().height(300.dp)); Skeleton(Modifier.fillMaxWidth().height(24.dp)); Skeleton(Modifier.fillMaxWidth().height(200.dp))
}

@Composable private fun EmptyHome(onRefresh: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally,
) {
    Text("В списке «Смотрю» пока пусто", style = MaterialTheme.typography.titleLarge)
    Text("Добавьте аниме на Shikimori и обновите список.", modifier = Modifier.padding(12.dp))
    Button(onClick = onRefresh) { Text("Обновить") }
}
```

- [ ] **Step 7: Реализовать экран тайтла**

`ui/mobile/details/DetailsScreen.kt`:

```kotlin
package app.kaeru.ui.mobile.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.Skeleton

@Composable
fun DetailsScreen(state: DetailsUiState, onBack: () -> Unit, onStatus: (ListStatus) -> Unit) {
    val entry = state.entry
    if (entry == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { Skeleton(Modifier.fillMaxWidth().height(300.dp)) }
        return
    }
    val anime = entry.anime
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Button(onClick = onBack) { Text("Назад") }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Poster(anime.posterUrl, anime.title, Modifier.width(132.dp).height(198.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    anime.year?.let { Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("${anime.availableEpisodes} серий", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    anime.score?.let { Text("★ $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                anime.studio?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        Text("Список", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ListStatus.WATCHING to "Смотрю", ListStatus.PLANNED to "В планах", ListStatus.COMPLETED to "Завершено").forEach { (status, label) ->
                FilterChip(selected = entry.rate.status == status, onClick = { onStatus(status) }, enabled = !state.updatingStatus, label = { Text(label) })
            }
        }
        Text("Просмотрено ${entry.rate.episodes} из ${anime.availableEpisodes}", modifier = Modifier.padding(top = 16.dp))
        anime.description?.takeIf { it.isNotBlank() }?.let {
            Text("Описание", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            Text(it)
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
    }
}
```

- [ ] **Step 8: Собрать навигацию и deep link**

`ui/mobile/Routes.kt`:

```kotlin
package app.kaeru.ui.mobile

object Routes {
    const val HOME = "home"
    const val DETAILS = "details/{animeId}"
    fun details(animeId: Int) = "details/$animeId"
}
```

`ui/mobile/MobileApp.kt`:

```kotlin
package app.kaeru.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.mobile.auth.LoginScreen
import app.kaeru.ui.mobile.details.DetailsScreen
import app.kaeru.ui.mobile.details.DetailsViewModel
import app.kaeru.ui.mobile.home.HomeScreen

@Composable
fun MobileApp(
    pendingAuthCode: String?,
    onAuthCodeConsumed: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(pendingAuthCode) {
        pendingAuthCode?.let { authViewModel.exchangeMobileCode(it, onAuthCodeConsumed) }
    }
    KaeruTheme {
        when (auth.loggedIn) {
            null -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize())
            false -> LoginScreen(authViewModel.mobileAuthorizeUrl, auth)
            true -> {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        val vm: HomeViewModel = hiltViewModel()
                        val state = vm.uiState.collectAsStateWithLifecycle().value
                        HomeScreen(state, vm::refresh) { nav.navigate(Routes.details(it)) }
                    }
                    composable(
                        Routes.DETAILS,
                        arguments = listOf(navArgument("animeId") { type = NavType.IntType }),
                    ) { backStackEntry ->
                        val vm: DetailsViewModel = hiltViewModel(backStackEntry)
                        DetailsScreen(
                            state = vm.uiState.collectAsStateWithLifecycle().value,
                            onBack = { nav.popBackStack() },
                            onStatus = vm::setStatus,
                        )
                    }
                }
            }
        }
    }
}
```

Заменить `MainActivity.kt`:

```kotlin
package app.kaeru

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.kaeru.ui.mobile.MobileApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingAuthCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readAuthCode(intent)
        setContent {
            MobileApp(pendingAuthCode = pendingAuthCode, onAuthCodeConsumed = { pendingAuthCode = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAuthCode(intent)
    }

    private fun readAuthCode(intent: Intent?) {
        if (intent?.data?.scheme == "kaeru" && intent.data?.host == "oauth") {
            pendingAuthCode = intent.data?.getQueryParameter("code")
        }
    }
}
```

- [ ] **Step 9: Прогнать тесты и сборку**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: все тесты PASS; `MainActivity` собирается с Hilt-навигацией и deep link.

- [ ] **Step 10: Ручная проверка OAuth на телефоне**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop app.kaeru
adb shell am start -W -n app.kaeru/.MainActivity
```

Expected: кнопка входа открывает Custom Tab; после разрешения Shikimori
возвращает в Kaeru; кэшированная главная становится интерактивной до окончания
сетевого refresh; тап по hero или постеру открывает экран тайтла. Если OAuth-
приложение ещё не зарегистрировано, проверить deep link отдельно:
`adb shell am start -a android.intent.action.VIEW -d 'kaeru://oauth?code=test' app.kaeru`
и убедиться, что UI показывает ошибку обмена, а не падает.

- [ ] **Step 11: Коммит**

```bash
git add app/src
git commit -m "feat(mobile): shikimori login, cached home and anime details"
```

---

### Task 10: Телефон — «Мой список», поиск и нижняя навигация

**Files:**
- Create: `app/src/main/java/app/kaeru/ui/mobile/library/LibraryViewModel.kt`, `LibraryScreen.kt`
- Create: `app/src/main/java/app/kaeru/ui/mobile/search/SearchViewModel.kt`, `SearchScreen.kt`
- Create: `app/src/main/java/app/kaeru/ui/mobile/MobileShell.kt`
- Modify: `app/src/main/java/app/kaeru/ui/mobile/Routes.kt`, `MobileApp.kt`
- Test: `app/src/test/java/app/kaeru/ui/mobile/library/LibraryViewModelTest.kt`
- Test: `app/src/test/java/app/kaeru/ui/mobile/search/SearchViewModelTest.kt`

**Interfaces:**
- Consumes: `LibraryRepository.observeLibrary()`, `search()` и `setStatus()`.
- Produces:
  - `LibraryUiState(items, status, sort)` с чистой функцией `selectLibrary(...)`;
  - `SearchUiState(query, results, recentQueries, searching, errorMessage)`;
  - нижнюю навигацию `home | library | search`, сохраняющую состояние каждой вкладки;
  - тап по карточке библиотеки ведёт в `details/{animeId}`; результат поиска
    сначала добавляется «В планы» через `setStatus(PLANNED)`, после чего
    появляется в Room и открывается из библиотеки.

- [ ] **Step 1: Failing test фильтрации и сортировки библиотеки**

`app/src/test/java/app/kaeru/ui/mobile/library/LibraryViewModelTest.kt`:

```kotlin
package app.kaeru.ui.mobile.library

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LibraryViewModelTest {
    private fun item(id: Int, title: String, status: ListStatus, updated: String) = LibraryEntry(
        Anime(id, title, title, null, emptyList(), AnimeStatus.RELEASED, 12, 12, null, null, 2026, null, null),
        UserRate(id.toLong(), id, status, id, Instant.parse(updated)),
        null,
    )

    @Test
    fun `selected status is filtered then sorted by recent activity`() {
        val items = listOf(
            item(1, "Б", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(2, "А", ListStatus.WATCHING, "2026-09-10T00:00:00Z"),
            item(3, "В", ListStatus.PLANNED, "2026-09-11T00:00:00Z"),
        )
        assertEquals(listOf(2, 1), selectLibrary(items, ListStatus.WATCHING, LibrarySort.UPDATED).map { it.anime.id })
        assertEquals(listOf(1, 2), selectLibrary(items, ListStatus.WATCHING, LibrarySort.TITLE).map { it.anime.id })
    }
}
```

- [ ] **Step 2: Failing test поиска и добавления в планы**

`app/src/test/java/app/kaeru/ui/mobile/search/SearchViewModelTest.kt`:

```kotlin
package app.kaeru.ui.mobile.search

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val result = Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.RELEASED, 28, 28, null, 9.1, 2023, null, null)

    private class FakeRepository(private val result: Anime) : LibraryRepository {
        var query: String? = null
        var status: Pair<Int, ListStatus>? = null
        override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(emptyList())
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = MutableStateFlow(null)
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String): Result<List<Anime>> { this.query = query; return Result.success(listOf(result)) }
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> { this.status = animeId to status; return Result.success(Unit) }
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
    }

    @Test
    fun `submit trims query remembers it and add planned delegates to repository`() = runTest(main.dispatcher) {
        val repo = FakeRepository(result)
        val vm = SearchViewModel(repo)
        vm.setQuery("  Фрирен  ")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Фрирен", repo.query)
        assertEquals(listOf(7), vm.uiState.value.results.map { it.id })
        assertEquals(listOf("Фрирен"), vm.uiState.value.recentQueries)
        vm.addToPlanned(7)
        advanceUntilIdle()
        assertEquals(7 to ListStatus.PLANNED, repo.status)
        assertFalse(vm.uiState.value.searching)
    }
}
```

- [ ] **Step 3: Запустить, убедиться, что тесты падают**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.ui.mobile.library.*' --tests 'app.kaeru.ui.mobile.search.*' --console=plain`
Expected: FAIL с `Unresolved reference`.

- [ ] **Step 4: Реализовать LibraryViewModel**

`ui/mobile/library/LibraryViewModel.kt`:

```kotlin
package app.kaeru.ui.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class LibrarySort { UPDATED, TITLE }

data class LibraryUiState(
    val items: List<LibraryEntry> = emptyList(),
    val status: ListStatus = ListStatus.WATCHING,
    val sort: LibrarySort = LibrarySort.UPDATED,
)

fun selectLibrary(items: List<LibraryEntry>, status: ListStatus, sort: LibrarySort): List<LibraryEntry> {
    val filtered = items.filter { it.rate.status == status }
    return when (sort) {
        LibrarySort.UPDATED -> filtered.sortedByDescending { it.rate.updatedAt }
        LibrarySort.TITLE -> filtered.sortedBy { it.anime.title.lowercase() }
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(repository: LibraryRepository) : ViewModel() {
    private val status = MutableStateFlow(ListStatus.WATCHING)
    private val sort = MutableStateFlow(LibrarySort.UPDATED)
    val uiState: StateFlow<LibraryUiState> = combine(repository.observeLibrary(), status, sort) { items, selected, order ->
        LibraryUiState(selectLibrary(items, selected, order), selected, order)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectStatus(value: ListStatus) { status.value = value }
    fun selectSort(value: LibrarySort) { sort.value = value }
}
```

- [ ] **Step 5: Реализовать SearchViewModel**

`ui/mobile/search/SearchViewModel.kt`:

```kotlin
package app.kaeru.ui.mobile.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Anime> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val searching: Boolean = false,
    val errorMessage: String? = null,
    val addingAnimeId: Int? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = mutable

    fun setQuery(value: String) = mutable.update { it.copy(query = value) }
    fun useRecent(value: String) { setQuery(value); submit() }

    fun submit() {
        val query = mutable.value.query.trim()
        if (query.length < 2 || mutable.value.searching) return
        viewModelScope.launch {
            mutable.update { it.copy(query = query, searching = true, errorMessage = null) }
            val result = repository.search(query)
            mutable.update { state ->
                state.copy(
                    results = result.getOrDefault(emptyList()),
                    recentQueries = (listOf(query) + state.recentQueries.filterNot { it == query }).take(5),
                    searching = false,
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun addToPlanned(animeId: Int) {
        viewModelScope.launch {
            mutable.update { it.copy(addingAnimeId = animeId, errorMessage = null) }
            val result = repository.setStatus(animeId, ListStatus.PLANNED)
            mutable.update { it.copy(addingAnimeId = null, errorMessage = result.exceptionOrNull()?.message) }
        }
    }
}
```

- [ ] **Step 6: Реализовать экраны библиотеки и поиска**

`ui/mobile/library/LibraryScreen.kt`:

```kotlin
package app.kaeru.ui.mobile.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.ListStatus
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.ProgressStrip

@Composable
fun LibraryScreen(state: LibraryUiState, onStatus: (ListStatus) -> Unit, onSort: (LibrarySort) -> Unit, onAnime: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Мой список", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems(listOf(
                ListStatus.WATCHING to "Смотрю",
                ListStatus.PLANNED to "В планах",
                ListStatus.COMPLETED to "Готово",
                ListStatus.ON_HOLD to "Отложено",
                ListStatus.DROPPED to "Брошено",
                ListStatus.REWATCHING to "Пересматриваю",
            )) { (status, label) ->
                FilterChip(selected = state.status == status, onClick = { onStatus(status) }, label = { Text(label) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.sort == LibrarySort.UPDATED, onClick = { onSort(LibrarySort.UPDATED) }, label = { Text("Недавние") })
            FilterChip(selected = state.sort == LibrarySort.TITLE, onClick = { onSort(LibrarySort.TITLE) }, label = { Text("По названию") })
        }
        if (state.items.isEmpty()) {
            Text("В этом разделе пока пусто", modifier = Modifier.padding(24.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.items, key = { it.anime.id }) { entry ->
                    Column(Modifier.clickable { onAnime(entry.anime.id) }) {
                        Poster(entry.anime.posterUrl, entry.anime.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        entry.progressFraction(0.9f)?.let { ProgressStrip(it) }
                        Text(entry.anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${entry.rate.episodes}/${entry.anime.availableEpisodes}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
```

`ui/mobile/search/SearchScreen.kt`:

```kotlin
package app.kaeru.ui.mobile.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.Poster

@Composable
fun SearchScreen(state: SearchUiState, onQuery: (String) -> Unit, onSubmit: () -> Unit, onRecent: (String) -> Unit, onPlanned: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        Text("Поиск", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text("Название аниме") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSubmit() }),
            trailingIcon = { Button(onClick = onSubmit, enabled = !state.searching) { Text("Найти") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (state.recentQueries.isNotEmpty()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recentQueries.forEach { query -> InputChip(selected = false, onClick = { onRecent(query) }, label = { Text(query) }) }
            }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.results, key = { it.id }) { anime ->
                Column {
                    Poster(anime.posterUrl, anime.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Text(anime.title, maxLines = 2)
                    Button(onClick = { onPlanned(anime.id) }, enabled = state.addingAnimeId != anime.id) { Text("В планы") }
                }
            }
        }
    }
}
```

- [ ] **Step 7: Добавить shell с нижней навигацией**

Заменить `Routes.kt`:

```kotlin
package app.kaeru.ui.mobile

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val DETAILS = "details/{animeId}"
    fun details(animeId: Int) = "details/$animeId"
}
```

`ui/mobile/MobileShell.kt`:

```kotlin
package app.kaeru.ui.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.padding
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.mobile.details.DetailsScreen
import app.kaeru.ui.mobile.details.DetailsViewModel
import app.kaeru.ui.mobile.home.HomeScreen
import app.kaeru.ui.mobile.library.LibraryScreen
import app.kaeru.ui.mobile.library.LibraryViewModel
import app.kaeru.ui.mobile.search.SearchScreen
import app.kaeru.ui.mobile.search.SearchViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(Tab(Routes.HOME, "Главная", Icons.Default.Home), Tab(Routes.LIBRARY, "Мой список", Icons.Default.VideoLibrary), Tab(Routes.SEARCH, "Поиск", Icons.Default.Search))

@Composable
fun MobileShell(nav: NavHostController = rememberNavController()) {
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(bottomBar = {
        if (route in tabs.map { it.route }) NavigationBar {
            tabs.forEach { tab -> NavigationBarItem(
                selected = route == tab.route,
                onClick = { nav.navigate(tab.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            ) }
        }
    }) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { val vm: HomeViewModel = hiltViewModel(); HomeScreen(vm.uiState.collectAsStateWithLifecycle().value, vm::refresh) { nav.navigate(Routes.details(it)) } }
            composable(Routes.LIBRARY) { val vm: LibraryViewModel = hiltViewModel(); LibraryScreen(vm.uiState.collectAsStateWithLifecycle().value, vm::selectStatus, vm::selectSort) { nav.navigate(Routes.details(it)) } }
            composable(Routes.SEARCH) { val vm: SearchViewModel = hiltViewModel(); SearchScreen(vm.uiState.collectAsStateWithLifecycle().value, vm::setQuery, vm::submit, vm::useRecent, vm::addToPlanned) }
            composable(Routes.DETAILS, arguments = listOf(navArgument("animeId") { type = NavType.IntType })) { entry ->
                val vm: DetailsViewModel = hiltViewModel(entry)
                DetailsScreen(vm.uiState.collectAsStateWithLifecycle().value, { nav.popBackStack() }, vm::setStatus)
            }
        }
    }
}
```

В `MobileApp.kt` удалить прежний встроенный `NavHost` из ветки успешной
авторизации и заменить всю ветку одной строкой:

```kotlin
true -> MobileShell()
```

Удалить ставшие ненужными navigation/Home/Details imports из `MobileApp.kt`.

- [ ] **Step 8: Прогнать тесты и сборку**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: все тесты PASS; три вкладки сохраняют scroll/state при переключении.

- [ ] **Step 9: Ручной smoke test**

Открыть каждую вкладку. Проверить: фильтры библиотеки; сортировку; поиск по
`Фрирен`; добавление результата в «В планах»; возврат из Details; тап-цели не
меньше 48dp; системный Back не создаёт второй экземпляр Home.

- [ ] **Step 10: Коммит**

```bash
git add app/src
git commit -m "feat(mobile): library, search and bottom navigation"
```

---

### Task 11: Android TV — QR-вход и immersive-главная с D-pad

**Files:**
- Create: `app/src/main/java/app/kaeru/ui/tv/TvApp.kt`
- Create: `app/src/main/java/app/kaeru/ui/tv/auth/TvLoginScreen.kt`
- Create: `app/src/main/java/app/kaeru/ui/tv/home/TvHomeRows.kt`, `TvHomeScreen.kt`
- Modify: `app/src/main/java/app/kaeru/TvActivity.kt`
- Test: `app/src/test/java/app/kaeru/ui/tv/home/TvHomeRowsTest.kt`

**Interfaces:**
- Consumes: общий `AuthViewModel`, `HomeViewModel`, `HomeFeed`, `Poster` и `KaeruTvTheme`.
- Produces:
  - TV OAuth OOB: QR содержит `AuthViewModel.tvAuthorizeUrl`, код вводится с пульта и передаётся в `exchangeTvCode`;
  - `fun tvHomeRows(feed: HomeFeed): List<TvHomeRow>` — первый ряд объединяет «Продолжить» и «Новые серии» без дублей;
  - immersive-главную: hero следует за фокусом, стартовый фокус — top item
    либо первая карточка первого непустого ряда, D-pad всегда имеет видимую рамку/масштаб;
  - короткую карточку тайтла по OK. В плане 2 этот callback заменяется запуском серии.

- [ ] **Step 1: Failing test структуры TV-рядов**

`app/src/test/java/app/kaeru/ui/tv/home/TvHomeRowsTest.kt`:

```kotlin
package app.kaeru.ui.tv.home

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TvHomeRowsTest {
    private fun item(id: Int, kind: FeedKind) = FeedItem(
        LibraryEntry(
            Anime(id, "Аниме $id", "Anime $id", null, emptyList(), AnimeStatus.ONGOING, 12, 8, null, null, 2026, null, null),
            UserRate(id.toLong(), id, ListStatus.WATCHING, 4, Instant.EPOCH),
            null,
        ),
        episode = 5,
        kind = kind,
    )

    @Test
    fun `first row merges continue and new episodes without duplicates`() {
        val one = item(1, FeedKind.CONTINUE)
        val duplicate = item(1, FeedKind.NEW_EPISODE)
        val two = item(2, FeedKind.NEW_EPISODE)
        val feed = HomeFeed(one, listOf(one), listOf(duplicate, two), emptyList(), emptyList(), emptyList())
        val rows = tvHomeRows(feed)
        assertEquals("Смотреть сейчас", rows.first().title)
        assertEquals(listOf(1, 2), rows.first().items.map { it.entry.anime.id })
    }

    @Test
    fun `empty rows are omitted in stable order`() {
        val next = item(3, FeedKind.NEXT_UP)
        val planned = item(4, FeedKind.PLANNED)
        val rows = tvHomeRows(HomeFeed(next, emptyList(), emptyList(), listOf(next), emptyList(), listOf(planned)))
        assertEquals(listOf("Следующая серия", "В планах"), rows.map { it.title })
    }
}
```

- [ ] **Step 2: Запустить, убедиться, что тест падает**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests 'app.kaeru.ui.tv.home.TvHomeRowsTest' --console=plain`
Expected: FAIL с `Unresolved reference: tvHomeRows`.

- [ ] **Step 3: Реализовать чистое построение рядов**

`ui/tv/home/TvHomeRows.kt`:

```kotlin
package app.kaeru.ui.tv.home

import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.HomeFeed

data class TvHomeRow(val title: String, val items: List<FeedItem>)

fun tvHomeRows(feed: HomeFeed): List<TvHomeRow> = buildList {
    val watchNow = (feed.continueWatching + feed.newEpisodes).distinctBy { it.entry.anime.id }
    if (watchNow.isNotEmpty()) add(TvHomeRow("Смотреть сейчас", watchNow))
    if (feed.nextUp.isNotEmpty()) add(TvHomeRow("Следующая серия", feed.nextUp))
    if (feed.upcoming.isNotEmpty()) add(TvHomeRow("Скоро", feed.upcoming))
    if (feed.planned.isNotEmpty()) add(TvHomeRow("В планах", feed.planned))
}
```

- [ ] **Step 4: Реализовать QR и TV-вход**

`ui/tv/auth/TvLoginScreen.kt`:

```kotlin
package app.kaeru.ui.tv.auth

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.ui.common.auth.AuthUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private fun qrBitmap(value: String, size: Int = 360): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun TvLoginScreen(authorizeUrl: String, state: AuthUiState, code: String, onCode: (String) -> Unit, onSubmit: () -> Unit) {
    val qr = remember(authorizeUrl) { qrBitmap(authorizeUrl).asImageBitmap() }
    Row(
        Modifier.fillMaxSize().padding(72.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(qr, contentDescription = "QR-код входа через Shikimori", modifier = Modifier.size(360.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1f)) {
            Text("Вход в Kaeru", style = MaterialTheme.typography.displayMedium)
            Text("1. Отсканируйте QR-код телефоном\n2. Разрешите доступ\n3. Введите показанный Shikimori код")
            OutlinedTextField(
                value = code,
                onValueChange = onCode,
                singleLine = true,
                label = { androidx.compose.material3.Text("Код авторизации") },
            )
            Button(onClick = onSubmit, enabled = code.isNotBlank() && !state.exchanging) {
                Text(if (state.exchanging) "Проверяем…" else "Войти")
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
```

- [ ] **Step 5: Реализовать immersive TV-главную**

`ui/tv/home/TvHomeScreen.kt`:

```kotlin
package app.kaeru.ui.tv.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.theme.KaeruAccent
import coil3.compose.AsyncImage

@Composable
fun TvHomeScreen(state: HomeUiState, onRefresh: () -> Unit, onAnime: (LibraryEntry) -> Unit) {
    val rows = remember(state.feed) { tvHomeRows(state.feed) }
    val initialItem = rows.firstOrNull()?.items?.firstOrNull()
    var focused by remember(initialItem) { mutableStateOf(initialItem) }
    val firstFocus = remember { FocusRequester() }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Загружаем библиотеку…") }
        return
    }
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("В списке «Смотрю» пока пусто", style = MaterialTheme.typography.headlineLarge)
            Button(onClick = onRefresh, modifier = Modifier.padding(top = 20.dp)) { Text("Обновить") }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        focused?.let { HeroBackground(it) }
        LazyColumn(
            contentPadding = PaddingValues(top = 300.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(rows, key = { it.title }) { row ->
                Column {
                    Text(row.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 52.dp, vertical = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 52.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(row.items, key = { "${it.kind}-${it.entry.anime.id}" }) { item ->
                            val isFirst = item == initialItem
                            TvPosterCard(
                                item = item,
                                modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                                onFocused = { focused = item },
                                onClick = { onAnime(item.entry) },
                            )
                        }
                    }
                }
            }
        }
        state.errorMessage?.let {
            Row(Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text(it); Button(onClick = onRefresh, modifier = Modifier.padding(start = 12.dp)) { Text("Повторить") }
            }
        }
    }
    LaunchedEffect(initialItem) { if (initialItem != null) firstFocus.requestFocus() }
}

@Composable
private fun HeroBackground(item: FeedItem) {
    val anime = item.entry.anime
    Box(Modifier.fillMaxWidth().height(390.dp).animateContentSize()) {
        AsyncImage(
            model = anime.screenshotUrls.firstOrNull() ?: anime.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent, MaterialTheme.colorScheme.background))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 52.dp).fillMaxWidth(0.48f)) {
            Text(anime.title, style = MaterialTheme.typography.displayMedium, maxLines = 2)
            Text("${item.episode} серия", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            anime.description?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun TvPosterCard(item: FeedItem, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (hasFocus) 1.08f else 1f, label = "tvCardScale")
    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = 154.dp, height = 252.dp)
            .scale(scale)
            .onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) onFocused() }
            .then(if (hasFocus) Modifier.border(3.dp, KaeruAccent, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        Column {
            Poster(item.entry.anime.posterUrl, item.entry.anime.title, Modifier.fillMaxWidth().height(216.dp))
            Text("${item.episode} серия", maxLines = 1, modifier = Modifier.padding(8.dp))
        }
    }
}
```

- [ ] **Step 6: Собрать TvApp и краткую карточку тайтла**

`ui/tv/TvApp.kt`:

```kotlin
package app.kaeru.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.ui.common.Poster
import app.kaeru.ui.common.home.HomeViewModel
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.auth.AuthViewModel
import app.kaeru.ui.tv.auth.TvLoginScreen
import app.kaeru.ui.tv.home.TvHomeScreen

@Composable
fun TvApp(authViewModel: AuthViewModel = hiltViewModel()) {
    val auth = authViewModel.uiState.collectAsStateWithLifecycle().value
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LibraryEntry?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    KaeruTvTheme {
        when (auth.loggedIn) {
            null -> Box(Modifier.fillMaxSize())
            false -> TvLoginScreen(
                authorizeUrl = authViewModel.tvAuthorizeUrl,
                state = auth,
                code = code,
                onCode = { code = it },
                onSubmit = { authViewModel.exchangeTvCode(code) },
            )
            true -> {
                val home: HomeViewModel = hiltViewModel()
                TvHomeScreen(home.uiState.collectAsStateWithLifecycle().value, home::refresh) { selected = it }
                selected?.let { TvTitleCard(it) { selected = null } }
            }
        }
    }
}

@Composable
private fun TvTitleCard(entry: LibraryEntry, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxSize(0.72f)) {
            Row(Modifier.padding(36.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Poster(entry.anime.posterUrl, entry.anime.title, Modifier.weight(0.34f))
                Column(Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(entry.anime.title, style = MaterialTheme.typography.displaySmall)
                    Text("Просмотрено ${entry.rate.episodes} из ${entry.anime.availableEpisodes}")
                    entry.anime.description?.let { Text(it, maxLines = 8) }
                    Button(onClick = onClose) { Text("Назад") }
                }
            }
        }
    }
}
```

Заменить `TvActivity.kt`:

```kotlin
package app.kaeru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.kaeru.ui.tv.TvApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvApp() }
    }
}
```

- [ ] **Step 7: Прогнать тесты и сборку**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: все тесты PASS; TV Material и мобильный Material3 не имеют
неоднозначных imports; `TvActivity` находится в APK как LEANBACK launcher.

- [ ] **Step 8: D-pad и OAuth smoke test на TV**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop app.kaeru
adb shell am start -W -n app.kaeru/.TvActivity
```

Проверить: QR читается телефоном с 2–3 метров; код вводится с пульта; после
входа фокус сразу на top item (или на первой карточке первого ряда); Left/Right ходят по ряду, Up/Down — между
рядами; герой меняется вместе с фокусом; рамка не исчезает; OK открывает
карточку тайтла, Back/«Назад» закрывает её; refresh-ошибка не скрывает кэш.

- [ ] **Step 9: Коммит**

```bash
git add app/src
git commit -m "feat(tv): qr login and dpad-first immersive home"
```

---

### Task 12: Документация, полная проверка и приёмка фундамента

**Files:**
- Create: `README.md`
- Create: `docs/superpowers/manual/2026-09-12-foundation-checklist.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: весь результат Tasks 1–11.
- Produces: воспроизводимые команды настройки/сборки, зафиксированный ручной
  результат mobile/TV/OAuth/Chromecast spike и зелёную release-like сборку.

- [ ] **Step 1: Защитить локальные и сгенерированные файлы**

Дополнить `.gitignore`:

```gitignore
/tools/.venv/
/tools/__pycache__/
*.pyc
```

Не игнорировать `tools/fixtures/`: две fixture из Task 1 нужны тестам Kodik в
плане 2. Перед коммитом проверить, что в них нет OAuth-токенов,
`client_secret` или значения `KODIK_TOKEN`:

```bash
rg -n 'access_token|refresh_token|client_secret|KODIK_TOKEN' tools/fixtures || true
```

Expected: совпадений нет.

- [ ] **Step 2: Написать README**

`README.md`:

````markdown
# Kaeru

Android- и Android TV-клиент для запуска следующей серии аниме. Списки и
прогресс синхронизируются с Shikimori; источник видео Kodik и плеер
подключаются следующим планом.

## Требования

- Android Studio с Android SDK 36
- JDK 21
- `local.properties` на основе `local.properties.example`
- OAuth-приложение Shikimori с redirect URI `kaeru://oauth` и
  `urn:ietf:wg:oauth:2.0:oob`

## Сборка

```bash
cp local.properties.example local.properties
# заполнить sdk.dir, SHIKIMORI_CLIENT_ID и SHIKIMORI_CLIENT_SECRET
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Запуск

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.kaeru/.MainActivity   # телефон
adb shell am start -n app.kaeru/.TvActivity     # Android TV
```

## Архитектура

- `domain` — модели, интерфейсы и построение главной без Android;
- `data/shikimori`, `data/auth` — API и OAuth;
- `data/local`, `data/library` — Room-кэш и синхронизация;
- `ui/common`, `ui/mobile`, `ui/tv` — общая presentation-модель и два
  независимых Compose-интерфейса.

Дизайн: `docs/superpowers/specs/2026-09-12-kaeru-design.md`.
План фундамента: `docs/superpowers/plans/2026-09-12-kaeru-01-foundation.md`.
````

- [ ] **Step 3: Создать и заполнить ручной чек-лист**

`docs/superpowers/manual/2026-09-12-foundation-checklist.md`:

```markdown
# Kaeru foundation — ручная приёмка

Дата: 2026-09-12

## Spike Kodik / Chromecast

- [ ] Токен извлечён или причина отказа записана в design spec
- [ ] HLS manifest и первый segment отвечают HTTP 200
- [ ] CORS-заголовок записан в design spec
- [ ] ffprobe видит H.264 + AAC
- [ ] Default Media Receiver воспроизводит серию либо CAF отмечен обязательным

## Телефон

- [ ] Холодный старт из Room до интерактивной главной < 1000 мс
- [ ] Custom Tab возвращает OAuth code в `kaeru://oauth`
- [ ] Главная содержит правильный top item и непустые ряды
- [ ] Pull-to-refresh сохраняет кэш при сетевой ошибке
- [ ] Library фильтруется и сортируется
- [ ] Search находит тайтл и добавляет его в «В планах»

## Android TV

- [ ] QR читается и OOB code обменивается на токены
- [ ] Стартовый фокус стоит на top item либо первой карточке первого ряда
- [ ] D-pad проходит все карточки и ряды без потери фокуса
- [ ] Hero меняется при фокусе; OK открывает карточку тайтла
- [ ] При ошибке сети кэш остаётся виден

## Shikimori

- [ ] `User-Agent: Kaeru/0.1.0` присутствует
- [ ] Один refresh token request обслуживает параллельные 401
- [ ] Повторный запуск читает сохранённую сессию
- [ ] Изменение статуса видно на shikimori.one после refresh
```

- [ ] **Step 4: Полная автоматическая проверка**

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  --console=plain
```

Expected: `BUILD SUCCESSFUL`; нет failing tests и lint errors; APK существует:

```bash
test -s app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Проверить AndroidManifest итогового APK**

```bash
/Users/vitaliy/Library/Android/sdk/build-tools/36.0.0/aapt dump xmltree app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | rg 'MainActivity|TvActivity|LEANBACK_LAUNCHER|kaeru|oauth'
```

Expected: присутствуют обе activity, `LAUNCHER`, `LEANBACK_LAUNCHER` и схема
`kaeru`/host `oauth`; `TvActivity` landscape; обе activity exported.

- [ ] **Step 6: Замерить холодный старт из кэша**

После успешного OAuth и хотя бы одного sync выполнить пять раз:

```bash
for run in 1 2 3 4 5; do
  adb shell am force-stop app.kaeru
  adb shell am start -W -n app.kaeru/.MainActivity | rg 'TotalTime|WaitTime'
done
```

Expected: `TotalTime` каждого запуска < 1000 ms. Если хотя бы один запуск
медленнее, снять `Perfetto` trace и не отмечать критерий выполненным; оптимизация
становится первым corrective task до плана 2.

- [ ] **Step 7: Проверить отсутствие секретов**

```bash
git status --short
git diff --cached --name-only
git ls-files | rg '(^|/)local\.properties$' && exit 1 || true
rg -n 'SHIKIMORI_CLIENT_SECRET=.+|KODIK_TOKEN=.+' --glob '!local.properties' --glob '!docs/**' . && exit 1 || true
```

Expected: `local.properties` не отслеживается; в исходниках и fixtures нет
заполненных секретов. Упоминания имён переменных в BuildConfig и документации
допустимы.

- [ ] **Step 8: Финальный коммит плана 1**

Отметить только реально пройденные ручные пункты и закоммитить документацию:

```bash
git add .gitignore README.md docs/superpowers/manual/2026-09-12-foundation-checklist.md docs/superpowers/plans/2026-09-12-kaeru-01-foundation.md
git commit -m "docs: foundation setup and acceptance checklist"
```

Expected: `git status --short` пуст; история содержит отдельные коммиты
Tasks 1–12; можно переходить к плану 2 (Kodik, Media3, Chromecast).

---

## Граница готовности плана 1

План 1 завершён, когда автоматическая проверка зелёная, mobile и TV проходят
свои ручные smoke tests, результат Chromecast spike записан в design spec, а
ручной чек-лист содержит фактические отметки. Непройденный Chromecast не
блокирует фундамент, но меняет план 2: собственный CAF receiver становится
обязательным, а не резервным.
