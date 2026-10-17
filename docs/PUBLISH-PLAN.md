# План выпуска симметричных карточек

Локальный кандидат {{RELEASE_HEAD}}, версия {{RELEASE_VERSION}}.
Ветка codex/release-symmetric-cards; база публичный v0.1.61,
c96282ca4479879367612e77d9252bf7913cc00e. Точный H фиксируется во внешнем
candidate.json после commit; этот файл не подставляет собственный SHA.

Локальная подготовка включает только согласованные 13 путей принятой функции
и три release-документа. Временные debug suffix/label исключены; release package
pro.perfectproduct.cramin, стандартный debug package pro.perfectproduct.cramin.debug.
Версия вычисляется по полной истории git; ожидается count62 после одного commit
над count61. Изменение истории требует нового решения, а не ручного versionCode.

Принятый отдельный symmetricdebug и его evidence сохранены. Его прежние
337 JVM/Android19/19/lint0 errors не являются проверками этого release-кандидата.
Нового release APK пока нет. Полный StudyScreen200%/RTL, exact release package/
подпись/комплект и обновление public61 с сохранением данных ещё не проверены.

Ни одна команда B и далее ниже не выполнена. Это шаблоны будущих действий,
каждое из которых требует отдельного разрешения. Буквы обозначают разделы
этого файла: решение должно называть конкретные действия, не только букву.
Текущий этап не разрешает push/PR/main/CI/tag/draft/upload/AVD/публикацию.
Нельзя запускать файл целиком как script. Для каждого разрешённого shell-блока
использовать set -euo pipefail, точные SHA и guards; при расхождении STOP.
CRAMIN_RELEASE_SHA перед будущими действиями взять из внешнего candidate.json
и проверить равенство git rev-parse HEAD. Здесь placeholders являются шаблонами.

Постоянный release сертификат — release-signing/cert-sha256.txt; ключ существующий,
вне git или в CI secrets. Новый ключ не создавать, значения секретов не печатать.
Ожидаемые signing schemes v2/v3. Точный CI APK и весь комплект проверяются вместе,
после чего разрешение публикации рассматривается отдельно.

Адресный200%/upgrade запуск — проект отдельного решения. Прошлое одноразовое
разрешение AVD исчерпано. Новая оснастка/маркер и мониторинг должны быть проверены
до новой попытки; аварийные Windows/WSL<1GiB/OOM/commit/thrashing/потеря мониторинга/
D<12GiB/C<512MiB обязательны. Консервативные RAM start8/5.5,phase2/2 отдельно;
их режим WARN по20с стабильным рядам требует нового явного решения. Дисковые
start D32GiB/C1.125GiB плюс прогноз сохраняются. Без решения запуск запрещён.
Полные ресурсные метрики/политика — внешний RELEASE-PREP-RESULT; thresholds
не менялись. Без auto retry/чужих kills/WSL изменений/требований перезагрузки.

B. ОТДЕЛЬНО РАЗРЕШЁННЫЕ PUSH ВЕТКИ + DRAFT PR (ЗАПУСТЯТ CI)
# Только из подготовленного checkout; CRAMIN_RELEASE_SHA взять из candidate.json.
set -euo pipefail
cd /tmp/cramin-release-symmetric-20261017
: "${CRAMIN_RELEASE_SHA:?Set exact reviewed candidate H}"
test "$(git rev-parse HEAD)" = "$CRAMIN_RELEASE_SHA"
test -z "$(git status --porcelain)"
test "$(git ls-remote origin refs/heads/main | cut -f1)" = c96282ca4479879367612e77d9252bf7913cc00e
git push origin HEAD:refs/heads/codex/release-symmetric-cards
python3 - "$CRAMIN_RELEASE_SHA" <<'RENDER_PR'
import pathlib,sys
text=pathlib.Path('docs/PR-FIRST-RELEASE.md').read_text()
pathlib.Path('/mnt/d/CramIn-evidence/symmetric-cards/release-a-20261017/PR-BODY-RENDERED.txt').write_text(text.replace('{{RELEASE_HEAD}}',sys.argv[1]).replace('{{RELEASE_VERSION}}','0.1.62'))
RENDER_PR
gh pr create --repo PerfectProduct/cramin --base main --head codex/release-symmetric-cards --draft --title 'Симметричные стороны в привычной колоде' --body-file /mnt/d/CramIn-evidence/symmetric-cards/release-a-20261017/PR-BODY-RENDERED.txt
gh pr view codex/release-symmetric-cards --repo PerfectProduct/cramin --json headRefOid,baseRefName,isDraft,statusCheckRollup
gh run list --repo PerfectProduct/cramin --branch codex/release-symmetric-cards --limit 10
# Проверить head SHA/checkout SHA/event каждого run. PR synthetic merge может дать63;
# это не номер будущего APK main. Требуются ci и instrumented(API26/36) согласно
# существующим workflow. Не расширять локальную матрицу. Номер PR/run берётся из API.

C. ОТДЕЛЬНО РАЗРЕШЁННЫЙ FAST-FORWARD MAIN, БЕЗ MERGE-КОММИТА
set -euo pipefail
cd /tmp/cramin-release-symmetric-20261017
: "${CRAMIN_RELEASE_SHA:?Set reviewed H after all PR checks}"
git fetch origin main codex/release-symmetric-cards
test -z "$(git status --porcelain)"
test "$(git rev-parse HEAD)" = "$CRAMIN_RELEASE_SHA"
test "$(git rev-parse origin/main)" = c96282ca4479879367612e77d9252bf7913cc00e
test "$(git rev-parse origin/codex/release-symmetric-cards)" = "$CRAMIN_RELEASE_SHA"
test "$(git rev-list --count "$CRAMIN_RELEASE_SHA")" = 62
git merge-base --is-ancestor origin/main "$CRAMIN_RELEASE_SHA"
# Обычный non-force push fast-forward ref; сервер отклонит несовместимый main.
git push origin "$CRAMIN_RELEASE_SHA":refs/heads/main
# Проверить main SHA==H через API, получить ci push(main) success.
# При запрете такого push остановиться; не обходить protection и не gh pr merge
# с дополнительным merge-коммитом. Перепланировать версию при ином способе merge.
# Для instrumented exact main dispatch — только если это разрешено явно:
gh workflow run instrumented.yml --repo PerfectProduct/cramin --ref main
# gh run watch <EXACT_MAIN_RUN_ID> --repo PerfectProduct/cramin --exit-status

D. ОТДЕЛЬНО РАЗРЕШЁННЫЕ RELEASE CI + TAG + DRAFT + ASSET UPLOAD
# Сам release.yml создаёт draft и может создать ПУБЛИЧНЫЙ тег. Это внешняя запись.
# До запуска проверить main==H/count62, отсутствие v0.1.62 (remote tags + releases),
# успешные exact-main gates, действующие secrets и закреплённый cert, без вывода secrets.
gh workflow run release.yml --repo PerfectProduct/cramin --ref main
gh run list --repo PerfectProduct/cramin --workflow release.yml --branch main --limit 10
# Выбрать новый workflow_dispatch run с head_sha==H, не старый v0.1.61.
# gh run watch <RELEASE_RUN_ID> --repo PerfectProduct/cramin --exit-status
# Скачать в НОВЫЙ пустой каталог; tag взять из N, здесь62 только при неизменном плане.
gh release download v0.1.62 --repo PerfectProduct/cramin --dir /mnt/d/CramIn-evidence/symmetric-cards/release-candidate-62-ci
# В скачанном каталоге: sha256sum -c SHA256SUMS
# python3 scripts/verify_release_signature.py <downloaded/cramin-v0.1.62.apk>
# $ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging <downloaded/cramin-v0.1.62.apk>
# $ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose --print-certs <APK>
# python3 scripts/audit-release-inputs.py --apk <downloaded/cramin-v0.1.62.apk>
# Остальные точные связи и archive checks перечислены в RESULT.
# Не выдавать опубликованный тег/draft за публичный релиз или пройденный smoke.

E. ОТДЕЛЬНОЕ РАЗРЕШЕНИЕ НА ПОДГОТОВКУ TEST APK (БЕЗ ИЗМЕНЕНИЯ PRODUCTION)
# Только после идентификации H. Не пересобирать product APK вместо CI артефакта.
# Existing release testBuildType поддержан в app/build.gradle.kts. Использовать
# существующий keystore через CRAMIN_KEYSTORE_PROPERTIES, не setup-secrets/new key.
# В чистом checkout H, с безопасно настроенной подписью и полным git history:
./gradlew :app:assembleReleaseAndroidTest -PandroidTestBuildType=release -Plive=false --no-daemon
# Выход: app/build/outputs/apk/androidTest/release/app-release-androidTest.apk.
# Проверить manifest targetPackage=pro.perfectproduct.cramin, test package,
# runner, cert==постоянный release cert. Если AGP подписал иначе — STOP, не
# переподписывать product APK. Test artifact может быть подписан существующим
# ключом в защищённой среде по отдельному разрешению. Снять SHA test APK.
# Production target для установки — только скачанный CI APK, до/после SHA неизменен.

F. ПРОЕКТ ОДНОЙ НОВОЙ AVD-СЕССИИ; СЕЙЧАС НЕ РАЗРЕШЁН И НЕ ВЫПОЛНЯЛСЯ
# Сначала подготовить и проверить новый monitor-wrapper с отдельным маркером
# и каталогом. НЕ запускать прежний run-once.py/не удалять ONE-ATTEMPT.json.
# Wrapper обязан вести telemetry и отменять дочерние adb/instrument при STOP.
# Ниже argv для запуска внутри wrapper, ПОСЛЕ нового решения архитектора:
ANDROID_AVD_HOME=/tmp/cramin-symmetric-api34-avds /home/dev/Android/Sdk/emulator/emulator -avd cramin-symmetric-api34 -port 5580 -memory 2560 -cores 2 -gpu swangle -feature -Vulkan -no-window -no-audio -no-boot-anim -no-snapshot-load -no-snapshot-save -skin 720x1280
# Работать только с emulator-5580, проверить API34/density320/720x1280.
ADB=/home/dev/Android/Sdk/platform-tools/adb
# Все следующие команды исполняются ТОЛЬКО под monitor wrapper.
# Записать исходный font_scale, установить2.0, удостовериться в system и actual
# Activity Resources.Configuration.fontScale==2.0/dumpsys activity configuration.
# Устанавливать существующие accepted debug/test APK с проверенными SHA.
$ADB -s emulator-5580 install -r /mnt/c/Users/impor/OneDrive/Документы/ChatGPT/CramIn/CramIn-symmetric-debug/cramin-0.1.62-symmetric-debug.apk
$ADB -s emulator-5580 install -r -t /mnt/d/CramIn-evidence/symmetric-cards/app-symmetric-debug-androidTest.apk
# Для themes dark=true/false по очереди (НЕ 19 тестов):
$ADB -s emulator-5580 shell settings put system font_scale 2.0
$ADB -s emulator-5580 shell am instrument -w -r -e dark true -e class 'pro.perfectproduct.cramin.ui.CardContextTest#approvedBookPairChangesWholeSidesWithSessionDirection,pro.perfectproduct.cramin.ui.CardContextTest#hebrewWithMarksAndPhysicalSwipesInReverseDirection,pro.perfectproduct.cramin.ui.CardContextTest#longSegmentScrollReachesEndWithoutFlipOrSort' pro.perfectproduct.cramin.symmetricdebug.test/androidx.test.runner.AndroidJUnitRunner
# Повторить только эти3 адресные метода с -e dark false. Между темами сразу pull
# из /sdcard/Android/data/pro.perfectproduct.cramin.symmetricdebug/files/symmetric-cards
# в отдельные dark/light каталоги, чтобы shot names не перезаписались.
# Фикстуры: реальный полный StudyScreen+Scaffold, его StudyViewModel/Room,
# не isolated FlashCard/не LocalDensity override; app APK не изменяется.
# Это экран production composable в test Activity, НЕ полный MainActivity workflow;
# Library→StudyScreen проверяется дополнительно persistence smoke на release.

# Когда готовы CI release APK и release-test APK, совместить upgrade в эту же
# единственную разрешённую сессию; не ждать CI при работающем AVD.
# До запуска сессии все файлы уже скачаны/проверены. Release package на AVD должен
# отсутствовать; если он занят, STOP/план иной изолированной среды, без clear/uninstall.
# OLD_APK — скачанный публичный v0.1.61, проверенный sha/пакет/подпись.
# NEW_APK — exact CI v0.1.N; RELEASE_TEST_APK — test artifact H.
$ADB -s emulator-5580 install "$OLD_APK"
$ADB -s emulator-5580 install -t "$RELEASE_TEST_APK"
$ADB -s emulator-5580 shell am instrument -w -r -e persistenceMode seed -e class 'pro.perfectproduct.cramin.release.ReleasePersistenceTest#persistenceAcrossInstall' pro.perfectproduct.cramin.test/androidx.test.runner.AndroidJUnitRunner
# Seed работает на исходном signed v0.1.61. Метод/API/schema/resources не менялись;
# совместимость нового harness с61 должна быть подтверждена native PASS, не Assume/skip.
$ADB -s emulator-5580 shell am force-stop pro.perfectproduct.cramin
$ADB -s emulator-5580 install -r "$NEW_APK"
$ADB -s emulator-5580 shell am instrument -w -r -e persistenceMode verify -e class 'pro.perfectproduct.cramin.release.ReleasePersistenceTest#persistenceAcrossInstall' pro.perfectproduct.cramin.test/androidx.test.runner.AndroidJUnitRunner
# Между seed и verify НЕТ uninstall/clear/переноса БД. Скачать installed base.apk,
# SHA должен быть равен CI артефакту. Приложение не делать debuggable.
$ADB -s emulator-5580 shell am instrument -w -r -e class pro.perfectproduct.cramin.release.ReleaseLicensesTest pro.perfectproduct.cramin.test/androidx.test.runner.AndroidJUnitRunner
# Те же3 CardContext метода при200%, dark true/false адресно выполнить на release
# через pro.perfectproduct.cramin.test/...; это проверка exact CI APK, а не full19.
# PNG забрать из target release external-files/symmetric-cards по отдельным темам.
# Scope selectors исключают AppSmokeTest (debug assertion) и LiveApi/ExternalSource.
# Все native logs должны иметь ожидаемые counts, zero errors/failures/skips.
# В конце восстановить исходный font_scale, штатно остановить только свой AVD
# и собственные monitor processes; никаких auto retry/чужих kills/изменения WSL.

G. ОТДЕЛЬНОЕ РАЗРЕШЕНИЕ НА ПУБЛИКАЦИЮ ПОСЛЕ ПРИЁМКИ ТОЧНОГО KIT/APK
# Только после всех проверок RESULT, решения о200% и upgrade PASS, при том же SHA.
gh release edit v0.1.62 --repo PerfectProduct/cramin --draft=false
gh release view v0.1.62 --repo PerfectProduct/cramin --json tagName,isDraft,isPrerelease,assets
gh api repos/PerfectProduct/cramin/releases/latest
# Анонимно проверить latest/asset download/source kit/notices/config и discovery
# обновления на совместимом release в изолированной среде, без действий на телефоне.
# ARTIFACTS.published=false — свойство упаковки, не источник текущего GitHub-статуса.
