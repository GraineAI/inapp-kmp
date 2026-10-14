package ai.graine.inapp.kmp

import ai.graine.inapp.kmp.objc.GraineObjC
import ai.graine.inapp.kmp.objc.GraineObjCRegistration
import kotlinx.datetime.Instant
import kotlinx.datetime.toNSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber

/**
 * THE SHARED API ON iOS — every call delegated to the iOS SDK through its
 * Objective-C facade (GraineObjC). What Foundation values cannot carry exactly
 * crosses as JSON ([Bridge]); the facade's blocks arrive on the main thread.
 */
internal class IosEngine(private val facade: GraineObjC) : GraineEngine {
    private lateinit var sink: EngineSink
    /** Kept for as long as the engine lives: the facade drops an observer whose registration is let go. */
    private val registrations = mutableListOf<GraineObjCRegistration>()

    override val installId: String get() = facade.installId
    override val memoryEnabled: Boolean get() = facade.memoryChoice

    override fun start(sink: EngineSink) {
        this.sink = sink
        registrations += facade.observeEvents { name, json -> onEvent(name, json) }
        registrations += facade.chat.observe { publishChat() }
        registrations += facade.calls.observe { publishCalls() }
        registrations += facade.calls.observeOutcomes { json -> Bridge.outcome(json)?.let { sink.callOutcome(it) } }
        registrations += facade.calls.observeIncoming { json -> Bridge.incoming(json)?.let { sink.incomingCall(it) } }
        registrations += facade.voice.observe { publishVoice() }
        // The iOS SDK may have loaded its session already (the app configured it from Swift first).
        Bridge.session(facade.sessionJSON)?.let { sink.session(it) }
        publishChat()
        publishCalls()
        publishVoice()
    }

    private fun onEvent(name: String?, json: String?) {
        when (name) {
            null -> Unit
            "sessionReady" -> Bridge.session(json)?.let { sink.session(it) }
            else -> {
                // Before any session, an error is the session failing to load: a booking stops waiting for it.
                if (name == "error" && facade.sessionJSON == null) sink.sessionFailed(Json.obj(json)?.get("message") as? String ?: "")
                Bridge.event(name, json)?.let { sink.event(it) }
            }
        }
    }

    // ── who, and memory ───────────────────────────────────────────────────

    override fun identify(traits: Map<String, Any?>?, memory: Boolean?) {
        val json = Bridge.json(traits)
        if (memory == null) facade.identifyJSON(json) else facade.identifyJSON(json, memory)
    }

    override fun setMemory(enabled: Boolean) = facade.setMemory(enabled)

    override fun forgetMemory(done: (Result<Int>) -> Unit) = facade.forgetMemoryWithCompletion { deleted, error ->
        if (error != null) done(Result.failure(failure(error))) else done(Result.success(deleted.toInt()))
    }

    override fun memories(done: (Result<List<GraineMemory>>) -> Unit) = facade.memoriesJSONWithCompletion { json, error ->
        if (error != null) done(Result.failure(failure(error))) else done(Result.success(Bridge.memories(json)))
    }

    override fun setVariables(values: Map<String, Any?>?) = facade.setVariablesJSON(Bridge.json(values))

    // ── the screen ────────────────────────────────────────────────────────

    override fun setScreen(screen: GraineScreen?) = facade.setScreenJSON(Bridge.screen(screen))
    override fun updateField(field: GraineField) = facade.updateFieldJSON(Bridge.field(field))
    override fun removeField(name: String) = facade.removeField(name)
    override fun clearScreen(screenId: String?) = facade.clearScreen(screenId)
    override fun track(name: String, data: Map<String, Any?>) = facade.trackEvent(name, Bridge.json(data.takeIf { it.isNotEmpty() }))
    override fun tap(name: String) = facade.tap(name)
    override fun noteInteraction() = facade.noteInteraction()

    override fun registerAction(name: String, handler: (args: Map<String, Any?>, answer: (GraineActionResult) -> Unit) -> Unit): () -> Unit {
        val registration = facade.registerJSONAction(name) { argsJson, answer ->
            handler(Json.obj(argsJson) ?: emptyMap()) { result ->
                answer?.invoke(result.status.wire, result.message, if (result.data.isEmpty()) null else Json.write(result.data))
            }
        }
        return { registration.unregister() }
    }

    // ── scheduled calls ───────────────────────────────────────────────────

    override fun scheduleCall(at: Instant, reason: String?, done: (Result<GraineScheduledCall>) -> Unit) =
        facade.scheduleCallJSONAt(at.toNSDate(), reason) { json, error ->
            val call = if (error == null) Bridge.scheduledCall(json) else null
            when {
                error != null -> done(Result.failure(failure(error)))
                call != null -> done(Result.success(call))
                else -> done(Result.failure(GraineException(GraineErrorCode.Unavailable, "The booking was accepted but not described.")))
            }
        }

    override fun scheduledCalls(done: (Result<List<GraineScheduledCall>>) -> Unit) = facade.scheduledCallsJSONWithCompletion { json, error ->
        if (error != null) done(Result.failure(failure(error))) else done(Result.success(Bridge.scheduledCalls(json)))
    }

    override fun cancelScheduledCall(id: String, done: (Result<GraineCallStatus>) -> Unit) = facade.cancelScheduledCall(id) { status, error ->
        if (error != null) done(Result.failure(failure(error))) else done(Result.success(GraineCallStatus.from(status ?: "cancelled")))
    }

    // ── the chat ──────────────────────────────────────────────────────────

    // Without a size, the plain open: what every facade answers.
    override fun chatOpen(size: GraineChatSize?) = if (size == null) facade.chat.open() else facade.chat.openWithSize(size.wire)
    override fun chatClose() = facade.chat.close()
    override fun chatSend(text: String) = facade.chat.send(text)
    override fun chatRetry() = facade.chat.retry()

    override fun chatNewConversation(done: (Result<Unit>) -> Unit) = facade.chat.newConversationWithCompletion { error ->
        if (error != null) done(Result.failure(failure(error))) else done(Result.success(Unit))
    }

    private fun publishChat() {
        val chat = Bridge.chat(facade.chat.snapshotJSON)
        sink.chat(chat.turns, chat.status, chat.typing)
    }

    // ── native calls ──────────────────────────────────────────────────────

    override fun callsConfigure(config: GraineCallsConfig) = facade.calls.configureWithDisplayName(
        config.displayName, config.ringtone, config.ringTimeoutSeconds.toDouble(), config.includeInRecents,
    )

    override fun callsEnableVoIPPushes() = facade.calls.enableVoIPPushes()

    override fun callsStart(reason: String?, variables: Map<String, Any?>): String? =
        facade.calls.startCallJSONWithReason(reason, Json.write(variables))

    override fun callsAnswer() = facade.calls.answer()
    override fun callsDecline() = facade.calls.decline()
    override fun callsEnd() = facade.calls.end()
    override fun callsSetMuted(muted: Boolean) = facade.calls.setMuted(muted)
    override fun callsSetSpeaker(on: Boolean) = facade.calls.setSpeaker(on)
    override fun callsCallBack(): String? = facade.calls.callBack()
    override fun callsDismissMissed() = facade.calls.dismissMissedCall()

    private fun publishCalls() = sink.calls(Bridge.calls(facade.calls.snapshotJSON))

    // ── the voice bar's call ──────────────────────────────────────────────

    override fun voiceStart() = facade.voice.start()
    override fun voiceEnd() = facade.voice.end()
    override fun voiceSetMuted(muted: Boolean) = facade.voice.setMuted(muted)

    private fun publishVoice() = sink.voice(Bridge.voice(facade.voice.snapshotJSON))

    companion object {
        /** The facade's NSError: its code word in `userInfo["code"]`, its sentence as the description, a rate limit's wait in `userInfo["retryAfterSeconds"]`. */
        internal fun failure(error: NSError): GraineException =
            Bridge.error(error.userInfo["code"] as? String, error.localizedDescription, whole(error.userInfo["retryAfterSeconds"]))

        /** A number from Foundation: an NSNumber made in Swift stays one; one made in Kotlin comes back as a Kotlin number. */
        private fun whole(v: Any?): Int? = when (v) {
            is Number -> v.toInt()
            is NSNumber -> v.longValue.toInt()
            else -> null
        }
    }
}
