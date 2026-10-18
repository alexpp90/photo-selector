import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// :saf holds the Storage Access Framework file operations both Android products
// run on their photographs: the verified copy/move engine (collision-free bundle
// naming, size verification before a source is deleted, partial outcomes, undo
// into the original folder) and the persisted-grant bookkeeping (FolderAccessManager).
//
// Admission rule — the same as :core's: a file belongs here when it is identical for
// Android Desktop (:android-desktop) and PhotoTok (:phototok). Where the products
// differ (how a bundle is moved, when the Selection marker is written, which folder
// name a second pass uses, how a file is sorted into RAW/ or JPEG/), the difference is
// configuration passed in by the product (SafTransferPolicy, SelectionSubfolderRule,
// SelectionDestination), never a fork of this module.
//
// :saf must not depend on Compose, Hilt, Room, OpenCV, Vico, :core or on either app
// module, and must not know product types (ImageItem, settings). Each product wires
// it into its own dependency graph.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.photoselector.saf"
    compileSdk = 37

    // Uniform product layout: every product in this repository exposes `src/`
    // and `tests/`, whatever its language. That overrides Gradle's default
    // src/main | src/test | src/androidTest convention on purpose — see
    // docs/README.md. Keep these four blocks in sync across the Android modules.
    sourceSets {
        getByName("main") {
            java.directories.apply { clear(); add("src") }
            kotlin.directories.apply { clear(); add("src") }
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

    lint {
        abortOnError = false
    }
}

dependencies {
    // DocumentFile is part of :saf's public surface (SafStorage, SelectionDestination).
    api(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}

// The shared companion table (testdata/formats/companions.json) and the product source
// trees are read by the unit tests (SafCompanionTableTest, SoleCallerRuleTest): make them
// inputs so a change re-runs the tests instead of reporting UP-TO-DATE.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("../../testdata/formats/companions.json"))
        .withPropertyName("companionTable").withPathSensitivity(PathSensitivity.RELATIVE)
    listOf("android-desktop/src", "phototok/src", "core/src", "saf/src").forEach { tree ->
        inputs.dir(rootProject.file(tree))
            .withPropertyName("sources-${tree.replace('/', '-')}")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
