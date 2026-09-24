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
