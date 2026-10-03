# Decision log — Cramin v1

Формат: `CRM-DL-NNN — дата — решение — почему — альтернативы`.

---

**CRM-DL-001 — 2026-09-30 — `SPEC.md` перенесён в `docs/SPEC.md` без правок.**
Почему: так предписывает шапка спецификации и промпт. Альтернативы: оставить в корне — засоряет корень репозитория.

**CRM-DL-002 — 2026-09-30 — Версии библиотек ограничены сверху `compileSdk 36`.**
Решение: Compose BOM `2026.06.01` (Compose 1.11.4, Material3 1.4.0), `core-ktx 1.18.0`, `lifecycle 2.10.0`,
`navigation-compose 2.9.8`, `OkHttp 5.4.0`. Самые свежие стабильные (Compose 1.12.x, core 1.19.x, lifecycle 2.11.0,
navigation 2.10.x, OkHttp 5.5.0) требуют `compileSdk 37` (проверено по `aar-metadata.properties`), а SPEC §5.1
фиксирует compile/target 36 как жёсткое ограничение. Альтернативы: поднять compileSdk до 37 — противоречит SPEC и
требует platform android-37, которого нет в среде.

**CRM-DL-003 — 2026-09-30 — Сборка: AGP 9.4.1 со встроенным Kotlin, Gradle 9.8.0, Kotlin 2.4.20, KSP 2.3.12, Room Gradle plugin.**
Почему: актуальные стабильные версии на дату сборки; AGP 9 требует Gradle ≥ 9.6 и не требует плагина
`kotlin-android`. Room-плагин задаёт `schemaDirectory` и сам подключает схемы в assets androidTest для
`MigrationTestHelper`. Альтернативы: AGP 8.13 — не «актуальная стабильная».

**CRM-DL-004 — 2026-09-30 — Эмулятор запускается без окна: `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`.**
Почему: на этой машине (WSL2) это режим, проверенный `~/bin/tg-test.sh`; окно не нужно, скриншоты снимаются
`screencap`. Окно можно включить переменной `CRAMIN_EMULATOR_WINDOW=1`. Промпт задаёт только `-no-snapshot-save`,
он сохранён. Альтернативы: окно через WSLg — лишняя нагрузка и риск зависаний.

**CRM-DL-005 — 2026-09-30 — Живые JVM-тесты выбираются по пакету `pro.perfectproduct.cramin.live`, живые инструментированные — аннотацией `@LiveApi`.**
Почему: фильтр Gradle по пакету не требует JUnit-категорий и второй аннотации; для устройства AndroidJUnitRunner
умеет фильтровать только по аннотации (`annotation` / `notAnnotation`). Альтернативы: `@Category` — двойная разметка.

**CRM-DL-006 — 2026-09-30 — Разбор `.env` терпим к пробелам вокруг `=`, кавычкам и префиксу `export`.**
Почему: реальный `.env` владельца был записан как `KEY = value`, и буквальная проверка `^OPENROUTER_API_KEY=.+`
его не видела. Скрипты и Gradle разбирают оба варианта; `setup-secrets.sh` перезаписывает файл в каноническом виде.
Альтернативы: править чужой секретный файл автоматически — нет, его содержимое агенту читать нельзя.

**CRM-DL-007 — 2026-09-30 — Release без R8/минификации.**
Почему: NewPipeExtractor (Rhino), PdfBox-Android и jsoup чувствительны к обфускации, а проверить релиз на
всех путях без телефона владельца нельзя; размер APK для сайдлоада приемлем. Альтернативы: R8 с keep-правилами —
отдельный риск, отложено в бэклог.

**CRM-DL-008 — 2026-09-30 — `android:supportsRtl="false"`.**
Почему: SPEC §3 требует LTR-макет при русском интерфейсе независимо от локали устройства; направление текста
задаётся по содержимому (`TextDirection.Content`). Альтернативы: `supportsRtl=true` — на устройстве с ивритской
локалью зеркалило бы весь макет.

**CRM-DL-009 — 2026-09-30 — Фрагмент манифеста обновлений подключён как манифест типов сборки debug и release.**
Почему: SPEC §12.4 требует отдельного фрагмента, а flavor'ов в v1 нет; манифест типа сборки — единственная точка
слияния без flavor'ов и без второго модуля. Будущий Play-flavor заменит файл пустым. Альтернативы: отдельный
library-модуль — противоречит «один модуль `:app`».

**CRM-DL-010 — 2026-09-30 — Debug-логирование WorkManager на уровне INFO, в release — выключено (`ASSERT`).**
Почему: правило «в release лога нет вообще» распространяется и на логи библиотек, которыми мы управляем.

**CRM-DL-011 — 2026-09-30 — В запросах OpenRouter передаётся `provider: {"require_parameters": true}`.**
Почему: strict-схема structured outputs честно соблюдается только провайдерами, которые её поддерживают;
без флага OpenRouter может маршрутизировать к провайдеру, игнорирующему `response_format`. Альтернативы:
проверять валидность ответа постфактум — это уже делается, но лишние повторы стоят денег.

**CRM-DL-012 — 2026-09-30 — В `config/models.json` роль может содержать `reasoning` (объект OpenRouter как есть).**
Почему: современные дешёвые модели — рассуждающие; без `{"effort":"none"|"low"}` они тратят тысячи токенов
и десятки секунд на 6 предложений (deepseek-v4-flash: 3637 токенов, 173 с). Поле необязательное, схема
остаётся v1, старые парсеры его игнорируют. Альтернативы: зашить эвристику по имени модели — противоречит
правилу «ни одного id модели в коде».

**CRM-DL-013 — 2026-09-30 — Клиент не отправляет `temperature`/`reasoning`, если каталог говорит, что модель их не поддерживает.**
Почему: с `require_parameters` запрос с неподдерживаемым параметром получает 404 «No endpoints found»
(модели OpenAI серии 5 не принимают temperature). Каталог — источник `supported_parameters`; если каталога
нет (офлайн), параметры отправляются как есть.

**CRM-DL-014 — 2026-09-30 — Модели по ролям (см. docs/model-bakeoff/README.md).**
`brief`: `openai/gpt-5.6-luna` (effort none); `translate`: `openai/gpt-5.6-luna` (effort none) — сильный кандидат
`google/gemini-3.8-flash` дороже в 4,5 раза по факту при неотличимом на фикстурах качестве (правило ~3×);
`extract`: `google/gemini-3.8-flash` (effort low) — единственный кандидат, стабильно извлекающий термины
глоссария с чистыми леммами и огласовками; `consolidate`: `google/gemini-3.1-flash-lite` (effort low);
`stt`: `openai/whisper-large-v3-turbo`. Альтернативы и цены — в бейкоффе; владелец меняет модель правкой файла.

**CRM-DL-015 — 2026-09-30 — Живой тест стадий проверяет «ядро» частых терминов (≥ 3 из 5), а не одно конкретное слово.**
Почему: извлечение стохастично — даже лучшая модель иногда пропускает одно слово; тест на конкретную лемму
был бы ложно-красным. Наблюдение о «избегании глоссария» зафиксировано как риск в отчёте.

**CRM-DL-016 — 2026-09-30 — Job-строка = логическая единица работы (секция/чанк/пакет), а не один HTTP-вызов.**
Почему: дозапрос дыр, деление пополам при `length` и повтор невалидного JSON — подвызовы одной задачи; их
токены и стоимость суммируются в строке, `responseJson` хранит итоговый разобранный результат. При смерти
процесса задача повторяется целиком (DONE-задачи не трогаются). Альтернативы: строка на каждый вызов —
ломает уникальность `(documentId, kind, idx)` и усложняет возобновление без выгоды.

**CRM-DL-017 — 2026-09-30 — SurfaceMatcher ищет и саму лемму, не только формы `f`.**
Почему: лемма — заведомо известная поверхностная форма (library ↔ libraries), больше подчёркиваний во вкладке
«Текст». Это надмножество требования SPEC §6.9.

**CRM-DL-018 — 2026-09-30 — Бриф, не прошедший валидацию после повтора, помечается FAILED, но документ идёт дальше (фолбэк §6.10); ошибки ключа/кредита/сети валят документ сразу.**
Почему: бриф вспомогателен, а ошибки авторизации повторятся на следующей стадии — нет смысла тратить вызовы.

**CRM-DL-019 — 2026-09-30 — Видео для живых тестов: TED-Ed «Why do cats act so weird?» (sI8NsYIyQ2A) и «David After Dentist» (txqiwrbYGrs).**
Почему: разведка через NewPipeExtractor (YoutubeProbeTest, `-PytProbe=`) показала у TED-Ed 35 авторских дорожек
(включая en, ru, iw) и одну автоматическую; у «David After Dentist» (1:59, живая речь) авторских дорожек нет —
только автоматические, что и нужно для проверки нарезки аудио и STT. Статья — https://en.wikipedia.org/wiki/Flashcard:
стабильный публичный URL с абзацами. Альтернативы («Charlie bit my finger» 0:56, «RickRoll'D» 3:33, песни) не
подходят по длительности или по отсутствию речи.

**CRM-DL-020 — 2026-09-30 — Язык видео без метаданных берётся из языка единственной автоматической дорожки.**
Почему: `StreamExtractor.getLanguageInfo()` у YouTube почти всегда null, а автоматическая дорожка YouTube всегда на
языке речи. Используется только факт её языка, содержимое автосубтитров не читается (SPEC §7.2, §15 п. 4). Если
автоматических дорожек несколько (автопереводы) — язык неизвестен, и для транскрипции пользователь выбирает его
в диалоге (ошибка YOUTUBE_NO_LANG).

**CRM-DL-021 — 2026-09-30 — Вне PDF одиночный перевод строки — всегда граница предложения.**
Почему: заголовки, пункты списков и реплики субтитров не заканчиваются точкой; BreakIterator их не разделил бы.
В PDF-режиме строки внутри абзаца склеиваются (переносы по дефису), как требует SPEC §7.3.

**CRM-DL-022 — 2026-09-30 — PDF копируется в `files/docs/{id}/input.pdf` при импорте.**
Почему: права на `content://` из share/ACTION_OPEN_DOCUMENT временные и не переживают смерть процесса,
а обработка идёт в WorkManager. `sourceRef` хранит отображаемое имя файла для заголовка-фолбэка.

**CRM-DL-023 — 2026-09-30 — STT: до трёх попыток при сети/429/5xx, base64 пишется потоково; куски по 10 минут.**
Почему: документация OpenRouter — таймаут провайдера 60 с на запрос и лимит 25 МБ для multipart; 10 минут AAC 128 kbps
≈ 9,6 МБ (≈ 12,8 МБ в base64) на JSON-эндпоинте OpenAI/Groq укладываются. Живая проверка: 2 куска по ~60 с
«David After Dentist» — whisper-large-v3-turbo, $0.0004 за видео.

**CRM-DL-024 — 2026-09-30 — Пунктирное подчёркивание слов с карточками рисуется вручную поверх `Text` (drawBehind по TextLayoutResult).**
Почему: Compose поддерживает только сплошное `TextDecoration.Underline`, а SPEC §9.4 требует пунктир. Слова —
`LinkAnnotation.Clickable`, поэтому тап по слову открывает мини-карточку без собственного обработчика касаний.

**CRM-DL-025 — 2026-09-30 — Жесты карточки обрабатываются до `graphicsLayer` с переворотом.**
Почему: при `rotationY = 180°` координаты касаний зеркалятся, и свайп вправо на перевёрнутой карточке читался
как свайп влево (поймано инструментированным тестом сессии). Наклон и подсветка краёв — по смещению Animatable.

**CRM-DL-026 — 2026-09-30 — Автопроигрывание проходит карточки без сортировки; пройденные им карточки в сводке раунда не считаются ни «знаю», ни «ещё учу».**
Почему: SPEC §9.6 — «статусы при этом не меняются, карточки идут по порядку». Пропуск попадает в стек отмены
(↶ возвращает карточку), в конце колоды автопроигрывание выключается и показывается сводка раунда.

**CRM-DL-027 — 2026-09-30 — Выбор модели роли не показывает роутеры с отрицательной «ценой» (openrouter/auto и т. п.).**
Почему: каталог отдаёт для них `-1000000`; в списке по цене они всплывали первыми и бессмысленны как модель роли.

**CRM-DL-028 — 2026-09-30 — Ручной прогон на эмуляторе выполнен в системном тёмном режиме (`cmd uimode night yes`).**
Почему: тема следует системе (SPEC), эмулятор по умолчанию светлый; скриншоты сняты в тёмной теме — основной
для приложения. Ключ на скриншотах не виден: поле ввода маскировано, после сохранения показаны только последние
4 символа (SPEC §11). Экран настроек снят после сохранения ключа.

**CRM-DL-029 — 2026-09-30 — Ссылка/текст из «Поделиться» проходят через аргументы навигации экрана загрузки; PDF копируется в кэш немедленно.**
Почему: MainActivity — singleTask, `onNewIntent` кладёт вход в состояние и NavHost открывает экран загрузки с предзаполнением;
права на `content://` живут только пока жива активность.

**CRM-DL-030 — 2026-10-01 — Результат PackageInstaller доставляется через `MutableStateFlow` в объекте `ApkInstaller`.**
Почему: ресивер объявлен в манифесте (изолированный фрагмент `src/update/AndroidManifest.xml`) и не имеет доступа
к контейнеру; общий Flow — простейший канал в UI без EventBus. `STATUS_PENDING_USER_ACTION` открывает системный
диалог подтверждения.

**CRM-DL-031 — 2026-10-01 — Подпись release читается из `CRAMIN_KEYSTORE_PROPERTIES` (файл вне репозитория) или переменных CI `ANDROID_KEYSTORE_PATH/PASSWORD` и `ANDROID_KEY_ALIAS`; Gradle не читает `.env`.**
Почему: SPEC §14.4 — Gradle трогает `.env` только в тестовых задачах; `scripts/build-release.sh` экспортирует
одну переменную из `.env`. Без подписи `packageRelease` зависит от `checkReleaseSigning` и падает с инструкцией.

**CRM-DL-032 — 2026-10-01 — GitHub Release создаётся через предустановленный `gh`, а не сторонним action.**
Почему: меньше поверхности supply-chain; `permissions: contents: write` выдано только джобе release,
у остальных workflow — `contents: read`.

**CRM-DL-033 — 2026-10-01 — `release.yml` отказывает в сборке не из `main` и при уже существующем теге `v0.1.N`.**
Почему: версия — счётчик коммитов; повторный запуск на той же истории создал бы конфликт тегов.

**CRM-DL-034 — 2026-10-01 — Полный прогон фазы 7 на чистом эмуляторе (`--wipe`): неживые и живые наборы зелёные.**
Итог: JVM 98 тестов + 7 живых; эмулятор 11 неживых (5 классов) + 7 живых (2 класса). Стоимость всех живых
прогонов, бейкоффа и ручных проверок за проход — $0.53 по `usage` ключа OpenRouter.

**CRM-DL-035 — 2026-10-02 — Undo хранит индивидуальные статусы по ID и фиксируется вместе с сессией.**
AUD-001: чтение состава группы, снимок `ID → CardStatus`, изменение статусов и сохранение `stateJson`
выполняются в одной Room-транзакции; состояние UI публикуется после commit. Undo восстанавливает только
сохранённые ID в одной транзакции с новым состоянием сессии. Удалённые строки игнорируются SQL UPDATE,
новые дубликаты не затрагиваются. Старые записи `stateJson` без индивидуальных статусов/позиции действия
сохраняют позицию и счётчики, но вся недостоверная история Undo сбрасывается: восстановить прежние статусы
из одного статуса представителя невозможно. Автопроигрывание при загрузке и Undo выключается.

**CRM-DL-036 — 2026-10-02 — История Undo продолжается через границы раундов.**
AUD-003: «Повторить невыученные» сохраняет стек действий и порядки раундов; каждый Undo возвращает раунд,
позицию и счётчики до соответствующего свайпа. Порядок хранится один раз на раунд, а не копируется в каждое
действие. В итоговом диалоге есть отдельная кнопка отмены. «Начать заново» создаёт новую сессию со сбросом
истории, не меняя статусы карточек в БД. Сохранённая сессия доступна через «Учить» даже при нуле невыученных.

**CRM-DL-037 — 2026-10-02 — Общая карточка одинаково строится для новой и восстановленной сессии.**
AUD-015: общий builder объединяет смыслы и их собственные примеры, включая KNOWN-группы, необходимые
сохранённой сессии. Восстановление общей колоды не использует одиночные `cardsByIds`. Сохранённый ID остаётся
адресом карточки сессии, даже если появился более новый представитель; при удалении представителя группу
можно найти по исходным ID из истории. Если все доступные участники исчезли и нужную карточку восстановить
невозможно, сессия не продолжается с подставленной чужой карточкой: строится доступная новая колода без
изменения статусов. Это не восстанавливает удалённые данные.

**CRM-DL-038 — 2026-10-02 — Snapshot reprocess хранится в Room, схема v2.**
AUD-002: аддитивная миграция 1→2 создаёт `ReprocessState` с FK на Document; экспорт v1 не меняется.
Snapshot по lemmaKey (status, starred и резервные поля повторения), удаление производных данных и QUEUED
фиксируются одной транзакцией. Повторный запрос незавершённой обработки сохраняет исходный snapshot и
DONE-задачи. Замена карточек, восстановление snapshot, READY и отметка его потребления — одна транзакция.
При ошибке до commit snapshot остаётся; после commit ошибка очистки файла/уведомления не превращает READY
в FAILED. Пустой повторный запрос не заменяет существующий snapshot.

Для v1 незавершённых документов импортируется сохранившийся `status-snapshot.json`; если файл уже удалён,
снимок строится из сохранившихся карточек. Если потеряны оба источника, восстановить прежний прогресс нельзя.
Некорректный старый файл останавливает операцию до удаления данных. Завершённый маркер в БД исключает
повторное применение устаревшего файла после нового изучения; для старого READY приоритет у текущих карточек.
Файл можно удалять после commit: его наличие больше не является признаком незавершённой обработки.

Отмена WorkManager ожидается до prepare; процессор и prepare используют общий mutex документа в экземпляре
Room (приложение и Worker работают в одном процессе). Mutex удерживается до завершения/кооперативной отмены
старого процессора, поэтому новый prepare не конкурирует с его записями. Запрос живёт в appScope, чтобы уход
с экрана его не отменял. При старте приложения pending-документы в нетерминальном состоянии повторно
передаются WorkManager с KEEP; FAILED требует явного повтора. Непрерываемый HTTP-вызов может задержать
подготовку до отмены/таймаута — изменение HTTP-клиента относится к AUD-009 и не входит в этот пакет.

Проверки пакета различают транзакционные исключения/fault injection и настоящую смерть Android-процесса:
первое проверяет откат/повтор, но само по себе не подтверждает второе. Миграция проверяет сохранение карточки
со статусом и starred, а также старого stateJson. Device-сценарии используют только отдельную копию AVD.

**CRM-DL-039 — 2026-10-03 — Последний отказ отделён от текущего статуса.**
Room 2→3 добавляет nullable Document.failureJson, не меняя v1/v2. Запись содержит только тип
источника, явную стадию и ErrorCode. Процессор атомарно сохраняет её с FAILED; retry оставляет
последний отказ, старый errorCode переносит со стадией UNKNOWN. Старый raw errorMessage никогда
не показывается и не копируется. Если он уже очищен, причину не восстанавливаем предположением.
Копирование добавляет только версию приложения/API Android. Свободные строки, URL, текст, имена
файлов, provider body и stacktrace исключены. UI открывает FAILED как документ, retry отдельно;
действия выбора языка сохраняются. PDF parse/password/no-text имеют разные коды; общий отказ
YouTube больше не объявляется изменением формата. Ответы ошибок LLM/STT не включаются в exception.
Debug HTTP-лог теперь пишет только HTTP status/факт transport failure: BASIC включал полный URL,
поэтому одной редакции Authorization было недостаточно для приватных ссылок.


**CRM-DL-040 — 2026-10-03 — Card как отдельное значение, схема v4.**
Выбрано физическое разделение Card: существующие DAO прогресса, счётчики и per-ID атомарный Undo
сохраняют контракт. Sense остаётся связью с переводом/примером, одна запись на новую Card.
Это меньше изменяет весь путь, чем переадресация ID во всех сессиях/репозиториях на Sense.
Ключ общей группы — языковая пара, lemmaKey (лемма+POS), meaningKey (строгая нормализация
канонического перевода). В отличие от старой нормализации не удаляем смыслоразличительные
диакритики, не заменяем ё→е. Эквивалентность вариантов внутри документа определяет consolidation;
между документами неподтверждённые синонимы остаются отдельными группами.

Миграция3→4 сохраняет первый Card ID, клонирует прогресс/SRS-поля для остальных значений,
переносит Sense/Occurrence, сохраняет все тексты/Job/usage. Сессии инвалидируются явно, потому что
их группы и единицы изучения могли измениться; никакой недостоверный Undo не переносится.
Прогресс не сбрасывается. Вхождения старого SurfaceMatcher без senseId при одном значении
привязываются однозначно, при нескольких остаются нераспределёнными в БД без ложной подсветки.
Пользователю показано уведомление об изменении состава.

Reprocess snapshot включает meaningKey. Точное совпадение сохраняет статус/starred/SRS.
Если старый snapshot не знает значения или новый канонический перевод неоднозначен, новая
карточка NEW; несопоставленная запись остаётся в ReprocessState, показывается уведомление.
Не используем fallback по одной лемме для KNOWN. Повторный запрос сохраняет pending snapshot.
Новый extraction-контракт требует каждое вхождение; повтор формы не размножает учебные единицы.

## CRM-DL-041 — Один смысл, читаемый пример и начало жеста

Сохраняем структуру экранов и синюю палитру. EN/RU используют системный SansSerif,
HE — системный fallback того же семейства. Перевод примера — bodyMedium 16/26sp,
вместе с оригиналом в отдельном спокойном блоке; никаких раскрывающихся примеров.
Карточка ограничена высотой экрана, её содержимое прокручивается, действия остаются снаружи.
Короткие режимы «Пары / Оригинал / Перевод» и FlowRow переносят элементы целиком.

Начало касания на экране обучения останавливает autoplay до распознавания клика/свайпа,
включая отменённый жест. Отдельно учитываем кнопку Pause и accessibility click, чтобы
предварительная остановка не превращалась в повторный запуск.

Подсветка ищет целую фактическую форму, не обрезает ft и не включает соседние слова.
NFC/case folding хранит исходные UTF-16 границы кластеров, включая огласовки и surrogate pairs.
Повторные f в одном предложении сопоставляются по порядку контракта. Неоднозначный повтор ft
не подсвечивается: перестановка слов в переводе не позволяет доказать соответствие по порядку.
Это ограничение локальной валидации, а не доказательство семантической точности платной модели.

## CRM-DL-042 — Отмена HTTP и усечённые повторные ответы

Связь Job cancellation → OkHttp Call.cancel живёт до закрытия response body, включая
синхронные callbacks NewPipe (Job передаётся через coroutine thread-local context).
Отмена не становится NETWORK/retry; частичные APK и аудиофайлы удаляются при исключении.
Retry-After принимает delta-seconds и HTTP-date, ограничивается 60 секундами без overflow;
STT использует ту же политику и собственную нижнюю границу backoff.

finish_reason=length проверяется до разбора JSON и на повторном ответе. В переводе/извлечении
это вызывает деление диапазона, в том числе при заполнении дыр. При единичном неделимом
диапазоне и на brief/consolidate усечённый ответ не принимается как полный: безопасная ошибка.
Job остаётся логической задачей: attempts включает успешные транспортные ответы подвызовов,
не число HTTP-попыток внутри клиента. Платные обращения этими тестами не выполняются.

## CRM-DL-043 — Неизменная конфигурация обработки

Сохраняем versioned JSON с effective roles, параметрами pipeline и используемыми записями
каталога до первого этапа. Retry/resume не обращаются к новому конфигу; STT получает ту же
модель из snapshot. Поддерживаемые параметры и лимиты каталога также заморожены.
Только явная новая обработка атомарно очищает snapshot вместе с производными задачами;
повторный запрос уже pending-обработки этого не делает.

У установленной старой версии snapshot содержал только роли. Модели сохраняем, отсутствующие
параметры не выдаём за восстановленные: используем стандартные значения PipelineParams и
показываем отдельное уведомление с предложением явно начать обработку заново для текущих
настроек. Историческую смесь моделей/настроек, созданную старым багом, восстановить нельзя.
Неизвестную будущую версию snapshot не принимаем молча.

Обновление Job usage и итогов Document выполняется в одной транзакции. Сумма costUsd —
нижняя граница учтённых расходов, поскольку провайдер может не вернуть usage/cost или ответ
может оборваться. Так и подписываем её в UI; это не точная стоимость счёта провайдера.

## CRM-DL-044 — Pending update и проверенный SHA публикации

После checksum загрузки сохраняем в приватном каталоге basename и SHA-256 APK атомарной
заменой файла. При возврате из Settings/пересоздании экрана восстанавливаем APK, повторно
проверяем digest, applicationId, увеличение versionCode и совпадение сертификатов.
ActivityResult и ON_RESUME вызывают продолжение после выдачи разрешения; защита installJob
и Installing предотвращает двойной запуск. Отказ в разрешении оставляет возможность повторить.
Отмена закрывает pending. Удалённый/изменённый cache APK не устанавливается.
Системный raw status message не показывается/не копируется: только код результата.

Release workflow checkout закреплён за github.sha, gates assembleDebug/testDebugUnitTest
без live/lintDebug выполняются до раскрытия keystore; ошибка gates блокирует следующие шаги.
Перед публикацией HEAD сверяется снова, существующий удалённый тег отклоняется, новый тег
создаётся с явным --target точного SHA. Одна concurrency group сериализует публикации.
Локальная проверка YAML не является запуском GitHub CI. Публикация в этом проходе запрещена.

## CRM-DL-045 — Реальные источники и изолированная lifecycle-фикстура

PublicSourceTest — отдельный opt-in ExternalSource набор: реальный HTTP/NewPipe, fake LLM,
никакого STT. Не смешиваем его с детерминированными тестами или успехом исходных документов
владельца. NASA-статья прошла, два YouTube-кандидата вернули SignInConfirmNotBotException;
добавлена точная категория YOUTUBE_RESTRICTED без обхода входа/cookies/автосубтитров.

PDF scenario использует Android PdfDocument, настоящий picker и share, удаление URI после
копирования и настоящий WorkManager с fake LLM. WorkerFactory делегирует текущему контейнеру,
что позволяет честно подменить зависимости до работы без подмены самого планировщика.

Только `-PlifecycleProbe=true assembleDebug` подключает src/probe и fake LLM, постоянную Room,
синтетический документ и контрольные точки. Обычный debug и release не содержат этих классов
и экспортированного ProbeReceiver. CraminApp имеет обычную переопределяемую фабрику контейнера;
в production она всегда создаёт AppContainer. Извлечённый source.txt записывается атомарным
rename после fsync, чтобы resume не принял частичный файл за готовый источник.

API34: шесть SIGKILL (repo-snapshot, repo-prepared, replacementDeleted, restored, ready,
committed) сохранили per-meaning status/starred; fingerprint одинаков. Force-stop проверен
отдельно со stopped=true и явным запуском Activity. Это настоящая смерть процесса, не
моделируемое исключение; платные клиенты в фикстуре отсутствуют. Уведомления были запрещены.

## DL-046 — Attribution and source delivery

Runtime inventory includes transitive libraries, desugaring and PDFBox's bundled font/resources.
Preserve full primary notices in offline assets and provide pinned source archives outside git;
do not change the root licence. Proposed combined APK distribution with NewPipe requires GPL-compatible
terms/source delivery; owner decision remains a publication gate (see THIRD_PARTY_NOTICES.md).
Source availability is recorded separately from rebuilding every dependency or proving identical binaries.

## DL-047 — Final verification boundaries and large-font controls

Onboarding/общая колода используют minimum button height; общая колода прокручивается,
пары в FlowRow. API26/36 smoke выполнены на новых отдельных AVD, API34 — на рабочей копии.
Финальные APK собираются только после последнего коммита, их SHA/version/cert и свежие проверки
фиксируются вне git в HANDOFF.md, чтобы отчёт сам не менял вычисляемую из истории версию.
Демонстрационные снимки используют синтетическую Room4; они не подтверждают реальную LLM-семантику.

## DL-048 — BRIEF wire contract and historical failure provenance

0.1.23 copied the **current** app version / Android API alongside persisted source, stage and code.
These are not the failure's build/device/parameters. Old failureJson remains readable; absent event
fields stay UNKNOWN. Never infer historical request parameters from today's settings or snapshot.
No Room migration: an optional typed request event extends failureJson. Retry preserves it.

Capture each HTTP attempt from the actual serialized body: local UUID, start/response time, build,
config origin, requested/reported model, allowlisted provider, HTTP/API status, bounded identifier,
input sizes, explicit byte-proxy estimate, sent options, strict-schema hash and finite rejection.
Persist failure at transport callback before retry/Worker unwinding, and attach the same event to
final failure. This is the **last observed failure**, not an append-only history or proof that later
attempts did not run. A process killed before the callback cannot supply an unobserved response.
No raw error prose/body, document text, keys, URLs or account routing preferences are recorded.
Known parameter names and nonnegative numeric metadata limits are copied; prose is never parsed.
Native JSON wrapped in metadata.raw is parsed for exact machine codes/params/limits only (string
size capped at 32768), never retained. Response model is accepted only if equal to requested model.
Provider allowlist / UUID or generation-ID grammar deliberately sacrifice coverage for privacy.
Unreported model/provider/limit/request ID stay UNKNOWN; NOT_SENT distinguishes omitted options.

New snapshots keep saved capability metadata. Roles-only legacy snapshots retain saved role models
and settings, default missing pipeline parameters, and enrich only absent catalog metadata once
from the same model IDs; save before HTTP. Existing metadata is not replaced. If a legacy model
has no capabilities, fail locally before inference instead of guessing support. Missing entries can
be filled on a later attempt; known entries remain frozen. This does not recover historic metadata.

BRIEF word sampling now obeys small caps and keeps a bounded nonempty giant paragraph. Reserve
explicit output + 1024 protocol units against saved context, counting the full serialized UTF-8 JSON
(system/user/schema included) as a conservative token proxy. This is NOT a model tokenizer or a
proof of provider-context fit. Unknown context uses a local 32768 budget, not a claimed model limit.
Unset BRIEF output is bounded to min(6000, positive catalog output cap); explicit values remain,
and impossible output budgets fail locally. Trimming may reduce sampled tail coverage. No model
change, loss of structured outputs, automatic context-error retry or blind BAD_REQUEST retry.

Contract references: OpenRouter errors-and-debugging, parameters, structured-outputs,
reasoning-tokens and provider-selection (linked in docs/BRIEF-DIAGNOSTICS.md). Current catalog
omits temperature for configured BRIEF; old legacy path sent it. That is a reproducible request
construction defect, NOT proof of the three phone failures. Their concrete causes remain unknown.

## DL049 — Consolidation recovery and local diagnostic events (2026-10-03)

CONSOLIDATING contains local operations and optional LLM calls. Preserve bounded exception types,
application class/method/line frames, event build/attempt, explicit substage, numeric structure and
client invocation/response counts; never messages/payloads/paths. Old events remain unknown.
Keep historical cache metadata separate from this attempt. A missing request event is not no API.

Length in first/retry CONSOLIDATE uses the existing distinct-translation fallback; cached/fresh parsing
share fence handling. Bind new cached response JSON to batch SHA-256; legacy unbound batches must
match reconstructed pre-v4 grouping to avoid reassigned occurrence IDs. Corrupt/incompatible caches
fail before atomic replacement. No Room schema change. Add explicit network-free consolidation retry,
persisted in WorkData, which stops if cache is missing and never calls earlier stages/catalog/LLM.
Ordinary retry can still cost money. Details and phone procedure: CONSOLIDATION-DIAGNOSTICS.md.

## DL050 — Durable subdivision of CONSOLIDATE and addressed API recovery (2026-10-03)

Real 0.1.26 events show unfinished first jobs with last recorded length (attempts 1/3), all extraction
jobs DONE; current cache-only failures are expected and made zero calls. Ordinary retry resubmitted
the whole batch. Split deterministically between lexical groups, never inside one lemma/POS; persist
partial results/tree/active path in existing Job JSON, hash-bound to input. At single-group length or
local size/output limit, preserve data and stop explicitly; no endless length retry or increased output.
Completed parent folds to backward-readable normal response. No Room change; no destructive migration.
Use serialized UTF8 bytes + output +1024 as conservative context proxy; unknown context local cap32768,
not a claim about model capacity. Missing output defaults bounded to min(4000, known cap). No dollar cap.
Add distinct missing/unfinished/incompatible cache codes and a separately confirmed consolidation-only
API action that skips even FAILED BRIEF and uses saved config. No paid calls authorized/performed.
See LENGTH-RECOVERY.md for exact semantics, call bounds, unavoidable HTTP→DB window and phone route.
