import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.chaquo.python")
}

// RepeaterBook API credentials (never commit secrets) — same keys as NICFW Android editor
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun String?.forBuildConfig(): String =
    (this ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

val repeaterBookToken = localProps.getProperty("REPEATERBOOK_APP_TOKEN", "").forBuildConfig()
val repeaterBookEmail = localProps.getProperty("REPEATERBOOK_CONTACT_EMAIL", "").forBuildConfig()
val repeaterBookUrl = localProps.getProperty("REPEATERBOOK_APP_URL", "").forBuildConfig()
val repeaterBookAuthMode = localProps.getProperty("REPEATERBOOK_AUTH_MODE", "bearer").forBuildConfig()
val repeaterBookUserAgent = localProps.getProperty("REPEATERBOOK_USER_AGENT", "").forBuildConfig()

android {
    namespace = "com.radiodroid.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            val keystorePropsFile = project.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val keystoreProps = Properties()
                keystoreProps.load(keystorePropsFile.inputStream())
                storeFile = project.file(keystoreProps["storeFile"]!!.toString())
                storePassword = keystoreProps["storePassword"]!!.toString()
                keyAlias = keystoreProps["keyAlias"]!!.toString()
                keyPassword = keystoreProps["keyPassword"]!!.toString()
            }
        }
    }
    val releaseSigning = signingConfigs.getByName("release")
    val hasReleaseSigning = releaseSigning.storeFile?.exists() == true

    defaultConfig {
        applicationId = "com.radiodroid.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 21
        versionName = "4.5.0"

        buildConfigField("String", "REPEATERBOOK_APP_TOKEN", "\"$repeaterBookToken\"")
        buildConfigField("String", "REPEATERBOOK_CONTACT_EMAIL", "\"$repeaterBookEmail\"")
        buildConfigField("String", "REPEATERBOOK_APP_URL", "\"$repeaterBookUrl\"")
        buildConfigField("String", "REPEATERBOOK_AUTH_MODE", "\"$repeaterBookAuthMode\"")
        buildConfigField("String", "REPEATERBOOK_USER_AGENT", "\"$repeaterBookUserAgent\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Host Python for Chaquopy (must match version below). Do not hardcode machine paths in git —
// set chaquopy.buildPython in AndroidRadioDroid/local.properties (gitignored), or rely on PATH.
val chaquopyBuildPython: String? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.inputStream()
    ?.use { stream ->
        Properties().apply { load(stream) }.getProperty("chaquopy.buildPython")?.trim()?.takeIf { it.isNotEmpty() }
    }

chaquopy {
    defaultConfig {
        chaquopyBuildPython?.let { buildPython(it) }
        version = "3.12"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ── Generate a static CHIRP driver list at build time ────────────────────────
// Chaquopy packages Python into a zip (app.imy); at runtime, os.listdir() and
// pkgutil.iter_modules() both fail against the zip-backed AssetFinder importer.
// This task scans the driver source tree on the host and emits:
//   src/main/python/chirp_driver_list.py
//   DRIVER_MODULES = ["alinco", "anytone", ...]
// chirp_bridge.py then imports this list to drive importlib.import_module() calls —
// no filesystem enumeration needed at runtime.
tasks.register("generateChirpDriverList") {
    val driversDir = project.file("src/main/python/chirp/chirp/drivers")
    val outputFile = project.file("src/main/python/chirp_driver_list.py")
    inputs.dir(driversDir)
    outputs.file(outputFile)
    doLast {
        val drivers = driversDir.listFiles()
            ?.filter { it.isFile && it.extension == "py" && !it.name.startsWith("_") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
        val driverList = drivers.joinToString(", ") { "\"$it\"" }
        outputFile.writeText(
            "# Auto-generated by generateChirpDriverList — DO NOT EDIT\n" +
            "# Source: app/src/main/python/chirp/chirp/drivers/*.py\n" +
            "# Regenerated on every build via preBuild.\n" +
            "DRIVER_MODULES = [$driverList]\n"
        )
        println("generateChirpDriverList: wrote ${drivers.size} driver names to $outputFile")
    }
}
// Hook generateChirpDriverList directly into every Chaquopy merge*PythonSources task
// (one per build variant: mergeDebugPythonSources, mergeReleasePythonSources, …).
// Gradle 9+ requires explicit task dependencies when two tasks share an output directory;
// a preBuild hook alone is not sufficient — the merge task itself must declare the dep.
tasks.named("preBuild") { dependsOn("generateChirpDriverList") }
afterEvaluate {
    tasks.matching { it.name.matches(Regex("merge.*PythonSources")) }.configureEach {
        dependsOn("generateChirpDriverList")
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coordinatorlayout)
    implementation(libs.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    // USB OTG serial (Kotlin/Java side)
    implementation(libs.usb.serial.android)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
}
