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

У двух desugar 2.1.5 компонентов source classifier не опубликован. Комплект включает
release-preparation дерево google/desugar_jdk_libs 73170c345e6a762fc6a1f0301bb15218850023ef
и R8 c331a820ddae7dea41f41a04375b784486d0f705 с human configuration nio 2.1.5,
conversion sources и tools/archive_desugar_jdk_libs.py. В этом R8 архиве отсутствует
названный в старом комплекте tools/archive_desugar_jdk_libs_configuration.py.
Опубликованный configuration JAR содержит machine desugar.json, а не тот же human JSON;
точное преобразование и provenance требуют отдельной проверки.
Точная связь Maven-бинарника configuration с этим деревом и достаточность дополнительных
upstream toolchains для его пересборки пока не подтверждены независимой сборкой.
Это открытый пункт проверки corresponding source перед публичной выдачей; наличие архивов
и совпадение номера версии сами по себе его не закрывают. Архив Liberation Fonts — reference
исходников; идентичность сборки bundled TTF также не заявляется (OFL допускает распространение
шрифта с уведомлением и не требует corresponding source).

Создание комплекта из чистого коммита:
`python3 scripts/package-source-kit.py --output /absolute/new/dist`.
Существующий проверяемый кэш можно передать через `--cache /path/source-kit`.
Без кэша скрипт скачивает только публичные исходники с проверкой закреплённых SHA-256.
