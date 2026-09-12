#!/usr/bin/env python3
"""Throwaway spike: shikimori_id -> Kodik HLS URL -> CORS check -> Chromecast.

Usage: python3 tools/kodik_probe.py <shikimori_id> [episode] [--cast]
"""
import base64
import json
import os
import re
import subprocess
import sys

import requests

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0 Safari/537.36")
API = "https://kodik-api.com"
PLAYER_HOST = "https://kodikplayer.com"
ADD_PLAYERS_JS = "https://kodik-add.com/add-players.min.js?v=2"
FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")

s = requests.Session()
s.headers["User-Agent"] = UA


def public_token() -> str:
    env = os.environ.get("KODIK_TOKEN")
    if env:
        return env
    js = s.get(ADD_PLAYERS_JS, timeout=20).text
    m = re.search(r'token="([a-z0-9]+)"', js)
    if not m:
        sys.exit("token not found in add-players.min.js — mechanism changed")
    return m.group(1)


def search(token: str, shikimori_id: int) -> list[dict]:
    r = s.post(f"{API}/search", data={
        "token": token, "shikimori_id": shikimori_id,
        "with_episodes": "true", "limit": 100}, timeout=20)
    data = r.json()
    if "error" in data:
        sys.exit(f"kodik search error: {data['error']}")
    return data["results"]


def player_page(link: str, season: int, episode: int) -> str:
    url = link if link.startswith("http") else "https:" + link
    url = re.sub(r"https://[^/]+", PLAYER_HOST, url)
    url = f"{url}?season={season}&episode={episode}"
    r = s.get(url, headers={"Referer": PLAYER_HOST + "/"}, timeout=20)
    r.raise_for_status()
    return r.text


def parse_page(html: str) -> dict:
    url_params = json.loads(re.search(r"urlParams\s*=\s*'([^']+)'", html).group(1))
    info = {k: re.search(rf"videoInfo\.{k}\s*=\s*'([^']+)'", html).group(1)
            for k in ("type", "hash", "id")}
    script = re.search(r'src="(/assets/js/app\.player_single\.[^"]+\.js)"', html).group(1)
    js = s.get(PLAYER_HOST + script, timeout=20).text
    post_path = base64.b64decode(
        re.search(r'atob\("([^"]+)"\)', js).group(1)).decode()
    return {"url_params": url_params, "info": info, "post_path": post_path}


def rot(text: str, n: int) -> str:
    out = []
    for ch in text:
        if "a" <= ch <= "z":
            out.append(chr((ord(ch) - 97 + n) % 26 + 97))
        elif "A" <= ch <= "Z":
            out.append(chr((ord(ch) - 65 + n) % 26 + 65))
        else:
            out.append(ch)
    return "".join(out)


def decode_src(enc: str) -> str:
    for n in range(26):
        candidate = rot(enc, n)
        candidate += "=" * (-len(candidate) % 4)
        try:
            url = base64.b64decode(candidate).decode()
        except Exception:
            continue
        if "mp4:hls:manifest" in url:
            return url if url.startswith("http") else "https:" + url
    raise RuntimeError("could not decode src")


def resolve_links(page: dict) -> dict[str, str]:
    p = page["url_params"]
    r = s.post(PLAYER_HOST + page["post_path"], data={
        "hash": page["info"]["hash"], "id": page["info"]["id"],
        "type": page["info"]["type"], "d": p["d"], "d_sign": p["d_sign"],
        "pd": p["pd"], "pd_sign": p["pd_sign"], "ref": "",
        "ref_sign": p["ref_sign"], "bad_user": "true", "cdn_is_working": "true",
    }, headers={"Referer": PLAYER_HOST + "/", "Origin": PLAYER_HOST,
                "X-Requested-With": "XMLHttpRequest"}, timeout=20)
    r.raise_for_status()
    os.makedirs(FIXTURES, exist_ok=True)
    with open(os.path.join(FIXTURES, "kodik_links.json"), "w") as f:
        f.write(r.text)
    links = r.json()["links"]
    return {q: decode_src(v[0]["src"]) for q, v in links.items()}


def cors_check(url: str) -> None:
    r = s.get(url, headers={"Origin": "https://example.com"}, timeout=20)
    print(f"  manifest HTTP {r.status_code}, "
          f"Access-Control-Allow-Origin={r.headers.get('Access-Control-Allow-Origin')}")
    first_seg = next((l for l in r.text.splitlines() if l and not l.startswith("#")), None)
    if first_seg:
        seg = first_seg if first_seg.startswith("http") else url.rsplit("/", 1)[0] + "/" + first_seg
        rs = s.head(seg, headers={"Origin": "https://example.com"}, timeout=20)
        print(f"  segment  HTTP {rs.status_code}, "
              f"Access-Control-Allow-Origin={rs.headers.get('Access-Control-Allow-Origin')}")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    shikimori_id = int(args[0])
    episode = int(args[1]) if len(args) > 1 else 1
    token = public_token()
    print(f"token: {token[:4]}…{token[-4:]}")
    results = search(token, shikimori_id)
    if not results:
        sys.exit("nothing found on kodik for this shikimori_id")
    by_translation = {r["translation"]["id"]: r for r in results}
    for r in by_translation.values():
        print(f"- [{r['translation']['id']}] {r['translation']['title']} "
              f"eps={r.get('episodes_count')} last_season={r.get('last_season')} {r['link']}")
    chosen = max(by_translation.values(), key=lambda r: r.get("episodes_count") or 0)
    season = chosen.get("last_season") or 1
    print(f"chosen: {chosen['translation']['title']} season={season} episode={episode}")
    html = player_page(chosen["link"], season, episode)
    os.makedirs(FIXTURES, exist_ok=True)
    with open(os.path.join(FIXTURES, "kodik_player.html"), "w") as f:
        f.write(html)
    page = parse_page(html)
    links = resolve_links(page)
    for q, u in sorted(links.items(), key=lambda kv: int(kv[0])):
        print(f"{q}p: {u}")
    best = links[max(links, key=int)]
    print("CORS check:")
    cors_check(best)
    if "--cast" in sys.argv:
        print("casting via catt…")
        subprocess.run(["catt", "cast", best], check=False)


if __name__ == "__main__":
    main()
