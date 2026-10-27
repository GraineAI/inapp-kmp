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

    /** The identity token each identify carried, in the same order as [identified] (null: none given). */
    val identityTokens = mutableListOf<String?>()
    override fun identify(traits: Map<String, Any?>?, memory: Boolean?, identityToken: String?) {
        identified += traits to memory
        identityTokens += identityToken
        calls += "identify"
    }
    override fun setMemory(enabled: Boolean) { memoryEnabled = enabled; calls += "setMemory:$enabled" }

    // Your app and your users.
    /** Every token setIdentityToken handed over, in order. */
    val tokensSet = mutableListOf<String?>()
    override fun setIdentityToken(token: String?) { tokensSet += token; calls += "setIdentityToken" }
    /** The refresher the platform holds now, as the shared code handed it over; null when none. */
    var refresher: ((done: (String?) -> Unit) -> Unit)? = null
    /** Each refresher handed over: true for one, false for null (removed). */
    val refreshersSet = mutableListOf<Boolean>()
    override fun setIdentityTokenRefresher(refresher: ((done: (String?) -> Unit) -> Unit)?) {
        this.refresher = refresher
        refreshersSet += refresher != null
    }
    /** What prepareAppVerification answers; null leaves it waiting (the test answers through [prepareDone]). */
    var prepareAnswer: GraineAppVerdict? = GraineAppVerdict.VERIFIED
    var prepareDone: ((GraineAppVerdict) -> Unit)? = null
    override fun prepareAppVerification(done: (GraineAppVerdict) -> Unit) {
        calls += "prepareAppVerification"
        prepareDone = done
        prepareAnswer?.let(done)
    }
    override var verification: GraineVerification = GraineVerification()
    /** Every development mode the shared code set, in order. */
    val debugTokenModes = mutableListOf<GraineDebugTokenMode>()
    override var debugTokenMode: GraineDebugTokenMode = GraineDebugTokenMode.AUTOMATIC
        set(value) { field = value; debugTokenModes += value }
    override var debugToken: String? = null
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
    val reported = mutableListOf<Triple<String, String?, Map<String, Any?>>>()
    override fun reportEvent(name: String, detail: String?, data: Map<String, Any?>) { reported += Triple(name, detail, data); calls += "reportEvent:$name" }
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

    /** What startWorkflow was asked: workflow, data, key. */
    val workflowStarts = mutableListOf<Triple<String, Map<String, Any?>, String?>>()
    var workflowAnswer: Result<GraineWorkflowRun>? = Result.success(GraineWorkflowRun("oex_1", "owf_1", false))
    override fun startWorkflow(workflow: String, data: Map<String, Any?>, idempotencyKey: String?, done: (Result<GraineWorkflowRun>) -> Unit) {
        workflowStarts += Triple(workflow, data, idempotencyKey)
        calls += "startWorkflow"
        workflowAnswer?.let(done)
    }

    override var bookingAvailable: Boolean = false
    override var bookingUnavailableReason: String? = "scheduling_off"
    /** What the booking sheet offers on any day asked for. */
    var slots: List<Instant> = emptyList()
    override fun bookingSlots(isoDay: String): List<Instant> { calls += "bookingSlots:$isoDay"; return slots }
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
    override fun dismissWidget(widgetId: String) { calls += "dismissWidget:$widgetId" }
    override fun chatShown(visible: Boolean) { calls += "chatShown:$visible" }
    override fun chatNewConversation(done: (Result<Unit>) -> Unit) { calls += "newConversation"; newConversationAnswer?.let(done) }
    /** Every pin handed over, as the platform got it. */
    val pins = mutableListOf<GraineChatPin?>()
    override fun chatPin(pin: GraineChatPin?) { pins += pin; calls += "chatPin:${pin?.title}" }
    override fun chatRate(style: String, value: Int) { calls += "chatRate:$style:$value" }
    override fun chatSendQuickReply(reply: GraineQuickReply) { calls += "sendQuickReply:${reply.label}:${reply.text}" }
    /** The paperclip's handler as the platform holds it. */
    var attachHandler: (() -> Unit)? = null
    override fun chatSetAttachHandler(handler: (() -> Unit)?) { attachHandler = handler; calls += "attach:${handler != null}" }
    override fun chatRefreshPastSessions() { calls += "refreshPastSessions" }
    override fun chatLoadMorePastSessions() { calls += "loadMorePastSessions" }
    /** What a transcript answers; null leaves it waiting (the test answers through `transcriptDone`). */
    var transcriptAnswer: Result<GraineSessionTranscript>? = null
    var transcriptDone: ((Result<GraineSessionTranscript>) -> Unit)? = null
    override fun chatSessionTranscript(id: String, done: (Result<GraineSessionTranscript>) -> Unit) {
        calls += "sessionTranscript:$id"
        transcriptDone = done
        transcriptAnswer?.let(done)
    }
    /** Every close delay the shared code set, in order. */
    val closeDelays = mutableListOf<Int>()
    override var chatCloseDelaySeconds: Int = 15
        set(value) { field = value; closeDelays += value }

    override fun callsConfigure(config: GraineCallsConfig) { callsConfig = config; calls += "callsConfigure" }
    override fun callsEnableVoIPPushes() { calls += "enableVoIPPushes" }
    override fun callsStart(reason: String?, variables: Map<String, Any?>): String? { calls += "startCall:$reason:${variables.keys}"; return startCallAnswer }
    override fun callsPrepare(reason: String?, variables: Map<String, Any?>) { prepared += reason to variables; calls += "prepareCall:$reason:${variables.keys}" }
    override fun callsAnswer() { calls += "answer" }
    override fun callsDecline() { calls += "decline" }
    override fun callsEnd() { calls += "end" }
    override fun callsSetMuted(muted: Boolean) { calls += "callMuted:$muted" }
    override fun callsSetSpeaker(on: Boolean) { calls += "speaker:$on" }
    /** What the platform was told of GraineCalls.speakerByDefault, in order. */
    val speakerDefaults = mutableListOf<Boolean>()
    override fun callsSetSpeakerByDefault(on: Boolean) { speakerDefaults += on }
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
