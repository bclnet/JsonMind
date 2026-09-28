# JsonMind

Personalities for JsonUI documents. A *mind* is JSON: a persona, the senses
it perceives, the commands it may emit, a token budget, the events that wake
it and canned rules for when no model is attached. JsonMind runs the turn:
budget and cooldown, prompt assembly, streaming the reply into commands,
history. Where the tokens come from is a `MindProvider`; the
`TokenXMindProvider` adapter gets them from [TokenX](https://github.com/bclnet/TokenX),
and JsonMind never sees keys or model names.

[JsonScene](https://github.com/bclnet/JsonScene) gives minds to 3D actors;
the same library can drive an assistant on a JsonUI form.

```json
{
  "persona": "You are the Singing Bush: a shrub that answers riddles with songs.",
  "tools": ["say", "play", "sound"],
  "budget": { "tokens": 20000, "perTurn": 300, "cooldown": 8 },
  "triggers": ["tap", "spoken"],
  "canned": [ { "match": "^tap", "do": [ { "say": "Ask, and the bush shall sing." }, { "play": "sing" } ] } ]
}
```

`docs/MIND.md` is the reference: the schema, the senses, the command
vocabulary, how a turn runs and how a provider plugs in.

## Libraries

| platform | package | contents |
| --- | --- | --- |
| iOS, macOS | `JsonMind` (Swift package, `ios/`) | `Mind`, `ActorCommand` / `ActorScript`, `MindPrompt` / `MindReply` / `MindReplyAssembler`, `TokenLedger`, `MindSession`, `MindProvider`, `CannedMindProvider`, `CompletionMindProvider` |
| iOS, macOS | `JsonMindTokenX` | `TokenXMindProvider`: the provider backed by a [TokenX](https://github.com/bclnet/TokenX) client |
| Android, JVM | `jsonmind`, `jsonmind-tokenx` (Kotlin, `android/`) | the same, under `com.bclnet.jsonmind` |

The core depends on JsonUI's core (`JsonValue` / `JsonElement`, actions,
fragments); only the adapter depends on TokenX.

## Using it

```swift
let mind = Mind(try JsonValue.parse(json))
let session = MindSession(actorId: "bush", mind: mind, provider: TokenXMindProvider(broker: tokenServer))
session.onCommand = { command in stage.perform(command) }               // streamed as they complete
let prompt = session.prompt(event: "tap", senses: ["userDistance": 1.2])
session.respond(to: prompt) { result in /* the full, filtered command list */ }
```

```kotlin
val session = MindSession("bush", Mind.parse(json), TokenXMindProvider(tokenServer))
session.onCommand = { stage.perform(it) }
session.respond(session.prompt("tap", senses = mapOf("userDistance" to jsonOf(1.2)))) { result -> }
```

Minds are shared as JsonUI fragments (`examples/minds/`):

```json
"mind": { "$ref": "https://raw.githubusercontent.com/bclnet/JsonMind/master/examples/minds/snoopy.json" }
```

## Building

```
swift test                                   # 18 tests, Linux or macOS
JSONUI_PATH=/path/to/JsonUI TOKENX_PATH=/path/to/TokenX swift test   # against local checkouts
git submodule update --init && cd android && ./gradlew build   # 18 JVM tests
```

## Layout

```
Package.swift            Swift manifest (root, so SwiftPM can add the package by URL)
ios/Sources/JsonMind     the library
ios/Sources/JsonMindTokenX  the TokenX adapter
ios/Tests/                tests
android/jsonmind         Kotlin/JVM module with tests
android/jsonmind-tokenx  the TokenX adapter
docs/MIND.md             the format and the provider contract
examples/minds/          mind fragments (the singing bush, Snoopy)
third_party/JsonUI       JsonUI submodule
third_party/TokenX       TokenX submodule
```

## License

MIT, see [LICENSE](LICENSE).
