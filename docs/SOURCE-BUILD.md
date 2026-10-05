# Сборка соответствующих исходников

Для каждого APK комплект фиксирует commit, versionCode/versionName, контрольные суммы
зависимостей и исходников. `cramin-<commit>.bundle` содержит только достижимую историю HEAD;
`cramin-<commit>.tar.gz` — снимок того же коммита. Не копируйте локальное рабочее дерево.

1. Установите JDK 21, Android SDK platform 36 и build-tools 36.0.0. Gradle 9.8.0 загружает wrapper.
2. В `source-kit/CRAMIN-SOURCE.json` возьмите commit. Выполните:
   `git clone cramin-<commit>.bundle cramin`, затем `cd cramin` и `git checkout <commit>`.
3. Задайте `ANDROID_HOME` и PATH для SDK либо создайте собственный `local.properties`.
4. `./gradlew assembleDebug testDebugUnitTest lintDebug -Plive=false`.
   Платный API и ключ OpenRouter для сборки и offline-тестов не нужны.
5. Для собственной release-сборки создайте свой ключ вне checkout и properties:
   `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
   `CRAMIN_KEYSTORE_PROPERTIES=/absolute/path/keystore.properties ./gradlew assembleRelease`.
   Не включайте файл, пароль и ключ в опубликованные исходники.

Версия вычисляется из полной истории git: голый tar без истории не собирается. Bundle решает
это ограничение без патча Gradle. Подпись собственной сборки отличается от издателя, поэтому
она не обновляет его пакет. Приватный ключ издателя не требуется для изменения/сборки программы.
Побайтовую воспроизводимость подписанного APK комплект не обещает.

`manifests/dependency-sources.json` содержит версии, исходные URL, SHA-256 POM/source JAR.
115 опубликованных source JAR и 117 POM охватывают также debug-only компоненты; их маркировка
в `legal/inventory.json`. `upstream/` содержит полные деревья NewPipeExtractor v0.26.5,
nanojson, Rhino 1.8.1, PdfBox-Android 2.0.27.0, desugar и Liberation Fonts с build scripts.
Сверяйте всё по `SHA256.json`. Upstream библиотеки используются без локальных модификаций.

У двух desugar 2.1.5 компонентов source classifier не опубликован; preferred sources
предоставлены полными архивами google/desugar_jdk_libs 73170c345e6a762fc6a1f0301bb15218850023ef
и R8 c331a820ddae7dea41f41a04375b784486d0f705. Процедура и независимое сравнение
описаны в `DESUGAR-REVIEW.md`; первичные метаданные и результаты в `desugar-evidence/`.
Это проверка классов/конфигурации, а не совпадения одного номера версии.

Для самостоятельной пересборки обеих зависимостей на Linux x86_64 нужны Python 3.12,
zip/unzip и сеть к публичным upstream/Maven. Внутри распакованного kit:

```bash
python3 build-support/scripts/rebuild-desugar.py \
  --source-kit "$PWD" --work /absolute/new-desugar-work
```

Скрипт берёт preferred sources из kit, проверяет pins, получает внешние build tools
по SHA-256 из `manifests/desugar-build.json`, собирает библиотеку и conversion classes,
собирает R8 converter из исходников, вызывает upstream configuration/lint packaging
и сравнивает все файловые записи с официальными Maven JAR. Генераторы и адаптер
с соответствующими BSD notices приложены; для сборки не нужен source classifier.
Выходной каталог содержит команды, logs, comparison.json и результат. Это отдельная
проверка desugar, она не запускает Cramin, телефон, платные API или публикацию.

Архив Liberation Fonts — reference исходников; идентичность сборки bundled TTF
не заявляется (OFL допускает распространение с уведомлением без corresponding source).
Весь граф Android-зависимостей из исходников не пересобирался.

Создание комплекта из чистого коммита:
`python3 scripts/package-source-kit.py --output /absolute/new/dist`.
Существующий проверяемый кэш можно передать через `--cache /path/source-kit`.
Без кэша скрипт скачивает только публичные исходники с проверкой закреплённых SHA-256.
