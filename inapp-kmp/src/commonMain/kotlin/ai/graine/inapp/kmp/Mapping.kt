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
    callingWindow: GraineCallingWindow? = null,
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
            callingWindow = callingWindow,
        ),
        declaredActions = declaredActions,
    )
}

private val HHMM = Regex("([01]\\d|2[0-3]):([0-5]\\d)")

/**
 * The calling window a platform SDK reported (`{start, end, days}`), or null —
 * no window — when there is none or it cannot be read: times "HH:MM" with
 * start before end; days a list whose whole numbers 0–6 are kept (each once,
 * in order), every day when it is missing, and no window when none is left.
 * The rule every Graine SDK reads the session's window with.
 */
internal fun callingWindowOf(raw: Any?): GraineCallingWindow? {
    val o = raw as? Map<*, *> ?: return null
    fun clock(v: Any?): String? = (v as? String)?.trim()?.takeIf { HHMM.matches(it) }
    val start = clock(o["start"]) ?: return null
    val end = clock(o["end"]) ?: return null
    if (start >= end) return null
    val days = when (val d = o["days"]) {
        null -> (0..6).toList()
        is List<*> -> d.mapNotNull { v ->
            when (v) {
                is Long -> v.takeIf { it in 0L..6L }?.toInt()
                is Int -> v.takeIf { it in 0..6 }
                is Double -> v.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 0.0..6.0 }?.toInt()
                else -> null
            }
        }.distinct().sorted()
        else -> return null
    }
    return if (days.isEmpty()) null else GraineCallingWindow(start, end, days)
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
     * says it. `disconnected`: Graine ended the conversation itself (idle, or
     * its time limit) and nothing is connecting again yet. `ended`: the agent
     * ended the conversation — not the chat failing, so its closed connection
     * is IDLE, as React Native and Flutter say it, whatever else is left.
     */
    fun status(
        connected: Boolean,
        connecting: Boolean,
        failed: Boolean,
        reconnecting: Boolean,
        disconnected: Boolean = false,
        ended: Boolean = false,
    ): GraineChatStatus = when {
        connected -> GraineChatStatus.ONLINE
        connecting -> if (reconnecting) GraineChatStatus.RECONNECTING else GraineChatStatus.CONNECTING
        disconnected -> GraineChatStatus.DISCONNECTED
        ended -> GraineChatStatus.IDLE
        failed -> GraineChatStatus.OFFLINE
        else -> GraineChatStatus.IDLE
    }
}

/**
 * THE VOICE BAR'S CALL AS ONE WORD, from what a platform reports — the bar's
 * own order, first match wins ([GraineVoiceStatus]). [phase] is the
 * platform's word for a live call (`reconnecting`, `thinking`, `speaking`,
 * `listening`) when it has one; without it the captions tell: the agent's
 * line still arriving is SPEAKING, the user's finished line with no answer
 * begun is THINKING.
 */
internal object VoiceInference {
    fun status(
        connected: Boolean,
        connecting: Boolean,
        muted: Boolean,
        failed: Boolean,
        ended: Boolean,
        phase: String? = null,
        captions: List<GraineCaption> = emptyList(),
    ): GraineVoiceStatus {
        val word = phase?.lowercase()
        val last = captions.lastOrNull()
        return when {
            failed -> GraineVoiceStatus.FAILED
            word == "reconnecting" && (connected || connecting) -> GraineVoiceStatus.RECONNECTING
            connecting -> GraineVoiceStatus.CONNECTING
            !connected -> if (ended) GraineVoiceStatus.ENDED else GraineVoiceStatus.IDLE
            muted -> GraineVoiceStatus.MUTED
            word == "thinking" -> GraineVoiceStatus.THINKING
            word == "speaking" -> GraineVoiceStatus.SPEAKING
            word != null -> GraineVoiceStatus.LISTENING
            last != null && last.role == GraineRole.AGENT && last.live -> GraineVoiceStatus.SPEAKING
            last != null && last.role == GraineRole.USER && !last.live -> GraineVoiceStatus.THINKING
            else -> GraineVoiceStatus.LISTENING
        }
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

    /**
     * An error as the facade reports it: its code word, its sentence, `Retry-After`
     * when there was one, and the server's one-word reason beside the code
     * (`scope`, `signature`, …) when it gave one.
     */
    fun error(code: String?, message: String?, retryAfterSeconds: Int? = null, reason: String? = null): GraineException =
        GraineException(
            GraineErrorCode.from(code),
            message?.takeIf { it.isNotBlank() } ?: "Graine could not complete the request.",
            retryAfterSeconds?.takeIf { it > 0 },
            reason?.trim()?.takeIf { it.isNotEmpty() },
        )

    /**
     * What Graine has proven, as the facade's JSON says it — `{app, appReason,
     * user, userReason}`, the SDKs' words (`testBuild`; Graine's `test_build`
     * reads the same). Nothing readable is the state before any session: off,
     * off. A verdict this build does not know is not proven (unverified).
     */
    fun verification(json: String?): GraineVerification {
        val o = Json.obj(json) ?: return GraineVerification()
        val app = (o["app"] as? String)?.let { GraineAppVerdict.from(it) ?: GraineAppVerdict.UNVERIFIED } ?: GraineAppVerdict.OFF
        val user = (o["user"] as? String)?.let { GraineUserVerdict.from(it) ?: GraineUserVerdict.UNVERIFIED } ?: GraineUserVerdict.OFF
        return GraineVerification(app, str(o["appReason"])?.trim(), user, str(o["userReason"])?.trim())
    }

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

    /** The iOS facade's `startWorkflowJSON` answer — `{runId, workflowId, alreadyRunning}` — or null without a run id. */
    fun workflowRun(json: String?): GraineWorkflowRun? {
        val o = Json.obj(json) ?: return null
        val runId = str(o["runId"]) ?: return null
        return GraineWorkflowRun(runId, str(o["workflowId"]), o["alreadyRunning"] == true)
    }

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
            callingWindow = callingWindowOf(calls?.get("callingWindow")),
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
            // The agent ending the conversation is not the chat failing ([ChatInference.status]): idle, as every SDK says it.
            "offline" -> if (o["ended"] == true) GraineChatStatus.IDLE else GraineChatStatus.OFFLINE
            "disconnected" -> GraineChatStatus.DISCONNECTED
            else -> GraineChatStatus.IDLE
        }
        val turns = (o["turns"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { t -> turn(t) } }
        val upcoming = (o["upcomingCalls"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { c -> scheduledCall(c) } }
        // The iOS SDK says a chat that gave up as `offline` — its chat window's "Not connected" with a tap to try again —
        // never for one the agent ended (idle above) or Graine closed (`disconnected`).
        return Chat(turns, status, o["typing"] == true, upcoming, chatExtras(o).copy(failed = status == GraineChatStatus.OFFLINE))
    }

    /**
     * The snapshot's `quickReplies` ([{label, text}]), `ended`, `rating`
     * ({value, scale, style} or null), `pin` ({title, subtitle, imageUrl,
     * badge} or null) and `newConversationBefore` ([turn ids]). A reply needs
     * its label (no text: the label), a rating its numbers, a pin its title.
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
        newConversationBefore = (o["newConversationBefore"] as? List<*>).orEmpty().mapNotNull { str(it) }.distinct(),
        // The current session: a string that is not empty, else none (an older facade says nothing).
        sessionId = (o["sessionId"] as? String)?.takeIf { it.isNotEmpty() },
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
            // The session it was said in: a string that is not empty, else none.
            session = (o["session"] as? String)?.takeIf { it.isNotEmpty() },
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
        val caption = (o["caption"] as? Map<*, *>)?.let { c -> captionLine(c) }
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
            reason = str(o["reason"]),
            endReason = str(o["endReason"]).takeIf { state == GraineCallState.ENDED },
            endCause = str(o["endCause"]).takeIf { state == GraineCallState.ENDED },
            agentLevel = level(o["agentLevel"]),
            startedAt = instant(o["connectedAt"]),
            connection = (o["connection"] as? Map<*, *>)?.let { connection(it) },
            // The iOS SDK's word for it is `transcript` (GraineCalls.transcript); `captions` read first.
            captions = captionLines(o["captions"] ?: o["transcript"]).takeIf { state != GraineCallState.IDLE }.orEmpty(),
        )
    }

    /** A line of a transcript, `{role, text, live}`: the agent's, else the user's; null without words. */
    private fun captionLine(line: Map<*, *>): GraineCaption? {
        val text = line["text"] as? String ?: return null
        return GraineCaption(if (line["role"] == "agent") GraineRole.AGENT else GraineRole.USER, text, line["live"] == true)
    }

    /** A transcript, oldest first; anything that is not a line is skipped. */
    private fun captionLines(v: Any?): List<GraineCaption> = (v as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { l -> captionLine(l) } }

    fun outcome(json: String?): GraineCallOutcome? {
        val o = Json.obj(json) ?: return null
        val id = str(o["callId"]) ?: return null
        val kind = GraineCallOutcome.Kind.entries.firstOrNull { it.wire == (o["outcome"] as? String)?.lowercase() } ?: return null
        return GraineCallOutcome(
            id, kind, str(o["reason"]), int(o["durationSeconds"]), o["hosted"] == true, str(o["agent"]),
            // iOS's facade says it as `cause`; `endCause` read too.
            (o["connection"] as? Map<*, *>)?.let { connection(it) }, str(o["cause"]) ?: str(o["endCause"]),
        )
    }

    /**
     * A connection report as the bridges carry it — `{transport, prepared,
     * preparedAgeSeconds, credential, setupMs, loginMs, answerMs,
     * firstWordsMs, tapToVoiceMs, audio: {lossPct, jitterAvgMs, jitterPeakMs,
     * rttMs, quality}, recoveries}`; a fact absent, null or of the wrong type
     * is not known.
     */
    fun connection(o: Map<*, *>): GraineCallConnection = GraineCallConnection(
        transport = GraineCallTransport.ofCall(str(o["transport"])),
        prepared = o["prepared"] as? Boolean,
        preparedAgeSeconds = long(o["preparedAgeSeconds"]),
        credential = str(o["credential"]),
        setupMs = long(o["setupMs"]),
        loginMs = long(o["loginMs"]),
        answerMs = long(o["answerMs"]),
        firstWordsMs = long(o["firstWordsMs"]),
        tapToVoiceMs = long(o["tapToVoiceMs"]),
        audio = (o["audio"] as? Map<*, *>)?.let { a ->
            GraineCallConnection.Audio(double(a["lossPct"]), long(a["jitterAvgMs"]), long(a["jitterPeakMs"]), long(a["rttMs"]), str(a["quality"]))
        },
        recoveries = int(o["recoveries"])?.takeIf { it > 0 } ?: 0,
    )

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
        val captions = captionLines(o["captions"])
        val connected = o["connected"] == true
        val connecting = o["connecting"] == true
        val muted = o["muted"] == true
        val error = str(o["error"])
        // The facade's own word when it says one; else the bar's rule over what it does say.
        val status = GraineVoiceStatus.of(o["status"] as? String)
            ?: VoiceInference.status(connected, connecting, muted, failed = error != null, ended = false, captions = captions)
        return GraineVoiceInfo(
            connected = connected,
            connecting = connecting,
            muted = muted,
            // A facade from before the switch says nothing: the captions are on.
            captionsOn = o["captionsOn"] != false,
            captions = captions,
            status = status,
            error = error.takeIf { status == GraineVoiceStatus.FAILED },
            agentLevel = level(o["agentLevel"]),
            micLevel = level(o["micLevel"]),
            transport = transport(str(o["transport"])),
            transportFallbackReason = str(o["transportFallbackReason"]) ?: str(o["fallbackReason"]),
        )
    }

    /** The voice bar's transport from a platform's word: `webrtc` or `rtc`, `websocket` or `ws`; null for anything else. */
    fun transport(word: String?): GraineTransport? = when (word?.lowercase()) {
        "webrtc", GraineTransport.WEBRTC.wire -> GraineTransport.WEBRTC
        "websocket", GraineTransport.WEBSOCKET.wire -> GraineTransport.WEBSOCKET
        else -> null
    }

    /** A level, 0–1: anything else (or nothing) is 0. */
    fun level(v: Any?): Float = when (v) {
        is Number -> v.toDouble()
        else -> null
    }?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f

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

    /** A whole number, not negative: a figure of a connection report. */
    private fun long(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v.isFinite()) kotlin.math.round(v).toLong() else null
        else -> null
    }?.takeIf { it >= 0 }

    private fun double(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        else -> null
    }?.takeIf { it.isFinite() && it >= 0 }

    private fun stringKeys(m: Map<*, *>): Map<String, Any?> = m.entries.associate { (k, v) -> k.toString() to v }
}
