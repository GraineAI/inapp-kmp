## 0.14.1

- **Booking follows the agent's calling hours.** `bookingSlots(day)` and the SDK's booking sheet offer only times inside the agent's calling window, in the user's time zone, so a picker of your own never offers a time Graine would refuse. The window is part of the scheduling rules (`callingWindow`: start, end, days).
- **Talk levels on Android too:** `GraineVoiceInfo.agentLevel` and `micLevel` now move on both platforms.
- **The call's transcript:** `GraineCallInfo.captions`.
- **A chat whose retries are spent:** `Graine.chat.failed` (true until the user retries). `status` is unchanged.

## 0.14.0

- **The chat keeps its conversation.** Through the Android SDK 0.14.0 and, in the iOS app, `GraineInApp` 0.14.0, `GraineChat()` (and `GraineViews.chat()` on iOS) no longer ends the conversation when it leaves the screen: it stays connected until it has been idle for 5 minutes, so the chat opened again continues it, with no new welcome. When Graine ends an idle conversation the header reads *Disconnected* until the user opens the chat or writes again, and when a conversation cannot continue a line — *Disconnected · new conversation* — replaces a stacked second welcome. `Graine.chat.turns` is unchanged. Nothing to change in your app.
- **Every screen, drawn your own way.** Every state the SDK's own screens read is now public on both platforms, so your app can draw its own screens with no Graine-drawn view:
  - chat: `GraineChatStatus.DISCONNECTED`, `Graine.chat.newConversationBefore`, `Graine.chat.shown(visible)` (a chat screen of your own says when it shows, so the conversation lives as the SDK's own window's does), and `GraineChatRow.NewConversation` from `GraineChatRows.build(turns, newConversationBefore, …)`;
  - voice: `GraineVoiceInfo.status` (`GraineVoiceStatus`), `error`, `transport`, `transportFallbackReason`, `agentLevel` and `micLevel` (on iOS; 0 on Android for now);
  - calls: `GraineCallInfo.connection` (`GraineCallConnection`, the Connection card of Execution Logs, live and final), `reason`, `endReason`, `endCause`, `agentLevel` and `startedAt`, and `connection` on `GraineCallOutcome`;
  - booking: `Graine.bookingUnavailableReason` and `Graine.bookingSlots(day)` (also on `GraineAgent`);
  - components: `Graine.component` and `Graine.componentPending` (also on `GraineAgent`), `GraineComponent.data` and `fallbackText`, and `Graine.chat.dismissWidget(id)`.
  If your code matches `GraineChatStatus` or `GraineChatRow` exhaustively, add the new cases.
- **Calls start on the loudspeaker** unless a headset is connected; `Graine.calls.speakerByDefault = false` (set before configure) starts them on the earpiece on Android.
- **A call connects sooner on a slow network.** A call no longer waits on the background readiness check before it dials; the request that sets the call up gives up after 1.5 seconds and takes Graine's web route instead; and the 12-second dial budget starts once that request has answered, so a slow setup no longer eats the time a call has to connect.
- **Why a call ended:** `GraineCallInfo.endCause` and `GraineCallOutcome`'s cause (`local_end`, `remote_bye`, `media_drop`, `audio_watch`, `failed`).
- **A chat belongs to the user it was opened for.** Identifying the user after the chat opened reconnects it as that user at once, and their conversation continues; signing out or switching user closes the previous user's chat and clears its lines. Lines that had not gone out (sending or failed) are kept and sent on the next connection.
- **Chat sessions:** `Graine.chat.sessionId`, `Graine.chat.sessions` (`GraineChatSession`: id, start and end, chat or voice, message count, current) and `session` on every `GraineChatTurn` — the id Execution Logs shows, for a list of past conversations of your own.
- **Your own call screen on Android:** `GraineCallsConfig.androidCallActivity` names an activity of your app that opens for rings, calls and missed calls (ignored on iOS).

## 0.13.1

- **A WebRTC call no longer redials while the agent is picking up.** On a slow connection the half-second wait for the agent to answer could start before the server had the call, so a call about to be answered was cancelled and dialled again: a longer *Connecting…*, and at times the agent's greeting captioned twice and an extra, empty call in the call history. The wait now starts once the server confirms it has the call, and a dial that must be made again is always ended on the server first, so each call is answered by one agent. Nothing to change in your app. Through the Android SDK 0.13.1 and, in the iOS app, `GraineInApp` and `GraineInAppRTC` 0.13.1.

## 0.13.0

- **Chat window v2.** `GraineChat()` (and `GraineViews.chat()` on iOS) draws the chat style the dashboard sets (Chat window → Style): Classic — the chat as it has always looked, the default — Soft, Bold, Glass, Minimal, Crisp, Cozy, Calm, Compact or Lively, every piece tunable: the header, bubbles, times, wallpaper, chips, cards, composer, typing indicator, welcome, the end-of-chat rating and a call button. These are the Android and iOS SDKs' own windows, so nothing changes in your app; a design saved before draws exactly as it did.
- **New chat API, all optional** (each agent's chat has its own): `Graine.chat.quickReplies` (`GraineQuickReply`: `label`, `text`) and `sendQuickReply(reply)` — the replies the agent suggests for its turn, a tap the user's own message; `ended` — the agent ended the conversation; `rating` (`GraineChatRating`: `value`, `scale`, `style`), `rate(style, value)` and `onRated` — the user's end-of-chat rating, recorded on the conversation and heard once per rating; `pin(GraineChatPin(title, subtitle, imageUrl, badge))`, `pin(null)` and `pinned` — what the conversation is about, drawn under the header or atop the thread as the design says, never sent to the agent, each line one line (60, 80 and 24 characters); `onAttach` — the composer's paperclip, shown where the design has one and only while this is set (your app picks and sends the file; it may be set before `configure`).
- **The chat window's pieces, for UI of your own:** `GraineThemeTokens.Chat` gains `preset`, `header`, `bubbles`, `timestamps`, `wallpaper`, `chips`, `cards`, `composer`, `typing`, `density`, `welcome`, `rating`, `escalation` and `glass`, as the dashboard's style resolved them. A design from before chat styles carries the classic chat its own colours imply — the same values on Android and iOS. A `GraineThemeTokens.Chat` made by hand carries Graine Classic's.
- On iOS the new chat API needs the GraineInApp of this version: with an older one the new states stay empty and the new controls do nothing.

## 0.12.0

- **The call screen draws its look from the dashboard.** `GraineCallScreen()` hosts the platforms' own call screens, which draw the Garage's call screen design with no app release: ten ready looks, or your own backdrop, layout, avatar ring, voice, captions, buttons and type, and a look of its own for each moment of a call. Words and icons always read at 4.5:1 on the backdrop. A design saved before this draws exactly as before. Needs the Android and iOS SDKs of the same release.
- **`GraineThemeTokens.Call` carries the whole look** (`look`, `Backdrop`, `Stop`, `Action`, `Look`, `lookFor(moment)`; `statusText.outgoing` and `.reconnecting`), for an app that draws its own call screen.
- **A call that loses its connection says so.** While a connected call recovers a dropped connection, the platforms' call screens show the *Reconnecting* moment, and `GraineCallInfo.reconnecting` is `true` until the agent's audio is back, on Android and iOS alike — for an app that draws its own screen too.
- **The voice on the call screen is the agent's real voice level** on every transport, WebRTC and the standard connection alike.

## 0.11.0

- **Calls connect faster.** The SDK now sets each WebRTC call up with Graine's call servers directly, with a short-lived permission it fetches ahead in the background, instead of an extra hop through Graine's web service. A call that can't use it connects exactly as before. Nothing to change in your app. Through the Android SDK 0.11.0 and, in the iOS app, `GraineInApp` and `GraineInAppRTC` 0.11.0.
- **New, optional: `Graine.calls.prepareCall(reason, variables, agent)` (and each agent's `prepareCall`) — the next call set up before the tap.** Call it on a screen with a call button, each time the screen appears; the tap then only dials. Pass the same reason and variables you will pass to `startCall` — anything different and the call is set up at the tap, as before. Nothing is dialled, recorded or billed before the tap: the microphone stays off until the user taps Call. The prepared call is let go when the app goes to the background, when the signed-in user, their memory choice or their time zone changes, after 4 minutes, or when a call rings. On iOS it needs GraineInApp 0.11.0; with an older one it does nothing.
- **Every call reports how it connected**, shown in Execution Logs: WebRTC or the standard connection (and why), whether it was set up ahead, the time from the tap to the agent's first words, and the call's audio quality. Timings only — nothing personal. Nothing to change in your app.

## 0.10.3

- **A stalled WebRTC dial is redialled after half a second, not 3 seconds.** A dial the server has acknowledged is answered within a few dozen milliseconds; one still unanswered half a second after that is cancelled and made once more on a fresh connection, and a second stalled dial continues on the standard connection, with `transportFallbackReason` `no_answer`. 0.10.2's 3 seconds only delayed that fallback. Nothing to change in your app. Through the Android SDK 0.10.3 and, in the iOS app, `GraineInApp` and `GraineInAppRTC` 0.10.3.

## 0.10.2

- **Withdrawn the same day — use 0.10.3,** which redials after half a second.
- **A WebRTC call the server acknowledges but never answers is dialled again after 3 seconds.** The engine used to wait out the whole 12-second WebRTC attempt for such a call — a long *Connecting…*, then the call continued without WebRTC — because its 15-second answer ceiling could never fire inside that budget. Now, 3 s after the server's first reply with no answer, the stuck dial is cancelled and made once more on a fresh connection (what a second tap did by hand); a second stuck dial continues on the standard connection, with `transportFallbackReason` `no_answer`. Nothing to change in your app. Through the Android SDK 0.10.2 and, in the iOS app, `GraineInApp` and `GraineInAppRTC` 0.10.2.

## 0.10.1

- **On Android the SDK registers for pushes itself: no `FirebaseMessagingService` to write.** With Firebase Cloud Messaging in the Android app (`google-services.json`, the Google services plugin and `com.google.firebase:firebase-messaging`), the Android SDK's own messaging service — merged with it — receives every new token and rings Graine's call pushes, and the SDK reads the current token when it starts. Firebase stays optional. An app with its own `FirebaseMessagingService` keeps it: it outranks the SDK's, and hands Graine's pushes over with `Graine.calls.onNewToken(token)` and `Graine.calls.handlePush(message.data)`, as before. Needs the Android SDK of the same release.

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
