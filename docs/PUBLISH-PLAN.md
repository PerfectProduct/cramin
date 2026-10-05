# План публикации (команды подготовлены, не выполнены)

Remote: `origin = git@github.com:PerfectProduct/cramin.git`; кандидат на `feat/v1`.
Точный исходный SHA/версия записаны в ARTIFACTS.json рядом с APK. Локальные теги отсутствовали.
На GitHub при подготовке были только main (8d5e5291…), 0 workflows и 0 releases;
анонимные latest release и main/config/models.json возвращали 404. Отсутствие модели
не блокирует локальную встроенную конфигурацию. Эти ответы не проверяют будущую публикацию.
Публичный релиз требует решения о GPL условиях APK и закрытия source-kit gap в SOURCE-BUILD.md.
Если эти решения приводят к коммитам, старый APK перестаёт быть финальным: соберите новый комплект.

Предлагаемый способ сохранить SHA и версию кандидата — fast-forward `main` к `feat/v1`.
Сначала сверить remote; если main не предок feat/v1, остановиться и выбрать merge с новым
кандидатом либо согласованный иной способ. Не применять force-push, reset удалённой ветки или squash:
versionCode зависит от достижимой истории, squash может уменьшить его.

После явного разрешения push/создания draft PR и решения о лицензиях:
```bash
git fetch origin --tags
git switch feat/v1
git status --short
git log --oneline origin/main..feat/v1
git merge-base --is-ancestor origin/main feat/v1
git push origin feat/v1
# Дождаться успешного ci для точного SHA; не принимать зелёный статус чужого коммита.
gh run list --repo PerfectProduct/cramin --branch feat/v1 --workflow ci.yml
# На default branch пока нет workflow: workflow_dispatch до появления YAML в main не работает.
# Отдельно разрешённый draft PR запустит pull_request Android matrix:
gh pr create --repo PerfectProduct/cramin --head feat/v1 --base main --draft \
  --title "Prepare Cramin first public release" --body-file docs/PR-FIRST-RELEASE.md
gh run list --repo PerfectProduct/cramin --branch feat/v1
# gh run watch <RUN_ID> --repo PerfectProduct/cramin --exit-status для обоих workflow.
```

Обязательные secrets (без значений): ANDROID_KEYSTORE_BASE64 (существующий постоянный PKCS12),
ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS. Текущий CI предполагает равные store/key passwords.
Если они разные — нужен отдельный ANDROID_KEY_PASSWORD и изменение loadReleaseSigning/workflow.
`GH_TOKEN` release берёт из встроенного github.token; отдельный персональный токен не нужен.
OPENROUTER_API_KEY в CI не нужен, живые тесты не запускать. Безопасно проверить имена:
`gh secret list --repo PerfectProduct/cramin`. Не запускать setup-secrets.sh для создания нового ключа.
Сделать две защищённые копии постоянного ключа; наличие этих копий проверяет владелец.

Только после отдельного разрешения обновления main:
```bash
# Создавать локальную main нужно лишь если её ещё нет; не перезаписывать существующую ветку.
git switch main
git merge --ff-only origin/main
git merge --ff-only feat/v1
git push origin main
gh workflow run instrumented.yml --repo PerfectProduct/cramin --ref main
# дождаться ci + instrumented на точном main SHA, затем:
gh workflow run release.yml --repo PerfectProduct/cramin --ref main
gh run list --repo PerfectProduct/cramin --workflow release.yml --branch main
# gh run watch <RELEASE_RUN_ID> --repo PerfectProduct/cramin --exit-status
```

Workflow собирает **новый CI APK** из main. Не утверждать, что его hash равен локальному:
скачать draft-ассеты, проверить sha256, подпись, package/versionCode, source metadata и точный commit.
Если получился другой бинарник — провести smoke именно этого бинарника до публикации.
Draft не обнаруживается `/releases/latest` и не является опубликованным релизом.
Ассеты: cramin-v0.1.N.apk, .apk.sha256, cramin-v0.1.N-source-kit.zip,
cramin-v0.1.N-notices.zip, SHA256SUMS. Размеры и checksum должны совпасть после скачивания.

Перед публикацией: подтвердить GPL scope/уведомления, corresponding source, JVM/lint/Android CI,
smoke final CI APK и правильный target SHA. Проверить анонимный доступ к repo/config/models.json;
приватный repo не обеспечивает обычному пользователю discovery без авторизации.
После отдельного разрешения публичного тега/релиза:
```bash
gh release edit v0.1.N --repo PerfectProduct/cramin --draft=false
# N заменить точным code из проверенного draft; не угадывать номер.
gh release view v0.1.N --repo PerfectProduct/cramin --json tagName,isDraft,isPrerelease,assets
curl --fail https://api.github.com/repos/PerfectProduct/cramin/releases/latest
curl --fail https://raw.githubusercontent.com/PerfectProduct/cramin/main/config/models.json
```

Проверка discovery требует реально опубликованного стабильного релиза и установленного release
с меньшим code: «Проверить обновления» показывает тот же tag, заметки и APK, SHA-256 совпадает,
Android подтверждает установку; документы/карточки/занятие сохраняются. Debug публичный канал
не проверяет. При 404 отображается последняя версия, при HTTP/network ошибке — ошибка;
недостающий checksum/неправильное имя/другой tag format отклоняются. При неудачной установке
показывается ошибка; неправильный пакет/подпись/небольший code отвергаются до установки.
Отмена пользователем и отсутствие install permission проверить на эмуляторе с опубликованными
ассетами. GitHub discovery/CI до push не считаются пройденными.

Переход владельца: release ставится рядом с debug, оба приложения остаются. Новые документы —
в release, старые занятия — в debug; не удалять debug и не чистить его данные. Автоматического
переноса нет. Если все старые документы и прогресс должны оказаться в release, отдельно согласовать
узкую миграцию/export-import и её тесты. Для первого публичного релиза новым пользователям она
не нужна; для замены debug у владельца с сохранением истории — нужна. Ручное повторное создание
материалов в release может стоить денег и не переносит статусы/сессии.
