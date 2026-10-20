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
)

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
)

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

/** Where the chat's connection is. */
enum class GraineChatStatus {
    /** Not in the chat: never opened, left, or the app is in the background. */
    IDLE,
    CONNECTING,
    ONLINE,
    /** The connection dropped and is being made again. */
    RECONNECTING,
    /** It could not reconnect, or the other side ended the conversation. A message, or [GraineChat.retry], connects again. */
    OFFLINE,
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
 */
class GraineChat internal constructor(private val agent: GraineAgent) {
    /** What has been said, oldest first. */
    val turns: StateFlow<List<GraineChatTurn>> = agent.chatTurns.asStateFlow()

    val status: StateFlow<GraineChatStatus> = agent.chatStatus.asStateFlow()

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
     * themselves. On iOS it needs the GraineInApp of this version.
     */
    fun answerWidget(id: String, data: Map<String, Any?>, summary: String) = agent.engine().answerWidget(id, data, summary)

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
)

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
