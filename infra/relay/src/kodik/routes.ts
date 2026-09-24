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
