package ai.graine.inapp.kmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * PUBLIC STATE FOR SCREENS OF YOUR OWN (sdk/contract/PUBLIC-STATE.md): every
 * state the SDK's own chat, voice bar, call screen, booking sheet and
 * component renderer read, and every action their controls perform — through
 * the shared API over a fake engine, and across the iOS bridge as the facade
 * hands it over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicStateTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() {
        Graine.reset()
        Graine.actionDispatcher = graineMainDispatcher
    }

    private fun configured(): FakeEngine = engine.also { Graine.install(it) }

    private val now = Instant.parse("2026-10-21T09:00:00Z")
    private val utc = TimeZone.UTC

    private fun turn(id: String, at: String, user: Boolean = false, voice: Boolean = false) =
        GraineChatTurn(id, if (user) GraineRole.USER else GraineRole.AGENT, "…", Instant.parse(at),
            channel = if (voice) GraineChatChannel.VOICE else GraineChatChannel.CHAT)

    // ── the chat ──────────────────────────────────────────────────────────

    @Test
    fun aConversationGraineEndedIsDisconnectedUntilSomethingConnectsAgain() {
        assertEquals(GraineChatStatus.DISCONNECTED, ChatInference.status(connected = false, connecting = false, failed = false, reconnecting = false, disconnected = true))
        assertEquals(GraineChatStatus.DISCONNECTED, ChatInference.status(false, false, failed = true, reconnecting = false, disconnected = true), "said before a give-up")
        assertEquals(GraineChatStatus.CONNECTING, ChatInference.status(false, connecting = true, failed = false, reconnecting = false, disconnected = true), "opening it again connects")
        assertEquals(GraineChatStatus.FAILED, ChatInference.status(false, false, failed = true, reconnecting = false))
        // The iOS facade's word.
        assertEquals(GraineChatStatus.DISCONNECTED, Bridge.chat("""{"turns":[],"status":"disconnected","typing":false}""").status)
    }

    @Test
    fun aConversationTheAgentEndedIsIdleNotFailedAsReactNativeAndFlutterSayIt() {
        val ended = { failed: Boolean -> ChatInference.status(connected = false, connecting = false, failed = failed, reconnecting = false, ended = true) }
        assertEquals(GraineChatStatus.IDLE, ended(false))
        assertEquals(GraineChatStatus.IDLE, ended(true), "the agent saying goodbye is never the chat failing")
        assertEquals(GraineChatStatus.CONNECTED, ChatInference.status(true, false, false, false, ended = true), "the moment held open for a rating")
        assertEquals(GraineChatStatus.CONNECTING, ChatInference.status(false, true, false, false, ended = true), "the next message starts over")
        assertEquals(GraineChatStatus.DISCONNECTED, ChatInference.status(false, false, false, false, disconnected = true, ended = true),
            "Graine's own close still says so")
        // The iOS facade says `offline` there; with `ended` it is idle too.
        assertEquals(GraineChatStatus.IDLE, Bridge.chat("""{"turns":[],"status":"offline","ended":true}""").status)
        assertEquals(GraineChatStatus.FAILED, Bridge.chat("""{"turns":[],"status":"offline","ended":false}""").status)
        assertEquals(GraineChatStatus.DISCONNECTED, Bridge.chat("""{"turns":[],"status":"disconnected","ended":true}""").status)
    }

    @Test
    @Suppress("DEPRECATION")
    fun aChatThatGaveUpIsFailedBesideItsWordAndATryAgainClearsIt() {
        // The iOS facade says a chat that gave up as `offline`: failed, never for one the agent ended or Graine closed.
        assertTrue(Bridge.chat("""{"turns":[],"status":"offline","ended":false}""").extras.failed)
        assertFalse(Bridge.chat("""{"turns":[],"status":"offline","ended":true}""").extras.failed)
        assertFalse(Bridge.chat("""{"turns":[],"status":"disconnected"}""").extras.failed)
        assertFalse(Bridge.chat("""{"turns":[],"status":"reconnecting","attempt":3}""").extras.failed)
        // Through the shared API: the word says it (0.14.5), and the flag beside it stays for code that reads it.
        configured()
        assertFalse(Graine.chat.failed.value)
        engine.sink.chat(emptyList(), GraineChatStatus.FAILED, false)
        engine.sink.chatExtras(ChatExtras(failed = true))
        assertTrue(Graine.chat.failed.value)
        assertEquals(GraineChatStatus.FAILED, Graine.chat.status.value)
        Graine.chat.retry()
        engine.sink.chat(emptyList(), GraineChatStatus.CONNECTING, false)
        engine.sink.chatExtras(ChatExtras(failed = false))
        assertFalse(Graine.chat.failed.value, "tried again")
    }

    /** Who a chat belongs to, through the iOS facade: the snapshot after an identity change, and what the shared state makes of it. */
    @Test
    fun aChatConnectingAgainForANewIdentityReadsAsConnectingWithTheUnsentLineKept() {
        val after = Bridge.chat(
            """{"turns":[{"id":"t2","role":"user","text":"Where is my order?","delivery":"sending","at":2000}],
               "status":"connecting","typing":false,"newConversationBefore":[],"ended":false}""",
        )
        assertEquals(GraineChatStatus.CONNECTING, after.status)
        assertEquals(listOf("Where is my order?"), after.turns.map { it.text })
        assertEquals(GraineDelivery.SENDING, after.turns.single().delivery)
        assertTrue(after.extras.newConversationBefore.isEmpty())
        // And a component of the conversation before is let go once the chat is online again as the new identity.
        configured()
        engine.sink.chat(emptyList(), GraineChatStatus.CONNECTED, false)
        engine.sink.event(GraineEvent.Component(slots))
        engine.sink.chat(after.turns, GraineChatStatus.CONNECTING, false)
        engine.sink.chat(after.turns, GraineChatStatus.CONNECTED, false)
        assertNull(Graine.component.value)
    }

    @Test
    fun theChatsSessionsFollowItsTurnsAndItsSessionId() {
        configured()
        assertNull(Graine.chat.sessionId.value)
        assertTrue(Graine.chat.sessions.value.isEmpty())
        val earlier = turn("b1", "2026-10-20T10:00:00Z", user = true).copy(session = "s-7a1c", restored = true)
        val now1 = turn("t1", "2026-10-21T08:59:00Z", user = true).copy(session = "s-9f2c")
        val now2 = turn("t2", "2026-10-21T08:59:02Z").copy(session = "s-9f2c")
        engine.sink.chat(listOf(earlier, now1, now2), GraineChatStatus.CONNECTED, false)
        assertEquals(listOf("s-7a1c", "s-9f2c"), Graine.chat.sessions.value.map { it.id })
        assertEquals(listOf(false, false), Graine.chat.sessions.value.map { it.current }, "not current until the chat says which is")
        engine.sink.chatExtras(ChatExtras(sessionId = "s-9f2c"))
        assertEquals("s-9f2c", Graine.chat.sessionId.value)
        assertEquals(listOf(false, true), Graine.chat.sessions.value.map { it.current })
        assertEquals(listOf(1, 2), Graine.chat.sessions.value.map { it.turns })
        // Across the iOS bridge: the facade's keys, an empty id none, an older facade none.
        val ios = Bridge.chat("""{"turns":[{"id":"t1","role":"user","text":"Hi","at":1000,"session":"s-1"},{"id":"t2","role":"agent","text":"Hello","at":2000,"session":""}],
            "status":"online","sessionId":"s-1"}""")
        assertEquals(listOf("s-1", null), ios.turns.map { it.session })
        assertEquals("s-1", ios.extras.sessionId)
        assertNull(Bridge.chat("""{"turns":[],"status":"online","sessionId":""}""").extras.sessionId)
        assertNull(Bridge.chat("""{"turns":[{"id":"t1","role":"user","text":"Hi","at":1000}],"status":"online"}""").turns.single().session)
    }

    @Test
    fun theMarkersAndTheChatScreensComingAndGoingAreTheChats() {
        configured()
        assertTrue(Graine.chat.newConversationBefore.value.isEmpty())
        engine.sink.chatExtras(ChatExtras(newConversationBefore = listOf("t4", "t9")))
        assertEquals(listOf("t4", "t9"), Graine.chat.newConversationBefore.value)
        engine.sink.chatExtras(ChatExtras())
        assertTrue(Graine.chat.newConversationBefore.value.isEmpty(), "New conversation or clear takes them away")
        Graine.chat.shown(true)
        Graine.chat.shown(false)
        assertEquals(listOf("chatShown:true", "chatShown:false"), engine.calls)
        // Across the bridge: the facade's ids, a blank or repeated one dropped.
        val chat = Bridge.chat("""{"turns":[],"status":"online","typing":false,"newConversationBefore":["t4","",4,"t4","t9"]}""")
        assertEquals(listOf("t4", "t9"), chat.extras.newConversationBefore)
        assertTrue(Bridge.chat("""{"turns":[],"status":"online"}""").extras.newConversationBefore.isEmpty(), "an older facade: none")
    }

    @Test
    fun theNewConversationRowGoesAfterItsDaySeparatorAndOnlyUnderEarlierRows() {
        val turns = listOf(
            turn("a", "2026-10-20T09:00:00Z"),
            turn("b", "2026-10-20T09:01:00Z", user = true),
            turn("c", "2026-10-21T08:00:00Z"),
        )
        val rows = GraineChatRows.build(turns, listOf("c"), now, utc)
        assertEquals(listOf("day-2026-10-20", "a", "b", "day-2026-10-21", "fresh-c", "c"), rows.map { it.key })
        val row = rows[4] as GraineChatRow.NewConversation
        assertEquals("c", row.before)
        assertEquals(2, row.index)
        assertEquals("Disconnected · new conversation", row.label)
        assertEquals("Disconnected. A new conversation started.", row.spoken)
        assertEquals(GraineChatRows.build(turns, now, utc), GraineChatRows.build(turns, emptyList(), now, utc), "no markers: the rows as before")
        assertTrue(GraineChatRows.build(turns, listOf("a"), now, utc).none { it is GraineChatRow.NewConversation }, "nothing earlier to set apart")
        // A marked voice turn starts a call row of its own.
        val calls = listOf(turn("v1", "2026-10-21T08:00:00Z", voice = true), turn("v2", "2026-10-21T08:00:05Z", voice = true))
        assertEquals(listOf("voice-v1", "fresh-v2", "voice-v2"), GraineChatRows.build(calls, setOf("v2"), now, utc).map { it.key })
    }

    @Test
    fun theFreshVectorsDrawTheirMarkersBeforeTheirTurns() {
        for (c in HistoryVectors.section("fresh")) {
            val name = c["name"] as String
            val expected = (c["turns"] as List<*>).mapIndexed { i, raw ->
                val t = raw as Map<*, *>
                GraineChatTurn("f$i", if (t["role"] == "customer") GraineRole.USER else GraineRole.AGENT, t["text"] as String,
                    HistoryVectors.anyTime + i.seconds)
            }
            val marks = (c["marks"] as List<*>).map { (it as Long).toInt() }
            // The ids as the iOS facade's snapshot carries them, then the rows an app draws from them.
            val snapshot = mapOf("turns" to emptyList<Any>(), "status" to "online", "newConversationBefore" to marks.map { "f$it" })
            val before = Bridge.chat(Json.write(snapshot)).extras.newConversationBefore
            val rows = GraineChatRows.build(expected, before, HistoryVectors.anyTime, utc)
            val drawn = rows.mapIndexedNotNull { k, r -> (r as? GraineChatRow.NewConversation)?.let { k to it } }
            assertEquals(marks, drawn.map { it.second.index }, "$name: a marker before each marked turn")
            for ((k, marker) in drawn) assertEquals("f${marker.index}", (rows[k + 1] as GraineChatRow.Turn).turn.id, "$name: right before it")
        }
    }

    // ── the voice bar ─────────────────────────────────────────────────────

    @Test
    fun theVoiceBarsWordFollowsTheBarsOrder() {
        fun word(connected: Boolean = true, connecting: Boolean = false, muted: Boolean = false, failed: Boolean = false, ended: Boolean = false, phase: String? = null,
                 captions: List<GraineCaption> = emptyList()) = VoiceInference.status(connected, connecting, muted, failed, ended, phase, captions)
        assertEquals(GraineVoiceStatus.IDLE, word(connected = false))
        assertEquals(GraineVoiceStatus.ENDED, word(connected = false, ended = true))
        assertEquals(GraineVoiceStatus.CONNECTING, word(connected = false, connecting = true))
        assertEquals(GraineVoiceStatus.FAILED, word(connected = false, connecting = true, failed = true), "a failure outranks everything")
        assertEquals(GraineVoiceStatus.RECONNECTING, word(connecting = true, phase = "RECONNECTING"))
        assertEquals(GraineVoiceStatus.IDLE, word(connected = false, phase = "RECONNECTING"), "an engine reloading with no call is no call recovering")
        assertEquals(GraineVoiceStatus.MUTED, word(muted = true, phase = "SPEAKING"))
        assertEquals(GraineVoiceStatus.THINKING, word(phase = "THINKING"))
        assertEquals(GraineVoiceStatus.SPEAKING, word(phase = "SPEAKING"))
        assertEquals(GraineVoiceStatus.LISTENING, word(phase = "LISTENING"))
        // No phase from the platform: the captions tell.
        assertEquals(GraineVoiceStatus.SPEAKING, word(captions = listOf(GraineCaption(GraineRole.AGENT, "It costs", true))))
        assertEquals(GraineVoiceStatus.THINKING, word(captions = listOf(GraineCaption(GraineRole.USER, "What does it cost?", false))))
        assertEquals(GraineVoiceStatus.LISTENING, word(captions = listOf(GraineCaption(GraineRole.AGENT, "It costs ₹499.", false))))
        assertEquals(GraineVoiceStatus.SPEAKING, GraineVoiceStatus.of(" Speaking "))
        assertNull(GraineVoiceStatus.of("singing"))
    }

    @Test
    fun theVoiceBarsStateCrossesTheBridge() {
        val v = Bridge.voice(
            """{"connected":true,"connecting":false,"muted":false,"captionsOn":true,"captions":[{"role":"agent","text":"Hello","live":true}],
               "agentLevel":0.42,"micLevel":3,"transport":"webrtc"}""",
        )
        assertEquals(GraineVoiceStatus.SPEAKING, v.status, "derived from what the facade says")
        assertEquals(0.42f, v.agentLevel)
        assertEquals(1f, v.micLevel, "held to 0–1")
        assertEquals(GraineTransport.WEBRTC, v.transport)
        assertNull(v.error)
        val fell = Bridge.voice("""{"connected":true,"status":"listening","transport":"ws","transportFallbackReason":"no_answer"}""")
        assertEquals(GraineVoiceStatus.LISTENING, fell.status, "the facade's own word")
        assertEquals(GraineTransport.WEBSOCKET, fell.transport)
        assertEquals("no_answer", fell.transportFallbackReason)
        val failed = Bridge.voice("""{"connected":false,"connecting":false,"error":"This agent is not set up for calls."}""")
        assertEquals(GraineVoiceStatus.FAILED, failed.status)
        assertEquals("This agent is not set up for calls.", failed.error)
        val old = Bridge.voice("""{"connected":false,"connecting":false,"muted":false}""")
        assertEquals(GraineVoiceInfo(), old, "an older facade: the defaults")
    }

    // ── calls ─────────────────────────────────────────────────────────────

    @Test
    fun theCallScreensStateCrossesTheBridge() {
        val active = Bridge.calls(
            """{"state":"active","callId":"c1","direction":"outgoing","reason":"kyc_help","endReason":"local","agentLevel":0.5,
               "connectedAt":1760000000000,"transport":"webrtc",
               "connection":{"transport":"webrtc","prepared":true,"preparedAgeSeconds":75,"credential":"direct","setupMs":12,"loginMs":null,
                 "answerMs":410,"firstWordsMs":880,"tapToVoiceMs":1302,"audio":{"lossPct":0.28,"jitterAvgMs":12,"jitterPeakMs":40,"rttMs":95,"quality":"good"},
                 "recoveries":1}}""",
        )
        assertEquals("kyc_help", active.reason)
        assertNull(active.endReason, "only an ended call says how it ended")
        assertEquals(0.5f, active.agentLevel)
        assertEquals(Instant.fromEpochMilliseconds(1_760_000_000_000L), active.startedAt)
        assertEquals(
            GraineCallConnection(
                transport = GraineCallTransport.WEBRTC, prepared = true, preparedAgeSeconds = 75, credential = "direct", setupMs = 12, loginMs = null,
                answerMs = 410, firstWordsMs = 880, tapToVoiceMs = 1302,
                audio = GraineCallConnection.Audio(0.28, 12, 40, 95, "good"), recoveries = 1,
            ),
            active.connection,
        )
        assertEquals("remote", Bridge.calls("""{"state":"ended","callId":"c1","endReason":"remote"}""").endReason)
        val old = Bridge.calls("""{"state":"connecting","callId":"c1"}""")
        assertNull(old.connection, "an older facade: nothing known")
        assertNull(old.startedAt)
        assertEquals(0f, old.agentLevel)
        assertTrue(old.captions.isEmpty())
    }

    @Test
    fun theCallsTranscriptCrossesTheBridgeAsTheIosSdkKeepsIt() {
        val said = Bridge.calls(
            """{"state":"active","callId":"c1","caption":{"role":"agent","text":"It costs","live":true},
               "transcript":[{"role":"user","text":"What does it cost?","live":false},{"role":"agent","text":"It costs","live":true},{"text":7},"x"]}""",
        )
        assertEquals(
            listOf(GraineCaption(GraineRole.USER, "What does it cost?", false), GraineCaption(GraineRole.AGENT, "It costs", true)),
            said.captions, "the iOS SDK's transcript; a line without words is skipped",
        )
        assertEquals(said.captions.last(), said.caption)
        assertEquals(1, Bridge.calls("""{"state":"ended","callId":"c1","transcript":[{"role":"agent","text":"Bye","live":false}]}""").captions.size,
            "kept through the end")
        assertTrue(Bridge.calls("""{"state":"idle","transcript":[{"role":"agent","text":"Bye","live":false}]}""").captions.isEmpty(), "no call, no transcript")
        assertEquals(listOf("Hi"), Bridge.calls("""{"state":"active","captions":[{"role":"agent","text":"Hi"}],"transcript":[]}""").captions.map { it.text },
            "`captions` read first")
    }

    @Test
    fun aConnectionReportIsReadLeniently() {
        val c = Bridge.connection(mapOf("transport" to "websocket", "setupMs" to "12", "answerMs" to -3L, "tapToVoiceMs" to 1301.6, "recoveries" to null, "audio" to "x"))
        assertEquals(GraineCallConnection(transport = GraineCallTransport.WEBSOCKET, tapToVoiceMs = 1302), c, "a fact of the wrong type is not known")
        assertEquals(GraineCallConnection(), Bridge.connection(emptyMap<String, Any?>()))
    }

    @Test
    fun anOutcomeCarriesHowTheCallConnected() {
        val o = Bridge.outcome("""{"callId":"c1","outcome":"ended","reason":"local","durationSeconds":64,"connection":{"transport":"webrtc","recoveries":2}}""")!!
        assertEquals(GraineCallConnection(transport = GraineCallTransport.WEBRTC, recoveries = 2), o.connection)
        assertNull(Bridge.outcome("""{"callId":"c1","outcome":"ended"}""")!!.connection)
    }

    @Test
    fun theCallScreensStateIsGraineCallsState() {
        configured()
        val connection = GraineCallConnection(transport = GraineCallTransport.WEBRTC, tapToVoiceMs = 900)
        engine.sink.calls(GraineCallInfo(GraineCallState.ACTIVE, callId = "c1", reason = "kyc_help", agentLevel = 0.3f, connection = connection))
        assertEquals(connection, Graine.calls.state.value.connection)
        assertEquals("kyc_help", Graine.calls.state.value.reason)
        assertEquals(0.3f, Graine.calls.state.value.agentLevel)
    }

    @Test fun theLoudspeakerByDefaultReachesEveryEngineSetBeforeOrAfterConfigure() {
        assertTrue(Graine.calls.speakerByDefault, "the default")
        Graine.calls.speakerByDefault = false
        configured()
        assertEquals(listOf(false), engine.speakerDefaults, "told as the engine is made")
        Graine.calls.speakerByDefault = true
        assertEquals(listOf(false, true), engine.speakerDefaults)
        assertTrue(Graine.calls.speakerByDefault)
    }

    @Test fun whoEndedTheCallCrossesTheBridge() {
        assertEquals("media_drop", Bridge.outcome("""{"callId":"c1","outcome":"ended","reason":"failed","cause":"media_drop"}""")!!.endCause,
            "the iOS facade's key")
        assertEquals("provider_reset", Bridge.outcome("""{"callId":"c1","outcome":"ended","reason":"failed","endCause":"provider_reset"}""")!!.endCause)
        assertNull(Bridge.outcome("""{"callId":"c1","outcome":"ended","reason":"local"}""")!!.endCause, "an older facade says nothing")
        assertEquals("remote_bye", Bridge.calls("""{"state":"ended","callId":"c1","endReason":"remote","endCause":"remote_bye"}""").endCause)
        assertNull(Bridge.calls("""{"state":"active","callId":"c1","endCause":"remote_bye"}""").endCause, "only an ended call says who ended it")
    }

    // ── booking ───────────────────────────────────────────────────────────

    @Test
    fun whyBookingIsUnavailableIsTheNativeSdksWord() {
        assertEquals("not_loaded", Graine.bookingUnavailableReason, "before configure: nothing is known")
        configured()
        assertEquals("scheduling_off", Graine.bookingUnavailableReason)
        engine.bookingUnavailableReason = null
        engine.bookingAvailable = true
        assertNull(Graine.bookingUnavailableReason, "read as it is now")
    }

    @Test
    fun theSlotsAreTheBookingSheetsForTheDayAsked() {
        assertTrue(Graine.bookingSlots(LocalDate(2026, 10, 22)).isEmpty(), "before configure: none")
        configured()
        engine.slots = listOf(Instant.parse("2026-10-22T04:30:00Z"), Instant.parse("2026-10-22T04:35:00Z"))
        assertEquals(engine.slots, Graine.bookingSlots(LocalDate(2026, 10, 22)))
        assertEquals(listOf("bookingSlots:2026-10-22"), engine.calls)
        assertEquals(engine.slots, Graine.agent()!!.bookingSlots(LocalDate(2026, 10, 22)))
    }

    // ── components ────────────────────────────────────────────────────────

    private val slots = GraineComponent(
        "w1", "calendar_slots", "Pick a time",
        mapOf("widget_id" to "w1", "kind" to "calendar_slots", "data" to mapOf("title" to "Pick a time", "slots" to listOf("10:00")), "fallback_text" to "Say a time."),
    )

    @Test
    fun aComponentSaysWhatItShowsAndWhatToSayWithoutIt() {
        assertEquals(mapOf("title" to "Pick a time", "slots" to listOf("10:00")), slots.data)
        assertEquals("Say a time.", slots.fallbackText)
        val bare = GraineComponent("w2", null, null, mapOf("widget_id" to "w2", "fallback_text" to " "))
        assertTrue(bare.data.isEmpty())
        assertNull(bare.fallbackText)
    }

    @Test
    fun theComponentOnScreenIsTheOneWaitingForAnAnswer() {
        configured()
        assertNull(Graine.component.value)
        engine.sink.event(GraineEvent.ComponentPending(mapOf("kind" to "calendar_slots")))
        assertTrue(Graine.componentPending.value, "on its way")
        engine.sink.event(GraineEvent.Component(slots))
        assertFalse(Graine.componentPending.value)
        assertEquals(slots, Graine.component.value)
        Graine.chat.answerWidget("w1", mapOf("slot" to "10:00"), "10 am, please")
        assertNull(Graine.component.value, "answered")
        engine.sink.event(GraineEvent.Component(slots))
        Graine.chat.dismissWidget("w1")
        assertNull(Graine.component.value, "closed")
        assertTrue(engine.calls.contains("dismissWidget:w1"))
        engine.sink.event(GraineEvent.Component(slots))
        Graine.chat.dismissWidget("w9")
        assertEquals(slots, Graine.component.value, "another id closes nothing on screen")
        engine.sink.event(GraineEvent.VoiceEnded)
        assertNull(Graine.component.value, "its conversation is over")
    }

    @Test
    fun aChatThatConnectsAgainLetsTheLastConversationsComponentGo() {
        configured()
        engine.sink.chat(emptyList(), GraineChatStatus.CONNECTED, false)
        engine.sink.event(GraineEvent.Component(slots))
        engine.sink.chat(emptyList(), GraineChatStatus.CONNECTED, true)
        assertEquals(slots, Graine.component.value, "the same connection")
        engine.sink.chat(emptyList(), GraineChatStatus.RECONNECTING, false)
        engine.sink.chat(emptyList(), GraineChatStatus.CONNECTED, false)
        assertNull(Graine.component.value)
    }

    @Test
    fun aLoaderWithNothingArrivingGoesAfterTwelveSeconds() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        engine.sink.event(GraineEvent.ComponentPending(emptyMap()))
        runCurrent()
        advanceTimeBy(11_999)
        runCurrent()
        assertTrue(Graine.componentPending.value)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(Graine.componentPending.value, "the SDK's renderer gives up too")
    }

    // ── waiting for the agent's reply (0.14.8) ────────────────────────────

    private fun answer(agent: GraineAgent? = null) =
        (agent?.chat ?: Graine.chat).answerWidget("w1", mapOf("slot" to "10:00"), "10 am, please")

    private fun said(id: String, text: String, user: Boolean = false, live: Boolean = false, restored: Boolean = false) =
        GraineChatTurn(id, if (user) GraineRole.USER else GraineRole.AGENT, text, now, live = live, restored = restored)

    @Test
    fun anAnswerIsAwaitedUntilTheAgentStartsItsReply() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        val waiting = { Graine.componentAwaitingReply.value }
        assertFalse(waiting(), "nothing answered")
        engine.sink.event(GraineEvent.Component(slots))
        answer()
        assertTrue(waiting(), "answered: the agent has not started")
        assertNull(Graine.component.value, "and the component is off the screen")
        engine.sink.event(GraineEvent.Caption(GraineRole.USER, "10 am, please", false))
        engine.sink.event(GraineEvent.ChatMessage(said("u1", "10 am, please", user = true)))
        assertTrue(waiting(), "the user's own words are not the reply")
        engine.sink.event(GraineEvent.Caption(GraineRole.AGENT, "Booked", true))
        assertFalse(waiting(), "a caption of the agent's on the call")

        answer()
        engine.sink.event(GraineEvent.ChatMessage(said("a9", "Booked for 10 am.")))
        assertFalse(waiting(), "a message of the agent's in the chat")
        answer()
        engine.sink.event(GraineEvent.Component(slots))
        assertFalse(waiting(), "the next component")
        assertEquals(slots, Graine.component.value)
        answer()
        engine.sink.event(GraineEvent.ComponentPending(emptyMap()))
        assertFalse(waiting(), "the next component's loader")
        answer()
        engine.sink.event(GraineEvent.VoiceEnded)
        assertFalse(waiting(), "the call is over")
        answer()
        Graine.chat.dismissWidget("w1")
        assertFalse(waiting(), "closed")
        answer()
        engine.sink.chat(emptyList(), GraineChatStatus.RECONNECTING, false)
        assertTrue(waiting())
        engine.sink.chat(emptyList(), GraineChatStatus.CONNECTED, false)
        assertFalse(waiting(), "a chat that connects again is a new conversation")
        Graine.chat.answerWidget("w1", mapOf("slot" to "10:00"), "  ")
        assertFalse(waiting(), "no sentence: the conversation does not move, so no reply is coming")
        assertEquals(8, engine.answers.size, "every answer reached the platform")
    }

    @Test
    fun theAgentsFirstWordsInTheChatEndTheWaitBeforeItsMessageIsWhole() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        val asked = said("a1", "Which time suits you?")
        val chose = said("u1", "10 am, please", user = true)
        engine.sink.chat(listOf(asked), GraineChatStatus.CONNECTED, false)
        answer()
        engine.sink.chat(listOf(asked, chose), GraineChatStatus.CONNECTED, true)
        assertTrue(Graine.componentAwaitingReply.value, "typing is not talking")
        engine.sink.chat(listOf(asked, chose, said("a2", "", live = true)), GraineChatStatus.CONNECTED, true)
        assertTrue(Graine.componentAwaitingReply.value, "a turn with no words yet")
        engine.sink.chat(listOf(asked, chose, said("a2", "Booked", live = true)), GraineChatStatus.CONNECTED, false)
        assertFalse(Graine.componentAwaitingReply.value, "its first words")

        // A turn already under way when the user answered is not the reply, and neither is one from an earlier conversation.
        answer()
        engine.sink.chat(listOf(asked, chose, said("a2", "Booked for 10 am", live = true)), GraineChatStatus.CONNECTED, false)
        engine.sink.chat(listOf(asked, chose, said("a2", "Booked for 10 am."), said("r1", "Hello again", restored = true)), GraineChatStatus.CONNECTED, false)
        assertTrue(Graine.componentAwaitingReply.value)
        engine.sink.chat(
            listOf(asked, chose, said("a2", "Booked for 10 am."), said("u2", "And a reminder?", user = true), said("a3", "Sure", live = true)),
            GraineChatStatus.CONNECTED, false,
        )
        assertFalse(Graine.componentAwaitingReply.value, "the agent's next turn")
    }

    @Test
    fun theAgentsVoiceStartingOnTheCallEndsTheWait() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        val voice = { status: GraineVoiceStatus -> engine.sink.voice(GraineVoiceInfo(connected = true, status = status)) }
        voice(GraineVoiceStatus.LISTENING)
        answer()
        voice(GraineVoiceStatus.THINKING)
        assertTrue(Graine.componentAwaitingReply.value)
        voice(GraineVoiceStatus.SPEAKING)
        assertFalse(Graine.componentAwaitingReply.value, "its voice, before any caption")
        // Answered while it was still speaking: only its voice starting again is the reply.
        answer()
        voice(GraineVoiceStatus.SPEAKING)
        assertTrue(Graine.componentAwaitingReply.value, "the sentence it was finishing")
        voice(GraineVoiceStatus.LISTENING)
        voice(GraineVoiceStatus.SPEAKING)
        assertFalse(Graine.componentAwaitingReply.value)
    }

    @Test
    fun aReplyThatNeverStartsIsNoLongerAwaitedAfterTwelveSeconds() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        answer()
        runCurrent()
        advanceTimeBy(11_999)
        runCurrent()
        assertTrue(Graine.componentAwaitingReply.value)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(Graine.componentAwaitingReply.value, "the SDK's renderer stops waiting too")
        // Another answer waits 12 seconds of its own.
        answer()
        advanceTimeBy(10_000)
        runCurrent()
        answer()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(Graine.componentAwaitingReply.value, "the second answer's 12 seconds")
        advanceTimeBy(2_001)
        runCurrent()
        assertFalse(Graine.componentAwaitingReply.value)
        // A reply that started takes its timer with it: it never cuts the next answer's wait short.
        answer()
        advanceTimeBy(5_000)
        runCurrent()
        engine.sink.event(GraineEvent.Caption(GraineRole.AGENT, "Sure", true))
        answer()
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(Graine.componentAwaitingReply.value)
        Graine.reset()
        assertFalse(Graine.componentAwaitingReply.value)
    }

    @Test
    fun eachAgentWaitsForItsOwnReplyAndGraineIsTheDefaultAgents() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        val other = FakeEngine()
        Graine.install(other, "support")
        val support = Graine.agent("support")!!
        assertSame(Graine.agent()!!.componentAwaitingReply, Graine.componentAwaitingReply)
        answer(support)
        assertTrue(support.componentAwaitingReply.value)
        assertFalse(Graine.componentAwaitingReply.value, "the default agent was not answered")
        assertTrue(other.calls.contains("answerWidget:w1") && !engine.calls.contains("answerWidget:w1"))
        engine.sink.event(GraineEvent.Caption(GraineRole.AGENT, "Hello", true))
        assertTrue(support.componentAwaitingReply.value, "another agent's words are not its reply")
        other.sink.event(GraineEvent.Caption(GraineRole.AGENT, "Booked", true))
        assertFalse(support.componentAwaitingReply.value)
    }
}
