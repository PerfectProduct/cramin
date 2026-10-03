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
| Учебная единица, миграция, reprocess, Undo | Проверено:112 JVM,6 UI; миграция API34 |
| Оформление, ft, AUD-011/014 | 116 JVM и 7 UI passed; расширенный font/RTL smoke и снимки впереди |
| Отмена HTTP, snapshot, retries AUD-009/010/013 | 0267a6b: сеть; snapshot и STT resume проверены |
| Обновление и release workflow AUD-005/006 | 131 JVM и assembleDebug passed; реальный installer впереди |
| Положительные источники | PDF picker/share и NASA HTTP→fake READY passed; YouTube blocked (SignInConfirmNotBotException) |
| Лицензии AUD-007 | Ожидает; возможен выбор владельца о распространении |
| Сценарии AUD-012, API34/26/36, финальные артефакты | Ожидает |

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

Завершить notices/source kit, затем API26/36 smoke, полный финальный набор API34 и снимки; собрать APK из финального коммита.

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
