package ai.graine.inapp.kmp

/**
 * THE BRAND'S DESIGN TOKENS, for UI of your own — the colours, type, corners,
 * depth, motion and icons the SDK draws with, for the device's current light
 * or dark appearance (or the brand's fixed scheme), as plain numbers and
 * `#RRGGBB` strings: `Graine.theme`, and each agent's `theme`. What each
 * token paints is the design contract every Graine SDK draws from — the same
 * values as React Native's `useGraineTheme()`, iOS `theme`, Android
 * `themeTokens()` and Flutter `tokens`.
 *
 * Lengths are dp (points on iOS), type sizes sp. Words are the design's own
 * (`"outline"`, `"spring"`, `"window"`…): compare against them, and keep a
 * default branch — a newer design can send a word this build does not know.
 */
data class GraineThemeTokens(
    /** The token format's version. */
    val v: Int,
    /** The design the brand started from — informational: the tokens are already resolved. */
    val preset: String,
    /** `light` or `dark`: which of the design's two sets this is. */
    val scheme: String,
    val colors: Colors,
    val font: Font,
    val shape: Shape,
    val spacing: Spacing,
    val elevation: Elevation,
    val motion: Motion,
    val icons: Icons,
    /** Translucent surfaces; null: opaque ones. */
    val glass: Glass?,
    /** How chat messages sit on the page: `filled`, `outline` or `minimal`. */
    val bubbles: String,
    /** `none`, or `window`: the chat, the caption and components open as desktop windows. */
    val chrome: String,
    /** The chat window's own tokens; null from a server that predates them (draw from [colors]). */
    val chat: Chat?,
    /** The call screen's tokens; null from a server that predates them. */
    val call: Call?,
) {
    val isDark: Boolean get() = scheme == "dark"

    /** Every colour a surface is drawn in, `#RRGGBB`. */
    data class Colors(
        val background: String,
        val surface: String,
        val surfaceRaised: String,
        val text: String,
        val textMuted: String,
        val border: String,
        val accent: String,
        val onAccent: String,
        /** The accent at low strength: chips, selected rows, the focus halo. */
        val accentSoft: String,
        val success: String,
        val warning: String,
        val danger: String,
        val userBubble: String,
        val userBubbleText: String,
        val agentBubble: String,
        val agentBubbleText: String,
        val scrim: String,
    )

    data class Font(
        /** `system` means the platform's own UI font; anything else is a family the app may bundle. */
        val body: String,
        val heading: String,
        val scale: Scale,
        val weights: Weights,
        /** Letter spacing, in em. */
        val letterSpacing: Pair,
        /** Line height, as a multiplier. */
        val lineHeight: Pair,
    ) {
        data class Scale(val caption: Double, val body: Double, val title: Double, val display: Double)

        /** CSS weights, 100–900. */
        data class Weights(val body: Int, val strong: Int, val title: Int)

        /** One number for body text and one for titles. */
        data class Pair(val body: Double, val title: Double)
    }

    data class Shape(
        val radius: Radius,
        /** The width of every outline. */
        val borderWidth: Double,
        /** The launcher's outline: `circle`, `squircle`, `rounded-square`, `square` or `pill`. */
        val launcher: String,
    ) {
        data class Radius(val sm: Double, val md: Double, val lg: Double, val xl: Double)
    }

    data class Spacing(val unit: Double, val cardPadding: Double, val gap: Double)

    /** A soft shadow; [opacity] (0–1) applies to [color]. */
    data class Shadow(val y: Double, val blur: Double, val spread: Double, val opacity: Double, val color: String)

    /** A solid offset shadow, drawn in `colors.text`. */
    data class Offset(val x: Double, val y: Double)

    data class Elevation(
        /** Null: no shadow. */
        val card: Shadow?,
        val floating: Shadow?,
        /** Null unless the design draws depth as a hard offset instead of a shadow. */
        val hardOffset: Offset?,
    )

    data class Motion(
        /** 0 means no transition at all; the device's own reduce-motion setting wins over any number here. */
        val durationMs: Int,
        /** `standard`, `emphasized`, `spring`, `linear` or `none`. */
        val easing: String,
        /** 0–1, for `spring`. */
        val bounce: Double,
    )

    /** A launcher glyph: 24×24 SVG path data, stroked (with `icons.stroke`) or filled. */
    data class Glyph(val id: String, val path: String, val stroke: Boolean)

    data class Icons(
        /** `outline`, `filled`, `duotone` or `sharp`. */
        val style: String,
        val stroke: Double,
        /** Null: the launcher shows the brand's logo. */
        val launcher: Glyph?,
    )

    /** [colors.surface] at [surfaceOpacity], what is behind blurred by [blur] where the platform can. */
    data class Glass(val blur: Double, val surfaceOpacity: Double)

    /** The chat window, resolved. An empty word means the SDK's own default (the heading, "Online"…). */
    data class Chat(
        val title: String,
        val subtitle: String,
        /** The header's mark: `logo`, `glyph` or `none`. */
        val avatar: String,
        val placeholder: String,
        val showTimestamps: Boolean,
        /** Up to four replies offered before the user has said anything. */
        val suggestions: List<String>,
        val background: String,
        /** `none` or `dots`. */
        val pattern: String,
        val patternColor: String,
        val headerBackground: String,
        val headerText: String,
        val headerSubtext: String,
        val composerBackground: String,
        val composerText: String,
        val composerPlaceholder: String,
        val composerBorder: String,
        /** How big the window opens; null for a word this build does not know (draw it compact). */
        val size: GraineChatSize?,
        /** The Upcoming calls card. */
        val showUpcomingCalls: Boolean,
        /** That card's title; empty: "Upcoming call" / "Upcoming calls". */
        val upcomingTitle: String,
        /** The conversation kept from before, on screen. */
        val showHistory: Boolean,
        /** The header's New conversation control. */
        val newConversation: Boolean,
    )

    /** The call screen, resolved. */
    data class Call(
        val background: String,
        /** With one, the background is a gradient from [background] (top) to this (bottom). */
        val backgroundTo: String?,
        val text: String,
        val textMuted: String,
        /** `logo`, `glyph` or `initials`. */
        val avatar: String,
        /** `circle`, `squircle` or `rounded-square`. */
        val avatarShape: String,
        val avatarFill: String,
        val avatarText: String,
        val buttonFill: String,
        val buttonIcon: String,
        /** A control that is on (muted, the speaker). */
        val buttonActiveFill: String,
        val buttonActiveIcon: String,
        val endFill: String,
        val endIcon: String,
        /** `round` or `pill`. */
        val endShape: String,
        val acceptFill: String,
        val acceptIcon: String,
        val declineFill: String,
        val declineIcon: String,
        val callbackFill: String,
        val callbackIcon: String,
        val captions: Boolean,
        val timer: Boolean,
        /** In order, from `mute`, `speaker`, `chat` and `end`. */
        val controls: List<String>,
        /** `system`, `graine` or `soft`. */
        val ringtone: String,
        val statusText: StatusText,
    ) {
        /** The words under the name for each moment; null where the design leaves the SDK's own. */
        data class StatusText(val ringing: String, val connecting: String, val ended: String, val scheduled: String?, val missed: String?)
    }
}

/**
 * THE TOKENS AS THE PLATFORM SDKS HAND THEM OVER — the contract's token JSON
 * (Android's `ThemeTokens.toJson()`, the iOS facade's `themeJSON`), read
 * leniently: a set without its colours is no design (null); any other value
 * missing or of the wrong type takes a plain default, never a crash.
 */
internal object ThemeBridge {
    fun parse(json: String?): GraineThemeTokens? {
        val o = Json.obj(json) ?: return null
        val c = o["colors"] as? Map<*, *> ?: return null
        fun col(k: String): String? = c[k] as? String
        val colors = GraineThemeTokens.Colors(
            background = col("background") ?: return null, surface = col("surface") ?: return null,
            surfaceRaised = col("surfaceRaised") ?: return null, text = col("text") ?: return null,
            textMuted = col("textMuted") ?: return null, border = col("border") ?: return null,
            accent = col("accent") ?: return null, onAccent = col("onAccent") ?: return null,
            accentSoft = col("accentSoft") ?: return null, success = col("success") ?: return null,
            warning = col("warning") ?: return null, danger = col("danger") ?: return null,
            userBubble = col("userBubble") ?: return null, userBubbleText = col("userBubbleText") ?: return null,
            agentBubble = col("agentBubble") ?: return null, agentBubbleText = col("agentBubbleText") ?: return null,
            scrim = col("scrim") ?: return null,
        )
        val font = obj(o["font"])
        val scale = obj(font["scale"])
        val weights = obj(font["weights"])
        val spacingOf = obj(font["letterSpacing"])
        val lineOf = obj(font["lineHeight"])
        val shape = obj(o["shape"])
        val radius = obj(shape["radius"])
        val spacing = obj(o["spacing"])
        val elevation = obj(o["elevation"])
        val motion = obj(o["motion"])
        val icons = obj(o["icons"])
        return GraineThemeTokens(
            v = num(o["v"])?.toInt() ?: 1,
            preset = str(o["preset"]),
            scheme = if (str(o["scheme"]) == "dark") "dark" else "light",
            colors = colors,
            font = GraineThemeTokens.Font(
                body = str(font["body"], "system"), heading = str(font["heading"], "system"),
                scale = GraineThemeTokens.Font.Scale(dbl(scale["caption"]), dbl(scale["body"]), dbl(scale["title"]), dbl(scale["display"])),
                weights = GraineThemeTokens.Font.Weights(int(weights["body"], 400), int(weights["strong"], 600), int(weights["title"], 600)),
                letterSpacing = GraineThemeTokens.Font.Pair(dbl(spacingOf["body"]), dbl(spacingOf["title"])),
                lineHeight = GraineThemeTokens.Font.Pair(dbl(lineOf["body"], 1.0), dbl(lineOf["title"], 1.0)),
            ),
            shape = GraineThemeTokens.Shape(
                radius = GraineThemeTokens.Shape.Radius(dbl(radius["sm"]), dbl(radius["md"]), dbl(radius["lg"]), dbl(radius["xl"])),
                borderWidth = dbl(shape["borderWidth"]),
                launcher = str(shape["launcher"], "circle"),
            ),
            spacing = GraineThemeTokens.Spacing(dbl(spacing["unit"]), dbl(spacing["cardPadding"]), dbl(spacing["gap"])),
            elevation = GraineThemeTokens.Elevation(
                card = shadow(elevation["card"]),
                floating = shadow(elevation["floating"]),
                hardOffset = (elevation["hardOffset"] as? Map<*, *>)?.let { GraineThemeTokens.Offset(dbl(it["x"]), dbl(it["y"])) },
            ),
            motion = GraineThemeTokens.Motion(int(motion["durationMs"]), str(motion["easing"], "standard"), dbl(motion["bounce"])),
            icons = GraineThemeTokens.Icons(
                style = str(icons["style"], "outline"),
                stroke = dbl(icons["stroke"], 2.0),
                launcher = (icons["launcher"] as? Map<*, *>)?.let { g ->
                    val id = g["id"] as? String ?: return@let null
                    val path = g["path"] as? String ?: return@let null
                    GraineThemeTokens.Glyph(id, path, g["stroke"] == true)
                },
            ),
            glass = (o["glass"] as? Map<*, *>)?.let { GraineThemeTokens.Glass(dbl(it["blur"]), dbl(it["surfaceOpacity"], 1.0)) },
            bubbles = str(o["bubbles"], "filled"),
            chrome = str(o["chrome"], "none"),
            chat = (o["chat"] as? Map<*, *>)?.let { chat(it) },
            call = (o["call"] as? Map<*, *>)?.let { call(it) },
        )
    }

    private fun chat(o: Map<*, *>) = GraineThemeTokens.Chat(
        title = str(o["title"]), subtitle = str(o["subtitle"]), avatar = str(o["avatar"], "logo"), placeholder = str(o["placeholder"]),
        showTimestamps = o["showTimestamps"] == true,
        suggestions = (o["suggestions"] as? List<*>).orEmpty().filterIsInstance<String>(),
        background = str(o["background"]), pattern = str(o["pattern"], "none"), patternColor = str(o["patternColor"]),
        headerBackground = str(o["headerBackground"]), headerText = str(o["headerText"]), headerSubtext = str(o["headerSubtext"]),
        composerBackground = str(o["composerBackground"]), composerText = str(o["composerText"]),
        composerPlaceholder = str(o["composerPlaceholder"]), composerBorder = str(o["composerBorder"]),
        size = GraineChatSize.entries.firstOrNull { it.wire == o["size"] },
        // Absent (an older server): on, as every SDK reads them.
        showUpcomingCalls = o["showUpcomingCalls"] != false, upcomingTitle = str(o["upcomingTitle"]),
        showHistory = o["showHistory"] != false, newConversation = o["newConversation"] != false,
    )

    private fun call(o: Map<*, *>): GraineThemeTokens.Call {
        val status = obj(o["statusText"])
        return GraineThemeTokens.Call(
            background = str(o["background"]), backgroundTo = o["backgroundTo"] as? String,
            text = str(o["text"]), textMuted = str(o["textMuted"]),
            avatar = str(o["avatar"], "logo"), avatarShape = str(o["avatarShape"], "circle"),
            avatarFill = str(o["avatarFill"]), avatarText = str(o["avatarText"]),
            buttonFill = str(o["buttonFill"]), buttonIcon = str(o["buttonIcon"]),
            buttonActiveFill = str(o["buttonActiveFill"]), buttonActiveIcon = str(o["buttonActiveIcon"]),
            endFill = str(o["endFill"]), endIcon = str(o["endIcon"]), endShape = str(o["endShape"], "round"),
            acceptFill = str(o["acceptFill"]), acceptIcon = str(o["acceptIcon"]),
            declineFill = str(o["declineFill"]), declineIcon = str(o["declineIcon"]),
            callbackFill = str(o["callbackFill"]), callbackIcon = str(o["callbackIcon"]),
            captions = o["captions"] != false, timer = o["timer"] != false,
            controls = (o["controls"] as? List<*>).orEmpty().filterIsInstance<String>(),
            ringtone = str(o["ringtone"], "system"),
            statusText = GraineThemeTokens.Call.StatusText(
                str(status["ringing"]), str(status["connecting"]), str(status["ended"]),
                status["scheduled"] as? String, status["missed"] as? String,
            ),
        )
    }

    private fun shadow(v: Any?): GraineThemeTokens.Shadow? = (v as? Map<*, *>)?.let {
        GraineThemeTokens.Shadow(dbl(it["y"]), dbl(it["blur"]), dbl(it["spread"]), dbl(it["opacity"]), str(it["color"], "#000000"))
    }

    private fun obj(v: Any?): Map<*, *> = v as? Map<*, *> ?: emptyMap<Any?, Any?>()
    private fun str(v: Any?, default: String = ""): String = v as? String ?: default
    private fun num(v: Any?): Double? = when (v) {
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Double -> v.takeIf { it.isFinite() }
        else -> null
    }
    private fun dbl(v: Any?, default: Double = 0.0): Double = num(v) ?: default
    private fun int(v: Any?, default: Int = 0): Int = num(v)?.toInt() ?: default
}
