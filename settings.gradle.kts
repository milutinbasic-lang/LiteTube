pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack hosts NewPipeExtractor; only allow TeamNewPipe artifacts from it.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("(?i)com\\.github\\.teamnewpipe") }
        }
    }
}

rootProject.name = "LiteTube"
include(":app")
