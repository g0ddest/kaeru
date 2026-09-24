# Kodik probe

Answers one question: does a browser play a Kodik link that the Worker resolved on Cloudflare's IP?

    cd infra/relay && npx wrangler dev --remote --port 8787     # routes run on Cloudflare's network
    cd tools/kodik-probe && python3 -m http.server 5173
    open "http://localhost:5173/index.html?worker=http://localhost:8787"

PLAYING, fragments loaded and a time past 600 after the seek: links are not bound to the resolving
address — the browser plays Kodik's CDN directly. An HTTP 403/410 on the manifest or the first
fragment: they are bound, and video has to go through the Worker (spec §5).
