package ai.graine.inapp.kmp

/**
 * HOW A COMPONENT LOOKS — the Look & feel the dashboard set on it, as every
 * Graine SDK resolves it: the preset's defaults, then each choice made on top
 * of them, then where it goes on screen for its kind. Read it from
 * [GraineComponent.style] to draw a component of your own in the shape the
 * SDK's views would draw it, in your app's design.
 *
 *     val look = component.style
 *     when (look.surface) {
 *         GraineComponentSurface.SCREEN -> MyListScreen(component, columns = look.columns)
 *         GraineComponentSurface.SHEET -> MyChoiceSheet(component, pickMany = look.select == GraineComponentSelect.MANY)
 *         else -> MyCard(component)
 *     }
 *
 * Only the words this build knows are read: a preset or a value it does not
 * know is left out, never guessed — so a newer preset draws as
 * [GraineComponentPreset.CARDS], and a component with no look of its own is
 * Cards. [radius] and [accent] are null unless the component sets them: draw
 * it in the agent's own design then ([GraineAgent.theme] — its accent, its
 * cards' corners). New in 0.14.8.
 */
data class GraineComponentStyle(
    /** The whole look in one word: [GraineComponentPreset.CARDS] unless the dashboard chose another. */
    val preset: GraineComponentPreset = GraineComponentPreset.CARDS,
    /** How big each item's picture is: the preset's own, unless the dashboard chose a size. */
    val imageSize: GraineComponentImageSize = GraineComponentImageSize.MEDIUM,
    /** How much room each item takes: COMFORTABLE unless the dashboard chose COMPACT. */
    val density: GraineComponentDensity = GraineComponentDensity.COMFORTABLE,
    /** What an item's button looks like: the preset's own, unless the dashboard chose one. */
    val button: GraineComponentButton = GraineComponentButton.FILLED,
    /** Whether to show each item's fact chips: true unless the dashboard turned them off. */
    val facts: Boolean = true,
    /** Items side by side: 2 only for GALLERY and CARDS when the dashboard chose two columns; 1 otherwise. */
    val columns: Int = 1,
    /**
     * Where it goes on screen: the dashboard's choice, else the rule for its
     * kind — `product_list` takes the screen; `plan_comparison`, `choices`,
     * `quick_actions` and `calendar_slots` a sheet; `info_card`, and any kind
     * this build does not know, a card.
     */
    val surface: GraineComponentSurface = GraineComponentSurface.CARD,
    /** Its corner radius, 0–24 (dp; points on iOS), when the component sets one; null: the agent's design's. */
    val radius: Int? = null,
    /** Its accent colour, `#RRGGBB`, when the component sets one; null: the agent's design's. */
    val accent: String? = null,
    /** Whose design grammar it follows: AUTO follows the phone (Apple's on iPhone, Material on Android, One UI on a Samsung). */
    val platform: GraineComponentPlatform = GraineComponentPlatform.AUTO,
    /** ONE: a tap answers it. MANY: the user ticks several and answers once, with everything chosen. */
    val select: GraineComponentSelect = GraineComponentSelect.ONE,
    /** With [select] MANY, the most the user may choose: 2–8, 3 unless the dashboard chose. */
    val maxSelect: Int = 3,
)

/** A component's look in one word ([GraineComponentStyle.preset]). New in 0.14.8. */
enum class GraineComponentPreset(
    /** The dashboard's word for it. */
    val wire: String,
) {
    /** The picture on top, one item to a row, a full-width button. Large pictures and filled buttons unless chosen otherwise. */
    GALLERY("gallery"),
    /** A thumbnail beside the item's facts, about three to a screen — the default. Medium pictures and filled buttons unless chosen otherwise. */
    CARDS("cards"),
    /** Dense rows, each row the button — for long results. Small pictures and row buttons unless chosen otherwise. */
    LIST("list"),
    /** One item at a time, the picture on top, swiped to compare — for two to five. Large pictures and filled buttons unless chosen otherwise. */
    SPOTLIGHT("spotlight"),
    /** One line each, with no picture, and outlined buttons unless chosen otherwise. */
    COMPACT("compact"),
}

/** How big an item's picture is ([GraineComponentStyle.imageSize]). New in 0.14.8. */
enum class GraineComponentImageSize(
    /** The dashboard's word for it. */
    val wire: String,
) {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
}

/** How much room each item takes ([GraineComponentStyle.density]). New in 0.14.8. */
enum class GraineComponentDensity(
    /** The dashboard's word for it. */
    val wire: String,
) {
    /** The default. */
    COMFORTABLE("comfortable"),
    /** Tighter: less space around and between items. */
    COMPACT("compact"),
}

/** What an item's button looks like ([GraineComponentStyle.button]). New in 0.14.8. */
enum class GraineComponentButton(
    /** The dashboard's word for it. */
    val wire: String,
) {
    /** Filled with the accent. */
    FILLED("filled"),
    /** An outline in the accent. */
    OUTLINE("outline"),
    /** No button: the whole row is tapped. */
    ROW("row"),
}

/** Where a component goes on screen ([GraineComponentStyle.surface]). New in 0.14.8. */
enum class GraineComponentSurface(
    /** The dashboard's word for it. */
    val wire: String,
) {
    /** The component is the screen: pictures, several items to compare. */
    SCREEN("screen"),
    /** It slides over the app: a choice to finish, then back to the app. */
    SHEET("sheet"),
    /** A card beside the conversation: a fact, or a kind that cannot be drawn (show its `fallbackText`). */
    CARD("card"),
}

/** Whose design grammar a component follows ([GraineComponentStyle.platform]). New in 0.14.8. */
enum class GraineComponentPlatform(
    /** The dashboard's word for it. */
    val wire: String,
) {
    /** Follow the phone: Apple's grammar on iPhone, Material on Android, One UI on a Samsung. The default. */
    AUTO("auto"),
    /** Apple's, on every phone. */
    IOS("ios"),
    /** Material 3, on every phone. */
    MATERIAL("material"),
    /** Samsung's One UI, on every phone. */
    ONEUI("oneui"),
}

/** One choice, or several ([GraineComponentStyle.select]). New in 0.14.8. */
enum class GraineComponentSelect(
    /** The dashboard's word for it. */
    val wire: String,
) {
    /** A tap is the answer. The default. */
    ONE("one"),
    /** A tick on each item and one button at the end: the answer carries everything chosen, at most [GraineComponentStyle.maxSelect]. */
    MANY("many"),
}

/**
 * THE LOOK, RESOLVED AS THE SDK CONTRACT RESOLVES IT — protocol.ts in the
 * React Native SDK owns these rules and lists (`normaliseWidgetStyle`,
 * `resolveWidgetLook`, `widgetSurface`, WIDGET_PRESETS, WIDGET_IMAGE_SIZES,
 * WIDGET_DENSITIES, WIDGET_BUTTONS, WIDGET_SURFACE_CHOICES, WIDGET_PLATFORMS,
 * WIDGET_SELECTS, PRESET_DEFAULTS, DEFAULT_WIDGET_PRESET, MAX_SELECT_DEFAULT),
 * and this is them word for word: each value an allowlist, an unknown key or
 * value dropped (never passed on), the preset's defaults under the knobs. The
 * contract's own answers (sdk/contract/rn-parity.json) and its lists are held
 * to these by ComponentStyleContractTest. Pure; never throws.
 */
internal object ComponentStyles {
    /** WIDGET_SURFACE_CHOICES: "auto" keeps the rule for the kind. */
    const val AUTO = "auto"
    /** MAX_SELECT_DEFAULT, and maxSelect's range. */
    const val MAX_SELECT_DEFAULT = 3
    const val MAX_SELECT_MIN = 2
    const val MAX_SELECT_MAX = 8
    /** A radius is 0–24, rounded. */
    const val RADIUS_MAX = 24
    /** DEFAULT_WIDGET_PRESET. */
    val DEFAULT_PRESET = GraineComponentPreset.CARDS

    /** widgetSurface's SURFACE: where each kind goes. Any other kind is a card: what is drawn for it is one fallback sentence. */
    val SURFACE: Map<String, GraineComponentSurface> = linkedMapOf(
        "product_list" to GraineComponentSurface.SCREEN,
        "products" to GraineComponentSurface.SCREEN,
        "plan_comparison" to GraineComponentSurface.SHEET,
        "choices" to GraineComponentSurface.SHEET,
        "quick_actions" to GraineComponentSurface.SHEET,
        "calendar_slots" to GraineComponentSurface.SHEET,
        "info_card" to GraineComponentSurface.CARD,
    )

    /** WIDGET_SURFACE_CHOICES: "auto", then the surfaces. */
    val SURFACE_CHOICES: List<String> = listOf(AUTO) + GraineComponentSurface.entries.map { it.wire }

    /** PRESET_DEFAULTS: each preset's picture size and button. */
    fun defaults(preset: GraineComponentPreset): Pair<GraineComponentImageSize, GraineComponentButton> = when (preset) {
        GraineComponentPreset.GALLERY -> GraineComponentImageSize.LARGE to GraineComponentButton.FILLED
        GraineComponentPreset.CARDS -> GraineComponentImageSize.MEDIUM to GraineComponentButton.FILLED
        GraineComponentPreset.LIST -> GraineComponentImageSize.SMALL to GraineComponentButton.ROW
        GraineComponentPreset.SPOTLIGHT -> GraineComponentImageSize.LARGE to GraineComponentButton.FILLED
        GraineComponentPreset.COMPACT -> GraineComponentImageSize.SMALL to GraineComponentButton.OUTLINE
    }

    /** The look as stored, allowlisted (`normaliseWidgetStyle`'s answer): null where it chose nothing this build can read. */
    data class Chosen(
        val preset: GraineComponentPreset? = null,
        val imageSize: GraineComponentImageSize? = null,
        val density: GraineComponentDensity? = null,
        val button: GraineComponentButton? = null,
        val facts: Boolean? = null,
        val columns: Int? = null,
        /** A word of [SURFACE_CHOICES]: "auto" is kept, so it can be told from nothing. */
        val surface: String? = null,
        val radius: Int? = null,
        val accent: String? = null,
        val platform: GraineComponentPlatform? = null,
        val select: GraineComponentSelect? = null,
        val maxSelect: Int? = null,
    )

    /** The component's look: [raw] (the payload's `style`) for a component of [kind]. */
    fun resolve(raw: Any?, kind: String?): GraineComponentStyle = look(normalise(raw), kind)

    /** `widgetSurface`: an unknown kind is a card. */
    fun surface(kind: String?): GraineComponentSurface = SURFACE[jsTrim(kind ?: "").lowercase()] ?: GraineComponentSurface.CARD

    /** `normaliseWidgetStyle`: only the keys and values the renderers read. Anything else is dropped. */
    fun normalise(raw: Any?): Chosen {
        val r = raw as? Map<*, *> ?: return Chosen()
        val columns = r["columns"].let { c ->
            when {
                finite(c) == 2.0 || c == "2" -> 2
                finite(c) == 1.0 || c == "1" -> 1
                else -> null
            }
        }
        val accent = (r["accent"] as? String)?.let { jsTrim(it) }?.takeIf { isHex6(it) }?.uppercase()
        return Chosen(
            preset = pick(r["preset"], GraineComponentPreset.entries) { it.wire },
            imageSize = pick(r["imageSize"], GraineComponentImageSize.entries) { it.wire },
            density = pick(r["density"], GraineComponentDensity.entries) { it.wire },
            button = pick(r["button"], GraineComponentButton.entries) { it.wire },
            facts = r["facts"] as? Boolean,
            columns = columns,
            surface = pick(r["surface"], SURFACE_CHOICES) { it },
            radius = finite(r["radius"])?.let { jsRound(it).coerceIn(0.0, RADIUS_MAX.toDouble()).toInt() },
            accent = accent,
            platform = pick(r["platform"], GraineComponentPlatform.entries) { it.wire },
            select = pick(r["select"], GraineComponentSelect.entries) { it.wire },
            maxSelect = finite(r["maxSelect"])?.let { jsRound(it).coerceIn(MAX_SELECT_MIN.toDouble(), MAX_SELECT_MAX.toDouble()).toInt() },
        )
    }

    /** `resolveWidgetLook`: the preset's defaults, then the knobs on top; "auto" (or none) is the rule for the kind. */
    fun look(s: Chosen, kind: String?): GraineComponentStyle {
        val preset = s.preset ?: DEFAULT_PRESET
        val (imageSize, button) = defaults(preset)
        val surface = s.surface?.takeIf { it != AUTO }?.let { w -> GraineComponentSurface.entries.firstOrNull { it.wire == w } } ?: surface(kind)
        return GraineComponentStyle(
            preset = preset,
            imageSize = s.imageSize ?: imageSize,
            density = s.density ?: GraineComponentDensity.COMFORTABLE,
            button = s.button ?: button,
            facts = s.facts ?: true,
            columns = if ((preset == GraineComponentPreset.GALLERY || preset == GraineComponentPreset.CARDS) && s.columns == 2) 2 else 1,
            surface = surface,
            radius = s.radius,
            accent = s.accent,
            platform = s.platform ?: GraineComponentPlatform.AUTO,
            select = s.select ?: GraineComponentSelect.ONE,
            maxSelect = s.maxSelect ?: MAX_SELECT_DEFAULT,
        )
    }

    // ── JavaScript's own reading (JsRead), so a value means here what it means there ──

    /** `String(v ?? "").trim().toLowerCase()`, then the allowlist. */
    private fun <T> pick(v: Any?, allowed: List<T>, word: (T) -> String): T? {
        val s = JsRead.trim(JsRead.string(v)).lowercase()
        return allowed.firstOrNull { word(it) == s }
    }

    private fun finite(v: Any?): Double? = JsRead.finite(v)

    private fun jsRound(d: Double): Double = JsRead.round(d)

    private fun jsTrim(s: String): String = JsRead.trim(s)

    /** `/^#[0-9a-f]{6}$/i`. */
    private fun isHex6(s: String): Boolean =
        s.length == 7 && s[0] == '#' && (1 until 7).all { s[it] in '0'..'9' || s[it] in 'a'..'f' || s[it] in 'A'..'F' }
}
