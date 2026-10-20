package ai.graine.inapp.kmp

/**
 * The chat window v2 pieces of a [GraineThemeTokens.Chat] as the contract's
 * token JSON spells them (design.json → `design.light.chat`), every number a
 * Double — to hold what was parsed against what the contract says, field by
 * field.
 */
internal object ChatPiecesWire {
    fun of(c: GraineThemeTokens.Chat): Map<String, Any?> = mapOf(
        "preset" to c.preset,
        "header" to c.header.let { h ->
            mapOf(
                "style" to h.style, "align" to h.align, "status" to h.status, "typingStatus" to h.typingStatus,
                "contextCard" to h.contextCard, "fill" to fill(h.fill), "dot" to h.dot, "divider" to h.divider,
            )
        },
        "bubbles" to c.bubbles.let { b ->
            mapOf(
                "shape" to b.shape, "radius" to b.radius, "tightRadius" to b.tightRadius, "grouping" to b.grouping,
                "maxWidth" to b.maxWidth, "shadow" to shadow(b.shadow), "user" to side(b.user), "agent" to side(b.agent),
            )
        },
        "timestamps" to c.timestamps.let { mapOf("show" to it.show, "style" to it.style, "ticks" to it.ticks) },
        "wallpaper" to c.wallpaper.let { w ->
            mapOf(
                "style" to w.style, "color" to w.color, "colorTo" to w.colorTo, "pattern" to w.pattern,
                "patternColor" to w.patternColor, "intensity" to w.intensity, "text" to w.text, "textMuted" to w.textMuted,
            )
        },
        "chips" to c.chips.let { ch ->
            mapOf(
                "style" to ch.style, "placement" to ch.placement, "scroll" to ch.scroll, "height" to ch.height,
                "radius" to ch.radius, "fill" to ch.fill, "text" to ch.text, "border" to ch.border,
                "borderWidth" to ch.borderWidth, "shadow" to shadow(ch.shadow),
            )
        },
        "cards" to c.cards.let { cd ->
            mapOf(
                "radius" to cd.radius, "shadow" to shadow(cd.shadow), "hard" to cd.hard, "border" to cd.border,
                "image" to cd.image, "button" to cd.button, "buttonFill" to cd.buttonFill, "buttonText" to cd.buttonText,
                "buttonBorder" to cd.buttonBorder,
            )
        },
        "composer" to c.composer.let { cp ->
            mapOf(
                "style" to cp.style, "mic" to cp.mic, "attach" to cp.attach, "send" to cp.send, "fill" to cp.fill,
                "text" to cp.text, "placeholder" to cp.placeholder, "border" to cp.border, "strip" to cp.strip,
                "shadow" to shadow(cp.shadow), "height" to cp.height, "radius" to cp.radius, "sendFill" to cp.sendFill,
                "sendIcon" to cp.sendIcon, "icon" to cp.icon,
            )
        },
        "typing" to c.typing.let { mapOf("style" to it.style, "text" to it.text, "color" to it.color) },
        "density" to c.density.let { d ->
            mapOf("name" to d.name, "gap" to d.gap, "groupGap" to d.groupGap, "padX" to d.padX, "padY" to d.padY, "edge" to d.edge)
        },
        "welcome" to c.welcome.let { mapOf("layout" to it.layout, "starters" to it.starters) },
        "rating" to c.rating.let { mapOf("show" to it.show, "style" to it.style, "prompt" to it.prompt) },
        "escalation" to mapOf("call" to c.escalation.call),
        "glass" to c.glass?.let { mapOf("blur" to it.blur, "opacity" to it.opacity) },
    )

    /** The v2 groups of a contract `chat` object, as [Json] reads it. */
    fun groupsOf(chat: Map<*, *>): Map<String, Any?> = ChatStyles.KEYS.filter { chat.containsKey(it) }.associateWith { canon(chat[it]) }

    /** Every number a Double, recursively: whole numbers read as Long and the rest as Double compare as one. */
    fun canon(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to canon(x) }
        is List<*> -> v.map { canon(it) }
        is Number -> v.toDouble()
        else -> v
    }

    private fun fill(f: GraineThemeTokens.Chat.Fill) = mapOf("color" to f.color, "colorTo" to f.colorTo, "text" to f.text, "textMuted" to f.textMuted)

    private fun side(s: GraineThemeTokens.Chat.BubbleSide) = mapOf(
        "color" to s.color, "colorTo" to s.colorTo, "text" to s.text, "textMuted" to s.textMuted,
        "border" to s.border, "bare" to s.bare, "link" to s.link,
    )

    private fun shadow(s: GraineThemeTokens.Shadow?) =
        s?.let { mapOf("y" to it.y, "blur" to it.blur, "spread" to it.spread, "opacity" to it.opacity, "color" to it.color) }
}
