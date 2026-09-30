# Cramin

Android-приложение для зубрёжки слов из реальных текстов. Даёте ссылку на статью, видео YouTube, PDF
или вставляете текст — Cramin переводит его через OpenRouter крупными связными секциями, извлекает
лексику из пары «оригинал + перевод» и строит двусторонние карточки. Изучение — в механике Quizlet:
тап переворачивает, свайп вправо «знаю», влево «ещё учу», ↶ отменяет, ▶ автопроигрывание.
Языки v1: английский, русский, иврит, любая пара. Интерфейс русский. Подготовка карточек требует сети,
изучение работает офлайн.

Спецификация — [`docs/SPEC.md`](docs/SPEC.md), решения — [`docs/decision-log.md`](docs/decision-log.md),
эксплуатация — [`docs/RUNBOOK.md`](docs/RUNBOOK.md), выбор моделей — [`docs/model-bakeoff/`](docs/model-bakeoff/README.md),
скриншоты — [`docs/screenshots/`](docs/screenshots/).

## Стек

Kotlin, Jetpack Compose (Material 3), Room, WorkManager, DataStore, OkHttp, kotlinx.serialization,
Readability4J, NewPipeExtractor, PdfBox-Android. minSdk 26, compile/target 36, один модуль `:app`, DI вручную.
Модели по ролям — `config/models.json` (обновляется с GitHub без выпуска релиза).

## Как собрать

Нужны JDK 21 и Android SDK (platform 36, build-tools 36.0.0).

```bash
./gradlew assembleDebug                         # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug testDebugUnitTest lintDebug
scripts/test-device.sh                          # инструментированные тесты на эмуляторе emulator-5554
scripts/setup-secrets.sh                        # .env с ключом OpenRouter (для живых тестов) и keystore
scripts/build-release.sh                        # подписанный release + сверка отпечатка
```

Живые тесты (реальный OpenRouter, бюджет `LIVE_BUDGET_USD`): `./gradlew testDebugUnitTest -Plive=true`
и `scripts/test-device.sh --live`.

## Как поставить APK

1. Возьмите `cramin-v0.1.N.apk` из [Releases](https://github.com/PerfectProduct/cramin/releases)
   и сверьте `sha256sum -c cramin-v0.1.N.apk.sha256`.
2. Установите APK (разрешив установку из этого источника). Следующие версии ставятся поверх
   с сохранением базы; в настройках есть «Проверить обновления».
3. На первом экране введите ключ OpenRouter (создайте отдельный ключ с лимитом кредита) и нажмите «Проверить ключ».

Ключ хранится зашифрованным ключом Android Keystore и никуда не отправляется, кроме `openrouter.ai`.
Аналитики и телеметрии нет.

## Лицензия

MIT (см. `LICENSE`). NewPipeExtractor используется без изменений по GPL-3.0.
