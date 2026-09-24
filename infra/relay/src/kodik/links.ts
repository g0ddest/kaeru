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
      decoded = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
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
