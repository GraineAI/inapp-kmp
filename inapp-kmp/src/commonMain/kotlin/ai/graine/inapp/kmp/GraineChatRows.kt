package ai.graine.inapp.kmp

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** Which day a separator names. */
enum class GraineChatDay { TODAY, YESTERDAY, DATE }

/** One row of a chat window, as [GraineChatRows.build] lays the conversation out. */
sealed class GraineChatRow {
    /** Stable while the conversation grows: a separator by its date, a call by its first turn, a bubble by its turn. */
    abstract val key: String

    /** A day separator: which day, the device's date, and the first message's time on it. */
    data class Day(val day: GraineChatDay, val date: LocalDate, val at: Instant) : GraineChatRow() {
        override val key: String get() = "day-$date"
    }

    /**
     * An earlier call, as ONE row — that it happened and when, never its words:
     * when its first turn was said, and which turns ([first] to [last], indices
     * into the conversation) it stands for.
     */
    data class VoiceCall(val id: String, val at: Instant, val first: Int, val last: Int) : GraineChatRow() {
        override val key: String get() = id
    }

    /** A message bubble: the turn, and its index in the conversation. */
    data class Turn(val turn: GraineChatTurn, val index: Int) : GraineChatRow() {
        override val key: String get() = turn.id
    }

    /**
     * Where Graine began a new conversation under earlier lines
     * ([GraineChat.newConversationBefore]): drawn like a day separator, after
     * any separator of the turn it goes before — [before], that turn's id, at
     * [index] in the conversation. Never a turn, and never sent.
     */
    data class NewConversation(val before: String, val index: Int) : GraineChatRow() {
        override val key: String get() = "fresh-$before"
        /** What it says: "Disconnected · new conversation". */
        val label: String get() = GraineChatRows.NEW_CONVERSATION
        /** What a screen reader says for it: "Disconnected. A new conversation started." */
        val spoken: String get() = GraineChatRows.NEW_CONVERSATION_SPOKEN
    }
}

/**
 * WHAT A CHAT WINDOW DRAWS, in order — the rule every Graine SDK draws its own
 * window by, for an app that draws its chat from `Graine.chat.turns` (the
 * SDK's `GraineChat()` already does): a bubble per chat turn; ONE "Voice call ·
 * 6:05 PM" row for a run of voice turns on one day; a day separator —
 * Today, Yesterday, a date — before the first row when it is not from today,
 * and wherever the device's day changes between rows; and, given
 * `Graine.chat.newConversationBefore`, a "Disconnected · new conversation"
 * row where a new conversation began. A conversation all from today draws
 * exactly what it always did.
 */
object GraineChatRows {
    private val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** The row where a new conversation began under earlier lines ([GraineChatRow.NewConversation]). */
    const val NEW_CONVERSATION = "Disconnected · new conversation"
    /** What a screen reader says for it. */
    const val NEW_CONVERSATION_SPOKEN = "Disconnected. A new conversation started."

    /** The rows for [turns] (oldest first), on the days of [zone] — the device's own unless a test says otherwise. */
    fun build(
        turns: List<GraineChatTurn>,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<GraineChatRow> = build(turns, emptyList(), now, zone)

    /**
     * The rows for [turns] with a [GraineChatRow.NewConversation] before each
     * turn whose id is in [newConversationBefore] (`Graine.chat.newConversationBefore`)
     * — after that turn's day separator, and only under earlier rows: with
     * nothing drawn before it there is nothing to set apart. A marked voice
     * turn starts a call row of its own.
     */
    fun build(
        turns: List<GraineChatTurn>,
        newConversationBefore: Collection<String>,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<GraineChatRow> {
        val today = now.toLocalDateTime(zone).date
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val marks = newConversationBefore.toHashSet()
        val rows = ArrayList<GraineChatRow>()
        var lastDay: LocalDate? = null
        var openCall = -1
        var callDay: LocalDate? = null
        var drawn = false
        turns.forEachIndexed { index, turn ->
            val day = turn.at.toLocalDateTime(zone).date
            val voice = turn.channel == GraineChatChannel.VOICE
            val fresh = drawn && turn.id in marks
            // One call, one row — until the day turns, or a new conversation begins: a separator never sits inside a row.
            val call = if (openCall >= 0) rows[openCall] as? GraineChatRow.VoiceCall else null
            if (voice && call != null && callDay == day && !fresh) {
                rows[openCall] = call.copy(last = index)
                return@forEachIndexed
            }
            if (if (lastDay == null) day != today else day != lastDay) {
                val kind = when (day) {
                    today -> GraineChatDay.TODAY
                    yesterday -> GraineChatDay.YESTERDAY
                    else -> GraineChatDay.DATE
                }
                rows += GraineChatRow.Day(kind, day, turn.at)
            }
            lastDay = day
            if (fresh) rows += GraineChatRow.NewConversation(turn.id, index)
            drawn = true
            if (voice) {
                rows += GraineChatRow.VoiceCall("voice-${turn.id}", turn.at, index, index)
                openCall = rows.size - 1
                callDay = day
            } else {
                rows += GraineChatRow.Turn(turn, index)
                openCall = -1
                callDay = null
            }
        }
        return rows
    }

    /** "Today", "Yesterday", else the date — "Sat 10 Oct", with the year when it is not this one ("Wed 31 Dec 2025"). */
    fun dayLabel(row: GraineChatRow.Day, now: Instant = Clock.System.now(), zone: TimeZone = TimeZone.currentSystemDefault()): String =
        when (row.day) {
            GraineChatDay.TODAY -> "Today"
            GraineChatDay.YESTERDAY -> "Yesterday"
            GraineChatDay.DATE -> {
                val d = row.date
                val year = if (d.year == now.toLocalDateTime(zone).year) "" else " ${d.year}"
                "${weekdays[d.dayOfWeek.ordinal]} ${d.dayOfMonth} ${months[d.monthNumber - 1]}$year"
            }
        }

    /** "Voice call · 6:05 PM": [clock] is the call's first turn in the device's short time. */
    fun callLabel(clock: String): String = "Voice call · $clock"

    /** The brand's starters belong to a new chat: not once the user has said something, nor on a conversation that continues. */
    fun offersStarters(turns: List<GraineChatTurn>): Boolean = turns.none { it.role == GraineRole.USER || it.restored }

    /** "New conversation" belongs to a conversation that continues: offered while restored turns are on screen. */
    fun offersNewConversation(turns: List<GraineChatTurn>): Boolean = turns.any { it.restored }
}
