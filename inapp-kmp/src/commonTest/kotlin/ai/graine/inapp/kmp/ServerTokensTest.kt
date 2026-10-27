package ai.graine.inapp.kmp

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SERVER TOKENS, KEYS AND THE BOT CHECK — the contract's codes
 * (client-tokens.json, vendored in src/commonTest/contract), run here on the
 * JVM and on iOS: every wire word the contract names is the shared code it
 * names (`kmp`), a refusal keeps the server's reason, and the app's token
 * provider reaches the platform engine with the configuration — the platform
 * SDK keeps every rule of when to ask it.
 */
class ServerTokensTest {
    @BeforeTest
    fun setUp() = Graine.reset()

    @AfterTest
    fun tearDown() = Graine.reset()

    private val contract: Map<String, Any?> by lazy { Json.obj(CLIENT_TOKENS_JSON) ?: error("client-tokens.json is not JSON") }

    private val codes: List<Map<*, *>> get() = (contract["errorCodes"] as List<*>).map { it as Map<*, *> }

    @Test
    fun everyCodeOfTheContractIsTheSharedOne() {
        assertTrue(codes.size >= 6, "the contract's codes were read")
        for (c in codes) {
            val wire = c["wire"] as String
            val code = GraineErrorCode.from(wire)
            assertEquals(c["kmp"], code.toString(), wire)
            assertEquals(wire, code.wire)
            assertFalse(code is GraineErrorCode.Unknown, wire)
            assertEquals(code, GraineErrorCode.from(" ${wire.uppercase()} "), "case and spaces never matter")
        }
    }

    @Test
    fun aRefusalKeepsTheServersReason() {
        val reasons = contract["reasons"] as Map<*, *>
        assertTrue(reasons.isNotEmpty())
        for ((wire, said) in reasons) {
            for (reason in said as List<*>) {
                val e = Bridge.error(wire as String, "Refused.", null, reason as String)
                assertEquals(GraineErrorCode.from(wire), e.code)
                assertEquals(reason, e.reason)
                assertEquals("Refused.", e.message)
            }
        }
        assertNull(Bridge.error("token_scope", "Refused.").reason, "none given: none kept")
        assertNull(Bridge.error("token_scope", "Refused.", null, "  ").reason)
    }

    @Test
    fun theConfigsTokenProviderReachesThePlatformEngine() = runTest {
        val made = mutableListOf<Pair<String, GraineConfig>>()
        Graine.engineFactory = { config, name -> made += name to config; FakeEngine() }
        var asked = 0
        Graine.configure(GraineConfig(baseUrl = "https://www.graine.ai", publishableKey = "pk_test_key", tokenProvider = { asked++; "tok_1" }))
        val provider = made.single().second.tokenProvider
        assertNotNull(provider, "the platform engine is made with the app's provider")
        assertEquals("tok_1", provider())
        assertEquals(1, asked)
        // An agent configured without one: its engine is given none, and nothing changes.
        Graine.configure(GraineConfig(baseUrl = "https://www.graine.ai", publishableKey = "pk_test_sales"), name = "sales")
        assertEquals("sales", made.last().first)
        assertNull(made.last().second.tokenProvider)
    }
}
