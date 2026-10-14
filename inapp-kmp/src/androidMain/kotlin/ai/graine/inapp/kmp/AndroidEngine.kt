package ai.graine.inapp.kmp

import ai.graine.inapp.ActionResult
import ai.graine.inapp.FrictionSignal
import ai.graine.inapp.GraineCall
import ai.graine.inapp.GraineCallListener
import ai.graine.inapp.GraineCallOutcome as NativeOutcome
import ai.graine.inapp.GraineCallState as NativeCallState
import ai.graine.inapp.GraineListener
import ai.graine.inapp.Role
import ai.graine.inapp.SessionConfig
import android.app.Activity
import kotlinx.datetime.Instant
import org.json.JSONObject
import java.util.Date

/**
 * THE SHARED API ON ANDROID — one agent's calls delegated to the Android SDK
 * through [AndroidPort], and everything it reports turned into the shared types.
 *
 * The Android SDK reports in WIRE.md's types already (chat turns, booked calls,
 * errors, a call's outcome with why, how long and whether Graine rang it);
 * what is added here: `ringing` as an outcome when a ring starts, the chat's
 * connection as one word ([ChatInference.status]), the last missed ring, for
 * "Call back", and the agent each call is with, kept past the call (the
 * Android SDK names it only for the call in progress).
 */
internal class AndroidEngine(
    private val port: AndroidPort,
    /** The app's own name: a call that names nobody rings with it. */
    private val appName: () -> String? = { null },
    /** The activity in front: a call and the voice bar start from it. */
    private val frontActivity: () -> Activity? = { AndroidHost.activity },
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    private val ringtone: (String) -> android.net.Uri? = { android.net.Uri.parse(it) },
    /** The main thread, where the Android SDK's calls and chat want to be driven. */
    private val onMain: (() -> Unit) -> Unit = { AndroidHost.onMain(it) },
    private val onMainSync: (() -> String?) -> String? = { AndroidHost.onMainSync(it) },
) : GraineEngine {

    private lateinit var sink: EngineSink

    // Calls bookkeeping. Read and written on the main thread, where the Android SDK reports.
    private val calls = HashMap<String, GraineCall>()
    /** The agent each call is with, by id, noted while it was the call in progress: its outcome can come after. */
    private val callAgents = HashMap<String, String>()
    private var caption: GraineCaption? = null
    private var missed: GraineMissedCall? = null
    private var chatStatus = GraineChatStatus.IDLE

    override val installId: String get() = port.installId
    override val memoryEnabled: Boolean get() = port.memoryEnabled

    override fun start(sink: EngineSink) {
        this.sink = sink
        port.addListener(listener)
        port.addCallListener(callListener)
        port.addChatChangeListener(Runnable { publishChat() })
        // The Android SDK may have been started by the app before (or loaded its session already).
        port.session?.let { sink.session(AndroidMapping.session(it)) }
        publishChat()
        publishCalls()
        publishVoice()
    }

    // ── who, and memory ───────────────────────────────────────────────────

    override fun identify(traits: Map<String, Any?>?, memory: Boolean?) = port.identify(traits, memory)
    override fun setMemory(enabled: Boolean) = port.setMemory(enabled)

    override fun forgetMemory(done: (Result<Int>) -> Unit) = port.forgetMemory { deleted, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error))) else done(Result.success(deleted ?: 0))
    }

    override fun memories(done: (Result<List<GraineMemory>>) -> Unit) = port.memories { list, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error)))
        else done(Result.success(list.orEmpty().map { AndroidMapping.memory(it) }))
    }

    override fun setVariables(values: Map<String, Any?>?) = port.setVariables(values)

    // ── the screen ────────────────────────────────────────────────────────

    override fun setScreen(screen: GraineScreen?) = port.setScreen(screen?.let { AndroidMapping.screen(it) })
    override fun updateField(field: GraineField) = port.updateField(AndroidMapping.field(field))
    override fun removeField(name: String) = port.removeField(name)
    override fun clearScreen(screenId: String?) = port.clearScreen(screenId)
    override fun track(name: String, data: Map<String, Any?>) = port.track(name, data)
    override fun tap(name: String) = port.tap(name)
    override fun noteInteraction() = port.noteInteraction()

    override fun registerAction(name: String, handler: (args: Map<String, Any?>, answer: (GraineActionResult) -> Unit) -> Unit): () -> Unit =
        port.registerDeferredAction(name) { args: JSONObject, answer: (ActionResult) -> Unit ->
            handler(AndroidMapping.args(args)) { result -> answer(AndroidMapping.result(result)) }
        }

    // ── scheduled calls ───────────────────────────────────────────────────

    override fun scheduleCall(at: Instant, reason: String?, done: (Result<GraineScheduledCall>) -> Unit) =
        port.scheduleCall(Date(at.toEpochMilliseconds()), reason) { call, error ->
            when {
                error != null -> done(Result.failure(AndroidMapping.error(error)))
                call != null -> done(Result.success(AndroidMapping.scheduledCall(call)))
                else -> done(Result.failure(GraineException(GraineErrorCode.Unavailable, "The booking was accepted but not described.")))
            }
        }

    override fun scheduledCalls(done: (Result<List<GraineScheduledCall>>) -> Unit) = port.scheduledCalls { list, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error)))
        else done(Result.success(list.orEmpty().map { AndroidMapping.scheduledCall(it) }))
    }

    override fun cancelScheduledCall(id: String, done: (Result<GraineCallStatus>) -> Unit) = port.cancelScheduledCall(id) { status, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error)))
        else done(Result.success(status?.let { AndroidMapping.callStatus(it) } ?: GraineCallStatus.Cancelled))
    }

    // ── the chat ──────────────────────────────────────────────────────────

    override fun chatOpen(size: GraineChatSize?) = onMain { port.chatOpen(size?.let { AndroidMapping.chatSize(it) }) }
    override fun chatClose() = onMain { port.chatClose() }
    override fun chatSend(text: String) = port.chatSend(text)
    override fun chatRetry() = onMain { port.chatConnect() }

    override fun chatNewConversation(done: (Result<Unit>) -> Unit) = port.chatNewConversation { _, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error))) else done(Result.success(Unit))
    }

    private fun publishChat() {
        chatStatus = ChatInference.status(port.chatConnected, port.chatConnecting, port.chatFailed, chatStatus)
        sink.chat(port.chatTurns.map { AndroidMapping.turn(it) }, chatStatus, port.chatTyping)
    }

    // ── native calls ──────────────────────────────────────────────────────

    override fun callsConfigure(config: GraineCallsConfig) =
        port.configureCalls(AndroidMapping.callsConfig(config, appName(), ringtone))

    /** Android rings from the messaging service's data messages ([PlatformPush]); nothing to enable. */
    override fun callsEnableVoIPPushes() = Unit

    override fun callsStart(reason: String?, variables: Map<String, Any?>): String? = onMainSync start@{
        val activity = frontActivity() ?: return@start null
        missed = null
        port.startCall(activity, reason, variables)
    }

    override fun callsAnswer() = port.answerCall()
    override fun callsDecline() = port.declineCall()
    override fun callsEnd() = port.endCall()
    override fun callsSetMuted(muted: Boolean) = port.setCallMuted(muted)
    override fun callsSetSpeaker(on: Boolean) = port.setCallSpeaker(on)

    override fun callsCallBack(): String? = onMainSync back@{
        val activity = frontActivity() ?: return@back null
        val m = missed
        missed = null
        val id = port.callBack(activity, m?.callId, m?.reason)
        publishCalls()
        id
    }

    override fun callsDismissMissed() = onMain {
        missed = null
        publishCalls()
    }

    private fun publishCalls() {
        val state = AndroidMapping.callState(port.callState)
        val c = port.call
        sink.calls(
            GraineCallInfo(
                state = state,
                callId = c?.callId,
                callerName = c?.callerName,
                incoming = c?.direction == GraineCall.Direction.INCOMING,
                hosted = c?.hosted == true,
                muted = port.callMuted,
                speakerOn = port.callSpeaker,
                agentSpeaking = port.callAgentSpeaking,
                caption = caption.takeIf { state != GraineCallState.IDLE },
                missedCall = missed,
                agent = port.callAgentName.takeIf { c != null },
            )
        )
    }

    private val callListener = object : GraineCallListener {
        override fun onIncomingCall(call: GraineCall) {
            remember(call)
            missed = null
            val agent = agentOf(call.callId)
            sink.incomingCall(GraineIncomingCall(call.callId, call.callerName, call.reason, call.hosted, agent))
            // The ring itself is an outcome in WIRE.md's terms, as the native SDKs report it to Graine.
            sink.callOutcome(outcomeOf(call.callId, GraineCallOutcome.Kind.RINGING, hosted = call.hosted, agent = agent))
        }

        override fun onCallState(state: NativeCallState, call: GraineCall?) {
            call?.let { remember(it) }
            if (state == NativeCallState.IDLE) caption = null
            publishCalls()
        }

        override fun onCallOutcome(callId: String, outcome: NativeOutcome) {
            val call = calls[callId]
            val hosted = outcome.hosted || call?.hosted == true
            val kind = AndroidMapping.outcomeKind(outcome)
            // The Android SDK says why (busy: another call was live), how long a call that connected lasted, and who rang it.
            sink.callOutcome(
                outcomeOf(
                    callId, kind, outcome.endReason?.wire, outcome.error, outcome.busy, outcome.durationSeconds?.let { it * 1000L }, hosted,
                    agentOf(callId),
                )
            )
            if (kind == GraineCallOutcome.Kind.MISSED && !outcome.busy) {
                missed = GraineMissedCall(callId, call?.callerName, call?.reason, hosted, Instant.fromEpochMilliseconds(wallMs()))
            }
            // That was the last word on this call.
            if (kind != GraineCallOutcome.Kind.ANSWERED) {
                calls.remove(callId)
                callAgents.remove(callId)
            }
            publishCalls()
        }

        override fun onCallCaption(role: Role, text: String, live: Boolean) {
            caption = GraineCaption(AndroidMapping.role(role), text, live)
            publishCalls()
        }

        override fun onAgentSpeaking(speaking: Boolean) = publishCalls()
        override fun onCallControls(muted: Boolean, speaker: Boolean) = publishCalls()
    }

    private fun remember(call: GraineCall) {
        calls[call.callId] = call
        // While it is the call in progress, the Android SDK names its agent: kept for the outcomes after it.
        if (port.call?.callId == call.callId) port.callAgentName?.let { callAgents[call.callId] = it }
    }

    /**
     * The agent a call is with: as the Android SDK named it while that call was
     * in progress. Null for a call never in progress here — a ring refused
     * because another call was live.
     */
    private fun agentOf(callId: String): String? =
        callAgents[callId] ?: port.callAgentName?.takeIf { port.call?.callId == callId }

    // ── the voice bar's call ──────────────────────────────────────────────

    override fun voiceStart() = onMain {
        frontActivity()?.let { port.attachVoice(it) }
        port.startVoice()
    }

    override fun voiceEnd() = onMain { port.endVoice() }

    override fun voiceSetMuted(muted: Boolean) = onMain {
        port.setVoiceMuted(muted)
        publishVoice()
    }

    private fun publishVoice() = sink.voice(GraineVoiceInfo(port.voiceConnected, port.voiceConnecting, port.voiceMuted))

    // ── what the SDK reports ──────────────────────────────────────────────

    private val listener = object : GraineListener {
        override fun onSessionReady(session: SessionConfig) = sink.session(AndroidMapping.session(session))

        override fun onCallState(connected: Boolean, connecting: Boolean) = publishVoice()

        override fun onCaption(role: Role, text: String, live: Boolean) {
            sink.event(GraineEvent.Caption(AndroidMapping.role(role), text, live))
            publishVoice()
        }

        override fun onEnded() {
            sink.event(GraineEvent.VoiceEnded)
            publishVoice()
        }

        override fun onWidget(widget: JSONObject) {
            componentOf(AndroidMapping.map(widget))?.let { sink.event(GraineEvent.Component(it)) }
        }

        override fun onWidgetPending(hint: JSONObject) = sink.event(GraineEvent.ComponentPending(AndroidMapping.map(hint)))

        override fun onMicDenied(reason: String) = sink.event(GraineEvent.MicrophoneDenied(reason))

        override fun onActionRequested(name: String) = sink.event(GraineEvent.ActionRequested(name))

        override fun onActionCompleted(name: String, result: ActionResult) =
            sink.event(GraineEvent.ActionCompleted(name, AndroidMapping.result(result)))

        override fun onFriction(signal: FrictionSignal) =
            sink.event(GraineEvent.Friction(signal.name, signal.screen.takeIf { it.isNotBlank() }, signal.detail))

        override fun onError(message: String) {
            // Before any session, an error is the session failing to load: a booking stops waiting for it.
            if (port.session == null) sink.sessionFailed(message)
            sink.event(GraineEvent.Error(message))
        }

        override fun onChatState(connected: Boolean, connecting: Boolean) = publishChat()

        override fun onChatMessage(role: Role, text: String, live: Boolean) {
            if (role != Role.AGENT || live) return
            publishChat()
            // The agent's message, complete: the turn as the chat now holds it.
            val turn = port.chatTurns.lastOrNull { it.role == Role.AGENT }?.let { AndroidMapping.turn(it) } ?: return
            sink.event(GraineEvent.ChatMessage(turn))
        }
    }
}
