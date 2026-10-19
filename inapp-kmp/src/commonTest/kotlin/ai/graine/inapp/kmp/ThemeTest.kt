package ai.graine.inapp.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE BRAND'S TOKENS AS THE PLATFORM SDKS HAND THEM OVER — the contract's
 * token JSON (Android's `ThemeTokens.toJson()`, the iOS facade's
 * `themeJSON`), read into [GraineThemeTokens].
 */
class ThemeTest {
    private val colors = """{"background":"#0E0F11","surface":"#17181B","surfaceRaised":"#1F2125","text":"#F5F5F4",
        "textMuted":"#A0A5AE","border":"#2A2D33","accent":"#318CE7","onAccent":"#FFFFFF","accentSoft":"#1B2D44",
        "success":"#16A34A","warning":"#D97706","danger":"#DC2626","userBubble":"#318CE7","userBubbleText":"#FFFFFF",
        "agentBubble":"#1F2125","agentBubbleText":"#F5F5F4","scrim":"#000000"}"""

    private val chat = """{"title":"Asha","subtitle":"","avatar":"glyph","placeholder":"Ask anything","showTimestamps":true,
        "suggestions":["Track my order","Change my plan",7],"background":"#0E0F11","pattern":"dots","patternColor":"#2A2D33",
        "headerBackground":"#17181B","headerText":"#F5F5F4","headerSubtext":"#A0A5AE","composerBackground":"#17181B",
        "composerText":"#F5F5F4","composerPlaceholder":"#A0A5AE","composerBorder":"#2A2D33","size":"large",
        "showUpcomingCalls":false,"upcomingTitle":"Your calls","showHistory":true,"newConversation":true}"""

    private val call = """{"background":"#0E0F11","backgroundTo":"#17181B","text":"#F5F5F4","textMuted":"#A0A5AE",
        "avatar":"glyph","avatarShape":"squircle","avatarFill":"#318CE7","avatarText":"#FFFFFF","buttonFill":"#333435",
        "buttonIcon":"#F5F5F4","buttonActiveFill":"#F5F5F4","buttonActiveIcon":"#0E0F11","endFill":"#DC2626","endIcon":"#FFFFFF",
        "endShape":"pill","acceptFill":"#16A34A","acceptIcon":"#FFFFFF","declineFill":"#DC2626","declineIcon":"#FFFFFF",
        "callbackFill":"#318CE7","callbackIcon":"#FFFFFF","captions":false,"timer":true,"controls":["mute","end"],
        "ringtone":"soft","statusText":{"ringing":"Asha is calling","connecting":"Connecting…","ended":"Call ended","missed":"Missed call"}}"""

    private fun tokens(extra: String = ""): String = """{"v":1,"preset":"graine-classic","scheme":"dark","colors":$colors,
        "font":{"body":"system","heading":"DM Sans","scale":{"caption":12,"body":15,"title":17,"display":22},
                "weights":{"body":400,"strong":600,"title":700},"letterSpacing":{"body":0,"title":-0.01},"lineHeight":{"body":1.45,"title":1.25}},
        "shape":{"radius":{"sm":6,"md":10,"lg":14,"xl":20},"borderWidth":1,"launcher":"squircle"},
        "spacing":{"unit":4,"cardPadding":14,"gap":10},
        "elevation":{"card":{"y":4,"blur":12,"spread":0,"opacity":0.18,"color":"#000000"},"floating":null,"hardOffset":{"x":3,"y":3}},
        "motion":{"durationMs":220,"easing":"spring","bounce":0.2},
        "icons":{"style":"outline","stroke":1.75,"launcher":{"id":"spark","path":"M12 2v20","stroke":true}},
        "glass":{"blur":18,"surfaceOpacity":0.72},"bubbles":"outline","chrome":"window"$extra}"""

    @Test
    fun theTokensAreReadAsTheContractSpellsThem() {
        val t = ThemeBridge.parse(tokens(""","chat":$chat,"call":$call"""))!!
        assertEquals(1, t.v)
        assertEquals("graine-classic", t.preset)
        assertTrue(t.isDark)
        assertEquals("#318CE7", t.colors.accent)
        assertEquals("#000000", t.colors.scrim)
        assertEquals("DM Sans", t.font.heading)
        assertEquals(GraineThemeTokens.Font.Scale(12.0, 15.0, 17.0, 22.0), t.font.scale)
        assertEquals(GraineThemeTokens.Font.Weights(400, 600, 700), t.font.weights)
        assertEquals(-0.01, t.font.letterSpacing.title)
        assertEquals(1.45, t.font.lineHeight.body)
        assertEquals(GraineThemeTokens.Shape.Radius(6.0, 10.0, 14.0, 20.0), t.shape.radius)
        assertEquals("squircle", t.shape.launcher)
        assertEquals(GraineThemeTokens.Spacing(4.0, 14.0, 10.0), t.spacing)
        assertEquals(GraineThemeTokens.Shadow(4.0, 12.0, 0.0, 0.18, "#000000"), t.elevation.card)
        assertNull(t.elevation.floating)
        assertEquals(GraineThemeTokens.Offset(3.0, 3.0), t.elevation.hardOffset)
        assertEquals(GraineThemeTokens.Motion(220, "spring", 0.2), t.motion)
        assertEquals(GraineThemeTokens.Glyph("spark", "M12 2v20", true), t.icons.launcher)
        assertEquals(1.75, t.icons.stroke)
        assertEquals(GraineThemeTokens.Glass(18.0, 0.72), t.glass)
        assertEquals("outline", t.bubbles)
        assertEquals("window", t.chrome)

        val c = t.chat!!
        assertEquals("Asha", c.title)
        assertEquals(listOf("Track my order", "Change my plan"), c.suggestions, "a suggestion that is not words is skipped")
        assertEquals(GraineChatSize.LARGE, c.size)
        assertFalse(c.showUpcomingCalls)
        assertEquals("Your calls", c.upcomingTitle)
        assertTrue(c.showHistory && c.newConversation)

        val k = t.call!!
        assertEquals("#17181B", k.backgroundTo)
        assertEquals("pill", k.endShape)
        assertFalse(k.captions)
        assertEquals(listOf("mute", "end"), k.controls)
        assertEquals("Asha is calling", k.statusText.ringing)
        assertNull(k.statusText.scheduled, "left to the SDK's own words")
        assertEquals("Missed call", k.statusText.missed)
    }

    @Test
    fun aServerFromBeforeTheChatAndCallTokensSendsNeitherAndAnUnknownSizeIsNone() {
        val t = ThemeBridge.parse(tokens())!!
        assertNull(t.chat)
        assertNull(t.call)
        val odd = ThemeBridge.parse(tokens(""","chat":{"size":"huge"}"""))!!.chat!!
        assertNull(odd.size, "a size this build does not know: draw it compact")
        assertTrue(odd.showUpcomingCalls && odd.showHistory && odd.newConversation, "absent: on, as every SDK reads them")
    }

    @Test
    fun aCallScreenFromBeforeTheLookIsDrawnAsItAlwaysWas() {
        val k = ThemeBridge.parse(tokens(""","call":$call"""))!!.call!!
        assertEquals("classic", k.preset)
        assertEquals("classic", k.layout)
        assertEquals(
            GraineThemeTokens.Call.Backdrop("linear", listOf(GraineThemeTokens.Call.Stop("#0E0F11", 0.0), GraineThemeTokens.Call.Stop("#17181B", 1.0))),
            k.backdrop, "the flat gradient, top to bottom",
        )
        assertEquals("light", k.ink)
        assertEquals(112.0, k.avatarSize)
        assertEquals(listOf("pulse", "pulse", "none", "none", "none", "none", "none", "none"), GraineThemeTokens.Call.MOMENTS.map { k.rings.getValue(it) }, "a ring only while it rings")
        assertEquals(k.textMuted, k.avatarRingColor)
        assertEquals(listOf("solid", "circle", "below"), listOf(k.buttonStyle, k.buttonShape, k.buttonLabels))
        assertEquals(64.0, k.buttonSize)
        assertEquals(GraineThemeTokens.Call.Action("#333435", 1.0, "#F5F5F4", null, "#F5F5F4", "#0E0F11"), k.actions["mute"])
        assertEquals(GraineThemeTokens.Call.ACTIONS, k.actions.keys.toList())
        assertEquals(k.acceptFill, k.actions.getValue("answer").fill)
        assertEquals(k.endFill, k.actions.getValue("end").fill)
        assertEquals(k.callbackFill, k.actions.getValue("callback").fill)
        assertEquals(listOf("none", k.text, "plain", "middle", "#000000"), listOf(k.visualizer, k.visualizerColor, k.captionStyle, k.captionPosition, k.captionFill))
        assertEquals(0.0, k.captionFillAlpha)
        assertEquals(28.0, k.nameSize)
        assertEquals(700, k.nameWeight, "the design's own title weight")
        assertEquals(400, k.statusWeight)
        assertTrue(k.states.isEmpty())
        assertEquals(k.look, k.lookFor("missed"))
        assertNull(k.statusText.outgoing, "a placed call says the connecting words")
    }

    @Test
    fun theLookIsReadAsTheContractSpellsItAndAWordNobodyKnowsIsTheFirstListed() {
        val look = ""","preset":"aurora","layout":"orb","ink":"light","avatarSize":96,"buttonStyle":"glass","buttonShape":"pill",
            "buttonSize":72,"buttonLabels":"none",
            "backdrop":{"type":"mesh","stops":[{"color":"#145C53","at":0},{"color":"nope","at":0.5},{"color":"#311C84","at":1}],
                        "angle":180,"motion":"drift","intensity":0.6,"scrim":1},
            "rings":{"incoming":"glow","scheduled":"glow","outgoing":"ripple","connecting":"none","connected":"spin",
                     "reconnecting":"pulse","ended":"none","missed":"none"},
            "actions":{"mute":{"fill":"#FFFFFF","fillAlpha":0.18,"icon":"#F5F5F4","stroke":null,"activeFill":"#F5F5F4","activeIcon":"#145C53"},
                       "end":{"fill":"#B91C1C","fillAlpha":1,"icon":"#FFFFFF","stroke":"#FFFFFF","activeFill":"#B91C1C","activeIcon":"#FFFFFF"}},
            "visualizer":"wave","visualizerColor":"#FFFFFF","visualizerSensitivity":0.8,"captionStyle":"subtitle",
            "captionPosition":"bottom","captionFill":"#000000","captionFillAlpha":0.4,"nameSize":22,"nameWeight":500,
            "statusSize":15,"statusWeight":400,
            "states":{"ended":{"backdrop":{"type":"mesh","stops":[{"color":"#3F3F46","at":0}]},"text":"#F5F5F4"},"lunch":{"ink":"dark"}}"""
        val withLook = call.dropLast(1) + look + "}"
        val k = ThemeBridge.parse(tokens(""","call":$withLook"""))!!.call!!
        assertEquals(listOf("aurora", "orb", "light", "glass", "pill", "none"), listOf(k.preset, k.layout, k.ink, k.buttonStyle, k.buttonShape, k.buttonLabels))
        assertEquals(listOf(96.0, 72.0, 22.0, 15.0), listOf(k.avatarSize, k.buttonSize, k.nameSize, k.statusSize))
        assertEquals(500, k.nameWeight)
        assertEquals("mesh", k.backdrop.type)
        assertEquals(listOf("#145C53", "#311C84"), k.backdrop.stops.map { it.color }, "a stop that is not a colour is skipped")
        assertEquals("drift", k.backdrop.motion)
        assertEquals("none", k.rings["connected"], "a ring this build does not know is the first listed")
        assertEquals("ripple", k.rings["outgoing"])
        assertEquals(0.18, k.actions.getValue("mute").fillAlpha)
        assertNull(k.actions.getValue("mute").stroke)
        assertEquals("#FFFFFF", k.actions.getValue("end").stroke)
        assertEquals(k.acceptFill, k.actions.getValue("answer").fill, "a button the session left out is the flat one")
        assertEquals(listOf("wave", "subtitle", "bottom"), listOf(k.visualizer, k.captionStyle, k.captionPosition))
        assertEquals(setOf("ended"), k.states.keys, "a moment nobody knows is ignored")
        val ended = k.lookFor("ended")
        assertEquals("#3F3F46", ended.backdrop.stops.single().color)
        assertEquals(k.actions, ended.actions, "what the moment leaves out is the screen's")
        assertEquals(k.look, k.lookFor("connected"))
        val odd = ThemeBridge.parse(tokens(""","call":${call.dropLast(1)},"layout":"sideways","buttonSize":"big"}"""))!!.call!!
        assertEquals("classic", odd.layout)
        assertEquals(64.0, odd.buttonSize)
    }

    @Test
    fun aSetWithoutItsColoursIsNoDesign() {
        assertNull(ThemeBridge.parse(null))
        assertNull(ThemeBridge.parse("not json"))
        assertNull(ThemeBridge.parse("""{"v":1,"preset":"x"}"""))
        assertNull(ThemeBridge.parse("""{"colors":{"background":"#000000"}}"""), "colours half there are no colours")
    }

    @Test
    fun aValueOfTheWrongTypeTakesAPlainDefaultNeverACrash() {
        val t = ThemeBridge.parse("""{"colors":$colors,"font":"big","motion":{"durationMs":"fast"},"scheme":"sepia","glass":7}""")!!
        assertEquals("light", t.scheme, "a scheme this build does not know reads light")
        assertEquals("system", t.font.body)
        assertEquals(0, t.motion.durationMs)
        assertNull(t.glass)
        assertEquals("filled", t.bubbles)
        assertEquals("none", t.chrome)
    }
}
