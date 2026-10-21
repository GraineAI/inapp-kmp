package ai.graine.inapp.kmp

import ai.graine.inapp.ActionResult
import ai.graine.inapp.GraineCall
import ai.graine.inapp.GraineCallListener
import ai.graine.inapp.GraineCallPermission as NativeCallPermission
import ai.graine.inapp.GraineCallPermissions as NativeCallPermissions
import ai.graine.inapp.GraineCallConnection as NativeCallConnection
import ai.graine.inapp.GraineCallSetupProblem as NativeCallSetupProblem
import ai.graine.inapp.GraineCallState as NativeCallState
import ai.graine.inapp.GraineCallStatus as NativeCallStatus
import ai.graine.inapp.GraineCallback
import ai.graine.inapp.GraineCaption as NativeCaption
import ai.graine.inapp.GraineCallsConfig as NativeCallsConfig
import ai.graine.inapp.GraineChatPin as NativeChatPin
import ai.graine.inapp.GraineChatRating as NativeChatRating
import ai.graine.inapp.GraineChatSize as NativeChatSize
import ai.graine.inapp.GraineChatTurn as NativeChatTurn
import ai.graine.inapp.GraineQuickReply as NativeQuickReply
import ai.graine.inapp.GraineField as NativeField
import ai.graine.inapp.GraineInApp
import ai.graine.inapp.GraineListener
import ai.graine.inapp.GraineMemory as NativeMemory
import ai.graine.inapp.GraineScheduledCall as NativeScheduledCall
import ai.graine.inapp.GraineScreen as NativeScreen
import ai.graine.inapp.SessionConfig
import android.app.Activity
import org.json.JSONObject
import java.util.Date

/**
 * EVERYTHING THE KOTLIN MULTIPLATFORM LAYER CALLS ON THE ANDROID SDK, in one
 * place: [RealAndroidPort] is these calls, one line each, on one agent's
 * `GraineInApp`. [AndroidEngine] drives only this interface, so
 * androidUnitTest runs it over a fake port and the Android SDK's real types —
 * and when the Android SDK's signatures move, this file is the one to follow them.
 */
internal interface AndroidPort {
    /** The agent's name: a call placed through this port is with it. */
    val name: String
    val installId: String
    val memoryEnabled: Boolean
    val session: SessionConfig?
    /** The design's tokens for the phone's appearance now, as the contract's JSON (ThemeTokens.toJson); null without a design. */
    val themeTokensJson: String?
    fun addListener(listener: GraineListener)

    fun identify(traits: Map<String, Any?>?, memory: Boolean?)
    fun setMemory(enabled: Boolean)
    fun forgetMemory(done: GraineCallback<Int>)
    fun memories(done: GraineCallback<List<NativeMemory>>)
    fun setVariables(values: Map<String, Any?>?)
    fun currentVariables(): Map<String, String>
    fun setScreen(screen: NativeScreen?)
    fun updateField(field: NativeField)
    fun removeField(name: String)
    fun clearScreen(screenId: String?)
    fun track(name: String, data: Map<String, Any?>)
    fun tap(name: String)
    fun noteInteraction()
    fun registerDeferredAction(name: String, handler: (args: JSONObject, answer: (ActionResult) -> Unit) -> Unit): () -> Unit
    fun scheduleCall(at: Date, reason: String?, done: GraineCallback<NativeScheduledCall>)
    fun scheduledCalls(done: GraineCallback<List<NativeScheduledCall>>)
    fun cancelScheduledCall(id: String, done: GraineCallback<NativeCallStatus>)
    val bookingAvailable: Boolean
    /** Why not, as a word (GraineInApp.bookingUnavailableReason); null when it can be. */
    val bookingUnavailableReason: String?
    /** The booking sheet's times on the day [day] falls on (GraineInApp.bookingSlots). */
    fun bookingSlots(day: Date): List<Date>
    /** The booking sheet over [activity]: the booked call once it closes, or null. */
    fun presentBooking(activity: Activity, reason: String?, done: GraineCallback<NativeScheduledCall?>)
    /** Close a component without answering it (GraineInApp.dismissWidget). */
    fun dismissWidget(widgetId: String)

    // The agent's chat (GraineInApp.chat).
    val chatTurns: List<NativeChatTurn>
    val chatConnected: Boolean
    val chatConnecting: Boolean
    val chatFailed: Boolean
    /** A drop (or a first try that failed) being made again. */
    val chatReconnecting: Boolean
    val chatTyping: Boolean
    /** The chat's upcoming calls, soonest first (its card's). */
    val chatUpcomingCalls: List<NativeScheduledCall>
    /** The replies the agent suggests for its latest turn. */
    val chatQuickReplies: List<NativeQuickReply>
    /** The agent ended the conversation. */
    val chatEnded: Boolean
    /** The user's rating of the conversation, once given. */
    val chatRating: NativeChatRating?
    /** The card the app pinned. */
    val chatPinned: NativeChatPin?
    /** Graine ended the conversation itself and nothing is connecting again yet (GraineChat.disconnected). */
    val chatDisconnected: Boolean
    /** The turns a "Disconnected · new conversation" row goes before (GraineChat.newConversationBefore). */
    val chatNewConversationBefore: List<String>
    /** The chat's current session's id (GraineChat.sessionId). */
    val chatSessionId: String?
    fun addChatChangeListener(listener: Runnable)
    /** A chat screen of the app's own came on screen or left it (GraineChat.shown). */
    fun chatShown(visible: Boolean)
    /** The user is in the chat, at [size] for this open (null: the app's, then the dashboard's). */
    fun chatOpen(size: NativeChatSize?)
    fun chatConnect()
    fun chatClear()
    fun chatClose()
    fun chatSend(text: String)
    fun chatNewConversation(done: GraineCallback<Unit>)
    fun chatRefreshUpcoming()
    fun chatCancelUpcoming(id: String)
    /** A component answered, on the conversation that is live (GraineInApp.answerWidget). */
    fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String)
    fun chatPin(pin: NativeChatPin?)
    fun chatRate(style: String, value: Int)
    fun chatSendQuickReply(reply: NativeQuickReply)
    /** The paperclip's handler (GraineChat.onAttach); null: no paperclip. */
    fun chatSetAttach(handler: (() -> Unit)?)

    // Native calls (GraineInApp.calls): the app's one call, whichever agent it is with.
    val callState: NativeCallState
    val call: GraineCall?
    val callMuted: Boolean
    val callSpeaker: Boolean
    val callAgentSpeaking: Boolean
    /** The call in progress is recovering its connection. */
    val callReconnecting: Boolean
    /** The name of the agent the call in progress is with; null with no call. */
    val callAgentName: String?
    /** What the call in progress runs on: "webrtc" or "websocket"; null with no call. */
    val callTransport: String?
    /** Why the call in progress, which tried WebRTC, runs on the WebSocket; null otherwise. */
    val callTransportFallbackReason: String?
    /** The agent's voice level, 0–1, on the call in progress (GraineCalls.agentLevel). */
    val callAgentLevel: Float
    /** When the call in progress connected, on the elapsed-time clock (GraineCalls.connectedAt); 0 before. */
    val callConnectedAt: Long
    /** How the call in progress connected so far (GraineCalls.connection). */
    val callConnection: NativeCallConnection?
    fun addCallListener(listener: GraineCallListener)
    fun configureCalls(config: NativeCallsConfig)
    /** A call with this port's agent. */
    fun startCall(activity: Activity, reason: String?, variables: Map<String, Any?>): String?
    /** This port's agent's next call, ready before the tap (GraineCalls.prepareCall). */
    fun prepareCall(reason: String?, variables: Map<String, Any?>)
    fun callBack(activity: Activity, missedCallId: String?, reason: String?): String?
    fun answerCall()
    fun declineCall()
    fun endCall()
    fun setCallMuted(muted: Boolean)
    fun setCallSpeaker(on: Boolean)
    /** Calls start on the loudspeaker unless a headset is connected (GraineCalls.speakerByDefault). */
    fun setSpeakerByDefault(on: Boolean)
    /** The call permissions as the phone holds them (GraineCalls.callPermissions). */
    val callPermissions: NativeCallPermissions
    /** Ask over [activity] (null: the screen in front, as the SDK finds it); [done] gets the permissions after. */
    fun requestCallPermissions(activity: Activity?, done: (NativeCallPermissions) -> Unit)
    fun openCallSettings(item: NativeCallPermission, activity: Activity?): Boolean
    /** What the app's setup still lacks for calls (GraineCalls.callSetupProblems). */
    val callSetupProblems: List<NativeCallSetupProblem>

    // The agent's voice bar call (GraineInApp.voice).
    val voiceConnected: Boolean
    val voiceConnecting: Boolean
    val voiceMuted: Boolean
    val voiceCaptionsOn: Boolean
    val voiceCaptions: List<NativeCaption>
    /** What the call is doing, in one word (GraineVoice.phase): its name. */
    val voicePhase: String
    /** A setting someone must fix before calls work (GraineVoice.error). */
    val voiceError: String?
    /** The last start could not be connected at all (GraineVoice.callFailed). */
    val voiceCallFailed: Boolean
    /** What the call runs on, as its wire word — "rtc" or "ws" (GraineVoice.transport); null before any call. */
    val voiceTransport: String?
    val voiceTransportFallbackReason: String?
    fun attachVoice(activity: Activity)
    fun startVoice()
    fun endVoice()
    fun setVoiceMuted(muted: Boolean)
    fun setVoiceCaptionsOn(on: Boolean)
    fun clearVoiceCaptions()
}

/** [AndroidPort] on the real SDK: the agent [g]. */
internal class RealAndroidPort(private val g: GraineInApp) : AndroidPort {
    override val name: String get() = g.name
    override val installId: String get() = g.installId
    override val memoryEnabled: Boolean get() = g.memoryEnabled
    override val session: SessionConfig? get() = g.session
    override val themeTokensJson: String? get() = g.themeTokens()?.toJson()?.toString()
    override fun addListener(listener: GraineListener) = g.addListener(listener)

    override fun identify(traits: Map<String, Any?>?, memory: Boolean?) = g.identify(traits, memory)
    override fun setMemory(enabled: Boolean) = g.setMemory(enabled)
    override fun forgetMemory(done: GraineCallback<Int>) = g.forgetMemory(done)
    override fun memories(done: GraineCallback<List<NativeMemory>>) = g.memories(done)
    override fun setVariables(values: Map<String, Any?>?) = g.setVariables(values)
    override fun currentVariables(): Map<String, String> = g.currentVariables()
    override fun setScreen(screen: NativeScreen?) = g.setScreen(screen)
    override fun updateField(field: NativeField) = g.updateField(field)
    override fun removeField(name: String) = g.removeField(name)
    override fun clearScreen(screenId: String?) = g.clearScreen(screenId)
    override fun track(name: String, data: Map<String, Any?>) = g.track(name, data)
    override fun tap(name: String) = g.tap(name)
    override fun noteInteraction() = g.noteInteraction()
    override fun registerDeferredAction(name: String, handler: (args: JSONObject, answer: (ActionResult) -> Unit) -> Unit): () -> Unit =
        g.registerDeferredAction(name, handler)
    override fun scheduleCall(at: Date, reason: String?, done: GraineCallback<NativeScheduledCall>) = g.scheduleCall(at, reason, done)
    override fun scheduledCalls(done: GraineCallback<List<NativeScheduledCall>>) = g.scheduledCalls(done)
    override fun cancelScheduledCall(id: String, done: GraineCallback<NativeCallStatus>) = g.cancelScheduledCall(id, done)
    override val bookingAvailable: Boolean get() = g.bookingAvailable
    override val bookingUnavailableReason: String? get() = g.bookingUnavailableReason
    override fun bookingSlots(day: Date): List<Date> = g.bookingSlots(day)
    override fun presentBooking(activity: Activity, reason: String?, done: GraineCallback<NativeScheduledCall?>) =
        g.presentBooking(activity, reason, done)
    override fun dismissWidget(widgetId: String) = g.dismissWidget(widgetId)

    override val chatTurns: List<NativeChatTurn> get() = g.chat.messages
    override val chatConnected: Boolean get() = g.chat.connected
    override val chatConnecting: Boolean get() = g.chat.connecting
    override val chatFailed: Boolean get() = g.chat.failed
    override val chatReconnecting: Boolean get() = g.chat.reconnecting
    override val chatTyping: Boolean get() = g.chat.typing
    override val chatUpcomingCalls: List<NativeScheduledCall> get() = g.chat.upcomingCalls
    override val chatQuickReplies: List<NativeQuickReply> get() = g.chat.quickReplies
    override val chatEnded: Boolean get() = g.chat.ended
    override val chatRating: NativeChatRating? get() = g.chat.rating
    override val chatPinned: NativeChatPin? get() = g.chat.pinned
    override val chatDisconnected: Boolean get() = g.chat.disconnected
    override val chatNewConversationBefore: List<String> get() = g.chat.newConversationBefore
    override val chatSessionId: String? get() = g.chat.sessionId
    override fun addChatChangeListener(listener: Runnable) = g.chat.addChangeListener(listener)
    override fun chatShown(visible: Boolean) = g.chat.shown(visible)
    override fun chatOpen(size: NativeChatSize?) = g.chat.open(size)
    override fun chatConnect() = g.chat.connect()
    override fun chatClear() = g.chat.clear()
    override fun chatClose() = g.chat.close()
    override fun chatSend(text: String) = g.chat.send(text)
    override fun chatNewConversation(done: GraineCallback<Unit>) = g.chat.newConversation(done)
    override fun chatRefreshUpcoming() = g.chat.refreshUpcomingCalls()
    override fun chatCancelUpcoming(id: String) = g.chat.cancelUpcomingCall(id)
    override fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String) = g.answerWidget(widgetId, data, summary)
    override fun chatPin(pin: NativeChatPin?) = g.chat.pin(pin)
    override fun chatRate(style: String, value: Int) { g.chat.rate(style, value) }
    override fun chatSendQuickReply(reply: NativeQuickReply) = g.chat.sendQuickReply(reply)
    override fun chatSetAttach(handler: (() -> Unit)?) { g.chat.onAttach = handler }

    override val callState: NativeCallState get() = g.calls.state
    override val call: GraineCall? get() = g.calls.call
    override val callMuted: Boolean get() = g.calls.muted
    override val callSpeaker: Boolean get() = g.calls.speaker
    override val callAgentSpeaking: Boolean get() = g.calls.agentSpeaking
    override val callReconnecting: Boolean get() = g.calls.reconnecting
    override val callAgentName: String? get() = g.calls.callAgent?.name
    override val callTransport: String? get() = g.calls.transport
    override val callTransportFallbackReason: String? get() = g.calls.transportFallbackReason
    override val callAgentLevel: Float get() = g.calls.agentLevel
    override val callConnectedAt: Long get() = g.calls.connectedAt
    override val callConnection: NativeCallConnection? get() = g.calls.connection
    override fun addCallListener(listener: GraineCallListener) = g.calls.addListener(listener)
    override fun configureCalls(config: NativeCallsConfig) = g.calls.configure(config)
    override fun startCall(activity: Activity, reason: String?, variables: Map<String, Any?>): String? =
        g.calls.startCall(activity, reason, variables, agent = g.name)
    override fun prepareCall(reason: String?, variables: Map<String, Any?>) = g.calls.prepareCall(reason, variables, agent = g.name)
    override fun callBack(activity: Activity, missedCallId: String?, reason: String?): String? = g.calls.callBack(activity, missedCallId, reason)
    override fun answerCall() = g.calls.answer()
    override fun declineCall() = g.calls.decline()
    override fun endCall() = g.calls.end()
    override fun setCallMuted(muted: Boolean) = g.calls.setMuted(muted)
    override fun setCallSpeaker(on: Boolean) = g.calls.setSpeaker(on)
    override fun setSpeakerByDefault(on: Boolean) { g.calls.speakerByDefault = on }
    override val callPermissions: NativeCallPermissions get() = g.calls.callPermissions
    override fun requestCallPermissions(activity: Activity?, done: (NativeCallPermissions) -> Unit) =
        g.calls.requestCallPermissions(activity) { done(it) }
    override fun openCallSettings(item: NativeCallPermission, activity: Activity?): Boolean = g.calls.openCallSettings(item, activity)
    override val callSetupProblems: List<NativeCallSetupProblem> get() = g.calls.callSetupProblems

    override val voiceConnected: Boolean get() = g.voice.connected
    override val voiceConnecting: Boolean get() = g.voice.connecting
    override val voiceMuted: Boolean get() = g.voice.muted
    override val voiceCaptionsOn: Boolean get() = g.voice.captionsOn
    override val voiceCaptions: List<NativeCaption> get() = g.voice.captions
    override val voicePhase: String get() = g.voice.phase.name
    override val voiceError: String? get() = g.voice.error
    override val voiceCallFailed: Boolean get() = g.voice.callFailed
    override val voiceTransport: String? get() = g.voice.transport?.wire
    override val voiceTransportFallbackReason: String? get() = g.voice.transportFallbackReason
    override fun attachVoice(activity: Activity) = g.voice.attach(activity)
    override fun startVoice() = g.voice.start()
    override fun endVoice() = g.voice.end()
    override fun setVoiceMuted(muted: Boolean) = g.voice.setMuted(muted)
    override fun setVoiceCaptionsOn(on: Boolean) = g.voice.setCaptionsOn(on)
    override fun clearVoiceCaptions() = g.voice.clearCaptions()
}
