# Kaeru — план 5: совместный просмотр (дизайн)

Дата: 2026-09-16. Основа: ресёрч `docs/superpowers/research/2026-09-15-watch-together-tech.md` и
`…-ux.md`; идея пользователя записана в базовой спеке §12a. Пользователь разрешил начинать
разработку без отдельного согласования, если ресёрч не показал блокеров; один открытый вопрос —
аккаунт Cloudflare для релея (см. §2, «Транспорт»).

## 1. Цель

Два телефона с Kaeru смотрят одну серию синхронно и общаются поверх видео: хост шлёт ссылку в любой
чат, друг открывает её, видит экран подключения и попадает в ту же секунду; дальше оба управляют
воспроизведением на равных, обмениваются короткими сообщениями, реакциями и голосовыми клипами.
Аудитория: автор и несколько друзей. v1 — только телефоны; Android TV не участвует.

Критерии готовности: (1) сессия на двоих на разных сетях устанавливается по ссылке за ≤ 10 с;
(2) пауза/перемотка/смена серии одной стороны видна другой ≤ 1 с с подписью; (3) расхождение
позиций держится ≤ 2 с при нормальной сети; (4) сообщения, реакции и голосовые доходят и исчезают
по правилам UX‑отчёта; (5) любое ожидание имеет таймаут и кнопку выхода; (6) без сети/релея
приложение честно говорит об этом и предлагает «Смотреть одному».

## 2. Решения и почему

| Вопрос | Решение | Почему |
| --- | --- | --- |
| Транспорт | Интерфейс `WatchTogetherTransport`; реализации: `LanSocketTransport` (прямой TCP в одной сети, переиспользует пейринг ТВ) и `RelayTransport` (WebSocket к Cloudflare Worker + Durable Object) | Прямой адрес из ссылки не достижим через NAT/CGNAT; WebRTC не нужен, медиа по каналу не идёт; релей на бесплатном тарифе стоит €0 и не добавляет зависимостей (OkHttp умеет WebSocket) |
| Открытый вопрос | Релей требует аккаунт Cloudflare пользователя (`wrangler login` интерактивен) | Код воркера и клиента пишется и тестируется заранее (MockWebServer WebSocket, локальный `wrangler dev` если доступен); деплой — после ответа пользователя; запасной путь — публичный брокер (ntfy.sh) без гарантий |
| Ссылка | `https://kaeru.vitaliy.velikodniy.name/w/<roomId>#<key>` (App Links, `assetlinks.json` на том же GitHub Pages; страница `w/` с APK для тех, у кого нет приложения); LAN‑форма `kaeru://watch?h=<ip>&p=<port>&r=<roomId>#<key>` | Работает из любого чата; ключ во фрагменте не уходит на сервер |
| Шифрование | Полезная нагрузка шифруется AES‑GCM ключом из фрагмента (128 бит), nonce per message; релей видит только шифртекст и roomId | Релей не надо считать доверенным |
| Роли | Симметрично: обе стороны шлют действия, побеждает последнее (по монотонному `seq` отправителя + часы); каждое чужое действие подписывается уведомлением | Двое; хост‑права усложняют и раздражают (UX‑отчёт §4.1) |
| Синхронизация | Каждый играет свой поток Kodik (ссылки IP‑bound). Часы: ping/pong, offset = RTT/2; «эталон» = позиция последнего действовавшего. Расхождение < 0.5 с — ничего; 0.5–2 с — `playbackParameters` 0.97/1.03 до схождения; 2–10 с — seek; > 10 с — seek + уведомление «Догоняем»; буферизация одной стороны не ставит другую на паузу; ожидание подключения/догоняющего — таймаут 30 с + «Смотреть дальше» | Правило UX‑отчёта: любое ожидание с выходом; числа из Syncplay/SyncPlay |
| Серия/озвучка | Сессия фиксирует anime + episode + translationId; сторона без такой озвучки берёт ближайшую из памяти и получает уведомление «У тебя другая озвучка»; смена серии одной стороной переключает обе (автоплей тоже) | Простая модель, ничего не блокирует |
| Чат | Текст ≤ 200 символов, показывается 7 с в левом нижнем столбце (до 3 строк), стопка раскрывается тапом (история только текущей сессии, в памяти); три пресета («😂», «Стоп, что?», «Дальше!») | UX‑отчёт §2 |
| Реакции | Шесть: ❤️ 😂 😮 😢 🔥 👏; всплывают и гаснут 1.2 с, ≤ 3 одновременно; при отключённых анимациях — статично 1.2 с | UX‑отчёт §2, WCAG 2.3.3 |
| Голос | Клипы ≤ 30 с: `MediaRecorder` OGG/Opus 16 кГц моно (~3 КБ/с); удержание — запись, свайп влево — отмена, свайп вверх — закрепить; приём — автопроигрывание с приглушением видео (audio focus DUCK), возможность переслушать; блоб шифруется и идёт тем же каналом | UX‑отчёт §3, техотчёт §6 |
| Жизненный цикл | Сессия живёт, пока плеер на экране или в PiP; сворачивание > 60 с → «Вы вышли из совместного просмотра», рекоммит по возврату в течение 5 мин по тому же roomId | Doze режет сеть; честное ожидание |
| Разрешения | `RECORD_AUDIO` запрашивается при первом удержании микрофона; foreground‑сервис не нужен (запись только на экране) | Минимум |
| ТВ | Ничего в v1 (кнопки не показываются) | UX‑отчёт §6 |

## 3. Архитектура

```
domain/together
  RoomLink(roomId, key, lan: LanEndpoint?)        parse(uri)/toHttps()/toLan()
  TogetherMessage (sealed): Hello(name, animeId, episode, translationId, positionMs, playing, seq)
      | Play(positionMs, seq) | Pause(positionMs, seq) | Seek(positionMs, seq)
      | Episode(episode, translationId, seq) | State(positionMs, playing, buffering, seq, sentAt)
      | Chat(text, seq) | Reaction(kind, seq) | Voice(bytes, durationMs, seq) | Ping(t) | Pong(t, t2) | Bye
  TogetherCodec: JSON (kotlinx.serialization) + AES‑GCM(key) → frames
  SyncPolicy.decide(local, remote, clockOffset) → Nothing | Rate(x) | SeekTo(ms) | SeekAndNotify(ms)
  WatchTogetherTransport { connect(link): Flow<TogetherMessage>; send(msg); close() } + ConnectionState
data/together
  TogetherSession: coordinates transport ↔ PlaybackController through a PlaybackPort; last-action-wins; notices; timeouts (in data: owns a scope, transports and logging; the contract TogetherSessionApi stays in domain)
  LanSocketTransport (TCP, length-prefixed frames; host side = ServerSocket like the pairing server)
  RelayTransport (OkHttp WebSocket, reconnect with backoff, room join by roomId)
  VoiceRecorder (MediaRecorder), VoicePlayer (ExoPlayer/MediaPlayer with ducking)
infra/relay (TypeScript)   Cloudflare Worker + Durable Object: room = DO id; broadcast frames; 2 peers max; idle expiry 6 h
ui/common/together          TogetherViewModel (state: connection, peerName, notices, chat stack, reactions, voice)
ui/mobile/together          Share action in the player top bar; JoinScreen (Routes.WATCH); overlay (chat/reactions/voice/notices)
docs/cast/w/index.html + .well-known/assetlinks.json   landing + App Links on GitHub Pages
```

Слои прежние. `domain` без android/androidx/data и без русских строк; копирайт в `ui.common`.

## 4. Потоки

**Хост.** «Смотреть вместе» → `TogetherSession.host()` создаёт roomId (64 бит) + key (128 бит),
открывает транспорт (LAN: `ServerSocket`; relay: WebSocket join), формирует ссылку и открывает share
sheet с текстом «Смотрим «<тайтл>», <N серию>. Открой в Kaeru: <ссылка>». Плеер показывает «Ждём
друга…» без остановки видео.

**Гость.** Ссылка → `MainActivity` → `Routes.WATCH` → экран подключения (постер, серия, минута хоста
из `Hello`) → «Присоединиться» → резолв своего потока (та же серия/озвучка) → `TogetherSession.join()`
→ seek к позиции хоста с учётом offset → уведомление хосту «<имя> подключился».

**Действия.** Локальные play/pause/seek/episode → сообщения с `seq`; входящие применяются к
`PlaybackController` с пометкой «remote» (чтобы не эхо‑отправлять) и уведомлением. `State` каждую
секунду; `SyncPolicy` каждые 2 с.

**Разрыв.** Транспорт переподключается 30 с; на экране «Связь с другом потеряна» + «Смотреть одному»;
`Bye` — «<имя> вышел».

## 5. Тесты

Pure: `RoomLink`, `TogetherCodec` (round trip, tamper → reject), `SyncPolicy` (границы 0.5/2/10 с,
offset), last‑action‑wins с `seq`, таймауты. Data: `LanSocketTransport` через реальный сокет на
localhost; `RelayTransport` через MockWebServer WebSocket (join, reconnect, frames). Session:
фейковый транспорт + фейковый контроллер (apply remote, no echo, notices, episode switch, rejoin).
UI: ViewModel‑мэппинг (стопка чата 7 с, ≤3 реакции, состояния подключения), превью. Устройства:
телефон + ТВ как второй пир по LAN (ТВ без UI, но сессию принимает — только для проверки таймлайнов
Kodik и синхронизации; ТВ‑кнопки не показываются).

## 6. Вне охвата

> 2 участников, живой голос, история между сессиями, аватары, ТВ‑интерфейс, транскрибация.
