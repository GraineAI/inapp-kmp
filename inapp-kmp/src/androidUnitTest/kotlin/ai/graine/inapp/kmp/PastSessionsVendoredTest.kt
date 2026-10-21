package ai.graine.inapp.kmp

import ai.graine.inapp.GraineSessionChannel as NativeChannel
import ai.graine.inapp.GraineSessionDirection as NativeDirection
import ai.graine.inapp.GraineSessionSummary as NativeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Past sessions on Android (contract/PUBLIC-STATE.md "Past sessions"): the vendored vectors are
 * the contract's, and every session the contract says an SDK keeps crosses from the Android SDK's
 * own type into the shared one unchanged — the same session the iOS bridge reads (PastSessionsContractTest).
 */
class PastSessionsVendoredTest {

    @Test
    fun theVendoredVectorsAreTheContracts() {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder: the vendored copy is the only one", dir != null)
        val contract = File(dir!!, "past-sessions.json")
        assertTrue("$contract is missing", contract.isFile)
        assertEquals(
            "src/commonTest/contract/past-sessions.json must be a copy of sdk/contract/past-sessions.json: copy it over",
            contract.readText(),
            PAST_SESSIONS_JSON,
        )
    }

    @Test
    fun everyKeptSessionCrossesFromTheAndroidSdkUnchanged() {
        val pages = (Json.obj(PAST_SESSIONS_JSON)!!["pages"] as List<*>).map { it as Map<*, *> }
        var crossed = 0
        for (c in pages) {
            val response = c["response"] as Map<*, *>
            // The shared reading of the page: what the SDK keeps, as the contract says.
            for (kept in PastSessionsWire.page(response).sessions) {
                val native = NativeSummary(
                    kept.id, Date(kept.startedAt.toEpochMilliseconds()), kept.endedAt?.let { Date(it.toEpochMilliseconds()) }, kept.durationSeconds,
                    NativeChannel.values().first { it.wire == kept.channel.wire },
                    kept.direction?.let { d -> NativeDirection.values().first { it.wire == d.wire } },
                    kept.status, kept.turns, kept.preview,
                )
                assertEquals(c["name"] as String, kept, AndroidMapping.sessionSummary(native))
                crossed++
            }
        }
        assertTrue("the vectors were read", crossed >= 4)
    }
}
