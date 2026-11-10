package ai.graine.inapp.kmp.compose

import ai.graine.inapp.kmp.Graine
import ai.graine.inapp.kmp.GraineStageCalls
import ai.graine.inapp.kmp.GraineViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import platform.UIKit.UIView
import platform.UIKit.UIViewController

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

@Composable
actual fun GraineAvatarFace(modifier: Modifier) {
    if (Graine.agents.isEmpty()) return
    // An iOS SDK from before the face has no view for it: an empty one keeps the layout.
    UIKitView(factory = { GraineViews.avatarView() ?: UIView() }, modifier = modifier, properties = accessible)
}

@Composable
actual fun GraineAvatarStage(modifier: Modifier, name: String?, role: String?, calls: GraineStageCalls, onChat: (() -> Unit)?, onClose: (() -> Unit)?) {
    if (Graine.agents.isEmpty()) return
    val chat by rememberUpdatedState(onChat)
    val close by rememberUpdatedState(onClose)
    // Without onChat the stage draws no Chat button; without onClose, no close control.
    val hasChat = onChat != null
    val hasClose = onClose != null
    UIKitViewController(
        // An iOS SDK from before the stage has none: the face alone keeps the layout.
        factory = {
            GraineViews.avatarStage(
                name, role, calls,
                if (hasChat) ({ chat?.invoke() }) else null,
                if (hasClose) ({ close?.invoke() }) else null,
            ) ?: faceOnly()
        },
        modifier = modifier,
        properties = accessible,
    )
}

private fun faceOnly(): UIViewController = UIViewController().apply { GraineViews.avatarView()?.let { setView(it) } }
