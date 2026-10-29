package ai.graine.inapp.kmp

/**
 * WHAT A TAP DOES IN YOUR APP — the component's "When they tap", as the
 * dashboard set it: Open a screen ([path], through the one `open_screen`
 * handler your app registers) or Run an action ([action], one your app
 * registered), with [args] filled from the item the user tapped. "Tell the
 * agent" is no tap at all — [GraineComponent.onSelect] is null — as the
 * choice is then only a turn of the conversation.
 *
 * A component you draw yourself does what the SDK's own views do on a tap:
 * your app's job first, without waiting for it, then the turn —
 *
 *     fun onPicked(component: GraineComponent, data: Map<String, Any?>, summary: String) {
 *         val call = component.tapFor(data, summary)
 *         if (call != null) runInApp(call.action, call.args)   // what your open_screen handler, or the action it names, does
 *         Graine.chat.answerWidget(component.id, data, call?.say ?: summary)
 *     }
 *
 * After it navigates, your app reports the new screen (`Graine.setScreen`),
 * which is how the agent learns where the user now is. Only what every
 * Graine SDK can read is kept: an action name of lowercase letters, digits
 * and underscores, starting with a letter (at most 64); a path, each
 * argument and [say] at most 200 characters; at most 10 arguments, each a
 * word, a number or a yes or no. New in 0.14.8.
 */
data class GraineComponentTap(
    /** Open a screen: a path your app's navigator understands — a route, a screen name, a deep link — with `{id}`-style placeholders. Null when it runs an action only. */
    val path: String? = null,
    /** Run an action: the name your app registered. Null for a path alone, which goes to [NAVIGATE_ACTION]. */
    val action: String? = null,
    /** The action's arguments as the dashboard wrote them, placeholders and all (`{id}`, `{title}`, `{price}`, `{summary}`…); empty when it has none. */
    val args: Map<String, String> = emptyMap(),
    /** What the agent is told instead of your own sentence, with the same placeholders; null: your sentence, which is usually right. */
    val say: String? = null,
) {
    companion object {
        /**
         * The one handler an app registers so a component's "Open a screen"
         * can open any screen: `open_screen`, with the screen as its `path`
         * argument. Registered once, every screen a component opens is a field
         * in the dashboard, with no app release.
         */
        const val NAVIGATE_ACTION: String = ComponentTaps.NAVIGATE_ACTION
    }
}

/**
 * ONE TAP'S WORK IN YOUR APP ([GraineComponent.tapFor]): the [action] to run
 * — the component's own, or [GraineComponentTap.NAVIGATE_ACTION] for a screen
 * path — with its [args] filled from the tapped item (a path travels as
 * `args["path"]` either way), and [say], the sentence the agent hears instead
 * of yours. New in 0.14.8.
 */
data class GraineComponentTapCall(
    /** The action your app runs for this tap: the component's own, or `open_screen` for a screen path. */
    val action: String,
    /** Its arguments, each placeholder filled from the tapped item; one with no value is empty, never left as `{sku}`. */
    val args: Map<String, String>,
    /** The sentence to answer with instead of yours (`answerWidget`'s summary), filled the same way; null: yours. */
    val say: String?,
)

/** What one block of a component built from blocks is ([GraineComponentBlock.type]). New in 0.14.8. */
enum class GraineComponentBlockType(
    /** The dashboard's word for it. */
    val wire: String,
) {
    /** A heading: [GraineComponentBlock.content]. */
    HEADING("heading"),
    /** A paragraph: [GraineComponentBlock.content]. */
    TEXT("text"),
    /** A picture, as wide as the component: [GraineComponentBlock.imageUrl]. */
    IMAGE("image"),
    /** A line between blocks. */
    DIVIDER("divider"),
    /**
     * A button labelled [GraineComponentBlock.content]. A tap answers the
     * component, as the dashboard's preview does:
     * `Graine.chat.answerWidget(id, mapOf("action" to content), content)`.
     */
    BUTTON("button"),
}

/**
 * ONE BLOCK OF A COMPONENT BUILT FROM BLOCKS — a component made with "Start
 * blank" in the dashboard (kind `blank`): a heading, text, an image, a divider
 * or a button, in the order [GraineComponent.blocks] lists them. New in 0.14.8.
 */
data class GraineComponentBlock(
    /** Its id: the dashboard's own, else `block-<n>` (its place in the list). Unique within the component — a key for your list. */
    val id: String,
    val type: GraineComponentBlockType,
    /** The words of a heading, a text or a button; null for an image or a divider. */
    val content: String? = null,
    /** An image's picture — an absolute URL; a root-relative one is on the agent's origin. Null for any other block. */
    val imageUrl: String? = null,
)

/**
 * A TAP, READ AS THE SDK CONTRACT READS IT — protocol.ts's
 * `normaliseSelectAction`, `selectCall`, `fillSelectArgs` and
 * `fillSelectTemplate`, with its ACTION_NAME, NAVIGATE_ACTION, SELECT_ARG_MAX
 * and SELECT_VALUE_MAX, word for word. The contract's own answers
 * (sdk/contract/rn-parity.json) and its constants are held to these by
 * ComponentContentContractTest. Pure; never throws.
 */
internal object ComponentTaps {
    const val NAVIGATE_ACTION = "open_screen"
    /** SELECT_ARG_MAX. */
    const val ARG_MAX = 10
    /** SELECT_VALUE_MAX: a path, each argument, `say`. */
    const val VALUE_MAX = 200
    /** ACTION_NAME, /^[a-z][a-z0-9_]{0,63}$/: a letter, then up to 63 letters, digits or underscores. */
    const val ACTION_NAME_MAX = 64

    fun isActionName(s: String): Boolean =
        s.length in 1..ACTION_NAME_MAX && s[0] in 'a'..'z' && s.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    /**
     * `normaliseSelectAction`: only what every SDK reads. Null where the
     * contract answers `{}` — no action and no path: the tap only tells the
     * agent. Arguments alone are nothing; a path alone is enough.
     */
    fun normalise(raw: Any?): GraineComponentTap? {
        val r = raw as? Map<*, *> ?: return null
        val action = JsRead.trim(JsRead.string(r["action"])).lowercase().takeIf { isActionName(it) }
        // A path is the author's own string — only its length is checked.
        val path = (r["path"] as? String)?.let { JsRead.trim(it).take(VALUE_MAX) }?.takeIf { it.isNotEmpty() }
        val args = LinkedHashMap<String, String>()
        (r["args"] as? Map<*, *>)?.let { given ->
            for ((k, v) in JsRead.entries(given)) {
                val key = JsRead.trim(k)
                if (key.isEmpty() || args.size >= ARG_MAX) continue
                // `typeof v === "object"` and nothing at all are not arguments: a word, a number or a yes or no is.
                if (v !is String && v !is Number && v !is Boolean) continue
                args[key] = JsRead.string(v).take(VALUE_MAX)
            }
        }
        val say = (r["say"] as? String)?.let { JsRead.trim(it).take(VALUE_MAX) }?.takeIf { it.isNotEmpty() }
        return if (action != null || path != null) GraineComponentTap(path, action, args, say) else null
    }

    /** `selectCall`: an explicit action wins, a path goes to [NAVIGATE_ACTION], and the path travels as an argument either way. */
    fun call(tap: GraineComponentTap?, item: Map<String, Any?>): Pair<String, Map<String, String>>? {
        val s = tap ?: return null
        if (s.action.isNullOrEmpty() && s.path.isNullOrEmpty()) return null
        val args = LinkedHashMap<String, String>()
        for ((k, v) in s.args) args[k] = fill(v, item)
        s.path?.takeIf { it.isNotEmpty() }?.let { args["path"] = fill(it, item) }
        return (s.action?.takeIf { it.isNotEmpty() } ?: NAVIGATE_ACTION) to args
    }

    /** fillSelectTemplate's placeholder: `{` a letter or underscore, then letters, digits or underscores `}`. */
    val PLACEHOLDER = Regex("""\{([a-zA-Z_][a-zA-Z0-9_]*)\}""")

    /** `fillSelectTemplate`: `{id}`, `{title}`… from the tapped item. One it does not have is empty, never left literal. */
    fun fill(template: String, item: Map<String, Any?>): String =
        PLACEHOLDER.replace(template) { m -> JsRead.string(item[m.groupValues[1]]) }
}

/**
 * A COMPONENT BUILT FROM BLOCKS, AS THE DASHBOARD MAKES IT — "Start blank"
 * (kind `blank`), its `data.blocks`: `{ id, type: heading | text | image |
 * divider | button, content?, imageUrl? }` (the dashboard's BlankBlock). No
 * other SDK reads them yet; what is kept is what the dashboard's own preview
 * draws. Pure; never throws.
 */
internal object ComponentBlocks {
    /** The kind "Start blank" makes. */
    const val KIND = "blank"

    fun read(kind: String?, data: Any?): List<GraineComponentBlock> {
        if (kind == null || JsRead.trim(kind).lowercase() != KIND) return emptyList()
        val rows = (data as? Map<*, *>)?.get("blocks") as? List<*> ?: return emptyList()
        val out = ArrayList<GraineComponentBlock>(rows.size)
        val used = HashSet<String>()
        rows.forEachIndexed { at, row ->
            val r = row as? Map<*, *> ?: return@forEachIndexed
            val word = (r["type"] as? String)?.let { JsRead.trim(it).lowercase() }
            val type = GraineComponentBlockType.entries.firstOrNull { it.wire == word } ?: return@forEachIndexed
            val content = (r["content"] as? String)?.let { JsRead.trim(it) }?.takeIf { it.isNotEmpty() }
            val imageUrl = (r["imageUrl"] as? String)?.let { JsRead.trim(it) }?.takeIf { it.isNotEmpty() }
            // Nothing is drawn for a heading, text or button without words, or for an image without a picture.
            val block = when (type) {
                GraineComponentBlockType.HEADING, GraineComponentBlockType.TEXT, GraineComponentBlockType.BUTTON ->
                    content?.let { GraineComponentBlock("", type, content = it) }
                GraineComponentBlockType.IMAGE -> imageUrl?.let { GraineComponentBlock("", type, imageUrl = it) }
                GraineComponentBlockType.DIVIDER -> GraineComponentBlock("", type)
            } ?: return@forEachIndexed
            // Its own id, unless it has none or an earlier block took it: then its place, made unique.
            var id = (r["id"] as? String)?.takeIf { it.isNotBlank() && it !in used } ?: "block-$at"
            while (!used.add(id)) id = "$id-$at"
            out += block.copy(id = id)
        }
        return out
    }
}

/**
 * A PICTURE PATH THAT MEANS SOMETHING ON THE WEB MEANS NOTHING ON A PHONE —
 * protocol.ts's `resolveWidgetImages` and its IMAGE_KEY: a root-relative
 * picture ("/samples/car.png") in a component's data is resolved against the
 * agent's own origin, top level and one level into a list (a list's items, a
 * component's blocks), only under keys that name a picture. An absolute URL,
 * a `//` URL, a data: URI and a bare relative path are left alone. Pure.
 */
internal object ComponentImages {
    /** IMAGE_KEY's names, read in any case. */
    val KEYS: Set<String> = setOf(
        "image", "imageurl", "image_url", "icon", "iconurl", "icon_url", "logo", "logourl", "logo_url",
        "photo", "photourl", "photo_url", "thumbnail", "thumbnailurl", "thumbnail_url",
    )

    fun isImageKey(key: String): Boolean = key.lowercase() in KEYS

    /** `resolveOne`. */
    fun one(value: Any?, base: String): Any? =
        if (value !is String || !value.startsWith("/") || value.startsWith("//")) value else base.trimEnd('/') + value

    /** [data] with its root-relative pictures on [base]; null when nothing changes, or there is no base. */
    fun resolve(data: Map<*, *>, base: String?): Map<String, Any?>? {
        if (base.isNullOrEmpty()) return null
        var changed = false
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in data) {
            val k = key.toString()
            val next = when {
                isImageKey(k) -> one(value, base)
                value is List<*> -> value.map { row ->
                    if (row !is Map<*, *>) row
                    else row.entries.associateTo(LinkedHashMap<String, Any?>()) { (rk, rv) ->
                        rk.toString() to (if (isImageKey(rk.toString())) one(rv, base) else rv)
                    }
                }
                else -> value
            }
            if (next != value) changed = true
            out[k] = next
        }
        return if (changed) out else null
    }

    /** The component, its data's root-relative pictures on [base] — itself when nothing changes. */
    fun on(component: GraineComponent, base: String?): GraineComponent {
        val data = component.payload["data"] as? Map<*, *> ?: return component
        val resolved = resolve(data, base) ?: return component
        return component.copy(payload = LinkedHashMap(component.payload).apply { put("data", resolved) })
    }
}
