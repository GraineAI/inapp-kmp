package ai.graine.inapp.kmp

import kotlinx.datetime.Instant

/**
 * A platform SDK that records what the shared code asks of it and answers as
 * the test says — so every method of the shared API is proven without a
 * device, a network or a platform SDK.
 */
internal class FakeEngine : GraineEngine {
    lateinit var sink: EngineSink
    val calls = mutableListOf<String>()

    override var installId: String = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    override var memoryEnabled: Boolean = true

    // What the async methods answer; null leaves the request waiting (the test answers through `pending`).
    var forgetAnswer: Result<Int>? = Result.success(2)
    var memoriesAnswer: Result<List<GraineMemory>>? = Result.success(emptyList())
    var scheduleAnswer: Result<GraineScheduledCall>? = null
    var listAnswer: Result<List<GraineScheduledCall>>? = Result.success(emptyList())
    var cancelAnswer: Result<GraineCallStatus>? = Result.success(GraineCallStatus.Cancelled)
    var startCallAnswer: String? = "call_1"
    var newConversationAnswer: Result<Unit>? = Result.success(Unit)

    val identified = mutableListOf<Pair<Map<String, Any?>?, Boolean?>>()
    val prepared = mutableListOf<Pair<String?, Map<String, Any?>>>()
    val screens = mutableListOf<GraineScreen?>()
    val scheduled = mutableListOf<Pair<Instant, String?>>()
    val tracked = mutableListOf<Pair<String, Map<String, Any?>>>()
    val handlers = mutableMapOf<String, (Map<String, Any?>, (GraineActionResult) -> Unit) -> Unit>()
    val unregistered = mutableListOf<String>()
    var callsConfig: GraineCallsConfig? = null
    var started = 0

    override fun start(sink: EngineSink) {
        this.sink = sink
        started++
    }

    override fun identify(traits: Map<String, Any?>?, memory: Boolean?) { identified += traits to memory; calls += "identify" }
    override fun setMemory(enabled: Boolean) { memoryEnabled = enabled; calls += "setMemory:$enabled" }
    override fun forgetMemory(done: (Result<Int>) -> Unit) { calls += "forgetMemory"; forgetAnswer?.let(done) }
    override fun memories(done: (Result<List<GraineMemory>>) -> Unit) { calls += "memories"; memoriesAnswer?.let(done) }
    override fun setVariables(values: Map<String, Any?>?) { calls += "setVariables:${values?.keys}" }
    /** What the platform SDK says it will send. */
    var mergedVariables: Map<String, String> = emptyMap()
    override fun currentVariables(): Map<String, String> = mergedVariables
    override fun setScreen(screen: GraineScreen?) { screens += screen; calls += "setScreen" }
    override fun updateField(field: GraineField) { calls += "updateField:${field.name}" }
    override fun removeField(name: String) { calls += "removeField:$name" }
    override fun clearScreen(screenId: String?) { calls += "clearScreen:$screenId" }
    override fun track(name: String, data: Map<String, Any?>) { tracked += name to data; calls += "track:$name" }
    override fun tap(name: String) { calls += "tap:$name" }
    override fun noteInteraction() { calls += "noteInteraction" }

    override fun registerAction(name: String, handler: (args: Map<String, Any?>, answer: (GraineActionResult) -> Unit) -> Unit): () -> Unit {
        handlers[name] = handler
        calls += "registerAction:$name"
        return { if (handlers[name] === handler) handlers.remove(name); unregistered += name }
    }

    /** Run the agent's action as the platform would: on the main thread, answered once. */
    fun runAction(name: String, args: Map<String, Any?>): GraineActionResult? {
        var answered: GraineActionResult? = null
        val handler = handlers[name] ?: return GraineActionResult.refused("This app build does not implement \"$name\".")
        handler(args) { r -> if (answered == null) answered = r }
        return answered
    }

    /** The same, keeping the answer callback for a handler that answers later. */
    fun runActionLater(name: String, args: Map<String, Any?>, answer: (GraineActionResult) -> Unit) {
        handlers.getValue(name)(args, answer)
    }

    override fun scheduleCall(at: Instant, reason: String?, done: (Result<GraineScheduledCall>) -> Unit) {
        scheduled += at to reason
        calls += "scheduleCall"
        (scheduleAnswer ?: Result.success(GraineScheduledCall("sc_1", at, GraineCallStatus.Scheduled, GraineCallSource.App, reason))).let(done)
    }

    override fun scheduledCalls(done: (Result<List<GraineScheduledCall>>) -> Unit) { calls += "scheduledCalls"; listAnswer?.let(done) }
    override fun cancelScheduledCall(id: String, done: (Result<GraineCallStatus>) -> Unit) { calls += "cancel:$id"; cancelAnswer?.let(done) }

    override var bookingAvailable: Boolean = false
    /** What the booking sheet answers once it closes; null leaves it open. */
    var bookingAnswer: Result<GraineScheduledCall?>? = Result.success(null)
    val presented = mutableListOf<String?>()
    override fun presentBooking(reason: String?, done: (Result<GraineScheduledCall?>) -> Unit) {
        presented += reason
        calls += "presentBooking"
        bookingAnswer?.let(done)
    }

    override fun chatOpen(size: GraineChatSize?) { calls += if (size == null) "chatOpen" else "chatOpen:${size.wire}" }
    override fun chatClose() { calls += "chatClose" }
    override fun chatSend(text: String) { calls += "chatSend:$text" }
    override fun chatRetry() { calls += "chatRetry" }
    override fun chatClear() { calls += "chatClear" }
    override fun chatRefreshUpcoming() { calls += "refreshUpcoming" }
    override fun chatCancelUpcoming(id: String) { calls += "cancelUpcoming:$id" }
    val answers = mutableListOf<Triple<String, Map<String, Any?>, String>>()
    override fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String) {
        answers += Triple(widgetId, data, summary)
        calls += "answerWidget:$widgetId"
    }
    override fun chatNewConversation(done: (Result<Unit>) -> Unit) { calls += "newConversation"; newConversationAnswer?.let(done) }

    override fun callsConfigure(config: GraineCallsConfig) { callsConfig = config; calls += "callsConfigure" }
    override fun callsEnableVoIPPushes() { calls += "enableVoIPPushes" }
    override fun callsStart(reason: String?, variables: Map<String, Any?>): String? { calls += "startCall:$reason:${variables.keys}"; return startCallAnswer }
    override fun callsPrepare(reason: String?, variables: Map<String, Any?>) { prepared += reason to variables; calls += "prepareCall:$reason:${variables.keys}" }
    override fun callsAnswer() { calls += "answer" }
    override fun callsDecline() { calls += "decline" }
    override fun callsEnd() { calls += "end" }
    override fun callsSetMuted(muted: Boolean) { calls += "callMuted:$muted" }
    override fun callsSetSpeaker(on: Boolean) { calls += "speaker:$on" }
    override fun callsCallBack(): String? { calls += "callBack"; return "call_2" }
    override fun callsDismissMissed() { calls += "dismissMissed" }

    var permissions = GraineCallPermissions(GraineCallPermissionStatus.DENIED, GraineCallPermissionStatus.NOT_NEEDED, GraineCallPermissionStatus.NOT_NEEDED)
    var permissionsAfterAsking: GraineCallPermissions? = null
    var setupProblems: List<GraineCallSetupProblem> = emptyList()
    override fun callsPermissions(): GraineCallPermissions { calls += "callPermissions"; return permissions }
    override fun callsRequestPermissions(done: (GraineCallPermissions) -> Unit) {
        calls += "requestCallPermissions"
        permissionsAfterAsking?.let { permissions = it }
        done(permissions)
    }
    override fun callsOpenSettings(item: GraineCallPermission): Boolean { calls += "openCallSettings:${item.wire}"; return true }
    override fun callsSetupProblems(): List<GraineCallSetupProblem> { calls += "callSetupProblems"; return setupProblems }

    override fun voiceStart() { calls += "voiceStart" }
    override fun voiceEnd() { calls += "voiceEnd" }
    override fun voiceSetMuted(muted: Boolean) { calls += "voiceMuted:$muted" }
    override fun voiceSetCaptionsOn(on: Boolean) { calls += "captionsOn:$on" }
    override fun voiceClearCaptions() { calls += "clearCaptions" }
}

/** A session that lets users book, with the given limits. */
internal fun bookingSession(
    usersCanBook: Boolean = true,
    minLeadMinutes: Int = 5,
    maxDaysAhead: Int = 14,
) = GraineSession(
    agentId = "agent-1",
    memoryEnabled = true,
    schedulingRules = GraineSchedulingRules(usersCanBook = usersCanBook, minLeadMinutes = minLeadMinutes, maxDaysAhead = maxDaysAhead),
)
