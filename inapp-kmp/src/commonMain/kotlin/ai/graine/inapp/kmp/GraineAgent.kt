package ai.graine.inapp.kmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
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
    internal val chatTyping = MutableStateFlow(false)
    internal val voiceInfo = MutableStateFlow(GraineVoiceInfo())

    /** This agent's configuration, once it has loaded; null before. */
    val session: StateFlow<GraineSession?> = sessionState.asStateFlow()

    /** Everything this agent reports that is not a state. Not replayed. */
    val events: SharedFlow<GraineEvent> = eventFlow.asSharedFlow()

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
    }

    internal fun engineOrNull(): GraineEngine? = current

    internal fun engine(): GraineEngine = current ?: throw IllegalStateException(
        if (name == Graine.DEFAULT_AGENT) "Graine.configure(GraineConfig(…)) has not been called. Call it once, at launch."
        else "The agent \"$name\" is not configured: call Graine.configure(config, name = \"$name\") first."
    )

    /** Prompt variables from the app's own data, for this agent. Merged; null under a key removes it, null clears all. */
    fun setVariables(values: Map<String, Any?>?) = engine().setVariables(values)

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

    /** The user calls this agent: `Graine.calls.startCall(reason, variables, agent = name)`. */
    fun startCall(reason: String? = null, variables: Map<String, Any?> = emptyMap()): String? =
        Graine.calls.startCall(reason, variables, agent = name)

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
            eventFlow.tryEmit(event)
        }

        override fun chat(turns: List<GraineChatTurn>, status: GraineChatStatus, typing: Boolean) {
            chatTurns.value = turns
            chatStatus.value = status
            chatTyping.value = typing
        }

        // The phone has one call engine: one agent reports it for the app (Graine.calls).
        override fun calls(info: GraineCallInfo) = Graine.callsReported(this@GraineAgent) { Graine.callInfo.value = info }
        override fun callOutcome(outcome: GraineCallOutcome) = Graine.callsReported(this@GraineAgent) { Graine.callOutcomes.tryEmit(outcome) }
        override fun incomingCall(call: GraineIncomingCall) = Graine.callsReported(this@GraineAgent) { Graine.incomingCalls.tryEmit(call) }

        override fun voice(info: GraineVoiceInfo) {
            voiceInfo.value = info
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
        chatTyping.value = false
        voiceInfo.value = GraineVoiceInfo()
    }
}

/** A lock for the little state the shared code keeps across threads (which agents exist). */
internal expect class GraineLock() {
    fun <T> locked(block: () -> T): T
}
