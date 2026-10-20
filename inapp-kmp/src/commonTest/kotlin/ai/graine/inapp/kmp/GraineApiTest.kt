package ai.graine.inapp.kmp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class GraineApiTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    private fun configured(): FakeEngine = engine.also { Graine.install(it) }

    // ── configure ─────────────────────────────────────────────────────────

    @Test
    fun everyCallBeforeConfigureSaysWhatIsMissing() {
        assertFalse(Graine.isConfigured)
        val e = assertFailsWith<IllegalStateException> { Graine.identify(mapOf("user_id" to "u-1")) }
        assertTrue(e.message!!.contains("Graine.configure"))
        assertFailsWith<IllegalStateException> { Graine.installId }
        assertFailsWith<IllegalStateException> { Graine.chat.open() }
        assertFailsWith<IllegalStateException> { Graine.calls.startCall() }
    }

    @Test
    fun configureStartsTheEngineOnceAndASecondConfigureIsIgnored() {
        configured()
        assertTrue(Graine.isConfigured)
        assertEquals(1, engine.started)
        // The first configuration stays; nothing is made or started again.
        Graine.configure(GraineConfig(baseUrl = "https://www.graine.ai", publishableKey = "pk_test_other"))
        assertEquals(1, engine.started)
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", Graine.installId)
    }

    // ── the booking sheet ────────────────────────────────────────────────

    @Test
    fun bookingAvailableIsTheNativeSdksAnswerAndFalseBeforeConfigure() {
        assertFalse(Graine.bookingAvailable, "nothing configured: nothing can be booked")
        configured()
        assertFalse(Graine.bookingAvailable)
        engine.bookingAvailable = true
        assertTrue(Graine.bookingAvailable, "read as it is now, never cached")
        engine.bookingAvailable = false
        assertFalse(Graine.bookingAvailable)
    }

    @Test
    fun theSheetReturnsTheBookedCallOrNullWhenClosed() = runTest {
        configured()
        assertNull(Graine.presentBooking(), "closed without booking")
        val call = GraineScheduledCall("sc_1", Instant.fromEpochMilliseconds(1_800_000_000_000), GraineCallStatus.Scheduled, GraineCallSource.App, "Card replacement")
        engine.bookingAnswer = Result.success(call)
        assertEquals(call, Graine.presentBooking("Card replacement"))
        assertEquals(listOf<String?>(null, "Card replacement"), engine.presented)
        // The sheet stays open until the user closes it: the call waits for it.
        engine.bookingAnswer = null
        val waiting = async { Graine.presentBooking() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        waiting.cancel()
        engine.bookingAnswer = Result.failure(GraineException(GraineErrorCode.Unavailable, "No screen to show it on."))
        assertEquals(GraineErrorCode.Unavailable, assertFailsWith<GraineException> { Graine.presentBooking() }.code)
    }

    @Test
    fun theSheetNeedsAConfiguredAgent() = runTest {
        assertFailsWith<IllegalStateException> { Graine.presentBooking() }
    }

    // ── identity and memory ───────────────────────────────────────────────

    @Test
    fun identifyAndMemoryGoToThePlatformAsGiven() {
        configured()
        Graine.identify(mapOf("patient_id" to "MRN-1", "name" to "Riya"), memory = false)
        Graine.identify(null)
        Graine.setMemory(true)
        assertEquals(listOf<Pair<Map<String, Any?>?, Boolean?>>(mapOf("patient_id" to "MRN-1", "name" to "Riya") to false, null to null), engine.identified)
        assertEquals("setMemory:true", engine.calls.last())
        assertTrue(Graine.memoryEnabled)
    }

    @Test
    fun forgetMemorySaysHowManyWereDeleted() = runTest {
        configured().forgetAnswer = Result.success(7)
        assertEquals(7, Graine.forgetMemory())
    }

    @Test
    fun aRefusedForgetIsTheServersCode() = runTest {
        configured().forgetAnswer = Result.failure(Bridge.error("identity_required", "Sign in first."))
        val e = assertFailsWith<GraineException> { Graine.forgetMemory() }
        assertEquals(GraineErrorCode.IdentityRequired, e.code)
        assertEquals("Sign in first.", e.message)
    }

    @Test
    fun memoriesComeBackNewestFirstAsTheyCame() = runTest {
        val a = GraineMemory("m2", "Takes metformin at 8 pm", Instant.parse("2026-10-13T10:00:00Z"))
        val b = GraineMemory("m1", "Feels dizzy without breakfast", Instant.parse("2026-10-12T10:00:00Z"))
        configured().memoriesAnswer = Result.success(listOf(a, b))
        assertEquals(listOf(a, b), Graine.memories())
    }

    @Test
    fun aDeviceThatIsNotTheUsersCannotReadTheirMemories() = runTest {
        configured().memoriesAnswer = Result.failure(Bridge.error("device_limit", "This device is not one of theirs.", retryAfterSeconds = null))
        assertEquals(GraineErrorCode.DeviceLimit, assertFailsWith<GraineException> { Graine.memories() }.code)
    }

    @Test
    fun aRequestThatIsNeverAnsweredCanBeCancelled() = runTest {
        configured().memoriesAnswer = null
        val job = async { runCatching { Graine.memories() } }
        runCurrent()
        job.cancel()
        assertTrue(job.isCancelled)
    }

    @Test
    fun aPlatformThatAnswersTwiceIsHeardOnce() = runTest {
        val answers = mutableListOf<(Result<Int>) -> Unit>()
        val twice = object : GraineEngine by engine {
            override fun forgetMemory(done: (Result<Int>) -> Unit) { answers += done }
        }
        Graine.install(twice)
        val result = async { Graine.forgetMemory() }
        runCurrent()
        answers.single()(Result.success(1))
        answers.single()(Result.success(9))
        assertEquals(1, result.await())
    }

    // ── the screen ────────────────────────────────────────────────────────

    @Test
    fun screenCallsReachThePlatformUnchanged() {
        configured()
        val screen = GraineScreen(
            "kyc", title = "Upload", journey = GraineScreen.Journey("KYC", 2, 4, "review"),
            fields = listOf(GraineField("pan", "PAN", "ABCDE1234F", GraineFieldStatus.INVALID, "Not recognised")),
            data = mapOf("attempts" to 2), availableActions = listOf("open_screen"),
        )
        Graine.setScreen(screen)
        Graine.updateField(GraineField("otp", status = GraineFieldStatus.EMPTY))
        Graine.removeField("otp")
        Graine.clearScreen("kyc")
        Graine.clearScreen()
        Graine.track("otp_retry", mapOf("n" to 2))
        Graine.track("  ")
        Graine.tap("pay")
        Graine.noteInteraction()
        Graine.setVariables(mapOf("cart_total" to 1299))
        assertSame(screen, engine.screens.single())
        assertEquals(
            listOf("setScreen", "updateField:otp", "removeField:otp", "clearScreen:kyc", "clearScreen:null", "track:otp_retry",
                "tap:pay", "noteInteraction", "setVariables:[cart_total]"),
            engine.calls,
        )
    }

    // ── actions ───────────────────────────────────────────────────────────

    @Test
    fun anActionThatDoesNotSuspendAnswersInsideThePlatformsCall() {
        configured()
        Graine.registerAction("mark_done") { args -> GraineActionResult.ok("${args["item"]} is done.", mapOf("item" to args["item"])) }
        val answer = engine.runAction("mark_done", mapOf("item" to "metformin"))
        assertEquals(GraineActionResult.ok("metformin is done.", mapOf("item" to "metformin")), answer)
    }

    @Test
    fun anActionThatThrowsAnswersErrorWithItsMessage() {
        configured()
        Graine.registerAction("boom") { throw IllegalArgumentException("No such row.") }
        assertEquals(GraineActionResult.error("No such row."), engine.runAction("boom", emptyMap()))
    }

    @Test
    fun anActionThatSuspendsAnswersWhenItIsDone() = runTest {
        configured()
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        Graine.registerAction("upload") { delay(1_000); GraineActionResult.refused("Too large.") }
        val answer = CompletableDeferred<GraineActionResult>()
        engine.runActionLater("upload", emptyMap()) { answer.complete(it) }
        runCurrent()
        assertFalse(answer.isCompleted)
        advanceUntilIdle()
        assertEquals(GraineActionResult.refused("Too large."), answer.await())
    }

    @Test
    fun unregisteringRemovesTheHandler() {
        configured()
        val unregister = Graine.registerAction("open_screen") { GraineActionResult.ok() }
        unregister()
        assertEquals(listOf("open_screen"), engine.unregistered)
        assertEquals(GraineActionResult.Status.REFUSED, engine.runAction("open_screen", emptyMap())?.status)
    }

    // ── scheduling ────────────────────────────────────────────────────────

    @Test
    fun aBookingWaitsForTheSessionThenChecksItsRules() = runTest {
        configured()
        val at = Clock.System.now() + 2.hours
        val booking = async { Graine.scheduleCall(at, "  Weekly check-in  ") }
        runCurrent()
        assertTrue(engine.scheduled.isEmpty(), "nothing is sent before the session says booking is allowed")
        engine.sink.session(bookingSession())
        val call = booking.await()
        assertEquals("sc_1", call.id)
        assertEquals(GraineCallStatus.Scheduled, call.status)
        assertTrue(call.isUpcoming && call.canCancel)
        assertEquals(at to "Weekly check-in", engine.scheduled.single())
    }

    @Test
    fun aBookingTheAgentDoesNotAllowIsRefusedBeforeAnythingIsSent() = runTest {
        configured().sink.session(bookingSession(usersCanBook = false))
        val e = assertFailsWith<GraineException> { Graine.scheduleCall(Clock.System.now() + 1.days) }
        assertEquals(GraineErrorCode.SchedulingOff, e.code)
        assertTrue(engine.scheduled.isEmpty())
    }

    @Test
    fun aSessionThatSaysNothingAboutBookingAllowsNone() = runTest {
        configured().sink.session(GraineSession(agentId = "agent-1", memoryEnabled = null))
        assertEquals(GraineErrorCode.SchedulingOff, assertFailsWith<GraineException> { Graine.scheduleCall(Clock.System.now() + 1.days) }.code)
        assertFalse(Graine.schedulingRules.usersCanBook)
    }

    @Test
    fun tooSoonAndTooFarAreRefusedLocally() = runTest {
        configured().sink.session(bookingSession(minLeadMinutes = 30, maxDaysAhead = 7))
        val soon = assertFailsWith<GraineException> { Graine.scheduleCall(Clock.System.now() + 10.minutes) }
        assertEquals(GraineErrorCode.TooSoon, soon.code)
        assertEquals("Pick a time at least 30 minutes from now", soon.message)
        val far = assertFailsWith<GraineException> { Graine.scheduleCall(Clock.System.now() + 8.days) }
        assertEquals(GraineErrorCode.TooFar, far.code)
        assertEquals("Pick a time within 7 days", far.message)
        assertTrue(engine.scheduled.isEmpty())
    }

    @Test
    fun theServersRefusalComesBackAsItsCode() = runTest {
        configured().sink.session(bookingSession())
        engine.scheduleAnswer = Result.failure(Bridge.error("outside_window", "That time is outside calling hours."))
        val e = assertFailsWith<GraineException> { Graine.scheduleCall(Clock.System.now() + 3.hours) }
        assertEquals(GraineErrorCode.OutsideWindow, e.code)
        assertEquals(1, engine.scheduled.size)
    }

    @Test
    fun aRateLimitKeepsTheServersWait() = runTest {
        configured().sink.session(bookingSession())
        engine.scheduleAnswer = Result.failure(Bridge.error("rate_limited", "Slow down.", retryAfterSeconds = 30))
        assertEquals(30, assertFailsWith<GraineException> { Graine.scheduleCall(Clock.System.now() + 3.hours) }.retryAfterSeconds)
    }

    @Test
    fun aSessionThatCannotLoadLeavesTheDecisionToThePlatform() = runTest {
        configured()
        val booking = async { Graine.scheduleCall(Clock.System.now() + 3.hours) }
        runCurrent()
        engine.sink.sessionFailed("Could not load the agent.")
        assertEquals("sc_1", booking.await().id)
    }

    @Test
    fun aSessionThatNeverArrivesIsWaitedForAtMostTwentySeconds() = runTest {
        configured()
        val booking = async { Graine.scheduleCall(Clock.System.now() + 3.hours) }
        advanceUntilIdle()
        assertEquals(1, engine.scheduled.size, "after the wait, the platform decides")
        booking.await()
    }

    @Test
    fun aLongReasonIsCutTo120Characters() = runTest {
        configured().sink.session(bookingSession())
        Graine.scheduleCall(Clock.System.now() + 3.hours, "x".repeat(300))
        assertEquals(120, engine.scheduled.single().second!!.length)
    }

    @Test
    fun scheduledCallsAndCancellingReturnTheTypes() = runTest {
        val upcoming = GraineScheduledCall("sc_1", Instant.parse("2026-10-14T12:30:00Z"), GraineCallStatus.Scheduled, GraineCallSource.Agent, null)
        configured().listAnswer = Result.success(listOf(upcoming))
        assertEquals(listOf(upcoming), Graine.scheduledCalls())
        assertEquals(GraineCallStatus.Cancelled, Graine.cancelScheduledCall("sc_1"))
        assertEquals("cancel:sc_1", engine.calls.last())
        assertEquals(GraineErrorCode.InvalidRequest, assertFailsWith<GraineException> { Graine.cancelScheduledCall(" ") }.code)
        engine.cancelAnswer = Result.failure(Bridge.error("not_found", "No such call."))
        assertEquals(GraineErrorCode.NotFound, assertFailsWith<GraineException> { Graine.cancelScheduledCall("sc_9") }.code)
    }

    // ── what the platform reports ─────────────────────────────────────────

    @Test
    fun theSessionIsAStateAndAnEvent() = runTest {
        configured()
        val events = async(UnconfinedTestDispatcher(testScheduler)) { Graine.events.first() }
        val session = bookingSession()
        engine.sink.session(session)
        assertEquals(session, Graine.session.value)
        assertEquals(GraineEvent.SessionReady(session), events.await())
        assertTrue(Graine.schedulingRules.usersCanBook)
    }

    @Test
    fun eventsAreNotReplayedToALateCollector() = runTest {
        configured().sink.event(GraineEvent.Error("early"))
        val events = mutableListOf<GraineEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { Graine.events.collect { events += it } }
        engine.sink.event(GraineEvent.ActionRequested("open_screen"))
        job.cancel()
        assertEquals(listOf<GraineEvent>(GraineEvent.ActionRequested("open_screen")), events)
    }

    @Test
    fun theChatIsThreeStates() = runTest {
        configured()
        val turn = GraineChatTurn("1", GraineRole.USER, "Hi", Instant.fromEpochMilliseconds(1), GraineDelivery.SENDING)
        engine.sink.chat(listOf(turn), GraineChatStatus.CONNECTING, true)
        assertEquals(listOf(turn), Graine.chat.turns.value)
        assertEquals(GraineChatStatus.CONNECTING, Graine.chat.status.value)
        assertTrue(Graine.chat.typing.value)
        Graine.chat.open(); Graine.chat.send("  "); Graine.chat.send("Where is my order?"); Graine.chat.retry(); Graine.chat.close()
        assertEquals(listOf("chatOpen", "chatSend:Where is my order?", "chatRetry", "chatClose"), engine.calls)
    }

    @Test
    fun aComponentIsAnsweredFromTheAppsOwnUiAndTheUpcomingCallsAreTheChats() {
        configured()
        Graine.chat.answerWidget("w1", mapOf("ids" to listOf("creta", "seltos")), "Compare: Creta and Seltos")
        assertEquals(Triple("w1", mapOf<String, Any?>("ids" to listOf("creta", "seltos")), "Compare: Creta and Seltos"), engine.answers.single())
        assertTrue(Graine.chat.upcomingCalls.value.isEmpty(), "none before the platform says")
        val call = GraineScheduledCall("sc_1", Instant.parse("2026-10-16T12:30:00Z"), GraineCallStatus.Scheduled, GraineCallSource.App, "Talk through my order")
        engine.sink.upcomingCalls(listOf(call))
        assertEquals(listOf(call), Graine.chat.upcomingCalls.value)
        Graine.chat.refreshUpcomingCalls()
        Graine.chat.cancelUpcomingCall("  ")
        Graine.chat.cancelUpcomingCall("sc_1")
        assertEquals(listOf("answerWidget:w1", "refreshUpcoming", "cancelUpcoming:sc_1"), engine.calls)
    }

    @Test
    fun theCaptionsSwitchIsTheVoicesAndOnByDefault() {
        configured()
        assertTrue(Graine.voice.state.value.captionsOn, "an accessibility default")
        assertTrue(Graine.voice.state.value.captions.isEmpty())
        Graine.voice.setCaptionsOn(false)
        Graine.voice.clearCaptions()
        assertEquals(listOf("captionsOn:false", "clearCaptions"), engine.calls)
        val said = listOf(GraineCaption(GraineRole.AGENT, "Hello", live = false))
        engine.sink.voice(GraineVoiceInfo(connected = true, captionsOn = false, captions = said))
        assertFalse(Graine.voice.state.value.captionsOn)
        assertEquals(said, Graine.voice.state.value.captions)
    }

    @Test
    fun theMergedVariablesAreWhatThePlatformWillSend() {
        configured()
        engine.mergedVariables = mapOf("plan" to "Pro", "name" to "Asha")
        assertEquals(mapOf("plan" to "Pro", "name" to "Asha"), Graine.currentVariables())
        assertEquals(Graine.currentVariables(), Graine.agent()!!.currentVariables(), "the default agent's")
    }

    @Test
    fun theDesignsTokensAreAFlowThePlatformFeedsNullUntilThen() {
        assertNull(Graine.theme.value, "readable before configure: no tokens")
        configured()
        val tokens = ThemeBridge.parse(
            """{"scheme":"dark","colors":{"background":"#0E0F11","surface":"#17181B","surfaceRaised":"#1F2125","text":"#F5F5F4",
                "textMuted":"#A0A5AE","border":"#2A2D33","accent":"#318CE7","onAccent":"#FFFFFF","accentSoft":"#1B2D44",
                "success":"#16A34A","warning":"#D97706","danger":"#DC2626","userBubble":"#318CE7","userBubbleText":"#FFFFFF",
                "agentBubble":"#1F2125","agentBubbleText":"#F5F5F4","scrim":"#000000"}}"""
        )!!
        engine.sink.theme(tokens)
        assertEquals(tokens, Graine.theme.value)
        assertEquals(tokens, Graine.agent()!!.theme.value, "the default agent's")
        engine.sink.theme(null)
        assertNull(Graine.theme.value, "a session without a design")
    }

    @Test
    fun clearAsksThePlatformToEmptyTheScreenAndNothingElse() {
        configured()
        Graine.chat.clear()
        assertEquals(listOf("chatClear"), engine.calls, "the screen only: no close, no new conversation, no forget")
    }

    @Test
    fun theChatOpensAtTheSizeAskedForAndTheConfigCarriesOne() {
        configured()
        Graine.chat.open(GraineChatSize.LARGE)
        Graine.chat.open()
        Graine.chat.open(size = GraineChatSize.FULL)
        assertEquals(listOf("chatOpen:large", "chatOpen", "chatOpen:full"), engine.calls)
        assertNull(GraineConfig("https://www.graine.ai", "pk_test").chatSize, "the dashboard's size unless the app says")
        assertEquals(GraineChatSize.COMPACT, GraineConfig("https://www.graine.ai", "pk_test", chatSize = GraineChatSize.COMPACT).chatSize)
        assertEquals(listOf("compact", "large", "full"), GraineChatSize.entries.map { it.wire })
    }

    @Test
    fun aNewConversationIsTheAgentsAndARefusalIsItsCode() = runTest {
        configured()
        Graine.chat.newConversation()
        assertEquals("newConversation", engine.calls.single())
        engine.newConversationAnswer = Result.failure(Bridge.error("device_limit", "This device is not one of theirs."))
        assertEquals(GraineErrorCode.DeviceLimit, assertFailsWith<GraineException> { Graine.chat.newConversation() }.code)
        engine.newConversationAnswer = null
        val waiting = async { Graine.chat.newConversation() }
        runCurrent()
        assertFalse(waiting.isCompleted, "it returns once the platform has started the fresh thread")
        waiting.cancel()
    }

    @Test
    fun restoredTurnsKeepWhereTheyWereSaid() {
        configured()
        val call = GraineChatTurn("h1", GraineRole.AGENT, "Hi, this is Asha.", Instant.parse("2026-10-13T11:00:00Z"), restored = true, channel = GraineChatChannel.VOICE)
        val typed = GraineChatTurn("t1", GraineRole.USER, "Is my order shipped?", Instant.parse("2026-10-14T09:30:00Z"), GraineDelivery.SENDING)
        engine.sink.chat(listOf(call, typed), GraineChatStatus.CONNECTING, false)
        assertEquals(listOf(call, typed), Graine.chat.turns.value)
        assertTrue(Graine.chat.turns.value[0].restored)
        assertFalse(typed.restored, "a turn said just now is not restored")
        assertEquals(GraineChatChannel.CHAT, typed.channel, "and was typed in the chat")
    }

    @Test
    fun callsSayWhichAgentTheyAreWith() = runTest {
        configured()
        val outcomes = async(UnconfinedTestDispatcher(testScheduler)) { Graine.calls.outcomes.first() }
        val incoming = async(UnconfinedTestDispatcher(testScheduler)) { Graine.calls.incoming.first() }
        engine.sink.incomingCall(GraineIncomingCall("c1", "Help desk", null, true, agent = "help"))
        engine.sink.callOutcome(GraineCallOutcome("c1", GraineCallOutcome.Kind.RINGING, hosted = true, agent = "help"))
        engine.sink.calls(GraineCallInfo(GraineCallState.RINGING, "c1", "Help desk", incoming = true, hosted = true, agent = "help"))
        assertEquals("help", incoming.await().agent)
        assertEquals("help", outcomes.await().agent)
        assertEquals("help", Graine.calls.state.value.agent)
        assertNull(GraineCallInfo().agent, "no call: no agent")
    }

    @Test
    fun aRecoveringCallReachesTheCallScreenState() = runTest {
        configured()
        engine.sink.calls(GraineCallInfo(GraineCallState.ACTIVE, "c1", "Asha"))
        assertFalse(Graine.calls.state.value.reconnecting)
        engine.sink.calls(GraineCallInfo(GraineCallState.ACTIVE, "c1", "Asha", reconnecting = true))
        assertTrue(Graine.calls.state.value.reconnecting, "the platform says the call is recovering")
        engine.sink.calls(GraineCallInfo(GraineCallState.ACTIVE, "c1", "Asha"))
        assertFalse(Graine.calls.state.value.reconnecting, "and then that it is back")
    }

    @Test
    fun outcomesReachEveryCollectorInOrder() = runTest {
        configured()
        val heard = async(UnconfinedTestDispatcher(testScheduler)) { Graine.calls.outcomes.take(2).toList() }
        val ringing = GraineCallOutcome("c1", GraineCallOutcome.Kind.RINGING, hosted = true)
        val ended = GraineCallOutcome("c1", GraineCallOutcome.Kind.ENDED, "remote", 63, true)
        engine.sink.callOutcome(ringing)
        engine.sink.callOutcome(ended)
        assertEquals(listOf(ringing, ended), heard.await())
    }

    @Test
    fun theCallScreenStateFollowsThePlatform() = runTest {
        configured()
        assertFalse(Graine.calls.state.value.showsCallScreen)
        engine.sink.calls(GraineCallInfo(GraineCallState.RINGING, "c1", "Asha", incoming = true, hosted = true))
        assertTrue(Graine.calls.state.value.showsCallScreen)
        val missed = GraineMissedCall("c1", "Asha", null, true, Instant.fromEpochMilliseconds(5))
        engine.sink.calls(GraineCallInfo(missedCall = missed))
        assertTrue(Graine.calls.state.value.showsCallScreen, "a missed ring keeps the screen for Call back")
        val incoming = async(UnconfinedTestDispatcher(testScheduler)) { Graine.calls.incoming.first() }
        engine.sink.incomingCall(GraineIncomingCall("c2", "Asha", null, true))
        assertEquals("c2", incoming.await().callId)
    }

    @Test
    fun callControlsGoToThePlatform() {
        configured()
        Graine.calls.configure(GraineCallsConfig(displayName = "Asha · Graine Care", ringTimeoutSeconds = 45))
        Graine.calls.enableVoIPPushes()
        assertEquals("call_1", Graine.calls.startCall("  ", mapOf("order" to "A-1")))
        Graine.calls.answer(); Graine.calls.decline(); Graine.calls.end()
        Graine.calls.setMuted(true); Graine.calls.setSpeaker(true)
        assertEquals("call_2", Graine.calls.callBack())
        Graine.calls.dismissMissedCall()
        assertEquals("Asha · Graine Care", engine.callsConfig?.displayName)
        assertEquals(
            listOf("callsConfigure", "enableVoIPPushes", "startCall:null:[order]", "answer", "decline", "end", "callMuted:true",
                "speaker:true", "callBack", "dismissMissed"),
            engine.calls,
        )
    }

    @Test
    fun prepareCallHandsTheNextCallToThePlatformAndAnswersNothing() {
        configured()
        Graine.calls.prepareCall("  ", mapOf("order" to "A-1"))
        Graine.calls.prepareCall("kyc_help")
        Graine.agent()!!.prepareCall("kyc_help", mapOf("order" to "A-2"))
        assertEquals(listOf("prepareCall:null:[order]", "prepareCall:kyc_help:[]", "prepareCall:kyc_help:[order]"), engine.calls)
        assertEquals(mapOf<String, Any?>("order" to "A-1"), engine.prepared.first().second)
        assertEquals(GraineCallState.IDLE, Graine.calls.state.value.state, "nothing is placed")
    }

    // The platform SDKs never dial a call prepared for anyone else (the Android and iOS suites prove it on
    // their engines); the shared code's part is handing every change of who the user is to the platform, in
    // order, before the call it precedes.

    @Test
    fun anotherUsersCallFollowsTheSignOutAndTheNewUserToThePlatform() {
        configured()
        Graine.identify(mapOf("user_id" to "user-a"))
        Graine.calls.prepareCall("help")
        Graine.identify(null)
        Graine.identify(mapOf("user_id" to "user-b"))
        assertEquals("call_1", Graine.calls.startCall("help"))
        assertEquals(listOf("identify", "prepareCall:help:[]", "identify", "identify", "startCall:help:[]"), engine.calls)
        assertEquals(listOf(mapOf<String, Any?>("user_id" to "user-a"), null, mapOf<String, Any?>("user_id" to "user-b")), engine.identified.map { it.first })
    }

    @Test
    fun aNewMemoryChoiceReachesThePlatformBeforeTheCall() {
        configured()
        Graine.identify(mapOf("user_id" to "user-a"), memory = true)
        Graine.calls.prepareCall("help")
        Graine.setMemory(false)
        Graine.calls.startCall("help")
        assertEquals(listOf("identify", "prepareCall:help:[]", "setMemory:false", "startCall:help:[]"), engine.calls)
        assertFalse(engine.memoryEnabled)
    }

    @Test
    fun theVoiceBarsCallIsAStateAndThreeControls() {
        configured()
        engine.sink.voice(GraineVoiceInfo(connected = true, muted = true))
        assertEquals(GraineVoiceInfo(connected = true, connecting = false, muted = true), Graine.voice.state.value)
        Graine.voice.start(); Graine.voice.setMuted(false); Graine.voice.end()
        assertEquals(listOf("voiceStart", "voiceMuted:false", "voiceEnd"), engine.calls)
    }

    @Test
    fun theVersionIsTheOneGradlePublishes() {
        assertEquals("0.13.0", GraineKmpVersion.NAME)
    }

    @Test
    fun aResetClearsEveryState() {
        configured().sink.session(bookingSession())
        engine.sink.voice(GraineVoiceInfo(connected = true))
        Graine.reset()
        assertNull(Graine.session.value)
        assertEquals(GraineVoiceInfo(), Graine.voice.state.value)
        assertFalse(Graine.isConfigured)
    }

    // ── permissions and setup ─────────────────────────────────────────────

    @Test
    fun theCallPermissionsAreThePlatformsAndNothingAsksAtConfigure() = runTest {
        val e = configured()
        assertFalse("requestCallPermissions" in e.calls, "the app chooses when to ask")
        assertEquals(GraineCallPermissionStatus.DENIED, Graine.calls.callPermissions.microphone)
        assertFalse(Graine.calls.callPermissions.allGranted)
        e.permissionsAfterAsking = GraineCallPermissions(
            GraineCallPermissionStatus.GRANTED, GraineCallPermissionStatus.NOT_NEEDED, GraineCallPermissionStatus.NOT_NEEDED,
        )
        val after = Graine.calls.requestCallPermissions()
        assertTrue(after.allGranted)
        assertEquals(GraineCallPermissionStatus.GRANTED, after[GraineCallPermission.MICROPHONE])
        assertTrue(Graine.calls.openCallSettings(GraineCallPermission.FULL_SCREEN))
        assertTrue("openCallSettings:fullScreen" in e.calls)
    }

    @Test
    fun setupProblemsAreThePlatformsAndCallsBeforeConfigureSayWhatIsMissing() {
        assertFailsWith<IllegalStateException> { Graine.calls.callPermissions }
        assertFailsWith<IllegalStateException> { Graine.calls.callSetupProblems }
        val e = configured()
        assertTrue(Graine.calls.callSetupProblems.isEmpty())
        e.setupProblems = listOf(GraineCallSetupProblem("no_push_token", "Hand FCM's token to Graine.calls.onNewToken."))
        assertEquals("no_push_token", Graine.calls.callSetupProblems.single().code)
    }

    @Test
    fun aPermissionsWordIsReadTheSameWayOnEveryPlatform() {
        assertEquals(GraineCallPermission.FULL_SCREEN, GraineCallPermission.of("full_screen"))
        assertEquals(GraineCallPermission.NOTIFICATIONS, GraineCallPermission.of("Notifications"))
        assertNull(GraineCallPermission.of("camera"))
        assertEquals(GraineCallPermissionStatus.NOT_NEEDED, GraineCallPermissionStatus.of("notNeeded"))
        assertEquals(GraineCallPermissionStatus.DENIED, GraineCallPermissionStatus.of("maybe"), "never granted by accident")
        assertEquals(GraineCallPermissionStatus.DENIED, GraineCallPermissionStatus.of(null))
        assertTrue(GraineCallPermissionStatus.NOT_NEEDED.isSatisfied)
        assertFalse(GraineCallPermissionStatus.BLOCKED.isSatisfied)
    }
}
