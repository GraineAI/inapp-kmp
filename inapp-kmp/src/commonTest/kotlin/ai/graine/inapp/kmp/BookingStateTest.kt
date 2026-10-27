package ai.graine.inapp.kmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * 0.14.4: WHETHER AN AGENT CAN BE BOOKED, AS IT CHANGES — `bookingState`
 * follows the platform SDK's rule whenever what it depends on moves (the
 * session arriving or failing, identify, setMemory), so an app that collects
 * it never keeps the answer it read before the session loaded. And
 * `reportEvent`, which lets the agent speak first, reaches the platform SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookingStateTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    private fun state(available: Boolean, reason: String?) = GraineBookingState(available, reason)

    @Test
    fun beforeConfigureItIsNotLoadedAndConfigureReadsThePlatformsRule() {
        assertEquals(state(false, "not_loaded"), Graine.bookingState.value, "not yet — never a final no")
        engine.bookingUnavailableReason = "rings_not_set_up"
        Graine.install(engine)
        assertEquals(state(false, "rings_not_set_up"), Graine.bookingState.value)
    }

    @Test
    fun theSessionArrivingIsReadAgainBeforeAnyoneHearsItArrived() = runTest {
        engine.bookingUnavailableReason = "not_loaded"
        Graine.install(engine)
        engine.bookingAvailable = true
        engine.bookingUnavailableReason = null
        val agent = Graine.agent(Graine.DEFAULT_AGENT)!!
        val seen = mutableListOf<GraineBookingState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            agent.events.collect { if (it is GraineEvent.SessionReady) seen += Graine.bookingState.value }
        }
        engine.sink.session(GraineSession(agentId = "agent-1", memoryEnabled = true))
        job.cancel()
        assertEquals(state(true, null), Graine.bookingState.value)
        assertEquals(listOf(state(true, null)), seen, "already right when SessionReady is heard")
    }

    /** An app that reads the booking state once, before the session loads, still ends at available. */
    @Test
    fun readOnceBeforeTheSessionTheFlowStillEndsAtAvailable() = runTest {
        engine.bookingUnavailableReason = "not_loaded"
        Graine.install(engine)
        val atLaunch = Graine.bookingState.value
        assertEquals(state(false, "not_loaded"), atLaunch, "read once at launch: not yet")
        val heard = mutableListOf<GraineBookingState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { Graine.bookingState.toList(heard) }
        Graine.identify(mapOf("user_id" to "u-1"))               // identified before the session: still not_loaded
        engine.bookingAvailable = true
        engine.bookingUnavailableReason = null
        engine.sink.session(GraineSession(agentId = "agent-1", memoryEnabled = true))
        assertEquals(state(true, null), heard.last(), "the flow ends at available")
        assertEquals(null, Graine.bookingUnavailableReason, "the reason in step with it")
        engine.bookingAvailable = false
        engine.bookingUnavailableReason = "not_identified"
        Graine.identify(null)                                      // signed out
        job.cancel()
        assertEquals(listOf(state(false, "not_loaded"), state(true, null), state(false, "not_identified")), heard)
    }

    @Test
    fun identifySetMemoryAndAFailedSessionAreReadAgain() {
        Graine.install(engine)
        engine.sink.session(GraineSession(agentId = "agent-1", memoryEnabled = true))
        engine.bookingAvailable = true
        engine.bookingUnavailableReason = null
        Graine.identify(mapOf("user_id" to "u-1"))
        assertEquals(state(true, null), Graine.bookingState.value, "after identify")
        engine.bookingAvailable = false
        engine.bookingUnavailableReason = "not_identified"
        Graine.setMemory(false)
        assertEquals(state(false, "not_identified"), Graine.bookingState.value, "after setMemory")
        engine.bookingUnavailableReason = "not_loaded"
        engine.sink.sessionFailed("The agent's settings could not be loaded.")
        assertEquals(state(false, "not_loaded"), Graine.bookingState.value, "after a failed session")
    }

    @Test
    fun aCollectorHearsEachChangeOnceAndNothingWhenNothingChanged() = runTest {
        engine.bookingUnavailableReason = "not_loaded"
        Graine.install(engine)
        val heard = mutableListOf<GraineBookingState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { Graine.bookingState.toList(heard) }
        engine.sink.session(GraineSession(agentId = "agent-1", memoryEnabled = true))
        engine.bookingAvailable = true
        engine.bookingUnavailableReason = null
        Graine.identify(mapOf("user_id" to "u-1"))
        Graine.identify(mapOf("user_id" to "u-1"))
        job.cancel()
        assertEquals(listOf(state(false, "not_loaded"), state(true, null)), heard)
    }

    @Test
    fun eachAgentAnswersForItselfAndIdentifyReadsEveryOneAgain() {
        Graine.install(engine)
        val support = FakeEngine()
        Graine.install(support, "support")
        val s = Graine.agent("support")!!
        support.bookingAvailable = true
        support.bookingUnavailableReason = null
        support.sink.session(GraineSession(agentId = "agent-2", memoryEnabled = true))
        assertEquals(state(true, null), s.bookingState.value)
        assertEquals(state(false, "scheduling_off"), Graine.bookingState.value, "the default agent answers for itself")
        support.bookingAvailable = false
        support.bookingUnavailableReason = "not_identified"
        Graine.identify(null)
        assertEquals(state(false, "not_identified"), s.bookingState.value, "identify is every agent's")
    }

    @Test
    fun reportEventReachesThePlatformSdkOfItsAgent() {
        assertFailsWith<IllegalStateException> { Graine.reportEvent("reminder_opened") }
        Graine.install(engine)
        Graine.reportEvent(" reminder_opened ", "The user came from the breakfast reminder", mapOf("cooldown_seconds" to 0, "quiet" to false))
        Graine.reportEvent("   ")
        Graine.reportEvent("checked_in", detail = " ")
        assertEquals(
            listOf(
                Triple("reminder_opened", "The user came from the breakfast reminder", mapOf<String, Any?>("cooldown_seconds" to 0, "quiet" to false)),
                Triple("checked_in", null, emptyMap()),
            ),
            engine.reported,
        )
        val support = FakeEngine()
        Graine.install(support, "support")
        Graine.agent("support")!!.reportEvent("plan_viewed")
        assertTrue(support.reported.single().first == "plan_viewed" && engine.reported.size == 2, "the agent's own conversation")
    }
}
