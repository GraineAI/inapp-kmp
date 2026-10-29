package ai.graine.inapp.kmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 0.14.8 — WHAT A COMPONENT DOES AND HOLDS, for a component you draw yourself:
 * its tap ([GraineComponent.onSelect], [GraineComponent.tapFor]) read as the
 * SDK contract reads it (protocol.ts normaliseSelectAction, selectCall,
 * fillSelectTemplate), the blocks of one built from blocks
 * ([GraineComponent.blocks]), and its pictures on the agent's origin
 * (resolveWidgetImages). ComponentContentContractTest holds them to the
 * contract's own answers and constants; these say the rules in words, on
 * every platform.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComponentContentTest {
    @BeforeTest
    fun setUp() = Graine.reset()

    @AfterTest
    fun tearDown() = Graine.reset()

    private fun component(kind: String = "product_list", data: Any? = null, onSelect: Any? = null, key: String = "on_select") =
        GraineComponent("w1", kind, null, buildMap {
            put("widget_id", "w1")
            put("kind", kind)
            if (data != null) put("data", data)
            if (onSelect != null) put(key, onSelect)
        })

    // ── what a tap does ───────────────────────────────────────────────────

    @Test
    fun aTapThatOnlyTellsTheAgentIsNoTap() {
        assertNull(component().onSelect, "none set: Tell the agent")
        for (raw in listOf(
            emptyMap<String, Any?>(), mapOf("args" to mapOf("id" to "{id}"), "say" to "hi"), mapOf("action" to "9lives"),
            mapOf("action" to "open comparison"), mapOf("action" to ""), mapOf("action" to "x".repeat(65)), mapOf("path" to "   "),
            mapOf("path" to 42), "open", listOf(mapOf("action" to "go")), 3,
        )) {
            val c = component(onSelect = raw)
            assertNull(c.onSelect, "$raw")
            assertNull(c.tapFor(mapOf("id" to "a")), "$raw")
        }
        assertEquals("x".repeat(64), component(onSelect = mapOf("action" to "x".repeat(64))).onSelect?.action, "64 is the longest name")
    }

    @Test
    fun openAScreenGoesToTheOneOpenScreenHandlerWithItsPathFilled() {
        assertEquals("open_screen", GraineComponentTap.NAVIGATE_ACTION)
        val c = component(onSelect = mapOf("path" to " compare?ids={ids} "))
        assertEquals(GraineComponentTap(path = "compare?ids={ids}"), c.onSelect)
        assertEquals(
            GraineComponentTapCall("open_screen", mapOf("path" to "compare?ids=creta,seltos"), null),
            c.tapFor(mapOf("ids" to "creta,seltos", "titles" to "Creta, Seltos"), summary = "Compare: Creta and Seltos"),
        )
    }

    @Test
    fun runAnActionWithItsArgumentsAndTheSentenceTheAgentHears() {
        val c = component(onSelect = mapOf(
            "action" to " Open_Comparison ", "path" to "compare/{id}",
            "args" to mapOf("id" to "{id}", "label" to "{title} at {price}", "note" to "{sku}", "fixed" to "compare"),
            "say" to "  Compare {title}: {summary}  ",
        ))
        val tap = c.onSelect!!
        assertEquals("open_comparison", tap.action)
        assertEquals("compare/{id}", tap.path)
        assertEquals("Compare {title}: {summary}", tap.say)
        val call = c.tapFor(mapOf("id" to "creta", "title" to "Creta SX(O)", "price" to "₹19.2 lakh"), summary = "The Creta, please")!!
        assertEquals("open_comparison", call.action, "an explicit action wins; the path rides along")
        assertEquals(mapOf("id" to "creta", "label" to "Creta SX(O) at ₹19.2 lakh", "note" to "", "fixed" to "compare", "path" to "compare/creta"), call.args)
        assertEquals(listOf("id", "label", "note", "fixed", "path"), call.args.keys.toList(), "the path last, as the contract adds it")
        assertEquals("Compare Creta SX(O): The Creta, please", call.say)
        assertEquals("Compare Creta SX(O): I'll take it", c.tapFor(mapOf("title" to "Creta SX(O)", "summary" to "I'll take it"))!!.say, "no summary given: the item's own")
    }

    @Test
    fun aSayThatFillsToNothingLeavesYourOwnSentence() {
        assertNull(component(onSelect = mapOf("action" to "go", "say" to "{missing}")).tapFor(emptyMap())!!.say)
        assertNull(component(onSelect = mapOf("action" to "go")).tapFor(emptyMap())!!.say, "none set")
    }

    @Test
    fun theTapIsReadUnderEitherNameTheFrameUses() {
        assertEquals("go", component(onSelect = mapOf("action" to "go"), key = "onSelect").onSelect?.action)
        val nullFirst = GraineComponent("w1", "choices", null, mapOf("on_select" to null, "onSelect" to mapOf("action" to "go")))
        assertEquals("go", nullFirst.onSelect?.action, "a null on_select gives way to onSelect")
        val both = GraineComponent("w1", "choices", null, mapOf("on_select" to mapOf("action" to "first"), "onSelect" to mapOf("action" to "second")))
        assertEquals("first", both.onSelect?.action)
    }

    @Test
    fun argumentsAreAtMostTenWordsOfAtMost200Characters() {
        val tap = component(onSelect = mapOf("action" to "go", "args" to linkedMapOf(
            " spaced " to "x", "" to "empty key", "  " to "blank key", "t" to true, "n" to 2L, "price" to 1299.0, "rate" to 19.5,
            "big" to 1e21, "tiny" to 1e-7, "small" to 0.000001, "list" to listOf(1), "map" to mapOf("a" to 1), "nothing" to null,
        ))).onSelect!!
        assertEquals(
            mapOf("spaced" to "x", "t" to "true", "n" to "2", "price" to "1299", "rate" to "19.5", "big" to "1e+21", "tiny" to "1e-7", "small" to "0.000001"),
            tap.args, "a number in JavaScript's words; lists, maps and nothing are not arguments",
        )
        val many = component(onSelect = mapOf("action" to "go", "args" to (0 until 12).associate { "k$it" to "v".repeat(250) })).onSelect!!
        assertEquals((0 until 10).map { "k$it" }, many.args.keys.toList(), "the first ten, in order")
        assertTrue(many.args.values.all { it.length == 200 })
        // JavaScript walks an object's index-like keys first, smallest first.
        val ordered = component(onSelect = mapOf("action" to "go", "args" to linkedMapOf("b" to "1", "2" to "2", "a" to "3", "1" to "4", "01" to "5"))).onSelect!!
        assertEquals(listOf("1", "2", "b", "a", "01"), ordered.args.keys.toList())
        assertEquals("p".repeat(200), component(onSelect = mapOf("path" to "  " + "p".repeat(300))).onSelect!!.path)
        assertEquals("s".repeat(200), component(onSelect = mapOf("action" to "go", "say" to "s".repeat(500))).onSelect!!.say)
        assertEquals("go", component(onSelect = mapOf("action" to listOf(" GO "))).onSelect!!.action, "a one-item list reads as its item, as JavaScript reads it")
    }

    @Test
    fun placeholdersAreFilledAsTheContractFillsThem() {
        fun fill(t: String, item: Map<String, Any?>) = ComponentTaps.fill(t, item)
        assertEquals("", fill("{missing}", mapOf("id" to "x")), "never left as {missing}")
        assertEquals("7-", fill("{id}-{title}", mapOf("id" to 7L, "title" to null)))
        assertEquals("{a}", fill("{{id}}", mapOf("id" to "a")))
        assertEquals("{ id }", fill("{ id }", mapOf("id" to "a")))
        assertEquals("true", fill("{_x1}", mapOf("_x1" to true)))
        assertEquals("{9x}", fill("{9x}", mapOf("9x" to "no")))
        assertEquals("19.5 and 1299", fill("{a} and {b}", mapOf("a" to 19.5, "b" to 1299.0)))
        assertEquals("a,b", fill("{ids}", mapOf("ids" to listOf("a", "b"))), "a list as JavaScript writes it")
        assertEquals("$1 \\1", fill("{x}", mapOf("x" to "$1 \\1")), "a value goes in as it is")
    }

    @Test
    fun numbersAreWrittenAsJavaScriptWritesThem() {
        val cases = mapOf(
            2.0 to "2", -2.0 to "-2", 19.5 to "19.5", 0.5 to "0.5", 0.1 + 0.2 to "0.30000000000000004", 100.0 to "100",
            123456789.0 to "123456789", 1.2345678901234568E20 to "123456789012345680000", 1e21 to "1e+21", 1.5e22 to "1.5e+22",
            0.000001 to "0.000001", 1.5e-5 to "0.000015", 1e-7 to "1e-7", 1.25e-7 to "1.25e-7", 0.0 to "0", -0.0 to "0",
            Double.NaN to "NaN", Double.POSITIVE_INFINITY to "Infinity", Double.NEGATIVE_INFINITY to "-Infinity",
        )
        for ((d, words) in cases) assertEquals(words, JsRead.number(d), "$d")
    }

    // ── a component built from blocks ─────────────────────────────────────

    private val canvas = listOf(
        mapOf("id" to "b1", "type" to "heading", "content" to "Your plan"),
        mapOf("id" to "b2", "type" to "text", "content" to "  Three things to check.\nThen tap below.  "),
        mapOf("id" to "b3", "type" to "image", "imageUrl" to "https://cdn.example.com/a.png"),
        mapOf("id" to "b4", "type" to "divider"),
        mapOf("id" to "b5", "type" to "button", "content" to "Talk to sales"),
    )

    @Test
    fun aBlankComponentsBlocksComeInOrder() {
        val c = component(kind = "blank", data = mapOf("blocks" to canvas))
        assertEquals(
            listOf(
                GraineComponentBlock("b1", GraineComponentBlockType.HEADING, content = "Your plan"),
                GraineComponentBlock("b2", GraineComponentBlockType.TEXT, content = "Three things to check.\nThen tap below."),
                GraineComponentBlock("b3", GraineComponentBlockType.IMAGE, imageUrl = "https://cdn.example.com/a.png"),
                GraineComponentBlock("b4", GraineComponentBlockType.DIVIDER),
                GraineComponentBlock("b5", GraineComponentBlockType.BUTTON, content = "Talk to sales"),
            ),
            c.blocks,
        )
        assertEquals(listOf("heading", "text", "image", "divider", "button"), GraineComponentBlockType.entries.map { it.wire })
        assertEquals(GraineComponentSurface.CARD, c.style.surface, "a component built from blocks is a card")
    }

    @Test
    fun onlyWhatCanBeDrawnIsKept() {
        val rows = listOf(
            mapOf("id" to "a", "type" to " Heading ", "content" to "Kept, in any case"),
            mapOf("id" to "b", "type" to "video", "content" to "a type this version does not know"),
            mapOf("id" to "c", "type" to "heading"),
            mapOf("id" to "d", "type" to "text", "content" to "   "),
            mapOf("id" to "e", "type" to "button", "content" to 42),
            mapOf("id" to "f", "type" to "image"),
            mapOf("id" to "g", "type" to "image", "imageUrl" to " /samples/a.png ", "content" to "not its field"),
            mapOf("id" to "h", "type" to "divider", "content" to "not its field", "imageUrl" to "/x.png"),
            "not a block", null, listOf("heading"), mapOf("type" to 3, "content" to "x"),
            mapOf("id" to "i", "type" to "text", "content" to "x", "imageUrl" to "/y.png"),
        )
        val blocks = component(kind = "blank", data = mapOf("blocks" to rows)).blocks
        assertEquals(listOf("a", "g", "h", "i"), blocks.map { it.id })
        assertEquals(GraineComponentBlock("a", GraineComponentBlockType.HEADING, content = "Kept, in any case"), blocks[0])
        assertEquals(GraineComponentBlock("g", GraineComponentBlockType.IMAGE, imageUrl = "/samples/a.png"), blocks[1], "each block keeps only its own field")
        assertEquals(GraineComponentBlock("h", GraineComponentBlockType.DIVIDER), blocks[2])
        assertEquals(GraineComponentBlock("i", GraineComponentBlockType.TEXT, content = "x"), blocks[3])
    }

    @Test
    fun everyBlockHasAKeyOfItsOwn() {
        val rows = listOf(
            mapOf("type" to "divider"),
            mapOf("id" to "  ", "type" to "divider"),
            mapOf("id" to "x", "type" to "divider"),
            mapOf("id" to "x", "type" to "divider"),
            mapOf("id" to "block-5", "type" to "divider"),
            mapOf("id" to 7, "type" to "divider"),
        )
        assertEquals(
            listOf("block-0", "block-1", "x", "block-3", "block-5", "block-5-5"),
            component(kind = "blank", data = mapOf("blocks" to rows)).blocks.map { it.id },
        )
    }

    @Test
    fun onlyAComponentBuiltFromBlocksHasBlocks() {
        val data = mapOf("blocks" to canvas)
        assertTrue(component(kind = "product_list", data = data).blocks.isEmpty())
        assertTrue(GraineComponent("w1", null, null, mapOf("data" to data)).blocks.isEmpty(), "no kind")
        assertEquals(5, component(kind = " Blank ", data = data).blocks.size, "the kind as the surface rule reads it")
        val cyclic = ArrayList<Any?>().also { it.add(it) }
        for (junk in listOf(null, "blocks", mapOf("blocks" to "nope"), mapOf("blocks" to mapOf("0" to canvas[0])), emptyMap<String, Any?>(), mapOf("blocks" to emptyList<Any?>()), mapOf("blocks" to cyclic))) {
            assertTrue(component(kind = "blank", data = junk).blocks.isEmpty(), "$junk".take(40))
        }
    }

    // ── pictures on the agent's origin ────────────────────────────────────

    @Test
    fun rootRelativePicturesAreDrawnOnTheAgentsOrigin() = runTest {
        val engine = FakeEngine()
        Graine.engineFactory = { _, _ -> engine }
        Graine.configure(GraineConfig(baseUrl = "https://www.graine.ai/", publishableKey = "pk_test_key"))
        val heard = async(UnconfinedTestDispatcher(testScheduler)) { Graine.events.first { it is GraineEvent.Component } }
        val frame = mapOf(
            "widget_id" to "w1", "kind" to "blank",
            "data" to mapOf(
                "logo" to "/logo.png", "path" to "/checkout",
                "blocks" to listOf(
                    mapOf("id" to "b1", "type" to "image", "imageUrl" to "/samples/car-blue.png"),
                    mapOf("id" to "b2", "type" to "image", "imageUrl" to "https://cdn.example.com/a.png"),
                    mapOf("id" to "b3", "type" to "image", "imageUrl" to "//cdn.example.com/b.png"),
                    mapOf("id" to "b4", "type" to "image", "imageUrl" to "data:image/png;base64,AA=="),
                ),
            ),
        )
        engine.sink.event(GraineEvent.Component(componentOf(frame)!!))
        val shown = Graine.component.value!!
        assertEquals(
            listOf("https://www.graine.ai/samples/car-blue.png", "https://cdn.example.com/a.png", "//cdn.example.com/b.png", "data:image/png;base64,AA=="),
            shown.blocks.map { it.imageUrl },
        )
        assertEquals("https://www.graine.ai/logo.png", shown.data["logo"])
        assertEquals("/checkout", shown.data["path"], "a path that is not a picture is left alone")
        assertEquals(shown, (heard.await() as GraineEvent.Component).component, "the event carries the same component")
        // A list's items too, one level in; nothing to change, nothing made anew.
        val list = componentOf(mapOf("widget_id" to "w2", "kind" to "product_list", "data" to mapOf("items" to listOf(mapOf("id" to "a", "imageUrl" to "/a.png")))))!!
        engine.sink.event(GraineEvent.Component(list))
        assertEquals("https://www.graine.ai/a.png", (Graine.component.value!!.data["items"] as List<*>).filterIsInstance<Map<*, *>>().single()["imageUrl"])
        val plain = componentOf(mapOf("widget_id" to "w3", "kind" to "info_card", "data" to mapOf("imageUrl" to "https://cdn.example.com/c.png")))!!
        assertSame(plain, ComponentImages.on(plain, "https://www.graine.ai"))
        assertSame(list, ComponentImages.on(list, null), "no origin: nothing to resolve against")
    }

    @Test
    fun anAgentWithNoOriginLeavesPicturesAsTheyCame() {
        val engine = FakeEngine()
        Graine.install(engine)
        val c = componentOf(mapOf("widget_id" to "w1", "kind" to "info_card", "data" to mapOf("imageUrl" to "/a.png")))!!
        engine.sink.event(GraineEvent.Component(c))
        assertEquals("/a.png", Graine.component.value!!.data["imageUrl"])
    }
}
