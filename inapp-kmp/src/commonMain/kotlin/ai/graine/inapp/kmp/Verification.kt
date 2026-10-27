package ai.graine.inapp.kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/*
 * YOUR APP AND YOUR USERS (new in 0.14.4) — what Graine has proven about this
 * app and about the signed-in user, for one agent. Two features, each set per
 * agent in the dashboard (Agent → Embed & Widgets → Install) and each off
 * until someone turns it on:
 *
 *   · "Your apps": which apps may use the agent's publishable key. The SDK
 *     says which app it is on every request and, when the agent asks for a
 *     verified app, proves it — App Attest on iOS, Play Integrity on Android
 *     — and keeps the app pass Graine gives for it (24 hours).
 *   · "Verified users": the identity `Graine.identify` names is proven by an
 *     identity token — an HMAC your server computes, or the ID token (JWT) of
 *     your own sign-in — and Graine gives a user pass for it (1 hour).
 *
 * The native SDKs do the work (the declaration, the proofs, the passes, the
 * token's place on each request); this file is what an app reads and passes.
 */

/** Whether Graine knows this app is yours, for one agent: [GraineVerification.app]. New in 0.14.4. */
enum class GraineAppVerdict(
    /** The word every Graine SDK uses for it: `off`, `checking`, `verified`, `testBuild`, `debugToken`, `unverified`. */
    val word: String,
) {
    /** The agent does not ask for a verified app — or its settings have not arrived yet. */
    OFF("off"),

    /** The SDK is proving the app right now, and holds no app pass yet. */
    CHECKING("checking"),

    /** Proven: App Attest (iOS) or Play Integrity (Android) recognised this build, and Graine gave it an app pass. */
    VERIFIED("verified"),

    /** A development build the agent accepts while its "Allow development builds" is on. */
    TEST_BUILD("testBuild"),

    /** A debug build, accepted through a debug token registered on the agent ([Graine.debugToken]). */
    DEBUG_TOKEN("debugToken"),

    /** Not proven: [GraineVerification.appReason] says why when Graine said. */
    UNVERIFIED("unverified");

    internal companion object {
        /** The verdict for a word — the SDKs' (`testBuild`) or Graine's own (`test_build`); null for one this build does not know. */
        fun from(word: String?): GraineAppVerdict? = word?.let { w -> entries.firstOrNull { sameWord(it.word, w) } }
    }
}

/** Whether Graine knows the signed-in user is who `identify` says, for one agent: [GraineVerification.user]. New in 0.14.4. */
enum class GraineUserVerdict(
    /** The word every Graine SDK uses for it: `off`, `verified`, `testUser`, `unverified`, `invalid`. */
    val word: String,
) {
    /** The agent does not verify its users — or its settings have not arrived yet. */
    OFF("off"),

    /** Proven by the identity token, or by the user pass Graine gave for it. */
    VERIFIED("verified"),

    /** One of the agent's test users, from an install whose debug token is registered on the agent. Counts as verified. */
    TEST_USER("testUser"),

    /** No identity token proved it — none was given (`missing`), or nobody is identified: [GraineVerification.userReason] says why when Graine said. */
    UNVERIFIED("unverified"),

    /** A token was sent and refused: [GraineVerification.userReason] says why (`expired`, `signature`, `mismatch`…). */
    INVALID("invalid");

    internal companion object {
        /** The verdict for a word — the SDKs' (`testUser`) or Graine's own (`test_user`); null for one this build does not know. */
        fun from(word: String?): GraineUserVerdict? = word?.let { w -> entries.firstOrNull { sameWord(it.word, w) } }
    }
}

/**
 * WHAT GRAINE HAS PROVEN, for one agent: the app ([app]) and the signed-in
 * user ([user]), each with Graine's reason when it is not proven. Both are
 * off until the agent's settings ask for them. Collect it from
 * [GraineAgent.verification] (or [Graine.verification] for the default
 * agent). New in 0.14.4.
 *
 *     Graine.verification.collect { v ->
 *         if (v.user == GraineUserVerdict.INVALID) log("identity token refused: ${v.userReason}")
 *     }
 */
data class GraineVerification(
    val app: GraineAppVerdict = GraineAppVerdict.OFF,
    /**
     * Why [app] is [GraineAppVerdict.UNVERIFIED], in Graine's word, when it
     * said: `missing`, `expired`, `invalid`, `wrong_app`, `test_build`,
     * `not_configured`, `debug_token_unknown`, `debug_token_expired`… Null
     * otherwise.
     */
    val appReason: String? = null,
    val user: GraineUserVerdict = GraineUserVerdict.OFF,
    /**
     * Why [user] is [GraineUserVerdict.UNVERIFIED] or
     * [GraineUserVerdict.INVALID], in Graine's word, when it said: `missing`,
     * `expired`, `not_yet_valid`, `signature`, `audience`, `issuer`, `claim`,
     * `mismatch`, `malformed`, `unknown_key`, `unavailable`. Null otherwise.
     */
    val userReason: String? = null,
)

/**
 * DEVELOPMENT MODE: when this build sends a debug token instead of proving
 * itself with App Attest or Play Integrity ([Graine.debugTokenMode]) —
 * simulators, emulators, sideloaded debug builds and CI cannot prove
 * themselves. New in 0.14.4.
 */
enum class GraineDebugTokenMode(
    /** The word every Graine SDK uses for it: `automatic`, `always`, `never`. */
    val word: String,
) {
    /**
     * The default: a debug build — on Android a debuggable one (every build
     * Android Studio runs), on iOS one built with the Debug configuration or
     * running on the Simulator — sends this install's debug token instead of
     * proving itself; a release build never makes, logs or sends one.
     */
    AUTOMATIC("automatic"),

    /** This build sends the debug token whatever its kind — an internal QA build signed for release, say. */
    ALWAYS("always"),

    /** No debug token, even in a debug build: it proves itself like a release build (useful with "Allow development builds"). */
    NEVER("never");

    internal companion object {
        /** The mode for its word; null for one this build does not know. */
        fun from(word: String?): GraineDebugTokenMode? = word?.let { w -> entries.firstOrNull { sameWord(it.word, w) } }
    }
}

/** The same word whatever its case or underscores: `testBuild` is the SDKs' spelling of Graine's `test_build`. */
private fun sameWord(a: String, b: String): Boolean = normalWord(a) == normalWord(b)

private fun normalWord(w: String): String = w.trim().lowercase().replace("_", "")

/**
 * THE APP'S IDENTITY-TOKEN REFRESHER, as the platform SDKs call it: they hand
 * a callback to answer once, the app's refresher suspends. Shared, so the one
 * rule — a token, or null to keep the one held — holds on both platforms.
 */
internal object IdentityTokens {
    /**
     * [refresher] in the platform SDKs' callback form: started right where
     * the platform SDK asks (its main thread) and continued in [scope] (the
     * main thread), answered exactly once — with the fresh token, or with
     * null, which keeps the token the SDK holds, when it returns null or a
     * blank token, throws, or is cancelled.
     */
    fun bridge(refresher: suspend () -> String?, scope: () -> CoroutineScope = { Graine.actionScope }): (done: (String?) -> Unit) -> Unit =
        { done ->
            scope().launch(start = CoroutineStart.UNDISPATCHED) {
                val token = try {
                    refresher()
                } catch (e: Throwable) {
                    // A refresher that fails — or is cancelled — leaves the token as it was: the SDK is still answered.
                    null
                }
                done(token?.takeIf { it.isNotBlank() })
            }
        }
}
