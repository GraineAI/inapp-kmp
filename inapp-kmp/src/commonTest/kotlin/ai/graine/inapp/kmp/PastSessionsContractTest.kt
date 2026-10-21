package ai.graine.inapp.kmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PAST SESSIONS — the contract's vectors (past-sessions.json, vendored in
 * src/commonTest/contract), run here on the JVM and on iOS: every page and
 * every transcript read by the rules the iOS facade's JSON is read with,
 * every refusal's word kept as the state's error and the transcript's code,
 * and every append case through the shared API — the actions reaching the
 * platform SDK, and the list it holds after each step coming out as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PastSessionsContractTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    private val contract: Map<String, Any?> by lazy { Json.obj(PAST_SESSIONS_JSON) ?: error("past-sessions.json is not JSON") }

    private fun section(name: String): List<Map<*, *>> {
        val list = (contract[name] as List<*>).map { it as Map<*, *> }
        assertTrue(list.isNotEmpty(), "section $name is empty")
        return list
    }

    /** A session as the contract writes what the SDK keeps. */
    private fun expected(o: Map<*, *>) = GraineSessionSummary(
        id = o["id"] as String,
        startedAt = Instant.parse(o["startedAt"] as String),
        endedAt = (o["endedAt"] as? String)?.let { Instant.parse(it) },
        durationSeconds = (o["durationSeconds"] as? Long)?.toInt(),
        channel = GraineSessionChannel.entries.first { it.wire == o["channel"] },
        direction = (o["direction"] as? String)?.let { w -> GraineSessionDirection.entries.first { it.wire == w } },
        status = o["status"] as String,
        turns = (o["turns"] as Long).toInt(),
        preview = o["preview"] as String,
    )

    @Test
    fun everyPageIsKeptAsTheContractSays() {
        for (c in section("pages")) {
            val name = c["name"] as String
            val page = PastSessionsWire.page(c["response"] as Map<*, *>)
            assertEquals((c["sessions"] as List<*>).map { expected(it as Map<*, *>) }, page.sessions, name)
            assertEquals(c["next"] as String?, page.next, name)
            // The iOS facade hands the list over in the same shape: read alike.
            val state = PastSessionsWire.state(Json.write(mapOf("sessions" to (c["response"] as Map<*, *>)["sessions"], "loading" to false, "hasMore" to (c["next"] != null), "error" to null)))
            assertEquals(page.sessions, state.sessions, "$name, across the iOS bridge")
            assertEquals(c["next"] != null, state.hasMore, name)
        }
    }

    @Test
    fun everyTranscriptIsReadInTheSdksWords() {
        for (c in section("transcripts")) {
            val name = c["name"] as String
            val response = c["response"] as Map<*, *>
            // As the iOS facade answers it: JSON in the wire's own shape.
            val t = PastSessionsWire.transcript(Json.write(response), "asked")
            val want = (c["turns"] as List<*>).map { it as Map<*, *> }.map { o ->
                GraineSessionTurn(if (o["role"] == "agent") GraineRole.AGENT else GraineRole.USER, o["text"] as String, Instant.parse(o["at"] as String))
            }
            assertEquals(want, t.turns, name)
            assertEquals(response["id"], t.id, name)
            assertEquals(Instant.parse(response["startedAt"] as String), t.startedAt, name)
            assertEquals(Instant.parse(response["endedAt"] as String), t.endedAt, name)
            assertEquals((response["durationSeconds"] as Long).toInt(), t.durationSeconds, name)
            assertEquals(GraineSessionChannel.CHAT, t.channel, name)
            assertNull(t.direction, name)
            assertEquals("completed", t.status, name)
        }
        // An answer with no id is the session asked for.
        assertEquals("ex-9", PastSessionsWire.transcript("""{"turns":[]}""", "ex-9").id)
        assertEquals(GraineSessionTranscript("ex-9"), PastSessionsWire.transcript(null as String?, "ex-9"))
    }

    @Test
    fun everyRefusalIsTheStatesErrorWordAndTheTranscriptsCode() = runTest {
        Graine.install(engine)
        for (c in section("refusals")) {
            val name = c["name"] as String
            val word = c["error"] as String
            val body = c["body"] as Map<*, *>
            assertFalse(GraineErrorCode.from(word) is GraineErrorCode.Unknown, "$name: a code this build knows")
            // The list's refusal, as the platform SDK keeps it: the word, unchanged across the bridge and into the state.
            val state = PastSessionsWire.state("""{"sessions":[],"loading":false,"hasMore":false,"error":"$word"}""")
            assertEquals(word, state.error, name)
            engine.sink.pastSessions(state)
            assertEquals(word, Graine.chat.pastSessions.value.error, name)
            // The transcript's, as the facade's error carries it: its code word and its sentence.
            engine.transcriptAnswer = Result.failure(Bridge.error(word, body["error"] as String?))
            val e = assertFailsWith<GraineException> { Graine.chat.sessionTranscript("ex-7a1c0001") }
            assertEquals(word, e.code.wire, name)
        }
    }

    @Test
    fun everyAppendCaseRunsThroughTheSharedApi() {
        Graine.install(engine)
        for (c in section("append")) {
            val name = c["name"] as String
            val steps = (c["steps"] as List<*>).map { it as Map<*, *> }
            val states = (c["states"] as List<*>).map { it as Map<*, *> }
            assertEquals(steps.size, states.size, name)
            // Every session the steps' pages carry, by id, in the wire's shape.
            val byId = steps.flatMap { s -> ((s["refresh"] ?: s["more"]) as Map<*, *>)["sessions"] as List<*> }
                .map { it as Map<*, *> }.associateBy { it["id"] as String }
            engine.calls.clear()
            steps.forEachIndexed { i, step ->
                val refresh = step.containsKey("refresh")
                if (refresh) Graine.chat.refreshPastSessions() else Graine.chat.loadMorePastSessions()
                assertEquals(if (refresh) "refreshPastSessions" else "loadMorePastSessions", engine.calls.last(), "$name, step ${i + 1}")
                // The list the platform SDK holds after the step, as its facade hands it over.
                val want = states[i]
                val ids = (want["ids"] as List<*>).map { it as String }
                val held = mapOf("sessions" to ids.map { byId.getValue(it) }, "loading" to false, "hasMore" to want["hasMore"], "error" to null)
                engine.sink.pastSessions(PastSessionsWire.state(Json.write(held)))
                val state = Graine.chat.pastSessions.value
                assertEquals(ids, state.sessions.map { it.id }, "$name, step ${i + 1}: ids")
                assertEquals(want["hasMore"], state.hasMore, "$name, step ${i + 1}: hasMore")
                assertFalse(state.loading, name)
                assertNull(state.error, name)
            }
        }
    }

    @Test
    fun anIdAlreadyKeptOnAPageIsKeptOnce() {
        val page = PastSessionsWire.page(
            Json.obj("""{"sessions":[{"id":"a1","startedAt":"2026-10-21T10:00:00Z","preview":"first"},{"id":"a1","startedAt":"2026-10-21T10:00:00Z","preview":"again"}],"next":" "}"""),
        )
        assertEquals(listOf("first"), page.sessions.map { it.preview })
        assertNull(page.next, "a blank cursor is the end")
    }

    @Test
    fun looseNumbersAndWordsFallBackAsTheContractSays() {
        val s = PastSessionsWire.summary(
            Json.obj("""{"id":"x","startedAt":"2026-10-21T10:00:00.250Z","durationSeconds":59.6,"turns":2.0,"channel":" VOICE ","direction":"Outbound","status":"ended_by_agent","preview":"hi"}"""),
        )!!
        assertEquals(60, s.durationSeconds, "rounded")
        assertEquals(2, s.turns, "2.0 is a whole number")
        assertEquals(GraineSessionChannel.VOICE, s.channel)
        assertEquals(GraineSessionDirection.OUTBOUND, s.direction)
        assertEquals("ended_by_agent", s.status, "passed through")
        val loose = PastSessionsWire.summary(Json.obj("""{"id":"y","startedAt":"2026-10-21T10:00:00Z","durationSeconds":true,"turns":2.5,"status":7}"""))!!
        assertNull(loose.durationSeconds, "a boolean is no number")
        assertEquals(0, loose.turns)
        assertEquals("", loose.status)
        assertNull(PastSessionsWire.summary(Json.obj("""{"id":"z","startedAt":1760000000000}""")), "a number is no time")
        assertEquals(GrainePastSessions(), PastSessionsWire.state("not json"))
    }
}
