# Завершение Cramin — 2026-10-03

Основание: feat/v1, efff02d0f96f176dd7d3035ba2f2a6119426f47e, дерево чистое. Решения владельца от 03.10 имеют приоритет над прежней SPEC/макетом. Без публикации, платных API и телефона.

## Принятые решения

- Учебная единица — значение леммы/POS в документе, с независимым прогрессом. Синонимы объединяются только при подтверждённой эквивалентности, не по рискованной строковой эвристике.
- Retry продолжает сохранённый конфиг, новая обработка получает новый. Последняя безопасная диагностика переживает retry.
- Сначала диагностика и промежуточный APK, далее модель/миграция, UI, сеть/config, updater/release, лицензии и проверки.
- AUD-001/002/003/015 сохраняются: атомарные per-ID Undo и reprocess, история раундов, согласованное resume.
- Онбординг остаётся простым; выбор моделей в настройках (AUD-016).

## Задачи

| Задача | Статус |
|---|---|
| Диагностика AUD-004/008 + промежуточный APK | Завершено, 12a283a, APK 0.1.14-debug передан |
| Учебная единица, миграция, reprocess, Undo | Реализовано 29754a2; свежие JVM131/API34-19 passed, миграции API26/34/36 |
| Оформление, ft, AUD-011/014 | Реализовано 2e8856f/63a18ec; EN/HE font150/200 проверены API26/34/36; снимки готовятся |
| Отмена HTTP, snapshot, retries AUD-009/010/013 | 0267a6b: сеть; snapshot и STT resume проверены |
| Обновление и release workflow AUD-005/006 | 98d9a58; реальный N19→N20 installer, pending SIGKILL и данные passed; workflow только локально |
| Положительные источники | PDF picker/share и NASA HTTP→fake READY passed; YouTube blocked (SignInConfirmNotBotException) |
| Лицензии AUD-007 | 86282e5: inventory/notices/source kit; условия публичного распространения требуют решения |
| Сценарии AUD-012, API34/26/36, финальные артефакты | 19/8/8 passed; 6 SIGKILL + force-stop; финальная упаковка впереди |

## Коммиты и проверки

Первый пакет: Room v3, безопасная последняя ошибка, открытие FAILED, копирование allowlist,
отдельные PDF parse/password/no-text, безопасные provider/HTTP логи. 14 device tests passed на API34
(включая 1→3 и доступность выбора языка). JVM110: 109 passed, один устаревший assert BASIC-log
исправлен; целевой повтор 1 passed, 0 failed (diagnostics-log-regression.log). Первоначальный compile fail
(удалён соседний helper) исправлен, журнал сохранён. Исторические проверки не засчитываются как свежие.
Постоянные артефакты: `/home/dev/cramin-completion/2026-10-03/`.

## Ограничения

Исходные PDF/ссылки владельца отсутствуют; их отказы не установлены. Платные LLM/STT не разрешены. GitHub workflows не запускать/не заявлять успешными. Не менять корневую лицензию без решения владельца.

## Следующий шаг

Сверить `/home/dev/cramin-completion/2026-10-03/HANDOFF.md`: там фиксируется заключительный этап после этого коммита (APK, SHA, подписи, Downloads, финальный git status). Если HANDOFF отсутствует — собрать debug/release из HEAD и завершить упаковку. Если он заполнен — доступный проход завершён; следующий внешний шаг требует исходных PDF/ссылок/диагностики владельца и решения AUD-007, а публикация отдельной команды.

Коммит диагностики: `12a283a776412d3184709726a859e5f8b8a3feca`. Промежуточный APK и SHA в
`/mnt/c/Users/impor/Downloads/cramin-completion/2026-10-03-diagnostics-12a283a/`.

Модель (второй пакет), коммит `29754a2`: Card.meaningKey, отдельные Card/Sense на итоговый смысл, Room3→4;
точный reprocess snapshot по значению, нераспределённый старый прогресс сохраняется без назначения.
Старая сессия инвалидируется с уведомлением. Добавлены тест16/3, независимого прогресса/reprocess,
неоднозначного legacy snapshot и миграции3→4. Предыдущий прогон111: 110 passed, устаревший
prompt hash failed; SPEC/контракт/hash согласованы. Полный JVM-прогон112 passed. API34 полный device15:14 passed,1 старое UI-ожидание трёх смыслов failed; обновлено на одно значение/пример, целевой UI-повтор6 passed,0 failed (meaning-ui.log).

UI: регрессионные ExactSpanTest до исправления 2 failed/2; исправлена компиляционная ошибка дублированного OptIn. Полный JVM после первого исправления прошёл. Дополнительно проверяются повторные вхождения и реальные касания autoplay (ui-checks.log).

Третий пакет: `ui-checks.log` — 116 JVM и 7 UI passed, 0 failed/skipped на отдельном API34. UI включает отменённое касание, физические Play/Pause и accessibility click; существующие Undo/resume/границы раундов прошли. Следующий шаг: cancellation HTTP и retries, затем snapshot конфигурации.

Сеть/AUD-009/013: cancel-before.log — 2/2 regression failed (~2955/2960ms). После bridge полный JVM passed (network-checks.log); network-length-checks.log полный JVM passed. length-before.log — 1 из 3 failed (два сценария затем усилены: корректный JSON с length и последовательный extract). length-strict.log — 3 passed. stt-retry.log — 1 passed, локальный MockWebServer, НЕ реальный STT. Последний review добавил явный проброс CancellationException в KeyChecker/ModelCatalog; повтор полной проверки ниже.

Итог сетевого пакета: network-final.log, 123 JVM passed, 0 failed/skipped. AUD-009 и AUD-013 реализованы; реальное поведение провайдеров без платных вызовов не проверялось. Следующий шаг — immutable config snapshot (AUD-010).

Коммит сети `0267a6b`. Конфигурация: config-before.log — 1 regression failed; config-checks.log — полный JVM passed. STT получает ту же конфигурацию; добавлен отдельный resume-тест fake STT.

AUD-010: config-checks.log — 127 JVM passed; config-stt-final.log — 2 passed (retry/reprocess и STT resume). Промежуточный STT-тест failed из-за неверного NewDocument.Url вместо Youtube; models=[] подтвердило отсутствие STT-вызова, фикстура исправлена. Старые неполные параметры явно отмечаются. Следующий шаг: pending update, возврат из разрешения установки и exact-SHA release workflow.

Коммит snapshot: `6fa1b28`. Updater реализован: pending basename/digest, проверка пакета/версии/cert, ActivityResult/ON_RESUME, идемпотентное продолжение. update-unit.log: целевые JVM passed; реальный N→N+1 installer ещё предстоит. Release workflow локально проверен по YAML и порядку gates/target/concurrency; GitHub не запускался.

Updater: update-checks.log — assembleDebug и 131 JVM passed, 0 failed/skipped. Pending update проверен пересозданием менеджера (моделирование, не смерть процесса). Следующий шаг: build baseline из updater-коммита, затем реальные источники и lifecycle/installer на API34.

Источники/lifecycle: pdf-entry2.log — настоящий picker/share + WorkManager, 1 сценарий/2 PDF passed. public-sources-classified.log — 1 passed/2 failed (доступ YouTube заблокирован сервисом), исходные случаи владельца неизвестны. evidence/sources/device-proof сохранены. lifecycle-process.log / evidence/lifecycle/process-death.json: 6 SIGKILL + отдельный force-stop passed, status/starred fingerprint совпал, уведомления запрещены, fake LLM. Harness APK только opt-in, не для владельца. Next: коммит пакета и кандидат N20, встроенная установка N19→N20; лицензии, увеличенный шрифт, финальные артефакты.

Пакет источники/lifecycle: source-lifecycle-checks.log — обычный assembleDebug (без probe) и 131 JVM passed. Baseline updater: 98d9a58, 0.1.19-debug, сохранён в apks/update-baseline-98d9a58. Следующий коммит даст кандидат 0.1.20-debug для встроенной установки N→N+1. API26/36: официальные AOSP архивы 474/844 МБ; места достаточно, RAM ~10 ГБ требует последовательных AVD. Установка запущена, результат пока не заявлен.

Подтверждено встроенное обновление 0.1.19-debug (98d9a58) → 0.1.20-debug (ea2f21d): pending пережил настоящий SIGKILL; возврат из системного разрешения автоматически открыл installer. Все таблицы БД совпали по хешу, resume и Undo работают. Использован внедрённый проверенный pending APK; production GitHub discovery не проверялся. Доказательства: evidence/update-install.

Увеличенный шрифт: large-card2.log — 9 device сценариев passed (7 UI + 2 длинных карточки EN/HE, font150/200). Прокрутка не сортирует и не переворачивает, действия доступны. Исправлены перенос фильтров библиотеки и отображение номера сохраняемой позиции. API26/36 AOSP образы установлены, smoke ещё впереди.

UI accessibility package: 63a18ec. AUD-007 inventory/notices/source kit prepared; 117 resolved coordinates,
115 source JARs plus pinned upstream archives. Primary texts include PDFBox's OFL font. Owner's combined
GPL-compatible distribution decision and exact desugar configuration source/build provenance remain
publication gates; root licence unchanged. See THIRD_PARTY_NOTICES.md. Next: complete smoke API26/36,
full offline API34/unit/lint, final screenshots and signed artifacts.

Финальный main: assembleDebug/testDebugUnitTest/lintDebug passed (final-main.log), 131 JVM / 0 failed / 0 skipped.
Полный device34 первый прогон: 22 tests / 4 failed. Три ExternalSource попали в набор несмотря на
comma-separated notAnnotation и остановились на opt-in guard до запросов; один UI assert 1/2 остался
рядом с новым 2/2. Старый assert удалён, бесплатный сетевой класс теперь дополнительно исключается
через notClass. Предыдущее утверждение о 9 успешных целевых сценариях не является подтверждением
актуального полного набора; окончательные результаты берутся из свежего XML, а не только BUILD SUCCESSFUL.

Свежий API34: final-device34-pass.log и evidence/final-device34 XML — 19 tests, 0 failed/errors/skipped.
Второй промежуточный прогон имел 1 test-only failure: assertTextContains требовал substring=true для
«Продолжить с 2/2»; исправлено. Последний полный main: 131 JVM passed; lint 0 errors / 31 warnings
(версии, ресурсы, рекомендации и классы trust manager внутри BouncyCastle; приложение их не подключает
как TLS trust manager). API26/36 запущены последовательно на собственных новых AVD.

API26 и API36: по 8 targeted smoke passed, 0 failures (logs/smoke-api26.log, smoke-api36.log); отдельные AVD, последовательно. Workflow: YAML invariants и bash -n всех run-блоков passed (workflow-local.log). Не GitHub CI. Коммит notices: 86282e5.


## Заключительный контроль

39 оригинальных PNG + 5 контактных листов сохранены в `ui/`; EN/RU/HE, оба направления,
светлая/тёмная темы, 100/150/200%, отдельные значения берег/крен. UI-INDEX описывает синтетические
данные и SHA снимков. REPORT.md разделяет проверенное, внешне непроверенное и остаточные блокеры.
После последних адаптивных правок: final-adaptive-main.log — assembleDebug/131 JVM/lint passed;
final-adaptive-device34.log — 19 passed / 0 failed/errors/skipped. Снимок общей колоды200% проверен.
Последующие действия — только коммит этого завершённого пакета и сборка/подпись/упаковка из него.
Результаты после коммита записываются в HANDOFF.md вне репозитория, без изменения вычисляемой версии.

Коммиты прохода: 12a283a (diagnostics), 29754a2 (meaning), 2e8856f (UI/Unicode),
0267a6b (HTTP/retries), 6fa1b28 (config), 98d9a58 (updater/release), ea2f21d (source/lifecycle),
63a18ec (large font), 86282e5 (notices/offline tests), заключительный пакет docs/adaptive controls.
Полный финальный список SHA находится в HANDOFF.md/COMMITS.txt.

## 2026-10-03 — targeted BRIEF follow-up (in progress)

Phone 0.1.23 reports only Source/BRIEFING/BAD_REQUEST. Copy-time app/API values are not
failure provenance. Historical requests cannot be reconstructed from these blocks.
Before-fix reproductions: 3 wire tests failed; legacy capability and full-input-budget tests failed.
Confirmed local defects: small word caps ignored; giant single paragraph omitted; no full BRIEF
budget; legacy request bypassed capability filtering. No proof these caused owner's failures.
Implementing safe request-time event, immutable legacy metadata enrichment, exact-code rejection
classification, conservative full serialized input sizing. No model change or paid calls.
Evidence: /home/dev/cramin-completion/2026-10-03-brief/. Next: run regression/main checks,
review event privacy and persistence, record limitations and commit verified fixes.

Phone follow-up: supplied blocks confirm only saved source/BRIEFING/BAD_REQUEST. 0.1.23 copied
current app/API values; no historical provenance. Added request-time safe event and explicit
copy-time labels; regression confirms old JSON has UNKNOWN request, retry retains new event.
Nested native JSON error test first failed (UNSUPPORTED_PARAMETER -> UNKNOWN), then parser
added for machine fields only. Main JVM now 142 passed; final lint/device result pending below.
First API34 full run: 20 tests, 1 failure in existing cancelled-pointer autoplay test; new clipboard
case passed. Focus checked: launcher, no ANR dialog. Preserve failure and repeat on final code.

BRIEF final verification: `./gradlew assembleDebug testDebugUnitTest lintDebug -Plive=false`
(main-verified.log): passed, 142 JVM / 0 failed / 0 skipped; lint 0 errors / 31 existing warnings.
`ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest -Plive=false
-Pandroid.testInstrumentationRunnerArguments.notAnnotation=pro.perfectproduct.cramin.LiveApi
-Pandroid.testInstrumentationRunnerArguments.notClass=pro.perfectproduct.cramin.ingest.PublicSourceTest`
(device34-final.log): 20 passed, 0 failures/errors/skipped. Working AVD API34 verified explicitly;
first autoplay failure did not repeat, autoplay implementation/test unchanged. No paid requests,
phone access, process-death test or real provider rejection reproduction in this targeted pass.
Historical phone causes remain blocked on a new safe event / separately approved constrained call.
DL-048 and BRIEF-DIAGNOSTICS.md record confirmed fixes, provenance, limitations and paid plan.
Next: commit this verified package, then build/copy debug from that SHA and record external HANDOFF;
no further repo commit after artifact build. Publication and paid calls remain unauthorized.
Completed commit for this pass is identified by subject “Fix BRIEF request budgeting and preserve safe failure provenance”;
exact SHA and post-commit artifact checks belong in external HANDOFF.md (avoid changing version just to record its SHA).

## Approved isolated BRIEF probe — 2026-10-03

User explicitly authorized <=2 BRIEF calls, <=$0.10 total; no other stages. Production baseline
09c540c. Dedicated opt-in live test excludes all other live classes via init filter; maxAttempts=1,
OkHttp retry/redirect off, network interceptor rejects a second HTTP attempt, persistent slots
reserved before requests. Fresh public catalog + endpoints, maximum pricing including tiers/cache
write, full serialized byte proxy +4096 input reserve +6000 output +25% monetary margin.
First short RU upper bound $0.03457875. No phone model/snapshot assumed; uses repo BRIEF config.
Evidence /home/dev/cramin-completion/2026-10-03-brief-live/. Next: inspect first safe result;
second only if it answers remaining question and cumulative reserved bounds remain <=$0.10.


Probe completed: short HTTP200, 346/232 tokens, $0.0003476; longer RU HTTP200, 2175/265,
$0.0008616. Exactly 2 HTTP attempts, total reported $0.0012092; sum reserved bounds $0.08353875.
No source/pipeline/STT stages run. Second answered larger-input contract question after short success.
Original phone causes remain unknown. Current authorization exhausted: no more paid calls.
One long Gradle invocation was UP-TO-DATE/no HTTP; init mode input corrected, ledger verified.
Next: archive report/runner, offline regression, commit test/docs-only evidence; rebuild debug for
resulting history version. Production code unchanged; earlier 0.1.24 remains valid for 09c540c.
Offline JVM after probe: 142 passed, 0 failures/errors/skipped (`offline.log`); live excluded.
Production source unchanged. Evidence commit subject: “Record bounded live BRIEF diagnostic results”;
exact SHA and post-commit debug artifact recorded in external HANDOFF.md to avoid version recursion.

## 2026-10-03 — CONSOLIDATING UNKNOWN in phone 0.1.24

Active task supersedes CLI troubleshooting. Baseline 9431fd8, clean feat/v1; installed production
09c540c. No paid requests allowed/made. Phone cause remains unknown; historical error discarded type.

Completed: reproduce length escape and cached fenced-response divergence (2 tests red before fix);
conservative length fallback, consistent parse; bounded safe local events; durable cache-only Worker
path and explicit FAILED button; incomplete/corrupt/ambiguous legacy cache guards and batch binding.
Progress remains in existing transaction/snapshot; schemas 1–4 unchanged. See DL049 and
CONSOLIDATION-DIAGNOSTICS.md (facts, hypotheses, retained intermediates and exact phone route).

Checks: assembleDebug PASS; testDebugUnitTest -Plive=false: 151 passed, 0 failed/skipped;
lintDebug: 0 errors, 31 warnings; full offline API34 instrumented set: 22 passed, 0 failed/skipped.
Live annotation and PublicSourceTest explicitly excluded. New Worker test calls doWork through test
builder; this task did not test real process death or paid providers. Unit fault injection is simulated.
Expanded tests briefly failed on coroutine-added duplicate cause wrapper; assertion now accepts that
actual chain while retaining its types. AVD: separate working copy, emulator-5580 API34; no phone.

Evidence: /home/dev/cramin-completion/2026-10-03-consolidation/ (red/green logs, report, final artifact
metadata). Commit this completed code/test/docs part with subject “Diagnose and recover consolidation
without API calls”; exact SHA goes in external HANDOFF to avoid version-count recursion.
Next: post-commit debug build, signature/key scan, controlled 0.1.24→new APK update on synthetic data,
copy final artifact to unique Downloads folder, then owner uses only «Повторить консолидацию без API»
and sends new local event if failed. Missing cache requires diagnosis before separate paid approval.

## 2026-10-03 — second phone case; Payment/resume coverage

Baseline 9116ba2 / production 0.1.26, clean start. No production changes. Added PaymentResumeTest
(five scenarios: translation, parallel extraction, consolidation, and two partial split-range failures).
22 targeted JVM tests passed (5 new + 8 HTTP-mock client + 9 consolidation). Ordinary retry may pay
again for an unfinished split range; cache-only stops on missing durable inputs without any call.
No duplicates caused by retry in the exercised cases, saved DONE jobs reused unchanged. Structural
checks do not certify arbitrary DB corruption or semantic completeness. Phone causes remain unknown.
Initial test failure was an overstrict assertion about duplicate raw model units; fixed the test to
compare the uninterrupted baseline and final occurrence uniqueness. No source generation/API calls.
Details appended to CONSOLIDATION-DIAGNOSTICS.md. Evidence:
/home/dev/cramin-completion/2026-10-03-payment-resume/.
Next: commit test/docs-only coverage; run full offline JVM and assemble corresponding history-version
APK for traceability. Owner continues with existing 0.1.26 (identical production source); no replacement
APK required for the requested phone checks. External HANDOFF records final SHA/build/test result.
