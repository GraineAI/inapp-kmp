package ai.graine.inapp.kmp

import ai.graine.inapp.kmp.objc.GraineObjC
import kotlinx.datetime.Clock
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSRunLoop
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The iOS engine over the iOS SDK itself — its Objective-C facade, linked into
 * the test binary (scripts/ios-test-library.sh) — so every selector the binding
 * calls, every block and every JSON shape is exercised for real. Graine is a
 * reserved name that never resolves here: nothing reaches a real host.
 */
class IosEngineTest {
    private class Heard : EngineSink {
        val sessions = mutableListOf<GraineSession>()
        val failures = mutableListOf<String>()
        val events = mutableListOf<GraineEvent>()
        var chat: Triple<List<GraineChatTurn>, GraineChatStatus, Boolean>? = null
        var calls: GraineCallInfo? = null
        var voice: GraineVoiceInfo? = null
        override fun session(session: GraineSession) { sessions += session }
        override fun sessionFailed(message: String) { failures += message }
        override fun event(event: GraineEvent) { events += event }
        override fun chat(turns: List<GraineChatTurn>, status: GraineChatStatus, typing: Boolean) { chat = Triple(turns, status, typing) }
        override fun calls(info: GraineCallInfo) { calls = info }
        override fun callOutcome(outcome: GraineCallOutcome) {}
        override fun incomingCall(call: GraineIncomingCall) {}
        override fun voice(info: GraineVoiceInfo) { voice = info }
    }

    companion object {
        // One iOS SDK per process: configured once, for every test here.
        private val heard = Heard()
        private val engine: IosEngine by lazy {
            val facade = GraineObjC.configureWithBaseUrl(
                "https://graine.invalid", "pk_test_kmp", "kmp-test", false, true, false, null, """{"plan":"Gold"}""", "default",
            )
            IosEngine(assertNotNull(facade, "the default agent is configured")).also { it.start(heard) }
        }
    }

    /** The facade answers on the main queue: run the main loop until [done], at most [seconds]. */
    private fun spinUntil(seconds: Int = 15, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done() && start.elapsedNow() < seconds.seconds) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.02))
        }
    }

    @Test
    fun theFacadeTakesEverythingTheSharedCodeSends() {
        assertTrue(engine.installId.length >= 16, "an install id: ${engine.installId}")
        engine.identify(mapOf("user_id" to "u-1", "name" to "Riya", "age" to 41), memory = false)
        assertFalse(engine.memoryEnabled)
        engine.setMemory(true)
        assertTrue(engine.memoryEnabled)
        engine.setVariables(mapOf("cart_total" to 1299, "coupon" to null))
        engine.setVariables(null)
        engine.setScreen(GraineScreen("kyc", "Upload", GraineScreen.Journey("KYC", 2, 4), listOf(GraineField("pan", status = GraineFieldStatus.INVALID)), data = mapOf("n" to 1)))
        engine.updateField(GraineField("otp", status = GraineFieldStatus.EMPTY))
        engine.removeField("otp")
        engine.track("otp_retry", mapOf("n" to 2))
        engine.tap("pay")
        engine.noteInteraction()
        engine.clearScreen("kyc")
        val unregister = engine.registerAction("open_screen") { _, answer -> answer(GraineActionResult.ok("Opened.")) }
        unregister()
        engine.identify(null, null)
        spinUntil { heard.chat != null && heard.calls != null && heard.voice != null }
        assertEquals(GraineChatStatus.IDLE, heard.chat?.second)
        assertEquals(GraineCallState.IDLE, heard.calls?.state)
        assertEquals(GraineVoiceInfo(), heard.voice)
    }

    @Test
    fun aRefusalArrivesAsTheTypedError() {
        engine.identify(null, null)
        var result: Result<List<GraineMemory>>? = null
        engine.memories { result = it }
        spinUntil { result != null }
        val error = result?.exceptionOrNull() as? GraineException
        assertEquals(GraineErrorCode.IdentityRequired, error?.code, "nobody signed in: ${result}")
        // The session could not load from a name that never resolves, and the shared code was told.
        spinUntil { heard.failures.isNotEmpty() }
        assertTrue(heard.failures.isNotEmpty())
        assertTrue(heard.sessions.isEmpty())
    }

    @Test
    fun aBookingIsCheckedOnTheDeviceAndACancelWithNoAnswerIsANetworkError() {
        var result: Result<GraineScheduledCall>? = null
        engine.scheduleCall(Clock.System.now() + 2.hours, "Weekly check-in") { result = it }
        spinUntil { result != null }
        // No session could load, so the agent's rules are the defaults: nobody may book. Nothing was sent.
        assertEquals(GraineErrorCode.SchedulingOff, (result?.exceptionOrNull() as? GraineException)?.code, "refused on the device: ${result}")
        var cancelled: Result<GraineCallStatus>? = null
        engine.cancelScheduledCall("sc_1") { cancelled = it }
        spinUntil { cancelled != null }
        assertEquals(GraineErrorCode.Network, (cancelled?.exceptionOrNull() as? GraineException)?.code)
    }

    @Test
    fun eachNameIsItsOwnAgent() {
        engine.installId
        val support = assertNotNull(
            GraineObjC.configureWithBaseUrl("https://graine.invalid", "pk_test_support", null, false, true, false, null, null, "support")
        )
        // Objective-C identity (isEqual: on NSObject is the same instance).
        assertTrue(support.isEqual(GraineObjC.named(" support ")), "one facade per agent, found by its name")
        assertFalse(support.isEqual(GraineObjC.named("default")))
        assertFalse(support.chat.isEqual(GraineObjC.named("default")?.chat), "each agent has its own chat")
        assertEquals(engine.installId, IosEngine(support).installId, "one install for every agent")
        assertNull(GraineObjC.named("sales"))
        assertTrue(GraineObjC.named("default")!!.isEqual(GraineObjC.current()), "the default agent is the current one")
    }

    @Test
    fun aNewConversationWithNobodySignedInClearsTheChat() {
        engine.identify(null, null)
        engine.chatSend("Before the new conversation")
        spinUntil { heard.chat?.first?.isNotEmpty() == true }
        var result: Result<Unit>? = null
        engine.chatNewConversation { result = it }
        spinUntil { result != null }
        assertTrue(result?.isSuccess == true, "nothing was kept for nobody, so nothing to refuse: $result")
        spinUntil { heard.chat?.first?.isEmpty() == true }
        assertEquals(emptyList(), heard.chat?.first, "the chat on screen cleared")
    }

    @Test
    fun anAgentConfiguredWithAChatSizeIsMade() {
        engine.installId
        val sized = assertNotNull(
            GraineObjC.configureWithBaseUrl("https://graine.invalid", "pk_test_sized", null, false, true, false, null, null, "sized", "large")
        )
        assertTrue(sized.isEqual(GraineObjC.named("sized")), "configured by the selector that carries the size")
        // A size this build does not know is no size: the agent is made all the same.
        assertNotNull(GraineObjC.configureWithBaseUrl("https://graine.invalid", "pk_test_odd", null, false, true, false, null, null, "odd", "huge"))
    }

    @Test
    fun aRateLimitSaysHowLongToWait() {
        val error = NSError.errorWithDomain(
            "ai.graine.inapp", 429,
            mapOf<Any?, Any?>("code" to "rate_limited", "retryAfterSeconds" to NSNumber(int = 30), NSLocalizedDescriptionKey to "Slow down."),
        )
        val e = IosEngine.failure(error)
        assertEquals(GraineErrorCode.RateLimited, e.code)
        assertEquals(30, e.retryAfterSeconds)
        assertEquals("Slow down.", e.message)
        assertNull(IosEngine.failure(NSError.errorWithDomain("ai.graine.inapp", 0, mapOf<Any?, Any?>("code" to "network"))).retryAfterSeconds)
    }

    @Test
    fun theChatCallsAndVoiceBarAreDrivenThroughTheFacade() {
        engine.chatOpen(null)
        engine.chatSend("Hello")
        spinUntil { heard.chat?.first?.any { it.text == "Hello" } == true }
        val turn = heard.chat?.first?.lastOrNull { it.text == "Hello" }
        assertEquals(GraineRole.USER, turn?.role)
        assertEquals(GraineDelivery.SENDING, turn?.delivery, "not connected: it waits to be sent")
        assertEquals(false, turn?.restored, "said just now")
        assertEquals(GraineChatChannel.CHAT, turn?.channel, "typed in the chat")
        // Each size reaches the facade's open (openWithSize:), and a close lets it go.
        engine.chatOpen(GraineChatSize.LARGE)
        engine.chatOpen(GraineChatSize.FULL)
        engine.chatClose()
        // No call: none to name an agent for.
        spinUntil { heard.calls != null }
        assertNull(heard.calls?.agent)
        // A call cannot be placed from a test binary with no microphone permission prompt to answer — but it is accepted.
        engine.callsConfigure(GraineCallsConfig(displayName = "Asha", ringTimeoutSeconds = 30))
        engine.callsDismissMissed()
        engine.voiceSetMuted(true)
        spinUntil { heard.voice?.muted == true }
        assertTrue(heard.voice?.muted == true)
    }
}
