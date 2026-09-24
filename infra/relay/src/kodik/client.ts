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
