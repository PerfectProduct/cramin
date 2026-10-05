Cramin prepares cards from text, PDF, articles and YouTube and preserves documents, favourites and study progress. This change keeps the accepted UI, models and prompts and prepares the first public APK for distribution under GPL-3.0-or-later; Cramin's own sources retain their MIT grant and dependency notices remain intact.

The source kit includes full history, pinned preferred sources, the verified desugar recipe/evidence and build instructions. A shared local/CI packager checks APK identity, version, signing certificate and offline licence assets and emits matching APK, source kit, notices, checksums and metadata.

Local candidate: {{RELEASE_VERSION}}, commit {{RELEASE_HEAD}}. Local validation includes release build/lint, exact APK smoke on API26/36, upgrade from the previous compatible signed release with synthetic documents/cards/progress/settings, and a build from the delivered source bundle. The accompanying REPORT.md records the actual results and limits.

Debug data migration is deferred: release installs alongside debug. GitHub CI and published-release discovery still require real checks. This draft PR authorizes no merge, tag, asset upload or publication. Running release.yml later requires separate permission because gh release create --draft may create a public git tag.
