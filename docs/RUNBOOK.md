# RUNBOOK — Cramin

Эксплуатационные процедуры владельца. Спецификация — `SPEC.md`, решения — `decision-log.md`.

## 1. Секреты и keystore

- `.env` в корне репозитория (права 600, в `.gitignore`): `OPENROUTER_API_KEY`, `LIVE_BUDGET_USD`,
  `CRAMIN_KEYSTORE_PROPERTIES` (путь к `keystore.properties` **вне** репозитория).
- Создать или обновить: `scripts/setup-secrets.sh` (скрытый ввод, атомарная запись). Вручную:
  `cp .env.example .env && chmod 600 .env && nano .env`. Формат строго `KEY=value` без пробелов вокруг `=`
  (скрипты терпимы к пробелам, но каноничная проверка `grep -Eq '^OPENROUTER_API_KEY=.+' .env` — нет).
- Совет: заведите в OpenRouter отдельный ключ для разработки с лимитом кредита.
- Релизный keystore создаёт тот же скрипт (блок «Релизная подпись»): PKCS12, RSA 4096, 30 лет,
  alias `cramin-release`, файл `~/.cramin/release.p12` и рядом `keystore.properties` (права 600).
  Скрипт ставит секреты GitHub `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
  (через stdin в `gh secret set`) и пишет публичный отпечаток в `release-signing/cert-sha256.txt`.
- **Бэкап `release.p12` в двух местах, пароль — отдельно от файла.** Потеря keystore = невозможность обновить
  установленное приложение (нужно удалять и ставить заново с потерей прогресса).
- Сверка отпечатка: `keytool -list -v -keystore ~/.cramin/release.p12 -alias cramin-release` → строка `SHA256:`
  должна совпадать с `release-signing/cert-sha256.txt` (с двоеточиями или без — скрипт проверки нормализует).

## 2. Локальная сборка и тесты

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug      # зелёный набор CI
scripts/test-device.sh                                    # инструментированные без @LiveApi (эмулятор emulator-5554)
./gradlew testDebugUnitTest -Plive=true                   # живые JVM-тесты (ключ из .env, бюджет LIVE_BUDGET_USD)
scripts/test-device.sh --live                             # живые на эмуляторе: пайплайн, статья, YouTube, STT
./gradlew testDebugUnitTest -Plive=true -Pbakeoff=true    # бейкофф моделей перевода → docs/model-bakeoff
scripts/build-release.sh                                  # подписанный release + сверка отпечатка
```

Стоимость живых прогонов пишется в `app/build/reports/live/live-cost.json` (JVM) и
`app/build/outputs/connected_android_test_additional_output/.../live-cost.json` (эмулятор).
Ключ не печатается; `test-device.sh --live` после прогона вычищает префикс ключа из артефактов, если он туда попал.

## 3. Первый релиз через `release.yml`

1. Влейте `feat/v1` в `main`. Убедитесь, что секреты GitHub стоят (`gh secret list --repo PerfectProduct/cramin`)
   и `release-signing/cert-sha256.txt` содержит отпечаток вашего keystore (не заглушку).
2. GitHub → Actions → **release** → Run workflow на `main` (или `gh workflow run release.yml --ref main`).
3. Джоба вычислит `N = git rev-list --count HEAD`, соберёт `assembleRelease`, подпишет из секретов,
   сверит отпечаток `scripts/verify_release_signature.py`, посчитает SHA-256 и создаст Release `v0.1.N`
   с ассетами `cramin-v0.1.N.apk` и `cramin-v0.1.N.apk.sha256` (формат `sha256sum`). Заметки — из `git log`
   с предыдущего тега. Права `contents: write` только у этой джобы.
4. Проверьте на странице релиза, что оба ассета на месте.

## 4. Установка на телефон

- Скачайте `cramin-v0.1.N.apk` из релиза, проверьте контрольную сумму: `sha256sum -c cramin-v0.1.N.apk.sha256`.
- Разрешите установку из неизвестных источников для браузера/файлового менеджера, установите APK.
- Первый запуск: онбординг → ключ OpenRouter → «Проверить ключ» → «Начать».
- Debug-сборка (`Cramin (debug)`, `pro.perfectproduct.cramin.debug`) не конфликтует с релизом.

## 5. Ручной смоук (SPEC §14.3)

Реальный ключ; по одному документу каждого типа:
1. Статья по ссылке (например, страница Википедии) → «Карточки готовы».
2. Видео YouTube с авторскими субтитрами (TED-Ed) → текст субтитров, абзацы по паузам.
3. Видео без авторских субтитров (1–3 мин) → транскрипция (статус «Транскрипция…»), затем карточки.
4. PDF с текстовым слоем через «📁 Файл PDF» и через «Поделиться» из файлового менеджера.
5. Скопированный текст; текст на языке перевода должен блокироваться с предложением сменить язык.
6. Иврит в обе стороны: he→ru и en→he (RTL по содержимому, огласованная лемма на карточке, 🔊).
7. Сессия: тап — переворот, свайпы, ↶ отмена (возврат статуса и счётчика), ▶ автопроигрывание, сводка раунда,
   «Повторить L невыученных».
8. Выход посреди сессии → повторный вход в ту же колоду предлагает «Продолжить с K/N».
9. Обновление N → N+1 (раздел 6) с сохранением статусов карточек.

## 6. Обновление N → N+1

- В приложении: Настройки → «Проверить обновления» → диалог с версией, заметками и размером →
  «Скачать и установить» → проверка SHA-256 → системная установка. Без разрешения на установку из этого
  источника приложение откроет системные настройки; после возврата установка продолжится.
- Прогресс сохраняется: схема Room v1 экспортирована, `fallbackToDestructiveMigration` запрещён тестом,
  каждое изменение схемы — новая версия + `Migration` + тест `MigrationTestHelper`.
- Репетиция на эмуляторе выполнена: release `0.1.8` → `0.1.9` поверх, документ, статусы карточек и
  незавершённая сессия сохранились (см. отчёт PR).

## 7. Как сменить модель

- Правка `config/models.json` в `main`: роли `brief`, `translate`, `extract`, `consolidate`, `stt`; поля `model`,
  `temperature`, `maxTokens` (`null` — из каталога), необязательный `reasoning` (объект OpenRouter, например
  `{"effort":"none"}`). Приложение подтягивает файл с `raw.githubusercontent.com` в начале каждой обработки и по
  кнопке «Обновить конфиг моделей»; встроенная копия собирается из того же файла при сборке.
- Модель без `structured_outputs`/`response_format` в каталоге отклоняется — берётся следующий источник, на
  экране настроек появляется предупреждение.
- Локально для себя: Настройки → Модели → роль → выбор из каталога или ручной id; «Сбросить к конфигу».
- Ориентиры по цене и качеству — `docs/model-bakeoff/README.md`.

## 8. Если что-то сломалось

- «YouTube изменил формат» — обновите NewPipeExtractor (`gradle/libs.versions.toml`, `newpipe`) и выпустите релиз.
- Странный красный в UI-тестах на эмуляторе: `adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus`;
  если фокус у системного диалога — `scripts/test-device.sh --wipe`.
- Ключ после восстановления из резервной копии не расшифровывается (другой Keystore) — введите заново.

## Диагностика импортов (2026-10-03)

Библиотека → строка с ошибкой → причина/действия → «Скопировать диагностику».
Тап не запускает обработку. Кнопка «Повторить» может снова обращаться к платным моделям;
согласовывайте бюджет перед диагностическими реальными попытками. Копирование содержит только
версию, API Android, тип источника, стадию и код. Не передавайте БД, logcat, provider body,
документы или ключи вместо этого отчёта. У ошибок старых версий стадия неизвестна; уже очищенную
причину восстановить нельзя. Retry сохраняет последний безопасный отказ независимо от QUEUED.

Карточки изучаются по отдельным значениям. После обновления старая многозначная карточка
разделяется, каждое значение наследует прежние status/starred. Несовместимая сессия сбрасывается
с уведомлением без обнуления прогресса. После reprocess новый перевод с неоднозначным
соответствием получает NEW, а несопоставленный snapshot сохраняется для разбора. Это не
доказательство, что прежний прогресс потерян: его нельзя без проверки назначить другому значению.

Материалы текущего завершения: `/home/dev/cramin-completion/2026-10-03/`.
Текущий план и результаты — `docs/completion-progress.md`; не считать прежние отчёты свежим CI.

## Установка после выдачи разрешения

Debug обновляется только debug APK того же applicationId и сертификата, с большим versionCode.
При возврате из системных настроек установки менеджер повторно проверяет разрешение и APK.
Pending basename/SHA-256 сохраняются в приватном файле; при потере cache APK загрузите его снова.
Release workflow проверяет собственный checkout SHA через build/unit/lint до подписи,
публикует тег только с явным target этого SHA и сериализует публикации. Это не заменяет
проверки на устройстве и не означает, что workflow уже был выполнен в GitHub.

## Настоящая смерть процесса без платных API

На отдельном тестовом AVD (никогда не телефоне):

```bash
adb start-server
./gradlew assembleDebug -PlifecycleProbe=true -Plive=false > /permanent/path/probe-build.log 2>&1
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 shell pm revoke pro.perfectproduct.cramin.debug android.permission.POST_NOTIFICATIONS
python3 scripts/lifecycle-probe.py --serial emulator-5580 --output /permanent/path/lifecycle > /permanent/path/lifecycle.log 2>&1
```

Режим подключает только debug source set `src/probe`, имеет экспортированный контрольный
receiver и fake LLM: такой APK не передавать владельцу и не распространять. Скрипт проверяет
qemu и наличие receiver, создаёт новый синтетический документ, сверяет progress fingerprint
после шести SIGKILL и отдельного force-stop. Затем собрать обычный APK **без** свойства probe.
При финальной упаковке проверить отсутствие ProbeApp/ProbeReceiver/FakeLlmClient в APK.
PublicSourceTest запускается отдельно с `-e freeSources true`; это бесплатная сеть, не live LLM.
