## 0.10.0

- **Call permissions, asked when the app chooses.** `Graine.calls.requestCallPermissions()` (suspending) asks for what the system's dialog can still ask for — the microphone, and notifications on Android 13+ — never on its own at launch; `Graine.calls.callPermissions` reads the microphone, notifications and the full-screen ring (Android 14+), each `GRANTED`, `DENIED`, `BLOCKED` or `NOT_NEEDED`, with `allGranted`; `openCallSettings(GraineCallPermission)` opens the right Settings page. Types: `GraineCallPermission`, `GraineCallPermissionStatus`, `GraineCallPermissions`. On iOS they need GraineInApp of this version.
- **`Graine.calls.callSetupProblems`**: what the app's setup still lacks for calls — `GraineCallSetupProblem(code, message)`, each with the exact fix, the same codes as every platform SDK (Info.plist entries, the push entitlement, VoIP pushes and their token on iOS; a manifest entry, the push token, notifications and the full-screen ring on Android; the microphone blocked and Graine not ringing this phone on both). A problem is never an exception.

## 0.9.0

- **WebRTC calls.** `GraineCallsConfig(callTransport = …)`: `GraineCallTransport.AUTO` (the default — WebRTC when the app includes the platform's WebRTC module), `WEBSOCKET` or `WEBRTC`, mapped to each platform SDK's own setting. `GraineCallInfo.transport` (`WEBRTC` or `WEBSOCKET`) and `transportFallbackReason` say which one carried a call, and why a call that tried WebRTC is on the WebSocket. The WebRTC module is the app's choice: on Android `ai.graine:inapp-android-rtc` beside the SDK, on iOS the `GraineInAppRTC` package or pod beside `GraineInApp`. A call WebRTC cannot set up goes on over the WebSocket inside the same call.
- On Android, `chat.status` is `RECONNECTING` exactly when the Android SDK says its chat is being made again — after a first try that failed too, as on iOS — rather than only after the chat had been online.
- **`Graine.chat.clear()`** empties the chat on screen — the screen only: the conversation goes on, a message still waiting is still sent, and nothing Graine keeps is touched (`newConversation()` starts over). On iOS it needs GraineInApp of this version; an older one ignores it.
- **`Graine.chat.answerWidget(id, data, summary)`** answers a component the agent drew (`GraineEvent.Component`) from a UI of your own, on the conversation that is live; and **`Graine.chat.upcomingCalls`** (a `StateFlow`), `refreshUpcomingCalls()` and `cancelUpcomingCall(id)` give a chat of your own the SDK window's Upcoming calls card. On iOS they need GraineInApp of this version; with an older one the calls stay empty and the rest does nothing.
- **What was said on a call, and the captions switch**: `Graine.voice.state` carries `captionsOn` (on by default) and `captions` (the last 12 turns); `Graine.voice.setCaptionsOn(on)` and `clearCaptions()`, as React Native's. The native voice bars hide their caption while it is off. On iOS it needs GraineInApp of this version; an older one keeps its captions on.
- **The brand's design tokens, for UI of your own**: `Graine.theme` (and each agent's `theme`), a `StateFlow<GraineThemeTokens?>` — the colours, type, corners, depth, motion and icons the SDK draws with, the chat window's and the call screen's tokens too, for the device's light or dark appearance (or the brand's fixed scheme), following the device when it switches; null before the session and without a design. React Native's `useGraineTheme()`, iOS `theme`, Android `themeTokens()`, Flutter `tokens`. On iOS it needs GraineInApp of this version.
- **`Graine.currentVariables()`** (and each agent's): every variable the agent will receive — the dashboard's defaults, the config's `variables`, `setVariables`, then who is signed in — merged in that order, as iOS, Android and Flutter's `currentVariables()` give it. On iOS it needs GraineInApp of this version; an older one answers empty.
- **`scheduleCall`'s own refusals say the booking sheet's words.** Booking switched off, too soon and too far — refused before anything is sent — now read "Booking a call isn't available right now", "Pick a time at least 5 minutes from now" and "Pick a time within 14 days" (no closing full stop, no "the next"), the same on every SDK and the website.

## 0.8.0

- **Booking a call, drawn by the SDK.** `Graine.presentBooking(reason)` (and `GraineAgent.presentBooking`), a
  suspending call: the native SDK's own booking sheet — a sheet on a phone, a centred panel on a tablet — in the
  agent's design, with the day, the time (each platform's own time picker), the phone's time zone, "What's it about?
  (optional)" and Book call. It returns the booked call once the sheet closes, or null when the user closed it
  without booking; refusals stay in the sheet as one line. Android shows it over the activity in front (null when
  there is none); iOS over the key window's top view controller (the iOS SDK's `presentBookingWithReason:completion:`).
- **`bookingAvailable`**, on each agent and on `Graine`: the native SDK's own rule — Graine rings this phone, the
  agent lets users book, and the user is identified. Show your own "Call me" button only then.
- The native chat windows (`GraineChat()` in `inapp-kmp-compose`, `GraineViews.chat()`) offer **Book a call** in
  their Upcoming calls card by the same rule.
- On iOS the sheet is GraineInApp's, so the app links GraineInApp of this version. With one from before the
  sheet (0.7.0), `bookingAvailable` is false and `presentBooking` fails with `Unavailable` — the app keeps running.

## 0.7.0

The first release: the Graine in-app agent for Kotlin Multiplatform, on Android and iOS, from shared code.

- **One API in `commonMain`** over Graine's Android and iOS SDKs: `Graine.configure(config)`, `identify(traits, memory)`,
  `setMemory`, `memories()`, `forgetMemory()` (how many were deleted), the screen (`setScreen`, `updateField`,
  `removeField`, `clearScreen`, `track`), `registerAction(name) { args -> result }` (the handler may suspend),
  `installId`, and `events`.
- **Scheduled calls owned by the SDK**: `scheduleCall(at, reason)` checks the agent's rules (`schedulingRules`, with
  `earliest()` and `latest()`) before anything is sent — `scheduling_off`, `too_soon`, `too_far` — then the server's;
  `scheduledCalls()`, `cancelScheduledCall(id)` (the status afterwards). `GraineScheduledCall` has `status`
  (`GraineCallStatus`, with `Unknown(raw)`), `source`, `isUpcoming` and `canCancel`.
- **Calls like WhatsApp**: `Graine.calls` — `startCall(reason, variables)`, `outcomes` (`GraineCallOutcome`: outcome,
  reason, duration, whether Graine rang it), `state` for a call screen, `incoming`, the controls, `callBack()`; Android's
  `onNewToken` / `handlePush` from your messaging service, iOS's `enableVoIPPushes()`. Each outcome, ring and the
  call's state say which agent the call is with (`agent`, the name it was configured under), when it is known.
- **The chat** (`Graine.chat`: `turns`, `status`, `typing`, `open`, `send`, `close`, `retry`, `newConversation`) and
  **the voice bar** (`Graine.voice`).
- **How big the chat opens**: `GraineChatSize` (`COMPACT`, `LARGE`, `FULL`), `GraineConfig(chatSize = …)` and
  `chat.open(size)` — the open's size, then the app's, then the dashboard's Window size. The voice bar draws its chat
  at it: a sheet over most of a phone (a panel on a tablet) for `LARGE`, the whole screen for `FULL`.
- **Conversations continue**: where the agent remembers its users, a signed-in user who comes back finds the chat
  where they left it and the agent picks up from there — `GraineChatTurn.restored` and `channel` (`CHAT`, `VOICE`). `GraineChat()` shows the earlier messages
  under day separators, an earlier call as one "Voice call" row, without starters and with "New conversation" in its
  header; `GraineChatRows` lays the turns out the same way for a chat of your own. `chat.newConversation()` starts
  a fresh thread.
- **Several agents in one app**: `Graine.configure(config, name)`, `Graine.agent(name)` — each its own session, chat,
  voice bar, actions, scheduling and memory; identity, the install id and the screen shared;
  `calls.startCall(reason, agent = …)`; `GraineChat(agent = …)` and `GraineAgentBar(agent = …)`.
- **Typed errors**: `GraineException(code, message, retryAfterSeconds)` with the server's codes and `Unknown(raw)`.
- **`ai.graine:inapp-kmp-compose`**: `GraineChat()`, `GraineCallScreen()` and `GraineAgentBar()` host the SDKs' own
  screens, drawn in the brand's design.
- iOS goes through the iOS SDK's new Objective-C facade (`GraineObjC`), which Objective-C apps can use directly; the
  app links the iOS SDK itself, with Swift Package Manager or CocoaPods. The facade takes the chat's size
  (`configure…name:chatSize:`, `openWithSize:`), starts a new conversation (`newConversationWithCompletion:`), and
  names a call's agent (`agent` on `GraineObjCCalls` and `GraineObjCCallOutcome`, and in the calls' JSON).
