package ai.graine.inapp.kmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PAST SESSIONS AND THE CHAT'S CLOSE DELAY THROUGH THE SHARED API (0.14.3):
 * `Graine.chat.pastSessions` follows the platform SDK's list — cleared when
 * it clears it for another identity — its actions reach the platform SDK, a
 * transcript answers or throws, and `closeDelaySeconds` is kept within 0–300.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PastSessionsTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    private fun configured(): FakeEngine = engine.also { Graine.install(it) }

    private val meeras = GrainePastSessions(
        sessions = listOf(
            GraineSessionSummary("ex-1", Instant.parse("2026-10-21T12:00:05Z"), Instant.parse("2026-10-21T12:04:41Z"), 276, preview = "Can I move my call?"),
            GraineSessionSummary("ex-2", Instant.parse("2026-10-20T08:00:00Z"), channel = GraineSessionChannel.CALL, direction = GraineSessionDirection.OUTBOUND),
        ),
        hasMore = true,
    )

    @Test
    fun theListIsEmptyUntilThePlatformSdkHoldsOne() {
        configured()
        assertEquals(GrainePastSessions(), Graine.chat.pastSessions.value)
        engine.sink.pastSessions(meeras)
        assertEquals(meeras, Graine.chat.pastSessions.value)
    }

    @Test
    fun refreshAndLoadMoreAskThePlatformSdk() {
        configured()
        Graine.chat.refreshPastSessions()
        Graine.chat.loadMorePastSessions()
        assertEquals(listOf("refreshPastSessions", "loadMorePastSessions"), engine.calls)
    }

    @Test
    fun beforeConfigureTheActionsSayWhatIsMissing() {
        assertFailsWith<IllegalStateException> { Graine.chat.refreshPastSessions() }
        assertFailsWith<IllegalStateException> { Graine.chat.loadMorePastSessions() }
        assertEquals(GrainePastSessions(), Graine.chat.pastSessions.value)
    }

    @Test
    fun anotherIdentityClearsTheListAsThePlatformSdkDoes() {
        configured()
        engine.sink.pastSessions(meeras)
        // Another user signs in: the platform SDK lets the last user's list go (DESIGN.md "Who a chat belongs to").
        Graine.identify(mapOf("user_id" to "u-arjun"))
        engine.sink.pastSessions(GrainePastSessions())
        assertEquals(GrainePastSessions(), Graine.chat.pastSessions.value)
        // And signing out, the same.
        engine.sink.pastSessions(meeras)
        Graine.identify(null)
        engine.sink.pastSessions(GrainePastSessions())
        assertEquals(GrainePastSessions(), Graine.chat.pastSessions.value)
        assertEquals(2, engine.identified.size)
    }

    @Test
    fun eachAgentKeepsItsOwnList() {
        configured()
        val sales = FakeEngine()
        Graine.install(sales, "sales")
        sales.sink.pastSessions(meeras)
        assertEquals(GrainePastSessions(), Graine.chat.pastSessions.value)
        assertEquals(meeras, Graine.agent("sales")!!.chat.pastSessions.value)
        Graine.agent("sales")!!.chat.refreshPastSessions()
        assertEquals(listOf("refreshPastSessions"), sales.calls)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun aResetForgetsTheList() {
        configured()
        engine.sink.pastSessions(meeras)
        Graine.reset()
        assertEquals(GrainePastSessions(), Graine.chat.pastSessions.value)
    }

    @Test
    fun aTranscriptAnswersInTheSdksWords() = runTest {
        configured()
        val t = GraineSessionTranscript(
            "ex-1", status = "completed",
            turns = listOf(GraineSessionTurn(GraineRole.AGENT, "Hi Meera", Instant.parse("2026-10-21T12:00:05Z"))),
        )
        engine.transcriptAnswer = Result.success(t)
        assertEquals(t, Graine.chat.sessionTranscript(" ex-1 "))
        assertEquals("sessionTranscript:ex-1", engine.calls.last(), "asked for by its id, trimmed")
    }

    @Test
    fun aBlankIdIsRefusedBeforeThePlatformIsAsked() = runTest {
        configured()
        assertEquals(GraineErrorCode.InvalidRequest, assertFailsWith<GraineException> { Graine.chat.sessionTranscript("  ") }.code)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun anotherUsersSessionIsNotFound() = runTest {
        configured()
        engine.transcriptAnswer = Result.failure(GraineException(GraineErrorCode.NotFound, "Session not found."))
        val e = assertFailsWith<GraineException> { Graine.chat.sessionTranscript("ex-someone-elses") }
        assertEquals(GraineErrorCode.NotFound, e.code)
        assertEquals("Session not found.", e.message)
    }

    // ── the close delay ─────────────────────────────────────────────────────

    @Test
    fun theCloseDelayIsFifteenSecondsByDefault() {
        assertEquals(15, GraineConfig("https://www.graine.ai", "pk_live_test").chatCloseDelaySeconds)
        assertEquals(15, Graine.chat.closeDelaySeconds, "before configure: the default")
        configured()
        assertEquals(15, Graine.chat.closeDelaySeconds, "the platform SDK's own")
    }

    @Test
    fun theCloseDelayIsThePlatformSdksAndSettingItIsKeptWithinZeroToThreeHundred() {
        configured()
        for ((asked, kept) in listOf(40 to 40, 0 to 0, -5 to 0, 300 to 300, 301 to 300, 1000 to 300, Int.MIN_VALUE to 0, Int.MAX_VALUE to 300)) {
            Graine.chat.closeDelaySeconds = asked
            assertEquals(kept, engine.closeDelays.last(), "$asked is handed over as $kept")
            assertEquals(kept, Graine.chat.closeDelaySeconds)
        }
        engine.chatCloseDelaySeconds = 25
        assertEquals(25, Graine.chat.closeDelaySeconds, "read from the platform SDK")
    }

    @Test
    fun settingTheCloseDelayBeforeConfigureSaysWhatIsMissing() {
        assertFailsWith<IllegalStateException> { Graine.chat.closeDelaySeconds = 30 }
    }

    @Test
    fun theConfigsCloseDelayIsKeptWithinZeroToThreeHundred() {
        for ((asked, kept) in listOf(15 to 15, 0 to 0, 40 to 40, -5 to 0, 301 to 300, 1000 to 300)) {
            assertEquals(kept, ChatCloseDelay.clamp(GraineConfig("https://www.graine.ai", "pk", chatCloseDelaySeconds = asked).chatCloseDelaySeconds), "$asked")
        }
    }
}
