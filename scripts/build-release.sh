#!/usr/bin/env bash
# Локальная подписанная release-сборка: путь к keystore.properties берётся из .env
# (только переменная CRAMIN_KEYSTORE_PROPERTIES, значения не печатаются), затем проверка отпечатка.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
LOG="${CRAMIN_RELEASE_LOG:-$ROOT/app/build/release-build.log}"

props="${CRAMIN_KEYSTORE_PROPERTIES:-}"
if [ -z "$props" ] && [ -f .env ]; then
  props="$(sed -nE 's/^[[:space:]]*(export[[:space:]]+)?CRAMIN_KEYSTORE_PROPERTIES[[:space:]]*=[[:space:]]*//p' .env | head -n1 | tr -d '\r' | sed -E 's/[[:space:]]+$//; s/^"(.*)"$/\1/')"
fi
props="${props/#\~/$HOME}"
if [ -z "$props" ] || [ ! -f "$props" ]; then
  echo "Нет keystore.properties: запустите scripts/setup-secrets.sh (блок «Релизная подпись»)" >&2
  exit 1
fi
mkdir -p "$(dirname "$LOG")"
echo "==> assembleRelease (лог: $LOG)"
CRAMIN_KEYSTORE_PROPERTIES="$props" ./gradlew assembleRelease > "$LOG" 2>&1 || { tail -n 30 "$LOG"; exit 1; }
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
ls -la "$APK"
python3 "$ROOT/scripts/verify_release_signature.py" "$APK"
