# Graine in-app agent for Kotlin Multiplatform

An AI agent inside your Kotlin Multiplatform app, on Android and iOS, from
shared code: it sees the screen you describe, acts through the actions you
register, chats, talks in a voice bar, and calls like WhatsApp — the agent
rings your user, or your user calls the agent. Graine's native SDKs do the
work on each platform (the same engine, the same design from the dashboard);
this library is one API over both.

Integration guide: https://www.graine.ai/docs/in-app/kotlin-multiplatform ·
Every public function and type: https://www.graine.ai/docs/in-app/reference/kotlin-multiplatform

## Install

```kotlin
// shared/build.gradle.kts
kotlin {
    androidTarget()
    iosArm64(); iosSimulatorArm64(); iosX64()
    sourceSets {
        commonMain.dependencies {
            implementation("ai.graine:inapp-kmp:0.13.1")
            implementation("ai.graine:inapp-kmp-compose:0.13.1")   // the chat, call screen and voice bar for Compose Multiplatform
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
with it, and its manifest brings the permissions, the call screen, the call
service and a Firebase messaging service; there is nothing to copy. The library
finds your application by itself, so `Graine.configure(config)` works from
shared code.

For calls that ring, add Firebase to your Android app — `google-services.json`,
the Google services Gradle plugin and `com.google.firebase:firebase-messaging` —
and the SDK registers for pushes itself: its own messaging service receives the
token and Graine's calls, and it reads the current token at start. The SDK does
not depend on Firebase.

Already have your own `FirebaseMessagingService`? It outranks the SDK's and
receives every message, so hand Graine's over — two lines:

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
  `https://github.com/GraineAI/inapp-ios`, from `0.13.1`, added to the app
  target. Build your shared framework as a static framework
  (`binaries.framework { isStatic = true }`, the Compose Multiplatform
  default); the app's link resolves it against the package.
- **CocoaPods**: in the app's `Podfile`,
  `pod 'GraineInApp', :git => 'https://github.com/GraineAI/inapp-ios.git', :tag => '0.13.1'`
  (or `pod 'GraineInApp', '~> 0.13.1'`). With Kotlin's CocoaPods plugin, declare it
  in the shared module instead: `pod("GraineInApp") { version = "0.13.1"; linkOnly = true }`
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

### From source

To build your app against this repository instead of Maven Central (a version
not on Maven Central yet, or a fix not released): clone it beside your project,
and the Android SDK of the same version beside it as `android` — the Android
side builds on it. From your project's root:

```bash
git clone --branch 0.13.1 https://github.com/GraineAI/inapp-kmp ../graine-sdk/kmp
git clone --branch 0.13.1 https://github.com/GraineAI/inapp-android ../graine-sdk/android
```

then include the build in your app's `settings.gradle.kts`, in place of the
published artifacts:

```kotlin
includeBuild("../graine-sdk/kmp") {
    dependencySubstitution {
        substitute(module("ai.graine:inapp-kmp")).using(project(":inapp-kmp"))
        substitute(module("ai.graine:inapp-kmp-compose")).using(project(":inapp-kmp-compose"))
    }
}
```

Your build then compiles the library with the Gradle plugins your build
declares: use the versions this repository's `build.gradle.kts` names (Kotlin
2.1.20, Android Gradle plugin 8.13.2, Compose Multiplatform 1.8.2). Run Gradle
on JDK 17 or newer, with `ANDROID_HOME` set to the Android SDK — the included
builds do not read your app's `local.properties`. The iOS app links GraineInApp
as above.

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
    screen = "checkout_address",
    title = "Delivery address",
    journey = GraineScreen.Journey("Checkout", step = 2, of = 4),
    fields = listOf(GraineField("postal_code", label = "Postal code", value = postalCode, status = GraineFieldStatus.INVALID, error = "Not recognised")),
))
Graine.updateField(GraineField("otp", status = GraineFieldStatus.EMPTY))   // one field changed
Graine.removeField("otp")
Graine.clearScreen("checkout_address")    // in the screen's teardown: only if it is still the one described
Graine.track("otp_retry", mapOf("attempt" to 2))
Graine.setVariables(mapOf("app_version" to "4.2.0"))
Graine.currentVariables()              // every variable the next conversation carries, merged
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
`send(text)`, `close()` when they leave, `retry()`, `clear()` to empty the screen
(the conversation goes on), and the states `turns`
(`GraineChatTurn`: role, text, when, `delivery` for the user's messages,
`proactive` for the agent's, `live` while words arrive, `restored` and
`channel` for an earlier conversation's), `status` (idle, connecting, online,
reconnecting, offline), `typing` and `upcomingCalls` (the user's booked calls,
as the SDK's window shows them: `refreshUpcomingCalls()`, `cancelUpcomingCall(id)`).
A component the agent drew (`GraineEvent.Component`) that you draw yourself is
answered with `answerWidget(id, data, summary)`.

`GraineChat()` draws the chat style chosen in the dashboard (Chat window →
Style): Classic, the chat as it has always looked, or Soft, Bold, Glass,
Minimal, Crisp, Cozy, Calm, Compact or Lively — the header, bubbles, times,
wallpaper, chips, cards, composer, typing indicator, welcome and rating, each
tunable. Nothing to change in your app.

**Quick replies, the end of a conversation, and its rating.** The agent can
suggest replies for its turn — chips in the chat window, a tap sent as the
user's own message — and end the conversation, after which the window asks
"How was this chat?" where the design does (thumbs, stars or faces). For a
chat of your own: `quickReplies` (`GraineQuickReply`: `label`, `text`) and
`sendQuickReply(reply)`; `ended`; `rating` (`GraineChatRating`: `value`,
`scale`, `style`) and `rate(style, value)`. `onRated` hears each rating,
given in the window or through `rate`, on the main thread:

```kotlin
Graine.chat.onRated = { rating -> analytics.log("chat_rated", rating.value) }
```

**What the conversation is about, and attachments.** Pin a card the window
draws under its header or atop the thread, as the design says — never sent to
the agent (describe the screen with `setScreen`, as always). Each line is one
line: the title at most 60 characters, the subtitle 80, the badge 24.

```kotlin
Graine.chat.pin(GraineChatPin(title = "Your plan", subtitle = "Renews on 14 Nov", badge = "Active"))
Graine.chat.pin(null)                          // take it away; `pinned` has what is pinned
Graine.chat.onAttach = { openFilePicker() }   // the composer's paperclip, where the design shows one
```

The paperclip shows only while `onAttach` is set; the SDK sends nothing
itself — your app picks and uploads the file, then tells the agent about it
as it would about any screen.

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
`setMuted()`, `setCaptionsOn()`, `clearCaptions()`, and `state` — with `captions` and
the CC switch `captionsOn`) is there for your own controls.

## The brand's design, for UI of your own

```kotlin
val t = Graine.theme.value ?: return   // null before the session loads, or without a design
t.colors.accent                        // "#318CE7"
t.shape.radius.lg                      // 14.0
t.font.heading                         // "DM Sans", or "system" for the platform's own
```

`Graine.theme` (each agent's `theme`) is the design the SDK draws with — `GraineThemeTokens`:
`colors`, `font`, `shape`, `spacing`, `elevation`, `motion`, `icons`, `glass`, `bubbles`,
`chrome`, and the chat window's (`chat`) and the call screen's (`call`) tokens — for the
device's light or dark appearance, or the brand's fixed scheme. It changes when the device
switches.

`chat` carries every piece of the chat window as the dashboard's chat style resolved it:
`preset`, `header`, `bubbles`, `timestamps`, `wallpaper`, `chips`, `cards`, `composer`,
`typing`, `density`, `welcome`, `rating`, `escalation` and `glass`. A design from before
chat styles carries the classic chat its colours imply, so they are always there:

```kotlin
val chat = Graine.theme.value?.chat ?: return
chat.bubbles.shape                     // "rounded", "squircle", "tail" or "flat"
chat.bubbles.user.color                // "#318CE7"; null for no fill
chat.composer.style                    // "bar", "pill" or "floating"
```

## Calls

```kotlin
Graine.calls.configure(GraineCallsConfig(displayName = "Acme Assistant", ringTimeoutSeconds = 30))
val callId: String? = Graine.calls.startCall(reason = "order_help", variables = mapOf("order" to "A-1043"))

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

**What the SDK does, what your app adds.** The platform SDKs do the calls
themselves — CallKit and VoIP pushes, the ringing notification and call screen
(the Android manifest merges every entry), the screen-off call, the microphone
asked for when a call starts without it — and check your setup at `configure`,
logging each problem once with its fix. Your app adds the iOS capabilities and
Info.plist entries (Push Notifications, Background Modes *Audio* and *Voice over
IP*, `NSMicrophoneUsageDescription`), Firebase messaging on Android, and one call
to ask for the permissions at a moment you choose — never needed at launch:

```kotlin
val p = Graine.calls.requestCallPermissions()   // suspends until answered
// p.microphone · p.notifications (Android 13+) · p.fullScreen (Android 14+), each
// GRANTED · DENIED (not yet) · BLOCKED (only Settings can change it) · NOT_NEEDED (iOS: CallKit rings)
if (p.fullScreen == GraineCallPermissionStatus.BLOCKED) Graine.calls.openCallSettings(GraineCallPermission.FULL_SCREEN)

// What is still missing, in plain words — the same list the log shows:
Graine.calls.callSetupProblems.forEach { println("${it.code}: ${it.message}") }
```

`Graine.calls.callPermissions` reads them at any time; `allGranted` is true when
each is granted or not needed. The codes: iOS `microphone_usage_missing`,
`background_audio_missing`, `background_voip_missing`,
`push_entitlement_missing`, `voip_pushes_not_enabled`, `no_voip_token`; Android
`manifest_entry_missing`, `no_push_token`, `notifications_not_allowed`,
`full_screen_not_allowed`; both `microphone_blocked`, `hosted_ringing_off`. A
problem is never an exception: calls still work where they can. On iOS these
need GraineInApp of this version.

### WebRTC calls

Calls carry their audio over a WebSocket unless the app includes each
platform's WebRTC module. With it, they carry it over WebRTC: a jitter buffer,
loss concealment and a connection that holds up better on mobile data. Nothing
in shared code changes — the ring, the call screen and the outcomes stay the same.

- **Android** — the module beside the Android SDK, at the same version as the
  `ai.graine:inapp-android` you build on, in the Android app (or `androidMain`):
  `implementation("ai.graine:inapp-android-rtc:$graineAndroidVersion")`. Its
  manifest adds `ACCESS_NETWORK_STATE` (a normal permission: nothing is asked)
  and its R8 rules ship inside it.
- **iOS** — the `GraineInAppRTC` package (`https://github.com/GraineAI/inapp-ios-rtc`),
  at the same version as `GraineInApp`, its library added to the app target
  beside `GraineInApp`; or, with CocoaPods, `pod 'GraineInAppRTC'` beside
  `pod 'GraineInApp'`, the same way and at the same version. The calls find it
  by themselves.

`GraineCallsConfig(callTransport = …)` chooses: `GraineCallTransport.AUTO` (the
default — WebRTC where the module is in the app), `WEBSOCKET` or `WEBRTC`. The
agent's **In-app call audio** setting in the dashboard can switch WebRTC off for
every install at once. A call WebRTC cannot set up goes on over the WebSocket
inside the same call — no second ring, no error — and `Graine.calls.state` says
which one carried it: `transport` (`WEBRTC` or `WEBSOCKET`) and
`transportFallbackReason` (`no_answer`, `ice_timeout`, …). While a connected
call recovers a dropped connection, `reconnecting` is `true` and the SDK's call
screen says *Reconnecting…*. Over WebRTC the agent's voice arrives at telephone
bandwidth today. Size, per phone: about
12 MB installed on an iPhone, about 7.4 MB on a 64-bit Android phone. WebRTC is
BSD 3-Clause with Google's patent grant — list it in your app's open-source
licences.

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

## Booking a call from your app

When your workspace has Graine ring its users and the agent lets them book
(Calls & scheduling, in the dashboard), the native SDK draws the booking screen
itself — "Call me at…" with no screen of your own, from shared code:

```kotlin
if (Graine.bookingAvailable) {                         // rung here, booking on, the user identified
    val call: GraineScheduledCall? = Graine.presentBooking(reason = "Talk through my order")
    if (call != null) showBooked(call)                 // null: closed without booking
}
```

The sheet is drawn in the agent's design, light or dark: the day (chips from
today to the furthest day the agent allows), the time on each platform's own
time picker (only times the agent's rules allow), the phone's time zone,
"What's it about? (optional)" — 120 characters, kept with the call for the
agent, never in the push; `reason` fills it — and **Book call**. A refusal stays
in the sheet as one line ("Pick a time at least 5 minutes from now", "That time
is outside the hours we can call — pick another", "Sign in to book a call",
"Couldn't book just now — try again", …); once it is booked, the sheet says when
the agent will call. A sheet on a phone, a centred panel on a tablet. On Android
it opens over the activity in front (`presentBooking` returns null when no
activity is in front); on iOS over the key window's top view controller.

On iOS the sheet is GraineInApp's: link GraineInApp of this library's version
(with an older one, `bookingAvailable` is false and `presentBooking` fails with
`Unavailable`).

`bookingAvailable` is each agent's own (`Graine.agent("sales")?.bookingAvailable`)
and is the native SDK's rule, read as it is now: true only when Graine rings
this phone, the agent lets users book, and the user is identified with the
trait the agent's Identity setting names. The native chat windows
(`GraineChat()`, `GraineViews.chat()`) show **Book a call** in their Upcoming
calls card by the same rule.

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

On a Mac with Xcode, JDK 17 and `ANDROID_HOME` set, with the Android and iOS
SDKs of the same version cloned beside this folder as `android` and `ios`:

```bash
git clone --branch 0.13.1 https://github.com/GraineAI/inapp-android ../android
git clone --branch 0.13.1 https://github.com/GraineAI/inapp-ios ../ios
./gradlew build -x iosX64Test -x iosSimulatorArm64Test   # every target, the JVM tests and lint
opts=$(scripts/ios-test-library.sh)                       # GraineInApp from ../ios, a static library for the simulator
./gradlew :inapp-kmp:iosSimulatorArm64Test -Pgraine.iosTestLinkerOpts="$opts" -Pgraine.simulator=<device>
node scripts/check-objc-header.mjs                        # the header Kotlin binds against GraineInApp's facade
```

The iOS tests run against the iOS SDK itself, so the test binary links it;
`<device>` is a simulator's name or id (`xcrun simctl list devices available`).
`-Pgraine.kmp.ios=false` builds the Android side alone. The conversation
vectors every Graine SDK is tested against are in
`inapp-kmp/src/commonTest/contract/history.json`.

## License

MIT — see `LICENSE`.
