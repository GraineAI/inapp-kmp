package ai.graine.inapp.kmp

/**
 * Everything the SDK reports that is not a state: `Graine.events`. Not
 * replayed — collect it from app start for what you log or count. The states
 * themselves are flows that always hold the latest value: `Graine.session`,
 * `Graine.chat.turns`, `Graine.calls.state`, `Graine.voice.state`.
 */
sealed class GraineEvent {
    /** The agent's configuration arrived (also in `Graine.session`). */
    data class SessionReady(val session: GraineSession) : GraineEvent()

    /** A line of the voice bar's call: the agent's as it is spoken, the user's as it is heard. */
    data class Caption(val role: GraineRole, val text: String, val live: Boolean) : GraineEvent()

    /** The voice bar's call is over. */
    data object VoiceEnded : GraineEvent()

    /** The agent drew a component, in the chat or on a call. The SDK's views draw it; this is for an app that draws its own. */
    data class Component(val component: GraineComponent) : GraineEvent()

    /** A component is on its way; its content is loading. */
    data class ComponentPending(val hint: Map<String, Any?>) : GraineEvent()

    /** The agent finished a message in the chat. `turn.proactive`: it spoke up unasked — badge your chat if it is closed. */
    data class ChatMessage(val turn: GraineChatTurn) : GraineEvent()

    /** The agent asked for one of your actions. */
    data class ActionRequested(val name: String) : GraineEvent()

    /** What one of your actions answered the agent — the handler's result, or the SDK's when it was late or unknown. */
    data class ActionCompleted(val name: String, val result: GraineActionResult) : GraineEvent()

    /**
     * The user looks stuck (`stalled_on_step`): on one screen, with a field
     * outstanding, through a stretch of a live conversation. The agent has
     * already been told; this is for the app — badge a control, count it.
     */
    data class Friction(val name: String, val screen: String?, val detail: String) : GraineEvent()

    /** The microphone was refused, so the agent cannot hear the user. */
    data class MicrophoneDenied(val reason: String) : GraineEvent()

    /** Loud for you, invisible to the user: a misconfigured agent is never the user's problem. */
    data class Error(val message: String) : GraineEvent()
}

/**
 * AN APP EVENT AS IT IS SENT, and the most it may weigh (PUBLIC-STATE.md §8):
 * `{name, detail?, cooldown_seconds?, max_per_session?, quiet?, data?}` — the
 * pacing keys at the event's own level, everything else of the app's under
 * `data` (a `data` map of its own first), as the platform SDKs build it — and
 * its size as JSON, in UTF-8 bytes.
 */
internal object AppEvents {
    /** 8 KB: Graine closes a conversation whose frame passes 32 KB. */
    const val MAX_BYTES = 8_192

    private val PACING = setOf("cooldown_seconds", "max_per_session", "quiet")

    fun event(name: String, detail: String?, data: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["name"] = name
        if (!detail.isNullOrEmpty()) out["detail"] = detail
        val rest = LinkedHashMap<String, Any?>()
        for (key in data.keys.sorted()) {
            when {
                key in PACING -> out[key] = data[key]
                key != "data" -> rest[key] = data[key]
            }
        }
        val inner = LinkedHashMap<String, Any?>()
        (data["data"] as? Map<*, *>)?.forEach { (k, v) -> inner[k.toString()] = v }
        for ((k, v) in rest) if (k !in inner) inner[k] = v
        if (inner.isNotEmpty()) out["data"] = inner
        return out
    }

    /** The event as JSON, in UTF-8 bytes. */
    fun bytes(name: String, detail: String?, data: Map<String, Any?>): Int =
        Json.write(event(name, detail, data)).encodeToByteArray().size

    /** What an event over the limit says. */
    fun tooLarge(name: String, bytes: Int): String =
        "reportEvent(\"${name.take(64)}\") was not sent: the event is $bytes bytes as JSON, over the limit of $MAX_BYTES (8 KB). " +
            "Send less in its data — an id the agent can look up, not the record."
}
