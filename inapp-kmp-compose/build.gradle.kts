import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    `maven-publish`
}

version = providers.gradleProperty("GRAINE_VERSION").get()

/** As in inapp-kmp: `-Pgraine.kmp.ios=false` builds the Android side alone. Never publish that way. */
val iosEnabled = providers.gradleProperty("graine.kmp.ios").orNull != "false"

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    if (iosEnabled) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        // The iOS side calls Objective-C (the facade, UIKit) through cinterop.
        matching { it.name.startsWith("ios") }.configureEach { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
        commonMain.dependencies {
            // The SDK itself, and only the Compose pieces the composables need: they host the
            // platform SDK's own views (AndroidView, UIKitViewController), they draw nothing of their own.
            api(project(":inapp-kmp"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
    }
}

// Kotlin/Native libraries record their source files' paths: relative to this folder, never this machine's.
val relativePaths = "-Xklib-relative-path-base=${projectDir.absolutePath}"
tasks.withType<KotlinNativeCompile>().configureEach { compilerOptions.freeCompilerArgs.add(relativePaths) }
tasks.withType<KotlinCompileCommon>().configureEach { compilerOptions.freeCompilerArgs.add(relativePaths) }

android {
    namespace = "ai.graine.inapp.kmp.compose"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ── publishing: the convention every ai.graine library publishes through ──
extra["graine.pomName"] = "Graine in-app agent for Compose Multiplatform"
extra["graine.pomDescription"] = "The Graine in-app agent's own chat, call screen and voice bar as Compose Multiplatform composables, on Android and iOS."
extra["graine.docsUrl"] = "https://www.graine.ai/docs/in-app/kotlin-multiplatform"
extra["graine.scmUrl"] = "https://github.com/GraineAI/inapp-kmp"
// In sdk/gradle in the monorepo, in gradle/ in the exported repository.
apply(
    from = listOf(rootProject.file("gradle/central-publishing.gradle.kts"), rootProject.file("../gradle/central-publishing.gradle.kts"))
        .firstOrNull { it.isFile }
        ?: throw GradleException("central-publishing.gradle.kts is in neither gradle/ nor ../gradle/"),
)
