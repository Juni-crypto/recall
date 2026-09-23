#!/usr/bin/env bash
# Builds Recall.ipa for sideloading (AltStore, SideStore). It isn't signed with a developer
# certificate: those tools re-sign it with the user's Apple ID. An ad-hoc signature carries the
# App Group entitlement, so the widget can read the app's data after re-signing.
set -euo pipefail
IOS="$(cd "$(dirname "$0")/.." && pwd)"
cd "$IOS"
"$IOS/scripts/build-llama.sh"
command -v xcodegen >/dev/null && xcodegen --quiet
xcodebuild -project Recall.xcodeproj -scheme Recall -configuration Release -sdk iphoneos \
  -destination 'generic/platform=iOS' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build | tail -3
APP="$IOS/build/Build/Products/Release-iphoneos/Recall.app"
for f in "$APP"/Frameworks/*.framework; do codesign -f -s - "$f"; done
codesign -f -s - --entitlements RecallWidget/RecallWidget.entitlements "$APP/PlugIns/RecallWidget.appex"
codesign -f -s - --entitlements Recall/Recall.entitlements "$APP"
OUT="$IOS/build/ipa"
rm -rf "$OUT" && mkdir -p "$OUT/Payload"
cp -R "$APP" "$OUT/Payload/"
(cd "$OUT" && zip -qry Recall.ipa Payload)
echo "Built $OUT/Recall.ipa ($(du -h "$OUT/Recall.ipa" | cut -f1))"
