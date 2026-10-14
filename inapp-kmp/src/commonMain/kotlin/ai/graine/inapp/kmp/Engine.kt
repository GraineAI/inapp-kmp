package ai.graine.inapp.kmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.Instant

/*
 * THE ONE SEAM between the shared code and a platform SDK. Each platform
 * implements GraineEngine by delegating to its native SDK (androidMain: the
 * Android SDK; iosMain: the iOS SDK's Objective-C facade), and reports back
 * through EngineSink. Everything above the seam — the flows, the scheduling
 * rules, error mapping, how actions are run — is shared and tested against a
 * fake engine in commonTest.
 *
 * Callbacks may arrive on any thread; the sink only touches thread-safe flows.
 */

/** What a platform SDK does for the shared code. */
internal interface GraineEngine {
    val installId: String
    /** The current user's memory choice (true until they turn it off). */
    val memoryEnabled: Boolean

    /** Start reporting to [sink]: the session, events, the chat, calls and the voice bar. Called once. */
    fun start(sink: EngineSink)

    fun identify(traits: Map<String, Any?>?, memory: Boolean?)
    fun setMemory(enabled: Boolean)
    fun forgetMemory(done: (Result<Int>) -> Unit)
    fun memories(done: (Result<List<GraineMemory>>) -> Unit)
    fun setVariables(values: Map<String, Any?>?)

    fun setScreen(screen: GraineScreen?)
    fun updateField(field: GraineField)
    fun removeField(name: String)
    fun clearScreen(screenId: String?)
    fun track(name: String, data: Map<String, Any?>)
    fun tap(name: String)
    fun noteInteraction()

    /**
     * Register [handler] for the agent's action [name]. The platform calls it
     * on the main thread; it calls `answer` once, from any thread. Returns
     * what unregisters it (this registration only).
     */
    fun registerAction(name: String, handler: (args: Map<String, Any?>, answer: (GraineActionResult) -> Unit) -> Unit): () -> Unit

    fun scheduleCall(at: Instant, reason: String?, done: (Result<GraineScheduledCall>) -> Unit)
    fun scheduledCalls(done: (Result<List<GraineScheduledCall>>) -> Unit)
    fun cancelScheduledCall(id: String, done: (Result<GraineCallStatus>) -> Unit)

    /** Whether this agent can be booked from the app now: the native SDK's own rule (BOOKING-SHEET.md §1). */
    val bookingAvailable: Boolean
    /** The native SDK's booking sheet: answers the booked call once it closes, or null when nothing was booked. */
    fun presentBooking(reason: String?, done: (Result<GraineScheduledCall?>) -> Unit)

    fun chatOpen(size: GraineChatSize?)
    fun chatClose()
    fun chatSend(text: String)
    fun chatRetry()
    /** A fresh thread: answers once it is done, or with Graine's refusal (the chat then as it was). */
    fun chatNewConversation(done: (Result<Unit>) -> Unit)

    fun callsConfigure(config: GraineCallsConfig)
    fun callsEnableVoIPPushes()
    fun callsStart(reason: String?, variables: Map<String, Any?>): String?
    fun callsAnswer()
    fun callsDecline()
    fun callsEnd()
    fun callsSetMuted(muted: Boolean)
    fun callsSetSpeaker(on: Boolean)
    fun callsCallBack(): String?
    fun callsDismissMissed()

    fun voiceStart()
    fun voiceEnd()
    fun voiceSetMuted(muted: Boolean)
}

/** Where a platform SDK reports. Any thread. */
internal interface EngineSink {
    fun session(session: GraineSession)
    /** The agent's configuration could not be loaded (a scheduling call stops waiting for it). */
    fun sessionFailed(message: String)
    fun event(event: GraineEvent)
    fun chat(turns: List<GraineChatTurn>, status: GraineChatStatus, typing: Boolean)
    fun calls(info: GraineCallInfo)
    fun callOutcome(outcome: GraineCallOutcome)
    fun incomingCall(call: GraineIncomingCall)
    fun voice(info: GraineVoiceInfo)
}

/** The platform's engine for the agent [name], over its native SDK. */
internal expect fun createPlatformEngine(config: GraineConfig, name: String): GraineEngine

/** The main thread, where actions run. Android: the main looper; iOS: the main queue. */
internal expect val graineMainDispatcher: CoroutineDispatcher

/** What a push service hands over before (or without) the SDK being configured. Android forwards; iOS has PushKit. */
internal expect object PlatformPush {
    fun onNewToken(token: String)
    fun handlePush(data: Map<String, String>): Boolean
}
