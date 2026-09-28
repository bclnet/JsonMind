//
//  ActorCommand.swift
//  JsonMind
//
//  The command vocabulary shared by event handlers, behaviors and minds:
//  `{ "play": "sing" }`, `{ "moveTo": "user" }`, `{ "say": "Hello" }`.
//  Anything that is not a command is a JsonUI action (`set`, scripts, host
//  actions), so an `on` handler mixes both freely. The vocabulary lives here,
//  with the minds that speak it; JsonScene executes it on a stage.
//

import Foundation
import JsonUICore

/// A target of a command or behavior: the viewer or another actor.
public enum Target: Equatable, Hashable {
    case user
    case actor(String)

    public init?(_ value: JsonValue) {
        guard let s = value.text, !s.isEmpty else { return nil }
        self = s == "user" ? .user : .actor(s)
    }

    public var value: JsonValue { switch self { case .user: return "user"; case .actor(let id): return .string(id) } }
}

/// A point in scene space, metres, +Y up. Defined here so commands can carry positions; the scene has the geometry.
public struct Point3: Equatable, Hashable {
    public var x: Double
    public var y: Double
    public var z: Double

    public init(_ x: Double, _ y: Double, _ z: Double) { self.x = x; self.y = y; self.z = z }

    /// Parses `[x, y, z]`, `[x, z]` (y = 0) or `{ "x": .., "y": .., "z": .. }`.
    public init?(_ value: JsonValue) {
        if let a = value.arrayValue {
            let n = a.compactMap(\.numberValue)
            guard n.count == a.count, !n.isEmpty else { return nil }
            switch n.count {
            case 1: self.init(n[0], 0, 0)
            case 2: self.init(n[0], 0, n[1])
            default: self.init(n[0], n[1], n[2])
            }
        } else if let o = value.objectValue {
            self.init(o["x"]?.numberValue ?? 0, o["y"]?.numberValue ?? 0, o["z"]?.numberValue ?? 0)
        } else {
            return nil
        }
    }

    public var value: JsonValue { [.number(x), .number(y), .number(z)] }
}

public enum ActorCommand: Equatable {
    case play(animation: String, loop: Bool?, speed: Double?)
    case sound(name: String, loop: Bool?)
    /// `nil` stops every sound.
    case stopSound(name: String?)
    case say(String)
    case moveTo(MoveTarget, stopAt: Double?)
    case lookAt(Target)
    case stop
    case wait(seconds: Double)
    case emit(event: String)
    /// Selects a behavior by name or description (`{ "behave": "wander" }`, `{ "behave": { "type": "approach", "target": "user" } }`);
    /// the scene interprets the value, so a mind can steer without knowing the geometry.
    case behave(JsonValue)

    public enum MoveTarget: Equatable {
        case point(Point3)
        case target(Target)
    }

    public static let verbs = ["play", "sound", "stopSound", "say", "moveTo", "lookAt", "stop", "wait", "emit", "behave"]

    public init?(_ value: JsonValue) {
        guard let o = value.objectValue, o.count >= 1 else { return nil }
        // The verb is the one key that is a known command; extra keys are its options.
        guard let verb = ActorCommand.verbs.first(where: { o[$0] != nil }), let arg = o[verb] else { return nil }
        switch verb {
        case "play":
            if let name = arg.text { self = .play(animation: name, loop: o["loop"]?.flag, speed: o["speed"]?.numberValue) }
            else if let p = arg.objectValue, let name = p["animation"]?.text ?? p["name"]?.text { self = .play(animation: name, loop: p["loop"]?.flag, speed: p["speed"]?.numberValue) }
            else { return nil }
        case "sound":
            if let name = arg.text { self = .sound(name: name, loop: o["loop"]?.flag) }
            else if let p = arg.objectValue, let name = p["name"]?.text ?? p["sound"]?.text { self = .sound(name: name, loop: p["loop"]?.flag) }
            else { return nil }
        case "stopSound":
            if let name = arg.text { self = .stopSound(name: name) } else { self = .stopSound(name: nil) }
        case "say":
            guard let text = arg.text else { return nil }
            self = .say(text)
        case "moveTo":
            if let p = Point3(arg), arg.arrayValue != nil { self = .moveTo(.point(p), stopAt: o["stopAt"]?.numberValue) }
            else if let t = Target(arg) { self = .moveTo(.target(t), stopAt: o["stopAt"]?.numberValue) }
            else if let p = arg.objectValue {
                if let t = p["target"].flatMap(Target.init) { self = .moveTo(.target(t), stopAt: p["stopAt"]?.numberValue) }
                else if let v = p["point"].flatMap(Point3.init) ?? Point3(arg) { self = .moveTo(.point(v), stopAt: p["stopAt"]?.numberValue) }
                else { return nil }
            } else { return nil }
        case "lookAt":
            guard let t = Target(arg) else { return nil }
            self = .lookAt(t)
        case "stop":
            self = .stop
        case "wait":
            guard let s = arg.numberValue else { return nil }
            self = .wait(seconds: s)
        case "emit":
            guard let e = arg.text, !e.isEmpty else { return nil }
            self = .emit(event: e)
        case "behave":
            guard arg.text != nil || arg.objectValue != nil else { return nil }
            self = .behave(arg)
        default: return nil
        }
    }

    public var verb: String {
        switch self {
        case .play: return "play"
        case .sound: return "sound"
        case .stopSound: return "stopSound"
        case .say: return "say"
        case .moveTo: return "moveTo"
        case .lookAt: return "lookAt"
        case .stop: return "stop"
        case .wait: return "wait"
        case .emit: return "emit"
        case .behave: return "behave"
        }
    }

    public var value: JsonValue {
        switch self {
        case .play(let a, let loop, let speed):
            var o: [String: JsonValue] = ["play": .string(a)]
            if let l = loop { o["loop"] = .bool(l) }
            if let s = speed { o["speed"] = .number(s) }
            return .object(o)
        case .sound(let n, let loop):
            var o: [String: JsonValue] = ["sound": .string(n)]
            if let l = loop { o["loop"] = .bool(l) }
            return .object(o)
        case .stopSound(let n): return ["stopSound": n.map(JsonValue.string) ?? true]
        case .say(let t): return ["say": .string(t)]
        case .moveTo(let target, let stopAt):
            var o: [String: JsonValue] = [:]
            switch target {
            case .point(let p): o["moveTo"] = p.value
            case .target(let t): o["moveTo"] = t.value
            }
            if let s = stopAt { o["stopAt"] = .number(s) }
            return .object(o)
        case .lookAt(let t): return ["lookAt": t.value]
        case .stop: return ["stop": true]
        case .wait(let s): return ["wait": .number(s)]
        case .emit(let e): return ["emit": .string(e)]
        case .behave(let b): return ["behave": b]
        }
    }
}

/// One step of a handler: a command for the actor or a JsonUI action.
public enum ActorStep: Equatable {
    case command(ActorCommand)
    case action(JsonAction)

    public init?(_ value: JsonValue) {
        if let c = ActorCommand(value) { self = .command(c) }
        else if let a = JsonAction(value) { self = .action(a) }
        else { return nil }
    }

    public var value: JsonValue {
        switch self { case .command(let c): return c.value; case .action(let a): return a.value }
    }
}

/// A sequence of steps: a single step or an array of them.
public struct ActorScript: Equatable {
    public var steps: [ActorStep]

    public init(_ steps: [ActorStep]) { self.steps = steps }
    public init(commands: [ActorCommand]) { self.steps = commands.map(ActorStep.command) }

    public init?(_ value: JsonValue) {
        switch value {
        case .null: return nil
        case .array(let items):
            steps = items.compactMap(ActorStep.init)
        default:
            guard let step = ActorStep(value) else { return nil }
            steps = [step]
        }
    }

    public var isEmpty: Bool { steps.isEmpty }
    public var commands: [ActorCommand] { steps.compactMap { if case .command(let c) = $0 { return c } else { return nil } } }

    public var value: JsonValue { steps.count == 1 ? steps[0].value : .array(steps.map(\.value)) }

    /// Parses an `on` object: event name → script.
    public static func handlers(_ value: JsonValue) -> [String: ActorScript] {
        (value.objectValue ?? [:]).compactMapValues(ActorScript.init)
    }

    public static func value(handlers: [String: ActorScript]) -> JsonValue { .object(handlers.mapValues(\.value)) }
}
