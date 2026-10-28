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
import kotlinx.datetime.LocalDate
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
    /** [GraineCalls.phase]: [callInfo]'s phase, written with it ([publishCall]). */
    internal val callPhase = MutableStateFlow(GraineCallPhase.IDLE)
    internal val callOutcomes = MutableSharedFlow<GraineCallOutcome>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val incomingCalls = MutableSharedFlow<GraineIncomingCall>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** [GraineCalls.speakerByDefault], kept here so one set before configure reaches every engine made after. */
    @Volatile internal var speakerByDefault: Boolean = true

    /** [debugTokenMode] as the app chose it, kept so one set before configure reaches every engine made after; null: never chosen. */
    @Volatile private var debugTokenModeChosen: GraineDebugTokenMode? = null

    /** [identityTokenRefresher], kept so one set before configure reaches every engine made after. */
    @Volatile private var tokenRefresher: (suspend () -> String?)? = null

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
        if (mine) install(agent, engineFactory(config, agent.name))
        return agent
    }

    /** What makes an agent's engine from its whole configuration (its token provider too): the platform's; tests hand in their own. */
    internal var engineFactory: (config: GraineConfig, name: String) -> GraineEngine = ::createPlatformEngine

    /** The agent configured under [name]; null when it is not configured. */
    fun agent(name: String = DEFAULT_AGENT): GraineAgent? = lock.locked { holders[normalise(name)] }?.takeIf { it.isConfigured }

    /** Every configured agent, by name, in the order they were configured. */
    val agents: Map<String, GraineAgent> get() = lock.locked { holders.filterValues { it.isConfigured } }

    /** Whether the default agent is configured. */
    val isConfigured: Boolean get() = defaultAgent.isConfigured

    /** The engine for an agent; tests install fakes. */
    internal fun install(agent: GraineAgent, engine: GraineEngine) {
        if (callsReporter == null) callsReporter = agent
        // What the app chose before this agent was configured reaches its platform SDK before anything else does.
        debugTokenModeChosen?.let { engine.debugTokenMode = it }
        tokenRefresher?.let { engine.setIdentityTokenRefresher(IdentityTokens.bridge(it)) }
        agent.install(engine)
        engine.callsSetSpeakerByDefault(speakerByDefault)
    }

    /** Every configured agent's engine, in the order they were configured. */
    private fun engines(): List<GraineEngine> = lock.locked { holders.values.mapNotNull { it.engineOrNull() } }

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

    /** The default agent's design tokens, for UI of your own. See [GraineAgent.theme]. */
    val theme: StateFlow<GraineThemeTokens?> get() = defaultAgent.theme

    /** Everything the default agent reports that is not a state. Not replayed. */
    val events: SharedFlow<GraineEvent> get() = defaultAgent.events

    /** The typed conversation with the default agent. */
    val chat: GraineChat get() = defaultAgent.chat

    /** The default agent's voice bar call. */
    val voice: GraineVoice get() = defaultAgent.voice

    /**
     * The component the default agent drew that waits for an answer — in the
     * chat or on the voice bar's call — or null when there is none. Set when
     * one arrives; null again once it is answered ([GraineChat.answerWidget]),
     * closed ([GraineChat.dismissWidget]) or replaced, and when its
     * conversation is over. See [GraineAgent.component]. New in 0.14.0.
     */
    val component: StateFlow<GraineComponent?> get() = defaultAgent.component

    /**
     * True while a component the default agent asked for is still loading —
     * draw a loader where it will appear. False once it arrives, its
     * conversation is over, or after 12 seconds with nothing. See
     * [GraineAgent.componentPending]. New in 0.14.0.
     */
    val componentPending: StateFlow<Boolean> get() = defaultAgent.componentPending

    /** What the default agent allows when a user books a call. */
    val schedulingRules: GraineSchedulingRules get() = defaultAgent.schedulingRules

    /**
     * What Graine has proven about this app and its user, for the default
     * agent, as it changes — off until the agent's settings ask for it. See
     * [GraineAgent.verification]. New in 0.14.4.
     */
    val verification: StateFlow<GraineVerification> get() = defaultAgent.verification

    /**
     * Prove this app now, for the default agent — on first launch, so the
     * first conversation never waits for it — and return the verdict. See
     * [GraineAgent.prepareAppVerification]. New in 0.14.4.
     *
     *     scope.launch { Graine.prepareAppVerification() }   // after configure, on first launch
     */
    suspend fun prepareAppVerification(): GraineAppVerdict = defaultAgent.prepareAppVerification()

    /** Native calls — the app's, whichever agent a call is with. */
    val calls: GraineCalls = GraineCalls()

    /** The default agent's prompt variables. See [GraineAgent.setVariables]. */
    fun setVariables(values: Map<String, Any?>?) = defaultAgent.setVariables(values)

    /** Every variable the default agent will receive, merged. See [GraineAgent.currentVariables]. */
    fun currentVariables(): Map<String, String> = defaultAgent.currentVariables()

    /** An action for the default agent. See [GraineAgent.registerAction]. */
    fun registerAction(name: String, handler: suspend (args: Map<String, Any?>) -> GraineActionResult): () -> Unit =
        defaultAgent.registerAction(name, handler)

    /** Forget what the default agent remembers about the signed-in user. See [GraineAgent.forgetMemory]. */
    suspend fun forgetMemory(): Int = defaultAgent.forgetMemory()

    /** What the default agent remembers about the signed-in user. See [GraineAgent.memories]. */
    suspend fun memories(): List<GraineMemory> = defaultAgent.memories()

    /** Book a call from the default agent. See [GraineAgent.scheduleCall]: the agent's rules are checked first. */
    suspend fun scheduleCall(at: Instant, reason: String? = null): GraineScheduledCall = defaultAgent.scheduleCall(at, reason)

    /** Start one of your outreach workflows for the signed-in user, from the default agent. See [GraineAgent.startWorkflow]. */
    suspend fun startWorkflow(workflow: String, data: Map<String, Any?> = emptyMap(), idempotencyKey: String? = null): GraineWorkflowRun =
        defaultAgent.startWorkflow(workflow, data, idempotencyKey)

    /** The default agent's booked calls still to come. */
    suspend fun scheduledCalls(): List<GraineScheduledCall> = defaultAgent.scheduledCalls()

    /** Call off one of the default agent's booked calls. Returns its status afterwards: cancelled. */
    suspend fun cancelScheduledCall(id: String): GraineCallStatus = defaultAgent.cancelScheduledCall(id)

    /** Whether the default agent can be booked from the app now. See [GraineAgent.bookingAvailable]. */
    val bookingAvailable: Boolean get() = defaultAgent.bookingAvailable

    /**
     * Why the default agent cannot be booked from the app now, as a word —
     * `not_loaded`, `rings_not_set_up`, `scheduling_off` or `not_identified` —
     * or null when it can. Read as it is now: read it again after
     * [identify] and once [session] has a value. See
     * [GraineAgent.bookingUnavailableReason]. New in 0.14.0.
     */
    val bookingUnavailableReason: String? get() = defaultAgent.bookingUnavailableReason

    /**
     * Whether the default agent can be booked now, and why not, AS IT CHANGES
     * — the way to show or hide a "Book a call" of your own: collect it,
     * rather than reading [bookingAvailable] once at launch, before the
     * agent's session has loaded. See [GraineAgent.bookingState]. New in 0.14.4.
     */
    val bookingState: StateFlow<GraineBookingState> get() = defaultAgent.bookingState

    /**
     * The times the SDK's booking sheet would offer on [day] in the phone's
     * time zone — every 5 minutes within the default agent's booking rules,
     * and only inside its calling window when it has one
     * ([GraineSchedulingRules.callingWindow], from 0.14.1) — for a time picker
     * of your own; book one with [scheduleCall]. Empty for a day outside the
     * rules and before configure(). See [GraineAgent.bookingSlots].
     * New in 0.14.0.
     */
    fun bookingSlots(day: LocalDate): List<Instant> = defaultAgent.bookingSlots(day)

    /** The SDK's own booking sheet, for the default agent. See [GraineAgent.presentBooking]. */
    suspend fun presentBooking(reason: String? = null): GraineScheduledCall? = defaultAgent.presentBooking(reason)

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
     * conversations are anonymous. A voice call takes who it is at its start.
     *
     * A chat belongs to whoever was identified when it connected (from 0.14.0).
     * When the identity changes while the chat is connected or connecting —
     * signing in after it opened, another user, signing out — that
     * conversation closes quietly (never "Disconnected"), [GraineChat.turns]
     * clears but for a message typed and not yet sent, which is sent next, and
     * the chat connects again at once as the new identity ([GraineChat.status]
     * CONNECTING, then CONNECTED). The same identity with other traits, or another
     * memory choice, changes nothing on the open chat. Identify before showing
     * the chat where you can. On iOS it needs the GraineInApp of this version.
     *
     * `identityToken` (new in 0.14.4) proves the identity, for an agent with
     * "Verified users" on: the HMAC your server computes over the identity
     * value with the agent's user secret, or the ID token (JWT) of your own
     * sign-in. It travels only inside the request's identity, beside the value
     * it proves — never in variables, the screen's context or a log line — and
     * is held in memory only. Another user, or null traits, drops the token
     * (and the user pass Graine gave for it) in the same step; the same user
     * without a token keeps the one they have. Replace it with
     * [setIdentityToken]; let the SDK ask for a fresh one with
     * [identityTokenRefresher]. On iOS the token needs the GraineInApp of this
     * version (an older one identifies the user without it).
     *
     *     Graine.identify(mapOf("user_id" to user.id), identityToken = api.graineIdentityToken())
     */
    fun identify(traits: Map<String, Any?>?, memory: Boolean? = null, identityToken: String? = null) {
        primary().engine().identify(traits, memory, identityToken)
        bookingChanged()
    }

    /**
     * Replace the identity token for the user identified now, without
     * identifying anyone else — a fresh one your server minted, or your
     * sign-in's renewed ID token. Null clears it. For every agent, as
     * [identify] is. With nobody identified there is no one to attach it to:
     * identify the user with their token instead. On iOS it needs the
     * GraineInApp of this version. New in 0.14.4.
     */
    fun setIdentityToken(token: String?) = primary().engine().setIdentityToken(token)

    /**
     * HOW THE SDK ASKS YOUR APP FOR A FRESH IDENTITY TOKEN — a suspending
     * function that returns one for the signed-in user (from your server, or
     * your sign-in's SDK), or null to keep the token the SDK has. The SDK
     * calls it when Graine says the token expired, when a request is refused
     * [GraineErrorCode.IdentityUnverified] (that request is then sent once
     * more with the new token), before a conversation starts with a JWT that
     * has less than a minute left, and when a call rings.
     *
     * Since 0.14.6 it is also asked when no token is held at all — you
     * identified the user and fetch the token afterwards — for an agent with
     * "Verified users" on report or enforce: before a conversation starts
     * (the chat, the voice bar, a call your app places) and before a request
     * for the user ([memories], past sessions, a new conversation, booked
     * calls, [startWorkflow]), the SDK waits up to 10 seconds for your answer,
     * so that conversation goes out verified instead of anonymous. The token
     * you return is kept, as [setIdentityToken] keeps one. No answer within
     * 10 seconds lets the request go without one, and that refresh is not
     * waited for again (its token is used when it comes). Answering a call
     * that rings never waits for it.
     *
     * At most once every 30 seconds for the same user, never twice at once;
     * another user signing in is asked for at once, and a token still on its
     * way for the last user never reaches them. It starts on the main thread,
     * so make the network call suspend (Ktor's do) rather than block.
     * Returning null, or throwing, keeps the token as it was. One for the whole app, like
     * [identify]; may be set before [configure]. Null (the default): the SDK
     * only ever sends the token it was given. On iOS it needs the GraineInApp
     * of this version. New in 0.14.4; the wait for a missing token, in 0.14.6.
     *
     *     Graine.identityTokenRefresher = { api.graineIdentityToken() }   // a suspending call to your server
     */
    var identityTokenRefresher: (suspend () -> String?)?
        get() = tokenRefresher
        set(value) {
            tokenRefresher = value
            val bridged = value?.let { IdentityTokens.bridge(it) }
            engines().forEach { it.setIdentityTokenRefresher(bridged) }
        }

    /**
     * DEVELOPMENT MODE, for every agent: whether this build sends a debug
     * token instead of proving itself with App Attest (iOS) or Play Integrity
     * (Android) — [GraineDebugTokenMode.AUTOMATIC] (the default) in a debug
     * build and never in a release build, [GraineDebugTokenMode.ALWAYS] in any
     * build (an internal QA build), [GraineDebugTokenMode.NEVER] in none. Set
     * it before [configure], so the first request already follows it. A
     * debug token counts only once an Admin registers it on the agent: see
     * [debugToken]. On iOS it needs the GraineInApp of this version.
     * New in 0.14.4.
     *
     *     Graine.debugTokenMode = GraineDebugTokenMode.ALWAYS   // an internal QA build
     *     Graine.configure(config)
     */
    var debugTokenMode: GraineDebugTokenMode
        get() = debugTokenModeChosen ?: primary().engineOrNull()?.debugTokenMode ?: GraineDebugTokenMode.AUTOMATIC
        set(value) {
            debugTokenModeChosen = value
            engines().forEach { it.debugTokenMode = value }
        }

    /**
     * This install's debug token while the build uses one ([debugTokenMode]),
     * else null — a release build never makes one, and null before
     * [configure]. Made once per install and logged once, with where to
     * register it: Agent → Embed & Widgets → Install → Development → Debug
     * tokens. Registered there, this build counts as a verified app
     * ([GraineAppVerdict.DEBUG_TOKEN]) while "Require a verified app" is on.
     * New in 0.14.4.
     */
    val debugToken: String? get() = primary().engineOrNull()?.debugToken

    /** Remember the current user's conversations, or stop — `identify(the same traits, memory = enabled)`. Every agent. */
    fun setMemory(enabled: Boolean) {
        primary().engine().setMemory(enabled)
        bookingChanged()
    }

    /** Who the user is changed: every agent's [GraineAgent.bookingState] is read again. */
    private fun bookingChanged() {
        lock.locked { holders.values.toList() }.forEach { it.refreshBooking() }
    }

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

    /**
     * Something worth speaking up about, told to the default agent's live chat
     * or call: the agent may speak first. Paced (20 s apart, 3 per
     * conversation by default; [data] may carry `cooldown_seconds`,
     * `max_per_session`, or `quiet = true` to record without speaking).
     * Dropped when no chat or call is live — open the chat and wait for
     * [GraineChat.status] CONNECTED first. At most [REPORT_EVENT_MAX_BYTES]
     * (8 KB) of JSON; a bigger event is never sent and [events] hears why
     * (0.14.5). See [GraineAgent.reportEvent]. New in 0.14.4.
     */
    fun reportEvent(name: String, detail: String? = null, data: Map<String, Any?> = emptyMap()) =
        defaultAgent.reportEvent(name, detail, data)

    /**
     * THE MOST AN APP EVENT MAY WEIGH, in bytes: 8 KB (8192) — the event
     * [reportEvent] sends, `{name, detail, pacing keys, data}`, as JSON in
     * UTF-8. Graine closes a conversation whose frame passes 32 KB; an event
     * over this limit is refused before it is sent. New in 0.14.5.
     */
    const val REPORT_EVENT_MAX_BYTES: Int = 8_192

    /** A tap on a control the agent should know about (`tap:<name>`), for a control the SDK cannot name itself. */
    fun tap(name: String) = primary().engine().tap(name)

    /** The user did something that is not a field edit (scrolled, opened a picker): the stuck-user clock restarts. */
    fun noteInteraction() = primary().engine().noteInteraction()

    /** The call as the platform reports it, and its phase with it. */
    internal fun publishCall(info: GraineCallInfo) {
        callInfo.value = info
        callPhase.value = info.phase
    }

    /** Back to before configure(): tests only. */
    internal fun reset() {
        lock.locked { holders.values.forEach { it.reset() } }
        callsReporter = null
        publishCall(GraineCallInfo())
        speakerByDefault = true
        debugTokenModeChosen = null
        tokenRefresher = null
        engineFactory = ::createPlatformEngine
    }

}
