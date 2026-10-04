# Lint: происхождение предупреждений

Исходный XML 0.1.34 содержит 41 warning. Сравнение ссылок с точным checkout 95ac33f выявило 10 новых UnusedResources: create_title_2, doc_header_stats, cards_total, cards_new, cards_starred, cards_filter_starred, cards_all_learned, settings_about, topic_selected, topic_document_totals. Это полностью объясняет рост 31 → 41.

Удалены все неиспользуемые строки, включая эти десять, и четыре неиспользуемые иконки удалённых элементов (cards/clipboard/document/link). Исправлен порядок необязательного Modifier в EmojiBadge. Общих suppression нет.

Остальные типы исходных предупреждений: OldTargetApi (новый SDK требует отдельного тестирования), GradleDependency/NewerVersionAvailable (обновление зависимостей вне UI-задачи), TrustAllX509TrustManager (три места внутри зависимости BouncyCastle; сетевой клиент приложения не менялся), UnusedAttribute (manifest-атрибут API33 при min26, используется на новых устройствах), ObsoleteSdkInt (каталог адаптивных иконок v26), UseKtx (три стилистические рекомендации Uri.parse). Ниже приведён итоговый перечень из финального lint XML.

## Оставшиеся предупреждения (финальный XML)

| ID | Место | Причина |
|---|---|---|
| OldTargetApi | app/build.gradle.kts | Not targeting the latest versions of Android; compatibility modes apply. Consider testing and updating this version. Consult the `android.os.Build.VERSION_CODES` javadoc for details. |
| UnusedAttribute | app/src/main/AndroidManifest.xml | Attribute `enableOnBackInvokedCallback` is only used in API level 33 and higher (current min is 26) |
| GradleDependency | app/build.gradle.kts | A newer version of `compileSdk` than 36 is available: 37 |
| GradleDependency | gradle/libs.versions.toml | A newer version of androidx.compose:compose-bom than 2026.06.01 is available: 2026.09.00 |
| GradleDependency | gradle/libs.versions.toml | A newer version of androidx.core:core-ktx than 1.18.0 is available: 1.19.1 |
| GradleDependency | gradle/libs.versions.toml | A newer version of androidx.lifecycle:lifecycle-runtime-compose than 2.10.0 is available: 2.11.0 |
| GradleDependency | gradle/libs.versions.toml | A newer version of androidx.lifecycle:lifecycle-viewmodel-compose than 2.10.0 is available: 2.11.0 |
| GradleDependency | gradle/libs.versions.toml | A newer version of androidx.navigation:navigation-compose than 2.9.8 is available: 2.10.2 |
| NewerVersionAvailable | gradle/libs.versions.toml | A newer version of com.squareup.okhttp3:logging-interceptor than 5.4.0 is available: 5.5.0 |
| NewerVersionAvailable | gradle/libs.versions.toml | A newer version of com.squareup.okhttp3:mockwebserver3 than 5.4.0 is available: 5.5.0 |
| NewerVersionAvailable | gradle/libs.versions.toml | A newer version of com.squareup.okhttp3:okhttp than 5.4.0 is available: 5.5.0 |
| TrustAllX509TrustManager | org.bouncycastle/bcpkix-jdk15to18/1.72/32faf4d74dbc333fb3a7a6c80c30417cf888fc7a/bcpkix-jdk15to18-1.72.jar | `checkClientTrusted` is empty, which could cause insecure network traffic due to trusting arbitrary TLS/SSL certificates presented by peers |
| TrustAllX509TrustManager | org.bouncycastle/bcpkix-jdk15to18/1.72/32faf4d74dbc333fb3a7a6c80c30417cf888fc7a/bcpkix-jdk15to18-1.72.jar | `checkClientTrusted` is empty, which could cause insecure network traffic due to trusting arbitrary TLS/SSL certificates presented by peers |
| TrustAllX509TrustManager | org.bouncycastle/bcpkix-jdk15to18/1.72/32faf4d74dbc333fb3a7a6c80c30417cf888fc7a/bcpkix-jdk15to18-1.72.jar | `checkServerTrusted` is empty, which could cause insecure network traffic due to trusting arbitrary TLS/SSL certificates presented by peers |
| ObsoleteSdkInt | app/src/main/res/mipmap-anydpi-v26 | This folder configuration (`v26`) is unnecessary; `minSdkVersion` is 26. Merge all the resources in this folder into `mipmap-anydpi`. |
| UseKtx | app/src/main/kotlin/pro/perfectproduct/cramin/update/ApkInstaller.kt | Use the KTX extension function `String.toUri` instead? |
| UseKtx | app/src/main/kotlin/pro/perfectproduct/cramin/app/Navigation.kt | Use the KTX extension function `String.toUri` instead? |
| UseKtx | app/src/main/kotlin/pro/perfectproduct/cramin/ui/settings/SettingsScreen.kt | Use the KTX extension function `String.toUri` instead? |
