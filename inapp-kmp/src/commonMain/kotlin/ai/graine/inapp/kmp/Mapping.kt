package ai.graine.inapp.kmp

import kotlinx.datetime.Instant

/*
 * HOW WHAT A PLATFORM SDK REPORTS BECOMES THE SHARED TYPES — the rules both
 * platforms share, kept here so they are proven once, in commonTest.
 */

/** A session from what a platform SDK read off the server, with WIRE.md's defaults for what it did not say. */
internal fun sessionOf(
    agentId: String,
    memoryEnabled: Boolean?,
    identityField: String?,
    devicesPerUser: Int?,
    hosted: Boolean?,
    platforms: List<String>,
    ringTimeoutSeconds: Int?,
    callName: String?,
    usersCanBook: Boolean?,
    maxDaysAhead: Int?,
    minLeadMinutes: Int?,
    maxPendingPerUser: Int?,
    declaredActions: Set<String>,
): GraineSession {
    val defaults = GraineSchedulingRules()
    return GraineSession(
        agentId = agentId,
        memoryEnabled = memoryEnabled,
        identityField = identityField?.trim()?.takeIf { it.isNotEmpty() } ?: "user_id",
        devicesPerUser = devicesPerUser?.takeIf { it > 0 } ?: 2,
        schedulingRules = GraineSchedulingRules(
            usersCanBook = usersCanBook ?: false,
            minLeadMinutes = minLeadMinutes?.takeIf { it >= 0 } ?: defaults.minLeadMinutes,
            maxDaysAhead = maxDaysAhead?.takeIf { it > 0 } ?: defaults.maxDaysAhead,
            maxPendingPerUser = maxPendingPerUser?.takeIf { it > 0 } ?: defaults.maxPendingPerUser,
            ringTimeoutSeconds = ringTimeoutSeconds?.takeIf { it > 0 } ?: defaults.ringTimeoutSeconds,
            callName = callName?.trim()?.takeIf { it.isNotEmpty() },
            hostedRinging = hosted ?: false,
            platforms = platforms.mapNotNull { p -> GrainePlatform.entries.firstOrNull { it.wire == p.trim().lowercase() } }.distinct(),
        ),
        declaredActions = declaredActions,
    )
}

/**
 * A call's outcome as WIRE.md has it, from what a native call reports: why
 * (`busy` or `no_answer` for a miss, who ended a call that connected, why one
 * never did), how long a call that connected lasted, in whole seconds, and
 * the agent it was with, when known.
 */
internal fun outcomeOf(
    callId: String,
    kind: GraineCallOutcome.Kind,
    endReason: String? = null,
    error: String? = null,
    busy: Boolean = false,
    connectedMs: Long? = null,
    hosted: Boolean = false,
    agent: String? = null,
): GraineCallOutcome = when (kind) {
    GraineCallOutcome.Kind.MISSED -> GraineCallOutcome(callId, kind, if (busy) "busy" else "no_answer", null, hosted, agent)
    GraineCallOutcome.Kind.ENDED -> GraineCallOutcome(
        callId, kind, endReason?.takeIf { it.isNotBlank() } ?: "local",
        connectedMs?.let { ((maxOf(0L, it) + 500) / 1000).toInt() }, hosted, agent,
    )
    GraineCallOutcome.Kind.FAILED -> GraineCallOutcome(callId, kind, (error?.takeIf { it.isNotBlank() } ?: "failed").take(200), null, hosted, agent)
    else -> GraineCallOutcome(callId, kind, null, null, hosted, agent)
}

/** A component from the frame the conversation sent; null without an id. */
internal fun componentOf(frame: Map<String, Any?>): GraineComponent? {
    val id = listOf("widget_id", "id").firstNotNullOfOrNull { (frame[it] as? String)?.takeIf { s -> s.isNotBlank() } } ?: return null
    return GraineComponent(
        id = id,
        kind = (frame["kind"] as? String)?.takeIf { it.isNotBlank() } ?: (frame["type"] as? String)?.takeIf { it.isNotBlank() && it != "widget" },
        title = (frame["title"] as? String)?.takeIf { it.isNotBlank() },
        payload = frame,
    )
}

/** THE CHAT'S CONNECTION AS ONE WORD, from a platform that reports flags (Android). */
internal object ChatInference {
    /**
     * The connection's word, from flags. `reconnecting` is the platform's own:
     * a drop — or a first try that failed — being made again, as the iOS SDK
     * says it.
     */
    fun status(connected: Boolean, connecting: Boolean, failed: Boolean, reconnecting: Boolean): GraineChatStatus = when {
        connected -> GraineChatStatus.ONLINE
        connecting -> if (reconnecting) GraineChatStatus.RECONNECTING else GraineChatStatus.CONNECTING
        failed -> GraineChatStatus.OFFLINE
        else -> GraineChatStatus.IDLE
    }
}

/**
 * THE BRIDGE'S JSON — how the iOS SDK's Objective-C facade hands the shared
 * code what Foundation values cannot carry exactly (GraineObjC's `…JSON`
 * methods and observers, sdk/ios/Sources/GraineInApp/ObjC). Times are epoch
 * milliseconds; a field that is absent or null reads as "not said". Read
 * leniently: a field of the wrong type is skipped, never a crash.
 */
internal object Bridge {

    // ── shared code → platform ────────────────────────────────────────────

    fun screen(screen: GraineScreen?): String? = screen?.let { s ->
        val o = LinkedHashMap<String, Any?>()
        o["screen"] = s.screen
        s.title?.let { o["title"] = it }
        s.journey?.let { j ->
            o["journey"] = LinkedHashMap<String, Any?>().apply {
                put("name", j.name)
                j.step?.let { put("step", it) }
                j.of?.let { put("of", it) }
                j.next?.let { put("next", it) }
            }
        }
        o["fields"] = s.fields.map { fieldMap(it) }
        s.error?.let { o["error"] = it }
        if (s.data.isNotEmpty()) o["data"] = s.data
        s.availableActions?.let { o["availableActions"] = it }
        Json.write(o)
    }

    fun field(field: GraineField): String = Json.write(fieldMap(field))

    private fun fieldMap(f: GraineField): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("name", f.name)
        f.label?.let { put("label", it) }
        f.value?.let { put("value", it) }
        f.status?.let { put("status", it.wire) }
        f.error?.let { put("error", it) }
        if (f.optional) put("optional", true)
    }

    /** A map as JSON; null stays null (sign-out, clear all). */
    fun json(map: Map<String, Any?>?): String? = map?.let { Json.write(it) }

    // ── platform → shared code ────────────────────────────────────────────

    /** An error as the facade reports it: its code word, its sentence, and `Retry-After` when there was one. */
    fun error(code: String?, message: String?, retryAfterSeconds: Int? = null): GraineException =
        GraineException(
            GraineErrorCode.from(code),
            message?.takeIf { it.isNotBlank() } ?: "Graine could not complete the request.",
            retryAfterSeconds?.takeIf { it > 0 },
        )

    fun instant(v: Any?): Instant? = when (v) {
        is Long -> Instant.fromEpochMilliseconds(v)
        is Int -> Instant.fromEpochMilliseconds(v.toLong())
        is Double -> if (v.isFinite()) Instant.fromEpochMilliseconds(v.toLong()) else null
        is String -> v.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        else -> null
    }

    fun memories(json: String?): List<GraineMemory> =
        Json.array(json).orEmpty().mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            val id = str(o["id"]) ?: return@mapNotNull null
            val text = o["text"] as? String ?: return@mapNotNull null
            GraineMemory(id, text, instant(o["createdAt"]), instant(o["updatedAt"]))
        }

    fun scheduledCall(json: String?): GraineScheduledCall? = Json.obj(json)?.let { scheduledCall(it) }

    fun scheduledCall(o: Map<*, *>): GraineScheduledCall? {
        val id = str(o["id"]) ?: return null
        return GraineScheduledCall(
            id = id,
            at = instant(o["at"]),
            status = GraineCallStatus.from(o["status"] as? String),
            source = GraineCallSource.from(o["source"] as? String),
            reason = str(o["reason"]),
        )
    }

    fun scheduledCalls(json: String?): List<GraineScheduledCall> =
        Json.array(json).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { o -> scheduledCall(o) } }

    fun session(json: String?): GraineSession? {
        val o = Json.obj(json) ?: return null
        val identity = o["identity"] as? Map<*, *>
        val calls = o["calls"] as? Map<*, *>
        return sessionOf(
            agentId = o["agentId"] as? String ?: "",
            memoryEnabled = o["memoryEnabled"] as? Boolean,
            identityField = identity?.get("field") as? String,
            devicesPerUser = int(identity?.get("devicesPerUser")),
            hosted = calls?.get("hosted") as? Boolean,
            platforms = (calls?.get("platforms") as? List<*>).orEmpty().filterIsInstance<String>(),
            ringTimeoutSeconds = int(calls?.get("ringTimeoutSeconds")),
            callName = calls?.get("callName") as? String,
            usersCanBook = calls?.get("usersCanBook") as? Boolean,
            maxDaysAhead = int(calls?.get("maxDaysAhead")),
            minLeadMinutes = int(calls?.get("minLeadMinutes")),
            maxPendingPerUser = int(calls?.get("maxPendingPerUser")),
            declaredActions = (o["declaredActions"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
        )
    }

    /**
     * The chat: its turns, its connection's word, whether the agent is typing,
     * its upcoming calls and the chat window v2 state (none of either from an
     * older facade).
     */
    data class Chat(
        val turns: List<GraineChatTurn>,
        val status: GraineChatStatus,
        val typing: Boolean,
        val upcoming: List<GraineScheduledCall> = emptyList(),
        val extras: ChatExtras = ChatExtras(),
    )

    fun chat(json: String?): Chat {
        val o = Json.obj(json) ?: return Chat(emptyList(), GraineChatStatus.IDLE, false)
        val status = when ((o["status"] as? String)?.lowercase()) {
            "connecting" -> GraineChatStatus.CONNECTING
            "online" -> GraineChatStatus.ONLINE
            "reconnecting" -> GraineChatStatus.RECONNECTING
            "offline" -> GraineChatStatus.OFFLINE
            else -> GraineChatStatus.IDLE
        }
        val turns = (o["turns"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { t -> turn(t) } }
        val upcoming = (o["upcomingCalls"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { c -> scheduledCall(c) } }
        return Chat(turns, status, o["typing"] == true, upcoming, chatExtras(o))
    }

    /**
     * The snapshot's `quickReplies` ([{label, text}]), `ended`, `rating`
     * ({value, scale, style} or null) and `pin` ({title, subtitle, imageUrl,
     * badge} or null). A reply needs its label (no text: the label), a rating
     * its numbers, a pin its title.
     */
    fun chatExtras(o: Map<*, *>): ChatExtras = ChatExtras(
        quickReplies = (o["quickReplies"] as? List<*>).orEmpty().mapNotNull { r ->
            val reply = r as? Map<*, *> ?: return@mapNotNull null
            val label = str(reply["label"]) ?: return@mapNotNull null
            GraineQuickReply(label, str(reply["text"]) ?: label)
        },
        ended = o["ended"] == true,
        rating = (o["rating"] as? Map<*, *>)?.let { r ->
            val value = int(r["value"]) ?: return@let null
            val scale = int(r["scale"]) ?: return@let null
            GraineChatRating(value, scale, str(r["style"]) ?: "thumbs")
        },
        pinned = (o["pin"] as? Map<*, *>)?.let { p ->
            val title = str(p["title"]) ?: return@let null
            GraineChatPin(title, str(p["subtitle"]), str(p["imageUrl"]), str(p["badge"]))
        },
    )

    fun turn(o: Map<*, *>): GraineChatTurn? {
        val id = when (val v = o["id"]) {
            is String -> v.takeIf { it.isNotBlank() }
            is Long, is Int -> v.toString()
            is Double -> v.toLong().toString()
            else -> null
        } ?: return null
        val user = when ((o["role"] as? String)?.lowercase()) {
            "user", "customer" -> true
            "agent" -> false
            else -> return null
        }
        return GraineChatTurn(
            id = id,
            role = if (user) GraineRole.USER else GraineRole.AGENT,
            text = o["text"] as? String ?: "",
            at = instant(o["at"]) ?: Instant.fromEpochMilliseconds(0),
            delivery = if (!user) null else when ((o["delivery"] as? String)?.lowercase()) {
                "pending", "sending" -> GraineDelivery.SENDING
                "failed" -> GraineDelivery.FAILED
                else -> GraineDelivery.SENT
            },
            proactive = !user && o["proactive"] == true,
            component = (o["component"] as? Map<*, *>)?.let { componentOf(stringKeys(it)) },
            live = o["live"] == true,
            // Absent from an older facade: a turn said just now, in the chat. A channel other than voice is chat.
            restored = o["restored"] == true,
            channel = if (o["channel"] == GraineChatChannel.VOICE.wire) GraineChatChannel.VOICE else GraineChatChannel.CHAT,
        )
    }

    fun calls(json: String?): GraineCallInfo {
        val o = Json.obj(json) ?: return GraineCallInfo()
        val state = when ((o["state"] as? String)?.lowercase()) {
            "ringing" -> GraineCallState.RINGING
            "connecting" -> GraineCallState.CONNECTING
            "active" -> GraineCallState.ACTIVE
            "ended" -> GraineCallState.ENDED
            else -> GraineCallState.IDLE
        }
        val caption = (o["caption"] as? Map<*, *>)?.let { c ->
            val text = c["text"] as? String ?: return@let null
            GraineCaption(if (c["role"] == "agent") GraineRole.AGENT else GraineRole.USER, text, c["live"] == true)
        }
        val missed = (o["missedCall"] as? Map<*, *>)?.let { m ->
            val id = str(m["callId"]) ?: return@let null
            GraineMissedCall(id, str(m["callerName"]), str(m["reason"]), m["hosted"] == true, instant(m["at"]) ?: Instant.fromEpochMilliseconds(0))
        }
        return GraineCallInfo(
            state = state,
            callId = str(o["callId"]),
            callerName = str(o["callerName"]),
            incoming = o["direction"] == "incoming",
            hosted = o["hosted"] == true,
            muted = o["muted"] == true,
            speakerOn = o["speakerOn"] == true,
            agentSpeaking = o["agentSpeaking"] == true,
            caption = caption,
            missedCall = missed,
            agent = str(o["agent"]),
            transport = GraineCallTransport.ofCall(str(o["transport"])),
            transportFallbackReason = str(o["transportFallbackReason"]),
            // Only a true is a recovering call: an iOS SDK that predates it says nothing.
            reconnecting = o["reconnecting"] == true,
        )
    }

    fun outcome(json: String?): GraineCallOutcome? {
        val o = Json.obj(json) ?: return null
        val id = str(o["callId"]) ?: return null
        val kind = GraineCallOutcome.Kind.entries.firstOrNull { it.wire == (o["outcome"] as? String)?.lowercase() } ?: return null
        return GraineCallOutcome(id, kind, str(o["reason"]), int(o["durationSeconds"]), o["hosted"] == true, str(o["agent"]))
    }

    /** `{microphone, notifications, fullScreen}` from the iOS facade; a word it does not know (or nothing) is DENIED. */
    fun permissions(json: String?): GraineCallPermissions {
        val o = Json.obj(json).orEmpty()
        return GraineCallPermissions(
            microphone = GraineCallPermissionStatus.of(o["microphone"] as? String),
            notifications = GraineCallPermissionStatus.of(o["notifications"] as? String),
            fullScreen = GraineCallPermissionStatus.of(o["fullScreen"] as? String),
        )
    }

    /** `[{code, message}]` from the iOS facade; an entry without a code is dropped. */
    fun problems(json: String?): List<GraineCallSetupProblem> =
        Json.array(json).orEmpty().mapNotNull { p ->
            val o = p as? Map<*, *> ?: return@mapNotNull null
            val code = str(o["code"]) ?: return@mapNotNull null
            GraineCallSetupProblem(code, o["message"] as? String ?: "")
        }

    fun incoming(json: String?): GraineIncomingCall? {
        val o = Json.obj(json) ?: return null
        val id = str(o["callId"]) ?: return null
        return GraineIncomingCall(id, str(o["callerName"]), str(o["reason"]), o["hosted"] == true, str(o["agent"]))
    }

    fun voice(json: String?): GraineVoiceInfo {
        val o = Json.obj(json) ?: return GraineVoiceInfo()
        val captions = (o["captions"] as? List<*>).orEmpty().mapNotNull { c ->
            val line = c as? Map<*, *> ?: return@mapNotNull null
            val text = line["text"] as? String ?: return@mapNotNull null
            GraineCaption(if (line["role"] == "agent") GraineRole.AGENT else GraineRole.USER, text, line["live"] == true)
        }
        return GraineVoiceInfo(
            connected = o["connected"] == true,
            connecting = o["connecting"] == true,
            muted = o["muted"] == true,
            // A facade from before the switch says nothing: the captions are on.
            captionsOn = o["captionsOn"] != false,
            captions = captions,
        )
    }

    /** An event by its name; null for the session (reported as a state) and for any name this build does not know. */
    fun event(name: String, json: String?): GraineEvent? {
        val o = Json.obj(json).orEmpty()
        return when (name) {
            "caption" -> (o["text"] as? String)?.let { GraineEvent.Caption(if (o["role"] == "agent") GraineRole.AGENT else GraineRole.USER, it, o["live"] == true) }
            "voiceEnded" -> GraineEvent.VoiceEnded
            "widget" -> (o["widget"] as? Map<*, *>)?.let { componentOf(stringKeys(it)) }?.let { GraineEvent.Component(it) }
            "widgetPending" -> GraineEvent.ComponentPending((o["hint"] as? Map<*, *>)?.let { stringKeys(it) }.orEmpty())
            "chatMessage" -> (o["turn"] as? Map<*, *>)?.let { turn(it) }?.let { GraineEvent.ChatMessage(it) }
            "actionRequested" -> str(o["name"])?.let { GraineEvent.ActionRequested(it) }
            "actionCompleted" -> str(o["name"])?.let { n ->
                val status = GraineActionResult.Status.entries.firstOrNull { it.wire == o["status"] } ?: GraineActionResult.Status.ERROR
                GraineEvent.ActionCompleted(n, GraineActionResult(status, o["message"] as? String ?: ""))
            }
            "friction" -> str(o["name"])?.let { GraineEvent.Friction(it, str(o["screen"]), o["detail"] as? String ?: "") }
            "micDenied" -> GraineEvent.MicrophoneDenied(o["reason"] as? String ?: "")
            "error" -> GraineEvent.Error(o["message"] as? String ?: "")
            else -> null
        }
    }

    private fun str(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }

    private fun int(v: Any?): Int? = when (v) {
        is Long -> v.toInt()
        is Int -> v
        is Double -> if (v.isFinite()) v.toInt() else null
        else -> null
    }

    private fun stringKeys(m: Map<*, *>): Map<String, Any?> = m.entries.associate { (k, v) -> k.toString() to v }
}
