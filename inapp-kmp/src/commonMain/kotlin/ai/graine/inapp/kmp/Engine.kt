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

    /** Who is signed in, and the identity token that proves it (null: none given — the same user keeps theirs). */
    fun identify(traits: Map<String, Any?>?, memory: Boolean?, identityToken: String?)
    fun setMemory(enabled: Boolean)

    // Your app and your users (0.14.4): the platform SDK sends the declaration, proves the app and carries the token.
    /** Replace the identity token for the user identified now, without changing who it is; null clears it. */
    fun setIdentityToken(token: String?)
    /** The app's refresher in callback form ([IdentityTokens.bridge]): answered once, with a token or null (keep the one held). Null removes it. */
    fun setIdentityTokenRefresher(refresher: ((done: (String?) -> Unit) -> Unit)?)
    /** Prove the app now, for this agent: [done] hears the verdict, once. */
    fun prepareAppVerification(done: (GraineAppVerdict) -> Unit)
    /** What Graine has proven for this agent, as the platform SDK holds it now. */
    val verification: GraineVerification
    /** Development mode, as the platform SDK holds it. */
    var debugTokenMode: GraineDebugTokenMode
    /** This install's debug token while the build uses one, else null. */
    val debugToken: String?
    fun forgetMemory(done: (Result<Int>) -> Unit)
    fun memories(done: (Result<List<GraineMemory>>) -> Unit)
    fun setVariables(values: Map<String, Any?>?)
    /** Every variable the agent will receive, merged in the platform SDK's precedence order. */
    fun currentVariables(): Map<String, String>

    fun setScreen(screen: GraineScreen?)
    fun updateField(field: GraineField)
    fun removeField(name: String)
    fun clearScreen(screenId: String?)
    fun track(name: String, data: Map<String, Any?>)
    /** Something worth speaking up about, on the live chat or call: the platform SDK's `reportEvent`. */
    fun reportEvent(name: String, detail: String?, data: Map<String, Any?>)
    fun tap(name: String)
    fun noteInteraction()

    /**
     * Register [handler] for the agent's action [name]. The platform calls it
     * on the main thread; it calls `answer` once, from any thread. Returns
     * what unregisters it (this registration only).
     */
    fun registerAction(name: String, handler: (args: Map<String, Any?>, answer: (GraineActionResult) -> Unit) -> Unit): () -> Unit

    fun scheduleCall(at: Instant, reason: String?, done: (Result<GraineScheduledCall>) -> Unit)
    /** The platform SDK's `startWorkflow` (0.14.4): its checks, its key and its one retry are the platform's. */
    fun startWorkflow(workflow: String, data: Map<String, Any?>, idempotencyKey: String?, done: (Result<GraineWorkflowRun>) -> Unit)
    fun scheduledCalls(done: (Result<List<GraineScheduledCall>>) -> Unit)
    fun cancelScheduledCall(id: String, done: (Result<GraineCallStatus>) -> Unit)

    /** Whether this agent can be booked from the app now: the native SDK's own rule (BOOKING-SHEET.md §1). */
    val bookingAvailable: Boolean
    /** Why not, as a word (`not_loaded`, `rings_not_set_up`, `scheduling_off`, `not_identified`); null when it can be. */
    val bookingUnavailableReason: String?
    /** The times the native SDK's booking sheet offers on [isoDay] (`YYYY-MM-DD`, the phone's zone), oldest first. */
    fun bookingSlots(isoDay: String): List<Instant>
    /** The native SDK's booking sheet: answers the booked call once it closes, or null when nothing was booked. */
    fun presentBooking(reason: String?, done: (Result<GraineScheduledCall?>) -> Unit)

    fun chatOpen(size: GraineChatSize?)
    fun chatClose()
    fun chatSend(text: String)
    fun chatRetry()
    /** Empty the chat on screen; the conversation and what waits to be sent go on. */
    fun chatClear()
    /** Fetch the chat's upcoming calls again. */
    fun chatRefreshUpcoming()
    /** Cancel one of the chat's upcoming calls; it leaves the list once Graine has called it off. */
    fun chatCancelUpcoming(id: String)
    /** Answer a component the agent drew, on the conversation that is live. */
    fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String)
    /** Close a component the agent drew without answering it: the agent is told quietly where the platform SDK can tell it. */
    fun dismissWidget(widgetId: String)
    /** A chat screen of the app's own came on screen or left it: the conversation lives as the SDK's own windows' does. */
    fun chatShown(visible: Boolean)
    /** A fresh thread: answers once it is done, or with Graine's refusal (the chat then as it was). */
    fun chatNewConversation(done: (Result<Unit>) -> Unit)
    /** Pin [pin] (already cleaned: [ChatPins]) to the chat window; null takes it away. */
    fun chatPin(pin: GraineChatPin?)
    /** The user's rating of a conversation the agent ended, from the app's own UI: the platform SDK's rule decides. */
    fun chatRate(style: String, value: Int)
    /** One of the agent's quick replies, sent as the user's own message. */
    fun chatSendQuickReply(reply: GraineQuickReply)
    /** The app's attachment handler (main thread), or null: the chat window draws its paperclip only with one. */
    fun chatSetAttachHandler(handler: (() -> Unit)?)
    /** Load the first page of the user's past sessions, replacing what was loaded ([GraineChat.refreshPastSessions]). */
    fun chatRefreshPastSessions()
    /** Load the next page of past sessions and append it ([GraineChat.loadMorePastSessions]). */
    fun chatLoadMorePastSessions()
    /** One past session's transcript, or Graine's refusal ([GraineChat.sessionTranscript]). */
    fun chatSessionTranscript(id: String, done: (Result<GraineSessionTranscript>) -> Unit)
    /**
     * How long the chat stays connected once no window shows it, in seconds
     * (already kept within 0–300): the platform SDK's own value, read and set
     * ([GraineChat.closeDelaySeconds]).
     */
    var chatCloseDelaySeconds: Int

    fun callsConfigure(config: GraineCallsConfig)
    fun callsEnableVoIPPushes()
    fun callsStart(reason: String?, variables: Map<String, Any?>): String?
    /** The user's next call to this agent, ready before the tap; nothing is dialled. A platform SDK without it does nothing. */
    fun callsPrepare(reason: String?, variables: Map<String, Any?>)
    fun callsAnswer()
    fun callsDecline()
    fun callsEnd()
    fun callsSetMuted(muted: Boolean)
    fun callsSetSpeaker(on: Boolean)
    /** Calls start on the loudspeaker unless a headset is connected ([GraineCalls.speakerByDefault]). A platform without it does nothing. */
    fun callsSetSpeakerByDefault(on: Boolean)
    fun callsCallBack(): String?
    fun callsDismissMissed()
    /** The call permissions as the phone holds them now. */
    fun callsPermissions(): GraineCallPermissions
    /** Ask for what the system's dialog can still ask for; [done] gets the permissions after, once. */
    fun callsRequestPermissions(done: (GraineCallPermissions) -> Unit)
    /** The Settings page that changes [item]: true when one opened. */
    fun callsOpenSettings(item: GraineCallPermission): Boolean
    /** What the app's setup still lacks for calls. */
    fun callsSetupProblems(): List<GraineCallSetupProblem>

    fun voiceStart()
    fun voiceEnd()
    fun voiceSetMuted(muted: Boolean)
    /** The captions switch: the platform's bar hides its caption while it is off. */
    fun voiceSetCaptionsOn(on: Boolean)
    /** Forget what has been said on the call, without ending it. */
    fun voiceClearCaptions()
}

/** Where a platform SDK reports. Any thread. */
internal interface EngineSink {
    fun session(session: GraineSession)
    /** The agent's configuration could not be loaded (a scheduling call stops waiting for it). */
    fun sessionFailed(message: String)
    fun event(event: GraineEvent)
    fun chat(turns: List<GraineChatTurn>, status: GraineChatStatus, typing: Boolean)
    /** The chat window v2 state beside the turns. A platform SDK that has none never reports it: the defaults stand. */
    fun chatExtras(extras: ChatExtras) {}
    /** The chat's upcoming calls (its card's), soonest first. */
    fun upcomingCalls(calls: List<GraineScheduledCall>)
    /** The user's past sessions as the platform SDK holds them. A platform SDK that has none never reports them: the empty state stands. */
    fun pastSessions(state: GrainePastSessions) {}
    fun calls(info: GraineCallInfo)
    fun callOutcome(outcome: GraineCallOutcome)
    fun incomingCall(call: GraineIncomingCall)
    fun voice(info: GraineVoiceInfo)
    /** The brand's design tokens for the device's appearance now; null before the session and without a design. */
    fun theme(tokens: GraineThemeTokens?)
    /** What Graine has proven for this agent changed. A platform SDK without it never reports: off and off stand. */
    fun verification(verification: GraineVerification) {}
}

/**
 * The chat's state beside its turns (chat window v2): the replies the agent
 * suggests for its latest turn, whether it ended the conversation, the user's
 * rating of it, the card the app pinned, and where a new conversation began
 * under earlier lines — as the platform SDK holds them.
 */
internal data class ChatExtras(
    val quickReplies: List<GraineQuickReply> = emptyList(),
    val ended: Boolean = false,
    val rating: GraineChatRating? = null,
    val pinned: GraineChatPin? = null,
    /** The turns a "Disconnected · new conversation" row goes before, oldest first. */
    val newConversationBefore: List<String> = emptyList(),
    /** The chat's current session's id; null until Graine names it, and from a platform SDK that predates it. */
    val sessionId: String? = null,
    /** The chat gave up: every retry spent, nothing connecting it until the user acts. */
    val failed: Boolean = false,
)

/** The platform's engine for the agent [name], over its native SDK. */
internal expect fun createPlatformEngine(config: GraineConfig, name: String): GraineEngine

/** The main thread, where actions run. Android: the main looper; iOS: the main queue. */
internal expect val graineMainDispatcher: CoroutineDispatcher

/** What a push service hands over before (or without) the SDK being configured. Android forwards; iOS has PushKit. */
internal expect object PlatformPush {
    fun onNewToken(token: String)
    fun handlePush(data: Map<String, String>): Boolean
}
