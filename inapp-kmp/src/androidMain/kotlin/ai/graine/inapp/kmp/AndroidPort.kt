package ai.graine.inapp.kmp

import ai.graine.inapp.ActionResult
import ai.graine.inapp.GraineCall
import ai.graine.inapp.GraineCallListener
import ai.graine.inapp.GraineCallState as NativeCallState
import ai.graine.inapp.GraineCallStatus as NativeCallStatus
import ai.graine.inapp.GraineCallback
import ai.graine.inapp.GraineCallsConfig as NativeCallsConfig
import ai.graine.inapp.GraineChatSize as NativeChatSize
import ai.graine.inapp.GraineChatTurn as NativeChatTurn
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
    fun addListener(listener: GraineListener)

    fun identify(traits: Map<String, Any?>?, memory: Boolean?)
    fun setMemory(enabled: Boolean)
    fun forgetMemory(done: GraineCallback<Int>)
    fun memories(done: GraineCallback<List<NativeMemory>>)
    fun setVariables(values: Map<String, Any?>?)
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

    // The agent's chat (GraineInApp.chat).
    val chatTurns: List<NativeChatTurn>
    val chatConnected: Boolean
    val chatConnecting: Boolean
    val chatFailed: Boolean
    val chatTyping: Boolean
    fun addChatChangeListener(listener: Runnable)
    /** The user is in the chat, at [size] for this open (null: the app's, then the dashboard's). */
    fun chatOpen(size: NativeChatSize?)
    fun chatConnect()
    fun chatClose()
    fun chatSend(text: String)
    fun chatNewConversation(done: GraineCallback<Unit>)

    // Native calls (GraineInApp.calls): the app's one call, whichever agent it is with.
    val callState: NativeCallState
    val call: GraineCall?
    val callMuted: Boolean
    val callSpeaker: Boolean
    val callAgentSpeaking: Boolean
    /** The name of the agent the call in progress is with; null with no call. */
    val callAgentName: String?
    fun addCallListener(listener: GraineCallListener)
    fun configureCalls(config: NativeCallsConfig)
    /** A call with this port's agent. */
    fun startCall(activity: Activity, reason: String?, variables: Map<String, Any?>): String?
    fun callBack(activity: Activity, missedCallId: String?, reason: String?): String?
    fun answerCall()
    fun declineCall()
    fun endCall()
    fun setCallMuted(muted: Boolean)
    fun setCallSpeaker(on: Boolean)

    // The agent's voice bar call (GraineInApp.voice).
    val voiceConnected: Boolean
    val voiceConnecting: Boolean
    val voiceMuted: Boolean
    fun attachVoice(activity: Activity)
    fun startVoice()
    fun endVoice()
    fun setVoiceMuted(muted: Boolean)
}

/** [AndroidPort] on the real SDK: the agent [g]. */
internal class RealAndroidPort(private val g: GraineInApp) : AndroidPort {
    override val name: String get() = g.name
    override val installId: String get() = g.installId
    override val memoryEnabled: Boolean get() = g.memoryEnabled
    override val session: SessionConfig? get() = g.session
    override fun addListener(listener: GraineListener) = g.addListener(listener)

    override fun identify(traits: Map<String, Any?>?, memory: Boolean?) = g.identify(traits, memory)
    override fun setMemory(enabled: Boolean) = g.setMemory(enabled)
    override fun forgetMemory(done: GraineCallback<Int>) = g.forgetMemory(done)
    override fun memories(done: GraineCallback<List<NativeMemory>>) = g.memories(done)
    override fun setVariables(values: Map<String, Any?>?) = g.setVariables(values)
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

    override val chatTurns: List<NativeChatTurn> get() = g.chat.messages
    override val chatConnected: Boolean get() = g.chat.connected
    override val chatConnecting: Boolean get() = g.chat.connecting
    override val chatFailed: Boolean get() = g.chat.failed
    override val chatTyping: Boolean get() = g.chat.typing
    override fun addChatChangeListener(listener: Runnable) = g.chat.addChangeListener(listener)
    override fun chatOpen(size: NativeChatSize?) = g.chat.open(size)
    override fun chatConnect() = g.chat.connect()
    override fun chatClose() = g.chat.close()
    override fun chatSend(text: String) = g.chat.send(text)
    override fun chatNewConversation(done: GraineCallback<Unit>) = g.chat.newConversation(done)

    override val callState: NativeCallState get() = g.calls.state
    override val call: GraineCall? get() = g.calls.call
    override val callMuted: Boolean get() = g.calls.muted
    override val callSpeaker: Boolean get() = g.calls.speaker
    override val callAgentSpeaking: Boolean get() = g.calls.agentSpeaking
    override val callAgentName: String? get() = g.calls.callAgent?.name
    override fun addCallListener(listener: GraineCallListener) = g.calls.addListener(listener)
    override fun configureCalls(config: NativeCallsConfig) = g.calls.configure(config)
    override fun startCall(activity: Activity, reason: String?, variables: Map<String, Any?>): String? =
        g.calls.startCall(activity, reason, variables, agent = g.name)
    override fun callBack(activity: Activity, missedCallId: String?, reason: String?): String? = g.calls.callBack(activity, missedCallId, reason)
    override fun answerCall() = g.calls.answer()
    override fun declineCall() = g.calls.decline()
    override fun endCall() = g.calls.end()
    override fun setCallMuted(muted: Boolean) = g.calls.setMuted(muted)
    override fun setCallSpeaker(on: Boolean) = g.calls.setSpeaker(on)

    override val voiceConnected: Boolean get() = g.voice.connected
    override val voiceConnecting: Boolean get() = g.voice.connecting
    override val voiceMuted: Boolean get() = g.voice.muted
    override fun attachVoice(activity: Activity) = g.voice.attach(activity)
    override fun startVoice() = g.voice.start()
    override fun endVoice() = g.voice.end()
    override fun setVoiceMuted(muted: Boolean) = g.voice.setMuted(muted)
}
