package ai.graine.inapp.kmp

import kotlinx.datetime.Instant

/*
 * PAST SESSIONS (0.14.3; the contract's PUBLIC-STATE.md "Past sessions", the
 * wire and its vectors in past-sessions.json, vendored in
 * src/commonTest/contract): the signed-in user's earlier sessions with an
 * agent, newest first — as the dashboard's Execution History lists them — and
 * each one's transcript. The platform SDK holds the list and asks Graine for
 * it; [GraineChat.pastSessions] follows it on both platforms. The iOS SDK's
 * facade hands it over in the wire's own shape, read here by the contract's
 * rules ([PastSessionsWire]).
 */

/** Where a past session happened ([GraineSessionSummary.channel]). */
enum class GraineSessionChannel(
    /** The word on the wire: `chat`, `voice` or `call`. */
    val wire: String,
) {
    /** Typed, in the chat. */
    CHAT("chat"),
    /** Talked with the agent in the app. */
    VOICE("voice"),
    /** A call with the agent — [GraineSessionSummary.direction] says who rang whom. */
    CALL("call"),
}

/** Who started a past call or talk ([GraineSessionSummary.direction]). */
enum class GraineSessionDirection(
    /** The word on the wire: `inbound` or `outbound`. */
    val wire: String,
) {
    /** The user reached the agent. */
    INBOUND("inbound"),
    /** The agent reached the user. */
    OUTBOUND("outbound"),
}

/**
 * ONE OF THE USER'S EARLIER SESSIONS with this agent, as
 * [GraineChat.pastSessions] lists them — newest first. [id] is the session's
 * id, the one Execution History shows; open its chat history with
 * [GraineChat.sessionTranscript]. New in 0.14.3.
 */
data class GraineSessionSummary(
    /** The session's id — the id Execution History shows; pass it to [GraineChat.sessionTranscript]. */
    val id: String,
    /** When it started. */
    val startedAt: Instant,
    /** When it ended; null when Graine did not say. */
    val endedAt: Instant? = null,
    /** How long it lasted, in whole seconds; null when Graine did not say. */
    val durationSeconds: Int? = null,
    /** Typed ([GraineSessionChannel.CHAT]), talked in the app ([GraineSessionChannel.VOICE]) or a call ([GraineSessionChannel.CALL]). */
    val channel: GraineSessionChannel = GraineSessionChannel.CHAT,
    /** Who started a call or a talk; null for a chat, and when Graine did not say. */
    val direction: GraineSessionDirection? = null,
    /** How it ended, in plain words — `completed`, `missed`, `failed`, … — as Graine says it; "" when it did not. */
    val status: String = "",
    /** How many messages it holds. */
    val turns: Int = 0,
    /** The user's first line, at most 120 characters; "" when there is none. */
    val preview: String = "",
)

/** One message of a past session's transcript: who said it, what, and when. New in 0.14.3. */
data class GraineSessionTurn(
    /** [GraineRole.USER] or [GraineRole.AGENT]. */
    val role: GraineRole,
    /** What was said. */
    val text: String,
    /** When it was said. */
    val at: Instant,
)

/**
 * A PAST SESSION'S CHAT HISTORY, oldest first — what
 * [GraineChat.sessionTranscript] answers: the session as the list describes
 * it, and every message said in it. New in 0.14.3.
 */
data class GraineSessionTranscript(
    /** The session's id. */
    val id: String,
    /** When it started; null when Graine did not say. */
    val startedAt: Instant? = null,
    /** When it ended; null when Graine did not say. */
    val endedAt: Instant? = null,
    /** How long it lasted, in whole seconds; null when Graine did not say. */
    val durationSeconds: Int? = null,
    /** Typed, talked in the app or a call. */
    val channel: GraineSessionChannel = GraineSessionChannel.CHAT,
    /** Who started a call or a talk; null for a chat, and when Graine did not say. */
    val direction: GraineSessionDirection? = null,
    /** How it ended, in plain words, as Graine says it; "" when it did not. */
    val status: String = "",
    /** What was said, oldest first. */
    val turns: List<GraineSessionTurn> = emptyList(),
)

/**
 * THE USER'S PAST SESSIONS, AS STATE — [GraineChat.pastSessions]: the
 * sessions loaded so far (newest first), whether a page is loading, whether
 * there is another, and the last refusal's word. New in 0.14.3.
 */
data class GrainePastSessions(
    /** The sessions loaded so far, newest first; empty until [GraineChat.refreshPastSessions]. */
    val sessions: List<GraineSessionSummary> = emptyList(),
    /** A page is on its way. */
    val loading: Boolean = false,
    /** There is another page: [GraineChat.loadMorePastSessions] appends it. */
    val hasMore: Boolean = false,
    /**
     * Why the last page did not come, as a word ([GraineErrorCode.wire]) —
     * `identity_required`, `device_limit`, `rate_limited`, `unavailable`,
     * `network`, …; null when it came.
     */
    val error: String? = null,
)

/**
 * THE PAST-SESSIONS WIRE, read by the contract's rules — the list's page
 * (`{sessions, next}`), a transcript (`{id, startedAt, endedAt,
 * durationSeconds, channel, direction, status, turns}`), and the iOS SDK's
 * state (`{sessions, loading, hasMore, error}`), which carries its sessions in
 * the same shape. An item without an id or a readable `startedAt` is skipped
 * (an id repeated is kept once, the first); `endedAt` / `durationSeconds`
 * unreadable → null; a channel other than voice/call is chat; a direction
 * other than inbound/outbound is null; turns not a whole number ≥ 0 → 0;
 * preview (and status) not a string → ""; a turn without a user/assistant
 * role, a string text or a readable `at` is skipped.
 */
internal object PastSessionsWire {
    /** One page: its sessions as the SDK keeps them, and where the next page starts (null at the end). */
    data class Page(val sessions: List<GraineSessionSummary>, val next: String?)

    fun page(body: Map<*, *>?): Page {
        val seen = HashSet<String>()
        val sessions = (body?.get("sessions") as? List<*>).orEmpty().mapNotNull { summary(it) }.filter { seen.add(it.id) }
        return Page(sessions, text(body?.get("next")))
    }

    /** The iOS SDK's `pastSessionsJSON`: its sessions by the page's rules, and its flags. Nothing to read: the empty state. */
    fun state(json: String?): GrainePastSessions {
        val o = Json.obj(json) ?: return GrainePastSessions()
        return GrainePastSessions(
            sessions = page(o).sessions,
            loading = o["loading"] == true,
            hasMore = o["hasMore"] == true,
            error = text(o["error"]),
        )
    }

    /** One session of a page; null without an id or a readable `startedAt`. */
    fun summary(raw: Any?): GraineSessionSummary? {
        val o = raw as? Map<*, *> ?: return null
        val id = text(o["id"]) ?: return null
        val startedAt = instant(o["startedAt"]) ?: return null
        return GraineSessionSummary(
            id = id,
            startedAt = startedAt,
            endedAt = instant(o["endedAt"]),
            durationSeconds = seconds(o["durationSeconds"]),
            channel = channel(o["channel"]),
            direction = direction(o["direction"]),
            status = o["status"] as? String ?: "",
            turns = count(o["turns"]),
            preview = o["preview"] as? String ?: "",
        )
    }

    /** A transcript's answer, as JSON; [asked] is its id when the answer does not say. */
    fun transcript(json: String?, asked: String): GraineSessionTranscript = transcript(Json.obj(json), asked)

    fun transcript(body: Map<*, *>?, asked: String): GraineSessionTranscript = GraineSessionTranscript(
        id = text(body?.get("id")) ?: asked,
        startedAt = instant(body?.get("startedAt")),
        endedAt = instant(body?.get("endedAt")),
        durationSeconds = seconds(body?.get("durationSeconds")),
        channel = channel(body?.get("channel")),
        direction = direction(body?.get("direction")),
        status = body?.get("status") as? String ?: "",
        turns = (body?.get("turns") as? List<*>).orEmpty().mapNotNull { turn(it) },
    )

    /** One message: a user/assistant role, a string text and a readable `at`; null otherwise. */
    fun turn(raw: Any?): GraineSessionTurn? {
        val o = raw as? Map<*, *> ?: return null
        val role = when (word(o["role"])) {
            "user" -> GraineRole.USER
            "assistant" -> GraineRole.AGENT
            else -> return null
        }
        val text = o["text"] as? String ?: return null
        val at = instant(o["at"]) ?: return null
        return GraineSessionTurn(role, text, at)
    }

    fun channel(raw: Any?): GraineSessionChannel = when (word(raw)) {
        "voice" -> GraineSessionChannel.VOICE
        "call" -> GraineSessionChannel.CALL
        else -> GraineSessionChannel.CHAT
    }

    fun direction(raw: Any?): GraineSessionDirection? = word(raw)?.let { w -> GraineSessionDirection.entries.firstOrNull { it.wire == w } }

    /** An ISO-8601 time with its zone; null for anything else — a number, "yesterday". */
    fun instant(raw: Any?): Instant? = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** Whole seconds: a finite number ≥ 0, rounded; null for anything else (a string, a boolean). */
    fun seconds(raw: Any?): Int? {
        val d = number(raw)?.takeIf { it >= 0 && it < Int.MAX_VALUE } ?: return null
        return kotlin.math.round(d).toInt()
    }

    /** A count: a whole number ≥ 0; 0 for anything else. */
    fun count(raw: Any?): Int {
        val d = number(raw)?.takeIf { it >= 0 && it % 1.0 == 0.0 && it <= Int.MAX_VALUE } ?: return 0
        return d.toInt()
    }

    private fun number(raw: Any?): Double? = when (raw) {
        is Long -> raw.toDouble()
        is Int -> raw.toDouble()
        is Double -> raw.takeIf { it.isFinite() }
        else -> null
    }

    /** A string with something in it, as sent. */
    private fun text(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotBlank() }

    private fun word(raw: Any?): String? = (raw as? String)?.trim()?.lowercase()
}

/** THE CHAT'S CLOSE DELAY ([GraineConfig.chatCloseDelaySeconds], [GraineChat.closeDelaySeconds]): seconds, kept within 0–300. */
internal object ChatCloseDelay {
    /** The default: 15 seconds after the last screen left the chat. */
    const val DEFAULT_SECONDS = 15
    /** The longest: 5 minutes, Graine's own idle limit. */
    const val MAX_SECONDS = 300

    /** [seconds] kept within 0–[MAX_SECONDS]: a negative value is 0. */
    fun clamp(seconds: Int): Int = seconds.coerceIn(0, MAX_SECONDS)
}
