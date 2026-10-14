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
