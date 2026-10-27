package ai.graine.inapp.kmp

import ai.graine.inapp.GraineError
import ai.graine.inapp.GraineErrorCode as NativeErrorCode
import ai.graine.inapp.GraineTokenProvider as NativeTokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Server tokens on Android (sdk/contract/CLIENT-TOKENS.md): the vendored vectors
 * are the contract's; every code the contract names crosses from the Android
 * SDK's own error — its Kotlin name — into the shared one — its multiplatform
 * name — with the server's reason; and the app's suspending provider is the
 * Android SDK's provider, answering once, null when it throws.
 */
class ClientTokensVendoredTest {

    @Test
    fun theVendoredVectorsAreTheContracts() {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder: the vendored copy is the only one", dir != null)
        val contract = File(dir!!, "client-tokens.json")
        assertTrue("$contract is missing", contract.isFile)
        assertEquals(
            "src/commonTest/contract/client-tokens.json must be a copy of sdk/contract/client-tokens.json: copy it over",
            contract.readText(),
            CLIENT_TOKENS_JSON,
        )
    }

    @Test
    fun everyCodeCrossesFromTheAndroidSdkWithItsReason() {
        val codes = (Json.obj(CLIENT_TOKENS_JSON)!!["errorCodes"] as List<*>).map { it as Map<*, *> }
        assertTrue(codes.size >= 6)
        for (c in codes) {
            val wire = c["wire"] as String
            val native = NativeErrorCode.from(wire)
            assertEquals(wire, c["kotlin"], native.javaClass.simpleName)
            val shared = AndroidMapping.error(GraineError(native, "Refused.", null, "scope"))
            assertEquals(wire, c["kmp"], shared.code.toString())
            assertEquals("scope", shared.reason)
            assertEquals("Refused.", shared.message)
        }
        assertNull("no reason: none", AndroidMapping.error(GraineError(NativeErrorCode.KEY_REVOKED, "Revoked.")).reason)
    }

    @Test
    fun theAppsSuspendingProviderIsTheAndroidSdksAndAThrowIsNull() {
        val native = AndroidMapping.config(GraineConfig("https://www.graine.ai", "pk_test_key", tokenProvider = { "tok_1" })).tokenProvider
        assertEquals("tok_1", fetchOnce(native!!))
        val failing = AndroidMapping.config(GraineConfig("https://www.graine.ai", "pk_test_key", tokenProvider = { error("the app's server is down") })).tokenProvider
        assertNull(fetchOnce(failing!!))
        assertNull("none configured: the Android SDK is given none", AndroidMapping.config(GraineConfig("https://www.graine.ai", "pk_test_key")).tokenProvider)
    }

    private fun fetchOnce(p: NativeTokenProvider): String? {
        val answered = CountDownLatch(1)
        val got = arrayOf<String?>("unset")
        p.fetch { token -> got[0] = token; answered.countDown() }
        assertTrue("answered", answered.await(5, TimeUnit.SECONDS))
        return got[0]
    }
}
