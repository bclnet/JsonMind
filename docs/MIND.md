# Minds

A *mind* gives something in a JsonUI document a personality: an actor in a
[JsonScene](https://github.com/bclnet/JsonScene) scene, or an assistant on a
form. It is described in JSON, wakes on events, perceives *senses*, and
answers with *commands* from a fixed vocabulary. What it may spend is a
*token budget*; where the tokens come from is a *provider*, which the
document never sees.

```json
{
  "persona": "You are Snoopy, a beagle with a rich imagination…",
  "senses": ["userDistance", "userLooking", "timeOfDay", "state", "actors"],
  "tools": ["say", "play", "sound", "moveTo", "lookAt", "behave"],
  "budget": { "tokens": 50000, "perTurn": 400, "cooldown": 10 },
  "triggers": ["tap", "near", "timer"],
  "interval": 45,
  "canned": [
    { "match": "^near", "do": [ { "lookAt": "user" }, { "sound": "bark" } ] },
    { "match": "^timer", "do": { "behave": "wander" } }
  ]
}
```

## Schema

| key | type | meaning |
| --- | --- | --- |
| `persona` | string | who the mind is, in prose; becomes the system prompt. A bare string in place of the object is a persona with defaults. |
| `senses` | array | what the mind is told each turn (below); default all |
| `tools` | array | commands the mind may emit; default `say`, `play`, `sound`, `moveTo`, `lookAt`, `behave`, `set` |
| `budget` | `{ "tokens", "perTurn", "cooldown" }` | lifetime allowance (omit for unlimited), the cap per turn (default 400) and the minimum seconds between turns (default 5) |
| `triggers` | array | events that start a turn: `tap`, `near`, `far`, `spoken`, `timer` and any event the host fires; default `["tap", "near"]` |
| `interval` | number | seconds between `timer` turns, default 30 |
| `canned` | array | rules used when no provider is attached, when the budget is spent, or when the provider fails: `{ "match": regex, "do": commands }`. The expression is matched, case insensitively, against `"<event> <heard text>"`. |

Minds are usually shared as [fragments](https://github.com/bclnet/JsonUI/blob/master/docs/SCHEMA.md#fragments):

```json
"mind": { "$ref": "minds/snoopy.json", "budget": { "tokens": 5000 } }
```

### Senses

| sense | value |
| --- | --- |
| `userDistance` | metres from the viewer |
| `userLooking` | whether the viewer faces the actor |
| `timeOfDay` | `morning`, `afternoon`, `evening`, `night` |
| `state` | the document state snapshot |
| `actors` | positions of the other actors |
| `lastEvent` | the previous event |
| `animations`, `sounds`, `behaviors` | the actor's repertoire; always sent, in the system prompt |

Hosts may add senses of their own; a mind only receives the ones it lists.

## Commands

A command is an object with one verb key. Handlers, behaviors and minds all
produce the same commands; a scene executes them.

| command | form | does |
| --- | --- | --- |
| `play` | `{ "play": "sing" }`, `{ "play": { "animation": "sing", "loop": false, "speed": 1 } }` | plays an animation |
| `sound` | `{ "sound": "song" }`, `{ "sound": { "name": "song", "loop": true } }` | plays a sound |
| `stopSound` | `{ "stopSound": "song" }` or `{ "stopSound": true }` | stops one or every sound |
| `say` | `{ "say": "Hello!" }` | speech bubble and speech |
| `moveTo` | `{ "moveTo": [x, y, z] }`, `{ "moveTo": "user" }`, `{ "moveTo": { "target": "bush", "stopAt": 0.4 } }` | goes to a point or a target |
| `lookAt` | `{ "lookAt": "user" }` | turns towards a target |
| `behave` | `{ "behave": "wander" }`, `{ "behave": { "type": "approach", "target": "user" } }` | selects a behavior; the scene interprets it, so a mind steers without knowing the geometry |
| `stop` | `{ "stop": true }` | stops moving |
| `wait` | `{ "wait": 1.5 }` | pauses the command sequence |
| `emit` | `{ "emit": "sang" }` | fires an event handler |
| `set` | `{ "set": { "mood": "singing" } }` | sets document state (a JsonUI action) |

Anything that is not a command is a JsonUI action, so `"js: ..."` scripts and
`{ "name": "toast", "args": {...} }` host actions work in the same lists.

## Turns

1. An event the mind listens for happens. The host builds a `MindPrompt`
   from the persona, the event, any heard text, the allowed senses and the
   recent history (`MindSession.prompt`).
2. `MindSession.respond` checks the ledger: budget left, per turn cap,
   cooldown. Without budget or without a provider, the `canned` rules answer
   and nothing is charged.
3. The provider streams the reply text. `MindReplyAssembler` turns each
   completed JSON object into a command as it arrives, so `say` starts while
   the model is still writing. Commands not in `tools` are dropped.
4. When the stream ends, the reported usage (or an estimate) is charged to
   the ledger and the turn is added to the history.

The prompt has a text form for any chat model: `systemText` (who the actor
is, the allowed commands and the reply format) and `messages` (the history
and the event as JSON). The reply format is a JSON array of commands; plain
text is accepted and becomes a `say`.

## Providers and TokenX

JsonMind holds no API keys and names no models. A `MindProvider` streams
text for a prompt and reports what it used:

```swift
public protocol MindProvider {
    func respond(to prompt: MindPrompt, onText: @escaping (String) -> Void, completion: @escaping (Result<MindUsage, Error>) -> Void)
}
```

```kotlin
fun interface MindProvider {
    fun respond(prompt: MindPrompt, onText: (String) -> Unit, completion: (Result<MindUsage>) -> Unit)
}
```

The token supply is [TokenX](https://github.com/bclnet/TokenX), the library
that keeps the logistics (providers, keys, models, quotas) on the app's side
of a client/server split in the same process. TokenX knows nothing about
minds; the adapter is JsonMind's: `TokenXMindProvider` (target
`JsonMindTokenX`, module `jsonmind-tokenx`) opens one TokenX session per
mind, named after the actor, on the `character` profile, and streams the
reply text into the mind:

```swift
let provider = TokenXMindProvider(client: TokenClient(broker: tokenServer))
session.provider = provider          // or simulation.mindProvider = provider in a scene
```

JsonMind's ledger is the document's own allowance on top of whatever TokenX
grants: a scene author can cap a bush at 20 000 tokens without knowing where
they come from, and TokenX's daily cap and usage rows stay the app's business.
When TokenX refuses (no provider configured, cap reached), the canned rules
answer, as for any provider failure.

Shipped providers: `CannedMindProvider` (the rules, no tokens),
`CompletionMindProvider` (adapts a request/response function that returns a
whole reply) and `TokenXMindProvider`.
