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
    }
}

rootProject.name = "graine-inapp-kmp"
include(":inapp-kmp")
include(":inapp-kmp-compose")

/*
 * WHICH ANDROID SDK THE ANDROID SIDE IS BUILT ON.
 *
 * In this repository the Android SDK sits beside this folder (../android), and the
 * Kotlin Multiplatform library is developed against it as it is: the published
 * coordinate ai.graine:inapp-android is replaced by that project, so a change on
 * either side is built and tested together. A release resolves the published
 * artifact instead — pass -Pgraine.localAndroidSdk=false (the published module
 * metadata then names ai.graine:inapp-android:<version>, not a local project).
 * A standalone checkout without ../android always uses the published artifact.
 */
val localAndroidSdk = providers.gradleProperty("graine.localAndroidSdk").orNull?.toBooleanStrictOrNull()
    ?: file("../android/graine-inapp").isDirectory
if (localAndroidSdk) {
    includeBuild("../android") {
        dependencySubstitution {
            substitute(module("ai.graine:inapp-android")).using(project(":graine-inapp"))
        }
    }
}
