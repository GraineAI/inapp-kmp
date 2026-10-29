package ai.graine.inapp.kmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A COMPONENT'S TAP AND PICTURES ARE THE SDK CONTRACT'S; ITS BLOCKS ARE THE DASHBOARD'S.
 *
 * sdk/contract/rn-parity.json holds what the React Native SDK's own built code
 * answers for a tap (normaliseSelectAction, selectCall, fillSelectTemplate)
 * and for pictures (resolveWidgetImages); this library reproduces every
 * answer, and its constants are read from protocol.ts itself. Blocks are read
 * by no SDK before this one, so their shape is the dashboard's own
 * (lib/web-widgets.ts: the "blank" kind and BlankBlock). All of it sits
 * beside this library in the monorepo: an exported checkout has none of it,
 * and skips.
 */
class ComponentContentContractTest {
    private fun sdkDir(): File {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder", dir != null)
        return File(dir!!).canonicalFile.parentFile
    }

    private fun source(path: String): String {
        val file = File(sdkDir(), path).canonicalFile
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

    private fun item(v: Any?): Map<String, Any?> = (v as? Map<*, *>)?.entries?.associate { (k, x) -> k.toString() to x } ?: emptyMap()

    /** `normaliseSelectAction`'s answer in its own words: `{}` for no tap, `args` only when there are some. */
    private fun tap(t: GraineComponentTap?): Map<String, Any?> = if (t == null) emptyMap() else linkedMapOf<String, Any?>(
        "path" to t.path, "action" to t.action, "args" to t.args.takeIf { it.isNotEmpty() }, "say" to t.say,
    ).filterValues { it != null }

    // ── the answers, from the React Native SDK's own code ─────────────────

    @Test
    fun everyTapIsReadAsTheReactNativeSdkReadsIt() {
        for (c in cases("selectAction")) {
            assertEquals("normaliseSelectAction(${Json.write(c["input"])})", canon(c["output"]), canon(tap(ComponentTaps.normalise(c["input"]))))
        }
    }

    @Test
    fun everyTapRunsWhatTheReactNativeSdkRuns() {
        for (c in cases("selectCall")) {
            val expected = canon(c["output"])
            val call = ComponentTaps.call(ComponentTaps.normalise(c["onSelect"]), item(c["item"]))
            assertEquals("selectCall(${Json.write(c["onSelect"])}, ${Json.write(c["item"])})", expected, canon(call?.let { mapOf("name" to it.first, "args" to it.second) }))
            // And through a component that carries it, as an app reads it.
            val component = GraineComponent("w1", "product_list", null, mapOf("widget_id" to "w1", "on_select" to c["onSelect"]))
            assertEquals("tapFor", expected, canon(component.tapFor(item(c["item"]))?.let { mapOf("name" to it.action, "args" to it.args) }))
        }
    }

    @Test
    fun everyPlaceholderIsFilledAsTheReactNativeSdkFillsIt() {
        for (c in cases("template")) {
            assertEquals("fillSelectTemplate(${Json.write(c["template"])}, ${Json.write(c["item"])})", c["output"], ComponentTaps.fill(c["template"] as String, item(c["item"])))
        }
    }

    @Test
    fun everyPictureIsResolvedAsTheReactNativeSdkResolvesIt() {
        for (c in cases("widgetImages")) {
            val data = c["data"] as Map<*, *>
            val base = c["baseUrl"] as String
            assertEquals("resolveWidgetImages(${Json.write(data)}, $base)", canon(c["output"]), canon(ComponentImages.resolve(data, base) ?: data))
        }
    }

    // ── the constants, from protocol.ts ───────────────────────────────────

    @Test
    fun theTapsConstantsAreTheContractsOwn() {
        val ts = source("inapp/protocol.ts")
        fun const(name: String) = (Regex("""export const $name\s*=\s*"?([\w]+)"?;""").find(ts) ?: error("protocol.ts declares no $name")).groupValues[1]
        assertEquals("NAVIGATE_ACTION", const("NAVIGATE_ACTION"), GraineComponentTap.NAVIGATE_ACTION)
        assertEquals("SELECT_ARG_MAX", const("SELECT_ARG_MAX").toInt(), ComponentTaps.ARG_MAX)
        assertEquals("SELECT_VALUE_MAX", const("SELECT_VALUE_MAX").toInt(), ComponentTaps.VALUE_MAX)

        // ACTION_NAME, as written there, and the same answers from it for every kind of name.
        val pattern = (Regex("""const ACTION_NAME\s*=\s*/(.*)/;""").find(ts) ?: error("protocol.ts declares no ACTION_NAME")).groupValues[1]
        assertEquals("^[a-z][a-z0-9_]{0,63}$", pattern)
        val actionName = Regex(pattern)
        for (name in listOf("a", "go", "open_screen", "a_1", "x".repeat(64), "x".repeat(65), "9lives", "_a", "A", "open comparison", "", "é", "go-on")) {
            assertEquals("'$name'", actionName.matches(name), ComponentTaps.isActionName(name))
        }

        val placeholder = (Regex("""String\(template\)\.replace\(/(.*?)/g""").find(ts) ?: error("protocol.ts has no fillSelectTemplate")).groupValues[1]
        assertEquals("fillSelectTemplate's placeholder", placeholder, ComponentTaps.PLACEHOLDER.pattern)

        val keys = (Regex("""const IMAGE_KEY = /\^\((.*)\)\$/i;""").find(ts) ?: error("protocol.ts declares no IMAGE_KEY")).groupValues[1].split("|").toSet()
        assertEquals("IMAGE_KEY", keys, ComponentImages.KEYS)
    }

    // ── the blocks, from the dashboard ────────────────────────────────────

    @Test
    fun theBlocksAreTheDashboardsOwn() {
        val lib = source("../lib/web-widgets.ts")
        val kinds = (Regex("""export type WidgetKind\s*=([\s\S]*?);""").find(lib) ?: error("web-widgets.ts declares no WidgetKind")).groupValues[1]
        assertTrue("the kind \"Start blank\" makes", kinds.contains("\"${ComponentBlocks.KIND}\""))
        assertTrue(Regex("""kind:\s*"${ComponentBlocks.KIND}",\s*label:\s*"Start blank"""").containsMatchIn(lib))
        assertTrue("its data is its blocks", Regex("""export interface BlankData \{\s*blocks: BlankBlock\[\];""").containsMatchIn(lib))
        val block = (Regex("""export interface BlankBlock \{([\s\S]*?)\n\}""").find(lib) ?: error("web-widgets.ts declares no BlankBlock")).groupValues[1]
        val types = Regex(""""(\w+)"""").findAll(Regex("""type:\s*([^;]+);""").find(block)!!.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals("BlankBlock's types", types, GraineComponentBlockType.entries.map { it.wire })
        for (field in listOf("id", "content", "imageUrl")) assertTrue("BlankBlock.$field", Regex("""\b$field\??:""").containsMatchIn(block))
    }
}
