# Graine in-app agent for Kotlin Multiplatform

An AI agent inside your Kotlin Multiplatform app, on Android and iOS, from
shared code: it sees the screen you describe, acts through the actions you
register, chats, talks in a voice bar, and calls like WhatsApp — the agent
rings your user, or your user calls the agent. Graine's native SDKs do the
work on each platform (the same engine, the same design from the dashboard);
this library is one API over both.

Integration guide: https://www.graine.ai/docs/in-app/kotlin-multiplatform

## Install

```kotlin
// shared/build.gradle.kts
kotlin {
    androidTarget()
    iosArm64(); iosSimulatorArm64(); iosX64()
    sourceSets {
        commonMain.dependencies {
            implementation("ai.graine:inapp-kmp:0.7.0")
            implementation("ai.graine:inapp-kmp-compose:0.7.0")   // the chat, call screen and voice bar for Compose Multiplatform
        }
    }
}
```

Kotlin 2.1.20 or newer. The library depends on kotlinx-coroutines and
kotlinx-datetime only (Compose for the `-compose` artifact). In the dashboard,
add `app://` to the agent's **Allowed domains** (Embed & Widgets) — without it
every request is refused.

### Android

Android 7.0 (API 24) and up. The Android SDK (`ai.graine:inapp-android`) comes
with it, and its manifest brings the permissions, the call screen and the
call service; there is nothing to copy. The library finds your application by
itself, so `Graine.configure(config)` works from shared code.

For calls, two lines in your `FirebaseMessagingService` (the SDK does not
depend on Firebase):

```kotlin
class Messaging : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Graine.calls.onNewToken(token)
    override fun onMessageReceived(message: RemoteMessage) {
        if (Graine.calls.handlePush(message.data)) return
        // …your own messages
    }
}
```

Ask for `POST_NOTIFICATIONS` (Android 13+) during onboarding — without it the
phone cannot ring. An activity that shows the voice bar or places calls
forwards its permission answers:

```kotlin
override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    if (Graine.onRequestPermissionsResult(requestCode, grantResults)) return
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
}
```

### iOS

iOS 15 and up. The iOS side calls Graine's iOS SDK (`GraineInApp`), which your
app links — so it is never in your app twice:

- **Swift Package Manager**: in Xcode, *File → Add Package Dependencies…*,
  `https://github.com/GraineAI/inapp-ios`, from `0.7.0`, added to the app
  target. Build your shared framework as a static framework
  (`binaries.framework { isStatic = true }`, the Compose Multiplatform
  default); the app's link resolves it against the package.
- **CocoaPods**: in the app's `Podfile`,
  `pod 'GraineInApp', :git => 'https://github.com/GraineAI/inapp-ios.git', :tag => '0.7.0'`
  (or `pod 'GraineInApp', '~> 0.7'`). With Kotlin's CocoaPods plugin, declare it
  in the shared module instead: `pod("GraineInApp") { version = "0.7.0"; linkOnly = true }`
  for a dynamic framework, or keep the `Podfile` line for a static one.

`Info.plist`, because your app is the one asking for the microphone, and for
calls *Signing & Capabilities → Push Notifications* and *Background Modes →
Audio* and *Voice over IP*:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>Used to talk to the assistant.</string>
<key>UIBackgroundModes</key>
<array><string>audio</string><string>voip</string></array>
```

At every launch (iOS wakes a closed app for a VoIP push and hands it over once
this has run): `Graine.calls.enableVoIPPushes()`.

## Start

Once, at launch — `Application.onCreate` on Android, the app's `init` on iOS:

```kotlin
Graine.configure(GraineConfig(
    baseUrl = "https://www.graine.ai",
    publishableKey = "pk_live_…",          // Agent → Embed & Widgets
    appVersion = "2.4.0",
    debug = false,                         // true in debug builds: the SDK's traffic in the log
))
```

Every method may be called from any thread; the suspending ones never block
the main thread. The states are flows that always hold the latest value.

## Who the user is, and memory

```kotlin
Graine.identify(mapOf("user_id" to user.id, "name" to user.firstName))   // memory on by default
Graine.identify(mapOf("user_id" to user.id), memory = false)             // this user: remember nothing
Graine.setMemory(true)                                                   // they changed their mind
val remembered: List<GraineMemory> = Graine.memories()                   // newest first, already redacted
val deleted: Int = Graine.forgetMemory()                                 // erase what the agent remembers
Graine.identify(null)                                                    // sign out
```

The agent's **Who is the user?** setting (Agent → Memory, or Embed & Widgets → Users & memory) names the trait that says
who the user is — `user_id` unless you chose `email`, `phone` or a custom
trait. Its value travels only to Graine, never into the prompt; a placeholder
(`"guest"`, a masked value) is nobody, and that conversation has no memory.
`Graine.installId` is this install's id; `Graine.memoryEnabled` the user's
choice. `memories()` and `forgetMemory()` work from a device the user is using
(`device_limit` otherwise, `identity_required` with nobody signed in).

## What is on the screen

```kotlin
Graine.setScreen(GraineScreen(
    screen = "kyc_documents",
    title = "Upload your documents",
    journey = GraineScreen.Journey("KYC", step = 2, of = 4),
    fields = listOf(GraineField("pan", label = "PAN", value = pan, status = GraineFieldStatus.INVALID, error = "Not recognised")),
))
Graine.updateField(GraineField("otp", status = GraineFieldStatus.EMPTY))   // one field changed
Graine.removeField("otp")
Graine.clearScreen("kyc_documents")    // in the screen's teardown: only if it is still the one described
Graine.track("otp_retry", mapOf("attempt" to 2))
Graine.setVariables(mapOf("cart_total" to 1299))
```

Report real values: they are masked on the device on the way out. Taps on
labelled controls reach the agent by themselves (`GraineConfig.captureTaps`).

## Let the agent act

```kotlin
val unregister = Graine.registerAction("mark_done") { args ->
    val item = args["item"] as? String ?: return@registerAction GraineActionResult.refused("Which one?")
    list.markDone(item)
    GraineActionResult.ok("$item is done.")
}
```

The handler starts on the main thread and must answer within six seconds. It
may suspend (a network call, a confirmation) and continues on the main
thread. Arguments arrive as plain values — strings, `Long` and `Double`,
booleans, lists and maps — the same on both platforms. Declare each action on
the agent in the dashboard too.

## The chat

```kotlin
GraineChat(Modifier.fillMaxSize(), onClose = { navigator.pop() })    // the SDK's own chat screen (inapp-kmp-compose)
```

Or draw your own from `Graine.chat`: `open()` when the user is in the chat,
`send(text)`, `close()` when they leave, `retry()`, and the states `turns`
(`GraineChatTurn`: role, text, when, `delivery` for the user's messages,
`proactive` for the agent's, `live` while words arrive, `restored` and
`channel` for an earlier conversation's), `status` (idle, connecting, online,
reconnecting, offline) and `typing`.

**How big the chat opens.** The dashboard sets it per agent (Embed & Widgets →
Appearance → Chat window → Window size); the app can choose otherwise, for
every open or for one:

```kotlin
Graine.configure(GraineConfig(baseUrl, publishableKey, chatSize = GraineChatSize.LARGE))
Graine.chat.open(size = GraineChatSize.FULL)      // this open only, until close()
```

`COMPACT` is the window above the voice bar; `LARGE` a sheet over most of a
phone (a panel in the corner of a tablet); `FULL` the whole screen. The voice
bar draws its chat at that size, and moving between the call and the chat
keeps it.

**Conversations continue.** Where the agent remembers its users (Memory &
identity), a signed-in user who comes back — later today, tomorrow, on their
other phone — finds the chat where they left it, and the agent picks up from
there: the chat opens with the earlier messages, each
`restored = true`, an earlier call's as `channel = GraineChatChannel.VOICE`.
`GraineChat()` shows them under day separators, an earlier call as one "Voice
call" row, with no starters and a "New conversation" control in its header. A
chat of your own lays the turns out the same way:

```kotlin
val rows = GraineChatRows.build(turns)   // Day, VoiceCall and Turn rows, on the device's days
```

`GraineChatRows.dayLabel(row)` and `callLabel(clock)` give the words,
`offersStarters(turns)` and `offersNewConversation(turns)` what to show.
`Graine.chat.newConversation()` starts a fresh thread: the agent no longer
continues the old one, the chat clears and opens again with the welcome. What
the agent remembers about the user is untouched; `forgetMemory()` forgets the
conversation too.

## The voice bar

```kotlin
Box(Modifier.fillMaxSize()) {
    AppContent()
    GraineAgentBar(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp), onClose = { showBar = false })
}
```

The bar places and ends the call itself; `Graine.voice` (`start()`, `end()`,
`setMuted()`, `state`) is there for your own controls.

## Calls

```kotlin
Graine.calls.configure(GraineCallsConfig(displayName = "Acme Assistant", ringTimeoutSeconds = 30))
val callId: String? = Graine.calls.startCall(reason = "card_blocked", variables = mapOf("order" to "A-1043"))

// Collect from app start — outcomes are not replayed:
scope.launch { Graine.calls.outcomes.collect { o -> log(o.callId, o.outcome, o.reason, o.durationSeconds, o.hosted) } }

// Show the SDK's call screen while there is something to show:
val call by Graine.calls.state.collectAsState()
if (call.showsCallScreen) GraineCallScreen(Modifier.fillMaxSize())
```

`GraineCallOutcome.outcome` is `RINGING`, `ANSWERED`, `DECLINED`, `MISSED`
(`reason` `busy` or `no_answer`), `FAILED` (`reason` says why) or `ENDED`
(`reason` `local`, `remote` or `failed`, and `durationSeconds`). An outcome, a
ring (`Graine.calls.incoming`) and the call's state say which agent the call is
with in `agent` — the name it was configured under, `"default"` for the
default agent — when it is known. Controls:
`answer()`, `decline()`, `end()`, `setMuted()`, `setSpeaker()`, `callBack()`
after a missed ring, `dismissMissedCall()`. On Android the call screen is a
full-screen activity that also covers the lock screen and opens by itself; on
iOS the system's call screen covers the lock screen and `GraineCallScreen()`
is the in-app one.

**Graine rings your users itself** once your push credentials are uploaded
(Workspace settings → In-app calling): with the Android lines above and
`enableVoIPPushes()` on iOS, the SDK registers the device, rings the calls the
agent, the user or the dashboard booked, and reports what became of each.

## Scheduled calls

```kotlin
val rules = Graine.schedulingRules            // from the session: usersCanBook, minLeadMinutes, maxDaysAhead, …
val at = rules.earliest() + 1.hours            // earliest(now) and latest(now) bound the picker
val call: GraineScheduledCall = Graine.scheduleCall(at, reason = "Weekly check-in")
val upcoming = Graine.scheduledCalls()         // soonest first
val status: GraineCallStatus = Graine.cancelScheduledCall(call.id)   // Cancelled
```

`scheduleCall` checks the agent's rules before anything is sent —
`scheduling_off`, `too_soon`, `too_far` — and then the server's
(`outside_window`, `too_many_pending`, …). A `GraineScheduledCall` has `at`
(an `Instant`), `status` (`Scheduled`, `Ringing`, `Answered`, `Completed`,
`Declined`, `Missed`, `Failed`, `Cancelled`, `Expired`, `NoDevice`, or
`Unknown(raw)`), `source` (`Agent`, `App`, `Dashboard`, `Api`), `reason`,
`isUpcoming` and `canCancel`.

## Errors

Every refusal is a `GraineException`: `code` (`InvalidKey`, `OriginRefused`,
`RateLimited`, `InvalidRequest`, `IdentityRequired`, `DeviceLimit`,
`SchedulingOff`, `TooSoon`, `TooFar`, `OutsideWindow`, `TooManyPending`,
`NotFound`, `Unavailable`, `Network`, or `Unknown(raw)`), the server's
sentence as `message`, and `retryAfterSeconds` when the server asked to wait.
Branch on `code`, never on the message.

## Several agents

```kotlin
Graine.configure(GraineConfig(baseUrl, publishableKey = "pk_live_…"))                       // the default agent
Graine.configure(GraineConfig(baseUrl, publishableKey = "pk_live_…support"), name = "support")
val support = Graine.agent("support")!!
support.chat.open()
support.registerAction("open_ticket") { GraineActionResult.ok("Opened.") }
Graine.calls.startCall(reason = "billing", agent = "support")
GraineChat(Modifier.fillMaxSize(), agent = "support")
```

Each agent has its own session, chat (and its own kept conversation), voice
bar, design (with its chat window's size), actions, scheduling and memory. Who
the user is, the install id, the memory choice and the screen are the app's,
shared by every agent. One conversation at a time across all of them; a call's
`agent` says which one it is with. Everything on `Graine` itself is the default
agent's.

## Events

`Graine.events` (and each agent's `events`) reports what is not a state:
`SessionReady`, `Caption`, `VoiceEnded`, `Component`, `ComponentPending`,
`ChatMessage`, `ActionRequested`, `ActionCompleted`, `Friction`,
`MicrophoneDenied`, `Error`. Not replayed — collect from app start.

## Platform extras

- Android: `Graine.configure(context, config)` for an app that removed the
  library's start-up provider (`ai.graine.inapp.kmp.GraineInitProvider`), and
  everything in the Android SDK itself (`ai.graine.inapp`), which comes with it.
- iOS: `GraineViews.chat()`, `GraineViews.callScreen()`, `GraineViews.voiceBar()`
  return the SDK's screens as `UIViewController`s for an app that presents UIKit itself.

## Tests

```bash
./gradlew :inapp-kmp:testDebugUnitTest            # the shared tests and the Android side, on the JVM
./gradlew :inapp-kmp:iosSimulatorArm64Test -Pgraine.iosTestLinkerOpts="-L<dir> -lGraineInApp -L/usr/lib/swift -rpath /usr/lib/swift" -Pgraine.simulator=<device>
```

The iOS tests run against the iOS SDK itself, so the test binary links it:
build GraineInApp for the simulator as a static library (`libGraineInApp.a`
in `<dir>`) and pass the options that link it. `-Pgraine.kmp.ios=false` builds
the Android side alone. The conversation vectors every Graine SDK is tested
against are in `inapp-kmp/src/commonTest/contract/history.json`.

## License

MIT — see `LICENSE`.
