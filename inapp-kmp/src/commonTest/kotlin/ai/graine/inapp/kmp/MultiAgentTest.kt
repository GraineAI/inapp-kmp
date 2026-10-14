package ai.graine.inapp.kmp

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
import kotlin.time.Duration.Companion.hours

/** Several agents in one app (MULTI-AGENT.md): what is each agent's own, and what the app has once. */
class MultiAgentTest {
    private lateinit var main: FakeEngine
    private lateinit var support: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        main = FakeEngine()
        support = FakeEngine()
        Graine.install(main)
        Graine.install(support, name = "support")
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    @Test
    fun agentsAreFoundByTheirNameAndTheDefaultIsGraine() {
        val agent = Graine.agent("support")!!
        assertEquals("support", agent.name)
        assertSame(agent, Graine.agent(" support "), "names are trimmed")
        assertSame(Graine.agent(), Graine.agent(""), "empty is the default")
        assertNull(Graine.agent("sales"))
        assertEquals(listOf("default", "support"), Graine.agents.keys.toList())
        assertSame(Graine.agent()!!.chat, Graine.chat)
        // A second configure for a configured name returns that agent and makes nothing.
        assertSame(agent, Graine.configure(GraineConfig("https://www.graine.ai", "pk_test_other"), name = "support"))
        assertEquals(1, support.started)
    }

    @Test
    fun eachAgentHasItsOwnSessionChatAndEvents() {
        val s = Graine.agent("support")!!
        support.sink.session(bookingSession())
        assertNull(Graine.session.value, "the default agent's session is its own")
        assertTrue(s.schedulingRules.usersCanBook)
        assertFalse(Graine.schedulingRules.usersCanBook)
        val turn = GraineChatTurn("1", GraineRole.AGENT, "How can I help?", Instant.fromEpochMilliseconds(1), proactive = true)
        support.sink.chat(listOf(turn), GraineChatStatus.ONLINE, false)
        assertEquals(listOf(turn), s.chat.turns.value)
        assertTrue(Graine.chat.turns.value.isEmpty(), "chat on one agent never shows on another")
        s.chat.send("Where is my refund?")
        assertEquals(listOf("chatSend:Where is my refund?"), support.calls)
        assertTrue(main.calls.isEmpty())
    }

    @Test
    fun actionsSchedulingAndMemoryGoToTheirAgent() = runTest {
        val s = Graine.agent("support")!!
        support.sink.session(bookingSession())
        s.registerAction("open_ticket") { GraineActionResult.ok("Opened.") }
        assertEquals(setOf("open_ticket"), support.handlers.keys)
        assertTrue(main.handlers.isEmpty())
        s.scheduleCall(Clock.System.now() + 2.hours, "Refund")
        assertEquals(1, support.scheduled.size)
        assertTrue(main.scheduled.isEmpty())
        // The default agent's own rules still apply to its own bookings: it says nothing, so nobody may book.
        main.sink.session(GraineSession(agentId = "agent-0", memoryEnabled = true))
        assertEquals(GraineErrorCode.SchedulingOff, assertFailsWith<GraineException> { Graine.scheduleCall(Clock.System.now() + 2.hours) }.code)
        support.forgetAnswer = Result.success(4)
        assertEquals(4, s.forgetMemory())
        assertEquals(listOf("forgetMemory"), support.calls.filter { it == "forgetMemory" })
        s.setVariables(mapOf("ticket" to "T-1"))
        assertEquals("setVariables:[ticket]", support.calls.last())
    }

    @Test
    fun eachAgentSaysWhetherItCanBeBookedAndShowsItsOwnSheet() = runTest {
        val s = Graine.agent("support")!!
        support.bookingAvailable = true
        assertTrue(s.bookingAvailable)
        assertFalse(Graine.bookingAvailable, "the default agent answers for itself")
        val call = GraineScheduledCall("sc_9", Instant.fromEpochMilliseconds(1_800_000_000_000), GraineCallStatus.Scheduled, GraineCallSource.App, null)
        support.bookingAnswer = Result.success(call)
        assertEquals(call, s.presentBooking())
        assertEquals(listOf<String?>(null), support.presented)
        assertTrue(main.presented.isEmpty())
    }

    @Test
    fun whoTheUserIsAndTheScreenAreToldOnceForEveryAgent() {
        Graine.identify(mapOf("user_id" to "u-1"), memory = true)
        Graine.setMemory(false)
        Graine.setScreen(GraineScreen("help"))
        Graine.updateField(GraineField("topic"))
        Graine.removeField("topic")
        Graine.clearScreen("help")
        // The platform SDK gives every agent the same user, memory choice and screen: told once, to the default agent.
        assertEquals(listOf<Pair<Map<String, Any?>?, Boolean?>>(mapOf("user_id" to "u-1") to true), main.identified)
        assertEquals(listOf("identify", "setMemory:false", "setScreen", "updateField:topic", "removeField:topic", "clearScreen:help"), main.calls)
        // An event too — never once per agent.
        Graine.track("faq_opened")
        Graine.tap("contact")
        Graine.noteInteraction()
        assertEquals(listOf("track:faq_opened", "tap:contact", "noteInteraction"), main.calls.takeLast(3))
        assertTrue(support.calls.isEmpty(), "nothing of the app's is told to each agent again")
        assertTrue(support.identified.isEmpty())
        assertEquals(main.installId, Graine.installId)
    }

    @Test
    fun aCallIsPlacedWithTheAgentItNamesAndTheAppHasOneCallEngine() {
        assertEquals("call_1", Graine.calls.startCall("billing", agent = "support"))
        assertEquals("startCall:billing:[]", support.calls.single())
        Graine.calls.startCall("kyc_help")
        assertEquals("startCall:kyc_help:[]", main.calls.single())
        assertEquals("call_1", Graine.agent("support")!!.startCall("refund"))
        // Controls go to the one call engine, reported by the agent configured first.
        Graine.calls.end()
        assertEquals("end", main.calls.last())
        support.sink.calls(GraineCallInfo(GraineCallState.ACTIVE, "c9"))
        assertEquals(GraineCallState.IDLE, Graine.calls.state.value.state, "only the first agent reports the phone's calls")
        main.sink.calls(GraineCallInfo(GraineCallState.ACTIVE, "c9"))
        assertEquals(GraineCallState.ACTIVE, Graine.calls.state.value.state)
        assertFailsWith<IllegalStateException> { Graine.calls.startCall(agent = "sales") }
    }

    @Test
    fun anAppWithOnlyNamedAgentsUsesTheFirstForWhatItHasOnce() {
        Graine.reset()
        val sales = FakeEngine().apply { installId = "aa11bb22-0000-4000-8000-000000000001" }
        Graine.install(sales, name = "sales")
        assertFalse(Graine.isConfigured, "no default agent")
        assertEquals("aa11bb22-0000-4000-8000-000000000001", Graine.installId)
        Graine.identify(mapOf("user_id" to "u-2"))
        assertEquals(1, sales.identified.size)
        assertEquals("call_1", Graine.calls.startCall("demo"))
        val e = assertFailsWith<IllegalStateException> { Graine.chat.open() }
        assertTrue(e.message!!.contains("Graine.configure"))
        assertFailsWith<IllegalStateException> { Graine.agent("support") ?: error("not configured") }
    }
}
