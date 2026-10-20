package ai.graine.inapp.kmp

import ai.graine.inapp.ActionResult
import ai.graine.inapp.FrictionSignal
import ai.graine.inapp.GraineCall
import ai.graine.inapp.GraineCallListener
import ai.graine.inapp.GraineCallPermission as NativeCallPermission
import ai.graine.inapp.GraineCallPermissionStatus as NativeStatus
import ai.graine.inapp.GraineCallPermissions as NativeCallPermissions
import ai.graine.inapp.GraineCallSetupProblem as NativeCallSetupProblem
import ai.graine.inapp.GraineCallOutcome as NativeOutcome
import ai.graine.inapp.GraineCallSource as NativeCallSource
import ai.graine.inapp.GraineCallState as NativeCallState
import ai.graine.inapp.GraineCallStatus as NativeCallStatus
import ai.graine.inapp.GraineCallTransport as NativeCallTransport
import ai.graine.inapp.GraineCallback
import ai.graine.inapp.GraineCaption as NativeCaption
import ai.graine.inapp.GraineCallsConfig as NativeCallsConfig
import ai.graine.inapp.GraineChatPin as NativeChatPin
import ai.graine.inapp.GraineChatRating as NativeChatRating
import ai.graine.inapp.GraineChatSize as NativeChatSize
import ai.graine.inapp.GraineChatTurn as NativeChatTurn
import ai.graine.inapp.GraineError
import ai.graine.inapp.GraineErrorCode as NativeErrorCode
import ai.graine.inapp.GraineField as NativeField
import ai.graine.inapp.GraineListener
import ai.graine.inapp.GraineMemory as NativeMemory
import ai.graine.inapp.GraineQuickReply as NativeQuickReply
import ai.graine.inapp.GraineScheduledCall as NativeScheduledCall
import ai.graine.inapp.GraineSchedulingRules as NativeRules
import ai.graine.inapp.GraineScreen as NativeScreen
import ai.graine.inapp.GraineWidget
import ai.graine.inapp.Role
import ai.graine.inapp.SessionConfig
import ai.graine.inapp.SessionIdentity
import ai.graine.inapp.Transport
import android.app.Activity
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * The shared API on Android, run over the Android SDK's real types: its
 * listeners, sessions, calls, errors and chat turns, as the SDK reports them —
 * through a fake port standing where one agent's GraineInApp stands.
 */
class AndroidEngineTest {

    private class Heard : EngineSink {
        val sessions = mutableListOf<GraineSession>()
        val failures = mutableListOf<String>()
        val events = mutableListOf<GraineEvent>()
        var chat: Triple<List<GraineChatTurn>, GraineChatStatus, Boolean>? = null
        var calls: GraineCallInfo? = null
        val outcomes = mutableListOf<GraineCallOutcome>()
        val incoming = mutableListOf<GraineIncomingCall>()
        var voice: GraineVoiceInfo? = null
        val themes = mutableListOf<GraineThemeTokens?>()
        override fun theme(tokens: GraineThemeTokens?) { themes += tokens }
        override fun session(session: GraineSession) { sessions += session }
        override fun sessionFailed(message: String) { failures += message }
        override fun event(event: GraineEvent) { events += event }
        override fun chat(turns: List<GraineChatTurn>, status: GraineChatStatus, typing: Boolean) { chat = Triple(turns, status, typing) }
        var extras: ChatExtras? = null
        override fun chatExtras(extras: ChatExtras) { this.extras = extras }
        var upcoming: List<GraineScheduledCall>? = null
        override fun upcomingCalls(calls: List<GraineScheduledCall>) { upcoming = calls }
        override fun calls(info: GraineCallInfo) { calls = info }
        override fun callOutcome(outcome: GraineCallOutcome) { outcomes += outcome }
        override fun incomingCall(call: GraineIncomingCall) { incoming += call }
        override fun voice(info: GraineVoiceInfo) { voice = info }
    }

    private class Port : AndroidPort {
        val did = mutableListOf<String>()
        val listeners = mutableListOf<GraineListener>()
        val callListeners = mutableListOf<GraineCallListener>()
        val chatListeners = mutableListOf<Runnable>()
        var screens = mutableListOf<NativeScreen?>()
        var fields = mutableListOf<NativeField>()
        var actions = mutableMapOf<String, (JSONObject, (ActionResult) -> Unit) -> Unit>()
        var forget: GraineCallback<Int>? = null
        var memoriesDone: GraineCallback<List<NativeMemory>>? = null
        var scheduleArgs: Pair<Date, String?>? = null
        var scheduleDone: GraineCallback<NativeScheduledCall>? = null
        var listDone: GraineCallback<List<NativeScheduledCall>>? = null
        var cancelDone: GraineCallback<NativeCallStatus>? = null
        var callsConfig: NativeCallsConfig? = null
        var startedFrom: Activity? = null
        var callBackArgs: Pair<String?, String?>? = null
        var newConversationDone: GraineCallback<Unit>? = null

        override val name = "help"
        override var installId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        override var memoryEnabled = true
        override var session: SessionConfig? = null
        override var themeTokensJson: String? = null
        override fun addListener(listener: GraineListener) { listeners += listener }
        override fun identify(traits: Map<String, Any?>?, memory: Boolean?) { did += "identify:$traits:$memory" }
        override fun setMemory(enabled: Boolean) { did += "setMemory:$enabled" }
        override fun forgetMemory(done: GraineCallback<Int>) { forget = done }
        override fun memories(done: GraineCallback<List<NativeMemory>>) { memoriesDone = done }
        override fun setVariables(values: Map<String, Any?>?) { did += "setVariables:$values" }
        var merged: Map<String, String> = emptyMap()
        override fun currentVariables(): Map<String, String> = merged
        override fun setScreen(screen: NativeScreen?) { screens.add(screen) }
        override fun updateField(field: NativeField) { fields.add(field) }
        override fun removeField(name: String) { did += "removeField:$name" }
        override fun clearScreen(screenId: String?) { did += "clearScreen:$screenId" }
        override fun track(name: String, data: Map<String, Any?>) { did += "track:$name:$data" }
        override fun tap(name: String) { did += "tap:$name" }
        override fun noteInteraction() { did += "noteInteraction" }
        override fun registerDeferredAction(name: String, handler: (args: JSONObject, answer: (ActionResult) -> Unit) -> Unit): () -> Unit {
            actions[name] = handler
            return { actions.remove(name) }
        }
        override fun scheduleCall(at: Date, reason: String?, done: GraineCallback<NativeScheduledCall>) { scheduleArgs = at to reason; scheduleDone = done }
        override fun scheduledCalls(done: GraineCallback<List<NativeScheduledCall>>) { listDone = done }
        override fun cancelScheduledCall(id: String, done: GraineCallback<NativeCallStatus>) { did += "cancel:$id"; cancelDone = done }
        override var bookingAvailable = false
        var bookingFrom: Activity? = null
        var bookingReason: String? = null
        var bookingDone: GraineCallback<NativeScheduledCall?>? = null
        override fun presentBooking(activity: Activity, reason: String?, done: GraineCallback<NativeScheduledCall?>) {
            bookingFrom = activity; bookingReason = reason; bookingDone = done
        }

        override var chatTurns: List<NativeChatTurn> = emptyList()
        override var chatConnected = false
        override var chatConnecting = false
        override var chatFailed = false
        override var chatReconnecting = false
        override var chatTyping = false
        override var chatUpcomingCalls: List<NativeScheduledCall> = emptyList()
        override var chatQuickReplies: List<NativeQuickReply> = emptyList()
        override var chatEnded = false
        override var chatRating: NativeChatRating? = null
        override var chatPinned: NativeChatPin? = null
        override fun addChatChangeListener(listener: Runnable) { chatListeners += listener }
        override fun chatOpen(size: NativeChatSize?) { did += "chatOpen:$size" }
        override fun chatConnect() { did += "chatConnect" }
        override fun chatClear() { did += "chatClear" }
        override fun chatClose() { did += "chatClose" }
        override fun chatSend(text: String) { did += "chatSend:$text" }
        override fun chatNewConversation(done: GraineCallback<Unit>) { did += "newConversation"; newConversationDone = done }
        override fun chatRefreshUpcoming() { did += "refreshUpcoming" }
        override fun chatCancelUpcoming(id: String) { did += "cancelUpcoming:$id" }
        val answered = mutableListOf<Triple<String, Map<String, Any?>, String>>()
        override fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String) { answered += Triple(widgetId, data, summary) }
        val pinsGiven = mutableListOf<NativeChatPin?>()
        override fun chatPin(pin: NativeChatPin?) { pinsGiven += pin; did += "chatPin:${pin?.title}" }
        override fun chatRate(style: String, value: Int) { did += "chatRate:$style:$value" }
        val repliesSent = mutableListOf<NativeQuickReply>()
        override fun chatSendQuickReply(reply: NativeQuickReply) { repliesSent += reply; did += "sendQuickReply:${reply.text}" }
        var attach: (() -> Unit)? = null
        override fun chatSetAttach(handler: (() -> Unit)?) { attach = handler; did += "attach:${handler != null}" }
        fun chatChanged() = chatListeners.forEach { it.run() }

        override var callState = NativeCallState.IDLE
        override var call: GraineCall? = null
        var mutedNow = false
        var speakerNow = false
        override val callMuted: Boolean get() = mutedNow
        override val callSpeaker: Boolean get() = speakerNow
        override var callAgentSpeaking = false
        override var callReconnecting = false
        override var callAgentName: String? = null
        override var callTransport: String? = null
        override var callTransportFallbackReason: String? = null
        override fun addCallListener(listener: GraineCallListener) { callListeners += listener }
        override fun configureCalls(config: NativeCallsConfig) { callsConfig = config }
        override fun startCall(activity: Activity, reason: String?, variables: Map<String, Any?>): String? {
            startedFrom = activity; did += "startCall:$reason:$variables"; return "call_9"
        }
        override fun prepareCall(reason: String?, variables: Map<String, Any?>) { did += "prepareCall:$reason:$variables" }
        override fun callBack(activity: Activity, missedCallId: String?, reason: String?): String? { callBackArgs = missedCallId to reason; return "call_10" }
        override fun answerCall() { did += "answer" }
        override fun declineCall() { did += "decline" }
        override fun endCall() { did += "endCall" }
        override fun setCallMuted(muted: Boolean) { did += "callMuted:$muted" }
        override fun setCallSpeaker(on: Boolean) { did += "speaker:$on" }
        var permissionsNow = NativeCallPermissions(NativeStatus.DENIED, NativeStatus.DENIED, NativeStatus.BLOCKED)
        var permissionsAfterAsking: NativeCallPermissions? = null
        var askedFrom: Activity? = null
        var settingsFrom: Activity? = null
        override val callPermissions: NativeCallPermissions get() = permissionsNow
        override fun requestCallPermissions(activity: Activity?, done: (NativeCallPermissions) -> Unit) {
            askedFrom = activity
            did += "requestCallPermissions"
            permissionsAfterAsking?.let { permissionsNow = it }
            done(permissionsNow)
        }
        override fun openCallSettings(item: NativeCallPermission, activity: Activity?): Boolean {
            settingsFrom = activity
            did += "openCallSettings:${item.wire}"
            return true
        }
        override var callSetupProblems: List<NativeCallSetupProblem> = emptyList()

        override var voiceConnected = false
        override var voiceConnecting = false
        var voiceMutedNow = false
        override val voiceMuted: Boolean get() = voiceMutedNow
        override fun attachVoice(activity: Activity) { did += "attachVoice" }
        override fun startVoice() { did += "startVoice" }
        override fun endVoice() { did += "endVoice" }
        override fun setVoiceMuted(muted: Boolean) { voiceMutedNow = muted; did += "voiceMuted:$muted" }
        var captionsOnNow = true
        override val voiceCaptionsOn: Boolean get() = captionsOnNow
        override var voiceCaptions: List<NativeCaption> = emptyList()
        override fun setVoiceCaptionsOn(on: Boolean) { captionsOnNow = on; did += "captionsOn:$on" }
        override fun clearVoiceCaptions() { voiceCaptions = emptyList(); did += "clearCaptions" }
    }

    private lateinit var port: Port
    private lateinit var heard: Heard
    private lateinit var engine: AndroidEngine
    private var front: Activity? = null
    private var appearanceChanged: (() -> Unit)? = null

    @Before
    fun setUp() {
        port = Port()
        heard = Heard()
        front = null
        engine = AndroidEngine(
            port = port,
            appName = { "Graine Demo" },
            frontActivity = { front },
            wallMs = { 1_760_000_000_000L },
            ringtone = { null },
            onMain = { it() },
            onMainSync = { it() },
            watchAppearance = { appearanceChanged = it },
        )
    }

    private fun start() = engine.start(heard)

    private fun session(
        rules: NativeRules = NativeRules(),
        identity: SessionIdentity? = SessionIdentity("patient_id", 1),
        memory: Boolean? = true,
    ) = SessionConfig(
        agentId = "agent-1", webcallAgentId = "web-1", appearance = JSONObject(), variables = emptyMap(), pageVersion = null,
        declaredActions = setOf("open_screen", "mark_done"), identity = identity, memoryEnabled = memory, schedulingRules = rules,
    )

    // ── the session ───────────────────────────────────────────────────────

    @Test
    fun aSessionTheSdkAlreadyHasIsReportedAtStart() {
        port.session = session(rules = NativeRules(true, 10, 7, 2, 45, "Asha · Graine Care", true, listOf("android")))
        start()
        val s = heard.sessions.single()
        assertEquals("patient_id", s.identityField)
        assertEquals(1, s.devicesPerUser)
        assertEquals(true, s.memoryEnabled)
        assertEquals(GraineSchedulingRules(true, 10, 7, 2, 45, "Asha · Graine Care", true, listOf(GrainePlatform.ANDROID)), s.schedulingRules)
        assertEquals(setOf("open_screen", "mark_done"), s.declaredActions)
    }

    @Test
    fun aSessionWithoutCallsAllowsNoBookingAndTheDefaults() {
        start()
        port.listeners.single().onSessionReady(session(identity = null, memory = null))
        val s = heard.sessions.single()
        assertEquals("user_id", s.identityField)
        assertNull(s.memoryEnabled)
        assertEquals(GraineSchedulingRules(), s.schedulingRules)
    }

    @Test
    fun anErrorBeforeTheSessionIsTheSessionFailing() {
        start()
        port.listeners.single().onError("Publishable key was rejected.")
        assertEquals(listOf("Publishable key was rejected."), heard.failures)
        port.session = session()
        port.listeners.single().onError("A later problem.")
        assertEquals(1, heard.failures.size)
        assertEquals(listOf(GraineEvent.Error("Publishable key was rejected."), GraineEvent.Error("A later problem.")), heard.events)
    }

    // ── identity, memory, the screen ──────────────────────────────────────

    @Test
    fun callsReachTheSdkAsItsOwnTypes() {
        start()
        engine.identify(mapOf("patient_id" to "MRN-1"), false)
        engine.setMemory(true)
        engine.setScreen(
            GraineScreen(
                "kyc", "Upload", GraineScreen.Journey("KYC", 2, 4, "review"),
                listOf(GraineField("pan", "PAN", "ABCDE1234F", GraineFieldStatus.INVALID, "Not recognised", optional = true)),
                "Banner", mapOf("attempts" to 2), listOf("open_screen"),
            )
        )
        engine.setScreen(null)
        engine.updateField(GraineField("otp", status = GraineFieldStatus.LOCKED))
        val native = port.screens.first()!!
        assertEquals("kyc", native.screen)
        assertEquals(NativeScreen.Journey("KYC", 2, 4, "review"), native.journey)
        assertEquals(NativeField("pan", "PAN", "ABCDE1234F", NativeField.Status.INVALID, "Not recognised", true), native.fields.single())
        assertEquals("Banner", native.error)
        assertEquals(mapOf("attempts" to 2), native.data)
        assertEquals(listOf("open_screen"), native.availableActions)
        assertNull(port.screens[1])
        assertEquals(NativeField.Status.LOCKED, port.fields.single().status)
        assertEquals(listOf("identify:{patient_id=MRN-1}:false", "setMemory:true"), port.did)
    }

    @Test
    fun memoriesAndForgettingMapTypesAndErrors() {
        start()
        var memories: Result<List<GraineMemory>>? = null
        engine.memories { memories = it }
        port.memoriesDone!!.onResult(listOf(NativeMemory("m1", "Takes metformin", Date(1_000), null)), null)
        assertEquals(listOf(GraineMemory("m1", "Takes metformin", Instant.fromEpochMilliseconds(1_000), null)), memories!!.getOrThrow())

        engine.memories { memories = it }
        port.memoriesDone!!.onResult(null, GraineError(NativeErrorCode.DEVICE_LIMIT, "Not one of theirs."))
        val e = memories!!.exceptionOrNull() as GraineException
        assertEquals(GraineErrorCode.DeviceLimit, e.code)
        assertEquals("Not one of theirs.", e.message)

        var forgot: Result<Int>? = null
        engine.forgetMemory { forgot = it }
        port.forget!!.onResult(3, null)
        assertEquals("how many the Android SDK says were deleted", 3, forgot!!.getOrThrow())
        engine.forgetMemory { forgot = it }
        port.forget!!.onResult(null, GraineError(NativeErrorCode.Unknown("not_ready"), "The agent could not be loaded."))
        assertEquals(GraineErrorCode.Unknown("not_ready"), (forgot!!.exceptionOrNull() as GraineException).code)
        engine.forgetMemory { forgot = it }
        port.forget!!.onResult(null, GraineError(NativeErrorCode.RATE_LIMITED, "Slow down.", retryAfterSeconds = 30))
        val limited = forgot!!.exceptionOrNull() as GraineException
        assertEquals(GraineErrorCode.RateLimited, limited.code)
        assertEquals(30, limited.retryAfterSeconds)
    }

    // ── actions ───────────────────────────────────────────────────────────

    @Test
    fun anActionsArgumentsAndAnswerCrossAsPlainValues() {
        start()
        var args: Map<String, Any?>? = null
        engine.registerAction("mark_done") { a, answer -> args = a; answer(GraineActionResult.refused("Which one?")) }
        var answered: ActionResult? = null
        port.actions.getValue("mark_done")(JSONObject("""{"item":"metformin","count":2,"ratio":0.5,"ok":true,"none":null,"tags":["a",1],"o":{"k":"v"}}""")) { answered = it }
        assertEquals(
            mapOf("item" to "metformin", "count" to 2L, "ratio" to 0.5, "ok" to true, "none" to null, "tags" to listOf("a", 1L), "o" to mapOf("k" to "v")),
            args,
        )
        assertEquals(ActionResult(ActionResult.Status.REFUSED, "Which one?"), answered)
        assertEquals(ActionResult.ok("Done.", mapOf("n" to 1)), AndroidMapping.result(GraineActionResult.ok("Done.", mapOf("n" to 1))))
        assertEquals(ActionResult(ActionResult.Status.ERROR, "Broke."), AndroidMapping.result(GraineActionResult.error("Broke.")))
        assertEquals(GraineActionResult.error("x"), AndroidMapping.result(ActionResult.error("x")))
    }

    // ── the booking sheet ─────────────────────────────────────────────────

    @Test
    fun theBookingSheetIsTheAndroidSdksOverTheActivityInFront() {
        start()
        assertFalse(engine.bookingAvailable)
        port.bookingAvailable = true
        assertTrue("the Android SDK's own answer, read as it is now", engine.bookingAvailable)

        var heard: Result<GraineScheduledCall?>? = null
        engine.presentBooking("Card replacement") { heard = it }
        assertNull("no activity in front: nothing to show it over, so nothing is booked", heard!!.getOrThrow())
        assertNull(port.bookingDone)

        val activity = Activity()
        front = activity
        heard = null
        engine.presentBooking("Card replacement") { heard = it }
        assertTrue(port.bookingFrom === activity)
        assertEquals("Card replacement", port.bookingReason)
        assertNull("open until the user closes it", heard)
        val at = Instant.parse("2026-10-15T12:30:00Z")
        port.bookingDone!!.onResult(NativeScheduledCall("sc_2", Date(at.toEpochMilliseconds()), NativeCallStatus.SCHEDULED, NativeCallSource.APP, "Card replacement"), null)
        assertEquals(GraineScheduledCall("sc_2", at, GraineCallStatus.Scheduled, GraineCallSource.App, "Card replacement"), heard!!.getOrThrow())

        engine.presentBooking(null) { heard = it }
        port.bookingDone!!.onResult(null, null)
        assertNull("closed without booking", heard!!.getOrThrow())
    }

    // ── scheduling ────────────────────────────────────────────────────────

    @Test
    fun bookingsListsAndCancelsAreTheWireTypes() {
        start()
        var booked: Result<GraineScheduledCall>? = null
        val at = Instant.parse("2026-10-14T12:30:00Z")
        engine.scheduleCall(at, "Weekly check-in") { booked = it }
        assertEquals(Date(at.toEpochMilliseconds()) to "Weekly check-in", port.scheduleArgs)
        port.scheduleDone!!.onResult(NativeScheduledCall("sc_1", Date(at.toEpochMilliseconds()), NativeCallStatus.SCHEDULED, NativeCallSource.APP, "Weekly check-in"), null)
        assertEquals(GraineScheduledCall("sc_1", at, GraineCallStatus.Scheduled, GraineCallSource.App, "Weekly check-in"), booked!!.getOrThrow())

        engine.scheduleCall(at, null) { booked = it }
        port.scheduleDone!!.onResult(null, GraineError(NativeErrorCode.OUTSIDE_WINDOW, "Outside calling hours."))
        assertEquals(GraineErrorCode.OutsideWindow, (booked!!.exceptionOrNull() as GraineException).code)
        engine.scheduleCall(at, null) { booked = it }
        port.scheduleDone!!.onResult(null, null)
        assertEquals(GraineErrorCode.Unavailable, (booked!!.exceptionOrNull() as GraineException).code)

        var listed: Result<List<GraineScheduledCall>>? = null
        engine.scheduledCalls { listed = it }
        port.listDone!!.onResult(
            listOf(
                NativeScheduledCall("a", null, NativeCallStatus.NO_DEVICE, NativeCallSource.DASHBOARD),
                NativeScheduledCall("b", null, NativeCallStatus.Unknown("snoozed")),
            ),
            null,
        )
        assertEquals(
            listOf(GraineScheduledCall("a", null, GraineCallStatus.NoDevice, GraineCallSource.Dashboard), GraineScheduledCall("b", null, GraineCallStatus.Unknown("snoozed"))),
            listed!!.getOrThrow(),
        )

        var cancelled: Result<GraineCallStatus>? = null
        engine.cancelScheduledCall("a") { cancelled = it }
        port.cancelDone!!.onResult(NativeCallStatus.CANCELLED, null)
        assertEquals(GraineCallStatus.Cancelled, cancelled!!.getOrThrow())
        engine.cancelScheduledCall("b") { cancelled = it }
        port.cancelDone!!.onResult(NativeCallStatus.EXPIRED, null)
        assertEquals("the server's word, passed on", GraineCallStatus.Expired, cancelled!!.getOrThrow())
        engine.cancelScheduledCall("z") { cancelled = it }
        port.cancelDone!!.onResult(null, GraineError(NativeErrorCode.NOT_FOUND, "No such call."))
        assertEquals(GraineErrorCode.NotFound, (cancelled!!.exceptionOrNull() as GraineException).code)
    }

    // ── the chat ──────────────────────────────────────────────────────────

    @Test
    fun theChatsTurnsAreTheSdksAndItsFlagsAWord() {
        start()
        val card = GraineWidget.adopt(JSONObject("""{"widget_id":"w1","kind":"choices","data":{"title":"Pick one"}}"""), "https://www.graine.ai")
        port.chatTurns = listOf(
            NativeChatTurn("t1", Role.CUSTOMER, "Where is my order?", Date(1_000), NativeChatTurn.Delivery.SENDING),
            NativeChatTurn("t2", Role.AGENT, "It ships to", Date(2_000), proactive = false, component = card, live = true),
        )
        port.chatConnecting = true
        port.chatTyping = true
        port.chatChanged()
        val (turns, status, typing) = heard.chat!!
        assertEquals(GraineChatStatus.CONNECTING, status)
        assertTrue(typing)
        assertEquals(listOf("t1", "t2"), turns.map { it.id })
        assertEquals(listOf(GraineRole.USER, GraineRole.AGENT), turns.map { it.role })
        assertEquals(listOf(GraineDelivery.SENDING, null), turns.map { it.delivery })
        assertEquals(Instant.fromEpochMilliseconds(1_000), turns[0].at)
        assertTrue(turns[1].live)
        val component = turns[1].component!!
        assertEquals("w1", component.id)
        assertEquals("choices", component.kind)
        assertEquals("Pick one", component.title)

        port.chatConnecting = false; port.chatConnected = true; port.chatChanged()
        assertEquals(GraineChatStatus.ONLINE, heard.chat!!.second)
        port.chatConnected = false; port.chatConnecting = true; port.chatReconnecting = true; port.listeners.single().onChatState(false, true)
        assertEquals(GraineChatStatus.RECONNECTING, heard.chat!!.second)
        port.chatConnecting = false; port.chatReconnecting = false; port.chatFailed = true; port.chatChanged()
        assertEquals(GraineChatStatus.OFFLINE, heard.chat!!.second)
    }

    @Test
    fun aFirstTryThatFailedIsAReconnectAsTheSdkSaysIt() {
        start()
        port.chatConnecting = true; port.chatChanged()
        assertEquals(GraineChatStatus.CONNECTING, heard.chat!!.second)
        // The Android SDK retries a first connect that failed on the same ladder as a drop, and says so.
        port.chatReconnecting = true; port.chatChanged()
        assertEquals("the iOS SDK's word for the same moment", GraineChatStatus.RECONNECTING, heard.chat!!.second)
        port.chatReconnecting = false; port.chatChanged()
        assertEquals("asked again after giving up: a fresh connect", GraineChatStatus.CONNECTING, heard.chat!!.second)
    }

    @Test
    fun theChatsUpcomingCallsAreTheSdksCardAndItsControlsGoToTheSdk() {
        start()
        port.chatUpcomingCalls = listOf(
            NativeScheduledCall("sc_1", Date(1_760_000_000_000), NativeCallStatus.SCHEDULED, NativeCallSource.AGENT, "Your delivery"),
        )
        port.chatChanged()
        val call = heard.upcoming!!.single()
        assertEquals("sc_1", call.id)
        assertEquals(Instant.fromEpochMilliseconds(1_760_000_000_000), call.at)
        assertEquals(GraineCallStatus.Scheduled, call.status)
        assertEquals(GraineCallSource.Agent, call.source)
        assertEquals("Your delivery", call.reason)
        engine.chatRefreshUpcoming(); engine.chatCancelUpcoming("sc_1")
        assertEquals(listOf("refreshUpcoming", "cancelUpcoming:sc_1"), port.did)
    }

    @Test
    fun aComponentAnsweredFromTheAppsOwnUiGoesToTheSdksRouter() {
        start()
        engine.answerWidget("w1", mapOf("id" to "creta"), "The Creta, please.")
        assertEquals(listOf(Triple("w1", mapOf<String, Any?>("id" to "creta"), "The Creta, please.")), port.answered)
    }

    @Test
    fun theMergedVariablesAreTheSdks() {
        start()
        port.merged = mapOf("plan" to "Pro", "name" to "Asha")
        assertEquals(mapOf("plan" to "Pro", "name" to "Asha"), engine.currentVariables())
    }

    @Test
    fun theDesignsTokensAreTheSdksAndFollowTheSessionAndTheAppearance() {
        val light = TOKENS.replace("\"scheme\":\"dark\"", "\"scheme\":\"light\"")
        port.themeTokensJson = null
        start()
        assertEquals("no design yet: no tokens", listOf<GraineThemeTokens?>(null), heard.themes)
        port.themeTokensJson = light
        port.listeners.single().onSessionReady(session())
        assertEquals("light", heard.themes.last()!!.scheme)
        assertEquals("#318CE7", heard.themes.last()!!.colors.accent)
        // The phone switched to dark: the Android SDK's tokens are dark now, and they are read again.
        port.themeTokensJson = TOKENS
        appearanceChanged!!()
        assertTrue(heard.themes.last()!!.isDark)
    }

    @Test
    fun aFinishedAgentMessageIsAnEvent() {
        start()
        port.chatTurns = listOf(
            NativeChatTurn("t1", Role.CUSTOMER, "Hi", Date(1_000)),
            NativeChatTurn("t2", Role.AGENT, "Hello, I am Asha.", Date(2_000), proactive = false),
        )
        port.listeners.single().onChatMessage(Role.AGENT, "Hello", true)
        port.listeners.single().onChatMessage(Role.CUSTOMER, "Hi", false)
        assertTrue(heard.events.isEmpty())
        port.listeners.single().onChatMessage(Role.AGENT, "Hello, I am Asha.", false)
        val turn = (heard.events.single() as GraineEvent.ChatMessage).turn
        assertEquals("t2", turn.id)
        assertEquals("Hello, I am Asha.", turn.text)
        assertFalse(turn.proactive)
    }

    @Test
    fun chatControlsGoToTheSdk() {
        start()
        engine.chatOpen(null); engine.chatSend("Hi"); engine.chatRetry(); engine.chatClear(); engine.chatClose()
        engine.chatOpen(GraineChatSize.LARGE); engine.chatOpen(GraineChatSize.FULL); engine.chatOpen(GraineChatSize.COMPACT)
        // Opening is the Android SDK's open — a bar on screen moves to the chat, at the size — retrying is connecting,
        // and clearing is the Android SDK's clear: the screen only.
        assertEquals(
            listOf("chatOpen:null", "chatSend:Hi", "chatConnect", "chatClear", "chatClose", "chatOpen:LARGE", "chatOpen:FULL", "chatOpen:COMPACT"),
            port.did,
        )
    }

    @Test
    fun aNewConversationIsTheSdksAndItsRefusalTheSharedError() {
        start()
        var result: Result<Unit>? = null
        engine.chatNewConversation { result = it }
        assertEquals(listOf("newConversation"), port.did)
        assertNull("nothing until the Android SDK answers", result)
        port.newConversationDone!!.onResult(Unit, null)
        assertTrue(result!!.isSuccess)
        engine.chatNewConversation { result = it }
        port.newConversationDone!!.onResult(null, GraineError(NativeErrorCode.DEVICE_LIMIT, "This device is not one of theirs."))
        val e = result!!.exceptionOrNull() as GraineException
        assertEquals(GraineErrorCode.DeviceLimit, e.code)
        assertEquals("This device is not one of theirs.", e.message)
    }

    @Test
    fun aRestoredTurnKeepsWhereItWasSaid() {
        start()
        port.chatTurns = listOf(
            NativeChatTurn("b00000000001", Role.AGENT, "Hi, this is Asha.", Date(1_760_353_200_000), restored = true, channel = NativeChatTurn.Channel.VOICE),
            NativeChatTurn("aa0000000002", Role.CUSTOMER, "Where is my order?", Date(1_760_356_800_000), restored = true),
            NativeChatTurn("t1", Role.CUSTOMER, "one", Date(1_760_360_400_000), NativeChatTurn.Delivery.SENDING),
        )
        port.chatChanged()
        val turns = heard.chat!!.first
        assertEquals(listOf(true, true, false), turns.map { it.restored })
        assertEquals(listOf(GraineChatChannel.VOICE, GraineChatChannel.CHAT, GraineChatChannel.CHAT), turns.map { it.channel })
        assertEquals(listOf(null, GraineDelivery.SENT, GraineDelivery.SENDING), turns.map { it.delivery })
        assertEquals(Instant.fromEpochMilliseconds(1_760_353_200_000), turns[0].at)
    }

    // ── chat window v2 ────────────────────────────────────────────────────

    @Test
    fun theChatWindowsStateIsTheSdksInTheSharedTypes() {
        start()
        assertEquals("reported with the chat from the start", ChatExtras(), heard.extras)
        port.chatQuickReplies = listOf(NativeQuickReply("Yes", "Yes, please"), NativeQuickReply("Later", "Later"))
        port.chatChanged()
        assertEquals(listOf(GraineQuickReply("Yes", "Yes, please"), GraineQuickReply("Later", "Later")), heard.extras!!.quickReplies)
        // The agent ended the conversation and the user rated it; the app had pinned a card.
        port.chatQuickReplies = emptyList()
        port.chatEnded = true
        port.chatRating = NativeChatRating(4, 5, "stars")
        port.chatPinned = NativeChatPin("Your plan", "Renews on 14 Nov", "https://example.com/p.png", "Active")
        port.listeners.single().onChatState(false, false)
        assertEquals(
            ChatExtras(emptyList(), true, GraineChatRating(4, 5, "stars"), GraineChatPin("Your plan", "Renews on 14 Nov", "https://example.com/p.png", "Active")),
            heard.extras,
        )
    }

    @Test
    fun theChatWindowsControlsAreTheSdksMembers() {
        start()
        engine.chatPin(GraineChatPin("Your plan", badge = "Active"))
        engine.chatPin(null)
        engine.chatRate("emoji", 5)
        engine.chatSendQuickReply(GraineQuickReply("A person", "I'd like to talk to a person"))
        var taps = 0
        engine.chatSetAttachHandler { taps++ }
        port.attach!!()
        engine.chatSetAttachHandler(null)
        assertEquals(listOf(NativeChatPin("Your plan", null, null, "Active"), null), port.pinsGiven)
        assertEquals("the Android SDK's sendQuickReply, with the reply as its type", listOf(NativeQuickReply("A person", "I'd like to talk to a person")), port.repliesSent)
        assertEquals("a tap on the Android SDK's paperclip is the app's handler", 1, taps)
        assertNull("no handler, no paperclip", port.attach)
        assertEquals(
            listOf("chatPin:Your plan", "chatPin:null", "chatRate:emoji:5", "sendQuickReply:I'd like to talk to a person", "attach:true", "attach:false"),
            port.did,
        )
        // Field for field, both ways.
        val pin = GraineChatPin("Your plan", "Renews on 14 Nov", "https://example.com/p.png", "Active")
        assertEquals(pin, AndroidMapping.pin(AndroidMapping.pin(pin)))
        assertEquals(GraineQuickReply("Yes", "Yes, please"), AndroidMapping.quickReply(AndroidMapping.quickReply(GraineQuickReply("Yes", "Yes, please"))))
        assertEquals(GraineChatRating(0, 1, "thumbs"), AndroidMapping.rating(NativeChatRating(0, 1, "thumbs")))
    }

    @Test
    fun throughTheSharedApiARatingTheAndroidSdkTakesIsHeardOnceAndTheAttachHandlerIsTheApps() {
        Graine.reset()
        try {
            // The shared API over this engine and its port, as configure() puts it together.
            Graine.install(engine)
            val ratings = mutableListOf<GraineChatRating>()
            Graine.chat.onRated = { ratings += it }
            var taps = 0
            Graine.chat.onAttach = { taps++ }
            port.attach!!()
            assertEquals(1, taps)
            Graine.chat.pin(GraineChatPin("  Your plan\n", badge = "  Active "))
            assertEquals("cleaned before the Android SDK has it", NativeChatPin("Your plan", null, null, "Active"), port.pinsGiven.last())
            Graine.chat.rate("thumbs", 1)
            assertEquals("chatRate:thumbs:1", port.did.last())
            // The Android SDK took the rating: its chat changed, and says so more than once.
            port.chatEnded = true
            port.chatRating = NativeChatRating(1, 1, "thumbs")
            port.chatPinned = NativeChatPin("Your plan", null, null, "Active")
            port.chatChanged()
            port.chatChanged()
            assertEquals(listOf(GraineChatRating(1, 1, "thumbs")), ratings)
            assertEquals(GraineChatRating(1, 1, "thumbs"), Graine.chat.rating.value)
            assertTrue(Graine.chat.ended.value)
            assertEquals(GraineChatPin("Your plan", null, null, "Active"), Graine.chat.pinned.value)
            Graine.chat.sendQuickReply(GraineQuickReply("Yes", "Yes, please"))
            assertEquals(NativeQuickReply("Yes", "Yes, please"), port.repliesSent.last())
        } finally {
            Graine.reset()
        }
    }

    @Test
    fun theConfigurationIsTheSdksFieldForField() {
        val native = AndroidMapping.config(
            GraineConfig(
                baseUrl = "https://www.graine.ai", publishableKey = "pk_test_kmp", variables = mapOf("plan" to "Gold"), appVersion = "2.4.0",
                endOnBackground = false, debug = true, captureTaps = false, transport = GraineTransport.WEBRTC, chatSize = GraineChatSize.LARGE,
            )
        )
        assertEquals("https://www.graine.ai", native.baseUrl)
        assertEquals("pk_test_kmp", native.publishableKey)
        assertEquals(mapOf("plan" to "Gold"), native.variables)
        assertEquals("2.4.0", native.appVersion)
        assertFalse(native.endOnBackground)
        assertTrue(native.debug)
        assertFalse(native.captureTaps)
        assertEquals(Transport.WEBRTC, native.transport)
        assertEquals(NativeChatSize.LARGE, native.chatSize)
        val plain = AndroidMapping.config(GraineConfig("https://www.graine.ai", "pk_test_kmp"))
        assertNull("the dashboard decides", plain.chatSize)
        assertNull(plain.transport)
        assertEquals(NativeChatSize.entries.map { it.name }, GraineChatSize.entries.map { AndroidMapping.chatSize(it).name })
    }

    // ── native calls ──────────────────────────────────────────────────────

    private fun ring(id: String = "c1", hosted: Boolean = true) =
        GraineCall(id, GraineCall.Direction.INCOMING, "Asha · Graine Care", "payment_due", emptyMap(), hosted)

    @Test
    fun aRingIsAnIncomingCallAndARingingOutcome() {
        start()
        port.callListeners.single().onIncomingCall(ring())
        assertEquals(GraineIncomingCall("c1", "Asha · Graine Care", "payment_due", true), heard.incoming.single())
        assertEquals(GraineCallOutcome("c1", GraineCallOutcome.Kind.RINGING, hosted = true), heard.outcomes.single())
    }

    @Test
    fun anEndedCallSaysWhoEndedItAndHowLongItLasted() {
        start()
        val c = ring()
        val l = port.callListeners.single()
        l.onIncomingCall(c)
        port.callState = NativeCallState.ACTIVE; port.call = c
        l.onCallState(NativeCallState.ACTIVE, c)
        assertEquals(GraineCallState.ACTIVE, heard.calls!!.state)
        assertTrue(heard.calls!!.incoming && heard.calls!!.hosted)
        l.onCallOutcome("c1", NativeOutcome(NativeOutcome.Kind.ANSWERED, callId = "c1", hosted = true))
        l.onCallCaption(Role.AGENT, "Your payment is due", false)
        assertEquals(GraineCaption(GraineRole.AGENT, "Your payment is due", false), heard.calls!!.caption)
        // The Android SDK measures the call; its length is passed on as it says.
        l.onCallOutcome("c1", NativeOutcome(NativeOutcome.Kind.ENDED, NativeOutcome.EndReason.REMOTE, callId = "c1", durationSeconds = 63, hosted = true))
        assertEquals(
            listOf(
                GraineCallOutcome("c1", GraineCallOutcome.Kind.RINGING, hosted = true),
                GraineCallOutcome("c1", GraineCallOutcome.Kind.ANSWERED, hosted = true),
                GraineCallOutcome("c1", GraineCallOutcome.Kind.ENDED, "remote", 63, true),
            ),
            heard.outcomes,
        )
        port.callState = NativeCallState.IDLE; port.call = null
        l.onCallState(NativeCallState.IDLE, null)
        assertNull(heard.calls!!.caption)
        assertFalse(heard.calls!!.showsCallScreen)
    }

    @Test
    fun aRecoveringCallIsOnTheCallScreenStateOnlyWhileACallIsLive() {
        start()
        val c = ring()
        val l = port.callListeners.single()
        port.callState = NativeCallState.ACTIVE; port.call = c
        l.onCallState(NativeCallState.ACTIVE, c)
        assertFalse(heard.calls!!.reconnecting)
        // The Android SDK's media dropped and is being restored.
        port.callReconnecting = true
        l.onCallReconnecting(true)
        assertTrue("the shared state says the call is recovering", heard.calls!!.reconnecting)
        port.callReconnecting = false
        l.onCallReconnecting(false)
        assertFalse("and that it is back", heard.calls!!.reconnecting)
        // With no call there is nothing to recover, whatever a stale flag says.
        port.callReconnecting = true
        port.callState = NativeCallState.IDLE; port.call = null
        l.onCallState(NativeCallState.IDLE, null)
        assertFalse(heard.calls!!.reconnecting)
    }

    @Test
    fun aRingNobodyAnsweredIsKeptForCallBack() {
        start()
        val c = ring()
        val l = port.callListeners.single()
        l.onIncomingCall(c)
        port.callState = NativeCallState.RINGING; port.call = c
        l.onCallOutcome("c1", NativeOutcome(NativeOutcome.Kind.MISSED, callId = "c1", hosted = true))
        assertEquals(GraineCallOutcome("c1", GraineCallOutcome.Kind.MISSED, "no_answer", null, true), heard.outcomes.last())
        val missed = heard.calls!!.missedCall!!
        assertEquals("c1", missed.callId)
        assertEquals("payment_due", missed.reason)
        assertEquals(Instant.fromEpochMilliseconds(1_760_000_000_000L), missed.at)
        assertTrue(heard.calls!!.showsCallScreen)

        assertNull("no screen in front to call from", engine.callsCallBack())
        assertTrue("the missed call is still there to call back", heard.calls!!.missedCall != null)
        front = Activity()
        assertEquals("call_10", engine.callsCallBack())
        assertEquals("c1" to "payment_due", port.callBackArgs)
        assertNull(heard.calls!!.missedCall)
    }

    @Test
    fun aRingWhileAnotherCallIsLiveIsBusy() {
        start()
        port.call = ring("live")
        port.callState = NativeCallState.ACTIVE
        port.callAgentName = "default"
        port.callListeners.single().onCallOutcome("c2", NativeOutcome(NativeOutcome.Kind.MISSED, callId = "c2", busy = true))
        // Never the live call's agent: the refused ring's is not known here.
        assertEquals(GraineCallOutcome("c2", GraineCallOutcome.Kind.MISSED, "busy"), heard.outcomes.single())
        assertNull(heard.calls!!.missedCall)
    }

    @Test
    fun aCallSaysWhichAgentItIsWithFromRingToItsLastWord() {
        start()
        val c = ring()
        val l = port.callListeners.single()
        // The Android SDK has the ring in progress, routed to the help desk, before it tells anyone.
        port.call = c; port.callState = NativeCallState.RINGING; port.callAgentName = "help"
        l.onCallState(NativeCallState.RINGING, c)
        l.onIncomingCall(c)
        assertEquals("help", heard.incoming.single().agent)
        assertEquals("help", heard.calls!!.agent)
        port.callState = NativeCallState.ACTIVE
        l.onCallOutcome("c1", NativeOutcome(NativeOutcome.Kind.ANSWERED, callId = "c1", hosted = true))
        port.callState = NativeCallState.ENDED
        l.onCallOutcome("c1", NativeOutcome(NativeOutcome.Kind.ENDED, NativeOutcome.EndReason.LOCAL, callId = "c1", durationSeconds = 9, hosted = true))
        // Idle again: no call, so no agent on the screen's state — and a late word on the call still knows its agent.
        port.call = null; port.callState = NativeCallState.IDLE; port.callAgentName = null
        l.onCallState(NativeCallState.IDLE, null)
        assertNull(heard.calls!!.agent)
        assertEquals(listOf("help", "help", "help"), heard.outcomes.map { it.agent })
        assertEquals(GraineCallOutcome("c1", GraineCallOutcome.Kind.ENDED, "local", 9, true, "help"), heard.outcomes.last())

        // A call the user places is with the agent it was placed with: the Android SDK names it from the start.
        val out = GraineCall("call_9", GraineCall.Direction.OUTGOING, null, "kyc_help", emptyMap())
        port.call = out; port.callState = NativeCallState.CONNECTING; port.callAgentName = "default"
        l.onCallState(NativeCallState.CONNECTING, out)
        assertEquals("default", heard.calls!!.agent)
        port.call = null; port.callState = NativeCallState.IDLE; port.callAgentName = null
        l.onCallOutcome("call_9", NativeOutcome(NativeOutcome.Kind.FAILED, error = "The microphone permission was not granted.", callId = "call_9"))
        assertEquals("default", heard.outcomes.last().agent)
        l.onCallOutcome("call_9", NativeOutcome(NativeOutcome.Kind.FAILED, error = "again", callId = "call_9"))
        assertNull("after its last word the call is forgotten", heard.outcomes.last().agent)
    }

    @Test
    fun aCallIsPlacedFromTheScreenInFront() {
        start()
        assertNull(engine.callsStart("kyc_help", emptyMap()))
        front = Activity()
        assertEquals("call_9", engine.callsStart("kyc_help", mapOf("order" to "A-1")))
        assertTrue(port.startedFrom === front)
        engine.callsAnswer(); engine.callsDecline(); engine.callsEnd(); engine.callsSetMuted(true); engine.callsSetSpeaker(false)
        assertEquals(
            listOf("startCall:kyc_help:{order=A-1}", "answer", "decline", "endCall", "callMuted:true", "speaker:false"),
            port.did,
        )
    }

    @Test
    fun aCallIsPreparedWithNoScreenInFront() {
        start()
        // No activity needed, none asked for: nothing is dialled until a call starts.
        engine.callsPrepare("kyc_help", mapOf("order" to "A-1"))
        engine.callsPrepare(null, emptyMap())
        assertEquals(listOf("prepareCall:kyc_help:{order=A-1}", "prepareCall:null:{}"), port.did)
        assertNull("nothing started", port.startedFrom)
    }

    @Test
    fun theCallsConfigurationIsTheSdks() {
        start()
        engine.callsConfigure(GraineCallsConfig(ringTimeoutSeconds = 45, smallIcon = 7))
        assertEquals(NativeCallsConfig(displayName = "Graine Demo", smallIcon = 7, ringtone = null, callActivity = null, ringTimeoutMs = 45_000), port.callsConfig)
        engine.callsConfigure(GraineCallsConfig(displayName = "Asha", ringTimeoutSeconds = 1))
        assertEquals("Asha", port.callsConfig!!.displayName)
        assertEquals(5_000L, port.callsConfig!!.ringTimeoutMs)
        assertEquals("AUTO unless the app chose", NativeCallTransport.AUTO, port.callsConfig!!.callTransport)
        for (t in GraineCallTransport.entries) {
            engine.callsConfigure(GraineCallsConfig(callTransport = t))
            assertEquals(t.name, port.callsConfig!!.callTransport.name)
        }
    }

    @Test
    fun aCallSaysWhatItsAudioRunsOnAndWhyItFellBack() {
        start()
        val l = port.callListeners.single()
        val out = GraineCall("call_9", GraineCall.Direction.OUTGOING, null, "kyc_help", emptyMap())
        port.call = out; port.callState = NativeCallState.CONNECTING; port.callTransport = "webrtc"
        l.onCallState(NativeCallState.CONNECTING, out)
        assertEquals(GraineCallTransport.WEBRTC, heard.calls!!.transport)
        assertNull(heard.calls!!.transportFallbackReason)
        port.callState = NativeCallState.ACTIVE; port.callTransport = "websocket"; port.callTransportFallbackReason = "no_answer"
        l.onCallState(NativeCallState.ACTIVE, out)
        assertEquals(GraineCallTransport.WEBSOCKET, heard.calls!!.transport)
        assertEquals("no_answer", heard.calls!!.transportFallbackReason)
        // Idle: no call, so nothing about its audio — even if the SDK has not cleared it yet.
        port.call = null; port.callState = NativeCallState.IDLE
        l.onCallState(NativeCallState.IDLE, null)
        assertNull(heard.calls!!.transport)
        assertNull(heard.calls!!.transportFallbackReason)
    }

    // ── the voice bar, and what else the SDK reports ──────────────────────

    @Test
    fun theVoiceBarsCallStartsFromTheScreenInFront() {
        start()
        engine.voiceStart()
        assertEquals(listOf("startVoice"), port.did)
        front = Activity()
        engine.voiceStart()
        engine.voiceSetMuted(true)
        assertEquals(GraineVoiceInfo(connected = false, connecting = false, muted = true), heard.voice)
        port.voiceConnected = true
        port.listeners.single().onCallState(true, false)
        assertTrue(heard.voice!!.connected)
        assertEquals(listOf("startVoice", "attachVoice", "startVoice", "voiceMuted:true"), port.did)
    }

    @Test
    fun theCaptionsSwitchAndWhatWasSaidAreTheSdks() {
        start()
        port.voiceConnected = true
        port.voiceCaptions = listOf(NativeCaption(Role.CUSTOMER, "What does it cost?", false), NativeCaption(Role.AGENT, "It costs", true))
        port.listeners.single().onCaption(Role.AGENT, "It costs", true)
        assertEquals(
            listOf(GraineCaption(GraineRole.USER, "What does it cost?", false), GraineCaption(GraineRole.AGENT, "It costs", true)),
            heard.voice!!.captions,
        )
        assertTrue("on by default", heard.voice!!.captionsOn)
        engine.voiceSetCaptionsOn(false)
        assertFalse(heard.voice!!.captionsOn)
        assertEquals("off keeps what was said", 2, heard.voice!!.captions.size)
        engine.voiceClearCaptions()
        assertTrue(heard.voice!!.captions.isEmpty())
        assertTrue("the call goes on", heard.voice!!.connected)
        assertEquals(listOf("captionsOn:false", "clearCaptions"), port.did)
    }

    @Test
    fun componentsFrictionAndActionsAreEvents() {
        start()
        val l = port.listeners.single()
        l.onWidget(JSONObject("""{"widget_id":"w1","kind":"choices","title":"Pick one","options":["a","b"]}"""))
        l.onWidgetPending(JSONObject("""{"kind":"calendar_slots"}"""))
        l.onFriction(FrictionSignal("stalled_on_step", "kyc", "Stuck on PAN.", emptyMap(), 120, 2, false))
        l.onActionRequested("mark_done")
        l.onActionCompleted("mark_done", ActionResult.ok("Done."))
        l.onMicDenied("NotAllowedError")
        l.onCaption(Role.CUSTOMER, "hello", true)
        l.onEnded()
        assertEquals(
            listOf(
                GraineEvent.Component(GraineComponent("w1", "choices", "Pick one", mapOf("widget_id" to "w1", "kind" to "choices", "title" to "Pick one", "options" to listOf("a", "b")))),
                GraineEvent.ComponentPending(mapOf("kind" to "calendar_slots")),
                GraineEvent.Friction("stalled_on_step", "kyc", "Stuck on PAN."),
                GraineEvent.ActionRequested("mark_done"),
                GraineEvent.ActionCompleted("mark_done", GraineActionResult.ok("Done.")),
                GraineEvent.MicrophoneDenied("NotAllowedError"),
                GraineEvent.Caption(GraineRole.USER, "hello", true),
                GraineEvent.VoiceEnded,
            ),
            heard.events,
        )
    }

    @Test
    fun theVersionIsGradles() {
        assertEquals(System.getProperty("graine.version"), GraineKmpVersion.NAME)
    }

    private companion object {
        /** A token set as the Android SDK writes one (ThemeTokens.toJson), dark. */
        val TOKENS = """{"v":1,"preset":"graine-classic","scheme":"dark","colors":{"background":"#0E0F11","surface":"#17181B",
            "surfaceRaised":"#1F2125","text":"#F5F5F4","textMuted":"#A0A5AE","border":"#2A2D33","accent":"#318CE7","onAccent":"#FFFFFF",
            "accentSoft":"#1B2D44","success":"#16A34A","warning":"#D97706","danger":"#DC2626","userBubble":"#318CE7",
            "userBubbleText":"#FFFFFF","agentBubble":"#1F2125","agentBubbleText":"#F5F5F4","scrim":"#000000"}}"""
    }

    // ── permissions and setup ─────────────────────────────────────────────

    @Test
    fun theAndroidSdksPermissionsCrossByTheirWords() {
        start()
        assertEquals(
            GraineCallPermissions(GraineCallPermissionStatus.DENIED, GraineCallPermissionStatus.DENIED, GraineCallPermissionStatus.BLOCKED),
            engine.callsPermissions(),
        )
        port.permissionsAfterAsking = NativeCallPermissions(NativeStatus.GRANTED, NativeStatus.GRANTED, NativeStatus.NOT_NEEDED)
        front = Activity()
        var after: GraineCallPermissions? = null
        engine.callsRequestPermissions { after = it }
        assertTrue(after!!.allGranted)
        assertTrue("asked over the screen in front", port.askedFrom === front)
        assertTrue(engine.callsOpenSettings(GraineCallPermission.FULL_SCREEN))
        assertTrue(port.did.contains("openCallSettings:fullScreen"))
        assertTrue(port.settingsFrom === front)
    }

    @Test
    fun theAndroidSdksSetupProblemsCrossAsTheyAre() {
        start()
        assertTrue(engine.callsSetupProblems().isEmpty())
        port.callSetupProblems = listOf(NativeCallSetupProblem("full_screen_not_allowed", "Offer the setting."))
        assertEquals(listOf(GraineCallSetupProblem("full_screen_not_allowed", "Offer the setting.")), engine.callsSetupProblems())
    }
}
