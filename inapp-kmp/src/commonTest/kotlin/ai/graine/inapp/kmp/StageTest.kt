package ai.graine.inapp.kmp

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The face stage across the bridge (contract/PUBLIC-STATE.md 9): the platform SDK's
 * `{"call","phase",…}` as Graine.stage.state, the stage's settings reaching the engine
 * before or after configure, its buttons, and the clock's words (contract/avatar-stage.json).
 */
class StageTest {
    private lateinit var engine: FakeEngine

    @BeforeTest fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest fun tearDown() = Graine.reset()

    private val liveJson = """
        {"call":"voice","phase":"live","status":"","live":true,"startedAt":1762430400000,
         "caption":{"who":"agent","text":"Does 10 work for you?","final":false},
         "muted":false,"speaker":true,"controls":["mute","chat","end","speaker"],"enabled":["mute","chat","end","speaker"],
         "endLabel":"End","face":{"phase":"live","reason":null},"name":"Asha","role":"AI care assistant",
         "componentId":"book_visit:1","componentPending":false}
    """.trimIndent()

    @Test fun thePlatformsStageIsGraineStageState() {
        assertEquals(GraineStageState(), Graine.stage.state.value, "idle before configure")
        Graine.install(engine)
        val hear = assertNotNull(engine.stageListener, "the default agent's engine is heard")
        hear(liveJson)
        val s = Graine.stage.state.value
        assertEquals(GraineStageCall.VOICE, s.call)
        assertEquals(GraineStagePhase.LIVE, s.phase)
        assertTrue(s.live)
        assertEquals(1762430400000L, s.startedAt)
        assertEquals(GraineStageCaption(GraineRole.AGENT, "Does 10 work for you?", false), s.caption)
        assertEquals(listOf(GraineStageControl.MUTE, GraineStageControl.CHAT, GraineStageControl.END, GraineStageControl.SPEAKER), s.controls)
        assertTrue(s.canUse(GraineStageControl.MUTE))
        assertFalse(s.shows(GraineStageControl.CALL))
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.LIVE), s.face)
        assertEquals("Asha", s.name)
        assertEquals("AI care assistant", s.role)
        assertEquals("book_visit:1", s.componentId)
        assertEquals("0:42", s.clock(1762430400000L + 42_000))
    }

    @Test fun whatCannotBeReadIsIdleNeverACrash() {
        Graine.install(engine)
        val hear = assertNotNull(engine.stageListener)
        hear(liveJson)
        hear("not json")
        assertEquals(GraineStageState(), Graine.stage.state.value)
        hear("""{"call":"sparkly","phase":"sparkly","controls":["sparkly"],"endLabel":"Bye","caption":{"who":"agent","text":"   "}}""")
        val s = Graine.stage.state.value
        assertEquals(GraineStageCall.NONE, s.call)
        assertEquals(GraineStagePhase.IDLE, s.phase)
        assertEquals(listOf(GraineStageControl.CALL), s.controls, "no readable button is the call button")
        assertEquals("End", s.endLabel)
        assertNull(s.caption, "never an empty line")
        hear("""{"phase":"connecting","controls":["mute","end","speaker"],"enabled":["end","speaker"],"endLabel":"Cancel","caption":{"who":"customer","text":"Hello","final":true}}""")
        val c = Graine.stage.state.value
        assertEquals("Cancel", c.endLabel)
        assertFalse(c.canUse(GraineStageControl.MUTE))
        assertEquals(GraineRole.USER, c.caption?.who, "the customer's line")
        assertEquals("", c.clock(1L), "no clock without startedAt")
    }

    @Test fun onlyTheDefaultAgentsEngineIsHeard() {
        val support = FakeEngine()
        Graine.install(support, "support")
        assertNull(support.stageListener, "another agent's stage is not the app's")
        Graine.install(engine)
        assertNotNull(engine.stageListener)
    }

    @Test fun theSettingsReachTheEngineBeforeOrAfterConfigure() {
        Graine.stage.name = "Asha"
        Graine.stage.role = "AI care assistant"
        Graine.stage.calls = GraineStageCalls.NATIVE
        var chats = 0
        Graine.stage.onChat = { chats++ }
        Graine.install(engine)
        assertEquals(listOf("words:Asha|AI care assistant", "calls:native", "chat:true"), engine.stageAsked, "told as the engine is made")
        engine.stageAsked.clear()
        Graine.stage.role = null
        Graine.stage.onChat = null
        assertEquals(listOf("words:Asha|null", "chat:false"), engine.stageAsked)
        assertEquals(GraineStageCalls.NATIVE, Graine.stage.calls)
        Graine.stage.onChat = { chats++ }
        Graine.stage.openChat()
        engine.stageChat?.invoke()
        assertEquals(2, chats, "the Chat button calls the app's chat, on either side")
    }

    @Test fun theButtonsAreTheEnginesActions() {
        Graine.install(engine)
        engine.stageAsked.clear()
        Graine.stage.call()
        Graine.stage.answer()
        Graine.stage.setMuted(true)
        Graine.stage.setSpeaker(false)
        Graine.stage.end()
        assertEquals(listOf("call", "answer", "muted:true", "speaker:false", "end"), engine.stageAsked)
    }

    /** contract/avatar-stage.json "clock" — every reading (StageVendoredTest checks the copy against the file). */
    @Test fun theClockReadsAsTheContractSays() {
        for ((ms, text) in STAGE_CLOCK) assertEquals(text, GraineStage.clock(ms), "$ms")
    }
}

/** A copy of contract/avatar-stage.json's "clock" readings: Kotlin/Native tests read no files. */
internal val STAGE_CLOCK: List<Pair<Long, String>> = listOf(
    0L to "0:00",
    999L to "0:00",
    1000L to "0:01",
    42_000L to "0:42",
    59_999L to "0:59",
    60_000L to "1:00",
    754_000L to "12:34",
    3_599_000L to "59:59",
    3_600_000L to "1:00:00",
    3_723_000L to "1:02:03",
    86_400_000L to "24:00:00",
    -5_000L to "0:00",
)
