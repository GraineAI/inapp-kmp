// The same toolchain as the Android SDK beside this folder (Kotlin 2.1.20, AGP 8.13.2),
// so a project that includes both builds them with one compiler.
plugins {
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}
