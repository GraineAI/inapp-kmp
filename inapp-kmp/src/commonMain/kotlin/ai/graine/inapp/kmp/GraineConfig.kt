package ai.graine.inapp.kmp

/** This library's version: what it says it is, the same on Android and iOS. */
object GraineKmpVersion {
    const val NAME = "0.14.7"
}

/**
 * Everything the SDK needs to know up front — the native SDKs' `GraineConfig`,
 * field for field.
 */
data class GraineConfig(
    /** "https://www.graine.ai" unless self-hosted. */
    val baseUrl: String,
    /**
     * From Agent → Embed & Widgets. Selects the agent. It is not a secret and
     * cannot place a call on its own — add `app://` to that agent's Allowed
     * domains, or every request is refused.
     */
    val publishableKey: String,
    /** Prompt {variables} known at build time. `identify()` and `setVariables()` win over these. */
    val variables: Map<String, Any?> = emptyMap(),
    /** Lands on the call record, to tell builds apart. */
    val appVersion: String? = null,
    /** End a live conversation when the app goes to the background. On by default; you want it on. */
    val endOnBackground: Boolean = true,
    /**
     * Log the SDK's traffic with Graine — keys only, never values — to logcat
     * (tag "Graine") and the iOS console. Off by default; turn it on in debug builds.
     */
    val debug: Boolean = false,
    /**
     * Notice taps on labelled controls by themselves (what a screen reader
     * reads, a view's id, a button's words), at most two a second and only
     * while a screen is reported, so the agent knows what the customer just
     * pressed. Only watches; every touch reaches the app as before. On by default.
     */
    val captureTaps: Boolean = true,
    /** Force how the voice bar's audio travels. Null lets the dashboard's Call audio setting decide. */
    val transport: GraineTransport? = null,
    /**
     * How big the chat window opens, over the dashboard's choice (Embed &
     * Widgets → Appearance → Chat window → Window size). Null, the default,
     * follows the dashboard; a size passed to [GraineChat.open] wins for that open.
     */
    val chatSize: GraineChatSize? = null,
    /**
     * How long the chat stays connected once no screen shows it, in seconds:
     * the conversation closes this long after the last screen left it (or
     * after its last message, whichever is later), unless a screen shows it
     * again first — long enough for a quick hop to the booking sheet or a
     * component. 15 by default; 0 closes it as soon as the last screen
     * leaves; kept within 0–300 (a negative value is 0).
     * [GraineChat.closeDelaySeconds] changes it while the app runs. On iOS it
     * needs the GraineInApp of this version. New in 0.14.3.
     */
    val chatCloseDelaySeconds: Int = 15,
    /**
     * Server tokens (optional — the strongest mode): returns a fresh token from
     * YOUR server, which mints it with `POST /v2/client-tokens` and your secret
     * API key, or null when none can be had right now (throwing counts as null).
     * Called at start, again shortly before the token expires (in the
     * background, with jitter, retried with backoff), and when Graine refuses
     * a token; every request to Graine then carries it. A token that expires
     * never ends a live chat or call — only new conversations wait for a fresh
     * one. Needed once the agent's "Require server tokens" is enforced; null,
     * the default, sends none. New in 0.14.4.
     */
    val tokenProvider: (suspend () -> String?)? = null,
)

/** How the voice bar's audio travels. */
enum class GraineTransport(val wire: String) {
    /** Raw audio on the conversation socket. Works everywhere. */
    WEBSOCKET("ws"),
    /** WebRTC with a jitter buffer and the device's echo canceller; falls back to [WEBSOCKET]. */
    WEBRTC("rtc"),
}
