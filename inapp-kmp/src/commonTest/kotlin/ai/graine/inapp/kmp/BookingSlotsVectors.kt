package ai.graine.inapp.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE AGENT'S CALLING WINDOW (contract/PUBLIC-STATE.md §4): sdk/contract/booking-slots.json,
 * vendored ([BOOKING_SLOTS_JSON]). The platform SDKs offer the times — bookingSlots and the
 * booking sheet run the `slots` cases there; what this layer owes the contract is the window
 * itself, read as every SDK reads it (`window` cases), from both platforms: here from the iOS
 * facade's session, in BookingSlotsVendoredTest from the Android SDK's rules.
 */
internal object BookingSlotsVectors {
    private val contract: Map<String, Any?> = Json.obj(BOOKING_SLOTS_JSON) ?: error("booking-slots.json is not a JSON object")

    /** One `window` case: the session's `callingWindow` as sent, and the window it is read as. */
    data class Case(val name: String, val wire: Any?, val window: GraineCallingWindow?)

    val windows: List<Case> by lazy {
        @Suppress("UNCHECKED_CAST")
        val list = contract["window"] as List<Map<String, Any?>>
        check(list.isNotEmpty()) { "section window is empty" }
        list.map { c ->
            val w = c["window"] as? Map<*, *>
            Case(
                name = c["name"] as String,
                wire = c["wire"],
                window = w?.let { GraineCallingWindow(it["start"] as String, it["end"] as String, (it["days"] as List<*>).map { d -> (d as Long).toInt() }) },
            )
        }
    }

    /** How many `slots` cases the platform SDKs run: the vendored copy is the whole contract. */
    val slotCases: Int get() = (contract["slots"] as List<*>).size
}

class BookingSlotsContractTest {
    @Test
    fun everyWindowCrossesFromTheIosFacadeAsTheContractReadsIt() {
        val cases = BookingSlotsVectors.windows
        assertTrue(cases.size >= 10)
        assertTrue(BookingSlotsVectors.slotCases >= 10, "the vendored copy carries the slot cases too")
        for (c in cases) {
            val session = Bridge.session(Json.write(mapOf("agentId" to "agent-1", "calls" to mapOf("usersCanBook" to true, "callingWindow" to c.wire))))!!
            assertEquals(c.window, session.schedulingRules.callingWindow, c.name)
        }
    }

    @Test
    fun noWindowFromTheFacadeIsNoWindow() {
        assertNull(Bridge.session("""{"agentId":"a","calls":{"usersCanBook":true}}""")!!.schedulingRules.callingWindow)
        assertNull(Bridge.session("""{"agentId":"a"}""")!!.schedulingRules.callingWindow)
        assertNull(GraineSchedulingRules().callingWindow)
    }
}
