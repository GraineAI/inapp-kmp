package ai.graine.inapp.kmp

import kotlin.math.pow

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

    /**
     * The chat window, resolved. An empty word means the SDK's own default (the heading, "Online"…).
     *
     * From [preset] on, every piece of the window — header, bubbles, times,
     * wallpaper, chips, cards, composer, typing indicator, density, welcome,
     * rating, call button and glass — as the dashboard's chat style and knobs
     * resolved them (Chat window → Style). From a server that sends none of
     * them they are the classic chat these very tokens imply, the chat as it
     * has always been drawn, so there is always one set to draw from. A
     * `Chat` made by hand carries Graine Classic's classic chat (light)
     * unless it says otherwise.
     */
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
        /** The chat style the pieces below came from — informational: they are resolved. `classic` from an older server. */
        val preset: String = ChatStyles.graineClassic.preset,
        val header: Header = ChatStyles.graineClassic.header,
        val bubbles: Bubbles = ChatStyles.graineClassic.bubbles,
        val timestamps: Timestamps = ChatStyles.graineClassic.timestamps,
        val wallpaper: Wallpaper = ChatStyles.graineClassic.wallpaper,
        val chips: Chips = ChatStyles.graineClassic.chips,
        val cards: Cards = ChatStyles.graineClassic.cards,
        val composer: Composer = ChatStyles.graineClassic.composer,
        val typing: Typing = ChatStyles.graineClassic.typing,
        val density: Density = ChatStyles.graineClassic.density,
        val welcome: Welcome = ChatStyles.graineClassic.welcome,
        val rating: Rating = ChatStyles.graineClassic.rating,
        val escalation: Escalation = ChatStyles.graineClassic.escalation,
        /** Frosted pieces; null: solid ones. */
        val glass: Glass? = null,
    ) {
        /** A fill — one colour, or a gradient — and the words that read on all of it. */
        data class Fill(
            val color: String,
            /** The gradient's far end; null: one solid colour. */
            val colorTo: String?,
            val text: String,
            /** Quieter words on it: the status line. */
            val textMuted: String,
        )

        /** The header: its fill, how its title sits, its status line, and where the app's pinned card goes. */
        data class Header(
            /** `surface`, `accent`, `gradient` (leading → trailing edge) or `plain` (no fill: the wallpaper runs under it). */
            val style: String,
            /** `start` or `center`. */
            val align: String,
            /** Under the title: `online` (a dot, then the words), `text` (the words alone) or `none`. */
            val status: String,
            /** While the agent works on a reply, the status line says so. */
            val typingStatus: Boolean,
            /** Where a pinned card ([GraineChat.pin]) goes: `bar` under the header, `card` atop the thread, or `none`. */
            val contextCard: String,
            val fill: Fill,
            /** The online dot. */
            val dot: String,
            /** A hairline in `colors.border` under it. */
            val divider: Boolean,
        )

        /** One side's bubbles. */
        data class BubbleSide(
            /** The fill; null: transparent. */
            val color: String?,
            /** A gradient's far end, top → bottom; null: solid. */
            val colorTo: String?,
            val text: String,
            /** A time or a mark inside the bubble. */
            val textMuted: String,
            /** An outline; null: none. */
            val border: String?,
            /** No bubble at all: the words on the wallpaper. */
            val bare: Boolean,
            /** Links in the words (always underlined). */
            val link: String,
        )

        data class Bubbles(
            /** `rounded`, `squircle`, `tail` or `flat`. */
            val shape: String,
            val radius: Double,
            /** The corner nearest the speaker, and a group's inner corners. */
            val tightRadius: Double,
            /** A run of messages from one side drawn as one group. */
            val grouping: Boolean,
            /** The widest a bubble may be, as a share of the thread's width (0.6–0.92). */
            val maxWidth: Double,
            /** Null: none. */
            val shadow: Shadow?,
            /** The user's bubbles. */
            val user: BubbleSide,
            val agent: BubbleSide,
        )

        data class Timestamps(
            /** `never`, `group` (the last message of each group) or `always`. */
            val show: String,
            /** `below` the bubble or `inside` it. */
            val style: String,
            /** A waiting / sent / not-sent mark beside the user's times. */
            val ticks: Boolean,
        )

        /** Behind the thread — and behind a `plain` header and a `floating` composer. */
        data class Wallpaper(
            /** `solid`, `gradient` (top → bottom) or `pattern`. */
            val style: String,
            /** The colour, or the gradient's top. */
            val color: String,
            /** The gradient's bottom; null unless a gradient. */
            val colorTo: String?,
            /** `none`, `dots`, `grid` or `diagonal`. */
            val pattern: String,
            val patternColor: String,
            /** How strong the gradient or the pattern is, 0–1 (already in the colours). */
            val intensity: Double,
            /** Words straight on it: a bare bubble, the welcome's title. */
            val text: String,
            /** Day lines, times under bubbles. */
            val textMuted: String,
        )

        /** The brand's starters and the agent's quick replies: one look. */
        data class Chips(
            /** `outline`, `filled`, `soft` or `elevated`. */
            val style: String,
            /** `both` (starters docked, quick replies in the thread), `inline` or `above-composer`. */
            val placement: String,
            /** `wrap` (rows that wrap) or `row` (one row that scrolls sideways). */
            val scroll: String,
            val height: Double,
            val radius: Double,
            val fill: String,
            val text: String,
            /** Null: no outline. */
            val border: String?,
            val borderWidth: Double,
            val shadow: Shadow?,
        )

        /** The agent's components, the upcoming-calls card, a pinned card and the rating card. */
        data class Cards(
            val radius: Double,
            val shadow: Shadow?,
            /** The design's hard offset instead of a shadow. */
            val hard: Boolean,
            /** An outline in `colors.border`. */
            val border: Boolean,
            /** A component's large image: `auto` (its own height), `square`, `landscape` or `wide`. */
            val image: String,
            /** A component's main button: `filled`, `outline`, `tonal` or `text`. */
            val button: String,
            /** Null: no fill. */
            val buttonFill: String?,
            val buttonText: String,
            val buttonBorder: String?,
        )

        /** The message field, its buttons and what is behind them. */
        data class Composer(
            /** `bar` (a strip), `pill` (the send button inside the field) or `floating` (a raised capsule over the thread). */
            val style: String,
            /** A microphone while the field is empty: it calls the agent. */
            val mic: Boolean,
            /** A paperclip, where the app takes attachments ([GraineChat.onAttach]). */
            val attach: Boolean,
            /** The send button: `circle`, `rounded` or `icon` (no fill). */
            val send: String,
            /** The field's fill. */
            val fill: String,
            val text: String,
            val placeholder: String,
            val border: String,
            /** The strip behind the field; null: none, the thread shows through. */
            val strip: String?,
            val shadow: Shadow?,
            /** The field's height and corners. */
            val height: Double,
            val radius: Double,
            /** Null: no fill. */
            val sendFill: String?,
            val sendIcon: String,
            /** The paperclip and the call button. */
            val icon: String,
        )

        data class Typing(
            /** `loader`, `dots`, `wave` or `text`. */
            val style: String,
            /** Empty: "Typing…". */
            val text: String,
            val color: String,
        )

        data class Density(
            /** `comfortable`, `compact` or `spacious`. */
            val name: String,
            /** Between rows. */
            val gap: Double,
            /** Between two messages of one group. */
            val groupGap: Double,
            /** Inside a bubble. */
            val padX: Double,
            val padY: Double,
            /** The thread's side margins. */
            val edge: Double,
        )

        /** How a new conversation opens. */
        data class Welcome(
            /** `centered`, `card`, `hero` or `minimal`. */
            val layout: String,
            /** The starters as `chips` or as a `list`. */
            val starters: String,
        )

        /** The rating asked for once the agent ends the conversation. */
        data class Rating(
            val show: Boolean,
            /** `thumbs`, `stars` or `emoji`. */
            val style: String,
            /** Empty: "How was this chat?". */
            val prompt: String,
        )

        data class Escalation(
            /** A button that calls the agent: `none`, `header` or `composer`. */
            val call: String,
        )

        /** Frosted pieces: their colour at [opacity], over the wallpaper blurred by [blur] where the platform can. */
        data class Glass(val blur: Double, val opacity: Double)
    }

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
        // ── The look (version 2: the design contract's "Call screen" → "The look"). From a
        // server — or a platform SDK — that predates it, each is what the contract derives
        // from the flat tokens above: the screen as it has always been drawn.
        /** The ready look it was resolved from; `classic` is the screen as it has always been. */
        val preset: String = "classic",
        /** `classic`, `full-bleed`, `card`, `orb` or `minimal`. */
        val layout: String = "classic",
        /** The fill: [background] / [backgroundTo]'s flat stand-ins are its nearest single colours. */
        val backdrop: Backdrop = Backdrop.flat(background, backgroundTo),
        /** `light` when [text] is light: the status bar's icons follow it. */
        val ink: String = if (ThemeBridge.luminance(text) > 0.5) "light" else "dark",
        val avatarSize: Double = 112.0,
        /** The ring the avatar wears in each of [MOMENTS]: `none`, `pulse`, `ripple` or `glow`. */
        val rings: Map<String, String> = MOMENTS.associateWith { if (it == "incoming" || it == "scheduled") "pulse" else "none" },
        val avatarRingColor: String = textMuted,
        /** `solid`, `glass` or `outline`; Answer, Decline, End and Call back are always filled. */
        val buttonStyle: String = "solid",
        /** `circle`, `squircle` or `pill`. */
        val buttonShape: String = "circle",
        /** The call's buttons; the ring's and the missed call's are 9/8 of it. */
        val buttonSize: Double = 64.0,
        /** `below` or `none`. */
        val buttonLabels: String = "below",
        /** Every button's colours, by [ACTIONS]. */
        val actions: Map<String, Action> = Action.flat(
            buttonFill, buttonIcon, buttonActiveFill, buttonActiveIcon, endFill, endIcon,
            acceptFill, acceptIcon, declineFill, declineIcon, callbackFill, callbackIcon,
        ),
        /** The agent's voice on a connected call: `none`, `bars`, `wave` or `orb`. */
        val visualizer: String = "none",
        val visualizerColor: String = text,
        val visualizerSensitivity: Double = 0.5,
        /** `plain`, `card`, `subtitle` or `bubble`. */
        val captionStyle: String = "plain",
        /** `middle` or `bottom`. */
        val captionPosition: String = "middle",
        /** Behind a card, subtitle or bubble caption: this colour at [captionFillAlpha]. */
        val captionFill: String = if (ink == "light") "#000000" else "#FFFFFF",
        val captionFillAlpha: Double = 0.0,
        val nameSize: Double = 28.0,
        /** CSS weights, 100–900. */
        val nameWeight: Int = 600,
        val statusSize: Double = 16.0,
        val statusWeight: Int = 400,
        /** A moment's own look where it differs from the screen's ([lookFor]). */
        val states: Map<String, Look> = emptyMap(),
    ) {
        /**
         * The words under the name for each moment; null where the design leaves the SDK's own —
         * a placed call's (`outgoing`) then says the connecting words, a recovering one's "Reconnecting…".
         */
        data class StatusText(
            val ringing: String,
            val connecting: String,
            val ended: String,
            val scheduled: String?,
            val missed: String?,
            val outgoing: String? = null,
            val reconnecting: String? = null,
        )

        /** One colour of the backdrop, at a position 0–1 along it. */
        data class Stop(val color: String, val at: Double)

        /** `solid`, `linear`, `radial`, `mesh` or `blurred-avatar`; [angle] as CSS reads it (180: top to bottom). */
        data class Backdrop(
            val type: String,
            val stops: List<Stop>,
            val angle: Double = 180.0,
            /** `none`, `drift` or `breathe`. */
            val motion: String = "none",
            val intensity: Double = 0.5,
            /** A blurred avatar's scrim opacity; 1 for every other type. */
            val scrim: Double = 1.0,
        ) {
            companion object {
                /** The flat fill: one colour, or a gradient top to bottom. */
                fun flat(background: String, backgroundTo: String?): Backdrop =
                    if (backgroundTo == null) Backdrop("solid", listOf(Stop(background, 0.0)))
                    else Backdrop("linear", listOf(Stop(background, 0.0), Stop(backgroundTo, 1.0)))
            }
        }

        /** One button's colours: [fillAlpha] below 1 is glass, 0 an outline in [stroke]; [activeFill] while a toggle is on. */
        data class Action(
            val fill: String,
            val fillAlpha: Double,
            val icon: String,
            val stroke: String?,
            val activeFill: String,
            val activeIcon: String,
        ) {
            companion object {
                internal fun of(fill: String, icon: String, activeFill: String = fill, activeIcon: String = icon) =
                    Action(fill, 1.0, icon, null, activeFill, activeIcon)

                /** Every button as the flat tokens describe it. */
                fun flat(
                    buttonFill: String, buttonIcon: String, buttonActiveFill: String, buttonActiveIcon: String,
                    endFill: String, endIcon: String, acceptFill: String, acceptIcon: String,
                    declineFill: String, declineIcon: String, callbackFill: String, callbackIcon: String,
                ): Map<String, Action> {
                    val neutral = of(buttonFill, buttonIcon, buttonActiveFill, buttonActiveIcon)
                    return linkedMapOf(
                        "answer" to of(acceptFill, acceptIcon), "decline" to of(declineFill, declineIcon),
                        "end" to of(endFill, endIcon), "callback" to of(callbackFill, callbackIcon),
                        "mute" to neutral, "speaker" to neutral, "chat" to neutral, "close" to neutral,
                    )
                }
            }
        }

        /** What changes from one moment to another: everything drawn in a colour. */
        data class Look(
            val backdrop: Backdrop,
            val ink: String,
            val text: String,
            val textMuted: String,
            val avatarRingColor: String,
            val actions: Map<String, Action>,
            val visualizerColor: String,
            val captionFill: String,
            val captionFillAlpha: Double,
        )

        /** The screen's own look. */
        val look: Look get() = Look(backdrop, ink, text, textMuted, avatarRingColor, actions, visualizerColor, captionFill, captionFillAlpha)

        /** What a moment of [MOMENTS] is drawn in: its own look, else the screen's. */
        fun lookFor(moment: String): Look = states[moment] ?: look

        companion object {
            /** The eight moments a call screen shows. */
            val MOMENTS: List<String> = listOf("incoming", "scheduled", "outgoing", "connecting", "connected", "reconnecting", "ended", "missed")

            /** Every button, by name (`close` is the missed call's Close). */
            val ACTIONS: List<String> = listOf("answer", "decline", "end", "callback", "mute", "speaker", "chat", "close")
        }
    }
}

/**
 * THE TOKENS AS THE PLATFORM SDKS HAND THEM OVER — the contract's token JSON
 * (Android's `ThemeTokens.toJson()`, the iOS facade's `themeJSON`), read
 * leniently: a set without its colours is no design (null); any other value
 * missing or of the wrong type takes a plain default, never a crash. The chat
 * window's pieces are read over the classic chat the same set implies
 * (ChatStyle.kt), so they are whole even from a server that sent none.
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
        val shapeTokens = GraineThemeTokens.Shape(
            radius = GraineThemeTokens.Shape.Radius(dbl(radius["sm"]), dbl(radius["md"]), dbl(radius["lg"]), dbl(radius["xl"])),
            borderWidth = dbl(shape["borderWidth"]),
            launcher = str(shape["launcher"], "circle"),
        )
        val spacingTokens = GraineThemeTokens.Spacing(dbl(spacing["unit"]), dbl(spacing["cardPadding"]), dbl(spacing["gap"]))
        val elevationTokens = GraineThemeTokens.Elevation(
            card = shadow(elevation["card"]),
            floating = shadow(elevation["floating"]),
            hardOffset = (elevation["hardOffset"] as? Map<*, *>)?.let { GraineThemeTokens.Offset(dbl(it["x"]), dbl(it["y"])) },
        )
        val bubbles = str(o["bubbles"], "filled")
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
            shape = shapeTokens,
            spacing = spacingTokens,
            elevation = elevationTokens,
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
            bubbles = bubbles,
            chrome = str(o["chrome"], "none"),
            chat = (o["chat"] as? Map<*, *>)?.let {
                chat(it, ChatStyleBase(colors, shapeTokens.radius, shapeTokens.borderWidth, spacingTokens, elevationTokens.card,
                    elevationTokens.hardOffset != null, bubbles))
            },
            call = (o["call"] as? Map<*, *>)?.let { call(it, weights) },
        )
    }

    private fun chat(o: Map<*, *>, base: ChatStyleBase): GraineThemeTokens.Chat {
        val v1 = ChatV1Words(
            background = str(o["background"]), pattern = str(o["pattern"], "none"), patternColor = str(o["patternColor"]),
            showTimestamps = o["showTimestamps"] == true,
            headerBackground = str(o["headerBackground"]), headerText = str(o["headerText"]), headerSubtext = str(o["headerSubtext"]),
            composerBackground = str(o["composerBackground"]), composerText = str(o["composerText"]),
            composerPlaceholder = str(o["composerPlaceholder"]), composerBorder = str(o["composerBorder"]),
        )
        // Chat window v2: the server's pieces, field by field over the classic chat these very tokens imply.
        val p = ChatStyles.read(o, ChatStyles.classic(v1, base))
        return GraineThemeTokens.Chat(
            title = str(o["title"]), subtitle = str(o["subtitle"]), avatar = str(o["avatar"], "logo"), placeholder = str(o["placeholder"]),
            showTimestamps = v1.showTimestamps,
            suggestions = (o["suggestions"] as? List<*>).orEmpty().filterIsInstance<String>(),
            background = v1.background, pattern = v1.pattern, patternColor = v1.patternColor,
            headerBackground = v1.headerBackground, headerText = v1.headerText, headerSubtext = v1.headerSubtext,
            composerBackground = v1.composerBackground, composerText = v1.composerText,
            composerPlaceholder = v1.composerPlaceholder, composerBorder = v1.composerBorder,
            size = GraineChatSize.entries.firstOrNull { it.wire == o["size"] },
            // Absent (an older server): on, as every SDK reads them.
            showUpcomingCalls = o["showUpcomingCalls"] != false, upcomingTitle = str(o["upcomingTitle"]),
            showHistory = o["showHistory"] != false, newConversation = o["newConversation"] != false,
            preset = p.preset, header = p.header, bubbles = p.bubbles, timestamps = p.timestamps, wallpaper = p.wallpaper,
            chips = p.chips, cards = p.cards, composer = p.composer, typing = p.typing, density = p.density,
            welcome = p.welcome, rating = p.rating, escalation = p.escalation, glass = p.glass,
        )
    }

    private fun call(o: Map<*, *>, weights: Map<*, *>): GraineThemeTokens.Call {
        val status = obj(o["statusText"])
        val flat = GraineThemeTokens.Call(
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
                status["outgoing"] as? String, status["reconnecting"] as? String,
            ),
        )
        return look(flat, o, weights)
    }

    // ── The call screen's look (version 2) ─────────────────────────────────

    private val LAYOUTS = listOf("classic", "full-bleed", "card", "orb", "minimal")
    private val LAYOUT_NAME_SIZE = mapOf("classic" to 28.0, "full-bleed" to 40.0, "card" to 24.0, "orb" to 24.0, "minimal" to 34.0)
    private val PRESETS = listOf("classic", "aurora", "midnight", "glass", "orbit", "daylight", "bloom", "studio", "minimal", "mono")
    private val BACKDROPS = listOf("solid", "linear", "radial", "mesh", "blurred-avatar")
    private val MOTIONS = listOf("none", "drift", "breathe")
    private val INKS = listOf("light", "dark")
    private val RINGS = listOf("none", "pulse", "ripple", "glow")
    private val STYLES = listOf("solid", "glass", "outline")
    private val SHAPES = listOf("circle", "squircle", "pill")
    private val LABELS = listOf("below", "none")
    private val VISUALIZERS = listOf("none", "bars", "wave", "orb")
    private val CAPTIONS = listOf("plain", "card", "subtitle", "bubble")
    private val POSITIONS = listOf("middle", "bottom")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    /** A word of [allowed] at [k]: absent, [own]; one this build does not know (a newer server's), the first listed. */
    private fun word(o: Map<*, *>, k: String, allowed: List<String>, own: String): String {
        val v = o[k] ?: return own
        return (v as? String)?.trim()?.lowercase()?.takeIf { it in allowed } ?: allowed.first()
    }

    private fun hex(v: Any?): String? = (v as? String)?.takeIf { HEX.matches(it) }?.uppercase()

    private fun backdrop(v: Any?, own: GraineThemeTokens.Call.Backdrop): GraineThemeTokens.Call.Backdrop {
        val o = v as? Map<*, *> ?: return own
        val stops = (o["stops"] as? List<*>).orEmpty().mapNotNull { s ->
            val m = s as? Map<*, *> ?: return@mapNotNull null
            val c = hex(m["color"]) ?: return@mapNotNull null
            val at = num(m["at"]) ?: return@mapNotNull null
            GraineThemeTokens.Call.Stop(c, at)
        }
        return GraineThemeTokens.Call.Backdrop(
            type = word(o, "type", BACKDROPS, own.type),
            stops = stops.ifEmpty { own.stops },
            angle = num(o["angle"]) ?: own.angle,
            motion = word(o, "motion", MOTIONS, own.motion),
            intensity = num(o["intensity"]) ?: own.intensity,
            scrim = num(o["scrim"]) ?: own.scrim,
        )
    }

    private fun actions(v: Any?, own: Map<String, GraineThemeTokens.Call.Action>): Map<String, GraineThemeTokens.Call.Action> {
        val o = v as? Map<*, *> ?: return own
        return own.mapValues { (name, d) ->
            val a = o[name] as? Map<*, *> ?: return@mapValues d
            GraineThemeTokens.Call.Action(
                fill = hex(a["fill"]) ?: d.fill,
                fillAlpha = num(a["fillAlpha"]) ?: d.fillAlpha,
                icon = hex(a["icon"]) ?: d.icon,
                // Null is an answer (no outline); a colour that is not one is the derived one.
                stroke = if (a.containsKey("stroke")) a["stroke"]?.let { hex(it) ?: d.stroke } else d.stroke,
                activeFill = hex(a["activeFill"]) ?: d.activeFill,
                activeIcon = hex(a["activeIcon"]) ?: d.activeIcon,
            )
        }
    }

    private fun lookOf(v: Any?, own: GraineThemeTokens.Call.Look): GraineThemeTokens.Call.Look {
        val o = v as? Map<*, *> ?: return own
        return GraineThemeTokens.Call.Look(
            backdrop = backdrop(o["backdrop"], own.backdrop),
            ink = word(o, "ink", INKS, own.ink),
            text = hex(o["text"]) ?: own.text,
            textMuted = hex(o["textMuted"]) ?: own.textMuted,
            avatarRingColor = hex(o["avatarRingColor"]) ?: own.avatarRingColor,
            actions = actions(o["actions"], own.actions),
            visualizerColor = hex(o["visualizerColor"]) ?: own.visualizerColor,
            captionFill = hex(o["captionFill"]) ?: own.captionFill,
            captionFillAlpha = num(o["captionFillAlpha"]) ?: own.captionFillAlpha,
        )
    }

    /** [flat] with the look [o] carries, each key read against what the contract derives for it. */
    private fun look(flat: GraineThemeTokens.Call, o: Map<*, *>, weights: Map<*, *>): GraineThemeTokens.Call {
        // The flat screen in the look's terms, with the design's own weights for the name and the status.
        val own = flat.copy(nameWeight = int(weights["title"], 600), statusWeight = int(weights["body"], 400))
        val rings = (o["rings"] as? Map<*, *>)?.let { r ->
            GraineThemeTokens.Call.MOMENTS.associateWith { m -> word(r, m, RINGS, own.rings.getValue(m)) }
        } ?: own.rings
        val layout = word(o, "layout", LAYOUTS, own.layout)
        val read = own.copy(
            preset = word(o, "preset", PRESETS, own.preset),
            layout = layout,
            backdrop = backdrop(o["backdrop"], own.backdrop),
            ink = word(o, "ink", INKS, own.ink),
            avatarSize = num(o["avatarSize"]) ?: own.avatarSize,
            rings = rings,
            avatarRingColor = hex(o["avatarRingColor"]) ?: own.avatarRingColor,
            buttonStyle = word(o, "buttonStyle", STYLES, own.buttonStyle),
            buttonShape = word(o, "buttonShape", SHAPES, own.buttonShape),
            buttonSize = num(o["buttonSize"]) ?: own.buttonSize,
            buttonLabels = word(o, "buttonLabels", LABELS, own.buttonLabels),
            actions = actions(o["actions"], own.actions),
            visualizer = word(o, "visualizer", VISUALIZERS, own.visualizer),
            visualizerColor = hex(o["visualizerColor"]) ?: own.visualizerColor,
            visualizerSensitivity = num(o["visualizerSensitivity"]) ?: own.visualizerSensitivity,
            captionStyle = word(o, "captionStyle", CAPTIONS, own.captionStyle),
            captionPosition = word(o, "captionPosition", POSITIONS, own.captionPosition),
            captionFill = hex(o["captionFill"]) ?: own.captionFill,
            captionFillAlpha = num(o["captionFillAlpha"]) ?: own.captionFillAlpha,
            nameSize = num(o["nameSize"]) ?: LAYOUT_NAME_SIZE[layout] ?: own.nameSize,
            nameWeight = num(o["nameWeight"])?.toInt() ?: own.nameWeight,
            statusSize = num(o["statusSize"]) ?: own.statusSize,
            statusWeight = num(o["statusWeight"])?.toInt() ?: own.statusWeight,
        )
        // The moments' own looks: exactly the moments sent, each read against the screen's look.
        val states = o["states"] as? Map<*, *> ?: return read
        val screen = read.look
        return read.copy(states = GraineThemeTokens.Call.MOMENTS.filter { states[it] is Map<*, *> }.associateWith { lookOf(states[it], screen) })
    }

    /** WCAG relative luminance of a #RRGGBB colour; 0 for anything else. */
    internal fun luminance(hex: String): Double {
        if (!HEX.matches(hex)) return 0.0
        val n = hex.substring(1).toInt(16)
        fun ch(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((n shr 16) and 255) + 0.7152 * ch((n shr 8) and 255) + 0.0722 * ch(n and 255)
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
