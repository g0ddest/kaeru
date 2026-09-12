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
