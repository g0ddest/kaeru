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
