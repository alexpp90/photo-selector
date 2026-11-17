// Included build (settings.gradle.kts: pluginManagement { includeBuild("build-logic") }): the
// convention plugin every Android module applies. It shares the root version catalog.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
