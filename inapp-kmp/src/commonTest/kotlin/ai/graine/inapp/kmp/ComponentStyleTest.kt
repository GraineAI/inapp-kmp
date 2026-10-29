package ai.graine.inapp.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 0.14.8 — A COMPONENT'S LOOK, AS THE DASHBOARD SET IT: [GraineComponent.style]
 * resolved from the payload's `style` exactly as the SDK contract resolves it
 * (protocol.ts `normaliseWidgetStyle`, `resolveWidgetLook`, `widgetSurface`) —
 * the preset's defaults, each knob on top, the place on screen for the kind,
 * and anything this build cannot read left out. The contract's own answers
 * and lists are held to this by ComponentStyleContractTest; these say the
 * rules in words, on every platform.
 */
class ComponentStyleTest {
    private fun look(style: Any?, kind: String? = "x") = ComponentStyles.resolve(style, kind)

    private fun component(style: Any?, kind: String = "product_list") =
        GraineComponent("w1", kind, null, mapOf("widget_id" to "w1", "kind" to kind, "style" to style))

    // ── the words ─────────────────────────────────────────────────────────

    @Test
    fun everyValueSaysTheDashboardsWord() {
        assertEquals(listOf("gallery", "cards", "list", "spotlight", "compact"), GraineComponentPreset.entries.map { it.wire })
        assertEquals(listOf("small", "medium", "large"), GraineComponentImageSize.entries.map { it.wire })
        assertEquals(listOf("comfortable", "compact"), GraineComponentDensity.entries.map { it.wire })
        assertEquals(listOf("filled", "outline", "row"), GraineComponentButton.entries.map { it.wire })
        assertEquals(listOf("screen", "sheet", "card"), GraineComponentSurface.entries.map { it.wire })
        assertEquals(listOf("auto", "screen", "sheet", "card"), ComponentStyles.SURFACE_CHOICES)
        assertEquals(listOf("auto", "ios", "material", "oneui"), GraineComponentPlatform.entries.map { it.wire })
        assertEquals(listOf("one", "many"), GraineComponentSelect.entries.map { it.wire })
    }

    // ── the preset, then the knobs ────────────────────────────────────────

    @Test
    fun noLookIsCardsInThePlaceItsKindTakes() {
        val none = look(null, "product_list")
        assertEquals(
            GraineComponentStyle(
                preset = GraineComponentPreset.CARDS, imageSize = GraineComponentImageSize.MEDIUM, density = GraineComponentDensity.COMFORTABLE,
                button = GraineComponentButton.FILLED, facts = true, columns = 1, surface = GraineComponentSurface.SCREEN,
                radius = null, accent = null, platform = GraineComponentPlatform.AUTO, select = GraineComponentSelect.ONE, maxSelect = 3,
            ),
            none,
        )
        assertEquals(GraineComponentStyle(), look(emptyMap<String, Any?>(), "something_new"), "the defaults are an unknown kind's look")
        assertEquals(ComponentStyles.DEFAULT_PRESET, GraineComponentStyle().preset)
        assertEquals(ComponentStyles.MAX_SELECT_DEFAULT, GraineComponentStyle().maxSelect)
    }

    @Test
    fun eachPresetBringsItsOwnPictureSizeAndButton() {
        fun d(preset: String) = look(mapOf("preset" to preset)).let { it.imageSize to it.button }
        assertEquals(GraineComponentImageSize.LARGE to GraineComponentButton.FILLED, d("gallery"))
        assertEquals(GraineComponentImageSize.MEDIUM to GraineComponentButton.FILLED, d("cards"))
        assertEquals(GraineComponentImageSize.SMALL to GraineComponentButton.ROW, d("list"))
        assertEquals(GraineComponentImageSize.LARGE to GraineComponentButton.FILLED, d("spotlight"))
        assertEquals(GraineComponentImageSize.SMALL to GraineComponentButton.OUTLINE, d("compact"))
        for (p in GraineComponentPreset.entries) assertEquals(ComponentStyles.defaults(p), d(p.wire), p.wire)
        assertEquals(GraineComponentPreset.CARDS, look(mapOf("preset" to "hologram")).preset, "a preset this build has never heard of is Cards")
    }

    @Test
    fun theKnobsOverrideThePreset() {
        val k = look(mapOf("preset" to "list", "imageSize" to "large", "button" to "outline", "density" to "compact", "facts" to false), "product_list")
        assertEquals(GraineComponentPreset.LIST, k.preset)
        assertEquals(GraineComponentImageSize.LARGE, k.imageSize)
        assertEquals(GraineComponentButton.OUTLINE, k.button)
        assertEquals(GraineComponentDensity.COMPACT, k.density)
        assertEquals(false, k.facts)
        val all = look(
            mapOf(
                "preset" to "Gallery", "imageSize" to "LARGE", "density" to "compact", "button" to "row", "facts" to false, "columns" to "2",
                "surface" to "sheet", "radius" to 31.6, "accent" to " #ff8800 ", "platform" to "oneui", "select" to "MANY", "maxSelect" to 4,
                "theme" to "dark", "onclick" to "alert(1)", "nested" to mapOf("a" to 1),
            ),
        )
        assertEquals(
            GraineComponentStyle(
                preset = GraineComponentPreset.GALLERY, imageSize = GraineComponentImageSize.LARGE, density = GraineComponentDensity.COMPACT,
                button = GraineComponentButton.ROW, facts = false, columns = 2, surface = GraineComponentSurface.SHEET, radius = 24,
                accent = "#FF8800", platform = GraineComponentPlatform.ONEUI, select = GraineComponentSelect.MANY, maxSelect = 4,
            ),
            all,
            "words in any case, trimmed; keys nobody reads are dropped",
        )
    }

    @Test
    fun twoColumnsOnlyForGalleryAndCards() {
        assertEquals(2, look(mapOf("preset" to "gallery", "columns" to 2)).columns)
        assertEquals(2, look(mapOf("preset" to "cards", "columns" to 2)).columns)
        assertEquals(2, look(mapOf("columns" to 2)).columns, "Cards is the default preset")
        for (p in listOf("list", "spotlight", "compact")) assertEquals(1, look(mapOf("preset" to p, "columns" to 2)).columns, p)
        assertEquals(2, look(mapOf("columns" to "2")).columns, "the word 2")
        assertEquals(2, look(mapOf("columns" to 2.0)).columns)
        assertEquals(1, look(mapOf("columns" to 3)).columns)
        assertEquals(1, look(mapOf("columns" to " 2")).columns, "only the bare word")
        assertNull(ComponentStyles.normalise(mapOf("columns" to 3)).columns, "neither 1 nor 2 is no choice")
        assertEquals(1, ComponentStyles.normalise(mapOf("columns" to "1")).columns)
    }

    // ── where it goes ─────────────────────────────────────────────────────

    @Test
    fun theSurfaceFollowsTheKindUnlessTheDashboardChoseOne() {
        val screen = GraineComponentSurface.SCREEN
        val sheet = GraineComponentSurface.SHEET
        val card = GraineComponentSurface.CARD
        val rule = mapOf(
            "product_list" to screen, "products" to screen, "plan_comparison" to sheet, "choices" to sheet, "quick_actions" to sheet,
            "calendar_slots" to sheet, "info_card" to card, "" to card, "  " to card, "some_future_kind" to card, "form" to card,
            "  Product_List  " to screen, "PLAN_COMPARISON" to sheet,
        )
        for ((kind, surface) in rule) {
            assertEquals(surface, ComponentStyles.surface(kind), "'$kind'")
            assertEquals(surface, look(null, kind).surface, "'$kind', no look")
        }
        assertEquals(card, ComponentStyles.surface(null))
        assertEquals(sheet, look(mapOf("surface" to "sheet"), "product_list").surface, "the dashboard's choice wins")
        assertEquals(card, look(mapOf("surface" to "card"), "product_list").surface)
        assertEquals(screen, look(mapOf("surface" to "screen"), "info_card").surface)
        assertEquals(screen, look(mapOf("surface" to "AUTO"), "product_list").surface, "auto keeps the rule")
        assertEquals("auto", ComponentStyles.normalise(mapOf("surface" to "AUTO")).surface, "auto is kept as a choice")
        assertEquals(screen, look(mapOf("surface" to "toast"), "product_list").surface, "an unknown surface is no choice")
    }

    // ── the values that must be exactly right ─────────────────────────────

    @Test
    fun anAccentIsSixHexDigitsAndComesBackInCapitals() {
        assertEquals("#FF8800", look(mapOf("accent" to " #ff8800 ")).accent)
        assertEquals("#A1B2C3", look(mapOf("accent" to "#A1b2C3")).accent)
        assertEquals("#112233", look(mapOf("accent" to " #112233\n")).accent, "JavaScript's white space is trimmed")
        for (bad in listOf("red", "#abc", "#12345", "#1234567", "ff8800", "#ff88zz", "# ff8800", "", 0xFF8800, true, listOf("#ff8800"))) {
            assertNull(look(mapOf("accent" to bad)).accent, "$bad")
        }
        assertNull(look(null).accent, "none: the agent's design's accent")
    }

    @Test
    fun aRadiusIsANumberRoundedAndHeldTo0To24() {
        assertEquals(13, look(mapOf("radius" to 12.5)).radius, "a half rounds up")
        assertEquals(11, look(mapOf("radius" to 11.49)).radius)
        assertEquals(0, look(mapOf("radius" to 0.49999999999999994)).radius, "as JavaScript rounds it")
        assertEquals(0, look(mapOf("radius" to -4)).radius)
        assertEquals(0, look(mapOf("radius" to -0.5)).radius)
        assertEquals(24, look(mapOf("radius" to 31.6)).radius)
        assertEquals(24, look(mapOf("radius" to 1e300)).radius)
        assertEquals(8, look(mapOf("radius" to 8L)).radius)
        assertEquals(0, look(mapOf("radius" to 0)).radius, "0 is a choice: square corners")
        for (bad in listOf("12", " 7.5 ", true, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null, listOf(12))) {
            assertNull(look(mapOf("radius" to bad)).radius, "$bad")
        }
    }

    @Test
    fun severalMayBeChosenTwoToEightThreeByDefault() {
        val many = look(mapOf("select" to "many"))
        assertEquals(GraineComponentSelect.MANY, many.select)
        assertEquals(3, many.maxSelect)
        assertEquals(2, look(mapOf("select" to "MANY", "maxSelect" to 1)).maxSelect)
        assertEquals(8, look(mapOf("select" to "many", "maxSelect" to 20)).maxSelect)
        assertEquals(5, look(mapOf("maxSelect" to 4.5)).maxSelect)
        assertEquals(3, look(mapOf("maxSelect" to "4")).maxSelect, "a word is not a number")
        assertEquals(GraineComponentSelect.ONE, look(mapOf("select" to "some")).select)
        assertEquals(GraineComponentSelect.ONE, look(null).select)
    }

    @Test
    fun theOtherKnobsAreAllowlistedToo() {
        assertEquals(GraineComponentPlatform.MATERIAL, look(mapOf("platform" to "Material")).platform)
        assertEquals(GraineComponentPlatform.AUTO, look(mapOf("platform" to "windows")).platform)
        assertEquals(GraineComponentDensity.COMFORTABLE, look(mapOf("density" to "COMFORTABLE")).density)
        assertEquals(true, look(mapOf("facts" to true)).facts)
        assertEquals(true, look(mapOf("facts" to "no")).facts, "only a yes or a no turns the facts off")
        val chosen = look(mapOf("radius" to 8, "accent" to "#112233"))
        assertEquals(8, chosen.radius)
        assertEquals("#112233", chosen.accent)
    }

    // ── anything else ─────────────────────────────────────────────────────

    @Test
    fun junkIsTheDefaultLookAndNeverThrows() {
        val cyclic = ArrayList<Any?>().also { it.add(it) }
        for (raw in listOf(null, "", "gallery", 0, 42, 4.5, true, emptyList<Any?>(), listOf("gallery"), listOf(mapOf("preset" to "gallery")), Any())) {
            assertEquals(GraineComponentStyle(), look(raw), "$raw")
        }
        assertEquals(
            GraineComponentStyle(),
            look(
                mapOf(
                    "preset" to mapOf("a" to 1), "imageSize" to 3, "density" to null, "button" to listOf("filled", "row"), "facts" to 1,
                    "columns" to listOf(2), "surface" to cyclic, "radius" to mapOf("x" to 1), "accent" to null, "platform" to false,
                    "select" to listOf(null), "maxSelect" to "eight",
                ),
            ),
            "a value of the wrong shape is no choice",
        )
        assertEquals(GraineComponentStyle(), look(mapOf(1 to "gallery", null to "cards")), "keys that are not words")
    }

    @Test
    fun aOneItemListReadsAsItsItemAsJavaScriptReadsIt() {
        assertEquals(GraineComponentPreset.SPOTLIGHT, look(mapOf("preset" to listOf("spotlight"))).preset)
        assertEquals(GraineComponentPreset.LIST, look(mapOf("preset" to listOf(listOf(" List ")))).preset)
        assertEquals(GraineComponentPreset.CARDS, look(mapOf("preset" to listOf("spotlight", "list"))).preset, "two words are no word")
    }

    // ── on the component ──────────────────────────────────────────────────

    @Test
    fun theComponentCarriesItsLookResolvedForItsKind() {
        val c = component(mapOf("preset" to "gallery", "columns" to 2, "radius" to 12.5, "select" to "many", "maxSelect" to 4))
        assertEquals(GraineComponentPreset.GALLERY, c.style.preset)
        assertEquals(2, c.style.columns)
        assertEquals(13, c.style.radius)
        assertEquals(GraineComponentSurface.SCREEN, c.style.surface, "a product list takes the screen")
        assertEquals(GraineComponentSelect.MANY, c.style.select)
        assertEquals(4, c.style.maxSelect)
        assertEquals(GraineComponentSurface.SHEET, component(null, "choices").style.surface, "no look: Cards, where its kind goes")
        assertEquals(GraineComponentStyle(surface = GraineComponentSurface.SHEET), component(null, "choices").style)
        assertEquals(GraineComponentStyle(), GraineComponent("w2", null, null, emptyMap()).style, "nothing at all")
        assertEquals(GraineComponentPreset.LIST, c.copy(payload = c.payload + ("style" to mapOf("preset" to "list"))).style.preset, "read from its own payload")
    }

    @Test
    fun theLookCrossesTheIosBridgeAsTheFrameCarriesIt() {
        val event = Bridge.event(
            "widget",
            """{"widget":{"widget_id":"w1","kind":"plan_comparison","data":{"title":"Two plans"},
               "style":{"preset":"Compact","radius":7.5,"accent":"#0a7f3c","columns":"2","surface":"auto","facts":false}}}""",
        ) as GraineEvent.Component
        val look = event.component.style
        assertEquals(GraineComponentPreset.COMPACT, look.preset)
        assertEquals(8, look.radius)
        assertEquals("#0A7F3C", look.accent)
        assertEquals(1, look.columns, "two columns are Gallery's and Cards' only")
        assertEquals(GraineComponentSurface.SHEET, look.surface)
        assertEquals(false, look.facts)
        assertEquals(GraineComponentButton.OUTLINE, look.button)
        val bare = Bridge.event("widget", """{"widget":{"widget_id":"w2","kind":"info_card"}}""") as GraineEvent.Component
        assertEquals(GraineComponentStyle(), bare.component.style, "the runtime sends a style only when one is set")
    }
}
