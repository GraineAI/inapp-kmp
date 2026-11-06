package ai.graine.inapp.kmp

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * THE AGENT'S FACE (avatar), as the app sees it — [GraineAvatar.state].
 *
 * [Phase.OFF] — no face on this call (or no call). [reason] says why when a
 * call's agent has a face that is not shown: `busy`, `error`, `off` (the agent
 * has none), `ended`, `failed`, `stalled`, `no_voice`, `blocked`,
 * `unsupported` (this call cannot show one: a native call needs the
 * platform's WebRTC module and a call on WebRTC). [Phase.AVAILABLE] — a face is
 * ready: show your avatar view ([GraineAvatarFace] in
 * `ai.graine:inapp-kmp-compose`). [Phase.LIVE] — the face is on screen and
 * carries the agent's voice.
 */
data class GraineAvatarState(val phase: Phase, val reason: String? = null) {
    enum class Phase { OFF, AVAILABLE, LIVE }

    /** A face to draw: available or live. */
    val showsFace: Boolean get() = phase != Phase.OFF

    companion object {
        val OFF = GraineAvatarState(Phase.OFF)

        /** The platform SDK's `{"phase":"off"|"available"|"live","reason":…}`; anything else is OFF. */
        internal fun fromJson(json: String?): GraineAvatarState {
            val o = Json.obj(json) ?: return OFF
            val phase = when (o["phase"]) {
                "available" -> Phase.AVAILABLE
                "live" -> Phase.LIVE
                else -> Phase.OFF
            }
            val reason = (o["reason"] as? String)?.takeIf { phase == Phase.OFF && Regex("^[a-z_]{1,24}$").matches(it) }
            return GraineAvatarState(phase, reason)
        }
    }
}

/**
 * THE AGENT'S FACE — a live talking face for the agent on in-app calls, when
 * the agent has one (switched on in the dashboard) and the app shows an avatar
 * view. The face carries the agent's voice in sync with its lips; the call's
 * own copy of the voice is silenced only while it does, and comes back the
 * moment the face stops. With no avatar view on screen nothing is watched and
 * the call is voice-only, as before. One call at a time, so one face.
 */
class GraineAvatar internal constructor() {
    /** The face of the call in progress. */
    val state: StateFlow<GraineAvatarState> = Graine.avatarState.asStateFlow()
}
