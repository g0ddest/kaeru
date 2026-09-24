# Kaeru для браузера, план 2 — клиент: каркас, вход, главная, поиск, тайтл, «Мой список», публикация

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** веб-клиент Kaeru на `kaeru.vitaliy.velikodniy.name`: вход через Shikimori только для белого списка, главная с полками, поиск, карточка тайтла со списком серий и отметками, «Мой список», настройки — опубликованный на GitHub Pages рядом со страницей приглашения, которая не меняется.

**Architecture:** React 19 SPA в `web/` (Vite 8, TypeScript 5.9, без библиотек состояния и данных). Слои: `domain/` — чистые функции, перенесённые с Android (формат, сезоны, цель «продолжить», лента главной) и проверенные его тестовыми векторами; `api/` — клиент Shikimori с лимитером и постерами из GraphQL; `auth/` — обмен кода и обновление токена через воркер, сессия в `localStorage`, одно обновление на все вкладки; `library/` — список, позиции серий в `localStorage`, записи в Shikimori по очереди на тайтл; `app/`, `ui/`, `screens/` — оболочка, примитивы и экраны. Публикация — объединение `docs/cast` и `web/dist` в ветку `gh-pages`; `404.html` остаётся страницей приглашения с вставкой, которая уводит остальные пути в SPA через `/?p=`.

**Tech Stack:** react 19.3.0, react-dom 19.3.0, react-router-dom 7.18.4; dev: vite 8.3.0, @vitejs/plugin-react 6.1.1, typescript 5.9.3, vitest 5.0.1, jsdom 30.1.1, @testing-library/react 16.3.3, @testing-library/dom 10.4.2, @testing-library/user-event 14.6.7, @testing-library/jest-dom 7.0.1, @types/node 26.6.2, @types/react и @types/react-dom 19.3.0.

**Spec:** `docs/superpowers/specs/2026-09-24-kaeru-web-design.md` (§2, §3, §6, §6a, §8, §10, §11 пункты 3–4). План 1 (воркер: `/kodik/*`, белый список, CORS) выполнен и выложен.

## Global Constraints

- Код в `web/`; TypeScript strict с `erasableSyntaxOnly`; React-функции и хуки; без библиотек состояния, загрузки данных и CSS-фреймворков.
- Зависимости — ровно версии из Tech Stack. Ничего больше в рантайме: hls.js придёт в плане 3, Firebase отложен (спека §9).
- Dev-сервер строго `http://localhost:5173` (`strictPort`): только этот адрес, кроме продакшена, пропускают CORS воркера и адрес возврата OAuth.
- Команды из `web/`: `npm test` (`vitest run`), `npm run typecheck`, `npm run build`, `npm run dev`. Git-шаги начинаются с `cd /Users/vitaliy/Projects/kaeru`.
- Воркеру (`RELAY_URL`) — никаких своих заголовков: его preflight разрешает только `authorization, content-type`. `X-Requested-With: Kaeru` — только на shikimori.io.
- Время в домене — миллисекунды эпохи; даты API разбираются в миллисекунды.
- Тексты интерфейса — дословно из карт и решений ниже; только тёмная тема.
- `src/test/setup.ts` определяет глобальный `jest.advanceTimersByTime` для Testing Library под `vi.useFakeTimers()`; с фейковыми таймерами — `userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })`.
- Экраны задач 9–11 перезаписывают заглушки задачи 8 и никогда не правят `App.tsx`.
- Страница приглашения `docs/cast/w/index.html` и `docs/cast/404.html` остаются побайтно одинаковыми; Android `AssetLinksTest` проходит без изменений.

## Решения, принятые при планировании

1. Статус «Завершено» (Android) везде.
2. Полки главной и порядок: «Новые серии», «Продолжить», «Дальше по списку», «Скоро», «В планах», «Популярно сейчас», «Популярное в сезоне» (три чипа сезона). Пустые личные полки не показываются.
3. Герой — один тайтл (первый из «Продолжить» → «Новые серии» → «Дальше по списку»), янтарная кнопка + «Подробнее», без карусели.
4. Кнопка просмотра — формулировки Android: «Продолжить с m:ss», «Пересмотреть», «Смотреть N серию» / «Продолжить N серию», неактивная «N серия выйдет завтра» / «Ещё не вышло» / «Ждём N серию».
5. Факты тайтла — одна строка через « · » в порядке iOS; оценка только если больше нуля.
6. Снятие отметки — без вопроса, с тостом «Серия N отмечена непросмотренной» и «Отменить»; отметка — правила Android; после последней серии — диалог «Перевести аниме в завершённые?» («Завершить просмотр» / «Позже»), сам статус не меняется.
7. Запись — только изменённое поле; очередь на тайтл; при ошибке — откат и тост.
8. Каталог — без токена; список, whoami и записи — с токеном.
9. До плана 3 кнопка просмотра ведёт на заглушку `/watch/:id/:episode` «Плеер появится в следующем обновлении»; выбор озвучки — в плане 3.
10. Настройки: аккаунт, «Выйти из аккаунта» с подтверждением, порог «просмотрено» 80/85/90/95 %.
11. Поиск: до ввода — сетка «Популярно сейчас»; задержка 350 мс, минимум 2 символа.
12. Шрифт — `manrope.ttf` как есть (`fonttools` нет), с лицензией OFL.
13. Хостинг: `404.html` = страница приглашения со вставкой `<script data-route>`, остальные пути — в SPA через `/?p=`; при публикации старые `assets/` сохраняются на одну публикацию.
14. Список догружает подробности (дата следующей серии, студии, кадры) для до 25 онгоингов со статусом «Смотрю»/«Пересматриваю», 6 часов на тайтл — иначе «Скоро» пуста.
15. Playwright из спецификации §10 переносится в план публикации; здесь — Vitest + Testing Library и проверка живого сайта безголовым Chrome.

## Review Focus

1. **Возврат из OAuth через GitHub Pages**: `/auth?code=…&state=…` приходит через `404.html` → `/?p=/auth?code…`; `restoreDeepLink` должен восстановить путь вместе с query, а `completeSignIn` — не отправить код дважды при перезагрузке.
2. **Две вкладки обновляют токен одновременно** (Shikimori может ротировать refresh-токен): проигравшая вкладка не должна разлогинить сессию, которую только что обновила другая.
3. **403 от DDoS-Guard Shikimori без CORS** приходит в браузер как сетевая ошибка: это «Нет соединения», а не выход из аккаунта.
4. **Онгоинг с `episodes = 0` и `next_episode_at` в прошлом** (каталог отстаёт): кнопка «Ждём N серию», не «выйдет вчера», и не «Пересмотреть».
5. **Список больше 1000 тайтлов в одном статусе**: постраничная загрузка `user_rates` по 1000, карточки пачками по 50, отказ одной пачки постеров не ломает список.

---

### Task 1: Scaffold, tokens, font, test harness

**Decisions:**
- Two dev dependencies are pinned beyond the contract's original list, as the contract amendment records. `@testing-library/dom` 10.4.2 is a required peer of `@testing-library/react` 16.3.3 (`^10.0.0`), `@testing-library/jest-dom` 7.0.1 (`>=10 <11`) and `@testing-library/user-event` 14.6.7. `@types/node` 26.6.2 is there for Task 12's `web/src/app/landing.test.ts`. That test reads `docs/cast/404.html` and `docs/cast/w/index.html` through `node:fs`, `node:path` and `node:url`, because Vite 8 denies `?raw` imports from outside `web/`. `tsconfig` therefore lists `"node"` in `types`. The version satisfies Vitest 5's optional peer range `^22.0.0 || >=24.0.0`. No code under `src` except tests uses Node APIs, and nothing is added at runtime.
- Node's types are in the program, so `setTimeout` returns `NodeJS.Timeout`. A timer handle is written `ReturnType<typeof setTimeout>`, never `number`.
- Vitest uses `globals: true` (the `vitest/globals` types), so Testing Library's automatic cleanup and act environment work. Tests still import from `"vitest"` explicitly.
- `src/test/setup.ts` defines a global `jest` that only has `advanceTimersByTime`, which forwards to `vi.advanceTimersByTime`. Testing Library 16 and `@testing-library/dom` 10 advance fake timers only through a global `jest`. They check for it before draining React's work after every `await user.*`, and in `waitFor`. Vitest has no such global. Without the shim, every `await user.click/type/keyboard` under `vi.useFakeTimers()` waits on a faked `setTimeout(0)` that never fires, and the test times out. That is what happens to Task 11's debounced search tests. The shim does nothing while timers are real, because Testing Library also requires `setTimeout.clock`, and only fake timers have it.
- `src/test/setup.ts` also runs `cleanup()` and clears `localStorage` and `sessionStorage` after each test, so every test starts from an empty page and an empty browser.
- The harness is code, so it gets a test: `src/test/harness.test.tsx` covers the jest-dom matchers, user-event under fake timers (the shim), and the empty page and storage between tests. `setup.ts` is written in Step 3, so this test fails first.
- `test.css.include: [/\.css\?raw$/]` is set because Vitest turns every CSS import into `""` by default, `?raw` included (the `vitest:css-disable` plugin in vitest 5.0.1). Without it, `tokens.test.ts` would read an empty string.
- `tsconfig` is `strict` and has no `noUnused*` or `verbatimModuleSyntax`, so type-only imports written without `import type` in later tasks still compile. It sets `erasableSyntaxOnly`: no `enum`, `namespace`, constructor parameter properties or `<T>value` assertions. Task 5's error classes declare their fields in the class body because of this rule.
- `tokens.css` also holds tints of the palette and black scrims (`rgb(... / a)`, never a new hue), plus the layout metrics that later components use. The narrow grid minimum is 88px, as in Android `Adaptive(88)`, which gives 3 columns at 320px.
- `index.html` preloads the font. Its favicon is `data:,`, so Pages never serves the landing page as `/favicon.ico`.

**Files:**
- Create: `web/package.json`, `web/package-lock.json` (generated by `npm install`), `web/tsconfig.json`, `web/vite.config.ts`, `web/index.html`, `web/src/main.tsx`, `web/src/test/setup.ts`, `web/src/config.ts`, `web/src/ui/tokens.css`, `web/src/ui/base.css`, `web/public/fonts/manrope.ttf` (copy), `web/public/fonts/OFL-Manrope.txt` (copy)
- Modify: `.gitignore`
- Test: `web/src/config.test.ts`, `web/src/ui/tokens.test.ts`, `web/src/test/harness.test.tsx`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `config.ts`: `SHIKIMORI_URL = "https://shikimori.io"`, `RELAY_URL = "https://kaeru-relay.vitaliy-velikodniy.workers.dev"`, `CLIENT_ID = "_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg"`, `SITE_ORIGIN = "https://kaeru.vitaliy.velikodniy.name"`, and `function redirectUri(origin = window.location.origin): string`, which returns `${origin}/auth`.
  - `tokens.css` custom properties:
    - Palette: `--bg --surface --elevated --line --ink --ink-soft --accent --on-accent --error`.
    - Tints and scrims: `--accent-soft --secondary-bg --badge-bg --track --skeleton-low --skeleton-high --disc --scrim`.
    - Spacing: `--s1 --s2 --s3 --s4 --s6 --s8 --s14`.
    - Radii and motion: `--r-card --r-chip`, `--fast --normal --hero`.
    - Layout: `--gutter --shelf-gap --card-gap --poster-w --grid-min --content-max --button-h --touch --sidebar-w --topbar-h --tabbar-h`, plus `--font`.
  - `tokens.css` type classes: `.t-display .t-headline .t-title .t-title-sm .t-body .t-body-lg .t-label .t-label-sm`.
  - `base.css`: `@font-face` Manrope 200–800, `.visually-hidden`, `.tabular`, the global `:focus-visible` ring and the reduced-motion rules.
  - Test harness: jsdom, Vitest globals, jest-dom matchers, a global `jest.advanceTimersByTime` shim so Testing Library and user-event work under `vi.useFakeTimers()`, and `cleanup()` plus empty `localStorage`/`sessionStorage` after each test. The rule for later tasks is `userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })` whenever timers are fake.
  - TypeScript: `types` are `vite/client`, `vitest/globals` and `node`, with `strict` and `erasableSyntaxOnly`.
  - Scripts: `npm test`, `npm run typecheck`, `npm run build`, `npm run dev` (http://localhost:5173, strict port).

- [ ] **Step 1: Write the failing test.** Create the project files the tests run in, then the three tests. The harness itself (`src/test/setup.ts`) comes in Step 3.

`web/package.json`:

```json
{
  "name": "kaeru-web",
  "version": "0.1.0",
  "private": true,
  "description": "Kaeru для браузера",
  "type": "module",
  "engines": {
    "node": "^22.22.2 || ^24.15.0 || >=26.0.0"
  },
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "test": "vitest run",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "react": "19.3.0",
    "react-dom": "19.3.0",
    "react-router-dom": "7.18.4"
  },
  "devDependencies": {
    "@testing-library/dom": "10.4.2",
    "@testing-library/jest-dom": "7.0.1",
    "@testing-library/react": "16.3.3",
    "@testing-library/user-event": "14.6.7",
    "@types/node": "26.6.2",
    "@types/react": "19.3.0",
    "@types/react-dom": "19.3.0",
    "@vitejs/plugin-react": "6.1.1",
    "jsdom": "30.1.1",
    "typescript": "5.9.3",
    "vite": "8.3.0",
    "vitest": "5.0.1"
  }
}
```

`web/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "types": ["vite/client", "vitest/globals", "node"],
    "strict": true,
    "erasableSyntaxOnly": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "resolveJsonModule": true,
    "noFallthroughCasesInSwitch": true,
    "forceConsistentCasingInFileNames": true
  },
  "include": ["src", "vite.config.ts"]
}
```

`web/vite.config.ts`:

```ts
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // The site root: the app sits beside the Cast skin and /w/ on the same Pages host.
  base: "/",
  plugins: [react()],
  // The worker's CORS and the OAuth redirect accept only this dev origin.
  server: { port: 5173, strictPort: true, host: "localhost" },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/test/setup.ts"],
    // Vitest blanks CSS in tests, `?raw` included; let raw CSS through so the token tests can read it.
    css: { include: [/\.css\?raw$/] },
  },
});
```

`web/src/test/harness.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

function Counter() {
  const [count, setCount] = useState(0);
  return <button onClick={() => setCount((n) => n + 1)}>Нажато {count}</button>;
}

describe("test harness", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("brings the jest-dom matchers", () => {
    render(<Counter />);
    expect(screen.getByRole("button", { name: "Нажато 0" })).toBeInTheDocument();
  });

  // Debounced screens are tested with fake timers; Testing Library needs the global `jest` shim for that.
  it("lets user-event click while timers are fake", async () => {
    vi.useFakeTimers();
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    render(<Counter />);
    await user.click(screen.getByRole("button"));
    expect(screen.getByRole("button")).toHaveTextContent("Нажато 1");
  });

  it("leaves a page and stored values behind", () => {
    render(<Counter />);
    window.localStorage.setItem("kaeru.harness", "1");
    window.sessionStorage.setItem("kaeru.harness", "1");
    expect(window.localStorage.getItem("kaeru.harness")).toBe("1");
  });

  it("starts the next test with an empty page and empty storage", () => {
    expect(document.body).toBeEmptyDOMElement();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});
```

`web/src/config.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { CLIENT_ID, RELAY_URL, SHIKIMORI_URL, SITE_ORIGIN, redirectUri } from "./config";

describe("redirectUri", () => {
  // The worker accepts exactly these two web redirects: infra/relay/src/whitelist.ts:14-17.
  it("builds the production redirect", () => {
    expect(redirectUri(SITE_ORIGIN)).toBe("https://kaeru.vitaliy.velikodniy.name/auth");
  });

  it("builds the dev-server redirect", () => {
    expect(redirectUri("http://localhost:5173")).toBe("http://localhost:5173/auth");
  });

  it("defaults to the page's own origin", () => {
    expect(redirectUri()).toBe(`${window.location.origin}/auth`);
  });
});

describe("service addresses", () => {
  it("match the worker, its client id and the site", () => {
    expect(SHIKIMORI_URL).toBe("https://shikimori.io");
    expect(RELAY_URL).toBe("https://kaeru-relay.vitaliy-velikodniy.workers.dev");
    // infra/relay/wrangler.toml:23
    expect(CLIENT_ID).toBe("_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg");
    expect(SITE_ORIGIN).toBe("https://kaeru.vitaliy.velikodniy.name");
  });
});
```

`web/src/ui/tokens.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import base from "./base.css?raw";
import tokens from "./tokens.css?raw";

// android/src/main/java/app/kaeru/ui/common/theme/Color.kt:10-24 — "nine values and no tenth".
const PALETTE: Array<[string, string]> = [
  ["--bg", "#0B0C10"],
  ["--surface", "#15171E"],
  ["--elevated", "#1E212B"],
  ["--line", "#2A2E3A"],
  ["--ink", "#F2F3F5"],
  ["--ink-soft", "#9AA0AA"],
  ["--accent", "#F5A524"],
  ["--on-accent", "#1A1200"],
  ["--error", "#E5484D"],
];

// Phone scale, size / line-height px / weight: android/src/main/java/app/kaeru/ui/common/theme/Type.kt:94-155.
const TYPE_SCALE: Array<[string, number, number, number]> = [
  ["t-display", 34, 48, 800],
  ["t-headline", 24, 34, 700],
  ["t-title", 17, 24, 600],
  ["t-title-sm", 15, 21, 600],
  ["t-body", 15, 22, 400],
  ["t-body-lg", 16, 24, 400],
  ["t-label", 13, 19, 600],
  ["t-label-sm", 11, 16, 600],
];

/** The text of the first `selector { … }` rule. */
function rule(css: string, selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`no rule for ${selector}`);
  return css.slice(start, css.indexOf("}", start) + 1);
}

/** The value of the first `property: value;` declaration. */
function value(css: string, property: string): string {
  const match = new RegExp(`(?:^|[\\s;{])${property}:\\s*([^;]+);`).exec(css);
  if (!match) throw new Error(`${property} is not declared`);
  return match[1].trim();
}

describe("tokens.css", () => {
  it("declares the nine palette colours with the Android values", () => {
    for (const [name, hex] of PALETTE) {
      expect(value(tokens, name).toUpperCase()).toBe(hex);
    }
  });

  it("has no tenth colour", () => {
    const hexes = new Set((tokens.match(/#[0-9a-f]{6}\b/gi) ?? []).map((hex) => hex.toUpperCase()));
    expect([...hexes].sort()).toEqual(PALETTE.map(([, hex]) => hex).sort());
  });

  it("keeps the spacing, radii and motion of KaeruTokens.kt", () => {
    expect(value(tokens, "--s1")).toBe("4px");
    expect(value(tokens, "--s2")).toBe("8px");
    expect(value(tokens, "--s3")).toBe("12px");
    expect(value(tokens, "--s4")).toBe("16px");
    expect(value(tokens, "--s6")).toBe("24px");
    expect(value(tokens, "--s8")).toBe("32px");
    expect(value(tokens, "--r-card")).toBe("12px");
    expect(value(tokens, "--r-chip")).toBe("20px");
    expect(value(tokens, "--fast")).toBe("150ms");
    expect(value(tokens, "--normal")).toBe("250ms");
    expect(value(tokens, "--hero")).toBe("400ms");
  });

  it("widens the gutter from 16px to 32px at 768px", () => {
    expect(value(tokens, "--gutter")).toBe("16px");
    const wide = tokens.slice(tokens.indexOf("@media (min-width: 768px)"));
    expect(value(wide, "--gutter")).toBe("32px");
  });

  it.each(TYPE_SCALE)(".%s follows the Android phone scale", (name, size, lineHeight, weight) => {
    const css = rule(tokens, `.${name}`);
    expect(value(css, "font-size")).toBe(`${size}px`);
    expect(value(css, "line-height")).toBe(`${lineHeight}px`);
    expect(value(css, "font-weight")).toBe(String(weight));
  });

  // Manrope's line box is 1.366 of the size; below 1.4 Cyrillic descenders get clipped (Type.kt:48-86).
  it.each(TYPE_SCALE)(".%s keeps line-height at least 1.4 times the size", (name) => {
    const css = rule(tokens, `.${name}`);
    const size = parseFloat(value(css, "font-size"));
    const lineHeight = parseFloat(value(css, "line-height"));
    expect(lineHeight * 10).toBeGreaterThanOrEqual(size * 14);
  });
});

describe("base.css", () => {
  it("self-hosts variable Manrope and loads no third-party font", () => {
    const face = rule(base, "@font-face");
    expect(value(face, "font-family")).toBe('"Manrope"');
    expect(value(face, "font-weight")).toBe("200 800");
    expect(value(face, "font-display")).toBe("swap");
    expect(face).toContain('url("/fonts/manrope.ttf")');
    expect(base).not.toMatch(/fonts\.googleapis|fonts\.gstatic/);
  });

  it("paints the page dark itself", () => {
    const body = rule(base, "body");
    expect(value(body, "background")).toBe("var(--bg)");
    expect(value(body, "color")).toBe("var(--ink)");
  });
});
```

- [ ] **Step 2: Run it to see it fail.**

```bash
cd /Users/vitaliy/Projects/kaeru/web
npm install
npx vitest run src/test/harness.test.tsx src/config.test.ts src/ui/tokens.test.ts
```

**Expected:** `npm install` finishes without errors. The run then lists all three files as `FAIL` with `(0 test)`, and ends with `Test Files  3 failed (3)` and `Tests  no tests`. The shared error is `Error: Cannot find module '/Users/vitaliy/Projects/kaeru/web/src/test/setup.ts'`: the harness does not exist yet, and neither does the code under test.

- [ ] **Step 3: Implement**

`web/src/test/setup.ts`:

```ts
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// Testing Library advances fake timers only through a global `jest`, and Vitest has none. Without
// this, every `await user.*` under vi.useFakeTimers() waits on a faked setTimeout(0) forever.
(globalThis as unknown as { jest: unknown }).jest = { advanceTimersByTime: (ms: number) => vi.advanceTimersByTime(ms) };

afterEach(() => {
  cleanup();
  // Stores persist in web storage; every test starts from an empty browser.
  window.localStorage.clear();
  window.sessionStorage.clear();
});
```

`web/src/config.ts`:

```ts
/** Shikimori answers browsers directly (CORS *), so the API client calls it without a proxy. */
export const SHIKIMORI_URL = "https://shikimori.io";

/** The worker: OAuth token exchange with the secret, Kodik resolving, watch-together rooms. */
export const RELAY_URL = "https://kaeru-relay.vitaliy-velikodniy.workers.dev";

/** Public OAuth client id of the Kaeru app on Shikimori, the same as in infra/relay/wrangler.toml. */
export const CLIENT_ID = "_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg";

/** The production site; the dev server is http://localhost:5173. */
export const SITE_ORIGIN = "https://kaeru.vitaliy.velikodniy.name";

/**
 * The OAuth return address for the page's own origin. The worker accepts exactly
 * https://kaeru.vitaliy.velikodniy.name/auth and http://localhost:5173/auth.
 */
export function redirectUri(origin: string = window.location.origin): string {
  return `${origin}/auth`;
}
```

`web/src/ui/tokens.css`:

```css
/* Kaeru design tokens. Colours: android/.../ui/common/theme/Color.kt; type: theme/Type.kt
   (phone scale); spacing, radii, motion and sizes: design/KaeruTokens.kt. Dark only. */
:root {
  color-scheme: dark;

  /* Nine colours and no tenth: every surface is a step of one near-black, amber is for the one action. */
  --bg: #0B0C10;
  --surface: #15171E;
  --elevated: #1E212B;
  --line: #2A2E3A;
  --ink: #F2F3F5;
  --ink-soft: #9AA0AA;
  --accent: #F5A524;
  --on-accent: #1A1200;
  --error: #E5484D;

  /* Tints of the palette and black scrims, never a new hue. */
  --accent-soft: rgb(245 165 36 / 0.18);
  --secondary-bg: rgb(30 33 43 / 0.85);
  --badge-bg: rgb(11 12 16 / 0.82);
  --track: rgb(255 255 255 / 0.18);
  --skeleton-low: rgb(255 255 255 / 0.1);
  --skeleton-high: rgb(255 255 255 / 0.24);
  --disc: rgb(0 0 0 / 0.42);
  --scrim: rgb(0 0 0 / 0.6);

  /* Spacing in 4px steps, named by their multiple of 4. */
  --s1: 4px;
  --s2: 8px;
  --s3: 12px;
  --s4: 16px;
  --s6: 24px;
  --s8: 32px;
  --s14: 56px;

  /* Nothing rounder than a chip. */
  --r-card: 12px;
  --r-chip: 20px;

  /* Press, open/close, hero crossfade. */
  --fast: 150ms;
  --normal: 250ms;
  --hero: 400ms;

  /* Layout, narrow (below 768px). */
  --gutter: 16px;
  --shelf-gap: 24px;
  --card-gap: 12px;
  --poster-w: 132px;
  --grid-min: 88px;
  --content-max: 1280px;
  --button-h: 52px;
  --touch: 48px;
  --sidebar-w: 248px;
  --topbar-h: 56px;
  --tabbar-h: 64px;

  --font: "Manrope", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}

@media (min-width: 768px) {
  :root {
    --gutter: 32px;
    --shelf-gap: 36px;
    --card-gap: 18px;
    --poster-w: 168px;
    --grid-min: 168px;
  }
}

@media (prefers-reduced-motion: reduce) {
  :root {
    --fast: 0ms;
    --normal: 0ms;
    --hero: 0ms;
  }
}

/* Type, the Android phone scale. Manrope's line box is 1.366 of the size, so every line-height is
   at least 1.4 times it: lower, and the descenders of у р д ф and the breve of й get clipped. */
.t-display {
  font-size: 34px;
  line-height: 48px;
  font-weight: 800;
  letter-spacing: -0.8px;
}

.t-headline {
  font-size: 24px;
  line-height: 34px;
  font-weight: 700;
  letter-spacing: -0.4px;
}

.t-title {
  font-size: 17px;
  line-height: 24px;
  font-weight: 600;
}

.t-title-sm {
  font-size: 15px;
  line-height: 21px;
  font-weight: 600;
}

.t-body {
  font-size: 15px;
  line-height: 22px;
  font-weight: 400;
}

.t-body-lg {
  font-size: 16px;
  line-height: 24px;
  font-weight: 400;
}

.t-label {
  font-size: 13px;
  line-height: 19px;
  font-weight: 600;
}

.t-label-sm {
  font-size: 11px;
  line-height: 16px;
  font-weight: 600;
}
```

`web/src/ui/base.css`:

```css
/* Page foundation: the font, the box model, one focus ring and motion preferences. */
@font-face {
  font-family: "Manrope";
  src: url("/fonts/manrope.ttf") format("truetype");
  font-weight: 200 800;
  font-style: normal;
  font-display: swap;
}

*,
*::before,
*::after {
  box-sizing: border-box;
}

html {
  -webkit-text-size-adjust: 100%;
  text-size-adjust: 100%;
  background: var(--bg);
}

body {
  margin: 0;
  min-height: 100dvh;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
  font-size: 15px;
  line-height: 22px;
  font-weight: 400;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  -webkit-tap-highlight-color: transparent;
}

h1,
h2,
h3,
h4,
p,
figure {
  margin: 0;
}

a {
  color: inherit;
}

button,
input,
select,
textarea {
  font: inherit;
  color: inherit;
  letter-spacing: inherit;
}

img,
svg,
video {
  display: block;
  max-width: 100%;
}

/* One focus treatment everywhere: a 3px amber ring; components add the 1.06 scale. */
:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: 2px;
}

::selection {
  background: var(--accent-soft);
}

.visually-hidden {
  position: absolute !important;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}

.tabular {
  font-variant-numeric: tabular-nums;
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

`web/index.html`:

```html
<!doctype html>
<html lang="ru">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
    <meta name="color-scheme" content="dark" />
    <meta name="theme-color" content="#0B0C10" />
    <link rel="icon" href="data:," />
    <link rel="preload" href="/fonts/manrope.ttf" as="font" type="font/ttf" crossorigin />
    <title>Kaeru</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`web/src/main.tsx`:

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./ui/tokens.css";
import "./ui/base.css";

const root = document.getElementById("root");
if (!root) throw new Error("index.html has no #root element");

// A bare page until the app shell exists (Task 8 replaces this file); it proves the font, palette and build.
createRoot(root).render(
  <StrictMode>
    <h1 className="t-display">Kaeru</h1>
  </StrictMode>,
);
```

Copy the font and its licence without converting them:

```bash
cd /Users/vitaliy/Projects/kaeru/web
mkdir -p public/fonts
cp ../android/src/main/res/font/manrope.ttf public/fonts/manrope.ttf
cp ../android/src/main/assets/licenses/OFL-Manrope.txt public/fonts/OFL-Manrope.txt
```

Modify `/Users/vitaliy/Projects/kaeru/.gitignore`. Old text (end of file):

```
# Firebase: конфигурация проекта, у каждого своя
android/google-services.json
ios/App/GoogleService-Info.plist
```

New text:

```
# Firebase: конфигурация проекта, у каждого своя
android/google-services.json
ios/App/GoogleService-Info.plist

# Web client: dependencies and build output
web/node_modules/
web/dist/
```

- [ ] **Step 4: Run to see it pass.**

```bash
cd /Users/vitaliy/Projects/kaeru/web
npx vitest run src/test/harness.test.tsx src/config.test.ts src/ui/tokens.test.ts
npm test
npm run typecheck
npm run build
cmp public/fonts/manrope.ttf ../android/src/main/res/font/manrope.ttf
cmp public/fonts/OFL-Manrope.txt ../android/src/main/assets/licenses/OFL-Manrope.txt
cd /Users/vitaliy/Projects/kaeru
git status --short .gitignore web
git status --short --untracked-files=all web
```

**Expected:**
- `vitest run`: `Test Files  3 passed (3)`, `Tests  30 passed (30)` (harness 4, config 4, tokens 22). The fake-timer test finishes in milliseconds, not after a 5 s timeout.
- `npm test`: the same 3 files and 30 tests pass.
- `npm run typecheck`: `tsc` prints nothing after npm's `> tsc --noEmit` header; exit code 0.
- `npm run build`: `✓ built in …`. `dist/index.html` and `dist/fonts/manrope.ttf` exist.
- Both `cmp` commands: no output, because the copies are byte-identical.
- `git status --short .gitignore web`: ` M .gitignore` and `?? web/`.
- `git status --short --untracked-files=all web`: exactly the 15 `web/` files that Step 5 adds, each as `?? web/…`. Nothing under `web/node_modules/` or `web/dist/` appears.

- [ ] **Step 5: Commit.**

```bash
cd /Users/vitaliy/Projects/kaeru
git add .gitignore web/package.json web/package-lock.json web/tsconfig.json web/vite.config.ts web/index.html web/src/main.tsx web/src/test/setup.ts web/src/test/harness.test.tsx web/src/config.ts web/src/config.test.ts web/src/ui/tokens.css web/src/ui/base.css web/src/ui/tokens.test.ts web/public/fonts/manrope.ttf web/public/fonts/OFL-Manrope.txt
git commit -m "feat(web): каркас клиента — Vite, React, Vitest, токены Kaeru и Manrope"
```

---

---

### Task 2: Domain models, formatting, seasons

**Decisions:**
- `plural` works on |n| as Android does, so negative day counts read like positive ones.
- `factsLine` prints the score with a point, the way Shikimori writes it. A whole score keeps its ".0" («★ 9.0»). «вышло N» appears on every ongoing title (iOS). A blank kind and blank studios are dropped, and studios are joined with «, ».
- `cleanDescription` unwraps `[[X]]` first. It then applies Android's BBCode regex (lower-case tag names, inner text kept) and strips `<…>`. Entities are decoded after that, `&amp;` last. Inner whitespace is kept, the ends are trimmed, and an empty result is `null`.
- `relativeDay` and `currentSeason` read the local calendar fields of `new Date(ms)`. Tests build instants with `new Date(y, m - 1, d, h, min)`, so they pass in any machine time zone. There is no TZ pin.
- Tests import `describe`/`it`/`expect` from `vitest` explicitly and do not rely on globals.

**Files:**
- Create: `web/src/domain/models.ts`
- Create: `web/src/domain/format.ts`
- Create: `web/src/domain/season.ts`
- Test: `web/src/domain/models.test.ts`
- Test: `web/src/domain/format.test.ts`
- Test: `web/src/domain/season.test.ts`

**Interfaces:**
- Consumes: only the Task 1 harness, with no code imports:
  - Vitest with the jsdom environment and `src/test/setup.ts`
  - `npm test`
  - `npm run typecheck`
- Produces:
  - `models.ts`:
    - types: `type AiringStatus = "ongoing" | "released" | "anons"`, `type ListStatus = "watching" | "planned" | "completed" | "rewatching" | "on_hold" | "dropped"`
    - constants: `LIST_TABS: readonly ListStatus[]`, `STATUS_MENU: readonly ListStatus[]`
    - interfaces: `Anime { id; title; originalTitle; posterUrl; backdropUrl; status; episodes; episodesAired; year; score; kind; studios; description; nextEpisodeAt }`, `UserRate { id; animeId; status; episodes; updatedAt }`, `LibraryEntry { anime; rate }`, `EpisodeProgress { animeId; episode; positionMs; durationMs; updatedAt }`
    - functions: `availableEpisodes(anime: Anime): number`, `parseListStatus(raw: string): ListStatus`, `parseAiringStatus(raw: string): AiringStatus`
  - `format.ts`:
    - plurals: `plural(n, one, few, many): string`, `pluralEpisodes(n): string`, `pluralEpisodesAccusative(n): string`
    - time and dates: `formatTime(ms): string`, `remainingLine(positionMs, durationMs): string | null`, `relativeDay(target, now): string`
    - labels: `statusLabel(s: ListStatus): string`, `airingLabel(s: AiringStatus): string`, `kindLabel(kind: string): string`, `episodeBadge(n): string`
    - text: `factsLine(anime: Anime): string`, `cleanDescription(raw: string | null): string | null`
  - `season.ts`:
    - types: `type SeasonKind = "winter" | "spring" | "summer" | "fall"`, `interface Season { kind: SeasonKind; year: number }`
    - functions: `currentSeason(now: number): Season`, `seasonChips(now: number): [Season, Season, Season]`, `seasonApiValue(s: Season): string`, `seasonTitle(s: Season): string`, `sameSeason(a: Season, b: Season): boolean`

- [ ] **Step 1: Write the failing test**

Create the three test files.

`web/src/domain/models.test.ts`:

```ts
// Vectors from android/src/test/java/app/kaeru/domain/model/AnimeTest.kt (availableEpisodes)
// and android domain/model/UserRate.kt + data/shikimori/ShikimoriMappers.kt (parsing).
import { describe, expect, it } from "vitest";
import type { AiringStatus, Anime } from "./models";
import { LIST_TABS, STATUS_MENU, availableEpisodes, parseAiringStatus, parseListStatus } from "./models";

function anime(status: AiringStatus, episodes: number, episodesAired: number): Anime {
  return {
    id: 1,
    title: "Тест",
    originalTitle: "Test",
    posterUrl: null,
    backdropUrl: null,
    status,
    episodes,
    episodesAired,
    year: null,
    score: null,
    kind: null,
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

describe("availableEpisodes", () => {
  it("an ongoing show has only what has aired, whatever the season promises", () => {
    expect(availableEpisodes(anime("ongoing", 24, 7))).toBe(7);
    expect(availableEpisodes(anime("ongoing", 24, 0))).toBe(0);
  });

  it("an announcement has nothing available, even when the catalogue says something aired", () => {
    expect(availableEpisodes(anime("anons", 12, 0))).toBe(0);
    expect(availableEpisodes(anime("anons", 0, 0))).toBe(0);
    expect(availableEpisodes(anime("anons", 12, 5))).toBe(0);
  });

  it("a released show counts its whole announced run", () => {
    // Live Shikimori: Death Note is released with episodes 37 and episodes_aired 0.
    expect(availableEpisodes(anime("released", 12, 0))).toBe(12);
    expect(availableEpisodes(anime("released", 37, 0))).toBe(37);
  });

  it("a released show with an unknown total falls back to what aired", () => {
    expect(availableEpisodes(anime("released", 0, 24))).toBe(24);
  });
});

describe("parseListStatus", () => {
  it("keeps every Shikimori status as it is", () => {
    for (const status of LIST_TABS) expect(parseListStatus(status)).toBe(status);
  });

  it("reads anything unknown as planned", () => {
    expect(parseListStatus("")).toBe("planned");
    expect(parseListStatus("watched")).toBe("planned");
    expect(parseListStatus("Watching")).toBe("planned");
  });
});

describe("parseAiringStatus", () => {
  it("knows ongoing and anons and treats everything else as released", () => {
    expect(parseAiringStatus("ongoing")).toBe("ongoing");
    expect(parseAiringStatus("anons")).toBe("anons");
    expect(parseAiringStatus("released")).toBe("released");
    expect(parseAiringStatus("latest")).toBe("released");
    expect(parseAiringStatus("")).toBe("released");
  });
});

describe("status orders", () => {
  it("lists the library tabs in Android order", () => {
    expect(LIST_TABS).toEqual(["watching", "planned", "completed", "rewatching", "on_hold", "dropped"]);
  });

  it("lists the title-screen status menu in the order both apps use", () => {
    expect(STATUS_MENU).toEqual(["watching", "planned", "completed", "on_hold", "dropped", "rewatching"]);
  });
});
```

`web/src/domain/format.test.ts`:

```ts
// Vectors from android/src/test/java/app/kaeru/ui/common/design/FormatTest.kt unless a test says
// otherwise; facts-line vectors follow ios/Features/DetailView.swift (facts, kindTitle).
import { describe, expect, it } from "vitest";
import type { Anime } from "./models";
import {
  airingLabel,
  cleanDescription,
  episodeBadge,
  factsLine,
  formatTime,
  kindLabel,
  plural,
  pluralEpisodes,
  pluralEpisodesAccusative,
  relativeDay,
  remainingLine,
  statusLabel,
} from "./format";

// A local wall-clock instant: the rules count days in the viewer's zone, whatever zone runs the tests.
function at(year: number, month: number, day: number, hour: number, minute: number): number {
  return new Date(year, month - 1, day, hour, minute).getTime();
}

// 12 April 2026, 21:30 — FormatTest's ordinary evening on the sofa.
const now = at(2026, 4, 12, 21, 30);

function anime(overrides: Partial<Anime> = {}): Anime {
  return {
    id: 1535,
    title: "Тетрадь смерти",
    originalTitle: "Death Note",
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 37,
    episodesAired: 0,
    year: 2006,
    score: 8.62,
    kind: "tv",
    studios: ["Madhouse"],
    description: null,
    nextEpisodeAt: null,
    ...overrides,
  };
}

describe("plural", () => {
  it("picks one, few or many with the 11–14 exception", () => {
    expect(plural(1, "день", "дня", "дней")).toBe("день");
    expect(plural(2, "день", "дня", "дней")).toBe("дня");
    expect(plural(4, "день", "дня", "дней")).toBe("дня");
    expect(plural(5, "день", "дня", "дней")).toBe("дней");
    expect(plural(0, "день", "дня", "дней")).toBe("дней");
    expect(plural(11, "день", "дня", "дней")).toBe("дней");
    expect(plural(14, "день", "дня", "дней")).toBe("дней");
    expect(plural(21, "день", "дня", "дней")).toBe("день");
    expect(plural(111, "день", "дня", "дней")).toBe("дней");
    expect(plural(-3, "день", "дня", "дней")).toBe("дня");
  });
});

describe("pluralEpisodes", () => {
  it("follows the Russian count", () => {
    expect(pluralEpisodes(1)).toBe("1 серия");
    expect(pluralEpisodes(3)).toBe("3 серии");
    expect(pluralEpisodes(12)).toBe("12 серий");
    expect(pluralEpisodes(21)).toBe("21 серия");
    expect(pluralEpisodes(24)).toBe("24 серии");
    expect(pluralEpisodes(11)).toBe("11 серий");
  });
});

describe("pluralEpisodesAccusative", () => {
  it("puts the count in the accusative", () => {
    expect(pluralEpisodesAccusative(1)).toBe("1 серию");
    expect(pluralEpisodesAccusative(2)).toBe("2 серии");
    expect(pluralEpisodesAccusative(4)).toBe("4 серии");
    expect(pluralEpisodesAccusative(5)).toBe("5 серий");
    expect(pluralEpisodesAccusative(21)).toBe("21 серию");
    expect(pluralEpisodesAccusative(22)).toBe("22 серии");
    expect(pluralEpisodesAccusative(60)).toBe("60 серий");
  });

  it("keeps the eleven to fourteen exception", () => {
    expect(pluralEpisodesAccusative(11)).toBe("11 серий");
    expect(pluralEpisodesAccusative(12)).toBe("12 серий");
    expect(pluralEpisodesAccusative(14)).toBe("14 серий");
    expect(pluralEpisodesAccusative(111)).toBe("111 серий");
  });

  it("differs from the nominative exactly where Russian says it does", () => {
    expect(pluralEpisodes(21)).toBe("21 серия");
    expect(pluralEpisodesAccusative(21)).toBe("21 серию");
    for (let n = 2; n <= 10; n++) expect(pluralEpisodesAccusative(n)).toBe(pluralEpisodes(n));
  });
});

describe("formatTime", () => {
  it("drops the hour until there is one", () => {
    expect(formatTime(0)).toBe("0:00");
    expect(formatTime(860_000)).toBe("14:20");
    expect(formatTime(3_754_000)).toBe("1:02:34");
  });

  it("floors to whole seconds and never prints a negative or NaN time", () => {
    expect(formatTime(59_999)).toBe("0:59");
    expect(formatTime(-5_000)).toBe("0:00");
    expect(formatTime(Number.NaN)).toBe("0:00");
  });
});

describe("remainingLine", () => {
  it("has nothing to say about an unknown or finished episode", () => {
    expect(remainingLine(600_000, 0)).toBeNull();
    expect(remainingLine(1_440_000, 1_440_000)).toBeNull();
    expect(remainingLine(2_000_000, 1_440_000)).toBeNull();
  });

  it("names the last seconds with a phrase, not a zero", () => {
    expect(remainingLine(1_410_000, 1_440_000)).toBe("осталось меньше минуты");
  });

  it("floors minutes so the number is never a promise", () => {
    expect(remainingLine(600_000, 1_440_000)).toBe("осталось 14 мин");
    expect(remainingLine(540_000, 1_440_000 - 41_000)).toBe("осталось 14 мин");
  });

  it("reads an hour or more in hours and minutes", () => {
    expect(remainingLine(0, 4_800_000)).toBe("осталось 1 ч 20 мин");
    expect(remainingLine(0, 7_200_000)).toBe("осталось 2 ч");
  });
});

describe("relativeDay", () => {
  it("names today and tomorrow by the calendar, not by 24-hour buckets", () => {
    expect(relativeDay(at(2026, 4, 12, 23, 45), now)).toBe("сегодня");
    expect(relativeDay(at(2026, 4, 13, 1, 15), now)).toBe("завтра");
  });

  it("counts days ahead with the Russian plural", () => {
    expect(relativeDay(at(2026, 4, 14, 12, 0), now)).toBe("через 2 дня");
    expect(relativeDay(at(2026, 4, 15, 12, 0), now)).toBe("через 3 дня");
    expect(relativeDay(at(2026, 4, 17, 12, 0), now)).toBe("через 5 дней");
    expect(relativeDay(at(2026, 4, 23, 12, 0), now)).toBe("через 11 дней");
    expect(relativeDay(at(2026, 5, 3, 12, 0), now)).toBe("через 21 день");
  });

  it("counts the past backwards the same way", () => {
    expect(relativeDay(at(2026, 4, 11, 3, 0), now)).toBe("вчера");
    expect(relativeDay(at(2026, 4, 9, 12, 0), now)).toBe("3 дня назад");
    expect(relativeDay(at(2026, 4, 5, 12, 0), now)).toBe("7 дней назад");
  });
});

describe("labels", () => {
  it("names every list status, completed as «Завершено»", () => {
    expect(statusLabel("watching")).toBe("Смотрю");
    expect(statusLabel("planned")).toBe("В планах");
    expect(statusLabel("completed")).toBe("Завершено");
    expect(statusLabel("rewatching")).toBe("Пересматриваю");
    expect(statusLabel("on_hold")).toBe("Отложено");
    expect(statusLabel("dropped")).toBe("Брошено");
  });

  it("names every airing status", () => {
    expect(airingLabel("ongoing")).toBe("Онгоинг");
    expect(airingLabel("released")).toBe("Вышло");
    expect(airingLabel("anons")).toBe("Анонс");
  });

  it("names the kinds iOS knows and upper-cases the rest", () => {
    expect(kindLabel("tv")).toBe("Сериал");
    expect(kindLabel("movie")).toBe("Фильм");
    expect(kindLabel("ova")).toBe("OVA");
    expect(kindLabel("ona")).toBe("ONA");
    expect(kindLabel("special")).toBe("Спецвыпуск");
    expect(kindLabel("tv_special")).toBe("Спецвыпуск");
    expect(kindLabel("music")).toBe("Музыкальное видео");
    expect(kindLabel("cm")).toBe("CM");
    expect(kindLabel("pv")).toBe("PV");
  });

  it("badges an episode by its number with the noun unchanged", () => {
    // android ui/common/home/HomeRows.kt episodeBadge
    expect(episodeBadge(7)).toBe("7 серия");
    expect(episodeBadge(21)).toBe("21 серия");
    expect(episodeBadge(22)).toBe("22 серия");
  });
});

describe("factsLine", () => {
  it("joins the facts with a middle dot in the iOS order", () => {
    expect(factsLine(anime())).toBe("Вышло · 2006 · 37 эп. · ★ 8.62 · Сериал · Madhouse");
  });

  it("adds how many aired for an ongoing show, after the score", () => {
    const ongoing = anime({
      status: "ongoing",
      year: 2026,
      episodes: 12,
      episodesAired: 8,
      score: 8.1,
      studios: ["MAPPA", "Studio Pierrot"],
    });
    expect(factsLine(ongoing)).toBe("Онгоинг · 2026 · 12 эп. · ★ 8.1 · вышло 8 · Сериал · MAPPA, Studio Pierrot");
  });

  it("hides a score of zero or none, and keeps the decimal of a whole score", () => {
    expect(factsLine(anime({ score: 0 }))).toBe("Вышло · 2006 · 37 эп. · Сериал · Madhouse");
    expect(factsLine(anime({ score: null }))).toBe("Вышло · 2006 · 37 эп. · Сериал · Madhouse");
    expect(factsLine(anime({ score: 9 }))).toBe("Вышло · 2006 · 37 эп. · ★ 9.0 · Сериал · Madhouse");
  });

  it("drops the facts nobody knows", () => {
    const bare = anime({ status: "ongoing", year: null, episodes: 0, episodesAired: 3, score: null, kind: null, studios: [] });
    expect(factsLine(bare)).toBe("Онгоинг · вышло 3");
    const announced = anime({ status: "anons", year: 2027, episodes: 0, score: null, kind: "movie", studios: ["", " "] });
    expect(factsLine(announced)).toBe("Анонс · 2027 · Фильм");
  });
});

describe("cleanDescription", () => {
  it("strips BBCode keeping the inner text and unwraps wiki links", () => {
    // The live description of 1535 carries [[Синигами]] and [character=…] tags.
    const raw = "Лайт находит тетрадь [[Синигами]] [character=80]Рюка[/character]. [spoiler=спойлер]Тайна[/spoiler]";
    expect(cleanDescription(raw)).toBe("Лайт находит тетрадь Синигами Рюка. Тайна");
  });

  it("strips HTML and then decodes entities, so escaped text survives as text", () => {
    const raw = "Цитата:<br> &quot;Я — справедливость&quot; &amp; &lt;не только&gt; &#39;это&#39; &amp;lt;";
    expect(cleanDescription(raw)).toBe("Цитата: \"Я — справедливость\" & <не только> 'это' &lt;");
  });

  it("keeps line breaks inside and trims the ends", () => {
    expect(cleanDescription("  Первый абзац.\n\nВторой [b]абзац[/b].  ")).toBe("Первый абзац.\n\nВторой абзац.");
  });

  it("returns null for nothing left to show", () => {
    expect(cleanDescription(null)).toBeNull();
    expect(cleanDescription("   ")).toBeNull();
    expect(cleanDescription("[b][/b]")).toBeNull();
  });
});
```

`web/src/domain/season.test.ts`:

```ts
// Vectors from android/src/test/java/app/kaeru/domain/discover/SeasonTest.kt and the season part
// of android/src/test/java/app/kaeru/ui/common/design/FormatTest.kt.
import { describe, expect, it } from "vitest";
import type { Season } from "./season";
import { currentSeason, sameSeason, seasonApiValue, seasonChips, seasonTitle } from "./season";

// A local wall-clock instant, so the season is the viewer's whatever zone runs the tests.
function local(year: number, month: number, day: number, hour = 0, minute = 0, second = 0): number {
  return new Date(year, month - 1, day, hour, minute, second).getTime();
}

const season = (kind: Season["kind"], year: number): Season => ({ kind, year });

describe("currentSeason", () => {
  it("January through March is winter", () => {
    expect(currentSeason(local(2026, 1, 1))).toEqual(season("winter", 2026));
    expect(currentSeason(local(2026, 3, 31, 23, 59, 59))).toEqual(season("winter", 2026));
  });

  it("April through June is spring", () => {
    expect(currentSeason(local(2026, 4, 1))).toEqual(season("spring", 2026));
    expect(currentSeason(local(2026, 6, 30, 12))).toEqual(season("spring", 2026));
  });

  it("July through September is summer", () => {
    expect(currentSeason(local(2026, 7, 1))).toEqual(season("summer", 2026));
    expect(currentSeason(local(2026, 9, 13, 20))).toEqual(season("summer", 2026));
  });

  it("October through December is fall", () => {
    expect(currentSeason(local(2026, 10, 1))).toEqual(season("fall", 2026));
    expect(currentSeason(local(2026, 12, 31, 23))).toEqual(season("fall", 2026));
  });

  it("is the season of the local calendar across the new year", () => {
    expect(currentSeason(local(2026, 12, 31, 23, 59, 59))).toEqual(season("fall", 2026));
    expect(currentSeason(local(2027, 1, 1, 0, 0, 1))).toEqual(season("winter", 2027));
  });
});

describe("seasonChips", () => {
  it("offers the season before, the one now and the one coming", () => {
    expect(seasonChips(local(2026, 8, 1))).toEqual([season("spring", 2026), season("summer", 2026), season("fall", 2026)]);
    expect(seasonChips(local(2026, 5, 1))).toEqual([season("winter", 2026), season("spring", 2026), season("summer", 2026)]);
  });

  it("crosses the new year without losing a season", () => {
    expect(seasonChips(local(2026, 2, 1))).toEqual([season("fall", 2025), season("winter", 2026), season("spring", 2026)]);
    expect(seasonChips(local(2026, 11, 1))).toEqual([season("summer", 2026), season("fall", 2026), season("winter", 2027)]);
  });
});

describe("seasonApiValue", () => {
  it("is what Shikimori calls the season, with fall rather than autumn", () => {
    expect(seasonApiValue(season("winter", 2026))).toBe("winter_2026");
    expect(seasonApiValue(season("spring", 2026))).toBe("spring_2026");
    expect(seasonApiValue(season("summer", 2026))).toBe("summer_2026");
    expect(seasonApiValue(season("fall", 2026))).toBe("fall_2026");
  });
});

describe("seasonTitle", () => {
  it("names the season and the year", () => {
    expect(seasonTitle(season("winter", 2026))).toBe("Зима 2026");
    expect(seasonTitle(season("spring", 2026))).toBe("Весна 2026");
    expect(seasonTitle(season("summer", 2026))).toBe("Лето 2026");
    expect(seasonTitle(season("fall", 2026))).toBe("Осень 2026");
  });

  it("labels the three chips in sentence case with no separator", () => {
    const titles = seasonChips(local(2026, 10, 15)).map(seasonTitle);
    expect(titles).toEqual(["Лето 2026", "Осень 2026", "Зима 2027"]);
    for (const title of titles) {
      expect(title).not.toContain("·");
      expect(title).not.toBe(title.toUpperCase());
    }
  });
});

describe("sameSeason", () => {
  it("compares kind and year", () => {
    expect(sameSeason(season("fall", 2026), season("fall", 2026))).toBe(true);
    expect(sameSeason(season("fall", 2026), season("fall", 2025))).toBe(false);
    expect(sameSeason(season("fall", 2026), season("summer", 2026))).toBe(false);
  });
});
```

- [ ] **Step 2: Run it to see it fail**

From `web/`:

```bash
npx vitest run src/domain/models.test.ts src/domain/format.test.ts src/domain/season.test.ts
```

**Expected:** FAIL. None of the three suites loads. The error is `Error: Cannot find module './models'` (and the same for `'./format'` and `'./season'`), or Vite's wording `Failed to resolve import "./models"`. The summary reads `Test Files  3 failed (3)` and `Tests  no tests`.

- [ ] **Step 3: Implement**

`web/src/domain/models.ts`:

```ts
// Domain shapes every screen shares. Instants and durations are milliseconds.

export type AiringStatus = "ongoing" | "released" | "anons";
export type ListStatus = "watching" | "planned" | "completed" | "rewatching" | "on_hold" | "dropped";

// «Мой список» tabs in Android order (LibraryTabs.kt).
export const LIST_TABS: readonly ListStatus[] = ["watching", "planned", "completed", "rewatching", "on_hold", "dropped"];

// Title-screen status menu, the order both apps use.
export const STATUS_MENU: readonly ListStatus[] = ["watching", "planned", "completed", "on_hold", "dropped", "rewatching"];

export interface Anime {
  id: number;
  // Russian name, or the original one when Shikimori has no Russian name.
  title: string;
  originalTitle: string;
  posterUrl: string | null;
  backdropUrl: string | null;
  status: AiringStatus;
  // Announced length; 0 means nobody has said (most ongoing shows).
  episodes: number;
  episodesAired: number;
  year: number | null;
  // Shikimori sends "0.0" for unrated titles, so only a value above 0 is a score.
  score: number | null;
  kind: string | null;
  studios: string[];
  description: string | null;
  nextEpisodeAt: number | null;
}

export interface UserRate {
  id: number;
  animeId: number;
  status: ListStatus;
  // Shikimori keeps a count, not a set: episode N is watched when episodes >= N.
  episodes: number;
  updatedAt: number;
}

export interface LibraryEntry {
  anime: Anime;
  rate: UserRate;
}

// Where this browser stopped inside one episode. Lives in localStorage only, never on Shikimori.
export interface EpisodeProgress {
  animeId: number;
  episode: number;
  positionMs: number;
  durationMs: number;
  updatedAt: number;
}

// Episodes that exist to play right now (android domain/model/Anime.kt). Released titles often
// report episodes_aired = 0, so their announced total wins.
export function availableEpisodes(anime: Anime): number {
  switch (anime.status) {
    case "ongoing":
      return anime.episodesAired;
    case "anons":
      return 0;
    case "released":
      return anime.episodes > 0 ? anime.episodes : anime.episodesAired;
  }
}

// Unknown values fall back to "planned", as ListStatus.fromApi does on Android.
export function parseListStatus(raw: string): ListStatus {
  return LIST_TABS.find((status) => status === raw) ?? "planned";
}

// Anything but "ongoing" or "anons" is a finished show (ShikimoriMappers.parseStatus).
export function parseAiringStatus(raw: string): AiringStatus {
  if (raw === "ongoing") return "ongoing";
  if (raw === "anons") return "anons";
  return "released";
}
```

`web/src/domain/format.ts`:

```ts
import type { AiringStatus, Anime, ListStatus } from "./models";

const MINUTE_MS = 60_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

// Russian counts three ways; 11–14 are checked before the last digit (Format.kt plural).
export function plural(n: number, one: string, few: string, many: string): string {
  const abs = Math.abs(n);
  const lastTwo = abs % 100;
  if (lastTwo >= 11 && lastTwo <= 14) return many;
  const last = abs % 10;
  if (last === 1) return one;
  if (last >= 2 && last <= 4) return few;
  return many;
}

export function pluralEpisodes(n: number): string {
  return `${n} ${plural(n, "серия", "серии", "серий")}`;
}

// «Показать ещё 21 серию»: the verb puts the noun in the accusative.
export function pluralEpisodesAccusative(n: number): string {
  return `${n} ${plural(n, "серию", "серии", "серий")}`;
}

// "1:02:34" from an hour up, "14:20" below it; zero, negative and NaN read "0:00".
export function formatTime(ms: number): string {
  if (!(ms > 0)) return "0:00";
  const total = Math.floor(ms / 1000);
  const seconds = String(total % 60).padStart(2, "0");
  const minutes = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3600);
  if (hours > 0) return `${hours}:${String(minutes).padStart(2, "0")}:${seconds}`;
  return `${minutes}:${seconds}`;
}

// Minutes are floored so «14 мин» always means at least fourteen (Format.kt remainingLine).
export function remainingLine(positionMs: number, durationMs: number): string | null {
  if (durationMs <= 0) return null;
  const left = durationMs - positionMs;
  if (left <= 0) return null;
  if (left < MINUTE_MS) return "осталось меньше минуты";
  if (left < HOUR_MS) return `осталось ${Math.floor(left / MINUTE_MS)} мин`;
  const hours = Math.floor(left / HOUR_MS);
  const minutes = Math.floor((left % HOUR_MS) / MINUTE_MS);
  return minutes === 0 ? `осталось ${hours} ч` : `осталось ${hours} ч ${minutes} мин`;
}

// Index of the local calendar day. Date.UTC over the local fields keeps 23 h and 25 h days whole.
function localDay(ms: number): number {
  const date = new Date(ms);
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS;
}

// Whole calendar days as the viewer counts them: 01:15 tomorrow is «завтра» though 4 h away.
export function relativeDay(target: number, now: number): string {
  const days = localDay(target) - localDay(now);
  if (days === 0) return "сегодня";
  if (days === 1) return "завтра";
  if (days === -1) return "вчера";
  if (days > 1) return `через ${days} ${plural(days, "день", "дня", "дней")}`;
  return `${-days} ${plural(days, "день", "дня", "дней")} назад`;
}

export function statusLabel(s: ListStatus): string {
  switch (s) {
    case "watching":
      return "Смотрю";
    case "planned":
      return "В планах";
    case "completed":
      return "Завершено";
    case "rewatching":
      return "Пересматриваю";
    case "on_hold":
      return "Отложено";
    case "dropped":
      return "Брошено";
  }
}

export function airingLabel(s: AiringStatus): string {
  switch (s) {
    case "ongoing":
      return "Онгоинг";
    case "released":
      return "Вышло";
    case "anons":
      return "Анонс";
  }
}

// iOS DetailView.kindTitle; unknown kinds such as "cm" or "pv" are shown upper-cased.
export function kindLabel(kind: string): string {
  switch (kind) {
    case "tv":
      return "Сериал";
    case "movie":
      return "Фильм";
    case "ova":
      return "OVA";
    case "ona":
      return "ONA";
    case "special":
    case "tv_special":
      return "Спецвыпуск";
    case "music":
      return "Музыкальное видео";
    default:
      return kind.toUpperCase();
  }
}

// Shikimori writes scores with a point ("8.62", "9.0"); a whole number keeps its ".0".
function scoreText(score: number): string {
  return Number.isInteger(score) ? score.toFixed(1) : String(score);
}

// One line in the iOS order: status · year · length · score · aired so far · kind · studios.
export function factsLine(anime: Anime): string {
  const parts: string[] = [airingLabel(anime.status)];
  if (anime.year !== null) parts.push(String(anime.year));
  if (anime.episodes > 0) parts.push(`${anime.episodes} эп.`);
  if (anime.score !== null && anime.score > 0) parts.push(`★ ${scoreText(anime.score)}`);
  if (anime.status === "ongoing") parts.push(`вышло ${anime.episodesAired}`);
  if (anime.kind !== null && anime.kind.trim() !== "") parts.push(kindLabel(anime.kind));
  const studios = anime.studios.filter((name) => name.trim() !== "");
  if (studios.length > 0) parts.push(studios.join(", "));
  return parts.join(" · ");
}

// [[Синигами]] is a wiki link: keep the word (iOS dropped it, Android kept the brackets).
const WIKI_LINK = /\[\[([^\]]+)\]\]/g;
// BBCode open and close tags with an optional =value (ShikimoriMappers.kt); inner text stays.
const BB_TAG = /\[\/?[a-z_]+(?:=[^\]]*)?\]/g;
const HTML_TAG = /<[^>]+>/g;

export function cleanDescription(raw: string | null): string | null {
  if (raw === null) return null;
  const text = raw
    .replace(WIKI_LINK, "$1")
    .replace(BB_TAG, "")
    .replace(HTML_TAG, "")
    // Entities after the tags, so "&lt;b&gt;" survives as text; &amp; last, so "&amp;lt;" stays "&lt;".
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
    .trim();
  return text === "" ? null : text;
}

// Card badge. It names one episode, so the noun never changes: «21 серия», «22 серия».
export function episodeBadge(n: number): string {
  return `${n} серия`;
}
```

`web/src/domain/season.ts`:

```ts
// Broadcast seasons, as android domain/discover/Season.kt computes them.

export type SeasonKind = "winter" | "spring" | "summer" | "fall";

export interface Season {
  kind: SeasonKind;
  year: number;
}

// Quarter of the local date: January starts winter, April spring, July summer, October fall.
export function currentSeason(now: number): Season {
  const date = new Date(now);
  const month = date.getMonth();
  const kind: SeasonKind = month < 3 ? "winter" : month < 6 ? "spring" : month < 9 ? "summer" : "fall";
  return { kind, year: date.getFullYear() };
}

function previous(season: Season): Season {
  switch (season.kind) {
    case "winter":
      return { kind: "fall", year: season.year - 1 };
    case "spring":
      return { kind: "winter", year: season.year };
    case "summer":
      return { kind: "spring", year: season.year };
    case "fall":
      return { kind: "summer", year: season.year };
  }
}

function next(season: Season): Season {
  switch (season.kind) {
    case "winter":
      return { kind: "spring", year: season.year };
    case "spring":
      return { kind: "summer", year: season.year };
    case "summer":
      return { kind: "fall", year: season.year };
    case "fall":
      return { kind: "winter", year: season.year + 1 };
  }
}

// The season that just ended, the one airing and the one coming, left to right.
export function seasonChips(now: number): [Season, Season, Season] {
  const current = currentSeason(now);
  return [previous(current), current, next(current)];
}

// Shikimori's `season` filter value. "autumn_2026" gets HTTP 422, so the fourth one is "fall".
export function seasonApiValue(s: Season): string {
  return `${s.kind}_${s.year}`;
}

function seasonName(kind: SeasonKind): string {
  switch (kind) {
    case "winter":
      return "Зима";
    case "spring":
      return "Весна";
    case "summer":
      return "Лето";
    case "fall":
      return "Осень";
  }
}

export function seasonTitle(s: Season): string {
  return `${seasonName(s.kind)} ${s.year}`;
}

export function sameSeason(a: Season, b: Season): boolean {
  return a.kind === b.kind && a.year === b.year;
}
```

- [ ] **Step 4: Run to see it pass**

From `web/`:

```bash
npx vitest run src/domain/models.test.ts src/domain/format.test.ts src/domain/season.test.ts
npm test
npm run typecheck
```

**Expected:**
- The first command reports `Test Files  3 passed (3)` and `Tests  46 passed (46)`.
- `npm test` passes every suite, including Task 1's.
- `npm run typecheck` exits 0 and prints nothing.

- [ ] **Step 5: Commit**

From the repo root `/Users/vitaliy/Projects/kaeru`:

```bash
git add web/src/domain/models.ts web/src/domain/models.test.ts web/src/domain/format.ts web/src/domain/format.test.ts web/src/domain/season.ts web/src/domain/season.test.ts
git commit -m "feat(web): доменные модели, форматирование и сезоны"
```

---

### Task 3: Progress, continue target, actions

**Decisions:**
- `continueTarget` derives three values, as Android's `LibraryEntry.continueTarget` does: `aired = availableEpisodes(anime)`, `announced = anime.episodes` and `finishedAiring = anime.status === "released"`. `progress` holds only this title's rows, because the caller passes `progressOf(animeId)`. Nothing filters by `animeId`.
- `primaryAction` reads its `anime` argument, because the title screen has fresher details such as `nextEpisodeAt`. It takes only `rate` from `entry`. A `null` entry becomes the rate `{ id: 0, animeId, status: "watching", episodes: 0, updatedAt: 0 }`.
- A disabled action keeps `episode` as the awaited episode, with `positionMs` 0. Android used `null` there. Callers decide on `enabled`.
- The rewatch action is «Пересмотреть» with episode 1 and no number in the label (Android `REWATCH`). The "continue" and "next" lines of `episodeLine` reuse `episodeBadge`.
- Test vectors are ported one to one:
  - Android's `WatchState` pointer becomes a plain progress row with the same episode and position.
  - `ContinueTarget.of(rate, aired, announced, …)` becomes an entry whose anime carries those numbers. Its status is "ongoing", or "released" with announced 0 when the vector sets `finishedAiring`.

**Files:**
- Create: `web/src/domain/progress.ts`
- Create: `web/src/domain/actions.ts`
- Test: `web/src/domain/progress.test.ts`
- Test: `web/src/domain/actions.test.ts`

**Interfaces:**
- Consumes (Task 2):
  - `models.ts`: `Anime`, `UserRate`, `LibraryEntry`, `EpisodeProgress`, `ListStatus`, `availableEpisodes`
  - `format.ts`: `formatTime`, `remainingLine`, `relativeDay`, `pluralEpisodes`, `episodeBadge`
- Produces:
  - `progress.ts`:
    - `DEFAULT_THRESHOLD = 0.9`
    - `progressFraction(p: EpisodeProgress): number`
    - `isStarted(p: EpisodeProgress): boolean`
    - `isFinished(p: EpisodeProgress, threshold: number): boolean`
    - `interface ContinueTarget { episode: number; positionMs: number; rewatch: boolean }`
    - `continueTarget(entry: LibraryEntry, progress: readonly EpisodeProgress[], threshold: number): ContinueTarget`
    - `episodeFraction(entry: LibraryEntry, progress: readonly EpisodeProgress[], episode: number, threshold: number): number | null`
    - `lastWatchedAt(progress: readonly EpisodeProgress[]): number | null`
    - `progressAt(progress: readonly EpisodeProgress[], episode: number): EpisodeProgress | undefined`
  - `actions.ts`:
    - `interface PrimaryAction { label: string; episode: number; positionMs: number; enabled: boolean }`
    - `primaryAction(entry: LibraryEntry | null, anime: Anime, progress: readonly EpisodeProgress[], threshold: number, now: number): PrimaryAction`
    - `waitingLabel(anime: Anime, episode: number, now: number): string`
    - `type FeedKind = "continue" | "new" | "next" | "upcoming" | "planned"`
    - `episodeLine(kind: FeedKind, entry: LibraryEntry, episode: number, progress: readonly EpisodeProgress[], now: number): string`

- [ ] **Step 1: Write the failing test**

`web/src/domain/progress.test.ts`:

```ts
// Vectors from android/src/test/java/app/kaeru/domain/playback/ContinueTargetTest.kt,
// android/src/test/java/app/kaeru/ui/common/details/EpisodeGridTest.kt (strips) and
// android/src/test/java/app/kaeru/domain/feed/HomeFeedBuilderTest.kt (lastWatchedAt).
import { describe, expect, it } from "vitest";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus } from "./models";
import {
  DEFAULT_THRESHOLD,
  continueTarget,
  episodeFraction,
  isFinished,
  isStarted,
  lastWatchedAt,
  progressAt,
  progressFraction,
} from "./progress";

// Twenty-four minutes, the length of an ordinary episode.
const EPISODE_MS = 1_440_000;
const threshold = 0.9;

function p(episode: number, positionMs: number, durationMs = EPISODE_MS, updatedAt = 0): EpisodeProgress {
  return { animeId: 100, episode, positionMs, durationMs, updatedAt };
}

// Android passes aired, announced and finishedAiring directly; here they come from the anime.
// "released" is used only with announced 0, where availableEpisodes falls back to episodesAired,
// so aired stays exactly what the Android vector says.
function entry(
  watched: number,
  aired: number,
  announced: number,
  opts: { status?: ListStatus; finishedAiring?: boolean } = {},
): LibraryEntry {
  const anime: Anime = {
    id: 100,
    title: "Тест",
    originalTitle: "Test",
    posterUrl: null,
    backdropUrl: null,
    status: opts.finishedAiring === true ? "released" : "ongoing",
    episodes: announced,
    episodesAired: aired,
    year: null,
    score: null,
    kind: null,
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
  return { anime, rate: { id: 1, animeId: 100, status: opts.status ?? "watching", episodes: watched, updatedAt: 0 } };
}

const resumeAt = (episode: number, positionMs: number) => ({ episode, positionMs, rewatch: false });
const fromTop = (episode: number) => ({ episode, positionMs: 0, rewatch: false });
const REWATCH = { episode: 1, positionMs: 0, rewatch: true };
const allFinished = (count: number) => Array.from({ length: count }, (_, i) => p(i + 1, 1_400_000));

describe("episode progress", () => {
  it("defaults the watched threshold to 90 %", () => {
    expect(DEFAULT_THRESHOLD).toBe(0.9);
  });

  it("measures the fraction clamped to 0..1, and 0 without a duration", () => {
    expect(progressFraction(p(1, 700_000, 1_400_000))).toBe(0.5);
    expect(progressFraction(p(1, 2_000_000))).toBe(1);
    expect(progressFraction(p(1, -5_000))).toBe(0);
    expect(progressFraction(p(1, 120_000, 0))).toBe(0);
  });

  it("counts a minute in as started however long the episode runs", () => {
    expect(isStarted(p(5, 60_000, 7_200_000))).toBe(true);
    expect(isStarted(p(5, 59_999, 7_200_000))).toBe(false);
    expect(isStarted(p(5, 120_000, 0))).toBe(true);
  });

  it("lets a short episode qualify on the share rather than the minute", () => {
    expect(isStarted(p(5, 3_600, 180_000))).toBe(true);
    expect(isStarted(p(5, 3_000, 180_000))).toBe(false);
    expect(isStarted(p(5, 10_000))).toBe(false);
  });

  it("treats reaching the threshold as finishing", () => {
    // 1_296_000 is exactly 90 % of EPISODE_MS.
    expect(isFinished(p(5, 1_296_000), threshold)).toBe(true);
    expect(isFinished(p(5, 1_295_000), threshold)).toBe(false);
    expect(isFinished(p(5, 1_000_000, 0), threshold)).toBe(false);
  });
});

describe("continueTarget", () => {
  it("a mis-tap on an earlier episode does not move the pointer off the one being watched", () => {
    const target = continueTarget(entry(6, 10, 24), [p(7, 2_400_000, 2_880_000), p(6, 10_000)], threshold);
    expect(target).toEqual(resumeAt(7, 2_400_000));
  });

  it("an episode watched to the end hands over to the next one, from the beginning", () => {
    expect(continueTarget(entry(6, 10, 24), [p(7, 1_400_000)], threshold)).toEqual(fromTop(8));
  });

  it("with nothing started the next episode after Shikimori's count is offered", () => {
    expect(continueTarget(entry(6, 10, 24), [], threshold)).toEqual(fromTop(7));
  });

  it("an announcement with nothing aired has nothing to resume", () => {
    expect(continueTarget(entry(0, 0, 12), [p(1, 600_000)], threshold)).toEqual(fromTop(1));
  });

  it("an episode that has not aired is never resumed from", () => {
    expect(continueTarget(entry(8, 8, 12), [p(9, 600_000)], threshold)).toEqual(fromTop(9));
  });

  it("the latest unfinished episode wins over an earlier one", () => {
    const rows = [p(4, 600_000), p(9, 300_000), p(6, 900_000)];
    expect(continueTarget(entry(3, 12, 12), rows, threshold)).toEqual(resumeAt(9, 300_000));
  });

  it("a minute in counts as started however long the episode runs", () => {
    const film = 7_200_000;
    expect(continueTarget(entry(4, 12, 12), [p(5, 60_000, film)], threshold)).toEqual(resumeAt(5, 60_000));
    expect(continueTarget(entry(4, 12, 12), [p(5, 59_999, film)], threshold)).toEqual(fromTop(5));
  });

  it("a short episode qualifies on the share rather than the minute", () => {
    const short = 180_000;
    expect(continueTarget(entry(4, 12, 12), [p(5, 3_600, short)], threshold)).toEqual(resumeAt(5, 3_600));
    expect(continueTarget(entry(4, 12, 12), [p(5, 3_000, short)], threshold)).toEqual(fromTop(5));
  });

  it("the watched threshold is the boundary, and reaching it is finishing", () => {
    expect(continueTarget(entry(4, 12, 12), [p(5, 1_296_000)], threshold)).toEqual(fromTop(6));
    expect(continueTarget(entry(4, 12, 12), [p(5, 1_295_000)], threshold)).toEqual(resumeAt(5, 1_295_000));
  });

  it("a mis-tap on a later episode does not cost the earlier one its position", () => {
    const target = continueTarget(entry(6, 10, 24), [p(7, 2_400_000, 2_880_000), p(9, 10_000)], threshold);
    expect(target).toEqual(resumeAt(7, 2_400_000));
  });

  it("the finale watched early does not carry the pointer past the episodes in between", () => {
    expect(continueTarget(entry(3, 12, 12), [p(12, 1_400_000)], threshold)).toEqual(fromTop(4));
  });

  it("a show finished to the last episode is offered from the top rather than from the end", () => {
    expect(continueTarget(entry(12, 12, 12), allFinished(12), threshold)).toEqual(REWATCH);
  });

  it("a season nobody has measured is never a season that has run out", () => {
    expect(continueTarget(entry(8, 8, 0), [p(8, 1_400_000)], threshold)).toEqual(fromTop(9));
  });

  it("a rewatcher starts where their own counter says, not where the last time round ended", () => {
    const target = continueTarget(entry(0, 12, 12, { status: "rewatching" }), allFinished(12), threshold);
    expect(target).toEqual(fromTop(1));
  });

  it("a rewatcher part-way through an episode is still returned to it", () => {
    const rows = [p(1, 1_400_000), p(2, 1_400_000), p(3, 600_000)];
    expect(continueTarget(entry(2, 12, 12, { status: "rewatching" }), rows, threshold)).toEqual(resumeAt(3, 600_000));
  });

  it("an episode finished locally moves the pointer on before Shikimori has heard about it", () => {
    const rows = [p(4, 1_400_000), p(5, 1_400_000), p(6, 1_400_000)];
    expect(continueTarget(entry(3, 12, 12), rows, threshold)).toEqual(fromTop(7));
  });

  it("a position in an episode Shikimori already counted is spent", () => {
    expect(continueTarget(entry(8, 12, 12), [p(6, 700_000)], threshold)).toEqual(fromTop(9));
  });

  it("an episode with no known duration still resumes once a minute is behind it", () => {
    expect(continueTarget(entry(4, 12, 12), [p(5, 120_000, 0)], threshold)).toEqual(resumeAt(5, 120_000));
  });

  it("a released show with everything watched and nothing on this device offers it from the top", () => {
    expect(continueTarget(entry(12, 12, 12, { status: "completed" }), [], threshold)).toEqual(REWATCH);
  });

  it("a rewatcher who has reached the last episode is offered the show from the top again", () => {
    expect(continueTarget(entry(12, 12, 12, { status: "rewatching" }), [], threshold)).toEqual(REWATCH);
  });

  it("a season still airing has not run out, however far ahead the count has got", () => {
    expect(continueTarget(entry(8, 8, 12), [], threshold)).toEqual(fromTop(9));
    expect(continueTarget(entry(12, 8, 12), [], threshold)).toEqual(fromTop(13));
  });

  it("a season that ran past its announced length keeps offering the episodes it grew", () => {
    expect(continueTarget(entry(12, 13, 12), [], threshold)).toEqual(fromTop(13));
  });

  it("an announcement is not a show that has been finished", () => {
    expect(continueTarget(entry(0, 0, 12), [], threshold)).toEqual(fromTop(1));
  });

  it("a finished show whose length nobody recorded has still ended", () => {
    const target = continueTarget(entry(24, 24, 0, { status: "completed", finishedAiring: true }), [], threshold);
    expect(target).toEqual(REWATCH);
  });

  it("a show of unknown length that nobody has called finished is waited for, not restarted", () => {
    expect(continueTarget(entry(24, 24, 0), [], threshold)).toEqual(fromTop(25));
  });

  it("a finished show of unknown length still hands a viewer the episode they are on", () => {
    expect(continueTarget(entry(10, 24, 0, { finishedAiring: true }), [], threshold)).toEqual(fromTop(11));
  });
});

describe("episodeFraction", () => {
  // EpisodeGridTest: 28 announced, 24 aired, 20 counted, 1_400_000 ms episodes.
  const grid = entry(20, 24, 28);
  const row = (episode: number, positionMs: number, durationMs = 1_400_000) => p(episode, positionMs, durationMs);

  it("shows on the episode in progress and nowhere else", () => {
    const rows = [row(21, 700_000)];
    expect(episodeFraction(grid, rows, 21, threshold)).toBeCloseTo(0.5, 3);
    expect(episodeFraction(grid, rows, 20, threshold)).toBeNull();
    expect(episodeFraction(grid, rows, 22, threshold)).toBeNull();
  });

  it("gives every episode with a position its own strip", () => {
    const rows = [row(21, 700_000), row(23, 350_000)];
    expect(episodeFraction(grid, rows, 21, threshold)).toBeCloseTo(0.5, 3);
    expect(episodeFraction(grid, rows, 23, threshold)).toBeCloseTo(0.25, 3);
    expect(episodeFraction(grid, rows, 22, threshold)).toBeNull();
  });

  it("draws nothing for a mis-tap or an episode finished here", () => {
    expect(episodeFraction(grid, [row(22, 10_000)], 22, threshold)).toBeNull();
    expect(episodeFraction(grid, [row(21, 1_350_000)], 21, threshold)).toBeNull();
  });

  it("draws nothing inside an episode Shikimori already counted", () => {
    expect(episodeFraction(entry(22, 24, 28), [row(21, 700_000)], 21, threshold)).toBeNull();
  });

  it("draws nothing under 1 % or without a duration", () => {
    // android domain/model/LibraryEntry.kt episodeFraction: started, but 60 s of two hours is 0.8 %.
    expect(episodeFraction(grid, [row(21, 60_000, 7_200_000)], 21, threshold)).toBeNull();
    expect(episodeFraction(grid, [row(21, 120_000, 0)], 21, threshold)).toBeNull();
  });
});

describe("lastWatchedAt and progressAt", () => {
  const day = 86_400_000;
  const now = 1_800_000_000_000;

  it("ignores a mis-tap even when it is the newest row", () => {
    // HomeFeedBuilderTest: 40 % of ep 7 three days ago, 0.5 % of ep 4 a minute ago.
    const rows = [p(7, 400_000, 1_000_000, now - 3 * day), p(4, 5_000, 1_000_000, now - 60_000)];
    expect(lastWatchedAt(rows)).toBe(now - 3 * day);
  });

  it("takes the newest started row across every episode", () => {
    const rows = [p(2, 400_000, 1_000_000, now - day), p(5, 400_000, 1_000_000, now - 300_000)];
    expect(lastWatchedAt(rows)).toBe(now - 300_000);
  });

  it("is null when nothing was really watched", () => {
    expect(lastWatchedAt([])).toBeNull();
    expect(lastWatchedAt([p(1, 10_000)])).toBeNull();
  });

  it("finds the row of one episode", () => {
    const rows = [p(3, 100_000), p(4, 200_000)];
    expect(progressAt(rows, 4)).toEqual(p(4, 200_000));
    expect(progressAt(rows, 5)).toBeUndefined();
  });
});
```

`web/src/domain/actions.test.ts`:

```ts
// Vectors from android/src/test/java/app/kaeru/ui/common/design/FormatTest.kt (primaryAction,
// waitingLabel, episodeLine). Android's WatchState pointer becomes a plain progress row here.
import { describe, expect, it } from "vitest";
import type { FeedKind } from "./actions";
import { episodeLine, primaryAction, waitingLabel } from "./actions";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus } from "./models";
import { availableEpisodes } from "./models";

// A local wall-clock instant: day names follow the viewer's calendar in any test zone.
function at(year: number, month: number, day: number, hour: number, minute: number): number {
  return new Date(year, month - 1, day, hour, minute).getTime();
}

// 12 April 2026, 21:30 — an ordinary evening on the sofa.
const now = at(2026, 4, 12, 21, 30);
const tomorrowEvening = at(2026, 4, 13, 18, 0);
const THRESHOLD = 0.9;

function anime(overrides: Partial<Anime> = {}): Anime {
  return {
    id: 21,
    title: "Магическая битва",
    originalTitle: "Jujutsu Kaisen",
    posterUrl: null,
    backdropUrl: null,
    status: "ongoing",
    episodes: 12,
    episodesAired: 8,
    year: 2026,
    score: 8.6,
    kind: "tv",
    studios: ["MAPPA"],
    description: null,
    nextEpisodeAt: null,
    ...overrides,
  };
}

function entry(a: Anime = anime(), watched = 6, status: ListStatus = "watching"): LibraryEntry {
  return { anime: a, rate: { id: 1, animeId: a.id, status, episodes: watched, updatedAt: 0 } };
}

function stopped(episode: number, positionMs: number, durationMs = 1_440_000): EpisodeProgress {
  return { animeId: 21, episode, positionMs, durationMs, updatedAt: 0 };
}

const finishedRows = (count: number) => Array.from({ length: count }, (_, i) => stopped(i + 1, 1_400_000));

function act(e: LibraryEntry, progress: readonly EpisodeProgress[] = []) {
  return primaryAction(e, e.anime, progress, THRESHOLD, now);
}

describe("primaryAction", () => {
  it("starts an untouched title at the first episode", () => {
    expect(act(entry(anime(), 0))).toEqual({ label: "Смотреть 1 серию", episode: 1, positionMs: 0, enabled: true });
  });

  it("continues a title in progress at the next episode", () => {
    expect(act(entry(anime(), 6))).toEqual({ label: "Продолжить 7 серию", episode: 7, positionMs: 0, enabled: true });
  });

  it("continues a half-watched episode from its timecode", () => {
    expect(act(entry(anime(), 6), [stopped(7, 860_000)])).toEqual({
      label: "Продолжить с 14:20",
      episode: 7,
      positionMs: 860_000,
      enabled: true,
    });
  });

  it("moves on once an episode is watched past the threshold", () => {
    expect(act(entry(anime(), 6), [stopped(7, 1_400_000)]).label).toBe("Продолжить 8 серию");
  });

  it("still offers the last episode that aired", () => {
    expect(act(entry(anime(), 7))).toEqual({ label: "Продолжить 8 серию", episode: 8, positionMs: 0, enabled: true });
  });

  it("names an unaired episode with its date and cannot be pressed", () => {
    const action = act(entry(anime({ nextEpisodeAt: tomorrowEvening }), 8));
    expect(action).toEqual({ label: "9 серия выйдет завтра", episode: 9, positionMs: 0, enabled: false });
  });

  it("counts the days to an episode further out", () => {
    const action = act(entry(anime({ nextEpisodeAt: at(2026, 4, 15, 18, 0) }), 8));
    expect(action.label).toBe("9 серия выйдет через 3 дня");
  });

  it("simply awaits an episode with no date, or with a date already gone by", () => {
    expect(act(entry(anime(), 8)).label).toBe("Ждём 9 серию");
    const overdue = act(entry(anime({ nextEpisodeAt: at(2026, 4, 11, 18, 0) }), 8));
    expect(overdue.label).toBe("Ждём 9 серию");
    expect(overdue.enabled).toBe(false);
  });

  it("offers a rewatch of a finished show with everything watched", () => {
    const done = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 12, "completed");
    expect(act(done)).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
  });

  it("offers a rewatcher at the last episode the show from the top", () => {
    const again = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 12, "rewatching");
    expect(act(again)).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
  });

  it("keeps an ongoing season waiting however far the count has got", () => {
    const caughtUp = act(entry(anime({ episodes: 12, episodesAired: 8 }), 8));
    expect(caughtUp.label).toBe("Ждём 9 серию");
    expect(caughtUp.enabled).toBe(false);
    expect(act(entry(anime({ episodes: 12, episodesAired: 8 }), 12)).label).toBe("Ждём 13 серию");
  });

  it("offers the rewatch for a finished show whose length nobody recorded", () => {
    const done = entry(anime({ status: "released", episodes: 0, episodesAired: 24 }), 24, "completed");
    expect(act(done)).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
    const airing = entry(anime({ status: "ongoing", episodes: 0, episodesAired: 24 }), 24);
    expect(act(airing).label).toBe("Ждём 25 серию");
  });

  it("offers nothing to press for an announcement with nothing aired", () => {
    const anons = act(entry(anime({ status: "anons", episodes: 0, episodesAired: 0 }), 0));
    expect(anons.label).toBe("Ещё не вышло");
    expect(anons.enabled).toBe(false);
  });

  it("names the day an announcement arrives", () => {
    const anons = act(entry(anime({ status: "anons", episodes: 12, episodesAired: 0, nextEpisodeAt: tomorrowEvening }), 0));
    expect(anons.label).toBe("1 серия выйдет завтра");
    expect(anons.enabled).toBe(false);
  });

  it("takes the timecode from the episode being continued, not the one opened last", () => {
    const action = act(entry(anime(), 6), [stopped(7, 860_000), stopped(6, 10_000)]);
    expect(action.label).toBe("Продолжить с 14:20");
    expect(action.episode).toBe(7);
  });

  it("never offers to continue ten seconds of an episode", () => {
    const action = act(entry(anime(), 6), [stopped(7, 10_000)]);
    expect(action.label).toBe("Продолжить 7 серию");
    expect(action.episode).toBe(7);
  });

  it("offers a show with every episode behind the viewer from the top", () => {
    const finished = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 12);
    expect(act(finished, finishedRows(12))).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
  });

  it("starts the show over for a rewatch that has reset the count", () => {
    const rewatching = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 0, "rewatching");
    expect(act(rewatching, finishedRows(12))).toEqual({ label: "Смотреть 1 серию", episode: 1, positionMs: 0, enabled: true });
  });

  it("says «Смотреть» for an episode finished here the last time round", () => {
    // Format.kt `seen`: a rewatcher on 4 whose row for 4 is finished is watching it again.
    const rewatching = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 3, "rewatching");
    expect(act(rewatching, finishedRows(12)).label).toBe("Смотреть 4 серию");
  });

  it("waits for the next episode of a caught-up show of unannounced length", () => {
    const caughtUp = entry(anime({ episodes: 0, episodesAired: 8, nextEpisodeAt: tomorrowEvening }), 8);
    const action = act(caughtUp, [stopped(8, 1_400_000)]);
    expect(action.label).toBe("9 серия выйдет завтра");
    expect(action.enabled).toBe(false);
  });

  it("does not skip the episodes in between when the finale was watched early", () => {
    const jumped = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 3);
    const action = act(jumped, [stopped(12, 1_400_000)]);
    expect(action.label).toBe("Продолжить 4 серию");
    expect(action.episode).toBe(4);
  });

  it("is pressable exactly when its episode has aired", () => {
    const cases = [
      entry(anime({ episodesAired: 8, nextEpisodeAt: tomorrowEvening }), 8),
      entry(anime({ episodesAired: 8 }), 8),
      entry(anime({ status: "anons", episodes: 0, episodesAired: 0 }), 0),
      entry(anime(), 6),
    ];
    for (const e of cases) {
      const action = act(e);
      expect(action.enabled).toBe(action.episode <= availableEpisodes(e.anime));
      if (!action.enabled) expect(action.positionMs).toBe(0);
    }
  });

  it("reads the anime it is given rather than the list card", () => {
    // The list card has no next_episode_at; the details passed in do.
    const listed = entry(anime(), 8);
    const details = anime({ nextEpisodeAt: tomorrowEvening });
    expect(primaryAction(listed, details, [], THRESHOLD, now).label).toBe("9 серия выйдет завтра");
  });

  it("treats a title in no list as nothing counted", () => {
    const released = anime({ status: "released", episodes: 12, episodesAired: 12 });
    expect(primaryAction(null, released, [], THRESHOLD, now)).toEqual({
      label: "Смотреть 1 серию",
      episode: 1,
      positionMs: 0,
      enabled: true,
    });
    const announced = anime({ status: "anons", episodes: 0, episodesAired: 0 });
    expect(primaryAction(null, announced, [], THRESHOLD, now)).toEqual({
      label: "Ещё не вышло",
      episode: 1,
      positionMs: 0,
      enabled: false,
    });
    expect(primaryAction(null, released, [stopped(3, 600_000)], THRESHOLD, now).label).toBe("Продолжить с 10:00");
  });
});

describe("waitingLabel", () => {
  it("names a dated episode still to come by its day", () => {
    expect(waitingLabel(anime({ nextEpisodeAt: tomorrowEvening }), 9, now)).toBe("9 серия выйдет завтра");
    expect(waitingLabel(anime({ nextEpisodeAt: at(2026, 4, 15, 18, 0) }), 9, now)).toBe("9 серия выйдет через 3 дня");
  });

  it("simply awaits an episode with no date", () => {
    expect(waitingLabel(anime(), 9, now)).toBe("Ждём 9 серию");
  });

  it("does not repeat a date already past", () => {
    expect(waitingLabel(anime({ nextEpisodeAt: at(2026, 4, 10, 18, 0) }), 9, now)).toBe("Ждём 9 серию");
  });

  it("says «Ещё не вышло» when nothing has aired", () => {
    expect(waitingLabel(anime({ episodesAired: 0 }), 1, now)).toBe("Ещё не вышло");
  });
});

describe("episodeLine", () => {
  it("says how much of a started episode is left, after a comma", () => {
    expect(episodeLine("continue", entry(), 7, [stopped(7, 600_000)], now)).toBe("7 серия, осталось 14 мин");
  });

  it("lets the episode stand alone without a remembered position", () => {
    expect(episodeLine("continue", entry(), 7, [], now)).toBe("7 серия");
    expect(episodeLine("continue", entry(), 7, [stopped(7, 120_000, 0)], now)).toBe("7 серия");
  });

  it("announces a fresh episode", () => {
    expect(episodeLine("new", entry(), 7, [], now)).toBe("Вышла 7 серия");
  });

  it("names the next episode by its number", () => {
    expect(episodeLine("next", entry(), 7, [], now)).toBe("7 серия");
  });

  it("carries the day of an episode still to air", () => {
    const dated = entry(anime({ nextEpisodeAt: tomorrowEvening }));
    expect(episodeLine("upcoming", dated, 9, [], now)).toBe("9 серия завтра");
    expect(episodeLine("upcoming", entry(), 9, [], now)).toBe("9 серия скоро");
  });

  it("reports the season of a planned title, not an episode", () => {
    expect(episodeLine("planned", entry(anime(), 0, "planned"), 1, [], now)).toBe("В планах, 12 серий");
    const unknownLength = entry(anime({ episodes: 0, episodesAired: 0 }), 0, "planned");
    expect(episodeLine("planned", unknownLength, 1, [], now)).toBe("В планах");
  });

  it("never joins facts with a middle dot", () => {
    const kinds: FeedKind[] = ["continue", "new", "next", "upcoming", "planned"];
    for (const kind of kinds) {
      expect(episodeLine(kind, entry(), 7, [stopped(7, 600_000)], now)).not.toContain("·");
    }
  });
});
```

- [ ] **Step 2: Run it to see it fail**

From `web/`:

```bash
npx vitest run src/domain/progress.test.ts src/domain/actions.test.ts
```

**Expected:** FAIL. Neither suite loads. The error is `Error: Cannot find module './progress'` (and the same for `'./actions'`), or Vite's wording `Failed to resolve import "./progress"`. The summary reads `Test Files  2 failed (2)` and `Tests  no tests`.

- [ ] **Step 3: Implement**

`web/src/domain/progress.ts`:

```ts
import { availableEpisodes } from "./models";
import type { EpisodeProgress, LibraryEntry } from "./models";

// Share of an episode that counts as watched, the default on both apps.
export const DEFAULT_THRESHOLD = 0.9;

// A minute in is watching whatever the length; a fiftieth covers three-minute shorts.
const STARTED_MS = 60_000;
const STARTED_FRACTION = 0.02;
// A strip under 1 % says nothing a viewer can read.
const MIN_STRIP = 0.01;

export function progressFraction(p: EpisodeProgress): number {
  if (p.durationMs <= 0) return 0;
  return Math.min(1, Math.max(0, p.positionMs / p.durationMs));
}

// Below both bars is a mis-tap, and a mis-tap must never become the episode to continue.
export function isStarted(p: EpisodeProgress): boolean {
  return p.positionMs >= STARTED_MS || (p.durationMs > 0 && p.positionMs / p.durationMs >= STARTED_FRACTION);
}

export function isFinished(p: EpisodeProgress, threshold: number): boolean {
  return progressFraction(p) >= threshold;
}

export interface ContinueTarget {
  episode: number;
  // 0 when the episode starts from the top; only a position earns «Продолжить с m:ss».
  positionMs: number;
  // The whole show is behind the viewer and episode 1 is a rewatch.
  rewatch: boolean;
}

// Port of android domain/playback/ContinueTarget.of. `progress` holds this title's rows only.
export function continueTarget(
  entry: LibraryEntry,
  progress: readonly EpisodeProgress[],
  threshold: number,
): ContinueTarget {
  const { anime, rate } = entry;
  const counted = rate.episodes;
  const aired = availableEpisodes(anime);
  const announced = anime.episodes;
  const started = progress.filter(isStarted);

  // Resume the highest started, unfinished episode that has aired and Shikimori has not counted.
  let resume: EpisodeProgress | undefined;
  for (const row of started) {
    const inRange = row.episode > counted && row.episode <= aired;
    if (inRange && !isFinished(row, threshold) && (resume === undefined || row.episode > resume.episode)) {
      resume = row;
    }
  }
  if (resume !== undefined) return { episode: resume.episode, positionMs: resume.positionMs, rewatch: false };

  // Step over episodes finished here that Shikimori has not heard about, never across a gap.
  // A rewatcher's finished rows are from the last time round, so they get no walk.
  const finishedHere = new Set<number>();
  if (rate.status !== "rewatching") {
    for (const row of started) {
      if (isFinished(row, threshold)) finishedHere.add(row.episode);
    }
  }
  let next = counted + 1;
  while (finishedHere.has(next)) next += 1;

  // Not clamped to aired: an unaired target is how the button says «Ждём 9 серию».
  // A released show of unknown length has still ended (LibraryEntry.continueTarget passes it).
  const finishedAiring = anime.status === "released";
  const runEnded = (announced > 0 && aired >= announced) || (finishedAiring && aired > 0);
  if (runEnded && next > Math.max(announced, aired)) return { episode: 1, positionMs: 0, rewatch: true };
  return { episode: next, positionMs: 0, rewatch: false };
}

export function progressAt(progress: readonly EpisodeProgress[], episode: number): EpisodeProgress | undefined {
  return progress.find((row) => row.episode === episode);
}

// Strip width for one episode, or null when it is counted, unopened, mis-tapped, finished or under 1 %.
export function episodeFraction(
  entry: LibraryEntry,
  progress: readonly EpisodeProgress[],
  episode: number,
  threshold: number,
): number | null {
  if (episode <= entry.rate.episodes) return null;
  const row = progressAt(progress, episode);
  if (row === undefined || !isStarted(row) || isFinished(row, threshold)) return null;
  const fraction = progressFraction(row);
  return fraction >= MIN_STRIP ? fraction : null;
}

// When the title was last really watched. Mis-taps are ignored so they cannot reorder «Продолжить».
export function lastWatchedAt(progress: readonly EpisodeProgress[]): number | null {
  let latest: number | null = null;
  for (const row of progress) {
    if (isStarted(row) && (latest === null || row.updatedAt > latest)) latest = row.updatedAt;
  }
  return latest;
}
```

`web/src/domain/actions.ts`:

```ts
import { episodeBadge, formatTime, pluralEpisodes, relativeDay, remainingLine } from "./format";
import { availableEpisodes } from "./models";
import type { Anime, EpisodeProgress, LibraryEntry, UserRate } from "./models";
import { continueTarget, isFinished, progressAt } from "./progress";

export interface PrimaryAction {
  label: string;
  episode: number;
  positionMs: number;
  enabled: boolean;
}

export type FeedKind = "continue" | "new" | "next" | "upcoming" | "planned";

// No episode number: a rewatch offers the show, not «1 серию».
const REWATCH = "Пересмотреть";

// The watch button (android ui/common/design/Format.kt primaryAction). `anime` wins over
// entry.anime because the title screen holds fresher details; only the rate comes from the entry.
export function primaryAction(
  entry: LibraryEntry | null,
  anime: Anime,
  progress: readonly EpisodeProgress[],
  threshold: number,
  now: number,
): PrimaryAction {
  // A title in no list behaves as a rate with nothing counted.
  const rate: UserRate = entry?.rate ?? { id: 0, animeId: anime.id, status: "watching", episodes: 0, updatedAt: 0 };
  const target = continueTarget({ anime, rate }, progress, threshold);
  const next = target.episode;
  if (target.positionMs > 0) {
    return { label: `Продолжить с ${formatTime(target.positionMs)}`, episode: next, positionMs: target.positionMs, enabled: true };
  }

  const aired = availableEpisodes(anime);
  if (next <= aired) {
    if (target.rewatch) return { label: REWATCH, episode: next, positionMs: 0, enabled: true };
    // «Продолжить» promises an episode still ahead; one already finished here is watched again.
    const row = progressAt(progress, next);
    const seen = next <= rate.episodes || (row !== undefined && isFinished(row, threshold));
    const label = next <= 1 || seen ? `Смотреть ${next} серию` : `Продолжить ${next} серию`;
    return { label, episode: next, positionMs: 0, enabled: true };
  }
  // Disabled: the episode is named in the label and kept for callers, but nothing plays.
  return { label: waitingLabel(anime, next, now), episode: next, positionMs: 0, enabled: false };
}

// What to say about an episode that cannot start yet. A date already past is the catalogue
// lagging, so it is not repeated as a promise.
export function waitingLabel(anime: Anime, episode: number, now: number): string {
  const date = anime.nextEpisodeAt;
  if (date !== null && date >= now) return `${episode} серия выйдет ${relativeDay(date, now)}`;
  if (availableEpisodes(anime) <= 0) return "Ещё не вышло";
  return `Ждём ${episode} серию`;
}

// Status line of a feed item (Format.kt episodeLine). Facts join with a comma, never a middle dot.
export function episodeLine(
  kind: FeedKind,
  entry: LibraryEntry,
  episode: number,
  progress: readonly EpisodeProgress[],
  now: number,
): string {
  const anime = entry.anime;
  switch (kind) {
    case "continue": {
      const row = progressAt(progress, episode);
      const left = row === undefined ? null : remainingLine(row.positionMs, row.durationMs);
      return left === null ? episodeBadge(episode) : `${episodeBadge(episode)}, ${left}`;
    }
    case "new":
      return `Вышла ${episode} серия`;
    case "next":
      return episodeBadge(episode);
    case "upcoming": {
      const day = anime.nextEpisodeAt === null ? "скоро" : relativeDay(anime.nextEpisodeAt, now);
      return `${episode} серия ${day}`;
    }
    case "planned": {
      const season = anime.episodes > 0 ? anime.episodes : availableEpisodes(anime);
      return season > 0 ? `В планах, ${pluralEpisodes(season)}` : "В планах";
    }
  }
}
```

- [ ] **Step 4: Run to see it pass**

From `web/`:

```bash
npx vitest run src/domain/progress.test.ts src/domain/actions.test.ts
npm test
npm run typecheck
```

**Expected:**
- The first command reports `Test Files  2 passed (2)` and `Tests  75 passed (75)`.
- `npm test` passes every suite, including Task 2's 46 tests.
- `npm run typecheck` exits 0 and prints nothing.

- [ ] **Step 5: Commit**

From the repo root `/Users/vitaliy/Projects/kaeru`:

```bash
git add web/src/domain/progress.ts web/src/domain/progress.test.ts web/src/domain/actions.ts web/src/domain/actions.test.ts
git commit -m "feat(web): цель «Продолжить», прогресс серий и подпись кнопки просмотра"
```

---

### Task 4: Feed builder and home rows

**Decisions:**
- `catalogueCard` falls back to `null`, not to a studio, when nothing has aired. The contract asks for this. Android's vector «a title that has not started says who is making it» ("Madhouse") therefore expects `null` here.
- `Card.key` is `${animeId}` when the card has no badge and `${animeId}:${badge}` when it has one (HomeRows.kt `HomeCard.key`).
- Sorts rely on `Array.prototype.sort` being stable, so titles with equal keys keep their input order. Kotlin's `sortedBy` and `sortedByDescending` behave the same way.
- An upcoming card whose `nextEpisodeAt` is `null` says «скоро» (HomeRows.kt `SOON`).
- Android's `WatchState` pointer is not ported. On the web the per-episode rows from `progressOf` are the only source of positions. The Android test cases that pass a `watch` get an equivalent progress row instead.
- The tests pin `now` to a local wall-clock time (`new Date(2026, 8, 13, 20, 0)`). This keeps the calendar-day copy («завтра») the same in any time zone.

**Files:**
- Create: `web/src/domain/feed.ts`
- Test: `web/src/domain/feed.test.ts`

**Interfaces:**
- Consumes:
  - Task 2, `models.ts`: `Anime`, `LibraryEntry`, `EpisodeProgress`, `AiringStatus`, `ListStatus`, `availableEpisodes(anime)`.
  - Task 2, `format.ts`: `episodeBadge(n)`, `pluralEpisodes(n)`, `relativeDay(target, now)`, `remainingLine(positionMs, durationMs)`.
  - Task 3, `progress.ts`: `ContinueTarget`, `continueTarget(entry, progress, threshold)`, `episodeFraction(entry, progress, episode, threshold)`, `lastWatchedAt(progress)`, `progressAt(progress, episode)`.
  - Task 3, `actions.ts`: `FeedKind`.
- Produces (`web/src/domain/feed.ts`):
  - `FeedItem`, `HomeFeed`, `isFeedEmpty(feed)`
  - `buildFeed(entries, progressOf, now, threshold)`
  - `Card`, `Row`, `feedRows(feed, progressOf, now, threshold)`
  - `catalogueCard(anime)`
  - `UPCOMING_WINDOW_MS`

- [ ] **Step 1: Write the failing test**

`web/src/domain/feed.test.ts`:

```ts
// Vectors ported from android/src/test/java/app/kaeru/domain/feed/HomeFeedBuilderTest.kt and
// android/src/test/java/app/kaeru/ui/common/home/HomeRowsTest.kt. Android's WatchState pointer is
// folded into the per-episode progress rows: the web keeps positions only per episode.
import { describe, expect, it } from "vitest";
import type { FeedKind } from "./actions";
import { buildFeed, catalogueCard, feedRows, isFeedEmpty, UPCOMING_WINDOW_MS } from "./feed";
import type { Card, FeedItem, HomeFeed, Row } from "./feed";
import type { AiringStatus, Anime, EpisodeProgress, LibraryEntry, ListStatus } from "./models";

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const DEFAULT = 0.9;
// Local wall-clock time, so calendar-day copy («завтра») holds in any time zone.
const NOW = new Date(2026, 8, 13, 20, 0, 0).getTime();

interface AnimeSpec {
  status?: AiringStatus;
  episodes?: number;
  aired?: number;
  next?: number | null;
}

function anime(id: number, spec: AnimeSpec = {}): Anime {
  const episodes = spec.episodes ?? 12;
  return {
    id,
    title: `Аниме ${id}`,
    originalTitle: `Anime ${id}`,
    posterUrl: `https://poster/${id}.jpg`,
    backdropUrl: null,
    status: spec.status ?? "released",
    episodes,
    episodesAired: spec.aired ?? episodes,
    year: 2026,
    score: 8,
    kind: "tv",
    studios: ["Madhouse"],
    description: null,
    nextEpisodeAt: spec.next ?? null,
  };
}

interface RateSpec {
  status?: ListStatus;
  watched?: number;
  updatedAt?: number;
}

function entry(a: Anime, spec: RateSpec = {}): LibraryEntry {
  return {
    anime: a,
    rate: {
      id: a.id,
      animeId: a.id,
      status: spec.status ?? "watching",
      episodes: spec.watched ?? 0,
      updatedAt: spec.updatedAt ?? NOW,
    },
  };
}

/** A stop at `fraction` of a 1 000 000 ms episode, as Android's `stopped` helper writes it. */
function stopped(animeId: number, episode: number, fraction: number, at = NOW): EpisodeProgress {
  return { animeId, episode, positionMs: Math.round(fraction * 1_000_000), durationMs: 1_000_000, updatedAt: at };
}

type Seed = readonly [LibraryEntry, EpisodeProgress[]?];

function build(seeds: readonly Seed[], threshold = DEFAULT): HomeFeed {
  const rows = new Map<number, EpisodeProgress[]>(
    seeds.map(([e, p]): [number, EpisodeProgress[]] => [e.anime.id, p ?? []]),
  );
  return buildFeed(seeds.map(([e]) => e), (id) => rows.get(id) ?? [], NOW, threshold);
}

const ids = (items: readonly FeedItem[]) => items.map((item) => item.entry.anime.id);

const ONGOING_24_10: AnimeSpec = { status: "ongoing", episodes: 24, aired: 10 };

describe("buildFeed", () => {
  // --- positions kept per episode ---

  it("keeps the card on the episode being watched after a mis-tap on an earlier one", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { watched: 6 }), [stopped(1, 7, 0.4, NOW - 2 * HOUR), stopped(1, 6, 0.01)]]]);
    expect(feed.top?.kind).toBe("continue");
    expect(feed.top?.episode).toBe(7);
  });

  it("makes no card of an episode nobody really started", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { watched: 6 }), [stopped(1, 7, 0.01)]]]);
    expect(feed.continueWatching).toEqual([]);
    expect(feed.top?.kind).toBe("new");
    expect(feed.top?.episode).toBe(7);
  });

  it("orders «Продолжить» by when each title was last watched", () => {
    const older = anime(1, ONGOING_24_10);
    const fresher = anime(2, ONGOING_24_10);
    const feed = build([
      [entry(older, { watched: 6 }), [stopped(1, 7, 0.4, NOW - 2 * DAY)]],
      [entry(fresher, { watched: 3 }), [stopped(2, 4, 0.4, NOW - 5 * MINUTE)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([2, 1]);
  });

  it("raises a title when any of its episodes is touched, not only the target", () => {
    const revisited = anime(1, ONGOING_24_10);
    const untouched = anime(2, ONGOING_24_10);
    const feed = build([
      [
        entry(revisited, { watched: 5 }),
        [stopped(1, 7, 0.4, NOW - 2 * DAY), stopped(1, 6, 0.3, NOW - 5 * MINUTE)],
      ],
      [entry(untouched, { watched: 3 }), [stopped(2, 4, 0.4, NOW - DAY)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([1, 2]);
    expect(feed.continueWatching[0]?.episode).toBe(7);
  });

  it("does not carry a mis-tapped title to the head of the row", () => {
    const misTapped = anime(1, ONGOING_24_10);
    const watched = anime(2, ONGOING_24_10);
    const feed = build([
      [
        entry(misTapped, { watched: 6 }),
        [stopped(1, 7, 0.4, NOW - 3 * DAY), stopped(1, 4, 0.005, NOW - MINUTE)],
      ],
      [entry(watched, { watched: 3 }), [stopped(2, 4, 0.4, NOW - DAY)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([2, 1]);
    expect(feed.continueWatching[feed.continueWatching.length - 1]?.episode).toBe(7);
  });

  it("prefers a later unfinished episode to an earlier one", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { watched: 3 }), [stopped(1, 4, 0.4), stopped(1, 9, 0.2)]]]);
    expect(feed.continueWatching.map((item) => item.episode)).toEqual([9]);
  });

  it("puts an unfinished episode first in «Продолжить» and in the hero", () => {
    const feed = build([[entry(anime(1), { watched: 4 }), [stopped(1, 5, 0.4)]]]);
    expect(feed.continueWatching).toHaveLength(1);
    expect(feed.continueWatching[0]?.episode).toBe(5);
    expect(feed.top?.kind).toBe("continue");
    expect(feed.top?.episode).toBe(5);
  });

  it("obeys the threshold the caller passes", () => {
    const seeds: Seed[] = [[entry(anime(1, ONGOING_24_10), { watched: 5 }), [stopped(1, 6, 0.85)]]];

    const strict = build(seeds, 0.9);
    expect(strict.top?.kind).toBe("continue");
    expect(strict.top?.episode).toBe(6);

    const lenient = build(seeds, 0.8);
    expect(lenient.top?.kind).toBe("new");
    expect(lenient.top?.episode).toBe(7);
  });

  it("moves past an episode watched beyond the threshold before Shikimori hears of it", () => {
    const feed = build([[entry(anime(1), { watched: 4 }), [stopped(1, 5, 0.95)]]]);
    expect(feed.continueWatching).toEqual([]);
    expect(feed.top?.kind).toBe("next");
    expect(feed.top?.episode).toBe(6);
  });

  it("lists ongoing titles with aired episodes ahead of the count as new episodes", () => {
    const ongoing = anime(2, { status: "ongoing", episodes: 24, aired: 7 });
    const caughtUp = anime(3, { status: "ongoing", episodes: 24, aired: 7 });
    const feed = build([[entry(ongoing, { watched: 6 })], [entry(caughtUp, { watched: 7 })]]);
    expect(ids(feed.newEpisodes)).toEqual([2]);
    expect(feed.newEpisodes[0]?.episode).toBe(7);
    expect(feed.newEpisodes[0]?.kind).toBe("new");
  });

  it("lets a new episode beat next-up for the hero when nothing is in progress", () => {
    const ongoing = anime(2, { status: "ongoing", episodes: 24, aired: 7 });
    const released = anime(1);
    const feed = build([[entry(released, { watched: 3 })], [entry(ongoing, { watched: 6 })]]);
    expect(feed.top?.entry.anime.id).toBe(2);
    expect(feed.top?.kind).toBe("new");
  });

  it("sorts next-up released titles by recent list activity", () => {
    const feed = build([
      [entry(anime(1), { watched: 3, updatedAt: NOW - 3 * DAY })],
      [entry(anime(2), { watched: 1, updatedAt: NOW - HOUR })],
    ]);
    expect(ids(feed.nextUp)).toEqual([2, 1]);
    expect(feed.nextUp[0]?.episode).toBe(2);
  });

  it("never puts an announcement into next-up", () => {
    const announced = anime(1, { status: "anons", episodes: 12, aired: 0 });
    const feed = build([[entry(announced, { watched: 0 })]]);
    expect(feed.nextUp).toEqual([]);
  });

  it("lists ongoing titles whose next episode lands within the window as upcoming", () => {
    const soon = anime(2, { status: "ongoing", episodes: 24, aired: 7, next: NOW + 2 * DAY });
    const far = anime(3, { status: "ongoing", episodes: 24, aired: 7, next: NOW + 20 * DAY });
    const feed = build([[entry(soon, { watched: 7 })], [entry(far, { watched: 7 })]]);
    expect(ids(feed.upcoming)).toEqual([2]);
    expect(feed.upcoming[0]?.episode).toBe(8);
  });

  it("fills «В планах» with planned titles and never the hero", () => {
    const feed = build([[entry(anime(9), { status: "planned" })]]);
    expect(feed.planned).toHaveLength(1);
    expect(feed.planned[0]?.episode).toBe(1);
    expect(feed.top).toBeNull();
  });

  it("ignores completed and dropped titles", () => {
    const feed = build([
      [entry(anime(1), { status: "completed", watched: 12 })],
      [entry(anime(2), { status: "dropped", watched: 2 })],
    ]);
    expect(isFeedEmpty(feed)).toBe(true);
  });

  it("drops a fully watched released title from next-up", () => {
    const feed = build([[entry(anime(1, { episodes: 12 }), { watched: 12 })]]);
    expect(feed.nextUp).toEqual([]);
  });

  it("drops a released title finished on this device from next-up too", () => {
    const all = Array.from({ length: 12 }, (_, i) => stopped(1, i + 1, 0.95));
    const feed = build([[entry(anime(1, { episodes: 12 }), { watched: 6 }), all]]);
    expect(feed.nextUp).toEqual([]);
    expect(feed.top).toBeNull();
  });

  it("keeps a rewatcher who reset the count in next-up from the first episode", () => {
    const all = Array.from({ length: 12 }, (_, i) => stopped(1, i + 1, 0.95));
    const feed = build([[entry(anime(1, { episodes: 12 }), { status: "rewatching", watched: 0 }), all]]);
    expect(ids(feed.nextUp)).toEqual([1]);
    expect(feed.nextUp[0]?.episode).toBe(1);
  });

  // --- a position is what puts a title in «Продолжить», whatever the list says ---

  it("leads «Продолжить» with a started episode of a planned title", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "planned", watched: 0 }), [stopped(1, 2, 0.4)]]]);
    expect(ids(feed.continueWatching)).toEqual([1]);
    expect(feed.continueWatching[0]?.episode).toBe(2);
    expect(feed.top?.kind).toBe("continue");
  });

  it("keeps a title shelved mid-episode in «Продолжить»", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "on_hold", watched: 1 }), [stopped(1, 2, 0.4)]]]);
    expect(feed.continueWatching.map((item) => item.episode)).toEqual([2]);
  });

  it("orders «Продолжить» by the newest position across every list status", () => {
    const planned = anime(1, ONGOING_24_10);
    const watching = anime(2, ONGOING_24_10);
    const feed = build([
      [entry(planned, { status: "planned", watched: 0 }), [stopped(1, 2, 0.4, NOW - 5 * MINUTE)]],
      [entry(watching, { watched: 3 }), [stopped(2, 4, 0.4, NOW - DAY)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([1, 2]);
  });

  it("does not offer a planned title being watched as something to plan", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "planned", watched: 0 }), [stopped(1, 2, 0.4)]]]);
    expect(feed.planned).toEqual([]);
  });

  it("leaves a planned title nobody opened in «В планах» alone", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "planned", watched: 0 })]]);
    expect(feed.continueWatching).toEqual([]);
    expect(ids(feed.planned)).toEqual([1]);
  });

  it("never offers to continue a title marked completed mid-episode", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { status: "completed", watched: 4 }), [stopped(1, 5, 0.4)]]]);
    expect(feed.continueWatching).toEqual([]);
    expect(feed.top).toBeNull();
  });

  // --- the same rules as map 1 states them for the web ---

  it("keeps a continued title in «Скоро» as well", () => {
    const a = anime(1, { status: "ongoing", episodes: 24, aired: 7, next: NOW + DAY });
    const feed = build([[entry(a, { watched: 6 }), [stopped(1, 7, 0.4)]]]);
    expect(ids(feed.continueWatching)).toEqual([1]);
    expect(feed.newEpisodes).toEqual([]);
    expect(ids(feed.upcoming)).toEqual([1]);
    expect(feed.upcoming[0]?.episode).toBe(8);
  });

  it("keeps the upcoming window open at both ends", () => {
    const soonAt = (id: number, next: number) =>
      entry(anime(id, { status: "ongoing", episodes: 24, aired: 7, next }), { watched: 7 });
    const feed = build([
      [soonAt(1, NOW)],
      [soonAt(2, NOW + UPCOMING_WINDOW_MS)],
      [soonAt(3, NOW + UPCOMING_WINDOW_MS - 1)],
      [soonAt(4, NOW + 1)],
    ]);
    expect(ids(feed.upcoming)).toEqual([4, 3]);
    expect(UPCOMING_WINDOW_MS).toBe(604_800_000);
  });

  it("sorts new episodes by next episode date, falling back to the rate's update", () => {
    const make = (id: number, next: number | null, updatedAt: number) =>
      entry(anime(id, { status: "ongoing", episodes: 24, aired: 7, next }), { watched: 6, updatedAt });
    const feed = build([
      [make(1, NOW + DAY, NOW - 5 * DAY)],
      [make(2, null, NOW - HOUR)],
      [make(3, NOW + 3 * DAY, NOW - 10 * DAY)],
    ]);
    expect(ids(feed.newEpisodes)).toEqual([3, 1, 2]);
  });

  it("sorts «В планах» by the rate's update and starts each at episode 1", () => {
    const feed = build([
      [entry(anime(1), { status: "planned", updatedAt: NOW - 3 * DAY })],
      [entry(anime(2), { status: "planned", updatedAt: NOW - HOUR })],
    ]);
    expect(ids(feed.planned)).toEqual([2, 1]);
    expect(feed.planned.map((item) => [item.episode, item.kind])).toEqual([
      [1, "planned"],
      [1, "planned"],
    ]);
  });

  it("never makes an upcoming title the hero", () => {
    const a = anime(1, { status: "ongoing", episodes: 24, aired: 7, next: NOW + DAY });
    const feed = build([[entry(a, { watched: 7 })]]);
    expect(ids(feed.upcoming)).toEqual([1]);
    expect(feed.top).toBeNull();
    expect(isFeedEmpty(feed)).toBe(false);
  });
});

// --- rows and cards (HomeRowsTest) ---

function show(id: number, spec: AnimeSpec = {}): Anime {
  return anime(id, { status: "ongoing", episodes: 12, aired: 8, ...spec });
}

function watching(id: number, spec: AnimeSpec = {}): LibraryEntry {
  return entry(show(id, spec), { watched: 6 });
}

function item(e: LibraryEntry, episode: number, kind: FeedKind): FeedItem {
  return { entry: e, episode, kind };
}

function feedOf(parts: Partial<HomeFeed>): HomeFeed {
  return { top: null, newEpisodes: [], continueWatching: [], nextUp: [], upcoming: [], planned: [], ...parts };
}

function pos(animeId: number, episode: number, positionMs: number, durationMs = 1_440_000): EpisodeProgress {
  return { animeId, episode, positionMs, durationMs, updatedAt: NOW };
}

function rowsOf(feed: HomeFeed, progress: Record<number, EpisodeProgress[]> = {}): Row[] {
  return feedRows(feed, (id) => progress[id] ?? [], NOW, DEFAULT);
}

function onlyCard(rows: readonly Row[]): Card {
  expect(rows).toHaveLength(1);
  const [row] = rows;
  expect(row?.cards).toHaveLength(1);
  const card = row?.cards[0];
  if (!card) throw new Error("the row has no card");
  return card;
}

describe("feedRows", () => {
  it("keeps the rows in Android's order", () => {
    const titles = rowsOf(
      feedOf({
        top: item(watching(2), 7, "continue"),
        continueWatching: [item(watching(2), 7, "continue")],
        newEpisodes: [item(watching(1), 7, "new")],
        nextUp: [item(watching(3, { status: "released" }), 7, "next")],
        upcoming: [item(watching(4, { next: NOW + DAY }), 9, "upcoming")],
        planned: [item(watching(5), 1, "planned")],
      }),
      { 2: [pos(2, 7, 60_000)] },
    ).map((row) => row.title);

    expect(titles).toEqual(["Новые серии", "Продолжить", "Дальше по списку", "Скоро", "В планах"]);
  });

  it("builds only the rows with items in them", () => {
    const rows = rowsOf(feedOf({ planned: [item(watching(5), 1, "planned")] }));
    expect(rows.map((row) => row.title)).toEqual(["В планах"]);
  });

  it("names the episode on a new-episode card and says nothing else", () => {
    const card = onlyCard(rowsOf(feedOf({ newEpisodes: [item(watching(1), 7, "new")] })));
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBeNull();
    expect(card.progress).toBeNull();
    expect(card.key).toBe("1:7 серия");
  });

  it("carries the title, poster and id of its anime", () => {
    const card = onlyCard(rowsOf(feedOf({ newEpisodes: [item(watching(1), 7, "new")] })));
    expect(card.animeId).toBe(1);
    expect(card.title).toBe("Аниме 1");
    expect(card.posterUrl).toBe("https://poster/1.jpg");
  });

  it("shows how far into a continued episode the viewer is and how much is left", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), { 2: [pos(2, 7, 600_000)] }),
    );
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBe("осталось 14 мин");
    expect(card.progress).toBeCloseTo(600_000 / 1_440_000, 3);
  });

  it("describes the badged episode, not the one opened last", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), {
        2: [pos(2, 7, 600_000), pos(2, 6, 10_000)],
      }),
    );
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBe("осталось 14 мин");
    expect(card.progress).toBeCloseTo(600_000 / 1_440_000, 3);
  });

  it("drops the strip of an episode past the threshold", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), { 2: [pos(2, 7, 1_400_000)] }),
    );
    expect(card.progress).toBeNull();
  });

  it("promises no time for an episode of unknown length", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), { 2: [pos(2, 7, 600_000, 0)] }),
    );
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBeNull();
  });

  it("says which day an upcoming episode arrives", () => {
    const card = onlyCard(rowsOf(feedOf({ upcoming: [item(watching(4, { next: NOW + 6 * HOUR }), 9, "upcoming")] })));
    expect(card.badge).toBe("9 серия");
    expect(card.subtitle).toBe("завтра");
  });

  it("still says something about an upcoming episode with no date", () => {
    const card = onlyCard(rowsOf(feedOf({ upcoming: [item(watching(4), 9, "upcoming")] })));
    expect(card.subtitle).toBe("скоро");
  });

  it("offers a planned title's season length instead of an episode number", () => {
    const card = onlyCard(rowsOf(feedOf({ planned: [item(watching(5, { episodes: 24 }), 1, "planned")] })));
    expect(card.badge).toBeNull();
    expect(card.subtitle).toBe("24 серии");
    expect(card.key).toBe("5");
  });

  it("says nothing about a planned title of unknown length", () => {
    const card = onlyCard(rowsOf(feedOf({ planned: [item(watching(5, { episodes: 0, aired: 0 }), 1, "planned")] })));
    expect(card.subtitle).toBeNull();
  });

  it("never joins facts with a middle dot", () => {
    const rows = rowsOf(
      feedOf({
        continueWatching: [item(watching(2), 7, "continue")],
        newEpisodes: [item(watching(1), 7, "new")],
        nextUp: [item(watching(3, { status: "released" }), 7, "next")],
        upcoming: [item(watching(4, { next: NOW + 3 * DAY }), 9, "upcoming")],
        planned: [item(watching(5, { episodes: 24 }), 1, "planned")],
      }),
      { 2: [pos(2, 7, 600_000)] },
    );
    const text = rows.flatMap((row) => [
      row.title,
      ...row.cards.flatMap((card) => [card.badge, card.subtitle].filter((s): s is string => s !== null)),
    ]);
    expect(text.length).toBeGreaterThan(5);
    for (const line of text) expect(line).not.toContain("·");
  });
});

describe("catalogueCard", () => {
  it("says how much of the show there is to watch", () => {
    expect(catalogueCard(show(1, { episodes: 12, aired: 8 })).subtitle).toBe("8 серий");
  });

  it("counts the whole season of a finished show", () => {
    expect(catalogueCard(show(1, { status: "released", episodes: 24, aired: 24 })).subtitle).toBe("24 серии");
  });

  it("says nothing about a title with nothing aired", () => {
    // Android falls back to the studio here; the web contract says null.
    expect(catalogueCard(show(1, { status: "anons", episodes: 12, aired: 0 })).subtitle).toBeNull();
  });

  it("carries no badge and no progress, keyed by the id", () => {
    expect(catalogueCard(show(1))).toEqual({
      key: "1",
      animeId: 1,
      title: "Аниме 1",
      posterUrl: "https://poster/1.jpg",
      badge: null,
      subtitle: "8 серий",
      progress: null,
    });
  });
});

describe("isFeedEmpty", () => {
  it("counts the hero, «Скоро» and «В планах» and nothing else", () => {
    const e = entry(anime(1));
    expect(isFeedEmpty(feedOf({}))).toBe(true);
    expect(isFeedEmpty(feedOf({ top: item(e, 1, "next") }))).toBe(false);
    expect(isFeedEmpty(feedOf({ upcoming: [item(e, 2, "upcoming")] }))).toBe(false);
    expect(isFeedEmpty(feedOf({ planned: [item(e, 1, "planned")] }))).toBe(false);
  });
});
```

- [ ] **Step 2: Run it to see it fail**

From `web/`: `npx vitest run src/domain/feed.test.ts`

**Expected:** FAIL, `Error: Failed to resolve import "./feed" from "src/domain/feed.test.ts". Does the file exist?`

- [ ] **Step 3: Implement**

`web/src/domain/feed.ts`:

```ts
import type { FeedKind } from "./actions";
import { episodeBadge, pluralEpisodes, relativeDay, remainingLine } from "./format";
import { availableEpisodes } from "./models";
import type { Anime, EpisodeProgress, LibraryEntry } from "./models";
import { continueTarget, episodeFraction, lastWatchedAt, progressAt } from "./progress";
import type { ContinueTarget } from "./progress";

/** «Скоро» looks one week ahead, open at both ends (HomeFeedBuilder.upcomingWindow). */
export const UPCOMING_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

const NEW_EPISODES = "Новые серии";
const CONTINUE = "Продолжить";
const NEXT_UP = "Дальше по списку";
const UPCOMING = "Скоро";
const PLANNED = "В планах";
/** What an upcoming card says when the catalogue has no date for the next episode. */
const SOON = "скоро";

export interface FeedItem {
  entry: LibraryEntry;
  episode: number;
  kind: FeedKind;
}

export interface HomeFeed {
  top: FeedItem | null;
  newEpisodes: FeedItem[];
  continueWatching: FeedItem[];
  nextUp: FeedItem[];
  upcoming: FeedItem[];
  planned: FeedItem[];
}

/** One card with every word already decided, so what it says can be read in a test. */
export interface Card {
  key: string;
  animeId: number;
  title: string;
  posterUrl: string | null;
  badge: string | null;
  subtitle: string | null;
  progress: number | null;
}

export interface Row {
  title: string;
  cards: Card[];
}

/** Nothing worth drawing a home screen around («Скачано» does not exist on the web). */
export function isFeedEmpty(feed: HomeFeed): boolean {
  return feed.top === null && feed.planned.length === 0 && feed.upcoming.length === 0;
}

interface Targeted {
  entry: LibraryEntry;
  progress: readonly EpisodeProgress[];
  target: ContinueTarget;
}

function isActive(entry: LibraryEntry): boolean {
  return entry.rate.status === "watching" || entry.rate.status === "rewatching";
}

// Array.prototype.sort is stable, so equal keys keep input order like Kotlin's sortedByDescending.
function sortedDescending<T>(items: readonly T[], key: (item: T) => number): T[] {
  return [...items].sort((a, b) => key(b) - key(a));
}

function feedItem(entry: LibraryEntry, episode: number, kind: FeedKind): FeedItem {
  return { entry, episode, kind };
}

export function buildFeed(
  entries: readonly LibraryEntry[],
  progressOf: (animeId: number) => readonly EpisodeProgress[],
  now: number,
  threshold: number,
): HomeFeed {
  // One target per title, shared by every row, so no two rows can disagree about the episode.
  const targeted: Targeted[] = entries.map((entry) => {
    const progress = progressOf(entry.anime.id);
    return { entry, progress, target: continueTarget(entry, progress, threshold) };
  });
  const active = targeted.filter((t) => isActive(t.entry));

  // A local position puts a title here whatever its list status, except «Завершено»: the viewer
  // said they are done, and the title screen writes that status without touching the count.
  const continueWatching = sortedDescending(
    targeted.filter((t) => t.target.positionMs > 0 && t.entry.rate.status !== "completed"),
    // When the title was last really watched; lastWatchedAt ignores mis-taps.
    (t) => lastWatchedAt(t.progress) ?? 0,
  ).map((t) => feedItem(t.entry, t.target.episode, "continue"));
  const inProgress = new Set(continueWatching.map((item) => item.entry.anime.id));

  const newEpisodes = sortedDescending(
    active.filter(
      (t) =>
        t.entry.anime.status === "ongoing" &&
        !t.target.rewatch &&
        t.target.episode <= t.entry.anime.episodesAired &&
        !inProgress.has(t.entry.anime.id),
    ),
    (t) => t.entry.anime.nextEpisodeAt ?? t.entry.rate.updatedAt,
  ).map((t) => feedItem(t.entry, t.target.episode, "new"));

  // A show with every episode behind the viewer has no «next»; the title screen offers a rewatch.
  const nextUp = sortedDescending(
    active.filter(
      (t) =>
        t.entry.anime.status !== "ongoing" &&
        !t.target.rewatch &&
        t.target.episode <= availableEpisodes(t.entry.anime) &&
        !inProgress.has(t.entry.anime.id),
    ),
    (t) => t.entry.rate.updatedAt,
  ).map((t) => feedItem(t.entry, t.target.episode, "next"));

  // In-progress titles stay: a title can be both continued and waiting for its next episode.
  const horizon = now + UPCOMING_WINDOW_MS;
  const upcoming = active
    .filter((t) => {
      const next = t.entry.anime.nextEpisodeAt;
      return t.entry.anime.status === "ongoing" && next !== null && next > now && next < horizon;
    })
    .sort((a, b) => (a.entry.anime.nextEpisodeAt ?? 0) - (b.entry.anime.nextEpisodeAt ?? 0))
    .map((t) => feedItem(t.entry, t.entry.anime.episodesAired + 1, "upcoming"));

  // One title, one card: a planned title already being continued is not offered again.
  const planned = sortedDescending(
    entries.filter((entry) => entry.rate.status === "planned" && !inProgress.has(entry.anime.id)),
    (entry) => entry.rate.updatedAt,
  ).map((entry) => feedItem(entry, 1, "planned"));

  const top = continueWatching[0] ?? newEpisodes[0] ?? nextUp[0] ?? null;
  return { top, newEpisodes, continueWatching, nextUp, upcoming, planned };
}

export function feedRows(
  feed: HomeFeed,
  progressOf: (animeId: number) => readonly EpisodeProgress[],
  now: number,
  threshold: number,
): Row[] {
  const rows: Array<[string, FeedItem[]]> = [
    [NEW_EPISODES, feed.newEpisodes],
    [CONTINUE, feed.continueWatching],
    [NEXT_UP, feed.nextUp],
    [UPCOMING, feed.upcoming],
    [PLANNED, feed.planned],
  ];
  // A heading with nothing under it says nothing, so empty rows are never built.
  return rows
    .filter(([, items]) => items.length > 0)
    .map(([title, items]) => ({
      title,
      cards: items.map((item) => feedCard(item, progressOf(item.entry.anime.id), now, threshold)),
    }));
}

/** A catalogue card: artwork, name and one fact about the size of the show. */
export function catalogueCard(anime: Anime): Card {
  const available = availableEpisodes(anime);
  return card(anime, null, available > 0 ? pluralEpisodes(available) : null, null);
}

// A card says only what its row cannot; the strip and the badge describe the same episode.
function feedCard(
  item: FeedItem,
  progress: readonly EpisodeProgress[],
  now: number,
  threshold: number,
): Card {
  const { entry, episode } = item;
  switch (item.kind) {
    case "new":
    case "next":
      return card(entry.anime, episodeBadge(episode), null, null);
    case "continue": {
      const row = progressAt(progress, episode);
      return card(
        entry.anime,
        episodeBadge(episode),
        row ? remainingLine(row.positionMs, row.durationMs) : null,
        episodeFraction(entry, progress, episode, threshold),
      );
    }
    case "upcoming": {
      const at = entry.anime.nextEpisodeAt;
      return card(entry.anime, episodeBadge(episode), at === null ? SOON : relativeDay(at, now), null);
    }
    case "planned":
      return card(entry.anime, null, seasonLength(entry.anime), null);
  }
}

function card(anime: Anime, badge: string | null, subtitle: string | null, progress: number | null): Card {
  return {
    key: badge === null ? `${anime.id}` : `${anime.id}:${badge}`,
    animeId: anime.id,
    title: anime.title,
    posterUrl: anime.posterUrl,
    badge,
    subtitle,
    progress,
  };
}

/** «24 серии» for a planned title, or null while the catalogue does not know the length. */
function seasonLength(anime: Anime): string | null {
  const n = anime.episodes > 0 ? anime.episodes : availableEpisodes(anime);
  return n > 0 ? pluralEpisodes(n) : null;
}
```

- [ ] **Step 4: Run to see it pass**

From `web/`:
1. `npx vitest run src/domain/feed.test.ts`. **Expected:** PASS, `Test Files 1 passed`, `Tests 48 passed`.
2. `npm test`. **Expected:** every test file passes and none fail.
3. `npm run typecheck`. **Expected:** exits 0 with no output.

- [ ] **Step 5: Commit**

```bash
cd /Users/vitaliy/Projects/kaeru
git add web/src/domain/feed.ts web/src/domain/feed.test.ts
git commit -m "feat(web): лента главной — ряды, герой и карточки по правилам Android"
```

---

---

### Task 5: HTTP and Shikimori API

**Decisions:**
- `ApiError` and `NetworkError` declare their fields in the class body rather than as constructor parameter properties. This keeps them valid under `erasableSyntaxOnly`. Their public shape is still exactly the contract's: `new ApiError(status, body = null)`, `.status`, `.body`.
- A 2xx response whose body is not JSON throws a plain `Error("Invalid API response")`, which `errorMessage` turns into the generic copy. An empty 2xx body reads as `null`.
- If the caller aborts, the abort reason (`AbortError`) is rethrown and never becomes `NetworkError`. Only a timeout or a `fetch` that throws becomes `NetworkError`. The retry after a 429 waits 1000 ms in a sleep that the same abort cancels.
- Mapping from Shikimori JSON to `Anime`/`UserRate` lives in `shikimori.ts`. `description` goes through Task 2's `cleanDescription` during mapping, so screens get clean text. `backdropUrl` is the first screenshot's `original` only; the Hero and the Title header fall back to `posterUrl` themselves.
- `search` trims the query. If the trimmed query is shorter than 2 characters, it returns `[]` and sends no request.
- `search`, `popularNow` and `popularInSeason` drop repeated ids and keep the first one. `byIds` keeps the REST order. `userRates` does not deduplicate, the same as the Kotlin client.
- `createRate` uses the `animeId` it sent when Shikimori's answer has no `target_id`. In every other case, a rate row with no anime id is an error.
- GraphQL poster queries never carry a token.
- Test fixtures are copied verbatim from `android/src/test/resources/shikimori/*.json` into the test files. They are not imported with `?raw` from outside `web/`, so Vite's `server.fs` rules cannot affect whether the tests pass.
- Tests read query strings with `URLSearchParams.forEach`, so they do not need `DOM.Iterable` in `lib`.

**Files:**
- Create: `web/src/api/http.ts`
- Create: `web/src/api/shikimori.ts`
- Test: `web/src/api/http.test.ts`
- Test: `web/src/api/shikimori.test.ts`

**Interfaces:**
- Consumes:
  - Task 1 `web/src/config.ts`: `SHIKIMORI_URL`
  - Task 2 `web/src/domain/models.ts`: `type Anime`, `type UserRate`, `type ListStatus`, `parseAiringStatus(raw: string): AiringStatus`, `parseListStatus(raw: string): ListStatus`
  - Task 2 `web/src/domain/season.ts`: `type Season`, `seasonApiValue(s: Season): string`
  - Task 2 `web/src/domain/format.ts`: `cleanDescription(raw: string | null): string | null`
- Produces:
  - `http.ts`:
    - `class ApiError extends Error { readonly status: number; readonly body: unknown; constructor(status: number, body?: unknown) }`
    - `class NetworkError extends Error {}`
    - `class RateLimiter { constructor(opts?: { perSecond?: number; perMinute?: number; now?: () => number; sleep?: (ms: number) => Promise<void> }); acquire(signal?: AbortSignal): Promise<void> }`
    - `interface ShikimoriRequest { method?: "GET" | "POST" | "PATCH"; token?: string | null; json?: unknown; signal?: AbortSignal }`
    - `createShikimoriHttp(deps?: { fetch?; limiter?; sleep?; timeoutMs? }): <T>(path: string, request?: ShikimoriRequest) => Promise<T>`
    - `errorMessage(error: unknown): string`
  - `shikimori.ts`:
    - `interface Account { id: number; nickname: string; avatar: string | null }`
    - `shikimoriUrl(value: string | null | undefined): string | null`
    - `createShikimori(http)` → `{ whoami, details, search, popularNow, popularInSeason, byIds, userRates, createRate, updateRate }` with the contract's signatures
    - `type Shikimori = ReturnType<typeof createShikimori>`

- [ ] **Step 1: Write the failing test**

`web/src/api/http.test.ts`:

```ts
// Vectors: shared/src/commonTest/kotlin/app/kaeru/shared/data/shikimori/ShikimoriRateLimiterTest.kt,
// shared/src/commonTest/kotlin/app/kaeru/shared/data/shikimori/ShikimoriClientTest.kt,
// android/src/test/java/app/kaeru/ui/common/ErrorMessagesTest.kt
import { describe, expect, it } from "vitest";
import { ApiError, NetworkError, RateLimiter, createShikimoriHttp, errorMessage } from "./http";

interface Call {
  url: string;
  method: string;
  headers: Headers;
  body: string | null;
}

function fakeFetch(answer: (call: Call, index: number) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetch: typeof globalThis.fetch = async (input, init) => {
    const call: Call = {
      url: String(input),
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : null,
    };
    calls.push(call);
    return answer(call, calls.length - 1);
  };
  return { fetch, calls };
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** A real limiter with room for everything a test sends. */
const roomy = (): RateLimiter => new RateLimiter({ perSecond: 1_000, perMinute: 10_000 });

/** A limiter on a virtual clock that moves only when the limiter sleeps. */
function virtualLimiter(opts: { perSecond?: number; perMinute?: number } = {}) {
  const clock = { t: 0 };
  const waits: number[] = [];
  const limiter = new RateLimiter({
    ...opts,
    now: () => clock.t,
    sleep: async (ms) => {
      waits.push(ms);
      clock.t += ms;
    },
  });
  return { limiter, clock, waits };
}

describe("RateLimiter", () => {
  it("lets five through in a second and holds the sixth until the window moves", async () => {
    const { limiter, clock } = virtualLimiter();
    for (let i = 0; i < 5; i += 1) await limiter.acquire();
    expect(clock.t).toBe(0);
    await limiter.acquire();
    expect(clock.t).toBe(1_000);
  });

  it("does not wait exactly a second after the first of five", async () => {
    const { limiter, clock, waits } = virtualLimiter();
    for (let i = 0; i < 5; i += 1) await limiter.acquire();
    clock.t += 1_000;
    await limiter.acquire();
    expect(waits).toEqual([]);
  });

  it("holds the ninety-first request in a minute until the oldest ages", async () => {
    const { limiter, clock } = virtualLimiter();
    for (let i = 0; i < 90; i += 1) {
      await limiter.acquire();
      clock.t += 250;
    }
    const before = clock.t;
    await limiter.acquire();
    expect(clock.t - before).toBe(37_500);
  });

  it("keeps both sliding windows over 91 requests", async () => {
    const { limiter, clock } = virtualLimiter();
    const admitted: number[] = [];
    for (let i = 0; i < 91; i += 1) {
      await limiter.acquire();
      admitted.push(clock.t);
    }
    expect(admitted.slice(0, 5)).toEqual([0, 0, 0, 0, 0]);
    expect(admitted[5]).toBe(1_000);
    expect(admitted[90]).toBe(60_000);
    for (const time of admitted) {
      expect(admitted.filter((t) => t > time - 1_000 && t <= time).length).toBeLessThanOrEqual(5);
      expect(admitted.filter((t) => t > time - 60_000 && t <= time).length).toBeLessThanOrEqual(90);
    }
  });

  it("serves concurrent callers one after another", async () => {
    const { limiter, waits } = virtualLimiter();
    await Promise.all(Array.from({ length: 6 }, () => limiter.acquire()));
    expect(waits).toEqual([1_000]);
  });

  it("gives up a wait on abort and lets the next caller through", async () => {
    const clock = { t: 0 };
    let sleeping!: () => void;
    const asleep = new Promise<void>((resolve) => {
      sleeping = resolve;
    });
    const limiter = new RateLimiter({
      perSecond: 1,
      now: () => clock.t,
      sleep: () => {
        sleeping();
        return new Promise<void>(() => undefined);
      },
    });
    await limiter.acquire();
    const controller = new AbortController();
    const waiting = limiter.acquire(controller.signal);
    await asleep;
    controller.abort();
    await expect(waiting).rejects.toMatchObject({ name: "AbortError" });
    clock.t = 1_000;
    await expect(limiter.acquire()).resolves.toBeUndefined();
  });
});

describe("createShikimoriHttp", () => {
  it("asks shikimori.io for JSON as Kaeru, with the bearer only when there is one", async () => {
    const { fetch, calls } = fakeFetch(() => json({ id: 1 }));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });

    await expect(http("api/animes/1")).resolves.toEqual({ id: 1 });
    await http("api/users/whoami", { token: "secret" });
    await http("api/users/whoami", { token: "   " });
    await http("api/users/whoami", { token: null });

    expect(calls.map((call) => call.url)).toEqual([
      "https://shikimori.io/api/animes/1",
      "https://shikimori.io/api/users/whoami",
      "https://shikimori.io/api/users/whoami",
      "https://shikimori.io/api/users/whoami",
    ]);
    expect(calls.map((call) => call.method)).toEqual(["GET", "GET", "GET", "GET"]);
    expect(calls.map((call) => call.headers.get("accept"))).toEqual(Array(4).fill("application/json"));
    expect(calls.map((call) => call.headers.get("x-requested-with"))).toEqual(Array(4).fill("Kaeru"));
    expect(calls.map((call) => call.headers.get("authorization"))).toEqual([null, "Bearer secret", null, null]);
    expect(calls[0]?.headers.has("content-type")).toBe(false);
    expect(calls[0]?.body).toBeNull();
  });

  it("sends a JSON body with its content type and method", async () => {
    const { fetch, calls } = fakeFetch(() => json({ id: 111 }));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });

    await http("api/v2/user_rates/111", { method: "PATCH", token: "secret", json: { user_rate: { episodes: 21 } } });

    expect(calls[0]?.method).toBe("PATCH");
    expect(calls[0]?.body).toBe('{"user_rate":{"episodes":21}}');
    expect(calls[0]?.headers.get("content-type")).toBe("application/json");
    expect(calls[0]?.headers.get("authorization")).toBe("Bearer secret");
  });

  it("reads Shikimori's null for an anonymous whoami as null", async () => {
    const { fetch } = fakeFetch(() => json(null));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });
    await expect(http("api/users/whoami")).resolves.toBeNull();
  });

  it("carries a refusal's status and JSON body", async () => {
    const answers = [new Response("nope", { status: 422 }), json({ error: "invalid_token" }, 401)];
    const { fetch } = fakeFetch((_call, index) => answers[index] ?? json(null, 500));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });

    const plain = await http("api/animes/1").catch((error: unknown) => error);
    expect(plain).toBeInstanceOf(ApiError);
    expect(plain).toMatchObject({ status: 422, body: null });

    const parsed = await http("api/animes/1").catch((error: unknown) => error);
    expect(parsed).toBeInstanceOf(ApiError);
    expect(parsed).toMatchObject({ status: 401, body: { error: "invalid_token" } });
  });

  it("turns a fetch that throws into a network failure", async () => {
    const http = createShikimoriHttp({
      fetch: async () => {
        throw new TypeError("Failed to fetch");
      },
      limiter: roomy(),
    });
    await expect(http("api/animes/1")).rejects.toBeInstanceOf(NetworkError);
  });

  it("does not take an unreadable 200 for data", async () => {
    const { fetch } = fakeFetch(() => new Response("<html>", { status: 200 }));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });
    const error = await http("api/animes/1").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(ApiError);
    expect(error).not.toBeInstanceOf(NetworkError);
  });

  it("repeats a 429 once, a second later", async () => {
    const answers = [new Response("slow down", { status: 429 }), json([1])];
    const { fetch, calls } = fakeFetch((_call, index) => answers[index] ?? json(null, 500));
    const sleeps: number[] = [];
    const http = createShikimoriHttp({ fetch, limiter: roomy(), sleep: async (ms) => void sleeps.push(ms) });

    await expect(http("api/animes")).resolves.toEqual([1]);
    expect(calls).toHaveLength(2);
    expect(sleeps).toEqual([1_000]);
  });

  it("lets a second 429 stand", async () => {
    const { fetch, calls } = fakeFetch(() => new Response("slow down", { status: 429 }));
    const sleeps: number[] = [];
    const http = createShikimoriHttp({ fetch, limiter: roomy(), sleep: async (ms) => void sleeps.push(ms) });

    const error = await http("api/animes").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 429 });
    expect(calls).toHaveLength(2);
    expect(sleeps).toEqual([1_000]);
  });

  it("takes a limiter slot for every attempt", async () => {
    const { limiter, waits } = virtualLimiter({ perSecond: 1 });
    const { fetch } = fakeFetch(() => json([]));
    const http = createShikimoriHttp({ fetch, limiter });
    await http("api/animes");
    await http("api/animes");
    expect(waits).toEqual([1_000]);
  });

  it("gives up after the timeout as a network failure", async () => {
    const hanging: typeof globalThis.fetch = (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("The operation was aborted.", "AbortError")));
      });
    const http = createShikimoriHttp({ fetch: hanging, limiter: roomy(), timeoutMs: 20 });
    await expect(http("api/animes/1")).rejects.toBeInstanceOf(NetworkError);
  });

  it("reports the caller's own abort as an abort, not as no network", async () => {
    const hanging: typeof globalThis.fetch = (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("The operation was aborted.", "AbortError")));
      });
    const http = createShikimoriHttp({ fetch: hanging, limiter: roomy() });
    const controller = new AbortController();
    const pending = http("api/animes/1", { signal: controller.signal });
    controller.abort();
    const error = await pending.catch((caught: unknown) => caught);
    expect(error).toMatchObject({ name: "AbortError" });
    expect(error).not.toBeInstanceOf(NetworkError);
  });
});

describe("errorMessage", () => {
  it("names the connection when there was no answer", () => {
    expect(errorMessage(new NetworkError())).toBe("Нет соединения. Проверьте интернет");
  });

  it("asks to sign in again on 401 and 403", () => {
    expect(errorMessage(new ApiError(401))).toBe("Сессия истекла, войдите снова");
    expect(errorMessage(new ApiError(403))).toBe("Сессия истекла, войдите снова");
  });

  it("gives rate limiting and server errors their own copy", () => {
    expect(errorMessage(new ApiError(429))).toBe("Слишком много запросов, попробуйте позже");
    expect(errorMessage(new ApiError(500))).toBe("Shikimori недоступен, попробуйте позже");
    expect(errorMessage(new ApiError(503))).toBe("Shikimori недоступен, попробуйте позже");
  });

  it("never leaks the text of anything else", () => {
    expect(errorMessage(new ApiError(404))).toBe("Что-то пошло не так. Повторите попытку");
    expect(errorMessage(new Error("No anime 100 returned by Shikimori"))).toBe("Что-то пошло не так. Повторите попытку");
    expect(errorMessage("boom")).toBe("Что-то пошло не так. Повторите попытку");
  });
});
```

`web/src/api/shikimori.test.ts`:

```ts
// Vectors and fixtures: shared/src/commonTest/kotlin/app/kaeru/shared/data/shikimori/ShikimoriClientTest.kt,
// shared/src/commonTest/kotlin/app/kaeru/shared/PosterEnrichmentTest.kt,
// android/src/test/java/app/kaeru/data/shikimori/ShikimoriMappersTest.kt,
// android/src/test/resources/shikimori/{animes_list,anime_details,user_rates}.json (copied verbatim below)
import { describe, expect, it } from "vitest";
import { RateLimiter, createShikimoriHttp } from "./http";
import { createShikimori, shikimoriUrl } from "./shikimori";

const ANIMES_LIST = [
  {
    id: 52991,
    name: "Sousou no Frieren",
    russian: "Провожающая в последний путь Фрирен",
    image: { original: "/system/animes/original/52991.jpg", preview: "/system/animes/preview/52991.jpg", x96: "/system/animes/x96/52991.jpg", x48: "/system/animes/x48/52991.jpg" },
    url: "/animes/52991-sousou-no-frieren",
    kind: "tv",
    score: "9.3",
    status: "released",
    episodes: 28,
    episodes_aired: 28,
    aired_on: "2023-09-29",
    released_on: "2024-03-22",
  },
  {
    id: 60000,
    name: "Ongoing Show",
    russian: "",
    image: { original: "/system/animes/original/60000.jpg", preview: "/system/animes/preview/60000.jpg", x96: "/system/animes/x96/60000.jpg", x48: "/system/animes/x48/60000.jpg" },
    url: "/animes/60000-ongoing-show",
    kind: "tv",
    score: "0.0",
    status: "ongoing",
    episodes: 0,
    episodes_aired: 7,
    aired_on: "2026-07-05",
    released_on: null,
  },
];

const ANIME_DETAILS = {
  id: 60000,
  name: "Ongoing Show",
  russian: "Онгоинг",
  image: { original: "/system/animes/original/60000.jpg", preview: "/system/animes/preview/60000.jpg", x96: "/system/animes/x96/60000.jpg", x48: "/system/animes/x48/60000.jpg" },
  url: "/animes/60000-ongoing-show",
  kind: "tv",
  score: "7.8",
  status: "ongoing",
  episodes: 12,
  episodes_aired: 7,
  aired_on: "2026-07-05",
  released_on: null,
  rating: "pg_13",
  english: ["Ongoing Show"],
  japanese: ["進行中"],
  synonyms: [],
  license_name_ru: null,
  duration: 24,
  description: "Описание [character=1]персонажа[/character].",
  description_html: "<p>Описание</p>",
  description_source: null,
  franchise: null,
  favoured: false,
  anons: false,
  ongoing: true,
  thread_id: 1,
  topic_id: 1,
  myanimelist_id: 60000,
  rates_scores_stats: [],
  rates_statuses_stats: [],
  updated_at: "2026-09-10T10:00:00.000+03:00",
  next_episode_at: "2026-09-14T17:00:00.000+03:00",
  fansubbers: [],
  fandubbers: [],
  licensors: [],
  genres: [{ id: 1, name: "Action", russian: "Экшен", kind: "genre" }],
  studios: [{ id: 10, name: "MAPPA", filtered_name: "MAPPA", real: true, image: null }],
  videos: [],
  screenshots: [
    { original: "/system/screenshots/original/1.jpg", preview: "/system/screenshots/x332/1.jpg" },
    { original: "/system/screenshots/original/2.jpg", preview: "/system/screenshots/x332/2.jpg" },
  ],
  user_rate: null,
};

const USER_RATES = [
  { id: 111, user_id: 42, target_id: 52991, target_type: "Anime", score: 10, status: "watching", rewatches: 0, episodes: 20, volumes: 0, chapters: 0, text: null, text_html: "", created_at: "2026-01-01T00:00:00.000+03:00", updated_at: "2026-09-11T21:30:00.000+03:00" },
  { id: 112, user_id: 42, target_id: 60000, target_type: "Anime", score: 0, status: "watching", rewatches: 0, episodes: 6, volumes: 0, chapters: 0, text: null, text_html: "", created_at: "2026-07-06T00:00:00.000+03:00", updated_at: "2026-09-05T18:00:00.000+03:00" },
];

interface Call {
  url: string;
  method: string;
  headers: Headers;
  body: string | null;
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function fakeShikimori(route: (call: Call) => Response) {
  const calls: Call[] = [];
  const fetch: typeof globalThis.fetch = async (input, init) => {
    const call: Call = {
      url: String(input),
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : null,
    };
    calls.push(call);
    return route(call);
  };
  const api = createShikimori(createShikimoriHttp({ fetch, limiter: new RateLimiter({ perSecond: 1_000, perMinute: 10_000 }) }));
  return { api, calls };
}

const path = (call: Call): string => new URL(call.url).pathname;
const params = (call: Call): URLSearchParams => new URL(call.url).searchParams;
/** The query string as an ordered record, without relying on URLSearchParams being iterable. */
function queryOf(call: Call): Record<string, string> {
  const out: Record<string, string> = {};
  params(call).forEach((value, key) => {
    out[key] = value;
  });
  return out;
}
const graphqlQuery = (call: Call): string => (JSON.parse(call.body ?? "{}") as { query: string }).query;
const graphqlIds = (call: Call): number[] => (/ids: "([^"]*)"/.exec(graphqlQuery(call))?.[1] ?? "").split(",").map(Number);
const isGraphql = (call: Call): boolean => path(call) === "/api/graphql";
const noPosters = (): Response => json({ data: { animes: [] } });
const legacyCard = (id: number) => ({ id, name: `Title ${id}`, image: { original: `/legacy/${id}.jpg` } });

describe("shikimoriUrl", () => {
  it("roots relative paths at shikimori.io and keeps absolute ones", () => {
    expect(shikimoriUrl("/system/animes/original/1.jpg")).toBe("https://shikimori.io/system/animes/original/1.jpg");
    expect(shikimoriUrl("images/poster.jpg")).toBe("https://shikimori.io/images/poster.jpg");
    expect(shikimoriUrl("//cdn.example/x.jpg")).toBe("https://cdn.example/x.jpg");
    expect(shikimoriUrl("https://cdn.example/poster.jpg")).toBe("https://cdn.example/poster.jpg");
    expect(shikimoriUrl("http://cdn.example/poster.jpg")).toBe("http://cdn.example/poster.jpg");
  });

  it("is null for nothing at all", () => {
    expect(shikimoriUrl(null)).toBeNull();
    expect(shikimoriUrl(undefined)).toBeNull();
    expect(shikimoriUrl("")).toBeNull();
    expect(shikimoriUrl("   ")).toBeNull();
  });
});

describe("createShikimori", () => {
  it("maps cards by ids, anonymously, fifty ids a request", async () => {
    const { api, calls } = fakeShikimori((call) => (isGraphql(call) ? noPosters() : json(ANIMES_LIST)));

    const cards = await api.byIds([52991, 60000]);

    expect(cards[0]).toEqual({
      id: 52991,
      title: "Провожающая в последний путь Фрирен",
      originalTitle: "Sousou no Frieren",
      posterUrl: "https://shikimori.io/system/animes/original/52991.jpg",
      backdropUrl: null,
      status: "released",
      episodes: 28,
      episodesAired: 28,
      year: 2023,
      score: 9.3,
      kind: "tv",
      studios: [],
      description: null,
      nextEpisodeAt: null,
    });
    // A blank russian name falls back to the romaji; a 0.0 score is no score.
    expect(cards[1]).toMatchObject({ title: "Ongoing Show", score: null, status: "ongoing", episodes: 0, episodesAired: 7, year: 2026 });
    const rest = calls[0];
    expect(rest && path(rest)).toBe("/api/animes");
    expect(rest && params(rest).get("ids")).toBe("52991,60000");
    expect(rest && params(rest).get("limit")).toBe("50");
    expect(rest?.headers.has("authorization")).toBe(false);
    expect(rest?.headers.get("x-requested-with")).toBe("Kaeru");
  });

  it("splits ids into batches of fifty and asks nothing for none", async () => {
    const { api, calls } = fakeShikimori(() => json([]));
    const ids = [...Array.from({ length: 120 }, (_, i) => i + 1), 1];

    await api.byIds(ids);
    expect(calls.map((call) => params(call).get("ids")?.split(",").length)).toEqual([50, 50, 20]);

    calls.length = 0;
    await expect(api.byIds([])).resolves.toEqual([]);
    expect(calls).toHaveLength(0);
  });

  it("prefers the GraphQL original poster, then main, and keeps REST posters when a batch fails", async () => {
    let batches = 0;
    const { api, calls } = fakeShikimori((call) => {
      if (!isGraphql(call)) return json((params(call).get("ids") ?? "").split(",").map((id) => legacyCard(Number(id))));
      batches += 1;
      if (batches === 1) return new Response("unavailable", { status: 500 });
      return json({
        data: {
          animes: [
            { id: "51", poster: { mainUrl: "/small.webp", originalUrl: "//cdn.example/large.webp" } },
            { id: "52", poster: { mainUrl: "/only-main.webp", originalUrl: "" } },
            { id: "999", poster: { mainUrl: "/unrequested.webp" } },
          ],
        },
      });
    });

    const cards = await api.byIds(Array.from({ length: 52 }, (_, i) => i + 1));

    expect(cards.map((card) => card.posterUrl)).toEqual([
      ...Array.from({ length: 50 }, (_, i) => `https://shikimori.io/legacy/${i + 1}.jpg`),
      "https://cdn.example/large.webp",
      "https://shikimori.io/only-main.webp",
    ]);
    const queries = calls.filter(isGraphql);
    expect(queries.map(graphqlIds)).toEqual([Array.from({ length: 50 }, (_, i) => i + 1), [51, 52]]);
    const second = queries[1];
    expect(second && graphqlQuery(second)).toBe('{ animes(ids: "51,52", limit: 50) { id poster { mainUrl originalUrl } } }');
    expect(second?.method).toBe("POST");
    expect(second?.headers.get("content-type")).toBe("application/json");
    expect(second?.headers.has("authorization")).toBe(false);
  });

  it("keeps REST posters whatever GraphQL answers instead of posters", async () => {
    const answers: Array<() => Response> = [
      () => new Response("private-upstream-body", { status: 500 }),
      () => new Response("unauthorized", { status: 401 }),
      () => new Response("<html>unavailable</html>", { status: 200 }),
      () => json({ errors: [{ message: "unavailable" }] }),
      () => json({ data: null }),
      () => json({ data: { animes: [{ id: "1", poster: null }, { id: "2", poster: { mainUrl: "", originalUrl: "" } }] } }),
    ];
    for (const answer of answers) {
      const { api } = fakeShikimori((call) => (isGraphql(call) ? answer() : json([legacyCard(1), legacyCard(2)])));
      const cards = await api.search("title");
      expect(cards.map((card) => card.posterUrl)).toEqual(["https://shikimori.io/legacy/1.jpg", "https://shikimori.io/legacy/2.jpg"]);
    }
  });

  it("searches by name, thirty at most, deduplicated, with posters", async () => {
    const { api, calls } = fakeShikimori((call) => {
      if (!isGraphql(call)) return json([1, 2, 3, 1].map(legacyCard));
      return json({
        data: {
          animes: [
            { id: "2", poster: { mainUrl: null, originalUrl: "//cdn.example/2.webp" } },
            { id: "1", poster: { mainUrl: "https://cdn.example/1.webp", originalUrl: "https://cdn.example/large1.webp" } },
            { id: "999", poster: { mainUrl: "https://cdn.example/unrequested.webp" } },
          ],
        },
      });
    });

    const cards = await api.search("  frog show ");

    expect(cards.map((card) => card.id)).toEqual([1, 2, 3]);
    expect(cards.map((card) => card.posterUrl)).toEqual([
      "https://cdn.example/large1.webp",
      "https://cdn.example/2.webp",
      "https://shikimori.io/legacy/3.jpg",
    ]);
    const rest = calls[0];
    expect(rest && path(rest)).toBe("/api/animes");
    expect(rest && Object.keys(queryOf(rest))).toEqual(["search", "limit"]);
    expect(rest && params(rest).get("search")).toBe("frog show");
    expect(rest && params(rest).get("limit")).toBe("30");
    const query = calls.find(isGraphql);
    expect(query && graphqlQuery(query)).toBe('{ animes(ids: "1,2,3", limit: 50) { id poster { mainUrl originalUrl } } }');
  });

  it("sends nothing for a query shorter than two characters", async () => {
    const { api, calls } = fakeShikimori(() => json([]));
    await expect(api.search(" a ")).resolves.toEqual([]);
    expect(calls).toHaveLength(0);
  });

  it("asks no posters for an empty answer", async () => {
    const { api, calls } = fakeShikimori(() => json([]));
    await expect(api.search("unknown")).resolves.toEqual([]);
    expect(calls.map(path)).toEqual(["/api/animes"]);
  });

  it("reads what is popular now: ongoing, by popularity, censored, twenty, deduplicated", async () => {
    const { api, calls } = fakeShikimori((call) =>
      isGraphql(call) ? json({ data: { animes: [{ id: "3", poster: { mainUrl: "/uploads/3.webp" } }] } }) : json([1, 2, 2, 3].map(legacyCard)),
    );

    const cards = await api.popularNow();

    expect(cards.map((card) => card.id)).toEqual([1, 2, 3]);
    expect(cards[2]?.posterUrl).toBe("https://shikimori.io/uploads/3.webp");
    const rest = calls[0];
    expect(rest && queryOf(rest)).toEqual({ order: "popularity", limit: "20", censored: "true", status: "ongoing" });
    expect(rest?.headers.has("authorization")).toBe(false);
    expect(calls.filter(isGraphql).map(graphqlIds)).toEqual([[1, 2, 3]]);
  });

  it("reads a season as fall_YYYY, never autumn", async () => {
    const { api, calls } = fakeShikimori((call) => (isGraphql(call) ? noPosters() : json([])));
    await api.popularInSeason({ kind: "fall", year: 2026 });
    const rest = calls[0];
    expect(rest && queryOf(rest)).toEqual({ order: "popularity", limit: "20", censored: "true", season: "fall_2026" });
  });

  it("maps details with studios, description, next episode, backdrop and the GraphQL poster", async () => {
    const { api, calls } = fakeShikimori((call) =>
      isGraphql(call)
        ? json({ data: { animes: [{ id: "60000", poster: { mainUrl: "https://shikimori.io/uploads/poster/animes/60000/main.webp", originalUrl: "https://shikimori.io/uploads/poster/animes/60000/orig.jpeg" } }] } })
        : json(ANIME_DETAILS),
    );

    const anime = await api.details(60000);

    expect(anime).toEqual({
      id: 60000,
      title: "Онгоинг",
      originalTitle: "Ongoing Show",
      posterUrl: "https://shikimori.io/uploads/poster/animes/60000/orig.jpeg",
      backdropUrl: "https://shikimori.io/system/screenshots/original/1.jpg",
      status: "ongoing",
      episodes: 12,
      episodesAired: 7,
      year: 2026,
      score: 7.8,
      kind: "tv",
      studios: ["MAPPA"],
      description: "Описание персонажа.",
      nextEpisodeAt: Date.parse("2026-09-14T14:00:00Z"),
    });
    const rest = calls[0];
    expect(rest && path(rest)).toBe("/api/animes/60000");
    expect(rest?.headers.has("authorization")).toBe(false);
  });

  it("falls back to safe values for unknown or malformed fields", async () => {
    const { api } = fakeShikimori((call) =>
      isGraphql(call) ? noPosters() : json({ id: 1, status: "unexpected", score: "not-a-score", aired_on: "unknown", next_episode_at: "not-a-time", kind: " " }),
    );
    const anime = await api.details(1);
    expect(anime).toMatchObject({ status: "released", score: null, year: null, nextEpisodeAt: null, kind: null, posterUrl: null, title: "" });
  });

  it("asks whoami with the token and roots the avatar", async () => {
    const { api, calls } = fakeShikimori(() => json({ id: 42, nickname: "frog", avatar: "/system/users/x160/42.png", ignored: true }));

    await expect(api.whoami("secret")).resolves.toEqual({ id: 42, nickname: "frog", avatar: "https://shikimori.io/system/users/x160/42.png" });

    const call = calls[0];
    expect(call && path(call)).toBe("/api/users/whoami");
    expect(call?.headers.get("authorization")).toBe("Bearer secret");
  });

  it("reads whoami's null as nobody and a missing avatar as null", async () => {
    const answers = [json(null), json({ id: 42, nickname: "frog", avatar: null })];
    const { api } = fakeShikimori(() => answers.shift() ?? json(null, 500));
    await expect(api.whoami("stale")).resolves.toBeNull();
    await expect(api.whoami("secret")).resolves.toEqual({ id: 42, nickname: "frog", avatar: null });
  });

  it("reads every status of the library, a thousand a page, with the bearer", async () => {
    const { api, calls } = fakeShikimori((call) => {
      const status = params(call).get("status");
      const page = Number(params(call).get("page"));
      if (status === "watching" && page === 1) return json(USER_RATES);
      if (status === "completed" && page === 1) {
        return json(Array.from({ length: 1000 }, (_, i) => ({ id: i + 1, target_id: i + 1, status: "completed", episodes: 1, updated_at: "2026-09-01T00:00:00.000+03:00" })));
      }
      return json([]);
    });

    const rates = await api.userRates(42, "secret");

    expect(rates).toHaveLength(1002);
    expect(rates[0]).toEqual({ id: 111, animeId: 52991, status: "watching", episodes: 20, updatedAt: Date.parse("2026-09-11T18:30:00Z") });
    const first = calls[0];
    expect(first && path(first)).toBe("/api/v2/user_rates");
    expect(first && queryOf(first)).toEqual({ target_type: "Anime", user_id: "42", status: "planned", page: "1", limit: "1000" });
    expect(first?.headers.get("authorization")).toBe("Bearer secret");
    expect([...new Set(calls.map((call) => params(call).get("status")))]).toEqual(["planned", "watching", "rewatching", "completed", "on_hold", "dropped"]);
    expect(calls.filter((call) => params(call).get("status") === "completed").map((call) => params(call).get("page"))).toEqual(["1", "2"]);
  });

  it("names a rate's anime by the embedded card in the older shape and falls back safely", async () => {
    const { api } = fakeShikimori((call) => {
      const status = params(call).get("status");
      if (status === "rewatching") return json([{ id: 9, status: "rewatching", episodes: 7, anime: { id: 2000, name: "Again" } }]);
      if (status === "on_hold") return json([{ id: 2, target_id: 1, status: "unexpected", updated_at: "invalid" }]);
      return json([]);
    });

    const rates = await api.userRates(42, "secret");

    expect(rates).toEqual([
      { id: 9, animeId: 2000, status: "rewatching", episodes: 7, updatedAt: 0 },
      { id: 2, animeId: 1, status: "planned", episodes: 0, updatedAt: 0 },
    ]);
  });

  it("writes rates wrapped in user_rate, naming only what changes", async () => {
    const { api, calls } = fakeShikimori(() => json(USER_RATES[0]));

    await expect(api.updateRate("secret", 111, { episodes: 21 })).resolves.toEqual({
      id: 111,
      animeId: 52991,
      status: "watching",
      episodes: 20,
      updatedAt: Date.parse("2026-09-11T18:30:00Z"),
    });
    await api.updateRate("secret", 111, { status: "on_hold" });
    await api.createRate("secret", 42, 52991, { status: "watching", episodes: 1 });
    await api.createRate("secret", 42, 52991, { status: "planned" });

    expect(calls.map((call) => `${call.method} ${path(call)}`)).toEqual([
      "PATCH /api/v2/user_rates/111",
      "PATCH /api/v2/user_rates/111",
      "POST /api/v2/user_rates",
      "POST /api/v2/user_rates",
    ]);
    expect(calls.map((call) => call.body)).toEqual([
      '{"user_rate":{"episodes":21}}',
      '{"user_rate":{"status":"on_hold"}}',
      '{"user_rate":{"user_id":42,"target_id":52991,"target_type":"Anime","status":"watching","episodes":1}}',
      '{"user_rate":{"user_id":42,"target_id":52991,"target_type":"Anime","status":"planned"}}',
    ]);
    expect(calls.map((call) => call.headers.get("content-type"))).toEqual(Array(4).fill("application/json"));
    expect(calls.map((call) => call.headers.get("authorization"))).toEqual(Array(4).fill("Bearer secret"));
  });

  it("takes the anime of a created rate from the request when the answer omits it", async () => {
    const { api } = fakeShikimori(() => json({ id: 123, status: "planned", episodes: 0 }));
    await expect(api.createRate("secret", 1, 42, { status: "planned" })).resolves.toEqual({
      id: 123,
      animeId: 42,
      status: "planned",
      episodes: 0,
      updatedAt: 0,
    });
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run (from `web/`): `npx vitest run src/api/http.test.ts src/api/shikimori.test.ts`

**Expected:** both files fail before any test runs. `src/api/http.test.ts` reports `Failed to resolve import "./http" from "src/api/http.test.ts". Does the file exist?` and `src/api/shikimori.test.ts` reports the same. The summary is `Test Files  2 failed (2)`.

- [ ] **Step 3: Implement**

`web/src/api/http.ts`:

```ts
import { SHIKIMORI_URL } from "../config";

/** Shikimori answered, and not with a success. `body` is the parsed JSON, or null. */
export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, body: unknown = null) {
    super(`HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

/** No answer at all: offline, DNS, a CORS refusal, a timeout. */
export class NetworkError extends Error {
  constructor(message = "Network request failed") {
    super(message);
    this.name = "NetworkError";
  }
}

const SECOND_MS = 1_000;
const MINUTE_MS = 60_000;
const RETRY_AFTER_MS = 1_000;
const TIMEOUT_MS = 30_000;

const defaultSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function abortReason(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException("The operation was aborted.", "AbortError");
}

/** [wait], cut short with the abort reason as soon as [signal] aborts. */
function abortable(wait: Promise<void>, signal?: AbortSignal): Promise<void> {
  if (signal === undefined) return wait;
  if (signal.aborted) return Promise.reject(abortReason(signal));
  return new Promise<void>((resolve, reject) => {
    const onAbort = (): void => reject(abortReason(signal));
    signal.addEventListener("abort", onAbort, { once: true });
    wait.then(
      () => {
        signal.removeEventListener("abort", onAbort);
        resolve();
      },
      (error: unknown) => {
        signal.removeEventListener("abort", onAbort);
        reject(error);
      },
    );
  });
}

function dropOlder(stamps: number[], now: number, span: number): void {
  for (let oldest = stamps[0]; oldest !== undefined && now - oldest >= span; oldest = stamps[0]) stamps.shift();
}

function waitFor(stamps: readonly number[], limit: number, now: number, span: number): number {
  const oldest = stamps[0];
  return stamps.length >= limit && oldest !== undefined ? span - (now - oldest) : 0;
}

/**
 * Shikimori's budget as the apps keep it (ShikimoriRateLimiter.kt): at most 5 requests in any
 * second and 90 in any minute. Callers are served in order; an aborted caller gives up its turn.
 */
export class RateLimiter {
  private readonly perSecond: number;
  private readonly perMinute: number;
  private readonly now: () => number;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly second: number[] = [];
  private readonly minute: number[] = [];
  private tail: Promise<void> = Promise.resolve();

  constructor(opts: { perSecond?: number; perMinute?: number; now?: () => number; sleep?: (ms: number) => Promise<void> } = {}) {
    this.perSecond = opts.perSecond ?? 5;
    this.perMinute = opts.perMinute ?? 90;
    // Monotonic: a clock change must not open or close the windows.
    this.now = opts.now ?? (() => performance.now());
    this.sleep = opts.sleep ?? defaultSleep;
  }

  acquire(signal?: AbortSignal): Promise<void> {
    const turn = this.tail.then(() => this.take(signal));
    // The next caller waits for this one whether it got its slot or gave up.
    this.tail = turn.catch(() => undefined);
    return turn;
  }

  private async take(signal?: AbortSignal): Promise<void> {
    for (;;) {
      if (signal?.aborted) throw abortReason(signal);
      const now = this.now();
      dropOlder(this.second, now, SECOND_MS);
      dropOlder(this.minute, now, MINUTE_MS);
      const wait = Math.max(
        waitFor(this.second, this.perSecond, now, SECOND_MS),
        waitFor(this.minute, this.perMinute, now, MINUTE_MS),
      );
      if (wait <= 0) {
        this.second.push(now);
        this.minute.push(now);
        return;
      }
      await abortable(this.sleep(wait), signal);
    }
  }
}

/** One budget for the whole tab: the limit is Shikimori's per address, not per screen. */
const sharedLimiter = new RateLimiter();

export interface ShikimoriRequest {
  method?: "GET" | "POST" | "PATCH";
  token?: string | null;
  json?: unknown;
  signal?: AbortSignal;
}

interface Answer {
  status: number;
  text: string;
}

async function exchange(
  send: typeof fetch,
  url: string,
  init: RequestInit,
  signal: AbortSignal | undefined,
  timeoutMs: number,
): Promise<Answer> {
  if (signal?.aborted) throw abortReason(signal);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const forward = (): void => controller.abort();
  signal?.addEventListener("abort", forward, { once: true });
  try {
    const response = await send(url, { ...init, signal: controller.signal });
    return { status: response.status, text: await response.text() };
  } catch {
    // The caller's own abort is a cancellation, not a network failure.
    if (signal?.aborted) throw abortReason(signal);
    throw new NetworkError(controller.signal.aborted ? "Request timed out" : "Network request failed");
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", forward);
  }
}

function parseJson(text: string): { ok: true; value: unknown } | { ok: false } {
  if (text.trim() === "") return { ok: true, value: null };
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return { ok: false };
  }
}

function read<T>(answer: Answer): T {
  const body = parseJson(answer.text);
  if (answer.status < 200 || answer.status > 299) throw new ApiError(answer.status, body.ok ? body.value : null);
  if (!body.ok) throw new Error("Invalid API response");
  return body.value as T;
}

export function createShikimoriHttp(
  deps: { fetch?: typeof fetch; limiter?: RateLimiter; sleep?: (ms: number) => Promise<void>; timeoutMs?: number } = {},
): <T>(path: string, request?: ShikimoriRequest) => Promise<T> {
  const send: typeof fetch = deps.fetch ?? ((input, init) => fetch(input, init));
  const limiter = deps.limiter ?? sharedLimiter;
  const sleep = deps.sleep ?? defaultSleep;
  const timeoutMs = deps.timeoutMs ?? TIMEOUT_MS;

  return async function request<T>(path: string, options: ShikimoriRequest = {}): Promise<T> {
    // X-Requested-With stands in for the User-Agent a page cannot set; Shikimori's preflight allows it.
    const headers: Record<string, string> = { Accept: "application/json", "X-Requested-With": "Kaeru" };
    if (options.token != null && options.token.trim() !== "") headers["Authorization"] = `Bearer ${options.token}`;
    const init: RequestInit = { method: options.method ?? "GET", headers };
    if (options.json !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(options.json);
    }
    for (let attempt = 0; ; attempt += 1) {
      await limiter.acquire(options.signal);
      const answer = await exchange(send, `${SHIKIMORI_URL}/${path}`, init, options.signal, timeoutMs);
      if (answer.status === 429 && attempt === 0) {
        // Retry-After is not exposed to scripts, so the apps' default of one second.
        await abortable(sleep(RETRY_AFTER_MS), options.signal);
        continue;
      }
      return read<T>(answer);
    }
  };
}

/** The one place a failure becomes copy (ErrorMessages.kt); exception text is never shown. */
export function errorMessage(error: unknown): string {
  if (error instanceof NetworkError) return "Нет соединения. Проверьте интернет";
  if (error instanceof ApiError) {
    if (error.status === 401 || error.status === 403) return "Сессия истекла, войдите снова";
    if (error.status === 429) return "Слишком много запросов, попробуйте позже";
    if (error.status >= 500) return "Shikimori недоступен, попробуйте позже";
  }
  return "Что-то пошло не так. Повторите попытку";
}
```

`web/src/api/shikimori.ts`:

```ts
import { SHIKIMORI_URL } from "../config";
import { cleanDescription } from "../domain/format";
import { parseAiringStatus, parseListStatus } from "../domain/models";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import { seasonApiValue } from "../domain/season";
import type { Season } from "../domain/season";
import type { createShikimoriHttp } from "./http";

export interface Account {
  id: number;
  nickname: string;
  avatar: string | null;
}

type Http = ReturnType<typeof createShikimoriHttp>;
type Json = Record<string, unknown>;

/** The six lists, in the order the apps read them (ShikimoriClient.STATUSES). */
const STATUSES: readonly ListStatus[] = ["planned", "watching", "rewatching", "completed", "on_hold", "dropped"];
/** Shikimori's ceiling for one `ids=` batch and for one GraphQL `animes` query. */
const BATCH = 50;
const ROW = 20;
const SEARCH_LIMIT = 30;
const MIN_QUERY = 2;
const RATES_PAGE = 1000;

/** A Shikimori path or URL as something an <img> can load; null for nothing at all. */
export function shikimoriUrl(value: string | null | undefined): string | null {
  if (value == null || value.trim() === "") return null;
  if (value.startsWith("//")) return `https:${value}`;
  if (value.startsWith("https://") || value.startsWith("http://")) return value;
  if (value.startsWith("/")) return SHIKIMORI_URL + value;
  return `${SHIKIMORI_URL}/${value}`;
}

function record(value: unknown): Json | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Json) : null;
}

function text(value: unknown): string {
  if (typeof value === "string") return value;
  return typeof value === "number" ? String(value) : "";
}

/** A non-negative whole number; anything else is 0, the way the apps coerce. */
function count(value: unknown): number {
  const n = Number(text(value));
  return Number.isFinite(n) && n > 0 ? Math.trunc(n) : 0;
}

function positiveId(value: unknown): number | null {
  const n = Number(text(value));
  return Number.isInteger(n) && n > 0 ? n : null;
}

/** ISO-8601 with an offset → epoch ms; null when absent or unreadable. */
function instant(value: unknown): number | null {
  const raw = text(value);
  if (raw === "") return null;
  const ms = Date.parse(raw);
  return Number.isNaN(ms) ? null : ms;
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error("Invalid API list response");
  return value;
}

/** REST's own poster: the current field, then the legacy image, then its preview. */
function restPoster(dto: Json): string | null {
  const poster = record(dto["poster"]);
  const image = record(dto["image"]);
  const candidates = [text(poster?.["originalUrl"]), text(image?.["original"]), text(image?.["preview"])];
  return shikimoriUrl(candidates.find((candidate) => candidate.trim() !== "") ?? null);
}

function firstScreenshot(dto: Json): string | null {
  const shots = Array.isArray(dto["screenshots"]) ? (dto["screenshots"] as unknown[]) : [];
  for (const shot of shots) {
    const url = shikimoriUrl(text(record(shot)?.["original"]));
    if (url !== null) return url;
  }
  return null;
}

function toAnime(value: unknown): Anime {
  const dto = record(value);
  const id = positiveId(dto?.["id"]);
  if (dto === null || id === null) throw new Error("Invalid anime response");
  const name = text(dto["name"]);
  const russian = text(dto["russian"]);
  const kind = text(dto["kind"]);
  const score = Number(text(dto["score"]));
  const year = /^\d{4}/.exec(text(dto["aired_on"]));
  const studios = Array.isArray(dto["studios"])
    ? (dto["studios"] as unknown[]).map((studio) => text(record(studio)?.["name"])).filter((studio) => studio.trim() !== "")
    : [];
  const description = text(dto["description"]);
  return {
    id,
    title: russian.trim() !== "" ? russian : name,
    originalTitle: name,
    posterUrl: restPoster(dto),
    backdropUrl: firstScreenshot(dto),
    status: parseAiringStatus(text(dto["status"])),
    episodes: count(dto["episodes"]),
    episodesAired: count(dto["episodes_aired"]),
    year: year === null ? null : Number(year[0]),
    // A score is a quoted string; 0 means «not rated yet» and is not shown.
    score: Number.isFinite(score) && score > 0 ? score : null,
    kind: kind.trim() !== "" ? kind : null,
    studios,
    description: cleanDescription(description.trim() !== "" ? description : null),
    nextEpisodeAt: instant(dto["next_episode_at"]),
  };
}

/** v2 names the anime by `target_id`; an older shape embeds the card as `target` or `anime`. */
function toUserRate(value: unknown, fallbackAnimeId = 0): UserRate {
  const dto = record(value);
  const id = positiveId(dto?.["id"]);
  if (dto === null || id === null) throw new Error("Invalid library rate response");
  const embedded = record(dto["target"]) ?? record(dto["anime"]);
  const animeId = positiveId(dto["target_id"]) ?? positiveId(embedded?.["id"]) ?? fallbackAnimeId;
  if (animeId <= 0) throw new Error("Library rate has no anime id");
  return {
    id,
    animeId,
    status: parseListStatus(text(dto["status"])),
    episodes: count(dto["episodes"]),
    updatedAt: instant(dto["updated_at"]) ?? 0,
  };
}

function unique<T>(values: readonly T[]): T[] {
  return [...new Set(values)];
}

function chunks<T>(values: readonly T[], size: number): T[][] {
  const out: T[][] = [];
  for (let start = 0; start < values.length; start += size) out.push(values.slice(start, start + size));
  return out;
}

function distinctById(cards: readonly Anime[]): Anime[] {
  const seen = new Set<number>();
  return cards.filter((card) => {
    if (seen.has(card.id)) return false;
    seen.add(card.id);
    return true;
  });
}

function withQuery(path: string, params: Record<string, string | number>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) search.set(key, String(value));
  return `${path}?${search.toString()}`;
}

function isAbort(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { name?: unknown }).name === "AbortError";
}

/**
 * Real posters by id from GraphQL, 50 per query. REST's `image` is a placeholder for anything
 * added after the poster migration. A failed batch costs its posters, never the catalogue.
 */
async function posters(http: Http, ids: readonly number[]): Promise<Map<number, string>> {
  const found = new Map<number, string>();
  for (const batch of chunks(unique(ids), BATCH)) {
    try {
      const query = `{ animes(ids: "${batch.join(",")}", limit: ${BATCH}) { id poster { mainUrl originalUrl } } }`;
      const answer = record(await http<unknown>("api/graphql", { method: "POST", json: { query } }));
      const entries = record(answer?.["data"])?.["animes"];
      if (!Array.isArray(entries)) continue;
      for (const entry of entries as unknown[]) {
        const card = record(entry);
        // GraphQL sends the id as a string.
        const id = positiveId(card?.["id"]);
        if (id === null || !batch.includes(id)) continue;
        const poster = record(card?.["poster"]);
        const original = text(poster?.["originalUrl"]);
        const url = shikimoriUrl(original.trim() !== "" ? original : text(poster?.["mainUrl"]));
        if (url !== null) found.set(id, url);
      }
    } catch (error) {
      if (isAbort(error)) throw error;
    }
  }
  return found;
}

async function withPosters(http: Http, cards: Anime[]): Promise<Anime[]> {
  if (cards.length === 0) return cards;
  const found = await posters(http, cards.map((card) => card.id));
  return cards.map((card) => {
    const url = found.get(card.id);
    return url === undefined ? card : { ...card, posterUrl: url };
  });
}

export function createShikimori(http: Http) {
  const cards = async (path: string): Promise<Anime[]> => list(await http<unknown>(path)).map((card) => toAnime(card));

  // Most popular first, one row's worth, and never adult titles on a home screen.
  const catalogue = async (filter: { status: string } | { season: string }): Promise<Anime[]> =>
    withPosters(http, distinctById(await cards(withQuery("api/animes", { order: "popularity", limit: ROW, censored: "true", ...filter }))));

  return {
    /** Who the token belongs to; Shikimori answers 200 `null` for no or an unknown session. */
    async whoami(token: string): Promise<Account | null> {
      const user = record(await http<unknown>("api/users/whoami", { token }));
      if (user === null) return null;
      const id = positiveId(user["id"]);
      if (id === null) throw new Error("Invalid account response");
      return { id, nickname: text(user["nickname"]), avatar: shikimoriUrl(text(user["avatar"])) };
    },

    async details(id: number): Promise<Anime> {
      const card = toAnime(await http<unknown>(`api/animes/${id}`));
      const [enriched] = await withPosters(http, [card]);
      return enriched ?? card;
    },

    async search(query: string): Promise<Anime[]> {
      const trimmed = query.trim();
      if (trimmed.length < MIN_QUERY) return [];
      return withPosters(http, distinctById(await cards(withQuery("api/animes", { search: trimmed, limit: SEARCH_LIMIT }))));
    },

    popularNow(): Promise<Anime[]> {
      return catalogue({ status: "ongoing" });
    },

    popularInSeason(season: Season): Promise<Anime[]> {
      return catalogue({ season: seasonApiValue(season) });
    },

    async byIds(ids: readonly number[]): Promise<Anime[]> {
      const found: Anime[] = [];
      for (const batch of chunks(unique(ids), BATCH)) {
        found.push(...(await cards(withQuery("api/animes", { ids: batch.join(","), limit: BATCH }))));
      }
      return withPosters(http, found);
    },

    async userRates(userId: number, token: string): Promise<UserRate[]> {
      const rates: UserRate[] = [];
      for (const status of STATUSES) {
        for (let page = 1; ; page += 1) {
          const path = withQuery("api/v2/user_rates", { target_type: "Anime", user_id: userId, status, page, limit: RATES_PAGE });
          const batch = list(await http<unknown>(path, { token }));
          rates.push(...batch.map((rate) => toUserRate(rate)));
          if (batch.length !== RATES_PAGE) break;
        }
      }
      return rates;
    },

    async createRate(token: string, userId: number, animeId: number, fields: { status: ListStatus; episodes?: number }): Promise<UserRate> {
      const rate: Json = { user_id: userId, target_id: animeId, target_type: "Anime", status: fields.status };
      if (fields.episodes !== undefined) rate["episodes"] = fields.episodes;
      return toUserRate(await http<unknown>("api/v2/user_rates", { method: "POST", token, json: { user_rate: rate } }), animeId);
    },

    /** Names only what changes: Shikimori reads an absent field as «leave it». */
    async updateRate(token: string, rateId: number, fields: { status?: ListStatus; episodes?: number }): Promise<UserRate> {
      const rate: Json = {};
      if (fields.status !== undefined) rate["status"] = fields.status;
      if (fields.episodes !== undefined) rate["episodes"] = fields.episodes;
      return toUserRate(await http<unknown>(`api/v2/user_rates/${rateId}`, { method: "PATCH", token, json: { user_rate: rate } }));
    },
  };
}

export type Shikimori = ReturnType<typeof createShikimori>;
```

- [ ] **Step 4: Run to see it pass**

Run (from `web/`): `npx vitest run src/api/http.test.ts src/api/shikimori.test.ts`

**Expected:** `Test Files  2 passed (2)`, `Tests  40 passed (40)`.

Run: `npm test`

**Expected:** every test file passes, including the two new ones. No failures.

Run: `npm run typecheck`

**Expected:** `tsc --noEmit` exits 0 with no output.

- [ ] **Step 5: Commit**

```bash
cd /Users/vitaliy/Projects/kaeru
git add web/src/api/http.ts web/src/api/http.test.ts web/src/api/shikimori.ts web/src/api/shikimori.test.ts
git commit -m "feat(web): клиент Shikimori — лимит запросов, повтор после 429, постеры GraphQL, список и записи оценок"
```

---

---

### Task 6: Auth: worker token calls, session, sign-in, refresh

**Decisions:**
- **Session storage.**
  - `localStorage["kaeru.session"]` holds the same shape as the iOS Keychain entry: `{"account":{"id","nickname","avatar"},"tokens":{"access_token","refresh_token","expires_in","created_at"}}`.
  - The closed state is stored under the same key as `{"closed":{"nickname"}}`. A reload or another tab keeps showing «Доступ закрыт» until the viewer presses «Выйти».
  - `signOut()` removes the key. The sign-out `message` is kept in memory only, so just the tab that signed out shows it.
- **Reading the session.** `SessionStore.get()` re-reads the key on every call. It returns the cached object while the raw string is unchanged, which `useSyncExternalStore` requires. Every write creates a new state object, even when the data is identical. An in-flight refresh compares that object's identity to detect a change.
- **Refresh in `authorized`.**
  - With no session, `authorized` throws `ApiError(401)` and calls nothing.
  - At most one refresh happens per call, either ahead of expiry or after a 401.
  - After a 401, a refresh failure that says nothing about the session surfaces as the matching error: `network` → `NetworkError`, `throttled` → `ApiError(429)`, `unavailable` → `ApiError(502)`, `not_configured` → `ApiError(503)`. For a plain `rejected`, the original 401 stands.
  - If a refresh ahead of expiry fails in one of those ways, the call goes ahead with the old token.
  - When `navigator.locks` is missing (jsdom, older browsers), refreshes in the tab run one at a time through a promise chain in the module.
- **Worker token calls.**
  - They send no `headers`, pass the body as `URLSearchParams`, and time out after 30 s. A timeout counts as `network`.
  - A 2xx without both non-blank tokens counts as `unavailable`, meaning the answer could not be read, like the worker's own 502.
  - A missing `created_at` becomes the device's current time in seconds. A missing `expires_in` becomes 86 400.
- **`completeSignIn`.**
  - It consumes the pending `kaeru.signin` entry before anything else, so each callback can be completed only once. A second call for the same callback is refused, which includes a React StrictMode double effect. The callback screen (Task 8) must guard the call with a ref.
  - Checks run in this order:
    1. Already signed in → «Вход уже выполнен. Запрос авторизации отклонён».
    2. No code, including `error=access_denied` → «Shikimori не вернул код. Попробуйте войти ещё раз».
    3. Nothing pending, a state mismatch, or a repeated `code`/`state` → «Не удалось подтвердить вход. Войдите заново».
    4. Exchange the code, call `whoami`, save the session.
  - If `whoami` throws, the message is `errorMessage(error)`. If it returns `null`, the message is «Что-то пошло не так. Повторите попытку».
- **`returnTo`** must be a path on this site other than `/auth`. Anything else becomes `/`.
- **`signInErrorMessage`** returns the generic «Что-то пошло не так. Повторите попытку» for `ok` and `closed`. Callers never pass those two kinds.

**Files:**
- Create: `web/src/auth/relay.ts`
- Create: `web/src/auth/session.ts`
- Create: `web/src/auth/signin.ts`
- Test: `web/src/auth/relay.test.ts`
- Test: `web/src/auth/session.test.ts`
- Test: `web/src/auth/signin.test.ts`

**Interfaces:**
- Consumes:
  - Task 1 `web/src/config.ts`: `RELAY_URL`, `CLIENT_ID`, `SHIKIMORI_URL`, `redirectUri(origin?: string): string`
  - Task 5 `web/src/api/http.ts`: `ApiError`, `NetworkError`, `createShikimoriHttp`, `errorMessage`
  - Task 5 `web/src/api/shikimori.ts`: `createShikimori`, `type Account`, `type Shikimori`
- Produces:
  - `relay.ts`:
    - `interface Tokens { accessToken: string; refreshToken: string; expiresIn: number; createdAt: number }` (createdAt in epoch seconds)
    - `type TokenResult` with the nine kinds from the contract
    - `exchangeCode(code: string, redirectUri: string, deps?: { fetch?: typeof fetch }): Promise<TokenResult>`
    - `refreshTokens(refreshToken: string, deps?: { fetch?: typeof fetch }): Promise<TokenResult>`
  - `session.ts`:
    - `interface Session { account: Account; tokens: Tokens }`
    - `type AccessState`
    - `class SessionStore { constructor(storage?: Storage); get(); subscribe(listener); setSession(session); setClosed(nickname); signOut(message?) }`
    - `const sessionStore: SessionStore`
    - `useAccess(): AccessState`
    - `authorized<T>(call: (token: string) => Promise<T>, deps?: { store?; refresh?; locks?: LockManager | null; now? }): Promise<T>`
  - `signin.ts`:
    - `beginSignIn(returnTo: string, deps?: { storage?; random?; origin? }): string`
    - `type SignInResult`
    - `completeSignIn(search: string, deps?: { storage?; store?; exchange?; shikimori?: Pick<Shikimori, "whoami">; origin? }): Promise<SignInResult>`
    - `signInErrorMessage(result: TokenResult): string`

- [ ] **Step 1: Write the failing test**

`web/src/auth/relay.test.ts`:

```ts
// Vectors: shared/src/commonTest/kotlin/app/kaeru/shared/data/shikimori/ShikimoriClientTest.kt (token form),
// android/src/test/java/app/kaeru/data/shikimori/ShikimoriSessionTest.kt (which answers end a session),
// infra/relay/test/oauth.test.ts and infra/relay/src/whitelist.ts (the site's 403/401/502 bodies)
import { describe, expect, it } from "vitest";
import { exchangeCode, refreshTokens } from "./relay";
import type { TokenResult } from "./relay";

interface Sent {
  url: string;
  init: RequestInit | undefined;
}

function worker(answer: () => Response) {
  const sent: Sent[] = [];
  const fetch: typeof globalThis.fetch = async (input, init) => {
    sent.push({ url: String(input), init });
    return answer();
  };
  return { fetch, sent };
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const plain = (body: string, status: number): Response =>
  new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });

const TOKENS = { access_token: "acc", token_type: "Bearer", expires_in: 86400, refresh_token: "ref", scope: "user_rates", created_at: 1757600000 };

describe("exchangeCode", () => {
  it("posts only the grant's fields as a form to the worker, with no headers of its own", async () => {
    const { fetch, sent } = worker(() => json(TOKENS));

    const result = await exchangeCode("abc", "http://localhost:5173/auth", { fetch });

    expect(result).toEqual({ kind: "ok", tokens: { accessToken: "acc", refreshToken: "ref", expiresIn: 86400, createdAt: 1757600000 } });
    expect(sent).toHaveLength(1);
    expect(sent[0]?.url).toBe("https://kaeru-relay.vitaliy-velikodniy.workers.dev/oauth/token");
    expect(sent[0]?.init?.method).toBe("POST");
    expect(sent[0]?.init?.body).toBeInstanceOf(URLSearchParams);
    expect(String(sent[0]?.init?.body)).toBe(
      "grant_type=authorization_code&client_id=_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg&code=abc&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fauth",
    );
    // Any custom header (X-Requested-With included) fails the worker's preflight.
    expect(sent[0]?.init?.headers).toBeUndefined();
  });

  it("fills a missing created_at with this device's clock and a missing expires_in with a day", async () => {
    const { fetch } = worker(() => json({ access_token: "acc", refresh_token: "ref" }));
    const before = Math.floor(Date.now() / 1000);
    const result = await exchangeCode("abc", "http://localhost:5173/auth", { fetch });
    const after = Math.ceil(Date.now() / 1000);

    expect(result.kind).toBe("ok");
    if (result.kind !== "ok") return;
    expect(result.tokens.expiresIn).toBe(86400);
    expect(result.tokens.createdAt).toBeGreaterThanOrEqual(before);
    expect(result.tokens.createdAt).toBeLessThanOrEqual(after);
  });

  it("reads each of the worker's answers by its JSON error, not the status alone", async () => {
    const cases: Array<[() => Response, TokenResult]> = [
      [() => json({ error: "not_allowed", nickname: "stranger" }, 403), { kind: "closed", nickname: "stranger" }],
      [() => json({ error: "sign_in" }, 401), { kind: "sign_in" }],
      [() => json({ error: "invalid_grant", error_description: "expired" }, 400), { kind: "invalid_grant" }],
      [() => json({ error: "invalid_grant", error_description: "expired" }, 401), { kind: "invalid_grant" }],
      [() => json({ error: "unavailable" }, 502), { kind: "unavailable" }],
      [() => plain("upstream unavailable", 502), { kind: "unavailable" }],
      [() => plain("upstream answer unreadable", 502), { kind: "unavailable" }],
      [() => plain("too many requests", 429), { kind: "throttled" }],
      [() => plain("not configured", 503), { kind: "not_configured" }],
      [() => plain("unknown client", 400), { kind: "rejected" }],
      [() => plain("unexpected redirect_uri", 400), { kind: "rejected" }],
      [() => json({ error: "invalid_client" }, 400), { kind: "rejected" }],
      [() => new Response(null, { status: 401 }), { kind: "rejected" }],
      [() => plain("method not allowed", 405), { kind: "rejected" }],
      [() => json({ access_token: "acc" }), { kind: "unavailable" }],
      [() => json({ access_token: " ", refresh_token: "ref" }), { kind: "unavailable" }],
    ];
    for (const [answer, expected] of cases) {
      const { fetch } = worker(answer);
      await expect(exchangeCode("abc", "http://localhost:5173/auth", { fetch })).resolves.toEqual(expected);
    }
  });

  it("reports a fetch that throws as no network", async () => {
    const fetch: typeof globalThis.fetch = async () => {
      throw new TypeError("Failed to fetch");
    };
    await expect(exchangeCode("abc", "http://localhost:5173/auth", { fetch })).resolves.toEqual({ kind: "network" });
  });
});

describe("refreshTokens", () => {
  it("posts grant_type, client_id and refresh_token only", async () => {
    const { fetch, sent } = worker(() =>
      json({ access_token: "new", token_type: "Bearer", expires_in: 86400, refresh_token: "refresh-2", scope: "user_rates", created_at: 1757600000 }),
    );

    const result = await refreshTokens("refresh-1", { fetch });

    expect(result).toEqual({ kind: "ok", tokens: { accessToken: "new", refreshToken: "refresh-2", expiresIn: 86400, createdAt: 1757600000 } });
    expect(String(sent[0]?.init?.body)).toBe("grant_type=refresh_token&client_id=_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg&refresh_token=refresh-1");
    expect(sent[0]?.init?.headers).toBeUndefined();
  });

  it("hears a refresh for an account taken off the list as closed", async () => {
    const { fetch } = worker(() => json({ error: "not_allowed", nickname: "frog" }, 403));
    await expect(refreshTokens("refresh-1", { fetch })).resolves.toEqual({ kind: "closed", nickname: "frog" });
  });
});
```

`web/src/auth/session.test.ts`:

```ts
// Vectors: android/src/test/java/app/kaeru/data/shikimori/ShikimoriSessionTest.kt (one refresh after a 401,
// one retry, compare-and-set, which failures keep the session); ios/Core/AppModel.swift:375-423 (refresh ahead
// under 60 s); ios/Core/Models.swift:52-61 (stored session shape)
import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { TokenResult, Tokens } from "./relay";
import { SessionStore, authorized, sessionStore, useAccess } from "./session";
import type { Session } from "./session";

const account = { id: 42, nickname: "frog", avatar: null };
const tokens = (accessToken: string, refreshToken: string, createdAt = 1_000): Tokens => ({ accessToken, refreshToken, expiresIn: 86_400, createdAt });
const session = (access = "old", refresh = "refresh-1"): Session => ({ account, tokens: tokens(access, refresh) });
/** 1 000 s after the epoch: a day before the test tokens expire. */
const now = (): number => 1_000_000;

function storeWith(initial: Session | null = session()): SessionStore {
  localStorage.clear();
  const store = new SessionStore(localStorage);
  if (initial !== null) store.setSession(initial);
  return store;
}

function refresher(...answers: TokenResult[]) {
  const seen: string[] = [];
  const refresh = async (refreshToken: string): Promise<TokenResult> => {
    seen.push(refreshToken);
    return answers.shift() ?? { kind: "network" };
  };
  return { refresh, seen };
}

beforeEach(() => {
  localStorage.clear();
  sessionStore.signOut();
});

describe("SessionStore", () => {
  it("starts signed out and keeps a session in the iOS shape under kaeru.session", () => {
    const store = storeWith(null);
    expect(store.get()).toEqual({ kind: "signed_out", message: null });

    store.setSession(session());

    expect(store.get()).toEqual({ kind: "signed_in", session: session() });
    expect(JSON.parse(localStorage.getItem("kaeru.session") ?? "null")).toEqual({
      account: { id: 42, nickname: "frog", avatar: null },
      tokens: { access_token: "old", refresh_token: "refresh-1", expires_in: 86_400, created_at: 1_000 },
    });
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "signed_in", session: session() });
  });

  it("returns the same object until something changes", () => {
    const store = storeWith();
    expect(store.get()).toBe(store.get());
  });

  it("remembers a closed account across reloads and forgets everything on sign-out", () => {
    const store = storeWith();
    store.setClosed("stranger");
    expect(store.get()).toEqual({ kind: "closed", nickname: "stranger" });
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "closed", nickname: "stranger" });

    store.signOut("Сессия истекла, войдите снова");
    expect(store.get()).toEqual({ kind: "signed_out", message: "Сессия истекла, войдите снова" });
    expect(localStorage.getItem("kaeru.session")).toBeNull();
  });

  it("reads anything unreadable as signed out", () => {
    localStorage.setItem("kaeru.session", "{not json");
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "signed_out", message: null });
    localStorage.setItem("kaeru.session", JSON.stringify({ account: { id: 42 }, tokens: {} }));
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "signed_out", message: null });
  });

  it("tells subscribers about every change until they leave", () => {
    const store = storeWith(null);
    const heard = vi.fn();
    const leave = store.subscribe(heard);
    store.setSession(session());
    store.setClosed("stranger");
    leave();
    store.signOut();
    expect(heard).toHaveBeenCalledTimes(2);
  });

  it("follows a sign-in or sign-out made in another tab", () => {
    const store = storeWith(null);
    const heard = vi.fn();
    store.subscribe(heard);

    localStorage.setItem(
      "kaeru.session",
      JSON.stringify({ account, tokens: { access_token: "tab", refresh_token: "r", expires_in: 86_400, created_at: 1_000 } }),
    );
    window.dispatchEvent(new StorageEvent("storage", { key: "kaeru.session", storageArea: localStorage }));

    expect(heard).toHaveBeenCalledTimes(1);
    expect(store.get()).toEqual({ kind: "signed_in", session: session("tab", "r") });
  });
});

describe("useAccess", () => {
  it("follows the module's store", () => {
    const { result, unmount } = renderHook(() => useAccess());
    expect(result.current).toEqual({ kind: "signed_out", message: null });

    act(() => sessionStore.setSession(session()));
    expect(result.current).toEqual({ kind: "signed_in", session: session() });

    act(() => sessionStore.signOut("Сессия истекла, войдите снова"));
    expect(result.current).toEqual({ kind: "signed_out", message: "Сессия истекла, войдите снова" });
    unmount();
  });
});

describe("authorized", () => {
  it("refreshes once on a 401 and retries with the new bearer", async () => {
    const store = storeWith();
    const { refresh, seen } = refresher({ kind: "ok", tokens: tokens("new", "refresh-2", 1_757_600_000) });
    const bearers: string[] = [];

    const result = await authorized(
      async (token) => {
        bearers.push(token);
        if (token === "old") throw new ApiError(401);
        return 42;
      },
      { store, refresh, locks: null, now },
    );

    expect(result).toBe(42);
    expect(bearers).toEqual(["old", "new"]);
    expect(seen).toEqual(["refresh-1"]);
    expect(store.get()).toEqual({ kind: "signed_in", session: { account, tokens: tokens("new", "refresh-2", 1_757_600_000) } });
  });

  it("ends the session on invalid_grant or sign_in, and the 401 stands", async () => {
    for (const verdict of [{ kind: "invalid_grant" }, { kind: "sign_in" }] as const) {
      const store = storeWith();
      const { refresh } = refresher(verdict);
      let calls = 0;

      const error = await authorized(
        async () => {
          calls += 1;
          throw new ApiError(401);
        },
        { store, refresh, locks: null, now },
      ).catch((caught: unknown) => caught);

      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ status: 401 });
      expect(calls).toBe(1);
      expect(store.get()).toEqual({ kind: "signed_out", message: "Сессия истекла, войдите снова" });
    }
  });

  it("closes access when the account was taken off the list", async () => {
    const store = storeWith();
    const { refresh } = refresher({ kind: "closed", nickname: "frog" });

    const error = await authorized(
      async () => {
        throw new ApiError(401);
      },
      { store, refresh, locks: null, now },
    ).catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401 });
    expect(store.get()).toEqual({ kind: "closed", nickname: "frog" });
  });

  it("keeps the session when the refresh fails without a verdict on it", async () => {
    const cases: Array<[TokenResult, (error: unknown) => void]> = [
      [{ kind: "network" }, (error) => expect(error).toBeInstanceOf(NetworkError)],
      [{ kind: "throttled" }, (error) => expect(error).toMatchObject({ status: 429 })],
      [{ kind: "unavailable" }, (error) => expect(error).toMatchObject({ status: 502 })],
      [{ kind: "not_configured" }, (error) => expect(error).toMatchObject({ status: 503 })],
      [{ kind: "rejected" }, (error) => expect(error).toMatchObject({ status: 401 })],
    ];
    for (const [answer, check] of cases) {
      const store = storeWith();
      const { refresh, seen } = refresher(answer);
      let calls = 0;

      const error = await authorized(
        async () => {
          calls += 1;
          throw new ApiError(401);
        },
        { store, refresh, locks: null, now },
      ).catch((caught: unknown) => caught);

      check(error);
      expect(seen).toEqual(["refresh-1"]);
      expect(calls).toBe(1);
      expect(store.get()).toEqual({ kind: "signed_in", session: session() });
    }
  });

  it("does not refresh a second time when the retry is refused too", async () => {
    const store = storeWith();
    const { refresh, seen } = refresher({ kind: "ok", tokens: tokens("new", "refresh-2") });
    let calls = 0;

    const error = await authorized(
      async () => {
        calls += 1;
        throw new ApiError(401);
      },
      { store, refresh, locks: null, now },
    ).catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401 });
    expect(calls).toBe(2);
    expect(seen).toHaveLength(1);
  });

  it("sends nothing without a session", async () => {
    const store = storeWith(null);
    const { refresh, seen } = refresher();
    const call = vi.fn(async () => 1);

    const error = await authorized(call, { store, refresh, locks: null, now }).catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401 });
    expect(call).not.toHaveBeenCalled();
    expect(seen).toEqual([]);
  });

  it("reuses a token another tab already rotated to, without refreshing", async () => {
    const store = storeWith();
    const { refresh, seen } = refresher();
    const bearers: string[] = [];

    const result = await authorized(
      async (token) => {
        bearers.push(token);
        if (token !== "old") return 42;
        store.setSession(session("rotated", "refresh-2"));
        throw new ApiError(401);
      },
      { store, refresh, locks: null, now },
    );

    expect(result).toBe(42);
    expect(bearers).toEqual(["old", "rotated"]);
    expect(seen).toEqual([]);
  });

  it("refreshes ahead when less than a minute is left, and not at exactly a minute", async () => {
    const expiry = (1_000 + 86_400) * 1000;

    const early = storeWith();
    const first = refresher({ kind: "ok", tokens: tokens("new", "refresh-2", 87_340) });
    const bearers: string[] = [];
    await authorized(async (token) => void bearers.push(token), { store: early, refresh: first.refresh, locks: null, now: () => expiry - 59_000 });
    expect(first.seen).toEqual(["refresh-1"]);
    expect(bearers).toEqual(["new"]);

    const onTime = storeWith();
    const second = refresher();
    await authorized(async (token) => void bearers.push(token), { store: onTime, refresh: second.refresh, locks: null, now: () => expiry - 60_000 });
    expect(second.seen).toEqual([]);
    expect(bearers).toEqual(["new", "old"]);
  });

  it("passes other failures through without a refresh", async () => {
    for (const failure of [new ApiError(500), new NetworkError(), new ApiError(403)]) {
      const store = storeWith();
      const { refresh, seen } = refresher();
      const error = await authorized(
        async () => {
          throw failure;
        },
        { store, refresh, locks: null, now },
      ).catch((caught: unknown) => caught);
      expect(error).toBe(failure);
      expect(seen).toEqual([]);
    }
  });

  it("refreshes once for concurrent 401s", async () => {
    const store = storeWith();
    const seen: string[] = [];
    let release!: (result: TokenResult) => void;
    const refresh = (refreshToken: string): Promise<TokenResult> => {
      seen.push(refreshToken);
      return new Promise<TokenResult>((resolve) => {
        release = resolve;
      });
    };
    const call = async (token: string): Promise<string> => {
      if (token === "old") throw new ApiError(401);
      return token;
    };

    const both = Promise.all([authorized(call, { store, refresh, locks: null, now }), authorized(call, { store, refresh, locks: null, now })]);
    await vi.waitFor(() => expect(seen).toHaveLength(1));
    release({ kind: "ok", tokens: tokens("new", "refresh-2") });

    await expect(both).resolves.toEqual(["new", "new"]);
    expect(seen).toEqual(["refresh-1"]);
  });

  it("refreshes under the kaeru-refresh Web Lock when there is one", async () => {
    const store = storeWith();
    const names: string[] = [];
    const locks = {
      request: (name: string, callback: () => Promise<unknown>) => {
        names.push(name);
        return callback();
      },
    } as unknown as LockManager;
    const { refresh } = refresher({ kind: "ok", tokens: tokens("new", "refresh-2") });

    await authorized(
      async (token) => {
        if (token === "old") throw new ApiError(401);
        return token;
      },
      { store, refresh, locks, now },
    );

    expect(names).toEqual(["kaeru-refresh"]);
  });

  it("never lets an in-flight refresh overwrite a sign-out or a newer sign-in", async () => {
    const replacements: Array<Session | null> = [
      null,
      { account: { id: 7, nickname: "other", avatar: null }, tokens: tokens("login", "login-refresh") },
      // Identical credentials still count as a new session.
      session(),
    ];
    for (const replacement of replacements) {
      for (const succeeds of [true, false]) {
        const store = storeWith();
        let started!: () => void;
        const refreshing = new Promise<void>((resolve) => {
          started = resolve;
        });
        let release!: (result: TokenResult) => void;
        const refresh = (): Promise<TokenResult> => {
          started();
          return new Promise<TokenResult>((resolve) => {
            release = resolve;
          });
        };
        let calls = 0;
        const pending = authorized(
          async () => {
            calls += 1;
            throw new ApiError(401);
          },
          { store, refresh, locks: null, now },
        ).catch((caught: unknown) => caught);

        await refreshing;
        if (replacement === null) store.signOut();
        else store.setSession(replacement);
        const expected = store.get();
        release(succeeds ? { kind: "ok", tokens: tokens("stale-refresh", "stale-refresh-token") } : { kind: "invalid_grant" });

        const error = await pending;
        expect(error).toBeInstanceOf(ApiError);
        expect(error).toMatchObject({ status: 401 });
        expect(store.get()).toBe(expected);
        expect(calls).toBe(1);
      }
    }
  });
});
```

`web/src/auth/signin.test.ts`:

```ts
// Vectors: android/src/test/java/app/kaeru/data/auth/ShikimoriAuthRepositoryTest.kt (authorize URL, single-use
// state, no account switch, whoami before the session is saved), android/.../ui/common/auth/AuthViewModel.kt and
// android/src/main/java/app/kaeru/ui/common/ErrorMessages.kt (copy)
import { beforeEach, describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Account } from "../api/shikimori";
import type { TokenResult, Tokens } from "./relay";
import { SessionStore } from "./session";
import { beginSignIn, completeSignIn, signInErrorMessage } from "./signin";

const ORIGIN = "http://localhost:5173";
/** base64url of the bytes 0 to 31 in order. */
const COUNTING_STATE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
const counting = (bytes: Uint8Array): Uint8Array => {
  bytes.forEach((_, index) => {
    bytes[index] = index;
  });
  return bytes;
};
const TOKENS: Tokens = { accessToken: "acc", refreshToken: "ref", expiresIn: 86_400, createdAt: 1_757_600_000 };
const FROG: Account = { id: 42, nickname: "frog", avatar: "https://shikimori.io/frog.png" };

function exchanger(result: TokenResult) {
  const calls: Array<[string, string]> = [];
  const exchange = async (code: string, redirect: string): Promise<TokenResult> => {
    calls.push([code, redirect]);
    return result;
  };
  return { exchange, calls };
}

function identity(answer: () => Promise<Account | null>) {
  const tokens: string[] = [];
  const shikimori = {
    whoami: (token: string): Promise<Account | null> => {
      tokens.push(token);
      return answer();
    },
  };
  return { shikimori, tokens };
}

function arm(returnTo = "/anime/1535"): void {
  beginSignIn(returnTo, { storage: sessionStorage, random: counting, origin: ORIGIN });
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
});

describe("beginSignIn", () => {
  it("builds Shikimori's authorize URL with the site's redirect and remembers the attempt", () => {
    const url = new URL(beginSignIn("/anime/1535", { storage: sessionStorage, random: counting, origin: ORIGIN }));

    expect(`${url.origin}${url.pathname}`).toBe("https://shikimori.io/oauth/authorize");
    const keys: string[] = [];
    url.searchParams.forEach((_value, key) => keys.push(key));
    expect(keys).toEqual(["client_id", "redirect_uri", "response_type", "scope", "state"]);
    expect(url.searchParams.get("client_id")).toBe("_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg");
    expect(url.searchParams.get("redirect_uri")).toBe("http://localhost:5173/auth");
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("scope")).toBe("user_rates");
    expect(url.searchParams.get("state")).toBe(COUNTING_STATE);
    expect(url.search).toContain("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fauth");
    expect(JSON.parse(sessionStorage.getItem("kaeru.signin") ?? "null")).toEqual({ state: COUNTING_STATE, returnTo: "/anime/1535" });
  });

  it("encodes the state as base64url without padding", () => {
    const url = new URL(beginSignIn("/", { storage: sessionStorage, random: (bytes) => bytes.fill(255), origin: ORIGIN }));
    expect(url.searchParams.get("state")).toBe(`${"_".repeat(42)}8`);
  });

  it("draws a fresh unguessable state per attempt", () => {
    const first = new URL(beginSignIn("/", { storage: sessionStorage, origin: ORIGIN })).searchParams.get("state") ?? "";
    const second = new URL(beginSignIn("/", { storage: sessionStorage, origin: ORIGIN })).searchParams.get("state") ?? "";
    expect(first).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(second).not.toBe(first);
  });

  it("returns only to a path on this site, never to the callback", () => {
    const cases: Array<[string, string]> = [
      ["/w/AAAAAAAAAAA#key", "/w/AAAAAAAAAAA#key"],
      ["//evil.example/x", "/"],
      ["https://evil.example/x", "/"],
      ["/auth?code=1", "/"],
      ["", "/"],
    ];
    for (const [asked, kept] of cases) {
      beginSignIn(asked, { storage: sessionStorage, random: counting, origin: ORIGIN });
      expect(JSON.parse(sessionStorage.getItem("kaeru.signin") ?? "null")).toEqual({ state: COUNTING_STATE, returnTo: kept });
    }
  });
});

describe("completeSignIn", () => {
  it("exchanges the code with the same redirect, asks whoami with the new token, then saves the session", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
    const { shikimori, tokens } = identity(async () => FROG);

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

    expect(result).toEqual({ kind: "done", returnTo: "/anime/1535" });
    expect(calls).toEqual([["abc", "http://localhost:5173/auth"]]);
    expect(tokens).toEqual(["acc"]);
    expect(store.get()).toEqual({ kind: "signed_in", session: { account: FROG, tokens: TOKENS } });
    expect(sessionStorage.getItem("kaeru.signin")).toBeNull();
  });

  it("uses a state once, so a replayed callback is refused", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
    const { shikimori } = identity(async () => FROG);
    const search = `?code=abc&state=${COUNTING_STATE}`;

    await completeSignIn(search, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });
    store.signOut();
    const replay = await completeSignIn(search, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

    expect(replay).toEqual({ kind: "error", message: "Не удалось подтвердить вход. Войдите заново" });
    expect(calls).toHaveLength(1);
  });

  it("refuses a callback nobody started, a wrong state, or duplicated parameters without exchanging", async () => {
    const searches = [
      { armed: false, search: `?code=abc&state=${COUNTING_STATE}` },
      { armed: true, search: "?code=attacker&state=not-the-state" },
      { armed: true, search: "?code=attacker" },
      { armed: true, search: `?code=abc&state=${COUNTING_STATE}&state=${COUNTING_STATE}` },
      { armed: true, search: `?code=abc&code=def&state=${COUNTING_STATE}` },
    ];
    for (const { armed, search } of searches) {
      sessionStorage.clear();
      if (armed) arm();
      const store = new SessionStore(localStorage);
      const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
      const { shikimori } = identity(async () => FROG);

      const result = await completeSignIn(search, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

      expect(result).toEqual({ kind: "error", message: "Не удалось подтвердить вход. Войдите заново" });
      expect(calls).toEqual([]);
      expect(store.get().kind).toBe("signed_out");
    }
  });

  it("says Shikimori sent no code when there is none", async () => {
    for (const search of [`?error=access_denied&state=${COUNTING_STATE}`, `?code=&state=${COUNTING_STATE}`, ""]) {
      arm();
      const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
      const result = await completeSignIn(search, {
        storage: sessionStorage,
        store: new SessionStore(localStorage),
        exchange,
        shikimori: identity(async () => FROG).shikimori,
        origin: ORIGIN,
      });
      expect(result).toEqual({ kind: "error", message: "Shikimori не вернул код. Попробуйте войти ещё раз" });
      expect(calls).toEqual([]);
    }
  });

  it("never switches accounts under a signed-in viewer", async () => {
    arm();
    const store = new SessionStore(localStorage);
    store.setSession({ account: { id: 7, nickname: "owner", avatar: null }, tokens: { ...TOKENS, accessToken: "owner" } });
    const before = store.get();
    const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, {
      storage: sessionStorage,
      store,
      exchange,
      shikimori: identity(async () => FROG).shikimori,
      origin: ORIGIN,
    });

    expect(result).toEqual({ kind: "error", message: "Вход уже выполнен. Запрос авторизации отклонён" });
    expect(calls).toEqual([]);
    expect(store.get()).toBe(before);
  });

  it("closes access for an account that is not on the list, keeping no tokens", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange } = exchanger({ kind: "closed", nickname: "stranger" });
    const { shikimori, tokens } = identity(async () => FROG);

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

    expect(result).toEqual({ kind: "closed", nickname: "stranger" });
    expect(store.get()).toEqual({ kind: "closed", nickname: "stranger" });
    expect(tokens).toEqual([]);
  });

  it("reports a failed exchange in words and saves nothing", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange } = exchanger({ kind: "unavailable" });

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, {
      storage: sessionStorage,
      store,
      exchange,
      shikimori: identity(async () => FROG).shikimori,
      origin: ORIGIN,
    });

    expect(result).toEqual({ kind: "error", message: "Shikimori недоступен, попробуйте позже" });
    expect(store.get().kind).toBe("signed_out");
  });

  it("fails the sign-in when whoami fails or knows nobody", async () => {
    const answers: Array<[() => Promise<Account | null>, string]> = [
      [() => Promise.reject(new NetworkError()), "Нет соединения. Проверьте интернет"],
      [() => Promise.reject(new ApiError(503)), "Shikimori недоступен, попробуйте позже"],
      [() => Promise.resolve(null), "Что-то пошло не так. Повторите попытку"],
    ];
    for (const [answer, message] of answers) {
      arm();
      const store = new SessionStore(localStorage);
      const { exchange } = exchanger({ kind: "ok", tokens: TOKENS });

      const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, {
        storage: sessionStorage,
        store,
        exchange,
        shikimori: identity(answer).shikimori,
        origin: ORIGIN,
      });

      expect(result).toEqual({ kind: "error", message });
      expect(store.get().kind).toBe("signed_out");
    }
  });
});

describe("signInErrorMessage", () => {
  it("names what went wrong in the apps' words", () => {
    expect(signInErrorMessage({ kind: "unavailable" })).toBe("Shikimori недоступен, попробуйте позже");
    expect(signInErrorMessage({ kind: "throttled" })).toBe("Слишком много запросов, попробуйте позже");
    expect(signInErrorMessage({ kind: "not_configured" })).toBe("Вход временно недоступен, попробуйте позже");
    expect(signInErrorMessage({ kind: "network" })).toBe("Нет соединения. Проверьте интернет");
    expect(signInErrorMessage({ kind: "invalid_grant" })).toBe("Что-то пошло не так. Повторите попытку");
    expect(signInErrorMessage({ kind: "rejected" })).toBe("Что-то пошло не так. Повторите попытку");
    expect(signInErrorMessage({ kind: "sign_in" })).toBe("Что-то пошло не так. Повторите попытку");
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run (from `web/`): `npx vitest run src/auth/relay.test.ts src/auth/session.test.ts src/auth/signin.test.ts`

**Expected:** all three files fail because a module from this task is missing.
- `relay.test.ts` fails with `Failed to resolve import "./relay"`.
- `session.test.ts` and `signin.test.ts` fail with `Failed to resolve import "./session"`, because their type-only imports are erased first.
- The summary line is `Test Files  3 failed (3)`.

- [ ] **Step 3: Implement**

`web/src/auth/relay.ts`:

```ts
import { CLIENT_ID, RELAY_URL } from "../config";

/** `createdAt` is in epoch SECONDS, as Shikimori sends it. */
export interface Tokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  createdAt: number;
}

export type TokenResult =
  | { kind: "ok"; tokens: Tokens }
  | { kind: "closed"; nickname: string }
  | { kind: "sign_in" }
  | { kind: "invalid_grant" }
  | { kind: "unavailable" }
  | { kind: "throttled" }
  | { kind: "not_configured" }
  | { kind: "rejected" }
  | { kind: "network" };

const TOKEN_TIMEOUT_MS = 30_000;
const DEFAULT_EXPIRES_IN = 86_400;

export function exchangeCode(code: string, redirectUri: string, deps: { fetch?: typeof fetch } = {}): Promise<TokenResult> {
  return tokenCall(
    new URLSearchParams({ grant_type: "authorization_code", client_id: CLIENT_ID, code, redirect_uri: redirectUri }),
    deps.fetch,
  );
}

export function refreshTokens(refreshToken: string, deps: { fetch?: typeof fetch } = {}): Promise<TokenResult> {
  return tokenCall(new URLSearchParams({ grant_type: "refresh_token", client_id: CLIENT_ID, refresh_token: refreshToken }), deps.fetch);
}

async function tokenCall(form: URLSearchParams, send: typeof fetch = (input, init) => fetch(input, init)): Promise<TokenResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TOKEN_TIMEOUT_MS);
  let status: number;
  let text: string;
  try {
    // No headers at all: the worker's preflight allows only authorization and content-type, and a
    // URLSearchParams body gets the form content type and the Content-Length the worker requires.
    const response = await send(`${RELAY_URL}/oauth/token`, { method: "POST", body: form, signal: controller.signal });
    status = response.status;
    text = await response.text();
  } catch {
    return { kind: "network" };
  } finally {
    clearTimeout(timer);
  }
  return interpret(status, parseObject(text));
}

function parseObject(text: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
  } catch {
    // The worker's own refusals are plain text.
    return null;
  }
}

/** Told apart by the JSON `error` field, not by the status alone. */
function interpret(status: number, body: Record<string, unknown> | null): TokenResult {
  const error = body?.["error"];
  if (status >= 200 && status <= 299) {
    const tokens = readTokens(body);
    // A 200 without both tokens is an answer nobody could read, like the worker's own 502.
    return tokens === null ? { kind: "unavailable" } : { kind: "ok", tokens };
  }
  if ((status === 400 || status === 401) && error === "invalid_grant") return { kind: "invalid_grant" };
  if (status === 401 && error === "sign_in") return { kind: "sign_in" };
  if (status === 403 && error === "not_allowed") {
    const nickname = body?.["nickname"];
    return { kind: "closed", nickname: typeof nickname === "string" ? nickname : "" };
  }
  if (status === 429) return { kind: "throttled" };
  if (status === 503) return { kind: "not_configured" };
  if (status >= 500) return { kind: "unavailable" };
  return { kind: "rejected" };
}

function readTokens(body: Record<string, unknown> | null): Tokens | null {
  const accessToken = body?.["access_token"];
  const refreshToken = body?.["refresh_token"];
  if (typeof accessToken !== "string" || accessToken.trim() === "") return null;
  if (typeof refreshToken !== "string" || refreshToken.trim() === "") return null;
  const expiresIn = Number(body?.["expires_in"] ?? DEFAULT_EXPIRES_IN);
  const createdAt = Number(body?.["created_at"] ?? 0);
  return {
    accessToken,
    refreshToken,
    expiresIn: Number.isFinite(expiresIn) && expiresIn > 0 ? expiresIn : DEFAULT_EXPIRES_IN,
    // Shikimori's own clock when it says; this device's otherwise (iOS does the same).
    createdAt: Number.isFinite(createdAt) && createdAt > 0 ? createdAt : Math.floor(Date.now() / 1000),
  };
}
```

`web/src/auth/session.ts`:

```ts
import { useSyncExternalStore } from "react";
import { ApiError, NetworkError } from "../api/http";
import type { Account } from "../api/shikimori";
import { refreshTokens } from "./relay";
import type { TokenResult, Tokens } from "./relay";

export interface Session {
  account: Account;
  tokens: Tokens;
}

export type AccessState =
  | { kind: "signed_out"; message: string | null }
  | { kind: "closed"; nickname: string }
  | { kind: "signed_in"; session: Session };

const SESSION_KEY = "kaeru.session";
const EXPIRED = "Сессия истекла, войдите снова";
const AHEAD_MS = 60_000;
const LOCK_NAME = "kaeru-refresh";

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

/** The iOS Keychain shape: the endpoint's own snake_case token keys. */
function encodeSession(session: Session): string {
  const { account, tokens } = session;
  return JSON.stringify({
    account: { id: account.id, nickname: account.nickname, avatar: account.avatar },
    tokens: {
      access_token: tokens.accessToken,
      refresh_token: tokens.refreshToken,
      expires_in: tokens.expiresIn,
      created_at: tokens.createdAt,
    },
  });
}

function decode(raw: string | null): AccessState {
  let root: Record<string, unknown> | null = null;
  try {
    root = raw === null ? null : record(JSON.parse(raw));
  } catch {
    root = null;
  }
  const closed = record(root?.["closed"]);
  const closedNickname = closed?.["nickname"];
  if (typeof closedNickname === "string") return { kind: "closed", nickname: closedNickname };
  const account = record(root?.["account"]);
  const tokens = record(root?.["tokens"]);
  const id = account?.["id"];
  const nickname = account?.["nickname"];
  const avatar = account?.["avatar"];
  const accessToken = tokens?.["access_token"];
  const refreshToken = tokens?.["refresh_token"];
  const expiresIn = tokens?.["expires_in"];
  const createdAt = tokens?.["created_at"];
  if (
    typeof id !== "number" || id <= 0 ||
    typeof nickname !== "string" ||
    (avatar !== null && typeof avatar !== "string") ||
    typeof accessToken !== "string" || accessToken === "" ||
    typeof refreshToken !== "string" || refreshToken === "" ||
    typeof expiresIn !== "number" || typeof createdAt !== "number"
  ) {
    return { kind: "signed_out", message: null };
  }
  return {
    kind: "signed_in",
    session: { account: { id, nickname, avatar }, tokens: { accessToken, refreshToken, expiresIn, createdAt } },
  };
}

function defaultStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    // Blocked storage (some private modes) throws on access; the tab keeps state in memory.
    return null;
  }
}

/**
 * Who is signed in, kept in localStorage so every tab shares it. `get()` re-reads storage, so a
 * write from another tab is seen at once, and returns the same object while nothing changed, as
 * `useSyncExternalStore` needs.
 */
export class SessionStore {
  private readonly storage: Storage | null;
  private readonly listeners = new Set<() => void>();
  private raw: string | null = null;
  private state: AccessState;

  private readonly onStorage = (event: StorageEvent): void => {
    if (event.storageArea !== this.storage) return;
    if (event.key !== null && event.key !== SESSION_KEY) return;
    const before = this.state;
    if (this.get() !== before) this.emit();
  };

  constructor(storage?: Storage) {
    this.storage = storage ?? defaultStorage();
    this.raw = this.read();
    this.state = decode(this.raw);
    if (typeof window !== "undefined") window.addEventListener("storage", this.onStorage);
  }

  get(): AccessState {
    const raw = this.read();
    if (raw !== this.raw) {
      this.raw = raw;
      this.state = decode(raw);
    }
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  setSession(session: Session): void {
    this.write(encodeSession(session), null);
  }

  setClosed(nickname: string): void {
    this.write(JSON.stringify({ closed: { nickname } }), null);
  }

  /** Local only: neither app revokes the token on the server. */
  signOut(message: string | null = null): void {
    this.write(null, message);
  }

  private read(): string | null {
    if (this.storage === null) return this.raw;
    try {
      return this.storage.getItem(SESSION_KEY);
    } catch {
      return this.raw;
    }
  }

  private write(raw: string | null, message: string | null): void {
    try {
      if (raw === null) this.storage?.removeItem(SESSION_KEY);
      else this.storage?.setItem(SESSION_KEY, raw);
    } catch {
      // A full or blocked storage: this tab still follows the change.
    }
    this.raw = raw;
    // Always a new object, even for identical data: an in-flight refresh compares by identity.
    this.state = raw === null ? { kind: "signed_out", message } : decode(raw);
    this.emit();
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }
}

export const sessionStore = new SessionStore();

const subscribeAccess = (listener: () => void): (() => void) => sessionStore.subscribe(listener);
const readAccess = (): AccessState => sessionStore.get();

export function useAccess(): AccessState {
  return useSyncExternalStore(subscribeAccess, readAccess);
}

type Rotation = { kind: "token"; token: string } | { kind: "ended" } | { kind: "kept"; error: Error | null };

/** Without Web Locks, refreshes in this tab still run one at a time. */
let serial: Promise<unknown> = Promise.resolve();

async function withLock<T>(locks: LockManager | null, task: () => Promise<T>): Promise<T> {
  if (locks !== null) {
    const result: unknown = await locks.request(LOCK_NAME, () => task());
    return result as T;
  }
  const run = serial.then(() => task());
  serial = run.catch(() => undefined);
  return run;
}

function browserLocks(): LockManager | null {
  return typeof navigator !== "undefined" && navigator.locks ? navigator.locks : null;
}

function expiresAt(tokens: Tokens): number {
  return (tokens.createdAt + tokens.expiresIn) * 1000;
}

/** A refresh that failed without a verdict on the session, as the error the caller understands. */
function refreshFailure(result: TokenResult): Error | null {
  switch (result.kind) {
    case "network":
      return new NetworkError();
    case "throttled":
      return new ApiError(429);
    case "unavailable":
      return new ApiError(502);
    case "not_configured":
      return new ApiError(503);
    default:
      // The worker's own plain refusals say nothing about the request; the 401 stands.
      return null;
  }
}

/**
 * A token to replace `stale`: one another request or tab already rotated to, or a refreshed one.
 * Runs under the lock and re-reads the store inside it, because tabs share one rotating refresh
 * token and two of them spending it would earn `invalid_grant`.
 */
function rotate(store: SessionStore, refresh: typeof refreshTokens, locks: LockManager | null, stale: string, allowRefresh: boolean): Promise<Rotation> {
  return withLock(locks, async (): Promise<Rotation> => {
    const before = store.get();
    if (before.kind !== "signed_in") return { kind: "ended" };
    const current = before.session.tokens.accessToken;
    if (current !== stale) return { kind: "token", token: current };
    if (!allowRefresh) return { kind: "kept", error: null };
    const result = await refresh(before.session.tokens.refreshToken);
    // Compare-and-set: a sign-out or a new sign-in that landed meanwhile wins over this answer.
    if (store.get() !== before) return { kind: "kept", error: null };
    switch (result.kind) {
      case "ok":
        store.setSession({ account: before.session.account, tokens: result.tokens });
        return { kind: "token", token: result.tokens.accessToken };
      case "invalid_grant":
      case "sign_in":
        store.signOut(EXPIRED);
        return { kind: "ended" };
      case "closed":
        store.setClosed(result.nickname);
        return { kind: "ended" };
      default:
        return { kind: "kept", error: refreshFailure(result) };
    }
  });
}

/**
 * `call` with the session's bearer: refreshed ahead when under a minute is left, and refreshed
 * once more (at most one refresh per call) and retried once after a 401. Only `invalid_grant`,
 * `sign_in` and `closed` end the session.
 */
export async function authorized<T>(
  call: (token: string) => Promise<T>,
  deps: { store?: SessionStore; refresh?: typeof refreshTokens; locks?: LockManager | null; now?: () => number } = {},
): Promise<T> {
  const store = deps.store ?? sessionStore;
  const refresh = deps.refresh ?? refreshTokens;
  const locks = deps.locks === undefined ? browserLocks() : deps.locks;
  const now = deps.now ?? Date.now;

  const state = store.get();
  // Nothing to authorize with: the gate shows sign-in; nothing is sent.
  if (state.kind !== "signed_in") throw new ApiError(401);
  let token = state.session.tokens.accessToken;
  let refreshed = false;
  if (expiresAt(state.session.tokens) - now() < AHEAD_MS) {
    refreshed = true;
    const ahead = await rotate(store, refresh, locks, token, true);
    if (ahead.kind === "token") token = ahead.token;
    else if (ahead.kind === "ended") throw new ApiError(401);
    // Kept: the old token may still work, and a wrong clock must not block every call.
  }
  try {
    return await call(token);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
    const rotation = await rotate(store, refresh, locks, token, !refreshed);
    if (rotation.kind === "token") return call(rotation.token);
    if (rotation.kind === "kept" && rotation.error !== null) throw rotation.error;
    throw error;
  }
}
```

`web/src/auth/signin.ts`:

```ts
import { CLIENT_ID, SHIKIMORI_URL, redirectUri } from "../config";
import { createShikimoriHttp, errorMessage } from "../api/http";
import { createShikimori } from "../api/shikimori";
import type { Account, Shikimori } from "../api/shikimori";
import { exchangeCode } from "./relay";
import type { TokenResult } from "./relay";
import { sessionStore } from "./session";
import type { SessionStore } from "./session";

const PENDING_KEY = "kaeru.signin";
const STATE_BYTES = 32;

const REJECTED = "Не удалось подтвердить вход. Войдите заново";
const NO_CODE = "Shikimori не вернул код. Попробуйте войти ещё раз";
const ALREADY_SIGNED_IN = "Вход уже выполнен. Запрос авторизации отклонён";
const UNKNOWN = "Что-то пошло не так. Повторите попытку";

export type SignInResult = { kind: "done"; returnTo: string } | { kind: "closed"; nickname: string } | { kind: "error"; message: string };

interface Pending {
  state: string;
  returnTo: string;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Only a path on this site, and never the callback itself: anything else is an open redirect or a loop. */
function safeReturnTo(path: string): string {
  if (!path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) return "/";
  return /^\/auth(?:[/?#]|$)/.test(path) ? "/" : path;
}

function readPending(raw: string | null): Pending | null {
  try {
    const value: unknown = raw === null ? null : JSON.parse(raw);
    if (typeof value !== "object" || value === null) return null;
    const { state, returnTo } = value as { state?: unknown; returnTo?: unknown };
    if (typeof state !== "string" || state === "" || typeof returnTo !== "string") return null;
    return { state, returnTo: safeReturnTo(returnTo) };
  } catch {
    return null;
  }
}

/** No early exit on the first differing character, like the apps' MessageDigest.isEqual. */
function sameText(actual: string, expected: string): boolean {
  let diff = actual.length ^ expected.length;
  for (let i = 0; i < expected.length; i += 1) diff |= (actual.charCodeAt(i) || 0) ^ expected.charCodeAt(i);
  return diff === 0;
}

/**
 * Arms a sign-in and returns Shikimori's authorize page. The state lives in sessionStorage because
 * the page reloads on the way back, and it names where to return, a pending invitation included.
 */
export function beginSignIn(
  returnTo: string,
  deps: { storage?: Storage; random?: (bytes: Uint8Array) => Uint8Array; origin?: string } = {},
): string {
  const storage = deps.storage ?? window.sessionStorage;
  const random = deps.random ?? ((bytes: Uint8Array) => crypto.getRandomValues(bytes));
  const origin = deps.origin ?? window.location.origin;
  const state = base64Url(random(new Uint8Array(STATE_BYTES)));
  const pending: Pending = { state, returnTo: safeReturnTo(returnTo) };
  storage.setItem(PENDING_KEY, JSON.stringify(pending));
  return (
    `${SHIKIMORI_URL}/oauth/authorize?client_id=${encodeURIComponent(CLIENT_ID)}` +
    `&redirect_uri=${encodeURIComponent(redirectUri(origin))}` +
    `&response_type=code&scope=user_rates&state=${encodeURIComponent(state)}`
  );
}

export function signInErrorMessage(result: TokenResult): string {
  switch (result.kind) {
    case "unavailable":
      return "Shikimori недоступен, попробуйте позже";
    case "throttled":
      return "Слишком много запросов, попробуйте позже";
    case "not_configured":
      return "Вход временно недоступен, попробуйте позже";
    case "network":
      return "Нет соединения. Проверьте интернет";
    default:
      return UNKNOWN;
  }
}

/**
 * The /auth callback: checks the state, exchanges the code through the worker, asks whoami with
 * the new token and only then saves the session (Android's order). The pending attempt is
 * consumed first, so a reload or a replayed link never spends a code twice.
 */
export async function completeSignIn(
  search: string,
  deps: {
    storage?: Storage;
    store?: SessionStore;
    exchange?: typeof exchangeCode;
    shikimori?: Pick<Shikimori, "whoami">;
    origin?: string;
  } = {},
): Promise<SignInResult> {
  const storage = deps.storage ?? window.sessionStorage;
  const store = deps.store ?? sessionStore;
  const exchange = deps.exchange ?? exchangeCode;
  const origin = deps.origin ?? window.location.origin;

  const pending = readPending(storage.getItem(PENDING_KEY));
  storage.removeItem(PENDING_KEY);

  // A silent account switch would replace the signed-in viewer's list.
  if (store.get().kind === "signed_in") return { kind: "error", message: ALREADY_SIGNED_IN };
  const params = new URLSearchParams(search);
  const codes = params.getAll("code");
  const states = params.getAll("state");
  const code = codes[0] ?? "";
  if (code.trim() === "") return { kind: "error", message: NO_CODE };
  const state = states[0];
  if (pending === null || codes.length !== 1 || states.length !== 1 || state === undefined || !sameText(state, pending.state)) {
    return { kind: "error", message: REJECTED };
  }

  const result = await exchange(code, redirectUri(origin));
  if (result.kind === "closed") {
    store.setClosed(result.nickname);
    return { kind: "closed", nickname: result.nickname };
  }
  if (result.kind !== "ok") return { kind: "error", message: signInErrorMessage(result) };

  const shikimori = deps.shikimori ?? createShikimori(createShikimoriHttp());
  let account: Account | null;
  try {
    account = await shikimori.whoami(result.tokens.accessToken);
  } catch (error) {
    return { kind: "error", message: errorMessage(error) };
  }
  if (account === null) return { kind: "error", message: UNKNOWN };
  store.setSession({ account, tokens: result.tokens });
  return { kind: "done", returnTo: pending.returnTo };
}
```

- [ ] **Step 4: Run to see it pass**

Run (from `web/`): `npx vitest run src/auth/relay.test.ts src/auth/session.test.ts src/auth/signin.test.ts`

**Expected:** `Test Files  3 passed (3)`, `Tests  38 passed (38)`.

Run: `npm test`

**Expected:** every test file passes, including the three new ones. No failures.

Run: `npm run typecheck`

**Expected:** `tsc --noEmit` exits 0 with no output.

- [ ] **Step 5: Commit**

```bash
cd /Users/vitaliy/Projects/kaeru
git add web/src/auth/relay.ts web/src/auth/relay.test.ts web/src/auth/session.ts web/src/auth/session.test.ts web/src/auth/signin.ts web/src/auth/signin.test.ts
git commit -m "feat(web): вход через Shikimori — обмен кода через воркер, сессия во всех вкладках, обновление токена"
```

---

### Task 7: Library store, progress store, preferences, rate writes

**Decisions:**
- **One in-memory library, optimistic writes.** `Library` keeps two layers per title: `confirmed` holds what Shikimori acknowledged, and `pending` holds the optimistic changes of writes that have not been answered yet. The view is `confirmed` with the pending changes applied in order. When a write is answered, successfully or not, its change is dropped. A failed write therefore reverts by itself. The error (`ApiError`/`NetworkError`) is rethrown, and the screen shows `errorMessage(error)` in a toast (decision 7). The web is online-only, so Android's offline outbox is not ported.
- **One queue per title.** Each title has its own promise chain, so an older value never lands after a newer one. A write reads the confirmed rate when its turn comes, not when it was made. A refused write does not block the next one, and titles do not wait on each other.
- **Only the changed field goes out** (decision 7, Android). `setStatus` sends `POST {status}` for a title in no list, `PATCH {status}` otherwise, and nothing when the status is already that. `markWatched` sends one `POST {status: "watching", episodes: N}` when there is no rate, because both fields are new. Otherwise it sends `PATCH {status: "watching"}` when the status is planned or on_hold, then `PATCH {episodes: N}` if the count rises. If the status PATCH lands and the count PATCH fails, the list keeps «Смотрю» with the old count.
- **Marking rules** (decision 6, `MarkEpisodeWatched.kt`). The target count is `min(N, anime.episodes)` when `anime.episodes > 0`, otherwise N. Planned and on_hold become watching, even when the count already covers N. Watching, rewatching, completed and dropped stay as they are. The count only rises. `suggestCompleted` is true only when a newly counted target reaches `anime.episodes > 0`. Nothing switches to completed or rewatching by itself.
- **Unmarking and undo** (decision 6, `MarkEpisodeUnwatched.kt`). `markUnwatched` does nothing when `rate.episodes < N` or `N < 1`, and returns an undo that does nothing. Otherwise it sends `PATCH {episodes: N-1}` and keeps the status. Positions for episodes ≥ N are removed only after Shikimori accepts the PATCH, so a refused unmark keeps the viewer's place. The undo is queued like any write. It PATCHes the count back to the previous value, not to N, because episodes after N were unmarked too, and it puts the removed positions back.
- **List reads.** `load()` reads `userRates` with the token, then `byIds` without one (decision 8). Concurrent calls share one read. If a write started or finished on a title while the list was in flight, that title keeps this tab's confirmed value (Android `pendingBefore`). When the account changes, the old entries are dropped before anything is shown (`loading` with `entries: null`). With no account, the library goes back to `idle`. A failed reread keeps the entries it already had (`error` with entries).
- **Details for ongoing titles being watched** (critic coverage gap; Android `ShikimoriLibraryRepository.fetchLibrary`). List cards carry no `next_episode_at`, studios, description or screenshots. Without details, «Скоро» stays empty and the hero never says «N серия выйдет завтра». So after the state turns `ready`, `load()` runs a details pass:
  - Candidates: entries whose status is watching or rewatching and whose `anime.status` is `"ongoing"`.
  - Which ones: a title is due when it was never fetched, or was fetched 6 h (`DETAILS_TTL_MS`) or more ago. Due titles are sorted never-fetched first, then oldest fetch, and at most 25 (`DETAILS_PER_LOAD`) go per load.
  - How: one title at a time, through `shikimori.details(id)`, which is anonymous and brings `nextEpisodeAt`, `studios`, `description`, `backdropUrl` and the GraphQL poster.
  - Merge: each answer is merged into the entry at once and published. The list's poster or backdrop stays when details lack one.
  - Failures: a failed request is ignored and not recorded, so the next load asks again.
  - Memory: fetch times and results live in memory on the `Library` instance. They are catalogue data, not tied to an account. Later list reads keep the detail-only fields through `mergeShort`.
  - The pass stops when the account changes.
  - `load()` settles after the pass, so a test can `await library.load()` and see the details.
- **A write changes the rate, never the title.** When a write is confirmed, the entry keeps whatever anime the list or a details pass stored in the meantime.
- **`LibraryDeps.now`** is an optional clock, `Date.now` by default. It stamps a rate's `updatedAt` and drives the details TTL. `DETAILS_PER_LOAD` and `DETAILS_TTL_MS` are exported for tests. The constructor still accepts exactly the contract's four deps.
- **ProgressStore.** It stores one JSON array under `kaeru.progress`. Reads are tolerant: broken JSON or malformed rows read as empty or are skipped. Write failures (quota, blocked storage) still serve the current tab. `of(id)` returns the same array until that title changes, which `useSyncExternalStore` needs. A `storage` event from another tab drops the parsed copy. `clear()` removes the key, empties every title and notifies subscribers; Task 11's sign-out uses it.
- **Preferences.** The threshold lives under `kaeru.threshold` and defaults to 0.9. Values are clamped to Android's `WATCHED_THRESHOLD_RANGE` 0.5–1. NaN and ±Infinity are never stored. Blocked storage reads as the default.

**Files:**
- Create: `web/src/library/progress.ts`, `web/src/library/prefs.ts`, `web/src/library/library.ts`
- Test: `web/src/library/progress.test.ts`, `web/src/library/prefs.test.ts`, `web/src/library/library.test.ts`

**Interfaces:**
- Consumes:
  - Task 2 `web/src/domain/models.ts`: `type Anime`, `type UserRate`, `type LibraryEntry`, `type ListStatus`, `type EpisodeProgress`.
  - Task 3 `web/src/domain/progress.ts`: `DEFAULT_THRESHOLD` (0.9).
  - Task 5 `web/src/api/http.ts`: `ApiError`, `NetworkError` (tests), `errorMessage(error: unknown): string`.
  - Task 5 `web/src/api/shikimori.ts`: `type Shikimori`, specifically `userRates(userId, token)`, `byIds(ids)`, `details(id)`, `createRate(token, userId, animeId, fields)` and `updateRate(token, rateId, fields)`.
  - Task 6 `web/src/auth/session.ts`: `authorized`, used only as `typeof authorized`.
- Produces (Task 8 builds `new Library({ shikimori, authorized, accountId, progress })` and uses `progressStore`. Tasks 9–11 use the rest.):

```ts
// web/src/library/progress.ts
export class ProgressStore {
  constructor(storage?: Storage);                       // default window.localStorage, key "kaeru.progress"
  of(animeId: number): EpisodeProgress[];               // sorted by episode; same array until the title changes
  put(p: EpisodeProgress): void;                        // one row per (animeId, episode)
  removeFrom(animeId: number, episode: number): EpisodeProgress[]; // removes episodes ≥ episode, returns them
  restore(rows: readonly EpisodeProgress[]): void;
  clear(): void;                                        // removes the key, empties every title, notifies
  subscribe(l: () => void): () => void;                 // bound arrow property
}
export const progressStore: ProgressStore;

// web/src/library/prefs.ts
export const THRESHOLD_CHOICES: readonly number[];      // [0.8, 0.85, 0.9, 0.95]
export function watchedThreshold(storage?: Storage): number;          // default 0.9, key "kaeru.threshold"
export function setWatchedThreshold(value: number, storage?: Storage): void;

// web/src/library/library.ts
export type LibraryState =
  | { kind: "idle" }
  | { kind: "loading"; entries: LibraryEntry[] | null }
  | { kind: "ready"; entries: LibraryEntry[] }
  | { kind: "error"; message: string; entries: LibraryEntry[] | null };
export interface LibraryDeps {
  shikimori: Shikimori;
  authorized: typeof authorized;
  accountId: () => number | null;
  progress: ProgressStore;
  now?: () => number;
}
export const DETAILS_PER_LOAD = 25;
export const DETAILS_TTL_MS = 21_600_000;
export class Library {
  constructor(deps: LibraryDeps);
  state(): LibraryState;                                // bound arrow property
  subscribe(l: () => void): () => void;                 // bound arrow property
  load(): Promise<void>;                                // userRates → byIds → ready → details pass
  entry(animeId: number): LibraryEntry | undefined;     // stable between notifications
  setStatus(anime: Anime, status: ListStatus): Promise<void>;
  markWatched(anime: Anime, episode: number): Promise<{ suggestCompleted: boolean }>;
  markUnwatched(anime: Anime, episode: number): Promise<() => Promise<void>>; // resolves to undo
}
export function useLibrary(library: Library): LibraryState;
```

- [ ] **Step 1: Write the failing tests**

`web/src/library/progress.test.ts`:
```ts
import { describe, expect, it, vi } from "vitest";
import type { EpisodeProgress } from "../domain/models";
import { ProgressStore, progressStore } from "./progress";

const KEY = "kaeru.progress";

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (key: string) => {
      data.delete(key);
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value));
    },
  };
}

function row(animeId: number, episode: number, positionMs = 600_000): EpisodeProgress {
  return { animeId, episode, positionMs, durationMs: 1_440_000, updatedAt: 1_000 + episode };
}

describe("ProgressStore", () => {
  it("keeps a title's positions in episode order and survives a reload of the page", () => {
    const storage = memoryStorage();
    const store = new ProgressStore(storage);
    store.put(row(1535, 3));
    store.put(row(1535, 1));
    store.put(row(21, 1));

    expect(store.of(1535).map((p) => p.episode)).toEqual([1, 3]);
    expect(store.of(21)).toEqual([row(21, 1)]);
    expect(store.of(999)).toEqual([]);
    expect(new ProgressStore(storage).of(1535)).toEqual([row(1535, 1), row(1535, 3)]);
  });

  it("keeps one row per episode, the latest", () => {
    const store = new ProgressStore(memoryStorage());
    store.put(row(1535, 2, 100_000));
    store.put(row(1535, 2, 700_000));

    expect(store.of(1535)).toEqual([row(1535, 2, 700_000)]);
  });

  it("hands out the same array until that title changes", () => {
    const store = new ProgressStore(memoryStorage());
    store.put(row(1535, 1));
    store.put(row(21, 1));
    const deathNote = store.of(1535);
    const other = store.of(21);

    expect(store.of(1535)).toBe(deathNote);
    store.put(row(1535, 2));
    expect(store.of(1535)).not.toBe(deathNote);
    expect(store.of(21)).toBe(other);
  });

  it("forgets the tapped episode and every later one of that title, and returns them", () => {
    const store = new ProgressStore(memoryStorage());
    for (const episode of [4, 5, 6, 7]) store.put(row(1535, episode));
    store.put(row(21, 6));

    expect(store.removeFrom(1535, 6)).toEqual([row(1535, 6), row(1535, 7)]);
    expect(store.of(1535).map((p) => p.episode)).toEqual([4, 5]);
    expect(store.of(21)).toEqual([row(21, 6)]);
    expect(store.removeFrom(1535, 9)).toEqual([]);
  });

  it("puts removed rows back", () => {
    const store = new ProgressStore(memoryStorage());
    store.put(row(1535, 5));
    store.put(row(1535, 6));
    const removed = store.removeFrom(1535, 5);

    store.restore(removed);

    expect(store.of(1535)).toEqual([row(1535, 5), row(1535, 6)]);
  });

  it("clears every position of every title, in memory and in storage", () => {
    const storage = memoryStorage();
    const store = new ProgressStore(storage);
    store.put(row(1535, 1));
    store.put(row(21, 4));

    store.clear();

    expect(store.of(1535)).toEqual([]);
    expect(store.of(21)).toEqual([]);
    expect(storage.getItem(KEY)).toBeNull();
    expect(new ProgressStore(storage).of(21)).toEqual([]);
  });

  it("tells subscribers about every change until they leave", () => {
    const store = new ProgressStore(memoryStorage());
    const listener = vi.fn();
    const leave = store.subscribe(listener);

    store.put(row(1535, 1));
    const removed = store.removeFrom(1535, 1);
    store.restore(removed);
    store.clear();
    expect(listener).toHaveBeenCalledTimes(4);

    store.removeFrom(1535, 1);
    store.restore([]);
    expect(listener).toHaveBeenCalledTimes(4);

    leave();
    store.put(row(1535, 2));
    expect(listener).toHaveBeenCalledTimes(4);
  });

  it("reads a broken or foreign store as empty and skips malformed rows", () => {
    const broken = memoryStorage();
    broken.setItem(KEY, "{not json");
    expect(new ProgressStore(broken).of(1535)).toEqual([]);

    const mixed = memoryStorage();
    mixed.setItem(KEY, JSON.stringify([row(1535, 1), { animeId: 1535, episode: "2" }, null, row(1535, 3)]));
    expect(new ProgressStore(mixed).of(1535).map((p) => p.episode)).toEqual([1, 3]);
  });

  it("keeps serving this tab when storage refuses to write", () => {
    const full = memoryStorage();
    full.setItem = () => {
      throw new DOMException("quota", "QuotaExceededError");
    };
    full.removeItem = () => {
      throw new DOMException("blocked", "SecurityError");
    };
    const store = new ProgressStore(full);

    store.put(row(1535, 1));
    expect(store.of(1535)).toEqual([row(1535, 1)]);
    store.clear();
    expect(store.of(1535)).toEqual([]);
  });

  it("picks up positions another tab wrote", () => {
    const store = new ProgressStore(window.localStorage);
    expect(store.of(1535)).toEqual([]);
    const listener = vi.fn();
    store.subscribe(listener);

    window.localStorage.setItem(KEY, JSON.stringify([row(1535, 8)]));
    window.dispatchEvent(new StorageEvent("storage", { key: KEY, storageArea: window.localStorage }));

    expect(listener).toHaveBeenCalledTimes(1);
    expect(store.of(1535)).toEqual([row(1535, 8)]);
  });

  it("the shared store lives in this browser's localStorage", () => {
    progressStore.put(row(1535, 2));
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? "[]")).toEqual([row(1535, 2)]);
    progressStore.clear();
    expect(window.localStorage.getItem(KEY)).toBeNull();
  });
});
```

`web/src/library/prefs.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { THRESHOLD_CHOICES, setWatchedThreshold, watchedThreshold } from "./prefs";

const KEY = "kaeru.threshold";

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (key: string) => {
      data.delete(key);
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value));
    },
  };
}

function refusing(): Storage {
  const storage = memoryStorage();
  storage.getItem = () => {
    throw new DOMException("blocked", "SecurityError");
  };
  storage.setItem = () => {
    throw new DOMException("blocked", "SecurityError");
  };
  return storage;
}

describe("watched threshold", () => {
  it("offers Android's four choices", () => {
    expect(THRESHOLD_CHOICES).toEqual([0.8, 0.85, 0.9, 0.95]);
  });

  it("is 0.9 until the viewer picks another", () => {
    expect(watchedThreshold(memoryStorage())).toBe(0.9);
  });

  it("keeps the viewer's choice under kaeru.threshold", () => {
    const storage = memoryStorage();
    setWatchedThreshold(0.85, storage);
    expect(storage.getItem(KEY)).toBe("0.85");
    expect(watchedThreshold(storage)).toBe(0.85);
  });

  it("uses this browser's localStorage when no storage is given", () => {
    setWatchedThreshold(0.95);
    expect(window.localStorage.getItem(KEY)).toBe("0.95");
    expect(watchedThreshold()).toBe(0.95);
  });

  it("holds any value inside Android's 0.5–1 range", () => {
    const storage = memoryStorage();
    setWatchedThreshold(0.3, storage);
    expect(watchedThreshold(storage)).toBe(0.5);
    setWatchedThreshold(1.4, storage);
    expect(watchedThreshold(storage)).toBe(1);
    storage.setItem(KEY, "0.2");
    expect(watchedThreshold(storage)).toBe(0.5);
  });

  it("never stores something that is not a number", () => {
    const storage = memoryStorage();
    setWatchedThreshold(0.8, storage);
    setWatchedThreshold(Number.NaN, storage);
    setWatchedThreshold(Number.POSITIVE_INFINITY, storage);
    expect(watchedThreshold(storage)).toBe(0.8);
  });

  it("reads garbage as the default", () => {
    const storage = memoryStorage();
    for (const raw of ["", "  ", "много", "NaN"]) {
      storage.setItem(KEY, raw);
      expect(watchedThreshold(storage)).toBe(0.9);
    }
  });

  it("falls back to the default when storage is blocked", () => {
    const storage = refusing();
    expect(() => setWatchedThreshold(0.8, storage)).not.toThrow();
    expect(watchedThreshold(storage)).toBe(0.9);
  });
});
```

`web/src/library/library.test.ts`:
```ts
// Rules: android/src/main/java/app/kaeru/domain/playback/MarkEpisodeWatched.kt,
// MarkEpisodeUnwatched.kt and data/library/ShikimoriLibraryRepository.kt; contract decisions 6 and 7.
import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import type { authorized } from "../auth/session";
import type { Anime, EpisodeProgress, ListStatus, UserRate } from "../domain/models";
import { DETAILS_PER_LOAD, DETAILS_TTL_MS, Library, useLibrary } from "./library";
import { ProgressStore } from "./progress";

const NOW = Date.parse("2026-09-24T12:00:00Z");
const HOUR = 3_600_000;

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (key: string) => {
      data.delete(key);
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value));
    },
  };
}

function anime(id: number, spec: Partial<Anime> = {}): Anime {
  return {
    id,
    title: `Аниме ${id}`,
    originalTitle: `Anime ${id}`,
    posterUrl: `https://shikimori.io/posters/${id}.jpg`,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year: 2024,
    score: 8.1,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
    ...spec,
  };
}

function ongoing(id: number, spec: Partial<Anime> = {}): Anime {
  return anime(id, { status: "ongoing", episodes: 24, episodesAired: 7, ...spec });
}

function rate(animeId: number, status: ListStatus, episodes: number): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt: 1 };
}

function stopped(animeId: number, episode: number): EpisodeProgress {
  return { animeId, episode, positionMs: 300_000, durationMs: 1_440_000, updatedAt: 1 };
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve: () => void = () => undefined;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** Lets every queued promise callback run. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function fieldsText(fields: { status?: ListStatus; episodes?: number }): string {
  return Object.entries(fields)
    .map(([name, value]) => `${name}=${String(value)}`)
    .join(" ");
}

/** Shikimori as far as the library sees it: rates, cards, details and the two writes. */
class FakeShikimori {
  rates: UserRate[] = [];
  cards: Anime[] = [];
  readonly fullCards = new Map<number, Anime>();
  readonly calls: string[] = [];
  /** Titles whose details fail; each entry is used up by one request. */
  detailsFailures: number[] = [];
  /** A failure for the list read; used up by one load. */
  ratesFailure: unknown = null;
  /** The list read waits for this when set. */
  ratesHold: Promise<void> | null = null;
  /** Details requests wait for this when set. */
  detailsHold: Promise<void> | null = null;
  /** One entry per write, in order; a write waits for its entry before it answers. */
  holds: Array<Promise<void> | null> = [];
  /** One entry per write, in order; a truthy entry is thrown by that write. */
  failures: unknown[] = [];
  private nextId = 900;

  writes(): string[] {
    return this.calls.filter((call) => call.startsWith("POST") || call.startsWith("PATCH"));
  }

  detailsCalls(): number[] {
    return this.calls.filter((call) => call.startsWith("details")).map((call) => Number(call.split(" ")[1]));
  }

  api(): Shikimori {
    const unused = async (): Promise<never> => {
      throw new Error("not used by the library");
    };
    return {
      whoami: unused,
      search: unused,
      popularNow: unused,
      popularInSeason: unused,
      userRates: async (userId, token) => {
        this.calls.push(`rates ${userId} ${token}`);
        if (this.ratesHold) await this.ratesHold;
        const failure = this.ratesFailure;
        this.ratesFailure = null;
        if (failure) throw failure;
        return this.rates.map((r) => ({ ...r }));
      },
      byIds: async (ids) => {
        this.calls.push(`cards ${ids.join(",")}`);
        return this.cards.filter((card) => ids.includes(card.id));
      },
      details: async (id) => {
        this.calls.push(`details ${id}`);
        if (this.detailsHold) await this.detailsHold;
        const failing = this.detailsFailures.indexOf(id);
        if (failing >= 0) {
          this.detailsFailures.splice(failing, 1);
          throw new NetworkError("Failed to fetch");
        }
        const card = this.fullCards.get(id) ?? this.cards.find((c) => c.id === id);
        if (!card) throw new ApiError(404);
        return card;
      },
      createRate: async (token, userId, animeId, fields) => {
        this.calls.push(`POST ${userId}/${animeId} ${fieldsText(fields)} ${token}`);
        await this.answer();
        const created: UserRate = {
          id: this.nextId++,
          animeId,
          status: fields.status,
          episodes: fields.episodes ?? 0,
          updatedAt: 5,
        };
        this.rates.push(created);
        return { ...created };
      },
      updateRate: async (token, rateId, fields) => {
        this.calls.push(`PATCH ${rateId} ${fieldsText(fields)} ${token}`);
        await this.answer();
        const index = this.rates.findIndex((r) => r.id === rateId);
        const current = this.rates[index];
        if (!current) throw new ApiError(404);
        const updated: UserRate = { ...current, ...fields, updatedAt: 5 };
        this.rates[index] = updated;
        return { ...updated };
      },
    };
  }

  private async answer(): Promise<void> {
    const hold = this.holds.shift();
    if (hold) await hold;
    const failure = this.failures.shift();
    if (failure) throw failure;
  }
}

const signedIn: typeof authorized = (call) => call("tok");

function setup(options: { account?: () => number | null } = {}) {
  const server = new FakeShikimori();
  const progress = new ProgressStore(memoryStorage());
  let clock = NOW;
  const library = new Library({
    shikimori: server.api(),
    authorized: signedIn,
    accountId: options.account ?? (() => 42),
    progress,
    now: () => clock,
  });
  return {
    server,
    progress,
    library,
    later(ms: number) {
      clock += ms;
    },
  };
}

/** A loaded library holding one title with the given rate. */
async function holding(title: Anime, status: ListStatus, episodes: number) {
  const s = setup();
  s.server.cards = [title];
  s.server.rates = [rate(title.id, status, episodes)];
  await s.library.load();
  s.server.calls.length = 0;
  return s;
}

describe("Library.load", () => {
  it("starts idle, then joins the account's rates with their cards", async () => {
    const { server, library } = setup();
    server.cards = [anime(1), anime(2)];
    server.rates = [rate(1, "watching", 3), rate(2, "planned", 0)];
    expect(library.state()).toEqual({ kind: "idle" });

    const loading = library.load();
    expect(library.state()).toEqual({ kind: "loading", entries: null });
    await loading;

    expect(server.calls).toEqual(["rates 42 tok", "cards 1,2"]);
    expect(library.state()).toEqual({
      kind: "ready",
      entries: [
        { anime: anime(1), rate: rate(1, "watching", 3) },
        { anime: anime(2), rate: rate(2, "planned", 0) },
      ],
    });
    expect(library.entry(2)?.rate.status).toBe("planned");
    expect(library.entry(3)).toBeUndefined();
  });

  it("is ready and empty for an empty list, without asking for cards", async () => {
    const { server, library } = setup();
    await library.load();
    expect(server.calls).toEqual(["rates 42 tok"]);
    expect(library.state()).toEqual({ kind: "ready", entries: [] });
  });

  it("skips a rate whose card Shikimori did not return", async () => {
    const { server, library } = setup();
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3), rate(2, "watching", 1)];
    await library.load();
    expect(library.entry(2)).toBeUndefined();
    expect(library.state().kind).toBe("ready");
  });

  it("stays idle and asks nothing while signed out", async () => {
    const { server, library } = setup({ account: () => null });
    await library.load();
    expect(library.state()).toEqual({ kind: "idle" });
    expect(server.calls).toEqual([]);
  });

  it("shares one read between callers that ask at once", async () => {
    const { server, library } = setup();
    await Promise.all([library.load(), library.load()]);
    expect(server.calls).toEqual(["rates 42 tok"]);
  });

  it("explains a failed first read and keeps the list a failed reread had", async () => {
    const { server, library } = setup();
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3)];
    server.ratesFailure = new NetworkError("offline");
    await library.load();
    expect(library.state()).toEqual({ kind: "error", message: "Нет соединения. Проверьте интернет", entries: null });

    await library.load();
    expect(library.state().kind).toBe("ready");

    server.ratesFailure = new ApiError(503);
    const again = library.load();
    expect(library.state()).toEqual({
      kind: "loading",
      entries: [{ anime: anime(1), rate: rate(1, "watching", 3) }],
    });
    await again;
    expect(library.state()).toEqual({
      kind: "error",
      message: "Shikimori недоступен, попробуйте позже",
      entries: [{ anime: anime(1), rate: rate(1, "watching", 3) }],
    });
  });

  it("never shows one account's list under another", async () => {
    let account: number | null = 42;
    const { server, library } = setup({ account: () => account });
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3)];
    await library.load();

    account = 7;
    server.rates = [];
    const loading = library.load();
    expect(library.state()).toEqual({ kind: "loading", entries: null });
    expect(library.entry(1)).toBeUndefined();
    await loading;
    expect(server.calls).toContain("rates 7 tok");
    expect(library.state()).toEqual({ kind: "ready", entries: [] });

    account = null;
    await library.load();
    expect(library.state()).toEqual({ kind: "idle" });
  });

  it("keeps this tab's value for a title written to while the list was in flight", async () => {
    const { server, library } = await holding(anime(1), "watching", 3);
    const gate = deferred();
    server.ratesHold = gate.promise;

    const reading = library.load();
    await settle();
    await library.markWatched(anime(1), 5);
    // The list in flight was read before the mark landed.
    server.rates = [rate(1, "watching", 3)];
    gate.resolve();
    await reading;

    expect(library.entry(1)?.rate.episodes).toBe(5);
  });
});

describe("details for ongoing titles being watched", () => {
  it("fetches details only for watching and rewatching titles still airing, and merges them", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1), ongoing(2), ongoing(3), anime(4), ongoing(5)];
    server.rates = [
      rate(1, "watching", 3),
      rate(2, "rewatching", 1),
      rate(3, "planned", 0),
      rate(4, "watching", 2),
      rate(5, "completed", 24),
    ];
    const airs = NOW + 26 * HOUR;
    server.fullCards.set(1, ongoing(1, {
      nextEpisodeAt: airs,
      studios: ["Madhouse"],
      description: "Эльфийка-маг переживает своих спутников.",
      backdropUrl: "https://shikimori.io/system/screenshots/original/1.jpg",
    }));

    await library.load();

    expect(server.detailsCalls()).toEqual([1, 2]);
    expect(library.entry(1)?.anime).toEqual(ongoing(1, {
      nextEpisodeAt: airs,
      studios: ["Madhouse"],
      description: "Эльфийка-маг переживает своих спутников.",
      backdropUrl: "https://shikimori.io/system/screenshots/original/1.jpg",
    }));
    expect(library.entry(1)?.rate).toEqual(rate(1, "watching", 3));
  });

  it("is ready with the list before the details arrive", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR }));
    const seen: Array<number | null | undefined> = [];
    library.subscribe(() => {
      if (library.state().kind === "ready") seen.push(library.entry(1)?.anime.nextEpisodeAt);
    });

    await library.load();

    expect(seen[0]).toBeNull();
    expect(seen.at(-1)).toBe(NOW + HOUR);
  });

  it("keeps details for six hours, across rereads of the list", async () => {
    const { server, library, later } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR, studios: ["MAPPA"] }));
    await library.load();

    later(DETAILS_TTL_MS - 1);
    server.cards = [ongoing(1, { episodesAired: 8 })];
    await library.load();
    expect(server.detailsCalls()).toEqual([1]);
    expect(library.entry(1)?.anime.episodesAired).toBe(8);
    expect(library.entry(1)?.anime.studios).toEqual(["MAPPA"]);
    expect(library.entry(1)?.anime.nextEpisodeAt).toBe(NOW + HOUR);

    later(1);
    await library.load();
    expect(server.detailsCalls()).toEqual([1, 1]);
  });

  it(`fetches at most ${DETAILS_PER_LOAD} per load: never fetched first, then the oldest`, async () => {
    const { server, library, later } = setup();
    const first = Array.from({ length: 25 }, (_, i) => i + 1);
    server.cards = first.map((id) => ongoing(id));
    server.rates = first.map((id) => rate(id, "watching", 1));
    await library.load();
    expect(server.detailsCalls()).toEqual(first);

    // Title 26 joins at the top of the list and gets its details an hour later.
    later(HOUR);
    server.cards = [ongoing(26), ...server.cards];
    server.rates = [rate(26, "watching", 1), ...server.rates];
    server.calls.length = 0;
    await library.load();
    expect(server.detailsCalls()).toEqual([26]);

    // Everything is stale now; title 27 is new. 26 was fetched last, so it waits for the next load.
    later(DETAILS_TTL_MS);
    server.cards = [...server.cards, ongoing(27)];
    server.rates = [...server.rates, rate(27, "watching", 1)];
    server.calls.length = 0;
    await library.load();
    expect(server.detailsCalls()).toEqual([27, ...first.slice(0, 24)]);
  });

  it("ignores a failed details request and asks again on the next load", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1), ongoing(2)];
    server.rates = [rate(1, "watching", 3), rate(2, "watching", 1)];
    server.fullCards.set(2, ongoing(2, { nextEpisodeAt: NOW + HOUR }));
    server.detailsFailures = [1];

    await library.load();

    expect(library.state().kind).toBe("ready");
    expect(library.entry(1)?.anime).toEqual(ongoing(1));
    expect(library.entry(2)?.anime.nextEpisodeAt).toBe(NOW + HOUR);

    await library.load();
    expect(server.detailsCalls()).toEqual([1, 2, 1]);
  });

  it("stops asking for details once another account signs in", async () => {
    let account: number | null = 42;
    const { server, library } = setup({ account: () => account });
    server.cards = [ongoing(1), ongoing(2)];
    server.rates = [rate(1, "watching", 3), rate(2, "watching", 1)];
    const gate = deferred();
    server.detailsHold = gate.promise;

    const loading = library.load();
    await settle();
    account = 7;
    gate.resolve();
    await loading;

    expect(server.detailsCalls()).toEqual([1]);
  });

  it("keeps a rate written while its details were in flight", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR }));
    const gate = deferred();
    server.detailsHold = gate.promise;

    const loading = library.load();
    await settle();
    expect(library.state().kind).toBe("ready");
    await library.markWatched(ongoing(1), 4);
    gate.resolve();
    await loading;

    expect(library.entry(1)?.rate.episodes).toBe(4);
    expect(library.entry(1)?.anime.nextEpisodeAt).toBe(NOW + HOUR);
  });

  it("keeps details that arrived while a write was in flight", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR, studios: ["MAPPA"] }));
    const details = deferred();
    const write = deferred();
    server.detailsHold = details.promise;
    server.holds = [write.promise];

    const loading = library.load();
    await settle();
    const marking = library.markWatched(ongoing(1), 4);
    await settle();
    details.resolve();
    await loading;
    write.resolve();
    await marking;

    expect(library.entry(1)?.rate.episodes).toBe(4);
    expect(library.entry(1)?.anime.studios).toEqual(["MAPPA"]);
    expect(library.entry(1)?.anime.nextEpisodeAt).toBe(NOW + HOUR);
  });
});

describe("Library.setStatus", () => {
  it("adds a title that is in no list with a POST of the status alone", async () => {
    const s = setup();
    await s.library.load();
    const title = anime(9);

    const adding = s.library.setStatus(title, "planned");
    expect(s.library.entry(9)?.rate.status).toBe("planned");
    await adding;

    expect(s.server.writes()).toEqual(["POST 42/9 status=planned tok"]);
    expect(s.library.entry(9)).toEqual({
      anime: title,
      rate: { id: 900, animeId: 9, status: "planned", episodes: 0, updatedAt: NOW },
    });
    expect(s.library.state()).toEqual({ kind: "ready", entries: [s.library.entry(9)] });
  });

  it("PATCHes only the status and keeps the count", async () => {
    const { server, library } = await holding(anime(1), "watching", 7);
    await library.setStatus(anime(1), "on_hold");
    expect(server.writes()).toEqual(["PATCH 101 status=on_hold tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "on_hold", episodes: 7 });
  });

  it("sends nothing when the status is already that", async () => {
    const { server, library } = await holding(anime(1), "watching", 7);
    await library.setStatus(anime(1), "watching");
    expect(server.calls).toEqual([]);
  });

  it("puts the old status back and rethrows when Shikimori refuses", async () => {
    const { server, library } = await holding(anime(1), "watching", 7);
    server.failures = [new ApiError(500)];

    const changing = library.setStatus(anime(1), "dropped");
    expect(library.entry(1)?.rate.status).toBe("dropped");
    await expect(changing).rejects.toMatchObject({ status: 500 });

    expect(library.entry(1)?.rate).toEqual(rate(1, "watching", 7));
  });

  it("refuses to write for nobody", async () => {
    const s = setup({ account: () => null });
    await expect(s.library.setStatus(anime(9), "planned")).rejects.toBeInstanceOf(ApiError);
    expect(s.library.entry(9)).toBeUndefined();
    expect(s.server.writes()).toEqual([]);
  });
});

describe("Library.markWatched", () => {
  it("adds a title that is in no list as watching with the count, in one POST", async () => {
    const s = setup();
    await s.library.load();
    await expect(s.library.markWatched(anime(9), 3)).resolves.toEqual({ suggestCompleted: false });
    expect(s.server.writes()).toEqual(["POST 42/9 status=watching episodes=3 tok"]);
    expect(s.library.entry(9)?.rate).toMatchObject({ id: 900, status: "watching", episodes: 3 });
  });

  it.each<ListStatus>(["planned", "on_hold"])("moves a %s title to watching, then raises the count", async (status) => {
    const { server, library } = await holding(anime(1), status, 2);
    await library.markWatched(anime(1), 4);
    expect(server.writes()).toEqual(["PATCH 101 status=watching tok", "PATCH 101 episodes=4 tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "watching", episodes: 4 });
  });

  it("moves a planned title to watching even when the count already covers the episode", async () => {
    const { server, library } = await holding(anime(1), "planned", 5);
    await library.markWatched(anime(1), 4);
    expect(server.writes()).toEqual(["PATCH 101 status=watching tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "watching", episodes: 5 });
  });

  it.each<ListStatus>(["watching", "rewatching", "completed", "dropped"])(
    "leaves %s as it is and sends only the count",
    async (status) => {
      const { server, library } = await holding(anime(1), status, 2);
      await library.markWatched(anime(1), 4);
      expect(server.writes()).toEqual(["PATCH 101 episodes=4 tok"]);
      expect(library.entry(1)?.rate).toMatchObject({ status, episodes: 4 });
    },
  );

  it("never lowers the count", async () => {
    const { server, library } = await holding(anime(1), "watching", 6);
    const marking = library.markWatched(anime(1), 4);
    expect(library.entry(1)?.rate.episodes).toBe(6);
    await expect(marking).resolves.toEqual({ suggestCompleted: false });
    expect(server.writes()).toEqual([]);
    expect(library.entry(1)?.rate.episodes).toBe(6);
  });

  it("clamps the count to the announced length, and not when the length is unknown", async () => {
    const clamped = await holding(anime(1, { episodes: 12 }), "watching", 10);
    await clamped.library.markWatched(anime(1, { episodes: 12 }), 13);
    expect(clamped.server.writes()).toEqual(["PATCH 101 episodes=12 tok"]);

    const open = await holding(ongoing(2, { episodes: 0, episodesAired: 30 }), "watching", 10);
    await open.library.markWatched(ongoing(2, { episodes: 0, episodesAired: 30 }), 30);
    expect(open.server.writes()).toEqual(["PATCH 102 episodes=30 tok"]);
  });

  it("suggests completing only when the last announced episode is newly counted", async () => {
    const twelve = anime(1, { episodes: 12 });
    const finale = await holding(twelve, "watching", 11);
    expect(await finale.library.markWatched(twelve, 12)).toEqual({ suggestCompleted: true });

    const again = await holding(twelve, "watching", 12);
    expect(await again.library.markWatched(twelve, 12)).toEqual({ suggestCompleted: false });

    const open = ongoing(2, { episodes: 0 });
    const unknown = await holding(open, "watching", 11);
    expect(await unknown.library.markWatched(open, 12)).toEqual({ suggestCompleted: false });

    const film = anime(3, { episodes: 1 });
    const fresh = setup();
    await fresh.library.load();
    expect(await fresh.library.markWatched(film, 1)).toEqual({ suggestCompleted: true });
  });

  it("shows the new count before Shikimori answers", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    const gate = deferred();
    server.holds = [gate.promise];

    const marking = library.markWatched(anime(1), 5);
    expect(library.entry(1)?.rate.episodes).toBe(5);
    gate.resolve();
    await marking;
    expect(library.entry(1)?.rate.episodes).toBe(5);
  });

  it("puts the old count back and rethrows when Shikimori refuses", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    server.failures = [new ApiError(422)];
    await expect(library.markWatched(anime(1), 5)).rejects.toMatchObject({ status: 422 });
    expect(library.entry(1)?.rate).toEqual(rate(1, "watching", 2));
  });

  it("keeps the status that landed when only the count is refused", async () => {
    const { server, library } = await holding(anime(1), "planned", 0);
    server.failures = [null, new NetworkError("offline")];
    await expect(library.markWatched(anime(1), 1)).rejects.toBeInstanceOf(NetworkError);
    expect(library.entry(1)?.rate).toMatchObject({ status: "watching", episodes: 0 });
  });
});

describe("Library.markUnwatched", () => {
  it("lowers the count to the episode before, keeps the status and forgets later positions", async () => {
    const { server, library, progress } = await holding(anime(1), "completed", 7);
    for (const episode of [4, 5, 6]) progress.put(stopped(1, episode));

    await library.markUnwatched(anime(1), 5);

    expect(server.writes()).toEqual(["PATCH 101 episodes=4 tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "completed", episodes: 4 });
    expect(progress.of(1).map((p) => p.episode)).toEqual([4]);
  });

  it("does nothing for an episode that is not counted, or episode 0", async () => {
    const { server, library, progress } = await holding(anime(1), "watching", 3);
    progress.put(stopped(1, 4));

    const undoLater = await library.markUnwatched(anime(1), 4);
    const undoZero = await library.markUnwatched(anime(1), 0);
    await undoLater();
    await undoZero();

    expect(server.writes()).toEqual([]);
    expect(library.entry(1)?.rate.episodes).toBe(3);
    expect(progress.of(1)).toEqual([stopped(1, 4)]);
  });

  it("keeps the count and the positions when Shikimori refuses", async () => {
    const { server, library, progress } = await holding(anime(1), "watching", 7);
    progress.put(stopped(1, 6));
    server.failures = [new ApiError(500)];

    const unmarking = library.markUnwatched(anime(1), 5);
    expect(library.entry(1)?.rate.episodes).toBe(4);
    await expect(unmarking).rejects.toMatchObject({ status: 500 });

    expect(library.entry(1)?.rate.episodes).toBe(7);
    expect(progress.of(1)).toEqual([stopped(1, 6)]);
  });

  it("undo restores the previous count, not the tapped episode, and the forgotten positions", async () => {
    const { server, library, progress } = await holding(anime(1), "watching", 7);
    progress.put(stopped(1, 5));
    progress.put(stopped(1, 6));

    const undo = await library.markUnwatched(anime(1), 5);
    expect(library.entry(1)?.rate.episodes).toBe(4);
    expect(progress.of(1)).toEqual([]);

    await undo();

    expect(server.writes()).toEqual(["PATCH 101 episodes=4 tok", "PATCH 101 episodes=7 tok"]);
    expect(library.entry(1)?.rate.episodes).toBe(7);
    expect(progress.of(1)).toEqual([stopped(1, 5), stopped(1, 6)]);
  });
});

describe("write order", () => {
  it("sends a title's writes one after another, in the order they were made", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    const first = deferred();
    server.holds = [first.promise];

    const slow = library.setStatus(anime(1), "on_hold");
    const quick = library.setStatus(anime(1), "dropped");
    await settle();
    expect(server.writes()).toEqual(["PATCH 101 status=on_hold tok"]);
    expect(library.entry(1)?.rate.status).toBe("dropped");

    first.resolve();
    await Promise.all([slow, quick]);
    expect(server.writes()).toEqual(["PATCH 101 status=on_hold tok", "PATCH 101 status=dropped tok"]);
    expect(library.entry(1)?.rate.status).toBe("dropped");
    expect(server.rates[0]?.status).toBe("dropped");
  });

  it("does not hold one title's write behind another title's", async () => {
    const s = setup();
    s.server.cards = [anime(1), anime(2)];
    s.server.rates = [rate(1, "watching", 2), rate(2, "watching", 2)];
    await s.library.load();
    const gate = deferred();
    s.server.holds = [gate.promise];

    const slow = s.library.markWatched(anime(1), 3);
    await s.library.markWatched(anime(2), 3);
    expect(s.library.entry(2)?.rate.episodes).toBe(3);

    gate.resolve();
    await slow;
    expect(s.server.writes()).toEqual(["PATCH 101 episodes=3 tok", "PATCH 102 episodes=3 tok"]);
  });

  it("goes on with the next write after a refused one", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    server.failures = [new ApiError(500)];

    const refused = library.markWatched(anime(1), 3);
    const next = library.markWatched(anime(1), 4);
    await expect(refused).rejects.toBeInstanceOf(ApiError);
    await next;

    expect(server.writes()).toEqual(["PATCH 101 episodes=3 tok", "PATCH 101 episodes=4 tok"]);
    expect(library.entry(1)?.rate.episodes).toBe(4);
  });
});

describe("useLibrary", () => {
  it("re-renders with each state the library passes through", async () => {
    const { server, library } = setup();
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3)];
    const { result } = renderHook(() => useLibrary(library));
    expect(result.current).toEqual({ kind: "idle" });

    await act(async () => {
      await library.load();
    });
    expect(result.current).toEqual({ kind: "ready", entries: [{ anime: anime(1), rate: rate(1, "watching", 3) }] });

    await act(async () => {
      await library.markWatched(anime(1), 4);
    });
    expect(result.current.kind === "ready" && result.current.entries[0]?.rate.episodes).toBe(4);
  });
});
```

- [ ] **Step 2: Run them to see them fail**

From `web/`:
```
npx vitest run src/library
```
**Expected:** all three suites FAIL before any test runs, and the summary reads `Test Files  3 failed (3)` and `Tests  no tests`. The errors are:
- `Failed to resolve import "./progress" from "src/library/progress.test.ts". Does the file exist?`
- `Failed to resolve import "./prefs" from "src/library/prefs.test.ts". Does the file exist?`
- `Failed to resolve import "./library" from "src/library/library.test.ts". Does the file exist?`

- [ ] **Step 3: Implement**

`web/src/library/progress.ts`:
```ts
import type { EpisodeProgress } from "../domain/models";

/** One JSON array of every position this browser holds, across titles. */
const KEY = "kaeru.progress";
const FIELDS = ["animeId", "episode", "positionMs", "durationMs", "updatedAt"] as const;

function rowKey(animeId: number, episode: number): string {
  return `${animeId}:${episode}`;
}

function isRow(value: unknown): value is EpisodeProgress {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return FIELDS.every((field) => {
    const item = record[field];
    return typeof item === "number" && Number.isFinite(item);
  });
}

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    // Blocked site data makes the getter itself throw.
    return null;
  }
}

/**
 * Where the viewer stopped inside each episode. Per browser only, as positions are per device on
 * Android and iOS; Shikimori holds just the watched count.
 */
export class ProgressStore {
  private readonly storage: Storage | null;
  private rows: Map<string, EpisodeProgress> | null = null;
  private readonly byAnime = new Map<number, EpisodeProgress[]>();
  private readonly listeners = new Set<() => void>();

  constructor(storage?: Storage) {
    this.storage = storage ?? browserStorage();
    if (typeof window !== "undefined") window.addEventListener("storage", this.onStorage);
  }

  of(animeId: number): EpisodeProgress[] {
    const cached = this.byAnime.get(animeId);
    if (cached) return cached;
    const rows = [...this.all().values()]
      .filter((row) => row.animeId === animeId)
      .sort((a, b) => a.episode - b.episode);
    // The same array until this title changes, so useSyncExternalStore can compare snapshots.
    this.byAnime.set(animeId, rows);
    return rows;
  }

  put(p: EpisodeProgress): void {
    this.all().set(rowKey(p.animeId, p.episode), { ...p });
    this.commit([p.animeId]);
  }

  removeFrom(animeId: number, episode: number): EpisodeProgress[] {
    const removed = this.of(animeId).filter((row) => row.episode >= episode);
    if (removed.length === 0) return [];
    const rows = this.all();
    for (const row of removed) rows.delete(rowKey(row.animeId, row.episode));
    this.commit([animeId]);
    return removed;
  }

  restore(rows: readonly EpisodeProgress[]): void {
    if (rows.length === 0) return;
    const all = this.all();
    for (const row of rows) all.set(rowKey(row.animeId, row.episode), { ...row });
    this.commit(rows.map((row) => row.animeId));
  }

  /** Forgets every position in this browser: sign-out clears the local cache. */
  clear(): void {
    this.rows = new Map();
    this.byAnime.clear();
    try {
      this.storage?.removeItem(KEY);
    } catch {
      // Blocked storage: this tab already reads as empty.
    }
    this.emit();
  }

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  private all(): Map<string, EpisodeProgress> {
    if (this.rows === null) this.rows = this.read();
    return this.rows;
  }

  private read(): Map<string, EpisodeProgress> {
    const rows = new Map<string, EpisodeProgress>();
    try {
      const raw = this.storage?.getItem(KEY);
      if (!raw) return rows;
      const parsed: unknown = JSON.parse(raw);
      if (!Array.isArray(parsed)) return rows;
      for (const item of parsed) {
        if (isRow(item)) rows.set(rowKey(item.animeId, item.episode), item);
      }
    } catch {
      // A store some other build broke reads as empty rather than taking the page down.
    }
    return rows;
  }

  private commit(animeIds: Iterable<number>): void {
    for (const id of animeIds) this.byAnime.delete(id);
    try {
      this.storage?.setItem(KEY, JSON.stringify([...this.all().values()]));
    } catch {
      // Quota or blocked storage: the rows still serve this tab.
    }
    this.emit();
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }

  // Another tab wrote positions: drop the parsed copy and read again on next use.
  private readonly onStorage = (event: StorageEvent): void => {
    if (event.storageArea !== this.storage) return;
    if (event.key !== null && event.key !== KEY) return;
    this.rows = null;
    this.byAnime.clear();
    this.emit();
  };
}

export const progressStore = new ProgressStore();
```

`web/src/library/prefs.ts`:
```ts
import { DEFAULT_THRESHOLD } from "../domain/progress";

/** Shares of an episode the settings screen offers as «watched» (Android settings). */
export const THRESHOLD_CHOICES: readonly number[] = [0.8, 0.85, 0.9, 0.95];

const KEY = "kaeru.threshold";
// Android WATCHED_THRESHOLD_RANGE: anything outside is not a share of an episode.
const LOWEST = 0.5;
const HIGHEST = 1;

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function clamp(value: number): number {
  return Math.min(HIGHEST, Math.max(LOWEST, value));
}

export function watchedThreshold(storage?: Storage): number {
  try {
    const raw = (storage ?? browserStorage())?.getItem(KEY);
    if (raw === null || raw === undefined || raw.trim() === "") return DEFAULT_THRESHOLD;
    const value = Number(raw);
    return Number.isFinite(value) ? clamp(value) : DEFAULT_THRESHOLD;
  } catch {
    return DEFAULT_THRESHOLD;
  }
}

export function setWatchedThreshold(value: number, storage?: Storage): void {
  // NaN would make every later comparison false and silently stop marking anything watched.
  if (!Number.isFinite(value)) return;
  try {
    (storage ?? browserStorage())?.setItem(KEY, String(clamp(value)));
  } catch {
    // Storage refused: the default keeps applying, which is safe.
  }
}
```

`web/src/library/library.ts`:
```ts
import { useSyncExternalStore } from "react";
import { ApiError, errorMessage } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import type { authorized } from "../auth/session";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus, UserRate } from "../domain/models";
import type { ProgressStore } from "./progress";

export type LibraryState =
  | { kind: "idle" }
  | { kind: "loading"; entries: LibraryEntry[] | null }
  | { kind: "ready"; entries: LibraryEntry[] }
  | { kind: "error"; message: string; entries: LibraryEntry[] | null };

export interface LibraryDeps {
  shikimori: Shikimori;
  authorized: typeof authorized;
  accountId: () => number | null;
  progress: ProgressStore;
  /** Clock for a rate's updatedAt and for the details TTL; tests pin it. */
  now?: () => number;
}

/** At most this many titles get their details per load (Android DETAILS_PER_REFRESH). */
export const DETAILS_PER_LOAD = 25;
/** Fetched details stay fresh this long (Android detailsTtl). */
export const DETAILS_TTL_MS = 6 * 60 * 60 * 1000;

/** What one queued write does to the entry on screen before Shikimori answers. */
type Optimistic = (entry: LibraryEntry | undefined) => LibraryEntry | undefined;

interface Sent<T> {
  /** The rate Shikimori now holds; undefined when the write changed nothing. */
  entry: LibraryEntry | undefined;
  result: T;
}

const NOTHING_TO_UNDO = async (): Promise<void> => {};

function entriesOf(state: LibraryState): LibraryEntry[] | null {
  return state.kind === "idle" ? null : state.entries;
}

// Android AnimeEntity.mergeShort: a list card refreshes what it carries; details it lacks stay.
function mergeShort(known: Anime, card: Anime): Anime {
  return {
    ...known,
    title: card.title,
    originalTitle: card.originalTitle,
    posterUrl: card.posterUrl ?? known.posterUrl,
    status: card.status,
    episodes: card.episodes,
    episodesAired: card.episodesAired,
    score: card.score ?? known.score,
    year: card.year ?? known.year,
    kind: card.kind ?? known.kind,
  };
}

// Details are the fuller card: air date, studios, description, screenshot. Artwork they lack stays.
function withDetails(known: Anime, details: Anime): Anime {
  return {
    ...details,
    posterUrl: details.posterUrl ?? known.posterUrl,
    backdropUrl: details.backdropUrl ?? known.backdropUrl,
  };
}

// Decision 6: a count never runs past what the catalogue announced.
function countFor(anime: Anime, episode: number): number {
  return anime.episodes > 0 ? Math.min(episode, anime.episodes) : episode;
}

// Watching it is the fact on the ground; planned or shelved is what disagrees (MarkEpisodeWatched.kt).
function picksUp(rate: UserRate): boolean {
  return rate.status === "planned" || rate.status === "on_hold";
}

function completes(anime: Anime, counted: number): boolean {
  return anime.episodes > 0 && counted >= anime.episodes;
}

// Only titles still airing that the viewer follows need the next air date (ShikimoriLibraryRepository.kt).
function wantsDetails(entry: LibraryEntry): boolean {
  const following = entry.rate.status === "watching" || entry.rate.status === "rewatching";
  return following && entry.anime.status === "ongoing";
}

export class Library {
  private readonly deps: LibraryDeps;
  private readonly now: () => number;
  private readonly listeners = new Set<() => void>();
  /** What Shikimori confirmed, per anime id. */
  private confirmed = new Map<number, LibraryEntry>();
  /** Writes not yet answered, per anime id, applied over `confirmed` in order. */
  private readonly pending = new Map<number, Optimistic[]>();
  private readonly tails = new Map<number, Promise<void>>();
  private readonly writeCounts = new Map<number, number>();
  /** Details fetched this session and when; catalogue data, so it is not tied to an account. */
  private readonly detailed = new Map<number, { at: number; anime: Anime }>();
  private view = new Map<number, LibraryEntry>();
  private current: LibraryState = { kind: "idle" };
  private loadedFor: number | null = null;
  private inflight: Promise<void> | null = null;

  constructor(deps: LibraryDeps) {
    this.deps = deps;
    this.now = deps.now ?? Date.now;
  }

  readonly state = (): LibraryState => this.current;

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  entry(animeId: number): LibraryEntry | undefined {
    return this.view.get(animeId);
  }

  /**
   * Reads the list, then refreshes details for the ongoing titles being watched. The state turns
   * ready as soon as the list is in; the returned promise settles once the details pass is done.
   */
  load(): Promise<void> {
    if (this.inflight) return this.inflight;
    const run = this.refresh().finally(() => {
      this.inflight = null;
    });
    this.inflight = run;
    return run;
  }

  setStatus(anime: Anime, status: ListStatus): Promise<void> {
    const shown = this.view.get(anime.id);
    if (shown && shown.rate.status === status && !this.pending.has(anime.id)) return Promise.resolve();
    const at = this.now();
    return this.write<undefined>(
      anime.id,
      (entry) => {
        if (!entry) return { anime, rate: { id: -anime.id, animeId: anime.id, status, episodes: 0, updatedAt: at } };
        if (entry.rate.status === status) return entry;
        return { anime: entry.anime, rate: { ...entry.rate, status, updatedAt: at } };
      },
      async (confirmed) => {
        if (confirmed && confirmed.rate.status === status) return { entry: undefined, result: undefined };
        if (!confirmed) {
          const userId = this.account();
          const created = await this.deps.authorized((token) =>
            this.deps.shikimori.createRate(token, userId, anime.id, { status }),
          );
          return { entry: { anime, rate: this.statusFrom(created, anime.id, status) }, result: undefined };
        }
        const patched = await this.patch(confirmed.rate.id, { status });
        return { entry: { anime: confirmed.anime, rate: this.statusFrom(patched, anime.id, status) }, result: undefined };
      },
    );
  }

  markWatched(anime: Anime, episode: number): Promise<{ suggestCompleted: boolean }> {
    const target = countFor(anime, episode);
    if (target < 1) return Promise.resolve({ suggestCompleted: false });
    const at = this.now();
    return this.write<{ suggestCompleted: boolean }>(
      anime.id,
      (entry) => {
        if (!entry) {
          return { anime, rate: { id: -anime.id, animeId: anime.id, status: "watching", episodes: target, updatedAt: at } };
        }
        const status: ListStatus = picksUp(entry.rate) ? "watching" : entry.rate.status;
        const episodes = Math.max(entry.rate.episodes, target);
        if (status === entry.rate.status && episodes === entry.rate.episodes) return entry;
        return { anime: entry.anime, rate: { ...entry.rate, status, episodes, updatedAt: at } };
      },
      async (confirmed) => {
        if (!confirmed) {
          // No rate to PATCH yet: one POST carries both the status and the count.
          const userId = this.account();
          const created = await this.deps.authorized((token) =>
            this.deps.shikimori.createRate(token, userId, anime.id, { status: "watching", episodes: target }),
          );
          return {
            entry: { anime, rate: this.statusFrom(created, anime.id, "watching") },
            result: { suggestCompleted: completes(anime, target) },
          };
        }
        let entry = confirmed;
        if (picksUp(entry.rate)) {
          const patched = await this.patch(entry.rate.id, { status: "watching" });
          entry = { anime: entry.anime, rate: this.statusFrom(patched, anime.id, "watching") };
          // Landed even if the count below fails: the title is being watched either way.
          this.confirm(anime.id, entry);
        }
        if (entry.rate.episodes >= target) return { entry, result: { suggestCompleted: false } };
        const patched = await this.patch(entry.rate.id, { episodes: target });
        return {
          entry: { anime: entry.anime, rate: this.countFrom(entry.rate, patched) },
          result: { suggestCompleted: completes(anime, target) },
        };
      },
    );
  }

  markUnwatched(anime: Anime, episode: number): Promise<() => Promise<void>> {
    return this.write<() => Promise<void>>(
      anime.id,
      (entry) =>
        entry && episode >= 1 && entry.rate.episodes >= episode
          ? { anime: entry.anime, rate: { ...entry.rate, episodes: episode - 1 } }
          : entry,
      async (confirmed) => {
        if (!confirmed || episode < 1 || confirmed.rate.episodes < episode) {
          return { entry: undefined, result: NOTHING_TO_UNDO };
        }
        const previousCount = confirmed.rate.episodes;
        const patched = await this.patch(confirmed.rate.id, { episodes: episode - 1 });
        const entry: LibraryEntry = { anime: confirmed.anime, rate: this.countFrom(confirmed.rate, patched) };
        // Only once the count is down: a refused write keeps the viewer's place (MarkEpisodeUnwatched.kt).
        const forgotten = this.deps.progress.removeFrom(anime.id, episode);
        return { entry, result: () => this.restoreCount(anime, previousCount, forgotten) };
      },
    );
  }

  // Undo gives back the count that stood before, not the tapped episode: 6 and 7 went too.
  private restoreCount(anime: Anime, previousCount: number, forgotten: readonly EpisodeProgress[]): Promise<void> {
    return this.write<undefined>(
      anime.id,
      (entry) =>
        entry && entry.rate.episodes < previousCount
          ? { anime: entry.anime, rate: { ...entry.rate, episodes: previousCount } }
          : entry,
      async (confirmed) => {
        let entry: LibraryEntry | undefined;
        if (confirmed && confirmed.rate.episodes < previousCount) {
          const patched = await this.patch(confirmed.rate.id, { episodes: previousCount });
          entry = { anime: confirmed.anime, rate: this.countFrom(confirmed.rate, patched) };
        }
        this.deps.progress.restore(forgotten);
        return { entry, result: undefined };
      },
    );
  }

  private async refresh(): Promise<void> {
    const userId = this.deps.accountId();
    if (userId === null) {
      this.reset();
      return;
    }
    if (userId !== this.loadedFor) {
      // Another account's list must never show under this one.
      this.confirmed = new Map();
      this.loadedFor = userId;
      this.current = { kind: "loading", entries: null };
    } else {
      this.current = { kind: "loading", entries: entriesOf(this.current) };
    }
    this.publish();
    const pendingBefore = new Set(this.pending.keys());
    const countsBefore = new Map(this.writeCounts);
    try {
      const rates = await this.deps.authorized((token) => this.deps.shikimori.userRates(userId, token));
      const ids = [...new Set(rates.map((rate) => rate.animeId))];
      const cards = ids.length > 0 ? await this.deps.shikimori.byIds(ids) : [];
      if (this.deps.accountId() !== userId) {
        this.reset();
        return;
      }
      // A title written to while the list was in flight keeps what this tab confirmed:
      // the list may have been read before that write landed (ShikimoriLibraryRepository.kt).
      const dirty = new Set<number>([...pendingBefore, ...this.pending.keys()]);
      for (const [id, count] of this.writeCounts) {
        if (countsBefore.get(id) !== count) dirty.add(id);
      }
      const cardsById = new Map(cards.map((card) => [card.id, card]));
      const next = new Map<number, LibraryEntry>();
      for (const rate of rates) {
        if (dirty.has(rate.animeId)) continue;
        const card = cardsById.get(rate.animeId);
        if (!card) continue;
        const known = this.confirmed.get(rate.animeId)?.anime ?? this.detailed.get(rate.animeId)?.anime;
        next.set(rate.animeId, { anime: known ? mergeShort(known, card) : card, rate });
      }
      for (const id of dirty) {
        const local = this.confirmed.get(id);
        if (local) next.set(id, local);
      }
      this.confirmed = next;
      this.current = { kind: "ready", entries: [] };
    } catch (error) {
      this.current = { kind: "error", message: errorMessage(error), entries: entriesOf(this.current) };
      this.publish();
      return;
    }
    this.publish();
    await this.refreshDetails(userId);
  }

  // List cards carry no air date, studios or screenshots: «Скоро» and «N серия выйдет завтра»
  // need details. Up to 25 per load, the longest-waiting first, each fresh for 6 h (Android).
  private async refreshDetails(userId: number): Promise<void> {
    const staleBefore = this.now() - DETAILS_TTL_MS;
    const due = [...this.confirmed.values()]
      .filter(wantsDetails)
      // Never fetched sorts ahead of everything: a new title deserves its air date first.
      .map((entry) => ({ id: entry.anime.id, at: this.detailed.get(entry.anime.id)?.at ?? Number.NEGATIVE_INFINITY }))
      .filter((title) => title.at <= staleBefore)
      .sort((a, b) => (a.at === b.at ? 0 : a.at < b.at ? -1 : 1))
      .slice(0, DETAILS_PER_LOAD);
    for (const { id } of due) {
      if (this.loadedFor !== userId || this.deps.accountId() !== userId) return;
      let details: Anime;
      try {
        details = await this.deps.shikimori.details(id);
      } catch {
        // Ignored: the list card still draws the title, and the next load asks again.
        continue;
      }
      this.detailed.set(id, { at: this.now(), anime: details });
      const held = this.confirmed.get(id);
      if (held && this.loadedFor === userId) {
        this.confirmed.set(id, { anime: withDetails(held.anime, details), rate: held.rate });
        this.publish();
      }
    }
  }

  private reset(): void {
    this.loadedFor = null;
    this.confirmed = new Map();
    this.current = { kind: "idle" };
    this.publish();
  }

  private write<T>(
    animeId: number,
    optimistic: Optimistic,
    send: (confirmed: LibraryEntry | undefined) => Promise<Sent<T>>,
  ): Promise<T> {
    this.writeCounts.set(animeId, (this.writeCounts.get(animeId) ?? 0) + 1);
    const ops = this.pending.get(animeId) ?? [];
    ops.push(optimistic);
    this.pending.set(animeId, ops);
    this.publish();
    // One queue per title, so an older value never lands after a newer one (decision 7).
    const previous = this.tails.get(animeId) ?? Promise.resolve();
    const run = previous.then(async () => {
      try {
        const sent = await send(this.confirmed.get(animeId));
        this.confirm(animeId, sent.entry);
        return sent.result;
      } finally {
        this.settle(animeId, optimistic);
      }
    });
    const tail = run.then(
      () => undefined,
      () => undefined,
    );
    this.tails.set(animeId, tail);
    void tail.then(() => {
      if (this.tails.get(animeId) === tail) this.tails.delete(animeId);
    });
    return run;
  }

  // A write changes the rate. The title keeps what the list or a details pass gave it meanwhile.
  private confirm(animeId: number, entry: LibraryEntry | undefined): void {
    if (!entry) return;
    const held = this.confirmed.get(animeId);
    this.confirmed.set(animeId, held ? { anime: held.anime, rate: entry.rate } : entry);
  }

  // The write is answered either way: its optimistic layer goes, which is the revert on failure.
  private settle(animeId: number, op: Optimistic): void {
    const ops = this.pending.get(animeId) ?? [];
    const index = ops.indexOf(op);
    if (index >= 0) ops.splice(index, 1);
    if (ops.length === 0) this.pending.delete(animeId);
    this.publish();
  }

  private publish(): void {
    const view = new Map<number, LibraryEntry>();
    for (const id of new Set([...this.confirmed.keys(), ...this.pending.keys()])) {
      let entry = this.confirmed.get(id);
      for (const apply of this.pending.get(id) ?? []) entry = apply(entry);
      if (entry) view.set(id, entry);
    }
    this.view = view;
    const entries = [...view.values()];
    const state = this.current;
    if (state.kind === "ready") this.current = { kind: "ready", entries };
    else if (state.kind === "loading" && state.entries !== null) this.current = { kind: "loading", entries };
    else if (state.kind === "error" && state.entries !== null) {
      this.current = { kind: "error", message: state.message, entries };
    }
    for (const listener of [...this.listeners]) listener();
  }

  private account(): number {
    const id = this.deps.accountId();
    // Signed out between the click and the write: say so the way an expired token would.
    if (id === null) throw new ApiError(401);
    return id;
  }

  private patch(rateId: number, fields: { status?: ListStatus; episodes?: number }): Promise<UserRate> {
    return this.deps.authorized((token) => this.deps.shikimori.updateRate(token, rateId, fields));
  }

  // Android setStatus: the server's rate, carrying the status this tab chose.
  private statusFrom(server: UserRate, animeId: number, status: ListStatus): UserRate {
    return { ...server, animeId, status, updatedAt: this.now() };
  }

  // Android setEpisodes: the local rate with the count the server stored.
  private countFrom(local: UserRate, server: UserRate): UserRate {
    return { ...local, id: server.id, episodes: server.episodes, updatedAt: this.now() };
  }
}

export function useLibrary(library: Library): LibraryState {
  return useSyncExternalStore(library.subscribe, library.state, library.state);
}
```

- [ ] **Step 4: Run to see it pass**

From `web/`:
```
npx vitest run src/library
```
**Expected:** `Test Files  3 passed (3)` and `Tests  62 passed (62)`: progress 11, prefs 8 and library 43.
```
npm test
```
**Expected:** exit code 0 with no failed tests, including the suites from Tasks 1–6.
```
npm run typecheck
```
**Expected:** exit code 0 and no diagnostics.

- [ ] **Step 5: Commit**

```
cd /Users/vitaliy/Projects/kaeru
git add web/src/library/progress.ts web/src/library/progress.test.ts web/src/library/prefs.ts web/src/library/prefs.test.ts web/src/library/library.ts web/src/library/library.test.ts
git commit -m "feat(web): список Shikimori, позиции серий и порог просмотра"
```

---

---

### Task 8: App shell, routing, gate, UI primitives

**Decisions:**
- **Screen stubs.** Screens are named exports that match their file names: `HomeScreen`, `SearchScreen`, `LibraryScreen`, `TitleScreen`, `SettingsScreen`. So that `App.tsx` compiles now, this task creates a one-line stub for each. Tasks 9–11 overwrite those files wholesale, keep the export name, and never edit `App.tsx`.
- **Services.** `services.tsx` exports `Services`, `createServices(deps?)`, `ServicesContext`, `ServicesProvider` and `useServices()`. Tests may provide fakes through either `ServicesProvider` or `ServicesContext.Provider`.
  - Services are rebuilt when the signed-in account id changes.
  - The signed-in outlet starts `library.load()` once, only while the library is still `idle`, so a screen may start it first.
- **Toast.** `useToast()` returns `{ show(text, action?: { label, onClick }), dismiss() }`. One toast shows at a time. It stays 4 s, or 8 s with an action, and pauses while it is hovered or focused.
- **Dialog.** `Dialog` is a confirm dialog: `{ open, title, text?, confirmLabel, cancelLabel, destructive?, onConfirm, onCancel }`. Focus starts on the cancel button.
- **Buttons.** Every button variant takes `to` and then renders a router link, because navigation is a link.
- **Extra primitives.** `PillGroup` gives a radiogroup or tablist with roving focus. `IconButton`, `PosterGrid`, `ProgressStrip`, `IndeterminateStrip` and `SyncingNotice` are added beside the contract's primitives.
- **One top bar.** Below 768px, `Layout` renders an opaque top bar (wordmark «Kaeru» and the «Аккаунт и настройки» link) above the content on every page, Home included. It is the only top bar in the app.
  - No transparent bar over the hero is built, here or later. Task 9 renders no `HomeBar` of its own, so the hero starts under this bar and settings are reached through this bar's account link.
  - From 768px the bar is hidden and the sidebar's account row does the same job.
- **Global CSS first.** `main.tsx` imports `tokens.css`, `base.css` and `components.css` above `App`. Stylesheets that screen modules import (Tasks 9–11) therefore come later in the bundle, so a screen rule wins over a `components.css` rule of equal specificity.
- **Routes.** `/watch/:id/:episode` renders outside `Layout`, with no bars, as the player will. Unknown paths inside the gate redirect to `/`. React Router matches `/auth/` with `path="/auth"`.
- **Sign-in callback.** `/auth` removes `?code&state` from the address with a replacing navigation before it calls `completeSignIn`.
  - On `closed` it also calls `sessionStore.setClosed(nickname)`. This is harmless if `completeSignIn` already did it.
  - «Войти ещё раз» starts a fresh authorization when signed out, and goes to `/` when already signed in.
- **Copy the maps don't give** (my own choices): skip link «Перейти к содержимому», skeleton label «Загрузка…», nav landmark «Разделы», stub headings.

**Files:**
- Create:
  - App: `web/src/app/App.tsx`, `web/src/app/bootstrap.ts`, `web/src/app/services.tsx`, `web/src/app/Gate.tsx`
  - Screens: `web/src/screens/SignInScreen.tsx`, `web/src/screens/AccessClosedScreen.tsx`, `web/src/screens/AuthCallbackScreen.tsx`, `web/src/screens/WatchPlaceholderScreen.tsx`
  - Route stubs, replaced wholesale by Tasks 9–11: `web/src/screens/HomeScreen.tsx`, `web/src/screens/SearchScreen.tsx`, `web/src/screens/LibraryScreen.tsx`, `web/src/screens/TitleScreen.tsx`, `web/src/screens/SettingsScreen.tsx`
  - UI: `web/src/ui/Layout.tsx`, `web/src/ui/Button.tsx`, `web/src/ui/Pill.tsx`, `web/src/ui/PosterCard.tsx`, `web/src/ui/Shelf.tsx`, `web/src/ui/Skeleton.tsx`, `web/src/ui/States.tsx`, `web/src/ui/Toast.tsx`, `web/src/ui/Dialog.tsx`, `web/src/ui/icons.tsx`, `web/src/ui/components.css`
- Modify: `web/src/main.tsx`
- Test:
  - App: `web/src/app/bootstrap.test.ts`, `web/src/app/services.test.tsx`, `web/src/app/Gate.test.tsx`, `web/src/app/App.test.tsx`
  - Screens: `web/src/screens/SignInScreen.test.tsx`, `web/src/screens/AuthCallbackScreen.test.tsx`, `web/src/screens/WatchPlaceholderScreen.test.tsx`
  - UI: `web/src/ui/Layout.test.tsx`, `web/src/ui/Button.test.tsx`, `web/src/ui/Pill.test.tsx`, `web/src/ui/PosterCard.test.tsx`, `web/src/ui/States.test.tsx`, `web/src/ui/Toast.test.tsx`, `web/src/ui/Dialog.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `CLIENT_ID` (tests), the tokens and `.t-*` classes.
  - Task 2: `episodeBadge(n: number): string`.
  - Task 4: `interface Card { key; animeId; title; posterUrl; badge; subtitle; progress }`.
  - Task 5: `createShikimoriHttp(deps?)`, `createShikimori(http)`, `type Shikimori`, `interface Account`.
  - Task 6: `useAccess()`, `sessionStore`, `class SessionStore`, `type Session`, `authorized`, `beginSignIn(returnTo, deps?)`, `completeSignIn(search, deps?)`, `type SignInResult`.
  - Task 7: `class Library` (`state()`, `load()`), `class ProgressStore`, `progressStore`.
- Produces:

```ts
// app/App.tsx
export function App(props: { services?: Services }): JSX.Element;       // BrowserRouter + AppRoutes
export function AppRoutes(props: { services?: Services }): JSX.Element; // route table without a router
// app/bootstrap.ts
export function restoreDeepLink(location: Location, history: History): void;
// app/services.tsx
export interface Services { shikimori: Shikimori; library: Library; progress: ProgressStore }
export function createServices(deps?: { fetch?: typeof fetch; store?: SessionStore; progress?: ProgressStore }): Services;
export const ServicesContext: React.Context<Services | null>;
export function ServicesProvider(props: { services: Services; children: ReactNode }): JSX.Element;
export function useServices(): Services; // throws outside the provider
// app/Gate.tsx
export function Gate(props: { children: ReactNode }): JSX.Element;
// screens
export function SignInScreen(props: { message: string | null; begin?: (returnTo: string) => string; navigateTo?: (url: string) => void }): JSX.Element;
export function AccessClosedScreen(props: { nickname: string; onSignOut?: () => void }): JSX.Element;
export function AuthCallbackScreen(props: { complete?: (search: string) => Promise<SignInResult>; begin?: (returnTo: string) => string; navigateTo?: (url: string) => void }): JSX.Element;
export function WatchPlaceholderScreen(): JSX.Element;
export function HomeScreen(): JSX.Element;     // stub, Task 9 overwrites
export function SearchScreen(): JSX.Element;   // stub, Task 11 overwrites
export function LibraryScreen(): JSX.Element;  // stub, Task 11 overwrites
export function TitleScreen(): JSX.Element;    // stub, Task 10 overwrites
export function SettingsScreen(): JSX.Element; // stub, Task 11 overwrites
// ui/Layout.tsx
export function Layout(props: { children?: ReactNode }): JSX.Element; // renders <Outlet/> when no children; below 768px its top bar is the app's only top bar
// ui/Button.tsx
export type ButtonAsButton; export type ButtonAsLink; export type ButtonProps = ButtonAsButton | ButtonAsLink;
// common props: children, icon?, className?, fullWidth?, compact?, chevron?; link form: to, replace?, onClick?, "aria-label"?
export function PrimaryButton(p: ButtonProps); export function SecondaryButton(p: ButtonProps);
export function TextAction(p: ButtonProps); export function DestructiveButton(p: ButtonProps);
export interface IconButtonProps { label: string; icon: ReactNode; overArt?: boolean /* + button attributes */ }
export function IconButton(p: IconButtonProps);
// ui/Pill.tsx
export interface PillProps { selected?: boolean; trailing?: ReactNode; className?: string /* + button attributes */ }
export function Pill(p: PillProps);
export interface PillOption<T extends string | number> { value: T; label: string }
export interface PillGroupProps<T> { kind: "radio" | "tab"; label: string; options: readonly PillOption<T>[]; value: T; onChange(value: T): void; panelId?: string; idPrefix?: string; className?: string }
export function PillGroup<T extends string | number>(p: PillGroupProps<T>);
// ui/PosterCard.tsx
export interface PosterCardProps { card: Card; layout?: "row" | "grid"; to?: string; footer?: ReactNode }
export function PosterCard(p: PosterCardProps); export function PosterGrid(p: { label?: string; children: ReactNode });
export function posterLetter(title: string): string;
// ui/Shelf.tsx
export interface ShelfProps { title: string; action?: { label: string; to: string }; extra?: ReactNode; children: ReactNode }
export function Shelf(p: ShelfProps);
// ui/Skeleton.tsx
export function SkeletonBlock(p: { className?; width?; height?; radius? }); export function SkeletonGroup(p: { label?: string; children });
export function SkeletonHero(); export function SkeletonShelf(p: { cards?: number }); export function SkeletonGrid(p: { count?: number });
// ui/States.tsx
export interface StateAction { label: string; to?: string; onClick?: () => void }
export function EmptyState(p: { title: string; text?: string; action?: StateAction; align?: "center" | "start" });
export function ErrorState(p: { message: string; onRetry?: () => void; align?: "center" | "start" }); // «Повторить», secondary
export function ProgressStrip(p: { value: number }); export function IndeterminateStrip(p: { label?: string });
export function SyncingNotice(p: { text?: string }); // default «Синхронизируем список с Shikimori…»
// ui/Toast.tsx
export interface ToastAction { label: string; onClick: () => void }
export interface ToastApi { show(text: string, action?: ToastAction): void; dismiss(): void }
export const TOAST_MS = 4000; export const TOAST_WITH_ACTION_MS = 8000;
export function ToastProvider(p: { children: ReactNode }); export function useToast(): ToastApi;
// ui/Dialog.tsx
export interface DialogProps { open: boolean; title: string; text?: string; confirmLabel: string; cancelLabel: string; destructive?: boolean; onConfirm(): void; onCancel(): void }
export function Dialog(p: DialogProps);
// ui/icons.tsx — each (props: IconProps = SVG props + size?) renders an aria-hidden 24×24 glyph
IconHome, IconSearch, IconLibrary, IconPlay, IconPlayCircle, IconCheckCircle, IconCheck, IconClock,
IconChevronRight, IconArrowDropDown, IconClose, IconBack, IconMore, IconPerson
// CSS helpers in components.css: .page, .page-title, .poster-grid, .screen-center
```

- [ ] **Step 1: Write the failing test**

`web/src/app/bootstrap.test.ts`:

```ts
import { afterEach, describe, expect, it } from "vitest";
import { restoreDeepLink } from "./bootstrap";

// The docs/cast/404.html prelude sends /anime/1535?x#frag to /?p=%2Fanime%2F1535%3Fx#frag (web-map 5).
function openAt(url: string): string {
  window.history.replaceState(null, "", url);
  restoreDeepLink(window.location, window.history);
  return window.location.pathname + window.location.search + window.location.hash;
}

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("restoreDeepLink", () => {
  it("puts a deep link back in the address", () => {
    expect(openAt("/?p=%2Fanime%2F1535")).toBe("/anime/1535");
  });

  it("keeps the query of the original path, e.g. the OAuth return", () => {
    expect(openAt("/?p=%2Fauth%3Fcode%3Dabc%26state%3Dxyz")).toBe("/auth?code=abc&state=xyz");
  });

  it("keeps the fragment, which the prelude leaves outside ?p=", () => {
    expect(openAt("/?p=%2Fsearch#top")).toBe("/search#top");
  });

  it("accepts the trailing-slash form Pages redirects to", () => {
    expect(openAt("/?p=%2Fauth%2F%3Fcode%3Dabc")).toBe("/auth/?code=abc");
  });

  it.each([
    ["a protocol-relative address", "/?p=%2F%2Fevil.example%2Fx"],
    ["a backslash that browsers read as //", "/?p=%2F%5Cevil.example"],
    ["an absolute URL", "/?p=https%3A%2F%2Fevil.example%2F"],
    ["a path without the leading slash", "/?p=anime%2F1"],
  ])("ignores %s", (_name, url) => {
    expect(openAt(url)).toBe(url);
  });

  it("leaves an ordinary address alone", () => {
    expect(openAt("/search?q=1")).toBe("/search?q=1");
  });
});
```

`web/src/app/services.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SessionStore, type Session } from "../auth/session";
import { progressStore } from "../library/progress";
import { createServices, ServicesProvider, useServices } from "./services";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function Probe() {
  const services = useServices();
  return <p>{services.progress === progressStore ? "общий прогресс" : "другой прогресс"}</p>;
}

describe("services", () => {
  it("refuse to work outside the provider", () => {
    expect(() => render(<Probe />)).toThrow(/ServicesProvider/);
  });

  it("reach every screen through the provider", () => {
    render(
      <ServicesProvider services={createServices()}>
        <Probe />
      </ServicesProvider>,
    );
    expect(screen.getByText("общий прогресс")).toBeInTheDocument();
  });

  it("start with an idle library", () => {
    expect(createServices().library.state()).toEqual({ kind: "idle" });
  });

  it("load the signed-in account's list with its token", async () => {
    const calls: { url: string; authorization: string | null }[] = [];
    const fakeFetch: typeof fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
      calls.push({ url, authorization: headers.get("Authorization") });
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    };
    const store = new SessionStore(window.sessionStorage);
    store.setSession(SESSION);
    const services = createServices({ fetch: fakeFetch, store });

    await services.library.load();

    const rates = calls.filter((call) => call.url.includes("user_rates"));
    expect(rates.length).toBeGreaterThan(0);
    for (const call of rates) {
      expect(call.url).toContain("user_id=42");
      expect(call.authorization).toBe("Bearer tok");
    }
    expect(services.library.state()).toEqual({ kind: "ready", entries: [] });
  }, 10_000);
});
```

`web/src/app/Gate.test.tsx`:

```tsx
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { sessionStore, type Session } from "../auth/session";
import { Gate } from "./Gate";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function renderGate() {
  return render(
    <MemoryRouter>
      <Gate>
        <p>Содержимое приложения</p>
      </Gate>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  sessionStore.signOut();
});

describe("Gate", () => {
  it("shows nothing but the sign-in screen when signed out", () => {
    renderGate();
    expect(screen.getByRole("heading", { name: "Kaeru" })).toBeInTheDocument();
    expect(
      screen.getByText("Войдите через Shikimori, чтобы синхронизировать список и просмотренные серии."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Войти через Shikimori" })).toBeEnabled();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText("Содержимое приложения")).toBeNull();
  });

  it("says why the session ended", () => {
    sessionStore.signOut("Сессия истекла, войдите снова");
    renderGate();
    expect(screen.getByRole("alert")).toHaveTextContent("Сессия истекла, войдите снова");
  });

  it("shows «Доступ закрыт» with the nickname for an account off the list", () => {
    sessionStore.setClosed("friend");
    renderGate();
    expect(screen.getByRole("heading", { name: "Доступ закрыт" })).toBeInTheDocument();
    expect(screen.getByText("friend")).toBeInTheDocument();
    expect(screen.getByText("Kaeru для браузера открыт по приглашению")).toBeInTheDocument();
    expect(screen.getByText("Попросите владельца добавить ваш аккаунт Shikimori в список.")).toBeInTheDocument();
    expect(screen.queryByText("Содержимое приложения")).toBeNull();
  });

  it("signs a closed account out, back to the sign-in screen", async () => {
    sessionStore.setClosed("friend");
    renderGate();
    await userEvent.setup().click(screen.getByRole("button", { name: "Выйти" }));
    expect(sessionStore.get().kind).toBe("signed_out");
    expect(screen.getByRole("button", { name: "Войти через Shikimori" })).toBeInTheDocument();
  });

  it("lets a signed-in account through", () => {
    sessionStore.setSession(SESSION);
    renderGate();
    expect(screen.getByText("Содержимое приложения")).toBeInTheDocument();
  });

  it("follows the session live, e.g. a sign-out in another tab", () => {
    sessionStore.setSession(SESSION);
    renderGate();
    act(() => {
      sessionStore.signOut("Сессия истекла, войдите снова");
    });
    expect(screen.queryByText("Содержимое приложения")).toBeNull();
    expect(screen.getByRole("alert")).toHaveTextContent("Сессия истекла, войдите снова");
  });
});
```

`web/src/app/App.test.tsx`:

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { sessionStore, type Session } from "../auth/session";
import { App } from "./App";
import { createServices } from "./services";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

// Every Shikimori call answers with an empty list; nothing leaves the test.
const emptyShikimori: typeof fetch = async () =>
  new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });

function openAt(path: string) {
  window.history.replaceState(null, "", path);
  const services = createServices({ fetch: emptyShikimori });
  render(<App services={services} />);
  return services;
}

beforeEach(() => {
  sessionStore.signOut();
});

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("App", () => {
  it("shows nothing but the sign-in screen when signed out", () => {
    openAt("/search");
    expect(screen.getByRole("button", { name: "Войти через Shikimori" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it.each(["/auth", "/auth/"])("serves %s outside the gate", async (path) => {
    openAt(path);
    expect(screen.getByText("Проверяем код…")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Войти ещё раз" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Войти через Shikimori" })).toBeNull();
  });

  it("shows «Доступ закрыт» for an account off the list, on any page", () => {
    sessionStore.setClosed("friend");
    openAt("/anime/1");
    expect(screen.getByRole("heading", { name: "Доступ закрыт" })).toBeInTheDocument();
    expect(screen.getByText("friend")).toBeInTheDocument();
  });

  it("lets a signed-in viewer in and starts loading the list", async () => {
    sessionStore.setSession(SESSION);
    const services = openAt("/watch/7/2");
    expect(screen.getByRole("heading", { name: "Плеер появится в следующем обновлении" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Назад к тайтлу" })).toHaveAttribute("href", "/anime/7");
    await waitFor(() => expect(services.library.state().kind).toBe("ready"), { timeout: 3000 });
  });
});
```

`web/src/screens/SignInScreen.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { CLIENT_ID } from "../config";
import { SignInScreen } from "./SignInScreen";

describe("SignInScreen", () => {
  it("sends the browser to Shikimori's authorize page", async () => {
    const navigateTo = vi.fn<(url: string) => void>();
    render(
      <MemoryRouter>
        <SignInScreen message={null} navigateTo={navigateTo} />
      </MemoryRouter>,
    );
    const button = screen.getByRole("button", { name: "Войти через Shikimori" });
    await userEvent.setup().click(button);

    expect(navigateTo).toHaveBeenCalledTimes(1);
    const url = new URL(navigateTo.mock.calls[0][0]);
    // web-map 3: /oauth/authorize?client_id&redirect_uri&response_type=code&scope=user_rates&state
    expect(url.origin + url.pathname).toBe("https://shikimori.io/oauth/authorize");
    expect(url.searchParams.get("client_id")).toBe(CLIENT_ID);
    expect(url.searchParams.get("redirect_uri")).toBe(`${window.location.origin}/auth`);
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("scope")).toBe("user_rates");
    // 32 random bytes, base64url without padding.
    expect(url.searchParams.get("state")).toMatch(/^[A-Za-z0-9_-]{43}$/);
    // The page is leaving: a second press must not start another authorization.
    expect(button).toBeDisabled();
  });

  it("asks to come back to the page that was open", async () => {
    const begin = vi.fn((returnTo: string) => `https://shikimori.io/oauth/authorize?r=${encodeURIComponent(returnTo)}`);
    render(
      <MemoryRouter initialEntries={["/anime/5?tab=1#episodes"]}>
        <SignInScreen message={null} begin={begin} navigateTo={() => undefined} />
      </MemoryRouter>,
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "Войти через Shikimori" }));
    expect(begin).toHaveBeenCalledWith("/anime/5?tab=1#episodes");
  });

  it("shows why the viewer is back", () => {
    render(
      <MemoryRouter>
        <SignInScreen message="Сессия истекла, войдите снова" navigateTo={() => undefined} />
      </MemoryRouter>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Сессия истекла, войдите снова");
  });
});
```

`web/src/screens/AuthCallbackScreen.test.tsx`:

```tsx
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Gate } from "../app/Gate";
import { sessionStore, type Session } from "../auth/session";
import type { SignInResult } from "../auth/signin";
import { AuthCallbackScreen } from "./AuthCallbackScreen";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function deferred() {
  let resolve: (result: SignInResult) => void = () => undefined;
  const promise = new Promise<SignInResult>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function Address() {
  const location = useLocation();
  return <p data-testid="address">{location.pathname + location.search}</p>;
}

function renderCallback(
  complete: (search: string) => Promise<SignInResult>,
  navigateTo: (url: string) => void = () => undefined,
) {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={["/auth?code=abc&state=xyz"]}>
        <Routes>
          <Route path="/auth" element={<AuthCallbackScreen complete={complete} navigateTo={navigateTo} />} />
          <Route path="/anime/:id" element={<p>Страница тайтла</p>} />
          <Route
            path="/"
            element={
              <Gate>
                <p>Главная страница</p>
              </Gate>
            }
          />
        </Routes>
        <Address />
      </MemoryRouter>
    </StrictMode>,
  );
}

beforeEach(() => {
  sessionStore.signOut();
});

describe("AuthCallbackScreen", () => {
  it("checks the code once, even under StrictMode, and drops it from the address", () => {
    const pending = deferred();
    const complete = vi.fn((_search: string) => pending.promise);
    renderCallback(complete);
    expect(screen.getByText("Проверяем код…")).toBeInTheDocument();
    expect(complete).toHaveBeenCalledTimes(1);
    expect(complete).toHaveBeenCalledWith("?code=abc&state=xyz");
    expect(screen.getByTestId("address")).toHaveTextContent(/^\/auth$/);
  });

  it("returns to the page that started the sign-in", async () => {
    const pending = deferred();
    renderCallback(() => pending.promise);
    await act(async () => {
      pending.resolve({ kind: "done", returnTo: "/anime/5" });
    });
    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
    expect(screen.getByTestId("address")).toHaveTextContent(/^\/anime\/5$/);
  });

  it("hands an account off the list to the gate's closed screen", async () => {
    const pending = deferred();
    renderCallback(() => pending.promise);
    await act(async () => {
      pending.resolve({ kind: "closed", nickname: "friend" });
    });
    expect(screen.getByRole("heading", { name: "Доступ закрыт" })).toBeInTheDocument();
    expect(screen.getByText("friend")).toBeInTheDocument();
    expect(sessionStore.get()).toEqual({ kind: "closed", nickname: "friend" });
  });

  it("explains a failure and starts a fresh sign-in", async () => {
    const pending = deferred();
    const navigateTo = vi.fn<(url: string) => void>();
    renderCallback(() => pending.promise, navigateTo);
    await act(async () => {
      pending.resolve({ kind: "error", message: "Shikimori не вернул код. Попробуйте войти ещё раз" });
    });
    expect(screen.getByRole("alert")).toHaveTextContent("Shikimori не вернул код. Попробуйте войти ещё раз");
    await userEvent.setup().click(screen.getByRole("button", { name: "Войти ещё раз" }));
    expect(navigateTo).toHaveBeenCalledTimes(1);
    const url = new URL(navigateTo.mock.calls[0][0]);
    expect(url.origin + url.pathname).toBe("https://shikimori.io/oauth/authorize");
  });

  it("sends an already signed-in viewer home instead of signing in again", async () => {
    sessionStore.setSession(SESSION);
    const pending = deferred();
    const navigateTo = vi.fn<(url: string) => void>();
    renderCallback(() => pending.promise, navigateTo);
    await act(async () => {
      pending.resolve({ kind: "error", message: "Вход уже выполнен. Запрос авторизации отклонён" });
    });
    await userEvent.setup().click(screen.getByRole("button", { name: "Войти ещё раз" }));
    expect(navigateTo).not.toHaveBeenCalled();
    expect(screen.getByText("Главная страница")).toBeInTheDocument();
  });

  it("treats a thrown failure as an error, not a hang", async () => {
    renderCallback(() => Promise.reject(new Error("boom")));
    expect(await screen.findByRole("alert")).toHaveTextContent("Что-то пошло не так. Повторите попытку");
  });
});
```

`web/src/screens/WatchPlaceholderScreen.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { WatchPlaceholderScreen } from "./WatchPlaceholderScreen";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/watch/:id/:episode" element={<WatchPlaceholderScreen />} />
        <Route path="/anime/:id" element={<p>Страница тайтла</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("WatchPlaceholderScreen", () => {
  it("says the player is coming and leads back to the title", async () => {
    renderAt("/watch/1535/3");
    expect(screen.getByRole("heading", { name: "Плеер появится в следующем обновлении" })).toBeInTheDocument();
    expect(screen.getByText("3 серия")).toBeInTheDocument();
    const back = screen.getByRole("link", { name: "Назад к тайтлу" });
    expect(back).toHaveAttribute("href", "/anime/1535");
    await userEvent.setup().click(back);
    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
  });

  it("leaves out the episode line for a malformed episode", () => {
    renderAt("/watch/1535/x");
    expect(screen.queryByText(/серия/)).toBeNull();
  });
});
```

`web/src/ui/Layout.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { sessionStore, type Session } from "../auth/session";
import { Layout } from "./Layout";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<p>Главная страница</p>} />
          <Route path="/list" element={<p>Страница списка</p>} />
          <Route path="/search" element={<p>Страница поиска</p>} />
          <Route path="/settings" element={<p>Страница настроек</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  sessionStore.setSession(SESSION);
});

afterEach(() => {
  sessionStore.signOut();
});

describe("Layout", () => {
  it("renders the page inside the main landmark", () => {
    renderAt("/list");
    expect(within(screen.getByRole("main")).getByText("Страница списка")).toBeInTheDocument();
  });

  it("offers the sections in the sidebar and the tab bar, marking the current one", () => {
    renderAt("/list");
    const current = screen.getAllByRole("link", { name: "Мой список" });
    expect(current).toHaveLength(2);
    for (const link of current) expect(link).toHaveAttribute("aria-current", "page");
    for (const name of ["Главная", "Поиск"]) {
      const links = screen.getAllByRole("link", { name });
      expect(links).toHaveLength(2);
      for (const link of links) expect(link).not.toHaveAttribute("aria-current");
    }
  });

  it("groups «Мой список» under «Библиотека» in the sidebar", () => {
    renderAt("/");
    const group = screen.getByRole("group", { name: "Библиотека" });
    expect(within(group).getByRole("link", { name: "Мой список" })).toHaveAttribute("href", "/list");
  });

  it("orders the tab bar as on Android: Главная, Мой список, Поиск", () => {
    const { container } = renderAt("/");
    const sidebar = container.querySelector("aside") as HTMLElement;
    const tabbar = screen
      .getAllByRole("navigation", { name: "Разделы" })
      .find((nav) => !sidebar.contains(nav)) as HTMLElement;
    expect(within(tabbar).getAllByRole("link").map((link) => link.textContent)).toEqual([
      "Главная",
      "Мой список",
      "Поиск",
    ]);
  });

  it("opens settings from the account row, labelled for screen readers", async () => {
    renderAt("/");
    const account = screen.getAllByRole("link", { name: "Аккаунт и настройки" });
    expect(account).toHaveLength(2);
    for (const link of account) expect(link).toHaveAttribute("href", "/settings");
    expect(screen.getByText("Vitaliy")).toBeInTheDocument();
    await userEvent.setup().click(account[0]);
    expect(screen.getByText("Страница настроек")).toBeInTheDocument();
  });

  it("shows the Shikimori avatar when there is one", () => {
    const avatar = "https://shikimori.io/system/users/x160/42.png";
    sessionStore.setSession({ ...SESSION, account: { ...SESSION.account, avatar } });
    const { container } = renderAt("/");
    const images = container.querySelectorAll("img.avatar");
    expect(images).toHaveLength(2);
    for (const image of images) {
      expect(image).toHaveAttribute("src", avatar);
      expect(image).toHaveAttribute("alt", "");
    }
  });

  it("moves between sections", async () => {
    renderAt("/");
    await userEvent.setup().click(screen.getAllByRole("link", { name: "Поиск" })[0]);
    expect(screen.getByText("Страница поиска")).toBeInTheDocument();
    for (const link of screen.getAllByRole("link", { name: "Поиск" })) {
      expect(link).toHaveAttribute("aria-current", "page");
    }
  });

  it("offers a skip link to the content", () => {
    renderAt("/");
    expect(screen.getByRole("link", { name: "Перейти к содержимому" })).toHaveAttribute("href", "#main");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main");
  });
});
```

`web/src/ui/Button.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { FormEvent } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { DestructiveButton, IconButton, PrimaryButton, SecondaryButton, TextAction } from "./Button";
import { IconBack, IconPlay } from "./icons";

describe("buttons", () => {
  it("are real buttons that never submit a form by accident", async () => {
    const onClick = vi.fn();
    const onSubmit = vi.fn((event: FormEvent) => event.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <PrimaryButton onClick={onClick}>Смотреть 1 серию</PrimaryButton>
      </form>,
    );
    const button = screen.getByRole("button", { name: "Смотреть 1 серию" });
    expect(button).toHaveAttribute("type", "button");
    await userEvent.setup().click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("ignore presses while disabled", async () => {
    const onClick = vi.fn();
    render(
      <PrimaryButton disabled onClick={onClick}>
        Ждём 9 серию
      </PrimaryButton>,
    );
    const button = screen.getByRole("button", { name: "Ждём 9 серию" });
    expect(button).toBeDisabled();
    await userEvent.setup().click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("become router links when they navigate", async () => {
    render(
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<SecondaryButton to="/anime/5">Подробнее</SecondaryButton>} />
          <Route path="/anime/:id" element={<p>Страница тайтла</p>} />
        </Routes>
      </MemoryRouter>,
    );
    const link = screen.getByRole("link", { name: "Подробнее" });
    expect(link).toHaveAttribute("href", "/anime/5");
    await userEvent.setup().click(link);
    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
  });

  it("keep the icon out of the accessible name", () => {
    render(<PrimaryButton icon={<IconPlay />}>Продолжить с 7:26</PrimaryButton>);
    const button = screen.getByRole("button", { name: "Продолжить с 7:26" });
    expect(button.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("carry their variant for styling", () => {
    render(
      <>
        <PrimaryButton>Первая</PrimaryButton>
        <SecondaryButton>Вторая</SecondaryButton>
        <TextAction>Третья</TextAction>
        <DestructiveButton>Четвёртая</DestructiveButton>
        <PrimaryButton fullWidth>Пятая</PrimaryButton>
        <SecondaryButton compact>Шестая</SecondaryButton>
      </>,
    );
    expect(screen.getByRole("button", { name: "Первая" })).toHaveClass("btn", "btn--primary");
    expect(screen.getByRole("button", { name: "Вторая" })).toHaveClass("btn", "btn--secondary");
    expect(screen.getByRole("button", { name: "Третья" })).toHaveClass("btn", "btn--text");
    expect(screen.getByRole("button", { name: "Четвёртая" })).toHaveClass("btn", "btn--destructive");
    expect(screen.getByRole("button", { name: "Пятая" })).toHaveClass("btn--full");
    expect(screen.getByRole("button", { name: "Шестая" })).toHaveClass("btn--compact");
  });

  it("let a text action show a chevron", () => {
    render(
      <MemoryRouter>
        <TextAction to="/list" chevron>
          Всё
        </TextAction>
      </MemoryRouter>,
    );
    const link = screen.getByRole("link", { name: "Всё" });
    expect(link.querySelectorAll("svg")).toHaveLength(1);
  });

  it("name an icon button by its label", async () => {
    const onClick = vi.fn();
    render(<IconButton label="Назад" icon={<IconBack />} onClick={onClick} overArt />);
    const button = screen.getByRole("button", { name: "Назад" });
    expect(button).toHaveClass("icon-button", "icon-button--over-art");
    await userEvent.setup().click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
```

`web/src/ui/Pill.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { IconArrowDropDown } from "./icons";
import { Pill, PillGroup, type PillOption } from "./Pill";

// Season chips: prev, current, next (web-map 1), radio semantics (HomeDiscover.kt:137-153).
const SEASONS: PillOption<string>[] = [
  { value: "summer_2026", label: "Лето 2026" },
  { value: "fall_2026", label: "Осень 2026" },
  { value: "winter_2027", label: "Зима 2027" },
];

function Seasons() {
  const [value, setValue] = useState("fall_2026");
  return <PillGroup kind="radio" label="Сезон" options={SEASONS} value={value} onChange={setValue} />;
}

function Statuses() {
  const [value, setValue] = useState("watching");
  return (
    <PillGroup
      kind="tab"
      label="Статус"
      options={[
        { value: "watching", label: "Смотрю 12" },
        { value: "planned", label: "В планах 3" },
      ]}
      value={value}
      onChange={setValue}
      panelId="list-panel"
      idPrefix="status"
    />
  );
}

describe("PillGroup as a radiogroup", () => {
  it("names the group and checks the chosen pill", () => {
    render(<Seasons />);
    const group = screen.getByRole("radiogroup", { name: "Сезон" });
    expect(within(group).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "Лето 2026",
      "Осень 2026",
      "Зима 2027",
    ]);
    const autumn = screen.getByRole("radio", { name: "Осень 2026" });
    expect(autumn).toHaveAttribute("aria-checked", "true");
    expect(autumn).toHaveClass("pill--selected");
    expect(screen.getByRole("radio", { name: "Лето 2026" })).toHaveAttribute("aria-checked", "false");
  });

  it("keeps a single tab stop, on the chosen pill", () => {
    render(<Seasons />);
    expect(screen.getByRole("radio", { name: "Осень 2026" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "Лето 2026" })).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("radio", { name: "Зима 2027" })).toHaveAttribute("tabindex", "-1");
  });

  it("chooses by click", async () => {
    render(<Seasons />);
    await userEvent.setup().click(screen.getByRole("radio", { name: "Зима 2027" }));
    expect(screen.getByRole("radio", { name: "Зима 2027" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Осень 2026" })).toHaveAttribute("aria-checked", "false");
  });

  it("moves with the arrow keys, wrapping around, and with Home and End", async () => {
    const user = userEvent.setup();
    render(<Seasons />);
    await user.click(screen.getByRole("radio", { name: "Осень 2026" }));
    const summer = screen.getByRole("radio", { name: "Лето 2026" });
    const winter = screen.getByRole("radio", { name: "Зима 2027" });

    await user.keyboard("{ArrowRight}");
    expect(winter).toHaveAttribute("aria-checked", "true");
    expect(winter).toHaveFocus();

    await user.keyboard("{ArrowRight}");
    expect(summer).toHaveAttribute("aria-checked", "true");
    expect(summer).toHaveFocus();

    await user.keyboard("{ArrowLeft}");
    expect(winter).toHaveFocus();

    await user.keyboard("{Home}");
    expect(summer).toHaveAttribute("aria-checked", "true");

    await user.keyboard("{End}");
    expect(winter).toHaveAttribute("aria-checked", "true");
  });
});

describe("PillGroup as a tablist", () => {
  it("marks the selected tab and the panel it controls", async () => {
    render(<Statuses />);
    const tablist = screen.getByRole("tablist", { name: "Статус" });
    const watching = within(tablist).getByRole("tab", { name: "Смотрю 12" });
    expect(watching).toHaveAttribute("aria-selected", "true");
    expect(watching).toHaveAttribute("aria-controls", "list-panel");
    expect(watching).toHaveAttribute("id", "status-watching");
    await userEvent.setup().click(screen.getByRole("tab", { name: "В планах 3" }));
    expect(screen.getByRole("tab", { name: "В планах 3" })).toHaveAttribute("aria-selected", "true");
    expect(watching).toHaveAttribute("aria-selected", "false");
  });

  it("does not move on up and down arrows (a horizontal tablist)", async () => {
    const user = userEvent.setup();
    render(<Statuses />);
    await user.click(screen.getByRole("tab", { name: "Смотрю 12" }));
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("tab", { name: "Смотрю 12" })).toHaveAttribute("aria-selected", "true");
  });
});

describe("Pill", () => {
  it("marks selection and hides its trailing glyph", () => {
    render(
      <Pill selected trailing={<IconArrowDropDown />} aria-haspopup="menu">
        Смотрю
      </Pill>,
    );
    const pill = screen.getByRole("button", { name: "Смотрю" });
    expect(pill).toHaveClass("pill", "pill--selected");
    expect(pill).toHaveAttribute("aria-haspopup", "menu");
    expect(pill.querySelector(".pill__trailing")).toHaveAttribute("aria-hidden", "true");
  });
});
```

`web/src/ui/PosterCard.test.tsx`:

```tsx
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Card } from "../domain/feed";
import { SecondaryButton } from "./Button";
import { PosterCard, PosterGrid, type PosterCardProps } from "./PosterCard";
import { Shelf } from "./Shelf";

const CARD: Card = {
  key: "52991",
  animeId: 52991,
  title: "Фрирен, провожающая в последний путь",
  posterUrl: "https://shikimori.io/uploads/poster/animes/52991/main_alt.jpeg",
  badge: "3 серия",
  subtitle: "осталось 12 мин",
  progress: 0.5,
};

function renderCard(props: Partial<PosterCardProps> & { card: Card }) {
  return render(
    <MemoryRouter>
      <PosterCard {...props} />
    </MemoryRouter>,
  );
}

describe("PosterCard", () => {
  it("links to the title page with the artwork, badge and subtitle", () => {
    const { container } = renderCard({ card: CARD });
    const link = screen.getByRole("link", { name: /Фрирен, провожающая в последний путь/ });
    expect(link).toHaveAttribute("href", "/anime/52991");
    expect(within(link).getByText("3 серия")).toBeInTheDocument();
    expect(within(link).getByText("осталось 12 мин")).toBeInTheDocument();
    const image = container.querySelector("img");
    expect(image).toHaveAttribute("src", CARD.posterUrl);
    // The title sits beside the artwork, so the image itself is decorative.
    expect(image).toHaveAttribute("alt", "");
  });

  it("shows the title's first letter when there is no poster", () => {
    const { container } = renderCard({ card: { ...CARD, posterUrl: null } });
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("Ф")).toHaveAttribute("aria-hidden", "true");
  });

  it("upper-cases the letter the Russian way", () => {
    renderCard({ card: { ...CARD, title: "ёлка", posterUrl: null } });
    expect(screen.getByText("Ё")).toBeInTheDocument();
  });

  it("falls back to the letter when the image fails", () => {
    const { container } = renderCard({ card: CARD });
    fireEvent.error(container.querySelector("img") as HTMLImageElement);
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("Ф")).toBeInTheDocument();
  });

  it("draws the progress strip only for a started episode", () => {
    const { container, rerender } = renderCard({ card: CARD });
    expect(container.querySelector<HTMLElement>(".progress-strip__fill")?.style.width).toBe("50%");
    rerender(
      <MemoryRouter>
        <PosterCard card={{ ...CARD, progress: null }} />
      </MemoryRouter>,
    );
    expect(container.querySelector(".progress-strip")).toBeNull();
    rerender(
      <MemoryRouter>
        <PosterCard card={{ ...CARD, progress: 0 }} />
      </MemoryRouter>,
    );
    expect(container.querySelector(".progress-strip")).toBeNull();
  });

  it("keeps the footer control outside the link", () => {
    renderCard({ card: CARD, layout: "grid", footer: <SecondaryButton compact>В планы</SecondaryButton> });
    expect(screen.getByRole("link")).not.toContainElement(screen.getByRole("button", { name: "В планы" }));
  });

  it("can lead somewhere other than the title page", () => {
    renderCard({ card: CARD, to: "/watch/52991/3" });
    expect(screen.getByRole("link")).toHaveAttribute("href", "/watch/52991/3");
  });
});

describe("Shelf", () => {
  it("is a region named by its header, one list item per card", () => {
    render(
      <MemoryRouter>
        <Shelf title="Продолжить" action={{ label: "Всё", to: "/list" }}>
          <PosterCard card={CARD} />
          <PosterCard card={{ ...CARD, key: "1535", animeId: 1535, title: "Тетрадь смерти" }} />
        </Shelf>
      </MemoryRouter>,
    );
    const shelf = screen.getByRole("region", { name: "Продолжить" });
    expect(within(shelf).getByRole("heading", { level: 2, name: "Продолжить" })).toBeInTheDocument();
    expect(within(shelf).getAllByRole("listitem")).toHaveLength(2);
    expect(within(shelf).getByRole("link", { name: "Всё" })).toHaveAttribute("href", "/list");
  });

  it("puts extra controls between the header and the row", () => {
    render(
      <MemoryRouter>
        <Shelf title="Популярное в сезоне" extra={<p>Выбор сезона</p>}>
          <PosterCard card={CARD} />
        </Shelf>
      </MemoryRouter>,
    );
    const shelf = screen.getByRole("region", { name: "Популярное в сезоне" });
    expect(within(shelf).getByText("Выбор сезона")).toBeInTheDocument();
  });
});

describe("PosterGrid", () => {
  it("lists its cards and skips empty children", () => {
    render(
      <MemoryRouter>
        <PosterGrid label="Результаты поиска">
          <PosterCard card={CARD} layout="grid" />
          {null}
        </PosterGrid>
      </MemoryRouter>,
    );
    const list = screen.getByRole("list", { name: "Результаты поиска" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
  });
});
```

`web/src/ui/States.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { SkeletonGrid, SkeletonGroup, SkeletonHero, SkeletonShelf } from "./Skeleton";
import { EmptyState, ErrorState, ProgressStrip, SyncingNotice } from "./States";

// Copy: android/src/main/java/app/kaeru/ui/common/library/LibraryTabs.kt:73-100 (web-map 6).
const WATCHING_TITLE = "Вы ничего не смотрите";
const WATCHING_TEXT = "Начните любой тайтл — он окажется здесь вместе с серией, на которой вы остановились.";

describe("EmptyState", () => {
  it("shows a title, a text and a link action", async () => {
    render(
      <MemoryRouter>
        <Routes>
          <Route
            path="/"
            element={<EmptyState title={WATCHING_TITLE} text={WATCHING_TEXT} action={{ label: "Найти аниме", to: "/search" }} />}
          />
          <Route path="/search" element={<p>Страница поиска</p>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByRole("heading", { name: WATCHING_TITLE })).toBeInTheDocument();
    expect(screen.getByText(WATCHING_TEXT)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("link", { name: "Найти аниме" }));
    expect(screen.getByText("Страница поиска")).toBeInTheDocument();
  });

  it("can run an action instead of navigating", async () => {
    const onClick = vi.fn();
    render(<EmptyState title={WATCHING_TITLE} action={{ label: "Найти аниме", onClick }} />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Найти аниме" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("has no control without an action", () => {
    render(<EmptyState title={WATCHING_TITLE} />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
  });
});

describe("ErrorState", () => {
  it("announces the message and retries with a secondary button", async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="Нет соединения. Проверьте интернет" onRetry={onRetry} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Нет соединения. Проверьте интернет");
    const retry = screen.getByRole("button", { name: "Повторить" });
    // Retry is never amber (web-map 6, accent discipline).
    expect(retry).toHaveClass("btn--secondary");
    await userEvent.setup().click(retry);
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("offers no retry without a handler", () => {
    render(<ErrorState message="Shikimori недоступен, попробуйте позже" />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("strips and notices", () => {
  it("clamps a progress strip to 0–100 %", () => {
    const { container } = render(
      <>
        <ProgressStrip value={0.25} />
        <ProgressStrip value={1.4} />
        <ProgressStrip value={-1} />
      </>,
    );
    const widths = Array.from(container.querySelectorAll<HTMLElement>(".progress-strip__fill")).map(
      (fill) => fill.style.width,
    );
    expect(widths).toEqual(["25%", "100%", "0%"]);
  });

  it("says the list is syncing", () => {
    render(<SyncingNotice />);
    expect(screen.getByRole("status")).toHaveTextContent("Синхронизируем список с Shikimori…");
  });
});

describe("skeletons", () => {
  it("announce one loading region and hide the blocks", () => {
    const { container } = render(
      <SkeletonGroup>
        <SkeletonHero />
        <SkeletonShelf cards={4} />
        <SkeletonGrid count={6} />
      </SkeletonGroup>,
    );
    const region = screen.getByRole("status");
    expect(region).toHaveAttribute("aria-busy", "true");
    expect(region).toHaveTextContent("Загрузка…");
    expect(container.querySelectorAll(".skeleton-shelf .skeleton-card")).toHaveLength(4);
    expect(container.querySelectorAll(".skeleton-grid .skeleton-card")).toHaveLength(6);
    // Only the label speaks. Every other child of the region is hidden, together with the blocks inside it.
    const shapes = Array.from(region.children).filter((child) => !child.classList.contains("visually-hidden"));
    expect(shapes).toHaveLength(3);
    for (const shape of shapes) expect(shape).toHaveAttribute("aria-hidden", "true");
    // A block inside a shelf or grid is hidden by its wrapper, not only by its own attribute.
    const nested = container.querySelectorAll(".skeleton-shelf .skeleton, .skeleton-grid .skeleton");
    expect(nested).toHaveLength(1 + 4 * 2 + 6 * 2);
    for (const block of nested) {
      expect(block.parentElement?.closest('[aria-hidden="true"]')).not.toBeNull();
    }
  });

  it("take a label of their own", () => {
    render(
      <SkeletonGroup label="Ищем аниме…">
        <SkeletonGrid />
      </SkeletonGroup>,
    );
    expect(screen.getByRole("status")).toHaveTextContent("Ищем аниме…");
  });
});
```

`web/src/ui/Toast.test.tsx`:

```tsx
import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TOAST_MS, TOAST_WITH_ACTION_MS, ToastProvider, useToast } from "./Toast";

// Copy: decision 6 (undo after unmarking) and the Android network error.
const UNMARKED = "Серия 3 отмечена непросмотренной";
const OFFLINE = "Нет соединения. Проверьте интернет";

function Trigger({ onUndo }: { onUndo?: () => void }) {
  const toast = useToast();
  return (
    <>
      <button
        type="button"
        onClick={() => toast.show(UNMARKED, onUndo ? { label: "Отменить", onClick: onUndo } : undefined)}
      >
        Снять отметку
      </button>
      <button type="button" onClick={() => toast.show(OFFLINE)}>
        Ошибка
      </button>
    </>
  );
}

function renderToasts(onUndo?: () => void) {
  return render(
    <ToastProvider>
      <Trigger onUndo={onUndo} />
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("toasts", () => {
  it("speak through a polite live region", () => {
    renderToasts();
    const region = screen.getByRole("status");
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toBeEmptyDOMElement();
    fireEvent.click(screen.getByRole("button", { name: "Ошибка" }));
    expect(region).toHaveTextContent(OFFLINE);
  });

  it("hide a plain toast after 4 seconds", () => {
    renderToasts();
    fireEvent.click(screen.getByRole("button", { name: "Ошибка" }));
    act(() => {
      vi.advanceTimersByTime(TOAST_MS - 1);
    });
    expect(screen.getByText(OFFLINE)).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText(OFFLINE)).toBeNull();
  });

  it("keep a toast with an action for 8 seconds", () => {
    renderToasts(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    act(() => {
      vi.advanceTimersByTime(TOAST_WITH_ACTION_MS - 1);
    });
    expect(screen.getByText(UNMARKED)).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("run the action once and close", () => {
    const onUndo = vi.fn();
    renderToasts(onUndo);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    fireEvent.click(screen.getByRole("button", { name: "Отменить" }));
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("show one at a time, the newest", () => {
    renderToasts(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    fireEvent.click(screen.getByRole("button", { name: "Ошибка" }));
    expect(screen.getByRole("status")).toHaveTextContent(OFFLINE);
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("wait while the viewer is on the toast", () => {
    renderToasts(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    const undo = screen.getByRole("button", { name: "Отменить" });
    act(() => {
      undo.focus();
    });
    act(() => {
      vi.advanceTimersByTime(TOAST_WITH_ACTION_MS * 2);
    });
    expect(screen.getByText(UNMARKED)).toBeInTheDocument();
    act(() => {
      undo.blur();
    });
    act(() => {
      vi.advanceTimersByTime(TOAST_WITH_ACTION_MS);
    });
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("need the provider", () => {
    expect(() => render(<Trigger />)).toThrow(/ToastProvider/);
  });
});
```

`web/src/ui/Dialog.test.tsx`:

```tsx
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Dialog } from "./Dialog";

// Copy: android/src/main/java/app/kaeru/ui/mobile/settings/SettingsScreen.kt:54-59 (web-map 3).
function Harness({ onConfirm = () => undefined }: { onConfirm?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Выйти из аккаунта
      </button>
      <Dialog
        open={open}
        title="Выйти из аккаунта?"
        text="Список и прогресс останутся на Shikimori, локальный кэш будет очищен"
        confirmLabel="Выйти"
        cancelLabel="Отмена"
        destructive
        onConfirm={() => {
          onConfirm();
          setOpen(false);
        }}
        onCancel={() => setOpen(false)}
      />
    </>
  );
}

describe("Dialog", () => {
  it("renders nothing while closed", () => {
    render(<Harness />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("opens as a named, described modal with focus on the safe choice", async () => {
    render(<Harness />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const dialog = screen.getByRole("dialog", { name: "Выйти из аккаунта?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("Список и прогресс останутся на Shikimori, локальный кэш будет очищен");
    expect(within(dialog).getByRole("button", { name: "Отмена" })).toHaveFocus();
    expect(within(dialog).getByRole("button", { name: "Выйти" })).toHaveClass("btn--destructive");
  });

  it("keeps Tab inside the dialog", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const cancel = screen.getByRole("button", { name: "Отмена" });
    const confirm = screen.getByRole("button", { name: "Выйти" });
    await user.tab();
    expect(confirm).toHaveFocus();
    await user.tab();
    expect(cancel).toHaveFocus();
    await user.tab({ shift: true });
    expect(confirm).toHaveFocus();
  });

  it("closes on Escape and gives focus back to the opener", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const opener = screen.getByRole("button", { name: "Выйти из аккаунта" });
    await user.click(opener);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(opener).toHaveFocus();
  });

  it("confirms once", async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    render(<Harness onConfirm={onConfirm} />);
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    await user.click(screen.getByRole("button", { name: "Выйти" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("closes when the backdrop is pressed", async () => {
    render(<Harness />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    fireEvent.mouseDown(screen.getByRole("dialog").parentElement as HTMLElement);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("uses the primary button for a confirm that is not destructive", () => {
    // Decision 6: the completion dialog.
    render(
      <Dialog
        open
        title="Перевести аниме в завершённые?"
        confirmLabel="Завершить просмотр"
        cancelLabel="Позже"
        onConfirm={() => undefined}
        onCancel={() => undefined}
      />,
    );
    const dialog = screen.getByRole("dialog", { name: "Перевести аниме в завершённые?" });
    expect(dialog).not.toHaveAttribute("aria-describedby");
    expect(within(dialog).getByRole("button", { name: "Завершить просмотр" })).toHaveClass("btn--primary");
    expect(within(dialog).getByRole("button", { name: "Позже" })).toHaveFocus();
  });
});
```

- [ ] **Step 2: Run it to see it fail.** From `web/`:

```bash
npx vitest run src/app src/screens src/ui
```

**Expected:** FAIL. The 14 new suites error before running with `Failed to resolve import …`, for example `Failed to resolve import "./bootstrap" from "src/app/bootstrap.test.ts"`, `Failed to resolve import "./Gate" from "src/app/Gate.test.tsx"` and `Failed to resolve import "./Toast" from "src/ui/Toast.test.tsx"`. `src/ui/tokens.test.ts` from Task 1 still passes.

- [ ] **Step 3: Implement**

`web/src/app/bootstrap.ts`:

```ts
/**
 * GitHub Pages has no rewrites. The prelude in docs/cast/404.html sends a deep link such as
 * /anime/1535?x#frag to /?p=%2Fanime%2F1535%3Fx#frag; this puts the path back before the router
 * reads the address. Only a same-site path is accepted: "//host" and "/\host" would leave the site.
 */
export function restoreDeepLink(location: Location, history: History): void {
  const target = new URLSearchParams(location.search).get("p");
  if (target === null || !target.startsWith("/")) return;
  if (target.startsWith("//") || target.startsWith("/\\")) return;
  history.replaceState(null, "", target + location.hash);
}
```

`web/src/app/services.tsx`:

```tsx
import { createContext, useContext, type ReactNode } from "react";
import { createShikimoriHttp } from "../api/http";
import { createShikimori, type Shikimori } from "../api/shikimori";
import { authorized, sessionStore, type SessionStore } from "../auth/session";
import { Library } from "../library/library";
import { progressStore, type ProgressStore } from "../library/progress";

export interface Services {
  shikimori: Shikimori;
  library: Library;
  progress: ProgressStore;
}

/** The real object graph; tests pass a fake fetch or a separate session store. */
export function createServices(
  deps: { fetch?: typeof fetch; store?: SessionStore; progress?: ProgressStore } = {},
): Services {
  const store = deps.store ?? sessionStore;
  const progress = deps.progress ?? progressStore;
  // Pass fetch only when given, so the browser's own fetch keeps its binding.
  const shikimori = createShikimori(createShikimoriHttp(deps.fetch ? { fetch: deps.fetch } : undefined));
  // Writes and the list go through this store's token, whichever store the caller chose.
  const auth: typeof authorized = (call, options) => authorized(call, { ...options, store });
  const library = new Library({
    shikimori,
    authorized: auth,
    accountId: () => {
      const access = store.get();
      return access.kind === "signed_in" ? access.session.account.id : null;
    },
    progress,
  });
  return { shikimori, library, progress };
}

export const ServicesContext = createContext<Services | null>(null);

export function ServicesProvider({ services, children }: { services: Services; children: ReactNode }) {
  return <ServicesContext.Provider value={services}>{children}</ServicesContext.Provider>;
}

export function useServices(): Services {
  const services = useContext(ServicesContext);
  if (services === null) throw new Error("useServices() is used outside <ServicesProvider>");
  return services;
}
```

`web/src/app/Gate.tsx`:

```tsx
import type { ReactNode } from "react";
import { useAccess } from "../auth/session";
import { AccessClosedScreen } from "../screens/AccessClosedScreen";
import { SignInScreen } from "../screens/SignInScreen";

/** Nothing but sign-in until the worker has let this account in (spec §6, §6a). */
export function Gate({ children }: { children: ReactNode }) {
  const access = useAccess();
  if (access.kind === "signed_out") return <SignInScreen message={access.message} />;
  if (access.kind === "closed") return <AccessClosedScreen nickname={access.nickname} />;
  return <>{children}</>;
}
```

`web/src/app/App.tsx`:

```tsx
import { useEffect, useMemo } from "react";
import { BrowserRouter, Navigate, Outlet, Route, Routes } from "react-router-dom";
import { useAccess } from "../auth/session";
import { AuthCallbackScreen } from "../screens/AuthCallbackScreen";
import { HomeScreen } from "../screens/HomeScreen";
import { LibraryScreen } from "../screens/LibraryScreen";
import { SearchScreen } from "../screens/SearchScreen";
import { SettingsScreen } from "../screens/SettingsScreen";
import { TitleScreen } from "../screens/TitleScreen";
import { WatchPlaceholderScreen } from "../screens/WatchPlaceholderScreen";
import { Layout } from "../ui/Layout";
import { ToastProvider } from "../ui/Toast";
import { Gate } from "./Gate";
import { createServices, ServicesProvider, useServices, type Services } from "./services";

export function App({ services }: { services?: Services }) {
  return (
    <BrowserRouter>
      <AppRoutes services={services} />
    </BrowserRouter>
  );
}

/** The route table without a router, so a test can mount it in a MemoryRouter. */
export function AppRoutes({ services }: { services?: Services }) {
  const access = useAccess();
  const accountId = access.kind === "signed_in" ? access.session.account.id : null;
  // One set of services per account: another account must never see the previous list.
  const current = useMemo(() => services ?? createServices(), [services, accountId]);

  return (
    <ServicesProvider services={current}>
      <ToastProvider>
        <Routes>
          {/* The OAuth return is the one page outside the gate: it is the way through it. */}
          <Route path="/auth" element={<AuthCallbackScreen />} />
          <Route
            element={
              <Gate>
                <SignedIn />
              </Gate>
            }
          >
            {/* Full window, no bars: the player takes this route in the next plan. */}
            <Route path="/watch/:id/:episode" element={<WatchPlaceholderScreen />} />
            <Route element={<Layout />}>
              <Route path="/" element={<HomeScreen />} />
              <Route path="/search" element={<SearchScreen />} />
              <Route path="/list" element={<LibraryScreen />} />
              <Route path="/anime/:id" element={<TitleScreen />} />
              <Route path="/settings" element={<SettingsScreen />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Route>
          </Route>
        </Routes>
      </ToastProvider>
    </ServicesProvider>
  );
}

function SignedIn() {
  const { library } = useServices();
  useEffect(() => {
    // Every screen reads the list: start it here unless a screen already has.
    // A failure is kept in library.state(), where the screens show it.
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);
  return <Outlet />;
}
```

`web/src/screens/SignInScreen.tsx`:

```tsx
import { useEffect, useState } from "react";
import { useLocation } from "react-router-dom";
import { beginSignIn } from "../auth/signin";
import { PrimaryButton } from "../ui/Button";

export interface SignInScreenProps {
  /** Why the viewer is here again, e.g. «Сессия истекла, войдите снова». */
  message: string | null;
  begin?: (returnTo: string) => string;
  navigateTo?: (url: string) => void;
}

function openPage(url: string): void {
  window.location.assign(url);
}

/** Without a session there is nothing but this screen (spec §6a). Copy: Android LoginScreen. */
export function SignInScreen({ message, begin = beginSignIn, navigateTo = openPage }: SignInScreenProps) {
  const location = useLocation();
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    // Back from Shikimori through the bfcache: the page is live again, so is the button.
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) setLeaving(false);
    };
    window.addEventListener("pageshow", onPageShow);
    return () => window.removeEventListener("pageshow", onPageShow);
  }, []);

  function signIn() {
    setLeaving(true);
    // After sign-in the viewer lands where they were, deep link included.
    navigateTo(begin(location.pathname + location.search + location.hash));
  }

  return (
    <main className="screen-center">
      <h1 className="t-display">Kaeru</h1>
      <p className="screen-center__text t-body">
        Войдите через Shikimori, чтобы синхронизировать список и просмотренные серии.
      </p>
      <PrimaryButton onClick={signIn} disabled={leaving}>
        Войти через Shikimori
      </PrimaryButton>
      {message ? (
        <p className="screen-center__error t-body" role="alert">
          {message}
        </p>
      ) : null}
    </main>
  );
}
```

`web/src/screens/AccessClosedScreen.tsx`:

```tsx
import { sessionStore } from "../auth/session";
import { SecondaryButton } from "../ui/Button";

export interface AccessClosedScreenProps {
  nickname: string;
  onSignOut?: () => void;
}

/** The account is not on the worker's list: its own nickname and a way out, no catalogue (spec §6a). */
export function AccessClosedScreen({ nickname, onSignOut = () => sessionStore.signOut() }: AccessClosedScreenProps) {
  return (
    <main className="screen-center">
      <h1 className="t-headline">Доступ закрыт</h1>
      <p className="screen-center__nickname t-title">{nickname}</p>
      <p className="screen-center__text t-body">Kaeru для браузера открыт по приглашению</p>
      <p className="screen-center__text t-body">Попросите владельца добавить ваш аккаунт Shikimori в список.</p>
      <SecondaryButton onClick={onSignOut}>Выйти</SecondaryButton>
    </main>
  );
}
```

`web/src/screens/AuthCallbackScreen.tsx`:

```tsx
import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { sessionStore, useAccess } from "../auth/session";
import { beginSignIn, completeSignIn, type SignInResult } from "../auth/signin";
import { PrimaryButton } from "../ui/Button";
import { IndeterminateStrip } from "../ui/States";

const UNKNOWN_ERROR = "Что-то пошло не так. Повторите попытку";

export interface AuthCallbackScreenProps {
  complete?: (search: string) => Promise<SignInResult>;
  begin?: (returnTo: string) => string;
  navigateTo?: (url: string) => void;
}

function openPage(url: string): void {
  window.location.assign(url);
}

/** /auth: Shikimori sends the browser back here with ?code&state, and a code works only once. */
export function AuthCallbackScreen({
  complete = completeSignIn,
  begin = beginSignIn,
  navigateTo = openPage,
}: AuthCallbackScreenProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const access = useAccess();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    // Once per visit, StrictMode's second effect run included.
    if (started.current) return;
    started.current = true;
    const search = location.search;
    // A reload must never resend a spent code, so the address loses it first.
    if (search !== "") navigate(location.pathname, { replace: true });
    complete(search).then(
      (result) => {
        if (!mounted.current) return;
        if (result.kind === "done") {
          navigate(result.returnTo, { replace: true });
        } else if (result.kind === "closed") {
          // completeSignIn records it too; setting it here keeps the gate right whatever the order.
          sessionStore.setClosed(result.nickname);
          navigate("/", { replace: true });
        } else {
          setError(result.message);
        }
      },
      () => {
        if (mounted.current) setError(UNKNOWN_ERROR);
      },
    );
  }, [complete, location, navigate]);

  function retry() {
    // Signed out: a fresh authorization, since the old code is spent. Signed in: nothing to redo.
    if (access.kind === "signed_out") navigateTo(begin("/"));
    else navigate("/", { replace: true });
  }

  if (error === null) {
    return (
      <main className="screen-center">
        <p className="t-title" role="status">
          Проверяем код…
        </p>
        <IndeterminateStrip />
      </main>
    );
  }

  return (
    <main className="screen-center">
      <h1 className="t-display">Kaeru</h1>
      <p className="screen-center__error t-body" role="alert">
        {error}
      </p>
      <PrimaryButton onClick={retry}>Войти ещё раз</PrimaryButton>
    </main>
  );
}
```

`web/src/screens/WatchPlaceholderScreen.tsx`:

```tsx
import { useParams } from "react-router-dom";
import { episodeBadge } from "../domain/format";
import { SecondaryButton } from "../ui/Button";
import { IconBack } from "../ui/icons";

/** Where the watch button leads until the player exists (decision 9). */
export function WatchPlaceholderScreen() {
  const { id = "", episode = "" } = useParams();
  const number = Number(episode);
  return (
    <main className="screen-center">
      {Number.isInteger(number) && number > 0 ? (
        <p className="screen-center__eyebrow t-label">{episodeBadge(number)}</p>
      ) : null}
      <h1 className="t-headline">Плеер появится в следующем обновлении</h1>
      <SecondaryButton to={`/anime/${id}`} icon={<IconBack />}>
        Назад к тайтлу
      </SecondaryButton>
    </main>
  );
}
```

Route stubs. Each file below is overwritten wholesale by its screen's task, which keeps the export name.

`web/src/screens/HomeScreen.tsx`:

```tsx
/** Route target for "/" until the home screen is written (Task 9 replaces this file). */
export function HomeScreen() {
  return <h1 className="page-title t-headline">Главная</h1>;
}
```

`web/src/screens/SearchScreen.tsx`:

```tsx
/** Route target for "/search" until the search screen is written (Task 11 replaces this file). */
export function SearchScreen() {
  return <h1 className="page-title t-headline">Поиск</h1>;
}
```

`web/src/screens/LibraryScreen.tsx`:

```tsx
/** Route target for "/list" until My list is written (Task 11 replaces this file). */
export function LibraryScreen() {
  return <h1 className="page-title t-headline">Мой список</h1>;
}
```

`web/src/screens/TitleScreen.tsx`:

```tsx
/** Route target for "/anime/:id" until the title screen is written (Task 10 replaces this file). */
export function TitleScreen() {
  return <h1 className="page-title t-headline">Аниме</h1>;
}
```

`web/src/screens/SettingsScreen.tsx`:

```tsx
/** Route target for "/settings" until settings are written (Task 11 replaces this file). */
export function SettingsScreen() {
  return <h1 className="page-title t-headline">Настройки</h1>;
}
```

`web/src/ui/icons.tsx`:

```tsx
import type { SVGProps } from "react";

export type IconProps = Omit<SVGProps<SVGSVGElement>, "children"> & { size?: number };

// Material Icons paths (Apache 2.0), the set Android draws from; inline, so the page loads no icon font.
// Every glyph is hidden from screen readers: the control around it carries the name.
function icon(path: string) {
  return function Icon({ size = 24, ...rest }: IconProps) {
    return (
      <svg
        viewBox="0 0 24 24"
        width={size}
        height={size}
        fill="currentColor"
        aria-hidden="true"
        focusable="false"
        {...rest}
      >
        <path d={path} />
      </svg>
    );
  };
}

export const IconHome = icon("M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z");
export const IconSearch = icon(
  "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.910 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
);
export const IconLibrary = icon(
  "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z",
);
export const IconPlay = icon("M8 5v14l11-7z");
export const IconPlayCircle = icon(
  "M10 16.5l6-4.5-6-4.5v9zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
);
export const IconCheckCircle = icon(
  "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
);
export const IconCheck = icon("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
export const IconClock = icon(
  "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
);
export const IconChevronRight = icon("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z");
export const IconArrowDropDown = icon("M7 10l5 5 5-5z");
export const IconClose = icon(
  "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
);
export const IconBack = icon("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z");
export const IconMore = icon(
  "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
);
export const IconPerson = icon(
  "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 4c1.93 0 3.5 1.57 3.5 3.5S13.93 13 12 13s-3.5-1.57-3.5-3.5S10.07 6 12 6zm0 14c-2.03 0-4.43-.82-6.14-2.88C7.55 15.8 9.68 15 12 15s4.45.8 6.14 2.12C16.43 19.18 14.03 20 12 20z",
);
```

`web/src/ui/Button.tsx`:

```tsx
import type { ButtonHTMLAttributes, MouseEventHandler, ReactNode } from "react";
import { Link } from "react-router-dom";
import { IconChevronRight } from "./icons";

interface CommonProps {
  children: ReactNode;
  /** A leading glyph from icons.tsx: 20px on buttons, 18px on text actions. */
  icon?: ReactNode;
  className?: string;
  /** Fills the row; its focus ring then skips the 1.06 scale, having nowhere to grow. */
  fullWidth?: boolean;
  /** 48px high with 8px padding, for the button under a grid card. */
  compact?: boolean;
  /** Text actions: a trailing chevron, as in Android's «Всё ›». */
  chevron?: boolean;
}

export type ButtonAsButton = CommonProps &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "className"> & { to?: undefined };

export type ButtonAsLink = CommonProps & {
  /** Navigation is a link, never a button that navigates. */
  to: string;
  replace?: boolean;
  onClick?: MouseEventHandler<HTMLAnchorElement>;
  "aria-label"?: string;
};

export type ButtonProps = ButtonAsButton | ButtonAsLink;

type Variant = "primary" | "secondary" | "text" | "destructive";

function isLink(props: ButtonProps): props is ButtonAsLink {
  return typeof props.to === "string";
}

function classNames(variant: Variant, props: CommonProps): string {
  return ["btn", `btn--${variant}`, props.fullWidth ? "btn--full" : "", props.compact ? "btn--compact" : "", props.className ?? ""]
    .filter((name) => name !== "")
    .join(" ");
}

function Action({ variant, props }: { variant: Variant; props: ButtonProps }) {
  const content = (
    <>
      {props.icon ? (
        <span className="btn__icon" aria-hidden="true">
          {props.icon}
        </span>
      ) : null}
      <span className="btn__label">{props.children}</span>
      {props.chevron ? <IconChevronRight className="btn__chevron" size={18} /> : null}
    </>
  );

  if (isLink(props)) {
    return (
      <Link
        to={props.to}
        replace={props.replace}
        onClick={props.onClick}
        aria-label={props["aria-label"]}
        className={classNames(variant, props)}
      >
        {content}
      </Link>
    );
  }

  const {
    children: _children,
    icon: _icon,
    className: _className,
    fullWidth: _fullWidth,
    compact: _compact,
    chevron: _chevron,
    to: _to,
    type,
    ...rest
  } = props;
  // type="button" by default: a button inside a form must never submit it by accident.
  return (
    <button type={type ?? "button"} className={classNames(variant, props)} {...rest}>
      {content}
    </button>
  );
}

/** The one amber action per view: dark text on amber, never white (2:1 contrast). */
export function PrimaryButton(props: ButtonProps) {
  return <Action variant="primary" props={props} />;
}

export function SecondaryButton(props: ButtonProps) {
  return <Action variant="secondary" props={props} />;
}

/** Quiet ink-soft action, never amber. */
export function TextAction(props: ButtonProps) {
  return <Action variant="text" props={props} />;
}

export function DestructiveButton(props: ButtonProps) {
  return <Action variant="destructive" props={props} />;
}

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "aria-label"> {
  /** The accessible name; the glyph itself is hidden from screen readers. */
  label: string;
  icon: ReactNode;
  /** Over artwork: a 42% black disc keeps the glyph readable on any poster. */
  overArt?: boolean;
}

export function IconButton({ label, icon, overArt = false, className, type, ...rest }: IconButtonProps) {
  const names = ["icon-button", overArt ? "icon-button--over-art" : "", className ?? ""]
    .filter((name) => name !== "")
    .join(" ");
  return (
    <button type={type ?? "button"} aria-label={label} title={label} className={names} {...rest}>
      {icon}
    </button>
  );
}
```

`web/src/ui/Pill.tsx`:

```tsx
import { useRef, type ButtonHTMLAttributes, type KeyboardEvent, type ReactNode } from "react";

export interface PillProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "className"> {
  /** Amber with dark text: only for the one pill the viewer is looking at. */
  selected?: boolean;
  /** A trailing glyph, e.g. IconArrowDropDown on a pill that opens a menu. */
  trailing?: ReactNode;
  className?: string;
}

export function Pill({ selected = false, trailing, className, type, children, ...rest }: PillProps) {
  const names = ["pill", selected ? "pill--selected" : "", className ?? ""].filter((name) => name !== "").join(" ");
  return (
    <button type={type ?? "button"} className={names} {...rest}>
      <span className="pill__label">{children}</span>
      {trailing ? (
        <span className="pill__trailing" aria-hidden="true">
          {trailing}
        </span>
      ) : null}
    </button>
  );
}

export interface PillOption<T extends string | number> {
  value: T;
  label: string;
}

export interface PillGroupProps<T extends string | number> {
  /** "radio": a radiogroup (season chips); "tab": a tablist (My list statuses). */
  kind: "radio" | "tab";
  /** The group's accessible name. */
  label: string;
  options: readonly PillOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Tabs: the id of the panel they control. */
  panelId?: string;
  /** Tabs: each tab gets the id `${idPrefix}-${value}`, so the panel can name its tab. */
  idPrefix?: string;
  className?: string;
}

/** A row of pills with one tab stop; arrows move the choice (WAI-ARIA radio group and tabs). */
export function PillGroup<T extends string | number>({
  kind,
  label,
  options,
  value,
  onChange,
  panelId,
  idPrefix,
  className,
}: PillGroupProps<T>) {
  const ref = useRef<HTMLDivElement>(null);
  const selectedIndex = options.findIndex((option) => option.value === value);
  const tabStop = selectedIndex >= 0 ? selectedIndex : 0;

  function select(index: number) {
    const count = options.length;
    const next = ((index % count) + count) % count;
    onChange(options[next].value);
    ref.current?.querySelectorAll<HTMLButtonElement>("button")[next]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (options.length === 0) return;
    const buttons = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>("button") ?? []);
    const focused = buttons.findIndex((button) => button === document.activeElement);
    const from = focused >= 0 ? focused : tabStop;
    const vertical = kind === "radio";
    if (event.key === "ArrowRight" || (vertical && event.key === "ArrowDown")) select(from + 1);
    else if (event.key === "ArrowLeft" || (vertical && event.key === "ArrowUp")) select(from - 1);
    else if (event.key === "Home") select(0);
    else if (event.key === "End") select(options.length - 1);
    else return;
    event.preventDefault();
  }

  return (
    <div
      ref={ref}
      role={kind === "radio" ? "radiogroup" : "tablist"}
      aria-label={label}
      className={["pill-group", className ?? ""].filter((name) => name !== "").join(" ")}
      onKeyDown={onKeyDown}
    >
      {options.map((option, index) => {
        const selected = option.value === value;
        return (
          <Pill
            key={String(option.value)}
            id={idPrefix ? `${idPrefix}-${String(option.value)}` : undefined}
            role={kind === "radio" ? "radio" : "tab"}
            aria-checked={kind === "radio" ? selected : undefined}
            aria-selected={kind === "tab" ? selected : undefined}
            aria-controls={kind === "tab" ? panelId : undefined}
            tabIndex={index === tabStop ? 0 : -1}
            selected={selected}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </Pill>
        );
      })}
    </div>
  );
}
```

`web/src/ui/PosterCard.tsx`:

```tsx
import { Children, isValidElement, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import type { Card } from "../domain/feed";
import { ProgressStrip } from "./States";

export interface PosterCardProps {
  card: Card;
  /** "row": the fixed poster width of a shelf; "grid": fills its grid cell. */
  layout?: "row" | "grid";
  /** Where the card leads; the title page by default. */
  to?: string;
  /** A control under the card (Search's «В планы»), kept outside the link as its own tab stop. */
  footer?: ReactNode;
}

/** The fallback artwork: the title's first letter, upper-cased the Russian way (ё → Ё). */
export function posterLetter(title: string): string {
  const first = Array.from(title.trim())[0] ?? "";
  return first.toLocaleUpperCase("ru");
}

export function PosterCard({ card, layout = "row", to, footer }: PosterCardProps) {
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const poster = failed ? null : card.posterUrl;

  return (
    <div className={`poster-card poster-card--${layout}`}>
      <Link to={to ?? `/anime/${card.animeId}`} className="poster-card__link">
        <span className="poster-card__art">
          {poster !== null ? (
            // The title sits beside the artwork, so the image is decorative.
            <img
              className="poster-card__img"
              src={poster}
              alt=""
              loading="lazy"
              decoding="async"
              data-loaded={loaded}
              onLoad={() => setLoaded(true)}
              onError={() => setFailed(true)}
            />
          ) : (
            <span className="poster-card__letter" aria-hidden="true">
              {posterLetter(card.title)}
            </span>
          )}
          {card.badge ? <span className="poster-card__badge t-label">{card.badge}</span> : null}
          {card.progress !== null && card.progress > 0 ? <ProgressStrip value={card.progress} /> : null}
        </span>
        <span className="poster-card__title t-title-sm">{card.title}</span>
        {card.subtitle ? <span className="poster-card__subtitle t-label">{card.subtitle}</span> : null}
      </Link>
      {footer ? <div className="poster-card__footer">{footer}</div> : null}
    </div>
  );
}

/** A responsive poster grid: minmax(--grid-min, 1fr), two title lines reserved by the cards. */
export function PosterGrid({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <ul className="poster-grid" aria-label={label}>
      {Children.map(children, (child) => (isValidElement(child) ? <li className="poster-grid__item">{child}</li> : null))}
    </ul>
  );
}
```

`web/src/ui/Shelf.tsx`:

```tsx
import { Children, isValidElement, useId, type ReactNode } from "react";
import { TextAction } from "./Button";

export interface ShelfProps {
  title: string;
  /** A quiet «Всё ›» link: ink-soft, never amber. */
  action?: { label: string; to: string };
  /** Controls between the header and the row, e.g. the season chips. */
  extra?: ReactNode;
  children: ReactNode;
}

/** A titled horizontal row of cards; the cards are links, so the row is reachable by keyboard. */
export function Shelf({ title, action, extra, children }: ShelfProps) {
  const headingId = useId();
  return (
    <section className="shelf" aria-labelledby={headingId}>
      <div className="shelf__header">
        <h2 id={headingId} className="shelf__title t-title">
          {title}
        </h2>
        {action ? (
          <TextAction to={action.to} chevron>
            {action.label}
          </TextAction>
        ) : null}
      </div>
      {extra ? <div className="shelf__extra">{extra}</div> : null}
      <ul className="shelf__row">
        {Children.map(children, (child) => (isValidElement(child) ? <li className="shelf__item">{child}</li> : null))}
      </ul>
    </section>
  );
}
```

`web/src/ui/Skeleton.tsx`:

```tsx
import type { CSSProperties, ReactNode } from "react";

interface BlockProps {
  className?: string;
  width?: CSSProperties["width"];
  height?: CSSProperties["height"];
  radius?: CSSProperties["borderRadius"];
}

/** A pulsing placeholder block in the exact shape of what is loading; never a spinner. */
export function SkeletonBlock({ className, width, height, radius }: BlockProps) {
  return (
    <span
      className={className ? `skeleton ${className}` : "skeleton"}
      style={{ width, height, borderRadius: radius }}
      aria-hidden="true"
    />
  );
}

/** One announced loading region; the blocks inside stay hidden from screen readers. */
export function SkeletonGroup({ label = "Загрузка…", children }: { label?: string; children: ReactNode }) {
  return (
    <div className="skeleton-group" role="status" aria-busy="true">
      <span className="visually-hidden">{label}</span>
      {children}
    </div>
  );
}

export function SkeletonHero() {
  return <SkeletonBlock className="skeleton--hero" radius={0} />;
}

function PosterBlock({ row }: { row: boolean }) {
  return (
    <div className={row ? "skeleton-card skeleton-card--row" : "skeleton-card"}>
      <SkeletonBlock className="skeleton-card__art" />
      <SkeletonBlock height={16} width="80%" />
    </div>
  );
}

export function SkeletonShelf({ cards = 6 }: { cards?: number }) {
  return (
    <div className="skeleton-shelf" aria-hidden="true">
      <SkeletonBlock className="skeleton-shelf__title" width="40%" height={24} />
      <div className="skeleton-shelf__row">
        {Array.from({ length: cards }, (_, index) => (
          <PosterBlock key={index} row />
        ))}
      </div>
    </div>
  );
}

export function SkeletonGrid({ count = 9 }: { count?: number }) {
  return (
    <div className="skeleton-grid poster-grid" aria-hidden="true">
      {Array.from({ length: count }, (_, index) => (
        <PosterBlock key={index} row={false} />
      ))}
    </div>
  );
}
```

`web/src/ui/States.tsx`:

```tsx
import { PrimaryButton, SecondaryButton } from "./Button";

export interface StateAction {
  label: string;
  /** A destination makes the action a link; otherwise onClick runs. */
  to?: string;
  onClick?: () => void;
}

type Align = "center" | "start";

/** Title, one line of help, at most one amber action (Android States.kt). */
export function EmptyState({
  title,
  text,
  action,
  align = "center",
}: {
  title: string;
  text?: string;
  action?: StateAction;
  align?: Align;
}) {
  return (
    <div className={`state state--${align}`}>
      <h2 className="state__title t-headline">{title}</h2>
      {text ? <p className="state__text t-body">{text}</p> : null}
      {action ? (
        <div className="state__action">
          {action.to !== undefined ? (
            <PrimaryButton to={action.to}>{action.label}</PrimaryButton>
          ) : (
            <PrimaryButton onClick={action.onClick}>{action.label}</PrimaryButton>
          )}
        </div>
      ) : null}
    </div>
  );
}

/** The message and «Повторить» as a secondary button: retry is never amber. */
export function ErrorState({ message, onRetry, align = "center" }: { message: string; onRetry?: () => void; align?: Align }) {
  return (
    <div className={`state state--${align}`} role="alert">
      <p className="state__message t-body-lg">{message}</p>
      {onRetry ? (
        <div className="state__action">
          <SecondaryButton onClick={onRetry}>Повторить</SecondaryButton>
        </div>
      ) : null}
    </div>
  );
}

/** A 4px amber strip; decorative, since the text beside it carries the numbers. */
export function ProgressStrip({ value }: { value: number }) {
  const percent = Math.round(Math.min(1, Math.max(0, value)) * 1000) / 10;
  return (
    <span className="progress-strip" aria-hidden="true">
      <span className="progress-strip__fill" style={{ width: `${percent}%` }} />
    </span>
  );
}

/** A 35% segment sweeping back and forth: work of unknown length. */
export function IndeterminateStrip({ label }: { label?: string }) {
  return label ? (
    <span className="indeterminate" role="progressbar" aria-label={label} />
  ) : (
    <span className="indeterminate" aria-hidden="true" />
  );
}

/** The first sync, so an empty list is never claimed while it is still being fetched. */
export function SyncingNotice({ text = "Синхронизируем список с Shikimori…" }: { text?: string }) {
  return (
    <div className="syncing" role="status">
      <p className="t-body">{text}</p>
      <IndeterminateStrip />
    </div>
  );
}
```

`web/src/ui/Toast.tsx`:

```tsx
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastApi {
  show: (text: string, action?: ToastAction) => void;
  dismiss: () => void;
}

export const TOAST_MS = 4000;
/** Long enough to reach «Отменить»; the timer also waits while the toast is hovered or focused. */
export const TOAST_WITH_ACTION_MS = 8000;

interface Shown {
  id: number;
  text: string;
  action: ToastAction | undefined;
}

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [shown, setShown] = useState<Shown | null>(null);
  const [paused, setPaused] = useState(false);
  const nextId = useRef(0);

  const show = useCallback((text: string, action?: ToastAction) => {
    nextId.current += 1;
    // A removed toast fires no blur, so a new one never inherits the pause.
    setPaused(false);
    setShown({ id: nextId.current, text, action });
  }, []);

  const dismiss = useCallback(() => {
    setPaused(false);
    setShown(null);
  }, []);

  useEffect(() => {
    if (shown === null || paused) return;
    const id = shown.id;
    const timer = setTimeout(
      () => setShown((current) => (current?.id === id ? null : current)),
      shown.action ? TOAST_WITH_ACTION_MS : TOAST_MS,
    );
    return () => clearTimeout(timer);
  }, [shown, paused]);

  const api = useMemo<ToastApi>(() => ({ show, dismiss }), [show, dismiss]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      {/* Always mounted, so screen readers hear what is put into it. */}
      <div className="toast-region" role="status" aria-live="polite">
        {shown ? (
          <div
            key={shown.id}
            className="toast"
            onMouseEnter={() => setPaused(true)}
            onMouseLeave={() => setPaused(false)}
            onFocus={() => setPaused(true)}
            onBlur={() => setPaused(false)}
          >
            <span className="toast__text t-body">{shown.text}</span>
            {shown.action ? (
              <button
                type="button"
                className="btn btn--text toast__action"
                onClick={() => {
                  const action = shown.action;
                  dismiss();
                  action?.onClick();
                }}
              >
                {shown.action.label}
              </button>
            ) : null}
          </div>
        ) : null}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (api === null) throw new Error("useToast() is used outside <ToastProvider>");
  return api;
}
```

`web/src/ui/Dialog.tsx`:

```tsx
import { useEffect, useId, useRef, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";

export interface DialogProps {
  open: boolean;
  title: string;
  text?: string;
  confirmLabel: string;
  cancelLabel: string;
  /** Red confirm with dark text (sign-out); otherwise the amber primary. */
  destructive?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

const FOCUSABLE =
  'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';

/** A modal confirmation: focus is trapped inside, Escape and the backdrop cancel, focus returns after. */
export function Dialog({
  open,
  title,
  text,
  confirmLabel,
  cancelLabel,
  destructive = false,
  onConfirm,
  onCancel,
}: DialogProps) {
  const titleId = useId();
  const textId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const overflow = document.body.style.overflow;
    // The page behind a modal must not scroll under it.
    document.body.style.overflow = "hidden";
    // Start on the choice that changes nothing.
    cancelRef.current?.focus();
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, [open]);

  if (!open) return null;

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onCancel();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = Array.from(panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return createPortal(
    <div
      className="dialog-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <div
        ref={panelRef}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={text ? textId : undefined}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId} className="dialog__title t-title">
          {title}
        </h2>
        {text ? (
          <p id={textId} className="dialog__text t-body">
            {text}
          </p>
        ) : null}
        <div className="dialog__actions">
          <button ref={cancelRef} type="button" className="btn btn--secondary" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button type="button" className={destructive ? "btn btn--destructive" : "btn btn--primary"} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
```

`web/src/ui/Layout.tsx`:

```tsx
import { useEffect, useId, type ReactNode } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigationType } from "react-router-dom";
import type { Account } from "../api/shikimori";
import { useAccess } from "../auth/session";
import { IconHome, IconLibrary, IconPerson, IconSearch } from "./icons";

function Avatar({ account }: { account: Account | null }) {
  if (account?.avatar) return <img className="avatar" src={account.avatar} alt="" width={30} height={30} />;
  return (
    <span className="avatar avatar--empty" aria-hidden="true">
      <IconPerson size={30} />
    </span>
  );
}

/**
 * The signed-in frame. From 768px: a sidebar with Главная and Поиск, then «Библиотека» → Мой список,
 * and the account row at the bottom. Narrower: a top bar with the account link and a bottom tab bar
 * in Android's order. This top bar is the app's only one: Home draws no bar of its own over the hero.
 * CSS shows one of the two sets, so only one is ever exposed.
 */
export function Layout({ children }: { children?: ReactNode }) {
  const access = useAccess();
  const account = access.kind === "signed_in" ? access.session.account : null;
  const libraryId = useId();
  const { pathname } = useLocation();
  const navigationType = useNavigationType();

  useEffect(() => {
    // A new page starts at the top; back and forward keep the browser's position.
    if (navigationType === "POP") return;
    (document.scrollingElement ?? document.documentElement).scrollTop = 0;
  }, [pathname, navigationType]);

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Перейти к содержимому
      </a>
      <aside className="sidebar">
        <span className="wordmark t-title">Kaeru</span>
        <nav className="sidebar__nav" aria-label="Разделы">
          <NavLink to="/" end className="nav-item t-title-sm">
            <IconHome size={22} />
            <span>Главная</span>
          </NavLink>
          <NavLink to="/search" className="nav-item t-title-sm">
            <IconSearch size={22} />
            <span>Поиск</span>
          </NavLink>
          <div className="nav-group" role="group" aria-labelledby={libraryId}>
            <span id={libraryId} className="nav-group__label t-label-sm">
              Библиотека
            </span>
            <NavLink to="/list" className="nav-item t-title-sm">
              <IconLibrary size={22} />
              <span>Мой список</span>
            </NavLink>
          </div>
        </nav>
        <Link to="/settings" className="account-row" aria-label="Аккаунт и настройки">
          <Avatar account={account} />
          <span className="account-row__name t-title-sm">{account?.nickname ?? "Гость"}</span>
        </Link>
      </aside>
      <header className="topbar">
        <span className="wordmark t-title">Kaeru</span>
        <Link to="/settings" className="topbar__account" aria-label="Аккаунт и настройки">
          <Avatar account={account} />
        </Link>
      </header>
      <main id="main" className="main" tabIndex={-1}>
        {children ?? <Outlet />}
      </main>
      <nav className="tabbar" aria-label="Разделы">
        <NavLink to="/" end className="tab-item">
          <span className="tab-item__icon">
            <IconHome />
          </span>
          <span className="tab-item__label">Главная</span>
        </NavLink>
        <NavLink to="/list" className="tab-item">
          <span className="tab-item__icon">
            <IconLibrary />
          </span>
          <span className="tab-item__label">Мой список</span>
        </NavLink>
        <NavLink to="/search" className="tab-item">
          <span className="tab-item__icon">
            <IconSearch />
          </span>
          <span className="tab-item__label">Поиск</span>
        </NavLink>
      </nav>
    </div>
  );
}
```

`web/src/ui/components.css`:

```css
/* Kaeru components. Values come from tokens.css; the rules follow the Android design system
   (web-map 6). Amber is spent only on the one watch action, progress, the selected pill or tab,
   and the focus ring. Controls filled with amber ring in ink instead. */

/* ---------- Buttons (Buttons.kt) ---------- */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--s2);
  min-height: var(--button-h);
  padding: 0 var(--s6);
  border: 1px solid transparent;
  border-radius: var(--r-card);
  font-size: 17px;
  line-height: 24px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
  transition:
    transform var(--fast) ease-out,
    background-color var(--fast) ease-out,
    color var(--fast) ease-out;
}

.btn__icon {
  display: inline-flex;
}

.btn__icon svg {
  width: 20px;
  height: 20px;
}

.btn--primary {
  background: var(--accent);
  color: var(--on-accent);
}

.btn--secondary {
  background: var(--secondary-bg);
  border-color: var(--line);
  color: var(--ink);
}

.btn--secondary:hover:not(:disabled) {
  background: var(--elevated);
}

.btn--destructive {
  background: var(--error);
  color: var(--bg);
}

.btn--text {
  min-height: var(--touch);
  padding: 0 var(--s3);
  background: transparent;
  color: var(--ink-soft);
  font-size: 15px;
  line-height: 21px;
}

.btn--text:hover:not(:disabled) {
  color: var(--ink);
}

.btn--text .btn__icon svg,
.btn__chevron {
  width: 18px;
  height: 18px;
}

.btn--compact {
  min-height: var(--touch);
  padding: 0 var(--s2);
  font-size: 15px;
  line-height: 21px;
}

.btn--full {
  display: flex;
  width: 100%;
}

.btn:disabled {
  background: var(--elevated);
  border-color: transparent;
  color: var(--ink-soft);
  cursor: default;
}

.btn--text:disabled {
  background: transparent;
}

.btn:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: 2px;
  transform: scale(1.06);
}

.btn--full:focus-visible {
  transform: none;
}

.btn--primary:focus-visible,
.btn--destructive:focus-visible {
  outline-color: var(--ink);
}

.icon-button {
  display: inline-grid;
  place-items: center;
  width: var(--touch);
  height: var(--touch);
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--ink);
  cursor: pointer;
  transition: transform var(--fast) ease-out;
}

.icon-button svg {
  width: 22px;
  height: 22px;
}

.icon-button--over-art {
  background: var(--disc);
}

.icon-button:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: 2px;
  transform: scale(1.06);
}

/* ---------- Pills (Chips.kt) ---------- */
.pill {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: var(--s1);
  min-height: var(--touch);
  padding: 0 var(--s4);
  border: 0;
  border-radius: var(--r-chip);
  background: var(--elevated);
  color: var(--ink);
  font-size: 15px;
  line-height: 21px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition:
    transform var(--fast) ease-out,
    background-color var(--fast) ease-out;
}

.pill__trailing {
  display: inline-flex;
  margin-right: calc(-1 * var(--s1));
}

.pill__trailing svg {
  width: 20px;
  height: 20px;
}

.pill--selected {
  background: var(--accent);
  color: var(--on-accent);
}

.pill:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: 2px;
  transform: scale(1.06);
}

.pill--selected:focus-visible {
  outline-color: var(--ink);
}

.pill-group {
  display: flex;
  gap: var(--s2);
  /* Room for the ring and the 1.06 scale inside the scroller. */
  padding: var(--s2) 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.pill-group::-webkit-scrollbar {
  display: none;
}

@media (min-width: 768px) and (pointer: fine) {
  .pill {
    min-height: 40px;
  }
}

/* ---------- Poster card (PosterCard.kt) ---------- */
.poster-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.poster-card--row {
  width: var(--poster-w);
}

.poster-card__link {
  display: flex;
  flex-direction: column;
  border-radius: var(--r-card);
  color: inherit;
  text-decoration: none;
}

/* The ring moves onto the artwork, which also takes the 1.06 scale. */
.poster-card__link:focus-visible {
  outline: none;
}

.poster-card__link:focus-visible .poster-card__art {
  outline: 3px solid var(--accent);
  outline-offset: 2px;
  transform: scale(1.06);
}

.poster-card__art {
  position: relative;
  display: block;
  aspect-ratio: 2 / 3;
  overflow: hidden;
  border-radius: var(--r-card);
  background: var(--elevated);
  transition: transform var(--fast) ease-out;
}

.poster-card__img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity var(--normal) ease-out;
}

.poster-card__img[data-loaded="true"] {
  opacity: 1;
}

.poster-card__letter {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--ink-soft);
  font-size: 24px;
  line-height: 34px;
  font-weight: 700;
}

.poster-card__badge {
  position: absolute;
  left: var(--s2);
  bottom: var(--s2);
  padding: 3px var(--s2);
  border-radius: var(--r-chip);
  background: var(--badge-bg);
  color: var(--ink);
}

.poster-card__art .progress-strip {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
}

.poster-card__art:has(.progress-strip) .poster-card__badge {
  bottom: calc(var(--s2) + 4px);
}

.poster-card__title {
  display: -webkit-box;
  /* Two lines reserved, so a row of cards keeps one baseline. */
  min-height: 42px;
  margin-top: var(--s2);
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.poster-card__subtitle {
  margin-top: 2px;
  overflow: hidden;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.poster-card__footer {
  margin-top: var(--s2);
}

.poster-card__footer .btn {
  width: 100%;
}

.poster-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--grid-min), 1fr));
  gap: 16px 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.poster-grid__item {
  min-width: 0;
}

@media (min-width: 768px) {
  .poster-grid {
    gap: 28px 18px;
  }
}

/* ---------- Shelf (RowHeader.kt, HomeScreen.kt row rhythm) ---------- */
.shelf {
  margin-top: var(--shelf-gap);
}

.shelf__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  min-height: var(--touch);
  padding: 0 var(--gutter);
}

.shelf__title {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.shelf__header .btn--text {
  margin-right: calc(-1 * var(--s3));
}

.shelf__extra {
  padding: var(--s2) var(--gutter) 0;
}

.shelf__row {
  display: flex;
  gap: var(--card-gap);
  margin: 0;
  padding: var(--s3) var(--gutter) var(--s2);
  list-style: none;
  overflow-x: auto;
  overscroll-behavior-x: contain;
  scroll-snap-type: x proximity;
  scroll-padding-inline: var(--gutter);
  scrollbar-width: none;
}

.shelf__row::-webkit-scrollbar {
  display: none;
}

.shelf__item {
  flex: none;
  scroll-snap-align: start;
}

/* ---------- Progress (ProgressStrip.kt) ---------- */
.progress-strip {
  display: block;
  height: 4px;
  overflow: hidden;
  background: var(--track);
}

.progress-strip__fill {
  display: block;
  height: 100%;
  background: var(--accent);
}

.indeterminate {
  position: relative;
  display: block;
  width: var(--poster-w);
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--track);
}

.indeterminate::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 35%;
  background: var(--accent);
  animation: kaeru-sweep 1400ms ease-in-out infinite alternate;
}

@keyframes kaeru-sweep {
  from {
    left: 0;
  }
  to {
    left: 65%;
  }
}

/* ---------- Skeletons (Skeletons.kt) ---------- */
.skeleton {
  display: block;
  border-radius: var(--r-card);
  background: var(--skeleton-low);
  animation: kaeru-pulse 700ms ease-in-out infinite alternate;
}

@keyframes kaeru-pulse {
  from {
    background-color: var(--skeleton-low);
  }
  to {
    background-color: var(--skeleton-high);
  }
}

.skeleton--hero {
  width: 100%;
  height: max(45vh, 320px);
}

.skeleton-shelf {
  margin-top: var(--shelf-gap);
}

.skeleton-shelf__title {
  margin: 0 var(--gutter);
}

.skeleton-shelf__row {
  display: flex;
  gap: var(--card-gap);
  padding: var(--s3) var(--gutter) 0;
  overflow: hidden;
}

.skeleton-card {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}

.skeleton-card--row {
  flex: none;
  width: var(--poster-w);
}

.skeleton-card__art {
  aspect-ratio: 2 / 3;
}

@media (min-width: 768px) {
  .skeleton--hero {
    height: max(40vh, 320px);
  }
}

/* ---------- States (States.kt) ---------- */
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--s8);
  text-align: center;
}

.state--start {
  align-items: flex-start;
  padding: var(--s6) var(--gutter);
  text-align: left;
}

.state__text {
  max-width: 320px;
  margin-top: var(--s3);
  color: var(--ink-soft);
}

.state__message {
  max-width: 420px;
  color: var(--ink);
}

.state__action {
  margin-top: var(--s6);
}

.syncing {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--s4);
  padding: var(--s8) var(--gutter);
  color: var(--ink-soft);
  text-align: center;
}

/* ---------- Toast ---------- */
.toast-region {
  position: fixed;
  bottom: calc(var(--tabbar-h) + var(--s4) + env(safe-area-inset-bottom));
  left: 50%;
  z-index: 60;
  width: min(560px, calc(100vw - 2 * var(--gutter)));
  transform: translateX(-50%);
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: center;
  gap: var(--s3);
  min-height: var(--touch);
  padding: var(--s2) var(--s2) var(--s2) var(--s4);
  border: 1px solid var(--line);
  border-radius: var(--r-card);
  background: var(--elevated);
  color: var(--ink);
  pointer-events: auto;
  animation: kaeru-rise var(--normal) ease-out;
}

.toast__text {
  flex: 1;
  min-width: 0;
}

.toast__action {
  color: var(--ink);
}

@keyframes kaeru-rise {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@media (min-width: 768px) {
  .toast-region {
    bottom: var(--s6);
    left: calc(50% + var(--sidebar-w) / 2);
    width: min(560px, calc(100vw - var(--sidebar-w) - 2 * var(--gutter)));
  }
}

/* ---------- Dialog ---------- */
.dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: var(--gutter);
  background: var(--scrim);
}

.dialog {
  width: min(440px, 100%);
  padding: var(--s6);
  border: 1px solid var(--line);
  border-radius: var(--r-card);
  background: var(--surface);
  animation: kaeru-rise var(--normal) ease-out;
}

.dialog__text {
  margin-top: var(--s3);
  color: var(--ink-soft);
}

.dialog__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--s3);
  margin-top: var(--s6);
}

/* ---------- Shell: sidebar from 768px, top bar and tab bar below ---------- */
.shell {
  min-height: 100dvh;
}

.skip-link {
  position: fixed;
  top: var(--s2);
  left: var(--s2);
  z-index: 100;
  padding: var(--s2) var(--s4);
  border-radius: var(--r-card);
  background: var(--elevated);
  color: var(--ink);
  transform: translateY(-200%);
}

.skip-link:focus {
  transform: none;
}

.wordmark {
  color: var(--ink);
  font-weight: 800;
  letter-spacing: -0.4px;
}

.sidebar {
  display: none;
}

.sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--s3);
  min-height: 40px;
  padding: 0 var(--s3);
  border-radius: var(--r-card);
  color: var(--ink);
  text-decoration: none;
  transition: background-color var(--fast) ease-out;
}

.nav-item svg {
  flex: none;
  color: var(--ink-soft);
}

.nav-item:hover {
  background: var(--elevated);
}

/* A muted amber tint, not a solid capsule brighter than any poster. */
.nav-item[aria-current="page"] {
  background: var(--accent-soft);
  color: var(--accent);
}

.nav-item[aria-current="page"] svg {
  color: var(--accent);
}

.nav-group {
  margin-top: var(--s4);
}

.nav-group__label {
  display: block;
  padding: 0 var(--s3) var(--s1);
  color: var(--ink-soft);
}

.account-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  min-height: var(--touch);
  margin-top: auto;
  padding: var(--s2) var(--s3);
  border-radius: var(--r-card);
  color: var(--ink);
  text-decoration: none;
}

.account-row:hover {
  background: var(--elevated);
}

.account-row__name {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.avatar {
  display: block;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--elevated);
  object-fit: cover;
}

.avatar--empty {
  display: grid;
  place-items: center;
  background: transparent;
  color: var(--ink-soft);
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--topbar-h);
  padding: 0 var(--gutter);
  background: var(--bg);
}

.topbar__account {
  display: inline-grid;
  place-items: center;
  width: var(--touch);
  height: var(--touch);
  margin-right: calc(-1 * var(--s2));
  border-radius: 50%;
}

.main {
  display: block;
  min-width: 0;
  padding-bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom));
}

/* The skip link's target is a region, not a control. */
.main:focus {
  outline: none;
}

.tabbar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  height: calc(var(--tabbar-h) + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--surface);
}

.tab-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--ink-soft);
  text-decoration: none;
}

.tab-item__icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 28px;
  border-radius: 14px;
  transition: background-color var(--fast) ease-out;
}

.tab-item__label {
  font-size: 15px;
  line-height: 21px;
  font-weight: 600;
}

.tab-item[aria-current="page"] {
  color: var(--accent);
}

.tab-item[aria-current="page"] .tab-item__icon {
  background: var(--elevated);
}

.tab-item:focus-visible {
  border-radius: var(--r-card);
  outline: 3px solid var(--accent);
  outline-offset: -3px;
}

@media (min-width: 768px) {
  .sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 20;
    display: flex;
    flex-direction: column;
    gap: var(--s1);
    width: var(--sidebar-w);
    padding: var(--s4) var(--s3);
    overflow-y: auto;
    background: var(--surface);
  }

  .sidebar .wordmark {
    padding: var(--s2) var(--s3) var(--s4);
  }

  .topbar,
  .tabbar {
    display: none;
  }

  .main {
    margin-left: var(--sidebar-w);
    padding-bottom: 0;
  }
}

/* ---------- Pages ---------- */
.page {
  width: 100%;
  max-width: var(--content-max);
  margin: 0 auto;
  padding-bottom: var(--s8);
}

.page-title {
  padding: var(--s4) var(--gutter) 0;
}

.screen-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--s4);
  min-height: 100dvh;
  padding: var(--s8) var(--gutter);
  text-align: center;
}

.screen-center__text {
  max-width: 360px;
  color: var(--ink-soft);
}

.screen-center__error {
  max-width: 360px;
  color: var(--error);
}

.screen-center__eyebrow {
  color: var(--ink-soft);
}

.screen-center .btn {
  margin-top: var(--s2);
}
```

Modify `web/src/main.tsx`. Old text (the whole file from Task 1):

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./ui/tokens.css";
import "./ui/base.css";

const root = document.getElementById("root");
if (!root) throw new Error("index.html has no #root element");

// A bare page until the app shell exists (Task 8 replaces this file); it proves the font, palette and build.
createRoot(root).render(
  <StrictMode>
    <h1 className="t-display">Kaeru</h1>
  </StrictMode>,
);
```

New text:

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
// Global styles first: the stylesheets that screens import through App must come after them in the bundle.
import "./ui/tokens.css";
import "./ui/base.css";
import "./ui/components.css";
import { App } from "./app/App";
import { restoreDeepLink } from "./app/bootstrap";

// Before the router reads the address: /?p=%2Fanime%2F1535 becomes /anime/1535.
restoreDeepLink(window.location, window.history);

const root = document.getElementById("root");
if (!root) throw new Error("index.html has no #root element");

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

- [ ] **Step 4: Run to see it pass.** From `web/`:

```bash
npx vitest run src/app src/screens src/ui
npm test
npm run typecheck
npm run build
```

**Expected:**
- `vitest run`: `Test Files  15 passed (15)`. That is the 14 new suites plus `tokens.test.ts`, with no failures.
- `npm test`: every suite of Tasks 1–8 passes.
- `npm run typecheck`: no output, exit code 0.
- `npm run build`: `✓ built in …`, with `dist/index.html` and `dist/assets/*.js` and `*.css` emitted.
- Optional manual check with `npm run dev`: http://localhost:5173 shows the «Kaeru» sign-in screen in Manrope on `#0B0C10`, with an amber «Войти через Shikimori» in dark text. Tab reaches the button with a 3px ring.

- [ ] **Step 5: Commit.**

```bash
cd /Users/vitaliy/Projects/kaeru
git add web/src/main.tsx \
  web/src/app/App.tsx web/src/app/App.test.tsx web/src/app/bootstrap.ts web/src/app/bootstrap.test.ts \
  web/src/app/services.tsx web/src/app/services.test.tsx web/src/app/Gate.tsx web/src/app/Gate.test.tsx \
  web/src/screens/SignInScreen.tsx web/src/screens/SignInScreen.test.tsx web/src/screens/AccessClosedScreen.tsx \
  web/src/screens/AuthCallbackScreen.tsx web/src/screens/AuthCallbackScreen.test.tsx \
  web/src/screens/WatchPlaceholderScreen.tsx web/src/screens/WatchPlaceholderScreen.test.tsx \
  web/src/screens/HomeScreen.tsx web/src/screens/SearchScreen.tsx web/src/screens/LibraryScreen.tsx \
  web/src/screens/TitleScreen.tsx web/src/screens/SettingsScreen.tsx \
  web/src/ui/Layout.tsx web/src/ui/Layout.test.tsx web/src/ui/Button.tsx web/src/ui/Button.test.tsx \
  web/src/ui/Pill.tsx web/src/ui/Pill.test.tsx web/src/ui/PosterCard.tsx web/src/ui/PosterCard.test.tsx \
  web/src/ui/Shelf.tsx web/src/ui/Skeleton.tsx web/src/ui/States.tsx web/src/ui/States.test.tsx \
  web/src/ui/Toast.tsx web/src/ui/Toast.test.tsx web/src/ui/Dialog.tsx web/src/ui/Dialog.test.tsx \
  web/src/ui/icons.tsx web/src/ui/components.css
git commit -m "feat(web): оболочка — маршруты, вход, «Доступ закрыт» и базовые компоненты"
```

---

### Task 9: Home screen

**Decisions:**
- **Where the pure logic lives:** `web/src/screens/home.ts` holds `homeContent`, `HomeContent`, `libraryEntries`, `DiscoverContent`, `discoverContent`, `popularNowContent`, `seasonalContent`, `CatalogueCache`, `catalogueCache` and `CATALOGUE_TTL_MS`. The styles live in `web/src/screens/HomeScreen.css`. `HomeScreen.tsx` exports only the component and its props type.
- **`HomeScreen` props:** a named export with optional props `{ now?: () => number; catalogue?: CatalogueCache }`. The defaults are `Date.now` and the module-level `catalogueCache`, which survives leaving the screen and coming back. The props exist so tests can pin the clock and use a fresh cache. Task 8's route `<HomeScreen />` passes neither.
- **Routing:** Task 8's `App.tsx` already imports `HomeScreen` from `../screens/HomeScreen` and routes `/` to `<HomeScreen />` inside `Layout`. This task overwrites the Task 8 stub `web/src/screens/HomeScreen.tsx` in place, keeps the export name, and never edits `App.tsx`.
- **Task 8 primitives used (real signatures):**
  - `Shelf({ title, action?, extra?, children })`. `extra` sits between the header and the row, and it carries the season chips and a row's own status line. Shelf always renders its `<ul class="shelf__row">`. Home hides that list with `:empty` in the catalogue block when a row has no cards.
  - `PosterCard({ card, layout?, to?, footer? })` is a link to `/anime/:id`.
  - `PrimaryButton`, `SecondaryButton` and `TextAction` take `ButtonProps`: `children`, `icon?` and `className?`, plus either button attributes or `to` (then they render a router `Link`).
  - `PillGroup<T>({ kind: "radio", label, options: { value, label }[], value, onChange, className? })` is a radiogroup with roving focus and arrow keys.
  - `SkeletonGroup({ label?, children })` is one `role="status"` region labelled «Загрузка…». `SkeletonHero()`, `SkeletonShelf({ cards? })` and `SkeletonBlock({ className?, width?, height?, radius? })` each draw aria-hidden blocks.
  - `EmptyState({ title, text?, action?: StateAction })`, where `StateAction` is `{ label; to?; onClick? }`. `ErrorState({ message, onRetry? })` renders a secondary «Повторить». `SyncingNotice({ text? })` defaults to «Синхронизируем список с Shikimori…» and shows the indeterminate strip.
  - `useToast()` returns `{ show(text, action?: { label, onClick }), dismiss() }`, and `ToastProvider` supplies it.
  - `useServices()` returns `{ shikimori, library, progress }`. `ServicesContext` (a React context of `Services | null`) is exported next to it.
  - `IconPlay` comes from `ui/icons.tsx`.
  - `.visually-hidden` comes from Task 1's `base.css`.
- **One bar:** below 768px, Layout's opaque top bar (wordmark «Kaeru» and «Аккаунт и настройки») is the only bar. Home draws no bar of its own, and the hero starts under Layout's bar. Android's transparent bar over the hero is not built in plan 2, as Task 8 records. The page's `h1` «Главная» is `.visually-hidden`, because the hero is the visible title.
- **Library loading:** Task 8's signed-in outlet starts `library.load()` while the library is `idle`. Home also calls it on mount when the state is still `idle`, which covers a Home mounted outside the shell, as in tests. `Library.load()` is single-flight, so the two never double-fetch. «Повторить» in the error state and in the toast calls `library.load()`. The web has no pull-to-refresh, so catalogue reads are never forced.
- **Offline:** when offline, the screen shows «Нет сети», hides the catalogue and suppresses the refresh toast. An empty feed with an error still shows the error screen, because the web keeps no offline copy of the list.
- **Season chips:** `PillGroup kind="radio" label="Сезон"`. Each option's value is the season's API value («summer_2026») and its label is `seasonTitle`. The group carries `className="home-seasons"`, so it scrolls edge to edge like the row under it.
- **Skeletons:**
  - Loading (library `idle`): `SkeletonGroup` around `SkeletonHero` and two `SkeletonShelf`.
  - First sync: `SyncingNotice` above the same blocks. There is no second status region, because the blocks are aria-hidden on their own.
  - A catalogue row that is still loading keeps its real heading, and its chips when it has them. Its cards become a `SkeletonGroup` row of `SkeletonBlock`s, drawn in `extra`.
- **Hero:**
  - The backdrop is `backdropUrl` when there is one. Otherwise it is the blurred poster, with the sharp poster on the right from 768px.
  - The scrims are Android's, drawn in the page colour with `color-mix(… var(--bg) …)`: a start gradient under the text and a bottom gradient that ends in opaque `--bg`. No raw colours.
  - The primary label and enabled state come from `primaryAction`, and the status line from `episodeLine`.
  - Navigation is a link. When `action.enabled`, the primary is `<PrimaryButton to="/watch/:id/:episode" icon={<IconPlay/>}>` (decision 9). Otherwise it is a disabled `<PrimaryButton disabled>` that goes nowhere; it keeps the play icon, as Android's HeroBanner does. «Подробнее» is `<SecondaryButton to="/anime/:id">`.
  - The feed only ever puts an item with an enabled action in the hero, so the disabled form is a safety net.
- **CSS order:** selectors that adjust a shared class carry two classes (`.hero .hero-title`, `.pill-group.home-seasons`, `.btn.home-retry`, `.home-discover .shelf__row:empty`). They then win wherever the bundle places `components.css` and `tokens.css`.

**Files:**
- Create: `web/src/screens/home.ts`
- Create: `web/src/screens/HomeScreen.css`
- Overwrite (Task 8 stub): `web/src/screens/HomeScreen.tsx`
- Test: `web/src/screens/home.test.ts`
- Test: `web/src/screens/HomeScreen.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `.visually-hidden` (`base.css`); tokens `--bg --surface --ink-soft --gutter --card-gap --poster-w --s2 --s3 --s6 --s8`; type classes `.t-display .t-body .t-label`.
  - Task 2: `Anime`, `EpisodeProgress`, `LibraryEntry`, `UserRate`, `ListStatus` (`models.ts`); `Season`, `seasonChips(now)`, `seasonApiValue(s)`, `seasonTitle(s)` (`season.ts`).
  - Task 3: `primaryAction(entry, anime, progress, threshold, now)`, `episodeLine(kind, entry, episode, progress, now)` (`actions.ts`).
  - Task 4: `buildFeed`, `feedRows`, `isFeedEmpty`, `catalogueCard`, `Card`, `FeedItem` (`feed.ts`).
  - Task 5: `Shikimori` with `popularNow()` and `popularInSeason(season)`, `ApiError`, `NetworkError`.
  - Task 6: `authorized`, as a type for the test fake.
  - Task 7: `Library` (`state()`, `subscribe`, `load()`), `useLibrary(library)`, `LibraryState`, `ProgressStore` (`of`, `put`, `subscribe`), `watchedThreshold()`.
  - Task 8:
    - `app/services.tsx`: `useServices()`, `ServicesContext`.
    - `ui/Shelf.tsx`: `Shelf` (`extra`).
    - `ui/PosterCard.tsx`: `PosterCard`.
    - `ui/Button.tsx`: `PrimaryButton`, `SecondaryButton`, `TextAction`.
    - `ui/icons.tsx`: `IconPlay`.
    - `ui/Pill.tsx`: `PillGroup`.
    - `ui/Skeleton.tsx`: `SkeletonGroup`, `SkeletonHero`, `SkeletonShelf`, `SkeletonBlock`.
    - `ui/States.tsx`: `EmptyState` (`action: StateAction`), `ErrorState`, `SyncingNotice`.
    - `ui/Toast.tsx`: `ToastProvider`, `useToast()`.
    - `ui/components.css` classes it adjusts: `.shelf__row`, `.pill-group`, `.btn`.
- Produces:
  - `web/src/screens/HomeScreen.tsx`: `HomeScreen(props: HomeScreenProps)` and `HomeScreenProps`, with the same export name as the Task 8 stub.
  - `web/src/screens/home.ts`:
    - `CatalogueCache` (`new CatalogueCache({ ttlMs?, now? })`, `read(key, load, force?)`)
    - `catalogueCache`, `CATALOGUE_TTL_MS`
    - `homeContent(library, feedEmpty)`, `HomeContent`
    - `libraryEntries(library)`
    - `DiscoverContent`, `discoverContent`, `popularNowContent`, `seasonalContent`
  - For Task 11: the shared catalogue cache is `catalogueCache`, exported from `web/src/screens/home.ts`. `SearchScreen.tsx`, which sits in the same folder, imports it with `import { catalogueCache } from "./home";`. It reads the idle «Популярно сейчас» grid as `catalogueCache.read("now", () => shikimori.popularNow())`. `"now"` is the key Home uses, so the two screens share one 6-hour entry.

- [ ] **Step 1: Write the failing tests**

`web/src/screens/home.test.ts`:

```ts
// Vectors: android/src/test/java/app/kaeru/ui/common/home/HomeContentTest.kt (screen state),
// android/src/test/java/app/kaeru/ui/common/home/HomeRowsTest.kt «discovery» (row states) and
// android/src/test/java/app/kaeru/data/library/ShikimoriDiscoverRepositoryTest.kt (cache).
import { describe, expect, it, vi } from "vitest";
import type { Anime, LibraryEntry } from "../domain/models";
import {
  CATALOGUE_TTL_MS,
  CatalogueCache,
  discoverContent,
  homeContent,
  libraryEntries,
  popularNowContent,
  seasonalContent,
} from "./home";
import type { DiscoverContent } from "./home";

const OFFLINE = "Нет соединения. Проверьте интернет";
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const START = Date.parse("2026-09-13T20:00:00Z");

function anime(id: number): Anime {
  return {
    id,
    title: `Аниме ${id}`,
    originalTitle: `Anime ${id}`,
    posterUrl: null,
    backdropUrl: null,
    status: "ongoing",
    episodes: 12,
    episodesAired: 8,
    year: 2026,
    score: 8,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

function entry(id: number): LibraryEntry {
  return { anime: anime(id), rate: { id, animeId: id, status: "watching", episodes: 6, updatedAt: START } };
}

function cardIds(content: DiscoverContent | null): number[] {
  if (content?.kind !== "titles") throw new Error(`expected titles, got ${content?.kind ?? "nothing"}`);
  return content.cards.map((card) => card.animeId);
}

function clockAt(start: number) {
  let now = start;
  return {
    now: () => now,
    advance: (ms: number) => {
      now += ms;
    },
  };
}

describe("homeContent", () => {
  it("is loading before anything was read", () => {
    expect(homeContent({ kind: "idle" }, true)).toEqual({ kind: "loading" });
  });

  it("is the feed when the feed has titles", () => {
    expect(homeContent({ kind: "ready", entries: [entry(1)] }, false)).toEqual({ kind: "feed" });
  });

  it("stays the feed when a refresh over it fails", () => {
    expect(homeContent({ kind: "error", message: OFFLINE, entries: [entry(1)] }, false)).toEqual({ kind: "feed" });
  });

  it("explains a failure when there is no feed", () => {
    expect(homeContent({ kind: "error", message: OFFLINE, entries: null }, true)).toEqual({
      kind: "error",
      message: OFFLINE,
    });
  });

  it("is the invitation when a finished sync found nothing", () => {
    expect(homeContent({ kind: "ready", entries: [] }, true)).toEqual({ kind: "empty" });
  });

  it("is the first sync, not an empty list, while the list is still coming", () => {
    expect(homeContent({ kind: "loading", entries: null }, true)).toEqual({ kind: "first_sync" });
  });

  it("keeps the feed on screen while it syncs again", () => {
    expect(homeContent({ kind: "loading", entries: [entry(1)] }, false)).toEqual({ kind: "feed" });
  });

  it("is the invitation when the list holds nothing the feed can show", () => {
    // For example a list of completed titles only: loaded, nothing wrong, nothing to watch.
    expect(homeContent({ kind: "ready", entries: [entry(1)] }, true)).toEqual({ kind: "empty" });
  });

  it("still explains a failure when a refresh over an empty feed fails", () => {
    expect(homeContent({ kind: "error", message: OFFLINE, entries: [] }, true)).toEqual({
      kind: "error",
      message: OFFLINE,
    });
  });
});

describe("libraryEntries", () => {
  it("reads the entries out of every state that has them", () => {
    const entries = [entry(1)];
    expect(libraryEntries({ kind: "idle" })).toEqual([]);
    expect(libraryEntries({ kind: "loading", entries: null })).toEqual([]);
    expect(libraryEntries({ kind: "loading", entries })).toBe(entries);
    expect(libraryEntries({ kind: "ready", entries })).toBe(entries);
    expect(libraryEntries({ kind: "error", message: OFFLINE, entries })).toBe(entries);
  });
});

describe("«Популярно сейчас»", () => {
  it("shows the titles it was given", () => {
    expect(cardIds(popularNowContent([anime(1)], false))).toEqual([1]);
  });

  it("is absent when the read failed", () => {
    expect(popularNowContent(null, false)).toBeNull();
  });

  it("is absent when the catalogue had nothing", () => {
    expect(popularNowContent([], false)).toBeNull();
  });

  it("keeps its place with a skeleton while loading", () => {
    expect(popularNowContent(null, true)).toEqual({ kind: "loading" });
  });

  it("does not replace titles on screen with a skeleton while they reload", () => {
    expect(cardIds(popularNowContent([anime(1)], true))).toEqual([1]);
  });
});

describe("«Популярное в сезоне»", () => {
  it("takes the whole block away when the very first load fails", () => {
    expect(seasonalContent(null, false, false)).toBeNull();
  });

  it("keeps the block up while the first load runs", () => {
    expect(seasonalContent(null, true, false)).toEqual({ kind: "loading" });
  });

  it("says an empty season is empty and keeps the switcher", () => {
    expect(seasonalContent([], false, true)).toEqual({ kind: "empty" });
  });

  it("offers a retry when a season fails after another one worked", () => {
    expect(seasonalContent(null, false, true)).toEqual({ kind: "failed" });
  });

  it("treats an empty answer as empty however the session got there", () => {
    expect(seasonalContent([], false, false)).toEqual({ kind: "empty" });
  });

  it("shows a skeleton, not the season before, while a new season loads", () => {
    expect(seasonalContent(null, true, true)).toEqual({ kind: "loading" });
  });
});

describe("discoverContent", () => {
  it("draws a title the catalogue repeats only once", () => {
    expect(cardIds(discoverContent([anime(1), anime(2), anime(1)], false))).toEqual([1, 2]);
  });

  it("builds catalogue cards with no badge and no progress", () => {
    expect(discoverContent([anime(1)], false)).toEqual({
      kind: "titles",
      cards: [
        { key: "1", animeId: 1, title: "Аниме 1", posterUrl: null, badge: null, subtitle: "8 серий", progress: null },
      ],
    });
  });
});

describe("CatalogueCache", () => {
  it("keeps six hours", () => {
    expect(CATALOGUE_TTL_MS).toBe(6 * HOUR);
  });

  it("answers a second read inside six hours without fetching", async () => {
    const clock = clockAt(START);
    const cache = new CatalogueCache({ now: clock.now });
    const load = vi.fn(async () => [anime(1)]);

    const first = await cache.read("now", load);
    clock.advance(5 * HOUR + 59 * MINUTE);
    const second = await cache.read("now", load);

    expect(second).toBe(first);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("reads the row again six hours on", async () => {
    const clock = clockAt(START);
    const cache = new CatalogueCache({ now: clock.now });
    const load = vi.fn(async () => [anime(1)]);

    await cache.read("now", load);
    clock.advance(6 * HOUR);
    await cache.read("now", load);

    expect(load).toHaveBeenCalledTimes(2);
  });

  it("ignores a fresh cache when forced", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const load = vi.fn(async () => [anime(1)]);

    await cache.read("now", load);
    await cache.read("now", load, true);

    expect(load).toHaveBeenCalledTimes(2);
  });

  it("remembers each season under its own key", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const summer = vi.fn(async () => [anime(1)]);
    const fall = vi.fn(async () => [anime(2)]);

    await cache.read("summer_2026", summer);
    await cache.read("fall_2026", fall);
    await cache.read("summer_2026", summer);

    expect(summer).toHaveBeenCalledTimes(1);
    expect(fall).toHaveBeenCalledTimes(1);
  });

  it("does not share a key between the ongoing row and a season", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const ongoing = vi.fn(async () => [anime(1)]);
    const summer = vi.fn(async () => [anime(2)]);

    expect(await cache.read("now", ongoing)).toEqual([anime(1)]);
    expect(await cache.read("summer_2026", summer)).toEqual([anime(2)]);
    expect(summer).toHaveBeenCalledTimes(1);
  });

  it("does not cache a failure, so the next read tries again", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const refused = new Error("503");
    const load = vi
      .fn<() => Promise<Anime[]>>()
      .mockRejectedValueOnce(refused)
      .mockResolvedValueOnce([anime(1)]);

    await expect(cache.read("now", load)).rejects.toBe(refused);
    expect(await cache.read("now", load)).toEqual([anime(1)]);
    expect(load).toHaveBeenCalledTimes(2);
  });
});
```

`web/src/screens/HomeScreen.test.tsx`:

```tsx
// The home screen over the real Library and ProgressStore with a fake Shikimori.
// Copy and state rules: android/src/main/java/app/kaeru/ui/mobile/home/HomeScreen.kt,
// android/src/main/java/app/kaeru/ui/mobile/home/HomeDiscover.kt,
// android/src/test/java/app/kaeru/ui/common/home/HomeContentTest.kt.
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import type { Season } from "../domain/season";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { ToastProvider } from "../ui/Toast";
import { CatalogueCache } from "./home";
import { HomeScreen } from "./HomeScreen";

// Local wall-clock time: September 2026 is the summer season in any zone.
const NOW = new Date(2026, 8, 13, 20, 0, 0).getTime();
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const ROWS = [
  "Новые серии",
  "Продолжить",
  "Дальше по списку",
  "Скоро",
  "В планах",
  "Популярно сейчас",
  "Популярное в сезоне",
];
const EMPTY_TITLE = "Здесь появятся тайтлы из списка «Смотрю»";
const EMPTY_TEXT =
  "Отметьте аниме как «Смотрю» на Shikimori или найдите его здесь. Kaeru продолжит с той серии, на которой вы остановились.";

function anime(id: number, title: string, spec: Partial<Anime> = {}): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "ongoing",
    episodes: 24,
    episodesAired: 7,
    year: 2026,
    score: 8.1,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
    ...spec,
  };
}

function rate(animeId: number, status: ListStatus, episodes: number, updatedAt = NOW - DAY): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt };
}

const unused = () => Promise.reject(new Error("not used on the home screen"));
const fakeAuthorized: typeof authorized = (call) => call("token");

interface Setup {
  rates?: UserRate[];
  titles?: Anime[];
  popularNow?: Shikimori["popularNow"];
  popularInSeason?: Shikimori["popularInSeason"];
}

function setup(s: Setup = {}) {
  const titles = s.titles ?? [];
  const userRates: Shikimori["userRates"] = async () => s.rates ?? [];
  const popularNow: Shikimori["popularNow"] = s.popularNow ?? (async () => [anime(50, "Популярное аниме")]);
  const popularInSeason: Shikimori["popularInSeason"] =
    s.popularInSeason ?? (async () => [anime(51, "Сезонное аниме")]);
  const shikimori = {
    whoami: unused,
    details: unused,
    search: unused,
    createRate: unused,
    updateRate: unused,
    userRates: vi.fn(userRates),
    byIds: vi.fn(async (ids: readonly number[]) => titles.filter((a) => ids.includes(a.id))),
    popularNow: vi.fn(popularNow),
    popularInSeason: vi.fn(popularInSeason),
  } satisfies Shikimori;
  const progress = new ProgressStore(window.localStorage);
  const library = new Library({ shikimori, authorized: fakeAuthorized, accountId: () => 7, progress });
  return { shikimori, progress, library };
}

/** One title in every personal row; «Фрирен» is 6:40 into its 7th episode. */
function watchingFixture() {
  const services = setup({
    titles: [
      anime(1, "Фрирен", { episodes: 28, episodesAired: 24 }),
      anime(2, "Дандадан"),
      anime(3, "Монолог фармацевта", { status: "released", episodes: 12, episodesAired: 12 }),
      anime(4, "Магическая битва", { nextEpisodeAt: NOW + 2 * DAY }),
      anime(5, "Клинок", { status: "released", episodes: 12, episodesAired: 12 }),
    ],
    rates: [
      rate(1, "watching", 6),
      rate(2, "watching", 6),
      rate(3, "watching", 3),
      rate(4, "watching", 7),
      rate(5, "planned", 0),
    ],
  });
  services.progress.put({ animeId: 1, episode: 7, positionMs: 400_000, durationMs: 1_440_000, updatedAt: NOW - HOUR });
  return services;
}

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname}</p>;
}

function renderHome(services: ReturnType<typeof setup>, cache = new CatalogueCache({ now: () => NOW })) {
  const { shikimori, library, progress } = services;
  return render(
    <ServicesContext.Provider value={{ shikimori, library, progress }}>
      <ToastProvider>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<HomeScreen now={() => NOW} catalogue={cache} />} />
            <Route path="*" element={<Where />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </ServicesContext.Provider>,
  );
}

let online = true;

beforeEach(() => {
  window.localStorage.clear();
  online = true;
  Object.defineProperty(navigator, "onLine", { configurable: true, get: () => online });
});

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(navigator, "onLine");
});

describe("HomeScreen", () => {
  it("puts the continued title in the hero and the rows in Android's order", async () => {
    renderHome(watchingFixture());

    const hero = await screen.findByRole("region", { name: "Фрирен" });
    expect(within(hero).getByText("7 серия, осталось 17 мин")).toBeInTheDocument();
    expect(within(hero).getByRole("link", { name: "Продолжить с 6:40" })).toHaveAttribute("href", "/watch/1/7");
    expect(within(hero).getByRole("link", { name: "Подробнее" })).toHaveAttribute("href", "/anime/1");

    await screen.findByText("Популярное аниме");
    await screen.findByText("Сезонное аниме");
    const headings = screen
      .getAllByRole("heading", { level: 2 })
      .map((heading) => heading.textContent ?? "")
      .filter((text) => ROWS.includes(text));
    expect(headings).toEqual(ROWS);
  });

  it("opens the player route for the episode the hero offers", async () => {
    const user = userEvent.setup();
    renderHome(watchingFixture());

    const hero = await screen.findByRole("region", { name: "Фрирен" });
    await user.click(within(hero).getByRole("link", { name: "Продолжить с 6:40" }));

    expect(screen.getByTestId("where")).toHaveTextContent("/watch/1/7");
  });

  it("opens the title page from «Подробнее»", async () => {
    const user = userEvent.setup();
    renderHome(watchingFixture());

    const hero = await screen.findByRole("region", { name: "Фрирен" });
    await user.click(within(hero).getByRole("link", { name: "Подробнее" }));

    expect(screen.getByTestId("where")).toHaveTextContent("/anime/1");
  });

  it("names the page for screen readers without a visible title", async () => {
    renderHome(watchingFixture());

    const title = screen.getByRole("heading", { level: 1, name: "Главная" });
    expect(title).toHaveClass("visually-hidden");
    await screen.findByRole("region", { name: "Фрирен" });
  });

  it("does not call a list that is still coming down the wire empty", async () => {
    const services = setup();
    services.shikimori.userRates.mockReturnValue(new Promise<UserRate[]>(() => {}));
    renderHome(services);

    expect(await screen.findByText("Синхронизируем список с Shikimori…")).toBeInTheDocument();
    expect(screen.queryByText(EMPTY_TITLE)).not.toBeInTheDocument();
  });

  it("invites a search from an empty list and keeps the catalogue under it", async () => {
    const user = userEvent.setup();
    renderHome(setup());

    expect(await screen.findByText(EMPTY_TITLE)).toBeInTheDocument();
    expect(screen.getByText(EMPTY_TEXT)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Популярно сейчас" })).toBeInTheDocument();

    const find = screen.getByRole("link", { name: "Найти аниме" });
    expect(find).toHaveAttribute("href", "/search");
    await user.click(find);
    expect(screen.getByTestId("where")).toHaveTextContent("/search");
  });

  it("explains a failed first sync and retries it", async () => {
    const user = userEvent.setup();
    const services = setup();
    services.shikimori.userRates.mockRejectedValueOnce(new NetworkError("offline"));
    renderHome(services);

    expect(await screen.findByText("Нет соединения. Проверьте интернет")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));

    expect(await screen.findByText(EMPTY_TITLE)).toBeInTheDocument();
    expect(services.shikimori.userRates).toHaveBeenCalledTimes(2);
  });

  it("reports a failed refresh over a feed as a toast with «Повторить»", async () => {
    const services = watchingFixture();
    await services.library.load().catch(() => undefined);
    renderHome(services);
    await screen.findByRole("region", { name: "Фрирен" });

    services.shikimori.userRates.mockRejectedValueOnce(new ApiError(503));
    await act(async () => {
      await services.library.load().catch(() => undefined);
    });

    expect(await screen.findByText("Shikimori недоступен, попробуйте позже")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Повторить" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Фрирен" })).toBeInTheDocument();
  });

  it("hides the catalogue offline, says «Нет сети» and brings the rows back online", async () => {
    online = false;
    const services = watchingFixture();
    renderHome(services);

    expect(await screen.findByText("Нет сети")).toBeInTheDocument();
    await screen.findByRole("region", { name: "Фрирен" });
    expect(screen.queryByRole("heading", { name: "Популярно сейчас" })).not.toBeInTheDocument();
    expect(services.shikimori.popularNow).not.toHaveBeenCalled();

    online = true;
    act(() => {
      window.dispatchEvent(new Event("online"));
    });

    expect(await screen.findByText("Популярное аниме")).toBeInTheDocument();
    expect(screen.queryByText("Нет сети")).not.toBeInTheDocument();
  });

  it("leaves out «Популярно сейчас» when it could not be read", async () => {
    const services = setup({
      popularNow: async () => {
        throw new ApiError(503);
      },
    });
    renderHome(services);

    expect(await screen.findByText("Сезонное аниме")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "Популярно сейчас" })).not.toBeInTheDocument(),
    );
    expect(services.shikimori.popularNow).toHaveBeenCalledTimes(1);
  });

  it("keeps the season heading and switcher over a skeleton while a season loads", async () => {
    renderHome(setup({ popularInSeason: () => new Promise<Anime[]>(() => {}) }));

    const block = await screen.findByRole("region", { name: "Популярное в сезоне" });
    expect(within(block).getByRole("radiogroup", { name: "Сезон" })).toBeInTheDocument();
    expect(within(block).getByRole("status")).toHaveTextContent("Загрузка…");
    expect(within(block).queryByRole("link")).not.toBeInTheDocument();
  });

  it("takes the season block away when the very first season fails", async () => {
    renderHome(
      setup({
        popularInSeason: async () => {
          throw new ApiError(503);
        },
      }),
    );

    expect(await screen.findByText("Популярное аниме")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole("radiogroup", { name: "Сезон" })).not.toBeInTheDocument(),
    );
    expect(screen.queryByText("Не удалось загрузить сезон")).not.toBeInTheDocument();
  });

  it("switches seasons from a radio group, retries a failed one and remembers the rest", async () => {
    const user = userEvent.setup();
    const services = setup({
      popularInSeason: async (season: Season) => {
        if (season.kind === "fall") throw new ApiError(503);
        return [anime(51, "Сезонное аниме")];
      },
    });
    const callsFor = (kind: Season["kind"]) =>
      services.shikimori.popularInSeason.mock.calls.filter(([season]) => season.kind === kind).length;
    renderHome(services);

    const group = await screen.findByRole("radiogroup", { name: "Сезон" });
    expect(within(group).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "Весна 2026",
      "Лето 2026",
      "Осень 2026",
    ]);
    expect(within(group).getByRole("radio", { name: "Лето 2026" })).toBeChecked();
    expect(await screen.findByText("Сезонное аниме")).toBeInTheDocument();

    await user.click(within(group).getByRole("radio", { name: "Осень 2026" }));
    expect(within(group).getByRole("radio", { name: "Осень 2026" })).toBeChecked();
    expect(await screen.findByText("Не удалось загрузить сезон")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Повторить" }));
    await waitFor(() => expect(callsFor("fall")).toBe(2));

    await user.click(within(group).getByRole("radio", { name: "Лето 2026" }));
    expect(screen.getByText("Сезонное аниме")).toBeInTheDocument();
    expect(callsFor("summer")).toBe(1);

    within(group).getByRole("radio", { name: "Лето 2026" }).focus();
    await user.keyboard("{ArrowLeft}");
    const spring = within(group).getByRole("radio", { name: "Весна 2026" });
    expect(spring).toBeChecked();
    expect(spring).toHaveFocus();
  });

  it("says a season with nothing in it is empty and keeps the switcher", async () => {
    renderHome(setup({ popularInSeason: async () => [] }));

    expect(await screen.findByText("В этом сезоне пока ничего нет")).toBeInTheDocument();
    expect(screen.getByRole("radiogroup", { name: "Сезон" })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it to see it fail**

From `web/`: `npx vitest run src/screens/home.test.ts src/screens/HomeScreen.test.tsx`

**Expected:** both files FAIL before any test runs. `home.ts` does not exist yet, and `HomeScreen.tsx` is still Task 8's stub:
- `Error: Failed to resolve import "./home" from "src/screens/home.test.ts". Does the file exist?`
- `Error: Failed to resolve import "./home" from "src/screens/HomeScreen.test.tsx". Does the file exist?`
- `Test Files  2 failed (2)`, `Tests  no tests`

- [ ] **Step 3: Implement**

`web/src/screens/home.ts`:

```ts
import { catalogueCard } from "../domain/feed";
import type { Card } from "../domain/feed";
import type { Anime, LibraryEntry } from "../domain/models";
import type { LibraryState } from "../library/library";

/** Catalogue rows are not about the viewer: six hours in memory, like Android's repository. */
export const CATALOGUE_TTL_MS = 6 * 60 * 60 * 1000;

/**
 * The answer is cached, not the attempt: a failed read stores nothing, so the next read tries again.
 */
export class CatalogueCache {
  private readonly stored = new Map<string, { titles: Anime[]; at: number }>();
  private readonly ttlMs: number;
  private readonly now: () => number;

  constructor(options: { ttlMs?: number; now?: () => number } = {}) {
    this.ttlMs = options.ttlMs ?? CATALOGUE_TTL_MS;
    this.now = options.now ?? Date.now;
  }

  async read(key: string, load: () => Promise<Anime[]>, force = false): Promise<Anime[]> {
    if (!force) {
      const hit = this.stored.get(key);
      if (hit && this.now() - hit.at < this.ttlMs) return hit.titles;
    }
    const titles = await load();
    this.stored.set(key, { titles, at: this.now() });
    return titles;
  }
}

/** Module-wide so leaving Home and coming back costs the catalogue nothing. */
export const catalogueCache = new CatalogueCache();

export type HomeContent =
  | { kind: "loading" }
  | { kind: "feed" }
  | { kind: "error"; message: string }
  | { kind: "first_sync" }
  | { kind: "empty" };

/**
 * Which screen Home is (HomeContent.kt). Only a finished sync may call a list empty, and a
 * failed refresh never takes away a feed the viewer is reading.
 */
export function homeContent(library: LibraryState, feedEmpty: boolean): HomeContent {
  if (library.kind === "idle") return { kind: "loading" };
  if (!feedEmpty) return { kind: "feed" };
  if (library.kind === "error") return { kind: "error", message: library.message };
  if (library.kind === "loading") return { kind: "first_sync" };
  return { kind: "empty" };
}

export function libraryEntries(library: LibraryState): readonly LibraryEntry[] {
  switch (library.kind) {
    case "idle":
      return [];
    case "ready":
      return library.entries;
    case "loading":
    case "error":
      return library.entries ?? [];
  }
}

export type DiscoverContent =
  | { kind: "titles"; cards: Card[] }
  | { kind: "loading" }
  | { kind: "empty" }
  | { kind: "failed" };

/** Titles win over loading; an empty list is an answer; null with nothing coming is a failure. */
export function discoverContent(titles: readonly Anime[] | null, loading: boolean): DiscoverContent {
  if (titles && titles.length > 0) return { kind: "titles", cards: uniqueById(titles).map(catalogueCard) };
  if (loading) return { kind: "loading" };
  if (titles) return { kind: "empty" };
  return { kind: "failed" };
}

/** «Популярно сейчас» has no controls, so with nothing to show it simply goes away. */
export function popularNowContent(titles: readonly Anime[] | null, loading: boolean): DiscoverContent | null {
  const content = discoverContent(titles, loading);
  return content.kind === "empty" || content.kind === "failed" ? null : content;
}

/** The season switcher stays once any season has answered, so a failed chip is never a dead end. */
export function seasonalContent(
  titles: readonly Anime[] | null,
  loading: boolean,
  anySeasonLoaded: boolean,
): DiscoverContent | null {
  const content = discoverContent(titles, loading);
  return content.kind === "failed" && !anySeasonLoaded ? null : content;
}

// The catalogue can repeat a title; React keys (and the viewer) need each one once.
function uniqueById(titles: readonly Anime[]): Anime[] {
  const seen = new Set<number>();
  return titles.filter((anime) => {
    if (seen.has(anime.id)) return false;
    seen.add(anime.id);
    return true;
  });
}
```

`web/src/screens/HomeScreen.tsx` replaces the Task 8 stub completely:

```tsx
import { useCallback, useEffect, useId, useMemo, useReducer, useRef, useState, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import type { Shikimori } from "../api/shikimori";
import { useServices } from "../app/services";
import { episodeLine, primaryAction } from "../domain/actions";
import { buildFeed, feedRows, isFeedEmpty } from "../domain/feed";
import type { FeedItem } from "../domain/feed";
import type { Anime, EpisodeProgress } from "../domain/models";
import { seasonApiValue, seasonChips, seasonTitle } from "../domain/season";
import type { Season } from "../domain/season";
import { useLibrary } from "../library/library";
import type { LibraryState } from "../library/library";
import { watchedThreshold } from "../library/prefs";
import type { ProgressStore } from "../library/progress";
import { PrimaryButton, SecondaryButton, TextAction } from "../ui/Button";
import { IconPlay } from "../ui/icons";
import { PillGroup } from "../ui/Pill";
import { PosterCard } from "../ui/PosterCard";
import { Shelf } from "../ui/Shelf";
import { SkeletonBlock, SkeletonGroup, SkeletonHero, SkeletonShelf } from "../ui/Skeleton";
import { EmptyState, ErrorState, SyncingNotice } from "../ui/States";
import { useToast } from "../ui/Toast";
import { catalogueCache, homeContent, libraryEntries, popularNowContent, seasonalContent } from "./home";
import type { CatalogueCache, DiscoverContent } from "./home";
import "./HomeScreen.css";

const PAGE_TITLE = "Главная";
const DETAILS = "Подробнее";
const EMPTY_TITLE = "Здесь появятся тайтлы из списка «Смотрю»";
const EMPTY_TEXT =
  "Отметьте аниме как «Смотрю» на Shikimori или найдите его здесь. Kaeru продолжит с той серии, на которой вы остановились.";
const FIND_ANIME = "Найти аниме";
const OFFLINE = "Нет сети";
const RETRY = "Повторить";
const POPULAR_NOW = "Популярно сейчас";
const POPULAR_IN_SEASON = "Популярное в сезоне";
const SEASON_GROUP = "Сезон";
const SEASON_EMPTY = "В этом сезоне пока ничего нет";
const SEASON_FAILED = "Не удалось загрузить сезон";
/** Cache key of the one catalogue row that is not about a season (Android's NOW_KEY). */
const NOW_KEY = "now";
const SKELETON_CARDS = [0, 1, 2, 3, 4, 5];

export interface HomeScreenProps {
  /** Clock for the feed and the season chips; tests pin it. */
  now?: () => number;
  /** Catalogue cache; the module one survives leaving and re-entering the screen. */
  catalogue?: CatalogueCache;
}

export function HomeScreen({ now = Date.now, catalogue = catalogueCache }: HomeScreenProps) {
  const { shikimori, library, progress } = useServices();
  const state = useLibrary(library);
  const progressVersion = useProgressVersion(progress);
  const online = useOnline();
  // Read once per render so the hero label and the cards agree on what «watched» means.
  const threshold = watchedThreshold();
  // One clock per feed: «осталось 14 мин» and «завтра» do not tick while being read.
  const feedNow = useMemo(() => now(), [now, state, progressVersion]);
  const progressOf = useCallback(
    (animeId: number): readonly EpisodeProgress[] => progress.of(animeId),
    [progress, progressVersion],
  );
  const feed = useMemo(
    () => buildFeed(libraryEntries(state), progressOf, feedNow, threshold),
    [state, progressOf, feedNow, threshold],
  );
  const rows = useMemo(() => feedRows(feed, progressOf, feedNow, threshold), [feed, progressOf, feedNow, threshold]);
  const content = homeContent(state, isFeedEmpty(feed));
  const discover = useDiscover(shikimori, catalogue, now, online);
  const { show } = useToast();

  const reload = useCallback(() => {
    // Library reports failures through its state; there is nothing to handle here.
    library.load().catch(() => undefined);
  }, [library]);

  // Sync once per start, as Android does; «Повторить» asks again. The app shell may have started it.
  useEffect(() => {
    if (library.state().kind === "idle") reload();
  }, [library, reload]);

  // Over a feed the viewer can still use, a failed refresh is a toast; offline, the strip says it.
  const reported = useRef<LibraryState | null>(null);
  useEffect(() => {
    if (state.kind !== "error" || content.kind !== "feed" || !online || reported.current === state) return;
    reported.current = state;
    show(state.message, { label: RETRY, onClick: reload });
  }, [state, content.kind, online, show, reload]);

  const top = content.kind === "feed" ? feed.top : null;

  return (
    <div className="home">
      {/* The hero is the page's visible title; this names the page for screen readers. */}
      <h1 className="visually-hidden">{PAGE_TITLE}</h1>
      {!online && (
        <p className="home-offline t-label" role="status">
          {OFFLINE}
        </p>
      )}
      {content.kind === "loading" && (
        <SkeletonGroup>
          <HomeSkeleton />
        </SkeletonGroup>
      )}
      {content.kind === "first_sync" && (
        <>
          {/* The notice is the one announcement; the blocks under it are hidden from screen readers. */}
          <SyncingNotice />
          <HomeSkeleton />
        </>
      )}
      {content.kind === "error" && <ErrorState message={content.message} onRetry={reload} />}
      {content.kind === "empty" && (
        <>
          <EmptyState title={EMPTY_TITLE} text={EMPTY_TEXT} action={{ label: FIND_ANIME, to: "/search" }} />
          {online && <DiscoverRows discover={discover} />}
        </>
      )}
      {content.kind === "feed" && (
        <>
          {top && <Hero item={top} progress={progressOf(top.entry.anime.id)} threshold={threshold} now={feedNow} />}
          {rows.map((row) => (
            <Shelf key={row.title} title={row.title}>
              {row.cards.map((card) => (
                <PosterCard key={card.key} card={card} />
              ))}
            </Shelf>
          ))}
          {online && <DiscoverRows discover={discover} />}
        </>
      )}
    </div>
  );
}

function useProgressVersion(progress: ProgressStore): number {
  const [version, bump] = useReducer((n: number) => n + 1, 0);
  useEffect(() => progress.subscribe(bump), [progress]);
  return version;
}

function subscribeOnline(listener: () => void): () => void {
  window.addEventListener("online", listener);
  window.addEventListener("offline", listener);
  return () => {
    window.removeEventListener("online", listener);
    window.removeEventListener("offline", listener);
  };
}

function useOnline(): boolean {
  return useSyncExternalStore(subscribeOnline, () => navigator.onLine);
}

interface Discover {
  popularNow: Anime[] | null;
  loadingNow: boolean;
  seasons: readonly [Season, Season, Season];
  season: Season;
  seasonTitles: Anime[] | null;
  loadingSeason: boolean;
  anySeasonLoaded: boolean;
  /** Takes a chip's value, which is the season's API value («summer_2026»). */
  select: (value: string) => void;
  retrySeason: () => void;
}

function useDiscover(shikimori: Shikimori, cache: CatalogueCache, now: () => number, online: boolean): Discover {
  const [seasons] = useState(() => seasonChips(now()));
  const [season, setSeason] = useState<Season>(seasons[1]);
  const [popularNow, setPopularNow] = useState<Anime[] | null>(null);
  const [loadingNow, setLoadingNow] = useState(false);
  // Visited seasons stay in memory so a chip pressed again shows its titles without a blink.
  const [seasonal, setSeasonal] = useState<ReadonlyMap<string, Anime[]>>(() => new Map());
  const [loadingKeys, setLoadingKeys] = useState<ReadonlySet<string>>(() => new Set());
  const [anySeasonLoaded, setAnySeasonLoaded] = useState(false);
  // Keys already on the wire; StrictMode runs effects twice and a chip can be pressed twice.
  const inFlight = useRef(new Set<string>());

  const loadNow = useCallback(() => {
    if (inFlight.current.has(NOW_KEY)) return;
    inFlight.current.add(NOW_KEY);
    setLoadingNow(true);
    cache
      .read(NOW_KEY, () => shikimori.popularNow())
      .then(
        (titles) => setPopularNow(titles),
        // A failure keeps what is on screen; with nothing there the row stays hidden.
        () => undefined,
      )
      .finally(() => {
        inFlight.current.delete(NOW_KEY);
        setLoadingNow(false);
      });
  }, [cache, shikimori]);

  const loadSeason = useCallback(
    (target: Season) => {
      const key = seasonApiValue(target);
      if (inFlight.current.has(key)) return;
      inFlight.current.add(key);
      setLoadingKeys((keys) => new Set(keys).add(key));
      cache
        .read(key, () => shikimori.popularInSeason(target))
        .then(
          (titles) => {
            setSeasonal((map) => new Map(map).set(key, titles));
            setAnySeasonLoaded(true);
          },
          // Forgotten rather than remembered empty, so pressing the chip again is a retry.
          () =>
            setSeasonal((map) => {
              const next = new Map(map);
              next.delete(key);
              return next;
            }),
        )
        .finally(() => {
          inFlight.current.delete(key);
          setLoadingKeys((keys) => {
            const next = new Set(keys);
            next.delete(key);
            return next;
          });
        });
    },
    [cache, shikimori],
  );

  const select = useCallback(
    (value: string) => {
      const next = seasons.find((candidate) => seasonApiValue(candidate) === value);
      if (next) setSeason(next);
    },
    [seasons],
  );

  // The catalogue is the one part of Home that needs the network; it loads when there is one.
  useEffect(() => {
    if (online) loadNow();
  }, [online, loadNow]);

  useEffect(() => {
    if (online) loadSeason(season);
  }, [online, season, loadSeason]);

  const key = seasonApiValue(season);
  return {
    popularNow,
    loadingNow,
    seasons,
    season,
    seasonTitles: seasonal.get(key) ?? null,
    loadingSeason: loadingKeys.has(key),
    anySeasonLoaded,
    select,
    retrySeason: () => loadSeason(season),
  };
}

interface HeroProps {
  item: FeedItem;
  progress: readonly EpisodeProgress[];
  threshold: number;
  now: number;
}

function Hero({ item, progress, threshold, now }: HeroProps) {
  const titleId = useId();
  const anime = item.entry.anime;
  // The same decision the title screen uses, so hero and title page never name different episodes.
  const action = primaryAction(item.entry, anime, progress, threshold, now);
  const line = episodeLine(item.kind, item.entry, item.episode, progress, now);
  return (
    <section className="hero" aria-labelledby={titleId}>
      <HeroArt anime={anime} />
      <div className="hero-text">
        <h2 id={titleId} className="hero-title t-display">
          {anime.title}
        </h2>
        <p className="hero-line t-body">{line}</p>
        <div className="hero-actions">
          {/* Navigation is a link; an episode that cannot start yet is a disabled button going nowhere. */}
          {action.enabled ? (
            <PrimaryButton to={`/watch/${anime.id}/${action.episode}`} icon={<IconPlay />}>
              {action.label}
            </PrimaryButton>
          ) : (
            <PrimaryButton disabled icon={<IconPlay />}>
              {action.label}
            </PrimaryButton>
          )}
          <SecondaryButton to={`/anime/${anime.id}`}>{DETAILS}</SecondaryButton>
        </div>
      </div>
    </section>
  );
}

// Decorative: the title sits right beside the artwork in text.
function HeroArt({ anime }: { anime: Anime }) {
  return (
    <div className="hero-art" aria-hidden="true">
      {anime.backdropUrl ? (
        <img className="hero-backdrop" src={anime.backdropUrl} alt="" />
      ) : anime.posterUrl ? (
        <>
          <img className="hero-blur" src={anime.posterUrl} alt="" />
          <img className="hero-poster" src={anime.posterUrl} alt="" />
        </>
      ) : null}
      <div className="hero-scrim" />
    </div>
  );
}

function DiscoverRows({ discover }: { discover: Discover }) {
  const popular = popularNowContent(discover.popularNow, discover.loadingNow);
  const seasonal = seasonalContent(discover.seasonTitles, discover.loadingSeason, discover.anySeasonLoaded);
  if (popular === null && seasonal === null) return null;
  return (
    <div className="home-discover">
      {popular && <DiscoverShelf title={POPULAR_NOW} content={popular} />}
      {seasonal && (
        <DiscoverShelf
          title={POPULAR_IN_SEASON}
          content={seasonal}
          switcher={
            <PillGroup
              kind="radio"
              label={SEASON_GROUP}
              className="home-seasons"
              options={discover.seasons.map((season) => ({ value: seasonApiValue(season), label: seasonTitle(season) }))}
              value={seasonApiValue(discover.season)}
              onChange={discover.select}
            />
          }
          onRetry={discover.retrySeason}
        />
      )}
    </div>
  );
}

interface DiscoverShelfProps {
  title: string;
  content: DiscoverContent;
  switcher?: ReactNode;
  onRetry?: () => void;
}

function DiscoverShelf({ title, content, switcher, onRetry }: DiscoverShelfProps) {
  // A row reporting on itself is one quiet line under its heading, not a centred empty state.
  const status =
    content.kind === "loading" ? (
      <RowSkeleton />
    ) : content.kind === "empty" ? (
      <p className="home-note t-body">{SEASON_EMPTY}</p>
    ) : content.kind === "failed" ? (
      <div className="home-failed">
        <p className="home-note t-body">{SEASON_FAILED}</p>
        {onRetry && (
          <TextAction className="home-retry" onClick={onRetry}>
            {RETRY}
          </TextAction>
        )}
      </div>
    ) : null;
  const extra =
    switcher || status ? (
      <>
        {switcher}
        {status}
      </>
    ) : undefined;
  return (
    <Shelf title={title} extra={extra}>
      {content.kind === "titles" ? content.cards.map((card) => <PosterCard key={card.key} card={card} />) : null}
    </Shelf>
  );
}

// The row keeps its real heading (and chips) while loading; only the cards are placeholders.
function RowSkeleton() {
  return (
    <SkeletonGroup>
      <div className="home-skel-row">
        {SKELETON_CARDS.map((n) => (
          <div key={n} className="home-skel-card">
            <SkeletonBlock className="home-skel-art" />
            <SkeletonBlock height={16} width="80%" />
          </div>
        ))}
      </div>
    </SkeletonGroup>
  );
}

// The shape of the screen before the feed arrives, so nothing jumps when it does.
function HomeSkeleton() {
  return (
    <>
      <SkeletonHero />
      <SkeletonShelf />
      <SkeletonShelf />
    </>
  );
}
```

`web/src/screens/HomeScreen.css`:

```css
/* Home: the hero, the list's rows, then the catalogue. Colours, spacing and type come from
   ui/tokens.css; shelves, pills, skeletons and states are ui/components.css. Selectors that
   adjust a shared class carry two classes, so they win whichever stylesheet the bundle puts last. */

.home {
  padding-bottom: var(--s8);
}

.home-offline {
  padding: var(--s2) var(--gutter);
  background: var(--surface);
  color: var(--ink-soft);
}

/* ---------- Hero ---------- */

.hero {
  position: relative;
  isolation: isolate;
  display: flex;
  align-items: flex-end;
  min-height: max(45vh, 320px);
  overflow: hidden;
  background: var(--surface);
}

.hero-art {
  position: absolute;
  inset: 0;
  z-index: -1;
}

.hero-backdrop,
.hero-blur {
  position: absolute;
  inset: 0;
  width: 100%;
  max-width: none;
  height: 100%;
  object-fit: cover;
}

.hero-blur {
  filter: blur(34px);
  opacity: 0.7;
  transform: scale(1.15);
}

/* Phones get the blur only: a sharp poster squeezed beside the text reads as clutter. */
.hero-poster {
  display: none;
}

/* Android's Backdrop scrims, in the page colour: one darkens the start under the text, the other
   ends in opaque --bg, so the hero dissolves into the page without a seam. */
.hero-scrim {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(
      to right,
      color-mix(in srgb, var(--bg) 78%, transparent) 0%,
      color-mix(in srgb, var(--bg) 40%, transparent) 50%,
      color-mix(in srgb, var(--bg) 5%, transparent) 100%
    ),
    linear-gradient(
      to bottom,
      transparent 30%,
      color-mix(in srgb, var(--bg) 78%, transparent) 68%,
      var(--bg) 100%
    );
}

.hero-text {
  position: relative;
  display: grid;
  gap: var(--s2);
  max-width: 560px;
  padding: var(--s8) var(--gutter) var(--s6);
}

/* Long Russian titles shrink rather than ellipsise; 1.4 keeps the descenders of «у р д ф» and the
   breve of «й» inside the clamp. */
.hero .hero-title {
  display: -webkit-box;
  overflow: hidden;
  font-size: clamp(22px, 5vw + 8px, 34px);
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.hero-line {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink-soft);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

/* The pair wraps to a second line rather than truncating a label. */
.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s3);
  margin-top: var(--s2);
}

@media (min-width: 768px) {
  .hero {
    align-items: center;
    min-height: max(40vh, 320px);
  }

  .hero-poster {
    position: absolute;
    inset: 0 0 0 auto;
    display: block;
    width: min(520px, 50%);
    max-width: none;
    height: 100%;
    object-fit: cover;
    /* A mask reads only alpha: the poster fades in from its left edge. */
    -webkit-mask-image: linear-gradient(to right, transparent 0%, black 35%);
    mask-image: linear-gradient(to right, transparent 0%, black 35%);
  }
}

/* ---------- Catalogue ---------- */

/* 32px more than between personal rows: the gap alone says the subject changed (DiscoverGap). */
.home-discover {
  padding-top: var(--s8);
}

/* A catalogue row that is loading, empty or failed has no cards, so no empty scroller either. */
.home-discover .shelf__row:empty {
  display: none;
}

/* The chips scroll edge to edge like the row under them, resting at the gutter. */
.pill-group.home-seasons {
  margin-inline: calc(-1 * var(--gutter));
  padding-inline: var(--gutter);
  scroll-padding-inline: var(--gutter);
}

.home-note {
  padding-top: var(--s3);
  color: var(--ink-soft);
}

.home-failed {
  display: grid;
  justify-items: start;
}

/* A text action carries 12px of its own padding; this lines its label up with the note above. */
.btn.home-retry {
  margin-inline-start: calc(-1 * var(--s3));
}

/* The loading row under a real heading, laid out like .shelf__row: edge to edge from the gutter. */
.home-skel-row {
  display: flex;
  gap: var(--card-gap);
  margin-inline: calc(-1 * var(--gutter));
  padding: var(--s3) var(--gutter) var(--s2);
  overflow: hidden;
}

.home-skel-card {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: var(--s2);
  width: var(--poster-w);
}

.home-skel-art {
  aspect-ratio: 2 / 3;
}
```

- [ ] **Step 4: Run to see it pass**

From `web/`:
1. `npx vitest run src/screens/home.test.ts src/screens/HomeScreen.test.tsx`
   - **Expected:** PASS, with `Test Files  2 passed (2)` and `Tests  44 passed (44)`. That is 30 tests in `home.test.ts` and 14 in `HomeScreen.test.tsx`.
2. `npm test`
   - **Expected:** every test file passes and none fail. Task 8's `App.test.tsx` still passes, because `App.tsx` is unchanged.
3. `npm run typecheck`
   - **Expected:** exits 0 with no output.
4. `npm run build`
   - **Expected:** `✓ built in …`. The emitted CSS contains the `.hero-*` and `.home-*` rules.

- [ ] **Step 5: Commit**

```bash
cd /Users/vitaliy/Projects/kaeru
git add web/src/screens/home.ts web/src/screens/home.test.ts web/src/screens/HomeScreen.tsx web/src/screens/HomeScreen.css web/src/screens/HomeScreen.test.tsx
git commit -m "feat(web): главный экран — герой, ряды, каталог и сезоны"
```

---

### Task 10: Title screen

**Decisions:**
- **Task 8 APIs this task uses** (the real signatures from Task 8's Interfaces; nothing here is guessed):
  - `PrimaryButton`, `SecondaryButton`, `TextAction`: `children`, optional `icon` (a leading glyph), and either `to` (then the button renders a router link: navigation is a link) or native `<button>` attributes (`type` defaults to `"button"`).
  - `IconButton { label; icon; overArt? }` plus button attributes; `label` is the accessible name, `overArt` draws the 42 % black disc.
  - `Pill { selected?; trailing?; className? }` plus button attributes.
  - `Dialog` with `DialogProps { open; title; text?; confirmLabel; cancelLabel; destructive?; onConfirm; onCancel }`. It takes no children, portals to `document.body`, starts focus on the cancel button, and cancels on Escape or a backdrop press.
  - `ErrorState { message; onRetry?; align? }`: `role="alert"`, the message, and a secondary «Повторить».
  - `useToast().show(text, action?)` with `ToastAction { label; onClick }`. `ToastProvider` renders the action as a real button that dismisses the toast and then calls `onClick`.
  - `SkeletonGroup { label?; children }` (one `role="status"` region with a visually hidden label) and `SkeletonBlock { className?; width?; height?; radius? }`.
  - Icons from `ui/icons.tsx`: `IconBack`, `IconPlay`, `IconClock`, `IconPlayCircle`, `IconCheckCircle`, `IconMore`, `IconArrowDropDown`, `IconCheck`.
  - `useServices()` returns `{ shikimori, library, progress }`. It reads `ServicesContext`, which tests provide directly.
- **Task 7 APIs this task uses:** `library.entry(id)`, `library.state()`, `library.subscribe`, `library.load()`, `useLibrary(library)`, `library.setStatus(anime, status): Promise<void>`, `library.markWatched(anime, episode): Promise<{ suggestCompleted: boolean }>`, and `library.markUnwatched(anime, episode): Promise<() => Promise<void>>`, which resolves to the undo. All writes are optimistic: on failure the library reverts and rethrows, and the screen then shows `errorMessage(error)` in a toast. Also `progress.of(id)`, `progress.subscribe` and `watchedThreshold()`.
- **New files beyond the contract:**
  - `web/src/screens/episodeRows.ts`: the pure episode range, ported from Android `episodeCells` with the EpisodeGridTest vectors.
  - `web/src/ui/Menu.tsx` + `Menu.css`: an accessible menu button used for the status menu and the row menus. Its trigger is Task 8's `Pill` (with `IconArrowDropDown`) or `IconButton`, so it matches every other pill and icon button. `Menu.css` styles only the popup.
- **Episode range:** follows Android. reached = max(count, highest started episode). playable = max(available, reached). total = max(announced, playable). A title with no rows shows the iOS copy «Серии ещё не вышли» / «Добавьте аниме в планы, чтобы вернуться к нему позже.»
- **Row caption and bar:** the m:ss caption appears exactly when the progress bar does (`episodeFraction` ≠ null), so both tell the same story.
- **Rows:**
  - An aired row is a link to `/watch/:id/:n`. Navigation is a link (Task 8), so a row can be opened in a new tab.
  - Aired rows are named «N серия», «N серия, просмотрено» or «N серия, остановились на m:ss».
  - An unaired row is plain text with no link and no menu. It shows «Не вышла» and is read as «N серия, не вышла» through `.visually-hidden` (Task 1 `base.css`).
- **Row menu:**
  - Trigger name: «Что сделать с серией N» (Android `MORE_ACTIONS`).
  - Items: «Смотреть», then «Отметить просмотренной» or «Отметить непросмотренной». The unmark item is red, as on Android.
- **Paging label:** «Показать ещё {pluralEpisodesAccusative(min(60, rest))}» (Android). «Свернуть серии» appears once everything is shown and there are more than 60.
- **«Об аниме»:**
  - The screen renders `anime.description` as it is. Task 5's mapping already ran `cleanDescription`, and a second pass would decode entities twice («&amp;lt;» → «<»).
  - «Читать полностью» / «Свернуть» shows only when the four-line clamp actually cuts text (measured), as on Android.
  - With no description, the section is left out.
- **List controls:** the status menu, «Добавить в планы» and the row menus stay disabled until the library has been read, so the screen never creates a rate that Shikimori already holds. The screen calls `library.load()` if the library is still idle. Task 8's signed-in shell does the same, and whichever comes first starts it.
- **Play:** when the primary action (decision 4) is enabled, it is `PrimaryButton to="/watch/:id/:episode"` with `IconPlay`. Otherwise it is a disabled `PrimaryButton` with `IconClock` and the waiting label. In plan 2 play only navigates (decision 9). Adding a title that starts playing to the list is the plan 3 player's job.
- **Completion dialog:** Task 8's `Dialog` with `open={completion}`, title «Перевести аниме в завершённые?», `text` = the anime title (iOS), `confirmLabel` «Завершить просмотр» and `cancelLabel` «Позже». The screen adds no dialog markup or CSS of its own. The dialog is skipped when the status is already «Завершено».
- **Unmark:** no confirmation (decision 6). A toast `show("Серия N отмечена непросмотренной", { label: "Отменить", onClick })` runs the undo, which restores the previous count and the deleted positions.
- **Page parts:**
  - The back control is Task 8's `IconButton` «Назад» with `overArt` over the artwork. It goes back in history, or to `/` on a deep link.
  - While nothing is loaded, a `SkeletonGroup` labelled «Обновляем информацию…» shows.
- **Styling:** `TitleScreen.css` only lays out the page. Every rule that touches a Task 8 class is scoped under `.title` (e.g. `.title .title-back`), so it wins in whatever order `main.tsx` imports the CSS. Tints are `color-mix()` of the tokens, and the progress track uses `--track`. No raw colours.
- **Route wiring:** `TitleScreen.tsx` overwrites Task 8's stub wholesale and keeps the named export. Task 8's `App.tsx` already routes `/anime/:id` to `<TitleScreen />` and is not touched.
- **Tests:** `TitleScreen.test.tsx` renders the real `ServicesContext.Provider` and `ToastProvider` over the real `Library` and `ProgressStore`. Only Shikimori is faked, and nothing is `vi.mock`ed. Undo is tested by clicking the real «Отменить» in the toast.

**Files:**
- Create: `web/src/screens/episodeRows.ts`
- Create: `web/src/ui/Menu.tsx`
- Create: `web/src/ui/Menu.css`
- Overwrite (Task 8 stub): `web/src/screens/TitleScreen.tsx`
- Create: `web/src/screens/TitleScreen.css`
- Test: `web/src/screens/episodeRows.test.ts`
- Test: `web/src/ui/Menu.test.tsx`
- Test: `web/src/screens/TitleScreen.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `.visually-hidden` (`base.css`), tokens (`--bg`, `--ink`, `--ink-soft`, `--accent`, `--error`, `--line`, `--track`, `--elevated`, `--surface`, `--touch`, `--gutter`, `--content-max`, spacing and radii).
  - Task 2 `models.ts`: `Anime`, `LibraryEntry`, `EpisodeProgress`, `ListStatus`, `AiringStatus`, `UserRate`, `STATUS_MENU`, `availableEpisodes`.
  - Task 2 `format.ts`: `factsLine`, `formatTime`, `pluralEpisodesAccusative`, `statusLabel`.
  - Task 3 `domain/progress.ts`: `episodeFraction`, `isStarted`, `progressAt`. Task 3 `actions.ts`: `primaryAction`.
  - Task 5 `http.ts`: `errorMessage`, plus `ApiError` and `NetworkError` in tests. Task 5 `shikimori.ts`: `type Shikimori`.
  - Task 6 `session.ts`: `type authorized` (tests only).
  - Task 7 `library.ts`: `Library`, `useLibrary`, `type LibraryState`. Task 7 `library/progress.ts`: `ProgressStore`. Task 7 `prefs.ts`: `watchedThreshold`.
  - Task 8:
    - `app/services.tsx`: `useServices`, plus `ServicesContext` and `type Services` in tests.
    - `ui/Button.tsx`: `PrimaryButton`, `SecondaryButton`, `TextAction`, `IconButton`.
    - `ui/Pill.tsx`: `Pill`. `ui/Dialog.tsx`: `Dialog`. `ui/States.tsx`: `ErrorState`.
    - `ui/Skeleton.tsx`: `SkeletonGroup`, `SkeletonBlock`.
    - `ui/Toast.tsx`: `useToast`, plus `ToastProvider` in tests.
    - `ui/icons.tsx`: the icons listed above.
- Produces:
  - `TitleScreen`: named export, no props, reads `:id` from the route.
  - `ui/Menu.tsx`:
    - `MenuButton(props: MenuButtonProps)`
    - `interface MenuItem { key: string; label: string; checked?: boolean; destructive?: boolean; onSelect: () => void }`
    - `interface MenuButtonProps { label: ReactNode; ariaLabel?: string; items: readonly MenuItem[]; disabled?: boolean; variant?: "pill" | "icon" }`
  - `screens/episodeRows.ts`:
    - `EPISODE_PAGE = 60`
    - `interface EpisodeRow { number: number; watched: boolean; aired: boolean; fraction: number | null; positionMs: number }`
    - `episodeRows(anime, entry | null, progress, threshold): EpisodeRow[]`

- [ ] **Step 1: Write the failing test**

`web/src/screens/episodeRows.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import type { AiringStatus, Anime, EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import { EPISODE_PAGE, episodeRows, type EpisodeRow } from "./episodeRows";

// Vectors from android/src/test/java/app/kaeru/ui/common/details/EpisodeGridTest.kt. Android's
// WatchState pointer is one more progress row here: the web keeps only per-episode rows.
const THRESHOLD = 0.9;

function anime(episodes: number, aired: number, status: AiringStatus = "ongoing"): Anime {
  return {
    id: 7,
    title: "Фрирен",
    originalTitle: "Frieren",
    posterUrl: null,
    backdropUrl: null,
    status,
    episodes,
    episodesAired: aired,
    year: 2023,
    score: 9.1,
    kind: "tv",
    studios: ["Madhouse"],
    description: null,
    nextEpisodeAt: null,
  };
}

function entry(show: Anime, episodes: number, status: ListStatus = "watching"): LibraryEntry {
  return { anime: show, rate: { id: 1, animeId: 7, status, episodes, updatedAt: 0 } };
}

function stopped(episode: number, positionMs: number, durationMs = 1_400_000): EpisodeProgress {
  return { animeId: 7, episode, positionMs, durationMs, updatedAt: 0 };
}

function at(rows: EpisodeRow[], episode: number): EpisodeRow {
  const found = rows.find((row) => row.number === episode);
  if (!found) throw new Error(`no row for episode ${episode}`);
  return found;
}

describe("episodeRows", () => {
  it("pages sixty rows at a time", () => {
    expect(EPISODE_PAGE).toBe(60);
  });

  it("runs to the announced length and stops being playable at what aired", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [], THRESHOLD);

    expect(rows.map((row) => row.number)).toEqual(Array.from({ length: 28 }, (_, index) => index + 1));
    expect(at(rows, 24).aired).toBe(true);
    expect(at(rows, 25).aired).toBe(false);
  });

  it("marks what Shikimori counted as watched and the rest as not", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [], THRESHOLD);

    expect(at(rows, 20).watched).toBe(true);
    expect(at(rows, 21).watched).toBe(false);
  });

  it("counts an episode the viewer already watched as aired whatever the catalogue says", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 26), [], THRESHOLD);

    expect(at(rows, 26).aired).toBe(true);
    expect(at(rows, 27).aired).toBe(false);
  });

  it("extends the list past the announced season to an episode opened here", () => {
    const show = anime(12, 12, "released");
    const rows = episodeRows(show, entry(show, 12), [stopped(13, 60_000)], THRESHOLD);

    expect(rows).toHaveLength(13);
    expect(at(rows, 13).aired).toBe(true);
  });

  it("shows progress on the episode in progress and nowhere else", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(21, 700_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeCloseTo(0.5, 3);
    expect(at(rows, 21).positionMs).toBe(700_000);
    expect(at(rows, 20).fraction).toBeNull();
    expect(at(rows, 22).fraction).toBeNull();
  });

  it("gives every episode with a position of its own its own bar", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(21, 700_000), stopped(23, 350_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeCloseTo(0.5, 3);
    expect(at(rows, 23).fraction).toBeCloseTo(0.25, 3);
    expect(at(rows, 22).fraction).toBeNull();
  });

  it("does not call ten seconds from a mis-tap started", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(22, 10_000)], THRESHOLD);

    expect(at(rows, 22).fraction).toBeNull();
    expect(at(rows, 22).positionMs).toBe(0);
  });

  it("draws nothing on an episode finished here before Shikimori counts it", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(21, 1_350_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeNull();
  });

  it("stretches the list to a row past the announced season", () => {
    const show = anime(12, 12, "released");
    const rows = episodeRows(show, entry(show, 12), [stopped(13, 700_000)], THRESHOLD);

    expect(rows).toHaveLength(13);
    expect(at(rows, 13).aired).toBe(true);
  });

  it("does not conjure an episode from a row nobody really started", () => {
    const show = anime(12, 12, "released");

    expect(episodeRows(show, entry(show, 12), [stopped(13, 10_000)], THRESHOLD)).toHaveLength(12);
  });

  it("treats a position in an episode Shikimori already counted as spent", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 22), [stopped(21, 700_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeNull();
  });

  it("lists a title in no list in full with nothing watched", () => {
    const rows = episodeRows(anime(12, 12, "released"), null, [], THRESHOLD);

    expect(rows).toHaveLength(12);
    expect(rows.every((row) => row.aired)).toBe(true);
    expect(rows.some((row) => row.watched)).toBe(false);
    expect(rows.every((row) => row.fraction === null)).toBe(true);
  });

  it("has no rows for an announcement with no episodes", () => {
    expect(episodeRows(anime(0, 0, "anons"), null, [], THRESHOLD)).toEqual([]);
  });

  it("runs an ongoing season of unknown length to what is out", () => {
    const show = anime(0, 24);
    const rows = episodeRows(show, entry(show, 20), [], THRESHOLD);

    expect(rows).toHaveLength(24);
    expect(rows.every((row) => row.aired)).toBe(true);
    expect(rows.filter((row) => row.watched)).toHaveLength(20);
  });

  it("lists the episodes an announcement promised as still to come", () => {
    const rows = episodeRows(anime(12, 0, "anons"), null, [], THRESHOLD);

    expect(rows).toHaveLength(12);
    expect(rows.some((row) => row.aired)).toBe(false);
  });

  it("lists a released season with no aired count as it was announced", () => {
    const rows = episodeRows(anime(12, 0, "released"), null, [], THRESHOLD);

    expect(rows).toHaveLength(12);
    expect(rows.every((row) => row.aired)).toBe(true);
  });
});
```

`web/src/ui/Menu.test.tsx`:
```tsx
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MenuButton, type MenuItem } from "./Menu";

afterEach(cleanup);

function statusItems(pick: (key: string) => void): MenuItem[] {
  return [
    { key: "watching", label: "Смотрю", checked: true, onSelect: () => pick("watching") },
    { key: "planned", label: "В планах", checked: false, onSelect: () => pick("planned") },
    { key: "dropped", label: "Брошено", checked: false, onSelect: () => pick("dropped") },
  ];
}

function renderStatus(options: { disabled?: boolean } = {}) {
  const pick = vi.fn();
  render(
    <>
      <MenuButton label="Смотрю" items={statusItems(pick)} disabled={options.disabled ?? false} />
      <button type="button">Снаружи</button>
    </>,
  );
  return pick;
}

describe("MenuButton", () => {
  it("opens a menu named by its button with the ticked choice focused", async () => {
    const user = userEvent.setup();
    renderStatus();
    const trigger = screen.getByRole("button", { name: "Смотрю" });
    expect(trigger).toHaveAttribute("aria-haspopup", "menu");
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);

    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("menu", { name: "Смотрю" })).toBeInTheDocument();
    expect(screen.getAllByRole("menuitemradio").map((item) => item.textContent)).toEqual(["Смотрю", "В планах", "Брошено"]);
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("menuitemradio", { name: "В планах" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
  });

  it("moves with the arrow keys, Home and End, and picks with Enter", async () => {
    const user = userEvent.setup();
    const pick = renderStatus();
    await user.tab();
    expect(screen.getByRole("button", { name: "Смотрю" })).toHaveFocus();

    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "В планах" })).toHaveFocus();
    await user.keyboard("{ArrowDown}{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
    await user.keyboard("{ArrowUp}");
    expect(screen.getByRole("menuitemradio", { name: "Брошено" })).toHaveFocus();
    await user.keyboard("{Home}");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
    await user.keyboard("{End}{Enter}");

    expect(pick).toHaveBeenCalledWith("dropped");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Смотрю" })).toHaveFocus();
  });

  it("closes on Escape and gives focus back to its button", async () => {
    const user = userEvent.setup();
    const pick = renderStatus();
    await user.click(screen.getByRole("button", { name: "Смотрю" }));

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Смотрю" })).toHaveFocus();
    expect(pick).not.toHaveBeenCalled();
  });

  it("closes when something outside it is pressed", async () => {
    const user = userEvent.setup();
    renderStatus();
    await user.click(screen.getByRole("button", { name: "Смотрю" }));

    await user.click(screen.getByRole("button", { name: "Снаружи" }));

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("offers plain actions as menu items behind an icon button", async () => {
    const user = userEvent.setup();
    const watch = vi.fn();
    const unmark = vi.fn();
    render(
      <MenuButton
        variant="icon"
        ariaLabel="Что сделать с серией 5"
        label={<svg aria-hidden="true" />}
        items={[
          { key: "watch", label: "Смотреть", onSelect: watch },
          { key: "unmark", label: "Отметить непросмотренной", destructive: true, onSelect: unmark },
        ]}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Что сделать с серией 5" }));

    expect(screen.getByRole("menu", { name: "Что сделать с серией 5" })).toBeInTheDocument();
    expect(screen.getAllByRole("menuitem").map((item) => item.textContent)).toEqual(["Смотреть", "Отметить непросмотренной"]);
    await user.click(screen.getByRole("menuitem", { name: "Отметить непросмотренной" }));
    expect(unmark).toHaveBeenCalledTimes(1);
    expect(watch).not.toHaveBeenCalled();
  });

  it("stays shut while disabled", async () => {
    const user = userEvent.setup();
    renderStatus({ disabled: true });
    const trigger = screen.getByRole("button", { name: "Смотрю" });

    expect(trigger).toBeDisabled();
    await user.click(trigger);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });
});
```

`web/src/screens/TitleScreen.test.tsx`:
```tsx
// The title screen over the real Library, ProgressStore and ToastProvider with a fake Shikimori.
// Copy and rules: web-map 4; android/src/main/java/app/kaeru/ui/mobile/details/DetailsScreen.kt,
// EpisodeSection.kt; ios/Features/DetailView.swift.
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useParams } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext, type Services } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, EpisodeProgress, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { ToastProvider } from "../ui/Toast";
import { TitleScreen } from "./TitleScreen";

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (key: string) => {
      data.delete(key);
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value));
    },
  };
}

/** A domain Anime: Task 5 has already cleaned the description. */
function deathNote(overrides: Partial<Anime> = {}): Anime {
  return {
    id: 1535,
    title: "Тетрадь смерти",
    originalTitle: "Death Note",
    posterUrl: "https://shikimori.io/uploads/poster/animes/1535/poster.jpeg",
    backdropUrl: "https://shikimori.io/system/screenshots/original/1535.jpg",
    status: "released",
    episodes: 37,
    episodesAired: 0,
    year: 2006,
    score: 8.62,
    kind: "tv",
    studios: ["Madhouse"],
    description: "Ягами Лайт находит Тетрадь смерти.",
    nextEpisodeAt: null,
    ...overrides,
  };
}

function stopped(episode: number, positionMs: number, durationMs = 1_400_000): EpisodeProgress {
  return { animeId: 1535, episode, positionMs, durationMs, updatedAt: 0 };
}

function fieldsText(fields: { status?: ListStatus; episodes?: number }): string {
  return Object.entries(fields)
    .map(([name, value]) => `${name}=${String(value)}`)
    .join(" ");
}

class FakeShikimori {
  readonly calls: string[] = [];
  rates: UserRate[] = [];
  cards: Anime[] = [];
  details: Anime = deathNote();
  detailsFailures = 0;
  /** While set, details wait for it. */
  detailsGate: Promise<void> | null = null;
  /** One entry per write, in order; a truthy entry is thrown by that write. */
  failures: unknown[] = [];
  private nextId = 900;

  writes(): string[] {
    return this.calls.filter((call) => call.startsWith("POST") || call.startsWith("PATCH"));
  }

  api(): Shikimori {
    const unused = async (): Promise<never> => {
      throw new Error("not used by the title screen");
    };
    return {
      whoami: unused,
      search: unused,
      popularNow: unused,
      popularInSeason: unused,
      details: async (id) => {
        this.calls.push(`details ${id}`);
        if (this.detailsGate) await this.detailsGate;
        if (this.detailsFailures > 0) {
          this.detailsFailures -= 1;
          throw new NetworkError("Failed to fetch");
        }
        return this.details;
      },
      byIds: async (ids) => this.cards.filter((card) => ids.includes(card.id)),
      userRates: async () => this.rates.map((rate) => ({ ...rate })),
      createRate: async (_token, userId, animeId, fields) => {
        this.calls.push(`POST ${userId}/${animeId} ${fieldsText(fields)}`);
        const failure = this.failures.shift();
        if (failure) throw failure;
        const rate: UserRate = { id: this.nextId++, animeId, status: fields.status, episodes: fields.episodes ?? 0, updatedAt: 5 };
        this.rates.push(rate);
        return { ...rate };
      },
      updateRate: async (_token, rateId, fields) => {
        this.calls.push(`PATCH ${rateId} ${fieldsText(fields)}`);
        const failure = this.failures.shift();
        if (failure) throw failure;
        const index = this.rates.findIndex((rate) => rate.id === rateId);
        const current = this.rates[index];
        if (!current) throw new ApiError(404);
        const rate: UserRate = { ...current, ...fields, updatedAt: 5 };
        this.rates[index] = rate;
        return { ...rate };
      },
    };
  }
}

const fakeAuthorized: typeof authorized = (call) => call("tok");

let server: FakeShikimori;
let progress: ProgressStore;
let services: Services;

function start(details: Anime, rate?: { status: ListStatus; episodes: number }, rows: EpisodeProgress[] = []): void {
  server = new FakeShikimori();
  server.details = details;
  server.cards = [details];
  if (rate) server.rates = [{ id: 1, animeId: details.id, status: rate.status, episodes: rate.episodes, updatedAt: 1 }];
  progress = new ProgressStore(memoryStorage());
  for (const row of rows) progress.put(row);
  const shikimori = server.api();
  const library = new Library({ shikimori, authorized: fakeAuthorized, accountId: () => 42, progress });
  services = { shikimori, library, progress };
}

function WatchProbe() {
  const { id, episode } = useParams();
  return <p>{`watch ${id ?? ""}/${episode ?? ""}`}</p>;
}

function renderTitle(id = 1535): void {
  render(
    <ServicesContext.Provider value={services}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/anime/${id}`]}>
          <Routes>
            <Route path="/" element={<p>home</p>} />
            <Route path="/anime/:id" element={<TitleScreen />} />
            <Route path="/watch/:id/:episode" element={<WatchProbe />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </ServicesContext.Provider>,
  );
}

type User = ReturnType<typeof userEvent.setup>;

async function pick(user: User, episode: number, action: string): Promise<void> {
  const more = await screen.findByRole("button", { name: `Что сделать с серией ${episode}` });
  await waitFor(() => expect(more).toBeEnabled());
  await user.click(more);
  await user.click(within(screen.getByRole("menu")).getByRole("menuitem", { name: action }));
}

afterEach(cleanup);

describe("TitleScreen", () => {
  it("shows the artwork header: title, original title and the facts line", async () => {
    start(deathNote());
    renderTitle();

    expect(await screen.findByRole("heading", { level: 1, name: "Тетрадь смерти" })).toBeInTheDocument();
    expect(screen.getByText("Death Note")).toBeInTheDocument();
    expect(screen.getByText("Вышло · 2006 · 37 эп. · ★ 8.62 · Сериал · Madhouse")).toBeInTheDocument();
    expect(server.calls).toContain("details 1535");
  });

  it("says it is loading while the title comes", async () => {
    start(deathNote());
    server.detailsGate = new Promise(() => undefined);
    renderTitle();

    const label = await screen.findByText("Обновляем информацию…");
    expect(label.closest("[role='status']")).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
  });

  it("starts a title that is in no list from its first episode", async () => {
    const user = userEvent.setup();
    start(deathNote());
    renderTitle();

    await user.click(await screen.findByRole("link", { name: "Смотреть 1 серию" }));

    expect(await screen.findByText("watch 1535/1")).toBeInTheDocument();
    expect(server.writes()).toEqual([]);
  });

  it("continues where this browser stopped", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 }, [stopped(4, 754_000)]);
    renderTitle();

    await user.click(await screen.findByRole("link", { name: "Продолжить с 12:34" }));

    expect(await screen.findByText("watch 1535/4")).toBeInTheDocument();
  });

  it("goes home from «Назад» when the page was opened directly", async () => {
    const user = userEvent.setup();
    start(deathNote());
    renderTitle();

    await user.click(await screen.findByRole("button", { name: "Назад" }));

    expect(await screen.findByText("home")).toBeInTheDocument();
  });

  it("adds a title that is in no list to the plans and keeps focus on the status", async () => {
    const user = userEvent.setup();
    start(deathNote());
    renderTitle();
    const add = await screen.findByRole("button", { name: "Добавить в планы" });
    await waitFor(() => expect(add).toBeEnabled());

    await user.click(add);

    await waitFor(() => expect(screen.getByRole("button", { name: "В планах" })).toHaveFocus());
    await waitFor(() => expect(server.writes()).toEqual(["POST 42/1535 status=planned"]));
  });

  it("changes the status from a menu in the Android order with the current one ticked", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    renderTitle();
    const status = await screen.findByRole("button", { name: "Смотрю" });
    await waitFor(() => expect(status).toBeEnabled());

    await user.click(status);

    const menu = screen.getByRole("menu");
    expect(within(menu).getAllByRole("menuitemradio").map((item) => item.textContent)).toEqual([
      "Смотрю",
      "В планах",
      "Завершено",
      "Отложено",
      "Брошено",
      "Пересматриваю",
    ]);
    expect(within(menu).getByRole("menuitemradio", { name: "Смотрю" })).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{ArrowDown}{ArrowDown}{ArrowDown}{Enter}");
    expect(await screen.findByRole("button", { name: "Отложено" })).toHaveFocus();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 status=on_hold"]));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("lists the episodes as rows with what each one says", async () => {
    start(deathNote(), { status: "watching", episodes: 3 }, [stopped(4, 754_000)]);
    renderTitle();

    expect(await screen.findByText("Просмотрено 3 из 37")).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "Серии" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(37);
    expect(within(list).getByRole("link", { name: "3 серия, просмотрено" })).toHaveAttribute("href", "/watch/1535/3");
    expect(within(list).getByRole("link", { name: "4 серия, остановились на 12:34" })).toHaveAttribute(
      "href",
      "/watch/1535/4",
    );
    expect(within(list).getByRole("link", { name: "5 серия" })).toHaveAttribute("href", "/watch/1535/5");
    expect(within(list).getByText("12:34")).toBeInTheDocument();
    expect(list.querySelectorAll(".title-episode-progress")).toHaveLength(1);
  });

  it("plays an episode from its row", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    renderTitle();

    await user.click(await screen.findByRole("link", { name: "7 серия" }));

    expect(await screen.findByText("watch 1535/7")).toBeInTheDocument();
  });

  it("pages the rows sixty at a time", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 130 }));
    renderTitle();
    const list = await screen.findByRole("list", { name: "Серии" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(60);

    await user.click(screen.getByRole("button", { name: "Показать ещё 60 серий" }));
    expect(within(list).getAllByRole("listitem")).toHaveLength(120);

    await user.click(screen.getByRole("button", { name: "Показать ещё 10 серий" }));
    expect(within(list).getAllByRole("listitem")).toHaveLength(130);

    await user.click(screen.getByRole("button", { name: "Свернуть серии" }));
    expect(within(list).getAllByRole("listitem")).toHaveLength(60);
  });

  it("shows episodes that have not aired as plain rows without a link or a menu", async () => {
    start(deathNote({ status: "ongoing", episodes: 12, episodesAired: 5 }), { status: "watching", episodes: 3 });
    renderTitle();

    const list = await screen.findByRole("list", { name: "Серии" });
    expect(within(list).getByText("6 серия, не вышла")).toBeInTheDocument();
    expect(within(list).queryByRole("link", { name: /^6 серия/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Что сделать с серией 6" })).not.toBeInTheDocument();
    expect(within(list).getByRole("link", { name: "5 серия" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Что сделать с серией 5" })).toBeInTheDocument();
  });

  it("waits for a title that has not aired yet with a disabled button", async () => {
    start(deathNote({ status: "anons", episodes: 12, episodesAired: 0 }));
    renderTitle();

    expect(await screen.findByRole("button", { name: "Ещё не вышло" })).toBeDisabled();
    expect(screen.queryByRole("link", { name: /Смотреть/ })).not.toBeInTheDocument();
  });

  it("marks an episode watched from its row menu", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    renderTitle();
    await screen.findByText("Просмотрено 3 из 37");

    await pick(user, 5, "Отметить просмотренной");

    expect(await screen.findByText("Просмотрено 5 из 37")).toBeInTheDocument();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=5"]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("offers to complete the title after its last episode", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "watching", episodes: 11 });
    renderTitle();
    await screen.findByText("Просмотрено 11 из 12");

    await pick(user, 12, "Отметить просмотренной");

    const dialog = await screen.findByRole("dialog", { name: "Перевести аниме в завершённые?" });
    expect(within(dialog).getByText("Тетрадь смерти")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Позже" })).toHaveFocus();
    await user.click(within(dialog).getByRole("button", { name: "Завершить просмотр" }));
    expect(await screen.findByRole("button", { name: "Завершено" })).toBeInTheDocument();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=12", "PATCH 1 status=completed"]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("lets the finale wait for later without changing the status", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "watching", episodes: 11 });
    renderTitle();
    await screen.findByText("Просмотрено 11 из 12");

    await pick(user, 12, "Отметить просмотренной");
    const dialog = await screen.findByRole("dialog", { name: "Перевести аниме в завершённые?" });
    await user.click(within(dialog).getByRole("button", { name: "Позже" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(server.writes()).toEqual(["PATCH 1 episodes=12"]);
    expect(screen.getByRole("button", { name: "Смотрю" })).toBeInTheDocument();
  });

  it("does not ask to complete a title that is already completed", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "completed", episodes: 10 });
    renderTitle();
    await screen.findByText("Просмотрено 10 из 12");

    await pick(user, 12, "Отметить просмотренной");

    expect(await screen.findByText("Просмотрено 12 из 12")).toBeInTheDocument();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=12"]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("unmarks without asking and undoes it from the toast", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "watching", episodes: 7 }, [stopped(5, 600_000), stopped(6, 300_000)]);
    renderTitle();
    await screen.findByText("Просмотрено 7 из 12");

    await pick(user, 5, "Отметить непросмотренной");

    expect(await screen.findByText("Серия 5 отмечена непросмотренной")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(server.writes()).toEqual(["PATCH 1 episodes=4"]);
    expect(screen.getByText("Просмотрено 4 из 12")).toBeInTheDocument();
    expect(progress.of(1535)).toEqual([]);

    await user.click(screen.getByRole("button", { name: "Отменить" }));

    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=4", "PATCH 1 episodes=7"]));
    await waitFor(() => expect(progress.of(1535).map((row) => row.episode)).toEqual([5, 6]));
    expect(await screen.findByText("Просмотрено 7 из 12")).toBeInTheDocument();
    expect(screen.queryByText("Серия 5 отмечена непросмотренной")).not.toBeInTheDocument();
  });

  it("puts a mark back and says why when Shikimori refuses it", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    server.failures = [new ApiError(500)];
    renderTitle();
    await screen.findByText("Просмотрено 3 из 37");

    await pick(user, 5, "Отметить просмотренной");

    expect(await screen.findByText("Shikimori недоступен, попробуйте позже")).toBeInTheDocument();
    expect(await screen.findByText("Просмотрено 3 из 37")).toBeInTheDocument();
    expect(server.writes()).toEqual(["PATCH 1 episodes=5"]);
  });

  it("offers a retry when the title cannot be loaded", async () => {
    const user = userEvent.setup();
    start(deathNote());
    server.detailsFailures = 1;
    renderTitle();

    expect(await screen.findByText("Не удалось загрузить аниме. Проверьте соединение и повторите")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Тетрадь смерти" })).toBeInTheDocument();
  });

  describe("about", () => {
    afterEach(() => {
      Reflect.deleteProperty(HTMLElement.prototype, "scrollHeight");
      Reflect.deleteProperty(HTMLElement.prototype, "clientHeight");
    });

    it("clamps a long description to four lines until asked for the rest", async () => {
      // jsdom has no layout: pretend the clamped paragraph hides text.
      Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
        configurable: true,
        get(this: HTMLElement) {
          return this.dataset.clamp === "about" ? 400 : 0;
        },
      });
      Object.defineProperty(HTMLElement.prototype, "clientHeight", {
        configurable: true,
        get(this: HTMLElement) {
          return this.dataset.clamp === "about" ? 88 : 0;
        },
      });
      const user = userEvent.setup();
      start(deathNote());
      renderTitle();

      expect(await screen.findByRole("heading", { level: 2, name: "Об аниме" })).toBeInTheDocument();
      expect(screen.getByText("Ягами Лайт находит Тетрадь смерти.")).toBeInTheDocument();
      const more = await screen.findByRole("button", { name: "Читать полностью" });
      expect(more).toHaveAttribute("aria-expanded", "false");

      await user.click(more);
      const less = screen.getByRole("button", { name: "Свернуть" });
      expect(less).toHaveAttribute("aria-expanded", "true");

      await user.click(less);
      expect(await screen.findByRole("button", { name: "Читать полностью" })).toBeInTheDocument();
    });

    it("offers nothing to expand when the description fits", async () => {
      start(deathNote());
      renderTitle();

      expect(await screen.findByRole("heading", { level: 2, name: "Об аниме" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Читать полностью" })).not.toBeInTheDocument();
    });

    it("shows the description exactly as the API client left it", async () => {
      // Task 5 already cleaned it; a second pass would eat these brackets and decode &lt; again.
      start(deathNote({ description: "Формула: a &lt; b, [сноска] и [[ссылка]]" }));
      renderTitle();

      expect(await screen.findByText("Формула: a &lt; b, [сноска] и [[ссылка]]")).toBeInTheDocument();
    });

    it("leaves the section out when there is no description", async () => {
      start(deathNote({ description: null }));
      renderTitle();

      expect(await screen.findByRole("heading", { level: 1, name: "Тетрадь смерти" })).toBeInTheDocument();
      expect(screen.queryByRole("heading", { level: 2, name: "Об аниме" })).not.toBeInTheDocument();
    });
  });
});
```

- [ ] **Step 2: Run it to see it fail**

From `web/`:
```
npx vitest run src/screens/episodeRows.test.ts src/ui/Menu.test.tsx src/screens/TitleScreen.test.tsx
```
**Expected:** FAIL, with `Test Files  3 failed (3)` and `Tests  23 failed (23)`.
- `src/screens/episodeRows.test.ts` fails at import: `Error: Failed to resolve import "./episodeRows" from "src/screens/episodeRows.test.ts". Does the file exist?`
- `src/ui/Menu.test.tsx` fails at import: `Error: Failed to resolve import "./Menu" from "src/ui/Menu.test.tsx". Does the file exist?`
- `src/screens/TitleScreen.test.tsx` loads, because Task 8's stub `TitleScreen.tsx` exists. All 23 of its tests fail on assertions against the stub, whose only content is `<h1>Аниме</h1>`. For example: `TestingLibraryElementError: Unable to find role="heading" and name "Тетрадь смерти"`, `Unable to find role="link" and name "Смотреть 1 серию"`, and `Unable to find an element with the text: Просмотрено 3 из 37`. Each one waits for the default one-second `findBy` timeout, so the file takes about 23 s.

- [ ] **Step 3: Implement**

`web/src/screens/episodeRows.ts`:
```ts
import { availableEpisodes, type Anime, type EpisodeProgress, type LibraryEntry } from "../domain/models";
import { episodeFraction, isStarted, progressAt } from "../domain/progress";

/** Rows shown at first and added per «Показать ещё». */
export const EPISODE_PAGE = 60;

export interface EpisodeRow {
  number: number;
  /** Shikimori's count has reached it. */
  watched: boolean;
  /** There is something to play. */
  aired: boolean;
  /** Where this browser stopped, or null when there is nothing worth drawing. */
  fraction: number | null;
  /** The stop behind `fraction`; 0 whenever `fraction` is null. */
  positionMs: number;
}

/**
 * Every episode the title announced, marked with what is behind the viewer, what they are in the
 * middle of and what has not arrived yet (Android EpisodeGrid.episodeCells).
 */
export function episodeRows(
  anime: Anime,
  entry: LibraryEntry | null,
  progress: readonly EpisodeProgress[],
  threshold: number,
): EpisodeRow[] {
  const own = progress.filter((row) => row.animeId === anime.id);
  // A title in no list borrows an empty rate, so the rules stay the ones the watch button uses.
  const effective: LibraryEntry = {
    anime,
    rate: entry?.rate ?? { id: 0, animeId: anime.id, status: "planned", episodes: 0, updatedAt: 0 },
  };
  const seen = entry?.rate.episodes ?? 0;
  // Only a really started episode stretches the list past the catalogue; a mis-tap must not.
  const started = own.filter(isStarted).reduce((highest, row) => Math.max(highest, row.episode), 0);
  const playable = Math.max(availableEpisodes(anime), seen, started);
  const total = Math.max(anime.episodes, playable);
  const rows: EpisodeRow[] = [];
  for (let number = 1; number <= total; number += 1) {
    const fraction = episodeFraction(effective, own, number, threshold);
    rows.push({
      number,
      watched: number <= seen,
      aired: number <= playable,
      fraction,
      positionMs: fraction === null ? 0 : (progressAt(own, number)?.positionMs ?? 0),
    });
  }
  return rows;
}
```

`web/src/ui/Menu.tsx`:
```tsx
import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { IconButton } from "./Button";
import { IconArrowDropDown, IconCheck } from "./icons";
import { Pill } from "./Pill";
import "./Menu.css";

export interface MenuItem {
  key: string;
  label: string;
  /** Set for one choice among several (menuitemradio); left out for a plain action. */
  checked?: boolean;
  destructive?: boolean;
  onSelect: () => void;
}

export interface MenuButtonProps {
  /** The pill's text, or the glyph of an icon button. */
  label: ReactNode;
  /** The name of an icon button; required with `variant="icon"`. */
  ariaLabel?: string;
  items: readonly MenuItem[];
  disabled?: boolean;
  variant?: "pill" | "icon";
}

const ITEM = "[role^='menuitem']";

/**
 * A button that opens a list of actions or choices (WAI-ARIA menu button). The trigger is Task 8's
 * Pill (with a drop-down glyph) or IconButton, so it looks like every other pill and icon button.
 */
export function MenuButton({ label, ariaLabel, items, disabled = false, variant = "pill" }: MenuButtonProps) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const buttonId = useId();
  const menuId = useId();

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  useEffect(() => {
    if (!open) return;
    const menu = list.current;
    // Start on the current choice, so Enter on an unchanged menu keeps it.
    const start = menu?.querySelector<HTMLElement>("[aria-checked='true']") ?? menu?.querySelector<HTMLElement>(ITEM);
    start?.focus();
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && root.current?.contains(event.target)) return;
      setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const close = () => {
    setOpen(false);
    // The trigger is the first child: the keyboard goes back where it came from.
    (root.current?.firstElementChild as HTMLElement | null)?.focus();
  };

  const onMenuKeyDown = (event: KeyboardEvent<HTMLUListElement>) => {
    const nodes = Array.from(list.current?.querySelectorAll<HTMLElement>(ITEM) ?? []);
    const index = nodes.findIndex((node) => node === document.activeElement);
    const focusAt = (next: number) => {
      event.preventDefault();
      nodes[(next + nodes.length) % nodes.length]?.focus();
    };
    switch (event.key) {
      case "ArrowDown":
        focusAt(index + 1);
        break;
      case "ArrowUp":
        focusAt(index <= 0 ? nodes.length - 1 : index - 1);
        break;
      case "Home":
        focusAt(0);
        break;
      case "End":
        focusAt(nodes.length - 1);
        break;
      case "Escape":
        event.preventDefault();
        close();
        break;
      case "Tab":
        setOpen(false);
        break;
      default:
        break;
    }
  };

  const onTriggerKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
    }
  };

  const triggerProps = {
    id: buttonId,
    "aria-haspopup": "menu" as const,
    "aria-expanded": open,
    "aria-controls": open ? menuId : undefined,
    disabled,
    onClick: () => setOpen((value) => !value),
    onKeyDown: onTriggerKeyDown,
  };

  return (
    <div className="menu" ref={root}>
      {variant === "icon" ? (
        <IconButton className="menu-trigger" label={ariaLabel ?? ""} icon={label} {...triggerProps} />
      ) : (
        <Pill className="menu-trigger" trailing={<IconArrowDropDown />} aria-label={ariaLabel} {...triggerProps}>
          {label}
        </Pill>
      )}
      {open && (
        <ul
          id={menuId}
          ref={list}
          role="menu"
          aria-labelledby={buttonId}
          className={`menu-list${variant === "icon" ? " menu-list-end" : ""}`}
          onKeyDown={onMenuKeyDown}
        >
          {items.map((item) => (
            <li key={item.key} role="none">
              <button
                type="button"
                role={item.checked === undefined ? "menuitem" : "menuitemradio"}
                aria-checked={item.checked}
                tabIndex={-1}
                className={`menu-item${item.destructive ? " menu-item-destructive" : ""}`}
                onClick={() => {
                  close();
                  item.onSelect();
                }}
              >
                <span>{item.label}</span>
                {item.checked && <IconCheck className="menu-item-tick" size={18} />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
```

`web/src/ui/Menu.css`:
```css
/* Menu button and its popup: the status menu and the episode row menus. The triggers are Task 8's
   .pill and .icon-button; rules here are scoped under .menu so they win whatever the file order. */
.menu {
  position: relative;
  display: inline-flex;
}

/* Task 8's pill has no disabled look; a menu that waits for the list shows it is waiting. */
.menu .menu-trigger:disabled {
  opacity: 0.5;
  cursor: default;
}

/* The row menus are secondary to the row itself. */
.menu .icon-button {
  color: var(--ink-soft);
}

.menu-list {
  position: absolute;
  top: calc(100% + var(--s1));
  left: 0;
  z-index: 30;
  min-width: 232px;
  margin: 0;
  padding: var(--s1) 0;
  list-style: none;
  background: var(--elevated);
  border: 1px solid var(--line);
  border-radius: var(--r-card);
}

.menu-list-end {
  left: auto;
  right: 0;
}

.menu-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  width: 100%;
  min-height: 44px;
  padding: 0 var(--s4);
  border: 0;
  background: none;
  color: var(--ink);
  font: inherit;
  font-size: 15px;
  line-height: 22px;
  text-align: left;
  cursor: pointer;
}

.menu-item:hover {
  background: var(--line);
}

/* Full-width row: ring inside, no scale. */
.menu-item:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: -3px;
  background: var(--line);
}

.menu-item-destructive {
  color: var(--error);
}

.menu-item-tick {
  flex: none;
  color: var(--accent);
}
```

`web/src/screens/TitleScreen.tsx`:
```tsx
import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { errorMessage } from "../api/http";
import { useServices } from "../app/services";
import { primaryAction } from "../domain/actions";
import { factsLine, formatTime, pluralEpisodesAccusative, statusLabel } from "../domain/format";
import { STATUS_MENU, type Anime, type EpisodeProgress, type LibraryEntry, type ListStatus } from "../domain/models";
import { useLibrary, type LibraryState } from "../library/library";
import { watchedThreshold } from "../library/prefs";
import { IconButton, PrimaryButton, SecondaryButton, TextAction } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import { IconBack, IconCheckCircle, IconClock, IconMore, IconPlay, IconPlayCircle } from "../ui/icons";
import { MenuButton, type MenuItem } from "../ui/Menu";
import { SkeletonBlock, SkeletonGroup } from "../ui/Skeleton";
import { ErrorState } from "../ui/States";
import { useToast } from "../ui/Toast";
import { EPISODE_PAGE, episodeRows, type EpisodeRow } from "./episodeRows";
import "./TitleScreen.css";

const LOAD_FAILED = "Не удалось загрузить аниме. Проверьте соединение и повторите";

type Details = { kind: "loading" } | { kind: "ready"; anime: Anime } | { kind: "failed" };

// Writing before the list is read could create a rate Shikimori already holds.
function listKnown(state: LibraryState): boolean {
  return state.kind !== "idle" && state.entries !== null;
}

function watchPath(animeId: number, episode: number): string {
  return `/watch/${animeId}/${episode}`;
}

/** /anime/:id — details, list status and episode marks (map 4, decisions 4–7 and 9). */
export function TitleScreen() {
  const { id } = useParams();
  const animeId = Number(id);
  const { shikimori, library, progress } = useServices();
  const [details, setDetails] = useState<Details>({ kind: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [threshold] = useState(() => watchedThreshold());
  const libraryState = useLibrary(library);
  const entry = useSyncExternalStore(library.subscribe, () => library.entry(animeId));
  const rows = useSyncExternalStore(progress.subscribe, () => progress.of(animeId));

  useEffect(() => {
    // The signed-in shell starts the list too; whoever comes first starts it once.
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);

  useEffect(() => {
    if (!Number.isInteger(animeId) || animeId <= 0) {
      setDetails({ kind: "failed" });
      return;
    }
    let live = true;
    setDetails({ kind: "loading" });
    shikimori.details(animeId).then(
      (anime) => {
        if (live) setDetails({ kind: "ready", anime });
      },
      () => {
        if (live) setDetails({ kind: "failed" });
      },
    );
    return () => {
      live = false;
    };
  }, [shikimori, animeId, attempt]);

  const retry = () => setAttempt((count) => count + 1);
  // Details are the full card; the list's card is enough to draw while they load.
  const anime = details.kind === "ready" && details.anime.id === animeId ? details.anime : (entry?.anime ?? null);

  if (anime === null) {
    return details.kind === "failed" ? (
      <div className="title title-failed">
        <ErrorState message={LOAD_FAILED} onRetry={retry} />
      </div>
    ) : (
      <TitleSkeleton />
    );
  }
  return (
    <TitleContent
      key={anime.id}
      anime={anime}
      entry={entry}
      rows={rows}
      threshold={threshold}
      known={listKnown(libraryState)}
      failed={details.kind === "failed"}
      onRetry={retry}
    />
  );
}

interface ContentProps {
  anime: Anime;
  entry: LibraryEntry | undefined;
  rows: readonly EpisodeProgress[];
  threshold: number;
  known: boolean;
  failed: boolean;
  onRetry: () => void;
}

function TitleContent({ anime, entry, rows, threshold, known, failed, onRetry }: ContentProps) {
  const { library } = useServices();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const controls = useRef<HTMLDivElement>(null);
  const [completion, setCompletion] = useState(false);
  const [focusStatus, setFocusStatus] = useState(false);

  useEffect(() => {
    // «Добавить в планы» turns into the status menu; keep the keyboard where it was.
    if (focusStatus && entry) {
      controls.current?.querySelector<HTMLButtonElement>("button")?.focus();
      setFocusStatus(false);
    }
  }, [focusStatus, entry]);

  const action = primaryAction(entry ?? null, anime, rows, threshold, Date.now());
  const facts = factsLine(anime);
  // Task 5 already cleaned the text; a second pass would decode entities twice.
  const description = anime.description;
  const artwork = anime.backdropUrl ?? anime.posterUrl;
  const current = entry?.rate.status;

  const fail = (error: unknown): void => {
    toast.show(errorMessage(error));
  };
  const back = () => {
    // A deep link has no page of ours to go back to.
    if (location.key !== "default") void navigate(-1);
    else void navigate("/");
  };
  const play = (episode: number) => {
    void navigate(watchPath(anime.id, episode));
  };
  const changeStatus = (status: ListStatus) => {
    library.setStatus(anime, status).catch(fail);
  };
  const addToPlans = () => {
    setFocusStatus(true);
    changeStatus("planned");
  };
  const markWatched = (episode: number) => {
    library.markWatched(anime, episode).then(({ suggestCompleted }) => {
      // Only ever a suggestion; an already completed title has nothing to ask.
      if (suggestCompleted && library.entry(anime.id)?.rate.status !== "completed") setCompletion(true);
    }, fail);
  };
  const markUnwatched = (episode: number) => {
    library.markUnwatched(anime, episode).then((undo) => {
      // No confirmation first: the undo is the better one (decision 6).
      toast.show(`Серия ${episode} отмечена непросмотренной`, {
        label: "Отменить",
        onClick: () => {
          undo().catch(fail);
        },
      });
    }, fail);
  };
  const complete = () => {
    setCompletion(false);
    changeStatus("completed");
  };

  const statusItems: MenuItem[] = STATUS_MENU.map((status) => ({
    key: status,
    label: statusLabel(status),
    checked: status === current,
    onSelect: () => changeStatus(status),
  }));

  return (
    <div className="title">
      <header className="title-hero">
        <div className="title-hero-art" aria-hidden="true">
          {artwork !== null && (
            <img
              className={anime.backdropUrl !== null ? "title-hero-img" : "title-hero-img title-hero-img-soft"}
              src={artwork}
              alt=""
              decoding="async"
            />
          )}
        </div>
        <IconButton className="title-back" label="Назад" icon={<IconBack />} overArt onClick={back} />
        <div className="title-hero-text">
          <h1 className="t-display title-name">{anime.title}</h1>
          {anime.originalTitle !== "" && anime.originalTitle !== anime.title && (
            <p className="t-body title-original">{anime.originalTitle}</p>
          )}
          {facts !== "" && <p className="t-label title-facts">{facts}</p>}
          <div className="title-play">
            {action.enabled ? (
              // Navigation is a link (Task 8); plan 3 puts the player behind it.
              <PrimaryButton to={watchPath(anime.id, action.episode)} icon={<IconPlay />}>
                {action.label}
              </PrimaryButton>
            ) : (
              <PrimaryButton disabled icon={<IconClock />}>
                {action.label}
              </PrimaryButton>
            )}
          </div>
        </div>
      </header>
      <div className="title-body">
        {failed && <ErrorState message={LOAD_FAILED} onRetry={onRetry} align="start" />}
        <div className="title-controls" ref={controls}>
          {entry ? (
            <MenuButton label={statusLabel(entry.rate.status)} items={statusItems} disabled={!known} />
          ) : (
            <SecondaryButton disabled={!known} onClick={addToPlans}>
              Добавить в планы
            </SecondaryButton>
          )}
        </div>
        {description !== null && description !== "" && <About text={description} />}
        <Episodes
          anime={anime}
          entry={entry}
          rows={rows}
          threshold={threshold}
          known={known}
          onPlay={play}
          onMark={markWatched}
          onUnmark={markUnwatched}
        />
      </div>
      <Dialog
        open={completion}
        title="Перевести аниме в завершённые?"
        text={anime.title}
        confirmLabel="Завершить просмотр"
        cancelLabel="Позже"
        onConfirm={complete}
        onCancel={() => setCompletion(false)}
      />
    </div>
  );
}

function About({ text }: { text: string }) {
  const headingId = useId();
  const textId = useId();
  const paragraph = useRef<HTMLParagraphElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [clipped, setClipped] = useState(false);

  useLayoutEffect(() => {
    if (expanded) return;
    const measure = () => {
      const node = paragraph.current;
      if (node) setClipped(node.scrollHeight > node.clientHeight + 1);
    };
    measure();
    window.addEventListener("resize", measure);
    // Manrope arriving late changes how many lines the text takes.
    void document.fonts?.ready.then(measure);
    return () => window.removeEventListener("resize", measure);
  }, [text, expanded]);

  return (
    <section className="title-section" aria-labelledby={headingId}>
      <h2 id={headingId} className="t-title title-section-head">
        Об аниме
      </h2>
      <p
        id={textId}
        ref={paragraph}
        data-clamp="about"
        className={`t-body title-about${expanded ? "" : " title-about-clamped"}`}
      >
        {text}
      </p>
      {(clipped || expanded) && (
        <TextAction aria-expanded={expanded} aria-controls={textId} onClick={() => setExpanded((open) => !open)}>
          {expanded ? "Свернуть" : "Читать полностью"}
        </TextAction>
      )}
    </section>
  );
}

interface EpisodesProps {
  anime: Anime;
  entry: LibraryEntry | undefined;
  rows: readonly EpisodeProgress[];
  threshold: number;
  known: boolean;
  onPlay: (episode: number) => void;
  onMark: (episode: number) => void;
  onUnmark: (episode: number) => void;
}

function Episodes({ anime, entry, rows, threshold, known, onPlay, onMark, onUnmark }: EpisodesProps) {
  const headingId = useId();
  const [visible, setVisible] = useState(EPISODE_PAGE);
  const all = episodeRows(anime, entry ?? null, rows, threshold);

  if (all.length === 0) {
    return (
      <section className="title-section" aria-labelledby={headingId}>
        <h2 id={headingId} className="t-title title-section-head">
          Серии
        </h2>
        <p className="t-title title-empty-title">Серии ещё не вышли</p>
        <p className="t-body title-muted">Добавьте аниме в планы, чтобы вернуться к нему позже.</p>
      </section>
    );
  }

  const rest = all.length - visible;
  // The label counts what pressing it adds, never a promise of «all» (Android EpisodeSection).
  const next = Math.min(EPISODE_PAGE, rest);
  return (
    <section className="title-section" aria-labelledby={headingId}>
      <h2 id={headingId} className="t-title title-section-head">
        Серии
      </h2>
      <p className="t-body title-muted">{`Просмотрено ${entry?.rate.episodes ?? 0} из ${all.length}`}</p>
      <ul className="title-episodes" aria-labelledby={headingId}>
        {all.slice(0, visible).map((row) => (
          <EpisodeItem
            key={row.number}
            animeId={anime.id}
            row={row}
            known={known}
            onPlay={onPlay}
            onMark={onMark}
            onUnmark={onUnmark}
          />
        ))}
      </ul>
      {rest > 0 ? (
        <TextAction onClick={() => setVisible((shown) => shown + next)}>
          {`Показать ещё ${pluralEpisodesAccusative(next)}`}
        </TextAction>
      ) : all.length > EPISODE_PAGE ? (
        <TextAction onClick={() => setVisible(EPISODE_PAGE)}>Свернуть серии</TextAction>
      ) : null}
    </section>
  );
}

interface EpisodeItemProps {
  animeId: number;
  row: EpisodeRow;
  known: boolean;
  onPlay: (episode: number) => void;
  onMark: (episode: number) => void;
  onUnmark: (episode: number) => void;
}

function EpisodeItem({ animeId, row, known, onPlay, onMark, onUnmark }: EpisodeItemProps) {
  const name = `${row.number} серия`;

  if (!row.aired) {
    // Nothing to play or mark yet: plain text, no link and no menu (Android has no menu here either).
    return (
      <li className="title-episode">
        <div className="title-episode-main title-episode-main-off">
          <span className="title-episode-icon" aria-hidden="true">
            <IconClock size={22} />
          </span>
          <span className="title-episode-name" aria-hidden="true">
            {name}
          </span>
          <span className="title-episode-caption" aria-hidden="true">
            Не вышла
          </span>
          <span className="visually-hidden">{`${name}, не вышла`}</span>
        </div>
      </li>
    );
  }

  const caption = row.fraction !== null ? formatTime(row.positionMs) : null;
  // One spoken sentence instead of a number and a fragment read separately.
  const spoken = row.watched
    ? `${name}, просмотрено`
    : caption !== null
      ? `${name}, остановились на ${caption}`
      : name;
  const items: MenuItem[] = [
    { key: "watch", label: "Смотреть", onSelect: () => onPlay(row.number) },
    row.watched
      ? { key: "unmark", label: "Отметить непросмотренной", destructive: true, onSelect: () => onUnmark(row.number) }
      : { key: "mark", label: "Отметить просмотренной", onSelect: () => onMark(row.number) },
  ];
  return (
    <li className="title-episode">
      <Link className="title-episode-main" to={watchPath(animeId, row.number)} aria-label={spoken}>
        <span className={`title-episode-icon${row.watched ? " title-episode-icon-watched" : ""}`} aria-hidden="true">
          {row.watched ? <IconCheckCircle size={22} /> : <IconPlayCircle size={22} />}
        </span>
        <span className="title-episode-name">{name}</span>
        {caption !== null && <span className="title-episode-caption">{caption}</span>}
      </Link>
      <MenuButton
        variant="icon"
        ariaLabel={`Что сделать с серией ${row.number}`}
        label={<IconMore />}
        items={items}
        disabled={!known}
      />
      {row.fraction !== null && (
        <span className="title-episode-progress" aria-hidden="true">
          <span style={{ width: `${Math.round(row.fraction * 100)}%` }} />
        </span>
      )}
    </li>
  );
}

function TitleSkeleton() {
  return (
    <div className="title">
      <SkeletonGroup label="Обновляем информацию…">
        <div className="title-hero">
          <div className="title-hero-text">
            <SkeletonBlock width="min(420px, 80%)" height={40} />
            <SkeletonBlock width="min(300px, 60%)" height={18} />
            <SkeletonBlock width={220} height={52} />
          </div>
        </div>
        <div className="title-body">
          {Array.from({ length: 6 }, (_, index) => (
            <SkeletonBlock key={index} height={44} />
          ))}
        </div>
      </SkeletonGroup>
    </div>
  );
}
```

`web/src/screens/TitleScreen.css`:
```css
/* Title screen: artwork header, controls, «Об аниме», episode rows (map 6, details).
   Buttons, the back disc, the dialog, the toast and the skeleton blocks are Task 8's; this file only
   lays them out. Rules that touch Task 8 classes are scoped under .title so they win in any order. */
.title {
  padding-bottom: var(--s8);
}

.title-failed {
  padding: var(--s8) var(--gutter);
}

.title-hero {
  position: relative;
  isolation: isolate;
  display: flex;
  align-items: flex-end;
  min-height: max(38vh, 280px);
  overflow: hidden;
  background: var(--surface);
}

.title-hero-art {
  position: absolute;
  inset: 0;
  z-index: -1;
}

.title-hero-img {
  display: block;
  width: 100%;
  height: 100%;
  max-width: none;
  object-fit: cover;
  animation: title-fade var(--normal) ease-out both;
}

/* No screenshot: a soft poster, as the iOS Backdrop does. */
.title-hero-img-soft {
  filter: blur(34px);
  opacity: 0.7;
  transform: scale(1.15);
}

/* Android Backdrop scrims: the artwork ends in the page colour, never in a seam. */
.title-hero-art::after {
  content: "";
  position: absolute;
  inset: 0;
  background:
    linear-gradient(
      180deg,
      transparent 30%,
      color-mix(in srgb, var(--bg) 78%, transparent) 68%,
      var(--bg) 100%
    ),
    linear-gradient(90deg, var(--bg) 0%, color-mix(in srgb, var(--bg) 72%, transparent) 28%, transparent 65%);
}

/* Task 8's IconButton with overArt draws the 42 % disc; this only pins it over the artwork. */
.title .title-back {
  position: absolute;
  top: var(--s3);
  left: var(--s3);
}

.title-hero-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s2);
  width: 100%;
  max-width: calc(560px + 2 * var(--gutter));
  padding: calc(var(--touch) + var(--s6)) var(--gutter) var(--s6);
}

/* Long Russian titles wrap to three lines; line-height stays ≥ 1.4 for й and у. */
.title-name {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink);
  font-size: clamp(24px, 4.2vw + 8px, 34px);
  line-height: 1.41;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.title-original {
  color: color-mix(in srgb, var(--ink) 75%, transparent);
}

.title-facts {
  color: color-mix(in srgb, var(--ink) 85%, transparent);
}

.title-play {
  display: flex;
  align-self: stretch;
  margin-top: var(--s2);
}

@media (max-width: 767px) {
  .title .title-play > .btn {
    flex: 1;
  }
}

.title-body {
  display: flex;
  flex-direction: column;
  gap: var(--s6);
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--s4) var(--gutter) 0;
}

.title-controls {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
}

.title-section {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s3);
}

/* Text actions line up with the text above them, not with their own padding. */
.title .title-section > .btn--text {
  margin-left: calc(-1 * var(--s3));
}

.title-section-head,
.title-empty-title {
  color: var(--ink);
}

.title-muted {
  color: var(--ink-soft);
}

.title-about {
  max-width: 72ch;
  color: var(--ink-soft);
  white-space: pre-line;
  user-select: text;
}

.title-about-clamped {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}

.title-episodes {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(300px, 100%), 1fr));
  column-gap: var(--s4);
  width: 100%;
  margin: 0;
  padding: 0;
  list-style: none;
}

.title-episode {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 52px;
}

/* Hairline inset past the glyph, as on iOS. */
.title-episode::after {
  content: "";
  position: absolute;
  left: 40px;
  right: 0;
  bottom: 0;
  height: 1px;
  background: var(--line);
}

.title-episode-main {
  display: flex;
  flex: 1;
  align-items: center;
  gap: var(--s3);
  min-width: 0;
  min-height: 52px;
  padding: 0 var(--s2) 0 0;
  border-radius: var(--r-card);
  color: var(--ink);
  text-decoration: none;
}

.title-episode-main-off {
  color: var(--ink-soft);
}

/* Full-width control: ring only, no scale. */
.title-episode-main:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: -3px;
}

.title-episode-icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 28px;
  color: var(--ink-soft);
}

.title-episode-icon-watched {
  color: var(--accent);
}

.title-episode-name {
  font-size: 16px;
  line-height: 24px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.title-episode-caption {
  margin-left: auto;
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 19px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.title-episode-progress {
  position: absolute;
  left: 40px;
  right: calc(var(--touch) + var(--s2));
  bottom: 6px;
  height: 3px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--track);
}

.title-episode-progress > span {
  display: block;
  height: 100%;
  background: var(--accent);
}

/* Only a start frame: the end is the element's own opacity (0.7 on a soft poster). */
@keyframes title-fade {
  from {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .title-hero-img {
    animation: none;
  }
}
```

- [ ] **Step 4: Run to see it pass**

From `web/`:
```
npx vitest run src/screens/episodeRows.test.ts src/ui/Menu.test.tsx src/screens/TitleScreen.test.tsx
```
**Expected:** `Test Files  3 passed (3)` and `Tests  46 passed (46)`: episodeRows 17, Menu 6 and TitleScreen 23.
```
npm test
```
**Expected:** exit code 0 with no failed tests, including every suite from Tasks 1–9.
```
npm run typecheck
```
**Expected:** exit code 0 and no diagnostics.
```
npm run build
```
**Expected:** `✓ built in …`, with no errors or CSS warnings.

- [ ] **Step 5: Commit**

```
cd /Users/vitaliy/Projects/kaeru
git add web/src/screens/episodeRows.ts web/src/screens/episodeRows.test.ts web/src/ui/Menu.tsx web/src/ui/Menu.css web/src/ui/Menu.test.tsx web/src/screens/TitleScreen.tsx web/src/screens/TitleScreen.css web/src/screens/TitleScreen.test.tsx
git commit -m "feat(web): экран тайтла — шапка, статус, серии и отметки"
```

---

### Task 11: My list, Search, Settings

**Decisions:**
- The pure list helpers go in `web/src/library/listing.ts`. The screen files then export only components (plus the `SearchScreenProps` type), which React Fast Refresh needs.
- **Wiring.** Task 8's `App.tsx` already imports `LibraryScreen`, `SearchScreen` and `SettingsScreen` and routes `/list`, `/search` and `/settings` to them. This task overwrites the three Task 8 stub files wholesale, keeps the export names, and does not touch `App.tsx`.
- **Task 8 primitives, with their real props:**
  - `PosterGrid({ label?, children })` and `PosterCard({ card, layout: "grid", footer? })`, both from `ui/PosterCard.tsx`. The grid is the global `.poster-grid` from `components.css`: `minmax(var(--grid-min), 1fr)`, which is 88px narrow and 168px wide. This task adds no grid component and no grid CSS.
  - `PillGroup({ kind: "tab" | "radio", label, options, value, onChange, panelId?, idPrefix?, className? })` for the status tabs and the threshold row. Roving focus, arrow keys, Home and End come with it.
  - `EmptyState({ title, text?, action?: StateAction, align? })`, where `StateAction` is `{ label, to?, onClick? }`, and `ErrorState({ message, onRetry? })`, which renders «Повторить».
  - `SkeletonGroup({ label?, children })` with `SkeletonGrid()`.
  - `SecondaryButton` and `DestructiveButton` with button props plus `compact` and `fullWidth`. `IconButton({ label, icon })`, `IconSearch` and `IconClose`.
  - `Dialog({ open, title, text?, confirmLabel, cancelLabel, destructive?, onConfirm, onCancel })`.
  - `useToast().show(text, { label, onClick })`.
  - `components.css` `.page` and `.page-title` frame each screen. The hidden loading label comes from `SkeletonGroup` (base.css `.visually-hidden`), so this task has no hidden-text class of its own.
- **Card names.** A `PosterCard` link is named by its whole text: the badge, the title and the caption. Tests therefore find a card by a title pattern (`{ name: /Фрирен/ }`) and read the caption or the year inside the link.
- **Screen CSS** goes in `web/src/screens/browse.css`, with classes prefixed `browse-`, `lib-`, `srch-` and `set-`. A rule that restyles a Task 8 class (`.pill-group.lib-tabs`, `.lib-panel .state--start`, `.srch-form__clear.icon-button`, `.pill-group.set-choices`) uses two classes, so it wins whatever order the bundle puts the stylesheets in. Colours come only from the tokens.
- **The address keeps the view.** My list uses `?tab=` and `?sort=`, and Search uses `?q=` (written with `replace`), so Back from a title returns to the same view. A `?q=` present on arrival runs at once.
- **Loading the list.** Both list screens call `library.load()` on mount while the library state is `idle`. Task 8's signed-in outlet does the same, and whichever runs first starts it. Until the first answer, My list shows a skeleton grid labelled «Синхронизируем список с Shikimori…». If that first load fails, it shows `ErrorState` with «Повторить».
- **Sorting.** Titles are sorted with `Intl.Collator("ru", {sensitivity: "accent"})`, as the contract says. ICU's «ru» puts Cyrillic before Latin, the opposite of Android's JDK collator, so that one vector from LibraryViewModelTest is not ported.
- **Search idle.** The idle state is the «Популярно сейчас» grid, read through Task 9's `catalogueCache` under Home's key `"now"`. Each key keeps 6 h, failures are not cached, and one read serves both screens. Like `HomeScreen`, `SearchScreen` takes an optional `catalogue` prop so that tests can pass a fresh `CatalogueCache`. If the read fails or comes back empty, the screen shows Android's idle copy «Что посмотреть сегодня?». There are no recent-query chips in plan 2.
- **Search in flight.** A `SkeletonGroup` labelled «Ищем аниме…» (iOS copy) sits over a skeleton grid. The toast region is a second `role="status"` on the page, so tests look for the loading text rather than for a single status element.
- **Search cards.** A card shows the year as a badge and has no caption (Android `ResultCard`). Under it sits a compact, full-width «В планы» button:
  - It reads «Добавляем…» while the write is in flight.
  - A disabled «В списке» wins over that (Android `addAction`).
  - Its aria-label is «Добавить {title} в планы» (iOS).
  - A failed add is reverted by the Library, and the error shows in a toast with «Повторить».
- **Threshold.** `PillGroup kind="radio"` labelled «Порог просмотра», with Android's note under the label. The labels read «80 %» with a no-break space (Android SettingsOptionsTest). A stored share that is none of the four joins the row in its place (Android `thresholdOptions`), so the row always lights the real value.
- **Sign-out.** Task 8's `Dialog` carries Android's copy. «Выйти» calls `progress.clear()` (added by Task 7), which forgets every position this browser kept, including titles played from search. It then navigates to `/` and calls `sessionStore.signOut()`. `App.tsx` rebuilds the services when the account id changes, so the list goes with the session.
- **Tests render the real providers**, as Task 9 does: `ServicesContext.Provider` (plus `ToastProvider` for Search) around a `MemoryRouter`, over a real `Library` and `ProgressStore` with a fake Shikimori. Nothing is `vi.mock`ed, so a prop mismatch or a toast-shape mismatch fails here.
- **Fake timers in the Search tests.** They use fake timers and `userEvent.setup({ advanceTimers })`. Testing Library's async wrapper drains a faked `setTimeout(0)` through `jest.advanceTimersByTime`. Task 1's `src/test/setup.ts` defines that `jest` shim over `vi.advanceTimersByTime`, so the awaits do not hang.

**Files:**
- Create: `web/src/library/listing.ts`
- Create: `web/src/screens/browse.css`
- Overwrite (Task 8 stub): `web/src/screens/LibraryScreen.tsx`
- Overwrite (Task 8 stub): `web/src/screens/SearchScreen.tsx`
- Overwrite (Task 8 stub): `web/src/screens/SettingsScreen.tsx`
- Test: `web/src/library/listing.test.ts`
- Test: `web/src/screens/LibraryScreen.test.tsx`
- Test: `web/src/screens/SearchScreen.test.tsx`
- Test: `web/src/screens/SettingsScreen.test.tsx`

**Interfaces:**
- Consumes:
  - `web/src/domain/models.ts`: `Anime`, `LibraryEntry`, `ListStatus`, `EpisodeProgress`, `UserRate`, `LIST_TABS: readonly ListStatus[]`, `availableEpisodes(anime: Anime): number`
  - `web/src/domain/format.ts`: `statusLabel(s: ListStatus): string`, `pluralEpisodes(n: number): string`
  - `web/src/domain/progress.ts`: `continueTarget(entry, progress, threshold): ContinueTarget`, `episodeFraction(entry, progress, episode, threshold): number | null`
  - `web/src/domain/feed.ts`: `Card`, `catalogueCard(anime: Anime): Card`
  - `web/src/api/http.ts`: `errorMessage(error: unknown): string`, `NetworkError` (tests)
  - `web/src/api/shikimori.ts`: `Shikimori` (`whoami`, `details`, `search`, `popularNow`, `popularInSeason`, `byIds`, `userRates`, `createRate`, `updateRate`), `Account` (tests)
  - `web/src/auth/relay.ts`: `Tokens` (tests)
  - `web/src/auth/session.ts`: `useAccess(): AccessState`, `sessionStore` (`get()`, `setSession(session)`, `signOut(message?)`), `authorized` (as a type in tests)
  - `web/src/library/progress.ts`: `ProgressStore` (`constructor(storage?)`, `of`, `put`, `subscribe`, `clear(): void`). Task 7 adds `clear`.
  - `web/src/library/prefs.ts`: `THRESHOLD_CHOICES: readonly number[]`, `watchedThreshold(storage?: Storage): number`, `setWatchedThreshold(value: number, storage?: Storage): void`
  - `web/src/library/library.ts`: `Library` (`new Library({ shikimori, authorized, accountId, progress })`, `state()`, `load()`, `entry(animeId)`, `setStatus(anime, status)`), `useLibrary(library: Library): LibraryState`
  - `web/src/app/services.tsx`: `useServices(): Services` where `Services` is `{ shikimori, library, progress }`, and `ServicesContext` (tests)
  - `web/src/screens/home.ts` (Task 9): `CatalogueCache` (`new CatalogueCache({ ttlMs?, now? })`, `read(key, load, force?)`), `catalogueCache`
  - `web/src/ui/PosterCard.tsx`: `PosterCard`, `PosterGrid`, `posterLetter(title: string): string`
  - `web/src/ui/Pill.tsx`: `PillGroup`, `PillOption<T>`
  - `web/src/ui/Skeleton.tsx`: `SkeletonGroup`, `SkeletonGrid`
  - `web/src/ui/States.tsx`: `EmptyState`, `ErrorState`
  - `web/src/ui/Button.tsx`: `SecondaryButton`, `DestructiveButton`, `IconButton`
  - `web/src/ui/icons.tsx`: `IconSearch`, `IconClose`
  - `web/src/ui/Dialog.tsx`: `Dialog` (`DialogProps`)
  - `web/src/ui/Toast.tsx`: `useToast(): ToastApi`, `ToastProvider` (tests)
  - CSS:
    - tokens `--gutter`, `--s1`–`--s8`, `--line`, `--surface`, `--elevated`, `--ink`, `--ink-soft`, `--r-card`, `--fast`, `--touch`, `--button-h`
    - `components.css` classes `.page`, `.page-title`, `.poster-grid`, `.pill-group`, `.state--start`, `.icon-button`
    - tests read `.poster-card__title` and `.progress-strip__fill`
- Produces:
  - `web/src/screens/LibraryScreen.tsx`: `LibraryScreen()`, routed at `/list`
  - `web/src/screens/SearchScreen.tsx`: `SearchScreen(props: SearchScreenProps)`, routed at `/search`, and `interface SearchScreenProps { catalogue?: CatalogueCache }`
  - `web/src/screens/SettingsScreen.tsx`: `SettingsScreen()`, routed at `/settings`
  - `web/src/library/listing.ts`:
    - `type LibrarySort = "updated" | "title"`
    - `SORT_OPTIONS: readonly { value: LibrarySort; label: string }[]`
    - `interface LibraryTab { status; count; text }`, `libraryTabs(entries): LibraryTab[]`
    - `selectLibrary(entries, status, sort): LibraryEntry[]`
    - `libraryCardSubtitle(entry): string | null`
    - `libraryCardProgress(entry, progress, threshold): number | null`
    - `libraryCard(entry, progress, threshold): Card`
    - `interface EmptyTabCopy { title; text; offersSearch }`, `emptyTabCopy(status): EmptyTabCopy`
    - `parseTab(raw: string | null): ListStatus`, `parseSort(raw: string | null): LibrarySort`

- [ ] **Step 1: Write the failing tests**

`web/src/library/listing.test.ts`:

```ts
// Vectors from android/src/test/java/app/kaeru/ui/common/library/LibraryTabsTest.kt and
// LibraryViewModelTest.kt. Not ported: «latin names come before cyrillic ones» — the JDK collator
// does that, ICU's «ru» (Intl.Collator, per the contract) puts Cyrillic first.
import { describe, expect, it } from "vitest";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import {
  emptyTabCopy,
  libraryCard,
  libraryCardProgress,
  libraryCardSubtitle,
  libraryTabs,
  parseSort,
  parseTab,
  selectLibrary,
} from "./listing";

const SEP_1 = "2026-09-01T00:00:00Z";

function anime(id: number, title: string, over: Partial<Anime> = {}): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year: 2026,
    score: null,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
    ...over,
  };
}

function entry(
  id: number,
  title: string,
  status: ListStatus,
  watched: number,
  updated = SEP_1,
  over: Partial<Anime> = {},
): LibraryEntry {
  return {
    anime: anime(id, title, over),
    rate: { id, animeId: id, status, episodes: watched, updatedAt: Date.parse(updated) },
  };
}

function row(episode: number, positionMs: number, durationMs = 1_440_000): EpisodeProgress {
  return { animeId: 1, episode, positionMs, durationMs, updatedAt: 1 };
}

const ids = (entries: readonly LibraryEntry[]) => entries.map((item) => item.anime.id);

describe("libraryTabs", () => {
  it("gives every status a tab counted over the whole list, in Android's order", () => {
    const tabs = libraryTabs([
      entry(1, "А", "watching", 0),
      entry(2, "Б", "watching", 0),
      entry(3, "В", "planned", 0),
      entry(4, "Г", "dropped", 0),
    ]);
    expect(tabs.map((tab) => tab.status)).toEqual([
      "watching",
      "planned",
      "completed",
      "rewatching",
      "on_hold",
      "dropped",
    ]);
    expect(tabs.map((tab) => tab.count)).toEqual([2, 1, 0, 0, 0, 1]);
  });

  it("reads as a label and a number with nothing between them", () => {
    const many = (from: number, count: number, status: ListStatus) =>
      Array.from({ length: count }, (_, index) => entry(from + index, "Т", status, 0));
    const tabs = libraryTabs([...many(0, 12, "watching"), ...many(100, 40, "planned"), ...many(200, 128, "completed")]);
    expect(tabs.slice(0, 3).map((tab) => tab.text)).toEqual(["Смотрю 12", "В планах 40", "Завершено 128"]);
    for (const tab of tabs) {
      expect(tab.text).not.toMatch(/[·()]/);
      expect(tab.text).not.toBe(tab.text.toUpperCase());
    }
  });
});

describe("selectLibrary", () => {
  it("keeps the open status and puts the latest update first", () => {
    const items = [
      entry(1, "А", "watching", 1, "2026-09-01T00:00:00Z"),
      entry(2, "Б", "watching", 2, "2026-09-10T00:00:00Z"),
      entry(3, "В", "planned", 3, "2026-09-11T00:00:00Z"),
    ];
    expect(ids(selectLibrary(items, "watching", "updated"))).toEqual([2, 1]);
    expect(ids(selectLibrary(items, "watching", "title"))).toEqual([1, 2]);
  });

  it("breaks a tie in update time by name, whatever order the list came in", () => {
    const same = "2026-09-10T00:00:00Z";
    const items = [entry(1, "Ящер", "watching", 0, same), entry(2, "Аист", "watching", 0, same)];
    expect(ids(selectLibrary(items, "watching", "updated"))).toEqual([2, 1]);
    expect(ids(selectLibrary([...items].reverse(), "watching", "updated"))).toEqual([2, 1]);
  });

  it("sorts ё right after е, not after я", () => {
    const items = [entry(1, "Яблоко", "watching", 0), entry(2, "Ёлка", "watching", 0), entry(3, "Ели", "watching", 0)];
    expect(ids(selectLibrary(items, "watching", "title"))).toEqual([3, 2, 1]);
  });

  it("does not let case decide the order", () => {
    const items = [entry(1, "фрирен", "watching", 0), entry(2, "Дандадан", "watching", 0)];
    expect(ids(selectLibrary(items, "watching", "title"))).toEqual([2, 1]);
  });

  it("leaves the list it was given untouched", () => {
    const items = [entry(1, "Ящер", "watching", 0), entry(2, "Аист", "watching", 0)];
    selectLibrary(items, "watching", "title");
    expect(ids(items)).toEqual([1, 2]);
  });
});

describe("libraryCardSubtitle", () => {
  it("counts a started title up to the season length", () => {
    expect(libraryCardSubtitle(entry(1, "Т", "watching", 7, SEP_1, { episodes: 28, episodesAired: 24 }))).toBe("7 из 28");
  });

  it("says how long an untouched season is instead of counting from zero", () => {
    expect(libraryCardSubtitle(entry(1, "Т", "planned", 0, SEP_1, { episodes: 28, episodesAired: 0 }))).toBe("28 серий");
  });

  it("counts against what has aired when the total is unknown", () => {
    expect(
      libraryCardSubtitle(entry(1, "Т", "watching", 3, SEP_1, { episodes: 0, episodesAired: 5, status: "ongoing" })),
    ).toBe("3 из 5");
  });

  it("writes «?» for a total nobody knows yet", () => {
    expect(
      libraryCardSubtitle(entry(1, "Т", "watching", 3, SEP_1, { episodes: 0, episodesAired: 0, status: "ongoing" })),
    ).toBe("3 из ?");
  });

  it("says nothing for a title with nothing aired and nothing watched", () => {
    expect(
      libraryCardSubtitle(entry(1, "Т", "planned", 0, SEP_1, { episodes: 0, episodesAired: 0, status: "anons" })),
    ).toBeNull();
  });
});

describe("libraryCardProgress", () => {
  const watching = entry(1, "Т", "watching", 2);

  it("shows how far into the episode being continued", () => {
    expect(libraryCardProgress(watching, [row(3, 600_000)], 0.9)).toBeCloseTo(600 / 1440, 5);
  });

  it("draws nothing for a mis-tap under a minute and under 2 %", () => {
    expect(libraryCardProgress(watching, [row(3, 20_000)], 0.9)).toBeNull();
  });

  it("draws nothing for an episode finished here", () => {
    expect(libraryCardProgress(watching, [row(3, 1_400_000)], 0.9)).toBeNull();
  });

  it("draws nothing without positions", () => {
    expect(libraryCardProgress(watching, [], 0.9)).toBeNull();
  });
});

describe("libraryCard", () => {
  it("is the poster card of a list title: no badge, a caption, a strip only mid-episode", () => {
    expect(libraryCard(entry(2, "Ели", "watching", 5), [], 0.9)).toEqual({
      key: "2",
      animeId: 2,
      title: "Ели",
      posterUrl: null,
      badge: null,
      subtitle: "5 из 12",
      progress: null,
    });
  });
});

describe("emptyTabCopy", () => {
  it("offers search only on the two tabs a viewer fills on purpose", () => {
    const offering = (["watching", "planned", "completed", "rewatching", "on_hold", "dropped"] as const).filter(
      (status) => emptyTabCopy(status).offersSearch,
    );
    expect(offering).toEqual(["watching", "planned"]);
  });

  it("uses Android's words", () => {
    expect(emptyTabCopy("watching")).toEqual({
      title: "Вы ничего не смотрите",
      text: "Начните любой тайтл — он окажется здесь вместе с серией, на которой вы остановились.",
      offersSearch: true,
    });
    expect(emptyTabCopy("completed")).toEqual({
      title: "Завершённых тайтлов пока нет",
      text: "Здесь соберётся всё, что вы досмотрели до конца.",
      offersSearch: false,
    });
    expect(emptyTabCopy("dropped").title).toBe("Ничего не брошено");
  });
});

describe("address values", () => {
  it("reads a known tab and falls back to «Смотрю»", () => {
    expect(parseTab("on_hold")).toBe("on_hold");
    expect(parseTab("bogus")).toBe("watching");
    expect(parseTab(null)).toBe("watching");
  });

  it("reads the sort and falls back to «Обновление»", () => {
    expect(parseSort("title")).toBe("title");
    expect(parseSort("anything")).toBe("updated");
    expect(parseSort(null)).toBe("updated");
  });
});
```

`web/src/screens/LibraryScreen.test.tsx`:

```tsx
// Behaviour follows android/src/main/java/app/kaeru/ui/mobile/library/LibraryScreen.kt and
// android/src/test/java/app/kaeru/ui/common/library/LibraryTabsTest.kt. The screen runs over the
// real Library and ProgressStore with a fake Shikimori, provided the way App.tsx provides them.
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { LibraryScreen } from "./LibraryScreen";

function anime(id: number, title: string): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year: 2026,
    score: null,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

function rate(animeId: number, status: ListStatus, episodes: number, updated: string): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt: Date.parse(updated) };
}

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (name: string) => data.get(name) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (name: string) => {
      data.delete(name);
    },
    setItem: (name: string, value: string) => {
      data.set(name, value);
    },
  };
}

function fakeShikimori(over: Partial<Shikimori>): Shikimori {
  const unused = () => Promise.reject(new Error("not used in this test"));
  return {
    whoami: unused,
    details: unused,
    search: unused,
    popularNow: unused,
    popularInSeason: unused,
    byIds: unused,
    userRates: unused,
    createRate: unused,
    updateRate: unused,
    ...over,
  };
}

const signedIn: typeof authorized = (call) => call("token");

const TITLES = [anime(1, "Ёлка"), anime(2, "Ели"), anime(3, "Аист"), anime(4, "Берсерк"), anime(5, "Ящер")];
const RATES = [
  rate(1, "watching", 0, "2026-09-01T00:00:00Z"),
  rate(2, "watching", 5, "2026-09-10T00:00:00Z"),
  rate(3, "watching", 0, "2026-09-05T00:00:00Z"),
  rate(4, "planned", 0, "2026-09-02T00:00:00Z"),
  rate(5, "dropped", 3, "2026-09-03T00:00:00Z"),
];
const byIds: Shikimori["byIds"] = async (ids) => TITLES.filter((title) => ids.includes(title.id));

function renderAt(path: string, shikimori: Shikimori) {
  const progress = new ProgressStore(memoryStorage());
  const library = new Library({ shikimori, authorized: signedIn, accountId: () => 1, progress });
  render(
    <ServicesContext.Provider value={{ shikimori, library, progress }}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/list" element={<LibraryScreen />} />
          <Route path="/search" element={<p>Экран поиска</p>} />
        </Routes>
      </MemoryRouter>
    </ServicesContext.Provider>,
  );
  return { progress };
}

// A card link reads its whole text (badge, title, caption), so a card is found by its title.
function card(title: string): HTMLElement {
  return screen.getByRole("link", { name: new RegExp(title) });
}

function panelTitles(): (string | null)[] {
  return within(screen.getByRole("tabpanel"))
    .getAllByRole("link")
    .map((link) => link.querySelector(".poster-card__title")?.textContent ?? null);
}

describe("LibraryScreen", () => {
  it("shows every status as a tab with its count, in Android's order, «Смотрю» open", async () => {
    renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "Смотрю 3",
      "В планах 1",
      "Завершено 0",
      "Пересматриваю 0",
      "Отложено 0",
      "Брошено 1",
    ]);
    expect(screen.getByRole("tab", { name: "Смотрю 3" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tablist", { name: "Мой список" })).toBeInTheDocument();
  });

  it("lists the open tab by last update, and «Название» sorts ё right after е", async () => {
    const user = userEvent.setup();
    renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    expect(panelTitles()).toEqual(["Ели", "Аист", "Ёлка"]);
    expect(screen.getByRole("button", { name: "Обновление" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "Название" }));
    expect(screen.getByRole("button", { name: "Название" })).toHaveAttribute("aria-pressed", "true");
    expect(panelTitles()).toEqual(["Аист", "Ели", "Ёлка"]);
  });

  it("says how far through a title is, or how long it runs when untouched", async () => {
    renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    const started = card("Ели");
    expect(within(started).getByText("5 из 12")).toBeInTheDocument();
    expect(started).toHaveAttribute("href", "/anime/2");
    expect(within(card("Аист")).getByText("12 серий")).toBeInTheDocument();
  });

  it("fills the strip of the episode being continued when this browser saves a position", async () => {
    const { progress } = renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    expect(card("Ели").querySelector(".progress-strip")).toBeNull();
    act(() => {
      progress.put({ animeId: 2, episode: 6, positionMs: 600_000, durationMs: 1_440_000, updatedAt: 1 });
    });
    expect(card("Ели").querySelector<HTMLElement>(".progress-strip__fill")?.style.width).toBe("41.7%");
  });

  it("opens a tab from the address, by click and by arrow keys", async () => {
    const user = userEvent.setup();
    renderAt("/list?tab=dropped", fakeShikimori({ userRates: async () => RATES, byIds }));
    expect(await screen.findByRole("tab", { name: "Брошено 1" })).toHaveAttribute("aria-selected", "true");
    expect(panelTitles()).toEqual(["Ящер"]);
    await user.click(screen.getByRole("tab", { name: "В планах 1" }));
    expect(panelTitles()).toEqual(["Берсерк"]);
    expect(screen.getByRole("tabpanel")).toHaveAccessibleName("В планах 1");
    await user.keyboard("{ArrowLeft}");
    const watching = screen.getByRole("tab", { name: "Смотрю 3" });
    expect(watching).toHaveAttribute("aria-selected", "true");
    expect(watching).toHaveFocus();
    await user.keyboard("{End}");
    expect(screen.getByRole("tab", { name: "Брошено 1" })).toHaveFocus();
  });

  it("explains an empty tab without offering search where the viewer does not fill it", async () => {
    renderAt("/list?tab=completed", fakeShikimori({ userRates: async () => RATES, byIds }));
    expect(await screen.findByRole("heading", { name: "Завершённых тайтлов пока нет" })).toBeInTheDocument();
    expect(screen.getByText("Здесь соберётся всё, что вы досмотрели до конца.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Найти аниме" })).not.toBeInTheDocument();
  });

  it("leads an empty «Смотрю» to search", async () => {
    const user = userEvent.setup();
    renderAt("/list", fakeShikimori({ userRates: async () => [], byIds: async () => [] }));
    expect(await screen.findByRole("heading", { name: "Вы ничего не смотрите" })).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Найти аниме" }));
    expect(screen.getByText("Экран поиска")).toBeInTheDocument();
  });

  it("says why the list did not load and loads it again on «Повторить»", async () => {
    const user = userEvent.setup();
    const userRates = vi
      .fn<Shikimori["userRates"]>()
      .mockRejectedValueOnce(new NetworkError("offline"))
      .mockResolvedValue(RATES);
    renderAt("/list", fakeShikimori({ userRates, byIds }));
    expect(await screen.findByText("Нет соединения. Проверьте интернет")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByRole("tab", { name: "Смотрю 3" })).toBeInTheDocument();
  });
});
```

`web/src/screens/SearchScreen.test.tsx`:

```tsx
// Rules: debounce 350 ms and 2 characters (ios/Features/SearchView.swift); screen states and the
// card action (android/src/test/java/app/kaeru/ui/common/search/SearchContentTest.kt).
// Fake timers drive the debounce. userEvent advances them itself, and Testing Library's async
// wrapper can drain them because src/test/setup.ts defines the `jest` shim it looks for.
import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { ToastProvider } from "../ui/Toast";
import { CatalogueCache } from "./home";
import { SearchScreen } from "./SearchScreen";

const OFFLINE = "Нет соединения. Проверьте интернет";

function anime(id: number, title: string, year: number): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year,
    score: null,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

function rate(animeId: number, status: ListStatus, episodes: number): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt: Date.parse("2026-09-10T00:00:00Z") };
}

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (name: string) => data.get(name) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (name: string) => {
      data.delete(name);
    },
    setItem: (name: string, value: string) => {
      data.set(name, value);
    },
  };
}

function fakeShikimori(over: Partial<Shikimori>): Shikimori {
  const unused = () => Promise.reject(new Error("not used in this test"));
  return {
    whoami: unused,
    details: unused,
    search: unused,
    popularNow: unused,
    popularInSeason: unused,
    byIds: unused,
    userRates: unused,
    createRate: unused,
    updateRate: unused,
    ...over,
  };
}

const signedIn: typeof authorized = (call) => call("token");
const FRIEREN = anime(7, "Фрирен", 2023);
const DANDADAN = anime(8, "Дандадан", 2024);
const created: Shikimori["createRate"] = async (_token, _userId, animeId, fields) => ({
  id: 900,
  animeId,
  status: fields.status,
  episodes: 0,
  updatedAt: Date.now(),
});

function setup(over: Partial<Shikimori>, options: { path?: string; catalogue?: CatalogueCache } = {}) {
  const shikimori = fakeShikimori({
    popularNow: async () => [FRIEREN],
    userRates: async () => [],
    byIds: async () => [],
    ...over,
  });
  const progress = new ProgressStore(memoryStorage());
  const library = new Library({ shikimori, authorized: signedIn, accountId: () => 1, progress });
  // A fresh cache per test: the module-wide one would carry titles from one test to the next.
  const catalogue = options.catalogue ?? new CatalogueCache();
  const user = userEvent.setup({
    advanceTimers: (ms) => {
      vi.advanceTimersByTime(ms);
    },
  });
  render(
    <ServicesContext.Provider value={{ shikimori, library, progress }}>
      <ToastProvider>
        <MemoryRouter initialEntries={[options.path ?? "/search"]}>
          <Routes>
            <Route path="/search" element={<SearchScreen catalogue={catalogue} />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </ServicesContext.Provider>,
  );
  return { user, library };
}

async function elapse(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

function field(): HTMLElement {
  return screen.getByRole("searchbox", { name: "Название аниме" });
}

// A card link reads its whole text (year badge and title), so a card is found by its title.
function cardLink(title: string): HTMLElement {
  return screen.getByRole("link", { name: new RegExp(title) });
}

function cardOf(title: string): HTMLElement {
  const item = cardLink(title).closest("li");
  if (item === null) throw new Error(`no card for ${title}`);
  return item;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  // Unmount while the fake clock still owns the screen's timers, then hand the real clock back.
  cleanup();
  vi.useRealTimers();
});

describe("SearchScreen", () => {
  it("before a search shows what is popular now, each with a way into «В планах»", async () => {
    setup({
      popularNow: async () => [FRIEREN, DANDADAN],
      userRates: async () => [rate(8, "watching", 3)],
      byIds: async () => [DANDADAN],
    });
    await elapse(0);
    const section = screen.getByRole("region", { name: "Популярно сейчас" });
    const frieren = within(section).getByRole("link", { name: /Фрирен/ });
    expect(within(frieren).getByText("2023")).toBeInTheDocument();
    expect(frieren).toHaveAttribute("href", "/anime/7");
    expect(within(section).getByRole("button", { name: "Добавить Фрирен в планы" })).toBeEnabled();
    expect(within(cardOf("Дандадан")).getByRole("button", { name: "В списке" })).toBeDisabled();
  });

  it("takes «Популярно сейчас» from the catalogue cache Home fills", async () => {
    const catalogue = new CatalogueCache();
    await catalogue.read("now", async () => [DANDADAN]);
    const popularNow = vi.fn<Shikimori["popularNow"]>().mockResolvedValue([FRIEREN]);
    setup({ popularNow }, { catalogue });
    await elapse(0);
    expect(popularNow).not.toHaveBeenCalled();
    const section = screen.getByRole("region", { name: "Популярно сейчас" });
    expect(within(section).getByRole("link", { name: /Дандадан/ })).toBeInTheDocument();
  });

  it("falls back to the invitation when nothing popular came back", async () => {
    setup({ popularNow: () => Promise.reject(new NetworkError("offline")) });
    await elapse(0);
    expect(screen.getByText("Что посмотреть сегодня?")).toBeInTheDocument();
    expect(
      screen.getByText("Введите название — Kaeru поищет его на Shikimori и положит найденное в ваш список."),
    ).toBeInTheDocument();
  });

  it("never searches one letter and waits 350 ms after the last keystroke", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "ф");
    await elapse(1000);
    expect(search).not.toHaveBeenCalled();
    await user.keyboard("р");
    await elapse(349);
    expect(search).not.toHaveBeenCalled();
    await elapse(1);
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith("фр");
    expect(within(cardLink("Дандадан")).getByText("2024")).toBeInTheDocument();
  });

  it("sends only the last query when typing fast", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "дандадан");
    await elapse(350);
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith("дандадан");
  });

  it("searches the trimmed query at once on Enter and not again after the pause", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "  дандадан {Enter}");
    await elapse(0);
    expect(search).toHaveBeenCalledWith("дандадан");
    await elapse(1000);
    expect(search).toHaveBeenCalledTimes(1);
    expect(field()).not.toHaveFocus();
  });

  it("tells a search that found nothing from one that failed, and retries the failed query", async () => {
    const search = vi
      .fn<Shikimori["search"]>()
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new NetworkError("offline"))
      .mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "ыыы");
    await elapse(350);
    expect(screen.getByText("Ничего не найдено")).toBeInTheDocument();
    expect(screen.getByText("Попробуйте оригинальное название или короче.")).toBeInTheDocument();
    await user.clear(field());
    await user.type(field(), "дандадан");
    await elapse(350);
    expect(screen.getByText(OFFLINE)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    await elapse(0);
    expect(search).toHaveBeenLastCalledWith("дандадан");
    expect(cardLink("Дандадан")).toBeInTheDocument();
  });

  it("never lets a late answer to an older query replace the newer one", async () => {
    let answerOld: (titles: Anime[]) => void = () => undefined;
    const search = vi
      .fn<Shikimori["search"]>()
      .mockImplementationOnce(
        () =>
          new Promise<Anime[]>((resolve) => {
            answerOld = resolve;
          }),
      )
      .mockResolvedValueOnce([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "да");
    await elapse(350);
    const loading = screen.getByText("Ищем аниме…");
    expect(loading.closest('[role="status"]')).toHaveAttribute("aria-busy", "true");
    await user.keyboard("н");
    await elapse(350);
    expect(cardLink("Дандадан")).toBeInTheDocument();
    answerOld([FRIEREN]);
    await elapse(0);
    expect(screen.queryByRole("link", { name: /Фрирен/ })).not.toBeInTheDocument();
    expect(cardLink("Дандадан")).toBeInTheDocument();
  });

  it("«Очистить» empties the field, keeps the focus there and brings the popular titles back", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "дандадан");
    await elapse(350);
    expect(screen.queryByRole("region", { name: "Популярно сейчас" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Очистить" }));
    expect(field()).toHaveValue("");
    expect(field()).toHaveFocus();
    expect(screen.getByRole("region", { name: "Популярно сейчас" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Очистить" })).not.toBeInTheDocument();
  });

  it("runs a query that came in with the address at once", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    setup({ search }, { path: "/search?q=дандадан" });
    await elapse(0);
    expect(search).toHaveBeenCalledWith("дандадан");
    expect(field()).toHaveValue("дандадан");
    expect(cardLink("Дандадан")).toBeInTheDocument();
  });

  it("«В планы» puts the title in «В планах» and the card says so", async () => {
    const createRate = vi.fn<Shikimori["createRate"]>().mockImplementation(created);
    const { user, library } = setup({ createRate });
    await elapse(0);
    await user.click(screen.getByRole("button", { name: "Добавить Фрирен в планы" }));
    await elapse(0);
    expect(library.entry(7)?.rate.status).toBe("planned");
    expect(within(cardOf("Фрирен")).getByRole("button", { name: "В списке" })).toBeDisabled();
  });

  it("a failed add puts «В планы» back and offers «Повторить» in a toast", async () => {
    const createRate = vi
      .fn<Shikimori["createRate"]>()
      .mockRejectedValueOnce(new NetworkError("offline"))
      .mockImplementation(created);
    const { user, library } = setup({ createRate });
    await elapse(0);
    await user.click(screen.getByRole("button", { name: "Добавить Фрирен в планы" }));
    await elapse(0);
    expect(library.entry(7)).toBeUndefined();
    expect(screen.getByRole("button", { name: "Добавить Фрирен в планы" })).toBeEnabled();
    const toast = screen.getByText(OFFLINE).parentElement as HTMLElement;
    await user.click(within(toast).getByRole("button", { name: "Повторить" }));
    await elapse(0);
    expect(createRate).toHaveBeenCalledTimes(2);
    expect(library.entry(7)?.rate.status).toBe("planned");
    expect(screen.queryByText(OFFLINE)).not.toBeInTheDocument();
  });
});
```

`web/src/screens/SettingsScreen.test.tsx`:

```tsx
// Copy and threshold labels from android/src/main/java/app/kaeru/ui/mobile/settings/SettingsScreen.kt
// and android/src/test/java/app/kaeru/ui/common/settings/SettingsOptionsTest.kt.
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Account, Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { Tokens } from "../auth/relay";
import { sessionStore } from "../auth/session";
import type { authorized } from "../auth/session";
import { Library } from "../library/library";
import { setWatchedThreshold, watchedThreshold } from "../library/prefs";
import { ProgressStore } from "../library/progress";
import { SettingsScreen } from "./SettingsScreen";

const ACCOUNT: Account = { id: 1, nickname: "Лягушка", avatar: "https://shikimori.io/system/users/x160/1.png" };
const TOKENS: Tokens = { accessToken: "access", refreshToken: "refresh", expiresIn: 86_400, createdAt: 1_790_000_000 };
const DIALOG_TEXT = "Список и прогресс останутся на Shikimori, локальный кэш будет очищен";

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (name: string) => data.get(name) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (name: string) => {
      data.delete(name);
    },
    setItem: (name: string, value: string) => {
      data.set(name, value);
    },
  };
}

function fakeShikimori(): Shikimori {
  const unused = () => Promise.reject(new Error("not used in this test"));
  return {
    whoami: unused,
    details: unused,
    search: unused,
    popularNow: unused,
    popularInSeason: unused,
    byIds: unused,
    userRates: unused,
    createRate: unused,
    updateRate: unused,
  };
}

const signedIn: typeof authorized = (call) => call("token");

function setup() {
  const shikimori = fakeShikimori();
  const storage = memoryStorage();
  const progress = new ProgressStore(storage);
  const library = new Library({ shikimori, authorized: signedIn, accountId: () => ACCOUNT.id, progress });
  // One title from the list and one that was only ever opened from search.
  progress.put({ animeId: 5, episode: 3, positionMs: 600_000, durationMs: 1_440_000, updatedAt: 1 });
  progress.put({ animeId: 99, episode: 1, positionMs: 90_000, durationMs: 1_440_000, updatedAt: 2 });
  // setup.ts clears localStorage after every test, so the next test starts signed out again.
  sessionStore.setSession({ account: ACCOUNT, tokens: TOKENS });
  const view = render(
    <ServicesContext.Provider value={{ shikimori, library, progress }}>
      <MemoryRouter initialEntries={["/settings"]}>
        <Routes>
          <Route path="/settings" element={<SettingsScreen />} />
          <Route path="/" element={<p>Главная</p>} />
        </Routes>
      </MemoryRouter>
    </ServicesContext.Provider>,
  );
  return { progress, storage, user: userEvent.setup(), container: view.container };
}

function thresholds(): HTMLElement {
  return screen.getByRole("radiogroup", { name: "Порог просмотра" });
}

describe("SettingsScreen", () => {
  it("shows who is signed in", () => {
    const { container } = setup();
    expect(screen.getByRole("heading", { name: "Аккаунт" })).toBeInTheDocument();
    expect(screen.getByText("Лягушка")).toBeInTheDocument();
    expect(screen.getByText("Shikimori")).toBeInTheDocument();
    expect(container.querySelector("img")?.getAttribute("src")).toBe(ACCOUNT.avatar);
  });

  it("offers the four thresholds as percentages and keeps the choice", async () => {
    const { user } = setup();
    expect(within(thresholds()).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "80\u00A0%",
      "85\u00A0%",
      "90\u00A0%",
      "95\u00A0%",
    ]);
    expect(within(thresholds()).getByRole("radio", { name: /^90\s%$/ })).toBeChecked();
    await user.click(within(thresholds()).getByRole("radio", { name: /^95\s%$/ }));
    expect(within(thresholds()).getByRole("radio", { name: /^95\s%$/ })).toBeChecked();
    expect(within(thresholds()).getByRole("radio", { name: /^90\s%$/ })).not.toBeChecked();
    expect(watchedThreshold()).toBe(0.95);
  });

  it("puts a stored threshold the row does not offer into the row, in its place", () => {
    setWatchedThreshold(0.7);
    setup();
    expect(within(thresholds()).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "70\u00A0%",
      "80\u00A0%",
      "85\u00A0%",
      "90\u00A0%",
      "95\u00A0%",
    ]);
    expect(within(thresholds()).getByRole("radio", { name: /^70\s%$/ })).toBeChecked();
  });

  it("asks before signing out, and «Отмена» keeps everything", async () => {
    const { user, progress } = setup();
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const dialog = screen.getByRole("dialog", { name: "Выйти из аккаунта?" });
    expect(dialog).toHaveAccessibleDescription(DIALOG_TEXT);
    expect(within(dialog).getByRole("button", { name: "Выйти" })).toHaveClass("btn--destructive");
    await user.click(within(dialog).getByRole("button", { name: "Отмена" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(sessionStore.get().kind).toBe("signed_in");
    expect(progress.of(5)).toHaveLength(1);
    expect(progress.of(99)).toHaveLength(1);
  });

  it("«Выйти» ends the session, forgets every position in this browser and goes home", async () => {
    const { user, progress, storage } = setup();
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const dialog = screen.getByRole("dialog", { name: "Выйти из аккаунта?" });
    await user.click(within(dialog).getByRole("button", { name: "Выйти" }));
    expect(sessionStore.get().kind).toBe("signed_out");
    expect(progress.of(5)).toEqual([]);
    // Not only the titles in the loaded list: a title played from search goes too.
    expect(progress.of(99)).toEqual([]);
    // Gone from storage as well, so a reload does not bring it back.
    expect(new ProgressStore(storage).of(99)).toEqual([]);
    expect(screen.getByText("Главная")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run them to see them fail**

From `web/` (`cd /Users/vitaliy/Projects/kaeru/web`): `npx vitest run src/library/listing.test.ts src/screens/LibraryScreen.test.tsx src/screens/SearchScreen.test.tsx src/screens/SettingsScreen.test.tsx`

**Expected:** FAIL, with `Test Files  4 failed (4)` and `Tests  25 failed (25)`.
- `src/library/listing.test.ts` fails at import with `Error: Failed to resolve import "./listing" from "src/library/listing.test.ts". Does the file exist?`. Its 21 tests do not run.
- The three screen suites load, because Task 8 left stubs with these exports, and every test fails on an assertion against the stub's lone `<h1>`. Vitest does not typecheck, so the `catalogue` prop the Search test passes to the stub does not stop it. Examples:
  - `src/screens/LibraryScreen.test.tsx`: 8 failed, e.g. `Unable to find role="tab" and name "Смотрю 3"`
  - `src/screens/SearchScreen.test.tsx`: 12 failed, e.g. `Unable to find an accessible element with the role "region" and name "Популярно сейчас"` and `… with the role "searchbox" and name "Название аниме"`
  - `src/screens/SettingsScreen.test.tsx`: 5 failed, e.g. `Unable to find an accessible element with the role "heading" and name "Аккаунт"` and `… with the role "radiogroup" and name "Порог просмотра"`
- The fake-timer Search suite fails at once instead of hanging, thanks to the `jest` shim in `src/test/setup.ts`.

- [ ] **Step 3: Implement**

`web/src/library/listing.ts`:

```ts
import { pluralEpisodes, statusLabel } from "../domain/format";
import type { Card } from "../domain/feed";
import { LIST_TABS, availableEpisodes } from "../domain/models";
import type { EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import { continueTarget, episodeFraction } from "../domain/progress";

export type LibrarySort = "updated" | "title";

export const SORT_OPTIONS: readonly { value: LibrarySort; label: string }[] = [
  { value: "updated", label: "Обновление" },
  { value: "title", label: "Название" },
];

export interface LibraryTab {
  status: ListStatus;
  count: number;
  text: string;
}

export interface EmptyTabCopy {
  title: string;
  text: string;
  offersSearch: boolean;
}

// Built once: constructing a collator is the expensive half of sorting by name.
const titleOrder = new Intl.Collator("ru", { sensitivity: "accent" });

// Every status keeps its tab, zero included; counts cover the whole list, not the open tab.
export function libraryTabs(entries: readonly LibraryEntry[]): LibraryTab[] {
  const counts = new Map<ListStatus, number>();
  for (const entry of entries) counts.set(entry.rate.status, (counts.get(entry.rate.status) ?? 0) + 1);
  return LIST_TABS.map((status) => {
    const count = counts.get(status) ?? 0;
    return { status, count, text: `${statusLabel(status)} ${count}` };
  });
}

// Both orders end in a name tie-break, so a list imported in one second never reshuffles.
export function selectLibrary(
  entries: readonly LibraryEntry[],
  status: ListStatus,
  sort: LibrarySort,
): LibraryEntry[] {
  const byName = (a: LibraryEntry, b: LibraryEntry) =>
    titleOrder.compare(a.anime.title, b.anime.title) || a.anime.id - b.anime.id;
  const order =
    sort === "updated"
      ? (a: LibraryEntry, b: LibraryEntry) => b.rate.updatedAt - a.rate.updatedAt || byName(a, b)
      : byName;
  return entries.filter((entry) => entry.rate.status === status).sort(order);
}

// Android libraryCardSubtitle: «7 из 28» once started, the season length before, nothing when unknown.
export function libraryCardSubtitle(entry: LibraryEntry): string | null {
  const total = entry.anime.episodes > 0 ? entry.anime.episodes : availableEpisodes(entry.anime);
  const watched = entry.rate.episodes;
  if (watched > 0) return `${watched} из ${total > 0 ? total : "?"}`;
  if (total > 0) return pluralEpisodes(total);
  return null;
}

// Android LibraryEntry.progressFraction: a strip only inside the episode being continued.
export function libraryCardProgress(
  entry: LibraryEntry,
  progress: readonly EpisodeProgress[],
  threshold: number,
): number | null {
  const target = continueTarget(entry, progress, threshold);
  if (target.positionMs <= 0) return null;
  return episodeFraction(entry, progress, target.episode, threshold);
}

export function libraryCard(entry: LibraryEntry, progress: readonly EpisodeProgress[], threshold: number): Card {
  return {
    key: String(entry.anime.id),
    animeId: entry.anime.id,
    title: entry.anime.title,
    posterUrl: entry.anime.posterUrl,
    badge: null,
    subtitle: libraryCardSubtitle(entry),
    progress: libraryCardProgress(entry, progress, threshold),
  };
}

// Android LibraryTabs.emptyTabCopy; only the tabs a viewer fills on purpose offer search.
export function emptyTabCopy(status: ListStatus): EmptyTabCopy {
  switch (status) {
    case "watching":
      return {
        title: "Вы ничего не смотрите",
        text: "Начните любой тайтл — он окажется здесь вместе с серией, на которой вы остановились.",
        offersSearch: true,
      };
    case "planned":
      return {
        title: "В планах пока пусто",
        text: "Складывайте сюда всё, что хотите посмотреть потом. Kaeru покажет, когда выйдут новые серии.",
        offersSearch: true,
      };
    case "completed":
      return {
        title: "Завершённых тайтлов пока нет",
        text: "Здесь соберётся всё, что вы досмотрели до конца.",
        offersSearch: false,
      };
    case "rewatching":
      return {
        title: "Вы ничего не пересматриваете",
        text: "Отметьте тайтл как «Пересматриваю» — он появится здесь.",
        offersSearch: false,
      };
    case "on_hold":
      return {
        title: "Ничего не отложено",
        text: "Тайтлы на паузе ждут здесь. Вернуться к ним можно в любой вечер.",
        offersSearch: false,
      };
    case "dropped":
      return {
        title: "Ничего не брошено",
        text: "Тайтлы, которые не пошли, собираются здесь, чтобы не мешать остальным.",
        offersSearch: false,
      };
  }
}

export function parseTab(raw: string | null): ListStatus {
  return LIST_TABS.find((status) => status === raw) ?? "watching";
}

export function parseSort(raw: string | null): LibrarySort {
  return raw === "title" ? "title" : "updated";
}
```

`web/src/screens/browse.css`:

```css
/* My list, Search and Settings. The page frame and title are .page and .page-title, and the grid,
   pills, cards, states and dialog come from components.css. A rule here that restyles a Task 8
   class carries two classes, so it wins whichever stylesheet the bundle puts first. */

.browse-body {
  padding: var(--s4) var(--gutter) 0;
}

/* ---------- My list ---------- */

/* The status pills scroll edge to edge on a phone and start in line with the title. */
.pill-group.lib-tabs {
  margin-inline: calc(-1 * var(--gutter));
  padding-inline: var(--gutter);
  scroll-padding-inline: var(--gutter);
}

/* Sort: two segments, the chosen one elevated and ink, the other ink-soft. Never amber. */
.lib-sort {
  display: inline-flex;
  margin-top: var(--s2);
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-card);
}

.lib-sort > button {
  min-height: var(--touch);
  padding: 0 var(--s4);
  border: 0;
  background: transparent;
  color: var(--ink-soft);
  cursor: pointer;
  transition:
    background-color var(--fast) ease-out,
    color var(--fast) ease-out;
}

.lib-sort > button + button {
  border-left: 1px solid var(--line);
}

.lib-sort > button[aria-pressed="true"] {
  background: var(--elevated);
  color: var(--ink);
}

/* The frame clips, so the ring is drawn inside the segment. */
.lib-sort > button:focus-visible {
  outline-offset: -3px;
}

@media (min-width: 768px) and (pointer: fine) {
  .lib-sort > button {
    min-height: 40px;
  }
}

.lib-panel {
  margin-top: var(--s4);
  border-radius: var(--r-card);
}

.lib-panel:focus-visible {
  outline-offset: 4px;
}

/* An empty tab sits under the tabs, in line with them, not centred in a void. */
.lib-panel .state--start {
  padding-inline: 0;
}

/* ---------- Search ---------- */

.srch-form {
  position: relative;
  display: flex;
  align-items: center;
}

.srch-form__icon {
  position: absolute;
  left: var(--s4);
  color: var(--ink-soft);
  pointer-events: none;
}

.srch-form__input {
  width: 100%;
  min-height: var(--button-h);
  margin: 0;
  padding: 0 56px 0 48px;
  border: 1px solid var(--line);
  border-radius: var(--r-card);
  background: var(--surface);
  color: var(--ink);
  appearance: none;
}

.srch-form__input::placeholder {
  color: var(--ink-soft);
  opacity: 1;
}

/* The field draws its own «Очистить». */
.srch-form__input::-webkit-search-cancel-button,
.srch-form__input::-webkit-search-decoration {
  appearance: none;
}

/* Full width: the ring only, no scale. */
.srch-form__input:focus-visible {
  outline-offset: 0;
}

.srch-form__clear.icon-button {
  position: absolute;
  right: 2px;
}

.srch-body {
  margin-top: var(--s6);
}

.srch-section__title {
  margin-bottom: var(--s3);
}

/* ---------- Settings: one form column, 680px at most ---------- */

.set-body {
  max-width: calc(680px + 2 * var(--gutter));
}

.set-section + .set-section {
  margin-top: var(--s8);
}

.set-section__title {
  margin-bottom: var(--s3);
}

.set-account {
  display: flex;
  align-items: center;
  gap: var(--s4);
  margin-bottom: var(--s4);
}

.set-account__avatar {
  flex: none;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--elevated);
  object-fit: cover;
}

.set-account__avatar--letter {
  display: grid;
  place-items: center;
  color: var(--ink-soft);
  font-size: 24px;
  line-height: 34px;
  font-weight: 700;
}

.set-account__text {
  min-width: 0;
}

.set-account__name {
  overflow: hidden;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.set-account__source,
.set-note {
  color: var(--ink-soft);
}

.set-note {
  margin-top: var(--s1);
}

.pill-group.set-choices {
  flex-wrap: wrap;
  margin-top: var(--s2);
}
```

`web/src/screens/LibraryScreen.tsx` (overwrites the Task 8 stub; replace the whole file):

```tsx
import { useEffect, useReducer } from "react";
import type { ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { useServices } from "../app/services";
import type { LibraryEntry } from "../domain/models";
import { useLibrary } from "../library/library";
import {
  SORT_OPTIONS,
  emptyTabCopy,
  libraryCard,
  libraryTabs,
  parseSort,
  parseTab,
  selectLibrary,
} from "../library/listing";
import { watchedThreshold } from "../library/prefs";
import { PillGroup } from "../ui/Pill";
import { PosterCard, PosterGrid } from "../ui/PosterCard";
import { SkeletonGrid, SkeletonGroup } from "../ui/Skeleton";
import { EmptyState, ErrorState } from "../ui/States";
import "./browse.css";

const PANEL_ID = "lib-panel";
// PillGroup gives each tab the id `${TAB_PREFIX}-${status}`; the panel is named by that id.
const TAB_PREFIX = "lib-tab";
const SYNCING = "Синхронизируем список с Shikimori…";

/** «Мой список»: six status tabs over one poster grid (Android LibraryScreen). */
export function LibraryScreen() {
  const { library, progress } = useServices();
  const state = useLibrary(library);
  const [params, setParams] = useSearchParams();
  const [, repaint] = useReducer((count: number) => count + 1, 0);
  const status = parseTab(params.get("tab"));
  const sort = parseSort(params.get("sort"));

  // Card strips read this browser's positions, so a new position repaints the grid.
  useEffect(() => progress.subscribe(repaint), [progress]);

  useEffect(() => {
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);

  // Tab and sort live in the address, so Back from a title returns to the same view.
  function setView(name: "tab" | "sort", value: string) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        next.set(name, value);
        return next;
      },
      { replace: true },
    );
  }

  function retry() {
    void library.load().catch(() => undefined);
  }

  // A plain function, not a nested component: a component type made per render would remount
  // the tabs on every change and lose the keyboard focus.
  function list(entries: readonly LibraryEntry[]): ReactNode {
    const threshold = watchedThreshold();
    const visible = selectLibrary(entries, status, sort);
    const copy = emptyTabCopy(status);
    return (
      <>
        <PillGroup
          kind="tab"
          label="Мой список"
          options={libraryTabs(entries).map((tab) => ({ value: tab.status, label: tab.text }))}
          value={status}
          onChange={(next) => setView("tab", next)}
          panelId={PANEL_ID}
          idPrefix={TAB_PREFIX}
          className="lib-tabs"
        />
        <div className="lib-sort">
          {SORT_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className="t-title-sm"
              aria-pressed={option.value === sort}
              onClick={() => setView("sort", option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <div
          role="tabpanel"
          id={PANEL_ID}
          aria-labelledby={`${TAB_PREFIX}-${status}`}
          tabIndex={0}
          className="lib-panel"
        >
          {visible.length > 0 ? (
            <PosterGrid>
              {visible.map((entry) => (
                <PosterCard
                  key={entry.anime.id}
                  layout="grid"
                  card={libraryCard(entry, progress.of(entry.anime.id), threshold)}
                />
              ))}
            </PosterGrid>
          ) : (
            <EmptyState
              align="start"
              title={copy.title}
              text={copy.text}
              action={copy.offersSearch ? { label: "Найти аниме", to: "/search" } : undefined}
            />
          )}
        </div>
      </>
    );
  }

  const entries = state.kind === "idle" ? null : state.entries;

  return (
    <div className="page">
      <h1 className="page-title t-headline">Мой список</h1>
      <div className="browse-body">
        {entries !== null ? (
          list(entries)
        ) : state.kind === "error" ? (
          <ErrorState message={state.message} onRetry={retry} />
        ) : (
          <SkeletonGroup label={SYNCING}>
            <SkeletonGrid />
          </SkeletonGroup>
        )}
      </div>
    </div>
  );
}
```

`web/src/screens/SearchScreen.tsx` (overwrites the Task 8 stub; replace the whole file):

```tsx
import { useEffect, useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { errorMessage } from "../api/http";
import { useServices } from "../app/services";
import { catalogueCard } from "../domain/feed";
import type { Card } from "../domain/feed";
import type { Anime } from "../domain/models";
import { useLibrary } from "../library/library";
import { IconButton, SecondaryButton } from "../ui/Button";
import { IconClose, IconSearch } from "../ui/icons";
import { PosterCard, PosterGrid } from "../ui/PosterCard";
import { SkeletonGrid, SkeletonGroup } from "../ui/Skeleton";
import { EmptyState, ErrorState } from "../ui/States";
import { useToast } from "../ui/Toast";
import { catalogueCache } from "./home";
import type { CatalogueCache } from "./home";
import "./browse.css";

// Below two characters a query matches half the catalogue (iOS SearchView, Android SearchViewModel).
const MIN_QUERY = 2;
// Pause after the last keystroke before a query goes out (iOS SearchView).
const DEBOUNCE_MS = 350;
// Home's «Популярно сейчас» row reads the same key, so the two screens share one read per 6 h.
const POPULAR_KEY = "now";

export interface SearchScreenProps {
  /** Where «Популярно сейчас» is kept: the module-wide cache Home also uses, unless a test passes its own. */
  catalogue?: CatalogueCache;
}

type Results =
  | { kind: "idle" }
  | { kind: "loading"; query: string }
  | { kind: "found"; query: string; titles: Anime[] }
  | { kind: "none"; query: string }
  | { kind: "failed"; query: string; message: string };

type Popular = { kind: "loading" } | { kind: "ready"; titles: Anime[] } | { kind: "failed" };

// The year rides on the artwork: it tells two seasons of one show apart (Android ResultCard).
function searchCard(anime: Anime): Card {
  return { ...catalogueCard(anime), badge: anime.year === null ? null : String(anime.year), subtitle: null };
}

export function SearchScreen({ catalogue = catalogueCache }: SearchScreenProps) {
  const { shikimori, library } = useServices();
  // Subscribed so cards repaint when a title lands in the list or a failed add is reverted.
  useLibrary(library);
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const [text, setText] = useState(() => params.get("q") ?? "");
  const [results, setResults] = useState<Results>({ kind: "idle" });
  const [popular, setPopular] = useState<Popular>({ kind: "loading" });
  const [adding, setAdding] = useState<ReadonlySet<number>>(() => new Set());
  const field = useRef<HTMLInputElement>(null);
  const sent = useRef<string | null>(null);
  const ticket = useRef(0);
  const opened = useRef(text.trim());

  useEffect(() => {
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);

  useEffect(() => {
    let live = true;
    catalogue.read(POPULAR_KEY, () => shikimori.popularNow()).then(
      (titles) => {
        if (live) setPopular({ kind: "ready", titles });
      },
      () => {
        if (live) setPopular({ kind: "failed" });
      },
    );
    return () => {
      live = false;
    };
  }, [catalogue, shikimori]);

  function run(query: string) {
    sent.current = query;
    ticket.current += 1;
    const mine = ticket.current;
    setResults({ kind: "loading", query });
    setParams({ q: query }, { replace: true });
    shikimori.search(query).then(
      (titles) => {
        // Only the newest query may paint: a slow answer to an older one is dropped.
        if (mine !== ticket.current) return;
        setResults(titles.length > 0 ? { kind: "found", query, titles } : { kind: "none", query });
      },
      (error: unknown) => {
        if (mine !== ticket.current) return;
        setResults({ kind: "failed", query, message: errorMessage(error) });
      },
    );
  }

  useEffect(() => {
    const query = text.trim();
    if (query.length < MIN_QUERY) {
      ticket.current += 1;
      sent.current = null;
      setResults({ kind: "idle" });
      if (params.has("q")) setParams({}, { replace: true });
      return undefined;
    }
    if (query === sent.current) return undefined;
    // A query that came with the address runs at once; typing waits for a pause.
    const delay = sent.current === null && query === opened.current ? 0 : DEBOUNCE_MS;
    const timer = setTimeout(() => {
      if (sent.current !== query) run(query);
    }, delay);
    return () => clearTimeout(timer);
  }, [text]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = text.trim();
    if (query.length >= MIN_QUERY) run(query);
    // Enter also puts the on-screen keyboard away.
    field.current?.blur();
  }

  function clear() {
    setText("");
    field.current?.focus();
  }

  async function add(anime: Anime) {
    setAdding((current) => new Set(current).add(anime.id));
    try {
      await library.setStatus(anime, "planned");
    } catch (error) {
      toast.show(errorMessage(error), {
        label: "Повторить",
        onClick: () => {
          void add(anime);
        },
      });
    } finally {
      setAdding((current) => {
        const next = new Set(current);
        next.delete(anime.id);
        return next;
      });
    }
  }

  function addButton(anime: Anime) {
    // Already in the list wins over a write in flight (Android addAction).
    const inList = library.entry(anime.id) !== undefined;
    const busy = adding.has(anime.id);
    return (
      <SecondaryButton
        compact
        fullWidth
        disabled={inList || busy}
        aria-label={inList || busy ? undefined : `Добавить ${anime.title} в планы`}
        onClick={() => {
          void add(anime);
        }}
      >
        {inList ? "В списке" : busy ? "Добавляем…" : "В планы"}
      </SecondaryButton>
    );
  }

  function grid(titles: readonly Anime[]): ReactNode {
    return (
      <PosterGrid>
        {titles.map((anime) => (
          <PosterCard key={anime.id} layout="grid" card={searchCard(anime)} footer={addButton(anime)} />
        ))}
      </PosterGrid>
    );
  }

  function idle(): ReactNode {
    if (popular.kind === "failed" || (popular.kind === "ready" && popular.titles.length === 0)) {
      return (
        <EmptyState
          title="Что посмотреть сегодня?"
          text="Введите название — Kaeru поищет его на Shikimori и положит найденное в ваш список."
        />
      );
    }
    return (
      <section aria-labelledby="srch-popular">
        <h2 id="srch-popular" className="t-title srch-section__title">
          Популярно сейчас
        </h2>
        {popular.kind === "ready" ? (
          grid(popular.titles)
        ) : (
          <SkeletonGroup>
            <SkeletonGrid />
          </SkeletonGroup>
        )}
      </section>
    );
  }

  function body(): ReactNode {
    switch (results.kind) {
      case "idle":
        return idle();
      case "loading":
        return (
          <SkeletonGroup label="Ищем аниме…">
            <SkeletonGrid />
          </SkeletonGroup>
        );
      case "found":
        return grid(results.titles);
      case "none":
        return <EmptyState title="Ничего не найдено" text="Попробуйте оригинальное название или короче." />;
      case "failed": {
        const query = results.query;
        return <ErrorState message={results.message} onRetry={() => run(query)} />;
      }
    }
  }

  return (
    <div className="page">
      <h1 className="page-title t-headline">Поиск</h1>
      <div className="browse-body">
        <form role="search" className="srch-form" onSubmit={submit}>
          <IconSearch className="srch-form__icon" size={20} />
          <input
            ref={field}
            type="search"
            className="srch-form__input t-body-lg"
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder="Название аниме"
            aria-label="Название аниме"
            enterKeyHint="search"
            autoComplete="off"
            spellCheck={false}
          />
          {text === "" ? null : (
            <IconButton label="Очистить" icon={<IconClose />} className="srch-form__clear" onClick={clear} />
          )}
        </form>
        <div className="srch-body">{body()}</div>
      </div>
    </div>
  );
}
```

`web/src/screens/SettingsScreen.tsx` (overwrites the Task 8 stub; replace the whole file):

```tsx
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useServices } from "../app/services";
import { sessionStore, useAccess } from "../auth/session";
import { THRESHOLD_CHOICES, setWatchedThreshold, watchedThreshold } from "../library/prefs";
import { DestructiveButton } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import { PillGroup } from "../ui/Pill";
import type { PillOption } from "../ui/Pill";
import { posterLetter } from "../ui/PosterCard";
import "./browse.css";

// Android's words (SettingsScreen.kt, SettingsSections.kt).
const NO_NAME = "Имя не загрузилось";
const THRESHOLD = "Порог просмотра";
const THRESHOLD_NOTE = "Серия считается просмотренной после этой доли";

// Two values that round to the same whole percent are one choice (Android thresholdChosen).
function sameChoice(option: number, current: number): boolean {
  return Math.abs(option - current) < 0.005;
}

// «80 %» is one word in Russian: a no-break space keeps the sign with the number.
function percent(fraction: number): string {
  return `${Math.round(fraction * 100)}\u00A0%`;
}

// The four shares, plus a stored one that is none of them, in its place (Android thresholdOptions):
// a row with nothing lit would claim the setting is one of four values when it is a fifth.
function thresholdOptions(current: number): PillOption<number>[] {
  const offered = THRESHOLD_CHOICES.some((choice) => sameChoice(choice, current))
    ? [...THRESHOLD_CHOICES]
    : [...THRESHOLD_CHOICES, current];
  return offered.sort((a, b) => a - b).map((value) => ({ value, label: percent(value) }));
}

/** Account and sign-out, then the watched threshold (decision 10). */
export function SettingsScreen() {
  const access = useAccess();
  const { progress } = useServices();
  const navigate = useNavigate();
  const [threshold, setThreshold] = useState(() => watchedThreshold());
  const [confirming, setConfirming] = useState(false);
  const account = access.kind === "signed_in" ? access.session.account : null;
  const name = account !== null && account.nickname !== "" ? account.nickname : NO_NAME;
  const options = thresholdOptions(threshold);
  const chosen = options.find((option) => sameChoice(option.value, threshold))?.value ?? threshold;

  function choose(value: number) {
    setWatchedThreshold(value);
    // Read back: storage that refused the write keeps the old value, and the row must say so.
    setThreshold(watchedThreshold());
  }

  function signOut() {
    // The dialog promises the local cache goes: every position this browser kept, titles played
    // from search included, not only the ones in the loaded list (Android wipes it on sign-out).
    progress.clear();
    // Signed out on "/", so the next sign-in lands home rather than back in settings.
    navigate("/", { replace: true });
    sessionStore.signOut();
  }

  return (
    <div className="page">
      <h1 className="page-title t-headline">Настройки</h1>
      <div className="browse-body set-body">
        <section className="set-section" aria-labelledby="set-account">
          <h2 id="set-account" className="t-title set-section__title">
            Аккаунт
          </h2>
          <div className="set-account">
            {account?.avatar ? (
              <img className="set-account__avatar" src={account.avatar} alt="" width={56} height={56} />
            ) : (
              <span className="set-account__avatar set-account__avatar--letter" aria-hidden="true">
                {posterLetter(name)}
              </span>
            )}
            <div className="set-account__text">
              <p className="t-title set-account__name">{name}</p>
              <p className="t-body set-account__source">Shikimori</p>
            </div>
          </div>
          <DestructiveButton onClick={() => setConfirming(true)}>Выйти из аккаунта</DestructiveButton>
        </section>
        <section className="set-section" aria-labelledby="set-playback">
          <h2 id="set-playback" className="t-title set-section__title">
            Воспроизведение
          </h2>
          <p className="t-title-sm">{THRESHOLD}</p>
          <p className="t-body set-note">{THRESHOLD_NOTE}</p>
          <PillGroup
            kind="radio"
            label={THRESHOLD}
            options={options}
            value={chosen}
            onChange={choose}
            className="set-choices"
          />
        </section>
      </div>
      <Dialog
        open={confirming}
        title="Выйти из аккаунта?"
        text="Список и прогресс останутся на Shikimori, локальный кэш будет очищен"
        confirmLabel="Выйти"
        cancelLabel="Отмена"
        destructive
        onConfirm={signOut}
        onCancel={() => setConfirming(false)}
      />
    </div>
  );
}
```

- [ ] **Step 4: Run to see it pass**

From `web/` (`cd /Users/vitaliy/Projects/kaeru/web`):
- `npx vitest run src/library/listing.test.ts src/screens/LibraryScreen.test.tsx src/screens/SearchScreen.test.tsx src/screens/SettingsScreen.test.tsx`
  - **Expected:** `Test Files  4 passed (4)` and `Tests  46 passed (46)`: listing 21, LibraryScreen 8, SearchScreen 12, SettingsScreen 5. No act() warnings on stderr.
- `npm test`
  - **Expected:** every test file passes, including the suites of Tasks 1–10. `App.tsx` is unchanged, and its test stays green.
- `npm run typecheck`
  - **Expected:** exits with code 0 and prints nothing. This needs Task 7's `ProgressStore.clear()`.
- `npm run build`
  - **Expected:** `✓ built in …`, with `dist/index.html` plus one `dist/assets/index-*.js` and one `dist/assets/index-*.css`.

- [ ] **Step 5: Commit**

```bash
cd /Users/vitaliy/Projects/kaeru
git add web/src/library/listing.ts web/src/library/listing.test.ts web/src/screens/browse.css web/src/screens/LibraryScreen.tsx web/src/screens/LibraryScreen.test.tsx web/src/screens/SearchScreen.tsx web/src/screens/SearchScreen.test.tsx web/src/screens/SettingsScreen.tsx web/src/screens/SettingsScreen.test.tsx
git commit -m "feat(web): «Мой список», поиск и настройки"
```

---

### Task 12: Hosting and publishing

**Decisions:**
- The test goes in `web/src/app/landing.test.ts`. It reads `docs/cast/404.html` and `docs/cast/w/index.html` from disk with `node:fs`, and finds `docs/cast` from `fileURLToPath(import.meta.url)`.
  - It does not use `?raw` imports. Vite 8 refuses a `?raw` id outside `server.fs.allow`. The repository root has no workspace marker, so the allowed root is `web/`, and the suite would stop with `Denied ID …/docs/cast/404.html?raw` before any test runs.
  - `server.fs.allow` is not widened to the repository root. That would let the dev server hand out `local.properties`.
  - `fileURLToPath` is given the `import.meta.url` string, never `new URL(…, import.meta.url)`. Under jsdom that URL object is jsdom's, and `readFileSync` rejects it with `TypeError: The URL must be of scheme file`.
  - The types for `node:fs`, `node:path` and `node:url` come from `@types/node` 26.6.2 (Task 1).
- The prelude leaves two kinds of path alone:
  - `/`, so a site published without `index.html` shows the landing instead of redirecting forever.
  - Every path under `/w/`, so invitations, their messenger previews and the apps' links behave exactly as today.
- The prelude keeps AssetLinksTest green without edits (map 5):
  - It is a `<script data-route>` tag, not a bare `<script>`. The test treats the text after the first literal `<script>` as "the script".
  - It reads the fragment from `location.href`, because the page may contain `location.hash` only once.
  - It contains no `fetch(`, no identifier `key`, and none of `cdn.`, `unpkg`, `analytics` or `<a class="button`.
- The publish script copies only the tracked files of `docs/cast`, so an ignored `.DS_Store` never ships. A real publish refuses to run when `docs/cast` or `web` has uncommitted changes. `--dry-run` builds and prints the diff against `origin/gh-pages` without committing or pushing.
- Hashed files under `assets/` carry over between publishes.
  - Why: Pages serves `index.html` with `max-age=600`. For up to 10 minutes after a publish, a newly opened tab can get the previous `index.html` and ask for the `assets/index-<oldhash>.js` it names.
  - How: before `web/dist` is copied, the script extracts `assets/` from the current `origin/gh-pages` into the site tree.
  - It skips this when gh-pages has no `assets/` yet, which is true of the first publish. A failed extraction stops the publish, so a stale tab never goes blank without anyone noticing.
  - The name-clash check ignores `assets/`: a hashed name that is already there holds the same bytes.
  - These files accumulate, at a few hundred KB per build. That is far below the Pages limit, so nothing prunes them.
- `assets/` belongs to the build. The script refuses to publish if `docs/cast` ever holds an `assets/` folder, because the clash check would not see it.
- Publishing and the live checks come after the commit in Step 5, because a real publish ships only committed files.
- Browser tests with Playwright are not part of plan 2. They move to the publishing plan. Here the live check is curl plus a headless-Chrome DOM dump.

**Files:**
- Modify: `docs/cast/w/index.html`
- Modify: `docs/cast/404.html`
- Modify: `docs/cast/README.md`
- Create: `web/scripts/publish-site.sh`
- Test: `web/src/app/landing.test.ts`
- Test (existing, must stay green unchanged): `android/src/test/java/app/kaeru/ui/mobile/together/AssetLinksTest.kt`

**Interfaces:**
- Consumes:
  - `restoreDeepLink(location: Location, history: History): void` from `web/src/app/bootstrap.ts` (Task 8). It accepts a `?p=` that starts with a single `/` (not `//` or `/\`) and calls `history.replaceState(null, "", p + location.hash)`.
  - `@types/node` from Task 1's dev dependencies, for `node:fs`, `node:path` and `node:url` in the test.
  - `npm run build` and the committed `web/package-lock.json` (Task 1).
  - The SPA copy used by the live checks (Task 8):
    - «Войти через Shikimori» on the sign-in screen.
    - «Войти ещё раз» on `/auth` when the address carries no code.
    - `<div id="root">` in the built `index.html`.
- Produces:
  - The `<script data-route>` prelude in `docs/cast/w/index.html` and `docs/cast/404.html`. Every path other than `/` and `/w/…` goes to `/?p=<encodeURIComponent(path + query)>`, and the `#fragment` stays a fragment.
  - `web/scripts/publish-site.sh [--dry-run]`. It publishes one tree as a single commit on top of `origin/gh-pages`, with a plain (non-forced) push. The tree is the union of:
    - the tracked files of `docs/cast`;
    - the `assets/` already on `origin/gh-pages`;
    - `web/dist`.
  - The published site `https://kaeru.vitaliy.velikodniy.name`.

- [ ] **Step 1: Write the failing test**

`web/src/app/landing.test.ts`:

```ts
// The invitation landing (docs/cast/w/index.html, served for every missing path as 404.html)
// hands non-invitation paths to the SPA through a <script data-route> prelude; restoreDeepLink
// turns the hop back into the original address. The strings Android's AssetLinksTest counts
// (android/src/test/java/app/kaeru/ui/mobile/together/AssetLinksTest.kt) are re-checked here.
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it, vi } from "vitest";
import { restoreDeepLink } from "./bootstrap";

// docs/cast sits outside web/, where Vite refuses `?raw` imports, so the pages are read from disk.
// fileURLToPath gets the string itself: a URL object here would be jsdom's, which node:fs rejects.
const CAST = resolve(dirname(fileURLToPath(import.meta.url)), "../../../docs/cast");
const fallback = readFileSync(resolve(CAST, "404.html"), "utf8");
const landing = readFileSync(resolve(CAST, "w/index.html"), "utf8");

const ORIGIN = "https://kaeru.vitaliy.velikodniy.name";

function prelude(page: string): string {
  const found = [...page.matchAll(/<script data-route>([\s\S]*?)<\/script>/g)];
  expect(found).toHaveLength(1);
  return found[0]?.[1] ?? "";
}

// Runs the prelude against a fake location and returns where it sent the browser, if anywhere.
function hop(address: string): string | null {
  const url = new URL(address, ORIGIN);
  const replace = vi.fn<(to: string) => void>();
  const run = new Function("location", prelude(landing)) as (location: unknown) => void;
  run({ href: url.href, pathname: url.pathname, search: url.search, replace });
  expect(replace.mock.calls.length).toBeLessThanOrEqual(1);
  return replace.mock.calls[0]?.[0] ?? null;
}

// Feeds the SPA's boot step the hop address and returns the address it restores.
function restore(address: string): string | null {
  const url = new URL(address, ORIGIN);
  const replaceState = vi.fn<(data: unknown, unused: string, to?: string | URL | null) => void>();
  restoreDeepLink(
    { href: url.href, pathname: url.pathname, search: url.search, hash: url.hash } as Location,
    { replaceState } as unknown as History,
  );
  const to = replaceState.mock.calls[0]?.[2];
  return to === undefined || to === null ? null : String(to);
}

describe("the invitation landing", () => {
  it("is served byte for byte as the 404 page", () => {
    expect(fallback).toBe(landing);
  });

  it("runs the route prelude in <head>, before the landing's own script", () => {
    const at = landing.indexOf("<script data-route>");
    expect(at).toBeGreaterThan(-1);
    expect(at).toBeLessThan(landing.indexOf("</head>"));
    expect(at).toBeLessThan(landing.indexOf("<script>"));
  });

  it("keeps every string AssetLinksTest counts where it was", () => {
    const code = prelude(landing);
    for (const banned of ["location.hash", "fetch(", "cdn.", "analytics", "unpkg", '<a class="button']) {
      expect(code).not.toContain(banned);
    }
    expect(code).not.toMatch(/\bkey\b/);
    expect(landing.match(/location\.hash/g)).toHaveLength(1);
    const script = landing.split("<script>")[1]?.split("</script>")[0] ?? "";
    expect(script).toContain("fetch(");
    expect(script).toContain("/^[A-Za-z0-9_-]{22}$/.test(key)");
    const uncommented = script
      .split("\n")
      .filter((line) => !line.trimStart().startsWith("//"))
      .join("\n")
      .replace(/\/\*[\s\S]*?\*\//g, "");
    expect(uncommented.match(/\bkey\b/g)).toHaveLength(4);
  });
});

describe("the route prelude", () => {
  it.each([
    ["/anime/1535", "/?p=%2Fanime%2F1535"],
    ["/auth?code=abc&state=xyz", "/?p=%2Fauth%3Fcode%3Dabc%26state%3Dxyz"],
    ["/auth/?code=abc&state=xyz", "/?p=%2Fauth%2F%3Fcode%3Dabc%26state%3Dxyz"],
    ["/list?tab=planned#top", "/?p=%2Flist%3Ftab%3Dplanned#top"],
  ])("sends %s to the app as %s", (from, to) => {
    expect(hop(from)).toBe(to);
  });

  it.each(["/w/AAAAAAAAAAA#BBBBBBBBBBBBBBBBBBBBBB", "/w/AAAAAAAAAAA", "/w/", "/", "/?p=%2Fanime%2F1535"])(
    "leaves %s on the landing",
    (address) => {
      expect(hop(address)).toBeNull();
    },
  );

  it.each([
    "/anime/1535",
    "/auth?code=abc&state=xyz",
    "/list?tab=planned#top",
    "/search?q=%D1%84%D1%80%D0%B8%D1%80%D0%B5%D0%BD",
  ])("comes back to %s once the app restores it", (address) => {
    const via = hop(address);
    expect(via).not.toBeNull();
    expect(restore(via ?? "")).toBe(address);
  });
});
```

- [ ] **Step 2: Run it to see it fail**

From `web/`: `npx vitest run src/app/landing.test.ts`

**Expected:** FAIL, with `Tests  15 failed | 1 passed (16)`.
- The suite loads and reads both pages from disk. No `Denied ID` error appears.
- «is served byte for byte as the 404 page» passes, because the two pages are identical today.
- «runs the route prelude in <head>, before the landing's own script» fails with `AssertionError: expected -1 to be greater than -1`.
- The other 14 tests fail inside `prelude()` with `AssertionError: expected [] to have a length of 1 but got +0`.

- [ ] **Step 3: Implement**

Modify `docs/cast/w/index.html`. Old text:

```html
<meta property="og:type" content="website">
<style>
```

New text:

```html
<meta property="og:type" content="website">
<script data-route>
  // Kaeru for the browser is the app at the site root. Pages answers every path without a file
  // with this page, so any path outside /w/ is handed to the app as /?p=<path and query>, the
  // fragment kept as a fragment; the app puts the address back when it starts. The root itself
  // stays here, so a site published without the app shows this page instead of looping.
  (function (here) {
    var path = here.pathname;
    if (path === '/' || path.indexOf('/w/') === 0) return;
    var at = here.href.indexOf('#');
    here.replace('/?p=' + encodeURIComponent(path + here.search) + (at < 0 ? '' : here.href.slice(at)));
  })(location);
</script>
<style>
```

Make `docs/cast/404.html` the same bytes by copying the edited file over it. Then check that the two match:

```bash
cd /Users/vitaliy/Projects/kaeru
cp docs/cast/w/index.html docs/cast/404.html
cmp docs/cast/404.html docs/cast/w/index.html && echo identical
```

**Expected:** `identical`.

Create `web/scripts/publish-site.sh`:

```bash
#!/usr/bin/env bash
# Publishes https://kaeru.vitaliy.velikodniy.name from the gh-pages branch: the tracked files of
# docs/cast (Cast receiver skin, App Links files, the invitation landing and its 404 copy), the
# hashed files of earlier publishes under assets/, and the web client build, as one new commit on
# top of origin/gh-pages. The push is a plain one: if gh-pages moved meanwhile it is refused, and
# nothing is ever overwritten.
#
#   web/scripts/publish-site.sh            build, commit and push
#   web/scripts/publish-site.sh --dry-run  build and show what would change; commit nothing
set -euo pipefail

DRY_RUN=0
case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=1 ;;
  *)
    echo "usage: web/scripts/publish-site.sh [--dry-run]" >&2
    exit 2
    ;;
esac

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

# A real publish ships only committed files, so every gh-pages commit maps to a commit here.
if [[ $DRY_RUN -eq 0 && -n "$(git status --porcelain -- docs/cast web)" ]]; then
  echo "docs/cast or web has uncommitted changes; commit them first" >&2
  exit 1
fi

git fetch origin gh-pages
PARENT=$(git rev-parse --verify "origin/gh-pages^{commit}")

npm --prefix web ci
npm --prefix web run build

SITE=$(mktemp -d)
INDEX="$SITE.index"
trap 'rm -rf "$SITE" "$INDEX"' EXIT

# Tracked files only: an ignored .DS_Store in docs/cast must not reach the site.
git ls-files -z -- docs/cast | while IFS= read -r -d '' path; do
  target="$SITE/${path#docs/cast/}"
  mkdir -p "$(dirname "$target")"
  cp "$path" "$target"
done

# assets/ belongs to the build and to the publishes before it.
if [[ -e "$SITE/assets" ]]; then
  echo "docs/cast/assets would mix with the build's hashed files; move it elsewhere" >&2
  exit 1
fi

# Pages serves index.html with max-age=600: for ten minutes a browser may still load the previous
# index.html and ask for the hashed files it names. So the files under assets/ in the current
# gh-pages stay; the new build adds its own next to them.
if git cat-file -e "$PARENT:assets" 2>/dev/null; then
  git archive "$PARENT" assets | tar -x -C "$SITE"
fi

# A build file must never replace a site file: the Cast console, messenger previews and the
# App Links verifiers fetch those by their exact paths. assets/ is exempt: a hashed name that is
# already there holds the same bytes.
CLASHES=$(cd web/dist && find . -type f ! -path './assets/*' | sed 's|^\./||' | while IFS= read -r path; do
  if [[ -e "$SITE/$path" ]]; then echo "$path"; fi
done)
if [[ -n "$CLASHES" ]]; then
  echo "web/dist would overwrite site files:" >&2
  echo "$CLASHES" >&2
  exit 1
fi
cp -R web/dist/. "$SITE"/
find "$SITE" -name .DS_Store -delete

[[ -f "$SITE/index.html" ]] || { echo "web/dist has no index.html" >&2; exit 1; }
[[ -f "$SITE/.nojekyll" ]] || { echo ".nojekyll is missing: Pages would hide .well-known" >&2; exit 1; }
cmp -s "$SITE/404.html" "$SITE/w/index.html" || { echo "404.html and w/index.html differ" >&2; exit 1; }

GIT_DIR_ABS=$(git rev-parse --absolute-git-dir)
(cd "$SITE" && GIT_INDEX_FILE="$INDEX" git --git-dir="$GIT_DIR_ABS" --work-tree=. add -A -f .)
TREE=$(GIT_INDEX_FILE="$INDEX" git --git-dir="$GIT_DIR_ABS" write-tree)

echo "Changes against origin/gh-pages ($PARENT):"
git --no-pager diff --stat "$PARENT" "$TREE"

if [[ "$(git rev-parse "$PARENT^{tree}")" == "$TREE" ]]; then
  echo "gh-pages already holds this site; nothing to publish."
  exit 0
fi
if [[ $DRY_RUN -eq 1 ]]; then
  echo "Dry run: nothing committed or pushed."
  exit 0
fi

COMMIT=$(git commit-tree "$TREE" -p "$PARENT" -m "Сайт: веб-клиент и docs/cast из $(git rev-parse --short HEAD)")
git push origin "$COMMIT:refs/heads/gh-pages"
echo "Published $COMMIT to gh-pages."
```

Make it executable:

```bash
chmod +x /Users/vitaliy/Projects/kaeru/web/scripts/publish-site.sh
```

Modify `docs/cast/README.md` in three hunks.

Hunk 1, old text:

````markdown
# GitHub Pages: скин Chromecast и совместный просмотр

Эта папка целиком уезжает в ветку `gh-pages` (см. «Как обновить»). Кроме скина приёмника
в ней лежат две вещи, от которых зависят ссылки «Смотреть вместе»:
````

Hunk 1, new text:

````markdown
# GitHub Pages: сайт, скин Chromecast и совместный просмотр

Эта папка целиком уезжает в ветку `gh-pages` вместе со сборкой веб-клиента из `web/dist`
(`index.html` и `assets/` в корне сайта; см. «Как обновить»). Кроме скина приёмника в ней лежат
вещи, от которых зависят ссылки «Смотреть вместе»:
````

Hunk 2, old text:

````markdown
| `w/index.html` | Посадочная страница приглашения. |
| `404.html` | Та же страница байт в байт: Pages — статика без рерайтов, и `/w/<room>` — не файл, поэтому реальные ссылки попадают именно сюда. |

Менять `w/index.html` и `404.html` только вместе: `app/src/test/.../AssetLinksTest.kt` падает, если
они разошлись. Проверка после публикации — `adb shell pm get-app-links app.kaeru`: у
````

Hunk 2, new text:

````markdown
| `w/index.html` | Посадочная страница приглашения. Короткий `<script data-route>` в её `<head>` отправляет в веб-клиент любой путь, кроме `/` и `/w/…`: `/anime/1535` → `/?p=%2Fanime%2F1535`, фрагмент `#…` остаётся фрагментом; клиент при запуске возвращает адрес на место. |
| `404.html` | Та же страница байт в байт: Pages — статика без рерайтов, и `/w/<room>` — не файл, поэтому реальные ссылки попадают именно сюда. Сюда же попадают глубокие ссылки веб-клиента (`/anime/…`, `/auth?code=…`), и `<script data-route>` уводит их в клиент. |

Менять `w/index.html` и `404.html` только вместе: `android/src/test/java/app/kaeru/ui/mobile/together/AssetLinksTest.kt`
падает, если они разошлись. Он же считает строки в скрипте страницы, поэтому переход в клиент —
отдельный `<script data-route>` без `location.hash`, `fetch(` и слова `key`; то же проверяет
`web/src/app/landing.test.ts`. Проверка после публикации — `adb shell pm get-app-links app.kaeru`: у
````

Hunk 3, old text:

````markdown
## Как обновить

1. Поправить файлы здесь и закоммитить в рабочую ветку.
2. Выложить папку в `gh-pages` (ветка держит только содержимое `docs/cast`, одним корневым коммитом):

   ```bash
   TREE=$(git rev-parse HEAD:docs/cast)
   git push --force origin "$(git commit-tree "$TREE" -m 'Cast receiver skin')":refs/heads/gh-pages
   ```

3. В консоли открыть приложение Kaeru и нажать Publish ещё раз: приёмники подхватывают
   изменения в течение 10–15 минут, иногда после перезагрузки устройства.
````

Hunk 3, new text:

````markdown
## Как обновить

1. Поправить файлы здесь или в `web/` и закоммитить в рабочую ветку.
2. Из корня репозитория выложить сайт:

   ```bash
   web/scripts/publish-site.sh --dry-run   # собрать и показать изменения, ничего не отправляя
   web/scripts/publish-site.sh
   ```

   Скрипт отказывается работать, если в `docs/cast` или `web` есть незакоммиченные изменения;
   собирает клиент (`npm ci`, `npm run build`); складывает в одно дерево отслеживаемые файлы этой
   папки, `assets/` из текущей `gh-pages` и `web/dist`; останавливается, если файл сборки заменил
   бы файл отсюда; проверяет, что `404.html` совпадает с `w/index.html`; кладёт дерево новым
   коммитом поверх `origin/gh-pages` и отправляет его обычным push, без `--force`. Если ветка ушла
   вперёд, push откажет — публикацию нужно повторить. История ветки сохраняется, так что откат —
   `git push --force-with-lease origin <прежний коммит>:refs/heads/gh-pages`.

   Прежние файлы из `assets/` остаются на сайте: Pages отдаёт `index.html` с `max-age=600`, и
   браузер ещё до 10 минут может открыть старый `index.html` и попросить файлы, которые тот
   называет. В именах этих файлов хеш содержимого, поэтому они только копятся — по нескольку сотен
   килобайт за сборку. Папки `assets/` в `docs/cast` быть не должно: она принадлежит сборке, и
   скрипт откажет.
3. Только если менялись `kaeru.css` или картинки скина: в консоли открыть приложение Kaeru и
   нажать Publish ещё раз — приёмники подхватывают изменения в течение 10–15 минут, иногда после
   перезагрузки устройства.
4. Pages отдаёт файлы с `Cache-Control: max-age=600`: новая версия видна не позже чем через
   10 минут после сборки Pages.
````

- [ ] **Step 4: Run to see it pass**

From `web/`:
- `npx vitest run src/app/landing.test.ts`
  - **Expected:** `Test Files  1 passed (1)` and `Tests  16 passed (16)`.
- `npm test`
  - **Expected:** every test file passes.
- `npm run typecheck`
  - **Expected:** exits with code 0 and prints no errors. `node:fs`, `node:path` and `node:url` resolve through `@types/node`.

From the repository root:

```bash
cd /Users/vitaliy/Projects/kaeru && JAVA_HOME=/Users/vitaliy/Library/Java/JavaVirtualMachines/temurin-21.0.12/Contents/Home ./gradlew :android:testDebugUnitTest --tests '*AssetLinksTest*'
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' android/build/test-results/testDebugUnitTest/TEST-app.kaeru.ui.mobile.together.AssetLinksTest.xml
bash -n web/scripts/publish-site.sh
web/scripts/publish-site.sh --dry-run
```

**Expected:**
- Gradle prints `BUILD SUCCESSFUL`.
- The grep prints `tests="11" skipped="0" failures="0" errors="0"`.
- `bash -n` prints nothing.
- The dry run:
  - runs `npm ci` and the build;
  - carries no `assets/` over, because the live gh-pages has none yet;
  - prints `Changes against origin/gh-pages (<sha>):` and a diff stat that lists `404.html`, `w/index.html`, `README.md`, `index.html`, `fonts/…` and `assets/…`;
  - ends with `Dry run: nothing committed or pushed.`

- [ ] **Step 5: Commit, then publish and verify live**

```bash
cd /Users/vitaliy/Projects/kaeru
git add docs/cast/w/index.html docs/cast/404.html docs/cast/README.md web/scripts/publish-site.sh web/src/app/landing.test.ts
git commit -m "feat(web): публикация сайта на gh-pages и переход с 404 в веб-клиент"
```

Publish from the committed tree:

```bash
cd /Users/vitaliy/Projects/kaeru && web/scripts/publish-site.sh
```

**Expected:** the script ends with `Published <sha> to gh-pages.` From now on gh-pages holds `assets/`, and the next publish keeps these files next to its own.

Wait for the Pages build. Repeat this command until it prints `built`:

```bash
gh api repos/g0ddest/kaeru/pages/builds/latest --jq '.status'
```

Check the live site with curl:

```bash
S=https://kaeru.vitaliy.velikodniy.name
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' "$S/"
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' "$S/kaeru.css"
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' "$S/.well-known/assetlinks.json"
curl -s -o /dev/null -w '%{http_code}\n' "$S/.well-known/apple-app-site-association"
curl -s -o /dev/null -w '%{http_code}\n' "$S/w/AAAAAAAAAAA"
curl -s "$S/w/AAAAAAAAAAA" | grep -q 'Вас зовут смотреть вместе' && echo "invitation landing ok"
curl -s "$S/anime/1" | grep -q '<script data-route>' && echo "404 hop ok"
curl -s "$S/" | grep -q '<div id="root">' && echo "spa shell ok"
```

**Expected**, in order:
1. `200 text/html; charset=utf-8`
2. `200 text/css; charset=utf-8`
3. `200 application/json; charset=utf-8`
4. `200`
5. `404`
6. `invitation landing ok`
7. `404 hop ok`
8. `spa shell ok`

If an old body comes back, wait out the 10-minute cache (`max-age=600`) and repeat.

Check with headless Chrome:

```bash
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
S=https://kaeru.vitaliy.velikodniy.name
dump() { "$CHROME" --headless=new --disable-gpu --virtual-time-budget=10000 --dump-dom "$1" 2>/dev/null; }
dump "$S/" | grep -q 'Войти через Shikimori' && echo "/ lands in the SPA"
dump "$S/anime/1535" | grep -q 'Войти через Shikimori' && echo "/anime/1535 lands in the SPA"
dump "$S/auth" | grep -q 'Войти ещё раз' && echo "/auth lands on the callback route"
dump "$S/w/AAAAAAAAAAA#BBBBBBBBBBBBBBBBBBBBBB" | grep -q 'Вас зовут смотреть вместе' && echo "/w/ keeps the landing"
dump "$S/w/AAAAAAAAAAA#BBBBBBBBBBBBBBBBBBBBBB" | grep -q 'id="root"' || echo "/w/ never loads the SPA"
```

**Expected:** all five lines print:
- `/ lands in the SPA`
- `/anime/1535 lands in the SPA`
- `/auth lands on the callback route`
- `/w/ keeps the landing`
- `/w/ never loads the SPA`

The skin files did not change, so no Cast console Publish is needed. If `adb devices` lists a device with Kaeru installed, run `adb shell pm get-app-links app.kaeru`. **Expected:** `kaeru.vitaliy.velikodniy.name: verified`.

---

