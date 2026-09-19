# Kaeru для iPhone и iPad

Первая нативная итерация: SwiftUI, SwiftData, Keychain и AVKit; iOS/iPadOS 17+.
На iPhone используются системные вкладки, на iPad — NavigationSplitView.
Светлая и тёмная темы следуют настройкам системы. Плеер использует штатные
элементы AVPlayerViewController; серии, озвучка и качество доступны в меню.

Поддержаны каталог, сезонная витрина, поиск, карточка аниме, Shikimori OAuth,
все разделы библиотеки, онлайн-просмотр Kodik, продолжение просмотра по сериям,
выбор озвучки и качества, пропуск заставок/титров, автоматическая следующая
серия, PiP и фоновое аудио, а также синхронизация просмотренных серий после
настраиваемого порога. Позиция внутри серии хранится на устройстве; изменения
библиотеки сохраняются через атомарный outbox и переживают выход из аккаунта.

В этой ветке также подключены фоновые HLS-загрузки с восстановлением после
перезапуска, локальные уведомления о новых сериях, управление Chromecast через
штатный Google Cast диалог и протокол совместного просмотра/TV pairing в
`Together/` и `Device/`. UI использует NavigationSplitView на iPad и системные
вкладки на iPhone, общие SwiftUI-компоненты масштабируются между размерами.

## Сборка

Требуются Xcode с iOS SDK, JDK 21, Android SDK 36 для конфигурации монорепозитория,
Ruby и gem `xcodeproj` (проверено с 1.22.0). Kotlin/Gradle скачивают зависимости
при первой сборке. Текущая конфигурация поддерживает arm64-устройства и
симуляторы на Apple Silicon.

```sh
gem install xcodeproj
ruby ios/App/generate_project.rb
open ios/App/Kaeru.xcodeproj
```

Выберите схему **Kaeru** и iPhone/iPad Simulator. Build phase сам соберёт
`KaeruShared.framework`. Не отключайте code signing у запускаемой simulator-сборки:
Связка ключей требует штатной подписи симулятора. Для физического устройства
выберите свою Development Team в Signing & Capabilities.

Генератор читает только публичные `SHIKIMORI_CLIENT_ID`, `AUTH_PROXY_URL` и
необязательный `TOGETHER_RELAY_URL`
из корневого `local.properties` либо одноимённых переменных окружения.
Можно передать путь к properties первым аргументом. `Configuration.plist`
игнорируется Git и включается в приложение как ресурс. `client_secret`
никогда не копируется в iOS; обмен токенов выполняет существующий OAuth relay.
Redirect URI: `kaeru://oauth`. Без конфигурации доступен гостевой каталог.
Повторный запуск генератора сохраняет пользовательские данные Xcode.

Для сборки с Chromecast положите Google Cast iOS SDK 4.8.6 в
`ios/Dependencies/GoogleCastSDK-ios-4.8.6_static_xcframework/GoogleCast.xcframework`.
Каталог зависимостей намеренно игнорируется Git, поэтому SDK устанавливается
локально на машине разработчика. Нативный проект также подключает
`GTMSessionFetcherCore` через Swift Package Manager из официального репозитория
Google; при первом открытии Xcode может показать `Resolve Package Versions`.
CocoaPods для iOS-части не требуется.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
xcodebuild -project ios/App/Kaeru.xcodeproj -scheme Kaeru \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  -derivedDataPath ios/build test
```

## Проверки

Результаты и ограничения текущего прогона: [VERIFICATION.md](VERIFICATION.md).

- **Kaeru** — автономные XCTest: OAuth state, Keychain, локальное хранение,
  очередь, refresh и смена аккаунта, Swift → Kotlin bridge.
- **Kaeru-Live** — дополнительно реальные публичные Shikimori/Kodik запросы,
  запуск AVPlayer, перемотка и смена качества с сохранением позиции.
- **Kaeru-UI** — сквозной UI-тест поиска, карточки, плеера и настроек;
  требует сеть, сохраняет скриншоты в `.xcresult`.
- `./gradlew :shared:allTests :app:testDebugUnitTest` — общий модуль и
  регрессии существующего Android-приложения.

Живая авторизация и запись в личную библиотеку требуют интерактивного входа
в Shikimori. Автотесты не используют реальные учётные данные и не меняют
библиотеку пользователя. Физические iPhone/iPad в текущем прогоне не проверялись.

## Структура

`App/` — точка входа, Xcode-проект и генератор; `Core/` — Swift-модели,
адаптер KMP, авторизация и хранилище; `Features/` — экраны и плеер;
`Tests/` — XCTest/XCUITest; `Scripts/` — интеграция Gradle.

Kodik-заголовки передаются через `AVURLAssetHTTPHeaderFieldsKey`, как проверено
спайком; это недокументированный ключ AVFoundation, и его поведение необходимо
перепроверять на целевых версиях iOS. Отмена Swift Task защищает UI от позднего
ответа, но не отменяет уже отправленный Kotlin suspend-запрос.
