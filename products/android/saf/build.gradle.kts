import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    id("photoselector.android-module")
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
    // DocumentFile is part of :saf's public surface (SafStorage, SelectionDestination).
    api(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
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
