package ai.graine.inapp.kmp

import ai.graine.inapp.kmp.objc.GraineObjC
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.UIKit.UIViewController
import kotlin.math.pow

internal actual fun createPlatformEngine(config: GraineConfig, name: String): GraineEngine {
    val variables = Bridge.json(config.variables.takeIf { it.isNotEmpty() })
    val size = config.chatSize
    // The iOS SDK's agent under that name: made here, or the one the app already configured from Swift.
    // Without a chat size, the selector every facade answers.
    val facade = if (size == null) {
        GraineObjC.configureWithBaseUrl(
            config.baseUrl, config.publishableKey, config.appVersion, config.debug, config.endOnBackground, config.captureTaps,
            config.transport?.wire, variables, name,
        )
    } else {
        GraineObjC.configureWithBaseUrl(
            config.baseUrl, config.publishableKey, config.appVersion, config.debug, config.endOnBackground, config.captureTaps,
            config.transport?.wire, variables, name, size.wire,
        )
    } ?: throw IllegalStateException("The iOS SDK did not configure the agent \"$name\".")
    // How long the chat stays connected once no screen shows it: the app's, read at configure (a GraineInApp
    // from before 0.14.3 keeps its own).
    return IosEngine(facade).also { it.chatCloseDelaySeconds = ChatCloseDelay.clamp(config.chatCloseDelaySeconds) }
}

internal actual class GraineLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

internal actual val graineMainDispatcher: CoroutineDispatcher = Dispatchers.Main

/** The system's pow, as the iOS SDK reckons the chat's colours. */
internal actual fun platformPow(x: Double, y: Double): Double = x.pow(y)

@OptIn(BetaInteropApi::class)
internal actual fun composed(text: String): String = NSString.create(string = text).precomposedStringWithCanonicalMapping

/** iOS rings through PushKit: the SDK registers the VoIP token itself (`Graine.calls.enableVoIPPushes()`), so there is nothing to hand over. */
internal actual object PlatformPush {
    actual fun onNewToken(token: String) = Unit
    actual fun handlePush(data: Map<String, String>): Boolean = false
}

/**
 * iOS: THE SDK'S OWN SCREENS AS VIEW CONTROLLERS, drawn in the brand's design —
 * for an app that presents UIKit itself (Compose Multiplatform apps use
 * `GraineChat()`, `GraineCallScreen()` and `GraineAgentBar()` from
 * `ai.graine:inapp-kmp-compose`, which host these). After [Graine.configure].
 */
object GraineViews {
    /** An agent's chat as a screen. `onClose`: the header's close control (null: none — the navigation closes it). */
    fun chat(onClose: (() -> Unit)? = null, agent: String = Graine.DEFAULT_AGENT): UIViewController =
        facade(agent).chatViewControllerWithOnClose(onClose) as UIViewController

    /** The call screen for while the app is open: the ring, the call, and a missed ring with Call back. The app's, whichever agent calls. */
    fun callScreen(): UIViewController = facade(null).callViewController() as UIViewController

    /** An agent's voice bar, drawn at the bottom of the view it fills. `onClose`: hung up, or closed with no call. */
    fun voiceBar(name: String? = null, onClose: (() -> Unit)? = null, agent: String = Graine.DEFAULT_AGENT): UIViewController =
        facade(agent).voiceBarViewControllerWithName(name, onClose) as UIViewController

    private fun facade(agent: String?): GraineObjC {
        val found = if (agent == null) GraineObjC.current() else GraineObjC.named(Graine.normalise(agent))
        return found ?: throw IllegalStateException(
            if (agent == null || Graine.normalise(agent) == Graine.DEFAULT_AGENT) "Graine.configure(GraineConfig(…)) has not been called. Call it once, at launch."
            else "The agent \"$agent\" is not configured: call Graine.configure(config, name = \"$agent\") first."
        )
    }
}
