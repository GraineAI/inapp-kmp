package ai.graine.inapp.kmp

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

// ── what is on the screen ──────────────────────────────────────────────────

/** One input on a screen, as the agent should understand it. */
data class GraineField(
    val name: String,
    val label: String? = null,
    /** The real value. It is masked on the device on its way out; never pre-redact it. */
    val value: String? = null,
    val status: GraineFieldStatus? = null,
    /** The validation message actually shown — what the agent can offer to fix. */
    val error: String? = null,
    /** Leaving it blank is fine, so the agent does not count it as work left. */
    val optional: Boolean = false,
)

/** Where a field stands. `invalid` and `empty` are what make the agent speak up about it. */
enum class GraineFieldStatus(val wire: String) {
    EMPTY("empty"), FILLED("filled"), INVALID("invalid"), PENDING("pending"), LOCKED("locked"),
}

/**
 * What the customer is looking at. `status` on each field is what makes the
 * agent proactive: after a pause it names the field that is `invalid` or
 * `empty` instead of asking "need a hand?".
 *
 * Report real values. Masking runs on the device on the way out; a screen
 * that pre-redacts only blinds the agent.
 */
data class GraineScreen(
    /** A stable id for the screen; keep it constant. */
    val screen: String,
    val title: String? = null,
    val journey: Journey? = null,
    val fields: List<GraineField> = emptyList(),
    /** A screen-level error banner, distinct from a field's own. */
    val error: String? = null,
    /** Anything else the agent should know: strings, numbers, booleans, lists and maps of them. */
    val data: Map<String, Any?> = emptyMap(),
    /**
     * Which registered actions work here. Null allows every action this build
     * registered; an empty list allows none.
     */
    val availableActions: List<String>? = null,
) {
    /** Where this screen sits in a flow: step 2 of 4 of "KYC". */
    data class Journey(val name: String, val step: Int? = null, val of: Int? = null, val next: String? = null)
}

/** What an action handler answers. The agent speaks about exactly this, so tell the truth. */
data class GraineActionResult(
    val status: Status,
    /** Shown to the agent, not the customer: it decides how to say it. */
    val message: String = "",
    /** A structured result the agent can reason about. */
    val data: Map<String, Any?> = emptyMap(),
) {
    enum class Status(val wire: String) { OK("ok"), REFUSED("refused"), ERROR("error") }

    companion object {
        /** It happened. The agent tells the customer it is done. */
        fun ok(message: String = "", data: Map<String, Any?> = emptyMap()) = GraineActionResult(Status.OK, message, data)

        /** It will not happen, and why — often the most useful answer. */
        fun refused(message: String) = GraineActionResult(Status.REFUSED, message)

        /** It was tried and failed. */
        fun error(message: String) = GraineActionResult(Status.ERROR, message)
    }
}

/** Who said a line: the person using the app, or the agent. */
enum class GraineRole { USER, AGENT }

// ── memory ─────────────────────────────────────────────────────────────────

/** One thing the agent remembers about the signed-in user, as Graine keeps it (already redacted). */
data class GraineMemory(
    val id: String,
    val text: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

// ── scheduled calls ────────────────────────────────────────────────────────

/**
 * Where a booked call is. A word this build does not know yet arrives as
 * [Unknown], with the server's word in it.
 */
sealed class GraineCallStatus(val wire: String) {
    data object Scheduled : GraineCallStatus("scheduled")
    data object Ringing : GraineCallStatus("ringing")
    data object Answered : GraineCallStatus("answered")
    data object Completed : GraineCallStatus("completed")
    data object Declined : GraineCallStatus("declined")
    data object Missed : GraineCallStatus("missed")
    data object Failed : GraineCallStatus("failed")
    data object Cancelled : GraineCallStatus("cancelled")
    data object Expired : GraineCallStatus("expired")
    /** Nothing could be rung: the user has no device registered for calls. */
    data object NoDevice : GraineCallStatus("no_device")
    data class Unknown(val raw: String) : GraineCallStatus(raw)

    companion object {
        private val known by lazy {
            listOf(Scheduled, Ringing, Answered, Completed, Declined, Missed, Failed, Cancelled, Expired, NoDevice)
                .associateBy { it.wire }
        }

        /** The status for a word on the wire. None at all reads as [Scheduled], as the native SDKs read it. */
        fun from(wire: String?): GraineCallStatus {
            val word = wire?.trim().orEmpty()
            if (word.isEmpty()) return Scheduled
            return known[word.lowercase()] ?: Unknown(word)
        }
    }
}

/** Who booked a call. `sdk` on the wire is [App]: booked from the app with `scheduleCall`. */
sealed class GraineCallSource(val wire: String) {
    data object Agent : GraineCallSource("agent")
    data object App : GraineCallSource("sdk")
    data object Dashboard : GraineCallSource("dashboard")
    data object Api : GraineCallSource("api")
    data class Unknown(val raw: String) : GraineCallSource(raw)

    companion object {
        /** Null when the server does not say. */
        fun from(wire: String?): GraineCallSource? {
            val word = wire?.trim().orEmpty()
            if (word.isEmpty()) return null
            return when (word.lowercase()) {
                "agent" -> Agent
                "sdk", "app" -> App
                "dashboard" -> Dashboard
                "api" -> Api
                else -> Unknown(word)
            }
        }
    }
}

/** A call booked to ring this user (or, with nobody signed in, this install) later. */
data class GraineScheduledCall(
    val id: String,
    /** When it rings. Null only when the server's time could not be read. */
    val at: Instant?,
    val status: GraineCallStatus,
    /** Who booked it; null when the server does not say. */
    val source: GraineCallSource? = null,
    /** Why it was booked, when the server shares it. */
    val reason: String? = null,
) {
    /** Still to come: scheduled, or ringing right now. */
    val isUpcoming: Boolean get() = status == GraineCallStatus.Scheduled || status == GraineCallStatus.Ringing

    /** It can still be called off: nothing has rung yet. */
    val canCancel: Boolean get() = status == GraineCallStatus.Scheduled
}

/** A platform Graine can ring. */
enum class GrainePlatform(val wire: String) { IOS("ios"), ANDROID("android") }

/**
 * What the agent allows when a user books a call, from the session (Calls &
 * scheduling in the dashboard). The defaults are what applies when the server
 * says nothing — and then nobody may book.
 */
data class GraineSchedulingRules(
    /** Users may book calls from the app. False when the session says nothing. */
    val usersCanBook: Boolean = false,
    val minLeadMinutes: Int = 5,
    val maxDaysAhead: Int = 14,
    val maxPendingPerUser: Int = 3,
    /** How long a call Graine rings rings before it is missed. */
    val ringTimeoutSeconds: Int = 45,
    /** The name the agent's calls ring with; null: the app's own display name. */
    val callName: String? = null,
    /** Graine rings this workspace's users itself (its push credentials are uploaded). */
    val hostedRinging: Boolean = false,
    /** The platforms Graine rings. Empty from the server means every platform. */
    val platforms: List<GrainePlatform> = emptyList(),
) {
    /** The soonest time a booking is accepted: now plus [minLeadMinutes]. */
    fun earliest(now: Instant = Clock.System.now()): Instant = now + minLeadMinutes.minutes

    /** The latest time a booking is accepted: now plus [maxDaysAhead] days. */
    fun latest(now: Instant = Clock.System.now()): Instant = now + maxDaysAhead.days
}

/** What the dashboard says about this agent, once the session has loaded. */
data class GraineSession(
    val agentId: String,
    /** Whether the agent remembers its users; null when the server does not say. */
    val memoryEnabled: Boolean?,
    /** The trait that names the user (Agent → Memory → Who is the user?): `user_id` unless the dashboard chose another. */
    val identityField: String = "user_id",
    /** How many devices one user may use the agent on. */
    val devicesPerUser: Int = 2,
    val schedulingRules: GraineSchedulingRules = GraineSchedulingRules(),
    /** The actions the agent is given (Embed & Widgets → actions). */
    val declaredActions: Set<String> = emptySet(),
)
