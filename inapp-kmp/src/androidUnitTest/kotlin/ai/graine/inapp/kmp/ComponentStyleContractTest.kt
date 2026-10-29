package ai.graine.inapp.kmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A COMPONENT'S LOOK IS THE SDK CONTRACT'S, ANSWER FOR ANSWER AND WORD FOR WORD.
 *
 * sdk/contract/rn-parity.json holds what the React Native SDK's own built code
 * answers — generate-rn-parity.mjs runs protocol.ts's normaliseWidgetStyle,
 * resolveWidgetLook and widgetSurface over fixed inputs — and the Android,
 * iOS and Flutter SDKs reproduce every answer; so does this one. The lists
 * and the defaults are read from protocol.ts itself, which owns them, so a
 * preset, a size or a surface added there fails here until this SDK knows it;
 * and the two 12-second waits are the React Native SDK's. All of it sits
 * beside this library in the monorepo: an exported checkout has none of it,
 * and skips.
 */
class ComponentStyleContractTest {
    private fun sdkDir(): File {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder", dir != null)
        return File(dir!!).canonicalFile.parentFile
    }

    private fun source(path: String): String {
        val file = File(sdkDir(), path)
        assertTrue("$file is missing", file.isFile)
        return file.readText()
    }

    @Suppress("UNCHECKED_CAST")
    private fun cases(name: String): List<Map<String, Any?>> {
        val parity = Json.obj(source("contract/rn-parity.json")) ?: error("rn-parity.json is not a JSON object")
        return (parity[name] as List<Map<String, Any?>>).also { assertTrue("rn-parity.json has no $name", it.isNotEmpty()) }
    }

    /** JSON values in one comparable shape: every number a Double. */
    private fun canon(v: Any?): Any? = when (v) {
        is Number -> v.toDouble()
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to canon(x) }
        is List<*> -> v.map { canon(it) }
        else -> v
    }

    /** `normaliseWidgetStyle`'s answer in its own words: only what was chosen. */
    private fun chosen(c: ComponentStyles.Chosen): Map<String, Any?> = linkedMapOf<String, Any?>(
        "preset" to c.preset?.wire, "imageSize" to c.imageSize?.wire, "density" to c.density?.wire, "button" to c.button?.wire,
        "facts" to c.facts, "columns" to c.columns, "surface" to c.surface, "radius" to c.radius, "accent" to c.accent,
        "platform" to c.platform?.wire, "select" to c.select?.wire, "maxSelect" to c.maxSelect,
    ).filterValues { it != null }

    /** `resolveWidgetLook`'s answer in its own words: radius and accent only when chosen. */
    private fun look(s: GraineComponentStyle): Map<String, Any?> = linkedMapOf<String, Any?>(
        "preset" to s.preset.wire, "imageSize" to s.imageSize.wire, "density" to s.density.wire, "button" to s.button.wire,
        "facts" to s.facts, "columns" to s.columns, "surface" to s.surface.wire, "radius" to s.radius, "accent" to s.accent,
        "platform" to s.platform.wire, "select" to s.select.wire, "maxSelect" to s.maxSelect,
    ).filterValues { it != null }

    // ── the answers, from the React Native SDK's own code ─────────────────

    @Test
    fun everyStyleIsAllowlistedAsTheReactNativeSdkAnswers() {
        for (c in cases("widgetStyle")) {
            assertEquals("normaliseWidgetStyle(${Json.write(c["input"])})", canon(c["output"]), canon(chosen(ComponentStyles.normalise(c["input"]))))
        }
    }

    @Test
    fun everyLookIsResolvedAsTheReactNativeSdkAnswers() {
        for (c in cases("widgetLook")) {
            val kind = c["kind"] as String?
            val expected = canon(c["output"])
            assertEquals("resolveWidgetLook(${Json.write(c["style"])}, $kind)", expected, canon(look(ComponentStyles.resolve(c["style"], kind))))
            // And as a component of that kind carries it, its style in the payload.
            val component = GraineComponent("w1", kind, null, mapOf("widget_id" to "w1", "kind" to kind, "style" to c["style"]))
            assertEquals("GraineComponent($kind).style", expected, canon(look(component.style)))
        }
    }

    @Test
    fun everyKindGoesWhereTheReactNativeSdkPutsIt() {
        for (c in cases("widgetSurface")) {
            assertEquals("widgetSurface(${Json.write(c["kind"])})", c["output"], ComponentStyles.surface(c["kind"] as String?).wire)
        }
    }

    // ── the lists and the defaults, from protocol.ts ──────────────────────

    /** `export const NAME: … = ["a", "b"];`, as its words. */
    private fun words(ts: String, name: String): List<String> {
        val list = Regex("""export const $name\b[^=]*=\s*\[([^\]]*)\]""").find(ts) ?: error("protocol.ts declares no $name")
        return Regex(""""([^"]*)"""").findAll(list.groupValues[1]).map { it.groupValues[1] }.toList()
            .also { assertTrue("$name is empty", it.isNotEmpty()) }
    }

    /** `const NAME… = { … };`, the object's body. */
    private fun block(ts: String, name: String): String =
        (Regex("""const $name\b[^=]*=\s*\{([\s\S]*?)\n\};""").find(ts) ?: error("protocol.ts declares no $name")).groupValues[1]

    @Test
    fun theListsAndDefaultsAreTheContractsOwn() {
        val ts = source("inapp/protocol.ts")
        assertEquals("WIDGET_PRESETS", words(ts, "WIDGET_PRESETS"), GraineComponentPreset.entries.map { it.wire })
        assertEquals("WIDGET_IMAGE_SIZES", words(ts, "WIDGET_IMAGE_SIZES"), GraineComponentImageSize.entries.map { it.wire })
        assertEquals("WIDGET_DENSITIES", words(ts, "WIDGET_DENSITIES"), GraineComponentDensity.entries.map { it.wire })
        assertEquals("WIDGET_BUTTONS", words(ts, "WIDGET_BUTTONS"), GraineComponentButton.entries.map { it.wire })
        assertEquals("WIDGET_SURFACE_CHOICES", words(ts, "WIDGET_SURFACE_CHOICES"), ComponentStyles.SURFACE_CHOICES)
        assertEquals("WIDGET_PLATFORMS", words(ts, "WIDGET_PLATFORMS"), GraineComponentPlatform.entries.map { it.wire })
        assertEquals("WIDGET_SELECTS", words(ts, "WIDGET_SELECTS"), GraineComponentSelect.entries.map { it.wire })

        val preset = Regex("""export const DEFAULT_WIDGET_PRESET\b[^=]*=\s*"(\w+)"""").find(ts)?.groupValues?.get(1)
        assertEquals("DEFAULT_WIDGET_PRESET", preset, ComponentStyles.DEFAULT_PRESET.wire)
        assertEquals("…and the look with no style is it", preset, GraineComponentStyle().preset.wire)
        val most = Regex("""export const MAX_SELECT_DEFAULT\s*=\s*(\d+)""").find(ts)?.groupValues?.get(1)?.toInt()
        assertEquals("MAX_SELECT_DEFAULT", most, ComponentStyles.MAX_SELECT_DEFAULT)
        assertEquals(most, GraineComponentStyle().maxSelect)

        val defaults = Regex("""(\w+):\s*\{\s*imageSize:\s*"(\w+)",\s*button:\s*"(\w+)"\s*\}""").findAll(block(ts, "PRESET_DEFAULTS"))
            .associate { it.groupValues[1] to listOf(it.groupValues[2], it.groupValues[3]) }
        assertEquals(
            "PRESET_DEFAULTS",
            defaults,
            GraineComponentPreset.entries.associate { p -> ComponentStyles.defaults(p).let { (size, button) -> p.wire to listOf(size.wire, button.wire) } },
        )

        val rule = Regex("""(\w+):\s*"(\w+)"""").findAll(block(ts, "SURFACE")).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("widgetSurface's SURFACE", rule, ComponentStyles.SURFACE.mapValues { it.value.wire })
        assertEquals("the defaults are an unknown kind's look", ComponentStyles.surface("not_a_kind"), GraineComponentStyle().surface)
    }

    @Test
    fun theWaitsAreTheReactNativeSdks() {
        val rn = source("inapp/react-native/index.tsx")
        fun ms(name: String): Long = (Regex("""export const $name\s*=\s*([\d_]+)""").find(rn) ?: error("index.tsx declares no $name"))
            .groupValues[1].replace("_", "").toLong()
        assertEquals("AWAITING_REPLY_MS", ms("AWAITING_REPLY_MS"), GraineAgent.AWAITING_REPLY_MS)
        assertEquals("PENDING_WIDGET_MS", ms("PENDING_WIDGET_MS"), GraineAgent.PENDING_COMPONENT_MS)
    }
}
