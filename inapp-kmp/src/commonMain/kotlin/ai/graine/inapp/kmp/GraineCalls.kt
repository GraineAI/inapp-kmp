package ai.graine.inapp.kmp

import kotlinx.coroutines.CompletableDeferred
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
 * WHERE A NATIVE CALL IS, as a call screen shows it — the one word every
 * Graine SDK uses for it (sdk/contract/PUBLIC-STATE.md, "Calls"; Flutter's and
 * React Native's `phase`): [GraineCalls.phase], [GraineCallInfo.phase]. [wire]
 * spells each. Use an `else` branch in a `when`, as more may come. New in 0.14.5.
 */
enum class GraineCallPhase(
    /** The word every Graine SDK uses for it. */
    val wire: String,
) {
    /** No call. */
    IDLE("idle"),
    /** An agent's call is ringing on this device. */
    RINGING("ringing"),
    /** The user's call, before the agent answers. */
    CALLING("calling"),
    /** An answered ring, connecting. */
    CONNECTING("connecting"),
    /** On the call. */
    ACTIVE("active"),
    /** On the call, its connection being recovered: "Reconnecting…". */
    RECONNECTING("reconnecting"),
    /** The call is over — its last moment on screen. */
    ENDED("ended"),
    /** A ring nobody answered ([GraineCallInfo.missedCall]), until the user calls back, dismisses it, or another call rings or is placed. */
    MISSED("missed"),
    ;

    companion object {
        /**
         * The phase of a call in [state] — Flutter's and React Native's mapping:
         * the user's call not yet answered is CALLING, a connected call
         * recovering its connection RECONNECTING, and no call with a missed ring
         * kept to call back MISSED.
         */
        fun of(state: GraineCallState, incoming: Boolean, reconnecting: Boolean, missed: Boolean): GraineCallPhase = when (state) {
            GraineCallState.IDLE -> if (missed) MISSED else IDLE
            GraineCallState.RINGING -> RINGING
            GraineCallState.CONNECTING -> if (incoming) CONNECTING else CALLING
            GraineCallState.ACTIVE -> if (reconnecting) RECONNECTING else ACTIVE
            GraineCallState.ENDED -> ENDED
        }
    }
}

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
    /**
     * How the call connected, as it stood when it ended — on ENDED and
     * FAILED; null otherwise (and from a platform SDK that predates it).
     */
    val connection: GraineCallConnection? = null,
    /**
     * Who or what ended the call, on ENDED and FAILED: `local_end` (this app or
     * the user hung up), `remote_bye` (the agent's side hung up), `media_drop`
     * (the audio path dropped and did not recover), `audio_watch` (the SDK's
     * own watch ended it — on Android nothing arrived for too long, on iOS the
     * call's audio never started), `failed` (anything else), and on iOS
     * `provider_reset` (the system reset CallKit). Null otherwise, and from a
     * platform SDK that predates it.
     */
    val endCause: String? = null,
) {
    enum class Kind(val wire: String) {
        RINGING("ringing"), ANSWERED("answered"), DECLINED("declined"), MISSED("missed"), FAILED("failed"), ENDED("ended"),
    }
}

/**
 * How native calls carry their audio ([GraineCallsConfig.callTransport]).
 *
 *   AUTO       WebRTC when the app includes the platform's WebRTC module
 *              (README → WebRTC calls), else the WebSocket. The default.
 *   WEBSOCKET  always the WebSocket
 *   WEBRTC     WebRTC whenever it can carry the call, else the WebSocket
 *
 * A call WebRTC cannot carry continues on the WebSocket inside the same call,
 * and the dashboard can turn WebRTC off for an agent without an app release.
 */
enum class GraineCallTransport(val wire: String) {
    AUTO("auto"), WEBSOCKET("websocket"), WEBRTC("webrtc");

    companion object {
        /** What a call runs on, from the platform's word: WEBRTC or WEBSOCKET, else null. */
        fun ofCall(wire: String?): GraineCallTransport? = when (wire?.lowercase()) {
            WEBRTC.wire -> WEBRTC
            WEBSOCKET.wire -> WEBSOCKET
            else -> null
        }
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
    /** What the call's audio runs on: WEBRTC or WEBSOCKET. Null with no call (and from a platform SDK that predates WebRTC). */
    val transport: GraineCallTransport? = null,
    /**
     * Why a call that tried WebRTC runs on the WebSocket instead — `mint_failed`,
     * `register_failed`, `no_response`, `no_answer`, `ice_timeout`, `error`, or
     * the dashboard's choice. Null when it did not fall back.
     */
    val transportFallbackReason: String? = null,
    /**
     * A connected call recovering its connection (its audio dropped and is
     * being restored) — the call screen says "Reconnecting…". False again once
     * the audio is back, and always false from a platform SDK that predates it.
     */
    val reconnecting: Boolean = false,
    /** Why the call is happening — the ring's reason, or `startCall`'s — when it has one. Null with no call. */
    val reason: String? = null,
    /** How the call ended, while it is ENDED: `local` (this app hung up), `remote` (the agent did) or `failed`. Null otherwise. */
    val endReason: String? = null,
    /** Who or what ended it, while it is ENDED — [GraineCallOutcome.endCause]'s word. Null otherwise. */
    val endCause: String? = null,
    /**
     * The agent's voice level, 0–1, as it is heard — what the call screen's
     * visualiser follows: about five times a second over WebRTC, ten over the
     * WebSocket while the agent's audio plays. 0 with no call. On iOS it needs
     * the GraineInApp of this version.
     */
    val agentLevel: Float = 0f,
    /** When the call connected; the call screen's duration counts from it. Null before (and with no call). */
    val startedAt: Instant? = null,
    /**
     * HOW THE CALL IS CONNECTING — the same facts as its Connection in
     * Execution Logs, growing as the call goes ([GraineCallConnection]). Null
     * with no call, before its first fact, and from a platform SDK that
     * predates it; its outcome carries the last of it.
     */
    val connection: GraineCallConnection? = null,
    /**
     * THE CALL'S LIVE TRANSCRIPT, as the call screen draws it: what has been
     * said, oldest first — the agent's lines as they are spoken, the user's as
     * they are heard, a live line replaced as its words arrive ([caption] is
     * the last of them) — the last 12. Kept through ENDED, empty with no call.
     * On iOS it needs the GraineInApp of this version. New in 0.14.1.
     */
    val captions: List<GraineCaption> = emptyList(),
) {
    /** A call screen has something to show: a call, or a missed ring to call back. */
    val showsCallScreen: Boolean get() = state != GraineCallState.IDLE || missedCall != null

    /**
     * WHERE THE CALL IS, in one word, as a call screen shows it — the word every
     * Graine SDK uses ([GraineCallPhase]): IDLE, RINGING, CALLING (the user's
     * call before the agent answers), CONNECTING, ACTIVE, RECONNECTING, ENDED,
     * or MISSED while [missedCall] keeps a ring nobody answered. From [state],
     * [incoming], [reconnecting] and [missedCall]. New in 0.14.5.
     */
    val phase: GraineCallPhase get() = GraineCallPhase.of(state, incoming, reconnecting, missedCall != null)
}

/**
 * HOW A CALL CONNECTED — the same facts as the call's Connection in Execution
 * Logs, live while the call goes ([GraineCallInfo.connection]) and final on
 * its outcome ([GraineCallOutcome.connection]). Each fact is null until it is
 * known (a call carried over the WebSocket has no audio figures). Timings and
 * network figures only — nothing personal.
 *
 * The tap's wait, in order: [setupMs], [loginMs], [answerMs], [firstWordsMs];
 * [tapToVoiceMs] is all of it, as the user felt it.
 */
data class GraineCallConnection(
    /** What carried the call: WEBRTC or WEBSOCKET. */
    val transport: GraineCallTransport? = null,
    /** Prepared before the tap ([GraineCalls.prepareCall], or a ring's, ready by its answer); false: set up after the tap. */
    val prepared: Boolean? = null,
    /** How long before the tap a prepared call was ready, in seconds. */
    val preparedAgeSeconds: Long? = null,
    /** Where the call's credential came from: `direct` (issued directly, WebRTC) or `web` (issued through the web). */
    val credential: String? = null,
    /** From the tap to the call being placed — without the login, on a call set up after the tap. */
    val setupMs: Long? = null,
    /** The device signing in to the call server, inside the setup (a prepared call logged in before the tap: null). */
    val loginMs: Long? = null,
    /** From the call being placed to it being answered. */
    val answerMs: Long? = null,
    /** From the answer to the agent's voice reaching the phone. */
    val firstWordsMs: Long? = null,
    /** From the tap to the agent's voice: the wait the user felt. */
    val tapToVoiceMs: Long? = null,
    /** The audio — the readings so far while the call is live, the call's own at its end. WebRTC only. */
    val audio: Audio? = null,
    /** How many times the connection recovered on its own. */
    val recoveries: Int = 0,
) {
    /** The audio as the phone measured it. */
    data class Audio(
        /** Packets lost of all the far side sent, in percent (two decimals): 0.28 is 0.28 %. */
        val lossPct: Double? = null,
        /** How unevenly the agent's audio arrived, on average, in milliseconds; under 30 is good. */
        val jitterAvgMs: Long? = null,
        /** The worst jitter measured, in milliseconds. */
        val jitterPeakMs: Long? = null,
        /** The network's round trip, on average, in milliseconds; under 150 is good. */
        val rttMs: Long? = null,
        /** In a word: `good`, `fair` or `poor` — Execution Logs' verdict on the loss, jitter and round trip. */
        val quality: String? = null,
    )
}

/** A permission a native call needs. [wire] is the word every Graine SDK uses. */
enum class GraineCallPermission(val wire: String) {
    /** The user's voice. Asked by the SDK when a call starts without it, or by [GraineCalls.requestCallPermissions]. */
    MICROPHONE("microphone"),
    /** A call that rings while the app is closed (Android; 13+ asks). iOS rings through CallKit, which needs none. */
    NOTIFICATIONS("notifications"),
    /** A ring that fills a locked screen (Android 14+, allowed in Settings). iOS: CallKit's own screen. */
    FULL_SCREEN("fullScreen");

    companion object {
        /** By its word — `microphone`, `notifications`, `fullScreen` (`full_screen` and any case too); null for any other. */
        fun of(word: String?): GraineCallPermission? {
            val folded = word?.trim()?.replace("_", "")?.lowercase() ?: return null
            return entries.firstOrNull { it.wire.lowercase() == folded }
        }
    }
}

/** Where a call permission stands. [wire] is the word every Graine SDK uses. */
enum class GraineCallPermissionStatus(val wire: String) {
    /** Allowed. */
    GRANTED("granted"),
    /** Not allowed yet: [GraineCalls.requestCallPermissions] can ask for it. */
    DENIED("denied"),
    /** Refused for good, or only allowed in Settings: only Settings can change it ([GraineCalls.openCallSettings]). */
    BLOCKED("blocked"),
    /** This phone needs no such permission. */
    NOT_NEEDED("notNeeded");

    /** Granted, or nothing to grant. */
    val isSatisfied: Boolean get() = this == GRANTED || this == NOT_NEEDED

    companion object {
        /** From the platform's word; anything it does not know is DENIED, never granted. */
        fun of(word: String?): GraineCallPermissionStatus = entries.firstOrNull { it.wire == word } ?: DENIED
    }
}

/** Every call permission's status, as the phone holds it when read. */
data class GraineCallPermissions(
    val microphone: GraineCallPermissionStatus,
    val notifications: GraineCallPermissionStatus,
    val fullScreen: GraineCallPermissionStatus,
) {
    /** One permission's status. */
    operator fun get(item: GraineCallPermission): GraineCallPermissionStatus = when (item) {
        GraineCallPermission.MICROPHONE -> microphone
        GraineCallPermission.NOTIFICATIONS -> notifications
        GraineCallPermission.FULL_SCREEN -> fullScreen
    }

    /** Every permission is granted or not needed: calls can ring and connect. */
    val allGranted: Boolean get() = GraineCallPermission.entries.all { get(it).isSatisfied }
}

/**
 * Something the app's setup still lacks for calls: a stable [code] and a
 * [message] with the exact fix — the same codes on every Graine SDK. Calls
 * still work where they can.
 */
data class GraineCallSetupProblem(val code: String, val message: String)

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
    /** How calls carry their audio: [GraineCallTransport.AUTO] (WebRTC when the app includes it), WEBSOCKET or WEBRTC. */
    val callTransport: GraineCallTransport = GraineCallTransport.AUTO,
    /**
     * Android: your app's own call screen — the full class name of an Activity
     * of your app ("com.acme.app.CallActivity"), declared in its manifest. The
     * SDK opens it instead of its own for a ring, a call placed or answered,
     * and a missed call; it draws from [GraineCalls.state], calls the same
     * functions as the SDK's screen, and forwards its permission answers to
     * `Graine.onRequestPermissionsResult`. Null, or a name that is not an
     * Activity of your app (a warning in the log): the SDK's call screen.
     * Ignored on iOS, where your screen draws over CallKit's. New in 0.14.0.
     */
    val androidCallActivity: String? = null,
)

/**
 * CALLS LIKE WHATSAPP — the agent rings the user, or the user calls the agent,
 * on the phone's own call screen and over the lock screen, with the call going
 * on with the screen off. `Graine.calls`.
 *
 * Graine rings your users itself once your push credentials are uploaded
 * (Workspace settings → In-app calling): on iOS call [enableVoIPPushes] at
 * every launch; on Android add Firebase Cloud Messaging to the app
 * (google-services.json, the Google services plugin, firebase-messaging) and
 * the SDK registers for pushes itself — its own messaging service receives the
 * calls, and it reads the push token at start. An app with a
 * FirebaseMessagingService of its own forwards from it to [onNewToken] and
 * [handlePush]. The SDK registers the device, rings the calls Graine sends and
 * reports what became of each.
 *
 * PERMISSIONS AND SETUP: [requestCallPermissions] asks when the app chooses
 * (never on its own at launch; a call that starts without the microphone
 * still asks then), [callPermissions] reads them, [openCallSettings] opens
 * Settings, and [callSetupProblems] says what the app's setup still lacks,
 * with the fix — each one is also logged once by the platform SDK.
 *
 * One call at a time: a ring while a call is live is reported missed (`busy`).
 * Methods may be called from any thread; [startCall] answers on the spot.
 */
class GraineCalls internal constructor() {
    /** The call as a call screen draws it. */
    val state: StateFlow<GraineCallInfo> = Graine.callInfo.asStateFlow()

    /**
     * WHERE THE CALL IS, in the word every Graine SDK uses — [GraineCallInfo.phase]
     * of [state], as it changes: IDLE, RINGING, CALLING, CONNECTING, ACTIVE,
     * RECONNECTING, ENDED or MISSED. New in 0.14.5.
     */
    val phase: StateFlow<GraineCallPhase> = Graine.callPhase.asStateFlow()

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
     * Android: nothing to do here — with Firebase in the app the SDK registers
     * for pushes itself ([onNewToken] and [handlePush] are for an app with a
     * messaging service of its own).
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

    /**
     * Get the user's next call to an agent — [agent] by name, the default
     * agent when null — ready before they tap, so that when they do,
     * [startCall] connects almost at once. Call it when the screen with your
     * call button appears; calling it again is cheap: while a call prepared
     * for the same agent, user, [reason] and [variables] is ready, nothing
     * more happens. A [startCall] with the same reason and variables within
     * four minutes uses it; any other call is prepared at the tap, as without
     * this.
     *
     * Nothing is dialled and no conversation starts — nothing is billed —
     * until [startCall], and the microphone is not touched. What was prepared
     * is let go when the app goes to the background, the signed-in user
     * changes ([Graine.identify], sign-out, [Graine.setMemory], the phone's
     * time zone), after four minutes, when calls stop using WebRTC, or when a
     * call rings in. For calls over WebRTC (the platform's WebRTC module in
     * the app); otherwise, before the agent's settings have loaded, while a
     * call rings or is live, or with a platform SDK from before it, it does
     * nothing.
     */
    fun prepareCall(reason: String? = null, variables: Map<String, Any?> = emptyMap(), agent: String? = null) =
        Graine.engineFor(agent).callsPrepare(reason?.takeIf { it.isNotBlank() }, variables)

    /** Pick up the ringing call (the SDK's call screen does this itself). */
    fun answer() = Graine.callsEngine().callsAnswer()

    /** Decline the ringing call. */
    fun decline() = Graine.callsEngine().callsDecline()

    /** Hang up — or, while it rings, decline. */
    fun end() = Graine.callsEngine().callsEnd()

    /**
     * Calls start on the loudspeaker — an agent's voice is heard with the phone
     * in the hand, not at the ear — unless a headset is connected (on iOS also
     * a car or AirPlay), which takes the call; [setSpeaker] still moves it.
     * False: the earpiece first, as before 0.14.0. Set it before a call; may be
     * set before [Graine.configure]. On iOS it needs the GraineInApp of this
     * version (an older one keeps its own route).
     */
    var speakerByDefault: Boolean
        get() = Graine.speakerByDefault
        set(value) {
            Graine.speakerByDefault = value
            Graine.agents.values.forEach { it.engineOrNull()?.callsSetSpeakerByDefault(value) }
        }

    /** Stop sending the user's voice without ending the call. */
    fun setMuted(muted: Boolean) = Graine.callsEngine().callsSetMuted(muted)

    /** The loudspeaker on or off. Off, the call goes back to a headset if one is connected, else the earpiece. */
    fun setSpeaker(on: Boolean) = Graine.callsEngine().callsSetSpeaker(on)

    /** Return the last missed call: the user calls the agent back. The new call's id, or null. */
    fun callBack(): String? = Graine.callsEngine().callsCallBack()

    /** The user has seen the missed call: a call screen can go. */
    fun dismissMissedCall() = Graine.callsEngine().callsDismissMissed()

    /**
     * The call permissions as the phone holds them now: the microphone, and on
     * Android the ring's notifications (13+ asks) and the full-screen ring
     * (14+, Settings only); on iOS those two are NOT_NEEDED — CallKit rings.
     * Read again when the app returns to the foreground: the user may have
     * changed Settings. After [Graine.configure].
     */
    val callPermissions: GraineCallPermissions get() = Graine.callsEngine().callsPermissions()

    /**
     * Ask for the call permissions the system's dialog can still ask for — the
     * microphone, and notifications on Android 13+ — at a moment the app
     * chooses (onboarding, a "Call me" screen); Android asks over the app's
     * screen in front. Returns the permissions as they stand after. The
     * full-screen ring has no dialog: [openCallSettings] opens its page.
     */
    suspend fun requestCallPermissions(): GraineCallPermissions {
        val answer = CompletableDeferred<GraineCallPermissions>()
        Graine.callsEngine().callsRequestPermissions { answer.complete(it) }
        return answer.await()
    }

    /**
     * Open the Settings page that changes [item] — on Android the full-screen
     * ring's own page (14+) or the app's notification settings; otherwise the
     * app's page, where the microphone is. True when a page opened.
     */
    fun openCallSettings(item: GraineCallPermission): Boolean = Graine.callsEngine().callsOpenSettings(item)

    /**
     * What the app's setup still lacks for calls, read now — each a stable
     * code and a sentence with the exact fix. iOS: an Info.plist entry or
     * background mode, the push entitlement, [enableVoIPPushes] never called
     * or no VoIP token. Android: a manifest entry, no push token (Firebase
     * missing or not set up in the app, or [onNewToken] never called by an app
     * with its own messaging service), notifications or the full-screen ring
     * not allowed.
     * Both: the microphone blocked, Graine not ringing this phone. Empty when
     * nothing is missing. Calls still work where they can.
     */
    val callSetupProblems: List<GraineCallSetupProblem> get() = Graine.callsEngine().callsSetupProblems()

    /**
     * Android, only for an app with a FirebaseMessagingService of its own
     * (which outranks the SDK's and receives every token) — one line,
     * `override fun onNewToken(token: String) = Graine.calls.onNewToken(token)`.
     * Otherwise the SDK's own messaging service receives each new token, and
     * the SDK reads the current one at start. Kept and registered with Graine
     * when Graine rings this app. Works before [Graine.configure], as a
     * messaging service may start first.
     * iOS: does nothing — the VoIP token comes from PushKit ([enableVoIPPushes]).
     */
    fun onNewToken(token: String) = PlatformPush.onNewToken(token)

    /**
     * Android, only for an app with a FirebaseMessagingService of its own
     * (which outranks the SDK's and receives every message): hand every FCM
     * data message here first — `if (Graine.calls.handlePush(message.data)) return`.
     * Otherwise the SDK's own messaging service does it. True when it was a
     * call (Graine's own ring, or one your server sent), which the SDK rings;
     * false when it is one of your own messages.
     * iOS: always false — a VoIP push reaches PushKit, and the SDK, by itself.
     */
    fun handlePush(data: Map<String, String>): Boolean = PlatformPush.handlePush(data)
}
