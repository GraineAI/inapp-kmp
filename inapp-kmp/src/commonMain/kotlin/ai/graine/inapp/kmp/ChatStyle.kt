package ai.graine.inapp.kmp

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * THE CHAT WINDOW, PIECE BY PIECE — contract/DESIGN.md "Chat window v2": the
 * header, bubbles, times, wallpaper, chips, cards, composer, typing indicator,
 * density, welcome, rating, call button and glass, resolved by the server and
 * handed over in the token set's `chat` group beside the v1 words and colours
 * ([GraineThemeTokens.Chat]).
 *
 * Read as every token is read here: nothing trusted, nothing thrown. A field
 * missing or of the wrong type is that field's CLASSIC value; a word this
 * build does not know is the first one listed for it. A server older than v2
 * sends none of the groups, and they are then all classic — the chat as it
 * has always been drawn — derived from the v1 tokens exactly as the
 * contract's table says, with theme.ts resolveChat's own arithmetic: the very
 * values a v2 server sends for that design, and the ones the Android and iOS
 * SDKs draw. The iOS SDK's token JSON carries them already derived; the
 * Android SDK's carries only what the server sent — so the derivation is
 * here, once, and both platforms hand an app the same pieces. Pure, and
 * tested against every design in design.json.
 */

/** What the classic derivation reads of the design beyond the chat's own v1 words. */
internal class ChatStyleBase(
    val colors: GraineThemeTokens.Colors,
    val radius: GraineThemeTokens.Shape.Radius,
    val borderWidth: Double,
    val spacing: GraineThemeTokens.Spacing,
    val cardShadow: GraineThemeTokens.Shadow?,
    /** The design draws depth as a hard offset. */
    val hardOffset: Boolean,
    /** The design's `bubbles` word: `filled`, `outline` or `minimal`. */
    val bubbles: String,
)

/** The chat's v1 words the classic pieces are derived from. */
internal class ChatV1Words(
    val background: String,
    val pattern: String,
    val patternColor: String,
    val showTimestamps: Boolean,
    val headerBackground: String,
    val headerText: String,
    val headerSubtext: String,
    val composerBackground: String,
    val composerText: String,
    val composerPlaceholder: String,
    val composerBorder: String,
)

/** Every v2 piece of the chat window, resolved — what [GraineThemeTokens.Chat] carries from `preset` on. */
internal data class ChatPieces(
    val preset: String,
    val header: GraineThemeTokens.Chat.Header,
    val bubbles: GraineThemeTokens.Chat.Bubbles,
    val timestamps: GraineThemeTokens.Chat.Timestamps,
    val wallpaper: GraineThemeTokens.Chat.Wallpaper,
    val chips: GraineThemeTokens.Chat.Chips,
    val cards: GraineThemeTokens.Chat.Cards,
    val composer: GraineThemeTokens.Chat.Composer,
    val typing: GraineThemeTokens.Chat.Typing,
    val density: GraineThemeTokens.Chat.Density,
    val welcome: GraineThemeTokens.Chat.Welcome,
    val rating: GraineThemeTokens.Chat.Rating,
    val escalation: GraineThemeTokens.Chat.Escalation,
    val glass: GraineThemeTokens.Chat.Glass?,
)

/**
 * `x` to the power `y`, as the platform SDK beside this library reckons it:
 * Android's StrictMath (fdlibm, as the Android SDK and the server's
 * JavaScript engine), iOS's own `pow` (as the iOS SDK) — so a colour checked
 * against what it sits on lands on the same side of 4.5:1 as in the SDK that
 * draws it.
 */
internal expect fun platformPow(x: Double, y: Double): Double

internal object ChatStyles {
    // The words each piece allows. The first of each is what a word this build does not know is drawn as.
    val HEADER_STYLES = listOf("surface", "accent", "gradient", "plain")
    val HEADER_ALIGNS = listOf("start", "center")
    val STATUS_LINES = listOf("online", "text", "none")
    val CONTEXT_CARDS = listOf("bar", "card", "none")
    val BUBBLE_SHAPES = listOf("rounded", "squircle", "tail", "flat")
    val TIMESTAMP_SHOWS = listOf("never", "group", "always")
    val TIMESTAMP_STYLES = listOf("below", "inside")
    val WALLPAPER_STYLES = listOf("solid", "gradient", "pattern")
    /** A pattern this build does not know is its dots ("none" is a pattern word too, never the fallback). */
    val PATTERNS = listOf("dots", "grid", "diagonal", "none")
    val CHIP_STYLES = listOf("outline", "filled", "soft", "elevated")
    val CHIP_PLACEMENTS = listOf("both", "inline", "above-composer")
    val CHIP_SCROLLS = listOf("wrap", "row")
    val CARD_IMAGES = listOf("auto", "square", "landscape", "wide")
    val CARD_BUTTONS = listOf("filled", "outline", "tonal", "text")
    val COMPOSER_STYLES = listOf("bar", "floating", "pill")
    val SEND_STYLES = listOf("circle", "rounded", "icon")
    val TYPING_STYLES = listOf("loader", "dots", "wave", "text")
    val DENSITIES = listOf("comfortable", "compact", "spacious")
    val WELCOME_LAYOUTS = listOf("centered", "card", "hero", "minimal")
    val STARTER_LOOKS = listOf("chips", "list")
    val RATING_STYLES = listOf("thumbs", "stars", "emoji")
    val CALL_BUTTONS = listOf("none", "header", "composer")

    /** The keys only a v2 server puts in `chat`. */
    val KEYS = listOf("preset", "header", "bubbles", "timestamps", "wallpaper", "chips", "cards", "composer", "typing",
        "density", "welcome", "rating", "escalation", "glass")

    /** Words on the accent (and its shades) read at this, as the v1 accent header and the user's bubble always have. */
    const val ON_ACCENT_MIN = 3.0

    /**
     * Graine Classic's classic chat, light — what a [GraineThemeTokens.Chat]
     * made by hand carries unless it says otherwise. Graine Classic's own
     * light tokens, through the same derivation as any design's.
     */
    val graineClassic: ChatPieces by lazy {
        classic(
            ChatV1Words(
                background = "#F7F7F5", pattern = "none", patternColor = "#E0E0DF", showTimestamps = false,
                headerBackground = "#FFFFFF", headerText = "#101318", headerSubtext = "#5F6672",
                composerBackground = "#FFFFFF", composerText = "#101318", composerPlaceholder = "#5F6672", composerBorder = "#E5E4E0",
            ),
            ChatStyleBase(
                colors = GraineThemeTokens.Colors(
                    background = "#F7F7F5", surface = "#FFFFFF", surfaceRaised = "#FFFFFF", text = "#101318", textMuted = "#5F6672",
                    border = "#E5E4E0", accent = "#318CE7", onAccent = "#FFFFFF", accentSoft = "#E6F1FC", success = "#16A34A",
                    warning = "#B45309", danger = "#DC2626", userBubble = "#318CE7", userBubbleText = "#FFFFFF",
                    agentBubble = "#F1F1EF", agentBubbleText = "#101318", scrim = "#000000",
                ),
                radius = GraineThemeTokens.Shape.Radius(8.0, 12.0, 18.0, 24.0),
                borderWidth = 1.0,
                spacing = GraineThemeTokens.Spacing(4.0, 14.0, 10.0),
                cardShadow = GraineThemeTokens.Shadow(2.0, 8.0, 0.0, 0.08, "#000000"),
                hardOffset = false,
                bubbles = "filled",
            ),
        )
    }

    // ── classic, from the v1 tokens ─────────────────────────────────────────

    /**
     * The chat as it has always looked, in this design: theme.ts resolveChat
     * with every v2 knob at its classic value, from what a pre-v2 server sends
     * — the v1 chat words and colours, `colors`, the radii, the spacing, the
     * card depth and the `bubbles` word.
     */
    fun classic(v1: ChatV1Words, base: ChatStyleBase): ChatPieces {
        val c = base.colors
        val r = base.radius
        val wall = v1.background
        val surfaceHeader = normaliseHex(v1.headerBackground) == normaliseHex(c.surface)

        /** The side as the design's `bubbles` draws it — v1, exactly. */
        fun side(user: Boolean): GraineThemeTokens.Chat.BubbleSide {
            val color: String?
            val text: String
            var border: String? = null
            var bare = false
            when (base.bubbles) {
                "outline" -> { color = null; text = c.text; border = if (user) c.accent else c.border }
                "minimal" -> if (user) { color = c.accentSoft; text = c.text } else { color = null; text = c.text; bare = true }
                else -> { color = if (user) c.userBubble else c.agentBubble; text = if (user) c.userBubbleText else c.agentBubbleText }
            }
            val under = color ?: wall
            val floor = if (user && color == c.userBubble) ON_ACCENT_MIN else 4.5
            return GraineThemeTokens.Chat.BubbleSide(color, null, text, quietOn(text, under, null, floor), border, bare, linkOn(text, c.accent, under, null))
        }

        val gap = jsRound(base.spacing.gap)
        return ChatPieces(
            preset = "classic",
            header = GraineThemeTokens.Chat.Header(
                style = if (surfaceHeader) "surface" else "accent",
                align = "start", status = "online", typingStatus = false, contextCard = "bar",
                fill = GraineThemeTokens.Chat.Fill(v1.headerBackground, null, v1.headerText, v1.headerSubtext),
                dot = if (surfaceHeader) c.success else v1.headerText,
                divider = true,
            ),
            bubbles = GraineThemeTokens.Chat.Bubbles("rounded", r.lg, min(r.sm, r.lg), false, 0.84, null, side(true), side(false)),
            timestamps = GraineThemeTokens.Chat.Timestamps(if (v1.showTimestamps) "always" else "never", "below", false),
            wallpaper = GraineThemeTokens.Chat.Wallpaper(
                if (v1.pattern == "dots") "pattern" else "solid", wall, null, v1.pattern, v1.patternColor, 0.5, c.text, c.textMuted,
            ),
            chips = GraineThemeTokens.Chat.Chips(
                "outline", "both", "wrap", 34.0, min(r.xl, 17.0), wall, ensureContrast(c.text, wall), c.accent, max(1.0, base.borderWidth), null,
            ),
            cards = GraineThemeTokens.Chat.Cards(r.lg, base.cardShadow, base.hardOffset, true, "auto", "filled", c.accent, c.onAccent, null),
            composer = GraineThemeTokens.Chat.Composer(
                style = "bar", mic = false, attach = false, send = "circle",
                fill = v1.composerBackground, text = v1.composerText, placeholder = v1.composerPlaceholder, border = v1.composerBorder,
                strip = wall, shadow = null, height = 40.0, radius = r.xl, sendFill = c.accent, sendIcon = c.onAccent,
                icon = ensureOnBoth(c.accent, v1.composerBackground, wall, ON_ACCENT_MIN),
            ),
            typing = GraineThemeTokens.Chat.Typing("loader", "", c.accent),
            density = GraineThemeTokens.Chat.Density(
                "comfortable", gap, max(2.0, jsRound(gap * 0.25)), jsRound(base.spacing.cardPadding), jsRound(base.spacing.cardPadding * 0.75), 12.0,
            ),
            welcome = GraineThemeTokens.Chat.Welcome("centered", "chips"),
            rating = GraineThemeTokens.Chat.Rating(false, "thumbs", ""),
            escalation = GraineThemeTokens.Chat.Escalation("none"),
            glass = null,
        )
    }

    // ── the server's, read over classic ─────────────────────────────────────

    /**
     * `chat`'s v2 groups over [k] (the classic pieces), field by field — the
     * Android SDK's rules, ranges included. With none of the groups (an older
     * server) every field is classic's. Never throws.
     */
    fun read(raw: Map<*, *>, k: ChatPieces): ChatPieces {
        val h = group(raw, "header")
        val hf = group(h, "fill")
        val b = group(raw, "bubbles")
        val ts = group(raw, "timestamps")
        val w = group(raw, "wallpaper")
        val ch = group(raw, "chips")
        val cd = group(raw, "cards")
        val cp = group(raw, "composer")
        val ty = group(raw, "typing")
        val dn = group(raw, "density")
        val wl = group(raw, "welcome")
        val rt = group(raw, "rating")
        val es = group(raw, "escalation")
        fun side(o: Map<*, *>, d: GraineThemeTokens.Chat.BubbleSide) = GraineThemeTokens.Chat.BubbleSide(
            color = hexOrNull(o, "color", d.color),
            colorTo = hexOrNull(o, "colorTo", d.colorTo),
            text = hex(o, "text", d.text),
            textMuted = hex(o, "textMuted", d.textMuted),
            border = hexOrNull(o, "border", d.border),
            bare = flag(o, "bare", d.bare),
            link = hex(o, "link", d.link),
        )
        return ChatPieces(
            preset = (raw["preset"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: k.preset,
            header = GraineThemeTokens.Chat.Header(
                style = word(h, "style", HEADER_STYLES, k.header.style),
                align = word(h, "align", HEADER_ALIGNS, k.header.align),
                status = word(h, "status", STATUS_LINES, k.header.status),
                typingStatus = flag(h, "typingStatus", k.header.typingStatus),
                contextCard = word(h, "contextCard", CONTEXT_CARDS, k.header.contextCard),
                fill = GraineThemeTokens.Chat.Fill(
                    color = hex(hf, "color", k.header.fill.color),
                    colorTo = hexOrNull(hf, "colorTo", k.header.fill.colorTo),
                    text = hex(hf, "text", k.header.fill.text),
                    textMuted = hex(hf, "textMuted", k.header.fill.textMuted),
                ),
                dot = hex(h, "dot", k.header.dot),
                divider = flag(h, "divider", k.header.divider),
            ),
            bubbles = GraineThemeTokens.Chat.Bubbles(
                shape = word(b, "shape", BUBBLE_SHAPES, k.bubbles.shape),
                radius = num(b, "radius", k.bubbles.radius, 0.0, 999.0),
                tightRadius = num(b, "tightRadius", k.bubbles.tightRadius, 0.0, 999.0),
                grouping = flag(b, "grouping", k.bubbles.grouping),
                maxWidth = num(b, "maxWidth", k.bubbles.maxWidth, 0.6, 0.92),
                shadow = shadow(b, "shadow", k.bubbles.shadow),
                user = side(group(b, "user"), k.bubbles.user),
                agent = side(group(b, "agent"), k.bubbles.agent),
            ),
            timestamps = GraineThemeTokens.Chat.Timestamps(
                show = word(ts, "show", TIMESTAMP_SHOWS, k.timestamps.show),
                style = word(ts, "style", TIMESTAMP_STYLES, k.timestamps.style),
                ticks = flag(ts, "ticks", k.timestamps.ticks),
            ),
            wallpaper = GraineThemeTokens.Chat.Wallpaper(
                style = word(w, "style", WALLPAPER_STYLES, k.wallpaper.style),
                color = hex(w, "color", k.wallpaper.color),
                colorTo = hexOrNull(w, "colorTo", k.wallpaper.colorTo),
                pattern = word(w, "pattern", PATTERNS, k.wallpaper.pattern),
                patternColor = hex(w, "patternColor", k.wallpaper.patternColor),
                intensity = num(w, "intensity", k.wallpaper.intensity, 0.0, 1.0),
                text = hex(w, "text", k.wallpaper.text),
                textMuted = hex(w, "textMuted", k.wallpaper.textMuted),
            ),
            chips = GraineThemeTokens.Chat.Chips(
                style = word(ch, "style", CHIP_STYLES, k.chips.style),
                placement = word(ch, "placement", CHIP_PLACEMENTS, k.chips.placement),
                scroll = word(ch, "scroll", CHIP_SCROLLS, k.chips.scroll),
                height = num(ch, "height", k.chips.height, 0.0, 200.0),
                radius = num(ch, "radius", k.chips.radius, 0.0, 999.0),
                fill = hex(ch, "fill", k.chips.fill),
                text = hex(ch, "text", k.chips.text),
                border = hexOrNull(ch, "border", k.chips.border),
                borderWidth = num(ch, "borderWidth", k.chips.borderWidth, 0.0, 16.0),
                shadow = shadow(ch, "shadow", k.chips.shadow),
            ),
            cards = GraineThemeTokens.Chat.Cards(
                radius = num(cd, "radius", k.cards.radius, 0.0, 999.0),
                shadow = shadow(cd, "shadow", k.cards.shadow),
                hard = flag(cd, "hard", k.cards.hard),
                border = flag(cd, "border", k.cards.border),
                image = word(cd, "image", CARD_IMAGES, k.cards.image),
                button = word(cd, "button", CARD_BUTTONS, k.cards.button),
                buttonFill = hexOrNull(cd, "buttonFill", k.cards.buttonFill),
                buttonText = hex(cd, "buttonText", k.cards.buttonText),
                buttonBorder = hexOrNull(cd, "buttonBorder", k.cards.buttonBorder),
            ),
            composer = GraineThemeTokens.Chat.Composer(
                style = word(cp, "style", COMPOSER_STYLES, k.composer.style),
                mic = flag(cp, "mic", k.composer.mic),
                attach = flag(cp, "attach", k.composer.attach),
                send = word(cp, "send", SEND_STYLES, k.composer.send),
                fill = hex(cp, "fill", k.composer.fill),
                text = hex(cp, "text", k.composer.text),
                placeholder = hex(cp, "placeholder", k.composer.placeholder),
                border = hex(cp, "border", k.composer.border),
                strip = hexOrNull(cp, "strip", k.composer.strip),
                shadow = shadow(cp, "shadow", k.composer.shadow),
                height = num(cp, "height", k.composer.height, 0.0, 200.0),
                radius = num(cp, "radius", k.composer.radius, 0.0, 999.0),
                sendFill = hexOrNull(cp, "sendFill", k.composer.sendFill),
                sendIcon = hex(cp, "sendIcon", k.composer.sendIcon),
                icon = hex(cp, "icon", k.composer.icon),
            ),
            typing = GraineThemeTokens.Chat.Typing(
                style = word(ty, "style", TYPING_STYLES, k.typing.style),
                text = ty["text"] as? String ?: k.typing.text,
                color = hex(ty, "color", k.typing.color),
            ),
            density = GraineThemeTokens.Chat.Density(
                name = word(dn, "name", DENSITIES, k.density.name),
                gap = num(dn, "gap", k.density.gap, 0.0, 200.0),
                groupGap = num(dn, "groupGap", k.density.groupGap, 0.0, 200.0),
                padX = num(dn, "padX", k.density.padX, 0.0, 200.0),
                padY = num(dn, "padY", k.density.padY, 0.0, 200.0),
                edge = num(dn, "edge", k.density.edge, 0.0, 200.0),
            ),
            welcome = GraineThemeTokens.Chat.Welcome(
                layout = word(wl, "layout", WELCOME_LAYOUTS, k.welcome.layout),
                starters = word(wl, "starters", STARTER_LOOKS, k.welcome.starters),
            ),
            rating = GraineThemeTokens.Chat.Rating(
                show = flag(rt, "show", k.rating.show),
                style = word(rt, "style", RATING_STYLES, k.rating.style),
                prompt = rt["prompt"] as? String ?: k.rating.prompt,
            ),
            escalation = GraineThemeTokens.Chat.Escalation(call = word(es, "call", CALL_BUTTONS, k.escalation.call)),
            // JSON null is no glass; an object is glass (a field it lacks: Graine Classic's frost); anything else keeps classic.
            glass = when (val g = raw["glass"]) {
                null -> if (raw.containsKey("glass")) null else k.glass
                is Map<*, *> -> GraineThemeTokens.Chat.Glass(
                    num(g, "blur", k.glass?.blur ?: 20.0, 0.0, 200.0),
                    num(g, "opacity", k.glass?.opacity ?: 0.72, 0.0, 1.0),
                )
                else -> k.glass
            },
        )
    }

    private val EMPTY: Map<*, *> = emptyMap<Any?, Any?>()

    /** A group, or nothing (absent or not an object: every field classic). */
    private fun group(o: Map<*, *>, key: String): Map<*, *> = o[key] as? Map<*, *> ?: EMPTY

    /** Absent or not a word: classic; a word this build does not know: the first listed. */
    private fun word(o: Map<*, *>, key: String, allowed: List<String>, classic: String): String {
        val v = o[key] as? String ?: return classic
        return v.trim().lowercase().takeIf { it in allowed } ?: allowed[0]
    }

    private fun hex(o: Map<*, *>, key: String, classic: String): String = normaliseHex(o[key] as? String) ?: classic

    /** JSON null is "none"; absent or not a colour keeps classic. */
    private fun hexOrNull(o: Map<*, *>, key: String, classic: String?): String? {
        if (!o.containsKey(key)) return classic
        val v = o[key] ?: return null
        return normaliseHex(v as? String) ?: classic
    }

    private fun num(o: Map<*, *>, key: String, classic: Double, min: Double, max: Double): Double =
        (o[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: classic

    private fun flag(o: Map<*, *>, key: String, classic: Boolean): Boolean = o[key] as? Boolean ?: classic

    private val NO_SHADOW = GraineThemeTokens.Shadow(0.0, 0.0, 0.0, 0.0, "#000000")

    /** JSON null is no shadow; an object is read over classic's (or nothing); anything else keeps classic. */
    private fun shadow(o: Map<*, *>, key: String, classic: GraineThemeTokens.Shadow?): GraineThemeTokens.Shadow? {
        if (!o.containsKey(key)) return classic
        val v = o[key] ?: return null
        if (v !is Map<*, *>) return classic
        val d = classic ?: NO_SHADOW
        return GraineThemeTokens.Shadow(
            y = num(v, "y", d.y, -200.0, 200.0),
            blur = num(v, "blur", d.blur, 0.0, 400.0),
            spread = num(v, "spread", d.spread, -200.0, 200.0),
            opacity = num(v, "opacity", d.opacity, 0.0, 1.0),
            color = hex(v, "color", d.color),
        )
    }

    // ── colour, as theme.ts reckons it ──────────────────────────────────────
    //
    // The classic bubbles' quiet words and links, the chips' words and the
    // composer's icon are checked against what they sit on, as the server
    // checks them: the same arithmetic gives the same colours.

    private val HEX6 = Regex("^#?([0-9a-fA-F]{6})$")
    private val HEX3 = Regex("^#?([0-9a-fA-F]{3})$")

    /** "#RRGGBB" for anything that is a hex colour ("#abc", "AABBCC", …), else null — theme.ts's normaliseHex. */
    fun normaliseHex(value: String?): String? {
        val s = value?.trim() ?: return null
        HEX6.find(s)?.let { return "#" + it.groupValues[1].uppercase() }
        HEX3.find(s)?.let { m -> return "#" + m.groupValues[1].map { "$it$it" }.joinToString("").uppercase() }
        return null
    }

    /** `Math.round`: half rounds up, toward +∞, as JavaScript does. */
    private fun jsRound(d: Double): Double = floor(d + 0.5)

    private fun rgb(hex: String): IntArray {
        val n = (normaliseHex(hex) ?: "#000000").substring(1).toInt(16)
        return intArrayOf((n shr 16) and 255, (n shr 8) and 255, n and 255)
    }

    /** WCAG relative luminance. */
    fun luminance(hex: String): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else platformPow((s + 0.055) / 1.055, 2.4)
        }
        val c = rgb(hex)
        return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
    }

    /** WCAG contrast ratio, 1–21. */
    fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** `a` mixed toward `b` by `t` — theme.ts's mixHex. */
    fun mix(a: String, b: String, t: Double): String {
        val x = rgb(a)
        val y = rgb(b)
        return "#" + (0..2).joinToString("") { i ->
            jsRound(x[i] + (y[i] - x[i]) * t).coerceIn(0.0, 255.0).toInt().toString(16).padStart(2, '0')
        }.uppercase()
    }

    /** `fg` if it reaches `min` on `bg`; else stepped toward black or white until it does. */
    fun ensureContrast(fg: String, bg: String, min: Double = 4.5): String {
        if (contrast(fg, bg) >= min) return fg
        val toward = if (luminance(bg) > 0.5) "#000000" else "#FFFFFF"
        // The same steps JavaScript takes, floating-point drift and all.
        var t = 0.1
        while (t <= 1.0001) {
            val c = mix(fg, toward, t)
            if (contrast(c, bg) >= min) return c
            t += 0.1
        }
        return toward
    }

    /** `fg` made to read on both ends of a gradient (or one fill twice). */
    private fun ensureOnBoth(fg: String, a: String, b: String, min: Double): String = ensureContrast(ensureContrast(fg, a, min), b, min)

    private fun worstOn(fg: String, a: String, b: String?): Double = if (b != null) min(contrast(fg, a), contrast(fg, b)) else contrast(fg, a)

    /** Words quieter than `text` on a fill, as far toward it as they still read — 4.5:1, or on the accent its own floor. */
    private fun quietOn(text: String, a: String, b: String?, floor: Double): String {
        val need = if (floor >= 4.5) 4.5 else max(ON_ACCENT_MIN, min(4.5, worstOn(text, a, b)))
        for (t in doubleArrayOf(0.3, 0.25, 0.2, 0.15, 0.1, 0.05)) {
            val c = mix(text, a, t)
            if (worstOn(c, a, b) >= need) return c
        }
        return text
    }

    /** Links: the accent where it reads at 4.5:1 on all of the fill, else the words' own colour (links are always underlined). */
    private fun linkOn(text: String, accent: String, a: String, b: String?): String = if (worstOn(accent, a, b) >= 4.5) accent else text
}
