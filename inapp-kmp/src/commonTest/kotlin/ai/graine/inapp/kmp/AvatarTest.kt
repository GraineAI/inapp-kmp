package ai.graine.inapp.kmp

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agent's face across the bridge: the platform SDK's state as Graine.avatar.state, and the call screen's opt-in. */
class AvatarTest {
    private lateinit var engine: FakeEngine

    @BeforeTest fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest fun tearDown() = Graine.reset()

    @Test fun thePlatformsFaceIsGraineAvatarState() {
        assertEquals(GraineAvatarState.OFF, Graine.avatar.state.value, "off before configure")
        Graine.install(engine)
        val hear = assertNotNull(engine.avatarListener, "the engine that reports the calls is heard")
        hear("""{"phase":"available","reason":null}""")
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.AVAILABLE), Graine.avatar.state.value)
        assertTrue(Graine.avatar.state.value.showsFace)
        hear("""{"phase":"live","reason":"busy"}""")
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.LIVE), Graine.avatar.state.value, "a reason only when off")
        hear("""{"phase":"off","reason":"unsupported"}""")
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "unsupported"), Graine.avatar.state.value)
        assertFalse(Graine.avatar.state.value.showsFace)
        hear("not json")
        assertEquals(GraineAvatarState.OFF, Graine.avatar.state.value)
        hear("""{"phase":"off","reason":"Not A Reason"}""")
        assertNull(Graine.avatar.state.value.reason)
    }

    @Test fun onlyTheEngineThatReportsCallsIsHeard() {
        Graine.install(engine)
        val second = FakeEngine()
        Graine.install(second, "support")
        assertNull(second.avatarListener)
        assertNotNull(engine.avatarListener)
    }

    @Test fun theCallScreensFaceIsOptInAndReachesTheEngineBeforeOrAfterConfigure() {
        assertFalse(Graine.calls.showsAvatar, "off by default")
        Graine.calls.showsAvatar = true
        Graine.install(engine)
        assertEquals(listOf(true), engine.avatarShown, "told as the engine is made")
        Graine.calls.showsAvatar = false
        assertEquals(listOf(true, false), engine.avatarShown)
        assertFalse(Graine.calls.showsAvatar)
    }
}
