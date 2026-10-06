# Внешние действия: этапы разрешаются отдельно

Локальный комплект готовится из `feat/v1`, origin `git@github.com:PerfectProduct/cramin.git`.
Точный кандидат: **{{RELEASE_HEAD}}**, версия **{{RELEASE_VERSION}}**. Эти placeholders в
репозиторном шаблоне заменяются упаковщиком в выдаваемом плане. ARTIFACTS.json — источник
точного SHA/версии; числа коммитов и теги нельзя угадывать или сохранять после новых коммитов.
GPL-3.0-or-later путь утверждён; desugar подтверждён; debug migration отложена владельцем.
Исторические отчёты 0.1.38/0.1.39 сохраняются; актуальные результаты — REPORT.md этого комплекта.
GitHub CI/discovery ещё не считаются пройденными. Ниже только подготовленные команды.

## Этап 1 — требуется разрешение только push feat/v1 и draft PR

PR title: **Prepare Cramin first public release under GPL-3.0-or-later**.
Точный body — PR-FIRST-RELEASE.md рядом с этим планом; SHA/версия в нём уже подставлены.
Выполнять из checkout /home/dev/cramin, а путь CRAMIN_FINAL_KIT задавать на final-local.

```bash
CRAMIN_FINAL_KIT=/home/dev/cramin/release-candidate/final-local/dist
CRAMIN_EXPECTED_SHA=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$CRAMIN_FINAL_KIT/ARTIFACTS.json")
git switch feat/v1
test -z "$(git status --porcelain)"
test "$(git rev-parse HEAD)" = "$CRAMIN_EXPECTED_SHA"
git fetch origin --tags
git log --oneline origin/main..feat/v1
# Если origin/main уже изменился, оценить PR diff; не merge/rebase без нового кандидата.
git push origin feat/v1
gh pr create --repo PerfectProduct/cramin --head feat/v1 --base main --draft \
  --title "Prepare Cramin first public release under GPL-3.0-or-later" \
  --body-file "$CRAMIN_FINAL_KIT/PR-FIRST-RELEASE.md"
gh run list --repo PerfectProduct/cramin --branch feat/v1
gh pr view feat/v1 --repo PerfectProduct/cramin --json headRefOid,baseRefName,isDraft,statusCheckRollup
# gh run watch <RUN_ID> --repo PerfectProduct/cramin --exit-status
```

Ожидаются `ci.yml` (push и pull_request: assembleDebug, JVM, lintDebug) и
`instrumented.yml` (push feat/v1 и pull_request: fake API, API26/36). Push запускает матрицу
даже при первой регистрации workflows до появления YAML на main. Проверить push run на точном head SHA;
PR run может собирать синтетический merge commit GitHub — зафиксировать его SHA и PR headRefOid.
Не выдавать PR merge SHA за подписанный локальный APK. Live/external API tests исключены.
Если workflow не запускается (например, политика fork/Actions permissions), это реальный
внешний блокер; разобраться до следующего этапа. Workflow_dispatch доступен после YAML
на default branch; существование локального YAML не доказывает CI.
Первый этап не включает main, tags, release.yml или публикацию.

## Этап 2 — отдельное разрешение обновить main

Предлагается fast-forward, сохраняющий SHA/версию. Не применять squash/force-push.
Если main не предок feat/v1, согласовать способ интеграции и собрать новый локальный комплект.

```bash
bash <<'CRAMIN_INTEGRATE'
set -euo pipefail
cd /home/dev/cramin
git fetch --no-tags origin
test -z "$(git status --porcelain)"
test "$(git rev-parse main)" = 3e93eb4f0a067b47d1075a7708c1dcd28758862a
test "$(git rev-parse origin/main)" = 3e93eb4f0a067b47d1075a7708c1dcd28758862a
test "$(git rev-parse feat/v1)" = '{{RELEASE_HEAD}}'
test "$(git rev-parse origin/feat/v1)" = '{{RELEASE_HEAD}}'
test "0.1.$(git rev-list --count origin/feat/v1)" = '{{RELEASE_VERSION}}'
git merge-base --is-ancestor origin/main origin/feat/v1
git switch main
git merge --ff-only origin/feat/v1
test "$(git rev-parse HEAD)" = '{{RELEASE_HEAD}}'
git push origin main
gh workflow run instrumented.yml --repo PerfectProduct/cramin --ref main
CRAMIN_INTEGRATE
```

Это самостоятельный неинтерактивный Bash с остановкой при любой ошибке; не копировать отдельные
строки в интерактивную оболочку. Все предварительные guards выполняются до switch/merge/push/dispatch.
Проверка после merge дополнительно останавливает push/dispatch при неожиданном результате.
Main исходного принятого релиза48 указан явно; при ином main требуется новое согласование.
Команды подготовлены, не выполнялись.

## Этап 3 — отдельное разрешение tag + draft release + upload

[gh release create](https://cli.github.com/manual/gh_release_create) автоматически создаёт
отсутствующий тег; `--target` выбирает commit. **`--draft` не делает создание git tag приватным**:
команда может создать публичный тег. Разрешение на push/draft PR или только main не даёт
разрешения на запуск release.yml. Нужно явно разрешить тег `v0.1.<code>`, создание draft и
загрузку ассетов. Workflow сам вычислит versionCode из сохранённой полной истории.

```bash
gh workflow run release.yml --repo PerfectProduct/cramin --ref main
gh run list --repo PerfectProduct/cramin --workflow release.yml --branch main
# gh run watch <RELEASE_RUN_ID> --repo PerfectProduct/cramin --exit-status
```

Signing secrets: `ANDROID_KEYSTORE_BASE64` (существующий постоянный PKCS12),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`. PKCS12 store/key passwords равны по
правилу проекта. Не создавать новый ключ; не выводить значения. Проверить два защищённых
бэкапа существующего ключа вне git. Встроенный github.token даёт GH_TOKEN в release job;
OpenRouter API key не нужен ни сборке, ни fake tests. CI/instrumented не требуют signing secrets.

Технические зависимости отдельно: JDK21, Android SDK platform36/build-tools36.0.0, Gradle wrapper
9.8.0, Linux x86_64 API26/36 images, доступ к Maven/JitPack/GitHub и source archive downloads.
Для package-release-kit.py SDK должен быть доступен через ANDROID_HOME/ANDROID_SDK_ROOT.
Python3.11+ нужен hashlib.file_digest; десугар-рецепт отдельно требует Python3.12/zip/unzip и
pinned tools, но release workflow не пересобирает его повторно. Source kit включает рецепт и доказательства.

Workflow собирает новый CI APK и единый kit из github.sha: APK/.sha256, source-kit.zip,
notices.zip, ARTIFACTS.json, RELEASE-NOTES.md и SHA256SUMS. Он не обязан совпасть побайтово с
локальным APK. Скачать draft assets, проверить все SHA/подпись/package/version/commit/source
manifest/notices. При другом CI бинарнике провести smoke именно его на API26/36.
Draft отсутствует в `/releases/latest`; это ещё не проверка discovery.

## Этап 4 — отдельное разрешение публикации

После успешного CI и проверки CI APK/kit, когда точный тег/target SHA/ассеты подтверждены:

```bash
gh release edit v0.1.N --repo PerfectProduct/cramin --draft=false
# N взять из проверенного ARTIFACTS.json, не угадывать.
gh release view v0.1.N --repo PerfectProduct/cramin --json tagName,isDraft,isPrerelease,assets
curl --fail https://api.github.com/repos/PerfectProduct/cramin/releases/latest
curl --fail https://raw.githubusercontent.com/PerfectProduct/cramin/main/config/models.json
```

Проверить анонимную доступность repo, source kit, notices и model config. На совместимом
release с меньшим code нажать «Проверить обновления»: тот же tag/notes/APK/checksum; Android
подтверждает установку; документы/карточки/прогресс/настройки сохраняются. Проверить отказ
install permission, отмену, сетевую ошибку; debug публичный release не ищет. 404/latest означает
отсутствие более новой версии; ошибочные имена/tag/checksum/package/cert/code отклоняются.
Контракт обновления в этой подготовке не меняется; реально опубликованный discovery
остаётся обязательной внешней проверкой.

Release владельца устанавливается рядом с debug. Старые документы/занятия остаются в debug;
его нельзя удалять или очищать ради перехода. Автоматического переноса нет и он не проверен.
Решение о переносе уже принято: отложено до после первого релиза, не является блокером выпуска.
