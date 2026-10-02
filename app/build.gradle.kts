import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.impl.VariantOutputImpl
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Matches Desktop's ReleaseVersion.psm1: China date, persistent four-digit counter,
// no daily reset, and fail rather than reuse numbers when the range is exhausted.
abstract class GenerateIdvbBuildVersion : DefaultTask() {
    @get:Input abstract val productVersion: Property<String>
    @get:Input abstract val releaseLine: Property<String>
    @get:Internal abstract val counterFile: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Each build must reserve a fresh number") { true }
    }

    @TaskAction
    fun generate() {
        val product = Regex("v?(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
            .matchEntire(productVersion.get()) ?: error("Invalid IDVB product version")
        val line = Regex("b(\\d{2})\\.(\\d+)").matchEntire(releaseLine.get())
            ?: error("Invalid IDVB release line; expected bNN.N")
        require(product.groupValues[1].toInt() == line.groupValues[1].toInt() &&
            product.groupValues[2].toInt() == line.groupValues[2].toInt()) {
            "IDVB product version does not match release line"
        }
        val counter = counterFile.get().asFile
        counter.parentFile.mkdirs()
        val number = FileChannel.open(
            counter.resolveSibling("${counter.name}.lock").toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                val raw = if (counter.exists()) counter.readText(Charsets.US_ASCII).trim().ifEmpty { "0" } else "0"
                require(Regex("[0-9]{1,4}").matches(raw)) { "Invalid IDVB build counter: $counter" }
                val next = raw.toInt() + 1
                require(next <= 9999) { "IDVB build counter exhausted its four-digit range: $counter" }
                counter.writeText(next.toString(), Charsets.US_ASCII)
                next
            }
        }
        val date = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yy.MM.dd", Locale.ROOT))
        val build = "${releaseLine.get()}-$date.${number.toString().padStart(4, '0')}"
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("buildVersion=$build\nversionCode=$number\n")
        logger.lifecycle("IDVB ${productVersion.get()} / $build (versionCode=$number)")
    }
}

val idvbProductVersion = providers.gradleProperty("idvbProductVersion").get()
val generateIdvbBuildVersion = tasks.register<GenerateIdvbBuildVersion>("generateIdvbBuildVersion") {
    productVersion.set(idvbProductVersion)
    releaseLine.set(providers.gradleProperty("idvbReleaseLine"))
    counterFile.set(rootProject.layout.projectDirectory.file(
        providers.gradleProperty("idvbBuildCounterPath").orElse(".idvb-build-count").get(),
    ))
    outputFile.set(layout.buildDirectory.file("generated/idvb/version.properties"))
}
val idvbBuildMetadata = generateIdvbBuildVersion.flatMap { it.outputFile }.map { file ->
    Properties().apply { file.asFile.inputStream().use { load(it) } }
}
val idvbBuildVersion = idvbBuildMetadata.map { it.getProperty("buildVersion") }
val idvbVersionCode = idvbBuildMetadata.map { it.getProperty("versionCode").toInt() }

// Capture the source actually used by this build, including uncommitted algorithm edits.
abstract class GenerateAlignmentProvenance : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val gitCommit: Property<String>

    @TaskAction fun generate() {
        val root = sourceRoot.get().asFile
        val output = outputDirectory.get().asFile
        output.mkdirs()
        // Only our generated assets are refreshed. No source or user data lives here.
        output.walkTopDown().filter { it.isFile }.forEach { check(it.delete()) }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val records = sourceFiles.files.filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.map { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val bytes = file.readBytes()
            val target = output.resolve("alignment-source/$path")
            target.parentFile.mkdirs(); target.writeBytes(bytes)
            path to hash(bytes)
        }
        val fingerprint = hash(records.joinToString("\n") { "${it.first}:${it.second}" }.toByteArray(Charsets.UTF_8))
        output.resolve("alignment-provenance.json").writeText(
            "{\"schemaVersion\":1,\"gitBaseCommit\":" + quote(gitCommit.get()) +
                ",\"sourceFingerprint\":" + quote(fingerprint) +
                ",\"sourceState\":\"working-tree snapshot; hashes include uncommitted edits\",\"files\":{" +
                records.joinToString(",") { quote(it.first) + ":" + quote(it.second) } + "}}",
        )
    }

    fun verifySnapshot() {
        val root = sourceRoot.get().asFile
        val snapshot = outputDirectory.get().asFile.resolve("alignment-source")
        val current = sourceFiles.files.filter { it.isFile }.associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val saved = snapshot.walkTopDown().filter { it.isFile }.map { it.relativeTo(snapshot).invariantSeparatorsPath }.toSet()
        val changed = current.filter { (path, file) ->
            val copy = snapshot.resolve(path)
            !copy.isFile || !file.readBytes().contentEquals(copy.readBytes())
        }.keys + (saved - current.keys)
        check(changed.isEmpty()) { "Alignment source changed during compilation; rebuild for an accurate diagnostic snapshot: $changed" }
    }
}

val generateAlignmentProvenance = tasks.register<GenerateAlignmentProvenance>("generateAlignmentProvenance") {
    sourceRoot.set(rootProject.layout.projectDirectory)
    outputDirectory.set(layout.buildDirectory.dir("generated/alignment-provenance"))
    gitCommit.set(providers.exec { workingDir(rootProject.projectDir); commandLine("git", "rev-parse", "HEAD") }
        .standardOutput.asText.map { it.trim() })
    sourceFiles.from(rootProject.fileTree("app/src") {
        include("**/*.kt", "**/*.java", "**/*.xml", "**/*.contract")
    }, rootProject.fileTree("tools") { include("**/*.ps1", "**/*.rules", "**/*.properties") },
        rootProject.file("app/build.gradle.kts"), rootProject.file("build.gradle.kts"),
        rootProject.file("settings.gradle.kts"), rootProject.file("gradle/libs.versions.toml"),
        rootProject.file("gradle/wrapper/gradle-wrapper.properties"), rootProject.file("gradle.properties"))
}

android {
    namespace = "com.idvb.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.idvb.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = idvbProductVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val abis = providers.gradleProperty("idvbAbiFilters")
            .map { it.split(",").map(String::trim).filter(String::isNotEmpty) }
            .orElse(listOf("arm64-v8a"))
        ndk {
            abiFilters.addAll(abis.get())
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateAlignmentProvenance, GenerateAlignmentProvenance::outputDirectory)
        val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
        val verifySources = tasks.register("verify${suffix}AlignmentProvenance") {
            group = "verification"
            dependsOn(generateAlignmentProvenance, "compile${suffix}Kotlin", "compile${suffix}JavaWithJavac")
            doLast { generateAlignmentProvenance.get().verifySnapshot() }
        }
        tasks.matching { it.name == "package$suffix" || it.name == "package${suffix}Bundle" }
            .configureEach { dependsOn(verifySources) }
        val productName = idvbProductVersion
        val fields = requireNotNull(variant.buildConfigFields)
        fields.put("PRODUCT_VERSION", BuildConfigField("String", "\"$idvbProductVersion\"", "Product version"))
        fields.put("BUILD_VERSION", idvbBuildVersion.map {
            BuildConfigField("String", "\"$it\"", "Desktop-compatible build version")
        })
        variant.outputs.forEach { output ->
            output.versionCode.set(idvbVersionCode)
            output.versionName.set(idvbBuildVersion.map { "$productName ($it)" })
            // AGP 9.1 has no public APK filename setter. Set the packaging output
            // directly so APK metadata and Android Studio install use the same name.
            val apkOutput = output as VariantOutputImpl
            val variantName = variant.name
            val unsigned = if (apkOutput.outputFileName.get().endsWith("-unsigned.apk")) "-unsigned" else ""
            val filters = output.filters.joinToString("") { "-${it.identifier}" }
            apkOutput.outputFileName.set(idvbBuildVersion.map {
                "IDVB-Android-$productName-$it-$variantName$filters$unsigned.apk"
            })
        }
    }
}

// Run before compiling or packaging any APK, including test APKs. Ignored local
// device scripts are included so a clean Git diff cannot hide gallery pollution.
val mediaStorageRules = rootProject.file("tools/media-storage-boundary.rules")
val evidenceScriptPatterns = arrayOf("**/*.ps1", "**/*.py", "**/*.sh", "**/*.bat", "**/*.cmd", "**/*.js", "**/*.mjs", "**/*.gradle", "**/*.kts")
val mediaStorageSources = files(
    fileTree("src") { include("**/*.kt", "**/*.java", "**/*.xml") },
    rootProject.fileTree("tools") { include(*evidenceScriptPatterns) },
    rootProject.fileTree(".verify") {
        include(*evidenceScriptPatterns)
        exclude("**/build/**", "**/gradle*/**", "**/.gradle/**", "**/node_modules/**")
    },
    rootProject.file("app/build.gradle.kts"), rootProject.file("build.gradle.kts"),
    rootProject.file("settings.gradle.kts"), rootProject.file("gradlew"), rootProject.file("gradlew.bat"),
)
val verifyMediaStorageBoundary = tasks.register("verifyMediaStorageBoundary") {
    group = "verification"
    description = "Reject shared-media output paths in runtime, tests and local device scripts"
    inputs.file(mediaStorageRules)
    inputs.files(mediaStorageSources)
    doLast {
        val rules = mediaStorageRules.readLines().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Regex(it, RegexOption.IGNORE_CASE) }
        check(rules.isNotEmpty()) { "Media storage boundary rules must not be empty" }
        val violations = mutableListOf<String>()
        mediaStorageSources.files.filter { it.isFile }.sortedBy { it.path }.forEach { source ->
            source.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (rules.any { it.containsMatchIn(line) }) {
                        val path = source.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                        violations.add("$path:${index + 1}: ${line.trim()}")
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Media storage boundary failed; review these paths before building:\n" + violations.joinToString("\n"))
        }
        logger.lifecycle("PASS: runtime, test, build and local verification sources have no shared-media output APIs or paths")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyMediaStorageBoundary) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.opencv)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
