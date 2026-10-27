package ai.graine.inapp.kmp

import ai.graine.inapp.ActionResult
import ai.graine.inapp.ConnectStage
import ai.graine.inapp.FrictionSignal
import ai.graine.inapp.GraineCall
import ai.graine.inapp.GraineCallListener
import ai.graine.inapp.GraineCallOutcome as NativeOutcome
import ai.graine.inapp.GraineCallState as NativeCallState
import ai.graine.inapp.GraineIdentityTokenRefresher
import ai.graine.inapp.GraineListener
import ai.graine.inapp.Role
import ai.graine.inapp.SessionConfig
import ai.graine.inapp.Transport
import android.app.Activity
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.json.JSONObject
import java.util.Date

/**
 * THE SHARED API ON ANDROID — one agent's calls delegated to the Android SDK
 * through [AndroidPort], and everything it reports turned into the shared types.
 *
 * The Android SDK reports in WIRE.md's types already (chat turns, booked calls,
 * errors, a call's outcome with why, how long and whether Graine rang it);
 * what is added here: `ringing` as an outcome when a ring starts, the chat's
 * connection as one word ([ChatInference.status], from its flags), the last missed ring, for
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
    /** The elapsed-time clock the Android SDK times a call on (it counts deep sleep). */
    private val elapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val ringtone: (String) -> android.net.Uri? = { android.net.Uri.parse(it) },
    /** The main thread, where the Android SDK's calls and chat want to be driven. */
    private val onMain: (() -> Unit) -> Unit = { AndroidHost.onMain(it) },
    private val onMainSync: (() -> String?) -> String? = { AndroidHost.onMainSync(it) },
    /** Calls back whenever the phone's configuration changes — light and dark among it. */
    private val watchAppearance: (() -> Unit) -> Unit = { changed -> AndroidHost.watchConfiguration(changed) },
) : GraineEngine {

    private lateinit var sink: EngineSink

    // Calls bookkeeping. Read and written on the main thread, where the Android SDK reports.
    private val calls = HashMap<String, GraineCall>()
    /** The agent each call is with, by id, noted while it was the call in progress: its outcome can come after. */
    private val callAgents = HashMap<String, String>()
    private var caption: GraineCaption? = null
    private var missed: GraineMissedCall? = null
    /** How the call that just ended ended (local, remote, failed), by id: its ENDED state says so. */
    private var endReason: Pair<String, String>? = null
    /** Who or what ended it (local_end, remote_bye, …), by id, likewise. */
    private var endCause: Pair<String, String>? = null
    /** When the call in progress connected, on the wall clock: worked out once per call, so it never moves. */
    private var startedAt: Pair<Long, Instant>? = null
    /** The voice bar's call ended and none has started since. Main thread. */
    private var voiceEnded = false

    override val installId: String get() = port.installId
    override val memoryEnabled: Boolean get() = port.memoryEnabled

    override fun start(sink: EngineSink) {
        this.sink = sink
        port.addListener(listener)
        port.addCallListener(callListener)
        port.addChatChangeListener(Runnable { publishChat() })
        // What Graine has proven for this agent: as it stands, then each change (the Android SDK tells it on its main thread).
        port.addVerificationListener { v -> sink.verification(AndroidMapping.verification(v)) }
        sink.verification(verification)
        // The Android SDK may have been started by the app before (or loaded its session already).
        port.session?.let { sink.session(AndroidMapping.session(it)) }
        // The design's tokens follow the phone between light and dark.
        watchAppearance { publishTheme() }
        publishTheme()
        publishChat()
        publishCalls()
        publishVoice()
    }

    // ── who, and memory ───────────────────────────────────────────────────

    override fun identify(traits: Map<String, Any?>?, memory: Boolean?, identityToken: String?) = port.identify(traits, memory, identityToken)
    override fun setMemory(enabled: Boolean) = port.setMemory(enabled)

    // ── your app and your users: the Android SDK declares the app, runs Play Integrity and carries the token ──

    override fun setIdentityToken(token: String?) = port.setIdentityToken(token)

    /** The shared refresher as the Android SDK's fun interface: its callback answered once, from any thread. */
    override fun setIdentityTokenRefresher(refresher: ((done: (String?) -> Unit) -> Unit)?) =
        port.setIdentityTokenRefresher(refresher?.let { r -> GraineIdentityTokenRefresher { done -> r { token -> done.onToken(token) } } })

    override fun prepareAppVerification(done: (GraineAppVerdict) -> Unit) =
        port.prepareAppVerification { verdict -> done(AndroidMapping.appVerdict(verdict)) }

    override val verification: GraineVerification get() = AndroidMapping.verification(port.verification)

    override var debugTokenMode: GraineDebugTokenMode
        get() = AndroidMapping.debugTokenMode(port.debugTokenMode)
        set(value) { port.debugTokenMode = AndroidMapping.debugTokenMode(value) }

    override val debugToken: String? get() = port.debugToken?.takeIf { it.isNotBlank() }

    override fun forgetMemory(done: (Result<Int>) -> Unit) = port.forgetMemory { deleted, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error))) else done(Result.success(deleted ?: 0))
    }

    override fun memories(done: (Result<List<GraineMemory>>) -> Unit) = port.memories { list, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error)))
        else done(Result.success(list.orEmpty().map { AndroidMapping.memory(it) }))
    }

    override fun setVariables(values: Map<String, Any?>?) = port.setVariables(values)
    override fun currentVariables(): Map<String, String> = port.currentVariables()

    // ── the screen ────────────────────────────────────────────────────────

    override fun setScreen(screen: GraineScreen?) = port.setScreen(screen?.let { AndroidMapping.screen(it) })
    override fun updateField(field: GraineField) = port.updateField(AndroidMapping.field(field))
    override fun removeField(name: String) = port.removeField(name)
    override fun clearScreen(screenId: String?) = port.clearScreen(screenId)
    override fun track(name: String, data: Map<String, Any?>) = port.track(name, data)
    /** The Android SDK's `reportEvent`, on its main thread as every call into it is. */
    override fun reportEvent(name: String, detail: String?, data: Map<String, Any?>) = port.reportEvent(name, detail, data)
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

    override fun startWorkflow(workflow: String, data: Map<String, Any?>, idempotencyKey: String?, done: (Result<GraineWorkflowRun>) -> Unit) =
        port.startWorkflow(workflow, data, idempotencyKey) { run, error ->
            when {
                error != null -> done(Result.failure(AndroidMapping.error(error)))
                run != null -> done(Result.success(GraineWorkflowRun(run.runId, run.workflowId, run.alreadyRunning)))
                else -> done(Result.failure(GraineException(GraineErrorCode.Unavailable, "The workflow was started, but its run id did not come back.")))
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

    override val bookingAvailable: Boolean get() = port.bookingAvailable
    override val bookingUnavailableReason: String? get() = port.bookingUnavailableReason

    /** The Android SDK's own slots for the day, asked at noon of it in the phone's zone (its sheet's zone too). */
    override fun bookingSlots(isoDay: String): List<Instant> {
        val day = runCatching { LocalDate.parse(isoDay) }.getOrNull() ?: return emptyList()
        val noon = LocalDateTime(day, LocalTime(12, 0)).toInstant(TimeZone.currentSystemDefault())
        return port.bookingSlots(Date(noon.toEpochMilliseconds())).map { Instant.fromEpochMilliseconds(it.time) }
    }

    /** The Android SDK's booking sheet, over the activity in front; none in front, nothing to show it over: null. */
    override fun presentBooking(reason: String?, done: (Result<GraineScheduledCall?>) -> Unit) = onMain {
        val activity = frontActivity()
        if (activity == null) {
            done(Result.success(null))
            return@onMain
        }
        port.presentBooking(activity, reason) { call, error ->
            if (error != null) done(Result.failure(AndroidMapping.error(error)))
            else done(Result.success(call?.let { AndroidMapping.scheduledCall(it) }))
        }
    }

    // ── the chat ──────────────────────────────────────────────────────────

    override fun chatOpen(size: GraineChatSize?) = onMain { port.chatOpen(size?.let { AndroidMapping.chatSize(it) }) }
    override fun chatClose() = onMain { port.chatClose() }
    override fun chatSend(text: String) = port.chatSend(text)
    override fun chatRetry() = onMain { port.chatConnect() }
    override fun chatClear() = onMain { port.chatClear() }
    override fun chatRefreshUpcoming() = onMain { port.chatRefreshUpcoming() }
    override fun chatCancelUpcoming(id: String) = onMain { port.chatCancelUpcoming(id) }
    override fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String) = onMain { port.answerWidget(widgetId, data, summary) }
    override fun dismissWidget(widgetId: String) = onMain { port.dismissWidget(widgetId) }
    override fun chatShown(visible: Boolean) = onMain { port.chatShown(visible) }

    override fun chatNewConversation(done: (Result<Unit>) -> Unit) = port.chatNewConversation { _, error ->
        if (error != null) done(Result.failure(AndroidMapping.error(error))) else done(Result.success(Unit))
    }

    // Chat window v2: the Android SDK's own members, which keep their rules (one rating, after the end; the pin's lines).
    override fun chatPin(pin: GraineChatPin?) = onMain { port.chatPin(pin?.let { AndroidMapping.pin(it) }) }
    override fun chatRate(style: String, value: Int) = onMain { port.chatRate(style, value) }
    override fun chatSendQuickReply(reply: GraineQuickReply) = port.chatSendQuickReply(AndroidMapping.quickReply(reply))
    override fun chatSetAttachHandler(handler: (() -> Unit)?) = onMain { port.chatSetAttach(handler) }

    // Past sessions: the Android SDK's own list, its paging and its rules (who it belongs to, what is kept).
    override fun chatRefreshPastSessions() = onMain { port.chatRefreshPastSessions() }
    override fun chatLoadMorePastSessions() = onMain { port.chatLoadMorePastSessions() }

    override fun chatSessionTranscript(id: String, done: (Result<GraineSessionTranscript>) -> Unit) = port.chatSessionTranscript(id) { t, error ->
        when {
            error != null -> done(Result.failure(AndroidMapping.error(error)))
            t != null -> done(Result.success(AndroidMapping.transcript(t)))
            else -> done(Result.failure(GraineException(GraineErrorCode.Unavailable, "Graine did not answer.")))
        }
    }

    override var chatCloseDelaySeconds: Int
        get() = port.chatCloseDelaySeconds
        set(value) { port.chatCloseDelaySeconds = value }

    private fun publishChat() {
        val turns = port.chatTurns.map { AndroidMapping.turn(it) }
        val status = ChatInference.status(
            port.chatConnected, port.chatConnecting, port.chatFailed, port.chatReconnecting, port.chatDisconnected, port.chatEnded,
            // A line the Android SDK still holds to send, with nothing connecting it: offline, as every SDK says it.
            waiting = ChatInference.waiting(turns),
        )
        sink.chat(turns, status, port.chatTyping)
        sink.chatExtras(
            AndroidMapping.extras(port.chatQuickReplies, port.chatEnded, port.chatRating, port.chatPinned)
                .copy(
                    newConversationBefore = port.chatNewConversationBefore,
                    sessionId = port.chatSessionId,
                    // Every retry spent, as the Android SDK's chat window says it: not connected, and gave up.
                    failed = port.chatFailed && !port.chatConnected,
                ),
        )
        sink.upcomingCalls(port.chatUpcomingCalls.map { AndroidMapping.scheduledCall(it) })
        sink.pastSessions(AndroidMapping.pastSessions(port.chatPastSessions))
    }

    // ── native calls ──────────────────────────────────────────────────────

    override fun callsConfigure(config: GraineCallsConfig) =
        port.configureCalls(AndroidMapping.callsConfig(config, appName(), ringtone))

    /** Android rings from Firebase data messages — the SDK's own messaging service, or an app's through [PlatformPush]; nothing to enable. */
    override fun callsEnableVoIPPushes() = Unit

    override fun callsStart(reason: String?, variables: Map<String, Any?>): String? = onMainSync start@{
        val activity = frontActivity() ?: return@start null
        missed = null
        port.startCall(activity, reason, variables)
    }

    /** No activity needed: nothing is dialled until a call starts. */
    override fun callsPrepare(reason: String?, variables: Map<String, Any?>) = onMain { port.prepareCall(reason, variables) }

    override fun callsAnswer() = port.answerCall()
    override fun callsDecline() = port.declineCall()
    override fun callsEnd() = port.endCall()
    override fun callsSetMuted(muted: Boolean) = port.setCallMuted(muted)
    override fun callsSetSpeaker(on: Boolean) = port.setCallSpeaker(on)
    override fun callsSetSpeakerByDefault(on: Boolean) = port.setSpeakerByDefault(on)

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

    override fun callsPermissions(): GraineCallPermissions = AndroidMapping.permissions(port.callPermissions)

    /** Over the app's screen in front: the SDK asks from a transparent screen of its own, so no result is forwarded. */
    override fun callsRequestPermissions(done: (GraineCallPermissions) -> Unit) = onMain {
        port.requestCallPermissions(frontActivity()) { done(AndroidMapping.permissions(it)) }
    }

    override fun callsOpenSettings(item: GraineCallPermission): Boolean =
        port.openCallSettings(AndroidMapping.permission(item), frontActivity())

    override fun callsSetupProblems(): List<GraineCallSetupProblem> = port.callSetupProblems.map { AndroidMapping.problem(it) }

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
                transport = GraineCallTransport.ofCall(port.callTransport).takeIf { c != null },
                transportFallbackReason = port.callTransportFallbackReason.takeIf { c != null },
                reconnecting = port.callReconnecting && c != null,
                reason = c?.reason?.takeIf { it.isNotBlank() },
                endReason = endReason?.takeIf { state == GraineCallState.ENDED && it.first == c?.callId }?.second,
                endCause = endCause?.takeIf { state == GraineCallState.ENDED && it.first == c?.callId }?.second,
                agentLevel = if (c != null) port.callAgentLevel.coerceIn(0f, 1f) else 0f,
                startedAt = startedAtOf(port.callConnectedAt).takeIf { c != null },
                connection = port.callConnection?.takeIf { c != null }?.let { AndroidMapping.connection(it) },
                // The Android SDK keeps them through the call's end and lets them go when it is idle again.
                captions = if (state == GraineCallState.IDLE) emptyList()
                    else port.callCaptions.map { GraineCaption(AndroidMapping.role(it.role), it.text, it.live) },
            )
        )
    }

    /** The elapsed-clock moment the call connected, on the wall clock — once per call: read again, it would move by a millisecond now and then. */
    private fun startedAtOf(connectedAt: Long): Instant? {
        if (connectedAt <= 0L) {
            startedAt = null
            return null
        }
        startedAt?.takeIf { it.first == connectedAt }?.let { return it.second }
        val at = Instant.fromEpochMilliseconds(wallMs() - (elapsedMs() - connectedAt))
        startedAt = connectedAt to at
        return at
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
            if (kind == GraineCallOutcome.Kind.ENDED) outcome.endReason?.let { endReason = callId to it.wire }
            outcome.endCause?.let { endCause = callId to it }
            // The Android SDK says why (busy: another call was live), how long a call that connected lasted, who rang it, and how it connected.
            sink.callOutcome(
                outcomeOf(
                    callId, kind, outcome.endReason?.wire, outcome.error, outcome.busy, outcome.durationSeconds?.let { it * 1000L }, hosted,
                    agentOf(callId),
                ).copy(connection = outcome.connection?.let { AndroidMapping.connection(it) }, endCause = outcome.endCause)
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
        override fun onCallReconnecting(reconnecting: Boolean) = publishCalls()
        override fun onAgentLevel(level: Float) = publishCalls()
        override fun onCallConnection(connection: ai.graine.inapp.GraineCallConnection) = publishCalls()
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

    override fun voiceSetCaptionsOn(on: Boolean) = onMain {
        port.setVoiceCaptionsOn(on)
        publishVoice()
    }

    override fun voiceClearCaptions() = onMain {
        port.clearVoiceCaptions()
        publishVoice()
    }

    private fun publishVoice() {
        val connected = port.voiceConnected
        val connecting = port.voiceConnecting
        if (connected || connecting) voiceEnded = false
        // A setting someone must fix, else a start nothing could carry: the bar's "Unavailable" and "Couldn't connect".
        val error = port.voiceError?.takeIf { it.isNotBlank() } ?: if (port.voiceCallFailed) CALL_FAILED else null
        val captions = port.voiceCaptions.map { GraineCaption(AndroidMapping.role(it.role), it.text, it.live) }
        val status = VoiceInference.status(connected, connecting, port.voiceMuted, error != null, voiceEnded, port.voicePhase, captions)
        sink.voice(
            GraineVoiceInfo(
                connected, connecting, port.voiceMuted, port.voiceCaptionsOn, captions,
                status = status,
                error = error.takeIf { status == GraineVoiceStatus.FAILED },
                agentLevel = port.voiceAgentLevel.coerceIn(0f, 1f).takeIf { connected } ?: 0f,
                micLevel = port.voiceMicLevel.coerceIn(0f, 1f).takeIf { connected } ?: 0f,
                transport = Bridge.transport(port.voiceTransport),
                transportFallbackReason = port.voiceTransportFallbackReason,
            )
        )
    }

    // ── what the SDK reports ──────────────────────────────────────────────

    /** The tokens the Android SDK draws with now, read again on a session and on a light/dark switch. */
    private fun publishTheme() = sink.theme(ThemeBridge.parse(port.themeTokensJson))

    private companion object {
        /** The voice bar's call could not be connected on either transport: the sentence its FAILED word carries. */
        const val CALL_FAILED = "The call could not be connected."
    }

    private val listener = object : GraineListener {
        override fun onSessionReady(session: SessionConfig) {
            sink.session(AndroidMapping.session(session))
            publishTheme()
        }

        override fun onCallState(connected: Boolean, connecting: Boolean) = publishVoice()

        override fun onCaption(role: Role, text: String, live: Boolean) {
            sink.event(GraineEvent.Caption(AndroidMapping.role(role), text, live))
            publishVoice()
        }

        override fun onEnded() {
            voiceEnded = true
            sink.event(GraineEvent.VoiceEnded)
            publishVoice()
        }

        // What the voice bar's word follows besides the call itself: its road, a connect that failed, a recovery.
        override fun onTransport(transport: Transport, requested: Transport, reason: String?, message: String?) = publishVoice()
        override fun onConnectProgress(stage: ConnectStage?, slow: Boolean, failed: Boolean) = publishVoice()
        override fun onReconnecting(active: Boolean) = publishVoice()
        // About five times a second while live; once more to 0 at the end.
        override fun onLevels(agent: Float, mic: Float) = publishVoice()

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
            // A call that cannot work as configured says so in the voice bar's word.
            publishVoice()
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
