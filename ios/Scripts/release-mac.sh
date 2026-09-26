#!/usr/bin/env bash
# Builds Kaeru for the Mac the way everyone outside the App Store gets it: archived, signed with the
# team's Developer ID certificate, packed into a disk image, notarized by Apple and stapled, so that
# Gatekeeper opens it on a Mac that has never seen it — offline too. The image is
# Kaeru-<version>-mac.dmg, the file the Mac app's updater looks for on a GitHub release (the one whose
# name ends in .dmg). Attaching it to the release is left to a person; this script uploads nothing.
#
#   ios/Scripts/release-mac.sh              everything, up to a stapled image
#   ios/Scripts/release-mac.sh --dry-run    the same up to notarization, or up to the first missing
#                                           piece; nothing is submitted to Apple's notary service
#   ... --out DIR                           where the build, the export and the image go
#                                           (default ios/build/mac, which git ignores)
#
# Environment:
#   MAC_ASSOCIATED_DOMAINS=0   no universal links: the build needs no provisioning profile, and
#                              nothing is asked of the developer portal (default 1 — a release opens
#                              invitations in the app)
#   NOTARY_PROFILE             the notarytool keychain profile (default kaeru-notary)
#   DEVELOPMENT_TEAM           as for generate_project.rb (default TXY49DW96F)
set -euo pipefail

usage() {
  echo "usage: ios/Scripts/release-mac.sh [--dry-run] [--out DIR]" >&2
  exit 2
}

DRY_RUN=0
OUT=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --out) [[ $# -ge 2 ]] || usage; OUT=$2; shift ;;
    *) usage ;;
  esac
  shift
done

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
OUT=${OUT:-ios/build/mac}
mkdir -p "$OUT/logs"
OUT=$(cd "$OUT" && pwd)

fail() { echo "release-mac: $*" >&2; exit 1; }
step() { echo "==> $*"; }
note() { echo "    $*"; }

# A command whose output would bury the screen: it goes to a log, and a failure shows its errors.
run() {
  local log=$1
  shift
  if ! "$@" >"$log" 2>&1; then
    { grep -E "error:|\*\* [A-Z ]+ FAILED \*\*" "$log" || tail -n 5 "$log"; } | tail -n 15 >&2 || true
    fail "шаг не прошёл, подробности в $log"
  fi
}

# The same sources the project generator reads, in the same order: environment, local.properties.
property() {
  [[ -f local.properties ]] || return 0
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" local.properties | tail -n 1
}
TEAM=${DEVELOPMENT_TEAM:-$(property DEVELOPMENT_TEAM)}
TEAM=${TEAM:-TXY49DW96F}
PROFILE=${NOTARY_PROFILE:-kaeru-notary}
case "$(printf '%s' "${MAC_ASSOCIATED_DOMAINS:-1}" | tr '[:upper:]' '[:lower:]')" in
  1 | true | yes) LINKS=1 ;;
  *) LINKS=0 ;;
esac
# The version is named once, by the Android client, as generate_project.rb reads it.
VERSION=$(sed -nE 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' android/build.gradle.kts)
VERSION=${VERSION%%$'\n'*}
[[ -n $VERSION ]] || fail "не нашёл versionName в android/build.gradle.kts"
DMG="$OUT/Kaeru-$VERSION-mac.dmg"

CERT_HELP="нет сертификата «Developer ID Application» команды $TEAM с закрытым ключом в связке ключей.
    Один раз: Xcode → Settings → Accounts → команда $TEAM → Manage Certificates… → «+» →
    Developer ID Application. Сохраните его копию (.p12) из «Связки ключей»: таких сертификатов
    у команды немного."
NOTARY_HELP="нет профиля нотаризации «${PROFILE}» в связке ключей.
    Один раз: создайте пароль приложения на account.apple.com → «Вход и безопасность» →
    «Пароли приложений» и выполните
    xcrun notarytool store-credentials $PROFILE --apple-id <ваш Apple ID> --team-id $TEAM
    (команда спросит этот пароль и сразу его проверит)."

step "Kaeru $VERSION для Mac → $OUT"

# A release ships committed code only, so the image maps to a commit. A dry run may build a draft.
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  if [[ $DRY_RUN -eq 1 ]]; then
    note "в рабочей копии есть незакоммиченные изменения — пробная сборка возьмёт их как есть"
  else
    fail "в рабочей копии есть незакоммиченные изменения: выпуск собирается только из коммита"
  fi
fi

# The two things only the owner of the team can provide, both looked up before anything is built.
# codesign signs the disk image itself, and that takes the certificate's private key in this Mac's
# keychain — a certificate Xcode keeps in the cloud cannot sign it.
IDENTITY=$(security find-identity -v -p codesigning \
  | sed -nE "s/^ *[0-9]+\) ([0-9A-F]{40}) \"Developer ID Application: .*\($TEAM\)\"$/\1/p")
IDENTITY=${IDENTITY%%$'\n'*}
NOTARY_OUTPUT=$(xcrun notarytool history --keychain-profile "$PROFILE" 2>&1) && NOTARY=1 || NOTARY=0
if [[ $NOTARY -eq 0 ]] && ! grep -q "No Keychain password item found" <<<"$NOTARY_OUTPUT"; then
  # The profile is there and Apple turned it down, or Apple could not be reached: a missing
  # profile is one message and this is another, and the step that needs it says which.
  NOTARY_HELP="профиль нотаризации «${PROFILE}» есть, но notarytool не смог им воспользоваться:
    $(tail -n 3 <<<"$NOTARY_OUTPUT")
    Проверьте сеть; если пароль приложения отозван, создайте новый и снова выполните
    xcrun notarytool store-credentials $PROFILE --apple-id <ваш Apple ID> --team-id $TEAM"
fi
if [[ $DRY_RUN -eq 0 && ( -z $IDENTITY || $NOTARY -eq 0 ) ]]; then
  [[ -n $IDENTITY ]] || echo "release-mac: $CERT_HELP" >&2
  [[ $NOTARY -eq 1 ]] || echo "release-mac: $NOTARY_HELP" >&2
  fail "выпуск не начат. Пробный прогон (--dry-run) соберёт всё, что можно собрать без этого."
fi
# A dry run goes as far as it can and stops, with the same words, where a piece is missing.
missing() {
  echo "release-mac: $1" >&2
  fail "пробный прогон остановлен на этом шаге; всё до него собрано в $OUT"
}

if [[ $LINKS -eq 1 ]]; then
  # Universal links need the site to name this app too. The file is in this repository; the site
  # is published from it separately, and Apple's CDN holds its copy for up to an hour.
  grep -q "\"$TEAM.app.kaeru.mac\"" docs/cast/.well-known/apple-app-site-association \
    || note "внимание: docs/cast/.well-known/apple-app-site-association не называет $TEAM.app.kaeru.mac"
  PUBLISHED=$(curl -fsS --max-time 15 https://app-site-association.cdn-apple.com/a/v1/kaeru.vitaliy.velikodniy.name 2>/dev/null || true)
  if ! grep -q "\"$TEAM.app.kaeru.mac\"" <<<"$PUBLISHED"; then
    note "внимание: Apple пока не видит $TEAM.app.kaeru.mac на сайте — ссылки-приглашения на Mac"
    note "откроются в браузере, пока сайт не опубликован (web/scripts/publish-site.sh) и CDN не обновился"
  fi
else
  note "без универсальных ссылок (MAC_ASSOCIATED_DOMAINS=0): приглашения открываются через kaeru://"
fi
[[ -f ios/App/Mac/GoogleService-Info.plist ]] \
  || note "ios/App/Mac/GoogleService-Info.plist нет: сборка без Firebase, отчёты о падениях с Mac не придут"

# The project is generated for the release and generated back afterwards, as the environment this
# script was started with would have it — a later everyday build is then the same as before.
step "Проект Xcode"
run "$OUT/logs/generate.log" env MAC_ASSOCIATED_DOMAINS=$LINKS ruby ios/App/generate_project.rb
restore() {
  ruby ios/App/generate_project.rb >/dev/null 2>&1 \
    || echo "release-mac: не удалось перегенерировать проект, выполните ruby ios/App/generate_project.rb" >&2
}
trap restore EXIT

# The developer portal is asked only when the build carries an entitlement that needs a profile:
# associated domains, for the archive's development profile and the export's Developer ID one.
# Without it both sign with the certificates alone.
PORTAL=()
[[ $LINKS -eq 1 ]] && PORTAL=(-allowProvisioningUpdates)

step "Архив (Release, arm64) — несколько минут"
rm -rf "$OUT/Kaeru.xcarchive"
run "$OUT/logs/archive.log" xcodebuild -project ios/App/Kaeru.xcodeproj -scheme KaeruMac \
  -configuration Release -destination 'generic/platform=macOS' \
  -derivedDataPath "$OUT/DerivedData" -archivePath "$OUT/Kaeru.xcarchive" \
  ${PORTAL[@]+"${PORTAL[@]}"} archive

[[ -n $IDENTITY ]] || missing "$CERT_HELP"

step "Экспорт с подписью Developer ID"
cat >"$OUT/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
    <key>method</key><string>developer-id</string>
    <key>signingStyle</key><string>automatic</string>
    <key>teamID</key><string>$TEAM</string>
    <key>destination</key><string>export</string>
</dict></plist>
PLIST
rm -rf "$OUT/export"
run "$OUT/logs/export.log" xcodebuild -exportArchive -archivePath "$OUT/Kaeru.xcarchive" \
  -exportPath "$OUT/export" -exportOptionsPlist "$OUT/ExportOptions.plist" \
  ${PORTAL[@]+"${PORTAL[@]}"}
APP="$OUT/export/Kaeru.app"

# What notarization will look at, checked here, where the answer is immediate.
codesign --verify --deep --strict "$APP" || fail "подпись $APP не проходит проверку"
SIGNATURE=$(codesign -dvv "$APP" 2>&1)
grep -q "^Authority=Developer ID Application: .*($TEAM)" <<<"$SIGNATURE" \
  || fail "приложение подписано не сертификатом Developer ID команды $TEAM"
grep -qE "^CodeDirectory .*flags=.*runtime" <<<"$SIGNATURE" \
  || fail "у приложения нет hardened runtime — нотаризация его не примет"
ENTITLEMENTS=$(codesign -d --entitlements - --xml "$APP" 2>/dev/null)
! grep -q "get-task-allow" <<<"$ENTITLEMENTS" \
  || fail "в подписи осталось get-task-allow — это отладочная сборка, нотаризация её не примет"
grep -q "com.apple.security.app-sandbox" <<<"$ENTITLEMENTS" || fail "в подписи нет App Sandbox"
if [[ $LINKS -eq 1 ]]; then
  grep -q "applinks:" <<<"$ENTITLEMENTS" || fail "в подписи нет associated domains, хотя сборка с ними"
fi
BUILT=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Contents/Info.plist")
[[ $BUILT == "$VERSION" ]] || fail "в приложении версия $BUILT, а выпускается $VERSION"

# The image a person opens: the app and a link to Applications to drag it onto. macOS 27 calls this
# form of `hdiutil create` deprecated in favour of `diskutil image`; it writes the same image, and
# hdiutil is on every macOS this may run on.
step "Образ диска"
STAGE="$OUT/dmg"
rm -rf "$STAGE"
mkdir -p "$STAGE"
ditto "$APP" "$STAGE/Kaeru.app"
ln -s /Applications "$STAGE/Applications"
run "$OUT/logs/dmg.log" hdiutil create -volname Kaeru -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov -o "$DMG"
# Signed as well as the app inside it: Apple notarizes the outermost container, and an unsigned
# image's ticket cannot be stapled to it.
run "$OUT/logs/codesign-dmg.log" codesign --force --sign "$IDENTITY" --timestamp -i app.kaeru.mac.dmg "$DMG"
codesign --verify --strict "$DMG" || fail "подпись образа не проходит проверку"

[[ $NOTARY -eq 1 ]] || missing "$NOTARY_HELP"
if [[ $DRY_RUN -eq 1 ]]; then
  step "Пробный прогон: образ собран и подписан, в нотаризацию не отправлялся"
  note "$DMG"
  exit 0
fi

step "Нотаризация — обычно несколько минут"
xcrun notarytool submit "$DMG" --keychain-profile "$PROFILE" --wait 2>&1 | tee "$OUT/logs/notary.log" || true
if ! grep -q "status: Accepted" "$OUT/logs/notary.log"; then
  SUBMISSION=$(sed -nE 's/^[[:space:]]*id: ([0-9a-f-]+)$/\1/p' "$OUT/logs/notary.log" | tail -n 1)
  fail "Apple не приняла образ. Почему — в журнале: xcrun notarytool log ${SUBMISSION:-<id>} --keychain-profile $PROFILE"
fi

step "Билет нотаризации в образ"
run "$OUT/logs/staple.log" xcrun stapler staple "$DMG"
run "$OUT/logs/staple-validate.log" xcrun stapler validate "$DMG"
spctl -a -t open --context context:primary-signature -v "$DMG" >"$OUT/logs/spctl.log" 2>&1 \
  || fail "Gatekeeper не принимает образ: $(cat "$OUT/logs/spctl.log")"
sed 's/^/    /' "$OUT/logs/spctl.log"

step "Готово: $DMG ($(du -h "$DMG" | cut -f 1 | tr -d ' '))"
note "Осталось приложить его к выпуску v$VERSION на GitHub — Mac-приложение найдёт его по .dmg:"
note "gh release upload v$VERSION \"$DMG\""
