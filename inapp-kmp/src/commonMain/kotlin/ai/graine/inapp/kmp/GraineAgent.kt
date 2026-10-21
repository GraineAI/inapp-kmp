package ai.graine.inapp.kmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.concurrent.Volatile

/**
 * ONE AGENT — its own publishable key, and everything that belongs to it: its
 * session (design, components, actions, scheduling rules, memory setting), its
 * chat, its voice bar, the actions registered with it, and the scheduling and
 * memory requests, which go to its key.
 *
 * An app with several agents (a support agent in Help, a sales agent on Plans)
 * configures each under its own name and picks one per screen:
 *
 *     Graine.configure(GraineConfig(baseUrl, publishableKey = "pk_live_support…"), name = "support")
 *     val support = Graine.agent("support")!!
 *     support.chat.open()
 *     support.registerAction("open_ticket") { … }
 *     Graine.calls.startCall(reason = "billing", agent = "support")
 *
 * Who the user is, the install id, the memory choice and the screen are the
 * app's — shared by every agent (`Graine.identify`, `Graine.setScreen`). One
 * conversation at a time across all agents. `Graine` itself is the default
 * agent: everything on it that belongs to an agent is that agent's.
 */
class GraineAgent internal constructor(
    /** The name it was configured under; "default" for the default agent. */
    val name: String,
) {
    @Volatile
    private var current: GraineEngine? = null

    /** configure() is making this agent's engine (or made it): a second configure leaves it alone. */
    @Volatile
    internal var claimed = false

    internal val sessionState = MutableStateFlow<GraineSession?>(null)
    private val sessionFailure = MutableStateFlow<String?>(null)
    internal val eventFlow = MutableSharedFlow<GraineEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val chatTurns = MutableStateFlow<List<GraineChatTurn>>(emptyList())
    internal val chatStatus = MutableStateFlow(GraineChatStatus.IDLE)
    internal val chatFailed = MutableStateFlow(false)
    internal val chatTyping = MutableStateFlow(false)
    internal val chatUpcoming = MutableStateFlow<List<GraineScheduledCall>>(emptyList())
    internal val chatQuickReplies = MutableStateFlow<List<GraineQuickReply>>(emptyList())
    internal val chatEnded = MutableStateFlow(false)
    internal val chatRating = MutableStateFlow<GraineChatRating?>(null)
    internal val chatPinned = MutableStateFlow<GraineChatPin?>(null)
    internal val chatNewConversationBefore = MutableStateFlow<List<String>>(emptyList())
    internal val chatSessionId = MutableStateFlow<String?>(null)
    internal val chatSessions = MutableStateFlow<List<GraineChatSession>>(emptyList())
    internal val chatPastSessions = MutableStateFlow(GrainePastSessions())
    internal val voiceInfo = MutableStateFlow(GraineVoiceInfo())
    internal val themeState = MutableStateFlow<GraineThemeTokens?>(null)
    internal val componentState = MutableStateFlow<GraineComponent?>(null)
    internal val componentPendingState = MutableStateFlow(false)
    /** Lets a loader that has stood too long go, as the SDK's renderer does. */
    @Volatile
    private var pendingExpiry: Job? = null

    /** The app's [GraineChat.onRated]. */
    @Volatile
    internal var ratedHandler: ((GraineChatRating) -> Unit)? = null

    /** The app's [GraineChat.onAttach]: kept here, so one set before configure() reaches the engine when it is made. */
    @Volatile
    internal var attachHandler: (() -> Unit)? = null
        private set

    internal fun setAttachHandler(handler: (() -> Unit)?) {
        attachHandler = handler
        current?.chatSetAttachHandler(handler)
    }

    /** This agent's configuration, once it has loaded; null before. */
    val session: StateFlow<GraineSession?> = sessionState.asStateFlow()

    /**
     * THIS AGENT'S DESIGN TOKENS, for UI of your own — the brand's colours,
     * type, corners, depth, motion and icons, for the device's light or dark
     * appearance (or the brand's fixed scheme), changing when the device
     * switches. Null before the session loads, and when it carries no design.
     * On iOS it needs GraineInApp of this version.
     */
    val theme: StateFlow<GraineThemeTokens?> = themeState.asStateFlow()

    /** Everything this agent reports that is not a state. Not replayed. */
    val events: SharedFlow<GraineEvent> = eventFlow.asSharedFlow()

    /**
     * THE COMPONENT ON SCREEN — the one the agent drew that waits for an
     * answer, in the chat or on the voice bar's call, as the SDK's renderer
     * holds it: its `id`, `kind`, `data` and `fallbackText`. Null once it is
     * answered (`chat.answerWidget`), closed (`chat.dismissWidget`), replaced
     * by the next, or its conversation is over — and before any. For a UI of
     * your own; [GraineEvent.Component] reports each one as it comes.
     */
    val component: StateFlow<GraineComponent?> = componentState.asStateFlow()

    /**
     * The agent asked for a component and its content is loading — draw a
     * loader where it will appear. False once it arrives, its conversation is
     * over, or after 12 seconds with nothing, as the SDK's renderer gives up.
     */
    val componentPending: StateFlow<Boolean> = componentPendingState.asStateFlow()

    /** The typed conversation with this agent. */
    val chat: GraineChat = GraineChat(this)

    /** This agent's voice bar call. */
    val voice: GraineVoice = GraineVoice(this)

    /** What this agent allows when a user books a call: its session's rules, or the defaults (no booking) before it loads. */
    val schedulingRules: GraineSchedulingRules get() = sessionState.value?.schedulingRules ?: GraineSchedulingRules()

    /** Whether this agent has been configured. */
    val isConfigured: Boolean get() = current != null

    internal fun install(engine: GraineEngine) {
        claimed = true
        current = engine
        engine.start(sink)
        attachHandler?.let { engine.chatSetAttachHandler(it) }
    }

    internal fun engineOrNull(): GraineEngine? = current

    internal fun engine(): GraineEngine = current ?: throw IllegalStateException(
        if (name == Graine.DEFAULT_AGENT) "Graine.configure(GraineConfig(…)) has not been called. Call it once, at launch."
        else "The agent \"$name\" is not configured: call Graine.configure(config, name = \"$name\") first."
    )

    /** Prompt variables from the app's own data, for this agent. Merged; null under a key removes it, null clears all. */
    fun setVariables(values: Map<String, Any?>?) = engine().setVariables(values)

    /**
     * EVERY VARIABLE THIS AGENT WILL RECEIVE, merged in precedence order: the
     * dashboard's defaults, the config's `variables`, [setVariables], then who
     * is signed in (`identify`, its identity trait withheld) — what the next
     * conversation carries, masked as it leaves the device. A copy. On iOS it
     * needs GraineInApp of this version (an older one answers empty).
     */
    fun currentVariables(): Map<String, String> = engine().currentVariables()

    /**
     * Something this agent can do in your app. Declare it on the agent in the
     * dashboard too — a handler with no declaration is never offered, and a
     * declaration with no handler is refused mid-sentence.
     *
     * The handler starts on the main thread (it will usually touch UI) and must
     * answer within six seconds, or the agent is told it was not done. It may
     * suspend — a network call, a confirmation the user taps — and continues on
     * the main thread. A handler that throws answers `error` with its message.
     * Registering a name again replaces the handler. Returns what unregisters it.
     */
    fun registerAction(name: String, handler: suspend (args: Map<String, Any?>) -> GraineActionResult): () -> Unit =
        engine().registerAction(name) { args, answer ->
            // Undispatched: a handler that never suspends answers right here, inside the platform's call.
            Graine.actionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val result = try {
                    handler(args)
                } catch (e: CancellationException) {
                    GraineActionResult.error("The action was cancelled.")
                } catch (e: Throwable) {
                    GraineActionResult.error(e.message ?: (e::class.simpleName ?: "The action failed."))
                }
                answer(result)
            }
        }

    /**
     * Forget everything this agent remembers about the signed-in user. Only
     * from a device that user is using. Returns how many memories were deleted.
     * Throws [GraineException]: `identity_required` with nobody signed in,
     * `device_limit` from a device that is not one of theirs.
     */
    suspend fun forgetMemory(): Int = await { e, done -> e.forgetMemory(done) }

    /**
     * What this agent remembers about the signed-in user, newest first (at most
     * 100), already redacted — to show people what is kept about them. Throws
     * [GraineException]: `identity_required`, `device_limit`, `network`…
     */
    suspend fun memories(): List<GraineMemory> = await { e, done -> e.memories(done) }

    /**
     * Book a call from this agent for the signed-in user (or, with nobody
     * signed in, this install) at [at] — "call me tomorrow at 6". Graine rings
     * this device then. [reason] (at most 120 characters) is kept with the call
     * for the agent, never put in the push that rings it.
     *
     * Checked here first, against [schedulingRules], before anything is sent:
     * `scheduling_off` when users may not book, `too_soon` before `earliest()`,
     * `too_far` after `latest()`. Then the server's own: `outside_window` (the
     * agent's calling hours, in this device's time zone), `too_many_pending`,
     * `identity_required`, `rate_limited`… — every refusal a [GraineException].
     */
    suspend fun scheduleCall(at: Instant, reason: String? = null): GraineScheduledCall {
        val engine = engine()
        val session = awaitSession()
        // With no session at all (it could not load), the platform SDK and the server decide.
        if (session != null) Scheduling.check(at, Clock.System.now(), session.schedulingRules)?.let { throw it }
        val why = reason?.trim()?.take(Scheduling.REASON_MAX)?.takeIf { it.isNotEmpty() }
        return await { _, done -> engine.scheduleCall(at, why, done) }
    }

    /** The calls this agent has booked for the signed-in user (or this install) that are still to come, soonest first. */
    suspend fun scheduledCalls(): List<GraineScheduledCall> = await { e, done -> e.scheduledCalls(done) }

    /**
     * Whether this agent can be booked from the app now — what the SDK's own
     * "Book a call" follows, and what your own button should: Graine rings
     * this phone (the workspace's In-app calling, for this platform), the
     * agent lets users book, and the user is identified with the trait the
     * agent's Identity setting names. The native SDK's rule, read as it is
     * now; false before configure(). Read it again after `identify` and once
     * the session has loaded.
     */
    val bookingAvailable: Boolean get() = current?.bookingAvailable ?: false

    /**
     * Why this agent cannot be booked from the app now — the first part of
     * [bookingAvailable]'s rule that fails, as a word: `not_loaded` (its
     * settings have not loaded yet, or before configure()), `rings_not_set_up`
     * (Graine does not ring this phone), `scheduling_off` (the agent does not
     * let users book), `not_identified` (nobody is identified with the trait
     * the agent's Identity setting names). Null when it can be booked. On iOS
     * it needs the GraineInApp of this version (an older one answers
     * `not_loaded` until booking is available).
     */
    val bookingUnavailableReason: String?
        get() {
            val engine = current ?: return NOT_LOADED
            return engine.bookingUnavailableReason
        }

    /**
     * The times the SDK's booking sheet would offer on [day], in the phone's
     * time zone — every 5 minutes from the soonest the agent's rules allow
     * ([GraineSchedulingRules.earliest], rounded up) to the latest, oldest
     * first — and, when the agent has a calling window
     * ([GraineSchedulingRules.callingWindow]), only the times inside it, on the
     * phone's clock — for a picker of your own; book one with [scheduleCall].
     * Empty for a day outside the rules, and before configure(). Graine checks
     * the time again when it is booked, with what only it knows (the calls
     * already booked). On iOS it needs the GraineInApp of this version.
     */
    fun bookingSlots(day: LocalDate): List<Instant> = current?.bookingSlots(day.toString()) ?: emptyList()

    /**
     * Show the SDK's own booking sheet, drawn by the native SDK in this
     * agent's design: the day, the time, the phone's time zone, "What's it
     * about? (optional)" — which [reason] fills — and Book call. Returns the
     * booked call once the sheet closes, or null when the user closed it
     * without booking; a refusal stays in the sheet as one line. Android: over
     * the activity in front (null when there is none); iOS: over the key
     * window's top view controller. Check [bookingAvailable] before offering it.
     */
    suspend fun presentBooking(reason: String? = null): GraineScheduledCall? = await { e, done -> e.presentBooking(reason, done) }

    /** Call one off — only one booked for this user or install. Returns its status afterwards: cancelled. `not_found` for any other. */
    suspend fun cancelScheduledCall(id: String): GraineCallStatus {
        if (id.isBlank()) throw GraineException(GraineErrorCode.InvalidRequest, "No call id.")
        return await { e, done -> e.cancelScheduledCall(id, done) }
    }

    /** A fresh thread in this agent's chat ([GraineChat.newConversation]). */
    internal suspend fun newConversation(): Unit = await { e, done -> e.chatNewConversation(done) }

    /** One past session's transcript ([GraineChat.sessionTranscript]). */
    internal suspend fun sessionTranscript(id: String): GraineSessionTranscript {
        val wanted = id.trim()
        if (wanted.isEmpty()) throw GraineException(GraineErrorCode.InvalidRequest, "No session id.")
        return await { e, done -> e.chatSessionTranscript(wanted, done) }
    }

    /** The component [id] was answered or closed: it is no longer on screen. */
    internal fun componentDone(id: String) {
        if (componentState.value?.id == id) componentState.value = null
    }

    /** The component state as the conversation moves: one arrives, one is on its way, the conversation is over. */
    private fun componentHeard(event: GraineEvent) {
        when (event) {
            is GraineEvent.Component -> {
                pendingExpiry?.cancel()
                componentPendingState.value = false
                componentState.value = event.component
            }
            is GraineEvent.ComponentPending -> {
                componentPendingState.value = true
                pendingExpiry?.cancel()
                pendingExpiry = Graine.actionScope.launch {
                    delay(PENDING_COMPONENT_MS)
                    componentPendingState.value = false
                }
            }
            GraineEvent.VoiceEnded -> componentGone()
            else -> Unit
        }
    }

    /** Nothing from the conversation that ended can be answered: no component, no loader. */
    private fun componentGone() {
        pendingExpiry?.cancel()
        componentPendingState.value = false
        componentState.value = null
    }

    /** The user calls this agent: `Graine.calls.startCall(reason, variables, agent = name)`. */
    fun startCall(reason: String? = null, variables: Map<String, Any?> = emptyMap()): String? =
        Graine.calls.startCall(reason, variables, agent = name)

    /** The user's next call to this agent, ready before they tap: `Graine.calls.prepareCall(reason, variables, agent = name)`. */
    fun prepareCall(reason: String? = null, variables: Map<String, Any?> = emptyMap()) =
        Graine.calls.prepareCall(reason, variables, agent = name)

    // ── plumbing ──────────────────────────────────────────────────────────

    /** The session, waited for — at most [Graine.SESSION_WAIT_MS], and no longer once it is known not to be coming. */
    internal suspend fun awaitSession(): GraineSession? {
        sessionState.value?.let { return it }
        return withTimeoutOrNull(Graine.SESSION_WAIT_MS) {
            combine(sessionState, sessionFailure) { s, failed -> s to failed }
                .first { (s, failed) -> s != null || failed != null }
                .first
        }
    }

    /** One platform request, as a suspending call: its value, or its GraineException. */
    private suspend fun <T> await(call: (GraineEngine, (Result<T>) -> Unit) -> Unit): T {
        val engine = engine()
        val answer = CompletableDeferred<T>()
        // A platform that answers twice is heard once: complete() keeps the first.
        call(engine) { result -> result.fold({ answer.complete(it) }, { answer.completeExceptionally(it) }) }
        return answer.await()
    }

    private val sink = object : EngineSink {
        override fun session(session: GraineSession) {
            sessionState.value = session
            sessionFailure.value = null
            eventFlow.tryEmit(GraineEvent.SessionReady(session))
        }

        override fun sessionFailed(message: String) {
            if (sessionState.value == null) sessionFailure.value = message
        }

        override fun event(event: GraineEvent) {
            componentHeard(event)
            eventFlow.tryEmit(event)
        }

        override fun chat(turns: List<GraineChatTurn>, status: GraineChatStatus, typing: Boolean) {
            // A chat that connects again is a new conversation on the server: a component from the last cannot be answered.
            if (status == GraineChatStatus.ONLINE && chatStatus.value != GraineChatStatus.ONLINE) componentGone()
            chatTurns.value = turns
            chatStatus.value = status
            chatTyping.value = typing
            chatSessions.value = ChatSessions.of(turns, chatSessionId.value)
        }

        override fun chatExtras(extras: ChatExtras) {
            val before = chatRating.value
            chatQuickReplies.value = extras.quickReplies
            chatEnded.value = extras.ended
            chatRating.value = extras.rating
            chatPinned.value = extras.pinned
            chatNewConversationBefore.value = extras.newConversationBefore
            chatFailed.value = extras.failed
            if (chatSessionId.value != extras.sessionId) {
                chatSessionId.value = extras.sessionId
                chatSessions.value = ChatSessions.of(chatTurns.value, extras.sessionId)
            }
            // A rating given — in the chat window or through rate() — is heard once; the platform repeats its state.
            val rated = extras.rating
            if (rated != null && rated != before) ratedHandler?.let { runCatching { it(rated) } }
        }

        override fun upcomingCalls(calls: List<GraineScheduledCall>) {
            chatUpcoming.value = calls
        }

        override fun pastSessions(state: GrainePastSessions) {
            chatPastSessions.value = state
        }

        // The phone has one call engine: one agent reports it for the app (Graine.calls).
        override fun calls(info: GraineCallInfo) = Graine.callsReported(this@GraineAgent) { Graine.callInfo.value = info }
        override fun callOutcome(outcome: GraineCallOutcome) = Graine.callsReported(this@GraineAgent) { Graine.callOutcomes.tryEmit(outcome) }
        override fun incomingCall(call: GraineIncomingCall) = Graine.callsReported(this@GraineAgent) { Graine.incomingCalls.tryEmit(call) }

        override fun voice(info: GraineVoiceInfo) {
            voiceInfo.value = info
        }

        override fun theme(tokens: GraineThemeTokens?) {
            themeState.value = tokens
        }
    }

    /** Back to before configure(): tests only. */
    internal fun reset() {
        current = null
        claimed = false
        sessionState.value = null
        sessionFailure.value = null
        chatTurns.value = emptyList()
        chatStatus.value = GraineChatStatus.IDLE
        chatFailed.value = false
        chatTyping.value = false
        chatUpcoming.value = emptyList()
        chatQuickReplies.value = emptyList()
        chatEnded.value = false
        chatRating.value = null
        chatPinned.value = null
        chatNewConversationBefore.value = emptyList()
        chatSessionId.value = null
        chatSessions.value = emptyList()
        chatPastSessions.value = GrainePastSessions()
        ratedHandler = null
        attachHandler = null
        voiceInfo.value = GraineVoiceInfo()
        themeState.value = null
        componentGone()
    }

    internal companion object {
        /** [bookingUnavailableReason] before the agent's settings are known. */
        const val NOT_LOADED = "not_loaded"
        /** How long a component's loader may stand with nothing arriving — the SDK's renderer's 12 seconds. */
        const val PENDING_COMPONENT_MS = 12_000L
    }
}

/** A lock for the little state the shared code keeps across threads (which agents exist). */
internal expect class GraineLock() {
    fun <T> locked(block: () -> T): T
}
