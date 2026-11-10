package ai.graine.inapp.kmp

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which call the face stage shows: none, the voice bar's, or a native call ([Graine.calls]). */
enum class GraineStageCall(val wire: String) {
    NONE("none"),
    VOICE("voice"),
    NATIVE("native");

    internal companion object {
        fun of(word: Any?): GraineStageCall = entries.firstOrNull { it.wire == word } ?: NONE
    }
}

/**
 * Where the call on the stage is. [FAILED]: the voice bar's call could not
 * start or go on; a native call that fails ends.
 */
enum class GraineStagePhase(val wire: String) {
    IDLE("idle"),
    RINGING("ringing"),
    CONNECTING("connecting"),
    LIVE("live"),
    RECONNECTING("reconnecting"),
    ENDED("ended"),
    FAILED("failed");

    internal companion object {
        fun of(word: Any?): GraineStagePhase = entries.firstOrNull { it.wire == word } ?: IDLE
    }
}

/** The stage's buttons. [CALL] places a call, [ANSWER] takes a ringing one, [END] hangs up, cancels or declines. */
enum class GraineStageControl(val wire: String) {
    CALL("call"),
    ANSWER("answer"),
    MUTE("mute"),
    CHAT("chat"),
    END("end"),
    SPEAKER("speaker");

    internal companion object {
        fun of(word: Any?): GraineStageControl? = entries.firstOrNull { it.wire == word }
    }
}

/** What the face stage's Call button places: the voice bar's call (the default), or a native call. */
enum class GraineStageCalls(val wire: String) {
    VOICE("voice"),
    NATIVE("native"),
}

/** The line being said, as the stage shows it. [final]: settled — draw a line still arriving a little faint. */
data class GraineStageCaption(val who: GraineRole, val text: String, val final: Boolean)

/**
 * EVERYTHING THE FACE STAGE READS ([GraineStage.state]) — for a screen of your
 * own over the agent's face ([GraineAvatarFace] in `ai.graine:inapp-kmp-compose`),
 * drawn from the same state the SDK's `GraineAvatarStage` draws
 * (contract/PUBLIC-STATE.md "The face stage").
 */
data class GraineStageState(
    /** Which call the stage shows. */
    val call: GraineStageCall = GraineStageCall.NONE,
    val phase: GraineStagePhase = GraineStagePhase.IDLE,
    /** The words beside the name: "Incoming call", "Connecting…", "Reconnecting…", "Call ended", "Couldn’t connect"; empty while live and idle. */
    val status: String = "",
    /** Draw the LIVE mark and the clock. */
    val live: Boolean = false,
    /** When the call went live (ms since 1970), while it is live or coming back; null otherwise. */
    val startedAt: Long? = null,
    /** The line being said, while the call is on — never an empty one. */
    val caption: GraineStageCaption? = null,
    val muted: Boolean = false,
    /** The call's audio is on the loudspeaker. */
    val speaker: Boolean = true,
    /** The buttons, left to right. */
    val controls: List<GraineStageControl> = listOf(GraineStageControl.CALL),
    /** The buttons that can act now (the microphone waits for the call to be on). */
    val enabled: List<GraineStageControl> = listOf(GraineStageControl.CALL),
    /** What End is called: "Cancel" while a call is being placed, "Decline" while one rings, else "End". */
    val endLabel: String = "End",
    /** The agent's face ([GraineAvatar.state]). */
    val face: GraineAvatarState = GraineAvatarState.OFF,
    /** The header's name: yours, else the dashboard chat header's title, else the voice bar's name. */
    val name: String = "Assistant",
    /** The header's second line, or null. */
    val role: String? = null,
    /** The id of the component on show, or null. */
    val componentId: String? = null,
    /** A component is on its way. */
    val componentPending: Boolean = false,
) {
    /** Whether [control] is drawn. */
    fun shows(control: GraineStageControl): Boolean = control in controls

    /** Whether [control] can act now. */
    fun canUse(control: GraineStageControl): Boolean = control in enabled

    /** The clock's words at [nowMs] (ms since 1970); "" when the call is not live or coming back. */
    fun clock(nowMs: Long): String = startedAt?.let { GraineStage.clock(nowMs - it) } ?: ""

    internal companion object {
        val IDLE = GraineStageState()

        /** The platform SDK's `{"call","phase",…}` (contract/PUBLIC-STATE.md 9); anything unreadable is [IDLE]. */
        fun fromJson(json: String?): GraineStageState {
            val o = Json.obj(json) ?: return IDLE
            fun words(key: String) = (o[key] as? List<*>).orEmpty().mapNotNull { GraineStageControl.of(it) }
            fun long(v: Any?): Long? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.toLong()
            val caption = (o["caption"] as? Map<*, *>)?.let { c ->
                val text = c["text"] as? String
                if (text.isNullOrBlank()) null
                else GraineStageCaption(if (c["who"] == "agent") GraineRole.AGENT else GraineRole.USER, text, c["final"] as? Boolean ?: true)
            }
            val face = (o["face"] as? Map<*, *>)?.let { f -> GraineAvatarState.fromJson(Json.write(f)) } ?: GraineAvatarState.OFF
            return GraineStageState(
                call = GraineStageCall.of(o["call"]),
                phase = GraineStagePhase.of(o["phase"]),
                status = o["status"] as? String ?: "",
                live = o["live"] as? Boolean ?: false,
                startedAt = long(o["startedAt"]),
                caption = caption,
                muted = o["muted"] as? Boolean ?: false,
                speaker = o["speaker"] as? Boolean ?: true,
                controls = words("controls").ifEmpty { listOf(GraineStageControl.CALL) },
                enabled = words("enabled"),
                endLabel = (o["endLabel"] as? String)?.takeIf { it in listOf("End", "Cancel", "Decline") } ?: "End",
                face = face,
                name = (o["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Assistant",
                role = (o["role"] as? String)?.takeIf { it.isNotBlank() },
                componentId = (o["componentId"] as? String)?.takeIf { it.isNotEmpty() },
                componentPending = o["componentPending"] as? Boolean ?: false,
            )
        }
    }
}

/**
 * THE FACE STAGE, AS STATE — everything `GraineAvatarStage` reads and every
 * action its buttons perform, for a screen of your own over the agent's face.
 * [state] is decided by the platform SDK from what it already publishes: a
 * native call while one rings, is being placed or is on, else the default
 * agent's voice bar call, with the face, the component and the header's words.
 *
 *     val stage by Graine.stage.state.collectAsState()
 *     Box(Modifier.fillMaxSize()) {
 *         GraineAvatarFace(Modifier.fillMaxSize())        // the face, under everything you draw
 *         if (stage.live) Text("LIVE ${stage.clock(nowMs)}")
 *         stage.caption?.let { Text(it.text) }
 *         Button(onClick = { Graine.stage.end() }) { Text(stage.endLabel) }
 *     }
 *
 * The face draws inside `GraineAvatarFace`, so anything above it is drawn over the face.
 */
class GraineStage internal constructor() {
    /** What the stage shows now. */
    val state: StateFlow<GraineStageState> = Graine.stageState.asStateFlow()

    /** The header's name; null: the dashboard chat header's title, else the voice bar's name. */
    var name: String?
        get() = Graine.stageName
        set(value) {
            Graine.stageName = value
            Graine.stageEngine()?.stageSetWords(value, Graine.stageRole)
        }

    /** The header's second line ("AI care assistant"); null: the dashboard chat header's subtitle, else none. */
    var role: String?
        get() = Graine.stageRole
        set(value) {
            Graine.stageRole = value
            Graine.stageEngine()?.stageSetWords(Graine.stageName, value)
        }

    /** What [call] places: the voice bar's call, or a native call. */
    var calls: GraineStageCalls
        get() = Graine.stageCalls
        set(value) {
            Graine.stageCalls = value
            Graine.stageEngine()?.stageSetCalls(value.wire)
        }

    /** Your chat: with one, the controls include [GraineStageControl.CHAT] and [openChat] calls it. */
    var onChat: (() -> Unit)?
        get() = Graine.stageOnChat
        set(value) {
            Graine.stageOnChat = value
            Graine.stageEngine()?.stageSetChat(value)
        }

    /** Place the call the stage is for: the voice bar's (the engine is mounted first if nothing has), or a native call. */
    fun call() { Graine.stageEngine()?.stageCall() }

    /** Take the native call that is ringing. */
    fun answer() { Graine.stageEngine()?.stageAnswer() }

    /** Hang up, cancel a call being placed, or decline a ring — whichever the stage shows. */
    fun end() { Graine.stageEngine()?.stageEnd() }

    /** The microphone of the call the stage shows. */
    fun setMuted(muted: Boolean) { Graine.stageEngine()?.stageSetMuted(muted) }

    /** The loudspeaker (true) or the earpiece of the call the stage shows — with no call, of the next one. */
    fun setSpeaker(on: Boolean) { Graine.stageEngine()?.stageSetSpeaker(on) }

    /** The Chat button: calls [onChat]. */
    fun openChat() { Graine.stageOnChat?.invoke() }

    companion object {
        /** The clock's words for [elapsedMs]: m:ss under an hour, h:mm:ss after; below zero reads as zero. */
        fun clock(elapsedMs: Long): String {
            val s = (if (elapsedMs < 0) 0 else elapsedMs) / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            fun two(n: Long) = if (n < 10) "0$n" else "$n"
            return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
        }
    }
}
