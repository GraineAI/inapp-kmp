package ai.graine.inapp.kmp

import ai.graine.inapp.GraineAppVerdict as NativeAppVerdict
import ai.graine.inapp.GraineDebugTokenMode as NativeDebugTokenMode
import ai.graine.inapp.GraineError
import ai.graine.inapp.GraineErrorCode as NativeErrorCode
import ai.graine.inapp.GraineUserVerdict as NativeUserVerdict
import ai.graine.inapp.GraineVerification as NativeVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Your app and your users on Android (sdk/contract/APP-IDENTITY.md): the
 * vendored app-identity.json is the contract's; the Android SDK names every
 * code, verdict and mode the contract does; and each crosses from the Android
 * SDK's own type into the shared one unchanged — the same values the iOS
 * bridge reads (AppIdentityTest).
 */
class AppIdentityVendoredTest {

    private val contract: Map<String, Any?> by lazy { Json.obj(APP_IDENTITY_JSON) ?: error("app-identity.json is not JSON") }

    private fun strings(name: String): List<String> = (contract[name] as List<*>).map { it as String }

    @Test
    fun theVendoredContractIsTheContracts() {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder: the vendored copy is the only one", dir != null)
        val file = File(dir!!, "app-identity.json")
        assertTrue("$file is missing", file.isFile)
        assertEquals(
            "src/commonTest/contract/app-identity.json must be a copy of sdk/contract/app-identity.json: copy it over",
            file.readText(),
            APP_IDENTITY_JSON,
        )
    }

    @Test
    fun theAndroidSdkNamesEveryCodeAsTheContractAndItArrivesAsTheSharedOne() {
        val codes = (contract["errorCodes"] as List<*>).map { it as Map<*, *> }
        assertEquals(3, codes.size)
        for (c in codes) {
            val wire = c["wire"] as String
            val native = NativeErrorCode.from(wire)
            assertEquals("the Android SDK's name for $wire", c["kotlin"], native::class.simpleName)
            val shared = AndroidMapping.error(GraineError(native, "Refused.")).code
            assertEquals(wire, shared.wire)
            assertFalse(wire, shared is GraineErrorCode.Unknown)
            assertEquals(GraineErrorCode.from(wire), shared)
        }
    }

    @Test
    fun everyVerdictAndModeIsTheContractsOnBothSides() {
        assertEquals(strings("appVerdicts"), NativeAppVerdict.values().map { it.word })
        assertEquals(strings("userVerdicts"), NativeUserVerdict.values().map { it.word })
        assertEquals((contract["debugTokens"] as Map<*, *>)["modes"], NativeDebugTokenMode.values().map { it.word })
        for (v in NativeAppVerdict.values()) assertEquals(v.word, AndroidMapping.appVerdict(v).word)
        for (v in NativeUserVerdict.values()) assertEquals(v.word, AndroidMapping.userVerdict(v).word)
        for (m in NativeDebugTokenMode.values()) {
            assertEquals(m.word, AndroidMapping.debugTokenMode(m).word)
            assertEquals(m, AndroidMapping.debugTokenMode(AndroidMapping.debugTokenMode(m)))
        }
    }

    @Test
    fun everyReasonCrossesFromTheAndroidSdkUnchanged() {
        for (r in strings("userReasons")) {
            val v = AndroidMapping.verification(NativeVerification(user = NativeUserVerdict.INVALID, userReason = r))
            assertEquals(GraineVerification(user = GraineUserVerdict.INVALID, userReason = r), v)
        }
        for (r in strings("appNotVerifiedReasons") + strings("appVerifyReasons")) {
            val v = AndroidMapping.verification(NativeVerification(NativeAppVerdict.UNVERIFIED, r))
            assertEquals(GraineVerification(app = GraineAppVerdict.UNVERIFIED, appReason = r), v)
        }
    }

    /**
     * The README's server snippet ("Secure your agent: your app and your
     * users"), line for line: the identity token is HMAC-SHA256 over the
     * identity value with the agent's user secret, as 64 hex characters (or
     * base64 / base64url of the 32 bytes) — the contract's vectors.
     */
    @Test
    fun theReadmesServerSnippetMakesTheContractsTokens() {
        val vectors = (contract["hmacVectors"] as List<*>).map { it as Map<*, *> }
        assertTrue(vectors.size >= 6)
        for (v in vectors) {
            val secret = v["secret"] as String
            val value = v["identityValue"] as String
            // ── as in the README ──
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
            val bytes = mac.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            val token = bytes.joinToString("") { "%02x".format(it) }
            // ──
            assertEquals(value, v["hex"], token)
            assertEquals(value, v["base64url"], Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
            assertEquals(value, v["base64"], Base64.getEncoder().encodeToString(bytes))
        }
        // The contract's example identity carries exactly such a token.
        val wire = ((contract["identityWire"] as Map<*, *>)["identity"] as Map<*, *>)
        assertNotNull(vectors.firstOrNull { it["identityValue"] == wire["value"] && it["hex"] == wire["token"] })
    }
}
