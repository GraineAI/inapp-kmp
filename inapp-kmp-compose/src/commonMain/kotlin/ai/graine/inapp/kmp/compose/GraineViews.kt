package ai.graine.inapp.kmp.compose

import ai.graine.inapp.kmp.Graine
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * THE SDK'S OWN SCREENS IN A SHARED COMPOSE UI — each composable hosts the
 * platform SDK's native view, drawn in the brand's design from the dashboard
 * (Garage → Chat window, Call screen, Assistant bar): on Android the Android
 * SDK's views, on iOS the iOS SDK's SwiftUI screens in a UIKitViewController.
 * Nothing is drawn before `Graine.configure(…)`.
 */

/**
 * The chat with the agent, as a whole screen: header, one bubble per reply,
 * the components the agent draws, the composer. It connects when it is shown
 * and hangs up a moment after it goes. A conversation that continues opens on
 * its earlier messages — under day separators, an earlier call as one "Voice
 * call" row, no starters — with "New conversation" in the header. `onClose`:
 * the header's close control. `agent`: which configured agent
 * (`Graine.configure(config, name)`); the default one when omitted.
 *
 *     composable("chat") { GraineChat(Modifier.fillMaxSize(), onClose = { nav.popBackStack() }) }
 *     composable("help") { GraineChat(Modifier.fillMaxSize(), agent = "support") }
 */
@Composable
expect fun GraineChat(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, agent: String = Graine.DEFAULT_AGENT)

/**
 * The call screen: the ring (Answer, Decline — "Scheduled call" for a call
 * Graine rang), connecting, the call with its controls, and a missed ring with
 * Call back. Show it while `Graine.calls.state.value.showsCallScreen`.
 *
 * On iOS it is drawn in place. On Android the SDK's call screen is a
 * full-screen activity that also covers the lock screen, and opens by itself
 * when a call rings or is placed; composing this brings it to the front.
 */
@Composable
expect fun GraineCallScreen(modifier: Modifier = Modifier)

/**
 * The voice bar: the agent talking inside the app, beside the screen it talks
 * about. It fills the area it is given and draws the bar at its bottom — give
 * it a band at the bottom of your layout. It mounts the voice engine itself.
 * Its chat opens at the size `Graine.chat.open(size)` asked for, else
 * `GraineConfig.chatSize`, else the dashboard's (a sheet or the whole screen
 * for large and full, over the app).
 * `name`: the agent's name beside the status (null: the dashboard's heading).
 * `onClose`: the user hung up, or closed the bar with no call. `agent`: which configured agent.
 */
@Composable
expect fun GraineAgentBar(modifier: Modifier = Modifier, name: String? = null, onClose: (() -> Unit)? = null, agent: String = Graine.DEFAULT_AGENT)

/**
 * THE AGENT'S FACE — where it is drawn. Place it in your layout (a square or
 * portrait area reads best): during a call whose agent has a face (switched on
 * in the dashboard) the face plays here and carries the agent's voice, in sync
 * with its lips. Transparent when there is none — put your own picture of the
 * agent behind it. Taking it off screen gives the voice back to the call at
 * once; with none on screen the call is voice-only. Show it while
 * `Graine.avatar.state.value.showsFace` for a face that appears with the call:
 *
 *     val face by Graine.avatar.state.collectAsState()
 *     if (face.showsFace) GraineAvatarFace(Modifier.size(220.dp, 275.dp).clip(RoundedCornerShape(24.dp)))
 *
 * Native calls need the platform's WebRTC module; the assistant bar's calls do not.
 */
@Composable
expect fun GraineAvatarFace(modifier: Modifier = Modifier)
