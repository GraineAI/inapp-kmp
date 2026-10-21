package ai.graine.inapp.kmp

import kotlinx.datetime.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CONVERSATIONS CONTINUE (contract §2.6): sdk/contract/history.json, vendored
 * ([HISTORY_JSON]), as these tests read it. The platform SDKs read the history
 * frame and fold it into the chat; what this layer owes the contract is to
 * carry the turns they then hold — every `frames` case's (each restored) and
 * every `restore` case's — across unchanged, from both platforms. The `rows`
 * cases are [GraineChatRows]'.
 */
internal object HistoryVectors {
    private val contract: Map<String, Any?> = Json.obj(HISTORY_JSON) ?: error("history.json is not a JSON object")

    /** One turn as the contract expects a platform SDK to hold it. */
    data class Expected(
        val case: String,
        val id: String?,
        val user: Boolean,
        val text: String,
        val at: Instant?,
        val channel: GraineChatChannel,
        val restored: Boolean,
        /** The user's turns only. */
        val delivery: GraineDelivery?,
        /** The session it was said in, as the platform SDK keeps it; compared only where the case names one. */
        val session: String? = null,
        val namesSession: Boolean = false,
    )

    @Suppress("UNCHECKED_CAST")
    fun section(name: String): List<Map<String, Any?>> =
        (contract[name] as List<Map<String, Any?>>).also { check(it.isNotEmpty()) { "section $name is empty" } }

    /** Every turn a platform SDK holds after the `frames` and `restore` cases, in order. */
    val turns: List<Expected> by lazy {
        val out = ArrayList<Expected>()
        // A frame's turns are all restored; its ignored frames (turns null) restore nothing.
        for (c in section("frames")) (c["turns"] as? List<*>)?.forEach { out += expected(c["name"] as String, it as Map<*, *>, restored = true) }
        // A field absent from an expected `restore` turn: restored false, channel chat, delivery sent.
        for (c in section("restore")) (c["turns"] as List<*>).forEach { out += expected(c["name"] as String, it as Map<*, *>, restored = false) }
        out
    }

    private fun expected(case: String, t: Map<*, *>, restored: Boolean): Expected {
        val user = when (t["role"]) {
            "customer" -> true
            "agent" -> false
            else -> error("$case: a role the contract does not use: ${t["role"]}")
        }
        return Expected(
            case = case,
            id = t["id"] as? String,
            user = user,
            text = t["text"] as String,
            at = (t["at"] as? String)?.let { Instant.parse(it) },
            channel = if (t["channel"] == "voice") GraineChatChannel.VOICE else GraineChatChannel.CHAT,
            restored = t["restored"] as? Boolean ?: restored,
            delivery = if (!user) null else when (t["delivery"]) {
                "sending" -> GraineDelivery.SENDING
                "failed" -> GraineDelivery.FAILED
                else -> GraineDelivery.SENT
            },
            session = t["session"] as? String,
            namesSession = t.containsKey("session"),
        )
    }

    /** When a turn the contract gives no time to was said: any fixed time does. */
    val anyTime: Instant = Instant.parse("2026-10-13T08:00:00Z")

    /** [e] as the iOS facade's chat snapshot sends a turn (ObjCBridge.turn in sdk/ios). */
    fun facadeJson(e: Expected, index: Int): Map<String, Any?> = linkedMapOf(
        "id" to (e.id ?: "t${index + 1}"),
        "role" to if (e.user) "user" else "agent",
        "text" to e.text,
        "live" to false,
        "delivery" to (e.delivery ?: GraineDelivery.SENT).name.lowercase(),
        "at" to (e.at ?: anyTime).toEpochMilliseconds(),
        "proactive" to false,
        "restored" to e.restored,
        "channel" to e.channel.wire,
        "session" to e.session,
    )

    /** The shared turn says everything the contract says of [e]; a restored one is delivered, not live, not volunteered. */
    fun assertCarried(e: Expected, t: GraineChatTurn) {
        val where = "${e.case}: \"${e.text}\""
        e.id?.let { assertEquals(it, t.id, "$where: id") }
        assertEquals(if (e.user) GraineRole.USER else GraineRole.AGENT, t.role, "$where: role")
        assertEquals(e.text, t.text, "$where: text")
        e.at?.let { assertEquals(it, t.at, "$where: at") }
        assertEquals(e.channel, t.channel, "$where: channel")
        assertEquals(e.restored, t.restored, "$where: restored")
        assertEquals(e.delivery, t.delivery, "$where: delivery")
        if (e.namesSession) assertEquals(e.session, t.session, "$where: session")
        if (e.restored) assertTrue(!t.live && !t.proactive, "$where: a restored turn is delivered, not live, not volunteered")
    }
}
