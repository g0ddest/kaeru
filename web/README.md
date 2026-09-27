# Kaeru для браузера

Веб-клиент Kaeru: главная, поиск, карточка тайтла, «Мой список», свой плеер (hls.js, в Safari — встроенный
HLS), синхронизация между устройствами и «Смотреть украдкой». Личный: войти может только аккаунт
Shikimori из белого списка воркера (`WEB_ALLOWED_SHIKIMORI_IDS`, см. `infra/relay/README.md`).

Спецификация: `docs/superpowers/specs/2026-09-24-kaeru-web-design.md`; планы —
`docs/superpowers/plans/2026-09-24-kaeru-web-0*.md`.

## Разработка

```sh
cd web
npm ci
npm run dev          # http://localhost:5173 — воркер пускает этот адрес по CORS
npm test             # Vitest + jsdom
npm run typecheck
npm run build
```

Shikimori отвечает браузеру напрямую; Kodik, обмен токена OAuth и `/sync` идут через воркер
(`src/config.ts`). Секретов в клиенте нет.

## Где что

| Папка | Что там |
|---|---|
| `src/app/` | маршруты, сервисы, вход и белый список |
| `src/api/`, `src/auth/` | клиент Shikimori, сессия и обновление токена |
| `src/library/` | список, прогресс по сериям, настройки, «украдкой» |
| `src/player/` | клиент Kodik через воркер, движок (hls.js/нативный HLS), контроллер, AniSkip |
| `src/sync/` | синхронизация между устройствами (`/sync`), включается в настройках |
| `src/screens/`, `src/ui/` | экраны и общие компоненты |

## Публикация

```sh
web/scripts/publish-site.sh --dry-run   # собрать и показать, что изменится
web/scripts/publish-site.sh             # собрать и отправить в gh-pages (без force)
```

Скрипт собирает сайт из закоммиченного кода вместе с `docs/cast/` (скин Chromecast, файлы
App Links и страница-приглашение `/w/`) и кладёт одним коммитом поверх `origin/gh-pages`. Глубокие
ссылки (`/anime/1535`) открываются через `404.html`, который переводит их в приложение.
