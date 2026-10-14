package ai.graine.inapp.kmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant
import kotlin.concurrent.Volatile

/**
 * THE GRAINE IN-APP AGENT, FOR KOTLIN MULTIPLATFORM — one API in shared code,
 * on Android and iOS, over Graine's native SDKs.
 *
 *     Graine.configure(GraineConfig(baseUrl = "https://www.graine.ai", publishableKey = "pk_live_…"))
 *     Graine.identify(mapOf("user_id" to user.id, "name" to user.firstName))
 *     Graine.setScreen(GraineScreen("kyc_documents", title = "Upload your documents", fields = listOf(…)))
 *     Graine.registerAction("open_screen") { args -> router.open(args["screen"]); GraineActionResult.ok("Opened.") }
 *     Graine.chat.open()                       // or GraineChat() from inapp-kmp-compose
 *     Graine.calls.startCall(reason = "kyc_help")
 *
 * `Graine` is the default agent, plus what the app has once: who the user is,
 * the install id, the memory choice, the screen and the phone's calls. An app
 * with several agents configures each by name ([configure], [agent]).
 *
 * Configured once at launch. Every method may be called from any thread;
 * suspending ones never block the main thread. Calling anything but
 * [configure] before it throws IllegalStateException.
 */
object Graine {
    /** The default agent's name. */
    const val DEFAULT_AGENT = "default"

    // The phone's calls are the app's, whichever agent a call is with. Declared first: the APIs below read them.
    internal val callInfo = MutableStateFlow(GraineCallInfo())
    internal val callOutcomes = MutableSharedFlow<GraineCallOutcome>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val incomingCalls = MutableSharedFlow<GraineIncomingCall>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val lock = GraineLock()
    /** Every agent asked for, configured or not, in the order they were first named. */
    private val holders = LinkedHashMap<String, GraineAgent>()
    /** The agent configured first: it reports the phone's one call engine. */
    @Volatile private var callsReporter: GraineAgent? = null

    /** The default agent. Its flows exist before it is configured, so they can be collected at once. */
    private val defaultAgent: GraineAgent = holder(DEFAULT_AGENT)

    /** Where suspending action handlers continue after they suspend. The main thread; tests hand in their own. */
    internal var actionDispatcher: CoroutineDispatcher = graineMainDispatcher
        set(value) { field = value; actionScope = CoroutineScope(SupervisorJob() + value) }
    internal var actionScope = CoroutineScope(SupervisorJob() + actionDispatcher)
        private set

    // ── the agents ────────────────────────────────────────────────────────

    /**
     * Start an agent. Call at launch — Application.onCreate on Android (the SDK
     * finds the application itself), the app's init on iOS. Without a name it
     * is the default agent, which everything on `Graine` belongs to; an app
     * with several agents configures each under its own name and key. A second
     * call for a name already configured returns that agent unchanged.
     */
    fun configure(config: GraineConfig, name: String = DEFAULT_AGENT): GraineAgent {
        val agent = holder(normalise(name))
        // Claimed under the lock, made outside it: the platform SDK is never called holding it.
        val mine = lock.locked { if (agent.claimed) false else { agent.claimed = true; true } }
        if (mine) install(agent, createPlatformEngine(config, agent.name))
        return agent
    }

    /** The agent configured under [name]; null when it is not configured. */
    fun agent(name: String = DEFAULT_AGENT): GraineAgent? = lock.locked { holders[normalise(name)] }?.takeIf { it.isConfigured }

    /** Every configured agent, by name, in the order they were configured. */
    val agents: Map<String, GraineAgent> get() = lock.locked { holders.filterValues { it.isConfigured } }

    /** Whether the default agent is configured. */
    val isConfigured: Boolean get() = defaultAgent.isConfigured

    /** The engine for an agent; tests install fakes. */
    internal fun install(agent: GraineAgent, engine: GraineEngine) {
        if (callsReporter == null) callsReporter = agent
        agent.install(engine)
    }

    internal fun install(engine: GraineEngine, name: String = DEFAULT_AGENT) = install(holder(normalise(name)), engine)

    internal fun holder(name: String): GraineAgent = lock.locked { holders.getOrPut(name) { GraineAgent(name) } }

    /** A name as it is kept: trimmed; empty is the default. */
    internal fun normalise(name: String): String = name.trim().ifEmpty { DEFAULT_AGENT }

    /**
     * What the app has once goes to the default agent, else the first
     * configured. Who the user is, their memory choice and the screen too: the
     * platform SDK gives every agent the same ones, so they are told once.
     */
    private fun primary(): GraineAgent = lock.locked { holders.values.firstOrNull { it.name == DEFAULT_AGENT && it.isConfigured } ?: holders.values.firstOrNull { it.isConfigured } }
        ?: defaultAgent

    /** Run [report] only for the agent that reports the phone's calls. */
    internal fun callsReported(from: GraineAgent, report: () -> Unit) {
        if (callsReporter == null || callsReporter === from) report()
    }

    internal fun callsEngine(): GraineEngine = (callsReporter ?: primary()).engine()

    internal fun engineFor(agent: String?): GraineEngine = (agent?.let { holder(normalise(it)) } ?: primary()).engine()

    // ── the default agent ─────────────────────────────────────────────────

    /** The default agent's configuration, once it has loaded; null before. */
    val session: StateFlow<GraineSession?> get() = defaultAgent.session

    /** Everything the default agent reports that is not a state. Not replayed. */
    val events: SharedFlow<GraineEvent> get() = defaultAgent.events

    /** The typed conversation with the default agent. */
    val chat: GraineChat get() = defaultAgent.chat

    /** The default agent's voice bar call. */
    val voice: GraineVoice get() = defaultAgent.voice

    /** What the default agent allows when a user books a call. */
    val schedulingRules: GraineSchedulingRules get() = defaultAgent.schedulingRules

    /** Native calls — the app's, whichever agent a call is with. */
    val calls: GraineCalls = GraineCalls()

    /** The default agent's prompt variables. See [GraineAgent.setVariables]. */
    fun setVariables(values: Map<String, Any?>?) = defaultAgent.setVariables(values)

    /** An action for the default agent. See [GraineAgent.registerAction]. */
    fun registerAction(name: String, handler: suspend (args: Map<String, Any?>) -> GraineActionResult): () -> Unit =
        defaultAgent.registerAction(name, handler)

    /** Forget what the default agent remembers about the signed-in user. See [GraineAgent.forgetMemory]. */
    suspend fun forgetMemory(): Int = defaultAgent.forgetMemory()

    /** What the default agent remembers about the signed-in user. See [GraineAgent.memories]. */
    suspend fun memories(): List<GraineMemory> = defaultAgent.memories()

    /** Book a call from the default agent. See [GraineAgent.scheduleCall]: the agent's rules are checked first. */
    suspend fun scheduleCall(at: Instant, reason: String? = null): GraineScheduledCall = defaultAgent.scheduleCall(at, reason)

    /** The default agent's booked calls still to come. */
    suspend fun scheduledCalls(): List<GraineScheduledCall> = defaultAgent.scheduledCalls()

    /** Call off one of the default agent's booked calls. Returns its status afterwards: cancelled. */
    suspend fun cancelScheduledCall(id: String): GraineCallStatus = defaultAgent.cancelScheduledCall(id)

    // ── what the app has once: who, and what is on the screen ─────────────

    /**
     * This install's id: a random UUID made on first launch and kept for as
     * long as the app is installed. Graine counts a user's devices by it; the
     * agent never sees it. The same id the native SDKs use, for every agent.
     */
    val installId: String get() = primary().engine().installId

    /** Whether the current user's conversations are remembered: their last choice on this install, else true. */
    val memoryEnabled: Boolean get() = primary().engine().memoryEnabled

    /**
     * Who is signed in — for every agent. Traits become prompt variables,
     * masked on the device. The trait the agent's Identity setting names
     * (`user_id` unless the dashboard chose `email`, `phone` or one of yours)
     * is how Graine tells your users apart — what lets the agent remember a
     * user and ring them — and its value travels only to Graine, never into
     * the prompt.
     *
     * `memory = false`: this user's conversations are neither remembered nor
     * recalled; kept on this install for them. Null keeps their last choice.
     * Null traits sign out everywhere: this device stops being theirs, and later
     * conversations are anonymous. A change applies to the next conversation.
     */
    fun identify(traits: Map<String, Any?>?, memory: Boolean? = null) = primary().engine().identify(traits, memory)

    /** Remember the current user's conversations, or stop — `identify(the same traits, memory = enabled)`. Every agent. */
    fun setMemory(enabled: Boolean) = primary().engine().setMemory(enabled)

    /** What the user is looking at — the app's, heard by whichever agent has a live conversation. Null: nothing. */
    fun setScreen(screen: GraineScreen?) = primary().engine().setScreen(screen)

    /** One field changed: merged by name into the current screen (added when new). Nothing happens before a screen is set. */
    fun updateField(field: GraineField) = primary().engine().updateField(field)

    /** A field left the screen. */
    fun removeField(name: String) = primary().engine().removeField(name)

    /**
     * The user left a screen. With [screenId], only if it is still the one
     * described — a screen closing late must not erase the one that replaced
     * it. Null clears whatever is there.
     */
    fun clearScreen(screenId: String? = null) = primary().engine().clearScreen(screenId)

    /** A product event that informs without interrupting. Rides the next screen update; the last ten are kept. */
    fun track(name: String, data: Map<String, Any?> = emptyMap()) {
        if (name.isBlank()) return
        primary().engine().track(name, data)
    }

    /** A tap on a control the agent should know about (`tap:<name>`), for a control the SDK cannot name itself. */
    fun tap(name: String) = primary().engine().tap(name)

    /** The user did something that is not a field edit (scrolled, opened a picker): the stuck-user clock restarts. */
    fun noteInteraction() = primary().engine().noteInteraction()

    /** Back to before configure(): tests only. */
    internal fun reset() {
        lock.locked { holders.values.forEach { it.reset() } }
        callsReporter = null
        callInfo.value = GraineCallInfo()
    }

    /** How long a booking waits for the agent's configuration: past the session request's own timeouts. */
    internal const val SESSION_WAIT_MS = 20_000L
}
