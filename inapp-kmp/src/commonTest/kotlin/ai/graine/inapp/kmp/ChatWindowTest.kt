package ai.graine.inapp.kmp

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CHAT WINDOW v2 IN THE SHARED API (contract/DESIGN.md "Chat window v2" → SDK
 * API, the Kotlin Multiplatform row): the replies the agent suggests, the end
 * of the conversation and its rating, the app's pin and its paperclip — each
 * a state the platform SDK reports, and a call it is handed.
 */
class ChatWindowTest {
    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        Graine.reset()
        engine = FakeEngine()
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    private fun configured(): FakeEngine = engine.also { Graine.install(it) }

    @Test
    fun theStatesAreThePlatformsAndEmptyUntilItSpeaks() {
        configured()
        assertTrue(Graine.chat.quickReplies.value.isEmpty())
        assertFalse(Graine.chat.ended.value)
        assertNull(Graine.chat.rating.value)
        assertNull(Graine.chat.pinned.value)
        val replies = listOf(GraineQuickReply("Yes", "Yes, please"), GraineQuickReply("Later", "Later"))
        engine.sink.chatExtras(ChatExtras(quickReplies = replies))
        assertEquals(replies, Graine.chat.quickReplies.value)
        assertEquals(replies, Graine.agent()!!.chat.quickReplies.value, "the default agent's")
        engine.sink.chatExtras(ChatExtras(ended = true))
        assertTrue(Graine.chat.ended.value)
        assertTrue(Graine.chat.quickReplies.value.isEmpty(), "gone with the end, as the platform reports it")
    }

    @Test
    fun aQuickReplyIsSentAsTheUsersOwnWords() {
        configured()
        Graine.chat.sendQuickReply(GraineQuickReply("A person", "I'd like to talk to a person"))
        Graine.chat.sendQuickReply(GraineQuickReply("Nothing", "  "))
        assertEquals(listOf("sendQuickReply:A person:I'd like to talk to a person"), engine.calls, "a blank one is not a turn")
    }

    @Test
    fun aRatingGoesToThePlatformAndIsHeardOnceWhenItIsTaken() {
        configured()
        val heard = mutableListOf<GraineChatRating>()
        Graine.chat.onRated = { heard += it }
        Graine.chat.rate("stars", 4)
        assertEquals(listOf("chatRate:stars:4"), engine.calls)
        assertTrue(heard.isEmpty(), "nothing until the platform has taken it (one per conversation, after the end)")
        val four = GraineChatRating(4, 5, "stars")
        engine.sink.chatExtras(ChatExtras(ended = true, rating = four))
        engine.sink.chatExtras(ChatExtras(ended = true, rating = four))
        assertEquals(listOf(four), heard, "the platform repeats its state; the rating is heard once")
        assertEquals(four, Graine.chat.rating.value)
        // The user wrote again — the conversation goes on — and later rated the next one the same.
        engine.sink.chatExtras(ChatExtras())
        assertNull(Graine.chat.rating.value)
        engine.sink.chatExtras(ChatExtras(ended = true, rating = four))
        assertEquals(listOf(four, four), heard)
    }

    @Test
    fun aRatingHandlerThatThrowsLeavesTheChatAsThePlatformSaysIt() {
        configured()
        Graine.chat.onRated = { error("the app's own bug") }
        val up = GraineChatRating(1, 1, "thumbs")
        engine.sink.chatExtras(ChatExtras(ended = true, rating = up, pinned = GraineChatPin("Your plan")))
        assertEquals(up, Graine.chat.rating.value)
        assertTrue(Graine.chat.ended.value)
        assertEquals(GraineChatPin("Your plan"), Graine.chat.pinned.value)
    }

    @Test
    fun aPinIsCleanedAsTheNativeSdksCleanIt() {
        configured()
        Graine.chat.pin(GraineChatPin("  Your\nplan \t renews  ", subtitle = "   ", imageUrl = "  https://example.com/p.png ", badge = "\u202EActive\u202C"))
        assertEquals(GraineChatPin("Your plan renews", null, "https://example.com/p.png", "Active"), engine.pins.last(),
            "one line each: controls and direction overrides are spaces, runs of spaces one, trimmed; blank is absent")

        Graine.chat.pin(GraineChatPin("a".repeat(70), subtitle = "b".repeat(90), badge = "c".repeat(30)))
        assertEquals(GraineChatPin("a".repeat(60), "b".repeat(80), null, "c".repeat(24)), engine.pins.last(), "60, 80 and 24 characters")

        // Characters, not code units: an emoji is one, and a cut never splits it.
        Graine.chat.pin(GraineChatPin("a".repeat(59) + "\uD83D\uDE00\uD83D\uDE00"))
        assertEquals("a".repeat(59) + "\uD83D\uDE00", engine.pins.last()!!.title)
        // Composed first: sixty accented letters typed as letter + accent are sixty characters, kept whole.
        Graine.chat.pin(GraineChatPin("e\u0301".repeat(60)))
        assertEquals("\u00E9".repeat(60), engine.pins.last()!!.title)
        // A cut that ends on a space is trimmed.
        Graine.chat.pin(GraineChatPin("a".repeat(59) + " b"))
        assertEquals("a".repeat(59), engine.pins.last()!!.title)

        // No title, no pin: the platform is told to take it away.
        Graine.chat.pin(GraineChatPin(" \n "))
        Graine.chat.pin(null)
        assertEquals(listOf(null, null), engine.pins.takeLast(2))
        assertEquals(listOf(60, 80, 24), listOf(ChatPins.TITLE_MAX, ChatPins.SUBTITLE_MAX, ChatPins.BADGE_MAX))
    }

    @Test
    fun thePinnedCardIsWhatThePlatformKeeps() {
        configured()
        Graine.chat.pin(GraineChatPin("Your plan", "Renews on 14 Nov", badge = "Active"))
        assertNull(Graine.chat.pinned.value, "drawn, and so pinned, once the platform has it")
        engine.sink.chatExtras(ChatExtras(pinned = engine.pins.last()))
        assertEquals(GraineChatPin("Your plan", "Renews on 14 Nov", null, "Active"), Graine.chat.pinned.value)
        Graine.chat.pin(null)
        engine.sink.chatExtras(ChatExtras(pinned = engine.pins.last()))
        assertNull(Graine.chat.pinned.value)
    }

    @Test
    fun theAttachHandlerSetBeforeConfigureReachesThePlatformWhenItStarts() {
        var taps = 0
        Graine.chat.onAttach = { taps++ }
        configured()
        assertEquals(listOf("attach:true"), engine.calls)
        engine.attachHandler!!()
        assertEquals(1, taps, "a tap on the paperclip is the app's handler")
        Graine.chat.onAttach = null
        assertNull(engine.attachHandler, "no handler, no paperclip")
        assertNull(Graine.chat.onAttach)
        assertEquals(listOf("attach:true", "attach:false"), engine.calls)
    }

    @Test
    fun withoutAnAttachHandlerThePlatformIsToldNothing() {
        configured()
        assertTrue(engine.calls.isEmpty())
        assertNull(engine.attachHandler)
    }

    @Test
    fun theControlsNeedAConfiguredAgentAsEveryOtherDoes() {
        assertFailsWith<IllegalStateException> { Graine.chat.pin(GraineChatPin("Your plan")) }
        assertFailsWith<IllegalStateException> { Graine.chat.rate("thumbs", 1) }
        assertFailsWith<IllegalStateException> { Graine.chat.sendQuickReply(GraineQuickReply("Yes", "Yes")) }
        // The handlers and the states are the app's to set and read before configure, as every flow is.
        Graine.chat.onRated = {}
        Graine.chat.onAttach = {}
        assertFalse(Graine.chat.ended.value)
        assertNull(Graine.chat.rating.value)
    }

    @Test
    fun eachAgentHasItsOwn() {
        configured()
        val support = FakeEngine()
        Graine.install(support, name = "support")
        support.sink.chatExtras(ChatExtras(quickReplies = listOf(GraineQuickReply("Yes", "Yes")), ended = true))
        assertTrue(Graine.agent("support")!!.chat.ended.value)
        assertFalse(Graine.chat.ended.value, "another agent's conversation ended, not the default agent's")
        Graine.agent("support")!!.chat.pin(GraineChatPin("A case"))
        assertEquals(listOf<GraineChatPin?>(GraineChatPin("A case")), support.pins)
        assertTrue(engine.pins.isEmpty())
    }

    @Test
    fun aResetForgetsTheStatesAndTheHandlers() {
        configured()
        Graine.chat.onRated = {}
        Graine.chat.onAttach = {}
        engine.sink.chatExtras(ChatExtras(listOf(GraineQuickReply("Yes", "Yes")), true, GraineChatRating(0, 1, "thumbs"), GraineChatPin("Your plan")))
        Graine.reset()
        assertTrue(Graine.chat.quickReplies.value.isEmpty())
        assertFalse(Graine.chat.ended.value)
        assertNull(Graine.chat.rating.value)
        assertNull(Graine.chat.pinned.value)
        assertNull(Graine.chat.onRated)
        assertNull(Graine.chat.onAttach)
    }
}
