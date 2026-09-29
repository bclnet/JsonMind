# JsonMind

Minds for the actors in a JsonScene: what an actor knows (persona, senses,
tools, budget, triggers), how a prompt is built, how a reply streams back as
actor commands, and where the tokens come from. Behaviors (wander, follow,
perch, flyTo, ...) stay in JsonScene; JsonMind owns thinking, not moving.

Family: JsonUI (documents, fragments) → **JsonMind** → JsonScene (actors that use
minds) → QRX (the app). TokenX supplies tokens through the adapter target only.
JsonMind is a library for anyone; it must not know about JsonScene or QRX.

`docs/MIND.md` is the reference; `examples/minds/` holds mind fragments
(`singing-bush.json`, `snoopy.json`) that scenes pull in with `$ref`.

## Layout

```
Package.swift                     deps: JsonUI master, TokenX master (JSONUI_PATH / TOKENX_PATH override with local checkouts)
ios/Sources/JsonMind              Mind, MindPrompt (system + messages, estimatedTokens), ActorCommand (play, sound, say,
                                  moveTo, lookAt, stop, wait, emit, behave...), MindReply / MindReplyAssembler (streams
                                  commands as JSON objects close), MindProvider protocol, CannedMindProvider,
                                  CompletionMindProvider, MindSession (budget, cooldown, canned fallback), TokenLedger
ios/Sources/JsonMindTokenX        TokenXMindProvider: one TokenSession per actor, profile .character, maxTokens capped at 1024
ios/Tests                         JsonMindTests, JsonMindTokenXTests (fake broker)
android/jsonmind                  Kotlin mirror
android/jsonmind-tokenx           Kotlin TokenXMindProvider
third_party/JsonUI, third_party/TokenX   submodules used by the Gradle composite build
```

## Build and test

```
swift test                                   # 18 tests on Linux; TOKENX_PATH=../tokenx JSONUI_PATH=../JsonUI to use local checkouts
cd android && ./gradlew build                # includes JsonUI and TokenX from third_party when built standalone
git submodule update --init                  # first, for the Android build
```

## Conventions

- `MindProvider.respond(to:onText:completion:)` streams text; the session parses commands
  from the stream, so providers never interpret replies.
- A mind with no provider, an exhausted budget, or a provider error falls back to its
  canned rules; nothing in a scene should break when there is no AI.
- Minds are fragments: keep example minds loadable by `$ref` from a scene document.
- Keep Swift and Kotlin in step, with tests on both sides.

## Gotchas

- `android/settings.gradle.kts` includes JsonUI and TokenX only when `gradle.parent == null`,
  because a parent composite build (JsonScene, QRX) already includes them under those names.
  Two included builds cannot share a name; nested libraries must keep this guard.
- The TokenX submodule can lag TokenX master; bump it when the provider needs new API.
- Local `master` in a checkout may be stale; the work in this session was pushed from a
  `claude/...` branch straight to origin/master.
