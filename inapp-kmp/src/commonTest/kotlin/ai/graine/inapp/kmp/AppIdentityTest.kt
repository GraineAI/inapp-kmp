package ai.graine.inapp.kmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * YOUR APP AND YOUR USERS (sdk/contract/APP-IDENTITY.md, app-identity.json
 * vendored in src/commonTest/contract) — run here on the JVM and on iOS.
 *
 * What the shared code owns is held to the contract field by field: the error
 * codes, the verdicts and their wire words, Graine's reasons, the development
 * modes. And the API on top of the platform SDKs: identify with a token, the
 * token replaced, the refresher bridged from a suspend function to the
 * platforms' callback, the app proven on request, the verification as a
 * StateFlow per agent, and development mode for every agent.
 *
 * What the native SDKs own — the app's declaration (team, certificates), App
 * Attest and Play Integrity, the passes and their lifetimes, the token's place
 * on each request, the JWT expiry read and the HMAC itself — is proven by
 * their own suites against the same file; here each of those sections must
 * still be present, so a contract that renames one fails on this side too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppIdentityTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() {
        Graine.reset()
        Graine.actionDispatcher = graineMainDispatcher
    }

    private fun configured(): FakeEngine = engine.also { Graine.install(it) }

    private val contract: Map<String, Any?> by lazy { Json.obj(APP_IDENTITY_JSON) ?: error("app-identity.json is not JSON") }

    private fun list(name: String): List<Any?> = (contract[name] as? List<*>)?.also { assertTrue(it.isNotEmpty(), "$name is empty") }
        ?: error("app-identity.json has no list \"$name\"")

    private fun obj(name: String): Map<*, *> = contract[name] as? Map<*, *> ?: error("app-identity.json has no object \"$name\"")

    private fun strings(name: String): List<String> = list(name).map { it as String }

    /** `APP_NOT_ALLOWED` → `AppNotAllowed`: the contract's Kotlin name in this SDK's idiom (its error codes are data objects). */
    private fun pascal(screaming: String): String =
        screaming.lowercase().split('_').joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }

    // ── the contract, field by field ─────────────────────────────────────

    @Test
    fun theContractIsTheOneThisSdkImplements() {
        assertEquals(1L, contract["version"], "a new version of app-identity.json: read what changed")
        assertEquals("0.14.4", contract["sdkVersion"])
        // The sections the native SDKs implement (the declaration, proofs, passes, the token on the wire): present.
        for (section in listOf("shapes", "certificateDigest", "passes", "jwtExpiry", "identityWire", "debugTokens")) obj(section)
        for (section in listOf("teamIdFromAccessGroup", "sessionSecurityExamples", "hmacVectors")) list(section)
    }

    @Test
    fun everyErrorCodeIsKnownByItsWireWordAndNamedAsTheContractSays() {
        val codes = list("errorCodes").map { it as Map<*, *> }
        assertEquals(3, codes.size)
        for (c in codes) {
            val wire = c["wire"] as String
            val code = GraineErrorCode.from(wire)
            assertFalse(code is GraineErrorCode.Unknown, "$wire is a code this SDK knows")
            assertEquals(wire, code.wire)
            assertEquals(pascal(c["kotlin"] as String), code::class.simpleName, "the contract's ${c["kotlin"]}")
            assertEquals(pascal(c["kotlin"] as String), (c["swift"] as String).replaceFirstChar { it.uppercase() }, "one name across the SDKs")
            assertSame(code, GraineErrorCode.from(wire.uppercase()), "read as the other codes are: whatever its case")
            assertEquals(403L, c["status"])
        }
        assertEquals(
            listOf<GraineErrorCode>(GraineErrorCode.AppNotAllowed, GraineErrorCode.AppNotVerified, GraineErrorCode.IdentityUnverified),
            codes.map { GraineErrorCode.from(it["wire"] as String) },
        )
        // What each means for a retry is the platform SDKs' to do: never, after proving the app, after a fresh token.
        assertEquals(listOf("never", "prove_then_retry_once", "refresh_token_then_retry_once"), codes.map { it["retry"] })
    }

    @Test
    fun aRefusalWithANewCodeArrivesTypedThroughEitherPlatform() = runTest {
        configured()
        for (wire in list("errorCodes").map { (it as Map<*, *>)["wire"] as String }) {
            // The iOS facade's NSError and the Android SDK's error both reach the shared code as a word: Bridge.error.
            engine.memoriesAnswer = Result.failure(Bridge.error(wire, "Graine refused it."))
            val e = assertFailsWith<GraineException> { Graine.memories() }
            assertEquals(GraineErrorCode.from(wire), e.code)
            assertEquals("Graine refused it.", e.message)
        }
    }

    @Test
    fun theVerdictsAreTheContractsWordsInItsOrder() {
        assertEquals(strings("appVerdicts"), GraineAppVerdict.entries.map { it.word })
        assertEquals(strings("userVerdicts"), GraineUserVerdict.entries.map { it.word })
        // Nothing proven before a session says otherwise.
        assertEquals(GraineVerification(GraineAppVerdict.OFF, null, GraineUserVerdict.OFF, null), GraineVerification())
    }

    @Test
    fun everyWireVerdictReadsAsItsPublicWord() {
        val apps = obj("wireAppVerdicts")
        assertEquals(5, apps.size)
        for ((wire, word) in apps) {
            assertEquals(word, GraineAppVerdict.from(wire as String)?.word, "app $wire")
            assertEquals(word, GraineAppVerdict.from(word as String)?.word, "app $word")
            assertEquals(word, Bridge.verification("""{"app":"$wire","user":"off"}""").app.word)
            assertEquals(word, Bridge.verification("""{"app":"$word","user":"off"}""").app.word)
        }
        // checking is the SDK's own, never Graine's: it reads too.
        assertEquals(GraineAppVerdict.CHECKING, Bridge.verification("""{"app":"checking","user":"off"}""").app)
        val users = obj("wireUserVerdicts")
        assertEquals(5, users.size)
        for ((wire, word) in users) {
            assertEquals(word, GraineUserVerdict.from(wire as String)?.word, "user $wire")
            assertEquals(word, GraineUserVerdict.from(word as String)?.word, "user $word")
            assertEquals(word, Bridge.verification("""{"app":"off","user":"$wire"}""").user.word)
            assertEquals(word, Bridge.verification("""{"app":"off","user":"$word"}""").user.word)
        }
        // Every public word is some wire word's, and the other way round.
        assertEquals(strings("appVerdicts").toSet() - "checking", apps.values.map { it as String }.toSet())
        assertEquals(strings("userVerdicts").toSet(), users.values.map { it as String }.toSet())
    }

    @Test
    fun theFacadesVerificationReadsWhateverItSays() {
        assertEquals(GraineVerification(), Bridge.verification(null), "a facade from before 0.14.4: nothing proven, nothing asked")
        assertEquals(GraineVerification(), Bridge.verification("not json"))
        assertEquals(GraineVerification(), Bridge.verification("{}"))
        assertEquals(
            GraineVerification(GraineAppVerdict.UNVERIFIED, null, GraineUserVerdict.UNVERIFIED, null),
            Bridge.verification("""{"app":"graced","user":"vouched"}"""),
            "a verdict newer than this build is not proven",
        )
        assertEquals(
            GraineVerification(GraineAppVerdict.VERIFIED, null, GraineUserVerdict.INVALID, "expired"),
            Bridge.verification("""{"app":"verified","appReason":null,"user":"invalid","userReason":" expired "}"""),
        )
        assertEquals(GraineVerification(), Bridge.verification("""{"app":"off","appReason":"","user":"off","userReason":"  "}"""), "a blank reason is none")
    }

    @Test
    fun everyReasonGraineGivesCrossesUnchanged() {
        val userReasons = strings("userReasons")
        assertEquals(11, userReasons.size)
        for (r in userReasons) {
            for (verdict in listOf("invalid", "unverified")) {
                val v = Bridge.verification("""{"app":"off","user":"$verdict","userReason":"$r"}""")
                assertEquals(r, v.userReason, "$verdict $r")
                assertEquals(GraineUserVerdict.from(verdict), v.user)
            }
        }
        val appReasons = strings("appNotVerifiedReasons") + strings("appVerifyReasons")
        for (r in appReasons) assertEquals(r, Bridge.verification("""{"app":"unverified","appReason":"$r","user":"off"}""").appReason, r)
        assertTrue("debug_token_unknown" in appReasons && "test_build" in appReasons && "unknown_key" in appReasons)
    }

    @Test
    fun theDevelopmentModesAreTheContracts() {
        val dev = obj("debugTokens")
        assertEquals(dev["modes"], GraineDebugTokenMode.entries.map { it.word })
        assertEquals(dev["defaultMode"], GraineDebugTokenMode.AUTOMATIC.word)
        for (word in dev["modes"] as List<*>) assertEquals(word, GraineDebugTokenMode.from(word as String)?.word)
        assertEquals(GraineDebugTokenMode.NEVER, GraineDebugTokenMode.from(" Never "))
        assertNull(GraineDebugTokenMode.from("sometimes"))
        // Nothing chosen and nothing configured: the default.
        assertEquals(GraineDebugTokenMode.AUTOMATIC, Graine.debugTokenMode)
        // The multiplatform SDK asks the native SDK whether this is a debug build.
        assertEquals("the native SDK's answer", (dev["debugBuildDetection"] as Map<*, *>)["kmp"])
        // The line a debug build logs once names where to register its token.
        assertTrue((dev["logLine"] as String).contains("Agent → Embed & Widgets → Install → Development → Debug tokens"))
    }

    // ── identify with a token, and an account change ─────────────────────

    @Test
    fun identifyWithATokenReachesThePlatformAsGiven() {
        configured()
        val wire = (obj("identityWire")["identity"] as Map<*, *>)
        val token = wire["token"] as String
        // The contract's own example: identify({user_id: "user-123"}, identityToken) with the identity field user_id.
        Graine.identify(mapOf("user_id" to wire["value"]), identityToken = token)
        assertEquals(listOf<Pair<Map<String, Any?>?, Boolean?>>(mapOf("user_id" to "user-123") to null), engine.identified)
        assertEquals(listOf<String?>(token), engine.identityTokens)
        // The token is the HMAC of the identity value (hmacVectors[0]): the shared code passes it on untouched.
        val vector = list("hmacVectors").map { it as Map<*, *> }.first { it["identityValue"] == "user-123" }
        assertEquals(vector["hex"], token)
        // As long as the contract allows, untouched: the platform SDK holds the limit.
        val longest = "a".repeat(((obj("shapes")["maxIdentityTokenLength"]) as Long).toInt())
        Graine.identify(mapOf("user_id" to "user-123"), memory = false, identityToken = longest)
        assertEquals(longest, engine.identityTokens.last())
        assertEquals(false, engine.identified.last().second)
    }

    @Test
    fun anAccountChangeCarriesNoTokenAndSaysTheNewUserIsNotYetProven() {
        configured()
        val token = (obj("identityWire")["identity"] as Map<*, *>)["token"] as String
        Graine.identify(mapOf("user_id" to "user-123", "name" to "Asha"), identityToken = token)
        engine.sink.verification(GraineVerification(user = GraineUserVerdict.VERIFIED))
        assertEquals(GraineUserVerdict.VERIFIED, Graine.verification.value.user)
        // The same user, other traits, no token: the platform SDK keeps theirs — nothing is sent in its place.
        Graine.identify(mapOf("user_id" to "user-123", "name" to "Asha K"))
        // Sign-out, then someone else: the shared code never carries a token from one identity to the next.
        Graine.identify(null)
        engine.sink.verification(GraineVerification(user = GraineUserVerdict.UNVERIFIED, userReason = "missing"))
        Graine.identify(mapOf("user_id" to "user-456"))
        assertEquals(listOf(token, null, null, null), engine.identityTokens)
        assertEquals(listOf(mapOf("user_id" to "user-123", "name" to "Asha"), mapOf("user_id" to "user-123", "name" to "Asha K"), null, mapOf("user_id" to "user-456")),
            engine.identified.map { it.first })
        // What the platform SDK made of it — the token and pass dropped — is what the app reads.
        assertEquals(GraineVerification(user = GraineUserVerdict.UNVERIFIED, userReason = "missing"), Graine.verification.value)
        // The new user's own token, later: setIdentityToken, for them only.
        Graine.setIdentityToken("tok-456")
        Graine.setIdentityToken(null)
        assertEquals(listOf<String?>("tok-456", null), engine.tokensSet)
    }

    @Test
    fun theTokenNeedsAConfiguredAgentLikeIdentify() {
        assertFailsWith<IllegalStateException> { Graine.identify(mapOf("user_id" to "u-1"), identityToken = "t") }
        assertFailsWith<IllegalStateException> { Graine.setIdentityToken("t") }
        assertNull(Graine.debugToken, "nothing configured: no debug token")
    }

    @Test
    fun theTokenIsTheAppsOnceForEveryAgent() {
        configured()
        val support = FakeEngine()
        Graine.install(support, "support")
        Graine.identify(mapOf("user_id" to "u-1"), identityToken = "t-1")
        Graine.setIdentityToken("t-2")
        // Told once, to the default agent's platform SDK, which gives every agent the same user (and token).
        assertEquals(listOf<String?>("t-1"), engine.identityTokens)
        assertEquals(listOf<String?>("t-2"), engine.tokensSet)
        assertTrue(support.identityTokens.isEmpty() && support.tokensSet.isEmpty())
    }

    // ── the refresher: suspend on the app's side, a callback on the platform's ──

    /** Ask the refresher the platform SDK holds, as it asks: a callback, answered once. */
    private fun ask(e: FakeEngine = engine): MutableList<String?> {
        val answers = mutableListOf<String?>()
        assertNotNull(e.refresher, "the platform SDK holds a refresher").invoke { answers += it }
        return answers
    }

    @Test
    fun theRefresherAnswersThePlatformOnceWithItsToken() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        var asked = 0
        Graine.identityTokenRefresher = { asked++; delay(500); "fresh-token" }
        val answers = ask()
        runCurrent()
        assertEquals(1, asked, "started at once, where the platform asked")
        assertTrue(answers.isEmpty(), "it waits for the app's refresher")
        advanceUntilIdle()
        assertEquals(listOf<String?>("fresh-token"), answers)
    }

    @Test
    fun aRefresherThatAnswersNothingOrThrowsKeepsTheToken() = runTest {
        Graine.actionDispatcher = StandardTestDispatcher(testScheduler)
        configured()
        val refreshers: List<suspend () -> String?> = listOf(
            { null },
            { "   " },
            { delay(10); throw IllegalStateException("The token service is down.") },
            { throw CancellationException("The user left.") },
        )
        for (r in refreshers) {
            Graine.identityTokenRefresher = r
            val answers = ask()
            advanceUntilIdle()
            assertEquals(listOf<String?>(null), answers, "null keeps the token the platform SDK holds — and it is answered")
        }
    }

    @Test
    fun aRefresherThatDoesNotSuspendAnswersInsideThePlatformsCall() {
        configured()
        Graine.identityTokenRefresher = { "at-once" }
        assertEquals(listOf<String?>("at-once"), ask())
    }

    @Test
    fun theRefresherSetBeforeConfigureReachesEveryAgentAndNullRemovesIt() {
        val refresher: suspend () -> String? = { "t" }
        Graine.identityTokenRefresher = refresher
        assertSame(refresher, Graine.identityTokenRefresher)
        configured()
        val support = FakeEngine()
        Graine.install(support, "support")
        assertEquals(listOf(true), engine.refreshersSet)
        assertEquals(listOf(true), support.refreshersSet)
        assertEquals(listOf<String?>("t"), ask(support))
        Graine.identityTokenRefresher = null
        assertNull(Graine.identityTokenRefresher)
        assertEquals(listOf(true, false), engine.refreshersSet)
        assertEquals(listOf(true, false), support.refreshersSet)
        assertNull(engine.refresher)
    }

    // ── proving the app ──────────────────────────────────────────────────

    @Test
    fun prepareAppVerificationReturnsTheVerdictOnceThePlatformHasOne() = runTest {
        configured().prepareAnswer = null
        val verdict = async { Graine.prepareAppVerification() }
        runCurrent()
        assertFalse(verdict.isCompleted, "it waits for the settings and the proof")
        assertEquals(listOf("prepareAppVerification"), engine.calls)
        engine.prepareDone!!(GraineAppVerdict.VERIFIED)
        assertEquals(GraineAppVerdict.VERIFIED, verdict.await())
        // Every verdict the platform can come to, as it said it.
        for (v in GraineAppVerdict.entries) {
            engine.prepareAnswer = v
            assertEquals(v, Graine.prepareAppVerification())
        }
    }

    @Test
    fun eachAgentProvesItselfAndTheAppNeedsOneConfigured() = runTest {
        assertFailsWith<IllegalStateException> { Graine.prepareAppVerification() }
        configured().prepareAnswer = GraineAppVerdict.OFF
        val support = FakeEngine().also { it.prepareAnswer = GraineAppVerdict.DEBUG_TOKEN }
        Graine.install(support, "support")
        assertEquals(GraineAppVerdict.DEBUG_TOKEN, Graine.agent("support")!!.prepareAppVerification())
        assertEquals(GraineAppVerdict.OFF, Graine.prepareAppVerification())
        assertEquals(listOf("prepareAppVerification"), support.calls)
    }

    // ── the verification, per agent ──────────────────────────────────────

    @Test
    fun theVerificationIsEachAgentsAsItsPlatformReportsIt() {
        assertEquals(GraineVerification(), Graine.verification.value, "before configure: off and off")
        configured()
        val support = FakeEngine()
        Graine.install(support, "support")
        val proven = GraineVerification(GraineAppVerdict.VERIFIED, null, GraineUserVerdict.TEST_USER, null)
        engine.sink.verification(proven)
        support.sink.verification(GraineVerification(GraineAppVerdict.UNVERIFIED, "debug_token_expired", GraineUserVerdict.INVALID, "signature"))
        assertEquals(proven, Graine.verification.value)
        assertSame(Graine.agent()!!.verification.value, Graine.verification.value)
        assertEquals(GraineVerification(GraineAppVerdict.UNVERIFIED, "debug_token_expired", GraineUserVerdict.INVALID, "signature"), Graine.agent("support")!!.verification.value)
        Graine.reset()
        assertEquals(GraineVerification(), Graine.verification.value)
    }

    // ── development mode ─────────────────────────────────────────────────

    @Test
    fun aModeChosenBeforeConfigureReachesEveryAgentFirst() {
        Graine.debugTokenMode = GraineDebugTokenMode.ALWAYS
        assertEquals(GraineDebugTokenMode.ALWAYS, Graine.debugTokenMode)
        configured()
        val support = FakeEngine()
        Graine.install(support, "support")
        assertEquals(listOf(GraineDebugTokenMode.ALWAYS), engine.debugTokenModes)
        assertEquals(listOf(GraineDebugTokenMode.ALWAYS), support.debugTokenModes)
        Graine.debugTokenMode = GraineDebugTokenMode.NEVER
        assertEquals(listOf(GraineDebugTokenMode.ALWAYS, GraineDebugTokenMode.NEVER), engine.debugTokenModes)
        assertEquals(GraineDebugTokenMode.NEVER, support.debugTokenMode)
    }

    @Test
    fun anAppThatNeverChoseReadsThePlatformsModeAndToken() {
        configured()
        assertTrue(engine.debugTokenModes.isEmpty(), "nothing chosen: the platform keeps its own")
        engine.debugTokenMode = GraineDebugTokenMode.NEVER
        assertEquals(GraineDebugTokenMode.NEVER, Graine.debugTokenMode)
        assertNull(Graine.debugToken)
        // A token of the contract's shape, as the platform SDK made it: read through, never changed.
        val token = "gdt_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5_-"
        assertTrue(Regex(obj("shapes")["debugToken"] as String).matches(token))
        engine.debugToken = token
        assertEquals(token, Graine.debugToken)
    }
}
