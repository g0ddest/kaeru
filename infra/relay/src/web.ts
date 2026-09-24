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
