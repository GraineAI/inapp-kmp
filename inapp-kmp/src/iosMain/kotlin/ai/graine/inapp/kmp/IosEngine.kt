package ai.graine.inapp.kmp

import ai.graine.inapp.kmp.objc.GraineObjC
import ai.graine.inapp.kmp.objc.GraineObjCQuickReply
import ai.graine.inapp.kmp.objc.GraineObjCRegistration
import ai.graine.inapp.kmp.objc.GraineObjCScheduledCall
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toNSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString

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
        publishTheme()
        publishChat()
        publishCalls()
        publishVoice()
    }

    private fun onEvent(name: String?, json: String?) {
        when (name) {
            null -> Unit
            "sessionReady" -> {
                Bridge.session(json)?.let { sink.session(it) }
                publishTheme()
            }
            // The design's tokens changed: the session arrived, or the device switched light and dark.
            "theme" -> publishTheme()
            else -> {
                // Before any session, an error is the session failing to load: a booking stops waiting for it.
                if (name == "error" && facade.sessionJSON == null) sink.sessionFailed(Json.obj(json)?.get("message") as? String ?: "")
                Bridge.event(name, json)?.let { sink.event(it) }
            }
        }
    }

    /** The facade's `themeJSON`, new after 0.8.0: with a GraineInApp from before it there are no tokens to read. */
    private fun publishTheme() {
        if (facade.respondsToSelector(THEME_JSON)) sink.theme(ThemeBridge.parse(facade.themeJSON))
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

    /** The facade's `currentVariablesJSON`, new after 0.8.0: an older GraineInApp has none to read, and answers empty. */
    override fun currentVariables(): Map<String, String> {
        if (!facade.respondsToSelector(CURRENT_VARIABLES)) return emptyMap()
        return Json.obj(facade.currentVariablesJSON).orEmpty().mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

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

    /**
     * The iOS SDK's own rule, read as it is now. A GraineInApp from before the booking sheet (0.7.0) has no
     * such selector, and an unknown selector would stop the app: it answers false instead.
     */
    override val bookingAvailable: Boolean get() = facade.respondsToSelector(BOOKING_AVAILABLE) && facade.bookingAvailable

    /**
     * The facade's `bookingUnavailableReason`, new in 0.14.0. A GraineInApp from before it has no word for
     * why: `not_loaded` until booking is available, as before configure().
     */
    override val bookingUnavailableReason: String?
        get() = when {
            facade.respondsToSelector(BOOKING_UNAVAILABLE_REASON) -> facade.bookingUnavailableReason?.takeIf { it.isNotBlank() }
            bookingAvailable -> null
            else -> GraineAgent.NOT_LOADED
        }

    /** The facade's own slots (ISO-8601 times), new in 0.14.0: a GraineInApp from before it offers none here. */
    override fun bookingSlots(isoDay: String): List<Instant> {
        if (!facade.respondsToSelector(BOOKING_SLOTS)) return emptyList()
        return Json.array(facade.bookingSlotsJSONForDay(isoDay)).orEmpty().mapNotNull { Bridge.instant(it) }
    }

    /**
     * The iOS SDK's booking sheet, over the key window's top view controller (the facade's
     * `presentBookingWithReason:completion:`). With a GraineInApp from before the sheet: `Unavailable`.
     */
    override fun presentBooking(reason: String?, done: (Result<GraineScheduledCall?>) -> Unit) {
        if (!facade.respondsToSelector(PRESENT_BOOKING)) {
            done(Result.failure(GraineException(GraineErrorCode.Unavailable, OLD_IOS_SDK)))
            return
        }
        facade.presentBookingWithReason(reason) { call -> done(Result.success(call?.let { scheduled(it) })) }
    }

    // ── the chat ──────────────────────────────────────────────────────────

    // Without a size, the plain open: what every facade answers.
    override fun chatOpen(size: GraineChatSize?) = if (size == null) facade.chat.open() else facade.chat.openWithSize(size.wire)
    override fun chatClose() = facade.chat.close()
    override fun chatSend(text: String) = facade.chat.send(text)
    override fun chatRetry() = facade.chat.retry()

    /** The facade's `clear`, new after 0.8.0: a GraineInApp from before it has nothing to clear with, and is left alone. */
    override fun chatClear() {
        if (facade.chat.respondsToSelector(CHAT_CLEAR)) facade.chat.clear()
    }

    // The upcoming calls ride the chat's snapshot; refreshing, cancelling and answering are new after 0.8.0 too.
    override fun chatRefreshUpcoming() {
        if (facade.chat.respondsToSelector(REFRESH_UPCOMING)) facade.chat.refreshUpcomingCalls()
    }

    override fun chatCancelUpcoming(id: String) {
        if (facade.chat.respondsToSelector(CANCEL_UPCOMING)) facade.chat.cancelUpcomingCall(id)
    }

    override fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String) {
        if (facade.chat.respondsToSelector(ANSWER_WIDGET)) facade.chat.answerWidgetWithId(widgetId, Bridge.json(data.takeIf { it.isNotEmpty() }), summary)
    }

    /**
     * The facade's `dismissWidgetWithId:`, new in 0.14.0: the agent is told,
     * quietly, that the user closed the component without choosing. A
     * GraineInApp from before it has none — the shared state still lets it go.
     */
    override fun dismissWidget(widgetId: String) {
        if (facade.chat.respondsToSelector(DISMISS_WIDGET)) facade.chat.dismissWidgetWithId(widgetId)
    }

    /** The facade's `setShown:`, new in 0.14.0: a GraineInApp from before it connects on open, as it always did. */
    override fun chatShown(visible: Boolean) {
        if (facade.chat.respondsToSelector(SET_SHOWN)) facade.chat.setShown(visible)
    }

    override fun chatNewConversation(done: (Result<Unit>) -> Unit) = facade.chat.newConversationWithCompletion { error ->
        if (error != null) done(Result.failure(failure(error))) else done(Result.success(Unit))
    }

    // Chat window v2's controls, new in the facade after 0.10: a GraineInApp from before them has no pin, no
    // rating and no paperclip to drive, and is left alone; its state (quick replies, the end, the rating, the
    // pin) rides the chat's snapshot, which an older facade simply leaves out.
    override fun chatPin(pin: GraineChatPin?) {
        if (!facade.chat.respondsToSelector(PIN)) return
        if (pin == null) facade.chat.clearPin() else facade.chat.pinWithTitle(pin.title, pin.subtitle, pin.imageUrl, pin.badge)
    }

    override fun chatRate(style: String, value: Int) {
        if (facade.chat.respondsToSelector(RATE)) facade.chat.rateWithStyle(style, value.toLong())
    }

    /** The facade's own reply object while the agent still offers it; otherwise its words, which is all sending one does. */
    override fun chatSendQuickReply(reply: GraineQuickReply) {
        val offered = if (facade.chat.respondsToSelector(SEND_QUICK_REPLY)) {
            facade.chat.quickReplies.filterIsInstance<GraineObjCQuickReply>().firstOrNull { it.label == reply.label && it.text == reply.text }
        } else {
            null
        }
        if (offered != null) facade.chat.sendQuickReply(offered) else facade.chat.send(reply.text)
    }

    override fun chatSetAttachHandler(handler: (() -> Unit)?) {
        if (facade.chat.respondsToSelector(SET_ATTACH)) facade.chat.setAttachHandler(handler)
    }

    private fun publishChat() {
        val chat = Bridge.chat(facade.chat.snapshotJSON)
        sink.chat(chat.turns, chat.status, chat.typing)
        sink.chatExtras(chat.extras)
        sink.upcomingCalls(chat.upcoming)
    }

    // ── native calls ──────────────────────────────────────────────────────

    override fun callsConfigure(config: GraineCallsConfig) {
        facade.calls.configureWithDisplayName(
            config.displayName, config.ringtone, config.ringTimeoutSeconds.toDouble(), config.includeInRecents,
        )
        // After it: the facade's configure starts from the SDK's defaults (auto), and the transport goes on top.
        if (config.callTransport != GraineCallTransport.AUTO) facade.calls.callTransport = config.callTransport.wire
    }

    override fun callsEnableVoIPPushes() = facade.calls.enableVoIPPushes()

    override fun callsStart(reason: String?, variables: Map<String, Any?>): String? =
        facade.calls.startCallJSONWithReason(reason, Json.write(variables))

    // New in the facade after 0.10.3: an older GraineInApp prepares each call at the tap, as it always did.
    override fun callsPrepare(reason: String?, variables: Map<String, Any?>) {
        if (facade.calls.respondsToSelector(PREPARE_CALL)) facade.calls.prepareCallJSONWithReason(reason, Json.write(variables))
    }

    override fun callsAnswer() = facade.calls.answer()
    override fun callsDecline() = facade.calls.decline()
    override fun callsEnd() = facade.calls.end()
    override fun callsSetMuted(muted: Boolean) = facade.calls.setMuted(muted)
    override fun callsSetSpeaker(on: Boolean) = facade.calls.setSpeaker(on)

    /** The facade's `speakerByDefault`, new after 0.13.2: a GraineInApp from before it keeps its own route. */
    override fun callsSetSpeakerByDefault(on: Boolean) {
        if (facade.calls.respondsToSelector(SET_SPEAKER_BY_DEFAULT)) facade.calls.speakerByDefault = on
    }
    override fun callsCallBack(): String? = facade.calls.callBack()
    override fun callsDismissMissed() = facade.calls.dismissMissedCall()

    override fun callsPermissions(): GraineCallPermissions = Bridge.permissions(facade.calls.callPermissionsJSON)

    override fun callsRequestPermissions(done: (GraineCallPermissions) -> Unit) =
        facade.calls.requestCallPermissionsJSONWithCompletion { json -> done(Bridge.permissions(json)) }

    override fun callsOpenSettings(item: GraineCallPermission): Boolean = facade.calls.openCallSettings(item.wire)

    override fun callsSetupProblems(): List<GraineCallSetupProblem> = Bridge.problems(facade.calls.callSetupProblemsJSON)

    private fun publishCalls() = sink.calls(Bridge.calls(facade.calls.snapshotJSON))

    // ── the voice bar's call ──────────────────────────────────────────────

    override fun voiceStart() = facade.voice.start()
    override fun voiceEnd() = facade.voice.end()
    override fun voiceSetMuted(muted: Boolean) = facade.voice.setMuted(muted)

    // The captions switch is new after 0.8.0: an older GraineInApp keeps its captions on, as it always did.
    override fun voiceSetCaptionsOn(on: Boolean) {
        if (facade.voice.respondsToSelector(SET_CAPTIONS_ON)) facade.voice.setCaptionsOn(on)
    }

    override fun voiceClearCaptions() {
        if (facade.voice.respondsToSelector(CLEAR_CAPTIONS)) facade.voice.clearCaptions()
    }

    private fun publishVoice() = sink.voice(Bridge.voice(facade.voice.snapshotJSON))

    companion object {
        // The booking sheet's selectors, new in the facade after 0.7.0 (asked for before each use).
        private val BOOKING_AVAILABLE = NSSelectorFromString("bookingAvailable")
        private val PRESENT_BOOKING = NSSelectorFromString("presentBookingWithReason:completion:")
        // New in the facade after 0.8.0 (asked for before each use).
        private val CHAT_CLEAR = NSSelectorFromString("clear")
        private val REFRESH_UPCOMING = NSSelectorFromString("refreshUpcomingCalls")
        private val CANCEL_UPCOMING = NSSelectorFromString("cancelUpcomingCall:")
        private val ANSWER_WIDGET = NSSelectorFromString("answerWidgetWithId:dataJSON:summary:")
        private val DISMISS_WIDGET = NSSelectorFromString("dismissWidgetWithId:")
        private val THEME_JSON = NSSelectorFromString("themeJSON")
        private val CURRENT_VARIABLES = NSSelectorFromString("currentVariablesJSON")
        private val SET_CAPTIONS_ON = NSSelectorFromString("setCaptionsOn:")
        private val CLEAR_CAPTIONS = NSSelectorFromString("clearCaptions")
        // New in the facade after 0.10.3 (asked for before each use).
        private val PREPARE_CALL = NSSelectorFromString("prepareCallJSONWithReason:variablesJSON:")
        // Chat window v2's, new in the facade after 0.10 (asked for before each use).
        private val PIN = NSSelectorFromString("pinWithTitle:subtitle:imageUrl:badge:")
        private val RATE = NSSelectorFromString("rateWithStyle:value:")
        private val SEND_QUICK_REPLY = NSSelectorFromString("sendQuickReply:")
        private val SET_ATTACH = NSSelectorFromString("setAttachHandler:")
        // New in the facade in 0.14.0 (asked for before each use).
        private val SET_SHOWN = NSSelectorFromString("setShown:")
        private val BOOKING_UNAVAILABLE_REASON = NSSelectorFromString("bookingUnavailableReason")
        private val BOOKING_SLOTS = NSSelectorFromString("bookingSlotsJSONForDay:")
        private val SET_SPEAKER_BY_DEFAULT = NSSelectorFromString("setSpeakerByDefault:")
        private val OLD_IOS_SDK =
            "This app links a GraineInApp without the booking sheet. Link GraineInApp ${GraineKmpVersion.NAME}, the version of this library."

        /** A call the facade hands over as its object, in the shared type. */
        internal fun scheduled(c: GraineObjCScheduledCall): GraineScheduledCall = GraineScheduledCall(
            id = c.identifier,
            at = c.at?.toKotlinInstant(),
            status = GraineCallStatus.from(c.status),
            source = GraineCallSource.from(c.source),
            reason = c.reason,
        )

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
