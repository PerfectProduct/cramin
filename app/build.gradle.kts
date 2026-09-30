import com.android.build.gradle.tasks.PackageApplication
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// ---------------------------------------------------------------------------
// Версия из истории git (SPEC §12.2): versionCode = git rev-list --count HEAD.
// Неполная история (shallow clone) — ошибка, а не тихий неверный номер.
// ---------------------------------------------------------------------------
val gitShallow: String = providers.exec {
    commandLine("git", "rev-parse", "--is-shallow-repository")
}.standardOutput.asText.get().trim()
if (gitShallow == "true") {
    throw GradleException(
        "История git неполная (shallow clone): versionCode вычисляется как " +
            "`git rev-list --count HEAD` и требует полной истории. В CI задайте fetch-depth: 0.",
    )
}
val gitCommitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull()
    ?: throw GradleException("Не удалось вычислить versionCode: `git rev-list --count HEAD` не вернул число.")

// ---------------------------------------------------------------------------
// Подпись release (SPEC §12.3): только из CRAMIN_KEYSTORE_PROPERTIES (файл вне
// репозитория) или из переменных окружения CI. Дефолтов нет: без подписи
// release-сборка падает с понятным сообщением (см. checkReleaseSigning).
// ---------------------------------------------------------------------------
data class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun loadReleaseSigning(): ReleaseSigning? {
    val env = providers
    val propsPath = env.environmentVariable("CRAMIN_KEYSTORE_PROPERTIES").orNull?.trim().orEmpty()
    if (propsPath.isNotEmpty()) {
        val file = File(propsPath.replaceFirst("~", System.getProperty("user.home")))
        if (!file.isFile) {
            throw GradleException("CRAMIN_KEYSTORE_PROPERTIES указывает на несуществующий файл: $propsPath")
        }
        val p = Properties().apply { file.inputStream().use { load(it) } }
        fun req(k: String) = p.getProperty(k)?.trim().orEmpty().ifEmpty {
            throw GradleException("В ${file.name} нет обязательного поля $k")
        }
        val store = File(req("storeFile")).let { if (it.isAbsolute) it else File(file.parentFile, it.path) }
        return ReleaseSigning(store, req("storePassword"), req("keyAlias"), p.getProperty("keyPassword")?.trim() ?: req("storePassword"))
    }
    val ciStore = env.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.trim().orEmpty()
    if (ciStore.isNotEmpty()) {
        val pass = env.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull.orEmpty()
        val alias = env.environmentVariable("ANDROID_KEY_ALIAS").orNull.orEmpty()
        if (pass.isEmpty() || alias.isEmpty()) {
            throw GradleException("Заданы не все переменные подписи: ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS")
        }
        return ReleaseSigning(File(ciStore), pass, alias, pass)
    }
    return null
}

val releaseSigning: ReleaseSigning? = loadReleaseSigning()

android {
    namespace = "pro.perfectproduct.cramin"
    compileSdk = 36

    defaultConfig {
        applicationId = "pro.perfectproduct.cramin"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "0.1.$gitCommitCount"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Debug никогда не перезаписывает release и его данные (SPEC §12.1).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Cramin (debug)")
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "Cramin")
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else null
        }
    }

    sourceSets {
        // Всё, что связано с обновлением (SPEC §12.4), объявлено в отдельном фрагменте манифеста,
        // который подключается к обоим типам сборки. Будущий Play-flavor подменит его пустым.
        getByName("debug").manifest.srcFile("src/update/AndroidManifest.xml")
        getByName("release").manifest.srcFile("src/update/AndroidManifest.xml")
        // Фейки и фикстуры общие для JVM- и инструментированных тестов.
        getByName("test").kotlin.directories += "src/sharedTest/kotlin"
        getByName("androidTest").kotlin.directories += "src/sharedTest/kotlin"
        getByName("androidTest").resources.directories += "src/test/resources"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor использует java.time/java.util.stream и NIO (SPEC §5.1).
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // app_name задаётся типом сборки через resValue.
        resValues = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        animationsDisabled = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/DEPENDENCIES",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

room {
    // exportSchema = true в @Database; схемы коммитятся в app/schemas (SPEC §12.5).
    schemaDirectory("$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// Вшитая копия config/models.json (SPEC §6.12): собирается из того же файла,
// чтобы копии не расходились. Синтаксис JSON проверяется при сборке.
// ---------------------------------------------------------------------------
abstract class EmbedModelsConfigTask : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val src = source.get().asFile
        try {
            groovy.json.JsonSlurper().parse(src)
        } catch (e: Exception) {
            throw GradleException("config/models.json — невалидный JSON: ${e.message}")
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        src.copyTo(File(out, "models.json"), overwrite = true)
    }
}

val embedModelsConfig = tasks.register<EmbedModelsConfigTask>("embedModelsConfig") {
    source.set(rootProject.layout.projectDirectory.file("config/models.json"))
    outputDir.set(layout.buildDirectory.dir("generated/modelsConfig"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(embedModelsConfig, EmbedModelsConfigTask::outputDir)
    }
}

// ---------------------------------------------------------------------------
// Проверка подписи release: без keystore сборка падает, а не выпускает
// неподписанный APK.
// ---------------------------------------------------------------------------
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val configured = releaseSigning != null
    doLast {
        if (!configured) {
            throw GradleException(
                """
                Release-сборка невозможна: не настроена подпись.
                Локально: scripts/setup-secrets.sh создаст keystore и запишет путь в .env;
                затем scripts/build-release.sh (экспортирует CRAMIN_KEYSTORE_PROPERTIES).
                В CI: секреты ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS
                (release.yml раскладывает их в ANDROID_KEYSTORE_PATH/ANDROID_KEYSTORE_PASSWORD/ANDROID_KEY_ALIAS).
                """.trimIndent(),
            )
        }
    }
}

tasks.withType<PackageApplication>().configureEach {
    if (name.contains("Release")) dependsOn(checkReleaseSigning)
}

// ---------------------------------------------------------------------------
// .env только для тестовых задач (SPEC §14.4): при -Plive=true переменные
// окружения JVM-тестов берутся из .env в момент выполнения задачи. Значения
// не попадают в BuildConfig, ресурсы, кэш конфигурации и вывод Gradle.
// Живые JVM-тесты лежат в пакете pro.perfectproduct.cramin.live.
// ---------------------------------------------------------------------------
val liveRequested: Boolean = providers.gradleProperty("live").map { it == "true" }.getOrElse(false)

fun parseDotEnv(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    val result = LinkedHashMap<String, String>()
    file.readLines().forEach { raw ->
        val line = raw.trim().removePrefix("export ").trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        if (value.length >= 2 && (value.startsWith('"') && value.endsWith('"') || value.startsWith('\'') && value.endsWith('\''))) {
            value = value.substring(1, value.length - 1)
        }
        result[key] = value
    }
    return result
}

tasks.withType<Test>().configureEach {
    if (liveRequested) {
        filter.includeTestsMatching("pro.perfectproduct.cramin.live.*")
    } else {
        filter.excludeTestsMatching("pro.perfectproduct.cramin.live.*")
    }
    filter.isFailOnNoMatchingTests = false
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    if (liveRequested) {
        val dotEnv = rootProject.file(".env")
        val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        // ApkHasNoSecretsTest сканирует собранный debug-APK.
        dependsOn("assembleDebug")
        doFirst {
            val env = parseDotEnv(dotEnv)
            val key = env["OPENROUTER_API_KEY"].orEmpty()
            if (key.isEmpty()) {
                throw GradleException("Живые тесты запрошены (-Plive=true), но в .env нет OPENROUTER_API_KEY. Запустите scripts/setup-secrets.sh.")
            }
            environment("OPENROUTER_API_KEY", key)
            environment("LIVE_BUDGET_USD", env["LIVE_BUDGET_USD"]?.ifEmpty { null } ?: "1.00")
            systemProperty("cramin.debugApk", debugApk.absolutePath)
            systemProperty("cramin.liveReportDir", layout.buildDirectory.dir("reports/live").get().asFile.absolutePath)
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    // Логирование HTTP только в debug, уровень BASIC, Authorization редактируется (SPEC §11).
    debugImplementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.readability4j)
    implementation(libs.newpipe.extractor)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.icu4j)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
