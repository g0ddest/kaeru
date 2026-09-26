# Kaeru shared

Kotlin Multiplatform online-core для Android, iOS и macOS: Shikimori, OAuth relay,
Kodik resolver и правила прогресса. Существующее Android-приложение пока
использует свои репозитории; его UI и сетевой стек не изменены.

`src/commonMain` содержит переносимую логику, `androidMain` — Ktor OkHttp,
`appleMain` — Ktor Darwin для iOS и macOS (`iosMain` и `macosMain` дают только
`Platform`). `NativeApi` экспортируется в статический
`KaeruShared.framework`; suspend-методы возвращают стабильный JSON-контракт
и NSError при ошибках. Swift декодирует его в свои модели. Секрет OAuth,
Keychain, состояние сессии, локальный кэш и очередь не дублируются в shared.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :shared:allTests :shared:linkDebugFrameworkIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosArm64 :shared:linkDebugFrameworkMacosArm64
```

Тесты common используют реальные Kodik HTML-фикстуры из Android, MockEngine
и виртуальное время. `verification/SwiftBridgeSmoke.swift` вместе с
`swift_bridge_smoke.py` проверяет NSError 401 и Content-Length OAuth на
настоящем Darwin transport через локальный HTTP fixture server.

Кодовый обмен использует `kaeru://oauth`. HTTP 401 обрабатывает Swift:
один refresh/retry, единый refresh для конкурентных запросов, проверка
поколения аккаунта до отправки и применения результата. Подписанные Kodik URL
не сохраняются. Каталоги озвучек и подписи запрашиваются заново.
