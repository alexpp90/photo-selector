plugins {
    `kotlin-dsl`
}

dependencies {
    // The Android DSL the convention plugin configures; the modules bring the plugin itself.
    compileOnly(libs.android.gradle.plugin)
    // Applied by the convention plugin, so every module is linted the same way.
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidModule") {
            id = "photoselector.android-module"
            implementationClass = "AndroidModuleConventionPlugin"
        }
    }
}
