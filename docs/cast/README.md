# Скин Chromecast-приёмника

Kaeru кастует на Styled Media Receiver, зарегистрированный в Google Cast SDK Developer Console
(Application ID `0EEA38FE`, статус Published). Эта папка — его оформление: `kaeru.css` и картинки,
на которые он ссылается. Идентификатор приёмника прописан в `app/src/main/java/app/kaeru/player/CastOptionsProvider.kt`.

## Где лежит

Скин раздаётся через GitHub Pages из ветки `gh-pages` этого репозитория:
`https://g0ddest.github.io/kaeru/kaeru.css` — эта ссылка вписана в консоли в поле Skin URL.

## Как обновить

1. Поправить файлы здесь и закоммитить в рабочую ветку.
2. Выложить папку в `gh-pages` (ветка держит только содержимое `docs/cast`, одним корневым коммитом):

   ```bash
   TREE=$(git rev-parse HEAD:docs/cast)
   git push --force origin "$(git commit-tree "$TREE" -m 'Cast receiver skin')":refs/heads/gh-pages
   ```

3. В консоли открыть приложение Kaeru и нажать Publish ещё раз: приёмники подхватывают
   изменения в течение 10–15 минут, иногда после перезагрузки устройства.

## Что настраивается

| Класс | Где виден | Файл |
| --- | --- | --- |
| `.background` | экран ожидания после подключения | `background.png`, 1920×1080 |
| `.logo` | логотип на экране ожидания | `logo.png`, прозрачный |
| `.splash` | пока приёмник загружается | `splash.png`, 1920×1080 |
| `.progressBar` | проигранная часть таймлайна | цвет `#F5A524` |
| `.watermark` | уголок во время воспроизведения | `watermark.png`, показывается 57×57 |

## Проверка

Подключиться к Chromecast из плеера Kaeru: на телевизоре должен появиться тёмный фон с лягушкой
и словом Kaeru, при воспроизведении — жёлтый прогресс и лягушка в углу.
