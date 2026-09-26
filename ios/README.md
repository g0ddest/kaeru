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

## macOS

Из тех же исходников собирается нативное приложение для Mac (не Catalyst): цель
**KaeruMac** в том же `generate_project.rb`, `Kaeru.app` с bundle id `app.kaeru.mac`,
macOS 15+, только Apple silicon. Плеер открывается отдельным окном, у него своё меню
«Воспроизведение» и клавиши; Chromecast, сканера QR-кода и «PiP при выходе» на Mac нет.
Приложение работает в App Sandbox: база, загрузки и кэш лежат в
`~/Library/Containers/app.kaeru.mac`, а не в общих папках пользователя.

### Сборка и запуск

```sh
ruby ios/App/generate_project.rb
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
xcodebuild -project ios/App/Kaeru.xcodeproj -scheme KaeruMac \
  -destination 'platform=macOS,arch=arm64' -derivedDataPath ios/build/mac-dev build
open ios/build/mac-dev/Build/Products/Debug/Kaeru.app
```

В Xcode — схема **KaeruMac** и «My Mac». Схема **KaeruMacTests** гоняет на Mac те же
XCTest, что и iOS (`-scheme KaeruMacTests … test`). Подпись автоматическая, команда
TXY49DW96F, сертификат Apple Development; профиль не нужен, пока сборка без
универсальных ссылок. `MAC_ASSOCIATED_DOMAINS=1` (окружение или `local.properties`)
включает их — тогда первый раз нужен `-allowProvisioningUpdates`: Xcode сам заведёт
App ID с Associated Domains и профиль. Firebase подключается, только если есть
`ios/App/Mac/GoogleService-Info.plist` — это отдельное приложение `app.kaeru.mac` в
проекте kaeru-fceb3.

### Выпуск

```sh
ios/Scripts/release-mac.sh --dry-run   # собрать, подписать и упаковать, в нотаризацию не отправлять
ios/Scripts/release-mac.sh             # то же, затем нотаризация и билет в образе
```

Скрипт собирает архив Release, экспортирует его с подписью Developer ID, кладёт в
образ `Kaeru-<версия>-mac.dmg` приложение и ссылку на «Программы», подписывает образ,
отправляет его в `notarytool submit --wait` и степлит билет (`stapler staple`). Всё
складывается в `ios/build/mac` (другая папка — `--out`); версия берётся из
`android/build.gradle.kts`, а настоящий выпуск собирается только из закоммиченного кода.
Сборка по умолчанию с универсальными ссылками, поэтому скрипт разрешает Xcode обратиться
к порталу разработчика (`-allowProvisioningUpdates`: App ID, профиль Developer ID);
`MAC_ASSOCIATED_DOMAINS=0` собирает без ссылок и без портала. Чтобы ссылки-приглашения
открывали приложение, сайт должен называть `TXY49DW96F.app.kaeru.mac` в
`docs/cast/.well-known/apple-app-site-association` — публикация `web/scripts/publish-site.sh`,
скрипт выпуска предупредит, если Apple этого ещё не видит.

Образ прикладывается к тому же выпуску `v<версия>` на GitHub, что и APK:
`gh release upload v<версия> ios/build/mac/Kaeru-<версия>-mac.dmg`. Экран «Обновления»
на Mac берёт у самого нового выпуска файл `.dmg` и открывает его в браузере; у выпуска
без образа — страницу выпуска.

### Один раз, владельцу команды

1. **Сертификат Developer ID Application** с закрытым ключом в связке ключей этого Mac:
   Xcode → Settings → Accounts → команда TXY49DW96F → Manage Certificates… → «+» →
   Developer ID Application. Сохраните его копию (`.p12`) из «Связки ключей».
2. **Профиль нотаризации `kaeru-notary`**: пароль приложения на account.apple.com →
   «Вход и безопасность» → «Пароли приложений», затем
   `xcrun notarytool store-credentials kaeru-notary --apple-id <Apple ID> --team-id TXY49DW96F`.

Без любого из них `release-mac.sh` останавливается и пишет, чего не хватает и как это
сделать; `--dry-run` при этом собирает всё, что можно собрать до этого шага.

## Структура

`App/` — точка входа, Xcode-проект и генератор; `Core/` — Swift-модели,
адаптер KMP, авторизация и хранилище; `Features/` — экраны и плеер;
`Tests/` — XCTest/XCUITest; `Scripts/` — интеграция Gradle, иконка Mac и
выпуск для Mac. Файлы только для Mac лежат в папках `Mac/` рядом со своими
iOS-двойниками; генератор отдаёт их одной цели KaeruMac.

Kodik-заголовки передаются через `AVURLAssetHTTPHeaderFieldsKey`, как проверено
спайком; это недокументированный ключ AVFoundation, и его поведение необходимо
перепроверять на целевых версиях iOS. Отмена Swift Task защищает UI от позднего
ответа, но не отменяет уже отправленный Kotlin suspend-запрос.
