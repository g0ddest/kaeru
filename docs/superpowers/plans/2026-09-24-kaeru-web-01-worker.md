# Kaeru для браузера, план 1 — воркер: Kodik, проба видео, белый список

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** воркер `infra/relay` умеет резолвить Kodik для браузера (`/kodik/translations`, `/kodik/resolve`), пускает к этому и к обмену токена из браузера только аккаунты Shikimori из белого списка, а проба отвечает, играет ли браузер ссылку, полученную с IP Cloudflare.

**Architecture:** Kotlin-цепочка Kodik (`shared/.../kodik/*`) переносится в TypeScript внутри воркера тремя модулями — разбор страницы, расшифровка ссылок, клиент с кэшами. Маршруты `/kodik/*` и CORS для сайта живут в отдельных модулях, `index.ts` только маршрутизирует. Белый список — модуль с запросом `whoami` к Shikimori и кэшем по хэшу токена; он же встраивается в `proxyToken` для запросов с `Origin` сайта.

**Tech Stack:** Cloudflare Workers (TypeScript 5.9, wrangler 4.132), Durable Objects (уже есть), Vitest 4 + `@cloudflare/vitest-pool-workers` 0.22.

**Spec:** `docs/superpowers/specs/2026-09-24-kaeru-web-design.md` (§4, §5, §6, §6a, §10, §11 пункты 1–2).

Это первый из нескольких планов по спецификации. Следующие — каркас веб-клиента и экраны, плеер, совместный просмотр, аналитика и публикация — пишутся после задачи 4 этого плана: её результат решает §5.

## Global Constraints

- Сайт: `https://kaeru.vitaliy.velikodniy.name`; разработка: `http://localhost:5173`. Только эти два `Origin` получают CORS и считаются вебом.
- Веб-адрес возврата OAuth: `https://kaeru.vitaliy.velikodniy.name/auth` (и `http://localhost:5173/auth` для разработки).
- Белый список: секрет воркера `WEB_ALLOWED_SHIKIMORI_IDS`, id через запятую. Пустой или отсутствующий список закрывает веб для всех.
- Кэш `whoami` — 10 минут по SHA-256 токена; кэш каталога Kodik — 6 часов; токен Kodik — сутки.
- Хосты Kodik: `https://kodik-api.com`, `https://kodikplayer.com`, `https://kodik-add.com/add-players.min.js?v=2`. Shikimori: `https://shikimori.io`.
- Приложения (`kaeru://oauth`, OOB, без `Origin` сайта) ведут себя ровно как до этого плана: существующие тесты `oauth.test.ts` и `room.test.ts` проходят без изменений.
- Ничего из токенов, кодов, id комнат, ников не логируется, кроме уже существующих строк.
- Экспортировать из `src/index.ts` можно только обработчик и классы Durable Object (workerd иначе не стартует); новые модули экспортируют что угодно.

## Review Focus

1. **Запрос из браузера к `/oauth/token` с `refresh_token`** — адреса возврата нет, веб узнаётся только по `Origin`; обновление токена аккаунта, удалённого из списка, должно получить 403, а не новый токен. Тест — в задаче 5.
2. **Preflight `OPTIONS`** от браузера на `/kodik/*` и `/oauth/token` — должен получить 204 с CORS без проверки токена, иначе браузер не отправит сам запрос. Тест — в задаче 3.
3. **Shikimori недоступен при проверке `whoami`** — ответ 502 «не удалось проверить доступ», а не 403 «доступ закрыт» и не пропуск. Тест — в задаче 5.
4. **Фильм без выбора озвучки** (`movie-single-track.html`) — `/kodik/translations` отдаёт одну озвучку со страницы, а не пустой список. Тест — в задаче 2.
5. **Токен Kodik протух** (401 или `{"error":"...токен..."}` под 200) — воркер перечитывает токен один раз и повторяет запрос. Тест — в задаче 2.

---

## Карта файлов

- `infra/relay/src/kodik/parse.ts` — разбор страницы плеера Kodik, токен из `add-players`, HTML-сущности. Чистые функции.
- `infra/relay/src/kodik/links.ts` — расшифровка ответа `/ftor` в `{quality: url}`. Чистые функции.
- `infra/relay/src/kodik/errors.ts` — `KodikError` с видом и шагом.
- `infra/relay/src/kodik/client.ts` — цепочка запросов: токен, `get-player`, страницы, `/ftor`; кэши каталога и токена. `fetch` и часы внедряются.
- `infra/relay/src/kodik/routes.ts` — `GET /kodik/translations`, `GET /kodik/resolve`: разбор параметров, JSON-ответы, коды ошибок.
- `infra/relay/src/web.ts` — какие `Origin` веб, preflight, добавление CORS к ответу.
- `infra/relay/src/whitelist.ts` — разбор списка, `whoami` с кэшем, проверка токена.
- `infra/relay/src/index.ts` — маршрутизация новых путей; `proxyToken` получает ветку веба.
- `infra/relay/types/env.d.ts` — `WEB_ALLOWED_SHIKIMORI_IDS?`, `KODIK_TOKEN?`.
- `infra/relay/vitest.config.ts` — привязки для тестов.
- Тесты: `infra/relay/test/kodik-parse.test.ts`, `kodik-links.test.ts`, `kodik-client.test.ts`, `kodik-routes.test.ts`, `whitelist.test.ts`; дополнения в `oauth.test.ts`.
- Фикстуры не копируются: тесты импортируют `android/src/test/resources/kodik/*` через `?raw`.
- `tools/kodik-probe/` — страница и скрипт пробы (задача 4).
- `infra/relay/README.md`, `docs/superpowers/specs/2026-09-24-kaeru-web-design.md` — документация и итог пробы.

Все команды — из `infra/relay`, если не сказано иное.

---

### Task 1: Разбор страницы Kodik и расшифровка ссылок

**Files:**
- Create: `infra/relay/src/kodik/errors.ts`, `infra/relay/src/kodik/parse.ts`, `infra/relay/src/kodik/links.ts`
- Test: `infra/relay/test/kodik-parse.test.ts`, `infra/relay/test/kodik-links.test.ts`

**Interfaces:**
- Produces:
  - `class KodikError extends Error { kind: "title" | "episode" | "token" | "parser" | "upstream"; step?: string }`
  - `interface TranslationOption { id: number; title: string; type: "voice" | "subtitles"; episodesCount: number | null; mediaId: string; mediaHash: string }`
  - `interface EpisodeOption { number: number; mediaId: string; mediaHash: string }`
  - `interface PlayerPage { domain; dSign; pd; pdSign; ref; refSign; currentType; currentHash; currentId: string; currentTranslationId: number | null; currentTranslationTitle: string | null; translations: TranslationOption[]; episodes: EpisodeOption[]; ftorPath: string }`
  - `parsePlayerPage(html: string): PlayerPage` — бросает `KodikError("parser", step)`
  - `extractPublicToken(js: string): string | null`
  - `decodeHtmlEntities(text: string): string`
  - `decodeLinks(json: string): Map<number, string>` — бросает `KodikError("parser", "links")`
  - `decodeSrc(encoded: string): string | null`

- [ ] **Step 1: Write the failing tests**

`infra/relay/test/kodik-parse.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import player from "../../../android/src/test/resources/kodik/player.html?raw";
import movie from "../../../android/src/test/resources/kodik/movie.html?raw";
import addPlayers from "../../../android/src/test/resources/kodik/add-players.js?raw";
import { KodikError } from "../src/kodik/errors";
import { decodeHtmlEntities, extractPublicToken, parsePlayerPage } from "../src/kodik/parse";

// The same fixtures and the same expected values as shared/src/commonTest/.../KodikHtmlParserTest.kt:
// the TypeScript port has to read Kodik's pages exactly as the apps do.
describe("parsePlayerPage", () => {
  it("reads the signing parameters of a real player page", () => {
    const page = parsePlayerPage(player);
    expect(page.domain).toBe("kodikplayer.com");
    expect(page.dSign).toBe("7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8:2609140747");
    expect(page.pd).toBe("kodikplayer.com");
    expect(page.pdSign).toBe("7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8:2609140747");
    expect(page.ref).toBe("https://kodikplayer.com/");
    expect(page.refSign).toBe("6137eaa1d4c94e3b6a15aaf56eb92ada806bda5bbec4784915459162b3ed622b:2609140747");
  });

  it("reads the video the page is showing", () => {
    const page = parsePlayerPage(player);
    expect(page.currentType).toBe("seria");
    expect(page.currentHash).toBe("cf62e729fdb71a0b7fb148ba6fc48ad6");
    expect(page.currentId).toBe("1211482");
  });

  it("reads every translation, the first being studio 3560", () => {
    const page = parsePlayerPage(player);
    expect(page.translations).toHaveLength(33);
    expect(page.translations[0]).toMatchObject({
      id: 3560, type: "voice", episodesCount: 28, mediaId: "55917", mediaHash: "d1d44d5cd59af5af897ce899a776dacf",
    });
  });

  it("reads all 28 episodes in order", () => {
    const page = parsePlayerPage(player);
    expect(page.episodes.map((e) => e.number)).toEqual(Array.from({ length: 28 }, (_, i) => i + 1));
    expect(page.episodes[0]).toMatchObject({ mediaId: "1211482", mediaHash: "cf62e729fdb71a0b7fb148ba6fc48ad6" });
  });

  it("defaults the ftor path", () => {
    expect(parsePlayerPage(player).ftorPath).toBe("/ftor");
  });

  it("accepts a movie page with no episode list", () => {
    const page = parsePlayerPage(movie);
    expect(page.currentType).toBe("video");
    expect(page.currentId).toBe("990011");
    expect(page.currentHash).toBe("aa11bb22cc33dd44ee55ff6677889900");
    expect(page.episodes).toHaveLength(0);
    expect(page.translations).toHaveLength(33);
  });

  it("names the first missing piece of a broken page", () => {
    expect(() => parsePlayerPage("<html>nothing</html>")).toThrowError(KodikError);
    try { parsePlayerPage("<html>nothing</html>"); } catch (e) {
      expect((e as KodikError).kind).toBe("parser");
      expect((e as KodikError).step).toBe("domain");
    }
  });
});

describe("extractPublicToken", () => {
  it("finds the token in add-players", () => {
    expect(extractPublicToken(addPlayers)).toBe("0000000000000000000000000000abcd");
  });
  it("answers null when there is none", () => {
    expect(extractPublicToken("var x = 1;")).toBeNull();
  });
});

describe("decodeHtmlEntities", () => {
  it("decodes named, decimal and hex entities", () => {
    expect(decodeHtmlEntities("Tom &amp; Jerry &lt;3&gt;")).toBe("Tom & Jerry <3>");
    expect(decodeHtmlEntities("&#65;&#x41;&#X41;")).toBe("AAA");
  });
  it("leaves unknown and out-of-range entities as they are", () => {
    expect(decodeHtmlEntities("&notareal;")).toBe("&notareal;");
    expect(decodeHtmlEntities("&#x110000;")).toBe("&#x110000;");
    expect(decodeHtmlEntities("&#99999999999;")).toBe("&#99999999999;");
  });
});
```

`infra/relay/test/kodik-links.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import links from "../../../android/src/test/resources/kodik/links.json?raw";
import { KodikError } from "../src/kodik/errors";
import { decodeLinks, decodeSrc } from "../src/kodik/links";

function rotate(text: string, n: number): string {
  return text.replace(/[a-z]/g, (c) => String.fromCharCode(97 + ((c.charCodeAt(0) - 97 + n) % 26)))
    .replace(/[A-Z]/g, (c) => String.fromCharCode(65 + ((c.charCodeAt(0) - 65 + n) % 26)));
}

describe("decodeLinks", () => {
  it("decodes every quality of a real /ftor answer to an https manifest", () => {
    const result = decodeLinks(links);
    expect([...result.keys()].sort((a, b) => a - b)).toEqual([360, 480, 720]);
    for (const url of result.values()) {
      expect(url.startsWith("https://")).toBe(true);
      expect(url).toContain("manifest.m3u8");
    }
  });

  it("refuses an answer with nothing decodable", () => {
    const json = JSON.stringify({ links: { 360: [{ src: "not-base64-garbage!!!" }] } });
    expect(() => decodeLinks(json)).toThrowError(KodikError);
  });
});

describe("decodeSrc", () => {
  it("round-trips a rotated base64 manifest url", () => {
    const plain = "https://example.com/stream/manifest.m3u8?sig=abc";
    const base64 = btoa(plain).replace(/=+$/, "");
    expect(decodeSrc(rotate(base64, 26 - 7))).toBe(plain);
  });
  it("answers null for garbage", () => {
    expect(decodeSrc("!!!not-valid-base64-at-all???")).toBeNull();
  });
});
```

Add to `infra/relay/types/env.d.ts` (the `?raw` imports need a declaration):

```ts
declare module "*?raw" {
  const text: string;
  export default text;
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `npx vitest run test/kodik-parse.test.ts test/kodik-links.test.ts`
Expected: FAIL — `Failed to resolve import "../src/kodik/parse"`.

- [ ] **Step 3: Implement**

`infra/relay/src/kodik/errors.ts`:

```ts
/** Why Kodik could not give what was asked, in words the routes can turn into a status. */
export type KodikErrorKind = "title" | "episode" | "token" | "parser" | "upstream";

export class KodikError extends Error {
  constructor(readonly kind: KodikErrorKind, readonly step?: string) {
    super(step === undefined ? `kodik ${kind}` : `kodik ${kind}: ${step}`);
  }
}
```

`infra/relay/src/kodik/parse.ts`:

```ts
import { KodikError } from "./errors";

/**
 * Kodik's player page, read the way `KodikHtmlParser` in shared/ reads it. Kept line for line
 * with the Kotlin: both are checked against the same fixtures, and a page that one reads and the
 * other does not is a bug in one of them.
 */
export interface TranslationOption {
  id: number;
  title: string;
  type: "voice" | "subtitles";
  episodesCount: number | null;
  mediaId: string;
  mediaHash: string;
}

export interface EpisodeOption {
  number: number;
  mediaId: string;
  mediaHash: string;
}

export interface PlayerPage {
  domain: string;
  dSign: string;
  pd: string;
  pdSign: string;
  ref: string;
  refSign: string;
  currentType: string;
  currentHash: string;
  currentId: string;
  currentTranslationId: number | null;
  currentTranslationTitle: string | null;
  translations: TranslationOption[];
  episodes: EpisodeOption[];
  ftorPath: string;
}

const OPTION = /<option\b([\s\S]*?)>([\s\S]*?)<\/option>/gi;
const ATTR = /([a-zA-Z][a-zA-Z0-9-]*)\s*=\s*"([^"]*)"/g;
const EPISODE_COUNT_IN_TEXT = /\((\d+)\s*эп\.\)/;
const TRAILING_EPISODE_COUNT = /\s*\(\d+\s*эп\.\)\s*$/;
const ATOB = /atob\(["']([^"']*)["']\)/g;
const TOKEN = /token\s*=\s*"([a-z0-9]+)"/;
const CURRENT_TRANSLATION_ID = /\btranslationId\s*=\s*(\d+)/;
const CURRENT_TRANSLATION_TITLE = /\btranslationTitle\s*=\s*"([^"]*)"/;
const ENTITY = /&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);/g;
const TRANSLATION_BOXES = ["serial-translations-box", "movie-translations-box"];
const NAMED: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: "\"", apos: "'" };

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function parsePlayerPage(html: string): PlayerPage {
  const paramsText = /\burlParams\s*=\s*'([^']*)'/.exec(html)?.[1];
  let params: Record<string, unknown> | null = null;
  if (paramsText !== undefined) {
    try { params = JSON.parse(paramsText) as Record<string, unknown>; } catch { params = null; }
  }
  const signing = (name: string, key: string = name): string => {
    const direct = new RegExp(`\\b${escape(name)}\\s*=\\s*"([^"]*)"`).exec(html)?.[1];
    if (direct !== undefined) return direct;
    const value = params?.[key];
    if (typeof value !== "string") throw new KodikError("parser", name);
    return key === "ref" ? decodeURIComponent(value) : value;
  };
  const domain = signing("domain", "d");
  const dSign = signing("d_sign");
  const pd = signing("pd");
  const pdSign = signing("pd_sign");
  const ref = signing("ref");
  const refSign = signing("ref_sign");

  const need = (regex: RegExp, step: string): string => {
    const found = regex.exec(html)?.[1];
    if (found === undefined) throw new KodikError("parser", step);
    return found;
  };
  const currentType = need(/vInfo\.type\s*=\s*'([^']*)'/, "vInfo.type");
  const currentHash = need(/vInfo\.hash\s*=\s*'([^']*)'/, "vInfo.hash");
  const currentId = need(/vInfo\.id\s*=\s*'([^']*)'/, "vInfo.id");
  const serial = currentType === "seria";

  const translations = parseTranslations(html);
  if (translations.length === 0 && serial) throw new KodikError("parser", "translations");
  const episodes = parseEpisodes(html);
  if (episodes.length === 0 && serial) throw new KodikError("parser", "episodes");

  const idText = CURRENT_TRANSLATION_ID.exec(html)?.[1];
  const titleText = CURRENT_TRANSLATION_TITLE.exec(html)?.[1];
  const title = titleText === undefined ? null : decodeHtmlEntities(titleText);
  return {
    domain, dSign, pd, pdSign, ref, refSign, currentType, currentHash, currentId,
    currentTranslationId: idText === undefined ? null : Number(idText),
    currentTranslationTitle: title !== null && title.trim() !== "" ? title : null,
    translations, episodes, ftorPath: ftorPath(html),
  };
}

export function extractPublicToken(js: string): string | null {
  return TOKEN.exec(js)?.[1] ?? null;
}

export function decodeHtmlEntities(text: string): string {
  if (!text.includes("&")) return text;
  return text.replace(ENTITY, (whole, body: string) => {
    let code: number | null = null;
    if (/^#x/i.test(body)) code = parseInt(body.slice(2), 16);
    else if (body.startsWith("#")) code = parseInt(body.slice(1), 10);
    else return NAMED[body.toLowerCase()] ?? whole;
    if (!Number.isFinite(code) || code < 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return whole;
    return String.fromCodePoint(code);
  });
}

function attributes(tag: string): Record<string, string> {
  const found: Record<string, string> = {};
  for (const match of tag.matchAll(ATTR)) found[match[1]] = match[2];
  return found;
}

/** The `<select>` inside the div whose class list holds [boxClass] as a whole token. */
function boxSelect(html: string, boxClass: string): string | null {
  const token = new RegExp(`(?:^|\\s)${escape(boxClass)}(?:\\s|$)`);
  for (const div of html.matchAll(/<div\s+class="([^"]*)"[^>]*>/g)) {
    if (!token.test(div[1])) continue;
    const after = html.slice((div.index ?? 0) + div[0].length);
    const select = /<select>([\s\S]*?)<\/select>/.exec(after);
    if (select !== null) return select[1];
  }
  return null;
}

function parseTranslations(html: string): TranslationOption[] {
  const content = TRANSLATION_BOXES.map((box) => boxSelect(html, box)).find((c) => c !== null);
  if (content === undefined || content === null) return [];
  const result: TranslationOption[] = [];
  for (const match of content.matchAll(OPTION)) {
    const attrs = attributes(match[1]);
    const text = match[2].trim();
    const id = Number(attrs["data-id"]);
    const mediaId = attrs["data-media-id"];
    const mediaHash = attrs["data-media-hash"];
    const kind = attrs["data-translation-type"];
    if (!Number.isInteger(id) || mediaId === undefined || mediaHash === undefined) continue;
    if (kind !== "voice" && kind !== "subtitles") continue;
    const counted = attrs["data-episode-count"] ?? EPISODE_COUNT_IN_TEXT.exec(text)?.[1];
    const episodesCount = counted === undefined || !Number.isInteger(Number(counted)) ? null : Number(counted);
    const title = decodeHtmlEntities(attrs["data-title"] ?? text.replace(TRAILING_EPISODE_COUNT, ""));
    result.push({ id, title, type: kind, episodesCount, mediaId, mediaHash });
  }
  return result;
}

function parseEpisodes(html: string): EpisodeOption[] {
  const content = boxSelect(html, "serial-series-box");
  if (content === null) return [];
  const result: EpisodeOption[] = [];
  for (const match of content.matchAll(OPTION)) {
    const attrs = attributes(match[1]);
    const number = Number(attrs["value"]);
    if (!Number.isInteger(number) || attrs["data-id"] === undefined || attrs["data-hash"] === undefined) continue;
    result.push({ number, mediaId: attrs["data-id"], mediaHash: attrs["data-hash"] });
  }
  return result;
}

function ftorPath(html: string): string {
  for (const match of html.matchAll(ATOB)) {
    try {
      const decoded = atob(match[1]);
      if (decoded.startsWith("/")) return decoded;
    } catch { /* not base64: not the override */ }
  }
  return "/ftor";
}
```

`infra/relay/src/kodik/links.ts`:

```ts
import { KodikError } from "./errors";

/**
 * The `/ftor` answer, decoded the way `KodikLinkDecoder` in shared/ decodes it: each `src` is a
 * base64 URL without padding under an unknown Caesar rotation of its letters, found by trying all
 * twenty-six and keeping the one that decodes to a manifest address.
 */
export function decodeLinks(json: string): Map<number, string> {
  let root: unknown;
  try { root = JSON.parse(json); } catch { throw new KodikError("parser", "links"); }
  const links = (root as { links?: unknown } | null)?.links;
  if (links === null || typeof links !== "object") throw new KodikError("parser", "links");
  const result = new Map<number, string>();
  for (const [quality, entries] of Object.entries(links as Record<string, unknown>)) {
    const q = Number(quality);
    if (!Number.isInteger(q) || !Array.isArray(entries)) continue;
    for (const entry of entries) {
      const src = (entry as { src?: unknown } | null)?.src;
      const url = typeof src === "string" ? decodeSrc(src) : null;
      if (url !== null) { result.set(q, url); break; }
    }
  }
  if (result.size === 0) throw new KodikError("parser", "links");
  return result;
}

export function decodeSrc(encoded: string): string | null {
  for (let n = 0; n < 26; n++) {
    const rotated = rotate(encoded, n);
    const padded = rotated + "=".repeat((4 - (rotated.length % 4)) % 4);
    let decoded: string;
    try {
      const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
      decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch { continue; }
    if (!decoded.includes("manifest")) continue;
    if (decoded.startsWith("//")) return `https:${decoded}`;
    if (decoded.startsWith("https://") || decoded.startsWith("http://")) return decoded;
  }
  return null;
}

function rotate(text: string, n: number): string {
  let out = "";
  for (const c of text) {
    const code = c.charCodeAt(0);
    if (code >= 97 && code <= 122) out += String.fromCharCode(97 + ((code - 97 + n) % 26));
    else if (code >= 65 && code <= 90) out += String.fromCharCode(65 + ((code - 65 + n) % 26));
    else out += c;
  }
  return out;
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `npx vitest run test/kodik-parse.test.ts test/kodik-links.test.ts && npx tsc --noEmit`
Expected: all PASS, no type errors. If the `?raw` import fails to resolve a path outside the package, add to `vitest.config.ts` `server: { fs: { allow: ["../.."] } }` and rerun.

- [ ] **Step 5: Commit**

```bash
git add infra/relay/src/kodik/errors.ts infra/relay/src/kodik/parse.ts infra/relay/src/kodik/links.ts infra/relay/test/kodik-parse.test.ts infra/relay/test/kodik-links.test.ts infra/relay/types/env.d.ts infra/relay/vitest.config.ts
git commit -m "feat(relay): разбор страниц Kodik и расшифровка ссылок на TypeScript"
```

---

### Task 2: Клиент Kodik — цепочка запросов и кэши

**Files:**
- Create: `infra/relay/src/kodik/client.ts`
- Test: `infra/relay/test/kodik-client.test.ts`

**Interfaces:**
- Consumes: `parsePlayerPage`, `extractPublicToken`, `decodeLinks`, `KodikError`, `TranslationOption` (task 1).
- Produces:
  - `interface KodikStream { urls: { quality: number; url: string }[]; translationId: number; episode: number; season: number }`
  - `class KodikClient { constructor(opts: { fetch: typeof fetch; now?: () => number; configuredToken?: string }); translations(animeId: number): Promise<TranslationOption[]>; resolve(animeId: number, translationId: number, episode: number, season?: number): Promise<KodikStream> }`
  - `const PLAYER_HOST = "https://kodikplayer.com"`, `const BROWSER_UA` (exported for tests).

- [ ] **Step 1: Write the failing tests**

`infra/relay/test/kodik-client.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import player from "../../../android/src/test/resources/kodik/player.html?raw";
import single from "../../../android/src/test/resources/kodik/movie-single-track.html?raw";
import addPlayers from "../../../android/src/test/resources/kodik/add-players.js?raw";
import links from "../../../android/src/test/resources/kodik/links.json?raw";
import { KodikClient } from "../src/kodik/client";
import { KodikError } from "../src/kodik/errors";

interface Seen { url: string; method: string; body: string }

/** A Kodik that answers from the fixtures, recording what it was asked. */
function fakeKodik(overrides: { getPlayer?: (n: number) => Response; page?: string } = {}) {
  const seen: Seen[] = [];
  let getPlayerCalls = 0;
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const body = typeof init?.body === "string" ? init.body : init?.body instanceof URLSearchParams ? init.body.toString() : "";
    seen.push({ url, method: init?.method ?? "GET", body });
    if (url.startsWith("https://kodik-add.com/")) return new Response(addPlayers);
    if (url === "https://kodik-api.com/get-player") {
      getPlayerCalls += 1;
      if (overrides.getPlayer) return overrides.getPlayer(getPlayerCalls);
      return Response.json({ found: true, link: "//kodikplayer.com/serial/55917/d1d44d5cd59af5af897ce899a776dacf/720p" });
    }
    if (url.includes("/ftor")) return new Response(links);
    if (url.startsWith("https://kodikplayer.com/")) return new Response(overrides.page ?? player);
    return new Response("unexpected", { status: 599 });
  }) as typeof globalThis.fetch;
  return { fetch, seen };
}

describe("KodikClient", () => {
  it("lists the translations of a title", async () => {
    const kodik = fakeKodik();
    const list = await new KodikClient({ fetch: kodik.fetch }).translations(1535);
    expect(list).toHaveLength(33);
    expect(list[0].id).toBe(3560);
  });

  it("keeps the catalogue for six hours", async () => {
    const kodik = fakeKodik();
    let now = 0;
    const client = new KodikClient({ fetch: kodik.fetch, now: () => now });
    await client.translations(1535);
    await client.translations(1535);
    expect(kodik.seen.filter((s) => s.url.endsWith("/get-player"))).toHaveLength(1);
    now = 6 * 60 * 60 * 1000;
    await client.translations(1535);
    expect(kodik.seen.filter((s) => s.url.endsWith("/get-player"))).toHaveLength(2);
  });

  it("resolves an episode into signed manifests, posting the page's signing parameters", async () => {
    const kodik = fakeKodik();
    const stream = await new KodikClient({ fetch: kodik.fetch }).resolve(1535, 3560, 1);
    expect(stream.urls.map((u) => u.quality)).toEqual([720, 480, 360]);
    expect(stream.episode).toBe(1);
    const ftor = kodik.seen.find((s) => s.url.includes("/ftor"));
    expect(ftor?.method).toBe("POST");
    const form = new URLSearchParams(ftor?.body);
    expect(form.get("type")).toBe("seria");
    expect(form.get("id")).toBe("1211482");
    expect(form.get("ref")).toBe("https://kodikplayer.com/");
  });

  it("says the episode is missing when the track's page does not list it", async () => {
    const kodik = fakeKodik();
    await expect(new KodikClient({ fetch: kodik.fetch }).resolve(1535, 3560, 99))
      .rejects.toMatchObject({ kind: "episode" });
  });

  it("says the title is missing when Kodik has no player for it", async () => {
    const kodik = fakeKodik({ getPlayer: () => Response.json({ found: false }) });
    await expect(new KodikClient({ fetch: kodik.fetch }).translations(1)).rejects.toMatchObject({ kind: "title" });
  });

  it("re-reads a rejected token once and asks again", async () => {
    const kodik = fakeKodik({
      getPlayer: (n) => n === 1
        ? Response.json({ error: "Отсутствует или неверный токен" })
        : Response.json({ found: true, link: "//kodikplayer.com/serial/55917/d1d44d5cd59af5af897ce899a776dacf/720p" }),
    });
    const list = await new KodikClient({ fetch: kodik.fetch }).translations(1535);
    expect(list).toHaveLength(33);
    expect(kodik.seen.filter((s) => s.url.startsWith("https://kodik-add.com/"))).toHaveLength(2);
  });

  it("gives up with a token error after the second rejection", async () => {
    const kodik = fakeKodik({ getPlayer: () => new Response("", { status: 401 }) });
    await expect(new KodikClient({ fetch: kodik.fetch }).translations(1535)).rejects.toMatchObject({ kind: "token" });
  });

  it("gives a single-voice film the one track its page names", async () => {
    const kodik = fakeKodik({ page: single });
    const list = await new KodikClient({ fetch: kodik.fetch }).translations(1535);
    expect(list).toHaveLength(1);
    expect(list[0].episodesCount).toBe(1);
  });

  it("uses a configured token instead of scraping", async () => {
    const kodik = fakeKodik();
    await new KodikClient({ fetch: kodik.fetch, configuredToken: "abc123" }).translations(1535);
    expect(kodik.seen.some((s) => s.url.startsWith("https://kodik-add.com/"))).toBe(false);
    expect(new URLSearchParams(kodik.seen[0].body).get("token")).toBe("abc123");
  });

  it("is an upstream error when Kodik answers 5xx", async () => {
    const kodik = fakeKodik({ getPlayer: () => new Response("down", { status: 503 }) });
    const failure = new KodikClient({ fetch: kodik.fetch }).translations(1535);
    await expect(failure).rejects.toBeInstanceOf(KodikError);
    await expect(failure).rejects.toMatchObject({ kind: "upstream" });
  });
});
```

- [ ] **Step 2: Run to see it fail**

Run: `npx vitest run test/kodik-client.test.ts`
Expected: FAIL — cannot resolve `../src/kodik/client`.

- [ ] **Step 3: Implement**

`infra/relay/src/kodik/client.ts`:

```ts
import { KodikError } from "./errors";
import { decodeLinks } from "./links";
import { extractPublicToken, parsePlayerPage, type PlayerPage, type TranslationOption } from "./parse";

export const PLAYER_HOST = "https://kodikplayer.com";
export const BROWSER_UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36";
const API_URL = "https://kodik-api.com";
const ADD_PLAYERS_URL = "https://kodik-add.com/add-players.min.js?v=2";
const CATALOGUE_TTL_MS = 6 * 60 * 60 * 1000;
const TOKEN_TTL_MS = 24 * 60 * 60 * 1000;
const SOLE_TRACK_TITLE = "Единственная озвучка";

export interface KodikStream {
  urls: { quality: number; url: string }[];
  translationId: number;
  episode: number;
  season: number;
}

interface Catalogue { page: PlayerPage; sourceUrl: string; at: number }

/**
 * The Kodik chain `KodikClient` in shared/ walks, for a browser that cannot walk it itself:
 * `get-player` for a Shikimori id → the player page (tracks and signing) → the track's own page →
 * `POST /ftor` → the signed manifests of one episode.
 *
 * State lives in the isolate: a Worker isolate is reused across requests for a while, so the
 * catalogue and the token are remembered there, and a cold isolate simply asks again.
 */
export class KodikClient {
  private readonly fetch: typeof fetch;
  private readonly now: () => number;
  private readonly configuredToken: string | undefined;
  private token: { value: string; at: number } | null = null;
  private readonly catalogues = new Map<number, Catalogue>();

  constructor(options: { fetch: typeof fetch; now?: () => number; configuredToken?: string }) {
    this.fetch = options.fetch;
    this.now = options.now ?? Date.now;
    this.configuredToken = options.configuredToken?.trim() || undefined;
  }

  async translations(animeId: number): Promise<TranslationOption[]> {
    return (await this.catalogue(animeId)).page.translations;
  }

  async resolve(animeId: number, translationId: number, episode: number, season = 1): Promise<KodikStream> {
    const catalogue = await this.catalogue(animeId);
    const tracks = catalogue.page.translations;
    const chosen = translationId === 0 ? tracks[0] : tracks.find((t) => t.id === translationId);
    if (chosen === undefined) throw new KodikError("episode");
    const serial = catalogue.page.currentType === "seria";
    const type = serial ? "serial" : catalogue.page.currentType;
    const url = playerUrl(`/${type}/${chosen.mediaId}/${chosen.mediaHash}/720p`, serial ? season : null, serial ? episode : null);
    const page = parsePlayerPage(await this.text(url, catalogue.sourceUrl));
    const wanted = page.episodes.find((e) => e.number === episode);
    if (page.episodes.length > 0 && wanted === undefined) throw new KodikError("episode");
    const form = new URLSearchParams({
      d: page.domain, d_sign: page.dSign, pd: page.pd, pd_sign: page.pdSign,
      ref: page.ref, ref_sign: page.refSign,
      type: wanted !== undefined ? "seria" : page.currentType,
      hash: wanted?.mediaHash ?? page.currentHash,
      id: wanted?.mediaId ?? page.currentId,
      bad_user: "false", cdn_is_working: "true",
    });
    const response = await this.request(playerUrl(page.ftorPath, null, null), {
      method: "POST",
      headers: {
        "user-agent": BROWSER_UA, referer: url, origin: PLAYER_HOST,
        "x-requested-with": "XMLHttpRequest",
        accept: "application/json, text/javascript, */*; q=0.01",
        "content-type": "application/x-www-form-urlencoded; charset=UTF-8",
      },
      body: form.toString(),
    });
    const decoded = decodeLinks(await response.text());
    const urls = [...decoded.entries()].sort((a, b) => b[0] - a[0]).map(([quality, link]) => ({ quality, url: link }));
    return { urls, translationId: chosen.id, episode: page.episodes.length === 0 ? 1 : episode, season };
  }

  private async catalogue(animeId: number): Promise<Catalogue> {
    const held = this.catalogues.get(animeId);
    const age = held === undefined ? -1 : this.now() - held.at;
    if (held !== undefined && age >= 0 && age < CATALOGUE_TTL_MS) return held;
    this.catalogues.delete(animeId);
    const answer = await this.getPlayer(animeId);
    const link = typeof answer.link === "string" ? answer.link : "";
    if (answer.found !== true || link === "") throw new KodikError("title");
    const sourceUrl = playerUrl(link, null, null);
    const page = withSoleTrack(parsePlayerPage(await this.text(sourceUrl, `${PLAYER_HOST}/`)));
    const fresh = { page, sourceUrl, at: this.now() };
    this.catalogues.set(animeId, fresh);
    return fresh;
  }

  private async getPlayer(animeId: number): Promise<Record<string, unknown>> {
    for (let attempt = 0; attempt < 2; attempt++) {
      const token = await this.currentToken();
      const response = await this.fetch(`${API_URL}/get-player`, {
        method: "POST",
        headers: { "user-agent": BROWSER_UA, referer: `${PLAYER_HOST}/`, "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ token, shikimoriID: String(animeId), types: "anime,anime-serial" }).toString(),
      });
      if (response.status === 401) { this.rejectToken(); continue; }
      if (!response.ok) throw new KodikError("upstream", "get-player");
      let answer: Record<string, unknown>;
      try { answer = (await response.json()) as Record<string, unknown>; } catch { throw new KodikError("parser", "get-player"); }
      const error = typeof answer.error === "string" ? answer.error.toLowerCase() : "";
      if (error.includes("токен") || error.includes("token")) { this.rejectToken(); continue; }
      return answer;
    }
    throw new KodikError("token");
  }

  private async currentToken(): Promise<string> {
    if (this.configuredToken !== undefined) return this.configuredToken;
    const held = this.token;
    if (held !== null && this.now() - held.at >= 0 && this.now() - held.at < TOKEN_TTL_MS) return held.value;
    const response = await this.fetch(ADD_PLAYERS_URL, { headers: { "user-agent": BROWSER_UA, referer: `${PLAYER_HOST}/` } });
    if (!response.ok) throw new KodikError("token");
    const scraped = extractPublicToken(await response.text());
    if (scraped === null) throw new KodikError("token");
    this.token = { value: scraped, at: this.now() };
    return scraped;
  }

  private rejectToken(): void {
    if (this.configuredToken !== undefined) throw new KodikError("token");
    this.token = null;
  }

  private async text(url: string, referer: string): Promise<string> {
    const response = await this.request(url, {
      headers: { "user-agent": BROWSER_UA, referer, accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" },
    });
    return response.text();
  }

  private async request(url: string, init: RequestInit): Promise<Response> {
    let response: Response;
    try { response = await this.fetch(url, init); } catch { throw new KodikError("upstream", new URL(url).pathname); }
    if (!response.ok) throw new KodikError("upstream", new URL(url).pathname);
    return response;
  }
}

/** Any link Kodik gives, pinned to the player host over https, with season and episode set. */
function playerUrl(link: string, season: number | null, episode: number | null): string {
  let absolute: string;
  if (link.startsWith("//")) absolute = `https:${link}`;
  else if (link.startsWith("https://") || link.startsWith("http://")) absolute = link;
  else if (link.startsWith("/")) absolute = PLAYER_HOST + link;
  else throw new KodikError("parser", "player-link");
  let url: URL;
  try { url = new URL(absolute); } catch { throw new KodikError("parser", "player-link"); }
  url.protocol = "https:";
  url.host = "kodikplayer.com";
  url.username = "";
  url.password = "";
  if (season !== null) url.searchParams.set("season", String(season));
  if (episode !== null) url.searchParams.set("episode", String(episode));
  return url.toString();
}

/** A film with one voice has no chooser; its page still names the voice it plays. */
function withSoleTrack(page: PlayerPage): PlayerPage {
  if (page.translations.length > 0) return page;
  const fallbackId = -(Number(page.currentId) > 0 ? Number(page.currentId) : 1);
  return {
    ...page,
    translations: [{
      id: page.currentTranslationId ?? fallbackId,
      title: page.currentTranslationTitle ?? SOLE_TRACK_TITLE,
      type: "voice", episodesCount: 1, mediaId: page.currentId, mediaHash: page.currentHash,
    }],
  };
}
```

- [ ] **Step 4: Run to see it pass**

Run: `npx vitest run test/kodik-client.test.ts && npx tsc --noEmit`
Expected: PASS. If the `movie-single-track.html` expectation fails because that fixture does carry a chooser, change the test's expectation to the Kotlin `KodikClientTest` expectation for the same fixture (`grep -n "single" ../../shared/src/commonTest/kotlin/app/kaeru/shared/data/kodik/KodikClientTest.kt`) — the Kotlin behaviour is the reference.

- [ ] **Step 5: Commit**

```bash
git add infra/relay/src/kodik/client.ts infra/relay/test/kodik-client.test.ts
git commit -m "feat(relay): клиент Kodik с кэшем каталога и токена"
```

---

### Task 3: Маршруты `/kodik/*` и CORS для сайта (без белого списка)

**Files:**
- Create: `infra/relay/src/web.ts`, `infra/relay/src/kodik/routes.ts`
- Modify: `infra/relay/src/index.ts` (маршрутизация), `infra/relay/types/env.d.ts` (`KODIK_TOKEN?`)
- Test: `infra/relay/test/kodik-routes.test.ts`

**Interfaces:**
- Consumes: `KodikClient`, `KodikError` (tasks 1–2).
- Produces:
  - `web.ts`: `WEB_ORIGINS: readonly string[]` = `["https://kaeru.vitaliy.velikodniy.name", "http://localhost:5173"]`; `webOrigin(request: Request): string | null`; `preflight(request: Request): Response | null`; `withCors(response: Response, origin: string | null): Response`.
  - `routes.ts`: `handleKodik(request: Request, client: KodikClient): Promise<Response>` — `GET /kodik/translations?anime=`, `GET /kodik/resolve?anime=&translation=&episode=&season=`; ошибки `{"error": "<kind>", "step"?: string}`: `title`/`episode` → 404, `token`/`upstream`/`parser` → 502, неверные параметры → 400.
  - `index.ts` держит один `KodikClient` на изолят: `let kodik: KodikClient | undefined` и создаёт его с `fetch` и `env.KODIK_TOKEN`.

- [ ] **Step 1: Write the failing tests**

`infra/relay/test/kodik-routes.test.ts`:

```ts
import { SELF } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import player from "../../../android/src/test/resources/kodik/player.html?raw";
import addPlayers from "../../../android/src/test/resources/kodik/add-players.js?raw";
import links from "../../../android/src/test/resources/kodik/links.json?raw";

const SITE = "https://kaeru.vitaliy.velikodniy.name";
const realFetch = globalThis.fetch;

beforeEach(() => {
  // The worker runs in this isolate, so a stubbed global fetch is the Kodik it talks to.
  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    if (url.startsWith("https://kodik-add.com/")) return new Response(addPlayers);
    if (url === "https://kodik-api.com/get-player") {
      return Response.json({ found: true, link: "//kodikplayer.com/serial/55917/d1d44d5cd59af5af897ce899a776dacf/720p" });
    }
    if (url.includes("/ftor")) return new Response(links);
    if (url.startsWith("https://kodikplayer.com/")) return new Response(player);
    return realFetch(input, init);
  });
});
afterEach(() => vi.unstubAllGlobals());

let ip = 0;
function get(path: string, origin: string | null = SITE): Promise<Response> {
  ip += 1;
  const headers: Record<string, string> = { "CF-Connecting-IP": `198.51.100.${ip}` };
  if (origin !== null) headers.Origin = origin;
  return SELF.fetch(`https://relay.test${path}`, { headers });
}

describe("/kodik/*", () => {
  it("lists translations with CORS for the site", async () => {
    const response = await get("/kodik/translations?anime=1535");
    expect(response.status).toBe(200);
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
    const body = (await response.json()) as { translations: { id: number }[] };
    expect(body.translations[0].id).toBe(3560);
  });

  it("resolves an episode", async () => {
    const response = await get("/kodik/resolve?anime=1535&translation=3560&episode=1");
    expect(response.status).toBe(200);
    const body = (await response.json()) as { urls: { quality: number; url: string }[]; episode: number };
    expect(body.urls[0].quality).toBe(720);
    expect(body.episode).toBe(1);
  });

  it("answers 404 with the kind for a missing episode", async () => {
    const response = await get("/kodik/resolve?anime=1535&translation=3560&episode=99");
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "episode" });
  });

  it("answers 400 for parameters that are not positive integers", async () => {
    expect((await get("/kodik/resolve?anime=abc&translation=1&episode=1")).status).toBe(400);
    expect((await get("/kodik/translations")).status).toBe(400);
    expect((await get("/kodik/resolve?anime=1&translation=1&episode=0")).status).toBe(400);
  });

  it("gives no CORS to an origin that is not the site", async () => {
    const response = await get("/kodik/translations?anime=1535", "https://evil.example");
    expect(response.headers.get("access-control-allow-origin")).toBeNull();
  });

  it("answers a browser preflight with 204 and CORS, before anything else", async () => {
    const response = await SELF.fetch("https://relay.test/kodik/resolve?anime=1", {
      method: "OPTIONS",
      headers: { Origin: SITE, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization" },
    });
    expect(response.status).toBe(204);
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
    expect(response.headers.get("access-control-allow-headers")?.toLowerCase()).toContain("authorization");
  });

  it("refuses other methods", async () => {
    const response = await SELF.fetch("https://relay.test/kodik/translations?anime=1535", { method: "POST", headers: { Origin: SITE } });
    expect(response.status).toBe(405);
  });
});
```

- [ ] **Step 2: Run to see it fail**

Run: `npx vitest run test/kodik-routes.test.ts`
Expected: FAIL — 404 from the worker for `/kodik/translations`.

- [ ] **Step 3: Implement**

`infra/relay/src/web.ts`:

```ts
/**
 * The site that may call this Worker from a browser, and the dev server beside it. Everything
 * else — the apps, curl — sends no Origin, or one that is not here, and gets no CORS: the browser
 * then refuses to hand the answer to a page, which is the whole of what CORS is for.
 */
export const WEB_ORIGINS: readonly string[] = [
  "https://kaeru.vitaliy.velikodniy.name",
  "http://localhost:5173",
];

export function webOrigin(request: Request): string | null {
  const origin = request.headers.get("Origin");
  return origin !== null && WEB_ORIGINS.includes(origin) ? origin : null;
}

/** A browser's preflight for one of the site's calls, answered before any check that needs the call itself. */
export function preflight(request: Request): Response | null {
  if (request.method !== "OPTIONS") return null;
  const origin = webOrigin(request);
  if (origin === null) return new Response(null, { status: 403 });
  return new Response(null, {
    status: 204,
    headers: {
      "access-control-allow-origin": origin,
      "access-control-allow-methods": "GET, POST, OPTIONS",
      "access-control-allow-headers": "authorization, content-type",
      "access-control-max-age": "7200",
      vary: "Origin",
    },
  });
}

export function withCors(response: Response, origin: string | null): Response {
  if (origin === null) return response;
  const copy = new Response(response.body, response);
  copy.headers.set("access-control-allow-origin", origin);
  copy.headers.append("vary", "Origin");
  return copy;
}
```

`infra/relay/src/kodik/routes.ts`:

```ts
import type { KodikClient } from "./client";
import { KodikError } from "./errors";

const STATUS: Record<KodikError["kind"], number> = { title: 404, episode: 404, token: 502, upstream: 502, parser: 502 };

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

/** A query value that must be a positive integer, or null. */
function positive(url: URL, name: string): number | null {
  const raw = url.searchParams.get(name);
  if (raw === null || !/^\d{1,9}$/.test(raw)) return null;
  const value = Number(raw);
  return value > 0 ? value : null;
}

/** `/kodik/translations` and `/kodik/resolve`: what the browser's player needs, and nothing more. */
export async function handleKodik(request: Request, client: KodikClient): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method" }, 405);
  const url = new URL(request.url);
  try {
    if (url.pathname === "/kodik/translations") {
      const anime = positive(url, "anime");
      if (anime === null) return json({ error: "parameters" }, 400);
      return json({ translations: await client.translations(anime) });
    }
    if (url.pathname === "/kodik/resolve") {
      const anime = positive(url, "anime");
      const episode = positive(url, "episode");
      const translationRaw = url.searchParams.get("translation") ?? "0";
      const translation = /^-?\d{1,9}$/.test(translationRaw) ? Number(translationRaw) : null;
      const season = url.searchParams.has("season") ? positive(url, "season") : 1;
      if (anime === null || episode === null || translation === null || season === null) {
        return json({ error: "parameters" }, 400);
      }
      return json(await client.resolve(anime, translation, episode, season));
    }
    return json({ error: "not_found" }, 404);
  } catch (error) {
    if (error instanceof KodikError) {
      console.log(`kodik ${error.kind}${error.step === undefined ? "" : ` ${error.step}`}`);
      return json(error.step === undefined || error.kind === "title" || error.kind === "episode"
        ? { error: error.kind } : { error: error.kind, step: error.step }, STATUS[error.kind]);
    }
    console.log("kodik unexpected");
    return json({ error: "upstream" }, 502);
  }
}
```

In `infra/relay/types/env.d.ts`, inside `RelayEnv`, add:

```ts
  /** A Kodik API token of our own, when there is one; otherwise the public one is scraped. */
  KODIK_TOKEN?: string;
```

In `infra/relay/src/index.ts` add imports at the top (after the header comment):

```ts
import { KodikClient } from "./kodik/client";
import { handleKodik } from "./kodik/routes";
import { preflight, webOrigin, withCors } from "./web";

/** One per isolate: the catalogue and the token it remembers are worth keeping between requests. */
let kodik: KodikClient | undefined;
```

and in `fetch`, right after the `/health` block:

```ts
    if (url.pathname.startsWith("/kodik/")) {
      const early = preflight(request);
      if (early !== null) return early;
      const retryAfter = await rateLimit(request, env, "kodik");
      if (retryAfter > 0) return withCors(tooManyRequests(retryAfter), webOrigin(request));
      kodik ??= new KodikClient({ fetch: (input, init) => fetch(input, init), configuredToken: env.KODIK_TOKEN });
      return withCors(await handleKodik(request, kodik), webOrigin(request));
    }
```

Update the routes list in the header comment of `index.ts`:

```
 *   GET  /kodik/translations?anime=          the Kodik tracks of a title, for the web client
 *   GET  /kodik/resolve?anime=&translation=&episode=&season=   signed HLS links of one episode
```

- [ ] **Step 4: Run all worker tests**

Run: `npm test && npx tsc --noEmit`
Expected: PASS, including the existing `oauth.test.ts` and `room.test.ts` unchanged.

- [ ] **Step 5: Commit**

```bash
git add infra/relay/src/web.ts infra/relay/src/kodik/routes.ts infra/relay/src/index.ts infra/relay/types/env.d.ts infra/relay/test/kodik-routes.test.ts
git commit -m "feat(relay): маршруты /kodik/* и CORS для сайта"
```

---

### Task 4: Проба — играет ли браузер ссылку, полученную с IP Cloudflare

Throwaway по своей сути, но страница пробы остаётся в `tools/` — пригодится, когда Kodik что-то поменяет.

**Files:**
- Create: `tools/kodik-probe/index.html`, `tools/kodik-probe/README.md`
- Modify: `docs/superpowers/specs/2026-09-24-kaeru-web-design.md` (§5 — итог пробы)

**Interfaces:**
- Consumes: маршруты задачи 3, запущенные на инфраструктуре Cloudflare через `npx wrangler dev --remote` (открыты, белого списка ещё нет).

- [ ] **Step 1: Write the probe page**

`tools/kodik-probe/index.html`:

```html
<!doctype html>
<meta charset="utf-8">
<title>Kodik probe</title>
<!-- A page served from http://localhost:5173 asks the Worker for links resolved on Cloudflare's IP,
     then plays them in this browser from this machine's IP. It answers one question: are Kodik's
     signed links bound to the address that resolved them? -->
<script src="https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.20/hls.min.js"></script>
<video id="v" controls muted width="640"></video>
<pre id="log"></pre>
<script>
  const log = (line) => { document.getElementById("log").textContent += line + "\n"; console.log(line); };
  const worker = new URLSearchParams(location.search).get("worker");
  (async () => {
    const answer = await fetch(`${worker}/kodik/resolve?anime=1535&translation=0&episode=1`);
    log(`resolve ${answer.status}`);
    const { urls } = await answer.json();
    const manifest = urls[urls.length - 1].url;
    log(`manifest host ${new URL(manifest).host}`);
    const video = document.getElementById("v");
    const hls = new Hls();
    hls.on(Hls.Events.MANIFEST_PARSED, () => log("manifest parsed"));
    hls.on(Hls.Events.FRAG_LOADED, (_, d) => log(`fragment ${d.frag.sn} loaded`));
    hls.on(Hls.Events.ERROR, (_, d) => log(`error ${d.type} ${d.details} ${d.response?.code ?? ""}`));
    hls.loadSource(manifest);
    hls.attachMedia(video);
    video.addEventListener("playing", () => log("PLAYING"));
    video.play().catch((e) => log(`play() ${e.name}`));
    setTimeout(() => { video.currentTime = 600; log("seek 600"); }, 8000);
    setTimeout(() => log(`time ${video.currentTime.toFixed(1)}`), 14000);
  })().catch((e) => log(`failed ${e}`));
</script>
```

`tools/kodik-probe/README.md`:

```markdown
# Kodik probe

Answers one question: does a browser play a Kodik link that the Worker resolved on Cloudflare's IP?

    cd infra/relay && npx wrangler dev --remote --port 8787     # routes run on Cloudflare's network
    cd tools/kodik-probe && python3 -m http.server 5173
    open "http://localhost:5173/index.html?worker=http://localhost:8787"

PLAYING, fragments loaded and a time past 600 after the seek: links are not bound to the resolving
address — the browser plays Kodik's CDN directly. An HTTP 403/410 on the manifest or the first
fragment: they are bound, and video has to go through the Worker (spec §5).
```

- [ ] **Step 2: Run the probe**

Run in three terminals (or background jobs):

```bash
cd infra/relay && npx wrangler dev --remote --port 8787
cd tools/kodik-probe && python3 -m http.server 5173
```

Open `http://localhost:5173/index.html?worker=http://localhost:8787` in Chrome (Chrome DevTools MCP `new_page`, then read the page's `#log` with `evaluate_script`). Also run from the shell, to see the raw CDN answers:

```bash
M=$(curl -s "http://localhost:8787/kodik/resolve?anime=1535&translation=0&episode=1" -H "Origin: http://localhost:5173" | python3 -c "import json,sys; print(json.load(sys.stdin)['urls'][-1]['url'])")
curl -s -D - -o /tmp/probe.m3u8 -H "Origin: http://localhost:5173" "$M" | grep -i -E "^HTTP|access-control"
S=$(grep -v '^#' /tmp/probe.m3u8 | head -1); case "$S" in http*) ;; *) S="$(dirname "$M")/$S";; esac
curl -s -D - -o /dev/null -H "Origin: http://localhost:5173" "$S" | grep -i -E "^HTTP|access-control"
```

Expected — one of two outcomes:
- **Plays:** log shows `PLAYING`, several `fragment N loaded`, `time` above 600; curl shows `200` and an `access-control-allow-origin` on both.
- **Bound to IP:** `403`/`410`/`404` on manifest or fragment, `error networkError manifestLoadError|fragLoadError`.

- [ ] **Step 3: Record the result in the spec**

Append to §5 of `docs/superpowers/specs/2026-09-24-kaeru-web-design.md` a paragraph `**Итог пробы (дата).**` with: the outcome, the HTTP status and CORS header of manifest and fragment, whether seek worked, the manifest host. If the outcome is «bound to IP» — **stop here**, report to the user with the numbers and the §5 choice (paid plan / other proxy / drop); tasks 5–6 wait for the answer.

- [ ] **Step 4: Commit**

```bash
git add tools/kodik-probe docs/superpowers/specs/2026-09-24-kaeru-web-design.md
git commit -m "docs(web): итог пробы — играет ли браузер ссылки Kodik с IP Cloudflare"
```

---

### Task 5: Белый список

**Files:**
- Create: `infra/relay/src/whitelist.ts`
- Modify: `infra/relay/src/index.ts` (`/kodik/*` за проверкой; `proxyToken`: веб-ветка), `infra/relay/types/env.d.ts` (`WEB_ALLOWED_SHIKIMORI_IDS?`), `infra/relay/vitest.config.ts` (привязка списка)
- Test: `infra/relay/test/whitelist.test.ts`, additions to `infra/relay/test/oauth.test.ts`, `infra/relay/test/kodik-routes.test.ts`

**Interfaces:**
- Consumes: `webOrigin`, `withCors`, `preflight` (task 3).
- Produces:
  - `parseAllowed(raw: string | undefined): Set<number>`
  - `interface Viewer { id: number; nickname: string }`
  - `whoami(token: string, fetcher: typeof fetch, now: () => number): Promise<Viewer | null | "unavailable">` — `null` for a token Shikimori does not accept, `"unavailable"` when Shikimori cannot be asked; cached 10 min by SHA-256 of the token.
  - `verdict(viewer: Viewer | null | "unavailable", allowed: Set<number>): Response | null`
  - `fromSite(request: Request): boolean`
  - `gate(request: Request, env: Cloudflare.Env): Promise<Response | null>` — `null` lets the request through; otherwise `401 {"error":"sign_in"}`, `403 {"error":"not_allowed","nickname":…}`, `502 {"error":"unavailable"}`.
  - `WEB_REDIRECTS = ["https://kaeru.vitaliy.velikodniy.name/auth", "http://localhost:5173/auth"]`.

- [ ] **Step 1: Write the failing tests**

`infra/relay/test/whitelist.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { parseAllowed, whoami } from "../src/whitelist";

describe("parseAllowed", () => {
  it("reads ids separated by commas and spaces", () => {
    expect([...parseAllowed(" 1, 22 ,333 ")]).toEqual([1, 22, 333]);
  });
  it("is empty for nothing, and ignores what is not an id", () => {
    expect(parseAllowed(undefined).size).toBe(0);
    expect(parseAllowed("").size).toBe(0);
    expect([...parseAllowed("5, abc, -3, 7")]).toEqual([5, 7]);
  });
});

describe("whoami", () => {
  const answering = (status: number, body: unknown) => {
    let calls = 0;
    const fetcher = (async () => { calls += 1; return Response.json(body, { status }); }) as typeof fetch;
    return { fetcher, calls: () => calls };
  };

  it("names the owner of a token", async () => {
    const shikimori = answering(200, { id: 42, nickname: "vitaliy" });
    expect(await whoami("token-a", shikimori.fetcher, () => 0)).toEqual({ id: 42, nickname: "vitaliy" });
  });

  it("remembers the answer for ten minutes", async () => {
    const shikimori = answering(200, { id: 42, nickname: "vitaliy" });
    let now = 1_000_000;
    await whoami("token-b", shikimori.fetcher, () => now);
    now += 9 * 60 * 1000;
    await whoami("token-b", shikimori.fetcher, () => now);
    expect(shikimori.calls()).toBe(1);
    now += 2 * 60 * 1000;
    await whoami("token-b", shikimori.fetcher, () => now);
    expect(shikimori.calls()).toBe(2);
  });

  it("is null for a token Shikimori refuses", async () => {
    expect(await whoami("token-c", answering(401, {}).fetcher, () => 0)).toBeNull();
  });

  it("is unavailable when Shikimori cannot be asked", async () => {
    const broken = (async () => { throw new Error("down"); }) as typeof fetch;
    expect(await whoami("token-d", broken, () => 0)).toBe("unavailable");
    expect(await whoami("token-e", answering(503, {}).fetcher, () => 0)).toBe("unavailable");
  });
});
```

Append to `infra/relay/test/kodik-routes.test.ts` (the stub in `beforeEach` gains Shikimori's `whoami`: token `allowed-token` → id 42, `stranger-token` → id 7, anything else → 401; and `vitest.config.ts` binds `WEB_ALLOWED_SHIKIMORI_IDS: "42, 100"`):

```ts
// Add inside the beforeEach stub, before the final realFetch line:
//   if (url === "https://shikimori.io/api/users/whoami") {
//     const auth = new Headers(init?.headers).get("authorization");
//     if (auth === "Bearer allowed-token") return Response.json({ id: 42, nickname: "vitaliy" });
//     if (auth === "Bearer stranger-token") return Response.json({ id: 7, nickname: "stranger" });
//     return new Response("", { status: 401 });
//   }
// and change get() to send `Authorization: Bearer allowed-token` unless told otherwise:
//   function get(path: string, origin: string | null = SITE, token: string | null = "allowed-token")
//   ... if (token !== null) headers.Authorization = `Bearer ${token}`;

describe("/kodik/* behind the whitelist", () => {
  it("asks to sign in without a token", async () => {
    const response = await get("/kodik/translations?anime=1535", SITE, null);
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: "sign_in" });
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
  });

  it("closes the door on an account that is not listed, naming it", async () => {
    const response = await get("/kodik/translations?anime=1535", SITE, "stranger-token");
    expect(response.status).toBe(403);
    expect(await response.json()).toEqual({ error: "not_allowed", nickname: "stranger" });
  });

  it("asks to sign in again for a token Shikimori refuses", async () => {
    expect((await get("/kodik/translations?anime=1535", SITE, "expired-token")).status).toBe(401);
  });
});
```

Append to `infra/relay/test/oauth.test.ts` (reusing its `calls`/`reply` stub; `reply` must also answer `https://shikimori.io/api/users/whoami` — token `web-access-allowed` → id 42, `web-access-stranger` → id 7):

```ts
describe("from the site", () => {
  const SITE = "https://kaeru.vitaliy.velikodniy.name";

  function webToken(form: Record<string, string>): Promise<Response> {
    const body = new URLSearchParams(form).toString();
    return SELF.fetch(`${ORIGIN}/oauth/token`, {
      method: "POST",
      headers: {
        Origin: SITE,
        "content-type": "application/x-www-form-urlencoded",
        "content-length": String(new TextEncoder().encode(body).byteLength),
        "CF-Connecting-IP": freshIp(),
      },
      body,
    });
  }

  it("accepts the site's redirect and hands a listed account its token, with CORS", async () => {
    reply = (call) => call.url.endsWith("/whoami")
      ? json(200, { id: 42, nickname: "vitaliy" })
      : json(200, { access_token: "web-access-allowed", refresh_token: "r" });
    const response = await webToken({ grant_type: "authorization_code", client_id: CLIENT_ID, code: "c", redirect_uri: `${SITE}/auth` });
    expect(response.status).toBe(200);
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
    expect(((await response.json()) as { access_token: string }).access_token).toBe("web-access-allowed");
  });

  it("keeps the token from an account that is not listed", async () => {
    reply = (call) => call.url.endsWith("/whoami")
      ? json(200, { id: 7, nickname: "stranger" })
      : json(200, { access_token: "web-access-stranger", refresh_token: "r" });
    const response = await webToken({ grant_type: "authorization_code", client_id: CLIENT_ID, code: "c", redirect_uri: `${SITE}/auth` });
    expect(response.status).toBe(403);
    const body = await response.text();
    expect(JSON.parse(body)).toEqual({ error: "not_allowed", nickname: "stranger" });
    expect(body).not.toContain("web-access-stranger");
  });

  it("checks a refresh from the site too — no redirect to tell it apart, only the Origin", async () => {
    reply = (call) => call.url.endsWith("/whoami")
      ? json(200, { id: 7, nickname: "stranger" })
      : json(200, { access_token: "web-access-stranger", refresh_token: "r2" });
    const response = await webToken({ grant_type: "refresh_token", client_id: CLIENT_ID, refresh_token: "r" });
    expect(response.status).toBe(403);
  });

  it("answers 502, not 403, when Shikimori cannot say who the token belongs to", async () => {
    reply = (call) => call.url.endsWith("/whoami")
      ? new Response("", { status: 503 })
      : json(200, { access_token: "web-access-allowed", refresh_token: "r" });
    const response = await webToken({ grant_type: "authorization_code", client_id: CLIENT_ID, code: "c", redirect_uri: `${SITE}/auth` });
    expect(response.status).toBe(502);
    expect(await response.json()).toEqual({ error: "unavailable" });
  });

  it("refuses the site's redirect from anything that is not the site", async () => {
    const body = new URLSearchParams({ grant_type: "authorization_code", client_id: CLIENT_ID, code: "c", redirect_uri: `${SITE}/auth` }).toString();
    const response = await SELF.fetch(`${ORIGIN}/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", "content-length": String(body.length), "CF-Connecting-IP": freshIp() },
      body,
    });
    expect(response.status).toBe(400);
  });

  it("answers the site's preflight", async () => {
    const response = await SELF.fetch(`${ORIGIN}/oauth/token`, {
      method: "OPTIONS",
      headers: { Origin: SITE, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type" },
    });
    expect(response.status).toBe(204);
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
  });
});
```

In `infra/relay/vitest.config.ts`, add to `miniflare.bindings`: `WEB_ALLOWED_SHIKIMORI_IDS: "42, 100",`.

- [ ] **Step 2: Run to see them fail**

Run: `npx vitest run test/whitelist.test.ts test/kodik-routes.test.ts test/oauth.test.ts`
Expected: FAIL — `../src/whitelist` missing; routes answer 200 without a token; site redirect refused.

- [ ] **Step 3: Implement**

`infra/relay/src/whitelist.ts`:

```ts
import { webOrigin } from "./web";

/**
 * Who may use the web client: Shikimori user ids in the `WEB_ALLOWED_SHIKIMORI_IDS` secret.
 *
 * Checked here and not in the browser, because a check in the browser is one line of the console
 * away from not being there. What is worth guarding — Kodik resolved through this Worker, and a
 * Shikimori token handed to a page — never leaves this Worker for an account not on the list.
 * An empty or missing list closes the web client to everyone rather than opening it.
 *
 * The apps are not affected: they resolve Kodik themselves, and their token requests carry no
 * Origin of the site.
 */
export const WEB_REDIRECTS: readonly string[] = [
  "https://kaeru.vitaliy.velikodniy.name/auth",
  "http://localhost:5173/auth",
];

const WHOAMI_URL = "https://shikimori.io/api/users/whoami";
const WHOAMI_TTL_MS = 10 * 60 * 1000;
const CACHE_LIMIT = 500;

export interface Viewer { id: number; nickname: string }

const cache = new Map<string, { viewer: Viewer | null; at: number }>();

export function parseAllowed(raw: string | undefined): Set<number> {
  const ids = new Set<number>();
  for (const part of (raw ?? "").split(/[\s,]+/)) {
    if (/^\d{1,12}$/.test(part)) ids.add(Number(part));
  }
  return ids;
}

async function keyOf(token: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function whoami(token: string, fetcher: typeof fetch, now: () => number): Promise<Viewer | null | "unavailable"> {
  const key = await keyOf(token);
  const held = cache.get(key);
  if (held !== undefined && now() - held.at >= 0 && now() - held.at < WHOAMI_TTL_MS) return held.viewer;
  let response: Response;
  try {
    response = await fetcher(WHOAMI_URL, { headers: { authorization: `Bearer ${token}`, "user-agent": "Kaeru", accept: "application/json" } });
  } catch {
    return "unavailable";
  }
  let viewer: Viewer | null;
  if (response.status === 401 || response.status === 403) viewer = null;
  else if (!response.ok) return "unavailable";
  else {
    const body = (await response.json().catch(() => null)) as { id?: unknown; nickname?: unknown } | null;
    if (typeof body?.id !== "number") return "unavailable";
    viewer = { id: body.id, nickname: typeof body.nickname === "string" ? body.nickname : "" };
  }
  if (cache.size >= CACHE_LIMIT) cache.delete(cache.keys().next().value as string);
  cache.set(key, { viewer, at: now() });
  return viewer;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

/** The verdict on one owner of a token, or null when they may pass. */
export function verdict(viewer: Viewer | null | "unavailable", allowed: Set<number>): Response | null {
  if (viewer === "unavailable") return json({ error: "unavailable" }, 502);
  if (viewer === null) return json({ error: "sign_in" }, 401);
  if (!allowed.has(viewer.id)) return json({ error: "not_allowed", nickname: viewer.nickname }, 403);
  return null;
}

/** For `/kodik/*`: the bearer token's owner must be on the list. */
export async function gate(request: Request, env: Cloudflare.Env): Promise<Response | null> {
  const header = request.headers.get("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (token === "") return json({ error: "sign_in" }, 401);
  return verdict(await whoami(token, (input, init) => fetch(input, init), Date.now), parseAllowed(env.WEB_ALLOWED_SHIKIMORI_IDS));
}

export function fromSite(request: Request): boolean {
  return webOrigin(request) !== null;
}
```

In `infra/relay/types/env.d.ts`, inside `RelayEnv`:

```ts
  /** Shikimori user ids, comma-separated, allowed into the web client. Set with `wrangler secret put`. */
  WEB_ALLOWED_SHIKIMORI_IDS?: string;
```

In `infra/relay/src/index.ts`:

1. Import: `import { WEB_REDIRECTS, fromSite, gate, parseAllowed, verdict, whoami } from "./whitelist";`
2. In the `/kodik/` block, after the rate limit and before creating the client:

```ts
      const closed = await gate(request, env);
      if (closed !== null) return withCors(closed, webOrigin(request));
```

3. Route `/oauth/token` through CORS and preflight — replace `if (url.pathname === TOKEN_PATH) return proxyToken(request, env);` with:

```ts
    if (url.pathname === TOKEN_PATH) {
      const early = preflight(request);
      if (early !== null) return early;
      return withCors(await proxyToken(request, env), webOrigin(request));
    }
```

4. In `refuseToken`, replace the redirect check with one that allows the site's redirect only from the site — change its signature to `refuseToken(form: URLSearchParams, env: Env, site: boolean)` and the check to:

```ts
  if (form.get("grant_type") === "authorization_code") {
    const redirect = form.get("redirect_uri") ?? "";
    const allowed = ALLOWED_REDIRECTS.has(redirect) || (site && WEB_REDIRECTS.includes(redirect));
    if (!allowed) return "unexpected redirect_uri";
  }
```

and call it as `refuseToken(form, env, fromSite(request))`.

5. In `proxyToken`, after `answer = await upstream.text();` and before the final `return`, add the site's check:

```ts
  // From the site, a token is handed over only to an account on the list. Shikimori is asked
  // whose it is with the token itself; the token goes back to the page only if the answer is
  // on the list. The apps never reach this: they send no Origin of the site.
  if (fromSite(request) && status === 200) {
    let accessToken = "";
    try { accessToken = String((JSON.parse(answer) as { access_token?: unknown }).access_token ?? ""); } catch { accessToken = ""; }
    if (accessToken === "") return plain("upstream answer unreadable", 502);
    const closed = verdict(await whoami(accessToken, (input, init) => fetch(input, init), Date.now), parseAllowed(env.WEB_ALLOWED_SHIKIMORI_IDS));
    if (closed !== null) {
      console.log(`oauth ${grant} web refused ${closed.status}`);
      return closed;
    }
  }
```

6. Update the header comment of `index.ts`: under Routes, note `/kodik/*` needs `Authorization: Bearer <Shikimori token>` of an account in `WEB_ALLOWED_SHIKIMORI_IDS`, and that `/oauth/token` from the site's Origin is checked against the same list.

- [ ] **Step 4: Run all worker tests**

Run: `npm test && npx tsc --noEmit`
Expected: PASS — new tests and the untouched app tests in `oauth.test.ts`, `room.test.ts`.

- [ ] **Step 5: Commit**

```bash
git add infra/relay/src/whitelist.ts infra/relay/src/index.ts infra/relay/types/env.d.ts infra/relay/vitest.config.ts infra/relay/test/whitelist.test.ts infra/relay/test/kodik-routes.test.ts infra/relay/test/oauth.test.ts
git commit -m "feat(relay): белый список Shikimori для веб-клиента"
```

---

### Task 6: Документация и выкладка

**Files:**
- Modify: `infra/relay/README.md`

- [ ] **Step 1: Document the new routes and the whitelist**

Add to `infra/relay/README.md` a section:

```markdown
## Веб-клиент

- `GET /kodik/translations?anime=<shikimoriId>` и `GET /kodik/resolve?anime=&translation=&episode=&season=` —
  резолв Kodik для браузера. Только с `Authorization: Bearer <токен Shikimori>` аккаунта из белого списка.
- `POST /oauth/token` с `Origin` сайта — обмен кода с адресом возврата `https://kaeru.vitaliy.velikodniy.name/auth`
  и обновление токена; токен отдаётся только аккаунту из белого списка.
- CORS — только для `https://kaeru.vitaliy.velikodniy.name` и `http://localhost:5173`.

Белый список — id пользователей Shikimori через запятую:

    npx wrangler secret put WEB_ALLOWED_SHIKIMORI_IDS

Пустой или отсутствующий список закрывает веб-клиент для всех. Изменение действует сразу для новых
входов и не позже чем через 10 минут для уже выданных токенов. Приложения список не затрагивает.

Свой токен Kodik, если появится: `npx wrangler secret put KODIK_TOKEN`.
```

- [ ] **Step 2: Deploy**

Run: `npm test && npx tsc --noEmit && npx wrangler deploy`
Expected: tests pass; deploy prints a new version id. Then check the live Worker:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://kaeru-relay.vitaliy-velikodniy.workers.dev/health          # 200
curl -s -w " %{http_code}\n" "https://kaeru-relay.vitaliy-velikodniy.workers.dev/kodik/translations?anime=1535"   # {"error":"sign_in"} 401
node tools/together-probe/probe.js                                                                    # rooms still work
```

The whitelist secret stays unset until the user sends the ids (spec §12, item 1a) — with it unset the web client is closed to everyone, which is the safe default.

- [ ] **Step 3: Commit**

```bash
git add infra/relay/README.md
git commit -m "docs(relay): маршруты веб-клиента и белый список"
git push origin master
```
