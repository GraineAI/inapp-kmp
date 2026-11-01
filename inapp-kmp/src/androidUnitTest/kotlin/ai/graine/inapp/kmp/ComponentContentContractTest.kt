package ai.graine.inapp.kmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A COMPONENT'S TAP, PICTURES AND BLOCKS ARE THE SDK CONTRACT'S.
 *
 * sdk/contract/rn-parity.json holds what the React Native SDK's own built code
 * answers for a tap (normaliseSelectAction, selectCall, fillSelectTemplate),
 * for pictures (resolveWidgetImages) and for a "Start blank" component's
 * blocks (normaliseBlankBlocks); this library reproduces every answer, and
 * its constants and the blocks' shape are read from protocol.ts itself. The
 * dashboard makes blocks in that same shape (lib/web-widgets.ts: the "blank"
 * kind, and BlankBlock re-exported from the contract). All of it sits beside
 * this library in the monorepo: an exported checkout has none of it, and
 * skips.
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

    /** One block in `normaliseBlankBlocks`'s words: its id and type, and only the field its type uses. */
    private fun block(b: GraineComponentBlock): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to b.id, "type" to b.type.wire, "content" to b.content, "imageUrl" to b.imageUrl,
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

    @Test
    fun everyBlockIsReadAsTheReactNativeSdkReadsIt() {
        for (c in cases("blankBlocks")) {
            val base = c["baseUrl"] as String
            // As an app receives it: the component as the conversation sent it, its pictures then put on the agent's origin.
            val sent = GraineComponent("w1", "blank", null, mapOf("widget_id" to "w1", "kind" to "blank", "data" to c["data"]))
            val heard = ComponentImages.on(sent, base)
            assertEquals("normaliseBlankBlocks(${Json.write(c["data"])}, \"$base\")", canon(c["output"]), canon(heard.blocks.map { block(it) }))
            // Blocks are a "Start blank" component's alone: the same data under any other kind has none.
            assertTrue("blocks on an info_card", GraineComponent("w1", "info_card", null, heard.payload).blocks.isEmpty())
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

    // ── the blocks' shape, from protocol.ts; the kind, from the dashboard ─

    @Test
    fun theBlocksAreTheContractsOwn() {
        val ts = source("inapp/protocol.ts")
        val words = { s: String -> Regex(""""(\w+)"""").findAll(s).map { it.groupValues[1] }.toList() }
        val union = (Regex("""export type WidgetBlockType\s*=([^;]+);""").find(ts) ?: error("protocol.ts declares no WidgetBlockType")).groupValues[1]
        assertEquals("WidgetBlockType", words(union), GraineComponentBlockType.entries.map { it.wire })
        val list = (Regex("""export const WIDGET_BLOCK_TYPES[^=]*=\s*\[([^\]]*)\]""").find(ts) ?: error("protocol.ts declares no WIDGET_BLOCK_TYPES")).groupValues[1]
        assertEquals("WIDGET_BLOCK_TYPES", words(list), GraineComponentBlockType.entries.map { it.wire })
        val block = (Regex("""export interface WidgetBlock \{([\s\S]*?)\n\}""").find(ts) ?: error("protocol.ts declares no WidgetBlock")).groupValues[1]
        for (field in listOf("id", "type", "content", "imageUrl")) assertTrue("WidgetBlock.$field", Regex("""\b$field\??:""").containsMatchIn(block))

        // The dashboard makes them — "Start blank", kind blank — in the contract's shape, re-exported rather than copied.
        val lib = source("../lib/web-widgets.ts")
        val kinds = (Regex("""export type WidgetKind\s*=([\s\S]*?);""").find(lib) ?: error("web-widgets.ts declares no WidgetKind")).groupValues[1]
        assertTrue("the kind \"Start blank\" makes", kinds.contains("\"${ComponentBlocks.KIND}\""))
        assertTrue(Regex("""kind:\s*"${ComponentBlocks.KIND}",\s*label:\s*"Start blank"""").containsMatchIn(lib))
        assertTrue("its data is its blocks", Regex("""export interface BlankData \{\s*blocks: BlankBlock\[\];""").containsMatchIn(lib))
        assertTrue(
            "BlankBlock is the contract's WidgetBlock",
            Regex("""export type \{[^}]*\bWidgetBlock as BlankBlock\b[^}]*\} from "\.\./sdk/inapp/protocol\.ts"""").containsMatchIn(lib),
        )
    }
}
