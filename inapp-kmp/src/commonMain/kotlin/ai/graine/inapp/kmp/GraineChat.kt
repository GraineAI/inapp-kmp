package ai.graine.inapp.kmp

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/** Where one of the user's messages is. */
enum class GraineDelivery {
    /** On its way: typed while the chat was not connected, sent as soon as it is. */
    SENDING,
    SENT,
    /** The chat could not reconnect to send it. [GraineChat.retry] tries again. */
    FAILED,
}

/** A component the agent drew — a product list, choices, time slots — as the conversation sent it. */
data class GraineComponent(
    val id: String,
    /** `product_list`, `plan_comparison`, `choices`, `quick_actions`, `calendar_slots`, `info_card` — or a newer one. */
    val kind: String?,
    val title: String?,
    /** The whole component as it arrived. */
    val payload: Map<String, Any?>,
) {
    /** What it shows — its items, options or slots, as the agent sent them (the payload's `data`); empty when it carries none. */
    val data: Map<String, Any?>
        get() = (payload["data"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    /** The words to show where it cannot be drawn (the payload's `fallback_text`); null when it has none. */
    val fallbackText: String? get() = (payload["fallback_text"] as? String)?.takeIf { it.isNotBlank() }
}

/** Where a message was said. */
enum class GraineChatChannel(
    /** The word on the wire. */
    val wire: String,
) {
    /** Typed, in the chat. */
    CHAT("chat"),
    /** Said on a call with the agent — only a restored turn is. A chat window draws a run of them as one "Voice call" row, never their words. */
    VOICE("voice"),
}

/** One message in the chat: the user's, or one reply of the agent's. */
data class GraineChatTurn(
    val id: String,
    val role: GraineRole,
    val text: String,
    /** When the message began. */
    val at: Instant,
    /** The user's messages only: where it is. Null for the agent's. */
    val delivery: GraineDelivery? = null,
    /** The agent's messages only: it spoke up without being asked (its turn did not follow one of the user's). */
    val proactive: Boolean = false,
    /** A component the agent drew with this message, when the platform SDK ties one to it. */
    val component: GraineComponent? = null,
    /** The agent's words are still arriving: draw them as provisional. */
    val live: Boolean = false,
    /**
     * From an earlier conversation, restored when the chat opened — the user
     * came back, and the agent picks up where it left off. Delivered, with the
     * time it was said, not said just now.
     */
    val restored: Boolean = false,
    /** Where it was said: typed in the chat, or on a call (a restored turn only). */
    val channel: GraineChatChannel = GraineChatChannel.CHAT,
    /**
     * The id of the session it was said in — a chat connection's run, or a
     * call's id for a voice turn, the id Execution Logs shows. A restored
     * turn keeps the one Graine kept; a turn said now carries the chat's
     * [GraineChat.sessionId] (a message typed before the chat connected, the
     * session of the connection that sends it). Null for turns kept before
     * sessions were recorded, and with a server that sends none. On iOS it
     * needs the GraineInApp of this version. New in 0.14.0.
     */
    val session: String? = null,
)

/**
 * ONE SESSION OF THE CHAT: a run of consecutive turns said in the same
 * session — a chat connection's run, or a call — for a "past conversations"
 * list or session dividers of your own ([GraineChat.sessions]). New in 0.14.0.
 */
data class GraineChatSession(
    /** The session's id, as Execution Logs shows it; null for turns kept before sessions were recorded, and with a server that sends none. */
    val id: String?,
    /** When its first turn was said. */
    val startedAt: Instant,
    /** When its last turn was said. */
    val endedAt: Instant,
    /** VOICE when every turn of it was said on a call, else CHAT. */
    val channel: GraineChatChannel,
    /** How many turns it holds. */
    val turns: Int,
    /** It is the chat's current session: its [id] is [GraineChat.sessionId]. Never for a session with no id. */
    val current: Boolean,
)

/**
 * THE CHAT'S SESSIONS, from its turns (history.json `sessions`): oldest first,
 * consecutive turns with the same session — null included — make one; its
 * start and end are its first and last turn's time; a call's when every turn
 * is voice; current when its id is not null and is [sessionId]. Pure.
 */
internal object ChatSessions {
    fun of(turns: List<GraineChatTurn>, sessionId: String?): List<GraineChatSession> {
        val out = ArrayList<GraineChatSession>()
        var i = 0
        while (i < turns.size) {
            val id = turns[i].session
            var j = i
            while (j + 1 < turns.size && turns[j + 1].session == id) j++
            val run = turns.subList(i, j + 1)
            out += GraineChatSession(
                id = id,
                startedAt = run.first().at,
                endedAt = run.last().at,
                channel = if (run.all { it.channel == GraineChatChannel.VOICE }) GraineChatChannel.VOICE else GraineChatChannel.CHAT,
                turns = run.size,
                current = id != null && id == sessionId,
            )
            i = j + 1
        }
        return out
    }
}

/**
 * How big the chat window opens: the dashboard's choice (Embed & Widgets →
 * Appearance → Chat window → Window size), the app's ([GraineConfig.chatSize]),
 * or one open's ([GraineChat.open]) — the open's wins, then the app's.
 */
enum class GraineChatSize(
    /** The design's word for it. */
    val wire: String,
) {
    /** The window above the voice bar, as it has always been. */
    COMPACT("compact"),
    /** A sheet over most of a phone; a panel in the corner of a tablet. */
    LARGE("large"),
    /** The whole screen. */
    FULL("full"),
}

/**
 * One reply the agent suggests for its latest turn — a chip in the SDK's chat
 * window: the words on the chip ([label]) and what a tap sends as the user's
 * own message ([text]).
 */
data class GraineQuickReply(val label: String, val text: String)

/**
 * The user's answer to "How was this chat?", asked once the agent ends the
 * conversation. [style] is `thumbs`, `stars` or `emoji`: thumbs are [value]
 * 1 (up) or 0 (down) of [scale] 1, stars and faces 1–5 of 5.
 */
data class GraineChatRating(val value: Int, val scale: Int, val style: String)

/**
 * WHAT THE CONVERSATION IS ABOUT — a card the app pins to the chat window
 * ([GraineChat.pin]): an item, an account, a request. Drawn as a bar under
 * the header or as a card atop the thread, as the design says; never sent to
 * the agent (describe the screen to it with [Graine.setScreen], as always).
 * Each line is one line: [title] at most 60 characters, [subtitle] 80,
 * [badge] 24.
 */
data class GraineChatPin(
    val title: String,
    val subtitle: String? = null,
    /** A picture for the thumbnail, filled and cropped; none draws the title's first letter. */
    val imageUrl: String? = null,
    /** A short word at the trailing end ("Active", "2 items"). */
    val badge: String? = null,
)

/**
 * A PIN AS THE CHAT WINDOW DRAWS IT — the native SDKs' rule, so what
 * [GraineChat.pin] hands over is what they keep: each line composed, control
 * and text-direction override characters as spaces, spaces collapsed,
 * trimmed, and cut to its length in characters (whole characters: a cut never
 * splits one); no title, no pin.
 */
internal object ChatPins {
    const val TITLE_MAX = 60
    const val SUBTITLE_MAX = 80
    const val BADGE_MAX = 24

    fun clean(pin: GraineChatPin?): GraineChatPin? {
        val title = line(pin?.title, TITLE_MAX)
        if (pin == null || title.isEmpty()) return null
        return GraineChatPin(
            title = title,
            subtitle = line(pin.subtitle, SUBTITLE_MAX).ifEmpty { null },
            imageUrl = pin.imageUrl?.trim()?.ifEmpty { null },
            badge = line(pin.badge, BADGE_MAX).ifEmpty { null },
        )
    }

    /** One line of words, at most [max] characters (code points). */
    fun line(v: String?, max: Int): String {
        if (v == null) return ""
        val sb = StringBuilder(v.length)
        var inSpace = false
        for (c in composed(v)) {
            // A control or a direction override is drawn as a space, never as what it does; a run of spaces is one.
            if (isControl(c) || isJsSpace(c)) {
                if (!inSpace) sb.append(' ')
                inSpace = true
            } else {
                sb.append(c)
                inSpace = false
            }
        }
        val s = sb.toString().trim(' ')
        var end = 0
        var count = 0
        while (end < s.length && count < max) {
            end += if (s[end].isHighSurrogate() && end + 1 < s.length && s[end + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return if (end < s.length) s.substring(0, end).trim(' ') else s
    }

    private fun isControl(c: Char): Boolean =
        c <= '\u001F' || c in '\u007F'..'\u009F' || c == '\u2028' || c == '\u2029' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

    /** JavaScript's `\s`: its whitespace and line terminators. */
    private fun isJsSpace(c: Char): Boolean =
        c == ' ' || c in '\t'..'\r' || c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' ||
            c == '\u202F' || c == '\u205F' || c == '\u3000' || c == '\uFEFF'
}

/** [text] composed (Unicode NFC), as the platform SDKs compose the words they draw. */
internal expect fun composed(text: String): String

/**
 * WHERE THE CHAT IS, in the words every Graine SDK uses (sdk/contract/
 * PUBLIC-STATE.md, "Chat"): [wire] spells each. First match wins: CONNECTED,
 * RECONNECTING, CONNECTING, DISCONNECTED, FAILED, OFFLINE, else IDLE.
 *
 * 0.14.5: CONNECTED is what ONLINE was (`GraineChatStatus.ONLINE` still
 * compiles, as an alias of CONNECTED); a chat that gave up says FAILED — what
 * [GraineChat.failed] said beside OFFLINE before — and OFFLINE now means what
 * every SDK means by it: a message waiting and nothing connecting it. Use an
 * `else` branch in a `when`, as more may come.
 */
enum class GraineChatStatus(
    /** The word every Graine SDK uses for it. */
    val wire: String,
) {
    /**
     * Not in use: never opened, left, or the app is in the background — or the
     * agent ended the conversation ([GraineChat.ended]) and its connection
     * closed; the user's next message starts over.
     */
    IDLE("idle"),
    CONNECTING("connecting"),
    /** Connected: a message goes now. (ONLINE before 0.14.5.) */
    CONNECTED("connected"),
    /** The connection dropped — or a try to open it failed — and is being made again. */
    RECONNECTING("reconnecting"),
    /**
     * A message of the user's waiting to go, and nothing connecting it — the
     * app in the background, a native call holding the chat: it goes once the
     * chat connects. (Before 0.14.5 this said what FAILED says.)
     */
    OFFLINE("offline"),
    /**
     * The chat gave up: every retry was spent, the server closed the
     * conversation, or Graine refused it. A message, [GraineChat.retry] or
     * [GraineChat.open] connects again. Never for a conversation the agent
     * ended — that is IDLE, with [GraineChat.ended] true. New in 0.14.5.
     */
    FAILED("failed"),
    /**
     * Graine ended the conversation itself — idle, or at the session's time
     * limit — and it does not reconnect on its own: the lines stay, the SDK's
     * chat says "Disconnected", and opening the chat or a message connects
     * again (continued when Graine can continue it). On iOS it needs the
     * GraineInApp of this version (an older one says FAILED).
     */
    DISCONNECTED("disconnected"),
    ;

    companion object {
        /** CONNECTED, by its name before 0.14.5. */
        @Deprecated(
            "The chat's status says CONNECTED, the word every Graine SDK uses.",
            ReplaceWith("GraineChatStatus.CONNECTED", "ai.graine.inapp.kmp.GraineChatStatus"),
        )
        val ONLINE: GraineChatStatus get() = CONNECTED

        /** The status for a word of [wire]'s, any case; null for a word it does not know. */
        fun of(wire: String?): GraineChatStatus? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
    }
}

/**
 * THE CHAT — a typed conversation with an agent: `Graine.chat` (the default
 * agent's), `Graine.agent("support")?.chat` (another's).
 *
 *     Graine.chat.open()                    // the user is in the chat
 *     Graine.chat.send("Where is my order?")
 *     Graine.chat.turns.collect { … }       // one turn per message, the agent's streaming in
 *     Graine.chat.close()                   // they left
 *
 * The agent sees the screen you describe, runs your actions and draws its
 * components, as on a call. A message typed while the chat is not connected
 * is shown at once and sent when it is; a dropped connection is made again on
 * its own. One conversation at a time: opening the chat ends a voice-bar call,
 * and a native call holds the chat until it is over. The SDK's own chat screen
 * is `GraineChat()` in `ai.graine:inapp-kmp-compose`.
 *
 * Where the agent remembers its users, a signed-in user who comes back finds
 * the conversation where they left it: the chat opens with the earlier
 * messages (`restored`, oldest first, an earlier call's as `channel` VOICE)
 * and the agent continues from them.
 * [newConversation] starts a fresh thread. An app drawing its own chat lays
 * the turns out with [GraineChatRows].
 *
 * The agent may suggest replies for its turn ([quickReplies], sent with
 * [sendQuickReply]) and end the conversation ([ended]), after which the chat
 * window asks how it went where the design does ([rating], [onRated],
 * [rate]). The app can pin what the conversation is about ([pin]) and take
 * attachments ([onAttach]).
 *
 * The user's earlier sessions with the agent, and each one's chat history,
 * are [pastSessions] and [sessionTranscript]. Once no screen shows the chat,
 * it closes [closeDelaySeconds] later (15 by default).
 */
class GraineChat internal constructor(private val agent: GraineAgent) {
    /** What has been said, oldest first. */
    val turns: StateFlow<List<GraineChatTurn>> = agent.chatTurns.asStateFlow()

    /**
     * Where the chat is, in the words every Graine SDK uses ([GraineChatStatus]):
     * IDLE, CONNECTING, CONNECTED, RECONNECTING, OFFLINE (a message waiting,
     * nothing connecting it), FAILED (it gave up — a message, [retry] or [open]
     * tries again) or DISCONNECTED (Graine ended the conversation). 0.14.5: a
     * chat that gave up says FAILED, where it said OFFLINE.
     */
    val status: StateFlow<GraineChatStatus> = agent.chatStatus.asStateFlow()

    /**
     * THE CHAT GAVE UP: every retry was spent and it will not connect again
     * by itself — where the SDK's chat window offers a tap to try again. True
     * until the user sends a message or tries again ([retry], [open], [send]),
     * when it connects again. Never for a conversation the agent ended
     * ([ended]) or Graine closed ([GraineChatStatus.DISCONNECTED]). New in
     * 0.14.1; from 0.14.5 [status] says it too (FAILED), and this stays for
     * code that reads it.
     */
    @Deprecated("The chat's status says FAILED.", ReplaceWith("status.value == GraineChatStatus.FAILED"))
    val failed: StateFlow<Boolean> = agent.chatFailed.asStateFlow()

    /** The agent is working on a reply: from the user's message until its first words. */
    val typing: StateFlow<Boolean> = agent.chatTyping.asStateFlow()

    /**
     * This user's calls still to come, soonest first, at most three — what the
     * SDK's chat window shows on its Upcoming calls card. Fetched when the chat
     * opens and after the agent finishes a reply (it may have just booked
     * one), only where Graine rings this app; empty otherwise.
     */
    val upcomingCalls: StateFlow<List<GraineScheduledCall>> = agent.chatUpcoming.asStateFlow()

    /**
     * The replies the agent suggests for its latest turn — chips in the SDK's
     * chat window; read them to draw your own, and send one with
     * [sendQuickReply]. Empty once the user sends anything, the agent starts
     * another turn, an earlier conversation is restored, or the conversation
     * ends. On iOS they need the GraineInApp of this version (an older one
     * offers none).
     */
    val quickReplies: StateFlow<List<GraineQuickReply>> = agent.chatQuickReplies.asStateFlow()

    /**
     * The agent ended the conversation: the chat window asks for a rating
     * where the design does. The user sending anything starts over.
     */
    val ended: StateFlow<Boolean> = agent.chatEnded.asStateFlow()

    /** The user's answer to "How was this chat?", once given — one per conversation; null before. */
    val rating: StateFlow<GraineChatRating?> = agent.chatRating.asStateFlow()

    /** The card the app pinned ([pin]), as the chat window has it; null when nothing is pinned. */
    val pinned: StateFlow<GraineChatPin?> = agent.chatPinned.asStateFlow()

    /**
     * Where Graine began a new conversation under earlier lines — the ids of
     * the turns a "Disconnected · new conversation" row goes before, oldest
     * first; empty when there are none. The SDK's chat draws the row; a chat
     * of your own gets it from [GraineChatRows.build]. Never turns: [turns]
     * does not change for them, and [newConversation] or [clear] takes them
     * away with the lines. On iOS it needs the GraineInApp of this version
     * (an older one reports none).
     */
    val newConversationBefore: StateFlow<List<String>> = agent.chatNewConversationBefore.asStateFlow()

    /**
     * The id of the chat's current session — this connection's run, the id
     * Execution Logs shows for it — from Graine as the connection starts. Null
     * until it arrives (each new connection starts without one), and with a
     * server that sends none. A turn said now carries it
     * ([GraineChatTurn.session]). On iOS it needs the GraineInApp of this
     * version (an older one says null). New in 0.14.0.
     */
    val sessionId: StateFlow<String?> = agent.chatSessionId.asStateFlow()

    /**
     * The sessions the conversation on screen holds, oldest first: each run of
     * consecutive [turns] said in the same session ([GraineChatSession]) — a
     * chat connection's, or a call's — the current one marked. Turns kept
     * before sessions were recorded make one with no id; with a server that
     * sends none, the whole conversation is one session with no id. For a
     * "past conversations" list, or session dividers of your own. Updates with
     * [turns] and [sessionId]. New in 0.14.0.
     */
    val sessions: StateFlow<List<GraineChatSession>> = agent.chatSessions.asStateFlow()

    /**
     * THE USER'S PAST SESSIONS with this agent — their earlier chats, talks
     * and calls, newest first, as Execution History lists them
     * ([GrainePastSessions]: `sessions`, `loading`, `hasMore`, `error`). Empty
     * until [refreshPastSessions]; [loadMorePastSessions] appends the next
     * page, and [sessionTranscript] answers one session's chat history. Only
     * for the user `identify` names, from a device bound to them — `error`
     * says why not (`identity_required`, `device_limit`, `rate_limited`,
     * `unavailable`, `network`) — and cleared when the identity changes
     * (another user, or nobody). The session open now is never in it. On iOS
     * it needs the GraineInApp of this version (an older one keeps it empty).
     * New in 0.14.3.
     */
    val pastSessions: StateFlow<GrainePastSessions> = agent.chatPastSessions.asStateFlow()

    /**
     * How long the conversation stays connected once no screen shows it, in
     * seconds: it closes this long after the last screen left it — or after
     * the last message either way, whichever is later — unless a screen shows
     * it again first ([shown]). Starts as [GraineConfig.chatCloseDelaySeconds]
     * (15 by default); 0 closes it as soon as the last screen leaves. Kept
     * within 0–300 (a negative value is 0). Set it any time after
     * [Graine.configure]: a close already waiting takes the new delay. On iOS
     * it needs the GraineInApp of this version. New in 0.14.3.
     */
    var closeDelaySeconds: Int
        get() = agent.engineOrNull()?.chatCloseDelaySeconds ?: ChatCloseDelay.DEFAULT_SECONDS
        set(value) { agent.engine().chatCloseDelaySeconds = ChatCloseDelay.clamp(value) }

    /**
     * The user rated the conversation — in the chat window's rating card, or
     * through [rate]. On the main thread, once for each rating. The answer is
     * also recorded on the conversation; [rating] keeps it.
     */
    var onRated: ((GraineChatRating) -> Unit)?
        get() = agent.ratedHandler
        set(value) { agent.ratedHandler = value }

    /**
     * The app takes attachments: the chat window draws its paperclip (where
     * the design asks for one) and a tap calls this, on the main thread. The
     * SDK sends nothing itself — pick and upload the file, then tell the agent
     * about it as you would about any screen. Null (the default): no
     * paperclip. May be set before [Graine.configure].
     */
    var onAttach: (() -> Unit)?
        get() = agent.attachHandler
        set(value) = agent.setAttachHandler(value)

    /**
     * PIN WHAT THE CONVERSATION IS ABOUT — a card the chat window draws under
     * its header or atop the thread, as the design says (nothing where the
     * design hides it). `pin(null)` takes it away. Never sent to the agent.
     * Each line is cleaned to one line and cut to its length; a pin without a
     * title is no pin. [pinned] has it once the chat window does. On iOS it
     * needs the GraineInApp of this version.
     */
    fun pin(pin: GraineChatPin?) = agent.engine().chatPin(ChatPins.clean(pin))

    /**
     * The user's answer to "How was this chat?", for an app drawing its own
     * rating: [style] `thumbs` ([value] 1 up, 0 down), `stars` or `emoji`
     * (1–5); another word is thumbs. Taken once per conversation, and only
     * after the agent ended it ([ended]); recorded on the conversation, then
     * in [rating] and [onRated]. On iOS it needs the GraineInApp of this version.
     */
    fun rate(style: String, value: Int) = agent.engine().chatRate(style, value)

    /** One of [quickReplies], sent as the user's own message — a real turn, exactly as if typed. */
    fun sendQuickReply(reply: GraineQuickReply) {
        if (reply.text.isBlank()) return
        agent.engine().chatSendQuickReply(reply)
    }

    /**
     * The user is in the chat: connect, and stay connected. Ends a call the
     * voice bar holds. A voice bar on screen moves to the chat and draws it at
     * [size] — this open's own, until [close]; null follows
     * [GraineConfig.chatSize], then the dashboard's Window size.
     */
    fun open(size: GraineChatSize? = null) = agent.engine().chatOpen(size)

    /** The user left: the chat hangs up and stays down. What was said stays. */
    fun close() = agent.engine().chatClose()

    /**
     * A chat screen of your own came on screen (`true`) or left it (`false`)
     * — say so in pairs, so the conversation lives as the SDK's own chat's
     * does: connected while a screen shows it, and with none, closed
     * [closeDelaySeconds] later (15 by default; a message either way in that
     * time starts the wait again) — a screen showing it again within that
     * time continues it on the same connection; opened later, it connects
     * again, and a signed-in user's conversation continues from Graine's
     * history, with no new welcome. The SDK's `GraineChat()` says so itself.
     * A chat nobody reports stays connected until Graine's own idle limit.
     * It closed after 5 idle minutes in 0.14.0–0.14.1. On iOS it needs the
     * GraineInApp of this version (an older one connects on [open] as before).
     */
    fun shown(visible: Boolean) = agent.engine().chatShown(visible)

    /** Send a message. Shown at once; sent now, or as soon as the chat is connected. Blank messages are ignored. */
    fun send(text: String) {
        if (text.isBlank()) return
        agent.engine().chatSend(text)
    }

    /** Connect now, and send what could not be sent. */
    fun retry() = agent.engine().chatRetry()

    /**
     * Empty the chat on screen — a new topic, a tidy screen. Only the screen:
     * the conversation goes on, a message still waiting for the connection is
     * still sent, and nothing Graine keeps — the conversation that continues,
     * what the agent remembers — is touched. [newConversation] starts over.
     * On iOS it needs the GraineInApp of this version (an older one ignores it).
     */
    fun clear() = agent.engine().chatClear()

    /**
     * Answer a component the agent drew ([GraineEvent.Component]) from a UI of
     * your own, on the conversation that is live — the chat, else the voice
     * bar's call. [data] is the structured answer, for your webhook; [summary]
     * the sentence that is the user's turn ("The Creta, please"), without which
     * the conversation does not move. The SDK's own views answer for
     * themselves. [GraineAgent.component] lets the component go (from 0.14.0).
     * On iOS it needs the GraineInApp of this version.
     */
    fun answerWidget(id: String, data: Map<String, Any?>, summary: String) {
        agent.engine().answerWidget(id, data, summary)
        agent.componentDone(id)
    }

    /**
     * Close a component the agent drew without answering it, from a UI of
     * your own — what the close of the SDK's component view does: the agent
     * is told quietly, so it does not go on about options nobody can see, and
     * [GraineAgent.component] lets it go. On iOS it needs the GraineInApp of
     * this version (with an older one only the state lets it go).
     */
    fun dismissWidget(id: String) {
        if (id.isBlank()) return
        agent.engine().dismissWidget(id)
        agent.componentDone(id)
    }

    /** Fetch [upcomingCalls] again — after booking one elsewhere (the chat refreshes them itself otherwise). */
    fun refreshUpcomingCalls() = agent.engine().chatRefreshUpcoming()

    /** Cancel one of [upcomingCalls]: it leaves the list once Graine has called it off. */
    fun cancelUpcomingCall(id: String) {
        if (id.isBlank()) return
        agent.engine().chatCancelUpcoming(id)
    }

    /**
     * START A NEW CONVERSATION — a fresh thread, as a support chat offers one.
     *
     * The chat hangs up, Graine lets go of the conversation it kept for this
     * agent and user (so the next chat does not continue it), the messages on
     * screen and any waiting to go are cleared, and — when the user was in the
     * chat — a fresh chat opens, with the welcome and the starters. What the
     * agent remembers about the user is untouched. With nobody signed in
     * nothing was kept, so only the screen clears. The SDK's chat window offers
     * it in its header while it shows an earlier conversation.
     *
     * Throws [GraineException] when Graine refused (`device_limit`,
     * `network`…); the chat is then as it was.
     */
    suspend fun newConversation() = agent.newConversation()

    /**
     * Load the first page of [pastSessions] — the user's most recent sessions
     * with this agent — replacing what was loaded. While it loads, `loading`
     * is true and what was loaded stays; a refusal keeps it too, with `error`
     * saying why. With nobody signed in nothing is asked: `error` is
     * `identity_required` at once. New in 0.14.3.
     */
    fun refreshPastSessions() = agent.engine().chatRefreshPastSessions()

    /**
     * Load the next page of [pastSessions] and append it — a session already
     * listed is not listed twice. Nothing happens while a page is loading,
     * and when there is no next page (`hasMore` false, or nothing loaded yet:
     * [refreshPastSessions] loads the first). After a refusal, calling it
     * again asks for the same page. New in 0.14.3.
     */
    fun loadMorePastSessions() = agent.engine().chatLoadMorePastSessions()

    /**
     * ONE PAST SESSION'S CHAT HISTORY, oldest first — the session as
     * [pastSessions] describes it, and every message said in it. Only the
     * signed-in user's own. Throws [GraineException]: `invalid_request` for a
     * blank id and `identity_required` with nobody signed in (nothing is
     * sent); `not_found` for a session Graine does not know or that is
     * another user's — and for an answer that arrives after the identity
     * changed; `device_limit`, `rate_limited`, `unavailable`, `network`. On
     * iOS it needs the GraineInApp of this version (an older one throws
     * `unavailable`). New in 0.14.3.
     */
    suspend fun sessionTranscript(id: String): GraineSessionTranscript = agent.sessionTranscript(id)
}

/** The voice bar's call, as a screen draws it. */
data class GraineVoiceInfo(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val muted: Boolean = false,
    /**
     * The captions switch — CC: the SDK's bar shows the line being said only
     * while it is on. On by default (a caption nobody asked to hide is an
     * accessibility default). [GraineVoice.setCaptionsOn] moves it.
     */
    val captionsOn: Boolean = true,
    /**
     * What has been said this call, oldest first — the last 12 turns, a live
     * one replaced as its words arrive; a new call starts empty. Kept whether
     * the captions are on or off; [GraineVoice.clearCaptions] forgets them.
     */
    val captions: List<GraineCaption> = emptyList(),
    /**
     * Where the call is, in one word — what the SDK's bar says
     * ([GraineVoiceStatus]): `IDLE` before any call, then CONNECTING,
     * LISTENING, THINKING, SPEAKING as the conversation moves, MUTED,
     * RECONNECTING, FAILED, and ENDED once a call has ended. Updates with
     * every change of the call. New in 0.14.0.
     */
    val status: GraineVoiceStatus = GraineVoiceStatus.IDLE,
    /**
     * Why the call could not work, as a sentence, while [status] is FAILED —
     * a setting someone must fix ("This agent is not set up for calls."), or
     * "The call could not be connected." when nothing could carry it (then
     * [GraineVoice.start] tries again); null otherwise. New in 0.14.0.
     */
    val error: String? = null,
    /**
     * The agent's voice level as it is heard, 0–1 — for a meter or a
     * visualiser of your own. Updates about five times a second while the call
     * is live, and is 0 otherwise. On iOS it needs the GraineInApp of 0.14.0
     * or later; on Android, 0.14.1 or later. New in 0.14.0.
     */
    val agentLevel: Float = 0f,
    /**
     * The user's microphone level, 0–1 — for a "you are being heard" meter.
     * Updates about five times a second while the call is live; 0 while muted
     * and with no call. On iOS it needs the GraineInApp of 0.14.0 or later; on
     * Android, 0.14.1 or later. New in 0.14.0.
     */
    val micLevel: Float = 0f,
    /**
     * What the call runs on: [GraineTransport.WEBRTC] or
     * [GraineTransport.WEBSOCKET], from the moment the call is placed; null
     * before any call, and kept after a call ends until the next starts.
     * New in 0.14.0.
     */
    val transport: GraineTransport? = null,
    /**
     * Why a call that asked for WebRTC runs on the WebSocket instead —
     * `mint_failed`, `no_answer`, `ice_timeout` and the like, for your logs;
     * the user hears no difference. Null when it did not fall back. New in 0.14.0.
     */
    val transportFallbackReason: String? = null,
)

/**
 * WHERE THE VOICE BAR'S CALL IS, in one word — what the SDK's bar says, first
 * match wins: FAILED ("Unavailable", or "Couldn't connect · tap to retry":
 * [GraineVoiceInfo.error] says why), RECONNECTING, CONNECTING, MUTED,
 * THINKING (the user finished a sentence and the agent has not answered yet),
 * SPEAKING, LISTENING, then ENDED (a call ended and no other has started) or
 * IDLE.
 */
enum class GraineVoiceStatus(
    /** The word every Graine SDK uses: `idle`, `connecting`, `listening`, `thinking`, `speaking`, `reconnecting`, `muted`, `failed`, `ended`. */
    val wire: String,
) {
    /** No call. Show "Tap to talk". */
    IDLE("idle"),
    /** The call is being placed. Show "Connecting…". */
    CONNECTING("connecting"),
    /** On the call, waiting for the user to speak. Show "Listening". */
    LISTENING("listening"),
    /** The user finished a sentence and the agent has not answered yet. Show "Thinking…". */
    THINKING("thinking"),
    /** The agent is talking. Show "Speaking". */
    SPEAKING("speaking"),
    /** The network interrupted the call and it is being made again. Show "Reconnecting…". */
    RECONNECTING("reconnecting"),
    /** On the call with the microphone off. Show "Muted". */
    MUTED("muted"),
    /** The call cannot work: [GraineVoiceInfo.error] says why. Show it, and offer to try again. */
    FAILED("failed"),
    /** A call ended and no other has started. */
    ENDED("ended");

    companion object {
        /** The status a word names, in any case and with spaces around it; null for any other word. */
        fun of(word: String?): GraineVoiceStatus? = entries.firstOrNull { it.wire == word?.trim()?.lowercase() }
    }
}

/**
 * THE VOICE BAR'S CALL — the agent talking inside the app, beside the screen it
 * is talking about. `Graine.voice`. The bar itself is `GraineAgentBar()` in
 * `ai.graine:inapp-kmp-compose`, which places and ends the call by itself; these
 * are for an app that draws its own controls.
 */
class GraineVoice internal constructor(private val agent: GraineAgent) {
    val state: StateFlow<GraineVoiceInfo> = agent.voiceInfo.asStateFlow()

    /** Place the call. Mounts the engine first if nothing has (on Android, in the activity in front). */
    fun start() = agent.engine().voiceStart()

    /** Hang up. */
    fun end() = agent.engine().voiceEnd()

    /** Stop sending the user's voice without ending the call. */
    fun setMuted(muted: Boolean) = agent.engine().voiceSetMuted(muted)

    /** Turn the captions on or off ([GraineVoiceInfo.captionsOn]) — for your own CC button. The call goes on. */
    fun setCaptionsOn(on: Boolean) = agent.engine().voiceSetCaptionsOn(on)

    /** Forget what has been said ([GraineVoiceInfo.captions]) without ending the call. */
    fun clearCaptions() = agent.engine().voiceClearCaptions()
}
