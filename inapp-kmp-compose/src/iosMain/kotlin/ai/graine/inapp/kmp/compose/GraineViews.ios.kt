package ai.graine.inapp.kmp.compose

import ai.graine.inapp.kmp.Graine
import ai.graine.inapp.kmp.GraineViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController

/**
 * The SDK's screens are UIKit inside Compose, and by default Compose leaves an interop view out
 * of its accessibility tree: the chat, the voice bar and the call screen were not in it at all.
 * With native accessibility on they are, which is what lets VoiceOver into them.
 */
@OptIn(ExperimentalComposeUiApi::class)
private val accessible = UIKitInteropProperties(isNativeAccessibilityEnabled = true)

@Composable
actual fun GraineChat(modifier: Modifier, onClose: (() -> Unit)?, agent: String) {
    if (Graine.agent(agent) == null) return
    val close by rememberUpdatedState(onClose)
    // Without onClose the chat draws no close control: the navigation around it closes it.
    val hasClose = onClose != null
    UIKitViewController(
        factory = { GraineViews.chat(if (hasClose) ({ close?.invoke() }) else null, agent) },
        modifier = modifier,
        properties = accessible,
    )
}

@Composable
actual fun GraineCallScreen(modifier: Modifier) {
    if (Graine.agents.isEmpty()) return
    UIKitViewController(factory = { GraineViews.callScreen() }, modifier = modifier, properties = accessible)
}

@Composable
actual fun GraineAgentBar(modifier: Modifier, name: String?, onClose: (() -> Unit)?, agent: String) {
    if (Graine.agent(agent) == null) return
    val close by rememberUpdatedState(onClose)
    UIKitViewController(
        factory = { GraineViews.voiceBar(name, { close?.invoke() }, agent) },
        modifier = modifier,
        properties = accessible,
    )
}
