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
