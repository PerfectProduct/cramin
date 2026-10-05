# Проверка desugar 2.1.5 — 2026-10-05

Область проверки — две зависимости кандидата Cramin 0.1.38, исходный HEAD
`0c1897df17855fce577088d562303b1c33be2f6f`. Корневая лицензия MIT, YouTube и
production-код не изменяются. Это проверка исходников и процесса сборки, а не разрешение
распространять весь APK. Решения о GPL-пути и debug-данных, GitHub CI/discovery остаются отдельно.

## Артефакты и источники

| Maven coordinate | SHA-256 используемого JAR | Лицензия |
| --- | --- | --- |
| `com.android.tools:desugar_jdk_libs_nio:2.1.5` | `d8044befae095781b9a80bf1faa92edc30382d75d437476784c1bf991598a976` | GPL-2.0 с Classpath Exception; отдельные permissive notices сохранены |
| `com.android.tools:desugar_jdk_libs_configuration_nio:2.1.5` | `7db51661cd07d1fd5cf12769a75ba201910624bb3801c09363dd3cb28e31c51b` | BSD-3-Clause |

Официальные Maven URL и хеши находятся в `config/source-kit/desugar-build.json`;
сверены с реальными Gradle-входами и `legal/inventory.json`. POM указывают repository,
но не точную ревизию исходников. `-sources.jar` для этих координат отвечает HTTP 404;
это отсутствие classifier, а не отсутствие исходников. GitHub tags не дают тега 2.1.5.
Используются полные закреплённые архивы исходников, а не версия как доказательство.

Первичные источники:

- [desugar commit 73170c3](https://github.com/google/desugar_jdk_libs/commit/73170c345e6a762fc6a1f0301bb15218850023ef):
  release preparation, 2025-02-14; VERSION/DEPENDENCIES NIO 2.1.5.
- [R8 commit c331a820](https://r8.googlesource.com/r8/+/c331a820ddae7dea41f41a04375b784486d0f705):
  prepare desugared library 2.1.5, 2025-02-24.
- [R8 dependency pin](https://r8.googlesource.com/r8/+/c331a820ddae7dea41f41a04375b784486d0f705/third_party/openjdk/desugar_jdk_libs_11.tar.gz.sha1):
  `e4c206c7bdfe1415cd2716a1d59d699a80bc63dd7`. Скачанный пакет содержит
  `desugar_jdk_libs_hash = 73170c345e6a762fc6a1f0301bb15218850023ef` и README.google
  с той же ревизией и Bazel targets. Все 1461 класса этого пакета совпадают с raw release JAR.
- [upstream library release builder](https://r8.googlesource.com/r8/+/c331a820ddae7dea41f41a04375b784486d0f705/tools/archive_desugar_jdk_libs.py).
- [configuration builder](https://r8.googlesource.com/r8/+/c331a820ddae7dea41f41a04375b784486d0f705/tools/create_maven_release.py)
  и [bot archive entry point](https://r8.googlesource.com/r8/+/c331a820ddae7dea41f41a04375b784486d0f705/tools/archive.py).

## Библиотека: фактическая процедура и доказательство

Upstream builder читает source pin из third_party, делает checkout, удаляет
`DesugarDateTimeFormatterBuilder.java` как документированный workaround b/256723819,
запускает Bazel `maven_release_jdk11_nio`. Java source level 11, сборка `java_base_all`,
`jdk_type_selector` применяет `d8_desugar`, добавляются libcore/addon. Затем
`DesugaredLibraryJDK11Undesugarer` исправляет обращения к временным Desugar owners и
удаляет два класса `sun/nio/fs/DefaultFile{SystemProvider,TypeDetector}`.

Raw JAR и Maven JAR различаются. [Raw release JAR](https://storage.googleapis.com/r8-releases/raw/desugar_jdk_libs_nio/2.1.5/desugar_jdk_libs_nio.jar)
имеет SHA-256 `a6d07723c1a5a8dd785f1b8cba88c072cce15be01be1ebc5d405c31bf607415c`.
[Upstream Maven ZIP](https://storage.googleapis.com/r8-releases/raw/desugar_jdk_libs_nio/2.1.5/desugar_jdk_libs_jdk11_nio.zip)
имеет SHA-256 `7e569eead993daeaa4e01a0d463517906fa80e2b6af197f3de6061729e266612`.
В ZIP присутствует `undesugared.jar` и JAR в Maven path: их байты совпадают с зависимостью
Cramin (`d8044b…`). Публичные GCS object metadata показывают создание 2025-02-25;
это дополнительная метаинформация, а не аттестация source commit.

Независимый Bazel build 73170c3 дал те же 1461 класса и все остальные файловые записи
raw JAR (0 различий). После трансформации получены ровно те же 1459 class entries,
каждый файл побайтово равен Maven 2.1.5. Трансформация выполнена адаптером upstream
алгоритма `scripts/desugar/BytecodeTransforms.java`, использующим ASM 9.7.1,
ClassReader → ClassWriter(reader, 0), ownerMap из оригинального source file.
Адаптер заменяет только тестовый harness/I/O; это не утверждение о выполнении всей
upstream bot-программы или её загрузки артефактов.

SHA пересобранного JAR в первом опыте `223f5bb1114ca1731c068960dacb63dd54b56ccbf78abf55935b3cc7504bcbd2`.
Размер равен 6113073 байтам; обе стороны используют STORED и timestamps 1980-01-01
с одинаковым UT extra. Порядок классов различается из-за порядка ZIP-объединения
Bazel genrule/файловой системы. ZIP metadata не считаются содержимым класса.
Простая переупаковка Python дополнительно меняет UTF-8 flags; её SHA также не служит
критерием соответствия исходников. Критерий: одинаковые имена и байты всех файлов.

## Configuration JAR: процедура

Это R8 для генерации upstream desugar артефактов; он не обозначает версию D8/L8,
используемую AGP при сборке Cramin. Это отдельный компонент: preferred source — human `src/library_desugar/jdk11/desugar_jdk_libs_nio.json`,
Java conversion sources в `src/library_desugar/java`, таблицы и генераторы из R8.
Это не копирование human JSON в JAR. `tools/archive_desugar_jdk_libs_configuration.py`
в указанном дереве отсутствует; его старое упоминание было ошибкой.

1. `GenerateCustomConversionTest` компилирует stubs и conversion Java отдельно JDK 8,
   затем `GenerateCustomConversion`/`CustomConversionAsmRewriteDescription` меняют
   `wrap_convert` на обращения к generated Wrapper/VivifiedWrapper/EnumConversion.
   Независимо пересобраны 29 классов; все побайтово совпали с pinned conversion JAR.
2. `create_maven_release.convert_desugar_configuration` вызывает
   `DesugaredLibraryConverter human.json implementation.jar conversions.jar android-33.jar machine.json`.
3. `generate_jar_with_desugar_configuration` кладёт conversion classes и machine JSON,
   вызывает `GenerateDesugaredLibraryLintFiles machine.json implementation.jar lint-dir android-34.jar`,
   добавляет R8 LICENSE; `generate_desugar_configuration_maven_zip` формирует POM/checksums/ZIP.

Вердикт: процедура воспроизведена. Собран R8 c331a820 (`:main:r8WithRelocatedDeps`,
Gradle 8.12.1, OpenJDK 17+35 для build и OpenJDK 11+28 toolchain); проверено реальное наличие r8.jar.
Upstream Python builder получил заново собранные raw library и conversion JAR.
Все 29 классов, machine desugar.json, двух lint text files и LICENSE побайтово совпали
с опубликованным configuration 2.1.5; 0 лишних/отсутствующих/различающихся файлов.
Machine JSON также сравнен после parsing: полностью равен.
SHA первого пересобранного configuration JAR:
`a38b84e4435626f0b104fab88dd3fbf0502d13279a31e5376786fc5c00d886c5`.
ZIP timestamps отражают время местной сборки вместо 2025-02-24; directory entries,
порядок обхода и ZIP attrs не используются как доказательство равенства class/config
содержимого. Сохранён исходный warning converter о неиспользуемом prefix
`Ljava/lang/DesugarCharacter`; итоговый JSON идентичен опубликованному, warning
не подавлялся. Это не предупреждение lint приложения.

Это подтверждённая связь preferred sources с бинарным содержимым. Она не основана
на совпадении identifier/version или наличии архива.

## Preferred sources и внешние инструменты

Для библиотеки комплектуются всё дерево 73170c3 (jdk11 Java, addon/libcore/stubs,
BUILD/WORKSPACE/repos.bzl/setup.bzl, `tools/jdk_type_selector`, maven_release.py,
лицензии и copyright) и полный R8 c331a820 (undesugar generator, human config,
conversion Java и stubs, rewrite table/generator, converter/exporter, lint generator,
Gradle и Python scripts, LICENSE/AUTHORS). Добавлены проверяемый рецепт и BSD-адаптер
трансформаций с собственной копией upstream LICENSE/AUTHORS.

Bazel 6.3.2, OpenJDK 8u152-android/11+28/17+35, Gradle 8.12.1, protoc 3.19.3, bootstrap R8,
Android SDK stub JARs 32/33/34, ASM/Guava и прочие компиляторные зависимости — внешние
инструменты и входы сборки. Они не становятся Android runtime components только
потому, что используются для сборки. Их точные публичные URLs, upstream SHA-1 и
проверенные SHA-256 закреплены в `desugar-build.json`. Предоставляется полный R8
source tree как консервативный набор preferred sources генераторов; скачанные JDK,
SDK и bootstrap binaries не выдаются за эти исходники.

[GPL v2 §3 и Classpath Exception](https://github.com/google/desugar_jdk_libs/blob/73170c345e6a762fc6a1f0301bb15218850023ef/LICENSE)
определяют preferred form, interface definitions и управляющие сборкой/установкой scripts.
Classpath Exception разрешает связывание independent modules на иных условиях,
сохраняя требования для самой библиотеки. Она не разрешает вместо Java preferred sources
передать только class files. R8 configuration имеет BSD-3-Clause: сама эта лицензия
требует сохранения notices, а не обязательной поставки corresponding source; R8 исходники
добавлены для объяснимой сборки и полноты предлагаемого GPL-пути всего приложения.
[GPL v3 §1](https://www.gnu.org/licenses/gpl-3.0.html) исключает из corresponding source
System Libraries, general-purpose tools и generally available free programs, используемые
без изменений. Поэтому утверждение «нужны исходники всех JDK/SDK/compiler зависимостей»
не следует автоматически из GPL. Конкретный требуемый subset генератора покрыт полным
R8 архивом; спор об общей лицензии APK с NewPipe остаётся решением владельца.

## Повторение проверки и ограничения

Linux x86_64, Python 3.12, `zip` 3.0 и `unzip` 6.0. Рецепт в `scripts/rebuild-desugar.py`
берёт source archives из kit, проверяет SHA, скачивает только публичные pinned build tools,
пишет команды/logs/сравнение в новый отдельный каталог. Выходные JAR не подписываются и
не публикуются. В source kit добавлен автономный `build-support/scripts/rebuild-desugar.py`.

```
python3 build-support/scripts/rebuild-desugar.py \
  --source-kit /absolute/source-kit --work /absolute/new-desugar-work
```

`--cache /absolute/downloads` может предоставить уже скачанные build archives и reference
Maven JAR; SHA всё равно проверяются. Сеть нужна для Bazel rules и Maven compiler dependencies.
Локальное HTTP-зеркало переводит запросы старого coursier к официальному HTTPS Maven Central;
сохраняются файлы, URL и SHA-256. Меняется только transport URL в временном setup.bzl.
Пропуск downloader R8 применяется через init script после загрузки всех нужных pinned inputs;
`-x :shared:downloadDeps` не используется: опыт дал неполный успешный build без r8.jar.

Сохранены также ошибки и исправления окружения: TLS coursier, отсутствующий zip,
отключённое upstream обнаружение toolchain (переданы пути JDK), отсутствие protoc/bootstrap R8,
относительный init-script path. Это не ошибки production Cramin и не повод менять зависимость.

Историческая аттестация конкретного buildbot/releaser конфигурации отсутствует; SHA/r8-version
файл по GCS raw/c331a820 возвращает 404. Совпадение пересобранного содержимого доказывает
достаточность закреплённых preferred sources для этих бинарников, а не identity всех команд
в закрытом publishing job. Побайтовая воспроизводимость всего ZIP/JAR/APK и пересборка всего
графа Android-зависимостей не заявляются.

Итоговые результаты проверки полного упаковщика, всех checksum записей и сборки Cramin
из bundle финального kit фиксируются в постоянном `desugar-review/REPORT.md` и logs.
Новый подписанный APK не собирается. После этого коммита вычисляемая git-count версия
возрастает; подписанный APK 0.1.38 остаётся предыдущим кандидатом с прежним source kit.
Обновлённый kit нового HEAD не следует публиковать как точные исходники APK 0.1.38.
Для публикации после лицензионного решения потребуется новый согласованный APK/kit.
