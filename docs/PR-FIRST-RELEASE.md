Первый публичный кандидат Cramin сохраняет принятые интерфейс, модели и промпты.
Проверка обновлений выбирает стабильный v0.1.N с точным APK и checksum; debug не ищет
публичный release. Workflow готовит draft с подписанным APK, notices и исходниками
точного коммита. Добавлены инструкции установки, платной обработки и конфиденциальности.

Публикация заблокирована до решения владельца об условиях APK с NewPipe GPL-3.0-or-later
и проверки corresponding-source desugar configuration. MIT LICENSE исходников не изменён.
См. docs/DISTRIBUTION.md и docs/SOURCE-BUILD.md. Draft PR не является этим решением.

Локальные результаты и точный binary/source SHA — в выданном релизном комплекте.
Проверить CI/JVM/lint и Android matrix API26/36 именно для текущего PR SHA; затем после
разрешённого fast-forward main проверить release workflow и его финальные ассеты.
