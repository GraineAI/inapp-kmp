package ai.graine.inapp.kmp

/** This library's version: what it says it is, the same on Android and iOS. */
object GraineKmpVersion {
    const val NAME = "0.10.1"
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
)

/** How the voice bar's audio travels. */
enum class GraineTransport(val wire: String) {
    /** Raw audio on the conversation socket. Works everywhere. */
    WEBSOCKET("ws"),
    /** WebRTC with a jitter buffer and the device's echo canceller; falls back to [WEBSOCKET]. */
    WEBRTC("rtc"),
}
