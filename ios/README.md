# Kaeru iOS

Нативное iOS-приложение Kaeru для iPhone и iPad.

Папка намеренно находится в корне репозитория, чтобы iOS-изменения проще
сливались с Android-веткой. Первый этап включает авторизацию, главную,
библиотеку, поиск, экран тайтла и нативный AVPlayer. Офлайн-загрузки,
Watch Together, Cast и уведомления подключаются отдельными этапами.

Планируемая структура:

```text
ios/
  App/
  Features/
  Player/
  Services/
  DesignSystem/

shared/
  commonMain/
    domain/
    data/kodik/
    data/shikimori/
    data/auth/
    data/playback/
  androidMain/
  iosMain/
```
