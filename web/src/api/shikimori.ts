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
