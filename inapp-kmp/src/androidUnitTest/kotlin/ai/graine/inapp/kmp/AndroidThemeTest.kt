package ai.graine.inapp.kmp

import ai.graine.inapp.ResolvedDesign
import ai.graine.inapp.ThemeTokens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The brand's tokens on Android: every design in the shared contract
 * (sdk/contract/design.json), as the Android SDK resolves it, crosses into
 * [GraineThemeTokens] whole — the Android SDK's `ThemeTokens.toJson()` read
 * by the shared parser.
 */
class AndroidThemeTest {

    @Test
    fun everyDesignInTheContractCrossesFromTheAndroidSdkWhole() {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder", dir != null)
        val cases = JSONObject(File(dir!!, "design.json").readText()).getJSONArray("cases")
        var crossed = 0
        for (i in 0 until cases.length()) {
            val design = ResolvedDesign.parse(cases.getJSONObject(i).opt("design")) ?: continue
            for (native in listOf(design.light, design.dark)) {
                val t = ThemeBridge.parse(native.toJson().toString())
                assertNotNull("case $i ${native.scheme}", t)
                assertCrossed("case $i ${native.preset} ${native.scheme}", native, t!!)
                crossed++
            }
        }
        assertTrue("the contract has designs", crossed >= 2)
    }

    private fun assertCrossed(why: String, n: ThemeTokens, t: GraineThemeTokens) {
        assertEquals(why, n.v, t.v)
        assertEquals(why, n.preset, t.preset)
        assertEquals(why, n.scheme, t.scheme)
        val c = n.colors
        assertEquals(
            why,
            listOf(c.background, c.surface, c.surfaceRaised, c.text, c.textMuted, c.border, c.accent, c.onAccent, c.accentSoft,
                c.success, c.warning, c.danger, c.userBubble, c.userBubbleText, c.agentBubble, c.agentBubbleText, c.scrim),
            t.colors.let { k ->
                listOf(k.background, k.surface, k.surfaceRaised, k.text, k.textMuted, k.border, k.accent, k.onAccent, k.accentSoft,
                    k.success, k.warning, k.danger, k.userBubble, k.userBubbleText, k.agentBubble, k.agentBubbleText, k.scrim)
            },
        )
        assertEquals(why, n.font.body, t.font.body)
        assertEquals(why, n.font.heading, t.font.heading)
        assertEquals(why, listOf(n.font.scale.caption, n.font.scale.body, n.font.scale.title, n.font.scale.display),
            listOf(t.font.scale.caption, t.font.scale.body, t.font.scale.title, t.font.scale.display))
        assertEquals(why, listOf(n.font.weights.body, n.font.weights.strong, n.font.weights.title),
            listOf(t.font.weights.body, t.font.weights.strong, t.font.weights.title))
        assertEquals(why, n.font.letterSpacing.title, t.font.letterSpacing.title, 0.0)
        assertEquals(why, n.font.lineHeight.body, t.font.lineHeight.body, 0.0)
        assertEquals(why, listOf(n.shape.radius.sm, n.shape.radius.md, n.shape.radius.lg, n.shape.radius.xl),
            listOf(t.shape.radius.sm, t.shape.radius.md, t.shape.radius.lg, t.shape.radius.xl))
        assertEquals(why, n.shape.borderWidth, t.shape.borderWidth, 0.0)
        assertEquals(why, n.shape.launcher, t.shape.launcher)
        assertEquals(why, listOf(n.spacing.unit, n.spacing.cardPadding, n.spacing.gap), listOf(t.spacing.unit, t.spacing.cardPadding, t.spacing.gap))
        assertEquals(why, n.elevation.card?.let { listOf(it.y, it.blur, it.spread, it.opacity, it.color) },
            t.elevation.card?.let { listOf(it.y, it.blur, it.spread, it.opacity, it.color) })
        assertEquals(why, n.elevation.hardOffset?.let { it.x to it.y }, t.elevation.hardOffset?.let { it.x to it.y })
        assertEquals(why, listOf(n.motion.durationMs, n.motion.easing, n.motion.bounce), listOf(t.motion.durationMs, t.motion.easing, t.motion.bounce))
        assertEquals(why, n.icons.style, t.icons.style)
        assertEquals(why, n.icons.stroke, t.icons.stroke, 0.0)
        assertEquals(why, n.icons.launcher?.let { listOf(it.id, it.path, it.stroke) }, t.icons.launcher?.let { listOf(it.id, it.path, it.stroke) })
        assertEquals(why, n.glass?.let { it.blur to it.surfaceOpacity }, t.glass?.let { it.blur to it.surfaceOpacity })
        assertEquals(why, n.bubbles, t.bubbles)
        assertEquals(why, n.chrome, t.chrome)
        assertEquals("$why: the chat's tokens cross when the server sent them", n.chat != null, t.chat != null)
        n.chat?.let { nc ->
            val tc = t.chat!!
            assertEquals(why, listOf(nc.title, nc.subtitle, nc.avatar, nc.placeholder, nc.background, nc.pattern, nc.patternColor,
                nc.headerBackground, nc.headerText, nc.headerSubtext, nc.composerBackground, nc.composerText, nc.composerPlaceholder,
                nc.composerBorder, nc.upcomingTitle),
                listOf(tc.title, tc.subtitle, tc.avatar, tc.placeholder, tc.background, tc.pattern, tc.patternColor,
                    tc.headerBackground, tc.headerText, tc.headerSubtext, tc.composerBackground, tc.composerText, tc.composerPlaceholder,
                    tc.composerBorder, tc.upcomingTitle))
            assertEquals(why, nc.suggestions, tc.suggestions)
            assertEquals(why, nc.size.wire, tc.size?.wire)
            assertEquals(why, listOf(nc.showTimestamps, nc.showUpcomingCalls, nc.showHistory, nc.newConversation),
                listOf(tc.showTimestamps, tc.showUpcomingCalls, tc.showHistory, tc.newConversation))
        }
        assertEquals("$why: the call's tokens cross when the server sent them", n.call != null, t.call != null)
        n.call?.let { nk ->
            val tk = t.call!!
            assertEquals(why, listOf(nk.background, nk.backgroundTo, nk.text, nk.textMuted, nk.avatar, nk.avatarShape, nk.avatarFill,
                nk.avatarText, nk.buttonFill, nk.buttonIcon, nk.buttonActiveFill, nk.buttonActiveIcon, nk.endFill, nk.endIcon, nk.endShape,
                nk.acceptFill, nk.acceptIcon, nk.declineFill, nk.declineIcon, nk.callbackFill, nk.callbackIcon, nk.ringtone),
                listOf(tk.background, tk.backgroundTo, tk.text, tk.textMuted, tk.avatar, tk.avatarShape, tk.avatarFill,
                    tk.avatarText, tk.buttonFill, tk.buttonIcon, tk.buttonActiveFill, tk.buttonActiveIcon, tk.endFill, tk.endIcon, tk.endShape,
                    tk.acceptFill, tk.acceptIcon, tk.declineFill, tk.declineIcon, tk.callbackFill, tk.callbackIcon, tk.ringtone))
            assertEquals(why, listOf(nk.captions, nk.timer), listOf(tk.captions, tk.timer))
            assertEquals(why, nk.controls, tk.controls)
            assertEquals(why, listOf(nk.statusText.ringing, nk.statusText.connecting, nk.statusText.ended, nk.statusText.scheduled, nk.statusText.missed),
                listOf(tk.statusText.ringing, tk.statusText.connecting, tk.statusText.ended, tk.statusText.scheduled, tk.statusText.missed))
        }
    }
}
