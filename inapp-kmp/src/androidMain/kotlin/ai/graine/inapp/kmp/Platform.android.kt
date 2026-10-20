package ai.graine.inapp.kmp

import ai.graine.inapp.GraineCalls as NativeCalls
import ai.graine.inapp.GraineInApp
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

internal actual fun createPlatformEngine(config: GraineConfig, name: String): GraineEngine {
    val app = AndroidHost.application ?: throw IllegalStateException(
        "Graine could not find the application. Keep ai.graine.inapp.kmp.GraineInitProvider in the manifest, " +
            "or call Graine.configure(context, config) on Android."
    )
    // The Android SDK's agent under that name: made here, or the one the app already made itself.
    val native = GraineInApp.init(app, AndroidMapping.config(config), name)
    return AndroidEngine(
        port = RealAndroidPort(native),
        appName = { runCatching { app.applicationInfo.loadLabel(app.packageManager).toString() }.getOrNull() },
    )
}

internal actual class GraineLock actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(monitor) { block() }
}

/** The main looper, without kotlinx-coroutines-android: actions continue where they started. */
internal actual val graineMainDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() != Looper.getMainLooper()
    override fun dispatch(context: CoroutineContext, block: Runnable) { handler.post(block) }
}

/** StrictMath: fdlibm's pow, as the Android SDK reckons the chat's colours (and the server's JavaScript engine). */
internal actual fun platformPow(x: Double, y: Double): Double = StrictMath.pow(x, y)

internal actual fun composed(text: String): String =
    runCatching { java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC) }.getOrDefault(text)

internal actual object PlatformPush {
    actual fun onNewToken(token: String) {
        val app = AndroidHost.application ?: return
        NativeCalls.onNewToken(app, token)
    }

    actual fun handlePush(data: Map<String, String>): Boolean {
        val app = AndroidHost.application ?: return false
        return NativeCalls.handlePush(app, data)
    }
}
