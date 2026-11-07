package ai.graine.inapp.kmp

/**
 * WHICH iOS SDK THE APP ACTUALLY LINKED.
 *
 * This library and the iOS SDK are separate dependencies: Gradle resolves `inapp-kmp`, Xcode resolves
 * `GraineInApp` and `GraineInAppRTC` (SwiftPM or CocoaPods), and nothing ties the two together. So Kotlin can
 * move forward while Xcode stays where it was, and the app builds and runs: the older SDK's bugs simply stay
 * in it, under a version number nobody thinks they are on.
 *
 * On iOS the engine asks the facade for its version and reports the gap in two places a developer
 * sees it: `GraineCalls.callSetupProblems`, and one line in the console at launch. An iOS SDK from before the
 * facade could answer has no such selector, and is older than this library expects by that alone.
 */
internal object LinkedIosSdk {
    /** Stable: what `GraineCalls.callSetupProblems` calls it. */
    const val PROBLEM_CODE = "ios_sdk_older"

    /** The iOS SDK this library is built for: GRAINE_IOS_VERSION in gradle.properties (bump-version moves both). */
    const val EXPECTED = "0.15.2"

    /** The first iOS SDK whose facade says its version. */
    const val FIRST_THAT_SAYS = "0.14.10"

    /**
     * The problem to report, or null when the linked SDK is the expected one or newer. [linked] is what the
     * facade says, or null when it cannot say. A version that cannot be read raises no alarm: a false warning
     * would send someone hunting for nothing.
     */
    fun problem(linked: String?, expected: String = EXPECTED): GraineCallSetupProblem? {
        if (linked != null && !isOlder(linked, expected)) return null
        val which = if (linked == null) "an iOS SDK older than $FIRST_THAT_SAYS" else "GraineInApp $linked"
        return GraineCallSetupProblem(
            PROBLEM_CODE,
            "This app links $which, and inapp-kmp ${GraineKmpVersion.NAME} is built for GraineInApp $expected. Kotlin and " +
                "Xcode resolve these separately, so updating inapp-kmp does not update the iOS SDK, and an older one " +
                "can miss fixes, such as calls with no sound. Set GraineInApp and GraineInAppRTC to $expected in Xcode " +
                "and reset the package caches (with CocoaPods: change the :tag in the Podfile and run pod update).",
        )
    }

    /** Whether [version] is older than [than], number by number. False when either cannot be read as numbers. */
    fun isOlder(version: String, than: String): Boolean {
        val a = numbers(version) ?: return false
        val b = numbers(than) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left < right
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val parts = version.trim().split(".")
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }
}
