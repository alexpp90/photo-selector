import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.phototok"
    compileSdk = 37

    // Uniform product layout: every product in this repository exposes `src/`
    // and `tests/`, whatever its language. That overrides Gradle's default
    // src/main | src/test | src/androidTest convention on purpose — see
    // docs/README.md. Keep these four blocks in sync across the Android modules.
    sourceSets {
        getByName("main") {
            java.directories.apply { clear(); add("src") }
            kotlin.directories.apply { clear(); add("src") }
            res.directories.apply { clear(); add("res") }
            manifest.srcFile("AndroidManifest.xml")
        }
        getByName("test") {
            java.directories.apply { clear(); add("tests/unit") }
            kotlin.directories.apply { clear(); add("tests/unit") }
        }
        getByName("androidTest") {
            java.directories.apply { clear(); add("tests/instrumented") }
            kotlin.directories.apply { clear(); add("tests/instrumented") }
        }
    }

    defaultConfig {
        applicationId = "com.phototok"
        minSdk = 26
        targetSdk = 36
        val baseVersion = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 6
        val offset = System.getenv("PHOTOTOK_VERSION_CODE_OFFSET")?.toIntOrNull()
            ?: System.getenv("VERSION_CODE_OFFSET")?.toIntOrNull()
            ?: 0
        versionCode = baseVersion + offset
        // Overridable from CI (e.g. derived from the git tag on release builds).
        versionName = System.getenv("PHOTOTOK_VERSION_NAME")
            ?: System.getenv("VERSION_NAME")
            ?: "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            // App-scoped env vars so each app uses its own upload key (full isolation).
            val keystorePath = System.getenv("PHOTOTOK_KEYSTORE_FILE")
            if (!keystorePath.isNullOrEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PHOTOTOK_STORE_PASSWORD")
                keyAlias = System.getenv("PHOTOTOK_KEY_ALIAS")
                keyPassword = System.getenv("PHOTOTOK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val keystorePath = System.getenv("PHOTOTOK_KEYSTORE_FILE")
            if (!keystorePath.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
        create("lab") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

// Format conformance (tests/unit/com/phototok/conformance): pass the RAW corpus
// location and the "corpus is mandatory" switch through to the test JVM, and
// re-run when the corpus or the shared manifest changes.
tasks.withType<Test>().configureEach {
    val corpusEnv = listOf("PST_FORMAT_CORPUS", "PST_REQUIRE_FORMAT_CORPUS")
    corpusEnv.forEach { key ->
        val value = System.getenv(key).orEmpty()
        inputs.property(key, value)
        if (value.isNotEmpty()) environment(key, value)
    }
    val corpusDir = System.getenv("PST_FORMAT_CORPUS")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache/photo-selector-toolbox/format-corpus")
    inputs.property(
        "formatCorpusListing",
        corpusDir.listFiles()?.sortedBy { it.name }?.joinToString(";") { "${it.name}:${it.length()}" }.orEmpty(),
    )
    inputs.file(rootProject.file("../../testdata/formats/manifest.json"))
        .withPropertyName("formatManifest").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("../../testdata/formats/companions.json"))
        .withPropertyName("companionTable").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // Shared Android baseline (EXIF model + readers, storage detection)
    implementation(project(":core"))
    // Shared SAF file operations (transfer engine, persisted grants)
    implementation(project(":saf"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Activity
    implementation(libs.androidx.activity.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ExifInterface
    implementation(libs.androidx.exifinterface)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // DocumentFile
    implementation(libs.androidx.documentfile)

    // Coroutines, aligned with kotlinx-coroutines-test (see libs.versions.toml)
    implementation(libs.kotlinx.coroutines.core)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // Testing — JVM unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)

    // Testing — instrumented (androidTest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
    }
}

// accessibility-test-framework (via ui-test-junit4-accessibility) pulls in guava:31.0.1-android,
// which requests listenablefuture:9999.0-empty-to-avoid-conflict-with-guava and conflicts with
// debugRuntimeClasspath's strictly-pinned listenablefuture:1.0 under AGP consistent resolution.
configurations.matching { it.name.contains("AndroidTest") }.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}
