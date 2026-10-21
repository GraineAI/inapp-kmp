package ai.graine.inapp.kmp

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Conversations continue (contract §2.6) — sdk/contract/history.json, run here on the JVM and on iOS. */
class HistoryContractTest {

    @Test
    fun everyRestoredTurnCrossesTheIosBridgeAsTheContractSays() {
        val turns = HistoryVectors.turns
        assertTrue(turns.size >= 20, "the vectors were read: ${turns.size} turns")
        assertTrue(turns.any { it.channel == GraineChatChannel.VOICE } && turns.any { it.delivery == GraineDelivery.FAILED })
        // One chat snapshot, as the facade sends it.
        val snapshot = mapOf("turns" to turns.mapIndexed { i, e -> HistoryVectors.facadeJson(e, i) }, "status" to "online", "typing" to false)
        val chat = Bridge.chat(Json.write(snapshot))
        assertEquals(turns.size, chat.turns.size)
        turns.zip(chat.turns).forEach { (expected, turn) -> HistoryVectors.assertCarried(expected, turn) }
    }

    @Test
    fun theRowsAreTheContracts() {
        for (c in HistoryVectors.section("rows")) {
            val name = c["name"] as String
            val now = Instant.parse(c["now"] as String)
            val turns = (c["turns"] as List<*>).mapIndexed { i, raw ->
                val t = raw as Map<*, *>
                GraineChatTurn(
                    id = "r$i",
                    role = if (t["role"] == "customer") GraineRole.USER else GraineRole.AGENT,
                    text = t["text"] as String,
                    at = Instant.parse(t["at"] as String),
                    channel = if (t["channel"] == "voice") GraineChatChannel.VOICE else GraineChatChannel.CHAT,
                )
            }
            val want = (c["rows"] as List<*>).map { contractRow(it as Map<*, *>) }
            // The device's zone by its name, and as the fixed offset the case gives: the same rows.
            val offset = (c["utcOffsetMinutes"] as Long).toInt()
            for (zone in listOf(TimeZone.of(c["timeZone"] as String), UtcOffset(hours = offset / 60, minutes = offset % 60).asTimeZone())) {
                assertEquals(want, GraineChatRows.build(turns, now, zone).map { row(it) }, "$name, in $zone")
            }
        }
    }

    /**
     * `sessionFrame`: the platform SDK reads the frame; the shared state reads
     * the platform's session id by the same rule — a string that is not empty,
     * else none — as the iOS facade hands it over.
     */
    @Test
    fun theSessionIdIsReadAsTheSessionFrameSays() {
        for (c in HistoryVectors.section("sessionFrame")) {
            val frame = c["frame"] as Map<*, *>
            val snapshot = mapOf("turns" to emptyList<Any>(), "status" to "online", "sessionId" to frame["id"])
            assertEquals(c["sessionId"] as String?, Bridge.chat(Json.write(snapshot)).extras.sessionId, c["name"] as String)
        }
    }

    @Test
    fun theSessionsAreDerivedAsTheContractSays() {
        for (c in HistoryVectors.section("sessions")) {
            val name = c["name"] as String
            val turns = (c["turns"] as List<*>).mapIndexed { i, raw ->
                val t = raw as Map<*, *>
                GraineChatTurn(
                    id = "s$i",
                    role = if (t["role"] == "customer") GraineRole.USER else GraineRole.AGENT,
                    text = t["text"] as String,
                    at = Instant.parse(t["at"] as String),
                    channel = if (t["channel"] == "voice") GraineChatChannel.VOICE else GraineChatChannel.CHAT,
                    session = t["session"] as String?,
                )
            }
            val want = (c["sessions"] as List<*>).map { raw ->
                val e = raw as Map<*, *>
                GraineChatSession(
                    id = e["id"] as String?,
                    startedAt = Instant.parse(e["startedAt"] as String),
                    endedAt = Instant.parse(e["endedAt"] as String),
                    channel = if (e["channel"] == "voice") GraineChatChannel.VOICE else GraineChatChannel.CHAT,
                    turns = (e["turns"] as Long).toInt(),
                    current = e["current"] as Boolean,
                )
            }
            assertEquals(want, ChatSessions.of(turns, c["sessionId"] as String?), name)
        }
    }

    /** The contract's row in one word: `turn:0`, `voice:1-3`, `day:today` / `day:yesterday` / `day:2026-10-12`. */
    private fun contractRow(r: Map<*, *>): String = when {
        "turn" in r -> "turn:${r["turn"]}"
        "voice" in r -> (r["voice"] as List<*>).let { "voice:${it[0]}-${it[1]}" }
        "day" in r -> "day:${r["day"]}"
        else -> error("a row the contract does not use: $r")
    }

    private fun row(r: GraineChatRow): String = when (r) {
        is GraineChatRow.Turn -> "turn:${r.index}"
        is GraineChatRow.VoiceCall -> "voice:${r.first}-${r.last}"
        is GraineChatRow.Day -> "day:" + when (r.day) {
            GraineChatDay.TODAY -> "today"
            GraineChatDay.YESTERDAY -> "yesterday"
            GraineChatDay.DATE -> r.date.toString()
        }
        is GraineChatRow.NewConversation -> "fresh:${r.index}"
    }

    // ── what a chat window says, and offers ───────────────────────────────

    private val now = Instant.parse("2026-10-14T06:30:00Z")
    private val utc = TimeZone.UTC

    private fun turn(id: String, at: String, user: Boolean = false, restored: Boolean = false, voice: Boolean = false) =
        GraineChatTurn(id, if (user) GraineRole.USER else GraineRole.AGENT, "…", Instant.parse(at), restored = restored,
            channel = if (voice) GraineChatChannel.VOICE else GraineChatChannel.CHAT)

    @Test
    fun aDayIsTodayYesterdayOrItsDate() {
        fun label(date: String) = GraineChatRows.dayLabel(
            GraineChatRows.build(listOf(turn("a", "${date}T09:00:00Z")), now, utc).first() as GraineChatRow.Day, now, utc,
        )
        assertEquals("Yesterday", label("2026-10-13"))
        assertEquals("Sat 10 Oct", label("2026-10-10"))
        assertEquals("Wed 31 Dec 2025", label("2025-12-31"), "the year when it is not this one")
        assertEquals("Today", GraineChatRows.dayLabel(GraineChatRow.Day(GraineChatDay.TODAY, LocalDate(2026, 10, 14), now), now, utc))
        assertEquals("Voice call · 6:05 PM", GraineChatRows.callLabel("6:05 PM"))
    }

    @Test
    fun aRowsKeyStaysWhileTheConversationGrows() {
        val first = GraineChatRows.build(listOf(turn("h1", "2026-10-13T09:00:00Z", voice = true)), now, utc)
        val more = GraineChatRows.build(
            listOf(turn("h1", "2026-10-13T09:00:00Z", voice = true), turn("h2", "2026-10-13T09:00:05Z", voice = true), turn("t1", "2026-10-14T06:00:00Z", user = true)),
            now, utc,
        )
        assertEquals(listOf("day-2026-10-13", "voice-h1"), first.map { it.key })
        assertEquals(listOf("day-2026-10-13", "voice-h1", "day-2026-10-14", "t1"), more.map { it.key })
        assertEquals(1, (more[1] as GraineChatRow.VoiceCall).last, "the call's second turn joined its row")
    }

    @Test
    fun startersAreForANewChatAndNewConversationForOneThatContinues() {
        val restored = listOf(turn("h1", "2026-10-13T09:00:00Z", restored = true))
        assertTrue(GraineChatRows.offersStarters(emptyList()))
        assertTrue(GraineChatRows.offersStarters(listOf(turn("w", "2026-10-14T06:00:00Z"))), "the welcome alone is a new chat")
        assertFalse(GraineChatRows.offersStarters(listOf(turn("t1", "2026-10-14T06:00:00Z", user = true))))
        assertFalse(GraineChatRows.offersStarters(restored), "never on a conversation that continues")
        assertTrue(GraineChatRows.offersNewConversation(restored))
        assertFalse(GraineChatRows.offersNewConversation(listOf(turn("t1", "2026-10-14T06:00:00Z", user = true))))
    }
}
