package ai.graine.inapp.kmp

import ai.graine.inapp.kmp.objc.GraineObjC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSRunLoop
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * SERVER TOKENS ON iOS (sdk/contract/CLIENT-TOKENS.md), over the iOS SDK linked
 * into the test binary: the app's suspending provider is the facade's token
 * block — asked by the iOS SDK itself when the agent starts, its answer handed
 * back once, a throw handed back as null — and every refusal the contract names
 * crosses from the facade's NSError into the shared code with the server's reason.
 */
class IosServerTokensTest {
    /** The facade answers on the main queue: run the main loop until [done], at most [seconds]. */
    private fun spinUntil(seconds: Int = 10, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done() && start.elapsedNow() < seconds.seconds) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.02))
        }
    }

    @Test
    fun everyCodeOfTheContractCrossesFromTheFacadeWithItsReason() {
        val codes = (Json.obj(CLIENT_TOKENS_JSON)!!["errorCodes"] as List<*>).map { it as Map<*, *> }
        assertTrue(codes.size >= 6, "the contract's codes were read")
        for (c in codes) {
            val wire = c["wire"] as String
            val error = NSError.errorWithDomain(
                "ai.graine.inapp", (c["status"] as Number).toLong(),
                mapOf<Any?, Any?>("code" to wire, "reason" to "scope", NSLocalizedDescriptionKey to "Refused."),
            )
            val e = IosEngine.failure(error)
            assertEquals(c["kmp"], e.code.toString(), wire)
            assertEquals("scope", e.reason, wire)
            assertEquals("Refused.", e.message, wire)
        }
        assertNull(IosEngine.failure(NSError.errorWithDomain("ai.graine.inapp", 401, mapOf<Any?, Any?>("code" to "key_revoked"))).reason, "none given: none kept")
    }

    @Test
    fun theSuspendingProviderIsTheFacadesBlockAndAThrowIsNull() {
        val main = CoroutineScope(Dispatchers.Main)
        var heard: List<String?> = emptyList()
        IosTokens.block({ "tok_1" }, main)({ token -> heard = heard + token })
        IosTokens.block({ error("the app's server is down") }, main)({ token -> heard = heard + token })
        spinUntil { heard.size == 2 }
        assertEquals(setOf("tok_1", null), heard.toSet())
    }

    @Test
    fun anAgentConfiguredWithATokenProviderIsAskedByTheIosSdkAtStart() {
        val asked = AtomicInt(0)
        val engine = createPlatformEngine(
            GraineConfig(baseUrl = "https://graine.invalid", publishableKey = "pk_test_tokens", tokenProvider = { asked.incrementAndGet(); "tok_kmp" }),
            "tokens",
        )
        spinUntil { asked.value >= 1 }
        assertTrue(asked.value >= 1, "the iOS SDK asked the provider when the agent started")
        assertNotNull(GraineObjC.named("tokens"), "configured by the selector that carries the provider")
        assertTrue(engine.installId.length >= 16)
    }
}
