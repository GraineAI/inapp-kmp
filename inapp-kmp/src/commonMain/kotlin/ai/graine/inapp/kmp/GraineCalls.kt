package ai.graine.inapp.kmp

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * Where the one native call is.
 *
 *   IDLE        no call
 *   RINGING     the agent is calling the user (incoming only)
 *   CONNECTING  answered, or placed by the user; the line is being opened
 *   ACTIVE      the agent and the user can hear each other
 *   ENDED       over — reported once, then IDLE again
 */
enum class GraineCallState { IDLE, RINGING, CONNECTING, ACTIVE, ENDED }

/**
 * What became of a call — for your server, and for your own screens. Every
 * outcome carries the call's id.
 *
 *   RINGING   the phone is ringing with it
 *   ANSWERED  the user picked up (an ENDED or FAILED follows)
 *   DECLINED  the user declined it
 *   MISSED    nobody answered in time (`no_answer`), or another call was live (`busy`)
 *   FAILED    it never connected; [reason] says why
 *   ENDED     a call that connected is over: [reason] is `local`, `remote` or `failed`,
 *             [durationSeconds] how long it was connected
 */
data class GraineCallOutcome(
    val callId: String,
    val outcome: Kind,
    val reason: String? = null,
    val durationSeconds: Int? = null,
    /** Graine rang it: a call booked by the agent, the user or the dashboard. */
    val hosted: Boolean = false,
    /**
     * The agent the call was with, by the name it was configured under
     * (`"default"` for the default agent); null when the platform does not know.
     */
    val agent: String? = null,
) {
    enum class Kind(val wire: String) {
        RINGING("ringing"), ANSWERED("answered"), DECLINED("declined"), MISSED("missed"), FAILED("failed"), ENDED("ended"),
    }
}

/** A line said on the call (or in the voice bar's call): the agent's as it is spoken, the user's as it is heard. */
data class GraineCaption(val role: GraineRole, val text: String, val live: Boolean)

/** An agent's call ringing on this device. */
data class GraineIncomingCall(
    val callId: String,
    /** What the ring shows: the push's caller name, else the agent's call name or the app's display name. */
    val callerName: String?,
    /** Why the agent is calling, when the push said (a call Graine rang keeps its reason on the server). */
    val reason: String?,
    val hosted: Boolean,
    /**
     * The agent ringing, by the name it was configured under (`"default"` for
     * the default agent): the platform matches the push to a configured agent.
     * Null when the platform does not say.
     */
    val agent: String? = null,
)

/** A ring nobody answered, kept so a call screen can say so and offer [GraineCalls.callBack]. */
data class GraineMissedCall(
    val callId: String,
    val callerName: String?,
    val reason: String?,
    val hosted: Boolean,
    val at: Instant,
)

/** The call as a call screen draws it. */
data class GraineCallInfo(
    val state: GraineCallState = GraineCallState.IDLE,
    val callId: String? = null,
    val callerName: String? = null,
    /** The agent rang (a push); false for a call the user placed. */
    val incoming: Boolean = false,
    val hosted: Boolean = false,
    val muted: Boolean = false,
    /** The loudspeaker (else a headset, if one is connected, or the earpiece). */
    val speakerOn: Boolean = false,
    val agentSpeaking: Boolean = false,
    /** The last line said, while a call is live. */
    val caption: GraineCaption? = null,
    /** The last ring nobody answered, until it is called back or dismissed. */
    val missedCall: GraineMissedCall? = null,
    /**
     * The agent the call is with, by the name it was configured under
     * (`"default"` for the default agent) — the one `startCall` named, or the
     * one a ring was routed to. Null with no call.
     */
    val agent: String? = null,
) {
    /** A call screen has something to show: a call, or a missed ring to call back. */
    val showsCallScreen: Boolean get() = state != GraineCallState.IDLE || missedCall != null
}

/** How native calls look and ring. Set once, at launch: [GraineCalls.configure]. */
data class GraineCallsConfig(
    /** The name a call rings with and shows, when the push names nobody. Empty: the agent's call name, else the app's name. */
    val displayName: String = "",
    /** Seconds an agent's call rings before it is missed (a call Graine rang uses the agent's own ring time). */
    val ringTimeoutSeconds: Int = 30,
    /** The ring: on Android a content URI, on iOS a sound file in the app's bundle. Null: the system ringtone. */
    val ringtone: String? = null,
    /** Android: a monochrome drawable resource for the status-bar icon. 0: the platform's call icon. */
    val smallIcon: Int = 0,
    /** iOS: list the calls in the Phone app's Recents. */
    val includeInRecents: Boolean = false,
)

/**
 * CALLS LIKE WHATSAPP — the agent rings the user, or the user calls the agent,
 * on the phone's own call screen and over the lock screen, with the call going
 * on with the screen off. `Graine.calls`.
 *
 * Graine rings your users itself once your push credentials are uploaded
 * (Workspace settings → In-app calling): on iOS call [enableVoIPPushes] at
 * every launch; on Android forward your messaging service's token and data
 * messages to [onNewToken] and [handlePush]. The SDK registers the device,
 * rings the calls Graine sends and reports what became of each.
 *
 * One call at a time: a ring while a call is live is reported missed (`busy`).
 * Methods may be called from any thread; [startCall] answers on the spot.
 */
class GraineCalls internal constructor() {
    /** The call as a call screen draws it. */
    val state: StateFlow<GraineCallInfo> = Graine.callInfo.asStateFlow()

    /** What became of each call, as it happens. Not replayed: collect it from app start. */
    val outcomes: SharedFlow<GraineCallOutcome> = Graine.callOutcomes.asSharedFlow()

    /** An agent's call started ringing on this device. Not replayed. */
    val incoming: SharedFlow<GraineIncomingCall> = Graine.incomingCalls.asSharedFlow()

    /** How calls look and ring. Call once, at launch (on iOS before [enableVoIPPushes]). */
    fun configure(config: GraineCallsConfig) = Graine.callsEngine().callsConfigure(config)

    /**
     * iOS: receive the agent's calls as VoIP pushes — call at every launch
     * (iOS wakes a closed app for a VoIP push and hands it over once this has
     * run). The SDK registers the token with Graine when Graine rings this app.
     * Android: nothing to do here (see [onNewToken] and [handlePush]).
     */
    fun enableVoIPPushes() = Graine.callsEngine().callsEnableVoIPPushes()

    /**
     * The user calls an agent — [agent] by name, the default agent when null:
     * the call screen opens and the call connects. `reason` becomes the
     * prompt's `call_reason`; `variables` are this call's own prompt variables,
     * over the SDK's. Returns the call's id — every outcome carries it — or null
     * when another call is live (or, on Android, when no screen of the app is
     * in front to start it from).
     */
    fun startCall(reason: String? = null, variables: Map<String, Any?> = emptyMap(), agent: String? = null): String? =
        Graine.engineFor(agent).callsStart(reason?.takeIf { it.isNotBlank() }, variables)

    /** Pick up the ringing call (the SDK's call screen does this itself). */
    fun answer() = Graine.callsEngine().callsAnswer()

    /** Decline the ringing call. */
    fun decline() = Graine.callsEngine().callsDecline()

    /** Hang up — or, while it rings, decline. */
    fun end() = Graine.callsEngine().callsEnd()

    /** Stop sending the user's voice without ending the call. */
    fun setMuted(muted: Boolean) = Graine.callsEngine().callsSetMuted(muted)

    /** The loudspeaker on or off. Off, the call goes back to a headset if one is connected, else the earpiece. */
    fun setSpeaker(on: Boolean) = Graine.callsEngine().callsSetSpeaker(on)

    /** Return the last missed call: the user calls the agent back. The new call's id, or null. */
    fun callBack(): String? = Graine.callsEngine().callsCallBack()

    /** The user has seen the missed call: a call screen can go. */
    fun dismissMissedCall() = Graine.callsEngine().callsDismissMissed()

    /**
     * Android: your FirebaseMessagingService's new token — one line,
     * `override fun onNewToken(token: String) = Graine.calls.onNewToken(token)`.
     * Kept and registered with Graine when Graine rings this app. Works before
     * [Graine.configure], as a messaging service may start first.
     * iOS: does nothing — the VoIP token comes from PushKit ([enableVoIPPushes]).
     */
    fun onNewToken(token: String) = PlatformPush.onNewToken(token)

    /**
     * Android: hand every FCM data message here first, from your
     * FirebaseMessagingService — `if (Graine.calls.handlePush(message.data)) return`.
     * True when it was a call (Graine's own ring, or one your server sent), which
     * the SDK rings; false when it is one of your own messages.
     * iOS: always false — a VoIP push reaches PushKit, and the SDK, by itself.
     */
    fun handlePush(data: Map<String, String>): Boolean = PlatformPush.handlePush(data)
}
