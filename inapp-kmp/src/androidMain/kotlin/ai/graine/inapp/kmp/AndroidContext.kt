package ai.graine.inapp.kmp

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ComponentCallbacks
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The application, and the activity in front — what the Android SDK needs and
 * shared code cannot pass: GraineInApp.init takes the application, and a call
 * or the voice bar starts from an activity in front.
 *
 * [GraineInitProvider] records both at process start, before Application.onCreate,
 * so `Graine.configure(config)` works from shared code. An app that removes
 * the provider calls `Graine.configure(context, config)` instead.
 */
internal object AndroidHost {
    @Volatile var application: Application? = null
        private set

    @Volatile private var front = WeakReference<Activity>(null)

    /** The activity the user is looking at, or null (the app is in the background, or none was ever shown). */
    val activity: Activity? get() = front.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private var watching = false

    @Synchronized
    fun attach(context: Context) {
        val app = context.applicationContext as? Application ?: (context as? Application) ?: return
        application = app
        if (watching) return
        watching = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { front = WeakReference(a) }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) { front = WeakReference(a) }
            override fun onActivityStopped(a: Activity) { if (front.get() === a) front = WeakReference(null) }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (front.get() === a) front = WeakReference(null) }
        })
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** [changed] on every configuration change of the application — the phone switching light and dark among them. */
    fun watchConfiguration(changed: () -> Unit) {
        application?.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = changed()

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        })
    }

    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * [block] on the main thread, and its answer here. From the main thread it
     * just runs; from another it waits for its turn — at most two seconds: a
     * block the main thread has not begun by then never runs, and the answer
     * is null ([MainSync]).
     */
    fun <T> onMainSync(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        return MainSync.run({ main.post(it) }, MAIN_SYNC_WAIT_MS, block)
    }

    /** The longest a caller off the main thread waits for it. */
    private const val MAIN_SYNC_WAIT_MS = 2_000L
}

/**
 * [AndroidHost.onMainSync]'s wait, on any [post]: the tests' as well as the
 * main thread's. A block the main thread has not begun when the wait is over
 * never runs — its caller was answered null, so nothing it asked for may
 * happen behind its back (a call placed after startCall said none was); one it
 * has begun is waited for to its end.
 */
internal object MainSync {
    private const val PENDING = 0
    private const val RUNNING = 1
    private const val ABANDONED = 2

    fun <T> run(post: (Runnable) -> Unit, timeoutMs: Long, block: () -> T): T? {
        val state = AtomicInteger(PENDING)
        var out: T? = null
        val done = CountDownLatch(1)
        post(Runnable {
            if (!state.compareAndSet(PENDING, RUNNING)) return@Runnable
            try { out = block() } finally { done.countDown() }
        })
        if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) return out
        if (state.compareAndSet(PENDING, ABANDONED)) return null
        done.await()
        return out
    }
}

/** Records the application (and, from then on, the activity in front) at process start. Serves no data. */
class GraineInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { AndroidHost.attach(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Android: start the SDK with an explicit context — for an app that removed
 * [GraineInitProvider] from its manifest. Otherwise `Graine.configure(config)`.
 */
fun Graine.configure(context: Context, config: GraineConfig, name: String = Graine.DEFAULT_AGENT): GraineAgent {
    AndroidHost.attach(context)
    return configure(config, name)
}

/**
 * Android: forward your activity's `onRequestPermissionsResult` when it shows
 * the voice bar or places calls from a screen of its own — the SDK asks for the
 * microphone there. True when the request was the SDK's. (`ComponentActivity`
 * apps: call it from `onRequestPermissionsResult`.)
 */
fun Graine.onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
    // Whichever agent's voice bar asked, or the app's calls (one for every agent).
    val agents = ai.graine.inapp.GraineInApp.all.values
    if (agents.isEmpty()) return false
    return agents.any { it.voice.onRequestPermissionsResult(requestCode, grantResults) } ||
        agents.first().calls.onRequestPermissionsResult(requestCode, grantResults)
}
