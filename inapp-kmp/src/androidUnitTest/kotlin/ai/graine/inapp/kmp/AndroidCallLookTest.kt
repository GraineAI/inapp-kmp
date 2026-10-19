package ai.graine.inapp.kmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * THE CALL SCREEN'S LOOK, from the shared contract (sdk/contract/design.json):
 * every design's call tokens as the session carries them read into
 * [GraineThemeTokens.Call] exactly — the look and each moment's own — and a
 * session from before the look (none of its keys) derived exactly as the
 * server writes a design that sets none. The screens themselves are the
 * platform SDKs': `GraineCallScreen()` hosts them (pinned below).
 */
class AndroidCallLookTest {
    private val lookKeys = listOf(
        "preset", "layout", "backdrop", "ink", "avatarSize", "rings", "avatarRingColor", "buttonStyle", "buttonShape", "buttonSize",
        "buttonLabels", "actions", "visualizer", "visualizerColor", "visualizerSensitivity", "captionStyle", "captionPosition",
        "captionFill", "captionFillAlpha", "nameSize", "nameWeight", "statusSize", "statusWeight", "states",
    )

    private fun contract(): Map<String, Any?> {
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder", dir != null)
        return Json.obj(File(dir!!, "design.json").readText())!!
    }

    /** JSON values in one comparable shape: every number a Double. */
    private fun canon(v: Any?): Any? = when (v) {
        is Number -> v.toDouble()
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to canon(x) }
        is List<*> -> v.map { canon(it) }
        else -> v
    }

    private fun backdrop(b: GraineThemeTokens.Call.Backdrop) = mapOf(
        "type" to b.type, "stops" to b.stops.map { mapOf("color" to it.color, "at" to it.at) }, "angle" to b.angle,
        "motion" to b.motion, "intensity" to b.intensity, "scrim" to b.scrim,
    )

    private fun actions(a: Map<String, GraineThemeTokens.Call.Action>) = a.mapValues { (_, x) ->
        mapOf("fill" to x.fill, "fillAlpha" to x.fillAlpha, "icon" to x.icon, "stroke" to x.stroke, "activeFill" to x.activeFill, "activeIcon" to x.activeIcon)
    }

    private fun look(l: GraineThemeTokens.Call.Look) = mapOf(
        "backdrop" to backdrop(l.backdrop), "ink" to l.ink, "text" to l.text, "textMuted" to l.textMuted,
        "avatarRingColor" to l.avatarRingColor, "actions" to actions(l.actions), "visualizerColor" to l.visualizerColor,
        "captionFill" to l.captionFill, "captionFillAlpha" to l.captionFillAlpha,
    )

    /** The look keys of [k], as the session spells them. */
    private fun lookOf(k: GraineThemeTokens.Call): Map<String, Any?> = mapOf(
        "preset" to k.preset, "layout" to k.layout, "backdrop" to backdrop(k.backdrop), "ink" to k.ink, "avatarSize" to k.avatarSize,
        "rings" to k.rings, "avatarRingColor" to k.avatarRingColor, "buttonStyle" to k.buttonStyle, "buttonShape" to k.buttonShape,
        "buttonSize" to k.buttonSize, "buttonLabels" to k.buttonLabels, "actions" to actions(k.actions), "visualizer" to k.visualizer,
        "visualizerColor" to k.visualizerColor, "visualizerSensitivity" to k.visualizerSensitivity, "captionStyle" to k.captionStyle,
        "captionPosition" to k.captionPosition, "captionFill" to k.captionFill, "captionFillAlpha" to k.captionFillAlpha,
        "nameSize" to k.nameSize, "nameWeight" to k.nameWeight, "statusSize" to k.statusSize, "statusWeight" to k.statusWeight,
        "states" to k.states.mapValues { (_, l) -> look(l) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun cases(fx: Map<String, Any?>) = (fx["cases"] as List<Any?>).map { it as Map<String, Any?> }

    @Test
    fun everyDesignsCallLookIsReadExactlyTheMomentsOwnLooksIncluded() {
        var read = 0
        var withStates = 0
        for (c in cases(contract())) {
            val design = c["design"] as Map<*, *>
            for (scheme in listOf("light", "dark")) {
                val tokens = design[scheme] as Map<*, *>
                val want = tokens["call"] as Map<*, *>
                val k = ThemeBridge.parse(Json.write(tokens))!!.call!!
                assertEquals("${c["config"]} $scheme", canon(lookKeys.associateWith { want[it] }), canon(lookOf(k)))
                for (m in GraineThemeTokens.Call.MOMENTS) {
                    val own = (want["states"] as Map<*, *>)[m]
                    assertEquals("${c["config"]} $scheme $m", canon(own ?: look(k.look)), canon(look(k.lookFor(m))))
                }
                val st = want["statusText"] as Map<*, *>
                assertEquals(st["outgoing"], k.statusText.outgoing)
                assertEquals(st["reconnecting"], k.statusText.reconnecting)
                if (k.states.isNotEmpty()) withStates++
                read++
            }
        }
        assertTrue("the contract has designs", read > 40)
        assertTrue("and moments with looks of their own", withStates > 0)
    }

    @Test
    fun aCallScreenFromBeforeTheLookDerivesItExactlyAsTheServerWritesADesignWithNone() {
        var derived = 0
        for (c in cases(contract())) {
            val knobs = (c["config"] as Map<*, *>)["call"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            if (lookKeys.any { knobs.containsKey(it) }) continue // a design with a look of its own
            val design = c["design"] as Map<*, *>
            for (scheme in listOf("light", "dark")) {
                val tokens = design[scheme] as Map<*, *>
                val served = tokens["call"] as Map<*, *>
                val flat = served.filterKeys { it !in lookKeys }
                val k = ThemeBridge.parse(Json.write(tokens.toMutableMap().apply { put("call", flat) }))!!.call!!
                assertEquals("${c["config"]} $scheme", canon(lookKeys.associateWith { served[it] }), canon(lookOf(k)))
                derived++
            }
        }
        assertTrue("the contract holds designs saved before the look", derived > 20)
    }

    /**
     * `GraineCallScreen()` hosts the platform SDKs' own call screens — on
     * Android the call activity, on iOS the facade's call view controller —
     * so the look reaches a multiplatform app with the platform SDK, and
     * nothing in this library draws a call.
     */
    @Test
    fun theComposeCallScreenHostsTheNativeScreensOnBothTargets() {
        val module = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "inapp-kmp-compose") }
            .firstOrNull { it.isDirectory }
        assertTrue("inapp-kmp-compose beside this module", module != null)
        fun body(file: String): String {
            val src = File(module!!, file).readText()
            val at = src.indexOf("actual fun GraineCallScreen(")
            assertTrue("$file declares GraineCallScreen", at >= 0)
            val end = src.indexOf("\n}\n", at)
            return src.substring(at, if (end < 0) src.length else end)
        }
        val android = body("src/androidMain/kotlin/ai/graine/inapp/kmp/compose/GraineViews.android.kt")
        assertTrue("Android opens the SDK's call activity", android.contains("Intent(context, GraineCallActivity::class.java)"))
        val ios = body("src/iosMain/kotlin/ai/graine/inapp/kmp/compose/GraineViews.ios.kt")
        assertTrue("iOS hosts the SDK's call view controller", ios.contains("GraineViews.callScreen()"))
        val platform = File(module!!.parentFile, "inapp-kmp/src/iosMain/kotlin/ai/graine/inapp/kmp/Platform.ios.kt").readText()
        assertTrue("…which is the facade's own", platform.contains("fun callScreen(): UIViewController = facade(null).callViewController()"))
    }
}
