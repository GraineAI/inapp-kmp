package ai.graine.inapp.kmp

import ai.graine.inapp.GraineSchedulingRules as NativeSchedulingRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The agent's calling window on Android (contract/PUBLIC-STATE.md §4): the vendored vectors are
 * the contract's, and the window the Android SDK reads from the session crosses as the contract
 * says — the same window the iOS side carries (BookingSlotsContractTest).
 */
class BookingSlotsVendoredTest {

    @Test
    fun theVendoredVectorsAreTheContracts() {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder: the vendored copy is the only one", dir != null)
        val contract = File(dir!!, "booking-slots.json")
        assertTrue("$contract is missing", contract.isFile)
        assertEquals(
            "src/commonTest/contract/booking-slots.json must be a copy of sdk/contract/booking-slots.json: copy it over",
            contract.readText(),
            BOOKING_SLOTS_JSON,
        )
    }

    @Test
    fun everyWindowCrossesFromTheAndroidSdkAsTheContractReadsIt() {
        val cases = BookingSlotsVectors.windows
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val calls = JSONObject("""{"scheduling":{"usersCanBook":true,"callingWindow":${Json.write(c.wire)}}}""")
            val native = NativeSchedulingRules.parse(calls)
            assertEquals(c.name, c.window, AndroidMapping.callingWindow(native.callingWindow))
        }
    }
}
