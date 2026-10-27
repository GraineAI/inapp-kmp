package ai.graine.inapp.kmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.14.5 — THE PUBLIC STATE IN THE CONTRACT'S WORDS (sdk/contract/PUBLIC-STATE.md):
 * the chat's status (§1), a native call's phase (§3), and the size an app event
 * is held to (§8). The rules are React Native's and Flutter's (`chatStatusFrom`,
 * `GraineChatSession.status`; `phaseOf`, `GraineCallPhase.of`), word for word.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicStateParityTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    private fun user(id: String, delivery: GraineDelivery) =
        GraineChatTurn(id, GraineRole.USER, "Are you there?", Instant.fromEpochMilliseconds(1_000), delivery)

    // ── the chat ──────────────────────────────────────────────────────────

    @Test
    fun everyStatusSaysTheWordEverySdkUses() {
        assertEquals(
            listOf("idle", "connecting", "connected", "reconnecting", "offline", "failed", "disconnected"),
            GraineChatStatus.entries.map { it.wire },
        )
        assertEquals(GraineChatStatus.FAILED, GraineChatStatus.of(" Failed "))
        assertEquals(null, GraineChatStatus.of("online"), "not a word of the contract's")
    }

    @Test
    @Suppress("DEPRECATION")
    fun onlineIsConnectedByItsOldName() {
        assertEquals(GraineChatStatus.CONNECTED, GraineChatStatus.ONLINE)
        val said = when (GraineChatStatus.CONNECTED) {
            GraineChatStatus.ONLINE -> "online"
            else -> "something else"
        }
        assertEquals("online", said, "a `when` branch on ONLINE still matches a connected chat")
    }

    /** From the Android SDK's flags: the contract's rule, first match wins. */
    @Test
    fun theFlagsMakeTheContractsWord() {
        fun s(connected: Boolean = false, connecting: Boolean = false, failed: Boolean = false, reconnecting: Boolean = false,
              disconnected: Boolean = false, ended: Boolean = false, waiting: Boolean = false) =
            ChatInference.status(connected, connecting, failed, reconnecting, disconnected, ended, waiting)
        assertEquals(GraineChatStatus.CONNECTED, s(connected = true, failed = true, disconnected = true, waiting = true))
        assertEquals(GraineChatStatus.RECONNECTING, s(connecting = true, reconnecting = true, waiting = true))
        assertEquals(GraineChatStatus.CONNECTING, s(connecting = true, waiting = true))
        assertEquals(GraineChatStatus.DISCONNECTED, s(disconnected = true, failed = true, waiting = true))
        assertEquals(GraineChatStatus.IDLE, s(ended = true, failed = true, waiting = true), "the agent's goodbye is never the chat failing")
        assertEquals(GraineChatStatus.FAILED, s(failed = true, waiting = true))
        assertEquals(GraineChatStatus.OFFLINE, s(waiting = true), "a message waiting, nothing connecting it")
        assertEquals(GraineChatStatus.IDLE, s())
        assertTrue(ChatInference.waiting(listOf(user("1", GraineDelivery.SENT), user("2", GraineDelivery.SENDING))))
        assertTrue(!ChatInference.waiting(listOf(user("1", GraineDelivery.FAILED))), "a line that could not go is not waiting")
    }

    /** The iOS facade: GraineInApp 0.14.5 says the contract's word (`phase`); an older one its own (`status`). */
    @Test
    fun theIosFacadeIsReadInEitherWords() {
        assertEquals(GraineChatStatus.FAILED, Bridge.chat("""{"turns":[],"status":"offline","phase":"failed"}""").status)
        assertEquals(GraineChatStatus.OFFLINE, Bridge.chat("""{"turns":[],"status":"idle","phase":"offline","open":true}""").status)
        assertEquals(GraineChatStatus.CONNECTED, Bridge.chat("""{"turns":[],"status":"online","phase":"connected"}""").status)
        // An older GraineInApp.
        assertEquals(GraineChatStatus.CONNECTED, Bridge.chat("""{"turns":[],"status":"online"}""").status)
        assertEquals(GraineChatStatus.FAILED, Bridge.chat("""{"turns":[],"status":"offline","ended":false}""").status)
        assertEquals(GraineChatStatus.IDLE, Bridge.chat("""{"turns":[],"status":"offline","ended":true}""").status)
        val waiting = """[{"id":"t1","role":"user","text":"Hi","delivery":"sending","at":1000}]"""
        assertEquals(GraineChatStatus.OFFLINE, Bridge.chat("""{"turns":$waiting,"status":"idle","open":true}""").status)
        assertEquals(GraineChatStatus.IDLE, Bridge.chat("""{"turns":$waiting,"status":"idle","open":false}""").status, "not in the chat")
        assertTrue(Bridge.chat("""{"turns":[],"status":"offline","phase":"failed"}""").extras.failed)
    }

    // ── a native call ─────────────────────────────────────────────────────

    /** Flutter's `GraineCallPhase.of` and React Native's `phaseOf`, with the missed ring only the calls can know. */
    @Test
    fun thePhaseIsTheWordEverySdkUses() {
        assertEquals(
            listOf("idle", "ringing", "calling", "connecting", "active", "reconnecting", "ended", "missed"),
            GraineCallPhase.entries.map { it.wire },
        )
        data class Row(val state: GraineCallState, val incoming: Boolean, val reconnecting: Boolean, val missed: Boolean, val phase: GraineCallPhase)
        val rows = listOf(
            Row(GraineCallState.IDLE, false, false, false, GraineCallPhase.IDLE),
            Row(GraineCallState.IDLE, false, false, true, GraineCallPhase.MISSED),
            Row(GraineCallState.IDLE, true, true, true, GraineCallPhase.MISSED),
            Row(GraineCallState.RINGING, true, false, false, GraineCallPhase.RINGING),
            Row(GraineCallState.RINGING, true, true, true, GraineCallPhase.RINGING),
            Row(GraineCallState.CONNECTING, false, false, false, GraineCallPhase.CALLING),
            Row(GraineCallState.CONNECTING, true, false, false, GraineCallPhase.CONNECTING),
            Row(GraineCallState.CONNECTING, false, true, false, GraineCallPhase.CALLING),
            Row(GraineCallState.ACTIVE, false, false, false, GraineCallPhase.ACTIVE),
            Row(GraineCallState.ACTIVE, true, true, false, GraineCallPhase.RECONNECTING),
            Row(GraineCallState.ACTIVE, false, true, true, GraineCallPhase.RECONNECTING),
            Row(GraineCallState.ENDED, false, false, false, GraineCallPhase.ENDED),
            Row(GraineCallState.ENDED, true, false, true, GraineCallPhase.ENDED),
            Row(GraineCallState.ENDED, true, true, false, GraineCallPhase.ENDED),
        )
        for (r in rows) {
            assertEquals(r.phase, GraineCallPhase.of(r.state, r.incoming, r.reconnecting, r.missed), r.toString())
            val missed = if (r.missed) GraineMissedCall("c0", null, null, false, Instant.fromEpochMilliseconds(0)) else null
            assertEquals(r.phase, GraineCallInfo(state = r.state, incoming = r.incoming, reconnecting = r.reconnecting, missedCall = missed).phase, r.toString())
        }
    }

    @Test
    fun theCallsSayThePhaseAsItChanges() = runTest(UnconfinedTestDispatcher()) {
        Graine.install(engine)
        val seen = mutableListOf<GraineCallPhase>()
        val job = launch { Graine.calls.phase.collect { seen += it } }
        engine.sink.calls(GraineCallInfo(state = GraineCallState.CONNECTING, callId = "c1", incoming = false))
        engine.sink.calls(GraineCallInfo(state = GraineCallState.ACTIVE, callId = "c1", incoming = false))
        engine.sink.calls(GraineCallInfo(state = GraineCallState.ACTIVE, callId = "c1", incoming = false, reconnecting = true))
        engine.sink.calls(GraineCallInfo(state = GraineCallState.ENDED, callId = "c1"))
        engine.sink.calls(GraineCallInfo(missedCall = GraineMissedCall("c2", "Graine", null, true, Instant.fromEpochMilliseconds(5))))
        engine.sink.calls(GraineCallInfo())
        assertEquals(
            listOf(GraineCallPhase.IDLE, GraineCallPhase.CALLING, GraineCallPhase.ACTIVE, GraineCallPhase.RECONNECTING,
                GraineCallPhase.ENDED, GraineCallPhase.MISSED, GraineCallPhase.IDLE),
            seen,
        )
        assertEquals(Graine.calls.state.value.phase, Graine.calls.phase.first())
        job.cancel()
    }

    @Test
    fun theIosFacadesCallReadsAsItsPhase() {
        assertEquals(GraineCallPhase.CALLING, Bridge.calls("""{"state":"connecting","direction":"outgoing","callId":"c1"}""").phase)
        assertEquals(GraineCallPhase.RECONNECTING, Bridge.calls("""{"state":"active","direction":"incoming","reconnecting":true}""").phase)
        assertEquals(GraineCallPhase.MISSED, Bridge.calls("""{"state":"idle","missedCall":{"callId":"c2","at":1000}}""").phase)
    }

    // ── what the app reports ─────────────────────────────────────────────

    /** The event as JSON, exactly [bytes] long: `{"name":"n","data":{"blob":"…"}}`. */
    private fun data(bytes: Int, unit: String = "a"): Map<String, Any?> {
        val base = AppEvents.bytes("n", null, mapOf("blob" to ""))
        return mapOf("blob" to unit.repeat((bytes - base) / unit.encodeToByteArray().size))
    }

    @Test
    fun theLimitIsEightKilobytesOfTheEventAsJson() {
        assertEquals(8_192, Graine.REPORT_EVENT_MAX_BYTES)
        assertEquals(Graine.REPORT_EVENT_MAX_BYTES, AppEvents.MAX_BYTES)
        assertEquals("""{"name":"n","data":{"blob":""}}""".length, AppEvents.bytes("n", null, mapOf("blob" to "")))
        assertEquals(8_192, AppEvents.bytes("n", null, data(8_192)))
        assertEquals(8_191, AppEvents.bytes("n", null, data(8_191, unit = "é")), "bytes, not characters")
        // The event as the platform SDKs build it: the pacing keys at its level, the rest of the app's under data.
        assertEquals(
            mapOf("name" to "n", "detail" to "d", "cooldown_seconds" to 0, "quiet" to true, "data" to mapOf("screen" to "kyc", "a" to 1)),
            AppEvents.event("n", "d", mapOf("quiet" to true, "screen" to "kyc", "cooldown_seconds" to 0, "data" to mapOf("a" to 1))),
        )
    }

    @Test
    fun anEventAtTheLimitIsSentAndOneByteMoreNeverIs() = runTest(UnconfinedTestDispatcher()) {
        Graine.install(engine)
        val errors = mutableListOf<String>()
        val job = launch { Graine.events.collect { if (it is GraineEvent.Error) errors += it.message } }

        Graine.reportEvent("n", data = data(8_192))
        assertEquals(1, engine.reported.size, "at the limit: sent")
        assertTrue(errors.isEmpty())

        val over = data(8_192).let { it + ("blob" to (it["blob"] as String) + "a") }
        Graine.reportEvent("n", data = over)
        assertEquals(1, engine.reported.size, "one byte over: never sent")
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("8193 bytes") && errors.single().contains("8192"), errors.single())
        assertTrue(errors.single().startsWith("reportEvent(\"n\")"), errors.single())

        Graine.reportEvent("n", detail = "d".repeat(9_000))
        assertEquals(1, engine.reported.size, "the detail counts too")
        assertEquals(2, errors.size)
        job.cancel()
    }
}
