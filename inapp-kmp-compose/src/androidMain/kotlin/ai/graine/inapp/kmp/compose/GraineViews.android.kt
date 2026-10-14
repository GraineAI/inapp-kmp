package ai.graine.inapp.kmp.compose

import ai.graine.inapp.GraineAgentBarView
import ai.graine.inapp.GraineCallActivity
import ai.graine.inapp.GraineCalls
import ai.graine.inapp.GraineChatView
import ai.graine.inapp.GraineInApp
import ai.graine.inapp.kmp.Graine
import ai.graine.inapp.kmp.GraineCallState
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/*
 * The Android SDK's own views, each drawing the agent it is handed: the agent's instance under the
 * name it was configured with (Graine.configure(config, name) is GraineInApp.init(application, config, name)).
 */

/** The Android SDK's instance for a configured agent; null when it is not configured. */
private fun nativeAgent(agent: String): GraineInApp? = Graine.agent(agent)?.let { GraineInApp.find(it.name) }

@Composable
actual fun GraineChat(modifier: Modifier, onClose: (() -> Unit)?, agent: String) {
    val native = nativeAgent(agent) ?: return
    val close by rememberUpdatedState(onClose)
    AndroidView(
        factory = { context -> GraineChatView(context).apply { source = native.chat; this.onClose = { close?.invoke() } } },
        modifier = modifier,
        update = { view -> if (view.source !== native.chat) view.source = native.chat },
    )
}

@Composable
actual fun GraineCallScreen(modifier: Modifier) {
    val info by Graine.calls.state.collectAsState()
    val context = LocalContext.current
    val live = info.state != GraineCallState.IDLE && info.state != GraineCallState.ENDED
    val missed = info.missedCall
    // Once per call (or missed ring): the SDK's own screen, which the SDK also opens by itself on a ring or a placed call.
    LaunchedEffect(if (live) info.callId else missed?.callId, live) {
        val intent = Intent(context, GraineCallActivity::class.java)
        when {
            live && info.callId != null -> intent.setAction(GraineCalls.ACTION_SHOW).putExtra(GraineCalls.EXTRA_CALL_ID, info.callId)
            missed != null -> intent.setAction(GraineCalls.ACTION_MISSED)
                .putExtra(GraineCalls.EXTRA_CALL_ID, missed.callId)
                .putExtra(GraineCalls.EXTRA_CALLER_NAME, missed.callerName)
                .putExtra(GraineCalls.EXTRA_REASON, missed.reason)
            else -> return@LaunchedEffect
        }
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
    Box(modifier)
}

@Composable
actual fun GraineAgentBar(modifier: Modifier, name: String?, onClose: (() -> Unit)?, agent: String) {
    val native = nativeAgent(agent) ?: return
    val activity = LocalContext.current.findActivity()
    // The voice engine lives in an activity's window; the bar's own activity is the one in front.
    LaunchedEffect(activity, native) { activity?.let { native.voice.attach(it) } }
    val close by rememberUpdatedState(onClose)
    AndroidView(
        factory = { context ->
            GraineAgentBarView(context).apply { graine = native; this.name = name; this.onClose = { close?.invoke() } }
        },
        modifier = modifier,
        update = { bar ->
            bar.graine = native
            bar.name = name
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
