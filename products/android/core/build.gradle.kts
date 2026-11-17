import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    id("photoselector.android-module")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// :core holds the *only* code both Android products are allowed to share.
//
// Admission rule — a file belongs here when it is identical for Android Desktop
// (:android-desktop) and PhotoTok (:phototok) and would stay identical if either product
// changed independently. Anything that merely looks similar today stays
// duplicated in the products on purpose: the two apps are independent
// solutions, and premature sharing is what couples them.
//
// :core must not depend on Compose, Room, OpenCV, Vico or on either app module.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

photoSelectorModule {
    sharedTestSources = true // tests-shared/: the format-manifest reader
}

android {
    namespace = "com.photoselector.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)

    // EXIF extraction — part of :core's public surface.
    api(libs.androidx.exifinterface)

    // Hilt: the readers and the storage detector are @Singleton @Inject types.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}

// Format conformance (testdata/formats/manifest.json): hand the corpus location
// and the "corpus is mandatory" switch to the forked test JVM explicitly, and
// make them task inputs so a changed corpus or switch re-runs the tests instead
// of reporting UP-TO-DATE. See docs/shared/SUPPORTED_FORMATS.md.
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
}
