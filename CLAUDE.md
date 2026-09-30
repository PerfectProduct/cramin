# Cramin — заметки для агента

Android-приложение для зубрёжки слов из реальных текстов. Спецификация — `docs/SPEC.md`
(источник истины), решения — `docs/decision-log.md`, эксплуатация — `docs/RUNBOOK.md`.

## Карта пакетов (`app/src/main/kotlin/pro/perfectproduct/cramin`)

| Пакет | Что внутри |
|---|---|
| `app/` | `CraminApp`, `AppContainer` (ручной DI), `MainActivity`, навигация, тема |
| `data/db/` | Room: entities, DAO, `CraminDatabase`, конвертеры; схемы в `app/schemas/` |
| `data/prefs/` | DataStore-настройки, `SecretStore` (AES/GCM из Android Keystore) |
| `data/repo/` | `DocumentRepository`, `CardRepository`, `StudyRepository`, `UsageRepository` |
| `ingest/` | `SourceExtractor` + `PlainText/Article/Pdf/Youtube` |
| `transcribe/` | `Transcriber`, `OpenRouterSttTranscriber`, `AudioSegmenter` |
| `llm/` | `LlmClient`, `OpenRouterClient`, схемы, промпты (дословно из SPEC), `ModelCatalog`, `ModelConfig` |
| `pipeline/` | `ProcessDocumentWorker`, `DocumentProcessor`, стадии, `Segmenter`, планировщики, валидаторы, `UnitMerger`, `SurfaceMatcher`, `LangDetector`, `Stoplists` |
| `study/` | `DeckBuilder`, `StudySessionMachine` (чистый Kotlin), `TtsController` |
| `update/` | `UpdateChecker`, `ApkDownloader`, `ApkInstaller`; манифест-фрагмент `src/update/AndroidManifest.xml` |
| `ui/` | `library/`, `create/`, `document/`, `study/`, `settings/`, `components/` |
| `util/` | `Log` (no-op в release), `Clock`, `Hashing` |

Тесты: `src/test` (JVM, Robolectric там, где нужен Android), `src/androidTest` (эмулятор),
`src/sharedTest` (фейки для обоих), фикстуры в `src/test/resources/fixtures/` (видны и androidTest).
Живые JVM-тесты — пакет `pro.perfectproduct.cramin.live` (включаются только `-Plive=true`);
живые инструментированные — аннотация `@LiveApi`.

## Команды

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug      # основной зелёный набор
./gradlew testDebugUnitTest -Plive=true                   # живые JVM-тесты, ключ из .env
scripts/test-device.sh                                    # инструментированные без @LiveApi
scripts/test-device.sh --live                             # только @LiveApi, ключ из .env
scripts/test-device.sh --class pro.perfectproduct.cramin.study.StudyScreenTest
scripts/test-device.sh --wipe                             # холодный старт эмулятора (-wipe-data)
scripts/setup-secrets.sh                                  # .env, keystore, секреты GitHub
scripts/build-release.sh                                  # подписанный release + проверка отпечатка
```

Эмулятор: AVD `theygrow-api34`, серийный номер `emulator-5554`, без личных данных, можно пересоздавать.
Физический телефон владельца не трогать.

## Правила локального цикла — соблюдать буквально

- (а) Вывод Gradle **не заворачивать в пайп** (`| tail`, `| grep`): порождённый adb-сервер держит пайп,
  и зелёный прогон выглядит зависшим. Перенаправлять в файл и читать файл.
- (б) `adb start-server` — **до** Gradle.
- (в) Странный красный в UI-тестах: сначала `adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus`.
  Если фокус у системного диалога (ANR) — только `scripts/test-device.sh --wipe` (перезапуск с
  `-wipe-data -no-snapshot-load` и повторное гашение анимаций).
- (г) Один класс: `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>` (или `--class`).

## `.env` и секреты — без исключений

- `.env` (права 600, в `.gitignore`): `OPENROUTER_API_KEY`, `LIVE_BUDGET_USD`, `CRAMIN_KEYSTORE_PROPERTIES`.
- **Никогда** не выводить содержимое `.env` (`cat`, `less`, `grep` без `-q`, `source` с `set -x`) и не пересказывать его.
  Проверка наличия ключа: `test -f .env && grep -Eq '^OPENROUTER_API_KEY=.+' .env`.
- Ключ не попадает в `BuildConfig`, ресурсы, assets, APK, логи, отчёты тестов, коммиты и PR.
  Gradle читает `.env` только в `doFirst` тестовых задач при `-Plive=true`; на эмулятор ключ
  передаёт только `scripts/test-device.sh --live` аргументом инструментирования.
- `ApkHasNoSecretsTest` (JVM, живой набор) проверяет, что первых 12 символов ключа нет в debug-APK.
- Живые тесты считают стоимость по `usage.cost`; при превышении `LIVE_BUDGET_USD` прогон останавливается.

## Логи

- Только через `util.Log`. В release это no-op. В debug — только идентификаторы, счётчики, статусы.
- Ни ключ, ни заголовок `Authorization`, ни тексты документов/переводов в лог не попадают.
  Тест подменяет sink и проверяет это на фейковом пайплайне.
- OkHttp `HttpLoggingInterceptor` — только debug, уровень `BASIC`, `Authorization` редактируется.

## Жёсткие ограничения

- minSdk 26, compile/target 36; один модуль `:app`, без flavor'ов, DI вручную.
- `applicationId = pro.perfectproduct.cramin`, debug — суффикс `.debug`.
- Room: `exportSchema = true`, схемы в `app/schemas/`, никакого `fallbackToDestructiveMigration*`.
- Ни одного id модели в Kotlin — только `config/models.json` (+ вшитая копия, собираемая Gradle) и настройки.
- YouTube: только авторские субтитры, иначе своя транскрипция. Автосубтитры не используются.
- Сеть: только хосты из SPEC §11. Без аналитики и crash-reporting.
- UI на русском, строки в `strings.xml`. Тёмная тема по умолчанию, светлая — по системе.
- Никаких `!!` без комментария-обоснования, никакого `GlobalScope`.
