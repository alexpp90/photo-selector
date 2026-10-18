pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhotoSelectorToolbox"
// Module names follow their directory names, which name the product they build.
// ":app" told you nothing; ":android-desktop" tells you which of the two Android
// products you are looking at.
include(":core")
// :saf — the SAF file operations (transfer engine, persisted grants) both products share.
include(":saf")
include(":android-desktop")
include(":phototok")
