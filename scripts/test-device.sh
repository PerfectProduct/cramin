#!/usr/bin/env bash
# Инструментированные тесты Cramin на эмуляторе emulator-5554 (AVD theygrow-api34).
#
#   scripts/test-device.sh            — все тесты без @LiveApi
#   scripts/test-device.sh --live     — только @LiveApi; ключ из .env уходит аргументом
#                                       инструментирования и нигде не печатается
#   scripts/test-device.sh --class FQCN   — один класс (можно вместе с --live)
#   scripts/test-device.sh --wipe     — холодный старт с -wipe-data (после ANR/зависаний)
#
# Правила локального цикла (CLAUDE.md, (а)–(г)): вывод Gradle не в пайп, а в файл;
# adb start-server до Gradle; без set -e, чтобы отчёт печатался и при красном прогоне.
set -uo pipefail

AVD="theygrow-api34"
SERIAL="emulator-5554"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="${CRAMIN_TEST_LOG:-$ROOT/app/build/test-device.log}"
EMU_LOG="${CRAMIN_EMULATOR_LOG:-/tmp/cramin-emulator.log}"

LIVE=0; WIPE=0; CLASS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --live) LIVE=1 ;;
    --wipe) WIPE=1 ;;
    --class) shift; CLASS="${1:-}" ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Неизвестный аргумент: $1" >&2; exit 2 ;;
  esac
  shift
done

cd "$ROOT" || exit 2
mkdir -p "$(dirname "$LOG")"

# (б) adb-сервер поднимаем до Gradle, иначе Gradle сам его породит и удержит пайп.
adb start-server >/dev/null 2>&1

emulator_running() { adb -s "$SERIAL" get-state 2>/dev/null | grep -q '^device$'; }

start_emulator() {
  local extra=()
  [ "$WIPE" = 1 ] && extra+=(-wipe-data -no-snapshot-load)
  # Без окна и звука: на WSL2 это проверенный режим (см. decision-log CRM-DL-004).
  if [ -z "${CRAMIN_EMULATOR_WINDOW:-}" ]; then extra+=(-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect); fi
  echo "==> запускаю эмулятор $AVD (${extra[*]:-с окном})"
  nohup emulator -avd "$AVD" -no-snapshot-save "${extra[@]}" > "$EMU_LOG" 2>&1 &
  adb -s "$SERIAL" wait-for-device
}

if [ "$WIPE" = 1 ] && emulator_running; then
  echo "==> --wipe: останавливаю запущенный эмулятор"
  adb -s "$SERIAL" emu kill >/dev/null 2>&1
  for _ in $(seq 1 30); do emulator_running || break; sleep 1; done
fi

if ! emulator_running; then start_emulator; fi

echo "==> жду sys.boot_completed"
for _ in $(seq 1 180); do
  if [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then break; fi
  sleep 2
done
if [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
  echo "Эмулятор не загрузился за 6 минут; лог: $EMU_LOG" >&2; exit 1
fi

# Анимации мешают Compose-тестам: гасим все три шкалы.
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$SERIAL" shell settings put global "$k" 0 >/dev/null 2>&1
done
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1

ARGS=(connectedDebugAndroidTest)
if [ -n "$CLASS" ]; then
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$CLASS")
fi
if [ "$LIVE" = 1 ]; then
  # Ключ читается в подоболочке одной командой, без эха и без set -x.
  if ! { test -f .env && grep -Eq '^[[:space:]]*OPENROUTER_API_KEY[[:space:]]*=[[:space:]]*[^[:space:]]+' .env; }; then
    echo "Нет OPENROUTER_API_KEY в .env — живые тесты невозможны. Запустите scripts/setup-secrets.sh" >&2; exit 1
  fi
  key="$(sed -nE 's/^[[:space:]]*(export[[:space:]]+)?OPENROUTER_API_KEY[[:space:]]*=[[:space:]]*//p' .env | head -n1 | tr -d '\r' | sed -E 's/[[:space:]]+$//; s/^"(.*)"$/\1/')"
  budget="$(sed -nE 's/^[[:space:]]*LIVE_BUDGET_USD[[:space:]]*=[[:space:]]*//p' .env | head -n1 | tr -d '\r' | sed -E 's/[[:space:]]+$//')"
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.openrouterKey=$key")
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.liveBudgetUsd=${budget:-1.00}")
  [ -z "$CLASS" ] && ARGS+=("-Pandroid.testInstrumentationRunnerArguments.annotation=pro.perfectproduct.cramin.LiveApi")
else
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.notAnnotation=pro.perfectproduct.cramin.LiveApi")
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.notClass=pro.perfectproduct.cramin.ingest.PublicSourceTest")
fi

echo "==> ./gradlew ${ARGS[0]} (лог: $LOG; ключ не печатается)"
# (а) Вывод Gradle — в файл, не в пайп.
ANDROID_SERIAL="$SERIAL" ./gradlew "${ARGS[@]}" > "$LOG" 2>&1
STATUS=$?
unset key

R="$ROOT/app/build/outputs/androidTest-results/connected/debug"
if [ -d "$R" ]; then
  find "$R" -name '*.xml' -exec grep -ho '<testsuite [^>]*tests="[0-9]*"[^>]*' {} \; | sed -E 's/ hostname="[^"]*"//; s/ timestamp="[^"]*"//'
else
  echo "(отчётов XML нет: $R)"
fi

if [ "$LIVE" = 1 ]; then
  # Ключ не должен осесть в отчётах и логах UTP; если осел — вычищаем и предупреждаем.
  prefix="$(sed -nE 's/^[[:space:]]*(export[[:space:]]+)?OPENROUTER_API_KEY[[:space:]]*=[[:space:]]*//p' .env | head -n1 | tr -d '\r' | cut -c1-12)"
  if [ -n "$prefix" ] && grep -rIlF -- "$prefix" app/build/outputs app/build/reports "$LOG" >/dev/null 2>&1; then
    grep -rIlF -- "$prefix" app/build/outputs app/build/reports "$LOG" 2>/dev/null | while read -r f; do
      sed -i "s/${prefix}[A-Za-z0-9_-]*/<redacted-key>/g" "$f"
    done
    echo "ВНИМАНИЕ: префикс ключа найден в артефактах тестов и вычищен. См. decision-log."
  fi
  unset prefix
  # Стоимость живых прогонов — из additionalTestOutput (live-cost.json) или logcat.
  find "$ROOT/app/build/outputs" -name 'live-cost*.json' -exec sh -c 'echo "==> $1:"; cat "$1"; echo' _ {} \; 2>/dev/null
fi

if [ "$STATUS" -ne 0 ]; then
  echo "==> КРАСНЫЙ прогон (код $STATUS). Хвост лога:"
  tail -n 40 "$LOG"
  echo "==> Фокус окна (диагностика ANR, правило (в)):"
  adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep mCurrentFocus
fi
exit $STATUS
