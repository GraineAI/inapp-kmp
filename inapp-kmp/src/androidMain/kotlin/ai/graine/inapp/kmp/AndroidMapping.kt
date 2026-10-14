package ai.graine.inapp.kmp

import ai.graine.inapp.ActionResult
import ai.graine.inapp.GraineCallOutcome as NativeOutcome
import ai.graine.inapp.GraineCallState as NativeCallState
import ai.graine.inapp.GraineCallStatus as NativeCallStatus
import ai.graine.inapp.GraineCallsConfig as NativeCallsConfig
import ai.graine.inapp.GraineChatSize as NativeChatSize
import ai.graine.inapp.GraineChatTurn as NativeChatTurn
import ai.graine.inapp.GraineConfig as NativeConfig
import ai.graine.inapp.GraineError
import ai.graine.inapp.GraineField as NativeField
import ai.graine.inapp.GraineMemory as NativeMemory
import ai.graine.inapp.GraineScheduledCall as NativeScheduledCall
import ai.graine.inapp.GraineScreen as NativeScreen
import ai.graine.inapp.GraineWidget
import ai.graine.inapp.Role
import ai.graine.inapp.SessionConfig
import ai.graine.inapp.Transport
import kotlinx.datetime.Instant
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/*
 * THE ANDROID SDK'S TYPES ⇄ THE SHARED ONES — pure functions, proven on the
 * JVM with the Android SDK's real classes (androidUnitTest).
 */
internal object AndroidMapping {

    /** The shared configuration as the Android SDK's, field for field. */
    fun config(c: GraineConfig): NativeConfig = NativeConfig(
        baseUrl = c.baseUrl,
        publishableKey = c.publishableKey,
        variables = c.variables,
        transport = c.transport?.let { t -> if (t == GraineTransport.WEBRTC) Transport.WEBRTC else Transport.WEBSOCKET },
        appVersion = c.appVersion,
        endOnBackground = c.endOnBackground,
        debug = c.debug,
        captureTaps = c.captureTaps,
        chatSize = c.chatSize?.let { chatSize(it) },
    )

    /** The same three sizes, by name. */
    fun chatSize(s: GraineChatSize): NativeChatSize = NativeChatSize.valueOf(s.name)

    fun screen(s: GraineScreen): NativeScreen = NativeScreen(
        screen = s.screen,
        title = s.title,
        journey = s.journey?.let { NativeScreen.Journey(it.name, it.step, it.of, it.next) },
        fields = s.fields.map { field(it) },
        error = s.error,
        data = s.data,
        availableActions = s.availableActions,
    )

    fun field(f: GraineField): NativeField = NativeField(
        name = f.name,
        label = f.label,
        value = f.value,
        status = f.status?.let { st -> NativeField.Status.entries.first { it.wire == st.wire } },
        error = f.error,
        optional = f.optional,
    )

    fun result(r: GraineActionResult): ActionResult = when (r.status) {
        GraineActionResult.Status.OK -> ActionResult.ok(r.message, r.data)
        GraineActionResult.Status.REFUSED -> ActionResult(ActionResult.Status.REFUSED, r.message, r.data)
        GraineActionResult.Status.ERROR -> ActionResult(ActionResult.Status.ERROR, r.message, r.data)
    }

    fun result(r: ActionResult): GraineActionResult = GraineActionResult(
        status = GraineActionResult.Status.entries.first { it.wire == r.status.wire },
        message = r.message,
        data = r.data,
    )

    /** An action's arguments as the shared code reads them on both platforms — through JSON, so the shapes are the same. */
    fun args(o: JSONObject?): Map<String, Any?> = Json.obj(o?.toString()) ?: emptyMap()

    /** A component frame (or any JSONObject) as a map. */
    fun map(o: JSONObject?): Map<String, Any?> = args(o)

    fun instant(d: Date?): Instant? = d?.let { Instant.fromEpochMilliseconds(it.time) }

    fun memory(m: NativeMemory): GraineMemory = GraineMemory(m.id, m.text, instant(m.createdAt), instant(m.updatedAt))

    /** The Android SDK's booked call, word for word: the shared types read the same wire words. */
    fun scheduledCall(c: NativeScheduledCall): GraineScheduledCall = GraineScheduledCall(
        id = c.id,
        at = instant(c.at),
        status = callStatus(c.status),
        source = c.source?.let { GraineCallSource.from(it.wire) },
        reason = c.reason,
    )

    fun callStatus(s: NativeCallStatus): GraineCallStatus = GraineCallStatus.from(s.wire)

    /** The Android SDK's error as the shared one: its code's word, its sentence, and a rate limit's wait. */
    fun error(e: GraineError): GraineException = GraineException(GraineErrorCode.from(e.code.wire), e.message, e.retryAfterSeconds)

    fun session(s: SessionConfig): GraineSession = s.schedulingRules.let { r ->
        sessionOf(
            agentId = s.agentId,
            memoryEnabled = s.memoryEnabled,
            identityField = s.identity?.field,
            devicesPerUser = s.identity?.devicesPerUser,
            hosted = r.hostedRinging,
            platforms = r.platforms,
            ringTimeoutSeconds = r.ringTimeoutSeconds,
            callName = r.callName,
            usersCanBook = r.usersCanBook,
            maxDaysAhead = r.maxDaysAhead,
            minLeadMinutes = r.minLeadMinutes,
            maxPendingPerUser = r.maxPendingPerUser,
            declaredActions = s.declaredActions,
        )
    }

    /**
     * A chat turn, one for one: the user's carry where they are, the agent's
     * the component drawn with them, and a turn from an earlier conversation
     * that it was restored and where it was said.
     */
    fun turn(t: NativeChatTurn): GraineChatTurn {
        val user = t.role == Role.CUSTOMER
        return GraineChatTurn(
            id = t.id,
            role = if (user) GraineRole.USER else GraineRole.AGENT,
            text = t.text,
            at = Instant.fromEpochMilliseconds(t.at.time),
            delivery = if (!user) null else when (t.delivery) {
                NativeChatTurn.Delivery.SENDING -> GraineDelivery.SENDING
                NativeChatTurn.Delivery.SENT -> GraineDelivery.SENT
                NativeChatTurn.Delivery.FAILED -> GraineDelivery.FAILED
            },
            proactive = !user && t.proactive,
            component = t.component?.let { component(it) },
            live = t.live,
            restored = t.restored,
            channel = if (t.channel == NativeChatTurn.Channel.VOICE) GraineChatChannel.VOICE else GraineChatChannel.CHAT,
        )
    }

    /** A component the Android SDK adopted, as the shared one: what it is, and all of it as plain values. */
    fun component(w: GraineWidget): GraineComponent {
        val data = map(w.data)
        return GraineComponent(
            id = w.id,
            kind = w.kind.takeIf { it.isNotBlank() },
            title = (data["title"] as? String)?.takeIf { it.isNotBlank() },
            payload = linkedMapOf("widget_id" to w.id, "kind" to w.kind, "data" to data, "fallback_text" to w.fallbackText),
        )
    }

    fun role(r: Role): GraineRole = if (r == Role.AGENT) GraineRole.AGENT else GraineRole.USER

    fun callState(s: NativeCallState): GraineCallState = GraineCallState.valueOf(s.name)

    fun outcomeKind(o: NativeOutcome): GraineCallOutcome.Kind = GraineCallOutcome.Kind.valueOf(o.kind.name)

    /**
     * The shared calls configuration as the Android SDK's. An empty display
     * name is the app's own name ([appName]) — the agent's call name still wins
     * for a call Graine rang, as it does on iOS.
     */
    fun callsConfig(c: GraineCallsConfig, appName: String?, ringtone: (String) -> android.net.Uri?): NativeCallsConfig = NativeCallsConfig(
        displayName = c.displayName.takeIf { it.isNotBlank() } ?: appName?.takeIf { it.isNotBlank() } ?: "Assistant",
        smallIcon = c.smallIcon,
        ringtone = c.ringtone?.takeIf { it.isNotBlank() }?.let(ringtone),
        ringTimeoutMs = c.ringTimeoutSeconds.coerceIn(5, 300) * 1000L,
    )

    /** A JSON value from the Android SDK (org.json) as plain Kotlin values, for anything not read through [args]. */
    fun plain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> map(v)
        is JSONArray -> Json.array(v.toString())
        else -> v
    }
}
