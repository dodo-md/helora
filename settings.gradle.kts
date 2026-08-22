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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.philburk")
                includeGroup("com.github.racra")
                // NewPipeExtractor is a multi-module JitPack build, so artifacts resolve
                // under both com.github.TeamNewPipe and com.github.TeamNewPipe.NewPipeExtractor.
                includeGroupByRegex("com\\.github\\.TeamNewPipe.*")
            }
        }
    }
}

rootProject.name = "Helora"
include(":app")
include(":baselineprofile")
