# Принятые условия распространения Cramin

Владелец утвердил сохранение NewPipe и распространение комбинированного APK по
**GPL-3.0-or-later**, сохраняя MIT grant собственных исходников. Это принятое решение,
а не ожидаемое разрешение. Корневой MIT LICENSE и исходные лицензии/уведомления
зависимостей сохранены. APK нельзя описывать как исключительно MIT.

[DISTRIBUTION-LICENSE](../DISTRIBUTION-LICENSE) задаёт scope и содержит полный текст GPL v3.
Тот же документ доступен офлайн в настройках → Лицензии, вместе с MIT и third-party notices.
NewPipeExtractor v0.26.5 — GPL-3.0-or-later, nanojson fork — Apache-2.0, Rhino — MPL-2.0;
точные компоненты и условия: THIRD_PARTY_NOTICES.md и COMPONENT-LICENSES.md.

По GPL §6(d) corresponding source того же коммита/версии публикуется рядом с APK тем же
способом без дополнительной платы. Комплект включает историю для git-count версии,
preferred sources, build scripts, notices и SOURCE-BUILD.md. Полнота desugar подтверждена
в DESUGAR-REVIEW.md; доказательства и ограничения сохранены, повторная сборка не требуется.
Личный ключ подписи и пароли не распространяются. Побайтовое воспроизведение signed APK
не обещается. Чужие исходники сохраняют свои лицензии; MIT LICENSE Cramin не заменяется GPL.

Первичные источники: [GPL v3](https://www.gnu.org/licenses/gpl-3.0.html),
[NewPipe LICENSE](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/LICENSE),
[MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/),
[desugar GPL v2/Classpath Exception](https://github.com/google/desugar_jdk_libs/blob/73170c345e6a762fc6a1f0301bb15218850023ef/LICENSE).

Владелец также решил отложить debug→release перенос до после первого релиза. Release
устанавливается рядом с debug. Debug и его документы/прогресс сохраняются, если приложение
не удалять и не очищать его данные. Автоматической миграции нет; перенос не проверен.
Совместимые release обновляют тот же пакет с постоянной подписью и сохраняют данные.

Утверждение этих условий не разрешает push/PR/main/tag/upload/publication. Эти внешние
действия требуют отдельных разрешений по PUBLISH-PLAN.md. Release workflow создаёт draft
и может создать публичный git tag; draft не является опубликованным release/discovery.
