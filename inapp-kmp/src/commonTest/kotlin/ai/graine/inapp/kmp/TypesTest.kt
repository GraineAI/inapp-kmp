package ai.graine.inapp.kmp

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TypesTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")

    // ── WIRE.md's types ───────────────────────────────────────────────────

    @Test
    fun everyStatusOnTheWireHasItsType() {
        val words = listOf("scheduled", "ringing", "answered", "completed", "declined", "missed", "failed", "cancelled", "expired", "no_device")
        val types = listOf(
            GraineCallStatus.Scheduled, GraineCallStatus.Ringing, GraineCallStatus.Answered, GraineCallStatus.Completed,
            GraineCallStatus.Declined, GraineCallStatus.Missed, GraineCallStatus.Failed, GraineCallStatus.Cancelled,
            GraineCallStatus.Expired, GraineCallStatus.NoDevice,
        )
        assertEquals(types, words.map { GraineCallStatus.from(it) })
        assertEquals(words, types.map { it.wire })
        assertEquals(GraineCallStatus.NoDevice, GraineCallStatus.from(" NO_DEVICE "))
    }

    @Test
    fun aStatusThisBuildDoesNotKnowPassesThrough() {
        assertEquals(GraineCallStatus.Unknown("snoozed"), GraineCallStatus.from("snoozed"))
        assertEquals("snoozed", GraineCallStatus.from("snoozed").wire)
        assertEquals(GraineCallStatus.Scheduled, GraineCallStatus.from(null))
        assertEquals(GraineCallStatus.Scheduled, GraineCallStatus.from(""))
    }

    @Test
    fun theAppIsWhoBookedWhatTheWireCallsSdk() {
        assertEquals(GraineCallSource.App, GraineCallSource.from("sdk"))
        assertEquals("sdk", GraineCallSource.App.wire)
        assertEquals(GraineCallSource.Agent, GraineCallSource.from("agent"))
        assertEquals(GraineCallSource.Dashboard, GraineCallSource.from("dashboard"))
        assertEquals(GraineCallSource.Api, GraineCallSource.from("API"))
        assertEquals(GraineCallSource.Unknown("crm"), GraineCallSource.from("crm"))
        assertNull(GraineCallSource.from(null))
        assertNull(GraineCallSource.from(" "))
    }

    @Test
    fun upcomingIsScheduledOrRingingAndOnlyScheduledCanBeCancelled() {
        fun call(status: GraineCallStatus) = GraineScheduledCall("c", now, status)
        assertTrue(call(GraineCallStatus.Scheduled).isUpcoming)
        assertTrue(call(GraineCallStatus.Scheduled).canCancel)
        assertTrue(call(GraineCallStatus.Ringing).isUpcoming)
        assertFalse(call(GraineCallStatus.Ringing).canCancel)
        for (s in listOf(GraineCallStatus.Answered, GraineCallStatus.Cancelled, GraineCallStatus.NoDevice, GraineCallStatus.Unknown("x"))) {
            assertFalse(call(s).isUpcoming, "$s")
            assertFalse(call(s).canCancel, "$s")
        }
    }

    @Test
    fun theRulesDefaultsAreWiresAndNobodyMayBook() {
        val r = GraineSchedulingRules()
        assertFalse(r.usersCanBook)
        assertEquals(5, r.minLeadMinutes)
        assertEquals(14, r.maxDaysAhead)
        assertEquals(3, r.maxPendingPerUser)
        assertEquals(45, r.ringTimeoutSeconds)
        assertEquals(now + 5.minutes, r.earliest(now))
        assertEquals(now + 14.days, r.latest(now))
    }

    @Test
    fun everyErrorCodeOnTheWireHasItsType() {
        val words = listOf(
            "invalid_key", "origin_refused", "rate_limited", "invalid_request", "identity_required", "device_limit",
            "scheduling_off", "too_soon", "too_far", "outside_window", "too_many_pending", "not_found", "unavailable", "network",
            "not_allowed", "not_verified",   // startWorkflow (0.14.4)
        )
        for (w in words) {
            val code = GraineErrorCode.from(w)
            assertFalse(code is GraineErrorCode.Unknown, w)
            assertEquals(w, code.wire)
        }
        // A platform SDK's own words (iOS server_error, Android not_ready) and newer server codes pass through.
        assertEquals(GraineErrorCode.Unknown("server_error"), GraineErrorCode.from("server_error"))
        assertEquals(GraineErrorCode.Unknown("not_ready"), GraineErrorCode.from("not_ready"))
        assertEquals(GraineErrorCode.Unknown("unknown"), GraineErrorCode.from(null))
    }

    @Test
    fun anExceptionKeepsTheServersSentenceAndWait() {
        val e = Bridge.error("rate_limited", "Too many requests. Try again in a minute.", 60)
        assertEquals(GraineErrorCode.RateLimited, e.code)
        assertEquals("Too many requests. Try again in a minute.", e.message)
        assertEquals(60, e.retryAfterSeconds)
        assertEquals("GraineException(rate_limited): Too many requests. Try again in a minute.", e.toString())
        assertNull(Bridge.error("network", null, 0).retryAfterSeconds)
        assertEquals("Graine could not complete the request.", Bridge.error("network", " ").message)
    }

    // ── the local booking checks ──────────────────────────────────────────

    @Test
    fun aBookingInsideTheRulesPasses() {
        val rules = GraineSchedulingRules(usersCanBook = true)
        assertNull(Scheduling.check(now + 5.minutes, now, rules), "exactly the lead time is allowed")
        assertNull(Scheduling.check(now + 14.days, now, rules), "exactly the furthest day is allowed")
    }

    @Test
    fun theRulesRefuseInOrderOff_Soon_Far() {
        val off = GraineSchedulingRules(usersCanBook = false)
        assertEquals(GraineErrorCode.SchedulingOff, Scheduling.check(now - 1.days, now, off)?.code, "off wins over too soon")
        val on = GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 1, maxDaysAhead = 1)
        val soon = Scheduling.check(now + 59.seconds, now, on)!!
        assertEquals(GraineErrorCode.TooSoon, soon.code)
        assertEquals("Pick a time at least 1 minute from now", soon.message)
        val past = Scheduling.check(now - 1.minutes, now, on)!!
        assertEquals(GraineErrorCode.TooSoon, past.code)
        val far = Scheduling.check(now + 1.days + 1.seconds, now, on)!!
        assertEquals(GraineErrorCode.TooFar, far.code)
        assertEquals("Pick a time within 1 day", far.message)
    }

    // ── JSON across the bridge ────────────────────────────────────────────

    @Test
    fun jsonReadsEveryShapeAsTheSharedCodeExpects() {
        val v = Json.read("""{"a":1,"b":-2.5,"c":true,"d":null,"e":"x\"y\\z\u00e9\n","f":[1,"2",{"g":[]}],"h":12345678901234,"i":1e3}""")
        assertEquals(
            mapOf("a" to 1L, "b" to -2.5, "c" to true, "d" to null, "e" to "x\"y\\zé\n", "f" to listOf(1L, "2", mapOf("g" to emptyList<Any?>())),
                "h" to 12345678901234L, "i" to 1000.0),
            v,
        )
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "h", "i"), (v as Map<*, *>).keys.toList(), "key order is kept")
    }

    @Test
    fun jsonWritesWhatItReads() {
        val value = mapOf(
            "name" to "Riya \"R\" Sharma", "age" to 41, "big" to 9_007_199_254_740_993L, "ratio" to 0.25, "whole" to 3.0,
            "nan" to Double.NaN, "ok" to false, "none" to null, "tags" to listOf("a", 1, null), "nested" to mapOf("k" to arrayOf(1, 2)),
            "tab" to "a\tb", "ctrl" to "\u0001", "sep" to "\u2028",
        )
        val text = Json.write(value)
        assertEquals(
            """{"name":"Riya \"R\" Sharma","age":41,"big":9007199254740993,"ratio":0.25,"whole":3,"nan":null,"ok":false,"none":null,""" +
                """"tags":["a",1,null],"nested":{"k":[1,2]},"tab":"a\tb","ctrl":"\u0001","sep":"\u2028"}""",
            text,
        )
        assertEquals("Riya \"R\" Sharma", (Json.read(text) as Map<*, *>)["name"])
        assertEquals(GraineFieldStatus.INVALID.name.lowercase(), (Json.read(Json.write(mapOf("s" to GraineFieldStatus.INVALID))) as Map<*, *>)["s"])
    }

    @Test
    fun textThatIsNotJsonIsRefusedOrReadAsNothing() {
        for (bad in listOf("{", "[1,]", "{\"a\" 1}", "tru", "01x", "\"open", "{} extra", "-", "\"\\q\"")) {
            assertFailsWith<IllegalArgumentException>(bad) { Json.read(bad) }
        }
        assertNull(Json.obj("[1]"))
        assertNull(Json.obj("not json"))
        assertNull(Json.obj(null))
        assertNull(Json.array("{}"))
        assertEquals("\uD83D\uDE00", Json.read("\"\\ud83d\\ude00\""))
    }
}
