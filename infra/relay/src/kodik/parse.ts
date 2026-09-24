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
