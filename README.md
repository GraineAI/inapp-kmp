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
            implementation("ai.graine:inapp-kmp:0.14.5")
            implementation("ai.graine:inapp-kmp-compose:0.14.5")   // the chat, call screen and voice bar for Compose Multiplatform
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
  `https://github.com/GraineAI/inapp-ios`, from `0.14.5`, added to the app
  target. Build your shared framework as a static framework
  (`binaries.framework { isStatic = true }`, the Compose Multiplatform
  default); the app's link resolves it against the package.
- **CocoaPods**: in the app's `Podfile`,
  `pod 'GraineInApp', :git => 'https://github.com/GraineAI/inapp-ios.git', :tag => '0.14.5'`
  (or `pod 'GraineInApp', '~> 0.14.5'`). With Kotlin's CocoaPods plugin, declare it
  in the shared module instead: `pod("GraineInApp") { version = "0.14.5"; linkOnly = true }`
  for a dynamic framework, or keep the `Podfile` line for a static one.

If the iOS app's link fails with `Undefined symbol '_OBJC_CLASS_$_GraineObjC'` (or another
`GraineObjC…` class), the app target does not link `GraineInApp`, or links an older one: add the
package to the app target (it then appears under General → Frameworks, Libraries, and Embedded
Content) at the same version as `ai.graine:inapp-kmp`, then clean the build folder and build again —
the same from Xcode or from Android Studio.

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
and the Android SDK it builds on beside it as `android` — 0.14.4 for this
release (`GRAINE_ANDROID_VERSION` in its `gradle.properties`). From your project's root:

```bash
git clone --branch 0.14.5 https://github.com/GraineAI/inapp-kmp ../graine-sdk/kmp
git clone --branch 0.14.4 https://github.com/GraineAI/inapp-android ../graine-sdk/android
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
Graine.identify(mapOf("user_id" to user.id, "name" to user.firstName))   // after sign-in and at every launch; memory on by default
Graine.identify(mapOf("user_id" to user.id), memory = false)             // this user: remember nothing
Graine.setMemory(true)                                                   // they changed their mind
val remembered: List<GraineMemory> = Graine.memories()                   // newest first, already redacted
val deleted: Int = Graine.forgetMemory()                                 // erase what the agent remembers
Graine.chat.newConversation()                                            // a fresh chat thread; memory stays
Graine.identify(null)                                                    // sign out
```

The agent's **Who is the user?** setting (Agent → Memory, or Embed & Widgets → Users & memory) names the trait that says
who the user is — `user_id` unless you chose `email`, `phone` or a custom
trait. Send exactly that key, before the chat or a call opens. Its value travels
only to Graine, never into the prompt; a placeholder (`"guest"`, a masked value)
is nobody, and that conversation has no memory. Other traits, such as `name`,
are prompt variables, not memories.

**How memory reaches the agent.** At the start of each chat, Talk session or
call — in-app calls in both directions too — the agent gets what it remembers
about the user: after its prompt for a prompt agent, in the flow's Briefing
(which every step reads) for a flow agent. During the conversation it looks
again only when the user refers to an earlier one ("last time", "I told you").
New facts are ready about a minute after a conversation ends. `memory = false`
(or `setMemory(false)`) keeps a user out of memory entirely, from their next
conversation; `Graine.memoryEnabled` is their choice.

`Graine.installId` is this install's id. `memories()` and `forgetMemory()` work
from a device the user is using (`device_limit` otherwise, `identity_required`
with nobody signed in). What is kept, the settings and the prompt lines that make
the agent use it: https://www.graine.ai/docs/in-app/memory

## Secure your agent: your app and your users

New in 0.14.4. Two protections, each switched on per agent in the dashboard
(Agent → Embed & Widgets → Install), each with a report-only stage before it
refuses anything, and each off until you turn it on. With neither on, nothing
changes.

- **Your apps** — which apps may use the agent's publishable key. Every request
  already says which app it is (Android: the package name and the SHA-256 of
  each signing certificate; iOS: the bundle id and Team ID): list the app under
  *Your apps* and turn on *Only my apps*. With *Require a verified app*, the app
  also proves it is yours — Play Integrity on Android, App Attest on iOS.
- **Verified users** — the identity you give with `identify` is proven by an
  **identity token**: an HMAC your server computes with the agent's user secret,
  or the ID token (JWT) of your own sign-in.

The native SDKs do the work on each platform; from shared code it is:

### Verified users: identify with a token

```kotlin
// After sign-in (and at every launch): the token your server minted for this user.
Graine.identify(mapOf("user_id" to user.id, "name" to user.firstName), identityToken = session.graineToken)

// Your sign-in renewed it: replace it without identifying anyone else (null clears it).
Graine.setIdentityToken(renewedToken)

// Let the SDK ask for a fresh one — when Graine says it expired, when a request is refused
// (that request is then sent once more), before a conversation whose JWT has less than a
// minute left, and when a call rings. May be set before configure.
Graine.identityTokenRefresher = { api.graineIdentityToken() }   // suspends; null (or a throw) keeps the current token

// Sign-out takes the token with it; another user never gets the last one's.
Graine.identify(null)
```

The refresher starts on the main thread, at most once every 30 seconds and never
twice at once — make its network call suspend rather than block. The token is
held in memory only and sent only inside the identity, beside the value it
proves: never in prompt variables, the screen context, a URL or a log. Graine
answers with a user pass the SDK keeps for an hour for that identity; another
user, or sign-out, drops both at once. A conversation already open is never cut
when a token lapses.

For an HMAC token, your **server** computes HMAC-SHA256 over the identity value
— the value of the trait the agent's *Who is the user?* setting names, as you
pass it to `identify` — keyed with the agent's user secret, and returns 64 hex
characters (base64 of the 32 bytes works too). The secret stays on your server:
never ship it in the app.

```kotlin
// On your server (Kotlin/JVM), for the signed-in user:
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun graineIdentityToken(identityValue: String, userSecret: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(userSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
    val bytes = mac.doFinal(identityValue.trim().toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
```

### Your apps: prove the app

Nothing to call: the native SDKs prove the app in the background once the
agent's settings arrive, keep the app pass Graine gives for 24 hours, and renew
it when less than six hours remain. Each host app needs one thing:

- **Android app** — add Play Integrity (the Android SDK only compiles against
  it, so an app that does not use it never carries it):

  ```kotlin
  // androidApp/build.gradle.kts
  dependencies {
      implementation("com.google.android.play:integrity:1.4.0")
  }
  ```

  Then, once: in **Play Console** → your app → **Test and release** → **App
  integrity**, link a Google Cloud project to the Play Integrity API; enable the
  Play Integrity API in that project, and give Graine the package's Play
  Integrity credentials in the dashboard when it asks for them (and the
  project's number on the agent, if you linked a project of your own). Under
  the agent's *Your apps*, add the package name and the SHA-256 fingerprints of
  its certificates (Play Console → **App integrity** → **App signing key
  certificate**, and the **Upload key certificate** for builds you sign
  yourself; `./gradlew signingReport` prints the debug key's).
- **iOS app** — in Xcode, the app target → **Signing & Capabilities** → **+
  Capability** → **App Attest**. Its environment is `development` for builds
  Xcode installs and `production` for TestFlight and the App Store (Apple uses
  production for every distributed build, whatever the file says); development
  builds are accepted only while the agent's *Allow development builds* is on.
  Under *Your apps*, add the bundle id and your Team ID.

Prove the app on first launch, so the first conversation never waits for it:

```kotlin
// After Graine.configure, from any coroutine scope of your app's:
appScope.launch {
    val verdict = Graine.prepareAppVerification()   // OFF when the agent does not ask; never throws for a failed proof
    log("app verification: ${verdict.word}")
}
```

If Apple or Google cannot be reached, the SDK keeps the pass it holds and tries
again later; an install proven in the last 7 days keeps working meanwhile.

### Test with verification on: debug tokens

Simulators, emulators, sideloaded debug builds and CI cannot prove themselves.
So a **debug build** — on Android a debuggable one (every build Android Studio
runs), on iOS one built with the Debug configuration or running on the
Simulator — sends a **debug token** instead of a proof, and never asks App
Attest or Play Integrity. The SDK makes it once per install and logs it once:

```
Graine debug token for this install: gdt_… — register it under Agent → Embed & Widgets → Install → Development → Debug tokens to use this build while "Require a verified app" is on.
```

Register it there (it expires after 30 days by default, 90 at most, and can be
revoked); the build then counts as a verified app (`DEBUG_TOKEN`). The same page
lists **test users**, accepted without an identity token — but only from an
install whose debug token is registered. A release build never makes, logs or
sends a debug token unless you opt it in:

```kotlin
Graine.debugTokenMode = GraineDebugTokenMode.ALWAYS   // before configure: an internal QA build signed for release
Graine.debugTokenMode = GraineDebugTokenMode.NEVER    // a debug build that proves itself (with "Allow development builds")
Graine.configure(config)

val token: String? = Graine.debugToken                // this install's token — show it on a debug screen to copy; null in a release build
```

### What Graine has proven

```kotlin
Graine.verification.collect { v ->               // the default agent's; each agent has its own (agent.verification)
    // v.app:  OFF · CHECKING · VERIFIED · TEST_BUILD · DEBUG_TOKEN · UNVERIFIED (v.appReason)
    // v.user: OFF · VERIFIED · TEST_USER · UNVERIFIED · INVALID (v.userReason: expired, signature, mismatch…)
    log("app ${v.app.word} ${v.appReason ?: ""}, user ${v.user.word} ${v.userReason ?: ""}")
}
```

Both read `OFF` until the agent's settings ask for them. When a protection
refuses, the SDK tries what it can first — it proves the app, or asks your
refresher, and sends the request once more — and then the `GraineException`'s
`code` says why:

| Code | What it means |
| --- | --- |
| `AppNotAllowed` | This app is not one of the agent's apps (*Only my apps*): add it under *Your apps*. Final — nothing tries again by itself |
| `AppNotVerified` | The agent requires a verified app and this install has no valid app pass: add Play Integrity or the App Attest capability, check the setup, or register this build's debug token |
| `IdentityUnverified` | The agent requires verified users and this identity is not verified: pass an identity token to `identify`, or set `identityTokenRefresher`. Memories, past sessions and booking are refused; a conversation goes ahead without memory |

On iOS these need `GraineInApp` 0.14.4 or later; link 0.14.5, the version of this library.

## Server tokens

The strongest mode: your server vouches for the signed-in user. It mints a
short-lived token for one user of one agent — `POST /v2/client-tokens` with
your secret API key: the `agent_id`, the `user_id` your app passes to
`identify()`, the `scopes` the app needs (`chat`, `call`, `memory`, `booking`,
`workflows`) and a `ttl_seconds` of 60–3600 — and the SDK sends it with every
request to Graine. Once the agent's **Require server tokens** is enforced
(Embed & Widgets), the publishable key alone opens nothing.

```kotlin
Graine.configure(GraineConfig(
    baseUrl = "https://www.graine.ai",
    publishableKey = "pk_live_…",
    tokenProvider = { backend.graineToken() },   // suspend: your server's token for the signed-in user, or null
))
```

- The SDK asks at start, again shortly before the token expires (in the
  background, with a little jitter, and after a failure again in 2, 4, 8 …
  60 seconds), when a call rings, and when Graine refuses a token — one
  question at a time. Nothing is asked while the app is in the background. A
  throw counts as null; so does no answer within 15 seconds.
- A token that expires never ends, pauses or reconnects a live chat or call:
  tokens are checked when a conversation starts. A new conversation waits at
  most 10 seconds for a fresh one.
- Refused for its token (`TokenRequired`, `TokenInvalid`, `TokenExpired`), a
  request asks for a fresh token and goes once more. `TokenScope` (another
  agent's or user's token, or a missing scope) and `KeyRevoked` (update the app
  with the agent's new key) are final. `GraineException.reason` says which part
  failed (`scope`, `user`, `agent`, `signature`, …).
- Without a `tokenProvider` nothing is sent and nothing changes. On iOS it
  needs the GraineInApp of this version.

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
Graine.track("otp_retry", mapOf("attempt" to 2))   // informs the agent, never makes it speak
Graine.reportEvent("reminder_opened", "The user came from the breakfast reminder")   // the agent may speak first
Graine.setVariables(mapOf("app_version" to "4.2.0"))
Graine.currentVariables()              // every variable the next conversation carries, merged
```

Report real values: they are masked on the device on the way out. Taps on
labelled controls reach the agent by themselves (`GraineConfig.captureTaps`).

`reportEvent(name, detail, data)` (from 0.14.4) is for something worth
speaking up about: a user who opens the chat from a reminder, and the agent
opens with its check-in question. It reaches the live chat or call — open the
chat and wait for `Graine.chat.status` to be `CONNECTED` first; with nothing live
it is dropped. The agent paces itself (20 s apart and 3 per conversation by
default); `data` may carry `cooldown_seconds`, `max_per_session`, or
`quiet = true` to record the event without speaking. On iOS it needs
GraineInApp 0.14.4. From 0.14.5 an event is at most 8 KB of JSON
(`Graine.REPORT_EVENT_MAX_BYTES`): a bigger one is never sent, and
`Graine.events` hears a `GraineEvent.Error` saying so. On iPhone (with
GraineInApp 0.14.5) a native call with the agent hears it once it is
connected; on Android a native call does not hear it yet.

The phase of a native call, in the word every Graine SDK uses, is
`Graine.calls.phase` (0.14.5): `IDLE`, `RINGING`, `CALLING`, `CONNECTING`,
`ACTIVE`, `RECONNECTING`, `ENDED` or `MISSED`.

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
reconnecting, offline, disconnected), `typing` and `upcomingCalls` (the user's booked calls,
as the SDK's window shows them: `refreshUpcomingCalls()`, `cancelUpcomingCall(id)`).
A component the agent drew (`GraineEvent.Component`) that you draw yourself is
answered with `answerWidget(id, data, summary)`. [Draw your own screens](#draw-your-own-screens)
shows each piece.

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
`setMuted()`, `setCaptionsOn()`, `clearCaptions()`, and `state` — with `status`,
`captions`, the CC switch `captionsOn`, the voice levels and the transport) is
there for your own controls ([Draw your own screens](#draw-your-own-screens)).

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
// Collect it where the button is drawn — never read once at launch (from 0.14.4).
val booking by Graine.bookingState.collectAsState()
if (booking.available) {                               // rung here, booking on, the user identified
    Button(onClick = { scope.launch {
        val call: GraineScheduledCall? = Graine.presentBooking(reason = "Talk through my order")
        if (call != null) showBooked(call)             // null: closed without booking
    } }) { Text("Book a call") }
}
```

**Collect `bookingState`; don't read `bookingAvailable` once.** Whether a user
can book depends on the agent's session, which names the trait that
identifies users and arrives after launch. Read before it has, the answer is
`false` — with the reason `not_loaded` ("not yet", never a final no) for a
user you identified; with nobody identified, the settings the last launch kept
on the device may already say why not.
`Graine.bookingState` (and each agent's `bookingState`) is a
`StateFlow<GraineBookingState>` — `available`, and `reason` when it isn't —
that is read again from the native SDK when the session arrives or changes,
after `identify` and `setMemory`, and when the session could not be loaded.
Outside Compose, `Graine.bookingState.collect { state -> … }` in a coroutine
scope of your own.

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

## Draw your own screens

Every state the SDK's own chat, voice bar, call screen, booking sheet and
components draw from is public, and every control they offer is a call you
can make — so a screen of your own behaves exactly like the SDK's. Each state
is a `StateFlow`: collect it, and your screen follows. The examples are
Compose; `DayLine`, `Bubble`, `LevelMeter` and the like stand for your own
composables. New in 0.14.0 unless noted.

### Chat

```kotlin
@Composable
fun SupportChat() {
    val turns by Graine.chat.turns.collectAsState()
    val status by Graine.chat.status.collectAsState()
    val freshBefore by Graine.chat.newConversationBefore.collectAsState()

    // Say when your screen comes and goes: the chat connects while it shows,
    // and closes 15 seconds after it goes (closeDelaySeconds), as the SDK's own does.
    DisposableEffect(Unit) {
        Graine.chat.shown(true)
        onDispose { Graine.chat.shown(false) }
    }

    Column {
        Text(when (status) {
            GraineChatStatus.CONNECTED -> "Online"
            GraineChatStatus.CONNECTING -> "Connecting…"
            GraineChatStatus.RECONNECTING -> "Reconnecting…"
            GraineChatStatus.DISCONNECTED -> "Disconnected"           // ended after a quiet spell; a message reconnects
            GraineChatStatus.FAILED -> "Couldn't connect · tap to retry" // it gave up: Graine.chat.retry()
            GraineChatStatus.OFFLINE -> "Offline · sends when you're back" // a message waits, nothing connecting it
            else -> ""                                                 // IDLE: also after the agent ended the conversation
        })
        LazyColumn {
            items(GraineChatRows.build(turns, freshBefore), key = { it.key }) { row ->
                when (row) {
                    is GraineChatRow.Day -> DayLine(GraineChatRows.dayLabel(row))
                    is GraineChatRow.NewConversation -> DayLine(row.label, spoken = row.spoken)
                    is GraineChatRow.VoiceCall -> CallRow(row.at)       // "Voice call · 6:05 PM": GraineChatRows.callLabel
                    is GraineChatRow.Turn -> Bubble(row.turn)
                }
            }
        }
        Composer(onSend = { text -> Graine.chat.send(text) })
    }
}
```

Each chat connection is a session, and so is each call: `turn.session` is the
id of the one a message was said in (the id Execution Logs shows), and
`Graine.chat.sessionId` the current one. **Sessions**, for a "Past
conversations" list of your own:

```kotlin
@Composable
fun PastConversations() {
    val sessions by Graine.chat.sessions.collectAsState()
    LazyColumn {
        items(sessions.reversed()) { s ->                     // newest first
            ListItem(
                headlineContent = { Text(if (s.channel == GraineChatChannel.VOICE) "Call" else "Chat") },
                supportingContent = { Text("${s.turns} messages · ${s.startedAt}") },
                trailingContent = { if (s.current) Text("Now") },
            )
        }
    }
}
```

Messages kept before sessions were recorded make one session with no id; with
a server that does not name sessions yet, the whole conversation is one session
with no id and `sessionId` is null.

**Past sessions** (0.14.3): the signed-in user's earlier sessions with the
agent — their past chats, talks and calls, newest first, like the dashboard's
Execution History — and each one's messages. `pastSessions` is
`{sessions, loading, hasMore, error}`; `refreshPastSessions()` loads the first
page, `loadMorePastSessions()` appends the next, and
`sessionTranscript(id)` answers one session's messages, oldest first. Only the
signed-in user's own, from one of their devices; the list clears when the
identity changes.

```kotlin
@Composable
fun History(open: (GraineSessionTranscript) -> Unit) {
    val past by Graine.chat.pastSessions.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Graine.chat.refreshPastSessions() }
    LazyColumn {
        items(past.sessions, key = { it.id }) { s ->
            ListItem(
                headlineContent = { Text(s.preview.ifEmpty { s.channel.wire }) },  // the user's first line
                supportingContent = { Text("${s.status} · ${s.turns} messages · ${s.startedAt}") },
                modifier = Modifier.clickable {
                    scope.launch { runCatching { Graine.chat.sessionTranscript(s.id) }.onSuccess(open) }
                },
            )
        }
        item {
            if (past.loading) CircularProgressIndicator()
            else if (past.hasMore) LaunchedEffect(past.sessions.size) { Graine.chat.loadMorePastSessions() }
        }
    }
    if (past.error == "identity_required") Text("Sign in to see your conversations")
}
```

`DISCONNECTED` means Graine ended the conversation itself after it had been
idle for 5 minutes, or at the session's time limit: the lines stay, and the
user's next message (or opening the chat again) connects again. A chat no
screen shows closes by itself, normally, 15 seconds after the last screen
left it (`GraineConfig.chatCloseDelaySeconds`, 0–300; `closeDelaySeconds`
changes it while the app runs) — a screen back within that time continues the
same connection, and opened later a signed-in user's conversation continues
from Graine's history, with no new welcome (0.14.3; it was 5 quiet minutes
before). When a new
conversation begins under earlier lines, `newConversationBefore` names the
message a "Disconnected · new conversation" row goes before;
`GraineChatRows.build` places it. When the agent ends the conversation,
`ended` is true and `status` is `IDLE` once its connection closes — never
`FAILED`, which means the chat gave up (offer a tap that calls `retry()`). From
0.14.5 the status says the words every Graine SDK uses (`wire`): `CONNECTED`
where it said `ONLINE` (still an alias), `FAILED` where it said `OFFLINE`
beside `failed`, and `OFFLINE` for a message waiting with nothing connecting it. A chat belongs to whoever
was identified when it connected: identify before showing the chat where you
can — if the identity changes while it is connected, the conversation closes
quietly, `turns` clears (a message typed and not yet sent stays, and goes
next), and the chat connects again as the new identity.

### Talk (the voice bar's call)

```kotlin
@Composable
fun TalkButton() {
    val talk by Graine.voice.state.collectAsState()
    val live = talk.connected || talk.connecting
    Column {
        Text(when (talk.status) {
            GraineVoiceStatus.IDLE, GraineVoiceStatus.ENDED -> "Tap to talk"
            GraineVoiceStatus.CONNECTING -> "Connecting…"
            GraineVoiceStatus.LISTENING -> "Listening"
            GraineVoiceStatus.THINKING -> "Thinking…"
            GraineVoiceStatus.SPEAKING -> "Speaking"
            GraineVoiceStatus.MUTED -> "Muted"
            GraineVoiceStatus.RECONNECTING -> "Reconnecting…"
            GraineVoiceStatus.FAILED -> talk.error ?: "Couldn't connect"
        })
        LevelMeter(talk.agentLevel)                          // 0–1, about five times a second
        talk.captions.lastOrNull()?.let { Text(it.text) }    // the line being said
        Button(onClick = { if (live) Graine.voice.end() else Graine.voice.start() }) {
            Text(if (live) "End" else "Talk")
        }
    }
}
```

`agentLevel` and `micLevel` move about five times a second while the call is
live, on both platforms (on Android from 0.14.1). `transport` says what the
call runs on (WebRTC or WebSocket) and `transportFallbackReason` why it is not
WebRTC, for your logs.

### Calls

On Android, name your own call screen so the SDK opens it — for a ring, a call
placed or answered, and a missed call — instead of its own: an Activity of your
app, declared in its manifest, that shows `CallScreen()` below and forwards its
permission answers to `Graine.onRequestPermissionsResult`. iOS ignores it: there
your screen draws over CallKit's.

```kotlin
Graine.calls.configure(GraineCallsConfig(displayName = "Acme Assistant", androidCallActivity = "com.acme.app.CallActivity"))
```

```kotlin
@Composable
fun CallScreen() {
    val call by Graine.calls.state.collectAsState()
    when (call.state) {
        GraineCallState.RINGING -> Ringing(call.callerName, onAnswer = { Graine.calls.answer() }, onDecline = { Graine.calls.decline() })
        GraineCallState.CONNECTING, GraineCallState.ACTIVE -> Column {
            Text(call.callerName ?: "Assistant")
            Visualiser(call.agentLevel)                       // 0–1, as the agent is heard
            CallTimer(call.startedAt)                         // null until the call connects
            call.connection?.let { ConnectionCard(it) }
            Row {
                Toggle("Mute", call.muted) { Graine.calls.setMuted(it) }
                Toggle("Speaker", call.speakerOn) { Graine.calls.setSpeaker(it) }
                Button(onClick = { Graine.calls.end() }) { Text("End") }
            }
        }
        GraineCallState.ENDED -> Text(if (call.endCause == "remote_bye") "The agent ended the call" else "Call ended")
        GraineCallState.IDLE -> Unit
    }
}

@Composable
fun ConnectionCard(c: GraineCallConnection) = Column {
    Text(if (c.transport == GraineCallTransport.WEBRTC) "Connected over WebRTC" else "Connected over WebSocket")
    c.tapToVoiceMs?.let { Text("The agent spoke ${it / 1000.0} s after the tap") }
    c.audio?.let { Text("Audio ${it.quality ?: "—"} · ${it.lossPct ?: 0.0}% lost · ${it.rttMs ?: "—"} ms round trip") }
    if (c.recoveries > 0) Text("Recovered ${c.recoveries} times on its own")
}
```

`connection` is the same report as the call's Connection in Execution Logs,
growing as the call goes: the transport and the wait from the tap (setup,
login, answer, first words) once it connects, the audio while it is live.
Each outcome carries the last of it, with `endCause` — who or what ended the
call (`local_end`, `remote_bye`, `media_drop`, `audio_watch`, `failed`; on iOS
also `provider_reset`):

```kotlin
scope.launch {
    Graine.calls.outcomes.collect { o -> analytics.log(o.callId, o.outcome.wire, o.endCause, o.connection?.tapToVoiceMs) }
}
```

Calls start on the loudspeaker unless a headset is connected; set
`Graine.calls.speakerByDefault = false` before a call for the earpiece first.
`setSpeaker` still moves the call in progress.

### Booking

```kotlin
when (val why = Graine.bookingUnavailableReason) {
    null -> {
        val tomorrow = Clock.System.todayIn(TimeZone.currentSystemDefault()).plus(1, DateTimeUnit.DAY)
        TimePicker(Graine.bookingSlots(tomorrow)) { at ->            // every 5 minutes the agent's rules allow
            scope.launch {
                try {
                    Graine.scheduleCall(at, reason = "Talk through my order")
                } catch (e: GraineException) {
                    showError(e.message)                               // e.g. outside the hours calls can ring
                }
            }
        }
    }
    "not_identified" -> Text("Sign in to book a call")
    else -> Unit                                                       // not_loaded, rings_not_set_up, scheduling_off
}
```

`bookingUnavailableReason` is the first part of `bookingAvailable`'s rule
that fails — `Graine.bookingState.value.reason`, which follows it as it
changes; `not_loaded` means "not yet". `bookingSlots(day)` are the booking sheet's own times. Graine checks
the agent's calling hours when the time is booked (`OutsideWindow`).

### Components

```kotlin
@Composable
fun AgentComponent() {
    val component by Graine.component.collectAsState()
    val loading by Graine.componentPending.collectAsState()
    val c = component
    when {
        c != null -> Column {
            when (c.kind) {
                "choices" -> Choices(c.data) { value, label -> Graine.chat.answerWidget(c.id, mapOf("choice" to value), summary = label) }
                else -> Text(c.fallbackText ?: c.title.orEmpty())     // a kind you do not draw
            }
            TextButton(onClick = { Graine.chat.dismissWidget(c.id) }) { Text("Close") }
        }
        loading -> CircularProgressIndicator()
    }
}
```

`component` is the one the agent drew that waits for an answer, in the chat
or on the voice bar's call; it goes back to null when it is answered, closed
or replaced, and when its conversation ends. `componentPending` is true while
one is loading, for at most 12 seconds. `dismissWidget` also tells the agent,
quietly, that the user closed it without choosing.

## Errors

Every refusal is a `GraineException`: `code` (`InvalidKey`, `OriginRefused`,
`RateLimited`, `InvalidRequest`, `IdentityRequired`, `DeviceLimit`,
`SchedulingOff`, `TooSoon`, `TooFar`, `OutsideWindow`, `TooManyPending`,
`NotFound`, `Unavailable`, `Network`, `AppNotAllowed`, `AppNotVerified`,
`IdentityUnverified` — see [Secure your agent](#secure-your-agent-your-app-and-your-users)
— the server tokens' `TokenRequired`, `TokenInvalid`, `TokenExpired`, `TokenScope`
and `KeyRevoked`, a website's `BotCheckFailed`, or `Unknown(raw)`), the server's
sentence as `message`, `retryAfterSeconds` when the server asked to wait, and
`reason` — the server's one word beside the code — when it gave one. Branch on
`code`, never on the message.

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
git clone --branch 0.14.4 https://github.com/GraineAI/inapp-android ../android
git clone --branch 0.14.5 https://github.com/GraineAI/inapp-ios ../ios
./gradlew build -x iosX64Test -x iosSimulatorArm64Test   # every target, the JVM tests and lint
opts=$(scripts/ios-test-library.sh)                       # GraineInApp from ../ios, a static library for the simulator
./gradlew :inapp-kmp:iosSimulatorArm64Test -Pgraine.iosTestLinkerOpts="$opts" -Pgraine.simulator=<device>
node scripts/check-objc-header.mjs                        # the header Kotlin binds against GraineInApp's facade
```

The iOS tests run against the iOS SDK itself, so the test binary links it;
`<device>` is a simulator's name or id (`xcrun simctl list devices available`).
`-Pgraine.kmp.ios=false` builds the Android side alone. The conversation
vectors every Graine SDK is tested against are in
`inapp-kmp/src/commonTest/contract/history.json`, the codes, verdicts and
identity-token vectors of "your app and your users" in `app-identity.json`
beside it, and the server tokens' codes and refresh schedule in
`inapp-kmp/src/commonTest/contract/client-tokens.json`.

## License

MIT — see `LICENSE`.
