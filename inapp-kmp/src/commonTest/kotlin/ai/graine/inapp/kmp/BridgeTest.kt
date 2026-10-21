package ai.graine.inapp.kmp

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shapes the iOS facade and the shared code exchange (Bridge), and the rules both platforms share (Mapping.kt). */
class BridgeTest {

    // ── shared code → the facade ──────────────────────────────────────────

    @Test
    fun aScreenIsWrittenWithOnlyWhatItSays() {
        val json = Bridge.screen(
            GraineScreen(
                "kyc", title = "Upload", journey = GraineScreen.Journey("KYC", 2, 4),
                fields = listOf(GraineField("pan", "PAN", "ABCDE1234F", GraineFieldStatus.INVALID, "Not recognised"), GraineField("note", optional = true)),
                data = mapOf("attempts" to 2), availableActions = emptyList(),
            )
        )
        assertEquals(
            """{"screen":"kyc","title":"Upload","journey":{"name":"KYC","step":2,"of":4},"fields":[{"name":"pan","label":"PAN",""" +
                """"value":"ABCDE1234F","status":"invalid","error":"Not recognised"},{"name":"note","optional":true}],"data":{"attempts":2},"availableActions":[]}""",
            json,
        )
        // No list of actions is every registered action; an empty list is none — so the two are written differently.
        assertEquals("""{"screen":"home","fields":[]}""", Bridge.screen(GraineScreen("home")))
        assertNull(Bridge.screen(null))
        assertEquals("""{"name":"otp","status":"empty"}""", Bridge.field(GraineField("otp", status = GraineFieldStatus.EMPTY)))
        assertNull(Bridge.json(null))
        assertEquals("""{"user_id":"u-1","age":41}""", Bridge.json(mapOf("user_id" to "u-1", "age" to 41)))
    }

    // ── the facade → shared code ──────────────────────────────────────────

    @Test
    fun memoriesAreReadLeniently() {
        val list = Bridge.memories(
            """[{"id":"m1","text":"Takes metformin at 8 pm","createdAt":1760356800000,"updatedAt":null},
               {"id":"","text":"no id"},{"text":"no id either"},{"id":"m2","text":"ISO","createdAt":"2026-10-13T10:00:00Z"},7]"""
        )
        assertEquals(
            listOf(
                GraineMemory("m1", "Takes metformin at 8 pm", Instant.fromEpochMilliseconds(1760356800000), null),
                GraineMemory("m2", "ISO", Instant.parse("2026-10-13T10:00:00Z"), null),
            ),
            list,
        )
        assertEquals(emptyList(), Bridge.memories(null))
        assertEquals(emptyList(), Bridge.memories("{\"memories\":[]}"))
    }

    @Test
    fun aScheduledCallIsTheWireTypes() {
        val call = Bridge.scheduledCall("""{"id":"sc_1","at":1760438400000,"status":"no_device","source":"sdk","reason":"Weekly check-in"}""")!!
        assertEquals(GraineScheduledCall("sc_1", Instant.fromEpochMilliseconds(1760438400000), GraineCallStatus.NoDevice, GraineCallSource.App, "Weekly check-in"), call)
        assertFalse(call.isUpcoming)
        val unreadable = Bridge.scheduledCall("""{"id":"sc_2","at":"yesterday","status":"later"}""")!!
        assertNull(unreadable.at)
        assertEquals(GraineCallStatus.Unknown("later"), unreadable.status)
        assertNull(unreadable.source)
        assertNull(Bridge.scheduledCall("""{"status":"scheduled"}"""))
        assertEquals(listOf("a", "b"), Bridge.scheduledCalls("""[{"id":"a"},{"nope":1},{"id":"b","status":"ringing"}]""").map { it.id })
    }

    @Test
    fun aSessionKeepsWhatTheServerSaidAndDefaultsTheRest() {
        val s = Bridge.session(
            """{"agentId":"agent-1","memoryEnabled":true,"identity":{"field":"patient_id","devicesPerUser":1},
               "calls":{"hosted":true,"platforms":["ios","android","web"],"ringTimeoutSeconds":45,"callName":"Asha · Graine Care",
                        "usersCanBook":true,"maxDaysAhead":7,"minLeadMinutes":10,"maxPendingPerUser":2},"declaredActions":["open_screen","mark_done"]}"""
        )!!
        assertEquals("agent-1", s.agentId)
        assertEquals(true, s.memoryEnabled)
        assertEquals("patient_id", s.identityField)
        assertEquals(1, s.devicesPerUser)
        assertEquals(
            GraineSchedulingRules(true, 10, 7, 2, 45, "Asha · Graine Care", true, listOf(GrainePlatform.IOS, GrainePlatform.ANDROID)),
            s.schedulingRules,
        )
        assertEquals(setOf("open_screen", "mark_done"), s.declaredActions)

        val bare = Bridge.session("""{"agentId":"agent-2"}""")!!
        assertNull(bare.memoryEnabled)
        assertEquals("user_id", bare.identityField)
        assertEquals(2, bare.devicesPerUser)
        assertEquals(GraineSchedulingRules(), bare.schedulingRules)
        assertNull(Bridge.session("not json"))
    }

    @Test
    fun theChatIsTurnsAWordAndAFlag() {
        val chat = Bridge.chat(
            """{"turns":[{"id":1,"role":"customer","text":"Hi","live":false,"delivery":"pending","at":1000,"proactive":false},
                         {"id":2,"role":"agent","text":"Hello","live":true,"delivery":"sent","at":2000,"proactive":true},
                         {"id":3,"role":"user","text":"Help","delivery":"failed","at":3000},{"id":4,"role":"robot","text":"?"}],
                "status":"reconnecting","typing":true}"""
        )
        assertEquals(GraineChatStatus.RECONNECTING, chat.status)
        assertTrue(chat.typing)
        assertEquals(
            listOf(
                GraineChatTurn("1", GraineRole.USER, "Hi", Instant.fromEpochMilliseconds(1000), GraineDelivery.SENDING, false),
                GraineChatTurn("2", GraineRole.AGENT, "Hello", Instant.fromEpochMilliseconds(2000), null, true, live = true),
                GraineChatTurn("3", GraineRole.USER, "Help", Instant.fromEpochMilliseconds(3000), GraineDelivery.FAILED),
            ),
            chat.turns,
        )
        assertEquals(Bridge.Chat(emptyList(), GraineChatStatus.IDLE, false), Bridge.chat(null))
        assertTrue(chat.upcoming.isEmpty(), "a facade from before the upcoming calls says nothing of them")
    }

    @Test
    fun theChatsUpcomingCallsRideItsSnapshot() {
        val chat = Bridge.chat(
            """{"turns":[],"status":"online","typing":false,
                "upcomingCalls":[{"id":"sc_1","at":1760617800000,"status":"scheduled","source":"agent","reason":"Your delivery"},
                                 {"id":"","at":1},{"at":2},"junk",{"id":"sc_2","at":null,"status":"ringing","source":null,"reason":null}]}"""
        )
        assertEquals(
            listOf(
                GraineScheduledCall("sc_1", Instant.fromEpochMilliseconds(1760617800000), GraineCallStatus.Scheduled, GraineCallSource.Agent, "Your delivery"),
                GraineScheduledCall("sc_2", null, GraineCallStatus.Ringing, null, null),
            ),
            chat.upcoming,
            "a call without an id, or not a call at all, is skipped",
        )
    }

    @Test
    fun aRestoredTurnSaysSoAndWhereItWasSaid() {
        val chat = Bridge.chat(
            """{"turns":[{"id":"b00000000001","role":"agent","text":"Hi, this is Asha.","live":false,"delivery":"sent","at":1760353200000,"proactive":false,"restored":true,"channel":"voice"},
                         {"id":"c00000000001","role":"user","text":"Sent from somewhere new","delivery":"sent","at":1760356800000,"restored":true,"channel":"sms"},
                         {"id":"t1","role":"user","text":"Hello","delivery":"sending","at":1760360400000,"restored":"yes"}],"status":"online"}"""
        )
        assertEquals(
            listOf(
                GraineChatTurn("b00000000001", GraineRole.AGENT, "Hi, this is Asha.", Instant.fromEpochMilliseconds(1760353200000), restored = true, channel = GraineChatChannel.VOICE),
                GraineChatTurn("c00000000001", GraineRole.USER, "Sent from somewhere new", Instant.fromEpochMilliseconds(1760356800000), GraineDelivery.SENT, restored = true),
                GraineChatTurn("t1", GraineRole.USER, "Hello", Instant.fromEpochMilliseconds(1760360400000), GraineDelivery.SENDING),
            ),
            chat.turns,
            "a channel other than voice is chat; restored is a boolean or nothing; an older facade says neither",
        )
    }

    @Test
    fun theChatWindowsStateRidesTheSnapshot() {
        val chat = Bridge.chat(
            """{"turns":[],"status":"online","typing":false,
                "quickReplies":[{"label":"Yes","text":"Yes, please"},{"label":"Later","text":""},{"label":"  ","text":"x"},"junk",{"text":"no label"}],
                "ended":true,"rating":{"value":4,"scale":5,"style":"stars"},
                "pin":{"title":"Your plan","subtitle":null,"imageUrl":"https://example.com/p.png","badge":"Active"}}"""
        )
        assertEquals(
            ChatExtras(
                listOf(GraineQuickReply("Yes", "Yes, please"), GraineQuickReply("Later", "Later")),
                ended = true,
                rating = GraineChatRating(4, 5, "stars"),
                pinned = GraineChatPin("Your plan", null, "https://example.com/p.png", "Active"),
            ),
            chat.extras,
            "a reply needs its label (no text: the label)",
        )
        assertEquals(ChatExtras(), Bridge.chat("""{"turns":[],"status":"online","typing":false}""").extras, "an older facade says nothing of them")
        val odd = Bridge.chat("""{"ended":"yes","rating":{"scale":5},"pin":{"subtitle":"No title"},"quickReplies":{}}""").extras
        assertEquals(ChatExtras(), odd, "a rating needs its numbers, a pin its title; the wrong types are nothing")
    }

    @Test
    fun theCallIsWhatTheScreenDraws() {
        val info = Bridge.calls(
            """{"state":"ended","endReason":"remote","callId":"c1","callerName":"Asha","direction":"incoming","hosted":true,"muted":true,
               "speakerOn":false,"agentSpeaking":true,"caption":{"role":"agent","text":"Goodbye","live":false},
               "missedCall":{"callId":"c0","callerName":"Asha","reason":null,"hosted":true,"at":5000},"agent":"help"}"""
        )
        assertEquals(
            GraineCallInfo(
                GraineCallState.ENDED, "c1", "Asha", incoming = true, hosted = true, muted = true, speakerOn = false, agentSpeaking = true,
                caption = GraineCaption(GraineRole.AGENT, "Goodbye", false),
                missedCall = GraineMissedCall("c0", "Asha", null, true, Instant.fromEpochMilliseconds(5000)),
                agent = "help",
                endReason = "remote",
            ),
            info,
        )
        assertEquals(GraineCallInfo(), Bridge.calls("{}"))
        assertNull(Bridge.calls("""{"state":"idle","agent":null}""").agent)
    }

    @Test
    fun aRecoveringCallSaysSoAndAnOlderFacadeSaysNothing() {
        assertTrue(Bridge.calls("""{"state":"active","callId":"c1","reconnecting":true}""").reconnecting)
        assertFalse(Bridge.calls("""{"state":"active","callId":"c1","reconnecting":false}""").reconnecting)
        assertFalse(Bridge.calls("""{"state":"active","callId":"c1"}""").reconnecting, "an iOS SDK that predates it says nothing: not recovering")
        assertFalse(Bridge.calls("""{"state":"active","callId":"c1","reconnecting":"true"}""").reconnecting, "only a true is recovering")
        assertFalse(GraineCallInfo().reconnecting)
    }

    @Test
    fun theCallSaysWhatItsAudioRunsOnAndWhyItFellBack() {
        val onWebRtc = Bridge.calls("""{"state":"active","callId":"c1","transport":"webrtc","transportFallbackReason":null}""")
        assertEquals(GraineCallTransport.WEBRTC, onWebRtc.transport)
        assertNull(onWebRtc.transportFallbackReason)
        val fellBack = Bridge.calls("""{"state":"active","callId":"c1","transport":"websocket","transportFallbackReason":"ice_timeout"}""")
        assertEquals(GraineCallTransport.WEBSOCKET, fellBack.transport)
        assertEquals("ice_timeout", fellBack.transportFallbackReason)
        assertNull(Bridge.calls("""{"state":"active","transport":"auto"}""").transport, "a call runs on webrtc or websocket, never on auto")
        assertNull(Bridge.calls("""{"state":"active"}""").transport, "an iOS SDK that predates WebRTC says nothing")
    }

    @Test
    fun theTransportsAreTheirWords() {
        assertEquals(listOf("auto", "websocket", "webrtc"), GraineCallTransport.entries.map { it.wire })
        assertEquals(GraineCallTransport.AUTO, GraineCallsConfig().callTransport)
    }

    @Test
    fun outcomesAndRingsAreReadWhole() {
        assertEquals(
            GraineCallOutcome("c1", GraineCallOutcome.Kind.ENDED, "local", 63, true),
            Bridge.outcome("""{"callId":"c1","outcome":"ended","reason":"local","durationSeconds":63,"hosted":true}"""),
        )
        assertEquals(GraineCallOutcome("c2", GraineCallOutcome.Kind.MISSED, "busy"), Bridge.outcome("""{"callId":"c2","outcome":"missed","reason":"busy"}"""))
        assertNull(Bridge.outcome("""{"callId":"c3","outcome":"exploded"}"""))
        assertNull(Bridge.outcome("""{"outcome":"ended"}"""))
        assertEquals(GraineIncomingCall("c4", "Asha", "payment_due", false), Bridge.incoming("""{"callId":"c4","callerName":"Asha","reason":"payment_due"}"""))
        assertEquals(
            GraineIncomingCall("c5", "Help desk", null, true, "help"),
            Bridge.incoming("""{"callId":"c5","callerName":"Help desk","reason":null,"hosted":true,"agent":"help"}"""),
        )
        assertEquals("default", Bridge.outcome("""{"callId":"c5","outcome":"ringing","hosted":true,"agent":"default"}""")?.agent)
        assertNull(Bridge.outcome("""{"callId":"c5","outcome":"ringing","agent":""}""")?.agent, "blank is not a name")
        assertEquals(GraineVoiceInfo(connected = true, connecting = false, muted = true, status = GraineVoiceStatus.MUTED), Bridge.voice("""{"connected":true,"muted":true}"""))
        assertTrue(Bridge.voice("""{"connected":true}""").captionsOn, "a facade from before the switch: the captions are on")
        assertEquals(
            GraineVoiceInfo(
                connected = true, captionsOn = false,
                captions = listOf(GraineCaption(GraineRole.USER, "What does it cost?", false), GraineCaption(GraineRole.AGENT, "It costs", true)),
                status = GraineVoiceStatus.SPEAKING,
            ),
            Bridge.voice(
                """{"connected":true,"captionsOn":false,"captions":[{"role":"user","text":"What does it cost?","live":false},
                    {"role":"agent","text":"It costs","live":true},{"role":"agent"},"junk"]}"""
            ),
            "a line with no words, or not a line, is skipped",
        )
    }

    @Test
    fun everyEventByItsName() {
        assertEquals(GraineEvent.Caption(GraineRole.USER, "hello", true), Bridge.event("caption", """{"role":"customer","text":"hello","live":true}"""))
        assertEquals(GraineEvent.VoiceEnded, Bridge.event("voiceEnded", "{}"))
        val widget = Bridge.event("widget", """{"widget":{"widget_id":"w1","kind":"calendar_slots","title":"Pick a time","slots":[]}}""")
        assertEquals(GraineComponent("w1", "calendar_slots", "Pick a time", mapOf("widget_id" to "w1", "kind" to "calendar_slots", "title" to "Pick a time", "slots" to emptyList<Any?>())),
            (widget as GraineEvent.Component).component)
        assertEquals(GraineEvent.ComponentPending(mapOf("kind" to "choices")), Bridge.event("widgetPending", """{"hint":{"kind":"choices"}}"""))
        assertEquals(
            GraineEvent.ChatMessage(GraineChatTurn("9", GraineRole.AGENT, "Done.", Instant.fromEpochMilliseconds(1), null, true)),
            Bridge.event("chatMessage", """{"turn":{"id":9,"role":"agent","text":"Done.","at":1,"proactive":true}}"""),
        )
        assertEquals(GraineEvent.ActionRequested("mark_done"), Bridge.event("actionRequested", """{"name":"mark_done"}"""))
        assertEquals(
            GraineEvent.ActionCompleted("mark_done", GraineActionResult.refused("Which one?")),
            Bridge.event("actionCompleted", """{"name":"mark_done","status":"refused","message":"Which one?"}"""),
        )
        assertEquals(GraineEvent.Friction("stalled_on_step", "kyc", "Stuck on PAN."), Bridge.event("friction", """{"name":"stalled_on_step","screen":"kyc","detail":"Stuck on PAN."}"""))
        assertEquals(GraineEvent.MicrophoneDenied("NotAllowedError"), Bridge.event("micDenied", """{"reason":"NotAllowedError"}"""))
        assertEquals(GraineEvent.Error("Publishable key was rejected."), Bridge.event("error", """{"message":"Publishable key was rejected."}"""))
        assertNull(Bridge.event("sessionReady", "{}"), "the session is a state, not an event the facade forwards")
        assertNull(Bridge.event("somethingNew", "{}"))
    }

    // ── rules both platforms share ────────────────────────────────────────

    @Test
    fun outcomesSayWhyAndHowLong() {
        assertEquals(GraineCallOutcome("c", GraineCallOutcome.Kind.MISSED, "busy"), outcomeOf("c", GraineCallOutcome.Kind.MISSED, busy = true))
        assertEquals(GraineCallOutcome("c", GraineCallOutcome.Kind.MISSED, "no_answer", hosted = true), outcomeOf("c", GraineCallOutcome.Kind.MISSED, hosted = true))
        assertEquals(GraineCallOutcome("c", GraineCallOutcome.Kind.ENDED, "remote", 3), outcomeOf("c", GraineCallOutcome.Kind.ENDED, endReason = "remote", connectedMs = 2_500))
        assertEquals(GraineCallOutcome("c", GraineCallOutcome.Kind.ENDED, "local", 0), outcomeOf("c", GraineCallOutcome.Kind.ENDED, connectedMs = -40))
        assertEquals(GraineCallOutcome("c", GraineCallOutcome.Kind.ENDED, "failed", null), outcomeOf("c", GraineCallOutcome.Kind.ENDED, endReason = "failed"))
        assertEquals("x".repeat(200), outcomeOf("c", GraineCallOutcome.Kind.FAILED, error = "x".repeat(500)).reason)
        assertEquals("failed", outcomeOf("c", GraineCallOutcome.Kind.FAILED).reason)
        assertEquals(GraineCallOutcome("c", GraineCallOutcome.Kind.ANSWERED), outcomeOf("c", GraineCallOutcome.Kind.ANSWERED, endReason = "ignored"))
        assertEquals(GraineCallOutcome("c", GraineCallOutcome.Kind.RINGING, hosted = true), outcomeOf("c", GraineCallOutcome.Kind.RINGING, hosted = true))
        for (kind in GraineCallOutcome.Kind.entries) assertEquals("help", outcomeOf("c", kind, agent = "help").agent, "$kind keeps its agent")
    }

    @Test
    fun aComponentNeedsAnId() {
        assertEquals("w2", componentOf(mapOf("id" to "w2", "type" to "info_card"))?.id)
        assertEquals("info_card", componentOf(mapOf("id" to "w2", "type" to "info_card"))?.kind)
        assertNull(componentOf(mapOf("id" to "w3", "type" to "widget"))?.kind)
        assertNull(componentOf(mapOf("kind" to "choices")))
    }

    @Test
    fun theFlagsAreOneWordAndTheSdksReconnectingNamesARemake() {
        assertEquals(GraineChatStatus.CONNECTING, ChatInference.status(connected = false, connecting = true, failed = false, reconnecting = false))
        assertEquals(GraineChatStatus.RECONNECTING, ChatInference.status(connected = false, connecting = true, failed = false, reconnecting = true))
        assertEquals(GraineChatStatus.ONLINE, ChatInference.status(true, false, false, false))
        assertEquals(GraineChatStatus.OFFLINE, ChatInference.status(false, false, true, false))
        assertEquals(GraineChatStatus.IDLE, ChatInference.status(false, false, false, false))
        assertEquals(GraineChatStatus.IDLE, ChatInference.status(false, false, false, true), "not connecting is never reconnecting")
    }

    @Test
    fun sessionDefaultsAreWires() {
        val s = sessionOf("a", null, "  ", 0, null, emptyList(), 0, " ", null, null, -1, 0, emptySet())
        assertEquals("user_id", s.identityField)
        assertEquals(2, s.devicesPerUser)
        assertEquals(GraineSchedulingRules(), s.schedulingRules)
    }

    @Test
    fun theIosFacadesPermissionsAndProblemsAreRead() {
        assertEquals(
            GraineCallPermissions(GraineCallPermissionStatus.BLOCKED, GraineCallPermissionStatus.NOT_NEEDED, GraineCallPermissionStatus.NOT_NEEDED),
            Bridge.permissions("""{"microphone":"blocked","notifications":"notNeeded","fullScreen":"notNeeded"}"""),
        )
        assertEquals(GraineCallPermissionStatus.DENIED, Bridge.permissions("not json").microphone, "nothing readable is never granted")
        assertEquals(
            listOf(GraineCallSetupProblem("background_voip_missing", "Add voip."), GraineCallSetupProblem("no_voip_token", "")),
            Bridge.problems("""[{"code":"background_voip_missing","message":"Add voip."},{"code":""},{"code":"no_voip_token"},7]"""),
        )
        assertEquals(emptyList<GraineCallSetupProblem>(), Bridge.problems(null))
    }
}
